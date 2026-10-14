package app.kavach.android.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Material roles from tokens.css: grouped background (--bg), cards (--surface), labels, separator.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0062CC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FA),
    onPrimaryContainer = Color(0xFF001D3D),
    secondary = Color(0xFF5856D6),
    onSecondary = Color.White,
    secondaryContainer = Color(0x1F787880),
    onSecondaryContainer = Color.Black,
    error = Color(0xFFD3281E),
    onError = Color.White,
    errorContainer = Color(0xFFFFE3E1),
    onErrorContainer = Color(0xFF5C0A05),
    background = Color(0xFFF2F2F7),
    onBackground = Color.Black,
    surface = Color(0xFFF2F2F7),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xC23C3C43),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE5E5EA),
    // outline is the boundary of text fields, outlined buttons and switches: it needs 3:1
    // (WCAG 1.4.11), which the translucent separator colour doesn't reach. Separators use outlineVariant.
    outline = Color(0xFF86868B),
    outlineVariant = Color(0x293C3C43),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF0069D1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0B3A6E),
    onPrimaryContainer = Color(0xFFD6E6FA),
    secondary = Color(0xFF5E5CE6),
    onSecondary = Color.White,
    secondaryContainer = Color(0x3D767680),
    onSecondaryContainer = Color.White,
    error = Color(0xFFC92D24),
    onError = Color.White,
    errorContainer = Color(0xFF5C1410),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xB3EBEBF5),
    surfaceContainerLowest = Color(0xFF1C1C1E),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0x66545458),
)

private val KavachShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),    // --r-sm
    medium = RoundedCornerShape(12.dp),  // --r-md (cards)
    large = RoundedCornerShape(14.dp),   // --r-lg
    extraLarge = RoundedCornerShape(20.dp),
)

/** Follows the system light/dark setting. No dynamic colour: Kavach keeps its own palette. */
@Composable
fun KavachTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKavachColors provides if (darkTheme) DarkKavachColors else LightKavachColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = KavachTypography,
            shapes = KavachShapes,
            content = content,
        )
    }
}

object KavachTheme {
    val colors: KavachColors
        @Composable @ReadOnlyComposable get() = LocalKavachColors.current
}

/** 8-point spacing (tokens.css --s-*). */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 40.dp
    /** Side padding for screen content. */
    val screen = 16.dp
}
