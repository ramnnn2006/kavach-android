package app.kavach.android.core.data.outbox

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.kavach.android.core.config.AppConfig
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.NewIncident
import app.kavach.android.core.data.net.ConnectivityObserver
import app.kavach.android.core.data.remote.Db
import app.kavach.android.core.data.remote.ErrorCode
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

data class OutboxItem(
    val clientId: String,
    val userId: String?,
    val report: NewIncident,
    val createdAt: Instant,
    val attempts: Int,
    val lastError: ErrorCode?,
    /** The server rejected it permanently (invalid / forbidden); never retried. */
    val failed: Boolean,
)

/** Queued reports for one user: still to send, and permanently rejected (shown until dismissed). */
data class OutboxSnapshot(val pending: List<OutboxItem> = emptyList(), val failed: List<OutboxItem> = emptyList()) {
    val isEmpty: Boolean get() = pending.isEmpty() && failed.isEmpty()
}

data class FlushResult(val sent: Int = 0, val kept: Int = 0, val failed: Int = 0)

data class OutboxSent(val clientId: String, val incident: Incident)

sealed interface SubmitResult {
    /** Reached the server. Open the tracker for [incident]. */
    data class Sent(val incident: Incident) : SubmitResult
    /** Saved on the phone and will be sent automatically. Show the "queued" state + call button. */
    data class Queued(val clientId: String, val reason: ErrorCode) : SubmitResult
    /** Server refused it (invalid / forbidden / …). Show error.messageRes(); nothing was queued. */
    data class Rejected(val error: KavachException) : SubmitResult
}

/**
 * Offline outbox for resident reports (data/outbox.js). A report that can't reach the server is
 * stored in Room and re-sent by [OutboxWorker] when the network returns. create() is idempotent by
 * clientId, so a retry after a dropped response never creates a second incident.
 */
