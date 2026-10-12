package app.kavach.android.feature.alerts

import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.MedicalAlert
import app.kavach.android.core.data.model.Notice
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.SafetyCheck
import app.kavach.android.core.data.model.UserRole
import java.time.Duration
import java.time.Instant

// Pure model of "who gets notified about what". No Android classes in this file or in
// AlertPolicy.kt, so the rules can be unit tested on the JVM.

/** Notification channel ids. */
enum class AlertChannelId(val id: String) {
    Emergency("emergency"),
    Status("status"),
    SafetyCheck("safety_check"),
    Notices("notices"),
    Service("service"),
}

/**
 * How long after it happened an event is still worth a notification when it is found late
 * (service was dead, catch-up worker). Mirrors TTL_URGENT / TTL_UPDATE in notify/index.ts.
 */
enum class AlertTtl(val maxAge: Duration) {
    Urgent(Duration.ofMinutes(60)),
    Update(Duration.ofHours(6)),
    Notice(Duration.ofHours(24)),
}

/** Extra data needed before an event may be shown (see AlertPolicy.resolve). */
enum class AlertLookup { None, Events, Team }

enum class NewReportMode {
    /** I am on duty and cover this type: show Claim. */
    OnDuty,

    /** I am an admin not on duty: only shown when nobody else on duty covers the type. */
    NoOneOnDuty,
}

/** The signed-in person, reduced to what the notify rules look at. */
data class AlertUser(
    val id: String,
    val role: UserRole,
    val fullName: String,
    val onDuty: Boolean,
    val specialties: List<IncidentType>,
    val isFirstResponder: Boolean,
    val homeZoneId: String?,
) {
    val isStaff: Boolean get() = role.isStaff

    /**
     * The notify rule (notify/index.ts forNewIncident): on-duty staff hear of a new report only when
     * their specialties include the type, admins included. Admins outside it get the "no one on
     * duty" variant instead (see AlertPolicy.staffEvents).
     */
    fun covers(type: IncidentType): Boolean = type in specialties
}

fun Profile.toAlertUser(): AlertUser = AlertUser(
    id = id,
    role = role,
    fullName = fullName,
    onDuty = onDuty,
    specialties = specialties,
    isFirstResponder = isFirstResponder,
    homeZoneId = homeZoneId,
)

fun incidentTag(incidentId: String) = "incident-$incidentId"
fun sosTag(incidentId: String) = "sos-$incidentId"
fun safetyTag(checkId: String) = "safety-$checkId"
fun noticeTag(noticeId: String) = "notice-$noticeId"

/**
 * Something worth a notification. [key] is what we remember so the same thing is never shown
 * twice (across service restarts and the catch-up worker); [tag] is the notification it
 * replaces (the website's push `tag`); [at] is when it happened on the server.
 */
sealed interface AlertEvent {
    val key: String
    val tag: String
    val at: Instant
    val channel: AlertChannelId
    val ttl: AlertTtl
    val lookup: AlertLookup
    val incidentId: String?

    /** A pending report in my specialties (responders on duty, admins). */
    data class NewReport(val incident: Incident, val mode: NewReportMode) : AlertEvent {
        override val key: String get() = "inc:${incident.id}:new"
        override val tag: String get() = incidentTag(incident.id)
        override val at: Instant get() = incident.createdAt
        override val channel: AlertChannelId get() = AlertChannelId.Emergency
        override val ttl: AlertTtl get() = AlertTtl.Urgent
        override val lookup: AlertLookup get() = if (mode == NewReportMode.NoOneOnDuty) AlertLookup.Team else AlertLookup.None
        override val incidentId: String get() = incident.id
    }

    /** A report was assigned to me. Dropped when I claimed or assigned it myself (see AlertPolicy.resolve). */
    data class Assigned(val incident: Incident, val userId: String) : AlertEvent {
        override val key: String get() = "inc:${incident.id}:assigned:$userId"
        override val tag: String get() = incidentTag(incident.id)
        override val at: Instant get() = incident.assignedAt ?: incident.updatedAt ?: incident.createdAt
        override val channel: AlertChannelId get() = AlertChannelId.Emergency
        override val ttl: AlertTtl get() = AlertTtl.Urgent
        override val lookup: AlertLookup get() = AlertLookup.Events
        override val incidentId: String get() = incident.id
    }

