package app.kavach.android.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** Dot that pulses (escalated reports). Static when motion is reduced. */
@Composable
fun PulsingDot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    val pulse = if (reduce) Modifier else {
        val t = rememberInfiniteTransition(label = "pulse")
        val pulseAlpha = t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulseAlpha")
        // Read in the draw phase: the dot redraws each frame instead of recomposing
        Modifier.graphicsLayer { alpha = pulseAlpha.value }
    }
    Box(modifier.size(size).then(pulse).clip(CircleShape).background(color))
}

/**
 * Hold-to-send button: fills over [holdMillis] while pressed, then calls [onComplete] once.
 * Releasing early resets it. TalkBack users get a normal double-tap action (no hold needed).
 *
 * The hold is timed on the wall clock, never by the animation system: with "Remove animations"
 * (or a shorter animator scale) an animated fill would finish on touch-down and a single tap
 * would send an emergency report.
 */
@Composable
fun HoldToSendButton(
    label: String,
    holdingLabel: String,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Color = KavachTheme.colors.danger,
    holdMillis: Int = 1000,
    height: Dp = 64.dp,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    // The gesture below outlives recompositions: always call the latest callback
    val currentOnComplete by rememberUpdatedState(onComplete)
    val holding = progress.value > 0f
    val container by animateColorAsState(if (enabled) tone.copy(alpha = 0.22f) else KavachTheme.colors.fill, label = "holdBg")

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (holding) stateDescription = holdingLabel
                if (!enabled) disabled()
                onClick { if (enabled) { haptics.confirm(); currentOnComplete() }; enabled }
            }
            .pointerInput(enabled, holdMillis) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    haptics.tick()
                    val hold = scope.launch {
                        // Visual only: the fill follows elapsed frame time
                        val fill = launch {
                            val start = withFrameNanos { it }
                            while (true) {
                                val elapsedMs = (withFrameNanos { it } - start) / 1_000_000f
                                progress.snapTo((elapsedMs / holdMillis).coerceIn(0f, 1f))
                            }
                        }
                        delay(holdMillis.toLong())
                        fill.cancel()
                        progress.snapTo(1f)
                        haptics.confirm()
                        currentOnComplete()
                        progress.snapTo(0f)
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        // Released early, moved off, scrolled, or this gesture was cancelled
                        if (hold.isActive) {
                            hold.cancel()
                            scope.launch { progress.animateTo(0f, tween(150)) }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value).background(tone))
        Text(
            if (holding) holdingLabel else label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (progress.value > 0.5f) KavachTheme.colors.onAccent else toneText(tone),
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
    }
}

/** One step of a progress tracker. */
data class ProgressStep(val label: String, val time: Instant?, val done: Boolean, val current: Boolean)

/** Vertical status steps (Stepper) that animate with springs as they complete. */
@Composable
fun ProgressSteps(steps: List<ProgressStep>, modifier: Modifier = Modifier, tone: Color = KavachTheme.colors.primary) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val doneText = stringResource(R.string.common_step_done)
        val nextText = stringResource(R.string.common_step_current)
        val notYetText = stringResource(R.string.common_step_not_yet)
        steps.forEach { step ->
            val scale = remember(step.label) { Animatable(if (step.done) 1f else 0.8f) }
            val spec = kavachSpring<Float>()
            LaunchedEffect(step.done) { scale.animateTo(if (step.done) 1f else 0.8f, spec) }
            val dotColor by animateColorAsState(if (step.done) tone else KavachTheme.colors.fill, label = "stepColor")
            // One TalkBack stop per step, with its state in words (the dot's colour alone isn't enough)
            val state = when {
                step.done -> doneText
                step.current -> nextText
                else -> notYetText
            }
            Row(
                Modifier.semantics(mergeDescendants = true) { stateDescription = state },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(14.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }.clip(CircleShape).background(dotColor))
                Text(
                    step.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (step.current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (step.done) MaterialTheme.colorScheme.onSurface else KavachTheme.colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                if (step.time != null) {
                    Text(Format.clock(step.time), style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
                }
            }
        }
    }
}
