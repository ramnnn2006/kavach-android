package app.kavach.android.feature.shared

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

// Small wrappers around Android settings screens, used by Setup and Profile.

/** Are notifications allowed for Kavach (permission granted and not switched off in settings)? */
internal fun Context.notificationsAllowed(): Boolean =
    NotificationManagerCompat.from(this).areNotificationsEnabled()

/** Android 13+ and the notification permission isn't granted yet (the system dialog applies). */
internal fun Context.needsNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/**
 * After a refusal: can the permission dialog still be shown? Android stops showing it after the
 * second refusal; from then on only the settings screen can turn notifications on.
 */
internal fun Context.canAskNotificationPermissionAgain(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
}

/** The Activity behind a (possibly wrapped) Compose context. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Is Kavach excluded from battery optimisation? */
internal fun Context.isIgnoringBatteryOptimizations(): Boolean {
    val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return power.isIgnoringBatteryOptimizations(packageName)
}

private fun Context.startSettings(intent: Intent): Boolean =
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        // Some OEM builds guard these settings screens
        false
    }

/** The app's page in system settings (fallback when a specific screen is missing). */
internal fun Context.openAppDetailsSettings() {
    startSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

/** Notification settings for Kavach (channels, sound, pop-up). */
internal fun Context.openAppNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    if (!startSettings(intent)) openAppDetailsSettings()
}

/** Ask the system to exclude Kavach from battery optimisation (one-tap system dialog). */
internal fun Context.requestIgnoreBatteryOptimizations() {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    if (!startSettings(request)) {
        if (!startSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppDetailsSettings()
    }
}

/** Android 14 requires a separate user grant for full-screen emergency alerts. */
internal fun Context.fullScreenAlertsAllowed(): Boolean =
    Build.VERSION.SDK_INT < 34 || (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).canUseFullScreenIntent()

internal fun Context.openFullScreenAlertSettings() {
    if (Build.VERSION.SDK_INT >= 34 && !startSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))) {
        openAppDetailsSettings()
    }
}
