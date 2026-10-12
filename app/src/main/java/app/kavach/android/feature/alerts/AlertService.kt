package app.kavach.android.feature.alerts

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Foreground service ("Kavach is active") that keeps [AlertEngine] subscribed while a person is
 * signed in. Started and stopped by [AlertsController]. START_STICKY: the system restarts it after
 * killing the process.
 */
@AndroidEntryPoint
class AlertService : Service() {
    @Inject lateinit var engine: AlertEngine
    @Inject lateinit var notifier: AlertNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        enterForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() requires startForeground() on every start
        if (!enterForeground()) return START_NOT_STICKY
        if (job?.isActive != true) {
            job = scope.launch {
                while (true) {
                    try {
                        engine.run()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Alert engine stopped, retrying", e)
                    }
                    delay(RETRY_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** False when Android refused (started from the background, or a permission is missing). */
    private fun enterForeground(): Boolean =
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, FOREGROUND_ID, notifier.serviceNotification(), type)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not enter the foreground", e)
            stopSelf()
            false
        }

    private companion object {
        const val TAG = "KavachAlerts"
        const val FOREGROUND_ID = 1
        const val RETRY_MS = 10_000L
    }
}
