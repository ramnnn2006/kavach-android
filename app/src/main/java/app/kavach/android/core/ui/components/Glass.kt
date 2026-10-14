package app.kavach.android.core.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** Device setting. Unsupported or software-rendered devices always use the solid surface. */
val LocalGlassEnabled = staticCompositionLocalOf { true }

val LocalFloatingBarInset = staticCompositionLocalOf { androidx.compose.ui.unit.Dp(0f) }
