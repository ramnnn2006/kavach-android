package app.kavach.android.core.data.repo

import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.RollCall
import app.kavach.android.core.data.model.SafetyCheck
import app.kavach.android.core.data.model.SafetyCheckScope
import app.kavach.android.core.data.model.SafetyResponse
import app.kavach.android.core.data.model.SafetyResponseStatus
import app.kavach.android.core.data.model.SafetySummary
import app.kavach.android.core.data.remote.Db
import app.kavach.android.core.data.remote.LiveQueries
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.table
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Safety Check: "Are you safe?" roll call. */
@Singleton
class SafetyCheckRepository @Inject constructor(
    private val db: Db,
    private val live: LiveQueries,
) {
    private val cols = Columns.raw(SAFETY_CHECK_COLUMNS)
    private val responseCols = Columns.raw("id, profile_id, status, note, responded_at, incident_id")

    suspend fun active(): SafetyCheck? = db.call {
        from("safety_checks").select(cols) {
            filter { exact("ended_at", null) }
            order("started_at", Order.DESCENDING)
            limit(1)
        }.decodeList<SafetyCheck>().firstOrNull()
    }

    fun listenActive(): Flow<Loadable<SafetyCheck?>> = live.live("safety-active", table("safety_checks")) { active() }

    suspend fun history(limit: Long = 20): List<SafetyCheck> = db.call {
        from("safety_checks").select(cols) {
            order("started_at", Order.DESCENDING)
            limit(limit)
        }.decodeList<SafetyCheck>()
    }

    fun listenHistory(limit: Long = 20): Flow<Loadable<List<SafetyCheck>>> =
        live.live("safety-history:$limit", table("safety_checks")) { history(limit) }

    /** My answer (null until I answer). */
    suspend fun myResponse(checkId: String): SafetyResponse? {
        val me = db.myId()
        return db.call {
            from("safety_check_responses").select(responseCols) {
                filter { eq("check_id", checkId); eq("profile_id", me) }
            }.decodeSingleOrNull<SafetyResponse>()
        }
    }

    fun listenMyResponse(checkId: String): Flow<Loadable<SafetyResponse?>> =
        live.live("safety-mine:$checkId", table("safety_check_responses", "check_id", checkId)) { myResponse(checkId) }

    suspend fun responses(checkId: String): List<SafetyResponse> = db.call {
        from("safety_check_responses").select(responseCols) {
            filter { eq("check_id", checkId) }
        }.decodeList<SafetyResponse>()
    }

    /** Staff: in-scope / safe / need-help / unanswered counts. */
    suspend fun summary(checkId: String): SafetySummary =
        db.rpcAs<List<SafetySummary>>("safety_check_summary", buildJsonObject { put("p_check_id", checkId) })
            .firstOrNull() ?: SafetySummary()

    /** Admin: live roll call for one check. */
    fun listenRollCall(checkId: String): Flow<Loadable<RollCall>> =
        live.live("roll-call:$checkId", table("safety_check_responses", "check_id", checkId)) {
            coroutineScope {
                val r = async { responses(checkId) }
                val s = async { summary(checkId) }
                RollCall(r.await(), s.await())
            }
        }

    /** Admin. [zoneId] required when scope is Zone. [type] is what a "need help" reply becomes. */
    suspend fun start(scope: SafetyCheckScope, zoneId: String?, message: String, type: IncidentType = IncidentType.Fire): SafetyCheck =
        db.rpcAs("start_safety_check", buildJsonObject {
            put("p_scope", scope.wire)
            put("p_zone_id", zoneId)
            put("p_message", message)
            put("p_type", type.wire)
        })

    suspend fun end(checkId: String) {
        db.rpc("end_safety_check", buildJsonObject { put("p_check_id", checkId) })
    }

    /** Resident in scope. "need_help" creates an incident server-side. */
    suspend fun respond(checkId: String, status: SafetyResponseStatus, note: String? = null): SafetyResponse =
        db.rpcAs("respond_safety_check", buildJsonObject {
            put("p_check_id", checkId)
            put("p_status", status.wire)
            put("p_note", note?.takeIf { it.isNotBlank() })
        })
}
