package app.kavach.android.feature.resident

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.MedicalAlert
import app.kavach.android.core.data.model.PowerSource
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.SafetyCheck
import app.kavach.android.core.data.model.SafetyResponse
import app.kavach.android.core.data.model.SafetyResponseStatus
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.net.ConnectivityObserver
import app.kavach.android.core.data.outbox.OutboxRepository
import app.kavach.android.core.data.outbox.OutboxSnapshot
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.repo.SafetyCheckRepository
import app.kavach.android.core.data.repo.SocietyRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.ui.components.MessageQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** What Home needs to know about the society (live row, falling back to the profile's copy). */
data class SocietyInfo(val name: String?, val securityPhone: String?, val powerSource: PowerSource?)

/** The Safety Check card: the check that applies to me, my answer so far, and what is in flight. */
data class SafetyCardState(
    val check: SafetyCheck,
    /** False until my saved answer has been fetched (avoids flashing the buttons). */
    val answerLoaded: Boolean,
    val answer: SafetyResponse?,
    val busy: SafetyResponseStatus?,
    val changing: Boolean,
)

private data class SavedAnswer(val loaded: Boolean, val response: SafetyResponse?)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ResidentHomeViewModel @Inject constructor(
    private val session: SessionManager,
    private val incidents: IncidentRepository,
    private val societyRepo: SocietyRepository,
    private val safety: SafetyCheckRepository,
    private val outbox: OutboxRepository,
    private val connectivity: ConnectivityObserver,
) : ViewModel() {

    val messages = MessageQueue()
    val profile: StateFlow<Profile?> = session.profile

    /** Haptics for key actions: true = done (confirm), false = failed (reject). */
    private val _feedback = Channel<Boolean>(Channel.CONFLATED)
    val feedback: Flow<Boolean> = _feedback.receiveAsFlow()

    private val started = SharingStarted.WhileSubscribed(5_000)

    // ── My reports ───────────────────────────────────────────────────────────
    private val reportsTick = MutableStateFlow(0)
    fun retryReports() { reportsTick.value += 1 }

    val myReports: StateFlow<Loadable<List<Incident>>> = reportsTick
        .flatMapLatest { incidents.listenMyIncidents() }
        .keepLoadedData()
        .stateIn(viewModelScope, started, Loadable<List<Incident>>())

    // ── Society, power ───────────────────────────────────────────────────────
    private val societyRow = societyRepo.listenSociety().keepLoadedData().map { it.data }

    /** One instance, so the zone list survives restarts while the society stays on DG. */
    private val zonesLive = societyRepo.listenZones().keepLoadedData()

    val societyInfo: StateFlow<SocietyInfo> = combine(societyRow, profile) { society, p ->
        SocietyInfo(
            name = society?.name ?: p?.society?.name,
            securityPhone = society?.securityPhone?.takeIf { it.isNotBlank() } ?: p?.society?.securityPhone?.takeIf { it.isNotBlank() },
            powerSource = society?.powerSource ?: p?.society?.powerSource,
        )
    }.stateIn(viewModelScope, started, SocietyInfo(null, null, null))

    /** My tower, loaded only while the society is on DG power. */
    val homeZone: StateFlow<Zone?> = societyInfo
        .map { it.powerSource == PowerSource.Dg }
        .distinctUntilChanged()
        .flatMapLatest { onDg -> if (onDg) zonesLive.map { it.data } else flowOf<List<Zone>?>(null) }
        .combine(profile) { zones, p -> zones?.firstOrNull { it.id == p?.homeZoneId } }
        .stateIn(viewModelScope, started, null)

    // ── Offline outbox ───────────────────────────────────────────────────────
    val outboxSnapshot: StateFlow<OutboxSnapshot> = profile
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(OutboxSnapshot()) else outbox.observe(id) }
        .stateIn(viewModelScope, started, OutboxSnapshot())

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    fun sendNow() {
        if (_sending.value) return
        _sending.value = true
        viewModelScope.launch {
            try {
                val r = outbox.flush()
                when {
                    r.sent > 0 -> {
                        messages.send(R.string.resident_outbox_sent)
                        _feedback.trySend(true)
                    }
                    // flush() returns without trying anything while offline: say so instead of nothing
                    r.kept > 0 || !connectivity.isOnline.value -> messages.send(R.string.common_error_offline)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.error(e)
            } finally {
                _sending.value = false
            }
        }
    }

    fun dismissFailed(clientId: String) {
        viewModelScope.launch { outbox.remove(clientId) }
    }

    // ── Safety Check ─────────────────────────────────────────────────────────
    // Collected once (inside safetyCard): one Realtime subscription for the active check.
    private val activeCheck: Flow<SafetyCheck?> = combine(safety.listenActive().keepLoadedData(), profile) { check, p ->
        check.data?.takeIf { ResidentRules.safetyCheckApplies(it, p?.homeZoneId) }
    }.distinctUntilChanged()

    /** Last answer state per check, so coming back to Home doesn't hide my answer while it refetches. */
    private var lastSaved: Pair<String, SavedAnswer>? = null

    private fun savedAnswerFor(checkId: String): Flow<SavedAnswer> {
        var current = lastSaved?.takeIf { it.first == checkId }?.second ?: SavedAnswer(loaded = false, response = null)
        return safety.listenMyResponse(checkId).map { l ->
            current = when {
                // Fetch finished: a null answer means "not answered yet"; on an error keep what we had
                !l.loading -> SavedAnswer(loaded = true, response = l.data ?: current.response.takeIf { l.error != null })
                l.data != null -> current.copy(response = l.data)
                else -> current
            }
            lastSaved = checkId to current
            current
        }
    }

    /** RPC result for (checkId, answer), shown until Realtime catches up. */
    private val localAnswer = MutableStateFlow<Pair<String, SafetyResponse>?>(null)
    private val safetyBusy = MutableStateFlow<SafetyResponseStatus?>(null)

    /** The check whose answer is being changed; a new check never opens in "changing". */
    private val changingCheckId = MutableStateFlow<String?>(null)

    val safetyCard: StateFlow<SafetyCardState?> = activeCheck
        .flatMapLatest { check ->
            if (check == null) {
                flowOf<SafetyCardState?>(null)
            } else {
                combine(savedAnswerFor(check.id), localAnswer, safetyBusy, changingCheckId) { saved, local, busy, changingId ->
                    val localForThis = local?.takeIf { it.first == check.id }?.second
                    SafetyCardState(
                        check = check,
                        answerLoaded = saved.loaded || localForThis != null,
                        answer = ResidentRules.latestAnswer(saved.response, localForThis),
                        busy = busy,
                        changing = changingId == check.id,
                    )
                }
            }
        }
        .stateIn(viewModelScope, started, null)

    fun respondToSafetyCheck(status: SafetyResponseStatus, note: String?) {
        val card = safetyCard.value ?: return
        if (safetyBusy.value != null) return
        safetyBusy.value = status
        viewModelScope.launch {
            try {
                val response = safety.respond(card.check.id, status, note?.trim()?.takeIf { it.isNotEmpty() })
                localAnswer.value = card.check.id to response
                changingCheckId.value = null
                _feedback.trySend(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.error(e)
                _feedback.trySend(false)
            } finally {
                safetyBusy.value = null
            }
        }
    }

    fun changeSafetyAnswer() { changingCheckId.value = safetyCard.value?.check?.id }
    fun keepSafetyAnswer() { changingCheckId.value = null }

    // ── Community first responder: medical alerts ───────────────────────────
    private val medicalTick = MutableStateFlow(0)
    fun retryMedical() { medicalTick.value += 1 }

    val medicalAlerts: StateFlow<Loadable<List<MedicalAlert>>> = profile
        .map { it?.isFirstResponder == true }
        .distinctUntilChanged()
        .combine(medicalTick) { isResponder, _ -> isResponder }
        .flatMapLatest { isResponder ->
            if (isResponder) incidents.listenMedicalAlerts()
            else flowOf(Loadable<List<MedicalAlert>>(data = emptyList(), error = null, loading = false))
        }
        // The 15 s poll then only reaches the screen when the alerts actually changed
        .keepLoadedData()
        .stateIn(viewModelScope, started, Loadable<List<MedicalAlert>>())

    private val _coming = MutableStateFlow<Set<String>>(emptySet())
    val coming: StateFlow<Set<String>> = _coming.asStateFlow()
    private val _ackBusy = MutableStateFlow<String?>(null)
    val ackBusy: StateFlow<String?> = _ackBusy.asStateFlow()

    fun imComing(incidentId: String) {
        if (_ackBusy.value != null) return
        _ackBusy.value = incidentId
        viewModelScope.launch {
            try {
                incidents.firstResponderAck(incidentId)
                _coming.value = _coming.value + incidentId
                _feedback.trySend(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.error(e)
                _feedback.trySend(false)
            } finally {
                _ackBusy.value = null
            }
        }
    }
}
