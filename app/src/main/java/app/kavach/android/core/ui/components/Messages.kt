package app.kavach.android.core.ui.components

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import app.kavach.android.core.data.remote.messageRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** A one-off message (the website's toast). Shown as a snackbar by [MessageEffect]. */
data class UiMessage(@StringRes val res: Int, val args: List<Any> = emptyList()) {
    companion object {
        /** The user-facing text for any thrown error (errorMessage()). */
        fun error(error: Throwable) = UiMessage(error.messageRes())
    }
}

/** For ViewModels: `val messages = MessageQueue()`, `messages.send(UiMessage(R.string.x))`. */
class MessageQueue {
    private val channel = Channel<UiMessage>(Channel.BUFFERED)
    val flow: Flow<UiMessage> = channel.receiveAsFlow()
    fun send(message: UiMessage) { channel.trySend(message) }
    fun send(@StringRes res: Int, vararg args: Any) = send(UiMessage(res, args.toList()))
    fun error(error: Throwable) = send(UiMessage.error(error))
}

/** The app-wide snackbar host (provided by KavachApp shell). */
val LocalSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

/**
 * App-shell scope the snackbars are shown in (provided by KavachRoot), so a message sent just
 * before its screen closes (Back, a tab switch) stays up instead of being cut off with the screen.
 */
internal val LocalMessageScope = staticCompositionLocalOf<CoroutineScope?> { null }

/** Collects [messages] and shows each as a snackbar. Put one per screen. */
@Composable
fun MessageEffect(messages: Flow<UiMessage>) {
    val host = LocalSnackbarHostState.current
    val context = LocalContext.current
    val shellScope = LocalMessageScope.current
    LaunchedEffect(messages, host) {
        messages.collect { m ->
            val text = context.getString(m.res, *m.args.toTypedArray())
            // SnackbarHostState queues concurrent calls, so messages still show one after another
            if (shellScope != null) shellScope.launch { host.showSnackbar(text) } else host.showSnackbar(text)
        }
    }
}

/**
 * Is the network up? Provided by MainActivity from ConnectivityObserver. Not static, so a network
 * flap recomposes only the readers (banners), not the whole app.
 */
val LocalIsOnline = compositionLocalOf { true }
