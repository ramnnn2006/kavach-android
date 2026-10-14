package app.kavach.android.feature.resident

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentEvent
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.ui.components.MessageQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrackerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    session: SessionManager,
    private val incidents: IncidentRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Routes.Tracker>()
    val incidentId: String = route.id
    /** Opened right after sending: show the "alert sent" confirmation. */
    val justSent: Boolean = route.justSent

    val messages = MessageQueue()
    val profile: StateFlow<Profile?> = session.profile

    private val started = SharingStarted.WhileSubscribed(5_000)
    private val tick = MutableStateFlow(0)
    fun retry() { tick.value += 1 }

    val incident: StateFlow<Loadable<Incident?>> = tick
        .flatMapLatest { incidents.listen(incidentId, ownOnly = true) }
        .stateIn(viewModelScope, started, Loadable<Incident?>())

    val events: StateFlow<Loadable<List<IncidentEvent>>> = tick
        .flatMapLatest { incidents.listenEvents(incidentId) }
        .stateIn(viewModelScope, started, Loadable<List<IncidentEvent>>())

    /**
     * Neighbours with medical training who said they are coming. Only medical reports get them,
     * and every "I'm coming" adds an incident event, so the count is re-read when the events change
     * instead of holding a second live subscription to the same events.
     */
    val responderCount: StateFlow<Int> = combine(
        incident.map { state -> state.data?.let { it.type == IncidentType.Medical && it.isActive } == true }.distinctUntilChanged(),
        events.map { it.data }.distinctUntilChanged(),
    ) { medicalActive, eventList -> if (medicalActive) eventList else null }
        .mapLatest { eventList ->
            if (eventList == null) {
                0
            } else {
                try {
                    incidents.firstResponderAcks(incidentId).size
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // keep the last count
                }
            }
        }
        .filterNotNull()
        .stateIn(viewModelScope, started, 0)

    // ── Cancel ───────────────────────────────────────────────────────────────
    private val _cancelling = MutableStateFlow(false)
    val cancelling: StateFlow<Boolean> = _cancelling.asStateFlow()
    private val _cancelOpen = MutableStateFlow(savedStateHandle["cancel_open"] ?: false)
    /** The "Cancel this alert?" dialog is open (kept open on failure, closed on success). */
    val cancelOpen: StateFlow<Boolean> = _cancelOpen.asStateFlow()
    private val _cancelError = MutableStateFlow<Throwable?>(null)
    /** Why the last cancel failed, shown inside the dialog. */
    val cancelError: StateFlow<Throwable?> = _cancelError.asStateFlow()

    fun openCancel() {
        _cancelError.value = null
        _cancelOpen.value = true
    }

    fun dismissCancel() {
        if (_cancelling.value) return
        _cancelOpen.value = false
        _cancelError.value = null
    }

    fun cancel(reason: String?) {
        if (_cancelling.value) return
        _cancelling.value = true
        _cancelError.value = null
        viewModelScope.launch {
            try {
                incidents.cancel(incidentId, reason?.trim()?.takeIf { it.isNotEmpty() })
                _cancelOpen.value = false
                messages.send(R.string.resident_cancelled_toast)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _cancelError.value = e
            } finally {
                _cancelling.value = false
            }
        }
    }

    // ── Add details ──────────────────────────────────────────────────────────
    private val _draft = MutableStateFlow(savedStateHandle.get<Int>("draft_people")?.let { ResidentRules.DetailsDraft(it, savedStateHandle["draft_note"] ?: "") })
    /** Non-null while the resident is editing people / note. */
    val draft: StateFlow<ResidentRules.DetailsDraft?> = _draft.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    init {
        viewModelScope.launch { _cancelOpen.collect { savedStateHandle["cancel_open"] = it } }
        viewModelScope.launch { _draft.collect {
            savedStateHandle["draft_people"] = it?.people
            savedStateHandle["draft_note"] = it?.note
        } }
    }

    fun startEdit(incident: Incident) { _draft.value = ResidentRules.draftFor(incident) }
    fun cancelEdit() { if (!_saving.value) _draft.value = null }
    fun setDraftPeople(people: Int) {
        _draft.update { d -> d?.copy(people = people.coerceIn(1, ReportRules.MAX_PEOPLE)) }
    }
    fun setDraftNote(note: String) {
        _draft.update { d -> d?.copy(note = note.take(ReportRules.NOTE_MAX)) }
    }

    fun saveDetails(incident: Incident) {
        val draft = _draft.value ?: return
        if (_saving.value) return
        val change = ResidentRules.detailsChange(incident, draft)
        if (change.isEmpty) {
            _draft.value = null
            return
        }
        _saving.value = true
        viewModelScope.launch {
            try {
                incidents.updateDetails(
                    id = incident.id,
                    description = change.description,
                    peopleAffected = change.peopleAffected ?: incident.peopleAffected,
                )
                _draft.value = null
                messages.send(R.string.resident_details_saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.error(e)
            } finally {
                _saving.value = false
            }
        }
    }
}
