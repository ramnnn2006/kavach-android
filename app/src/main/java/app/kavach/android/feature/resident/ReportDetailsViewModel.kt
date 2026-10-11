package app.kavach.android.feature.resident

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.kavach.android.R
import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetKind
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.outbox.OutboxRepository
import app.kavach.android.core.data.outbox.SubmitResult
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.toKavach
import app.kavach.android.core.data.repo.AssetRepository
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.repo.SocietyRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.ui.components.MessageQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** Where the report flow is: filling in details, checking them, or saved on the phone for later. */
enum class ReportStep { Details, Review, Queued }

data class ReportUiState(
    val form: ReportForm,
    val step: ReportStep = ReportStep.Details,
    val fieldError: ReportFieldError? = null,
    val serverError: KavachException? = null,
    val sending: Boolean = false,
    val retrying: Boolean = false,
    /** The queued report was later refused by the server (outbox "failed"); it will not be retried. */
    val queuedFailed: Boolean = false,
)

/** Where a queued report is in the outbox. */
private enum class QueueState { Pending, Failed, Gone }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportDetailsViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val session: SessionManager,
    societyRepo: SocietyRepository,
    assetRepo: AssetRepository,
    private val outbox: OutboxRepository,
    private val incidents: IncidentRepository,
) : ViewModel() {

    /** Null when the route carries an unknown type: the screen then goes back to the type list. */
    val type: IncidentType? = IncidentType.fromWire(handle.toRoute<Routes.ReportDetails>().type)

    val messages = MessageQueue()
    val profile: StateFlow<Profile?> = session.profile

    private val started = SharingStarted.WhileSubscribed(5_000)

    val zones: StateFlow<Loadable<List<Zone>>> = societyRepo.listenZones()
        .stateIn(viewModelScope, started, Loadable<List<Zone>>())

    private val liftTick = MutableStateFlow(0)
    fun retryLifts() { liftTick.value += 1 }

    /** All lifts (filtered by tower in the UI, like the website). Only loaded for lift reports. */
    val lifts: StateFlow<Loadable<List<Asset>>> = liftTick
        .flatMapLatest {
            val flow: Flow<Loadable<List<Asset>>> =
                if (type == IncidentType.Lift) assetRepo.listenAssets(kind = AssetKind.Lift)
                else flowOf(Loadable<List<Asset>>(data = emptyList(), error = null, loading = false))
            flow
        }
        .stateIn(viewModelScope, started, Loadable<List<Asset>>())

    private val _openTracker = Channel<String>(Channel.BUFFERED)
    /** Incident id to open in the tracker (report sent, or delivered later from the outbox). */
    val openTracker: Flow<String> = _openTracker.receiveAsFlow()
    private var navigated = false

    private val _invalid = Channel<ReportFieldError>(Channel.CONFLATED)
    /** A validation message was just shown: the screen scrolls it into view (also on repeated taps). */
    val invalid: Flow<ReportFieldError> = _invalid.receiveAsFlow()

    // The form and step are kept in the SavedStateHandle, so a report in progress (or queued)
    // survives the process being killed while the resident is in the dialler.
    private val _ui = MutableStateFlow(restore())

    /**
     * Where this report is in the outbox while the queued step is on screen: refused later by the
     * server, or delivered while nobody listened (e.g. by the worker after the process was killed).
     */
    private val queueState: Flow<QueueState?> = _ui
        .map { it.step == ReportStep.Queued }
        .distinctUntilChanged()
        .flatMapLatest { queued ->
            val clientId = handle.get<String>(KEY_CLIENT_ID)
            if (!queued || clientId == null) {
                flowOf<QueueState?>(null)
            } else {
                outbox.observe(null)
                    .map { snapshot ->
                        when {
                            snapshot.pending.any { it.clientId == clientId } -> QueueState.Pending
                            snapshot.failed.any { it.clientId == clientId } -> QueueState.Failed
                            else -> QueueState.Gone
                        }
                    }
                    .distinctUntilChanged()
                    .onEach { if (it == QueueState.Gone) openDelivered(clientId) }
            }
        }

    /** Only watches the outbox while the screen collects it. */
    val ui: StateFlow<ReportUiState> = combine(_ui, queueState) { state, queue ->
        if (queue == QueueState.Failed) state.copy(queuedFailed = true) else state
    }.stateIn(viewModelScope, started, _ui.value)

    init {
        viewModelScope.launch { _ui.collect { persist(it) } }
        // A queued report that gets delivered in the background goes straight to its tracker
        viewModelScope.launch {
            outbox.sent.collect { sent ->
                if (sent.clientId == handle.get<String>(KEY_CLIENT_ID)) openTrackerOnce(sent.incident.id)
            }
        }
    }

    /** One idempotency key per report, kept across retries (and process death). */
    private fun clientId(): String =
        handle.get<String>(KEY_CLIENT_ID) ?: outbox.newClientId().also { handle[KEY_CLIENT_ID] = it }

    private fun openTrackerOnce(incidentId: String) {
        if (navigated) return
        navigated = true
        _openTracker.trySend(incidentId)
    }

    private fun restore(): ReportUiState {
        val where = handle.get<String>(KEY_WHERE)?.let { name -> ReportWhere.entries.firstOrNull { it.name == name } }
            ?: if (session.profile.value?.homeZoneId != null) ReportWhere.Home else ReportWhere.Else
        val form = ReportForm(
            where = where,
            zoneId = handle.get<String>(KEY_ZONE),
            floor = handle.get<Int>(KEY_FLOOR),
            spot = handle.get<String>(KEY_SPOT).orEmpty(),
            assetId = handle.get<String>(KEY_ASSET),
            people = handle.get<Int>(KEY_PEOPLE) ?: 1,
            note = handle.get<String>(KEY_NOTE).orEmpty(),
        )
        val step = handle.get<String>(KEY_STEP)?.let { name -> ReportStep.entries.firstOrNull { it.name == name } }
            ?: ReportStep.Details
        return ReportUiState(form = form, step = step)
    }

    private fun persist(state: ReportUiState) {
        val form = state.form
        handle[KEY_WHERE] = form.where.name
        handle[KEY_ZONE] = form.zoneId
        handle[KEY_FLOOR] = form.floor
        handle[KEY_SPOT] = form.spot
        handle[KEY_ASSET] = form.assetId
        handle[KEY_PEOPLE] = form.people
        handle[KEY_NOTE] = form.note
        handle[KEY_STEP] = state.step.name
    }

    /** The queued report left the outbox without a "sent" signal reaching us: find and open it. */
    private suspend fun openDelivered(clientId: String) {
        if (navigated) return
        val incidentId = try {
            incidents.myIncidents().firstOrNull { it.clientId == clientId }?.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (incidentId != null) openTrackerOnce(incidentId)
    }

    private fun editForm(change: (ReportForm) -> ReportForm) {
        _ui.update { it.copy(form = change(it.form)) }
    }

    fun chooseWhere(where: ReportWhere) {
        if (_ui.value.form.where == where) return
        _ui.update {
            it.copy(
                form = it.form.copy(where = where, zoneId = null, floor = null, assetId = null),
                fieldError = null,
                serverError = null,
            )
        }
    }

    fun setZone(zoneId: String?) {
        _ui.update { it.copy(form = it.form.copy(zoneId = zoneId, floor = null, assetId = null), fieldError = null) }
    }

    fun setFloor(floor: Int?) = editForm { it.copy(floor = floor) }

    fun setSpot(spot: String) {
        _ui.update {
            it.copy(
                form = it.form.copy(spot = spot.take(ReportRules.SPOT_MAX)),
                fieldError = if (it.fieldError == ReportFieldError.Spot) null else it.fieldError,
            )
        }
    }

    fun setAsset(assetId: String?) = editForm { it.copy(assetId = assetId) }

    fun setPeople(people: Int) = editForm { it.copy(people = people.coerceIn(1, ReportRules.MAX_PEOPLE)) }

    fun setNote(note: String) = editForm { it.copy(note = note.take(ReportRules.NOTE_MAX)) }

    /** Details → review. Shows the website's validation message when something is missing. */
    fun review() {
        val error = ReportRules.validate(_ui.value.form, zonesLoaded = zones.value.data != null)
        _ui.update {
            if (error != null) it.copy(fieldError = error, step = ReportStep.Details)
            else it.copy(fieldError = null, serverError = null, step = ReportStep.Review)
        }
        if (error != null) _invalid.trySend(error)
    }

    fun backToDetails() {
        _ui.update { if (it.sending) it else it.copy(step = ReportStep.Details) }
    }

    /** Send now, or keep it on the phone for later (OutboxRepository.submit). */
    fun send() {
        val t = type ?: return
        val state = _ui.value
        if (state.sending || navigated) return
        val error = ReportRules.validate(state.form, zonesLoaded = zones.value.data != null)
        if (error != null) {
            _ui.update { it.copy(step = ReportStep.Details, fieldError = error) }
            _invalid.trySend(error)
            return
        }
        _ui.update { it.copy(sending = true, serverError = null, fieldError = null) }
        val payload = ReportRules.buildPayload(t, state.form, session.profile.value?.flatId)
        val clientId = clientId()
        viewModelScope.launch {
            val result = try {
                outbox.submit(payload, clientId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. the outbox could not be written: say so instead of spinning forever
                SubmitResult.Rejected(e.toKavach())
            }
            when (result) {
                // Stay in "sending" until the tracker opens, so a second tap can't send twice
                is SubmitResult.Sent -> openTrackerOnce(result.incident.id)
                is SubmitResult.Queued -> _ui.update { it.copy(sending = false, step = ReportStep.Queued) }
                // Stay on the review step: the error shows next to the send button, with call security
                is SubmitResult.Rejected -> _ui.update { it.copy(sending = false, serverError = result.error) }
            }
        }
    }

    /** "Try sending now" on the queued screen. A delivered report opens its tracker by itself. */
    fun retryNow() {
        if (_ui.value.retrying) return
        _ui.update { it.copy(retrying = true) }
        viewModelScope.launch {
            try {
                val result = outbox.flush()
                // Nothing went out (offline, or the server did not answer): say so instead of staying silent
                if (result.sent == 0 && result.failed == 0) messages.send(R.string.common_error_offline)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.error(e)
            } finally {
                _ui.update { it.copy(retrying = false) }
            }
        }
    }

    private companion object {
        const val KEY_CLIENT_ID = "clientId"
        const val KEY_WHERE = "report_where"
        const val KEY_ZONE = "report_zone"
        const val KEY_FLOOR = "report_floor"
        const val KEY_SPOT = "report_spot"
        const val KEY_ASSET = "report_asset"
        const val KEY_PEOPLE = "report_people"
        const val KEY_NOTE = "report_note"
        const val KEY_STEP = "report_step"
    }
}
