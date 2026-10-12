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
import java.time.Duration
import java.time.Instant

/**
 * The "who gets notified about what" rules, as pure functions over snapshots. They mirror
 * supabase/functions/notify/index.ts, which does the same on the server for Web Push:
 *
 *  - New pending report: on-duty responders and admins whose specialties include the type
 *    (never the reporter). The other admins only hear of it when nobody on duty covers the
 *    type. Community first responders hear of medical reports.
 *  - Assignment: the new assignee, unless they claimed it themselves.
 *  - Escalation of a still-pending report: every admin.
 *  - Progress of my own report (accepted, on the way, arrived, resolved, cancelled).
 *  - Cancelled or reassigned away from the responder who was working on it.
 *  - Safety Check: residents it applies to. Notices: residents.
 *
 * The functions only create candidate [AlertEvent]s from the current snapshot. Deciding which are
 * new is [fresh], using the persisted set of keys already shown.
 */
object AlertPolicy {
    /** Lookback margin on the persisted "last seen" time, for small clock differences. */
    val CUTOFF_MARGIN: Duration = Duration.ofMinutes(2)

    // ── Which candidates are new ──────────────────────────────────────────────

    /**
     * Cutoff for the first snapshot of a run: events that happened before it are not shown (they
     * were already there or already shown). With no [lastSeen] (first run ever for this user) the
     * cutoff is [Instant.MAX]: everything existing is a silent baseline.
     */
    fun cutoff(lastSeen: Instant?): Instant = lastSeen?.minus(CUTOFF_MARGIN) ?: Instant.MAX

    /**
     * Candidates that should be shown now: not in [seen], and, when [cutoff] is given (first
     * snapshot of a run or the catch-up worker), newer than the cutoff and not older than their
     * TTL. Later snapshots of a running service pass a null cutoff: anything new is shown.
     */
    fun fresh(events: List<AlertEvent>, seen: Set<String>, cutoff: Instant?, now: Instant): List<AlertEvent> =
        events
            .asSequence()
            .filter { it.key !in seen }
            .filter { cutoff == null || it.at.isAfter(cutoff) }
            .filter { cutoff == null || Duration.between(it.at, now) <= it.ttl.maxAge }
            .distinctBy { it.key }
            .toList()

    // ── Candidates per source ─────────────────────────────────────────────────

    /** From the staff queue (RLS already limits responders to their specialties and assignments). */
    fun staffEvents(user: AlertUser, queue: List<Incident>): List<AlertEvent> {
        if (!user.isStaff) return emptyList()
        val out = ArrayList<AlertEvent>()
        for (inc in queue) {
            if (!inc.isActive) continue
            if (inc.assignedTo == user.id) out += AlertEvent.Assigned(inc, user.id)
            if (inc.status != IncidentStatus.Pending) continue
            if (inc.reporterId != user.id) {
                if (user.onDuty && user.covers(inc.type)) {
                    out += AlertEvent.NewReport(inc, NewReportMode.OnDuty)
                } else if (user.role == UserRole.Admin) {
                    // Every admin hears of it when nobody on duty covers the type (checked in resolve)
                    out += AlertEvent.NewReport(inc, NewReportMode.NoOneOnDuty)
                }
            }
            if (user.role == UserRole.Admin && inc.escalationLevel > 1) out += AlertEvent.Escalated(inc)
        }
        return out
    }

    /**
     * From my own reports. Only the current status counts (a report that moved through three steps
     * while the service was dead gives one notification). When I am the assignee I did the work
     * myself, so only a cancellation is reported.
     */
    fun reporterEvents(user: AlertUser, mine: List<Incident>): List<AlertEvent> =
        mine.mapNotNull { inc ->
            when {
                inc.reporterId != null && inc.reporterId != user.id -> null
                inc.status == IncidentStatus.Pending -> null
                inc.assignedTo == user.id && inc.status != IncidentStatus.Cancelled -> null
                else -> AlertEvent.ReportProgress(inc)
            }
        }

    /** Medical alerts for a community first responder (a resident with a first-responder skill). */
    fun medicalEvents(user: AlertUser, alerts: List<MedicalAlert>): List<AlertEvent> {
        if (user.role != UserRole.Resident || !user.isFirstResponder) return emptyList()
        return alerts
            .filter { !it.iAmComing && it.status.isActive }
            .map { AlertEvent.MedicalHelp(it) }
    }

