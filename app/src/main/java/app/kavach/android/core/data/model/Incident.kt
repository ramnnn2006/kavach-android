package app.kavach.android.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** incidents row, exactly the columns of db.js INCIDENT_COLUMNS. */
@Serializable
data class Incident(
    val id: String,
    @SerialName("client_id") val clientId: String? = null,
    val source: IncidentSource = IncidentSource.App,
    val type: IncidentType,
    val status: IncidentStatus = IncidentStatus.Pending,
    @SerialName("reporter_id") val reporterId: String? = null,
    @SerialName("reporter_name") val reporterName: String? = null,
    @SerialName("reporter_phone") val reporterPhone: String? = null,
    @SerialName("reporter_vulnerability") val reporterVulnerability: Vulnerability = Vulnerability(),
    val vulnerable: Boolean = false,
    @SerialName("zone_id") val zoneId: String? = null,
    @SerialName("zone_name") val zoneName: String? = null,
    @SerialName("flat_id") val flatId: String? = null,
    @SerialName("flat_label") val flatLabel: String? = null,
    val floor: Int? = null,
    @SerialName("asset_id") val assetId: String? = null,
    @SerialName("asset_name") val assetName: String? = null,
    @SerialName("location_note") val locationNote: String? = null,
    val description: String? = null,
    @SerialName("people_affected") val peopleAffected: Int = 1,
    @SerialName("hazard_weight") val hazardWeight: Int = 50,
    @SerialName("zone_tier") val zoneTier: PriorityTier = PriorityTier.P3,
    @SerialName("urgency_score") val urgencyScore: Int = 0,
    @SerialName("escalation_level") val escalationLevel: Int = 1,
    @SerialName("assigned_to") val assignedTo: String? = null,
    @SerialName("assigned_name") val assignedName: String? = null,
    @SerialName("assigned_phone") val assignedPhone: String? = null,
    @SerialName("assigned_at") val assignedAt: IsoInstant? = null,
    @SerialName("acknowledged_at") val acknowledgedAt: IsoInstant? = null,
    @SerialName("en_route_at") val enRouteAt: IsoInstant? = null,
    @SerialName("on_scene_at") val onSceneAt: IsoInstant? = null,
    @SerialName("resolved_at") val resolvedAt: IsoInstant? = null,
    @SerialName("cancelled_at") val cancelledAt: IsoInstant? = null,
    @SerialName("resolution_note") val resolutionNote: String? = null,
    @SerialName("safety_check_id") val safetyCheckId: String? = null,
    @SerialName("created_at") val createdAt: IsoInstant,
    @SerialName("updated_at") val updatedAt: IsoInstant? = null,
) {
    val isActive: Boolean get() = status.isActive
    val isEscalated: Boolean get() = escalationLevel > 1
    /** admin/parts/format.js needsAttention() */
    val needsAttention: Boolean get() = status == IncidentStatus.Pending || isEscalated || vulnerable
}

/**
 * A new report as the resident builds it (ReportDetails buildPayload()). Also the outbox payload,
 * so keep it serializable and backwards compatible.
 */
@Serializable
data class NewIncident(
    val type: IncidentType,
    val zoneId: String? = null,
    val flatId: String? = null,
    val floor: Int? = null,
    val assetId: String? = null,
    val locationNote: String? = null,
    val description: String? = null,
    val peopleAffected: Int = 1,
)

/** incident_events row (audit log). */
@Serializable
data class IncidentEvent(
    val id: Long,
    /** Who did it (profiles.id); null = the system (escalation, Safety Check). */
    @SerialName("actor_id") val actorId: String? = null,
    @SerialName("actor_name") val actorName: String? = null,
    val action: EventAction,
    @SerialName("from_status") val fromStatus: IncidentStatus? = null,
    @SerialName("to_status") val toStatus: IncidentStatus? = null,
    val note: String? = null,
    val data: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: IsoInstant,
)

@Serializable
data class FirstResponderAck(
    @SerialName("profile_id") val profileId: String,
    @SerialName("created_at") val createdAt: IsoInstant,
)

/**
 * list_medical_alerts() row: what a community first responder may see of an active medical
 * incident (no reporter name, phone or health details).
 */
@Serializable
data class MedicalAlert(
    val id: String,
    @SerialName("zone_name") val zoneName: String? = null,
    @SerialName("flat_label") val flatLabel: String? = null,
    val floor: Int? = null,
    @SerialName("location_note") val locationNote: String? = null,
    @SerialName("people_affected") val peopleAffected: Int = 1,
    val description: String? = null,
    val status: IncidentStatus = IncidentStatus.Pending,
    @SerialName("created_at") val createdAt: IsoInstant,
    @SerialName("ack_count") val ackCount: Int = 0,
    @SerialName("i_am_coming") val iAmComing: Boolean = false,
)
