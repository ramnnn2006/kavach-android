package app.kavach.android.core.data.repo

import app.kavach.android.core.data.model.Contact
import app.kavach.android.core.data.model.ContactKind
import app.kavach.android.core.data.remote.Db
import app.kavach.android.core.data.remote.LiveQueries
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.table
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Society desk numbers and emergency contacts. */
@Singleton
class ContactRepository @Inject constructor(
    private val db: Db,
    private val live: LiveQueries,
) {
    suspend fun contacts(): List<Contact> = db.call {
        from("contacts").select(Columns.raw(CONTACT_COLUMNS)) {
            order("kind", Order.ASCENDING)
            order("sort_order", Order.ASCENDING)
        }.decodeList<Contact>()
    }

    fun listenContacts(): Flow<Loadable<List<Contact>>> = live.live("contacts", table("contacts")) { contacts() }

    /** Admin only (RLS). */
    suspend fun add(
        societyId: String,
        name: String,
        phone: String,
        kind: ContactKind = ContactKind.Society,
        roleLabel: String? = null,
        available: String? = null,
        sortOrder: Int = 0,
    ) {
        val row = buildJsonObject {
            put("society_id", societyId)
            put("kind", kind.wire)
            put("name", name.trim())
            put("role_label", roleLabel?.trim()?.ifBlank { null })
            put("phone", phone.trim())
            put("available", available?.trim()?.ifBlank { null })
            put("sort_order", sortOrder)
        }
        db.call { from("contacts").insert(row) }
    }

    suspend fun delete(id: String) {
        db.call { from("contacts").delete { filter { eq("id", id) } } }
    }
}
