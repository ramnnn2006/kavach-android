package app.kavach.android.core.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import app.kavach.android.core.ui.components.rememberReduceMotion

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shared-element key for an incident card ↔ its detail/tracker header. */
fun incidentSharedKey(incidentId: String) = "incident-$incidentId"

/**
 * List → detail shared bounds. Put the same [key] on the list card and on the detail header.
 * No-op outside the NavHost or when motion is reduced.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedBoundsFor(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedVisibilityScope.current ?: return this
    if (rememberReduceMotion()) return this
    return with(shared) { this@sharedBoundsFor.sharedBounds(rememberSharedContentState(key), visibility) }
}
