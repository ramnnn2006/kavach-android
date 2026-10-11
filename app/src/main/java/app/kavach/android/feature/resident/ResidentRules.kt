package app.kavach.android.feature.resident

import app.kavach.android.core.data.model.Contact
import app.kavach.android.core.data.model.ContactKind
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.MedicalAlert
import app.kavach.android.core.data.model.NewIncident
import app.kavach.android.core.data.model.Notice
import app.kavach.android.core.data.model.SafetyCheck
import app.kavach.android.core.data.model.SafetyCheckScope
import app.kavach.android.core.data.model.SafetyResponse
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.remote.Loadable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

// Pure resident-area logic (no Android): kept free of resources so it can be unit tested.
// Mirrors screens/resident/** of the website.

/**
 * Keeps the last loaded data of a live query while it (re)starts or a refetch fails, and reports
 * `loading` only while there is still nothing to show. Create it once per ViewModel property: it
 * remembers across restarts (WhileSubscribed after the screen was hidden, retries), so coming back
 * to a screen doesn't flash empty, and background refreshes don't re-emit unchanged state.
 * Works for nullable data too (a loaded `null`, e.g. "no active Safety Check", counts as loaded).
 */
internal fun <T> Flow<Loadable<T>>.keepLoadedData(): Flow<Loadable<T>> {
    var hasData = false
    var last: T? = null
    return map { l ->
        val loaded = l.data != null || (!l.loading && l.error == null)
        if (loaded) {
            last = l.data
            hasData = true
        }
        val data = if (loaded || !hasData) l.data else last
        Loadable(data = data, error = l.error, loading = l.loading && !loaded && !hasData)
    }
}

/** ReportDetails: "At my home" / "Somewhere else". */
enum class ReportWhere { Home, Else }

/** Which field a validation message belongs to. */
enum class ReportFieldError { Zone, Spot }

/** What the resident has entered so far in the report form. */
data class ReportForm(
    val where: ReportWhere,
    val zoneId: String? = null,
    val floor: Int? = null,
    val spot: String = "",
    val assetId: String? = null,
    val people: Int = 1,
    val note: String = "",
)

object ReportRules {
    const val MAX_PEOPLE = 50
    const val NOTE_MAX = 500
    /** The characters-left count shows only this close to the limit. */
    const val NOTE_WARN = 100
    const val SPOT_MAX = 200

    /** The tower the report is about: my home tower, or the one picked under "somewhere else". */
    fun targetZoneId(form: ReportForm, homeZoneId: String?): String? = when (form.where) {
        ReportWhere.Home -> homeZoneId
        ReportWhere.Else -> form.zoneId?.takeIf { it.isNotBlank() }
    }

    /** Number of floors above ground in the target zone (0 = unknown, no floor picker). */
    fun floorCount(zone: Zone?): Int = zone?.floors?.takeIf { it > 0 } ?: 0

    /** Floor picker shows for another place, and for a lift at home too. */
    fun showFloor(floorCount: Int, where: ReportWhere, isLift: Boolean): Boolean =
        floorCount > 0 && (where == ReportWhere.Else || isLift)

    /**
     * validate() of ReportDetails. [zonesLoaded] is true once the zone list loaded (even if empty).
     * Elsewhere needs a place when places are known, else a written spot.
     */
    fun validate(form: ReportForm, zonesLoaded: Boolean): ReportFieldError? {
        if (form.where != ReportWhere.Else) return null
        if (zonesLoaded && form.zoneId.isNullOrBlank()) return ReportFieldError.Zone
        if (!zonesLoaded && form.spot.isBlank()) return ReportFieldError.Spot
        return null
    }

    /** buildPayload() of ReportDetails. */
    fun buildPayload(type: IncidentType, form: ReportForm, homeFlatId: String?): NewIncident {
        val isLift = type == IncidentType.Lift
        return NewIncident(
            type = type,
            zoneId = if (form.where == ReportWhere.Else) form.zoneId?.takeIf { it.isNotBlank() } else null,
            flatId = if (form.where == ReportWhere.Home) homeFlatId?.takeIf { it.isNotBlank() } else null,
            floor = form.floor,
            assetId = if (isLift) form.assetId?.takeIf { it.isNotBlank() } else null,
            locationNote = if (form.where == ReportWhere.Else) form.spot.trim().ifEmpty { null } else null,
            description = form.note.trim().ifEmpty { null },
            peopleAffected = form.people.coerceIn(1, MAX_PEOPLE),
        )
    }
}

object ResidentRules {
    /** My reports, active ones first; each group keeps its (newest first) order. */
    fun activeFirst(reports: List<Incident>): List<Incident> = reports.sortedBy { !it.isActive }

