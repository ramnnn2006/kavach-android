package app.kavach.android.feature.responder

import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.repo.ProfileRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.domain.Rules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Everything the Alerts screen draws. */
data class AlertsUi(
    val queue: Loadable<QueueSplit> = Loadable(),
    val profile: Profile? = null,
    /** The switch position, including a change that is still being saved. */
    val onDuty: Boolean = false,
    val dutyBusy: Boolean = false,
    /** Incident ids with an action in flight. */
    val busy: Set<String> = emptySet(),
)

@HiltViewModel
class ResponderAlertsViewModel @Inject constructor(
    private val incidents: IncidentRepository,
    private val profiles: ProfileRepository,
    private val session: SessionManager,
) : ActionViewModel() {

    private val retryCount = MutableStateFlow(0)
    private val patches = MutableStateFlow<Map<String, Incident>>(emptyMap())
    private val busyIds = MutableStateFlow<Set<String>>(emptySet())
    private val dutyPending = MutableStateFlow<Boolean?>(null)
    private val _resolve = MutableStateFlow(DialogUi<Incident>())

    /** The resolve-note dialog. */
    val resolve: StateFlow<DialogUi<Incident>> = _resolve.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val queue: Flow<Loadable<List<Incident>>> = retryCount
        .flatMapLatest { incidents.listenQueue() }
        .onEach { q ->
            // A completed fetch makes the RPC rows it has caught up with unnecessary
            val rows = q.data
            if (rows != null && !q.loading && q.error == null) patches.update { prunePatches(rows, it) }
        }
        .keepLastData()

    val ui: StateFlow<AlertsUi> = combine(queue, patches, session.profile, busyIds, dutyPending) { q, p, profile, busy, pending ->
        val me = profile?.id ?: session.currentUserId
        AlertsUi(
            queue = Loadable(
                data = q.data?.let { splitQueue(applyPatches(it, p), me) },
                error = q.error,
                loading = q.loading,
            ),
            profile = profile,
            onDuty = pending ?: (profile?.onDuty == true),
            dutyBusy = pending != null,
            busy = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUi())

    fun retry() { retryCount.update { it + 1 } }

    /** The duty switch (ProfileRepository.setOnDuty). */
    fun setOnDuty(on: Boolean) {
        if (dutyPending.value != null) return
        dutyPending.value = on
        viewModelScope.launch {
            val saved = attempt { profiles.setOnDuty(on) } != null
            if (saved) {
                session.refreshProfile()
                confirm()
                messages.send(if (on) R.string.responder_duty_on_toast else R.string.responder_duty_off_toast)
                // Keep the switch where the user put it until the live profile catches up
                withTimeoutOrNull(3_000) { session.profile.first { it?.onDuty == on } }
            }
            dutyPending.value = null
        }
    }

    /** Claim an open alert. */
    fun claim(inc: Incident) {
        if (inc.id in busyIds.value) return
        busyIds.update { it + inc.id }
        viewModelScope.launch {
            val updated = attempt { incidents.claim(inc.id) }
            if (updated != null) {
                patch(updated)
                confirm()
                messages.send(R.string.responder_claimed_toast)
            }
            busyIds.update { it - inc.id }
        }
    }

    /** The next-step button: En route, On scene, or (after a note) Resolved. */
    fun next(inc: Incident) {
        val next = Rules.nextStatus(inc.status) ?: return
        if (next == IncidentStatus.Resolved) {
            _resolve.value = DialogUi(target = inc)
            return
        }
        if (inc.id in busyIds.value) return
        busyIds.update { it + inc.id }
        viewModelScope.launch {
            val updated = attempt { incidents.advance(inc.id, next) }
            if (updated != null) {
                patch(updated)
                confirm()
                messages.send(R.string.responder_status_updated_toast)
            }
            busyIds.update { it - inc.id }
        }
    }

    fun dismissResolve() {
        if (!_resolve.value.busy) _resolve.value = DialogUi()
    }

    /** Resolve with an optional [note]; the dialog closes on success and shows the error otherwise. */
    fun confirmResolve(note: String) {
        val state = _resolve.value
        val inc = state.target ?: return
        if (state.busy) return
        _resolve.value = state.copy(busy = true, error = null)
        viewModelScope.launch {
            val updated = attempt(onError = { e -> _resolve.update { it.copy(busy = false, error = e.messageRes()) } }) {
                incidents.advance(inc.id, IncidentStatus.Resolved, note.ifBlank { null })
            }
            if (updated != null) {
                patch(updated)
                _resolve.value = DialogUi()
                confirm()
                messages.send(R.string.responder_resolved_toast)
            }
        }
    }

    private fun patch(updated: Incident) {
        patches.update { it + (updated.id to updated) }
    }
}
