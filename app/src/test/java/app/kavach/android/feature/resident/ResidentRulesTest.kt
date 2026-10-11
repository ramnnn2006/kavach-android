package app.kavach.android.feature.resident

import app.kavach.android.core.data.model.Contact
import app.kavach.android.core.data.model.ContactKind
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.MedicalAlert
import app.kavach.android.core.data.model.Notice
import app.kavach.android.core.data.model.SafetyCheck
import app.kavach.android.core.data.model.SafetyCheckScope
import app.kavach.android.core.data.model.SafetyResponse
import app.kavach.android.core.data.model.SafetyResponseStatus
import app.kavach.android.core.data.remote.ErrorCode
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.Loadable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ResidentRulesTest {
    private val t0 = Instant.parse("2026-10-10T10:00:00Z")

    private fun incident(
        id: String,
        status: IncidentStatus = IncidentStatus.Pending,
        createdAt: Instant = t0,
        resolvedAt: Instant? = null,
        escalation: Int = 1,
        people: Int = 1,
        description: String? = null,
    ) = Incident(
        id = id,
        type = IncidentType.Lift,
        status = status,
        createdAt = createdAt,
        resolvedAt = resolvedAt,
        escalationLevel = escalation,
        peopleAffected = people,
        description = description,
    )

    @Test fun activeReportsComeFirstAndKeepOrder() {
        val list = listOf(
            incident("old-resolved", IncidentStatus.Resolved),
            incident("a", IncidentStatus.EnRoute),
            incident("cancelled", IncidentStatus.Cancelled),
            incident("b", IncidentStatus.Pending),
        )
        assertEquals(listOf("a", "b", "old-resolved", "cancelled"), ResidentRules.activeFirst(list).map { it.id })
        assertEquals(listOf("a", "b"), ResidentRules.activeReports(list).map { it.id })
    }

    @Test fun homeShowsAtMostThreeRecentClosedReports() {
        val list = (1..5).map { incident("r$it", IncidentStatus.Resolved) } + incident("open", IncidentStatus.Pending)
        assertEquals(listOf("r1", "r2", "r3"), ResidentRules.recentReports(list).map { it.id })
    }

    private fun check(scope: SafetyCheckScope, zoneId: String? = null) =
        SafetyCheck(id = "c1", scope = scope, zoneId = zoneId, message = "Fire drill", startedAt = t0)

    @Test fun societyWideCheckAsksEveryone() {
        assertTrue(ResidentRules.safetyCheckApplies(check(SafetyCheckScope.Society), homeZoneId = null))
        assertTrue(ResidentRules.safetyCheckApplies(check(SafetyCheckScope.Society), homeZoneId = "z1"))
    }

    @Test fun towerCheckAsksOnlyThatTower() {
        val c = check(SafetyCheckScope.Zone, "z1")
        assertTrue(ResidentRules.safetyCheckApplies(c, "z1"))
        assertFalse(ResidentRules.safetyCheckApplies(c, "z2"))
        assertFalse(ResidentRules.safetyCheckApplies(c, null))
        assertFalse(ResidentRules.safetyCheckApplies(null, "z1"))
    }

    @Test fun newestAnswerWins() {
        val old = SafetyResponse(id = "1", status = SafetyResponseStatus.Safe, respondedAt = t0)
        val new = SafetyResponse(id = "2", status = SafetyResponseStatus.NeedHelp, respondedAt = t0.plusSeconds(30))
        assertEquals(new, ResidentRules.latestAnswer(old, new))
        assertEquals(new, ResidentRules.latestAnswer(new, old))
        assertEquals(old, ResidentRules.latestAnswer(old, null))
        assertNull(ResidentRules.latestAnswer(null, null))
    }

    @Test fun otherRespondersExcludeMe() {
        val alert = MedicalAlert(id = "m", createdAt = t0, ackCount = 3, iAmComing = true)
        assertEquals(2, ResidentRules.otherResponders(alert))
        assertEquals(3, ResidentRules.otherResponders(alert.copy(iAmComing = false)))
        assertEquals(0, ResidentRules.otherResponders(alert.copy(ackCount = 0, iAmComing = true)))
    }

    @Test fun cancelOnlyForMyReportsNobodyIsOnTheWayTo() {
        assertTrue(ResidentRules.canCancel(IncidentStatus.Pending, isMine = true))
        assertTrue(ResidentRules.canCancel(IncidentStatus.Acknowledged, isMine = true))
        assertFalse(ResidentRules.canCancel(IncidentStatus.EnRoute, isMine = true))
        assertFalse(ResidentRules.canCancel(IncidentStatus.Resolved, isMine = true))
        assertFalse(ResidentRules.canCancel(IncidentStatus.Pending, isMine = false))
    }

    @Test fun escalationBannerOnlyWhileWaiting() {
        assertTrue(ResidentRules.isEscalatedWaiting(incident("a", IncidentStatus.Pending, escalation = 2)))
        assertFalse(ResidentRules.isEscalatedWaiting(incident("a", IncidentStatus.Pending, escalation = 1)))
        assertFalse(ResidentRules.isEscalatedWaiting(incident("a", IncidentStatus.Acknowledged, escalation = 2)))
    }

    @Test fun resolvedTimeIsAtLeastOneMinute() {
        assertEquals(1L, ResidentRules.minutesToResolve(incident("a", resolvedAt = t0.plusSeconds(10))))
        assertEquals(14L, ResidentRules.minutesToResolve(incident("a", resolvedAt = t0.plusSeconds(14 * 60))))
        assertEquals(1L, ResidentRules.minutesToResolve(incident("a", resolvedAt = null)))
    }

    @Test fun stepsAreDoneUpToTheCurrentStatus() {
        val steps = ResidentRules.stepStates(incident("a", IncidentStatus.EnRoute))
        assertEquals(listOf(true, true, true, false, false), steps.map { it.done })
        assertEquals(listOf(false, false, false, true, false), steps.map { it.current })
        // Only the first step has a time here (the others are not set), and undone steps never show one
        assertEquals(t0, steps[0].time)
        assertNull(steps[3].time)
    }

    @Test fun waitingReportOnlyHasSentStepDone() {
        val steps = ResidentRules.stepStates(incident("a", IncidentStatus.Pending))
        assertEquals(listOf(true, false, false, false, false), steps.map { it.done })
        assertTrue(steps[1].current)
    }

    @Test fun onlyChangedDetailsAreSent() {
        val inc = incident("a", people = 2, description = "stuck")
        val none = ResidentRules.detailsChange(inc, ResidentRules.DetailsDraft(people = 2, note = " stuck "))
        assertTrue(none.isEmpty)
        val people = ResidentRules.detailsChange(inc, ResidentRules.DetailsDraft(people = 4, note = "stuck"))
        assertEquals(4, people.peopleAffected)
        assertNull(people.description)
        val note = ResidentRules.detailsChange(inc, ResidentRules.DetailsDraft(people = 2, note = "stuck, 2 inside"))
        assertNull(note.peopleAffected)
        assertEquals("stuck, 2 inside", note.description)
    }

    @Test fun draftStartsFromTheReport() {
        val draft = ResidentRules.draftFor(incident("a", people = 3, description = null))
        assertEquals(3, draft.people)
        assertEquals("", draft.note)
    }

    @Test fun noticesSplitIntoPinnedAndRest() {
        val notices = listOf(
            Notice(id = "1", title = "Pinned", pinned = true, createdAt = t0),
            Notice(id = "2", title = "Other", createdAt = t0),
        )
        val (pinned, rest) = ResidentRules.splitNotices(notices)
        assertEquals(listOf("1"), pinned.map { it.id })
        assertEquals(listOf("2"), rest.map { it.id })
    }

    @Test fun expiredNoticesAreHidden() {
        val notices = listOf(
            Notice(id = "forever", title = "A", createdAt = t0),
            Notice(id = "later", title = "B", createdAt = t0, expiresAt = t0.plusSeconds(3600)),
            Notice(id = "gone", title = "C", createdAt = t0, expiresAt = t0.minusSeconds(1)),
            Notice(id = "now", title = "D", createdAt = t0, expiresAt = t0),
        )
        assertEquals(listOf("forever", "later"), ResidentRules.visibleNotices(notices, t0).map { it.id })
    }

    @Test fun loadedDataIsKeptAcrossRestartsAndFailedRefetches() {
        val offline = KavachException(ErrorCode.Offline)
        var run = 0
        val upstream = flow<Loadable<List<Int>>> {
            run += 1
            emit(Loadable<List<Int>>())
            if (run == 1) {
                emit(Loadable(data = listOf(1), loading = false))
                emit(Loadable(data = listOf(1), loading = true)) // background refresh
            } else {
                emit(Loadable<List<Int>>(error = offline, loading = false))
            }
        }
        val kept = upstream.keepLoadedData()
        val first = runBlocking { kept.toList() }
        assertEquals(
            listOf(
                Loadable<List<Int>>(),
                Loadable(data = listOf(1), loading = false),
                Loadable(data = listOf(1), loading = false), // no "refreshing" flip
            ),
            first,
        )
        // Restart (screen shown again): no empty flash, and a failed refetch keeps the data
        val second = runBlocking { kept.toList() }
        assertEquals(
            listOf(
                Loadable(data = listOf(1), loading = false),
                Loadable(data = listOf(1), error = offline, loading = false),
            ),
            second,
        )
    }

    @Test fun aLoadedNullCountsAsLoaded() {
        val upstream = flow<Loadable<String?>> {
            emit(Loadable<String?>())
            emit(Loadable<String?>(data = null, loading = false)) // e.g. no active Safety Check
            emit(Loadable<String?>(data = null, loading = true))
        }
        val states = runBlocking { upstream.keepLoadedData().toList() }
        assertEquals(listOf(true, false, false), states.map { it.loading })
        assertTrue(states.all { it.data == null })
    }

    @Test fun contactsGroupByKind() {
        val contacts = listOf(
            Contact(id = "1", kind = ContactKind.Society, name = "Security", roleLabel = "Desk", phone = "+911", available = "24x7"),
            Contact(id = "2", kind = ContactKind.Emergency, name = "Police", phone = "100"),
            Contact(id = "3", kind = ContactKind.Society, name = "Plumber", phone = "+912"),
        )
        val (society, emergency) = ResidentRules.groupContacts(contacts)
        assertEquals(listOf("1", "3"), society.map { it.id })
        assertEquals(listOf("2"), emergency.map { it.id })
        assertEquals("Desk · 24x7", ResidentRules.contactDetail(contacts[0]))
        assertEquals("", ResidentRules.contactDetail(contacts[1]))
    }
}
