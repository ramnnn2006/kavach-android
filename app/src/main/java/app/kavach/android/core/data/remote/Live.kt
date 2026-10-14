package app.kavach.android.core.data.remote

import app.kavach.android.core.data.net.AppForeground
import app.kavach.android.core.data.net.ConnectivityObserver
import app.kavach.android.core.di.ApplicationScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * State of a live query. [data] is kept when a later refetch fails, so screens can show stale
 * data with an error banner (like the website's useLive). [loading] is true until the first
 * fetch finished (or while a query restarts from remembered data); later background refetches
 * replace [data] without a loading step, and an [error] stays until a refetch succeeds.
 */
data class Loadable<out T>(
    val data: T? = null,
    val error: KavachException? = null,
    val loading: Boolean = true,
) {
    val isInitialLoading: Boolean get() = loading && data == null
}

/** A table to watch; [filterColumn]/[filterValue] add an `eq` filter (e.g. id=eq.<id>). */
data class LiveTable(val table: String, val filterColumn: String? = null, val filterValue: String? = null)

fun table(name: String) = LiveTable(name)
fun table(name: String, column: String, equals: String) = LiveTable(name, column, equals)

/**
 * Mirror of data/live.js: run fetch, then re-run it whenever any of the tables change
 * (Realtime applies RLS), after (re)subscribing, when the network returns, when the app comes
 * to the foreground and when a failing session refresh recovers. Bursts are coalesced (150 ms)
 * and a refetch never overlaps another. Fetching and decoding run off the main thread.
 */
@Singleton
class LiveQueries @Inject constructor(
    private val client: SupabaseClient,
    private val connectivity: ConnectivityObserver,
    private val foreground: AppForeground,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val seq = AtomicInteger()
    private val sharedQueries = object : LinkedHashMap<String, Flow<Loadable<Any?>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Flow<Loadable<Any?>>>?): Boolean = size > 48
    }

    /**
     * Last data of keyed queries (LRU, small), so a query that restarts (a screen shown again
     * after its WhileSubscribed window, a retry) starts from it instead of a spinner.
     */
    private val memory = object : LinkedHashMap<String, Remembered>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Remembered>?): Boolean =
            size > MEMORY_ENTRIES
    }

    private class Remembered(val owner: String, val data: Any)

    init {
        // Never show one user's data to the next one
        appScope.launch {
            client.auth.sessionStatus.collect { status ->
                if (status is SessionStatus.NotAuthenticated) {
                    synchronized(memory) { memory.clear() }
                    synchronized(sharedQueries) { sharedQueries.clear() }
                }
            }
        }
    }

    /**
     * [refresh] asks for extra refetches (e.g. SessionManager.refreshProfile()). [key] names the
     * query including all of its parameters; keyed queries remember their last data.
     */
    @OptIn(FlowPreview::class)
    fun <T> live(
        tables: List<LiveTable>,
        pollMillis: Long? = null,
        refresh: Flow<Unit>? = null,
        key: String? = null,
        fetch: suspend () -> T,
    ): Flow<Loadable<T>> {
        if (key == null) return liveQuery(tables, pollMillis, refresh, null, fetch)
        val owner = client.auth.currentUserOrNull()?.id ?: return liveQuery(tables, pollMillis, refresh, key, fetch)
        val identity = "$owner:$key"
        @Suppress("UNCHECKED_CAST")
        return synchronized(sharedQueries) {
            sharedQueries.getOrPut(identity) {
                liveQuery(tables, pollMillis, refresh, key, fetch)
                    .shareIn(appScope, SharingStarted.WhileSubscribed(5_000), replay = 1) as Flow<Loadable<Any?>>
            } as Flow<Loadable<T>>
        }
    }

    @OptIn(FlowPreview::class)
    private fun <T> liveQuery(
        tables: List<LiveTable>, pollMillis: Long?, refresh: Flow<Unit>?, key: String?, fetch: suspend () -> T,
    ): Flow<Loadable<T>> = channelFlow {
        val triggers = Channel<Unit>(Channel.CONFLATED)
        val trigger: () -> Unit = { triggers.trySend(Unit) }

        launch {
            val owner = if (key != null) ownerId() else null
            var last: T? = remembered<T>(key, owner)
            var loaded = last != null
            if (loaded) send(Loadable(data = last, error = null, loading = true))

            suspend fun run() {
                // Only the first load shows as loading; later refetches swap the data in quietly
                if (!loaded) send(Loadable<T>(data = null, error = null, loading = true))
                try {
                    val data = fetch()
                    last = data
                    loaded = true
                    if (key != null && owner != null && data != null) remember(key, owner, data)
                    send(Loadable(data = data, error = null, loading = false))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    send(Loadable(data = last, error = e.toKavach(connectivity.isOnline.value), loading = false))
                }
            }

            run()
            // A conflated channel keeps a trigger that arrives during a fetch: one more run after it
            triggers.receiveAsFlow().debounce(COALESCE_MS).collect { run() }
        }

        if (tables.isNotEmpty()) launch { follow(tables, trigger) }

        connectivity.isOnline.drop(1).filter { it }.onEach { trigger() }.launchIn(this)
        foreground.resumed.onEach { trigger() }.launchIn(this)
        sessionRecovered().onEach { trigger() }.launchIn(this)
        refresh?.onEach { trigger() }?.launchIn(this)

        if (pollMillis != null) {
            launch {
                // Poll only while the app is visible; nothing wakes the phone in the background
                foreground.isForeground.collectLatest { visible ->
                    if (visible) {
                        while (true) {
                            delay(pollMillis)
                            trigger()
                        }
                    }
                }
            }
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    fun <T> live(vararg tables: LiveTable, fetch: suspend () -> T): Flow<Loadable<T>> =
        live(tables.toList(), fetch = fetch)

    /** [live] that remembers its last data under [key] (include every query parameter in it). */
    fun <T> live(key: String, vararg tables: LiveTable, fetch: suspend () -> T): Flow<Loadable<T>> =
        live(tables.toList(), key = key, fetch = fetch)

    /**
     * Keeps one Realtime channel for [tables] while the query is collected. The library rejoins it
     * after a dropped websocket by itself; a channel the server closed (e.g. its token expired
     * while the phone slept) or whose join was refused is replaced at the next check (app back in
     * the foreground, network back, session token refreshed). The channel is always removed.
     */
    private suspend fun follow(tables: List<LiveTable>, onChange: () -> Unit) {
        val checks = Channel<Unit>(Channel.CONFLATED)
        coroutineScope {
            merge(
                foreground.resumed,
                connectivity.isOnline.drop(1).filter { it }.map { },
                tokenChanges(),
            ).onEach { checks.trySend(Unit) }.launchIn(this)

            while (true) {
                val ch = client.channel("live-${seq.incrementAndGet()}")
                try {
                    coroutineScope {
                        for (t in tables) {
                            ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                                table = t.table
                                if (t.filterColumn != null && t.filterValue != null) {
                                    filter(t.filterColumn, FilterOperator.EQ, t.filterValue)
                                }
                            }.onEach { onChange() }.launchIn(this)
                        }
                        val joiningSince = AtomicLong(0L)
                        ch.status.onEach { status ->
                            joiningSince.set(if (status == RealtimeChannel.Status.SUBSCRIBING) System.nanoTime() else 0L)
                            // Refetch after (re)joining so nothing missed while offline is lost
                            if (status == RealtimeChannel.Status.SUBSCRIBED) {
                                onChange()
                            }
                        }.launchIn(this)
                        launch {
                            try {
                                ch.subscribe()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                // Replaced at the next check; refetch triggers still work meanwhile
                            }
                        }
                        while (!isLost(ch, joiningSince.get())) checks.receive()
                        coroutineContext.cancelChildren()
                    }
                } finally {
                    release(ch)
                }
            }
        }
    }

    private fun isLost(ch: RealtimeChannel, joiningSince: Long): Boolean {
        val realtime = client.realtime
        // Closed by the server: the library drops it from its list and never rejoins it
        if (realtime.subscriptions[ch.topic] !== ch) return true
        // Join refused (e.g. an expired token): stuck joining although the socket is up
        return joiningSince != 0L &&
            realtime.status.value == Realtime.Status.CONNECTED &&
            System.nanoTime() - joiningSince > JOIN_TIMEOUT_NS
    }

    private fun release(ch: RealtimeChannel) {
        appScope.launch {
            try {
                // removeChannel() only leaves a joined channel; one still joining would stay open
                if (ch.status.value == RealtimeChannel.Status.SUBSCRIBING) ch.unsubscribe()
                client.realtime.removeChannel(ch)
            } catch (_: Throwable) {
                // Socket already gone: nothing left to leave
            }
        }
    }

    /** A usable session came back after a failing token refresh (earlier fetches failed). */
    private fun sessionRecovered(): Flow<Unit> = client.auth.sessionStatus
        .map { it is SessionStatus.Authenticated }
        .distinctUntilChanged()
        .drop(1)
        .filter { it }
        .map { }

    /** A new access token (refresh or recovery): a good moment to check the channels. */
    private fun tokenChanges(): Flow<Unit> = client.auth.sessionStatus
        .mapNotNull { (it as? SessionStatus.Authenticated)?.session?.accessToken }
        .distinctUntilChanged()
        .drop(1)
        .map { }

    private suspend fun ownerId(): String? =
        client.auth.currentUserOrNull()?.id
            ?: runCatching { client.auth.sessionManager.loadSession()?.user?.id }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun <T> remembered(key: String?, owner: String?): T? {
        if (key == null || owner == null) return null
        val entry = synchronized(memory) { memory[key] } ?: return null
        return if (entry.owner == owner) entry.data as T else null
    }

    private fun remember(key: String, owner: String, data: Any) {
        synchronized(memory) { memory[key] = Remembered(owner, data) }
    }

    private companion object {
        const val COALESCE_MS = 150L
        const val MEMORY_ENTRIES = 24
        const val JOIN_TIMEOUT_NS = 15_000_000_000L
    }
}
