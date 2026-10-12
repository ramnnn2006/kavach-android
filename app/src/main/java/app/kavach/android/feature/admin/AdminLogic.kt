package app.kavach.android.feature.admin

import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.data.model.PowerState
import app.kavach.android.core.data.model.ComplianceItem
import app.kavach.android.core.data.model.ComplianceStatus
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Member
import app.kavach.android.core.data.model.ReportByAssetRow
import app.kavach.android.core.data.model.ReportByZoneRow
import app.kavach.android.core.data.model.ReportMonthlyRow
import app.kavach.android.core.data.model.ReportSummaryRow
import app.kavach.android.core.data.model.SafetyCheck
import app.kavach.android.core.data.model.SafetyCheckScope
import app.kavach.android.core.data.model.SafetyResponse
import app.kavach.android.core.data.model.Society
import app.kavach.android.core.data.model.TeamMember
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.model.ApprovedMember
import app.kavach.android.core.domain.Phones
import app.kavach.android.core.domain.Rules
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure helpers for the admin screens (no Android, no Compose), so they can be unit-tested.
// Mirrors screens/admin/parts/format.js and the small bits of logic inside each admin screen.

// ── Overview ────────────────────────────────────────────────────────────────

/** The numbers on the Overview dashboard (Overview.jsx). */
data class OverviewStats(
    val open: Int,
    val unclaimed: Int,
    val inProgress: Int,
    val escalated: Int,
    val today: Int,
    val avgResponseMinutes: Double?,
    val attention: List<Incident>,
)

/** "Unclaimed > N min" threshold: society.escalate_l2_after, rounded to whole minutes. */
fun escalateMinutes(society: Society?): Int =
    Math.round(Rules.intervalMinutes(society?.escalateL2After)).toInt()

/**
 * [active] = active incidents (the queue); [todays] = everything reported since midnight
 * (null while loading).
 */
fun overviewStats(active: List<Incident>, todays: List<Incident>?, escalateMinutes: Int, now: Instant): OverviewStats {
    val pending = active.filter { it.status == IncidentStatus.Pending }
    return OverviewStats(
        open = pending.size,
        unclaimed = pending.count { Rules.isUnclaimedFor(it, escalateMinutes.toDouble(), now) },
        inProgress = active.count { it.status != IncidentStatus.Pending },
        escalated = active.count { it.isEscalated },
        today = todays?.size ?: 0,
        avgResponseMinutes = todays?.let { Rules.avgResponseMinutes(it) },
        attention = active.filter { it.needsAttention }.sortedWith(Rules.byUrgency),
    )
}

/** How many zones are cut / rotating (Overview power row). */
data class ZonePowerCounts(val off: Int, val rotating: Int)

fun zonePowerCounts(zones: List<Zone>): ZonePowerCounts = ZonePowerCounts(
    off = zones.count { it.powerState == PowerState.Off },
    rotating = zones.count { it.powerState == PowerState.Rotating },
)

// ── Incidents ───────────────────────────────────────────────────────────────

enum class StatusChoice { Active, Closed, All }

data class IncidentFilterState(
    val status: StatusChoice = StatusChoice.Active,
    val types: Set<IncidentType> = emptySet(),
    val zoneId: String? = null,
) {
    val hasFilters: Boolean get() = types.isNotEmpty() || zoneId != null
}

/** Type filter is client-side (multi-select like the website); active lists sort by urgency. */
fun visibleIncidents(list: List<Incident>, filter: IncidentFilterState): List<Incident> {
    val filtered = if (filter.types.isEmpty()) list else list.filter { it.type in filter.types }
    return if (filter.status == StatusChoice.Active) filtered.sortedWith(Rules.byUrgency) else filtered
}

// ── Assign sheet / Team ─────────────────────────────────────────────────────

/** AssignSheet rank: on duty first, and covering the incident type before not covering it. */
fun assignRank(member: TeamMember, type: IncidentType): Int =
    (if (member.onDuty) 0 else 2) + (if (type in member.specialties) 0 else 1)

