package app.kavach.android.feature.responder

import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetCheckKind
import app.kavach.android.core.data.model.AssetKind
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.data.model.ComplianceItem
import app.kavach.android.core.data.model.ComplianceStatus
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.model.ZoneRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ResponderLogicTest {
    private val t0 = Instant.parse("2026-10-10T08:00:00Z")

    private fun incident(
        id: String,
        status: IncidentStatus = IncidentStatus.Pending,
        assignedTo: String? = null,
        urgency: Int = 50,
        createdAt: Instant = t0,
        updatedAt: Instant? = null,
        resolvedAt: Instant? = null,
        type: IncidentType = IncidentType.Lift,
    ) = Incident(
        id = id,
        type = type,
        status = status,
        assignedTo = assignedTo,
        urgencyScore = urgency,
        createdAt = createdAt,
        updatedAt = updatedAt,
        resolvedAt = resolvedAt,
    )

    private fun check(id: String, status: ComplianceStatus, due: String, kind: AssetCheckKind, assetKind: AssetKind? = null) =
        ComplianceItem(
            id = id,
            kind = kind,
            title = id,
            dueAt = LocalDate.parse(due),
            assetKind = assetKind,
            complianceStatus = status,
        )

    private fun asset(
        id: String,
        kind: AssetKind,
        name: String = id,
        zone: ZoneRef? = null,
        state: AssetState = AssetState.Ok,
        amc: LocalDate? = null,
        licence: LocalDate? = null,
    ) = Asset(id = id, kind = kind, name = name, zone = zone, state = state, amcExpiresOn = amc, licenceExpiresOn = licence)

    private val maintenance = Profile(id = "me", role = UserRole.Responder, specialties = listOf(IncidentType.Lift, IncidentType.Power, IncidentType.Water))
    private val guard = Profile(id = "g", role = UserRole.Responder, specialties = listOf(IncidentType.Fire, IncidentType.Medical, IncidentType.Security))
    private val admin = Profile(id = "a", role = UserRole.Admin)

    // ── Alerts ──

    @Test fun queueSplitsMineFromOpenAndSortsByUrgencyThenAge() {
        val rows = listOf(
            incident("open-low", urgency = 20),
            incident("open-high", urgency = 90),
            incident("open-old", urgency = 20, createdAt = t0.minusSeconds(600)),
            incident("mine-enroute", IncidentStatus.EnRoute, assignedTo = "me", urgency = 40),
            incident("mine-claimed", IncidentStatus.Acknowledged, assignedTo = "me", urgency = 80),
            incident("theirs", IncidentStatus.Acknowledged, assignedTo = "someone-else"),
            incident("resolved", IncidentStatus.Resolved, assignedTo = "me"),
        )
        val split = splitQueue(rows, "me")
        assertEquals(listOf("mine-claimed", "mine-enroute"), split.mine.map { it.id })
        assertEquals(listOf("open-high", "open-old", "open-low"), split.open.map { it.id })
    }

    @Test fun alertAssignedToMeIsMineEvenIfStillPending() {
        val rows = listOf(incident("a", IncidentStatus.Pending, assignedTo = "me"), incident("b"))
        val split = splitQueue(rows, "me")
        assertEquals(listOf("a"), split.mine.map { it.id })
        assertEquals(listOf("b"), split.open.map { it.id })
    }

    @Test fun queueWithoutKnownUserHasNoMineButKeepsOpen() {
        val split = splitQueue(listOf(incident("a"), incident("b", IncidentStatus.Acknowledged, assignedTo = "x")), null)
        assertTrue(split.mine.isEmpty())
        assertEquals(listOf("a"), split.open.map { it.id })
    }

    @Test fun claimedPatchMovesAlertFromOpenToMine() {
        val rows = listOf(incident("a"), incident("b"))
        val claimed = incident("a", IncidentStatus.Acknowledged, assignedTo = "me")
        val split = splitQueue(applyPatches(rows, mapOf("a" to claimed)), "me")
        assertEquals(listOf("a"), split.mine.map { it.id })
        assertEquals(listOf("b"), split.open.map { it.id })
    }

    @Test fun resolvedPatchRemovesAlertFromQueue() {
        val rows = listOf(incident("a", IncidentStatus.OnScene, assignedTo = "me"))
        val resolved = incident("a", IncidentStatus.Resolved, assignedTo = "me")
        assertTrue(splitQueue(applyPatches(rows, mapOf("a" to resolved)), "me").isEmpty)
    }

    @Test fun stalePatchDoesNotOverwriteNewerRealtimeRow() {
        val newer = incident("a", IncidentStatus.EnRoute, assignedTo = "me", updatedAt = t0.plusSeconds(60))
        val stale = incident("a", IncidentStatus.Acknowledged, assignedTo = "me", updatedAt = t0)
        assertEquals(IncidentStatus.EnRoute, applyPatches(listOf(newer), mapOf("a" to stale)).single().status)
        val fresh = incident("a", IncidentStatus.OnScene, assignedTo = "me", updatedAt = t0.plusSeconds(120))
        assertEquals(IncidentStatus.OnScene, applyPatches(listOf(newer), mapOf("a" to fresh)).single().status)
    }

    @Test fun patchesForRowsNotInTheQueueAreIgnored() {
        val rows = listOf(incident("a"))
        assertEquals(rows, applyPatches(rows, mapOf("zzz" to incident("zzz"))))
    }

    @Test fun fetchedRowOfTheSameVersionWinsOverThePatch() {
        val fetched = incident("a", IncidentStatus.Acknowledged, assignedTo = "me", urgency = 70, updatedAt = t0)
        val patch = incident("a", IncidentStatus.Acknowledged, assignedTo = "me", urgency = 50, updatedAt = t0)
        assertEquals(70, applyPatches(listOf(fetched), mapOf("a" to patch)).single().urgencyScore)
    }

    @Test fun prunePatchesDropsCaughtUpAndGoneRowsButKeepsNewerPatches() {
        val stale = incident("a", IncidentStatus.Pending, updatedAt = t0)
        val caughtUp = incident("b", IncidentStatus.Acknowledged, assignedTo = "me", updatedAt = t0.plusSeconds(30))
        val rows = listOf(stale, caughtUp)
        val patches = mapOf(
            // claim returned after this fetch started: still needed
            "a" to incident("a", IncidentStatus.Acknowledged, assignedTo = "me", updatedAt = t0.plusSeconds(10)),
            // the fetch already has this version
            "b" to incident("b", IncidentStatus.Acknowledged, assignedTo = "me", updatedAt = t0.plusSeconds(30)),
            // resolved and gone from the active queue
            "c" to incident("c", IncidentStatus.Resolved, assignedTo = "me", updatedAt = t0.plusSeconds(20)),
        )
        assertEquals(setOf("a"), prunePatches(rows, patches).keys)
        assertTrue(prunePatches(rows, emptyMap()).isEmpty())
    }

    // ── Checks ──

    @Test fun checksAreFilteredBySpecialtyAndGroupedBySoonestDue() {
        val rows = listOf(
            check("ard", ComplianceStatus.Overdue, "2026-09-20", AssetCheckKind.LiftArdBattery),
            check("fuel-late", ComplianceStatus.Overdue, "2026-10-01", AssetCheckKind.DgFuel),
            check("extinguisher", ComplianceStatus.DueSoon, "2026-10-15", AssetCheckKind.FireExtinguisherExpiry),
            check("pump", ComplianceStatus.Ok, "2027-01-01", AssetCheckKind.PumpService),
            check("other", ComplianceStatus.Ok, "2027-02-01", AssetCheckKind.Other),
        )
        val sections = checkSections(rows, maintenance)
        assertEquals(listOf(ComplianceStatus.Overdue, ComplianceStatus.DueSoon, ComplianceStatus.Ok), sections.map { it.status })
        assertEquals(listOf("ard", "fuel-late"), sections[0].items.map { it.id })
        assertTrue(sections[1].items.isEmpty())
        assertEquals(listOf("pump"), sections[2].items.map { it.id })
        val data = ChecksData(sections)
        assertEquals(3, data.total)
        assertEquals(2, data.attention)
    }

    @Test fun assetKindDecidesCheckSpecialtyBeforeCheckKind() {
        // An "other" check on a fire pump belongs to the fire team
        val rows = listOf(check("x", ComplianceStatus.Ok, "2027-01-01", AssetCheckKind.Other, AssetKind.FirePump))
        assertEquals(1, ChecksData(checkSections(rows, guard)).total)
        assertEquals(0, ChecksData(checkSections(rows, maintenance)).total)
    }

    @Test fun adminsSeeEveryCheckIncludingUntypedOnes() {
        val rows = listOf(
            check("a", ComplianceStatus.Ok, "2027-01-01", AssetCheckKind.Other),
            check("b", ComplianceStatus.DueSoon, "2026-10-12", AssetCheckKind.LiftAmc),
        )
        assertEquals(2, ChecksData(checkSections(rows, admin)).total)
    }

    @Test fun allOkMeansNoAttention() {
        val rows = listOf(check("a", ComplianceStatus.Ok, "2027-01-01", AssetCheckKind.LiftAmc))
        val data = ChecksData(checkSections(rows, maintenance))
        assertEquals(1, data.total)
        assertEquals(0, data.attention)
    }

    @Test fun dueTextWordsTheDistance() {
        assertEquals(DueText.Overdue(1), dueText(-1))
        assertEquals(DueText.Overdue(12), dueText(-12))
        assertEquals(DueText.Today, dueText(0))
        assertEquals(DueText.Tomorrow, dueText(1))
        assertEquals(DueText.InDays(9), dueText(9))
    }

    // ── Assets ──

    @Test fun onlyCoveredAssetsAreListed() {
        val rows = listOf(
            asset("lift", AssetKind.Lift),
            asset("dg", AssetKind.Dg),
            asset("fire", AssetKind.FirePump),
            asset("sump", AssetKind.Sump),
        )
        assertEquals(listOf("lift", "dg", "sump"), coveredAssets(rows, maintenance).map { it.id })
        assertEquals(listOf("fire"), coveredAssets(rows, guard).map { it.id })
        assertEquals(4, coveredAssets(rows, admin).size)
    }

    @Test fun assetsAreGroupedByZoneOrderWithUnzonedLast() {
        val a = ZoneRef("za", "Tower A", sortOrder = 1)
        val b = ZoneRef("zb", "Tower B", sortOrder = 2)
        val rows = listOf(
            asset("l1", AssetKind.Lift, zone = b),
            asset("loose", AssetKind.Dg),
            asset("l2", AssetKind.Lift, zone = a),
            asset("l3", AssetKind.Lift, zone = b),
        )
        val groups = groupAssets(rows)
        assertEquals(listOf("Tower A", "Tower B", null), groups.map { it.name })
        assertEquals(listOf("l1", "l3"), groups[1].items.map { it.id })
        assertEquals("none", groups[2].key)
    }

    @Test fun assetsDataCountsDownAndDegradedAsAttention() {
        val rows = listOf(
            asset("a", AssetKind.Lift, state = AssetState.Down),
            asset("b", AssetKind.Lift, state = AssetState.Degraded),
            asset("c", AssetKind.Lift, state = AssetState.Maintenance),
            asset("d", AssetKind.Lift, state = AssetState.Ok),
            asset("e", AssetKind.FirePump, state = AssetState.Down),
        )
        val data = assetsData(rows, maintenance)
        assertEquals(2, data.attention)
        assertNull(data.find("e"))
        assertEquals("a", data.find("a")?.id)
        assertTrue(assetsData(emptyList(), maintenance).isEmpty)
    }

    @Test fun expiryWarningsShowWithin30DaysOrWhenPast() {
        val today = LocalDate.parse("2026-10-10")
        val none = asset("n", AssetKind.Lift, amc = today.plusDays(31), licence = null)
        assertTrue(expiryWarnings(none, today).isEmpty())

        val soon = asset("s", AssetKind.Lift, amc = today.plusDays(30), licence = today.minusDays(1))
        val warnings = expiryWarnings(soon, today)
        assertEquals(listOf(ExpiryKind.Amc, ExpiryKind.Licence), warnings.map { it.kind })
        assertFalse(warnings[0].expired)
        assertTrue(warnings[1].expired)

        assertFalse(expiryWarnings(asset("t", AssetKind.Lift, licence = today), today).single().expired)
    }

    // ── History ──

    @Test fun weekStartsOnMonday() {
        val saturday = Instant.parse("2026-10-10T12:00:00Z")
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), startOfWeek(saturday, ZoneOffset.UTC))
        val monday = Instant.parse("2026-10-05T00:30:00Z")
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), startOfWeek(monday, ZoneOffset.UTC))
        val sunday = Instant.parse("2026-10-11T23:00:00Z")
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), startOfWeek(sunday, ZoneOffset.UTC))
    }

    @Test fun historyStatsCountThisWeekAndAverageResolveTime() {
        val now = Instant.parse("2026-10-10T12:00:00Z")
        val thisWeek = incident(
            "w", IncidentStatus.Resolved, createdAt = Instant.parse("2026-10-08T10:00:00Z"),
            resolvedAt = Instant.parse("2026-10-08T10:10:00Z"),
        )
        val lastWeek = incident(
            "l", IncidentStatus.Resolved, createdAt = Instant.parse("2026-10-01T10:00:00Z"),
            resolvedAt = Instant.parse("2026-10-01T10:30:00Z"),
        )
        val cancelled = incident("c", IncidentStatus.Cancelled, createdAt = Instant.parse("2026-10-09T10:00:00Z"))
        val stats = historyStats(listOf(thisWeek, lastWeek, cancelled), now, ZoneOffset.UTC)
        assertEquals(1, stats.resolvedThisWeek)
        assertEquals(20L, stats.avgResolveMinutes)
        assertEquals(3, stats.total)
    }

    @Test fun historyStatsWithNothingResolvedHasNoAverage() {
        val stats = historyStats(listOf(incident("c", IncidentStatus.Cancelled)), t0, ZoneOffset.UTC)
        assertNull(stats.avgResolveMinutes)
        assertEquals(0, stats.resolvedThisWeek)
        assertEquals(1, stats.total)
    }

    @Test fun resolveMinutesOnlyForResolvedIncidents() {
        val resolved = incident("r", IncidentStatus.Resolved, createdAt = t0, resolvedAt = t0.plusSeconds(14 * 60))
        assertEquals(14L, resolveMinutes(resolved))
        assertNull(resolveMinutes(incident("c", IncidentStatus.Cancelled)))
        assertNull(resolveMinutes(incident("x", IncidentStatus.Resolved)))
    }
}
