package app.kavach.android.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import app.kavach.android.R
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Bundled typefaces work offline; Android font scale and in-app text size still apply.
private val body = FontFamily(Font(R.font.dm_sans_400, FontWeight.Normal), Font(R.font.dm_sans_500, FontWeight.Medium), Font(R.font.dm_sans_600, FontWeight.SemiBold), Font(R.font.dm_sans_700, FontWeight.Bold))
private val display = FontFamily(Font(R.font.manrope_400, FontWeight.Normal), Font(R.font.manrope_500, FontWeight.Medium), Font(R.font.manrope_600, FontWeight.SemiBold), Font(R.font.manrope_700, FontWeight.Bold))
private val base = TextStyle(fontFamily = body)
private val heading = TextStyle(fontFamily = display, letterSpacing = (-0.3).sp)

val KavachTypography = Typography(
    displaySmall = heading.copy(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),      // large title
    headlineMedium = heading.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),    // title1
    headlineSmall = heading.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold), // title2
    titleLarge = heading.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),    // title3
    titleMedium = heading.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),   // headline
    titleSmall = heading.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.copy(fontSize = 17.sp, lineHeight = 22.sp),                                        // body
    bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 20.sp),                                       // subhead
    bodySmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp),                                        // footnote
    labelLarge = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),      // caption
)