fun assignCandidates(team: List<TeamMember>, type: IncidentType): List<TeamMember> =
    team.filter { it.role == UserRole.Responder }
        .sortedWith(
            compareBy<TeamMember> { assignRank(it, type) }
                .thenBy { it.activeLoad }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.fullName },
        )

/** Team screen staff order: on duty first, responders before admins, then name. */
fun sortStaff(team: List<TeamMember>): List<TeamMember> =
    team.sortedWith(
        compareByDescending<TeamMember> { it.onDuty }
            .thenBy { if (it.role == UserRole.Responder) 0 else 1 }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.fullName },
    )

/** "A-1204 · Tower A" (memberFlat in format.js). */
fun memberFlatLabel(member: Member): String =
    listOfNotNull(member.flat?.number, member.flat?.zone?.name).joinToString(" · ")

/** Residents search: name, flat number or tower (case-insensitive). */
fun filterResidents(list: List<Member>, query: String): List<Member> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return list
    return list.filter { m ->
        m.fullName.lowercase().contains(q) ||
            (m.flat?.number ?: "").lowercase().contains(q) ||
            (m.flat?.zone?.name ?: "").lowercase().contains(q)
    }
}

/** Specialties in the website's TYPE_ORDER. */
fun orderedSpecialties(selected: Collection<IncidentType>): List<IncidentType> =
    Rules.TYPE_ORDER.filter { it in selected }

fun toggleSpecialty(selected: List<IncidentType>, type: IncidentType): List<IncidentType> =
    orderedSpecialties(if (type in selected) selected - type else selected + type)

fun presetActive(selected: List<IncidentType>, preset: List<IncidentType>): Boolean =
    selected.size == preset.size && preset.all { it in selected }

/** What goes to update_member for a role: residents keep no specialties. */
fun specialtiesForRole(role: UserRole, selected: List<IncidentType>): List<IncidentType> =
    if (role == UserRole.Resident) emptyList() else orderedSpecialties(selected)

// ── Approved members (new phone list) ───────────────────────────────────────

data class MemberFormValues(
    val name: String,
    val phone: String,
    val role: UserRole,
    val zoneId: String?,
    val flatNumber: String,
    val specialties: List<IncidentType>,
)

enum class MemberFormIssue { NameRequired, PhoneInvalid, FlatRequired, SpecialtyRequired, TowerRequired }

/** Longest name the server accepts (member_prepare). */
const val MEMBER_NAME_MAX = 100

/** First problem with the add/edit form, or null. The server re-checks everything. */
fun validateMemberForm(v: MemberFormValues): MemberFormIssue? = when {
    v.name.isBlank() -> MemberFormIssue.NameRequired
    Phones.normalize(v.phone) == null -> MemberFormIssue.PhoneInvalid
    v.role == UserRole.Resident && (v.zoneId.isNullOrBlank() || v.flatNumber.isBlank()) -> MemberFormIssue.FlatRequired
    // Staff may skip the flat, but a flat number alone (no tower) is refused by the server
    v.flatNumber.isNotBlank() && v.zoneId.isNullOrBlank() -> MemberFormIssue.TowerRequired
    v.role == UserRole.Responder && v.specialties.isEmpty() -> MemberFormIssue.SpecialtyRequired
    else -> null
}

/**
 * "A-1204" → "1204" (ApprovedMemberSheet flatDigits): list_approved_members returns the flat
 * label, but the RPCs take the number and rebuild the label from the tower. Sending the label
 * back would create a wrong flat ("A-A1204").
 */
fun flatDigits(label: String?): String = (label ?: "").replace(Regex("^[^-]*-"), "")

