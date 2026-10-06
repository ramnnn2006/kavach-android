package app.kavach.android.core.data.repo

import app.kavach.android.core.config.AppConfig
import app.kavach.android.core.data.model.FirstResponderAck
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentEvent
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.MedicalAlert
import app.kavach.android.core.data.model.NewIncident
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Admin incident list filters (db.js listIncidents). */
data class IncidentFilters(
    /** null = all, or one status. Use [StatusFilter] for active / closed groups. */
    val status: StatusFilter = StatusFilter.All,
    val type: IncidentType? = null,
    val zoneId: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val limit: Long = 300,
)

sealed interface StatusFilter {
    data object All : StatusFilter
    data object Active : StatusFilter
    data object Closed : StatusFilter
    data class Only(val status: IncidentStatus) : StatusFilter
}

@Singleton
class IncidentRepository @Inject constructor(
    private val db: Db,
    private val live: LiveQueries,
) {
    private val cols = Columns.raw(INCIDENT_COLUMNS)
    private val activeWire = IncidentStatus.ACTIVE.map { it.wire }
    private val closedWire = IncidentStatus.CLOSED.map { it.wire }

    /**
     * Create an incident. [clientId] (uuid) makes retries idempotent: a duplicate returns the
     * existing row (db.js createIncident). The server fills reporter, snapshots and urgency.
     */
    suspend fun create(report: NewIncident, clientId: String = UUID.randomUUID().toString()): Incident {
        val row = buildJsonObject {
            put("client_id", clientId)
            put("type", report.type.wire)
            put("zone_id", report.zoneId)
            put("flat_id", report.flatId)
            put("floor", report.floor)
            put("asset_id", report.assetId)
            put("location_note", report.locationNote)
            put("description", report.description)
            put("people_affected", report.peopleAffected)
        }
        return try {
            db.call { from("incidents").insert(row) { select(cols) }.decodeSingle<Incident>() }
        } catch (e: KavachException) {
            if (e.code != ErrorCode.Duplicate) throw e
            // Already sent (retry after a dropped response) → return the original
            val me = db.myId()
            db.call {
                from("incidents").select(cols) {
                    filter { eq("reporter_id", me); eq("client_id", clientId) }
                }.decodeSingle<Incident>()
            }
        }
    }

    suspend fun get(id: String): Incident? = db.call {
        from("incidents").select(cols) { filter { eq("id", id) } }.decodeSingleOrNull<Incident>()
    }

    fun listen(id: String): Flow<Loadable<Incident?>> =
        live.live("incident:$id", table("incidents", "id", id)) { get(id) }

    suspend fun events(incidentId: String): List<IncidentEvent> = db.call {
        from("incident_events").select(Columns.raw(EVENT_COLUMNS)) {
            filter { eq("incident_id", incidentId) }
            order("created_at", Order.ASCENDING)
        }.decodeList<IncidentEvent>()
    }

    fun listenEvents(incidentId: String): Flow<Loadable<List<IncidentEvent>>> =
        live.live("incident-events:$incidentId", table("incident_events", "incident_id", incidentId)) { events(incidentId) }

    /** Resident: my own reports, newest first. */
    suspend fun myIncidents(limit: Long = 100): List<Incident> {
        val me = db.myId()
        return db.call {
            from("incidents").select(cols) {
                filter { eq("reporter_id", me) }
                order("created_at", Order.DESCENDING)
                limit(limit)
            }.decodeList<Incident>()
        }
    }

    /** Watches only my own rows when the user is known (staff would otherwise refetch on every incident). */
    fun listenMyIncidents(): Flow<Loadable<List<Incident>>> {
        val me = db.myIdOrNull()
        val watch = if (me != null) table("incidents", "reporter_id", me) else table("incidents")
        return live.live("my-incidents", watch) { myIncidents() }
    }

    /**
     * Staff queue, most urgent first. RLS limits responders to their specialties + assigned;
     * admins see everything (db.js listQueue).
     */
    suspend fun queue(active: Boolean = true, since: Instant? = null, limit: Long = 200): List<Incident> = db.call {
        from("incidents").select(cols) {
            filter {
                if (active) isIn("status", activeWire)
                if (since != null) gte("created_at", since.toString())
            }
            order("urgency_score", Order.DESCENDING)
            order("created_at", Order.ASCENDING)
            limit(limit)
        }.decodeList<Incident>()
    }

    fun listenQueue(active: Boolean = true, since: Instant? = null, limit: Long = 200): Flow<Loadable<List<Incident>>> =
        live.live("queue:$active:$since:$limit", table("incidents")) { queue(active, since, limit) }

    /** Resolved/cancelled incidents I handled (responder history). */
    suspend fun myHandled(limit: Long = 100): List<Incident> {
        val me = db.myId()
        return db.call {
            from("incidents").select(cols) {
                filter { eq("assigned_to", me); isIn("status", closedWire) }
                order("resolved_at", Order.DESCENDING, nullsFirst = false)
                limit(limit)
            }.decodeList<Incident>()
        }
    }

    fun listenMyHandled(): Flow<Loadable<List<Incident>>> {
        val me = db.myIdOrNull()
        val watch = if (me != null) table("incidents", "assigned_to", me) else table("incidents")
        return live.live("my-handled", watch) { myHandled() }
    }

    /** Admin: incidents with filters, newest first. */
    suspend fun list(filters: IncidentFilters = IncidentFilters()): List<Incident> = db.call {
        from("incidents").select(cols) {
            filter {
                when (val s = filters.status) {
                    StatusFilter.All -> Unit
                    StatusFilter.Active -> isIn("status", activeWire)
                    StatusFilter.Closed -> isIn("status", closedWire)
                    is StatusFilter.Only -> eq("status", s.status.wire)
                }
                filters.type?.let { eq("type", it.wire) }
                filters.zoneId?.let { eq("zone_id", it) }
                filters.from?.let { gte("created_at", it.toString()) }
                filters.to?.let { lt("created_at", it.toString()) }
            }
            order("created_at", Order.DESCENDING)
            limit(filters.limit)
        }.decodeList<Incident>()
    }

    fun listenList(filters: IncidentFilters = IncidentFilters()): Flow<Loadable<List<Incident>>> =
        live.live("incidents:$filters", table("incidents")) { list(filters) }

    // ── RPCs: every state change is validated server-side ─────────────────────
    suspend fun claim(id: String): Incident =
        db.rpcAs("claim_incident", buildJsonObject { put("p_incident_id", id) })

    /** Forward-only: Acknowledged → EnRoute → OnScene → Resolved. */
    suspend fun advance(id: String, next: IncidentStatus, note: String? = null): Incident =
        db.rpcAs("advance_incident", buildJsonObject {
            put("p_incident_id", id)
            put("p_next", next.wire)
            put("p_note", note?.takeIf { it.isNotBlank() })
        })

    suspend fun cancel(id: String, reason: String? = null): Incident =
        db.rpcAs("cancel_incident", buildJsonObject {
            put("p_incident_id", id)
            put("p_reason", reason?.takeIf { it.isNotBlank() })
        })

    suspend fun assign(id: String, responderId: String): Incident =
        db.rpcAs("assign_incident", buildJsonObject {
            put("p_incident_id", id)
            put("p_responder_id", responderId)
        })

    /** Reporter / admin while active; null fields are left unchanged. */
    suspend fun updateDetails(
        id: String,
        description: String? = null,
        locationNote: String? = null,
        peopleAffected: Int? = null,
        assetId: String? = null,
    ): Incident = db.rpcAs("update_incident_details", buildJsonObject {
        put("p_incident_id", id)
        put("p_description", description)
        put("p_location_note", locationNote)
        put("p_people_affected", peopleAffected)
        put("p_asset_id", assetId)
    })

    /** Community first responder: "I'm coming" on an active medical incident. */
    suspend fun firstResponderAck(id: String) {
        db.rpc("first_responder_ack", buildJsonObject { put("p_incident_id", id) })
    }

    suspend fun firstResponderAcks(incidentId: String): List<FirstResponderAck> = db.call {
        from("first_responder_acks").select(Columns.raw("profile_id, created_at")) {
            filter { eq("incident_id", incidentId) }
        }.decodeList<FirstResponderAck>()
    }

    /** Acks write an incident_events row, so that is what we watch. */
    fun listenFirstResponderAcks(incidentId: String): Flow<Loadable<List<FirstResponderAck>>> =
        live.live("first-responders:$incidentId", table("incident_events", "incident_id", incidentId)) { firstResponderAcks(incidentId) }

    /**
     * Active medical incidents a community first responder can act on (not my own). Served by
     * list_medical_alerts(): place, time, people and description only.
     */
    suspend fun medicalAlerts(): List<MedicalAlert> =
        db.rpcAs("list_medical_alerts")

    /** First responders can't subscribe to medical rows (RLS), so this polls every 15 s while open. */
    fun listenMedicalAlerts(): Flow<Loadable<List<MedicalAlert>>> =
        live.live(emptyList(), AppConfig.MEDICAL_POLL_MS, key = "medical-alerts") { medicalAlerts() }

    /** JSON for the printable post-incident report: {incident, events, first_responders, generated_at}. */
    suspend fun incidentReport(id: String): JsonObject =
        db.rpcAs("incident_report", buildJsonObject { put("p_incident_id", id) })

    /** Throws NotFound when [incident] is null (helper for screens loading by id). */
    fun requireFound(incident: Incident?): Incident = incident ?: throw KavachException(ErrorCode.NotFound)
}
