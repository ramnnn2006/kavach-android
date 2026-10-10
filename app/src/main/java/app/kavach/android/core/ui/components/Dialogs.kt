package app.kavach.android.core.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.domain.Phones
import app.kavach.android.core.ui.theme.KavachTheme

/** Open the dialler with [phone] filled in (no CALL_PHONE permission needed). */
fun Context.dial(phone: String?) {
    val number = Phones.dialable(phone)
    if (number.isEmpty()) return
    try {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) { /* no dialler (tablet) */ }
}

enum class CallButtonStyle { Danger, Primary, Secondary }

/** Button that opens the dialler (CallLink). Shows nothing when there's no number to dial. */
@Composable
fun CallButton(
    phone: String?,
    label: String,
    modifier: Modifier = Modifier,
    style: CallButtonStyle = CallButtonStyle.Secondary,
    fillWidth: Boolean = false,
) {
    // Like the website's CallLink: no dead, disabled button when the number is missing
    if (Phones.dialable(phone).isEmpty()) return
    val context = LocalContext.current
    val m = if (fillWidth) modifier.fillMaxWidth() else modifier
    val content: @Composable () -> Unit = {
        Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
    when (style) {
        CallButtonStyle.Danger -> Button(
            onClick = { context.dial(phone) }, modifier = m,
            colors = ButtonDefaults.buttonColors(containerColor = KavachTheme.colors.danger, contentColor = KavachTheme.colors.onAccent),
        ) { content() }
        CallButtonStyle.Primary -> Button(onClick = { context.dial(phone) }, modifier = m) { content() }
        CallButtonStyle.Secondary -> FilledTonalButton(onClick = { context.dial(phone) }, modifier = m) { content() }
    }
}

/** Confirmation dialog (DialogContext confirm()). */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    text: String? = null,
    destructive: Boolean = false,
    dismissLabel: String = stringResource(R.string.common_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = KavachTheme.colors.danger) else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** Outlined secondary action (used next to primary buttons). */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(text) }
}
