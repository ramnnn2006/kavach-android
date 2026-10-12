package app.kavach.android.feature.alerts

import android.content.Context
import android.os.PowerManager
import android.util.Log
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentEvent
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.MedicalAlert
import app.kavach.android.core.data.model.Notice
import app.kavach.android.core.data.model.SafetyCheck
import app.kavach.android.core.data.model.TeamMember
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.net.ConnectivityObserver
import app.kavach.android.core.data.remote.LiveQueries
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.table
import app.kavach.android.core.data.repo.IncidentFilters
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.repo.NoticeRepository
import app.kavach.android.core.data.repo.SafetyCheckRepository
import app.kavach.android.core.data.repo.StatusFilter
import app.kavach.android.core.data.repo.TeamRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.data.session.SessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps live subscriptions through the repositories while [AlertService] runs and decides, with
 * [AlertPolicy], what to notify. Also offers a one-shot [catchUp] for the WorkManager worker.
 *
 * First snapshot of a run = baseline: events from before the persisted "last seen" time (or all of
 * them, on the very first run) are recorded silently. Later snapshots notify every new key.
 *
 * Channels held while running: my own reports (filtered to my rows), plus the staff queue for
 * responders and admins, or Safety Check and notices for residents. Community first responders
 * also poll the medical list (no Realtime access to those rows).
 */
