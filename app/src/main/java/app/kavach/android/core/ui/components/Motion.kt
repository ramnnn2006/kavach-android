package app.kavach.android.core.ui.components

import android.content.Context
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/** The system "Remove animations" setting (animator scale 0). */
internal fun Context.reduceMotionSetting(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * Provided by MainActivity, which reads the setting once and again on every resume, so a change
 * made while the app is open applies to every screen (and lists don't read Settings per item).
 */
internal val LocalReduceMotion = staticCompositionLocalOf<Boolean?> { null }

/** True when the system "Remove animations" setting is on (animator scale 0). Skip motion then. */
@Composable
fun rememberReduceMotion(): Boolean {
    val provided = LocalReduceMotion.current
    val context = LocalContext.current
    val fallback = remember(context) { provided ?: context.reduceMotionSetting() }
    return provided ?: fallback
}

/** Restrained spring for status steps and list moves; snaps when motion is reduced. */
@Composable
fun <T> kavachSpring(): AnimationSpec<T> {
    val reduce = rememberReduceMotion()
    return remember(reduce) {
        if (reduce) snap() else spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    }
}

/** Short fade/size tween; snaps when motion is reduced. */
@Composable
fun <T> kavachTween(durationMillis: Int = 220): AnimationSpec<T> {
    val reduce = rememberReduceMotion()
    return remember(reduce, durationMillis) { if (reduce) snap() else tween(durationMillis) }
}

/** Haptics for key actions (send, claim, status change, errors). */
@Stable
class Haptics(private val view: View) {
    fun confirm() { view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS) }
    fun reject() { view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS) }
    fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun longPress() { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
