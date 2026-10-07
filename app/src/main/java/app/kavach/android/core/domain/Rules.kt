package app.kavach.android.core.domain

import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetCheckKind
import app.kavach.android.core.data.model.AssetKind
import app.kavach.android.core.data.model.ComplianceItem
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.config.AppConfig
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Pure rules shared by every role. Mirrors config/society.js and data/responder.js. No Android here. */
object Rules {
    /** Report picker order: most life-threatening first (lift has its own hero button). */
    val TYPE_ORDER = listOf(IncidentType.Fire, IncidentType.Medical, IncidentType.Lift, IncidentType.Security, IncidentType.Power, IncidentType.Water)

    /** Chart stacking order (colour-blind safe neighbours). */
    val CHART_TYPE_ORDER = listOf(IncidentType.Fire, IncidentType.Lift, IncidentType.Medical, IncidentType.Water, IncidentType.Security, IncidentType.Power)

    /** Responder specialty presets. */
    val PRESET_MAINTENANCE = listOf(IncidentType.Lift, IncidentType.Power, IncidentType.Water)
    val PRESET_SECURITY = listOf(IncidentType.Fire, IncidentType.Medical, IncidentType.Security)

    enum class Team { Maintenance, Security }

    /** Which team handles a type (resident wording: "maintenance team" / "security team"). */
    fun teamFor(type: IncidentType): Team = if (type in PRESET_MAINTENANCE) Team.Maintenance else Team.Security

    /** Next forward step a responder can take, with its button (NEXT_STATUS). Null = none. */
    fun nextStatus(status: IncidentStatus): IncidentStatus? = when (status) {
        IncidentStatus.Acknowledged -> IncidentStatus.EnRoute
        IncidentStatus.EnRoute -> IncidentStatus.OnScene
        IncidentStatus.OnScene -> IncidentStatus.Resolved
        else -> null
    }

    /** asset_kind → specialty (public.asset_kind_type()). */
    fun assetSpecialty(kind: AssetKind): IncidentType = when (kind) {
        AssetKind.Lift -> IncidentType.Lift
        AssetKind.Dg, AssetKind.Transformer -> IncidentType.Power
        AssetKind.WaterPump, AssetKind.Sump -> IncidentType.Water
        AssetKind.FirePump -> IncidentType.Fire
    }

    fun assetSpecialty(asset: Asset): IncidentType = assetSpecialty(asset.kind)

    /** asset_check_kind → specialty (public.check_kind_type()). */
    fun checkKindSpecialty(kind: AssetCheckKind): IncidentType? = when (kind) {
        AssetCheckKind.DgFuel, AssetCheckKind.DgLoadTest, AssetCheckKind.TransformerService -> IncidentType.Power
        AssetCheckKind.LiftArdBattery, AssetCheckKind.LiftLicence, AssetCheckKind.LiftAmc -> IncidentType.Lift
        AssetCheckKind.FireExtinguisherExpiry, AssetCheckKind.FirePumpTest -> IncidentType.Fire
        AssetCheckKind.PumpService -> IncidentType.Water
        AssetCheckKind.Other -> null
    }

    /** Specialty of a compliance row: the asset's kind wins, then the check kind. */
    fun checkSpecialty(item: ComplianceItem): IncidentType? =
        item.assetKind?.let(::assetSpecialty) ?: checkKindSpecialty(item.kind)

    /** Does this profile cover an incident type? Admins cover everything. */
    fun covers(profile: Profile?, type: IncidentType?): Boolean {
        if (profile?.role == UserRole.Admin) return true
        return type != null && profile?.specialties?.contains(type) == true
    }

    /** Staff queue order: most urgent first, then oldest first (admin byUrgency). */
    val byUrgency: Comparator<Incident> =
        compareByDescending<Incident> { it.urgencyScore }.thenBy { it.createdAt }

    /** Pending for at least [minutes]. */
    fun isUnclaimedFor(incident: Incident, minutes: Double, now: Instant): Boolean =
        incident.status == IncidentStatus.Pending &&
            Duration.between(incident.createdAt, now).toMillis() >= (minutes * 60_000).toLong()

    /** Average created → acknowledged minutes (1 decimal), or null if nothing was acknowledged. */
    fun avgResponseMinutes(incidents: List<Incident>): Double? {
        val mins = incidents.mapNotNull { i -> i.acknowledgedAt?.let { minutesBetween(i.createdAt, it).toDouble() } }
        if (mins.isEmpty()) return null
        return Math.round(mins.average() * 10) / 10.0
    }

    fun minutesBetween(a: Instant, b: Instant): Long = maxOf(0, Math.round(Duration.between(a, b).toMillis() / 60_000.0))

    /** Postgres interval "00:02:00" → minutes (fallback 2). */
    fun intervalMinutes(value: String?, fallback: Double = AppConfig.ESCALATE_DEFAULT_MIN): Double {
        val m = Regex("^(\\d+):(\\d{2}):(\\d{2})").find(value ?: return fallback) ?: return fallback
        val (h, mi, s) = m.destructured
        return h.toInt() * 60 + mi.toInt() + s.toInt() / 60.0
    }

    /** Whole days from today (device zone) to [date]; negative = past. */
    fun daysUntil(date: LocalDate, today: LocalDate = LocalDate.now(ZoneId.systemDefault())): Long =
        ChronoUnit.DAYS.between(today, date)

    /** Midnight today in the device zone (admin "today" stats). */
    fun startOfToday(): Instant = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant()
}

/** Phone numbers for login and the approved list. India by default. */
object Phones {
    private val SEPARATORS = Regex("[\\s().-]")
    private val PLUS_DIGITS = Regex("^\\+?[0-9]+$")
    private val INTERNATIONAL = Regex("^[1-9][0-9]{7,14}$")

    /**
     * Normalize user input to E.164, exactly like the server's public.normalise_phone():
     * "98765 43210" / "098765-43210" / "919876543210" / "+91 98765 43210" → "+919876543210".
     * Indian mobile numbers must start with 6–9; other "+" numbers need 8–15 digits.
     * Returns null when it can't be a valid number (the server would refuse it).
     */
    fun normalize(input: String, defaultCountryCode: String = AppConfig.DEFAULT_COUNTRY_CODE): String? {
        val s = input.replace(SEPARATORS, "")
        if (!PLUS_DIGITS.matches(s)) return null
        val cc = defaultCountryCode.removePrefix("+")
        if (s.startsWith("+")) {
            val d = s.substring(1)
            if (Regex("^${cc}[6-9][0-9]{9}$").matches(d)) return "+$d"
            if (d.startsWith(cc)) return null // not a valid number for the home country
            return if (INTERNATIONAL.matches(d)) "+$d" else null
        }
        if (Regex("^[6-9][0-9]{9}$").matches(s)) return "+$cc$s"
        if (Regex("^0[6-9][0-9]{9}$").matches(s)) return "+$cc${s.substring(1)}"
        if (Regex("^${cc}[6-9][0-9]{9}$").matches(s)) return "+$s"
        return null
    }

    /** "+919876543210" → "+91 98765 43210" for display. */
    fun display(e164: String?): String {
        if (e164.isNullOrBlank()) return ""
        val d = e164.filter(Char::isDigit)
        return if (e164.startsWith("+91") && d.length == 12) "+91 ${d.substring(2, 7)} ${d.substring(7)}" else e164
    }

    /** tel: URI digits (CallLink telHref). */
    fun dialable(phone: String?): String = phone.orEmpty().filter { it.isDigit() || it == '+' }
}
