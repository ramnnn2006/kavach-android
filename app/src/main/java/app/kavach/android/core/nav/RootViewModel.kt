package app.kavach.android.core.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.prefs.SettingsStore
import app.kavach.android.core.data.remote.ErrorCode
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.data.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the app shows at the top level (App.jsx Protected + routes). */
sealed interface RootUi {
    data object Loading : RootUi
    data object SignedOut : RootUi
    /** Signed in but the profile isn't loaded (yet / failed). */
    data class ProfileMissing(val error: KavachException?) : RootUi
    /** First run on this phone: notification permission, battery exclusion. */
    data object Setup : RootUi
    /** Resident without a flat (email accounts not on the approved list). */
    data object NeedsHome : RootUi
    /** First time this role signs in on this phone: a few swipeable cards on how Kavach works. */
    data class Intro(val role: UserRole) : RootUi
    data class Ready(val role: UserRole) : RootUi
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RootViewModel @Inject constructor(
    private val session: SessionManager,
    private val settings: SettingsStore,
) : ViewModel() {

    /**
     * The last profile shown for the signed-in user. While the same user stays signed in, a
     * profile that is momentarily null (refreshProfile() restarts the live query; a refetch
     * failed) keeps the current screen instead of tearing the role shell down to the splash and
     * back to the home tab. Cleared on sign-out; ignored for another user.
     */
    private var lastProfile: Profile? = null

    // Declared before [ui]: its first emission can already run toUi()
    private val _pendingLink = MutableStateFlow<DeepLink?>(null)

    /** Bumped by [retryProfile] so the splash shows the spinner again while it retries. */
    private val retryTick = MutableStateFlow(0)

    val ui: StateFlow<RootUi> = combine(session.state, settings.setupDone, settings.introSeenRoles, retryTick) { state, setupDone, introSeen, _ ->
        Triple(state, setupDone, introSeen)
    }
        .transformLatest { (state, setupDone, introSeen) ->
            val shown = toUi(state, setupDone, introSeen)
            emit(shown)
            // SessionState can't tell "still loading" from "loaded, but there is no profile row":
            // after a while offer Try again / Log out instead of an endless spinner.
            if (shown == RootUi.Loading) {
                delay(PROFILE_WAIT_MS)
                emit(RootUi.ProfileMissing(KavachException(ErrorCode.Offline, "Session restore timed out")))
            } else if (shown is RootUi.ProfileMissing && shown.error == null) {
                delay(PROFILE_WAIT_MS)
                emit(RootUi.ProfileMissing(KavachException(ErrorCode.Unknown, "profile not loaded")))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RootUi.Loading)

    private fun toUi(state: SessionState, setupDone: Boolean, introSeen: Set<String>): RootUi = when (state) {
        SessionState.Loading -> RootUi.Loading
        SessionState.SignedOut -> {
            lastProfile = null
            // A link meant for the previous user must not open in the next user's shell
            _pendingLink.value = null
            RootUi.SignedOut
        }
        is SessionState.SignedIn -> {
            val loaded = state.profile
            if (loaded != null) lastProfile = loaded
            val p = loaded ?: lastProfile?.takeIf { it.id == state.userId }
            when {
                p == null -> RootUi.ProfileMissing(state.profileError)
                !setupDone -> RootUi.Setup
                p.role == UserRole.Resident && p.flatId == null -> RootUi.NeedsHome
                p.role.wire !in introSeen -> RootUi.Intro(p.role)
                else -> RootUi.Ready(p.role)
            }
        }
    }

    /** A notification tap waiting to be opened once the role shell is showing. */
    val pendingLink: StateFlow<DeepLink?> = _pendingLink.asStateFlow()

    fun onDeepLink(link: DeepLink?) { if (link != null) _pendingLink.value = link }
    fun consumeLink() { _pendingLink.value = null }

    fun retryProfile() {
        retryTick.value += 1
        session.refreshProfile()
    }

    fun signOut() { viewModelScope.launch { session.signOut() } }
    fun finishSetup() { viewModelScope.launch { settings.setSetupDone(true) } }
    fun finishIntro(role: UserRole) { viewModelScope.launch { settings.markIntroSeen(role) } }

    private companion object {
        const val PROFILE_WAIT_MS = 15_000L
    }
}
