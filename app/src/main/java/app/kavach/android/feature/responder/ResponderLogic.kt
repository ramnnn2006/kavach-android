package app.kavach.android.feature.responder

import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.data.model.ComplianceItem
import app.kavach.android.core.data.model.ComplianceStatus
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.domain.Rules
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

// Pure helpers behind the responder screens (no Android), so they can be unit tested.
// Mirrors the useMemo blocks in src/screens/responder/*.jsx.

// ── Alerts ──────────────────────────────────────────────────────────────────

/** The live queue split into "Assigned to you" and "Open", both most urgent first. */
data class QueueSplit(val mine: List<Incident>, val open: List<Incident>) {
    val isEmpty: Boolean get() = mine.isEmpty() && open.isEmpty()
}

/**
 * Mine = assigned to [me] and already claimed (not pending) and still active.
 * Open = pending and not assigned to me.
 */
fun splitQueue(rows: List<Incident>, me: String?): QueueSplit {
    val mine = rows
        .filter { me != null && it.assignedTo == me && it.status.isActive }
        .sortedWith(Rules.byUrgency)
    val open = rows
        .filter { it.status == IncidentStatus.Pending && (me == null || it.assignedTo != me) }
        .sortedWith(Rules.byUrgency)
    return QueueSplit(mine, open)
}

/**
 * Applies the row an RPC returned right away (merge()), until Realtime delivers the same or a
 * newer version: once the fetched row has caught up, the server's row wins.
 */
fun applyPatches(rows: List<Incident>, patches: Map<String, Incident>): List<Incident> {
    if (patches.isEmpty()) return rows
    return rows.map { row ->
        val patch = patches[row.id] ?: return@map row
        if (rowCaughtUp(row, patch)) row else patch
    }
}

/**
 * Patches still needed after a successful fetch of [rows]: drops those whose incident left the
 * queue (closed, or no longer visible) and those the fetched row has caught up with, so the map
 * doesn't grow for the life of the screen.
 */
fun prunePatches(rows: List<Incident>, patches: Map<String, Incident>): Map<String, Incident> {
    if (patches.isEmpty()) return patches
    val byId = rows.associateBy { it.id }
    return patches.filter { (id, patch) ->
        val row = byId[id]
        row != null && !rowCaughtUp(row, patch)
    }
}

/** The fetched [row] is at least as new as the RPC's [patch] (updated_at is bumped on every update). */
private fun rowCaughtUp(row: Incident, patch: Incident): Boolean {
    val rowAt = row.updatedAt ?: return false
    val patchAt = patch.updatedAt ?: return false
    return !rowAt.isBefore(patchAt)
}

// ── Checks ──────────────────────────────────────────────────────────────────

/** One group of the Checks screen (Overdue / Due soon / Up to date). */
data class CheckSection(val status: ComplianceStatus, val items: List<ComplianceItem>)

/** The three sections, in website order, holding only the checks [profile] covers, soonest due first. */
fun checkSections(rows: List<ComplianceItem>, profile: Profile?): List<CheckSection> {
    val mine = rows.filter { Rules.covers(profile, Rules.checkSpecialty(it)) }
    return listOf(ComplianceStatus.Overdue, ComplianceStatus.DueSoon, ComplianceStatus.Ok).map { status ->
        CheckSection(status, mine.filter { it.complianceStatus == status }.sortedBy { it.dueAt })
    }
}

/** Everything the Checks screen shows. */
data class ChecksData(val sections: List<CheckSection>) {
    val total: Int get() = sections.sumOf { it.items.size }

    /** Overdue + due soon. */
    val attention: Int get() = sections.filter { it.status != ComplianceStatus.Ok }.sumOf { it.items.size }
}

/** How to word the distance to a due date (dueText() on the website). */
sealed interface DueText {
    data class Overdue(val days: Int) : DueText
    data object Today : DueText
    data object Tomorrow : DueText
    data class InDays(val days: Int) : DueText
}

fun dueText(daysLeft: Long): DueText = when {
    daysLeft < 0 -> DueText.Overdue((-daysLeft).toInt())
    daysLeft == 0L -> DueText.Today
    daysLeft == 1L -> DueText.Tomorrow
    else -> DueText.InDays(daysLeft.toInt())
}

