package app.kavach.android.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Spacing.xl, horizontal = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = KavachTheme.colors.gray, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/** Full-area error with a retry button (nothing loaded yet). */
@Composable
fun ErrorState(error: Throwable, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Rounded.ErrorOutline,
        title = stringResource(error.messageRes()),
        modifier = modifier,
        action = onRetry?.let { retry -> { TextButton(onClick = retry) { Text(stringResource(R.string.common_retry)) } } },
    )
}

/** Thin banner above stale data when a refresh failed. */
@Composable
fun ErrorBanner(error: Throwable, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val tone = KavachTheme.colors.orange
    Row(
        modifier.fillMaxWidth().background(tone.copy(alpha = 0.12f), MaterialTheme.shapes.small)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(error.messageRes()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
    }
}

/** "You're offline…" strip shown by the app shell when [LocalIsOnline] is false. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(KavachTheme.colors.gray.copy(alpha = 0.18f))
            .padding(horizontal = Spacing.md, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.common_offline), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Standard handling of a [Loadable]: spinner on first load, full error when nothing loaded,
 * otherwise [content] (with an [ErrorBanner] on top when a refresh failed).
 */
@Composable
fun <T> LoadableContent(
    state: Loadable<T>,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val data = state.data
    when {
        data != null -> Column(modifier) {
            state.error?.let { ErrorBanner(it, onRetry, Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs)) }
            // Weighted so a LazyColumn inside gets the remaining height, not clipped by the banner
            Box(Modifier.weight(1f, fill = false)) { content(data) }
        }
        state.error != null -> ErrorState(state.error, onRetry, modifier)
        else -> LoadingState(modifier)
    }
}
