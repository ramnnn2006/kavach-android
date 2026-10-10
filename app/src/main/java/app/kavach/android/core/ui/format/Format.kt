package app.kavach.android.core.ui.format

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.model.Vulnerability
import app.kavach.android.core.domain.Rules
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Text formatting shared by all screens and notifications. Functions take a Context so the alert
// service can use them too; in Composables pass LocalContext.current (or use the @Composable
// overloads at the bottom).

object Format {
    /** The device's zone, read on every call (it can change while the app runs). */
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val locale: Locale = Locale.forLanguageTag("en-IN")

    // Parsed once (lists format many rows); the zone is applied per call with withZone()
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dateTimeFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm", locale)
    private val dayMonthFormat = DateTimeFormatter.ofPattern("d MMM", locale)
    private val dayMonthYearFormat = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    private val longDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)

    /** "Just now" / "4 min ago" / "2 h ago" / "3 d ago". */
    fun timeAgo(context: Context, at: Instant?, now: Instant = Instant.now()): String {
        if (at == null) return context.getString(R.string.common_just_now)
        val mins = maxOf(0, Duration.between(at, now).toMinutes()).toInt()
        return when {
            mins < 1 -> context.getString(R.string.common_just_now)
            mins < 60 -> context.getString(R.string.common_min_ago, mins)
            mins < 1440 -> context.getString(R.string.common_h_ago, mins / 60)
            else -> context.getString(R.string.common_d_ago, mins / 1440)
        }
    }

    /** "14:05" */
    fun clock(at: Instant?): String =
        at?.let { clockFormat.withZone(zone).format(it) }.orEmpty()

    /** "8 Oct, 14:05" */
    fun dateTime(at: Instant?): String =
        at?.let { dateTimeFormat.withZone(zone).format(it) }.orEmpty()

    /** "8 Oct" (adds the year when it isn't this year). */
    fun shortDate(date: LocalDate?): String {
        if (date == null) return ""
        val format = if (date.year == LocalDate.now(zone).year) dayMonthFormat else dayMonthYearFormat
        return format.format(date)
    }

    fun shortDate(at: Instant?): String = shortDate(at?.atZone(zone)?.toLocalDate())

    /** "8 October 2026" */
    fun longDate(date: LocalDate?): String =
        date?.let { longDateFormat.format(it) }.orEmpty()

    /** "14 min" / "1 h 5 min" / "–". */
    fun duration(context: Context, minutes: Long?): String {
        if (minutes == null) return context.getString(R.string.common_duration_none)
        if (minutes < 60) return context.getString(R.string.common_minutes, minutes.toInt())
        val h = (minutes / 60).toInt()
        val m = (minutes % 60).toInt()
        return if (m == 0) context.getString(R.string.common_hours, h) else context.getString(R.string.common_hours_minutes, h, m)
    }

    /** Elapsed time as m:ss or h:mm:ss (command board). */
    fun elapsed(since: Instant?, now: Instant): String {
        if (since == null) return ""
        val s = maxOf(0, Duration.between(since, now).seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = "%02d".format(s % 60)
        return if (h > 0) "$h:${"%02d".format(m)}:$sec" else "$m:$sec"
    }

    fun floor(context: Context, floor: Int?): String? = when (floor) {
        null -> null
        0 -> context.getString(R.string.common_ground_floor)
        else -> context.getString(R.string.common_floor_n, floor)
    }

    fun people(context: Context, n: Int): String =
        context.resources.getQuantityString(R.plurals.common_people_count, n.coerceAtLeast(1), n.coerceAtLeast(1))

    /**
     * Staff place line: "Tower A · A-1204 · Floor 12 · Lift A2" (responder placeLine). Falls back to
     * the location note, then "Place not given".
     */
    fun place(context: Context, inc: Incident): String {
        val parts = listOfNotNull(inc.zoneName, inc.flatLabel, floor(context, inc.floor), inc.assetName)
            .filter { it.isNotBlank() }
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
        return inc.locationNote?.takeIf { it.isNotBlank() } ?: context.getString(R.string.common_place_unknown)
    }

    /**
     * Resident place line (resident incidentPlace): the home flat is shown only when the report is
     * in the home tower, and a floor copied from home is hidden for reports elsewhere.
     */
    fun residentPlace(context: Context, inc: Incident, profile: Profile?): String {
        val homeZone = profile?.homeZoneId
        val atHome = homeZone == null || inc.zoneId == homeZone
        val copiedFloor = !atHome && inc.flatId != null && inc.flatId == profile?.flatId && inc.floor == profile?.flat?.floor
        val parts = mutableListOf<String>()
        inc.zoneName?.let(parts::add)
        if (inc.flatLabel != null && atHome) parts += context.getString(R.string.common_flat_n, inc.flatLabel)
        else if (inc.floor != null && !copiedFloor) residentFloor(context, inc.floor)?.let(parts::add)
        inc.assetName?.let(parts::add)
        inc.locationNote?.takeIf { it.isNotBlank() }?.let(parts::add)
        return parts.joinToString(" · ")
    }

    /** "Tower A · Flat A-1204" (+ " · Floor 12"). */
    fun homeLine(context: Context, profile: Profile?, withFloor: Boolean = false): String {
        val flat = profile?.flat ?: return ""
        val parts = mutableListOf<String>()
        flat.zone?.name?.let(parts::add)
        parts += context.getString(R.string.common_flat_n, flat.number)
        if (withFloor) residentFloor(context, flat.floor)?.let(parts::add)
        return parts.joinToString(" · ")
    }

    /** Resident wording (resident/parts/format.js floorLabel): "Ground floor" / "Floor 12". */
    private fun residentFloor(context: Context, floor: Int?): String? = when (floor) {
        null -> null
        0 -> context.getString(R.string.common_ground_floor_resident)
        else -> context.getString(R.string.common_floor_n, floor)
    }

    /** Short text for a badge: the reporter's own note wins, else the flags. */
    fun vulnerabilityShort(context: Context, v: Vulnerability): String {
        v.note?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return v.flags.joinToString(", ") { context.getString(it.labelRes()) }
    }

    /** Full text: flags + note, " · " separated. */
    fun vulnerabilityFull(context: Context, v: Vulnerability): String =
        (v.flags.map { context.getString(it.labelRes()) } + listOfNotNull(v.note?.trim()?.takeIf { it.isNotEmpty() }))
            .joinToString(" · ")

    /** "Maintenance · Lift, Power, Water"; admins → "All specialties". */
    fun specialties(context: Context, role: UserRole, specialties: List<IncidentType>): String {
        if (role == UserRole.Admin) return context.getString(R.string.common_all_specialties)
        if (specialties.isEmpty()) return context.getString(R.string.common_no_specialties)
        val names = specialties.joinToString(", ") { context.getString(it.shortLabelRes()) }
        val preset = when (specialties.toSet()) {
            Rules.PRESET_MAINTENANCE.toSet() -> R.string.common_preset_maintenance
            Rules.PRESET_SECURITY.toSet() -> R.string.common_preset_security
            else -> null
        }
        return preset?.let { "${context.getString(it)} · $names" } ?: names
    }
}

/**
 * Current time that ticks every [intervalMillis] (useNow). Use for "x min ago" and timers.
 * Ticks only while the screen is started (no wake-ups in the background) and catches up at once
 * when it comes back.
 */
@Composable
fun rememberNow(intervalMillis: Long = 30_000): State<Instant> {
    val state = remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(intervalMillis, lifecycle) {
        var first = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!first) state.value = Instant.now()
            first = false
            while (true) {
                delay(intervalMillis)
                state.value = Instant.now()
            }
        }
    }
    return state
}

@Composable
fun timeAgo(at: Instant?, now: Instant = Instant.now()): String = Format.timeAgo(LocalContext.current, at, now)

@Composable
fun placeLine(incident: Incident): String = Format.place(LocalContext.current, incident)

@Composable
fun peopleLabel(n: Int): String = Format.people(LocalContext.current, n)
