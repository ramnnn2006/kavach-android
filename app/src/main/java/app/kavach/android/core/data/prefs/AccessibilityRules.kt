package app.kavach.android.core.data.prefs

/** Device-relative text sizes, in exact 10% steps without float drift. */
object AccessibilityRules {
    fun textScale(value: Float): Float =
        if (!value.isFinite()) 1f else (kotlin.math.round(value * 10f) / 10f).coerceIn(0.8f, 1.6f)
    fun theme(value: String): String = value.takeIf { it in setOf("system", "light", "dark") } ?: "system"
}
