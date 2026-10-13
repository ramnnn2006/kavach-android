package app.kavach.android.core.nav

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.kavach.android.MainActivity
import app.kavach.android.core.data.model.UserRole

/** Where a notification tap should land. */
enum class LinkTarget { Home, Incident, MedicalAlert, Notices, SafetyCheck }

data class DeepLink(val target: LinkTarget, val incidentId: String? = null)

/**
 * Notification → app. The alert service builds intents with [pendingIntent]; MainActivity parses
 * them with [parse]; the role shell turns them into a route with [toRoute].
 */
object DeepLinks {
    private const val EXTRA_TARGET = "app.kavach.android.extra.TARGET"
    private const val EXTRA_INCIDENT = "app.kavach.android.extra.INCIDENT_ID"
    private const val EXTRA_TOKEN = "app.kavach.android.extra.TOKEN"
    private const val PREFS = "deep_link_guard"
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * Per-install secret stamped on every intent this app builds. MainActivity is exported (it is
     * the launcher), so without it any other app could send a crafted intent and open an
     * incident screen, or show the app over the lock screen.
     */
    @Synchronized
    private fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }
        val fresh = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString()
        prefs.edit().putString("token", fresh).apply()
        return fresh
    }

    fun intent(context: Context, link: DeepLink): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TARGET, link.target.name)
            .putExtra(EXTRA_INCIDENT, link.incidentId)
            .putExtra(EXTRA_TOKEN, token(context))

    /** Immutable PendingIntent that opens the app at [link]. Use a distinct [requestCode] per notification. */
    fun pendingIntent(context: Context, requestCode: Int, link: DeepLink): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, intent(context, link),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Null for anything this app did not build itself (missing/wrong token) or a malformed id. */
    fun parse(context: Context, intent: Intent?): DeepLink? {
        val target = intent?.getStringExtra(EXTRA_TARGET) ?: return null
        if (intent.getStringExtra(EXTRA_TOKEN) != token(context)) return null
        val t = LinkTarget.entries.firstOrNull { it.name == target } ?: return null
        val id = intent.getStringExtra(EXTRA_INCIDENT)
        if (id != null && !UUID_RE.matches(id)) return null
        return DeepLink(t, id)
    }

    /** Route for [link] in [role]'s graph (null = stay on the home tab). */
    fun toRoute(link: DeepLink, role: UserRole): Route? = when (link.target) {
        LinkTarget.Home -> null
        LinkTarget.Incident -> link.incidentId?.let { id ->
            if (role == UserRole.Resident) Routes.Tracker(id) else Routes.IncidentDetail(id)
        }
        LinkTarget.MedicalAlert -> null
        LinkTarget.Notices -> when (role) {
            UserRole.Resident -> Routes.ResidentNotices
            UserRole.Admin -> Routes.AdminNotices
            UserRole.Responder -> null
        }
        LinkTarget.SafetyCheck -> when (role) {
            UserRole.Admin -> Routes.AdminSafetyCheck
            UserRole.Responder -> Routes.ResponderChecks
            UserRole.Resident -> null
        }
    }
}
