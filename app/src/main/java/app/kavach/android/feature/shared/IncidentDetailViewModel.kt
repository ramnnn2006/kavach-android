package app.kavach.android.feature.shared

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.kavach.android.R
import app.kavach.android.core.data.model.EventAction
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentEvent
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.TeamMember
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.repo.TeamRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.domain.Rules
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.ui.components.MessageQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/** What the detail screen shows. */
sealed interface DetailUi {
    data object Loading : DetailUi
    /** Loaded, but there is no such incident (or RLS hides it). */
    data object NotFound : DetailUi
    data class Failed(val error: KavachException) : DetailUi
    /** [refreshError] is set when a later refresh failed and [incident] is stale. */
    data class Ready(val incident: Incident, val refreshError: KavachException?) : DetailUi
}

/** The action currently running (disables the buttons). */
enum class DetailAction { Claim, Advance, Resolve, Cancel, Assign, Share }

/** Team list for the assign dialog. */
sealed interface TeamUi {
    data object Idle : TeamUi
    data object Loading : TeamUi
    data class Ready(val responders: List<TeamMember>) : TeamUi
    data class Failed(val error: KavachException) : TeamUi
}

/** Why a claim is not possible right now, or null when it is allowed. */
enum class ClaimBlock { WrongSpecialty }

/** Pure rules for what the signed-in staff member may do (IncidentDetail.jsx + Rules). */
object DetailRules {
    /**
     * Staff must cover the type; admins cover every type. Being off duty doesn't block a claim (the
     * website and claim_incident only check the specialty). A reason is returned so the button can
     * be shown disabled with an explanation.
     */
    fun claimBlock(profile: Profile?, type: IncidentType): ClaimBlock? =
        if (Rules.covers(profile, type)) null else ClaimBlock.WrongSpecialty

    fun canAdvance(incident: Incident, userId: String?, isAdmin: Boolean): Boolean {
        if (!incident.isActive || Rules.nextStatus(incident.status) == null) return false
        return isAdmin || (userId != null && incident.assignedTo == userId)
    }