/** Search the approved list by name, phone digits, flat number or tower. */
fun filterApproved(list: List<ApprovedMember>, query: String): List<ApprovedMember> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return list
    val digits = q.filter(Char::isDigit)
    return list.filter { m ->
        m.fullName.lowercase().contains(q) ||
            (digits.isNotEmpty() && m.phone.filter(Char::isDigit).contains(digits)) ||
            (m.flatNumber ?: "").lowercase().contains(q) ||
            (m.zoneName ?: "").lowercase().contains(q)
    }
}

// ── Society: assets, compliance, contacts ───────────────────────────────────

data class AssetGroup(val key: String, val zoneName: String?, val rank: Int, val items: List<Asset>)

/** Assets grouped by zone, in the zones' display order; no-zone assets last. */
fun groupAssets(assets: List<Asset>, zones: List<Zone>): List<AssetGroup> {
    val order = zones.withIndex().associate { it.value.id to it.index }
    return assets.groupBy { it.zoneId ?: "none" }
        .map { (key, items) ->
            AssetGroup(
                key = key,
                zoneName = items.firstOrNull()?.zone?.name,
                rank = order[items.firstOrNull()?.zoneId] ?: 999,
                items = items,
            )
        }
        .sortedBy { it.rank }
}

fun assetsNotOk(assets: List<Asset>): Int = assets.count { it.state != AssetState.Ok }

val COMPLIANCE_GROUP_ORDER = listOf(ComplianceStatus.Overdue, ComplianceStatus.DueSoon, ComplianceStatus.Ok)

fun complianceGroups(list: List<ComplianceItem>): List<Pair<ComplianceStatus, List<ComplianceItem>>> =
    COMPLIANCE_GROUP_ORDER.map { status -> status to list.filter { it.complianceStatus == status } }
        .filter { it.second.isNotEmpty() }

/** Wording choice for a check's due line (CompliancePanel dueText). */
sealed interface DueKind {
    data object OverdueOneDay : DueKind
    data class OverdueDays(val n: Int) : DueKind
    data object Today : DueKind
    data object Tomorrow : DueKind
    data class InDays(val n: Int) : DueKind
}

fun dueKind(item: ComplianceItem): DueKind? {
    val d = item.daysLeft
    return when {
        d == -1 -> DueKind.OverdueOneDay
        d < 0 -> DueKind.OverdueDays(-d)
        d == 0 -> DueKind.Today
        d == 1 -> DueKind.Tomorrow
        item.complianceStatus == ComplianceStatus.DueSoon -> DueKind.InDays(d)
        else -> null
    }
}

enum class RenewalKind { Amc, Licence }

data class Renewal(val asset: Asset, val kind: RenewalKind, val days: Long, val date: LocalDate) {
    val key: String get() = "${asset.id}-${kind.name}"
}

const val RENEWAL_WINDOW_DAYS = 30L

/** AMC / lift licence ends within [windowDays] (or already past), soonest first. */
fun renewals(assets: List<Asset>, today: LocalDate, windowDays: Long = RENEWAL_WINDOW_DAYS): List<Renewal> {
    val out = mutableListOf<Renewal>()
    for (a in assets) {
        a.amcExpiresOn?.let { date ->
            val days = Rules.daysUntil(date, today)
            if (days <= windowDays) out += Renewal(a, RenewalKind.Amc, days, date)
        }
        a.licenceExpiresOn?.let { date ->
            val days = Rules.daysUntil(date, today)
            if (days <= windowDays) out += Renewal(a, RenewalKind.Licence, days, date)
        }
    }
    return out.sortedBy { it.days }
}

val CONTACT_PHONE_REGEX = Regex("^[0-9+()\\-\\s]{3,20}$")

fun isValidContactPhone(text: String): Boolean = CONTACT_PHONE_REGEX.matches(text.trim())

// ── Notices ─────────────────────────────────────────────────────────────────

const val NOTICE_TITLE_MAX = 160
const val NOTICE_BODY_MAX = 4000

/** A notice with an expiry date disappears after the end of that (local) day. */
fun endOfDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Instant =
    date.atTime(23, 59, 59).atZone(zone).toInstant()

