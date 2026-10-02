package app.kavach.android.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Sizes follow tokens.css Dynamic Type values, mapped onto Material roles. The system font and
// the user's font scale apply (no fixed "larger text" switch: Android's font size setting does it).
private val base = TextStyle(fontFamily = FontFamily.Default)

val KavachTypography = Typography(
    displaySmall = base.copy(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),      // large title
    headlineMedium = base.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),    // title1
    headlineSmall = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold), // title2
    titleLarge = base.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),    // title3
    titleMedium = base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),   // headline
    titleSmall = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.copy(fontSize = 17.sp, lineHeight = 22.sp),                                        // body
    bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 20.sp),                                       // subhead
    bodySmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp),                                        // footnote
    labelLarge = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),      // caption
)