    /** On-duty responders who handle the type first, then handlers, then by name. */
    fun sortForAssign(responders: List<TeamMember>, type: IncidentType): List<TeamMember> =
        responders
            .filter { it.role == UserRole.Responder }
            .sortedWith(
                compareByDescending<TeamMember> { it.onDuty }
                    .thenByDescending { type in it.specialties }
                    .thenBy { it.fullName.lowercase() },
            )
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class IncidentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val incidents: IncidentRepository,
    private val team: TeamRepository,
    session: SessionManager,
) : ViewModel() {

    val incidentId: String = savedStateHandle.toRoute<Routes.IncidentDetail>().id

    val messages = MessageQueue()

    val profile: StateFlow<Profile?> = session.profile

    private val retryTick = MutableStateFlow(0)

    /** The row the last action RPC returned, shown until the live query catches up (no stale buttons). */
    private val applied = MutableStateFlow<Incident?>(null)

    /** What [ui] last showed. Only touched by [ui]'s single collector. */
    private var shown: DetailUi = DetailUi.Loading

    val ui: StateFlow<DetailUi> = combine(
        retryTick.flatMapLatest { incidents.listen(incidentId) },
        applied,
    ) { live, local -> reduce(shown, live, local).also { shown = it } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUi.Loading)

    /**
     * The audit log, oldest first. Null until first loaded; the last list is kept through refreshes,
     * errors and re-subscriptions (coming back from the dialler) so the timeline doesn't flash empty.
     */
    val events: StateFlow<List<IncidentEvent>?> = retryTick
        .flatMapLatest { incidents.listenEvents(incidentId) }
        .mapNotNull { it.data }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Community first responders who said they're coming. Refetched only when the number of
     * first_responder_ack events changes (IncidentDetail.jsx), so it reuses the events subscription
     * instead of opening a second Realtime channel on incident_events.
     */
    val firstResponders: StateFlow<Int> = events
        .filterNotNull()
        .map { list -> list.count { it.action == EventAction.FirstResponderAck } }
        .distinctUntilChanged()
        .mapLatest { ackEvents ->
            try {
                incidents.firstResponderAcks(incidentId).size
            } catch (e: KavachException) {
                ackEvents // each new ack writes exactly one event
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _busy = MutableStateFlow<DetailAction?>(null)
    val busy: StateFlow<DetailAction?> = _busy.asStateFlow()

    private val _team = MutableStateFlow<TeamUi>(TeamUi.Idle)
    val teamUi: StateFlow<TeamUi> = _team.asStateFlow()

    private val feedbackChannel = Channel<Boolean>(Channel.BUFFERED)
    /** true = an action worked (confirm haptic), false = it failed (reject haptic). */
    val feedback: Flow<Boolean> = feedbackChannel.receiveAsFlow()

    private val reportChannel = Channel<JsonObject>(Channel.BUFFERED)
    /** The report to share, once loaded. */
    val reports: Flow<JsonObject> = reportChannel.receiveAsFlow()

    private var teamJob: Job? = null

    fun retry() { retryTick.value += 1 }

    private fun reduce(prev: DetailUi, live: Loadable<Incident?>, local: Incident?): DetailUi {
        val incident = newer(live.data, local)
        val error = live.error
        return when {
            incident != null -> DetailUi.Ready(incident, error)
            // A refresh or re-subscription is loading: keep what is on screen (no spinner flash)
            live.loading && (prev is DetailUi.Ready || prev is DetailUi.NotFound) -> prev
            error != null -> if (prev is DetailUi.Ready) prev.copy(refreshError = error) else DetailUi.Failed(error)
            live.loading -> DetailUi.Loading
            else -> DetailUi.NotFound
        }
    }

    /** The live row, unless an action just returned a newer one. */
    private fun newer(live: Incident?, local: Incident?): Incident? {
        if (live == null || local == null || local.id != live.id) return live
        val liveAt = live.updatedAt ?: return live
        val localAt = local.updatedAt ?: return live
        return if (localAt.isAfter(liveAt)) local else live
    }

    /**
     * Runs [block] as [action]: busy state, error snackbar, haptic feedback. [onSuccess] runs
     * after success (e.g. to close a dialog); [successRes] is the confirmation snackbar.
     */
    private fun perform(
        action: DetailAction,
        @StringRes successRes: Int,
        successArgs: List<Any> = emptyList(),
        onSuccess: () -> Unit = {},
        block: suspend () -> Incident,
    ) {
        if (_busy.value != null) return
        _busy.value = action
        viewModelScope.launch {
            try {
                // Show the new state right away; the live query follows
                applied.value = block()
                feedbackChannel.trySend(true)
                messages.send(successRes, *successArgs.toTypedArray())
                onSuccess()
            } catch (e: KavachException) {
                feedbackChannel.trySend(false)
                messages.error(e)
            } finally {
                _busy.value = null
            }
        }
    }

    fun claim() = perform(DetailAction.Claim, R.string.shared_detail_claimed_toast) { incidents.claim(incidentId) }

    /** One step forward: On my way, I've reached. (Resolve goes through [resolve] so a note can be added.) */
    fun advance(next: IncidentStatus) =
        perform(DetailAction.Advance, R.string.shared_detail_status_updated_toast) { incidents.advance(incidentId, next) }

    fun resolve(note: String, onDone: () -> Unit) =
        perform(DetailAction.Resolve, R.string.shared_detail_resolved_toast, onSuccess = onDone) {
            incidents.advance(incidentId, IncidentStatus.Resolved, note.trim().ifBlank { null })
        }

    fun cancel(reason: String, onDone: () -> Unit) =
        perform(DetailAction.Cancel, R.string.shared_detail_cancelled_toast, onSuccess = onDone) {
            incidents.cancel(incidentId, reason.trim().ifBlank { null })
        }

    fun assign(responder: TeamMember, onDone: () -> Unit) =
        perform(
            DetailAction.Assign,
            R.string.shared_detail_assigned_toast,
            successArgs = listOf(responder.fullName),
            onSuccess = onDone,
        ) { incidents.assign(incidentId, responder.id, (ui.value as? DetailUi.Ready)?.incident?.assignedTo) }

    /** Loads the responders for the assign dialog. */
    fun loadTeam() {
        if (teamJob?.isActive == true) return
        teamJob = viewModelScope.launch {
            _team.value = TeamUi.Loading
            _team.value = try {
                TeamUi.Ready(team.team())
            } catch (e: KavachException) {
                TeamUi.Failed(e)
            }
        }
    }

    /** Fetches the printable report; [reports] emits it for the screen to turn into text. */
    fun shareReport() {
        if (_busy.value != null) return
        _busy.value = DetailAction.Share
        viewModelScope.launch {
            try {
                reportChannel.trySend(incidents.incidentReport(incidentId))
            } catch (e: KavachException) {
                feedbackChannel.trySend(false)
                messages.error(e)
            } finally {
                _busy.value = null
            }
        }
    }
}
