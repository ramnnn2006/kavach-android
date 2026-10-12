package app.kavach.android.feature.responder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class ResponderHistoryViewModel @Inject constructor(
    incidents: IncidentRepository,
    session: SessionManager,
) : ViewModel() {

    private val retryCount = MutableStateFlow(0)

    /** Incidents I handled and that are now resolved or cancelled, newest first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<Loadable<List<Incident>>> = retryCount
        .flatMapLatest { incidents.listenMyHandled() }
        .keepLastData()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable())

    val profile: StateFlow<Profile?> = session.profile

    fun retry() { retryCount.update { it + 1 } }
}
