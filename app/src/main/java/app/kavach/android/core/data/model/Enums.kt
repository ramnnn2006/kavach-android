package app.kavach.android.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors supabase/migrations/20261008000100_enums.sql. `wire` is the Postgres value, used for
// RPC arguments and filters. Never send `name`.

@Serializable
enum class UserRole(val wire: String) {
    @SerialName("resident") Resident("resident"),
    @SerialName("responder") Responder("responder"),
    @SerialName("admin") Admin("admin");

    val isStaff get() = this != Resident
}

@Serializable
enum class IncidentType(val wire: String) {
    @SerialName("lift") Lift("lift"),
    @SerialName("fire") Fire("fire"),
    @SerialName("medical") Medical("medical"),
    @SerialName("water") Water("water"),
    @SerialName("power") Power("power"),
    @SerialName("security") Security("security");

    companion object {
        fun fromWire(value: String?): IncidentType? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
enum class IncidentStatus(val wire: String, val step: Int) {
    @SerialName("pending") Pending("pending", 0),
    @SerialName("acknowledged") Acknowledged("acknowledged", 1),
    @SerialName("en_route") EnRoute("en_route", 2),
    @SerialName("on_scene") OnScene("on_scene", 3),
    @SerialName("resolved") Resolved("resolved", 4),
    @SerialName("cancelled") Cancelled("cancelled", 4);

    val isActive get() = this in ACTIVE

    companion object {
        val ACTIVE = listOf(Pending, Acknowledged, EnRoute, OnScene)
        val CLOSED = listOf(Resolved, Cancelled)
    }
}

@Serializable
enum class IncidentSource(val wire: String) {
    @SerialName("app") App("app"),
    @SerialName("safety_check") SafetyCheck("safety_check"),
    @SerialName("qr") Qr("qr"),
    @SerialName("admin") Admin("admin"),
}

@Serializable
enum class PriorityTier(val wire: String) {
    @SerialName("P1") P1("P1"),
    @SerialName("P2") P2("P2"),
    @SerialName("P3") P3("P3"),
    @SerialName("P4") P4("P4"),
}

@Serializable
enum class PowerState(val wire: String) {
    @SerialName("on") On("on"),
    @SerialName("rotating") Rotating("rotating"),
    @SerialName("off") Off("off"),
}

@Serializable
enum class PowerSource(val wire: String) {
    @SerialName("grid") Grid("grid"),
    @SerialName("dg") Dg("dg"),
}

@Serializable
enum class ZoneKind(val wire: String) {
    @SerialName("tower") Tower("tower"),
    @SerialName("clubhouse") Clubhouse("clubhouse"),
    @SerialName("parking") Parking("parking"),
    @SerialName("utility") Utility("utility"),
    @SerialName("gate") Gate("gate"),
    @SerialName("common") Common("common"),
}

@Serializable
enum class AssetKind(val wire: String) {
    @SerialName("lift") Lift("lift"),
    @SerialName("dg") Dg("dg"),
    @SerialName("water_pump") WaterPump("water_pump"),
    @SerialName("fire_pump") FirePump("fire_pump"),
    @SerialName("sump") Sump("sump"),
    @SerialName("transformer") Transformer("transformer"),
}

@Serializable
enum class AssetState(val wire: String) {
    @SerialName("ok") Ok("ok"),
    @SerialName("degraded") Degraded("degraded"),
    @SerialName("down") Down("down"),
    @SerialName("maintenance") Maintenance("maintenance");

    companion object {
        /** Order shown in pickers (responder.ASSET_STATE_ORDER). */
        val ORDER = listOf(Ok, Degraded, Down, Maintenance)
    }
}

@Serializable
enum class AssetCheckKind(val wire: String) {
    @SerialName("dg_fuel") DgFuel("dg_fuel"),
    @SerialName("dg_load_test") DgLoadTest("dg_load_test"),
    @SerialName("lift_ard_battery") LiftArdBattery("lift_ard_battery"),
    @SerialName("lift_licence") LiftLicence("lift_licence"),
    @SerialName("lift_amc") LiftAmc("lift_amc"),
    @SerialName("fire_extinguisher_expiry") FireExtinguisherExpiry("fire_extinguisher_expiry"),
    @SerialName("fire_pump_test") FirePumpTest("fire_pump_test"),
    @SerialName("pump_service") PumpService("pump_service"),
    @SerialName("transformer_service") TransformerService("transformer_service"),
    @SerialName("other") Other("other"),
}

@Serializable
enum class EventAction(val wire: String) {
    @SerialName("created") Created("created"),
    @SerialName("claimed") Claimed("claimed"),
    @SerialName("status_changed") StatusChanged("status_changed"),
    @SerialName("assigned") Assigned("assigned"),
    @SerialName("reassigned") Reassigned("reassigned"),
    @SerialName("escalated") Escalated("escalated"),
    @SerialName("cancelled") Cancelled("cancelled"),
    @SerialName("resolved") Resolved("resolved"),
    @SerialName("note_added") NoteAdded("note_added"),
    @SerialName("details_updated") DetailsUpdated("details_updated"),
    @SerialName("first_responder_ack") FirstResponderAck("first_responder_ack"),
}

@Serializable
enum class SafetyCheckScope(val wire: String) {
    @SerialName("society") Society("society"),
    @SerialName("zone") Zone("zone"),
}

@Serializable
enum class SafetyResponseStatus(val wire: String) {
    @SerialName("safe") Safe("safe"),
    @SerialName("need_help") NeedHelp("need_help"),
}

@Serializable
enum class LanguageCode(val wire: String) {
    @SerialName("en") En("en"),
    @SerialName("ta") Ta("ta"),
}

@Serializable
enum class FirstResponderSkill(val wire: String) {
    @SerialName("doctor") Doctor("doctor"),
    @SerialName("nurse") Nurse("nurse"),
    @SerialName("paramedic") Paramedic("paramedic"),
    @SerialName("first_aid") FirstAid("first_aid"),
}

@Serializable
enum class ContactKind(val wire: String) {
    @SerialName("society") Society("society"),
    @SerialName("emergency") Emergency("emergency"),
}

/** v_compliance.compliance_status */
@Serializable
enum class ComplianceStatus(val wire: String) {
    @SerialName("overdue") Overdue("overdue"),
    @SerialName("due_soon") DueSoon("due_soon"),
    @SerialName("ok") Ok("ok"),
}
