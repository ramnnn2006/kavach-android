package app.kavach.android.feature.responder

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.toKavach
import app.kavach.android.core.ui.components.ErrorBanner
import app.kavach.android.core.ui.components.ErrorState
import app.kavach.android.core.ui.components.LoadingState
import app.kavach.android.core.ui.components.MessageQueue
import app.kavach.android.core.ui.components.rememberHaptics
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.coroutines.cancellation.CancellationException

// Small pieces shared by the four responder screens.

/** Haptic result of an action (claim, advance, mark done…). */
enum class Feedback { Confirm, Reject }

/** Plays the haptic for each [Feedback] the view-model emits. One per screen. */
@Composable
internal fun FeedbackEffect(feedback: Flow<Feedback>) {
    val haptics = rememberHaptics()
    LaunchedEffect(feedback) {
        feedback.collect { f ->
            when (f) {
                Feedback.Confirm -> haptics.confirm()
                Feedback.Reject -> haptics.reject()
            }
        }
    }
}

/** State of a dialog that acts on [target] (resolve note, mark-done note, asset status). */
data class DialogUi<T>(
    val target: T? = null,
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
)

/** Snackbar messages + haptic feedback + error handling shared by the responder view-models. */
abstract class ActionViewModel : ViewModel() {
    val messages = MessageQueue()

    private val feedbackChannel = Channel<Feedback>(Channel.BUFFERED)
    val feedback: Flow<Feedback> = feedbackChannel.receiveAsFlow()

    protected fun confirm() { feedbackChannel.trySend(Feedback.Confirm) }
    protected fun reject() { feedbackChannel.trySend(Feedback.Reject) }

    /**
     * Runs [block]. On a failure plays the reject haptic, calls [onError] (default: show the
     * message as a snackbar) and returns null. Repositories throw [KavachException]; anything else
     * (e.g. decoding an RPC result) is mapped to one instead of crashing the app.
     */
    protected suspend fun <T : Any> attempt(
        onError: (KavachException) -> Unit = { messages.error(it) },
        block: suspend () -> T,
    ): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reject()
        onError(e.toKavach())
        null
    }
}

/**
 * Keeps the last loaded data while a restarted live query loads again (Retry, or the screen
 * coming back after its 5 s WhileSubscribed window), so the list doesn't flash a spinner and lose
 * its scroll position. Call once per view-model property.
 */
internal fun <T> Flow<Loadable<T>>.keepLastData(): Flow<Loadable<T>> {
    var last: T? = null
    return map { state ->
        val data = state.data
        if (data != null) {
            last = data
            state
        } else {
            Loadable(data = last, error = state.error, loading = state.loading)
        }
    }
}

/**
 * Like LoadableContent (spinner / full error / content + stale-data banner), but the content gets
 * the remaining height, so a LazyColumn inside it scrolls to the bottom edge even with the banner.
 */
@Composable
internal fun <T> LiveContent(
    state: Loadable<T>,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val data = state.data
    val error = state.error
    Column(modifier) {
        when {
            data != null -> {
                if (error != null) {
                    ErrorBanner(error, onRetry, Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content(data) }
            }
            error != null -> ErrorState(error, onRetry, Modifier.weight(1f))
            else -> LoadingState(Modifier.weight(1f))
        }
    }
}

/** Tinted one-line notice with an icon (the website's AlertBanner). */
@Composable
internal fun InfoBanner(icon: ImageVector, tone: Color, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.12f), MaterialTheme.shapes.small)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

internal enum class ActionStyle { Primary, Success, Tonal }

/** Button that shows a small spinner and ignores taps while [busy]. */
@Composable
internal fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    style: ActionStyle = ActionStyle.Primary,
) {
    val content: @Composable RowScope.() -> Unit = {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        } else {
            Text(text)
        }
    }
    val active = enabled && !busy
    when (style) {
        ActionStyle.Primary -> Button(onClick = onClick, modifier = modifier, enabled = active, content = content)
        ActionStyle.Success -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = active,
            colors = ButtonDefaults.buttonColors(
                containerColor = KavachTheme.colors.success,
                contentColor = KavachTheme.colors.onAccent,
            ),
            content = content,
        )
        ActionStyle.Tonal -> FilledTonalButton(onClick = onClick, modifier = modifier, enabled = active, content = content)
    }
}

/** "Maintenance · Lift, Power, Water" (admins: "All specialties"), or null until the profile loads. */
@Composable
internal fun specialtySummary(profile: Profile?): String? {
    val context = LocalContext.current
    return profile?.let { Format.specialties(context, it.role, it.specialties) }
}

/** Fills the screen below the app bar. */
internal fun Modifier.fillBelowBar(padding: androidx.compose.foundation.layout.PaddingValues): Modifier =
    this.fillMaxSize().padding(padding)
