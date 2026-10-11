package app.kavach.android.feature.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.model.ZoneKind
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.repo.ProfileRepository
import app.kavach.android.core.data.repo.SocietyRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.ui.components.MessageQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

sealed interface TowersUi {
    data object Loading : TowersUi
    data class Ready(val towers: List<Zone>) : TowersUi
    data class Failed(val error: KavachException) : TowersUi
}

/** Pure helpers for the home form. */
object WelcomeLogic {
    /** Floors a tower offers: ground (0) up to its top floor; empty when the tower has no floor count. */
    fun floorOptions(floors: Int?): List<Int> = if (floors == null || floors <= 0) emptyList() else (0..floors).toList()

    /** Only towers can be a home (the website filters zones by kind). */
    fun towersOf(zones: List<Zone>): List<Zone> = zones.filter { it.kind == ZoneKind.Tower }
}

/** Fallback for a resident with no flat (Welcome.jsx): pick tower, floor and flat number. */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val society: SocietyRepository,
    private val profiles: ProfileRepository,
    private val session: SessionManager,
) : ViewModel() {

    val messages = MessageQueue()

    val profile: StateFlow<Profile?> = session.profile

    private val retry = MutableStateFlow(0)

    /**
     * Towers of the user's society. Follows the profile, so an account linked to a society while
     * this screen is open gets its towers; reloads on [loadTowers]. Fetched once per society:
     * the upstream is the in-memory session profile, so sharing lazily costs nothing in the
     * background and the form isn't swapped for a spinner each time the app comes back.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val towers: StateFlow<TowersUi> = combine(
        session.profile.map { it?.societyId }.distinctUntilChanged(),
        retry,
    ) { societyId, _ -> societyId }
        .flatMapLatest { societyId ->
            if (societyId == null) {
                flowOf<TowersUi>(TowersUi.Ready(emptyList()))
            } else {
                flow<TowersUi> {
                    emit(TowersUi.Loading)
                    val result: TowersUi = try {
                        TowersUi.Ready(WelcomeLogic.towersOf(society.zones()))
                    } catch (e: KavachException) {
                        TowersUi.Failed(e)
                    }
                    emit(result)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, TowersUi.Loading)

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    fun loadTowers() = retry.update { it + 1 }

    /** [floor] is null when the tower has no floor list. */
    fun save(zoneId: String, floor: Int?, flatNumber: String) {
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            try {
                profiles.setMyHome(zoneId, floor, flatNumber.trim())
                session.refreshProfile()
                messages.send(R.string.shared_welcome_saved)
            } catch (e: KavachException) {
                messages.error(e)
            } finally {
                _saving.value = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                session.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.error(e)
            }
        }
    }
}