@Singleton
class OutboxRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: OutboxDao,
    private val incidents: IncidentRepository,
    private val db: Db,
    private val connectivity: ConnectivityObserver,
    private val json: Json,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val flushLock = Mutex()
    private val _sent = MutableSharedFlow<OutboxSent>(extraBufferCapacity = 8)

    /** Queued reports that were delivered later. ReportDetails listens to jump to the tracker. */
    val sent: SharedFlow<OutboxSent> = _sent.asSharedFlow()

    /** Items for [userId] (all users when null). */
    fun observe(userId: String?): Flow<OutboxSnapshot> = dao.observeAll().map { rows ->
        val mine = rows.filter { userId == null || it.userId == userId }.map { it.toItem() }
        OutboxSnapshot(pending = mine.filterNot { it.failed }, failed = mine.filter { it.failed })
    }

    /** New idempotency key for one report; keep it for every retry of that report. */
    fun newClientId(): String = UUID.randomUUID().toString()

    /**
     * Send a report now, or queue it (ReportDetails send()). Offline, a 15 s timeout or an
     * unknown error queue it; a slow request that lands after the timeout clears the queue entry.
     * Runs in the app scope, so leaving the screen mid-send never loses the report.
     */
    suspend fun submit(report: NewIncident, clientId: String): SubmitResult =
        try {
            appScope.async { submitNow(report, clientId) }.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Could not even save it on the phone: say so instead of crashing the screen
            SubmitResult.Rejected(db.mapError(e))
        }

    private suspend fun submitNow(report: NewIncident, clientId: String): SubmitResult {
        // Also known while a token refresh is failing, so the queued report stays theirs
        val userId = db.knownUserId()
        // No network, or no usable session (requests would run as an anonymous user)
        if (!connectivity.isOnline.value || db.isSessionStale) {
            enqueue(clientId, report, userId, ErrorCode.Offline)
            return SubmitResult.Queued(clientId, ErrorCode.Offline)
        }
        val request = appScope.async { incidents.create(report, clientId) }
        return try {
            val incident = withTimeoutOrNull(AppConfig.SEND_TIMEOUT_MS) { request.await() }
            if (incident != null) {
                SubmitResult.Sent(incident)
            } else {
                // Queue first, then watch the slow request: markSent() always runs after the row exists
                enqueue(clientId, report, userId, ErrorCode.Timeout)
                appScope.launch { runCatching { request.await() }.onSuccess { markSent(clientId, it) } }
                SubmitResult.Queued(clientId, ErrorCode.Timeout)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val err = db.mapError(e)
            if (err.code.shouldQueue) {
                enqueue(clientId, report, userId, err.code)
                SubmitResult.Queued(clientId, err.code)
            } else {
                SubmitResult.Rejected(err)
            }
        }
    }

    suspend fun enqueue(clientId: String, report: NewIncident, userId: String?, lastError: ErrorCode? = null) {
        val existing = dao.get(clientId)
        dao.put(
            OutboxEntity(
                clientId = clientId,
                userId = userId ?: existing?.userId,
                payloadJson = json.encodeToString(NewIncident.serializer(), report),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                attempts = (existing?.attempts ?: 0) + 1,
                lastErrorCode = lastError?.name,
                lastErrorMessage = null,
                state = STATE_PENDING,
            ),
        )
        scheduleFlush()
    }

    suspend fun remove(clientId: String) {
        dao.delete(clientId)
    }

    /** Remove everything (sign-out). */
    suspend fun clear() = dao.clear()

    /**
     * A queued report reached the server some other way. [sent] fires only for the call that
     * actually removed the entry, so it is announced once per report.
     */
    suspend fun markSent(clientId: String, incident: Incident) {
        if (dao.delete(clientId) > 0) _sent.tryEmit(OutboxSent(clientId, incident))
    }

    /**
     * Send every pending report of the signed-in user, oldest first. Sent → removed; offline /
     * unknown → kept; invalid / forbidden → marked failed (outbox.js flushOutbox). While offline
     * (or while the session can't be used yet) every pending report counts as kept, so callers
     * can say so and the worker retries.
     */
    suspend fun flush(): FlushResult = flushLock.withLock {
        db.awaitSession()
        val uid = db.knownUserId() ?: return FlushResult()
        val pending = dao.all().filter { it.state != STATE_FAILED && (it.userId == uid || it.userId == null) }
        if (pending.isEmpty()) return FlushResult()
        if (!connectivity.isOnline.value || !db.awaitUsableSession(SESSION_WAIT_MS)) return FlushResult(kept = pending.size)

        var result = FlushResult()
        for (row in pending) {
            val report = runCatching { json.decodeFromString(NewIncident.serializer(), row.payloadJson) }.getOrNull()
            if (report == null) {
                dao.put(row.copy(state = STATE_FAILED, lastErrorCode = ErrorCode.Invalid.name))
                result = result.copy(failed = result.failed + 1)
                continue
            }
            // An unexplained server error that keeps coming back will not fix itself: stop retrying
            // and show it as not sent, so the resident calls instead of trusting "will send"
            if (row.lastErrorCode == ErrorCode.Unknown.name && row.attempts >= MAX_UNKNOWN_ATTEMPTS) {
                dao.put(row.copy(state = STATE_FAILED))
                result = result.copy(failed = result.failed + 1)
                continue
            }
            try {
                val incident = incidents.create(withLateNote(report, row.createdAt), row.clientId)
                result = result.copy(sent = result.sent + 1)
                markSent(row.clientId, incident)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = db.mapError(e)
                // Delivered meanwhile by a slow original request: don't bring the row back
                if (dao.get(row.clientId) == null) continue
                if (err.code.isPermanent) {
                    dao.put(row.copy(attempts = row.attempts + 1, lastErrorCode = err.code.name, lastErrorMessage = err.detail, state = STATE_FAILED))
                    result = result.copy(failed = result.failed + 1)
                } else {
                    dao.put(row.copy(attempts = row.attempts + 1, lastErrorCode = err.code.name, lastErrorMessage = err.detail))
                    result = result.copy(kept = result.kept + 1)
                    if (err.code == ErrorCode.Offline) break
                }
            }
        }
        result
    }

    /**
     * A report that waited in the queue lands as a fresh incident. Say when it was really made,
     * so responders don't read an old emergency as one happening now.
     */
    private fun withLateNote(report: NewIncident, createdAtMs: Long): NewIncident {
        if (System.currentTimeMillis() - createdAtMs < LATE_AFTER_MS) return report
        val at = java.time.LocalTime.ofInstant(Instant.ofEpochMilli(createdAtMs), java.time.ZoneId.systemDefault())
            .truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        val note = "[Sent late - reported at $at]"
        return report.copy(description = listOfNotNull(note, report.description?.takeIf { it.isNotBlank() }).joinToString(" "))
    }

    /** Ask WorkManager to flush once the network is up (retries with backoff while items remain). */
    fun scheduleFlush() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: a report queued while a flush is running gets its own run afterwards,
        // instead of cancelling the send in progress (REPLACE) or being skipped by it (KEEP)
        WorkManager.getInstance(context).enqueueUniqueWork(OutboxWorker.NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun OutboxEntity.toItem() = OutboxItem(
        clientId = clientId,
        userId = userId,
        report = runCatching { json.decodeFromString(NewIncident.serializer(), payloadJson) }
            .getOrElse { NewIncident(type = IncidentType.Security) },
        createdAt = Instant.ofEpochMilli(createdAt),
        attempts = attempts,
        lastError = lastErrorCode?.let { code -> ErrorCode.entries.firstOrNull { it.name == code } },
        failed = state == STATE_FAILED,
    )

    private companion object {
        const val STATE_PENDING = "pending"
        const val STATE_FAILED = "failed"
        /** How long a flush waits for a failing token refresh (it retries every 10 s). */
        const val SESSION_WAIT_MS = 12_000L
        /** A queued report older than this is marked as sent late. */
        const val MAX_UNKNOWN_ATTEMPTS = 20
        const val LATE_AFTER_MS = 5 * 60_000L
    }
}
