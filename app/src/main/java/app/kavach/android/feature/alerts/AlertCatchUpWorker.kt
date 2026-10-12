package app.kavach.android.feature.alerts

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.kavach.android.core.data.prefs.SettingsStore
import app.kavach.android.core.data.remote.Db
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.data.session.SessionState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Every 15 minutes (with a network): restarts [AlertService] if it should be running and checks
 * once for anything that happened while nothing was listening. Notifications already shown are
 * skipped by [AlertStateStore]. While the service is subscribed (or nothing could be shown) it
 * does no network work at all.
 */
@HiltWorker
class AlertCatchUpWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val controller: AlertsController,
    private val engine: AlertEngine,
    private val session: SessionManager,
    private val settings: SettingsStore,
    private val db: Db,
    private val notifier: AlertNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // The live subscriptions already cover everything: a second round of reads is waste
        if (engine.isWatching) return Result.success()
        // Notifications denied or off: nothing could be shown (the app re-checks when opened)
        if (!notifier.canNotify()) return Result.success()
        db.awaitSession()
        val state = withTimeoutOrNull(WAIT_MS) { session.state.first { it !is SessionState.Loading } }
        if (state !is SessionState.SignedIn) return Result.success()
        if (!settings.alertsEnabled.first()) return Result.success()
        val profile = state.profile ?: withTimeoutOrNull(WAIT_MS) { session.profile.filterNotNull().first() }
            ?: return Result.success()

        // May be refused while the app is in the background; the check below still runs
        controller.tryStartService()
        try {
            engine.catchUp(profile.toAlertUser())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next run (or the service) tries again
        }
        return Result.success()
    }

    companion object {
        const val PERIODIC_NAME = "kavach-alerts-catchup"
        const val NOW_NAME = "kavach-alerts-catchup-now"
        private const val WAIT_MS = 15_000L
    }
}
