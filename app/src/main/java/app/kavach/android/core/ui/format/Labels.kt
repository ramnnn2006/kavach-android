package app.kavach.android.core.ui.format

import androidx.annotation.StringRes
import app.kavach.android.R
import app.kavach.android.core.data.model.AssetCheckKind
import app.kavach.android.core.data.model.AssetKind
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.data.model.ComplianceStatus
import app.kavach.android.core.data.model.EventAction
import app.kavach.android.core.data.model.FirstResponderSkill
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.PowerSource
import app.kavach.android.core.data.model.PowerState
import app.kavach.android.core.data.model.PriorityTier
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.model.VulnerabilityFlag
import app.kavach.android.core.domain.Rules

// Enum → string resource. Pure functions, usable in Composables (stringResource(x.labelRes()))
// and in the alert service (context.getString(x.labelRes())).

@StringRes fun IncidentType.labelRes(): Int = when (this) {
    IncidentType.Lift -> R.string.common_type_lift
    IncidentType.Fire -> R.string.common_type_fire
    IncidentType.Medical -> R.string.common_type_medical
    IncidentType.Water -> R.string.common_type_water
    IncidentType.Power -> R.string.common_type_power
    IncidentType.Security -> R.string.common_type_security
}

@StringRes fun IncidentType.shortLabelRes(): Int = when (this) {
    IncidentType.Lift -> R.string.common_type_short_lift
    IncidentType.Fire -> R.string.common_type_short_fire
    IncidentType.Medical -> R.string.common_type_short_medical
    IncidentType.Water -> R.string.common_type_short_water
    IncidentType.Power -> R.string.common_type_short_power
    IncidentType.Security -> R.string.common_type_short_security
}

@StringRes fun IncidentType.hintRes(): Int = when (this) {
    IncidentType.Lift -> R.string.common_type_hint_lift
    IncidentType.Fire -> R.string.common_type_hint_fire
    IncidentType.Medical -> R.string.common_type_hint_medical
    IncidentType.Water -> R.string.common_type_hint_water
    IncidentType.Power -> R.string.common_type_hint_power
    IncidentType.Security -> R.string.common_type_hint_security
}

/** Resident wording ("Waiting", "Help arrived") or staff wording ("Open", "On scene"). */
@StringRes fun IncidentStatus.labelRes(staff: Boolean): Int = if (staff) when (this) {
    IncidentStatus.Pending -> R.string.common_staff_status_pending
    IncidentStatus.Acknowledged -> R.string.common_staff_status_acknowledged
    IncidentStatus.EnRoute -> R.string.common_staff_status_en_route
    IncidentStatus.OnScene -> R.string.common_staff_status_on_scene
    IncidentStatus.Resolved -> R.string.common_staff_status_resolved
    IncidentStatus.Cancelled -> R.string.common_staff_status_cancelled
} else when (this) {
    IncidentStatus.Pending -> R.string.common_status_pending
    IncidentStatus.Acknowledged -> R.string.common_status_acknowledged
    IncidentStatus.EnRoute -> R.string.common_status_en_route
    IncidentStatus.OnScene -> R.string.common_status_on_scene
    IncidentStatus.Resolved -> R.string.common_status_resolved
    IncidentStatus.Cancelled -> R.string.common_status_cancelled
}

/** Button for the step after this status ("On my way" / "I've reached" / "Mark resolved"), or null. */
@StringRes fun IncidentStatus.nextActionRes(): Int? = when (Rules.nextStatus(this)) {
    IncidentStatus.EnRoute -> R.string.common_action_on_my_way
    IncidentStatus.OnScene -> R.string.common_action_reached
    IncidentStatus.Resolved -> R.string.common_action_resolve
    else -> null
}

@StringRes fun UserRole.labelRes(): Int = when (this) {
    UserRole.Resident -> R.string.common_role_resident
    UserRole.Responder -> R.string.common_role_responder
    UserRole.Admin -> R.string.common_role_admin
}

