package app.kavach.android.core.data.remote

import androidx.annotation.StringRes
import app.kavach.android.R
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException

/** db.js DbError codes. */
enum class ErrorCode {
    AlreadyClaimed, Forbidden, NotFound, Invalid, NotOnDuty, WrongSpecialty, Duplicate, Offline, Timeout, Unknown;

    /** Outbox: never retried, shown to the resident until dismissed (outbox.js PERMANENT). */
    val isPermanent: Boolean get() = this == Invalid || this == Forbidden

    /** ReportDetails QUEUE_CODES: these go to the outbox instead of showing an error. */
    val shouldQueue: Boolean get() = this == Offline || this == Timeout || this == Unknown
}

/**
 * Every repository function throws only this. [detail] holds the server's `detail` text for
 * known RPC codes (e.g. "Incident is already closed"), used by [messageRes]. Never show
 * [detail] or [message] to users; show [messageRes].
 */
class KavachException(
    val code: ErrorCode,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: code.name, cause)

private val KNOWN_CODES = mapOf(
    "already_claimed" to ErrorCode.AlreadyClaimed,
    "forbidden" to ErrorCode.Forbidden,
    "not_found" to ErrorCode.NotFound,
    "invalid" to ErrorCode.Invalid,
    "not_on_duty" to ErrorCode.NotOnDuty,
    "wrong_specialty" to ErrorCode.WrongSpecialty,
)

/** Mirror of db.js toDbError(). */
fun Throwable.toKavach(online: Boolean = true): KavachException {
    if (this is KavachException) return this
    if (!online) return KavachException(ErrorCode.Offline, message, this)
    if (this is TimeoutCancellationException || this is HttpRequestTimeoutException) {
        return KavachException(ErrorCode.Timeout, message, this)
    }
    if (this is HttpRequestException || this is IOException) return KavachException(ErrorCode.Offline, message, this)
    if (this is PostgrestRestException) {
        val msg = error
        val detail = this.details
        val text = "$msg ${detail.orEmpty()} ${hint.orEmpty()}".lowercase()
        KNOWN_CODES.entries.firstOrNull { msg.lowercase().startsWith(it.key) }?.let {
            return KavachException(it.value, detail ?: msg, this)
        }
        return when {
            code == "42501" || "row-level security" in text || "permission denied" in text ->
                KavachException(ErrorCode.Forbidden, msg, this)
            code == "PGRST116" -> KavachException(ErrorCode.NotFound, msg, this)
            code == "23505" -> KavachException(ErrorCode.Duplicate, msg, this)
            code == "23514" || code == "22P02" || code == "23502" -> KavachException(ErrorCode.Invalid, msg, this)
            // Other constraint (23xxx, e.g. a missing zone) and bad-data (22xxx) errors never succeed on retry
            code?.let { it.startsWith("23") || it.startsWith("22") } == true -> KavachException(ErrorCode.Invalid, msg, this)
            else -> KavachException(ErrorCode.Unknown, msg, this)
        }
    }
    if (this is RestException) {
        val text = (error + " " + description.orEmpty()).lowercase()
        if ("network" in text || "failed to fetch" in text) return KavachException(ErrorCode.Offline, error, this)
        return KavachException(ErrorCode.Unknown, error, this)
    }
    return KavachException(ErrorCode.Unknown, message, this)
}

/** Server `detail` texts → strings (db.js INVALID_DETAILS). Anything else → generic text. */
private val INVALID_DETAILS = mapOf(
    "Incident is already closed" to R.string.common_err_closed,
    "Status can only move forward" to R.string.common_err_stale,
    "Use cancel_incident to cancel" to R.string.common_err_stale,
    "Location (zone) is required" to R.string.common_err_location,
    "Zone not in society" to R.string.common_err_location,
    "Choose a tower in your society" to R.string.common_err_choose_tower,
    "You cannot remove your own admin role" to R.string.common_err_own_admin,
    "This safety check has ended" to R.string.common_err_check_ended,
    "That flat number belongs to another tower" to R.string.common_err_flat_tower,
    "Enter a valid flat number" to R.string.common_err_flat_number,
    "Message is required (max 300 characters)" to R.string.common_err_message,
    "Only active medical incidents" to R.string.common_err_medical_only,
    // Approved members list (backend step 2)
    "Enter a valid phone number" to R.string.common_err_phone_number,
    "This phone number is already on the list" to R.string.common_err_phone_taken,
    "Residents need a flat" to R.string.common_err_member_flat,
    "Name is required" to R.string.common_err_name_required,
)

/** User-facing message for any error (db.js errorMessage()). */
@StringRes
fun Throwable.messageRes(): Int {
    val e = this as? KavachException ?: return R.string.common_error_generic
    return when (e.code) {
        ErrorCode.Offline -> R.string.common_error_offline
        ErrorCode.Timeout -> R.string.common_error_slow
        ErrorCode.Forbidden -> R.string.common_error_forbidden
        ErrorCode.NotFound -> R.string.common_error_not_found
        ErrorCode.AlreadyClaimed -> R.string.common_err_already_claimed
        ErrorCode.NotOnDuty -> R.string.common_err_not_on_duty
        ErrorCode.WrongSpecialty -> R.string.common_err_wrong_specialty
        ErrorCode.Invalid -> INVALID_DETAILS[e.detail] ?: R.string.common_error_invalid
        ErrorCode.Duplicate, ErrorCode.Unknown -> R.string.common_error_generic
    }
}
