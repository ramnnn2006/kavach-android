package app.kavach.android.feature.shared

import app.kavach.android.core.ui.components.hapticClick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.ui.components.ConfirmDialog
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/**
 * Shown while a saved session is restored, and when the profile could not be loaded
 * ([error] set): then it offers Try again and Log out.
 */
@Composable
fun SplashScreen(
    error: KavachException?,
    onRetry: (() -> Unit)?,
    onSignOut: (() -> Unit)?,
) {
    // Logging out deletes reports still waiting on this phone, so ask first (reset for each new error)
    var confirmLogOut by rememberSaveable(error) { mutableStateOf(false) }

    AuthFrame {
        AppMark(size = 72.dp)
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.shared_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        if (error == null) {
            val loading = stringResource(R.string.common_loading)
            CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (onRetry != null) {
                    ActionButton(stringResource(R.string.common_retry), onClick = hapticClick(onRetry), modifier = Modifier.padding(top = Spacing.sm))
                }
                if (onSignOut != null) {
                    TextButton(onClick = hapticClick({ confirmLogOut = true })) { Text(stringResource(R.string.shared_log_out)) }
                }
            }
        }
    }

    // Closes by itself if the profile loads meanwhile (no log out offered then)
    if (confirmLogOut && onSignOut != null) {
        ConfirmDialog(
            title = stringResource(R.string.shared_profile_logout_title),
            text = stringResource(R.string.shared_splash_logout_text),
            confirmLabel = stringResource(R.string.shared_log_out),
            destructive = true,
            onConfirm = {
                confirmLogOut = false
                onSignOut()
            },
            onDismiss = { confirmLogOut = false },
        )
    }
}