@Singleton
class AlertEngine @Inject constructor(
    @ApplicationContext context: Context,
    private val session: SessionManager,
    private val incidents: IncidentRepository,
    private val safety: SafetyCheckRepository,
    private val notices: NoticeRepository,
    private val teamRepo: TeamRepository,
    private val live: LiveQueries,
    private val connectivity: ConnectivityObserver,
    private val store: AlertStateStore,
    private val notifier: AlertNotifier,
) {
    private val power: PowerManager? = context.getSystemService(PowerManager::class.java)
    private val watching = AtomicInteger()

    /** True while the service is subscribed for a signed-in person (the catch-up is then redundant). */
    val isWatching: Boolean get() = watching.get() > 0

    /** Per-run memory: which sources delivered their first snapshot, and the previous ones. */
    private class RunState {
        private val firsts: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var prevQueue: List<Incident>? = null
        @Volatile var prevCheckId: String? = null
        @Volatile var prevMedical: Set<String> = emptySet()
        @Volatile var prevNotices: Set<String>? = null

        /** True the first time it is asked for [source]. */
        fun isFirst(source: String): Boolean = firsts.add(source)
    }

    /** The active Safety Check and whether I already answered it. */
    private class SafetySnapshot(val check: SafetyCheck?, val answered: Boolean)

    /**
     * Runs until cancelled. Restarts the subscriptions whenever something that decides who gets
     * what changes (role, duty, specialties, first-responder skill, home tower), and once a day so
     * every source is read afresh and the store can forget old keys.
     */
    suspend fun run() {
        session.state
            // While the profile reloads (refreshProfile) it is briefly missing: keep watching
            .filter { it is SessionState.SignedOut || (it is SessionState.SignedIn && it.profile != null) }
            .map { (it as? SessionState.SignedIn)?.profile?.toAlertUser() }
            .distinctUntilChanged()
            .collectLatest { user ->
                if (user == null) return@collectLatest
                while (true) {
                    store.ensureOwner(user.id)
                    withTimeoutOrNull(REBASELINE_MS) { watch(user) }
                }
            }
    }

    private suspend fun watch(user: AlertUser) {
        val state = RunState()
        watching.incrementAndGet()
        try {
            coroutineScope {
                // Every role: progress of my own reports. Filtered to my rows: for staff an
                // unfiltered channel refetches on every change in the society, including the
                // 30-second urgency tick.
                launch {
                    live.live(table("incidents", "reporter_id", user.id)) { incidents.myIncidents() }.collect { l ->
                        ready(l)?.let { safely { onMine(user, it, state) } }
                    }
                }
                if (user.isStaff) {
                    launch {
                        incidents.listenQueue().collect { l ->
                            ready(l)?.let { safely { onQueue(user, it, state) } }
                        }
                    }
                }
                if (user.role == UserRole.Resident) {
                    launch {
                        // My answers too, so the notification goes away once I have answered
                        live.live(
                            table("safety_checks"),
                            table("safety_check_responses", "profile_id", user.id),
                        ) { safetySnapshot() }.collect { l ->
                            ready(l)?.let { safely { onSafety(user, it, state) } }
                        }
                    }
                    launch {
                        notices.listenNotices().collect { l ->
                            ready(l)?.let { safely { onNotices(user, it, state) } }
                        }
                    }
                    if (user.isFirstResponder) {
                        // First responders cannot subscribe to medical rows (RLS): poll. Only with a
                        // network, and less often while the screen is off.
                        launch {
                            while (true) {
                                connectivity.isOnline.first { it }
                                safely { onMedical(user, incidents.medicalAlerts(), state) }
                                delay(if (power?.isInteractive != false) MEDICAL_POLL_MS else MEDICAL_POLL_IDLE_MS)
                            }
                        }
                    }
                }
            }
        } finally {
            watching.decrementAndGet()
        }
    }

    /**
     * One-shot check with plain reads, for the periodic worker. Safe to run while the service is
     * running: keys are claimed atomically.
     */
    suspend fun catchUp(user: AlertUser) {
        store.ensureOwner(user.id)
        val state = RunState()
        safely { onMine(user, incidents.myIncidents(), state) }
        if (user.isStaff) safely { onQueue(user, incidents.queue(), state) }
        if (user.role == UserRole.Resident) {
            safely { onSafety(user, safetySnapshot(), state) }
            safely { onNotices(user, notices.notices(), state) }
            if (user.isFirstResponder) safely { onMedical(user, incidents.medicalAlerts(), state) }
        }
    }

    // ── Sources ───────────────────────────────────────────────────────────────

    private suspend fun onMine(user: AlertUser, mine: List<Incident>, state: RunState) {
        deliver(user, AlertPolicy.reporterEvents(user, mine), SRC_MINE, state.isFirst(SRC_MINE))
    }

    private suspend fun onQueue(user: AlertUser, queue: List<Incident>, state: RunState) {
        val previous = state.prevQueue
        val now = Instant.now()

        // Somebody else took it, or it closed: take the "new alert" notification down
        AlertPolicy.staleIncidentIds(user, queue, previous).forEach { notifier.cancelIncident(it) }

        val extra = ArrayList<AlertEvent>()
        if (previous == null) {
            // First look after a gap (service start, catch-up): a report I was handling may have
            // been cancelled meanwhile, and the active queue no longer shows it
            val recent = IncidentFilters(
                status = StatusFilter.Only(IncidentStatus.Cancelled),
                from = now.minus(AlertTtl.Update.maxAge),
                limit = 50,
            )
            attempt { incidents.list(recent) }?.let { extra += AlertPolicy.cancelledWhileAway(user, it) }
        }
        extra += AlertPolicy.reassignedAway(user, previous, queue, now)
        for (id in AlertPolicy.assignedAndGone(user, previous, queue)) {
            val gone = attempt { incidents.get(id) }
            if (gone != null && gone.status == IncidentStatus.Cancelled) {
                extra += AlertPolicy.staffCancelled(gone, now)
            } else {
                notifier.cancelIncident(id)
            }
        }

        state.prevQueue = queue
        deliver(user, AlertPolicy.staffEvents(user, queue) + extra, SRC_QUEUE, state.isFirst(SRC_QUEUE))
    }

    private suspend fun safetySnapshot(): SafetySnapshot {
        val check = safety.active()
        // A failed lookup counts as "not answered": a duplicate beats a missed Safety Check
        val answered = check != null && attempt { safety.myResponse(check.id) } != null
        return SafetySnapshot(check, answered)
    }

    private suspend fun onSafety(user: AlertUser, snapshot: SafetySnapshot, state: RunState) {
        val check = snapshot.check
        val previous = state.prevCheckId
        if (previous != null && previous != check?.id) notifier.cancelTag(safetyTag(previous))
        state.prevCheckId = check?.id
        if (check != null && (snapshot.answered || !check.isActive)) notifier.cancelTag(safetyTag(check.id))
        deliver(user, AlertPolicy.safetyEvents(user, check, snapshot.answered), SRC_SAFETY, state.isFirst(SRC_SAFETY))
    }

    private suspend fun onNotices(user: AlertUser, list: List<Notice>, state: RunState) {
        // Deleted or expired since the last snapshot: take its notification down
        val ids = list.mapTo(HashSet()) { it.id }
        state.prevNotices?.forEach { if (it !in ids) notifier.cancelTag(noticeTag(it)) }
        state.prevNotices = ids
        deliver(user, AlertPolicy.noticeEvents(user, list), SRC_NOTICES, state.isFirst(SRC_NOTICES))
    }

    private suspend fun onMedical(user: AlertUser, alerts: List<MedicalAlert>, state: RunState) {
        AlertPolicy.staleMedicalIds(state.prevMedical, alerts).forEach { notifier.cancelIncident(it) }
        state.prevMedical = AlertPolicy.openMedicalIds(alerts)
        deliver(user, AlertPolicy.medicalEvents(user, alerts), SRC_MEDICAL, state.isFirst(SRC_MEDICAL))
    }

    // ── Delivery ──────────────────────────────────────────────────────────────

    /**
     * Shows what is new in [events]. [first] = first snapshot of this run: apply the cutoff from
     * the persisted last-seen time and record older events silently. Later snapshots show every
     * key not seen before.
     */
    private suspend fun deliver(user: AlertUser, events: List<AlertEvent>, source: String, first: Boolean) {
        val now = Instant.now()
        val cutoff = if (first) AlertPolicy.cutoff(store.lastSeen(source)) else null
        val fresh = AlertPolicy.fresh(events, store.seenKeys(), cutoff, now)

        // Baseline for the rest; also keeps the keys still in use from being pruned
        val freshKeys = fresh.mapTo(HashSet()) { it.key }
        store.markSeen(events.map { it.key }.filter { it !in freshKeys })

        // One notification per tag: when a batch has two for one incident (a new report and its
        // escalation found together after a gap), only the later, more specific one is posted
        val byTag = LinkedHashMap<String, AlertEvent>()
        for (event in fresh) {
            // Claiming first makes the service and the catch-up worker race-free
            if (!store.claim(event.key)) continue
            val shown = resolve(user, event) ?: continue
            byTag[shown.tag] = shown
        }
        for (event in byTag.values) notifier.show(event, user)
        store.setLastSeen(source, now)
    }

    private suspend fun resolve(user: AlertUser, event: AlertEvent): AlertEvent? {
        var history: List<IncidentEvent>? = null
        var team: List<TeamMember>? = null
        when (event.lookup) {
            AlertLookup.None -> Unit
            AlertLookup.Events -> {
                val id = event.incidentId
                if (id != null) history = attempt { incidents.events(id) }
            }
            AlertLookup.Team -> team = attempt { teamRepo.team() }
        }
        return AlertPolicy.resolve(event, history, team, user.fullName, user.id)
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    /** Only a settled, successful snapshot is worth acting on. */
    private fun <T> ready(l: Loadable<T>): T? = if (l.loading || l.error != null) null else l.data

    /** Runs [block]; a failure is logged and never kills the subscription. */
    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Alert check failed", e)
        }
    }

    /** Result of [block], or null if it failed (offline, no permission). */
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private companion object {
        const val TAG = "KavachAlerts"
        const val MEDICAL_POLL_MS = 30_000L

        /** Medical poll while the screen is off: each poll wakes the radio. */
        const val MEDICAL_POLL_IDLE_MS = 60_000L

        /** Subscriptions are renewed once a day (see [run]). */
        const val REBASELINE_MS = 24L * 60 * 60 * 1000
        const val SRC_MINE = "mine"
        const val SRC_QUEUE = "queue"
        const val SRC_SAFETY = "safety"
        const val SRC_NOTICES = "notices"
        const val SRC_MEDICAL = "medical"
    }
}
