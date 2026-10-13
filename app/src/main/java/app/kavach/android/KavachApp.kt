package app.kavach.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.kavach.android.core.data.net.AppForeground
import app.kavach.android.core.data.outbox.OutboxRepository
import app.kavach.android.core.di.ApplicationScope
import app.kavach.android.feature.alerts.AlertsController
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KavachApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var foreground: AppForeground
    @Inject lateinit var outbox: OutboxRepository
    @Inject lateinit var alerts: AlertsController
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        foreground.register()
        // Channels, foreground service and catch-up work follow the session from here on
        alerts.start()
        // Send anything queued while the app was closed. Off the main thread, and only when
        // something is queued: every process start (alert service, boot, workers) runs this.
        appScope.launch {
            val queued = runCatching { outbox.observe(null).first().pending.isNotEmpty() }.getOrDefault(true)
            if (queued) outbox.scheduleFlush()
        }
    }
}
