package app.kavach.android.core.data.outbox

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.coroutines.cancellation.CancellationException

/** Drains the outbox when the network is available; retries while anything is kept. */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val outbox: OutboxRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        try {
            if (outbox.flush().kept > 0) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Storage hiccup: try again later rather than dropping the queued reports
            Result.retry()
        }

    companion object {
        const val NAME = "kavach-outbox-flush"
    }
}
