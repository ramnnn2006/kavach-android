package app.kavach.android.feature.admin

import app.kavach.android.core.data.model.ApprovedMember
import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetKind
import app.kavach.android.core.data.model.ComplianceItem
import app.kavach.android.core.data.model.ComplianceStatus
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Member
import app.kavach.android.core.data.model.MemberFlat
import app.kavach.android.core.data.model.NameOnly
import app.kavach.android.core.data.model.ReportByAssetRow
import app.kavach.android.core.data.model.ReportMonthlyRow
import app.kavach.android.core.data.model.ReportSummaryRow
import app.kavach.android.core.data.model.SafetyCheck
import app.kavach.android.core.data.model.SafetyCheckScope
import app.kavach.android.core.data.model.SafetyResponse
import app.kavach.android.core.data.model.SafetyResponseStatus
import app.kavach.android.core.data.model.Society
import app.kavach.android.core.data.model.TeamMember
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.model.Vulnerability
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.model.ZoneRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class AdminLogicTest {
    private val now: Instant = Instant.parse("2026-10-10T10:00:00Z")

    private fun incident(
        id: String,
        status: IncidentStatus = IncidentStatus.Pending,
        urgency: Int = 50,
        minutesAgo: Long = 1,
        escalation: Int = 1,
        vulnerable: Boolean = false,
        type: IncidentType = IncidentType.Lift,
        acknowledgedMinutesAfter: Long? = null,
        assignedTo: String? = null,
    ): Incident {
        val created = now.minusSeconds(minutesAgo * 60)
        return Incident(
            id = id,
            type = type,
            status = status,
            urgencyScore = urgency,
            escalationLevel = escalation,
            vulnerable = vulnerable,
            createdAt = created,
            acknowledgedAt = acknowledgedMinutesAfter?.let { created.plusSeconds(it * 60) },
            assignedTo = assignedTo,
        )
    }

    private fun team(
        id: String,
        name: String,
        role: UserRole = UserRole.Responder,
        onDuty: Boolean = false,
        load: Int = 0,
        specialties: List<IncidentType> = emptyList(),
    ) = TeamMember(id = id, fullName = name, role = role, onDuty = onDuty, activeLoad = load, specialties = specialties)

    private fun member(
        id: String,
        name: String,
        role: UserRole = UserRole.Resident,
        flat: String? = null,
        tower: String? = null,
        vulnerability: Vulnerability = Vulnerability(),
    ) = Member(
        id = id,
        fullName = name,
        role = role,
        vulnerability = vulnerability,
        flat = flat?.let { MemberFlat(number = it, zone = tower?.let(::NameOnly)) },
    )

    // ── Overview ────────────────────────────────────────────────────────────

    @Test fun overviewCountsOpenUnclaimedAndInProgress() {
        val active = listOf(
            incident("a", IncidentStatus.Pending, minutesAgo = 5),
            incident("b", IncidentStatus.Pending, minutesAgo = 1),
            incident("c", IncidentStatus.EnRoute, minutesAgo = 9, escalation = 2),
            incident("d", IncidentStatus.OnScene, minutesAgo = 20),
        )
        val stats = overviewStats(active, todays = null, escalateMinutes = 2, now = now)
        assertEquals(2, stats.open)
        assertEquals(1, stats.unclaimed)
        assertEquals(2, stats.inProgress)
        assertEquals(1, stats.escalated)
        assertEquals(0, stats.today)
        assertNull(stats.avgResponseMinutes)
    }

    @Test fun overviewAverageResponseUsesTodaysAcknowledgedOnly() {
        val todays = listOf(
            incident("a", IncidentStatus.Resolved, acknowledgedMinutesAfter = 2),
            incident("b", IncidentStatus.Resolved, acknowledgedMinutesAfter = 4),
            incident("c", IncidentStatus.Pending),
        )
        val stats = overviewStats(emptyList(), todays, escalateMinutes = 2, now = now)
        assertEquals(3, stats.today)
        assertEquals(3.0, stats.avgResponseMinutes!!, 0.001)
    }

    @Test fun attentionListIsOpenEscalatedOrVulnerableMostUrgentFirst() {
        val active = listOf(
            incident("calm", IncidentStatus.OnScene, urgency = 90),
            incident("open-low", IncidentStatus.Pending, urgency = 20),
            incident("escalated", IncidentStatus.EnRoute, urgency = 60, escalation = 2),
            incident("vulnerable", IncidentStatus.Acknowledged, urgency = 80, vulnerable = true),
        )
        val ids = overviewStats(active, null, 2, now).attention.map { it.id }
        assertEquals(listOf("vulnerable", "escalated", "open-low"), ids)
    }

    @Test fun escalateMinutesReadsSocietyIntervalWithFallback() {
        assertEquals(2, escalateMinutes(null))
        assertEquals(5, escalateMinutes(Society(id = "s", name = "S", escalateL2After = "00:05:00")))
        assertEquals(2, escalateMinutes(Society(id = "s", name = "S", escalateL2After = "garbage")))
    }

    // ── Assign sheet and team ───────────────────────────────────────────────

    @Test fun assignCandidatesPutOnDutyCoveringResponderFirst() {
        val list = listOf(
            team("off-cover", "Asha", onDuty = false, specialties = listOf(IncidentType.Lift)),
            team("on-other", "Bala", onDuty = true, specialties = listOf(IncidentType.Fire)),
            team("on-cover-busy", "Chitra", onDuty = true, load = 3, specialties = listOf(IncidentType.Lift)),
            team("on-cover-free", "Dev", onDuty = true, load = 1, specialties = listOf(IncidentType.Lift)),
            team("admin", "Esha", role = UserRole.Admin, onDuty = true),
        )
        val ids = assignCandidates(list, IncidentType.Lift).map { it.id }
        assertEquals(listOf("on-cover-free", "on-cover-busy", "on-other", "off-cover"), ids)
    }

    @Test fun staffSortsOnDutyFirstThenRespondersThenName() {
        val list = listOf(
            team("1", "Zed", UserRole.Admin, onDuty = true),
            team("2", "Amy", UserRole.Responder, onDuty = false),
            team("3", "Bob", UserRole.Responder, onDuty = true),
            team("4", "Cal", UserRole.Responder, onDuty = true),
        )
        assertEquals(listOf("3", "4", "1", "2"), sortStaff(list).map { it.id })
    }

    @Test fun residentSearchMatchesNameFlatAndTower() {
        val list = listOf(
            member("1", "Chloe Ramesh", flat = "A-1204", tower = "Tower A"),
            member("2", "Dev Kumar", flat = "B-0502", tower = "Tower B"),
        )
        assertEquals(2, filterResidents(list, "  ").size)
        assertEquals(listOf("1"), filterResidents(list, "chloe").map { it.id })
        assertEquals(listOf("2"), filterResidents(list, "b-05").map { it.id })
        assertEquals(listOf("2"), filterResidents(list, "tower b").map { it.id })
        assertTrue(filterResidents(list, "zzz").isEmpty())
        assertEquals("A-1204 · Tower A", memberFlatLabel(list[0]))
        assertEquals("", memberFlatLabel(member("3", "No Flat")))
    }

    @Test fun specialtyHelpersKeepWebsiteOrderAndDetectPresets() {
        val picked = toggleSpecialty(listOf(IncidentType.Water), IncidentType.Fire)
        assertEquals(listOf(IncidentType.Fire, IncidentType.Water), picked)
        assertEquals(listOf(IncidentType.Water), toggleSpecialty(picked, IncidentType.Fire))
        assertTrue(presetActive(listOf(IncidentType.Water, IncidentType.Lift, IncidentType.Power), app.kavach.android.core.domain.Rules.PRESET_MAINTENANCE))
        assertFalse(presetActive(listOf(IncidentType.Lift), app.kavach.android.core.domain.Rules.PRESET_MAINTENANCE))
        assertTrue(specialtiesForRole(UserRole.Resident, listOf(IncidentType.Fire)).isEmpty())
        assertEquals(listOf(IncidentType.Fire), specialtiesForRole(UserRole.Responder, listOf(IncidentType.Fire)))
    }

    // ── Approved phone list ─────────────────────────────────────────────────

    private fun form(
        name: String = "Chloe",
        phone: String = "98765 43210",
        role: UserRole = UserRole.Resident,
        zoneId: String? = "zone-a",
        flat: String = "A-1204",
        specialties: List<IncidentType> = emptyList(),
    ) = MemberFormValues(name, phone, role, zoneId, flat, specialties)

    @Test fun memberFormValidation() {
        assertNull(validateMemberForm(form()))
        assertEquals(MemberFormIssue.NameRequired, validateMemberForm(form(name = "  ")))
        assertEquals(MemberFormIssue.PhoneInvalid, validateMemberForm(form(phone = "12345")))
        assertEquals(MemberFormIssue.FlatRequired, validateMemberForm(form(flat = "")))
        assertEquals(MemberFormIssue.FlatRequired, validateMemberForm(form(zoneId = null)))
        // staff need no flat, but a responder needs a specialty
        assertNull(validateMemberForm(form(role = UserRole.Admin, zoneId = null, flat = "")))
        assertEquals(
            MemberFormIssue.SpecialtyRequired,
            validateMemberForm(form(role = UserRole.Responder, zoneId = null, flat = "")),
        )
        assertNull(
            validateMemberForm(form(role = UserRole.Responder, zoneId = null, flat = "", specialties = listOf(IncidentType.Fire))),
        )
        // staff may skip the flat, but a flat number without a tower is refused by the server
        assertEquals(MemberFormIssue.TowerRequired, validateMemberForm(form(role = UserRole.Admin, zoneId = null, flat = "1204")))
        assertNull(validateMemberForm(form(role = UserRole.Admin, zoneId = "zone-a", flat = "1204")))
    }

    @Test fun editFormSendsTheFlatNumberNotTheLabel() {
        assertEquals("1204", flatDigits("A-1204"))
        assertEquals("0502", flatDigits("TB-0502"))
        assertEquals("1204", flatDigits("1204"))
        assertEquals("", flatDigits(null))
    }

    @Test fun approvedStaffKeepSpecialtiesResidentsDont() {
        assertEquals(listOf(IncidentType.Fire, IncidentType.Lift), specialtiesForRole(UserRole.Admin, listOf(IncidentType.Lift, IncidentType.Fire)))
        assertTrue(specialtiesForRole(UserRole.Resident, listOf(IncidentType.Lift)).isEmpty())
    }

    @Test fun approvedListSearchMatchesPhoneDigitsNameFlatAndTower() {
        fun approved(id: String, name: String, phone: String, flat: String?, tower: String?) =
            ApprovedMember(id = id, phone = phone, fullName = name, flatNumber = flat, zoneName = tower)
        val list = listOf(
            approved("1", "Chloe", "+919876543210", "A-1204", "Tower A"),
            approved("2", "Dev", "+919123456789", "B-0502", "Tower B"),
        )
        assertEquals(listOf("1"), filterApproved(list, "98765").map { it.id })
        assertEquals(listOf("1"), filterApproved(list, "+91 98765").map { it.id })
        assertEquals(listOf("2"), filterApproved(list, "dev").map { it.id })
        assertEquals(listOf("2"), filterApproved(list, "tower b").map { it.id })
        assertEquals(2, filterApproved(list, "").size)
    }

    // ── Incidents filters ───────────────────────────────────────────────────

    @Test fun activeIncidentListSortsByUrgencyAndTypeFilterIsClientSide() {
        val list = listOf(
            incident("low", urgency = 10, type = IncidentType.Water),
            incident("high", urgency = 90, type = IncidentType.Fire),
            incident("mid", urgency = 50, type = IncidentType.Fire),
        )
        val all = visibleIncidents(list, IncidentFilterState(status = StatusChoice.Active))
        assertEquals(listOf("high", "mid", "low"), all.map { it.id })
        val fire = visibleIncidents(list, IncidentFilterState(types = setOf(IncidentType.Fire)))
        assertEquals(listOf("high", "mid"), fire.map { it.id })
        // closed lists keep the server order (newest first)
        val closed = visibleIncidents(list, IncidentFilterState(status = StatusChoice.Closed))
        assertEquals(listOf("low", "high", "mid"), closed.map { it.id })
        assertTrue(IncidentFilterState(zoneId = "z").hasFilters)
        assertFalse(IncidentFilterState().hasFilters)
    }

    // ── Society ─────────────────────────────────────────────────────────────

    private fun asset(id: String, zoneId: String?, zoneName: String?, amc: LocalDate? = null, licence: LocalDate? = null) =
        Asset(
            id = id,
            zoneId = zoneId,
            kind = AssetKind.Lift,
            name = "Asset $id",
            amcExpiresOn = amc,
            licenceExpiresOn = licence,
            zone = if (zoneId != null && zoneName != null) ZoneRef(zoneId, zoneName) else null,
        )

    @Test fun assetsGroupByZoneInZoneOrderWithNoZoneLast() {
        val zones = listOf(Zone(id = "a", name = "Tower A"), Zone(id = "b", name = "Tower B"))
        val assets = listOf(
            asset("1", "b", "Tower B"),
            asset("2", null, null),
            asset("3", "a", "Tower A"),
            asset("4", "b", "Tower B"),
        )
        val groups = groupAssets(assets, zones)
        assertEquals(listOf("a", "b", "none"), groups.map { it.key })
        assertEquals(2, groups[1].items.size)
        assertNull(groups[2].zoneName)
    }

    @Test fun renewalsFindExpiredAndSoonEndingContracts() {
        val today = LocalDate.of(2026, 10, 10)
        val assets = listOf(
            asset("far", "a", "A", amc = today.plusDays(200)),
            asset("soon", "a", "A", amc = today.plusDays(10), licence = today.minusDays(3)),
        )
        val list = renewals(assets, today)
        assertEquals(listOf(RenewalKind.Licence, RenewalKind.Amc), list.map { it.kind })
        assertEquals(listOf(-3L, 10L), list.map { it.days })
        assertTrue(list.all { it.asset.id == "soon" })
    }

    private fun check(days: Int, status: ComplianceStatus) = ComplianceItem(
        id = "c$days",
        title = "Check",
        dueAt = LocalDate.of(2026, 10, 10).plusDays(days.toLong()),
        complianceStatus = status,
        daysLeft = days,
    )

    @Test fun dueWordingFollowsDaysLeft() {
        assertEquals(DueKind.OverdueOneDay, dueKind(check(-1, ComplianceStatus.Overdue)))
        assertEquals(DueKind.OverdueDays(4), dueKind(check(-4, ComplianceStatus.Overdue)))
        assertEquals(DueKind.Today, dueKind(check(0, ComplianceStatus.DueSoon)))
        assertEquals(DueKind.Tomorrow, dueKind(check(1, ComplianceStatus.DueSoon)))
        assertEquals(DueKind.InDays(9), dueKind(check(9, ComplianceStatus.DueSoon)))
        assertNull(dueKind(check(40, ComplianceStatus.Ok)))
    }

    @Test fun complianceGroupsSkipEmptyOnesAndKeepOverdueFirst() {
        val groups = complianceGroups(
            listOf(check(40, ComplianceStatus.Ok), check(-2, ComplianceStatus.Overdue), check(5, ComplianceStatus.Ok)),
        )
        assertEquals(listOf(ComplianceStatus.Overdue, ComplianceStatus.Ok), groups.map { it.first })
        assertEquals(2, groups[1].second.size)
    }

    @Test fun contactPhoneAcceptsLandlinesAndRejectsLetters() {
        assertTrue(isValidContactPhone("+91 44 2345 0000"))
        assertTrue(isValidContactPhone("112"))
        assertFalse(isValidContactPhone("call me"))
        assertFalse(isValidContactPhone("12"))
    }

    // ── Notices ─────────────────────────────────────────────────────────────

    @Test fun noticeExpiryIsEndOfThatLocalDay() {
        val zone = ZoneId.of("Asia/Kolkata")
        val date = LocalDate.of(2026, 10, 12)
        val at = endOfDay(date, zone)
        assertEquals(Instant.parse("2026-10-12T18:29:59Z"), at)
        assertEquals(date, expiryDate(at, zone))
        assertNull(expiryDate(null, zone))
    }

    // ── Safety Check ────────────────────────────────────────────────────────

    @Test fun unansweredResidentsRespectScopeAndShowVulnerableFirst() {
        val members = listOf(
            member("r1", "Zoya", flat = "A-1", tower = "Tower A"),
            member("r2", "Amit", flat = "A-2", tower = "Tower A", vulnerability = Vulnerability(elderly = true)),
            member("r3", "Bina", flat = "B-1", tower = "Tower B"),
            member("r4", "Chen", flat = "A-3", tower = "Tower A"),
            member("staff", "Guard", role = UserRole.Responder),
        )
        val responses = listOf(
            SafetyResponse(id = "x", profileId = "r4", status = SafetyResponseStatus.Safe, respondedAt = now),
        )
        val towerA = SafetyCheck(
            id = "c", scope = SafetyCheckScope.Zone, zoneId = "za", message = "m", startedAt = now,
            zone = ZoneRef("za", "Tower A"),
        )
        assertEquals(listOf("r2", "r1"), unansweredResidents(members, responses, towerA).map { it.id })
        val society = SafetyCheck(id = "c2", message = "m", startedAt = now)
        assertEquals(listOf("r2", "r3", "r1"), unansweredResidents(members, responses, society).map { it.id })
    }

    @Test fun shareIsClampedAndSafeForZeroTotal() {
        assertEquals(0f, share(3, 0), 0f)
        assertEquals(0.5f, share(5, 10), 0.0001f)
        assertEquals(1f, share(20, 10), 0f)
    }

    // ── Reports ─────────────────────────────────────────────────────────────

    @Test fun reportTotalsAddUpTheSummaryRows() {
        val totals = reportTotals(
            listOf(
                ReportSummaryRow(IncidentType.Lift, total = 5, resolved = 4, cancelled = 1, escalated = 2),
                ReportSummaryRow(IncidentType.Fire, total = 2, resolved = 2, cancelled = 0, escalated = 0),
            ),
        )
        assertEquals(ReportTotals(total = 7, resolved = 6, escalated = 2, cancelled = 1), totals)
    }

    @Test fun repeatProblemsNeedMoreThanOneIncidentAndStopAtFive() {
        fun row(id: String, n: Long) = ReportByAssetRow(assetId = id, assetName = id, kind = AssetKind.Lift, incidents = n)
        val rows = listOf(row("a", 1), row("b", 7), row("c", 2), row("d", 3), row("e", 4), row("f", 5), row("g", 6))
        assertEquals(listOf("b", "c", "d", "e", "f"), repeatProblems(rows).map { it.assetId })
    }

    @Test fun monthBarsCoverTheLastMonthsEvenWhenEmpty() {
        val rows = listOf(
            ReportMonthlyRow(LocalDate.of(2026, 10, 1), IncidentType.Fire, 2),
            ReportMonthlyRow(LocalDate.of(2026, 10, 1), IncidentType.Lift, 3),
            ReportMonthlyRow(LocalDate.of(2026, 8, 1), IncidentType.Lift, 1),
            ReportMonthlyRow(LocalDate.of(2025, 1, 1), IncidentType.Water, 9), // outside the window
        )
        val bars = monthBars(rows, months = 6, today = LocalDate.of(2026, 10, 15))
        assertEquals(6, bars.size)
        assertEquals(YearMonth.of(2026, 5), bars.first().month)
        assertEquals(YearMonth.of(2026, 10), bars.last().month)
        assertEquals(5L, bars.last().total)
        assertEquals(2L, bars.last().count(IncidentType.Fire))
        assertEquals(1L, bars[3].total) // August
        assertEquals(0L, bars[4].total) // September
        assertEquals(6L, bars.sumOf { it.total }) // the 2025 row is not counted
    }

    @Test fun legendFollowsStackingOrderAndSkipsAbsentTypes() {
        val bars = monthBars(
            listOf(
                ReportMonthlyRow(LocalDate.of(2026, 10, 1), IncidentType.Power, 1),
                ReportMonthlyRow(LocalDate.of(2026, 10, 1), IncidentType.Fire, 1),
                ReportMonthlyRow(LocalDate.of(2026, 9, 1), IncidentType.Medical, 1),
            ),
            months = 3,
            today = LocalDate.of(2026, 10, 2),
        )
        assertEquals(listOf(IncidentType.Fire, IncidentType.Medical, IncidentType.Power), presentTypes(bars))
        assertEquals("Oct", monthShortLabel(YearMonth.of(2026, 10)))
        assertEquals("October 2026", monthLongLabel(YearMonth.of(2026, 10)))
    }

    // ── Command board ───────────────────────────────────────────────────────

    @Test fun boardSplitsOpenFromInProgressByUrgency() {
        val (open, progress) = boardLists(
            listOf(
                incident("p1", IncidentStatus.Pending, urgency = 30),
                incident("p2", IncidentStatus.Pending, urgency = 80),
                incident("w1", IncidentStatus.EnRoute, urgency = 40),
                incident("w2", IncidentStatus.OnScene, urgency = 70),
            ),
        )
        assertEquals(listOf("p2", "p1"), open.map { it.id })
        assertEquals(listOf("w2", "w1"), progress.map { it.id })
    }

    @Test fun zonePowerCountsCutAndRotatingZones() {
        val zones = listOf(
            Zone(id = "1", name = "A", powerState = app.kavach.android.core.data.model.PowerState.Off),
            Zone(id = "2", name = "B", powerState = app.kavach.android.core.data.model.PowerState.Rotating),
            Zone(id = "3", name = "C", powerState = app.kavach.android.core.data.model.PowerState.Rotating),
            Zone(id = "4", name = "D"),
        )
        assertEquals(ZonePowerCounts(off = 1, rotating = 2), zonePowerCounts(zones))
    }
}
