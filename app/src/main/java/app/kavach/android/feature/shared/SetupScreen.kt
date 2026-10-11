package app.kavach.android.feature.shared

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.kavach.android.R
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/**
 * First run on this phone. Explains why alerts need notifications and a battery exception, offers
 * both, and lets the user carry on either way: Continue and Skip both call [onDone].
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(context.notificationsAllowed()) }
    var batteryOk by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    // Android stops showing the permission dialog after the second refusal: then send people to settings
    var permissionBlocked by rememberSaveable { mutableStateOf(false) }

    // Both can change in system settings while we are in the background
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsOn = context.notificationsAllowed()
        batteryOk = context.isIgnoringBatteryOptimizations()
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsOn = context.notificationsAllowed()
        if (!granted) permissionBlocked = !context.canAskNotificationPermissionAgain()
    }

    // Re-checked whenever the on/off state changes (e.g. after a visit to settings)
    val needsPermission = remember(notificationsOn) { context.needsNotificationPermission() }
    val canShowPermissionDialog = needsPermission && !permissionBlocked
    // Off without a dialog to show: only the settings screen can turn them on
    val notificationsBlocked = !notificationsOn && !canShowPermissionDialog
    val allDone = notificationsOn && batteryOk

    AuthFrame {
        AppMark()
        Text(
            stringResource(R.string.shared_setup_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.shared_setup_text),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )

        SetupStep(
            icon = Icons.Rounded.Notifications,
            tone = KavachTheme.colors.red,
            title = stringResource(R.string.shared_setup_notif_title),
            text = stringResource(
                if (notificationsBlocked) R.string.shared_setup_notif_blocked else R.string.shared_setup_notif_text,
            ),
            done = notificationsOn,
            doneText = stringResource(R.string.shared_setup_notif_on),
            actionLabel = stringResource(
                if (canShowPermissionDialog) R.string.shared_setup_notif_allow else R.string.shared_open_settings,
            ),
            onAction = {
                if (canShowPermissionDialog) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.openAppNotificationSettings()
                }
            },
        )

        SetupStep(
            icon = Icons.Rounded.BatterySaver,
            tone = KavachTheme.colors.orange,
            title = stringResource(R.string.shared_setup_battery_title),
            text = stringResource(R.string.shared_setup_battery_text),
            done = batteryOk,
            doneText = stringResource(R.string.shared_setup_battery_on),
            actionLabel = stringResource(R.string.shared_setup_battery_exclude),
            onAction = { context.requestIgnoreBatteryOptimizations() },
        )

        Text(
            stringResource(R.string.shared_setup_honest),
            style = MaterialTheme.typography.bodySmall,
            color = KavachTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.xs))
        ActionButton(text = stringResource(R.string.shared_setup_continue), onClick = onDone)
        if (!allDone) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.shared_setup_skip)) }
        }
    }
}

/** One thing to switch on: icon, explanation, and either a "done" line or an action button. */
@Composable
private fun SetupStep(
    icon: ImageVector,
    tone: Color,
    title: String,
    text: String,
    done: Boolean,
    doneText: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    KavachCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            IconTile(icon, tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        if (done) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = KavachTheme.colors.success,
                    modifier = Modifier.size(20.dp),
                )
                Text(doneText, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.success)
            }
        } else {
            FilledTonalButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
        }
    }
}
