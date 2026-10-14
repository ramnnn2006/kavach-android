package app.kavach.android.core.ui.components

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

/** Wrap activation, not pointer-down: scrolling, cancelled taps and disabled buttons stay quiet. */
object ClickFeedback {
    private var lastAt = 0L
    fun pulse(view: View) {
        val now = SystemClock.uptimeMillis()
        // Nested card/button wrappers count as one activation. Respect Android haptics setting.
        if (now - lastAt > 60L) {
            lastAt = now
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
}

@Composable
fun hapticClick(action: () -> Unit): () -> Unit {
    val view = LocalView.current
    return { ClickFeedback.pulse(view); action() }
}