    /** The active Safety Check, if it applies to this resident and they have not answered yet. */
    fun safetyEvents(user: AlertUser, check: SafetyCheck?, answered: Boolean): List<AlertEvent> {
        if (check == null || !check.isActive || answered) return emptyList()
        if (user.role != UserRole.Resident) return emptyList()
        val applies = when (check.scope) {
            SafetyCheckScope.Society -> true
            SafetyCheckScope.Zone -> check.zoneId != null && check.zoneId == user.homeZoneId
        }
        return if (applies) listOf(AlertEvent.SafetyCheckStarted(check)) else emptyList()
    }

    fun noticeEvents(user: AlertUser, notices: List<Notice>): List<AlertEvent> {
        if (user.role != UserRole.Resident) return emptyList()
        return notices.map { AlertEvent.NoticePosted(it) }
    }

    // ── Changes between two queue snapshots (only while the service runs) ─────

    /**
     * Incidents whose "needs action" notification should go away: somebody else took them (no
     * longer pending and not mine) or they left the active queue. With [previous] null (first
     * snapshot) every non-pending one that is not mine is cleared.
     */
    fun staleIncidentIds(user: AlertUser, queue: List<Incident>, previous: List<Incident>?): Set<String> {
        val before = previous?.associateBy { it.id }
        val out = LinkedHashSet<String>()
        for (inc in queue) {
            if (inc.status == IncidentStatus.Pending || inc.assignedTo == user.id) continue
            val wasPending = before == null || before[inc.id]?.status == IncidentStatus.Pending
            if (wasPending) out += inc.id
        }
        if (previous != null) {
            val now = queue.mapTo(HashSet()) { it.id }
            for (inc in previous) {
                if (inc.id !in now && inc.assignedTo != user.id) out += inc.id
            }
        }
        return out
    }

    /** Incidents that were assigned to me and are no longer in the active queue (closed). */
    fun assignedAndGone(user: AlertUser, previous: List<Incident>?, queue: List<Incident>): List<String> {
        if (previous == null) return emptyList()
        val now = queue.mapTo(HashSet()) { it.id }
        return previous.filter { it.assignedTo == user.id && it.id !in now }.map { it.id }
    }

    /** Incidents that were mine and are still active but now belong to somebody else. */
    fun reassignedAway(user: AlertUser, previous: List<Incident>?, queue: List<Incident>, now: Instant): List<AlertEvent> {
        if (previous == null) return emptyList()
        val current = queue.associateBy { it.id }
        return previous.mapNotNull { was ->
            val inc = current[was.id]
            if (was.assignedTo == user.id && inc != null && inc.isActive && inc.assignedTo != user.id) {
                AlertEvent.Reassigned(inc, now)
            } else {
                null
            }
        }
    }

    fun staffCancelled(incident: Incident, now: Instant): AlertEvent = AlertEvent.StaffCancelled(incident, now)

    /**
     * After a gap (no previous snapshot), reports assigned to me that were cancelled meanwhile: the
     * active queue no longer shows them. Timed by the cancellation, so the cutoff and TTL apply.
     */
    fun cancelledWhileAway(user: AlertUser, cancelled: List<Incident>): List<AlertEvent> =
        cancelled
            .filter { it.status == IncidentStatus.Cancelled && it.assignedTo == user.id }
            .map { AlertEvent.StaffCancelled(it, it.cancelledAt ?: it.updatedAt ?: it.createdAt) }

    /** Medical notifications to clear: gone from the list, or I already said I am coming. */
    fun staleMedicalIds(previous: Set<String>, alerts: List<MedicalAlert>): Set<String> {
        val stillOpen = alerts.filter { !it.iAmComing && it.status.isActive }.mapTo(HashSet()) { it.id }
        return previous.filterTo(LinkedHashSet()) { it !in stillOpen }
    }

    /** Ids to remember for the next [staleMedicalIds] call. */
    fun openMedicalIds(alerts: List<MedicalAlert>): Set<String> =
        alerts.filter { !it.iAmComing && it.status.isActive }.mapTo(LinkedHashSet()) { it.id }

