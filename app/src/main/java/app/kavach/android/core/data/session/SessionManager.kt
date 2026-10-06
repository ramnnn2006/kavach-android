package app.kavach.android.core.data.session

import android.content.Context
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.outbox.OutboxRepository
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.LiveQueries
import app.kavach.android.core.data.remote.table
import app.kavach.android.core.data.repo.ProfileRepository
import app.kavach.android.core.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SessionState {
    /** Restoring a saved session. Show the splash. */
    data object Loading : SessionState
    data object SignedOut : SessionState
    /**
     * Signed in. [profile] is null while it loads (or if loading failed: see [profileError]).
     * The profile is live: role / duty / flat changes made elsewhere arrive here.
     */
    data class SignedIn(
        val userId: String,
        val profile: Profile?,
        val profileError: KavachException? = null,
    ) : SessionState
}

/** AuthContext for Android: one source of truth for "who is signed in" and their profile. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SessionManager @Inject constructor(
    private val client: SupabaseClient,
    private val profiles: ProfileRepository,
    private val live: LiveQueries,
    private val outbox: OutboxRepository,
    private val auth: AuthRepository,
    private val json: Json,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Last profile on disk, so the app opens straight away (and offline: a resident must still be
     * able to queue an emergency report). Cleared on sign-out. Read and written off the main thread.
     */
    private val cache by lazy { context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE) }
    @Volatile private var cached: Profile? = null

    private val userId = client.auth.sessionStatus
        .onEach { status ->
            // Covers sign-outs that don't go through signOut() (expired session)
            if (status is SessionStatus.NotAuthenticated && status.isSignOut) {
                appScope.launch {
                    runCatching { outbox.clear() }
                    forgetProfile()
                }
            }
        }
        .scan<SessionStatus, UserKey>(UserKey.Loading) { previous, status ->
            when (status) {
                is SessionStatus.Authenticated ->
                    status.session.user?.id?.let { UserKey.Id(it) } ?: previous as? UserKey.Id ?: savedUser()
                is SessionStatus.NotAuthenticated -> UserKey.None
                // A failed token refresh (usually offline) keeps the user signed in: the app stays
                // usable and requests fail as offline until the refresh recovers
                is SessionStatus.RefreshFailure -> previous as? UserKey.Id ?: savedUser()
                SessionStatus.Initializing -> UserKey.Loading
            }
        }
        .distinctUntilChanged()

    val state: StateFlow<SessionState> = userId
        .flatMapLatest { key ->
            when (key) {
                UserKey.Loading -> flowOf(SessionState.Loading)
                UserKey.None -> flowOf(SessionState.SignedOut)
                // One live query per user; refreshProfile() refetches inside it, so the profile
                // never goes back to null (which would tear the signed-in screens down)
                is UserKey.Id -> live.live(listOf(table("profiles", "id", key.id)), refresh = refreshRequests) {
                    profiles.fetch(key.id)
                }.map { loaded ->
                    val fresh = loaded.data
                    if (fresh != null) saveProfile(fresh)
                    val shown = fresh ?: if (loaded.loading || loaded.error != null) savedProfile(key.id) else null
                    SessionState.SignedIn(key.id, shown, loaded.error)
                }
            }
        }
        .stateIn(appScope, SharingStarted.Eagerly, SessionState.Loading)

    /** The signed-in profile, or null. */
    val profile: StateFlow<Profile?> = state
        .map { (it as? SessionState.SignedIn)?.profile }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    val currentUserId: String? get() = (state.value as? SessionState.SignedIn)?.userId

    /** Reload the profile now (e.g. after editing it). The current profile stays while it loads. */
    fun refreshProfile() {
        refreshRequests.tryEmit(Unit)
    }

    /** Log out: sign out (also offline), clear queued reports (call only after the user confirmed). */
    suspend fun signOut() {
        auth.signOutRemote()
        runCatching { outbox.clear() }
        forgetProfile()
    }

    /** User of the saved session (known while its token can't be refreshed, e.g. offline). */
    private suspend fun savedUser(): UserKey =
        runCatching { client.auth.sessionManager.loadSession()?.user?.id }.getOrNull()
            ?.let { UserKey.Id(it) } ?: UserKey.Loading

    private fun savedProfile(userId: String): Profile? {
        cached?.let { return it.takeIf { p -> p.id == userId } }
        val text = runCatching { cache.getString(KEY_PROFILE, null) }.getOrNull() ?: return null
        val profile = runCatching { json.decodeFromString(Profile.serializer(), text) }.getOrNull() ?: return null
        cached = profile
        return profile.takeIf { it.id == userId }
    }

    private fun saveProfile(profile: Profile) {
        if (profile == cached) return
        cached = profile
        runCatching { cache.edit().putString(KEY_PROFILE, json.encodeToString(Profile.serializer(), profile)).apply() }
    }

    private fun forgetProfile() {
        cached = null
        runCatching { cache.edit().remove(KEY_PROFILE).apply() }
    }

    private sealed interface UserKey {
        data object Loading : UserKey
        data object None : UserKey
        data class Id(val id: String) : UserKey
    }

    private companion object {
        const val CACHE_FILE = "kavach_session"
        const val KEY_PROFILE = "profile"
    }
}
