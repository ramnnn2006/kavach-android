package app.kavach.android.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Society(
    val id: String,
    val name: String,
    val city: String? = null,
    val address: String? = null,
    @SerialName("security_phone") val securityPhone: String? = null,
    @SerialName("power_source") val powerSource: PowerSource = PowerSource.Grid,
    /** Postgres interval text, e.g. "00:02:00". Use intervalMinutes() to read it. */
    @SerialName("escalate_l2_after") val escalateL2After: String? = null,
    @SerialName("escalate_l3_after") val escalateL3After: String? = null,
)

@Serializable
data class Zone(
    val id: String,
    val name: String,
    val code: String? = null,
    val kind: ZoneKind = ZoneKind.Common,
    val tier: PriorityTier = PriorityTier.P3,
    @SerialName("power_state") val powerState: PowerState = PowerState.On,
    val floors: Int? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

/** assets row with zone and who last changed the state (responder.js ASSET_COLUMNS). */
@Serializable
data class Asset(
    val id: String,
    @SerialName("zone_id") val zoneId: String? = null,
    val kind: AssetKind,
    val name: String,
    val code: String? = null,
    val state: AssetState = AssetState.Ok,
    val vendor: String? = null,
    @SerialName("amc_expires_on") val amcExpiresOn: IsoDate? = null,
    @SerialName("licence_expires_on") val licenceExpiresOn: IsoDate? = null,
    val notes: String? = null,
    @SerialName("state_changed_at") val stateChangedAt: IsoInstant? = null,
    @SerialName("changed_by") val changedBy: NameRef? = null,
    val zone: ZoneRef? = null,
)

/** v_compliance row: a scheduled check with its due status. */
@Serializable
data class ComplianceItem(
    val id: String,
    @SerialName("asset_id") val assetId: String? = null,
    @SerialName("zone_id") val zoneId: String? = null,
    val kind: AssetCheckKind = AssetCheckKind.Other,
    val title: String,
    @SerialName("interval_days") val intervalDays: Int = 30,
    @SerialName("due_at") val dueAt: IsoDate,
    @SerialName("last_done_at") val lastDoneAt: IsoInstant? = null,
    @SerialName("done_by") val doneBy: String? = null,
    val notes: String? = null,
    @SerialName("asset_name") val assetName: String? = null,
    @SerialName("asset_kind") val assetKind: AssetKind? = null,
    @SerialName("zone_name") val zoneName: String? = null,
    @SerialName("compliance_status") val complianceStatus: ComplianceStatus = ComplianceStatus.Ok,
    @SerialName("days_left") val daysLeft: Int = 0,
)

@Serializable
data class PowerEvent(
    val id: Long,
    val source: PowerSource,
    @SerialName("switched_at") val switchedAt: IsoInstant,
    val note: String? = null,
    val by: NameRef? = null,
)

@Serializable
data class Notice(
    val id: String,
    val title: String,
    val body: String = "",
    val pinned: Boolean = false,
    @SerialName("expires_at") val expiresAt: IsoInstant? = null,
    @SerialName("author_name") val authorName: String? = null,
    @SerialName("created_at") val createdAt: IsoInstant,
)

@Serializable
data class Contact(
    val id: String,
    val kind: ContactKind = ContactKind.Society,
    val name: String,
    @SerialName("role_label") val roleLabel: String? = null,
    val phone: String,
    val available: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class SafetyCheck(
    val id: String,
    val scope: SafetyCheckScope = SafetyCheckScope.Society,
    @SerialName("zone_id") val zoneId: String? = null,
    @SerialName("incident_type") val incidentType: IncidentType = IncidentType.Fire,
    val message: String,
    @SerialName("started_at") val startedAt: IsoInstant,
    @SerialName("ended_at") val endedAt: IsoInstant? = null,
    val zone: ZoneRef? = null,
) {
    val isActive: Boolean get() = endedAt == null
}

@Serializable
data class SafetyResponse(
    val id: String,
    @SerialName("profile_id") val profileId: String? = null,
    val status: SafetyResponseStatus,
    val note: String? = null,
    @SerialName("responded_at") val respondedAt: IsoInstant,
    @SerialName("incident_id") val incidentId: String? = null,
)

@Serializable
data class SafetySummary(
    @SerialName("in_scope") val inScope: Long = 0,
    val safe: Long = 0,
    @SerialName("need_help") val needHelp: Long = 0,
    val unanswered: Long = 0,
)

/** Admin roll call: every response plus server counts (admin.js listenRollCall). */
data class RollCall(val responses: List<SafetyResponse>, val summary: SafetySummary)
