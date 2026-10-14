package app.kavach.android.feature.shared

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.config.AppConfig
import app.kavach.android.core.data.model.FirstResponderSkill
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.model.Vulnerability
import app.kavach.android.core.domain.Phones
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.ui.components.ConfirmDialog
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.ListRow
import app.kavach.android.core.ui.components.LoadingState
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/**
 * Profile for every role (Profile.jsx). Residents also edit safety information and the first
 * responder skill; staff see duty and specialties. Language, dark mode and text size follow the
 * system. Shown as a bottom tab for residents and responders (no back arrow) and pushed
 * from Admin "More" (back arrow).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(navigator: KavachNavigator) {
    val vm: ProfileViewModel = hiltViewModel()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val alertsEnabled by vm.alertsEnabled.collectAsStateWithLifecycle()
    val alertSound by vm.alertSound.collectAsStateWithLifecycle()
    val queued by vm.queuedReports.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val dutyPending by vm.dutyPending.collectAsStateWithLifecycle()
    val signingOut by vm.signingOut.collectAsStateWithLifecycle()
    val context = LocalContext.current
    MessageEffect(vm.messages.flow)

    // Re-checked on return from system settings
    var notificationsOn by remember { mutableStateOf(context.notificationsAllowed()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsOn = context.notificationsAllowed() }

    var confirmLogOut by rememberSaveable { mutableStateOf(false) }
    val me = profile

    KavachScaffold(
        title = stringResource(R.string.nav_profile),
        onBack = if (me?.role == UserRole.Admin) ({ navigator.back() }) else null,
    ) { padding ->
        if (me == null) {
            LoadingState(Modifier.padding(padding))
            return@KavachScaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                // Before the scroll so the viewport shrinks and the focused field scrolls above the keyboard
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.lg),
        ) {
            HeroCard(me)

            SectionHeader(stringResource(R.string.shared_profile_section_personal))
            PersonalCard(me, busy = busy == ProfileBusy.Personal, onSave = vm::savePersonal)
            Footnote(stringResource(R.string.shared_profile_phone_hint))

            if (me.role == UserRole.Resident) {
                SectionHeader(stringResource(R.string.common_home))
                KavachCard(contentPadding = PaddingValues(0.dp)) {
                    val home = Format.homeLine(context, me, withFloor = true)
                    ListRow(title = home.ifBlank { stringResource(R.string.shared_profile_home_none) })
                }

                SectionHeader(stringResource(R.string.shared_profile_section_safety))
                SafetyCard(me.vulnerability, busy = busy == ProfileBusy.Safety, onSave = vm::saveSafety)
                Footnote(stringResource(R.string.shared_profile_safety_explain))

                SectionHeader(stringResource(R.string.shared_profile_section_fr))
                SkillCard(me.firstResponderSkill, busy = busy == ProfileBusy.Skill, onSelect = vm::setSkill)
                Footnote(stringResource(R.string.shared_profile_fr_explain))
            } else {
                SectionHeader(stringResource(R.string.shared_profile_section_duty))
                DutyCard(
                    me,
                    onDuty = dutyPending ?: me.onDuty,
                    busy = busy == ProfileBusy.Duty,
                    onDutyChange = vm::setOnDuty,
                )
                Footnote(
                    stringResource(
                        if (me.role == UserRole.Admin) R.string.shared_profile_specialties_admin else R.string.shared_profile_specialties_hint,
                    ),
                )
            }

            SectionHeader(stringResource(R.string.shared_profile_section_alerts))
            KavachCard(contentPadding = PaddingValues(0.dp)) {
                SwitchRow(
                    title = stringResource(R.string.shared_profile_alerts_background),
                    hint = stringResource(
                        if (alertsEnabled) R.string.shared_profile_alerts_background_on else R.string.shared_profile_alerts_background_off,
                    ),
                    checked = alertsEnabled,
                    onCheckedChange = vm::setAlertsEnabled,
                )
                GroupDivider()
                SwitchRow(
                    title = stringResource(R.string.shared_profile_alert_sound),
                    hint = stringResource(R.string.shared_profile_alert_sound_hint),
                    checked = alertSound,
                    onCheckedChange = vm::setAlertSound,
                )
                GroupDivider()
                ListRow(
                    title = stringResource(R.string.shared_profile_notification_settings),
                    subtitle = stringResource(R.string.shared_profile_notification_settings_hint),
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = KavachTheme.colors.textTertiary,
                        )
                    },
                    onClick = { context.openAppNotificationSettings() },
                )
            }
            if (!notificationsOn) {
                Text(
                    stringResource(R.string.shared_setup_notif_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = KavachTheme.colors.danger,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                )
            }
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                TextButton(onClick = { context.openFullScreenAlertSettings() }) {
                    Text(stringResource(R.string.shared_full_screen_title))
                }
            }
            Footnote(stringResource(R.string.shared_profile_alerts_footnote))

            SectionHeader(stringResource(R.string.shared_profile_section_account))
            KavachCard(contentPadding = PaddingValues(0.dp)) {
                ListRow(
                    title = stringResource(R.string.shared_intro_replay),
                    subtitle = stringResource(R.string.shared_intro_replay_hint),
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = KavachTheme.colors.textTertiary,
                        )
                    },
                    onClick = { navigator.navigate(Routes.Walkthrough) },
                )
                GroupDivider()
                ListRow(
                    title = stringResource(R.string.shared_log_out),
                    titleColor = KavachTheme.colors.danger,
                    trailing = if (signingOut) ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
                    onClick = if (signingOut) null else ({ confirmLogOut = true }),
                )
            }

            Text(
                stringResource(R.string.common_version, AppConfig.APP_VERSION),
                style = MaterialTheme.typography.bodySmall,
                color = KavachTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg),
            )
        }
    }

    if (confirmLogOut) {
        ConfirmDialog(
            title = stringResource(R.string.shared_profile_logout_title),
            text = if (queued > 0) {
                pluralStringResource(R.plurals.shared_profile_logout_queued, queued, queued)
            } else {
                stringResource(R.string.shared_profile_logout_text)
            },
            confirmLabel = stringResource(R.string.shared_log_out),
            destructive = true,
            onConfirm = {
                confirmLogOut = false
                vm.signOut()
            },
            onDismiss = { confirmLogOut = false },
        )
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = Spacing.md), color = KavachTheme.colors.separator)
}

@Composable
private fun HeroCard(profile: Profile) {
    val sub = listOfNotNull(
        stringResource(profile.role.labelRes()),
        listOfNotNull(profile.society?.name, profile.society?.city).filter { it.isNotBlank() }
            .joinToString(", ").ifBlank { null },
    ).joinToString(" · ")
    KavachCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            InitialAvatar(profile.fullName)
            Column(Modifier.weight(1f)) {
                Text(profile.fullName, style = MaterialTheme.typography.titleLarge)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
            }
        }
    }
}

/** Name and phone, with Save/Cancel once something changed. Resets when the profile changes. */
@Composable
private fun PersonalCard(profile: Profile, busy: Boolean, onSave: (name: String, phone: String) -> Unit) {
    var name by rememberSaveable(profile.fullName) { mutableStateOf(profile.fullName) }
    var phone by rememberSaveable(profile.phone) { mutableStateOf(Phones.display(profile.phone)) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    val nameChanged = name.trim() != profile.fullName.trim()
    val phoneChanged = !ProfileLogic.samePhone(phone, profile.phone)
    val dirty = nameChanged || phoneChanged
    val nameError = ProfileLogic.nameError(name)
    val phoneError = if (phoneChanged) ProfileLogic.phoneError(phone) else null

    KavachCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(ProfileLogic.NAME_MAX + 20) },
                label = { Text(stringResource(R.string.common_name)) },
                isError = showErrors && nameError != null,
                supportingText = if (showErrors && nameError != null) ({ Text(stringResource(nameError)) }) else null,
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.take(20) },
                label = { Text(stringResource(R.string.common_phone)) },
                isError = showErrors && phoneError != null,
                supportingText = if (showErrors && phoneError != null) ({ Text(stringResource(phoneError)) }) else null,
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.common_email), style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
                Text(
                    profile.email?.takeIf { it.isNotBlank() } ?: stringResource(R.string.shared_profile_no_email),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (dirty) {
                ActionRow(
                    saveLabel = stringResource(R.string.shared_profile_save_changes),
                    busy = busy,
                    onCancel = {
                        name = profile.fullName
                        phone = Phones.display(profile.phone)
                        showErrors = false
                    },
                    onSave = {
                        if (nameError == null && phoneError == null) onSave(name, phone) else showErrors = true
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionRow(saveLabel: String, busy: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.common_cancel)) }
        ActionButton(text = saveLabel, onClick = onSave, busy = busy, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Flags and a note for responders; Save/Cancel once something changed. */
@Composable
private fun SafetyCard(value: Vulnerability, busy: Boolean, onSave: (Vulnerability) -> Unit) {
    var elderly by rememberSaveable(value) { mutableStateOf(value.elderly) }
    var mobility by rememberSaveable(value) { mutableStateOf(value.mobility) }
    var medicalDevice by rememberSaveable(value) { mutableStateOf(value.medicalDevice) }
    var infant by rememberSaveable(value) { mutableStateOf(value.infant) }
    var note by rememberSaveable(value) { mutableStateOf(value.note.orEmpty()) }

    val dirty = elderly != value.elderly || mobility != value.mobility ||
        medicalDevice != value.medicalDevice || infant != value.infant ||
        note.trim() != value.note.orEmpty().trim()
    val left = ProfileLogic.NOTE_MAX - note.length

    KavachCard(contentPadding = PaddingValues(0.dp)) {
        SwitchRow(stringResource(R.string.shared_profile_vuln_elderly), elderly, { elderly = it }, enabled = !busy)
        GroupDivider()
        SwitchRow(stringResource(R.string.shared_profile_vuln_mobility), mobility, { mobility = it }, enabled = !busy)
        GroupDivider()
        SwitchRow(stringResource(R.string.shared_profile_vuln_medical_device), medicalDevice, { medicalDevice = it }, enabled = !busy)
        GroupDivider()
        SwitchRow(stringResource(R.string.shared_profile_vuln_infant), infant, { infant = it }, enabled = !busy)
        GroupDivider()
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(ProfileLogic.NOTE_MAX) },
                label = { Text(stringResource(R.string.shared_profile_vuln_note)) },
                placeholder = { Text(stringResource(R.string.shared_profile_vuln_note_placeholder)) },
                supportingText = if (left <= ProfileLogic.NOTE_WARN_LEFT) {
                    { Text(stringResource(R.string.shared_profile_char_count, note.length, ProfileLogic.NOTE_MAX)) }
                } else {
                    null
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            if (dirty) {
                ActionRow(
                    saveLabel = stringResource(R.string.shared_profile_save_safety),
                    busy = busy,
                    onCancel = {
                        elderly = value.elderly
                        mobility = value.mobility
                        medicalDevice = value.medicalDevice
                        infant = value.infant
                        note = value.note.orEmpty()
                    },
                    onSave = {
                        onSave(
                            Vulnerability(
                                elderly = elderly,
                                mobility = mobility,
                                medicalDevice = medicalDevice,
                                infant = infant,
                                note = note.trim().ifBlank { null },
                            ),
                        )
                    },
                )
            }
        }
    }
}

@StringRes
private fun skillLabel(skill: FirstResponderSkill?): Int = when (skill) {
    null -> R.string.shared_profile_fr_none
    FirstResponderSkill.Doctor -> R.string.shared_profile_fr_doctor
    FirstResponderSkill.Nurse -> R.string.shared_profile_fr_nurse
    FirstResponderSkill.Paramedic -> R.string.shared_profile_fr_paramedic
    FirstResponderSkill.FirstAid -> R.string.shared_profile_fr_first_aid
}

/** Opt in or out of community first responder alerts. Saves as soon as a choice is made. */
@Composable
private fun SkillCard(current: FirstResponderSkill?, busy: Boolean, onSelect: (FirstResponderSkill?) -> Unit) {
    val choices: List<FirstResponderSkill?> = listOf(null) + FirstResponderSkill.entries
    KavachCard {
        PickerField(
            label = stringResource(R.string.shared_profile_fr_label),
            valueText = stringResource(skillLabel(current)),
            options = choices.map { stringResource(skillLabel(it)) },
            onSelect = { index -> if (choices[index] != current) onSelect(choices[index]) },
            enabled = !busy,
        )
    }
}

/** Duty switch and specialties (responders and admins). [onDuty] includes a save in progress. */
@Composable
private fun DutyCard(profile: Profile, onDuty: Boolean, busy: Boolean, onDutyChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val specialties = if (profile.role != UserRole.Admin && profile.specialties.isEmpty()) {
        stringResource(R.string.shared_profile_specialties_none)
    } else {
        Format.specialties(context, profile.role, profile.specialties)
    }
    KavachCard(contentPadding = PaddingValues(0.dp)) {
        SwitchRow(
            title = stringResource(R.string.shared_profile_on_duty),
            hint = stringResource(
                if (onDuty) R.string.shared_profile_on_duty_hint_on else R.string.shared_profile_on_duty_hint_off,
            ),
            checked = onDuty,
            onCheckedChange = onDutyChange,
            enabled = !busy,
        )
        GroupDivider()
        ListRow(title = stringResource(R.string.shared_profile_specialties), subtitle = specialties)
    }
}
