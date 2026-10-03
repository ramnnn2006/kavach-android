package app.kavach.android.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** profiles.vulnerability jsonb: {"elderly","mobility","medical_device","infant","note"}. */
@Serializable
data class Vulnerability(
    val elderly: Boolean = false,
    val mobility: Boolean = false,
    @SerialName("medical_device") val medicalDevice: Boolean = false,
    val infant: Boolean = false,
    val note: String? = null,
) {
    /** True when any flag is set or the note has text. */
    val any: Boolean get() = elderly || mobility || medicalDevice || infant || !note.isNullOrBlank()

    /** Flags in display order (matches VULN_KEYS on the website). */
    val flags: List<VulnerabilityFlag>
        get() = buildList {
            if (elderly) add(VulnerabilityFlag.Elderly)
            if (mobility) add(VulnerabilityFlag.Mobility)
            if (medicalDevice) add(VulnerabilityFlag.MedicalDevice)
            if (infant) add(VulnerabilityFlag.Infant)
        }
}

enum class VulnerabilityFlag { Elderly, Mobility, MedicalDevice, Infant }

@Serializable
data class ZoneRef(
    val id: String,
    val name: String,
    val code: String? = null,
    @SerialName("sort_order") val sortOrder: Int? = null,
)

/** Embedded `profiles(full_name)` join. */
@Serializable
data class NameRef(@SerialName("full_name") val fullName: String? = null)

@Serializable
data class ProfileFlat(
    val id: String,
    val number: String,
    val floor: Int,
    @SerialName("zone_id") val zoneId: String,
    val zone: ZoneRef? = null,
)

@Serializable
data class ProfileSociety(
    val id: String,
    val name: String,
    val city: String? = null,
    @SerialName("security_phone") val securityPhone: String? = null,
    @SerialName("power_source") val powerSource: PowerSource = PowerSource.Grid,
)

/**
 * The signed-in user's profile (AuthContext PROFILE_COLUMNS). Loaded by SessionManager and
 * kept fresh by a realtime subscription on the user's own row.
 */
@Serializable
data class Profile(
    val id: String,
    @SerialName("society_id") val societyId: String? = null,
    @SerialName("full_name") val fullName: String = "",
    val email: String? = null,
    val phone: String? = null,
    val role: UserRole = UserRole.Resident,
    val specialties: List<IncidentType> = emptyList(),
    @SerialName("on_duty") val onDuty: Boolean = false,
    @SerialName("flat_id") val flatId: String? = null,
    val language: LanguageCode = LanguageCode.En,
    val vulnerability: Vulnerability = Vulnerability(),
    @SerialName("first_responder_skill") val firstResponderSkill: FirstResponderSkill? = null,
    val flat: ProfileFlat? = null,
    val society: ProfileSociety? = null,
) {
    val firstName: String get() = fullName.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    val isFirstResponder: Boolean get() = firstResponderSkill != null
    val homeZoneId: String? get() = flat?.zone?.id ?: flat?.zoneId
}

/** Row of the admin member list (db.js MEMBER_COLUMNS) — existing accounts, any role. */
@Serializable
data class Member(
    val id: String,
    @SerialName("full_name") val fullName: String = "",
    val email: String? = null,
    val phone: String? = null,
    val role: UserRole = UserRole.Resident,
    val specialties: List<IncidentType> = emptyList(),
    @SerialName("on_duty") val onDuty: Boolean = false,
    @SerialName("flat_id") val flatId: String? = null,
    @SerialName("first_responder_skill") val firstResponderSkill: FirstResponderSkill? = null,
    val vulnerability: Vulnerability = Vulnerability(),
    @SerialName("updated_at") val updatedAt: IsoInstant? = null,
    val flat: MemberFlat? = null,
)

@Serializable
data class MemberFlat(
    val number: String,
    val floor: Int? = null,
    val zone: NameOnly? = null,
)

@Serializable
data class NameOnly(val name: String)

/** v_team row: responders and admins with their active load. */
@Serializable
data class TeamMember(
    val id: String,
    @SerialName("society_id") val societyId: String? = null,
    @SerialName("full_name") val fullName: String = "",
    val phone: String? = null,
    val role: UserRole = UserRole.Responder,
    val specialties: List<IncidentType> = emptyList(),
    @SerialName("on_duty") val onDuty: Boolean = false,
    val language: LanguageCode = LanguageCode.En,
    @SerialName("active_load") val activeLoad: Int = 0,
    @SerialName("last_resolved_at") val lastResolvedAt: IsoInstant? = null,
)

/**
 * Admin-approved phone list (new `members` table, list_approved_members()). A phone sign-up is
 * accepted only when its number is on this list, and gets this name / role / flat / specialties.
 */
@Serializable
data class ApprovedMember(
    val id: String,
    val phone: String,
    @SerialName("full_name") val fullName: String,
    val role: UserRole = UserRole.Resident,
    @SerialName("flat_id") val flatId: String? = null,
    @SerialName("flat_number") val flatNumber: String? = null,
    @SerialName("zone_id") val zoneId: String? = null,
    @SerialName("zone_name") val zoneName: String? = null,
    val specialties: List<IncidentType> = emptyList(),
    /** Set once this person has signed in (their profiles.id). */
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("created_at") val createdAt: IsoInstant? = null,
    @SerialName("updated_at") val updatedAt: IsoInstant? = null,
)
