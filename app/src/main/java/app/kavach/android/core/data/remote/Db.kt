package app.kavach.android.core.data.remote

import app.kavach.android.core.data.net.ConnectivityObserver
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Thin wrapper used by every repository: runs a Supabase call and turns any failure into a
 * [KavachException] (db.js q() / rpc()). Screens never touch SupabaseClient directly.
 */
@Singleton
class Db @Inject constructor(
    val client: SupabaseClient,
    private val connectivity: ConnectivityObserver,
) {
    val isOnline: Boolean get() = connectivity.isOnline.value

    /**
     * True while the token refresh keeps failing (usually offline). Requests would then go out
     * without the user's token and PostgREST would answer as an anonymous user (empty lists,
     * refused writes), so they fail as Offline until the session recovers.
     */
    val isSessionStale: Boolean get() = client.auth.sessionStatus.value is SessionStatus.RefreshFailure

    /** Runs [block] off the main thread (JSON decoding) and maps every failure. */
    suspend fun <T> call(block: suspend SupabaseClient.() -> T): T {
        if (isSessionStale) throw KavachException(ErrorCode.Offline)
        return try {
            withContext(Dispatchers.Default) { client.block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw mapError(e)
        }
    }

    /** RPC call; fails fast with Offline when there is no network (db.js rpc()). */
    suspend fun rpc(function: String, params: JsonObject = JsonObject(emptyMap())): PostgrestResult {
        if (!isOnline) throw KavachException(ErrorCode.Offline)
        return call { postgrest.rpc(function, params) }
    }

    /** [rpc] plus decoding of the result, so a malformed row also becomes a KavachException. */
    suspend inline fun <reified R : Any> rpcAs(function: String, params: JsonObject = JsonObject(emptyMap())): R {
        if (!isOnline) throw KavachException(ErrorCode.Offline)
        return call { postgrest.rpc(function, params).decodeAs<R>() }
    }

    fun mapError(e: Throwable): KavachException = e.toKavach(isOnline)

    /** Wait until a saved session has been restored (cold starts from a worker or service). */
    suspend fun awaitSession() {
        runCatching { client.auth.awaitInitialization() }
    }

    /**
     * Waits up to [timeoutMs] while a failing token refresh retries. True when a session can be
     * used for requests now.
     */
    suspend fun awaitUsableSession(timeoutMs: Long): Boolean {
        awaitSession()
        if (isSessionStale) {
            withTimeoutOrNull(timeoutMs) {
                client.auth.sessionStatus.first { it is SessionStatus.Authenticated || it is SessionStatus.NotAuthenticated }
            }
        }
        return myIdOrNull() != null
    }

    fun myIdOrNull(): String? = client.auth.currentUserOrNull()?.id

    /**
     * The signed-in user, also while a token refresh is failing (then read from the saved
     * session). Null when signed out.
     */
    suspend fun knownUserId(): String? =
        myIdOrNull() ?: runCatching { client.auth.sessionManager.loadSession()?.user?.id }.getOrNull()

    fun myId(): String = myIdOrNull()
        ?: throw if (isSessionStale) KavachException(ErrorCode.Offline) else KavachException(ErrorCode.Forbidden, "Not signed in")
}