    /** Home: open reports, and the latest [limit] closed ones. */
    fun activeReports(reports: List<Incident>): List<Incident> = reports.filter { it.isActive }

    fun recentReports(reports: List<Incident>, limit: Int = 3): List<Incident> =
        reports.filter { !it.isActive }.take(limit)

    /** Does this Safety Check ask me? Society-wide, or my tower. */
    fun safetyCheckApplies(check: SafetyCheck?, homeZoneId: String?): Boolean {
        if (check == null) return false
        return when (check.scope) {
            SafetyCheckScope.Society -> true
            SafetyCheckScope.Zone -> homeZoneId != null && check.zoneId == homeZoneId
        }
    }

    /** The newer of the saved answer and the RPC result shown until Realtime catches up. */
    fun latestAnswer(saved: SafetyResponse?, local: SafetyResponse?): SafetyResponse? {
        val answers = listOfNotNull(saved, local)
        return answers.maxByOrNull { it.respondedAt }
    }

    /** Neighbours already on the way, not counting me. */
    fun otherResponders(alert: MedicalAlert): Int =
        (alert.ackCount - if (alert.iAmComing) 1 else 0).coerceAtLeast(0)

    /** Notices split into pinned and the rest (server order kept). */
    fun splitNotices(notices: List<Notice>): Pair<List<Notice>, List<Notice>> =
        notices.partition { it.pinned }

    /** Notices still current at [now]: the server filters at fetch time, this drops ones that expired since. */
    fun visibleNotices(notices: List<Notice>, now: Instant): List<Notice> =
        notices.filter { n -> n.expiresAt.let { it == null || it.isAfter(now) } }

    /** Cancel is offered to the reporter while nobody is on the way yet. */
    fun canCancel(status: IncidentStatus, isMine: Boolean): Boolean =
        isMine && (status == IncidentStatus.Pending || status == IncidentStatus.Acknowledged)

    /** Escalated banner: still unclaimed after the escalation. */
    fun isEscalatedWaiting(incident: Incident): Boolean =
        incident.status == IncidentStatus.Pending && incident.escalationLevel > 1

    /** Whole minutes from sent to resolved, at least 1. */
    fun minutesToResolve(incident: Incident): Long {
        val end = incident.resolvedAt ?: return 1
        val mins = Math.round(java.time.Duration.between(incident.createdAt, end).toMillis() / 60_000.0)
        return maxOf(1L, mins)
    }

    /** One step of the tracker. */
    data class StepState(val done: Boolean, val current: Boolean, val time: Instant?)

    /**
     * Tracker steps: sent, accepted, on the way, help arrived, resolved. A step is done up to the
     * current status; the next one is "current". Times are shown only for done steps.
     */
    fun stepStates(incident: Incident): List<StepState> {
        val times = listOf(incident.createdAt, incident.acknowledgedAt, incident.enRouteAt, incident.onSceneAt, incident.resolvedAt)
        val reached = incident.status.step
        return times.mapIndexed { i, t ->
            StepState(done = i <= reached, current = i == reached + 1, time = if (i <= reached) t else null)
        }
    }

    /** Tracker "Add details": what the resident is editing. */
    data class DetailsDraft(val people: Int, val note: String)

    /** What actually changed; null fields are left unchanged by update_incident_details. */
    data class DetailsChange(val peopleAffected: Int?, val description: String?) {
        val isEmpty: Boolean get() = peopleAffected == null && description == null
    }

    fun draftFor(incident: Incident): DetailsDraft =
        DetailsDraft(people = incident.peopleAffected, note = incident.description.orEmpty())

    /** saveDetails() of the Tracker: send people / note only when they changed. */
    fun detailsChange(incident: Incident, draft: DetailsDraft): DetailsChange {
        val note = draft.note.trim()
        return DetailsChange(
            peopleAffected = draft.people.takeIf { it != incident.peopleAffected },
            description = note.takeIf { it != incident.description.orEmpty() },
        )
    }

    /** Contacts split by kind (server order kept): society desk numbers, emergency services. */
    fun groupContacts(contacts: List<Contact>): Pair<List<Contact>, List<Contact>> =
        contacts.filter { it.kind == ContactKind.Society } to contacts.filter { it.kind == ContactKind.Emergency }

    /** "Name · available" detail line of a contact: role and availability, when given. */
    fun contactDetail(contact: Contact): String =
        listOfNotNull(contact.roleLabel?.takeIf { it.isNotBlank() }, contact.available?.takeIf { it.isNotBlank() }).joinToString(" · ")
}