    // ── Final checks that need a little extra data ────────────────────────────

    /**
     * Applies the extra data an event asked for ([AlertEvent.lookup]). Returns the event (possibly
     * adjusted), or null to drop it. A failed lookup (null data) keeps the event: for an
     * emergency a duplicate beats a missed alert.
     *
     * The website never tells whoever caused a change about it. "Me" is matched by the event's
     * actor id when both ids are known ([myId]), otherwise by name.
     *
     *  - Assigned: dropped when the last claim/assign event is a claim, or an assignment I made
     *    myself.
     *  - ReportProgress Acknowledged: flagged when an admin assigned it ("X is assigned to your
     *    alert" instead of "X accepted your alert"); dropped when I made that assignment.
     *  - Cancelled (mine, or the one I was working on): dropped when I cancelled it.
     *  - Reassigned away from me: dropped when I reassigned it.
     *  - NewReport NoOneOnDuty: dropped when somebody on duty covers it.
     */
    fun resolve(
        event: AlertEvent,
        events: List<IncidentEvent>?,
        team: List<TeamMember>?,
        myName: String,
        myId: String? = null,
    ): AlertEvent? {
        fun isMe(ev: IncidentEvent?): Boolean = isActor(ev, myName, myId)
        return when (event) {
            is AlertEvent.Assigned -> {
                val last = lastAssignment(events)
                if (last != null && (last.action == EventAction.Claimed || isMe(last))) null else event
            }
            is AlertEvent.ReportProgress -> when (event.incident.status) {
                IncidentStatus.Acknowledged -> {
                    val last = lastAssignment(events)
                    when {
                        last == null || last.action == EventAction.Claimed -> event
                        isMe(last) -> null
                        else -> event.copy(assignedByOther = true)
                    }
                }
                IncidentStatus.Cancelled -> if (isMe(lastCancel(events))) null else event
                else -> event
            }
            is AlertEvent.StaffCancelled -> if (isMe(lastCancel(events))) null else event
            is AlertEvent.Reassigned -> if (isMe(lastAssignment(events))) null else event
            is AlertEvent.NewReport ->
                if (event.mode == NewReportMode.NoOneOnDuty && team != null &&
                    someoneOnDuty(team, event.incident.type, event.incident.reporterId)
                ) null else event
            else -> event
        }
    }

    /**
     * True when a responder or admin on duty, other than the reporter, has [type] in their
     * specialties (the on-duty query of notify/index.ts).
     */
    fun someoneOnDuty(team: List<TeamMember>, type: IncidentType, reporterId: String?): Boolean =
        team.any { it.onDuty && it.id != reporterId && it.role.isStaff && type in it.specialties }

    /** The last claim / assignment / reassignment in the history. */
    private fun lastAssignment(events: List<IncidentEvent>?): IncidentEvent? =
        events?.lastOrNull {
            it.action == EventAction.Claimed || it.action == EventAction.Assigned || it.action == EventAction.Reassigned
        }

    private fun lastCancel(events: List<IncidentEvent>?): IncidentEvent? =
        events?.lastOrNull { it.action == EventAction.Cancelled }

    /** Did I cause [ev]? Ids win when both are known (names can repeat); otherwise compare names. */
    private fun isActor(ev: IncidentEvent?, myName: String, myId: String?): Boolean {
        if (ev == null) return false
        if (myId != null && ev.actorId != null) return ev.actorId == myId
        val actor = ev.actorName?.trim()
        return !actor.isNullOrEmpty() && myName.isNotBlank() && actor.equals(myName.trim(), ignoreCase = true)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** When the incident reached its current status (falls back to updated / created time). */
    fun statusTime(inc: Incident): Instant {
        val at: Instant? = when (inc.status) {
            IncidentStatus.Pending -> inc.createdAt
            IncidentStatus.Acknowledged -> inc.acknowledgedAt ?: inc.assignedAt
            IncidentStatus.EnRoute -> inc.enRouteAt
            IncidentStatus.OnScene -> inc.onSceneAt
            IncidentStatus.Resolved -> inc.resolvedAt
            IncidentStatus.Cancelled -> inc.cancelledAt
        }
        return at ?: inc.updatedAt ?: inc.createdAt
    }
}
