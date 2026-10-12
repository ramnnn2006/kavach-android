package app.kavach.android.feature.alerts

import app.kavach.android.core.data.model.EventAction
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentEvent
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.MedicalAlert
import app.kavach.android.core.data.model.Notice
import app.kavach.android.core.data.model.SafetyCheck
import app.kavach.android.core.data.model.SafetyCheckScope
import app.kavach.android.core.data.model.TeamMember
import app.kavach.android.core.data.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AlertPolicyTest {
    private val t0: Instant = Instant.parse("2026-10-10T10:00:00Z")

    private fun incident(
        id: String = "i1",
        type: IncidentType = IncidentType.Lift,
        status: IncidentStatus = IncidentStatus.Pending,
        reporter: String? = "resident-1",
        assignedTo: String? = null,
        escalation: Int = 1,
        createdAt: Instant = t0,
    ) = Incident(
        id = id,
        type = type,
        status = status,
        reporterId = reporter,
        assignedTo = assignedTo,
        escalationLevel = escalation,
        createdAt = createdAt,
    )

    private fun user(
        id: String = "me",
        role: UserRole = UserRole.Responder,
        onDuty: Boolean = true,
        specialties: List<IncidentType> = listOf(IncidentType.Lift, IncidentType.Power),
        firstResponder: Boolean = false,
        zone: String? = "zone-a",
        name: String = "Chloe Resident",
    ) = AlertUser(id, role, name, onDuty, specialties, firstResponder, zone)

    private fun keys(events: List<AlertEvent>) = events.map { it.key }

    // ── Staff: new reports, assignments, escalations ──────────────────────────

    @Test fun onDutyResponderGetsNewReportsInTheirSpecialties() {
        val events = AlertPolicy.staffEvents(
            user(),
            listOf(incident("a", IncidentType.Lift), incident("b", IncidentType.Fire), incident("c", IncidentType.Power)),
        )
        assertEquals(listOf("inc:a:new", "inc:c:new"), keys(events))
        assertTrue(events.all { it is AlertEvent.NewReport && it.mode == NewReportMode.OnDuty })
        assertTrue(events.all { it.channel == AlertChannelId.Emergency })
    }

    @Test fun offDutyResponderGetsNoNewReports() {
        assertTrue(AlertPolicy.staffEvents(user(onDuty = false), listOf(incident())).isEmpty())
    }

    @Test fun reporterIsNotToldAboutTheirOwnReport() {
        assertTrue(AlertPolicy.staffEvents(user(id = "resident-1"), listOf(incident(reporter = "resident-1"))).isEmpty())
    }

    @Test fun residentGetsNothingFromTheStaffQueue() {
        assertTrue(AlertPolicy.staffEvents(user(role = UserRole.Resident), listOf(incident())).isEmpty())
    }

    @Test fun assignedToMeIsReportedEvenWhenOffDutyOrOtherSpecialty() {
        val inc = incident("x", IncidentType.Water, IncidentStatus.Acknowledged, assignedTo = "me")
        val events = AlertPolicy.staffEvents(user(onDuty = false), listOf(inc))
        assertEquals(listOf("inc:x:assigned:me"), keys(events))
        assertTrue(events.single() is AlertEvent.Assigned)
    }

    @Test fun assignedToSomeoneElseIsNotMine() {
        val inc = incident("x", status = IncidentStatus.Acknowledged, assignedTo = "other")
        assertTrue(AlertPolicy.staffEvents(user(), listOf(inc)).isEmpty())
    }

    @Test fun adminOnDutyWithTheSpecialtyGetsTheNormalAlertAndEscalations() {
        val admin = user(role = UserRole.Admin, specialties = listOf(IncidentType.Security))
        val events = AlertPolicy.staffEvents(admin, listOf(incident("a", IncidentType.Security, escalation = 2)))
        assertEquals(listOf("inc:a:new", "inc:a:esc:2"), keys(events))
        assertEquals(NewReportMode.OnDuty, (events[0] as AlertEvent.NewReport).mode)
    }

    @Test fun adminOnDutyOutsideTheirSpecialtiesGetsTheNoOneOnDutyVariant() {
        // notify/index.ts: the on-duty query needs the specialty, admins included
        val admin = user(role = UserRole.Admin, specialties = emptyList())
        val events = AlertPolicy.staffEvents(admin, listOf(incident("a", IncidentType.Security, escalation = 2)))
        assertEquals(listOf("inc:a:new", "inc:a:esc:2"), keys(events))
        assertEquals(NewReportMode.NoOneOnDuty, (events[0] as AlertEvent.NewReport).mode)
    }

    @Test fun adminOffDutyGetsTheNoOneOnDutyVariant() {
        val admin = user(role = UserRole.Admin, onDuty = false)
        val event = AlertPolicy.staffEvents(admin, listOf(incident())).single() as AlertEvent.NewReport
        assertEquals(NewReportMode.NoOneOnDuty, event.mode)
        assertEquals(AlertLookup.Team, event.lookup)
    }

    @Test fun escalationGoesToAdminsOnlyAndOnlyWhilePending() {
        val escalated = incident("e", escalation = 3)
        assertTrue(AlertPolicy.staffEvents(user(), listOf(escalated)).none { it is AlertEvent.Escalated })
        val claimed = incident("e", status = IncidentStatus.Acknowledged, escalation = 3)
        assertTrue(AlertPolicy.staffEvents(user(role = UserRole.Admin), listOf(claimed)).isEmpty())
        val level1 = incident("e", escalation = 1)
        assertTrue(AlertPolicy.staffEvents(user(role = UserRole.Admin), listOf(level1)).none { it is AlertEvent.Escalated })
    }

    @Test fun escalationKeyIncludesTheLevelSoEachRiseNotifiesOnce() {
        val admin = user(role = UserRole.Admin)
        val l2 = AlertPolicy.staffEvents(admin, listOf(incident("e", escalation = 2))).filterIsInstance<AlertEvent.Escalated>().single()
        val l3 = AlertPolicy.staffEvents(admin, listOf(incident("e", escalation = 3))).filterIsInstance<AlertEvent.Escalated>().single()
        assertEquals("inc:e:esc:2", l2.key)
        assertEquals("inc:e:esc:3", l3.key)
    }

    // ── Residents: progress of their own report ───────────────────────────────

    @Test fun reporterIsToldOnlyAboutTheCurrentStatus() {
        val me = user(id = "resident-1", role = UserRole.Resident)
        val inc = incident("r", status = IncidentStatus.OnScene, reporter = "resident-1", assignedTo = "resp")
        val events = AlertPolicy.reporterEvents(me, listOf(inc))
        assertEquals(listOf("inc:r:status:on_scene"), keys(events))
        assertEquals(AlertChannelId.Status, events.single().channel)
        assertEquals(sosTag("r"), events.single().tag)
    }

    @Test fun pendingReportsAreNotAnUpdate() {
        val me = user(id = "resident-1", role = UserRole.Resident)
        assertTrue(AlertPolicy.reporterEvents(me, listOf(incident(reporter = "resident-1"))).isEmpty())
    }

    @Test fun responderWhoReportedAndIsAssigneeOnlyHearsOfCancellation() {
        val me = user(id = "me")
        val advancing = incident("r", status = IncidentStatus.EnRoute, reporter = "me", assignedTo = "me")
        assertTrue(AlertPolicy.reporterEvents(me, listOf(advancing)).isEmpty())
        val cancelled = incident("r", status = IncidentStatus.Cancelled, reporter = "me", assignedTo = "me")
        assertEquals(1, AlertPolicy.reporterEvents(me, listOf(cancelled)).size)
    }

    @Test fun statusTimeUsesTheMatchingTimestamp() {
        val resolvedAt = t0.plusSeconds(600)
        val inc = incident(status = IncidentStatus.Resolved).copy(resolvedAt = resolvedAt)
        assertEquals(resolvedAt, AlertPolicy.statusTime(inc))
        assertEquals(t0, AlertPolicy.statusTime(incident(status = IncidentStatus.EnRoute)))
    }

    // ── First responders, safety checks, notices ──────────────────────────────

    private fun medical(id: String = "m1", coming: Boolean = false, status: IncidentStatus = IncidentStatus.Pending) =
        MedicalAlert(id = id, createdAt = t0, iAmComing = coming, status = status)

    @Test fun communityFirstResponderGetsMedicalAlertsTheyHaveNotAnswered() {
        val fr = user(role = UserRole.Resident, firstResponder = true)
        val events = AlertPolicy.medicalEvents(fr, listOf(medical("a"), medical("b", coming = true), medical("c", status = IncidentStatus.Resolved)))
        assertEquals(listOf("medical:a"), keys(events))
    }

    @Test fun medicalAlertsAreOnlyForResidentFirstResponders() {
        assertTrue(AlertPolicy.medicalEvents(user(role = UserRole.Resident, firstResponder = false), listOf(medical())).isEmpty())
        assertTrue(AlertPolicy.medicalEvents(user(role = UserRole.Responder, firstResponder = true), listOf(medical())).isEmpty())
    }

    private fun check(scope: SafetyCheckScope, zone: String? = null, ended: Boolean = false) = SafetyCheck(
        id = "c1",
        scope = scope,
        zoneId = zone,
        message = "Fire drill",
        startedAt = t0,
        endedAt = if (ended) t0.plusSeconds(60) else null,
    )

    @Test fun societyWideSafetyCheckReachesEveryResident() {
        val events = AlertPolicy.safetyEvents(user(role = UserRole.Resident), check(SafetyCheckScope.Society), answered = false)
        assertEquals(listOf("check:c1"), keys(events))
        assertEquals(AlertChannelId.SafetyCheck, events.single().channel)
    }

    @Test fun zoneSafetyCheckReachesOnlyThatTower() {
        val resident = user(role = UserRole.Resident, zone = "zone-a")
        assertEquals(1, AlertPolicy.safetyEvents(resident, check(SafetyCheckScope.Zone, "zone-a"), false).size)
        assertTrue(AlertPolicy.safetyEvents(resident, check(SafetyCheckScope.Zone, "zone-b"), false).isEmpty())
        assertTrue(AlertPolicy.safetyEvents(user(role = UserRole.Resident, zone = null), check(SafetyCheckScope.Zone, "zone-a"), false).isEmpty())
    }

    @Test fun safetyCheckNotShownWhenAnsweredEndedOrForStaff() {
        val resident = user(role = UserRole.Resident)
        assertTrue(AlertPolicy.safetyEvents(resident, check(SafetyCheckScope.Society), answered = true).isEmpty())
        assertTrue(AlertPolicy.safetyEvents(resident, check(SafetyCheckScope.Society, ended = true), answered = false).isEmpty())
        assertTrue(AlertPolicy.safetyEvents(resident, null, answered = false).isEmpty())
        assertTrue(AlertPolicy.safetyEvents(user(role = UserRole.Admin), check(SafetyCheckScope.Society), answered = false).isEmpty())
    }

    @Test fun noticesAreForResidents() {
        val notice = Notice(id = "n1", title = "Water cut", createdAt = t0)
        assertEquals(listOf("notice:n1"), keys(AlertPolicy.noticeEvents(user(role = UserRole.Resident), listOf(notice))))
        assertTrue(AlertPolicy.noticeEvents(user(role = UserRole.Responder), listOf(notice)).isEmpty())
    }

    // ── Which candidates are new ──────────────────────────────────────────────

    private fun newReportAt(id: String, at: Instant) = AlertEvent.NewReport(incident(id, createdAt = at), NewReportMode.OnDuty)

    @Test fun seenKeysAreNeverRepeated() {
        val events = listOf(newReportAt("a", t0), newReportAt("b", t0))
        val fresh = AlertPolicy.fresh(events, seen = setOf("inc:a:new"), cutoff = null, now = t0)
        assertEquals(listOf("inc:b:new"), keys(fresh))
    }

    @Test fun inSessionEverythingUnseenIsShownRegardlessOfAge() {
        val old = newReportAt("a", t0.minus(Duration.ofHours(5)))
        assertEquals(1, AlertPolicy.fresh(listOf(old), emptySet(), cutoff = null, now = t0).size)
    }

    @Test fun firstRunEverIsASilentBaseline() {
        val cutoff = AlertPolicy.cutoff(lastSeen = null)
        assertTrue(AlertPolicy.fresh(listOf(newReportAt("a", t0)), emptySet(), cutoff, now = t0.plusSeconds(5)).isEmpty())
    }

    @Test fun catchUpShowsOnlyWhatHappenedAfterLastSeen() {
        val lastSeen = t0
        val cutoff = AlertPolicy.cutoff(lastSeen)
        val before = newReportAt("before", t0.minus(Duration.ofMinutes(10)))
        val within = newReportAt("margin", t0.minus(Duration.ofMinutes(1))) // inside the 2 min margin
        val after = newReportAt("after", t0.plus(Duration.ofMinutes(5)))
        val fresh = AlertPolicy.fresh(listOf(before, within, after), emptySet(), cutoff, now = t0.plus(Duration.ofMinutes(20)))
        assertEquals(listOf("inc:margin:new", "inc:after:new"), keys(fresh))
    }

    @Test fun catchUpDropsEventsOlderThanTheirTtl() {
        val cutoff = AlertPolicy.cutoff(t0.minus(Duration.ofHours(10)))
        val stale = newReportAt("stale", t0)
        // an urgent alert 61 minutes old is no longer worth a notification
        assertTrue(AlertPolicy.fresh(listOf(stale), emptySet(), cutoff, now = t0.plus(Duration.ofMinutes(61))).isEmpty())
        assertEquals(1, AlertPolicy.fresh(listOf(stale), emptySet(), cutoff, now = t0.plus(Duration.ofMinutes(59))).size)
    }

    @Test fun duplicateKeysInOneBatchAppearOnce() {
        val e = newReportAt("a", t0)
        assertEquals(1, AlertPolicy.fresh(listOf(e, e), emptySet(), null, t0).size)
    }

    // ── Late checks that need extra data ──────────────────────────────────────

    private fun history(vararg actions: Pair<EventAction, String?>) = actions.mapIndexed { i, (action, actor) ->
        IncidentEvent(id = i.toLong(), actorName = actor, action = action, createdAt = t0.plusSeconds(i.toLong()))
    }

    @Test fun selfClaimIsNotReportedAsAnAssignment() {
        val assigned = AlertEvent.Assigned(incident(status = IncidentStatus.Acknowledged, assignedTo = "me"), "me")
        val claimed = history(EventAction.Created to "Chloe", EventAction.Claimed to "Ravi")
        assertNull(AlertPolicy.resolve(assigned, claimed, null, "Ravi"))
        val byAdmin = history(EventAction.Created to "Chloe", EventAction.Assigned to "Admin")
        assertEquals(assigned, AlertPolicy.resolve(assigned, byAdmin, null, "Ravi"))
    }

    @Test fun anAssignmentIMadeToMyselfIsNotReported() {
        val assigned = AlertEvent.Assigned(incident(status = IncidentStatus.Acknowledged, assignedTo = "me"), "me")
        val byMe = history(EventAction.Assigned to "Other", EventAction.Reassigned to "Asha Admin")
        assertNull(AlertPolicy.resolve(assigned, byMe, null, "Asha Admin"))
    }

    @Test fun reassigningAwayIsNotReportedToWhoeverDidIt() {
        val event = AlertEvent.Reassigned(incident(status = IncidentStatus.Acknowledged, assignedTo = "other"), t0)
        assertEquals(AlertLookup.Events, event.lookup)
        assertNull(AlertPolicy.resolve(event, history(EventAction.Reassigned to "Ravi"), null, "Ravi"))
        assertEquals(event, AlertPolicy.resolve(event, history(EventAction.Reassigned to "Admin"), null, "Ravi"))
        assertEquals(event, AlertPolicy.resolve(event, null, null, "Ravi"))
    }

    @Test fun aReporterWhoAssignedTheirOwnReportIsNotToldAboutIt() {
        val progress = AlertEvent.ReportProgress(incident(status = IncidentStatus.Acknowledged, assignedTo = "r"))
        assertNull(AlertPolicy.resolve(progress, history(EventAction.Assigned to "Asha Admin"), null, "Asha Admin"))
    }

    @Test fun failedLookupStillNotifies() {
        val assigned = AlertEvent.Assigned(incident(status = IncidentStatus.Acknowledged, assignedTo = "me"), "me")
        assertEquals(assigned, AlertPolicy.resolve(assigned, null, null, "Ravi"))
    }

    @Test fun acceptedByAdminAssignmentIsFlagged() {
        val progress = AlertEvent.ReportProgress(incident(status = IncidentStatus.Acknowledged, assignedTo = "r"))
        val resolved = AlertPolicy.resolve(progress, history(EventAction.Assigned to "Admin"), null, "Chloe") as AlertEvent.ReportProgress
        assertTrue(resolved.assignedByOther)
        val selfClaim = AlertPolicy.resolve(progress, history(EventAction.Claimed to "Ravi"), null, "Chloe") as AlertEvent.ReportProgress
        assertFalse(selfClaim.assignedByOther)
    }

    @Test fun myOwnCancellationIsNotAnnounced() {
        val cancelled = AlertEvent.ReportProgress(incident(status = IncidentStatus.Cancelled))
        assertNull(AlertPolicy.resolve(cancelled, history(EventAction.Cancelled to "chloe resident "), null, "Chloe Resident"))
        assertEquals(cancelled, AlertPolicy.resolve(cancelled, history(EventAction.Cancelled to "Admin"), null, "Chloe Resident"))
        val staff = AlertEvent.StaffCancelled(incident(status = IncidentStatus.Cancelled), t0)
        assertNull(AlertPolicy.resolve(staff, history(EventAction.Cancelled to "Ravi"), null, "Ravi"))
        assertEquals(staff, AlertPolicy.resolve(staff, history(EventAction.Cancelled to "Chloe"), null, "Ravi"))
    }

    private fun member(id: String, role: UserRole, onDuty: Boolean, vararg types: IncidentType) =
        TeamMember(id = id, role = role, onDuty = onDuty, specialties = types.toList())

    @Test fun adminNotOnDutyIsToldOnlyWhenNobodyCoversTheType() {
        val inc = incident(type = IncidentType.Lift, reporter = "res")
        val event = AlertEvent.NewReport(inc, NewReportMode.NoOneOnDuty)
        val covered = listOf(member("p", UserRole.Responder, true, IncidentType.Lift))
        assertNull(AlertPolicy.resolve(event, null, covered, "Admin"))
        val wrongType = listOf(member("p", UserRole.Responder, true, IncidentType.Water))
        assertEquals(event, AlertPolicy.resolve(event, null, wrongType, "Admin"))
        val offDuty = listOf(member("p", UserRole.Responder, false, IncidentType.Lift))
        assertEquals(event, AlertPolicy.resolve(event, null, offDuty, "Admin"))
        assertEquals(event, AlertPolicy.resolve(event, null, null, "Admin"))
    }

    @Test fun someoneOnDutyIgnoresTheReporter() {
        val team = listOf(member("res", UserRole.Responder, true, IncidentType.Lift))
        assertFalse(AlertPolicy.someoneOnDuty(team, IncidentType.Lift, reporterId = "res"))
        assertTrue(AlertPolicy.someoneOnDuty(team, IncidentType.Lift, reporterId = "other"))
    }

    @Test fun anAdminOnDutyCoversOnlyTheirSpecialties() {
        assertFalse(AlertPolicy.someoneOnDuty(listOf(member("a", UserRole.Admin, true)), IncidentType.Security, reporterId = null))
        val withSpecialty = listOf(member("a", UserRole.Admin, true, IncidentType.Security))
        assertTrue(AlertPolicy.someoneOnDuty(withSpecialty, IncidentType.Security, reporterId = null))
    }

    @Test fun residentsOnTheTeamListNeverCount() {
        val resident = listOf(member("r", UserRole.Resident, true, IncidentType.Lift))
        assertFalse(AlertPolicy.someoneOnDuty(resident, IncidentType.Lift, reporterId = null))
    }

    // ── Changes between snapshots ─────────────────────────────────────────────

    @Test fun notificationsClearWhenSomeoneElseTakesTheReport() {
        val me = user()
        val before = listOf(incident("a"), incident("b"), incident("c"))
        val now = listOf(
            incident("a"), // still pending
            incident("b", status = IncidentStatus.Acknowledged, assignedTo = "other"),
        ) // "c" left the active queue
        assertEquals(setOf("b", "c"), AlertPolicy.staleIncidentIds(me, now, before))
    }

    @Test fun myOwnClaimedReportKeepsItsNotification() {
        val me = user()
        val before = listOf(incident("a"))
        val now = listOf(incident("a", status = IncidentStatus.Acknowledged, assignedTo = "me"))
        assertTrue(AlertPolicy.staleIncidentIds(me, now, before).isEmpty())
    }

    @Test fun reassignedAwayNotificationIsNotImmediatelyCleared() {
        val me = user()
        val before = listOf(incident("a", status = IncidentStatus.Acknowledged, assignedTo = "me"))
        val now = listOf(incident("a", status = IncidentStatus.Acknowledged, assignedTo = "other"))
        assertTrue(AlertPolicy.staleIncidentIds(me, now, before).isEmpty())
        val events = AlertPolicy.reassignedAway(me, before, now, t0)
        assertEquals(1, events.size)
        assertTrue(events.single() is AlertEvent.Reassigned)
    }

    @Test fun firstSnapshotClearsEverythingNotPendingAndNotMine() {
        val me = user()
        val queue = listOf(incident("p"), incident("t", status = IncidentStatus.EnRoute, assignedTo = "other"), incident("m", status = IncidentStatus.EnRoute, assignedTo = "me"))
        assertEquals(setOf("t"), AlertPolicy.staleIncidentIds(me, queue, previous = null))
    }

    @Test fun assignedIncidentsThatLeftTheQueueAreReported() {
        val me = user()
        val before = listOf(incident("a", status = IncidentStatus.OnScene, assignedTo = "me"), incident("b", assignedTo = "other"))
        assertEquals(listOf("a"), AlertPolicy.assignedAndGone(me, before, queue = emptyList()))
        assertTrue(AlertPolicy.assignedAndGone(me, previous = null, queue = emptyList()).isEmpty())
    }

    @Test fun aCancellationWhileAwayIsFoundAndTimedByTheCancellation() {
        val me = user()
        val cancelledAt = t0.plusSeconds(300)
        val mine = incident("a", status = IncidentStatus.Cancelled, assignedTo = "me").copy(cancelledAt = cancelledAt)
        val other = incident("b", status = IncidentStatus.Cancelled, assignedTo = "other")
        val events = AlertPolicy.cancelledWhileAway(me, listOf(mine, other))
        assertEquals(listOf("inc:a:cancelled"), keys(events))
        assertEquals(cancelledAt, events.single().at)
    }

    @Test fun medicalNotificationsClearWhenAnsweredOrGone() {
        val previous = setOf("a", "b", "c")
        val alerts = listOf(medical("a"), medical("b", coming = true))
        assertEquals(setOf("b", "c"), AlertPolicy.staleMedicalIds(previous, alerts))
        assertEquals(setOf("a"), AlertPolicy.openMedicalIds(alerts))
    }
}