@StringRes fun FirstResponderSkill.labelRes(): Int = when (this) {
    FirstResponderSkill.Doctor -> R.string.common_skill_doctor
    FirstResponderSkill.Nurse -> R.string.common_skill_nurse
    FirstResponderSkill.Paramedic -> R.string.common_skill_paramedic
    FirstResponderSkill.FirstAid -> R.string.common_skill_first_aid
}

@StringRes fun VulnerabilityFlag.labelRes(): Int = when (this) {
    VulnerabilityFlag.Elderly -> R.string.common_vuln_elderly
    VulnerabilityFlag.Mobility -> R.string.common_vuln_mobility
    VulnerabilityFlag.MedicalDevice -> R.string.common_vuln_medical_device
    VulnerabilityFlag.Infant -> R.string.common_vuln_infant
}

@StringRes fun AssetKind.labelRes(): Int = when (this) {
    AssetKind.Lift -> R.string.common_asset_kind_lift
    AssetKind.Dg -> R.string.common_asset_kind_dg
    AssetKind.WaterPump -> R.string.common_asset_kind_water_pump
    AssetKind.FirePump -> R.string.common_asset_kind_fire_pump
    AssetKind.Sump -> R.string.common_asset_kind_sump
    AssetKind.Transformer -> R.string.common_asset_kind_transformer
}

@StringRes fun AssetState.labelRes(): Int = when (this) {
    AssetState.Ok -> R.string.common_asset_state_ok
    AssetState.Degraded -> R.string.common_asset_state_degraded
    AssetState.Down -> R.string.common_asset_state_down
    AssetState.Maintenance -> R.string.common_asset_state_maintenance
}

@StringRes fun ComplianceStatus.labelRes(): Int = when (this) {
    ComplianceStatus.Overdue -> R.string.common_compliance_overdue
    ComplianceStatus.DueSoon -> R.string.common_compliance_due_soon
    ComplianceStatus.Ok -> R.string.common_compliance_ok
}

@StringRes fun PriorityTier.labelRes(): Int = when (this) {
    PriorityTier.P1 -> R.string.common_tier_p1
    PriorityTier.P2 -> R.string.common_tier_p2
    PriorityTier.P3 -> R.string.common_tier_p3
    PriorityTier.P4 -> R.string.common_tier_p4
}

@StringRes fun PowerState.labelRes(): Int = when (this) {
    PowerState.On -> R.string.common_power_on
    PowerState.Rotating -> R.string.common_power_rotating
    PowerState.Off -> R.string.common_power_off
}

@StringRes fun PowerSource.labelRes(): Int = when (this) {
    PowerSource.Grid -> R.string.common_power_source_grid
    PowerSource.Dg -> R.string.common_power_source_dg
}

/** Area-specific check kind labels live with the screens that need them; Other → title only. */
val AssetCheckKind.isOther: Boolean get() = this == AssetCheckKind.Other

@StringRes fun Rules.Team.labelRes(): Int = when (this) {
    Rules.Team.Maintenance -> R.string.common_preset_maintenance
    Rules.Team.Security -> R.string.common_preset_security
}

/** Base text for an audit event; most take one argument (see strings common_event_*). */
@StringRes fun EventAction.labelRes(): Int = when (this) {
    EventAction.Created -> R.string.common_event_created
    EventAction.Claimed -> R.string.common_event_claimed
    EventAction.StatusChanged -> R.string.common_event_status_changed
    EventAction.Assigned -> R.string.common_event_assigned
    EventAction.Reassigned -> R.string.common_event_reassigned
    EventAction.Escalated -> R.string.common_event_escalated
    EventAction.Cancelled -> R.string.common_event_cancelled
    EventAction.Resolved -> R.string.common_event_resolved
    EventAction.NoteAdded -> R.string.common_event_note_added
    EventAction.DetailsUpdated -> R.string.common_event_details_updated
    EventAction.FirstResponderAck -> R.string.common_event_first_responder_ack
}
