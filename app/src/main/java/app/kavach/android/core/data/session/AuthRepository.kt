package app.kavach.android.core.data.session

import androidx.annotation.StringRes
import app.kavach.android.R
import app.kavach.android.core.data.net.ConnectivityObserver
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

enum class AuthErrorReason(@StringRes val messageRes: Int) {
    WrongPassword(R.string.auth_err_wrong_password),
    EmailNotConfirmed(R.string.auth_err_email_not_confirmed),
    NotApproved(R.string.auth_err_not_approved),
    InvalidCode(R.string.auth_err_invalid_code),
    InvalidPhone(R.string.auth_err_invalid_phone),
    RateLimited(R.string.auth_err_rate_limited),
    Offline(R.string.common_error_offline),
    Generic(R.string.common_error_generic),
}

class AuthException(val reason: AuthErrorReason, cause: Throwable? = null) : Exception(reason.name, cause)

/**
 * Sign-in actions. Session state lives in [SessionManager]. Phone numbers must already be in
 * E.164 (use Phones.normalize()). A number that is not on the society's approved list is refused
 * by the "before user created" hook with the text `phone_not_approved`.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val client: SupabaseClient,
    private val connectivity: ConnectivityObserver,
) {
    suspend fun sendPhoneOtp(phoneE164: String) = guard {
        client.auth.signInWith(OTP) {
            phone = phoneE164
            createUser = true
        }
    }

    suspend fun verifyPhoneOtp(phoneE164: String, code: String) = guard {
        client.auth.verifyPhoneOtp(type = OtpType.Phone.SMS, phone = phoneE164, token = code.trim())
    }

    suspend fun signInWithEmail(email: String, password: String) = guard {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun sendPasswordReset(email: String) = guard {
        client.auth.resetPasswordForEmail(email.trim())
    }

    /**
     * Use SessionManager.signOut() from screens: it also clears the outbox. auth.signOut() keeps
     * the local session when the logout request fails (offline), so clear it here: logging out
     * always logs out on this phone.
     */
    internal suspend fun signOutRemote() {
        try {
            client.auth.signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { client.auth.clearSession() }
        }
    }

    private suspend fun <T> guard(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AuthException(friendlyReason(e), e)
        }

    /** AuthContext friendlyAuthError(), plus the phone cases. */
    private fun friendlyReason(e: Throwable): AuthErrorReason {
        if (!connectivity.isOnline.value || e is HttpRequestException || e is IOException) return AuthErrorReason.Offline
        val msg = when (e) {
            is AuthRestException -> "${e.error} ${e.errorDescription} ${e.message}"
            is RestException -> "${e.error} ${e.description}"
            else -> e.message.orEmpty()
        }.lowercase()
        val status = (e as? RestException)?.statusCode
        return when {
            "phone_not_approved" in msg -> AuthErrorReason.NotApproved
            "invalid login credentials" in msg -> AuthErrorReason.WrongPassword
            "email not confirmed" in msg -> AuthErrorReason.EmailNotConfirmed
            "token has expired" in msg || "invalid otp" in msg || "otp_expired" in msg || ("token" in msg && "invalid" in msg) ->
                AuthErrorReason.InvalidCode
            "invalid phone" in msg || "phone number" in msg && "invalid" in msg -> AuthErrorReason.InvalidPhone
            "rate limit" in msg || status == 429 -> AuthErrorReason.RateLimited
            "fetch" in msg || "network" in msg -> AuthErrorReason.Offline
            else -> AuthErrorReason.Generic
        }
    }
}