fun expiryDate(at: Instant?, zone: ZoneId = ZoneId.systemDefault()): LocalDate? = at?.atZone(zone)?.toLocalDate()

// ── Safety Check ────────────────────────────────────────────────────────────

const val SAFETY_MESSAGE_MAX = 300

/** Residents in scope who haven't answered; people needing extra help first, then by name. */
fun unansweredResidents(members: List<Member>, responses: List<SafetyResponse>, check: SafetyCheck): List<Member> {
    val answered = responses.mapNotNull { it.profileId }.toSet()
    return members
        .filter { m ->
            m.role == UserRole.Resident && m.id !in answered &&
                (check.scope == SafetyCheckScope.Society || m.flat?.zone?.name == check.zone?.name)
        }
        .sortedWith(
            compareByDescending<Member> { it.vulnerability.any }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.fullName },
        )
}

/** Share of [total] taken by [n], 0..1. */
fun share(n: Long, total: Long): Float =
    if (total <= 0) 0f else (n.toFloat() / total.toFloat()).coerceIn(0f, 1f)

// ── Reports ─────────────────────────────────────────────────────────────────

val REPORT_PERIODS = listOf(7, 30, 90)
const val REPORT_MONTHS = 6

data class ReportTotals(val total: Long, val resolved: Long, val escalated: Long, val cancelled: Long)

fun reportTotals(summary: List<ReportSummaryRow>): ReportTotals = ReportTotals(
    total = summary.sumOf { it.total },
    resolved = summary.sumOf { it.resolved },
    escalated = summary.sumOf { it.escalated },
    cancelled = summary.sumOf { it.cancelled },
)

/** Equipment with more than one incident in the period, in the server's order (first 5). */
fun repeatProblems(rows: List<ReportByAssetRow>, limit: Int = 5): List<ReportByAssetRow> =
    rows.filter { it.incidents > 1 }.take(limit)

fun busyZones(rows: List<ReportByZoneRow>): List<ReportByZoneRow> = rows.filter { it.incidents > 0 }

/** One month of the stacked chart. */
data class MonthBar(val month: YearMonth, val counts: Map<IncidentType, Long>) {
    val total: Long get() = counts.values.sum()
    fun count(type: IncidentType): Long = counts[type] ?: 0L
}

/** The last [months] months ending with the month of [today], oldest first, zeros included. */
fun monthBars(rows: List<ReportMonthlyRow>, months: Int, today: LocalDate): List<MonthBar> {
    val current = YearMonth.from(today)
    val byMonth = rows.groupBy { YearMonth.from(it.month) }
    return (months - 1 downTo 0).map { back ->
        val ym = current.minusMonths(back.toLong())
        val counts = byMonth[ym].orEmpty()
            .groupBy { it.type }
            .mapValues { (_, list) -> list.sumOf { it.incidents } }
            .filterValues { it > 0 }
        MonthBar(ym, counts)
    }
}

/** Types that appear in the chart, in the stacking order (legend + table columns). */
fun presentTypes(bars: List<MonthBar>): List<IncidentType> =
    Rules.CHART_TYPE_ORDER.filter { type -> bars.any { it.count(type) > 0 } }

private val monthShort = DateTimeFormatter.ofPattern("MMM", Locale.forLanguageTag("en-IN"))
private val monthLong = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("en-IN"))

fun monthShortLabel(month: YearMonth): String = month.format(monthShort)
fun monthLongLabel(month: YearMonth): String = month.format(monthLong)

// ── Command board ───────────────────────────────────────────────────────────

/** Open (pending) alerts and in-progress ones, both most urgent first. */
fun boardLists(active: List<Incident>): Pair<List<Incident>, List<Incident>> {
    val open = active.filter { it.status == IncidentStatus.Pending }.sortedWith(Rules.byUrgency)
    val progress = active.filter { it.status != IncidentStatus.Pending }.sortedWith(Rules.byUrgency)
    return open to progress
}
