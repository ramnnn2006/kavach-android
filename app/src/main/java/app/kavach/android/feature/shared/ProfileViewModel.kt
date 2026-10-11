package app.kavach.android.feature.shared

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.FirstResponderSkill
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.Vulnerability
import app.kavach.android.core.data.outbox.OutboxRepository
import app.kavach.android.core.data.prefs.SettingsStore
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.repo.ProfilePatch
import app.kavach.android.core.data.repo.ProfileRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.domain.Phones
import app.kavach.android.core.ui.components.MessageQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** Which save is running (disables that section). */
enum class ProfileBusy { Personal, Safety, Skill, Duty }

/** Pure rules for the profile forms (Profile.jsx validation). */
object ProfileLogic {
    const val NAME_MAX = 80
    const val NOTE_MAX = 120
    /** Show the character counter when this many characters or fewer are left. */
    const val NOTE_WARN_LEFT = 20

    @StringRes
    fun nameError(name: String): Int? = when {
        name.isBlank() -> R.string.shared_profile_name_required
        name.trim().length > NAME_MAX -> R.string.shared_profile_name_too_long
        else -> null
    }

    /** Empty is fine (clears the number); anything else must be an Indian mobile number (Profile.jsx PHONE_RE). */
    @StringRes
    fun phoneError(input: String): Int? {
        if (input.isBlank()) return null
        val e164 = Phones.normalize(input)
        return if (e164 != null && INDIAN_MOBILE.matches(e164)) null else R.string.shared_profile_phone_invalid
    }

    private val INDIAN_MOBILE = Regex("^\\+91[6-9]\\d{9}$")

    /** What is stored for a typed number: E.164, or null to clear it. */
    fun phoneToStore(input: String): String? = if (input.isBlank()) null else Phones.normalize(input)

    /** Compares two numbers ignoring spaces and the +91 prefix style. */
    fun samePhone(a: String?, b: String?): Boolean = phoneKey(a) == phoneKey(b)

    private fun phoneKey(value: String?): String {
        val text = value.orEmpty().trim()
        if (text.isEmpty()) return ""
        return Phones.normalize(text) ?: text
    }

    fun trimNote(note: String): String = note.trim().take(NOTE_MAX)
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val session: SessionManager,
    private val profiles: ProfileRepository,
    private val settings: SettingsStore,
    private val outbox: OutboxRepository,
) : ViewModel() {

    val messages = MessageQueue()

    val profile: StateFlow<Profile?> = session.profile

    val alertsEnabled: StateFlow<Boolean> =
        settings.alertsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val alertSound: StateFlow<Boolean> =
        settings.alertSound.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Reports waiting on this phone (queued or failed): logging out deletes them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val queuedReports: StateFlow<Int> = session.profile
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { userId -> outbox.observe(userId) }
        .map { it.pending.size + it.failed.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _busy = MutableStateFlow<ProfileBusy?>(null)
    val busy: StateFlow<ProfileBusy?> = _busy.asStateFlow()

    private val _dutyPending = MutableStateFlow<Boolean?>(null)
    /** The duty value being saved, shown on the switch until the profile catches up. */
    val dutyPending: StateFlow<Boolean?> = _dutyPending.asStateFlow()

    private val _signingOut = MutableStateFlow(false)
    val signingOut: StateFlow<Boolean> = _signingOut.asStateFlow()

    /**
     * Runs [block] as [kind]. The session profile follows by Realtime; the save stays busy until it
     * shows the change ([applied]) or [PROFILE_SYNC_MS] passes, so the form doesn't flash old values.
     * SessionManager.refreshProfile() is not used: it reloads the profile from null, which swaps the
     * whole role shell for the splash and drops the user back on the home tab.
     */
    private fun save(
        kind: ProfileBusy,
        @StringRes successRes: Int,
        applied: (Profile) -> Boolean,
        block: suspend () -> Unit,
    ) {
        if (_busy.value != null) return
        _busy.value = kind
        viewModelScope.launch {
            try {
                block()
                withTimeoutOrNull(PROFILE_SYNC_MS) { session.profile.first { it != null && applied(it) } }
                messages.send(successRes)
            } catch (e: KavachException) {
                messages.error(e)
            } finally {
                _busy.value = null
                _dutyPending.value = null
            }
        }
    }

    /** Saves only what changed. [phone] is what the user typed. Inputs must already be validated. */
    fun savePersonal(name: String, phone: String) {
        val current = session.profile.value ?: return
        val patch = ProfilePatch()
        val newName = name.trim().takeIf { it != current.fullName.trim() }
        val phoneChanged = !ProfileLogic.samePhone(phone, current.phone)
        val newPhone = ProfileLogic.phoneToStore(phone)
        if (newName != null) patch.fullName(newName)
        if (phoneChanged) patch.phone(newPhone)
        if (patch.isEmpty) return
        save(
            ProfileBusy.Personal,
            R.string.common_saved,
            applied = { p ->
                (newName == null || p.fullName.trim() == newName) &&
                    (!phoneChanged || ProfileLogic.samePhone(p.phone, newPhone))
            },
        ) { profiles.updateMine(patch) }
    }

    fun saveSafety(vulnerability: Vulnerability) {
        val note = ProfileLogic.trimNote(vulnerability.note.orEmpty()).ifBlank { null }
        val clean = vulnerability.copy(note = note)
        save(ProfileBusy.Safety, R.string.common_saved, applied = { it.vulnerability == clean }) {
            profiles.updateMine(ProfilePatch().vulnerability(clean))
        }
    }

    /** null = not a community first responder. */
    fun setSkill(skill: FirstResponderSkill?) {
        save(ProfileBusy.Skill, R.string.common_saved, applied = { it.firstResponderSkill == skill }) {
            profiles.updateMine(ProfilePatch().firstResponderSkill(skill))
        }
    }

    fun setOnDuty(on: Boolean) {
        if (_busy.value != null) return
        _dutyPending.value = on
        save(
            ProfileBusy.Duty,
            if (on) R.string.shared_profile_duty_on_toast else R.string.shared_profile_duty_off_toast,
            applied = { it.onDuty == on },
        ) { profiles.setOnDuty(on) }
    }

    fun setAlertsEnabled(on: Boolean) {
        viewModelScope.launch {
            settings.setAlertsEnabled(on)
            messages.send(if (on) R.string.shared_profile_alerts_on_toast else R.string.shared_profile_alerts_off_toast)
        }
    }

    fun setAlertSound(on: Boolean) {
        viewModelScope.launch {
            settings.setAlertSound(on)
            messages.send(if (on) R.string.shared_profile_sound_on_toast else R.string.shared_profile_sound_off_toast)
        }
    }

    /** Call only after the user confirmed. KavachRoot returns to the login screen by itself. */
    fun signOut() {
        if (_signingOut.value) return
        _signingOut.value = true
        viewModelScope.launch {
            try {
                session.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.error(e)
            } finally {
                _signingOut.value = false
            }
        }
    }

    private companion object {
        /** How long a save waits for the live profile to show the change. */
        const val PROFILE_SYNC_MS = 3_000L
    }
}
