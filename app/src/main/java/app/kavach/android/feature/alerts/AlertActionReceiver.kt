package app.kavach.android.feature.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.kavach.android.R
import app.kavach.android.core.data.remote.ErrorCode
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.data.remote.toKavach
import app.kavach.android.core.nav.DeepLink
import app.kavach.android.core.nav.LinkTarget
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Notification buttons: "Claim" on a new report (responders on duty) and "I'm coming" on a medical
 * alert (community first responders). Replaces the notification with a "working" state (no
 * button, so it cannot be pressed twice), runs the repository call, then shows the result: done,
 * already taken by someone else, or why it failed.
 */
class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_CLAIM && action != ACTION_COMING) return
        val incidentId = intent.getStringExtra(EXTRA_INCIDENT_ID) ?: return
        val tag = intent.getStringExtra(EXTRA_TAG) ?: incidentTag(incidentId)
        // A second tap while the first is still running would turn "You claimed this alert" into
        // "Already taken"
        val flight = "$action:$incidentId"
        if (!inFlight.add(flight)) return

        val entry = EntryPointAccessors.fromApplication(context.applicationContext, AlertsEntryPoint::class.java)
        val app = context.applicationContext
        val pending = goAsync()
        entry.appScope().launch {
            try {
                handle(app, entry, action, incidentId, tag)
            } finally {
                inFlight.remove(flight)
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, entry: AlertsEntryPoint, action: String, incidentId: String, tag: String) {
        val notifier = entry.notifier()
        val target = if (action == ACTION_COMING) LinkTarget.MedicalAlert else LinkTarget.Incident
        val link = DeepLink(target, incidentId)
        val original = notifier.originalAlert(tag)
        notifier.showResult(
            tag,
            link,
            context.getString(if (action == ACTION_CLAIM) R.string.alerts_claim_working else R.string.alerts_coming_working),
            "",
            // No timeout: a pending timeout would also remove the result that replaces this
            timeout = null,
        )
        try {
            // The session wait counts too: the whole action has to finish inside the broadcast's time
            withTimeout(TIMEOUT_MS) {
                entry.db().awaitSession()
                if (action == ACTION_CLAIM) {
                    entry.incidents().claim(incidentId)
                    // I claimed it myself: do not tell me later that it was assigned to me
                    entry.session().currentUserId?.let { entry.store().claim("inc:$incidentId:assigned:$it") }
                } else {
                    entry.incidents().firstResponderAck(incidentId)
                }
            }
            val title = context.getString(
                if (action == ACTION_CLAIM) R.string.alerts_claim_done_title else R.string.alerts_coming_done_title,
            )
            val body = context.getString(
                if (action == ACTION_CLAIM) R.string.alerts_claim_done_body else R.string.alerts_coming_done_body,
            )
            if (action == ACTION_COMING) {
                // The next medical snapshot clears the alert's own tag (I am coming = no longer open);
                // keep the confirmation on a separate tag so it does not vanish with it
                notifier.cancelTag(tag)
                notifier.showResult("$tag:ack", link, title, body, timeout = null)
            } else {
                notifier.showResult(tag, link, title, body, timeout = null)
            }
        } catch (e: TimeoutCancellationException) {
            if (action == ACTION_CLAIM) {
                // The request may still have reached the server. Look before reporting a failure,
                // outside the broadcast's short time limit.
                notifier.showResult(tag, link, context.getString(R.string.alerts_claim_working), "", timeout = null)
                entry.appScope().launch { verifyClaim(context, entry, notifier, tag, link, incidentId, original) }
            } else {
                fail(context, notifier, action, tag, link, KavachException(ErrorCode.Timeout), original)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("KavachAlerts", "Notification action failed", e)
            fail(context, notifier, action, tag, link, e.toKavach(entry.db().isOnline), original)
        }
    }

    /** After a timed-out claim: if the incident is now mine, the claim went through. */
    private suspend fun verifyClaim(
        context: Context, entry: AlertsEntryPoint, notifier: AlertNotifier, tag: String, link: DeepLink, incidentId: String, original: android.app.Notification?,
    ) {
        val mine = try {
            withTimeout(20_000L) {
                entry.db().awaitSession()
                val me = entry.session().currentUserId
                me != null && entry.incidents().get(incidentId)?.assignedTo == me
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) false else throw e
        } catch (e: Exception) {
            false
        }
        if (mine) {
            entry.session().currentUserId?.let { entry.store().claim("inc:$incidentId:assigned:$it") }
            notifier.showResult(
                tag, link,
                context.getString(R.string.alerts_claim_done_title),
                context.getString(R.string.alerts_claim_done_body),
                timeout = null,
            )
        } else {
            fail(context, notifier, ACTION_CLAIM, tag, link, KavachException(ErrorCode.Timeout), original)
        }
    }

    private fun fail(context: Context, notifier: AlertNotifier, action: String, tag: String, link: DeepLink, error: KavachException, original: android.app.Notification?) {
        val taken = action == ACTION_CLAIM && error.code == ErrorCode.AlreadyClaimed
        val title = context.getString(if (taken) R.string.alerts_claim_taken_title else R.string.alerts_action_failed_title)
        if (taken || error.code == ErrorCode.NotFound || error.detail == "Incident is already closed") {
            notifier.showResult(tag, link, title, context.getString(error.messageRes()), timeout = Duration.ofMinutes(5))
        } else {
            notifier.restoreActionAlert(tag, original, link, title, context.getString(error.messageRes()), action)
        }
    }

    companion object {
        /** Actions still running in this process ("action:incidentId"). */
        private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

        const val ACTION_CLAIM = "app.kavach.android.alerts.CLAIM"
        const val ACTION_COMING = "app.kavach.android.alerts.COMING"
        const val EXTRA_INCIDENT_ID = "app.kavach.android.alerts.extra.INCIDENT_ID"
        const val EXTRA_TAG = "app.kavach.android.alerts.extra.TAG"
        private const val TIMEOUT_MS = 9_000L
    }
}