    /** Still unclaimed and escalated (admins). */
    data class Escalated(val incident: Incident) : AlertEvent {
        override val key: String get() = "inc:${incident.id}:esc:${incident.escalationLevel}"
        override val tag: String get() = incidentTag(incident.id)
        override val at: Instant get() = incident.updatedAt ?: incident.createdAt
        override val channel: AlertChannelId get() = AlertChannelId.Emergency
        override val ttl: AlertTtl get() = AlertTtl.Urgent
        override val lookup: AlertLookup get() = AlertLookup.None
        override val incidentId: String get() = incident.id
    }

    /** Medical alert for a community first responder. */
    data class MedicalHelp(val alert: MedicalAlert) : AlertEvent {
        override val key: String get() = "medical:${alert.id}"
        override val tag: String get() = incidentTag(alert.id)
        override val at: Instant get() = alert.createdAt
        override val channel: AlertChannelId get() = AlertChannelId.Emergency
        override val ttl: AlertTtl get() = AlertTtl.Urgent
        override val lookup: AlertLookup get() = AlertLookup.None
        override val incidentId: String get() = alert.id
    }

    /** Progress of a report I made. [assignedByOther] = an admin assigned it instead of a self-claim. */
    data class ReportProgress(val incident: Incident, val assignedByOther: Boolean = false) : AlertEvent {
        override val key: String get() = "inc:${incident.id}:status:${incident.status.wire}"
        override val tag: String get() = sosTag(incident.id)
        override val at: Instant get() = AlertPolicy.statusTime(incident)
        override val channel: AlertChannelId get() = AlertChannelId.Status
        override val ttl: AlertTtl get() = AlertTtl.Update
        override val lookup: AlertLookup
            get() = when (incident.status) {
                IncidentStatus.Acknowledged, IncidentStatus.Cancelled -> AlertLookup.Events
                else -> AlertLookup.None
            }
        override val incidentId: String get() = incident.id
    }

    /** The report I was working on was cancelled by someone else. */
    data class StaffCancelled(val incident: Incident, override val at: Instant) : AlertEvent {
        override val key: String get() = "inc:${incident.id}:cancelled"
        override val tag: String get() = incidentTag(incident.id)
        override val channel: AlertChannelId get() = AlertChannelId.Status
        override val ttl: AlertTtl get() = AlertTtl.Update
        override val lookup: AlertLookup get() = AlertLookup.Events
        override val incidentId: String get() = incident.id
    }

    /** The report I was working on now belongs to somebody else. Dropped when I reassigned it myself. */
    data class Reassigned(val incident: Incident, override val at: Instant) : AlertEvent {
        override val key: String get() = "inc:${incident.id}:reassigned:${incident.assignedTo ?: "none"}"
        override val tag: String get() = incidentTag(incident.id)
        override val channel: AlertChannelId get() = AlertChannelId.Status
        override val ttl: AlertTtl get() = AlertTtl.Update
        override val lookup: AlertLookup get() = AlertLookup.Events
        override val incidentId: String get() = incident.id
    }

    /** A Safety Check that applies to me. */
    data class SafetyCheckStarted(val check: SafetyCheck) : AlertEvent {
        override val key: String get() = "check:${check.id}"
        override val tag: String get() = safetyTag(check.id)
        override val at: Instant get() = check.startedAt
        override val channel: AlertChannelId get() = AlertChannelId.SafetyCheck
        override val ttl: AlertTtl get() = AlertTtl.Urgent
        override val lookup: AlertLookup get() = AlertLookup.None
        override val incidentId: String? get() = null
    }

    /** A new society notice. */
    data class NoticePosted(val notice: Notice) : AlertEvent {
        override val key: String get() = "notice:${notice.id}"
        override val tag: String get() = noticeTag(notice.id)
        override val at: Instant get() = notice.createdAt
        override val channel: AlertChannelId get() = AlertChannelId.Notices
        override val ttl: AlertTtl get() = AlertTtl.Notice
        override val lookup: AlertLookup get() = AlertLookup.None
        override val incidentId: String? get() = null
    }
}
