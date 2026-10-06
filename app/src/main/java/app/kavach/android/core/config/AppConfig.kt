package app.kavach.android.core.config

import app.kavach.android.BuildConfig

object AppConfig {
    const val APP_VERSION = BuildConfig.VERSION_NAME
    val isSupabaseConfigured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_KEY.isNotBlank()

    /** India's public emergency numbers (society.js PUBLIC_EMERGENCY). */
    const val EMERGENCY_112 = "112"
    const val FIRE_101 = "101"
    const val AMBULANCE_108 = "108"

    /** ReportDetails SEND_TIMEOUT_MS: after this a report goes to the outbox. */
    const val SEND_TIMEOUT_MS = 15_000L
    /** resident.js MEDICAL_POLL_MS */
    const val MEDICAL_POLL_MS = 15_000L
    /** admin/parts/format.js ESCALATE_DEFAULT_MIN */
    const val ESCALATE_DEFAULT_MIN = 2.0
    /** Default country code for phone login. */
    const val DEFAULT_COUNTRY_CODE = "+91"
}
