package app.kavach.android.feature.shared

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.session.AuthErrorReason
import app.kavach.android.core.data.session.AuthException
import app.kavach.android.core.data.session.AuthRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.data.session.SessionState
import app.kavach.android.core.domain.Phones
import app.kavach.android.core.ui.components.MessageQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LoginMode { Phone, Email }

enum class PhoneStep { Number, Code }

data class LoginUiState(
    val mode: LoginMode = LoginMode.Phone,
    val step: PhoneStep = PhoneStep.Number,
    /** What the user typed in the number field. */
    val phone: String = "",
    /** The E.164 number the code was sent to (set once the code is on its way). */
    val codeSentTo: String? = null,
    val code: String = "",
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    @StringRes val errorRes: Int? = null,
    /** Seconds until "send a new code" is allowed again; 0 = allowed. */
    val resendSeconds: Int = 0,
    /** When a new code may be asked for [codeSentTo] (epoch millis); 0 = no code sent yet. */
    val resendAt: Long = 0L,
)

/** Pure input rules for the login form (kept apart so they can be unit tested). */
object LoginInput {
    const val CODE_LENGTH = 6
    /** Supabase Auth sends a number a new SMS code at most once a minute (its default limit). */
    const val RESEND_SECONDS = 60
    private const val PHONE_MAX = 18
    private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    /** Digits, spaces, plus and dashes only. */
    fun sanitizePhone(raw: String): String = raw.filter { it.isDigit() || it == ' ' || it == '+' || it == '-' }.take(PHONE_MAX)

    /** Digits only, at most [CODE_LENGTH]. */
    fun sanitizeCode(raw: String): String = raw.filter { it.isDigit() }.take(CODE_LENGTH)

    fun isEmail(value: String): Boolean = EMAIL_RE.matches(value.trim())

    @StringRes
    fun emailError(email: String): Int? = when {
        email.isBlank() -> R.string.shared_login_err_email_required
        !isEmail(email) -> R.string.shared_login_err_email_invalid
        else -> null
    }

    /** Whole seconds (rounded up) until [resendAt], never more than [RESEND_SECONDS]. */
    fun resendSecondsLeft(resendAt: Long, now: Long): Int {
        val millis = (resendAt - now).coerceIn(0L, RESEND_SECONDS * 1_000L)
        return ((millis + 999) / 1_000).toInt()
    }
}