// ── Assets ──────────────────────────────────────────────────────────────────

/** AMC / licence warnings show from this many days before expiry (WARN_DAYS). */
const val EXPIRY_WARN_DAYS = 30L

/** Assets [profile] covers (admins: all). */
fun coveredAssets(rows: List<Asset>, profile: Profile?): List<Asset> =
    rows.filter { Rules.covers(profile, Rules.assetSpecialty(it)) }

/** One tower / zone block on the Assets screen. */
data class AssetGroup(val key: String, val name: String?, val order: Int, val items: List<Asset>)

/** Groups by zone, zones in their sort order then name; assets keep the order they came in (kind, name). */
fun groupAssets(assets: List<Asset>): List<AssetGroup> {
    val byZone = LinkedHashMap<String, MutableList<Asset>>()
    for (asset in assets) byZone.getOrPut(asset.zone?.id ?: "none") { mutableListOf() }.add(asset)
    return byZone.map { (key, items) ->
        val zone = items.first().zone
        AssetGroup(key, zone?.name, zone?.sortOrder ?: 9999, items)
    }.sortedWith(compareBy<AssetGroup> { it.order }.thenBy { it.name.orEmpty().lowercase() })
}

/** Down or partly working. */
fun assetsNeedingAttention(assets: List<Asset>): Int =
    assets.count { it.state == AssetState.Down || it.state == AssetState.Degraded }

/** Everything the Assets screen shows. */
data class AssetsData(val groups: List<AssetGroup>, val attention: Int) {
    val isEmpty: Boolean get() = groups.isEmpty()
    fun find(id: String): Asset? = groups.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.id == id }
}

fun assetsData(rows: List<Asset>, profile: Profile?): AssetsData {
    val mine = coveredAssets(rows, profile)
    return AssetsData(groupAssets(mine), assetsNeedingAttention(mine))
}

enum class ExpiryKind { Amc, Licence }

data class ExpiryWarning(val kind: ExpiryKind, val date: LocalDate, val expired: Boolean)

/** AMC / licence dates that are past or within [EXPIRY_WARN_DAYS] days. */
fun expiryWarnings(asset: Asset, today: LocalDate): List<ExpiryWarning> = buildList {
    asset.amcExpiresOn?.let { date ->
        val days = Rules.daysUntil(date, today)
        if (days <= EXPIRY_WARN_DAYS) add(ExpiryWarning(ExpiryKind.Amc, date, days < 0))
    }
    asset.licenceExpiresOn?.let { date ->
        val days = Rules.daysUntil(date, today)
        if (days <= EXPIRY_WARN_DAYS) add(ExpiryWarning(ExpiryKind.Licence, date, days < 0))
    }
}

// ── History ─────────────────────────────────────────────────────────────────

/** Header numbers of the History screen. */
data class HistoryStats(val resolvedThisWeek: Int, val avgResolveMinutes: Long?, val total: Int)

/** Monday 00:00 of the week containing [now], in [zone]. */
fun startOfWeek(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant =
    now.atZone(zone).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(zone).toInstant()

/** Created → resolved minutes, or null when the incident wasn't resolved. */
fun resolveMinutes(incident: Incident): Long? {
    if (incident.status != IncidentStatus.Resolved) return null
    val resolvedAt = incident.resolvedAt ?: return null
    return Rules.minutesBetween(incident.createdAt, resolvedAt)
}

fun historyStats(rows: List<Incident>, now: Instant, zone: ZoneId = ZoneId.systemDefault()): HistoryStats {
    val weekStart = startOfWeek(now, zone)
    val resolved = rows.filter { it.status == IncidentStatus.Resolved && it.resolvedAt != null }
    val durations = resolved.mapNotNull(::resolveMinutes)
    return HistoryStats(
        resolvedThisWeek = resolved.count { i -> i.resolvedAt?.let { !it.isBefore(weekStart) } == true },
        avgResolveMinutes = if (durations.isEmpty()) null else Math.round(durations.average()),
        total = rows.size,
    )
}
