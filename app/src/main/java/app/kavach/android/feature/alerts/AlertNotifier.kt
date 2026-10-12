package app.kavach.android.feature.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.MedicalAlert
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.net.AppForeground
import app.kavach.android.core.data.prefs.SettingsStore
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.nav.DeepLink
import app.kavach.android.core.nav.DeepLinks
import app.kavach.android.core.nav.LinkTarget
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.shortLabelRes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the notification channels and turns [AlertEvent]s into notifications. Texts follow the
 * English strings of notify/index.ts (see strings_alerts.xml).
 *
 * Every notification of one incident uses the same tag (`incident-<id>`, `sos-<id>`) and one fixed
 * id, so an update replaces the earlier notification instead of stacking.
 */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionManager,
    private val settings: SettingsStore,
    private val foreground: AppForeground,
) {
    private val manager = NotificationManagerCompat.from(context)

    // ── Channels ──────────────────────────────────────────────────────────────

    fun createChannels() {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIC)
            .build()
        val noteUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val noteAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIC)
            .build()
        val red = ContextCompat.getColor(context, R.color.notification_emergency)
        val blue = ContextCompat.getColor(context, R.color.notification_accent)

        // Emergency: loud, vibrates, lights; does not bypass Do Not Disturb
        val emergency = NotificationChannelCompat.Builder(AlertChannelId.Emergency.id, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.alerts_channel_emergency_name))
            .setDescription(context.getString(R.string.alerts_channel_emergency_desc))
            .setSound(alarmUri, alarmAttrs)
            .setVibrationPattern(EMERGENCY_VIBRATION)
            .setLightsEnabled(true)
            .setLightColor(red)
            .setShowBadge(true)
            .build()

        val status = NotificationChannelCompat.Builder(AlertChannelId.Status.id, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.alerts_channel_status_name))
            .setDescription(context.getString(R.string.alerts_channel_status_desc))
            .setSound(noteUri, noteAttrs)
            .setLightsEnabled(true)
            .setLightColor(blue)
            .setShowBadge(true)
            .build()

        val safety = NotificationChannelCompat.Builder(AlertChannelId.SafetyCheck.id, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.alerts_channel_safety_name))
            .setDescription(context.getString(R.string.alerts_channel_safety_desc))
            .setSound(alarmUri, alarmAttrs)
            .setVibrationPattern(EMERGENCY_VIBRATION)
            .setLightsEnabled(true)
            .setLightColor(red)
            .setShowBadge(true)
            .build()

        val notices = NotificationChannelCompat.Builder(AlertChannelId.Notices.id, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.alerts_channel_notices_name))
            .setDescription(context.getString(R.string.alerts_channel_notices_desc))
            .setSound(noteUri, noteAttrs)
            .setShowBadge(true)
            .build()

        // Service: silent, no badge ("Kavach is active")
        val service = NotificationChannelCompat.Builder(AlertChannelId.Service.id, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.alerts_channel_service_name))
            .setDescription(context.getString(R.string.alerts_channel_service_desc))
            .setSound(null, null)
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()

        manager.createNotificationChannelsCompat(listOf(emergency, status, safety, notices, service))
    }

    // ── Permission ────────────────────────────────────────────────────────────

    /** False when POST_NOTIFICATIONS was denied (13+) or notifications are switched off. */
    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return manager.areNotificationsEnabled()
    }

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return false
        return nm.canUseFullScreenIntent()
    }

    // ── Foreground service notification ───────────────────────────────────────

    fun serviceNotification(): Notification =
        NotificationCompat.Builder(context, AlertChannelId.Service.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(context.getString(R.string.alerts_service_title))
            .setContentText(context.getString(R.string.alerts_service_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(DeepLinks.pendingIntent(context, REQUEST_SERVICE, DeepLink(LinkTarget.Home)))
            .build()

    // ── Showing and clearing ──────────────────────────────────────────────────

    suspend fun show(event: AlertEvent, user: AlertUser) {
        if (!canNotify()) return
        val soundOn = settings.alertSound.first()
        // The Emergency channel already plays its sound; only mute it when the person turned the
        // in-app alert sound off and is looking at the app.
        val silent = event.channel == AlertChannelId.Emergency && foreground.isForeground.value && !soundOn
        val profile = session.profile.value
        val n = when (event) {
            is AlertEvent.NewReport -> newReport(event, user, silent)
            is AlertEvent.Assigned -> assigned(event, silent)
            is AlertEvent.Escalated -> escalated(event, silent)
            is AlertEvent.MedicalHelp -> medicalHelp(event, silent)
            is AlertEvent.ReportProgress -> progress(event, profile)
            is AlertEvent.StaffCancelled -> staffCancelled(event)
            is AlertEvent.Reassigned -> reassigned(event)
            is AlertEvent.SafetyCheckStarted -> safetyCheck(event)
            is AlertEvent.NoticePosted -> notice(event)
        }
        post(event.tag, n)
    }

    /**
     * Replaces the notification [tag] with a short result, or a "working" state when [body] is
     * empty (used by [AlertActionReceiver]). It has no buttons, so an action cannot run twice.
     */
    fun showResult(tag: String, link: DeepLink, title: String, body: String, timeout: Duration?) {
        if (!canNotify()) return
        val b = NotificationCompat.Builder(context, AlertChannelId.Status.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(DeepLinks.pendingIntent(context, tag.hashCode(), link))
        if (body.isNotBlank()) b.setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        if (timeout != null) b.setTimeoutAfter(timeout.toMillis())
        post(tag, b.build())
    }

    fun cancelTag(tag: String) {
        manager.cancel(tag, NOTIFICATION_ID)
    }

    /** Clears the notifications of one incident (someone else took it, or it closed). */
    fun cancelIncident(incidentId: String) {
        cancelTag(incidentTag(incidentId))
    }

    fun cancelAll() {
        manager.cancelAll()
    }

    @SuppressLint("MissingPermission")
    private fun post(tag: String, notification: Notification) {
        if (!canNotify()) return
        try {
            manager.notify(tag, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call: nothing to show
        }
    }

    // ── Builders per event ────────────────────────────────────────────────────

    private fun newReport(e: AlertEvent.NewReport, user: AlertUser, silent: Boolean): Notification {
        val inc = e.incident
        val type = typeWord(inc.type)
        val title = if (e.mode == NewReportMode.NoOneOnDuty) {
            context.getString(R.string.alerts_new_alert_no_duty, type)
        } else {
            context.getString(R.string.alerts_new_alert, type)
        }
        val b = emergencyBuilder(e, title, details(inc, withZone = true), DeepLink(LinkTarget.Incident, inc.id), silent)
        // Claim only makes sense for someone on duty who can take it
        if (e.mode == NewReportMode.OnDuty && user.onDuty) {
            b.addAction(
                R.drawable.ic_notification,
                context.getString(R.string.common_action_claim),
                actionIntent(AlertActionReceiver.ACTION_CLAIM, inc.id, e.tag),
            )
        }
        return b.build()
    }

    private fun assigned(e: AlertEvent.Assigned, silent: Boolean): Notification {
        val inc = e.incident
        val title = context.getString(R.string.alerts_assigned, typeWord(inc.type))
        return emergencyBuilder(e, title, details(inc, withZone = true), DeepLink(LinkTarget.Incident, inc.id), silent).build()
    }

    private fun escalated(e: AlertEvent.Escalated, silent: Boolean): Notification {
        val inc = e.incident
        val minutes = maxOf(1L, Duration.between(inc.createdAt, Instant.now()).toMinutes()).toInt()
        val title = context.getString(R.string.alerts_escalated_title, minutes, typeWord(inc.type), inc.zoneName.orEmpty())
        val body = context.getString(R.string.alerts_escalated_body, details(inc, withZone = false), inc.escalationLevel)
        return emergencyBuilder(e, title, body, DeepLink(LinkTarget.Incident, inc.id), silent).build()
    }

    private fun medicalHelp(e: AlertEvent.MedicalHelp, silent: Boolean): Notification {
        val alert = e.alert
        val title = context.getString(R.string.alerts_first_aid_title)
        val body = context.getString(R.string.alerts_first_aid_body, medicalWhere(alert))
        val b = emergencyBuilder(e, title, body, DeepLink(LinkTarget.MedicalAlert, alert.id), silent)
        b.addAction(
            R.drawable.ic_notification,
            context.getString(R.string.alerts_action_coming),
            actionIntent(AlertActionReceiver.ACTION_COMING, alert.id, e.tag),
        )
        return b.build()
    }

    private fun progress(e: AlertEvent.ReportProgress, profile: Profile?): Notification {
        val inc = e.incident
        val name = inc.assignedName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.alerts_someone)
        val title: String
        val body: String
        when (inc.status) {
            IncidentStatus.Acknowledged -> {
                title = context.getString(
                    if (e.assignedByOther) R.string.alerts_status_assigned_yours else R.string.alerts_status_accepted,
                    name,
                )
                body = context.getString(R.string.alerts_status_accepted_body)
            }
            IncidentStatus.EnRoute -> {
                title = context.getString(R.string.alerts_status_en_route, name)
                body = context.getString(R.string.alerts_status_en_route_body)
            }
            IncidentStatus.OnScene -> {
                title = context.getString(R.string.alerts_status_on_scene)
                body = context.getString(R.string.alerts_status_on_scene_body, name)
            }
            IncidentStatus.Resolved -> {
                title = context.getString(R.string.alerts_status_resolved)
                body = context.getString(R.string.alerts_status_resolved_body)
            }
            IncidentStatus.Cancelled -> {
                title = context.getString(R.string.alerts_status_cancelled)
                val place = Format.residentPlace(context, inc, profile).ifBlank { Format.place(context, inc) }
                body = context.getString(R.string.alerts_status_cancelled_body, typeCap(inc.type), place)
            }
            IncidentStatus.Pending -> {
                title = context.getString(R.string.app_name)
                body = ""
            }
        }
        return plainBuilder(e, title, body, DeepLink(LinkTarget.Incident, inc.id)).build()
    }

    private fun staffCancelled(e: AlertEvent.StaffCancelled): Notification {
        val inc = e.incident
        val body = context.getString(R.string.alerts_staff_cancelled_body, typeCap(inc.type), inc.zoneName.orEmpty())
        return plainBuilder(e, context.getString(R.string.alerts_staff_cancelled_title), body, DeepLink(LinkTarget.Incident, inc.id)).build()
    }

    private fun reassigned(e: AlertEvent.Reassigned): Notification {
        val inc = e.incident
        val name = inc.assignedName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.alerts_someone)
        val body = context.getString(R.string.alerts_reassigned_body, typeCap(inc.type), inc.zoneName.orEmpty(), name)
        return plainBuilder(e, context.getString(R.string.alerts_reassigned_title), body, DeepLink(LinkTarget.Incident, inc.id)).build()
    }

    private fun safetyCheck(e: AlertEvent.SafetyCheckStarted): Notification {
        val body = context.getString(R.string.alerts_safety_body, e.check.message)
        val b = plainBuilder(e, context.getString(R.string.alerts_safety_title), body, DeepLink(LinkTarget.SafetyCheck))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        return b.build()
    }

    private fun notice(e: AlertEvent.NoticePosted): Notification {
        val title = e.notice.title.ifBlank { context.getString(R.string.alerts_notice_fallback_title) }
        return plainBuilder(e, title, e.notice.body, DeepLink(LinkTarget.Notices))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private fun plainBuilder(e: AlertEvent, title: String, body: String, link: DeepLink): NotificationCompat.Builder =
        NotificationCompat.Builder(context, e.channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setWhen(e.at.toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(DeepLinks.pendingIntent(context, e.tag.hashCode(), link))

    /** Emergency: alarm category, high priority, lock-screen visible, full-screen when allowed. */
    private fun emergencyBuilder(e: AlertEvent, title: String, body: String, link: DeepLink, silent: Boolean): NotificationCompat.Builder {
        val open = DeepLinks.pendingIntent(context, e.tag.hashCode(), link)
        val b = NotificationCompat.Builder(context, AlertChannelId.Emergency.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_emergency))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(e.at.toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (canUseFullScreenIntent()) b.setFullScreenIntent(open, true)
        if (silent) b.setSilent(true)
        return b
    }

    private fun actionIntent(action: String, incidentId: String, tag: String): PendingIntent {
        val intent = Intent(context, AlertActionReceiver::class.java)
            .setAction(action)
            .putExtra(AlertActionReceiver.EXTRA_INCIDENT_ID, incidentId)
            .putExtra(AlertActionReceiver.EXTRA_TAG, tag)
        return PendingIntent.getBroadcast(
            context,
            (action + incidentId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ── Text helpers (where() / details() of notify/index.ts) ─────────────────

    /** "lift", "fire" ... */
    private fun typeWord(type: IncidentType): String = context.getString(type.shortLabelRes()).lowercase()

    /** "Lift", "Fire" ... */
    private fun typeCap(type: IncidentType): String = context.getString(type.shortLabelRes())

    private fun where(inc: Incident, withZone: Boolean): String =
        listOfNotNull(
            inc.zoneName.takeIf { withZone },
            inc.flatLabel ?: Format.floor(context, inc.floor),
            inc.assetName,
        ).filter { it.isNotBlank() }.joinToString(" · ")

    /** "Tower A · A-1204 · Lift A2 · 2 people · Needs extra care" */
    private fun details(inc: Incident, withZone: Boolean): String {
        val place = where(inc, withZone).ifEmpty { if (withZone) Format.place(context, inc) else "" }
        return listOfNotNull(
            place.takeIf { it.isNotEmpty() },
            Format.people(context, inc.peopleAffected),
            context.getString(R.string.alerts_needs_extra_care).takeIf { inc.vulnerable },
        ).joinToString(" · ")
    }

    private fun medicalWhere(alert: MedicalAlert): String {
        val parts = listOfNotNull(
            alert.zoneName,
            alert.flatLabel ?: Format.floor(context, alert.floor),
            alert.locationNote?.takeIf { it.isNotBlank() },
        ).filter { it.isNotBlank() }
        return if (parts.isEmpty()) context.getString(R.string.common_place_unknown) else parts.joinToString(" · ")
    }

    private companion object {
        /** All tagged alert notifications share one id; the tag tells them apart. */
        const val NOTIFICATION_ID = 1001
        const val REQUEST_SERVICE = 1
        val EMERGENCY_VIBRATION = longArrayOf(0, 500, 250, 500, 250, 800)
    }
}
