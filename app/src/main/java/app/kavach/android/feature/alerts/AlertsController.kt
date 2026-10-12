package app.kavach.android.feature.alerts

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.kavach.android.core.data.net.AppForeground
import app.kavach.android.core.data.prefs.SettingsStore
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.data.session.SessionState
import app.kavach.android.core.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alerts without Firebase. While a person is signed in (with a profile), "alerts" is on in
 * Profile and notifications are allowed, a foreground service ([AlertService], "Kavach is active") keeps live subscriptions to
 * Supabase Realtime through the repositories, and [AlertEngine] turns changes into notifications.
 * A 15 minute WorkManager job ([AlertCatchUpWorker]) restarts the service if the system killed it
 * and runs a one-shot check for anything missed meanwhile.
 *
 * [start] is called once from KavachApp.onCreate: it creates the notification channels and then
 * follows the session and the setting. Sign-out (or switching alerts off) stops the service and
 * clears the alert notifications.
 *
 * Start rules: from Android 12 a foreground service can only be started while the app is
 * visible, from BOOT_COMPLETED, or in a few other cases. When a start is refused the catch-up job
 * runs instead and the service is started the next time the app is opened.
 */
@Singleton
class AlertsController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionManager,
    private val settings: SettingsStore,
    private val notifier: AlertNotifier,
    private val store: AlertStateStore,
    private val foreground: AppForeground,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private enum class Mode { Run, Disabled, SignedOut }

    private val started = AtomicBoolean(false)
    @Volatile private var mode: Mode? = null
    /** Whether the periodic catch-up is scheduled (null = not known yet in this process). */
    @Volatile private var periodicOn: Boolean? = null

    /** Creates the channels and starts following the session. Call once from Application.onCreate. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        notifier.createChannels()
        scope.launch {
            // setupDone is only a trigger: the notification permission is usually granted during
            // the first-run setup, which ends with the app in the foreground (allowed to start)
            combine(session.state, settings.alertsEnabled, settings.setupDone) { state, enabled, setupDone ->
                desiredMode(state, enabled)?.let { it to setupDone }
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { (newMode, _) -> applyMode(newMode) }
        }
        // Back in the foreground is the one moment Android always lets us start the service; it is
        // also where a permission granted or revoked in system settings is noticed
        scope.launch {
            foreground.resumed.collect { if (mode == Mode.Run) runIfUseful() }
        }
    }

    /**
     * Looks at the session once and applies it now (waits a little while a saved session is being
     * restored). Used by [BootReceiver], so the start happens inside the broadcast window: a
     * restored session is enough (the service waits for the profile itself).
     */
    suspend fun reevaluate() {
        val state = withTimeoutOrNull(RESTORE_TIMEOUT_MS) { session.state.first { it !is SessionState.Loading } } ?: return
        val enabled = settings.alertsEnabled.first()
        val newMode = if (state is SessionState.SignedIn && enabled) Mode.Run else desiredMode(state, enabled)
        newMode?.let { applyMode(it) }
    }

    /** Tries to start the service (used by the catch-up worker). False when Android refused. */
    fun tryStartService(): Boolean = startService()

    private fun desiredMode(state: SessionState, enabled: Boolean): Mode? = when (state) {
        SessionState.Loading -> null
        SessionState.SignedOut -> Mode.SignedOut
        is SessionState.SignedIn -> when {
            !enabled -> Mode.Disabled
            state.profile != null -> Mode.Run
            else -> null // profile still loading (or failed to load): keep things as they are
        }
    }

    @Synchronized
    private fun applyMode(newMode: Mode) {
        mode = newMode
        when (newMode) {
            Mode.Run -> {
                notifier.createChannels()
                runIfUseful()
            }
            Mode.Disabled -> {
                stopService()
                notifier.cancelAll()
                cancelPeriodic()
            }
            Mode.SignedOut -> {
                stopService()
                notifier.cancelAll()
                store.clear()
                cancelPeriodic()
            }
        }
    }

    /**
     * Mode.Run: keeps the service and the catch-up job going only while a notification can be
     * shown. With notifications denied or switched off, a live connection (and the medical poll)
     * would only cost battery; this is checked again each time the app comes to the foreground.
     */
    @Synchronized
    private fun runIfUseful() {
        if (notifier.canNotify()) {
            if (!startService()) enqueueCatchUpNow()
            if (periodicOn != true) schedulePeriodic()
        } else {
            stopService()
            if (periodicOn != false) cancelPeriodic()
        }
    }

    private fun startService(): Boolean =
        try {
            ContextCompat.startForegroundService(context, Intent(context, AlertService::class.java))
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (12+) or IllegalStateException (8-11): the
            // app is in the background. The catch-up worker covers this until the app is opened.
            Log.w(TAG, "Could not start the alert service", e)
            false
        }

    private fun stopService() {
        try {
            context.stopService(Intent(context, AlertService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop the alert service", e)
        }
    }

    // ── WorkManager ───────────────────────────────────────────────────────────

    private val networkConstraint: Constraints
        get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun schedulePeriodic() {
        try {
            val request = PeriodicWorkRequestBuilder<AlertCatchUpWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(AlertCatchUpWorker.PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            periodicOn = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule the catch-up job", e)
        }
    }

    private fun cancelPeriodic() {
        try {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(AlertCatchUpWorker.PERIODIC_NAME)
            wm.cancelUniqueWork(AlertCatchUpWorker.NOW_NAME)
            periodicOn = false
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel the catch-up job", e)
        }
    }

    private fun enqueueCatchUpNow() {
        try {
            val request = OneTimeWorkRequestBuilder<AlertCatchUpWorker>()
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(AlertCatchUpWorker.NOW_NAME, ExistingWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Log.w(TAG, "Could not enqueue the catch-up job", e)
        }
    }

    private companion object {
        const val TAG = "KavachAlerts"
        const val RESTORE_TIMEOUT_MS = 8_000L
    }
}
