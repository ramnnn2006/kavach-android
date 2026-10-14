package app.kavach.android.core.data.repo

import app.kavach.android.core.data.model.Notice
import app.kavach.android.core.data.remote.Db
import app.kavach.android.core.data.remote.ErrorCode
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.LiveQueries
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.table
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Notices (society announcements). */
@Singleton
class NoticeRepository @Inject constructor(
    private val db: Db,
    private val live: LiveQueries,
) {
    /** Unexpired notices, pinned first, newest first. */
    suspend fun notices(includeExpired: Boolean = false): List<Notice> = db.call {
        val now = Instant.now().toString()
        from("notices").select(Columns.raw(NOTICE_COLUMNS)) {
            if (!includeExpired) filter {
                or {
                    exact("expires_at", null)
                    gt("expires_at", now)
                }
            }
            order("pinned", Order.DESCENDING)
            order("created_at", Order.DESCENDING)
        }.decodeList<Notice>()
    }

    fun listenNotices(includeExpired: Boolean = false): Flow<Loadable<List<Notice>>> = live.live("notices:$includeExpired", table("notices")) { notices(includeExpired) }

    /** Admin. Author name and society come from my profile. */
    suspend fun post(title: String, body: String, pinned: Boolean = false, expiresAt: Instant? = null): String {
        val me = db.myId()
        val author = db.call {
            from("profiles").select(Columns.raw("society_id, full_name")) { filter { eq("id", me) } }
                .decodeSingle<AuthorRow>()
        }
        val row = buildJsonObject {
            put("society_id", author.societyId)
            put("author_id", me)
            put("author_name", author.fullName)
            put("title", title.trim())
            put("body", body.trim())
            put("pinned", pinned)
            put("expires_at", expiresAt?.toString())
        }
        return db.call {
            from("notices").insert(row) { select(Columns.raw("id")) }.decodeSingle<IdRow>().id
        }
    }

    suspend fun update(id: String, title: String? = null, body: String? = null, pinned: Boolean? = null, expiresAt: Instant? = null, clearExpiry: Boolean = false) {
        val patch = buildJsonObject {
            title?.let { put("title", it.trim()) }
            body?.let { put("body", it.trim()) }
            pinned?.let { put("pinned", it) }
            if (clearExpiry) put("expires_at", null as String?) else expiresAt?.let { put("expires_at", it.toString()) }
        }
        // Like updateNotice (.select('id').single()): a notice deleted meanwhile is NotFound
        val updated = db.call {
            from("notices").update(patch) {
                select(Columns.raw("id"))
                filter { eq("id", id) }
            }.decodeList<IdRow>()
        }
        if (updated.isEmpty()) throw KavachException(ErrorCode.NotFound)
    }

    suspend fun delete(id: String) {
        db.call { from("notices").delete { filter { eq("id", id) } } }
    }
}

@Serializable
internal data class IdRow(val id: String)

@Serializable
internal data class AuthorRow(
    @SerialName("society_id") val societyId: String? = null,
    @SerialName("full_name") val fullName: String = "",
)
