package app.kavach.android.feature.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * After a restart or an app update: wakes the app process (KavachApp.onCreate starts
 * [AlertsController]) and asks it to re-evaluate right away, so the foreground service starts
 * inside the broadcast's allowance. If Android refuses, the controller falls back to the
 * catch-up worker.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val entry = EntryPointAccessors.fromApplication(context.applicationContext, AlertsEntryPoint::class.java)
        val pending = goAsync()
        entry.appScope().launch {
            try {
                entry.controller().start() // no-op if KavachApp already did
                entry.controller().reevaluate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("KavachAlerts", "Boot re-evaluation failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
