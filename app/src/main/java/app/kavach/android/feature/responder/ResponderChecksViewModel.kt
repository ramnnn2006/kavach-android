package app.kavach.android.feature.responder

import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.ComplianceItem
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.data.repo.AssetRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.ui.format.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Everything the Checks screen draws. */
data class ChecksUi(
    val checks: Loadable<ChecksData> = Loadable(),
    val profile: Profile? = null,
)

@HiltViewModel
class ResponderChecksViewModel @Inject constructor(
    private val assets: AssetRepository,
    session: SessionManager,
) : ActionViewModel() {

    private val retryCount = MutableStateFlow(0)
    private val _markDone = MutableStateFlow(DialogUi<ComplianceItem>())

    /** The mark-done note dialog. */
    val markDone: StateFlow<DialogUi<ComplianceItem>> = _markDone.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val compliance: Flow<Loadable<List<ComplianceItem>>> = retryCount
        .flatMapLatest { assets.listenCompliance() }
        .keepLastData()

    val ui: StateFlow<ChecksUi> = combine(compliance, session.profile) { rows, profile ->
        ChecksUi(
            checks = Loadable(
                data = rows.data?.let { ChecksData(checkSections(it, profile)) },
                error = rows.error,
                loading = rows.loading,
            ),
            profile = profile,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChecksUi())

    fun retry() { retryCount.update { it + 1 } }

    fun openMarkDone(item: ComplianceItem) { _markDone.value = DialogUi(target = item) }

    fun dismissMarkDone() {
        if (!_markDone.value.busy) _markDone.value = DialogUi()
    }

    /** Log the check as done with an optional [notes]; the dialog closes on success. */
    fun confirmMarkDone(notes: String) {
        val state = _markDone.value
        val item = state.target ?: return
        if (state.busy) return
        _markDone.value = state.copy(busy = true, error = null)
        viewModelScope.launch {
            var nextDue: LocalDate? = null
            val done = attempt(onError = { e -> _markDone.update { it.copy(busy = false, error = e.messageRes()) } }) {
                nextDue = assets.logCheck(item.id, notes.ifBlank { null })
            }
            if (done != null) {
                _markDone.value = DialogUi()
                confirm()
                // "Done. Next due 8 Oct." like the website; generic wording if the date didn't come back
                val due = nextDue
                if (due != null) {
                    messages.send(R.string.responder_check_logged_due_toast, Format.shortDate(due))
                } else {
                    messages.send(R.string.responder_check_logged_toast)
                }
            }
        }
    }
}