/**
 * Sign-in form. The screen sits outside the NavHost, so this ViewModel lives as long as the
 * activity: it stops its countdown while someone is signed in and starts clean after a sign-out.
 * The form (never the password or the code) is kept across process death, e.g. while the user
 * reads the SMS in another app.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    session: SessionManager,
    private val saved: SavedStateHandle,
    private val outbox: app.kavach.android.core.data.outbox.OutboxRepository,
) : ViewModel() {

    val messages = MessageQueue()
    val unsentCount = outbox.observe(null).map { it.pending.size }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), 0)

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var timerJob: Job? = null

    /** True while a session exists: nothing of the form is saved then. */
    private var signedIn = false

    init {
        // Back after process death on the code step: carry on counting down
        val restored = _state.value
        if (restored.step == PhoneStep.Code) startCountdown(restored.resendAt)
        viewModelScope.launch {
            session.state.collect { s ->
                if (s is SessionState.SignedIn && !signedIn) {
                    signedIn = true
                    timerJob?.cancel()
                    SAVED_KEYS.forEach { key -> saved.remove<Any>(key) }
                } else if (s == SessionState.SignedOut && signedIn) {
                    signedIn = false
                    _state.value = LoginUiState()
                }
            }
        }
        viewModelScope.launch { _state.collect { if (!signedIn) persist(it) } }
    }

    fun setMode(mode: LoginMode) = _state.update { it.copy(mode = mode, errorRes = null, password = "") }

    fun onPhoneChange(raw: String) = _state.update { it.copy(phone = LoginInput.sanitizePhone(raw), errorRes = null) }

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, errorRes = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, errorRes = null) }

    /** Typing the 6th digit verifies straight away. */
    fun onCodeChange(raw: String) {
        val code = LoginInput.sanitizeCode(raw)
        _state.update { it.copy(code = code, errorRes = null) }
        if (code.length == LoginInput.CODE_LENGTH) verifyCode()
    }

    /**
     * Back to the number field. The number the code went to and its resend time are kept, so
     * sending to the same number again within the minute goes back to that code (see [sendCode]).
     */
    fun changeNumber() {
        timerJob?.cancel()
        _state.update { it.copy(step = PhoneStep.Number, code = "", errorRes = null) }
    }

    /** "Send code" on the number step. */
    fun sendCode() {
        val current = _state.value
        if (current.busy) return
        val e164 = Phones.normalize(current.phone)
        if (e164 == null) {
            _state.update { it.copy(errorRes = AuthErrorReason.InvalidPhone.messageRes) }
            return
        }
        // A code went to this number under a minute ago (Supabase would refuse a new one): use it
        if (e164 == current.codeSentTo && current.resendAt > System.currentTimeMillis()) {
            _state.update { it.copy(step = PhoneStep.Code, code = "", errorRes = null) }
            startCountdown(current.resendAt)
            return
        }
        requestCode(e164, resend = false)
    }

    /** "Send a new code" on the code step. */
    fun resendCode() {
        val current = _state.value
        if (current.busy || current.resendSeconds > 0) return
        val e164 = current.codeSentTo ?: return
        requestCode(e164, resend = true)
    }

    private fun requestCode(e164: String, resend: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, errorRes = null) }
            try {
                auth.sendPhoneOtp(e164)
                val resendAt = System.currentTimeMillis() + LoginInput.RESEND_SECONDS * 1_000L
                _state.update {
                    it.copy(busy = false, step = PhoneStep.Code, codeSentTo = e164, code = "", resendAt = resendAt)
                }
                startCountdown(resendAt)
                if (resend) messages.send(R.string.shared_login_code_resent)
            } catch (e: AuthException) {
                _state.update { it.copy(busy = false, errorRes = e.reason.messageRes) }
            }
        }
    }

    /** Counts down to [resendAt]; worked out from the clock each tick, so it never drifts. */
    private fun startCountdown(resendAt: Long) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val left = LoginInput.resendSecondsLeft(resendAt, now)
                _state.update { it.copy(resendSeconds = left) }
                if (left == 0) break
                // Wake up when the number on screen changes
                delay((resendAt - now - (left - 1) * 1_000L).coerceIn(1L, 1_000L))
            }
        }
    }

    /** "Verify and sign in". After success the app switches screens by itself. */
    fun verifyCode() {
        val current = _state.value
        if (current.busy) return
        val e164 = current.codeSentTo ?: return
        if (current.code.length != LoginInput.CODE_LENGTH) {
            _state.update { it.copy(errorRes = AuthErrorReason.InvalidCode.messageRes) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, errorRes = null) }
            try {
                auth.verifyPhoneOtp(e164, current.code)
                _state.update { it.copy(busy = false) }
            } catch (e: AuthException) {
                _state.update { it.copy(busy = false, code = "", errorRes = e.reason.messageRes) }
            }
        }
    }

    /** Email + password sign-in (secondary option). */
    fun signInWithEmail() {
        val current = _state.value
        if (current.busy) return
        val problem = LoginInput.emailError(current.email)
            ?: if (current.password.isEmpty()) R.string.shared_login_err_password_required else null
        if (problem != null) {
            _state.update { it.copy(errorRes = problem) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, errorRes = null) }
            try {
                auth.signInWithEmail(current.email, current.password)
                _state.update { it.copy(busy = false) }
            } catch (e: AuthException) {
                _state.update { it.copy(busy = false, errorRes = e.reason.messageRes) }
            }
        }
    }

    fun forgotPassword() {
        val current = _state.value
        if (current.busy) return
        if (current.email.isBlank()) {
            _state.update { it.copy(errorRes = R.string.shared_login_enter_email_first) }
            return
        }
        LoginInput.emailError(current.email)?.let { problem ->
            _state.update { it.copy(errorRes = problem) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, errorRes = null) }
            try {
                auth.sendPasswordReset(current.email)
                _state.update { it.copy(busy = false) }
                messages.send(R.string.shared_login_reset_sent, current.email.trim())
            } catch (e: AuthException) {
                _state.update { it.copy(busy = false, errorRes = e.reason.messageRes) }
            }
        }
    }

    private fun restore(): LoginUiState {
        val sentTo = saved.get<String>(KEY_SENT_TO)
        val resendAt = saved.get<Long>(KEY_RESEND_AT) ?: 0L
        return LoginUiState(
            mode = if (saved.get<String>(KEY_MODE) == LoginMode.Email.name) LoginMode.Email else LoginMode.Phone,
            step = if (sentTo != null && saved.get<String>(KEY_STEP) == PhoneStep.Code.name) PhoneStep.Code else PhoneStep.Number,
            phone = saved.get<String>(KEY_PHONE).orEmpty(),
            codeSentTo = sentTo,
            email = saved.get<String>(KEY_EMAIL).orEmpty(),
            resendAt = resendAt,
            resendSeconds = LoginInput.resendSecondsLeft(resendAt, System.currentTimeMillis()),
        )
    }

    private fun persist(s: LoginUiState) {
        saved[KEY_MODE] = s.mode.name
        saved[KEY_STEP] = s.step.name
        saved[KEY_PHONE] = s.phone
        saved[KEY_SENT_TO] = s.codeSentTo
        saved[KEY_EMAIL] = s.email
        saved[KEY_RESEND_AT] = s.resendAt
    }

    private companion object {
        const val KEY_MODE = "login_mode"
        const val KEY_STEP = "login_step"
        const val KEY_PHONE = "login_phone"
        const val KEY_SENT_TO = "login_sent_to"
        const val KEY_EMAIL = "login_email"
        const val KEY_RESEND_AT = "login_resend_at"
        val SAVED_KEYS = listOf(KEY_MODE, KEY_STEP, KEY_PHONE, KEY_SENT_TO, KEY_EMAIL, KEY_RESEND_AT)
    }
}
