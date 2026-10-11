package app.kavach.android.feature.resident

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.config.AppConfig
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentEvent
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.domain.Rules
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.nav.incidentSharedKey
import app.kavach.android.core.nav.sharedBoundsFor
import app.kavach.android.core.ui.components.CallButton
import app.kavach.android.core.ui.components.CallButtonStyle
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.ErrorBanner
import app.kavach.android.core.ui.components.ErrorState
import app.kavach.android.core.ui.components.EventTimeline
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.LoadingState
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.ProgressStep
import app.kavach.android.core.ui.components.ProgressSteps
import app.kavach.android.core.ui.components.PulsingDot
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.TypeIcon
import app.kavach.android.core.ui.components.visibleEvents
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.format.rememberNow
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/** Live tracker for one of my reports: status in plain words, who is coming, what to do now. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackerScreen(navigator: KavachNavigator) {
    val vm: TrackerViewModel = hiltViewModel()
    MessageEffect(vm.messages.flow)

    val incidentState by vm.incident.collectAsStateWithLifecycle()
    val eventsState by vm.events.collectAsStateWithLifecycle()
    val responders by vm.responderCount.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val securityPhone = profile?.society?.securityPhone?.takeIf { it.isNotBlank() }

    // Right after sending, Back goes home (not to the form that was replaced)
    val goHome: () -> Unit = { navigator.openTab(Routes.ResidentHome) }
    BackHandler(enabled = vm.justSent, onBack = goHome)
    val onBack: () -> Unit = if (vm.justSent) goHome else navigator::back

    KavachScaffold(title = stringResource(R.string.resident_tracker_title), onBack = onBack) { padding ->
        val incident = incidentState.data
        val error = incidentState.error
        val modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        when {
            incident != null -> TrackerContent(
                incident = incident,
                events = eventsState,
                responders = responders,
                profile = profile,
                securityPhone = securityPhone,
                vm = vm,
                modifier = modifier,
            )
            // Until the report shows (slow network, or it failed to load), keep the way to get help in reach
            incidentState.loading -> Column(modifier) {
                LoadingState(Modifier.weight(1f))
                HelpCalls(securityPhone, Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.md))
            }
            error != null -> Column(
                modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                ErrorState(error, vm::retry)
                HelpCalls(securityPhone)
            }
            else -> EmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.resident_not_found_title),
                modifier = modifier,
                text = stringResource(R.string.resident_not_found_text),
                action = {
                    Button(onClick = { navigator.openTab(Routes.MyReports) }) {
                        Text(stringResource(R.string.resident_see_my_reports))
                    }
                },
            )
        }
    }
}

@Composable
private fun TrackerContent(
    incident: Incident,
    events: Loadable<List<IncidentEvent>>,
    responders: Int,
    profile: Profile?,
    securityPhone: String?,
    vm: TrackerViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val draft by vm.draft.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val cancelling by vm.cancelling.collectAsStateWithLifecycle()
    val cancelOpen by vm.cancelOpen.collectAsStateWithLifecycle()

    val colors = KavachTheme.colors
    val status = incident.status
    val active = incident.isActive
    val cancelled = status == IncidentStatus.Cancelled
    val isMine = incident.reporterId != null && incident.reporterId == profile?.id
    val team = Rules.teamFor(incident.type)
    val statusTone = colors.status(status)
    val place = Format.residentPlace(context, incident, if (isMine) profile else null)

    Column(
        modifier
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // Confirmation right after sending, until someone takes the report (then the status says it)
        if (vm.justSent && status == IncidentStatus.Pending) {
            NoticeBanner(colors.success, Icons.Rounded.CheckCircle) {
                Text(stringResource(R.string.resident_alert_sent), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(pendingSentenceRes(incident.type)), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (ResidentRules.isEscalatedWaiting(incident)) {
            NoticeBanner(colors.orange, Icons.Rounded.Warning) {
                Text(stringResource(R.string.resident_escalated), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Status + who is helping
        KavachCard(
            modifier = Modifier.sharedBoundsFor(incidentSharedKey(incident.id)),
            accent = statusTone,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TypeIcon(incident.type, size = 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(incident.type.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textMuted,
                    )
                    Text(
                        stringResource(status.labelRes(staff = false)),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (status == IncidentStatus.Pending) PulsingDot(colors.orange, size = 8.dp)
                        SentAtText(incident)
                    }
                }
            }

            val assignedName = incident.assignedName?.takeIf { it.isNotBlank() }
            if (assignedName != null) {
                HorizontalDivider(Modifier.padding(vertical = Spacing.md), color = colors.separator)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InitialAvatar(assignedName)
                    Column(Modifier.weight(1f)) {
                        Text(assignedName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(if (team == Rules.Team.Maintenance) R.string.resident_team_maintenance else R.string.resident_team_security),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                    val assignedPhone = incident.assignedPhone?.takeIf { it.isNotBlank() }
                    if (active && assignedPhone != null) {
                        val callName = stringResource(R.string.common_call_number, assignedName)
                        CallButton(
                            phone = assignedPhone,
                            label = stringResource(R.string.common_call),
                            style = CallButtonStyle.Primary,
                            modifier = Modifier.semantics { contentDescription = callName },
                        )
                    }
                }
            } else if (active) {
                HorizontalDivider(Modifier.padding(vertical = Spacing.md), color = colors.separator)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Icons.Rounded.Schedule, colors.gray)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.resident_waiting_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(pendingSentenceRes(incident.type)),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                    if (securityPhone != null) {
                        val callSecurityDesc = stringResource(R.string.common_call_security)
                        CallButton(
                            phone = securityPhone,
                            label = stringResource(R.string.common_call),
                            style = CallButtonStyle.Secondary,
                            modifier = Modifier.semantics { contentDescription = callSecurityDesc },
                        )
                    }
                }
            }
        }

        if (active && responders > 0) {
            NoticeBanner(colors.pink, Icons.Rounded.MonitorHeart) {
                Text(pluralStringResource(R.plurals.resident_fr_coming, responders, responders), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (active) SafetyTips(incident.type)

        // Progress
        if (!cancelled) {
            val labels = listOf(
                R.string.resident_step_sent,
                R.string.resident_step_accepted,
                R.string.resident_step_on_the_way,
                R.string.resident_step_arrived,
                R.string.resident_step_resolved,
            )
            val steps = ResidentRules.stepStates(incident).mapIndexed { i, step ->
                ProgressStep(label = stringResource(labels[i]), time = step.time, done = step.done, current = step.current)
            }
            Column {
                SectionHeader(stringResource(R.string.resident_progress_title))
                KavachCard { ProgressSteps(steps, tone = statusTone) }
            }
        }

        // What I reported
        Column {
            SectionHeader(stringResource(R.string.resident_your_report))
            val currentDraft = draft
            // Editing only while the report is open (the server refuses changes after that)
            if (currentDraft != null && active && isMine) {
                KavachCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.resident_people_label),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        PeopleStepper(currentDraft.people, vm::setDraftPeople)
                    }
                    Spacer(Modifier.size(Spacing.sm))
                    val left = ReportRules.NOTE_MAX - currentDraft.note.length
                    OutlinedTextField(
                        value = currentDraft.note,
                        onValueChange = vm::setDraftNote,
                        label = { Text(stringResource(R.string.resident_note_label)) },
                        supportingText = { Text(stringResource(R.string.resident_chars_left, left)) },
                        minLines = 3,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = vm::cancelEdit, enabled = !saving, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.common_cancel))
                        }
                        Button(
                            onClick = { vm.saveDetails(incident) },
                            enabled = !saving,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            if (saving) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onAccent)
                                Spacer(Modifier.size(Spacing.sm))
                            }
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }
            } else {
                KavachCard {
                    if (place.isNotBlank()) KeyValueRow(stringResource(R.string.resident_kv_where), place)
                    KeyValueRow(stringResource(R.string.resident_kv_people), incident.peopleAffected.toString())
                    if (!incident.description.isNullOrBlank()) {
                        KeyValueRow(stringResource(R.string.resident_kv_note), incident.description)
                    }
                    if (active && isMine) {
                        TextButton(onClick = { vm.startEdit(incident) }) {
                            Text(stringResource(R.string.resident_add_details))
                        }
                    }
                }
            }
        }

        // Updates: notes and changes the progress steps don't already show
        val hideSteps = !cancelled
        val eventList = events.data
        val visible = remember(eventList, hideSteps) { if (eventList != null) visibleEvents(eventList, hideSteps) else emptyList() }
        if (eventList == null && events.error != null) {
            Column {
                SectionHeader(stringResource(R.string.resident_updates_title))
                ErrorBanner(events.error, vm::retry)
            }
        } else if (eventList == null) {
            if (events.loading) {
                Column {
                    SectionHeader(stringResource(R.string.resident_updates_title))
                    Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        } else if (visible.isNotEmpty()) {
            Column {
                SectionHeader(stringResource(R.string.resident_updates_title))
                KavachCard { EventTimeline(eventList, staff = false, hideSteps = hideSteps) }
            }
        }

        if (active) {
            Column {
                SectionHeader(stringResource(R.string.resident_quick_call))
                KavachCard(contentPadding = PaddingValues(0.dp)) {
                    if (securityPhone != null) {
                        CallRow(
                            phone = securityPhone,
                            title = stringResource(R.string.resident_security_desk),
                            detail = securityPhone,
                            callLabel = stringResource(R.string.common_call_number, stringResource(R.string.resident_security_desk)),
                            leading = { IconTile(Icons.Rounded.Shield, colors.blue, size = 36.dp) },
                        )
                        HorizontalDivider(color = colors.separator)
                    }
                    CallRow(
                        phone = AppConfig.EMERGENCY_112,
                        title = stringResource(R.string.common_emergency_112),
                        detail = stringResource(R.string.resident_contact_112),
                        callLabel = stringResource(R.string.resident_call_112),
                        leading = { IconTile(Icons.Rounded.Warning, colors.red, size = 36.dp) },
                    )
                }
            }
        }

        if (ResidentRules.canCancel(status, isMine)) {
            TextButton(
                onClick = vm::openCancel,
                enabled = !cancelling,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (cancelling) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.danger)
                    Spacer(Modifier.size(Spacing.sm))
                }
                Text(stringResource(R.string.resident_cancel_false_alarm), color = colors.danger)
            }
        }
    }

    if (cancelOpen) {
        val cancelError by vm.cancelError.collectAsStateWithLifecycle()
        CancelReportDialog(
            running = cancelling,
            error = cancelError,
            onConfirm = vm::cancel,
            onDismiss = vm::dismissCancel,
        )
    }
}

/**
 * "Cancel this alert?" with an optional reason (ConfirmDialog plus a text field). Stays open while
 * the cancel runs and when it fails (with the reason kept and the error shown); closes on success.
 */
@Composable
private fun CancelReportDialog(
    running: Boolean,
    error: Throwable?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resident_cancel_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(stringResource(R.string.resident_cancel_confirm_text))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(ReportRules.NOTE_MAX) },
                    label = { Text(stringResource(R.string.resident_cancel_reason_label)) },
                    minLines = 2,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        stringResource(error.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KavachTheme.colors.danger,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.trim().ifEmpty { null }) }, enabled = !running) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = KavachTheme.colors.danger)
                    Spacer(Modifier.size(Spacing.sm))
                }
                Text(stringResource(R.string.resident_cancel_confirm_btn), color = KavachTheme.colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text(stringResource(R.string.resident_keep_alert)) }
        },
    )
}

/** Call the security desk (when the society has one) and 112. */
@Composable
private fun HelpCalls(securityPhone: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (securityPhone != null) {
            CallButton(securityPhone, stringResource(R.string.common_call_security), style = CallButtonStyle.Danger, fillWidth = true)
        }
        CallButton(AppConfig.EMERGENCY_112, stringResource(R.string.resident_call_112), style = CallButtonStyle.Secondary, fillWidth = true)
    }
}

/** "Sent at 10:42 · 3 min ago". Ticks on its own, so the rest of the tracker doesn't recompose. */
@Composable
private fun SentAtText(incident: Incident) {
    val sentAt = Format.clock(incident.createdAt)
    val text = if (incident.status == IncidentStatus.Resolved) {
        stringResource(R.string.resident_sent_at_short, sentAt) + " · " +
            stringResource(R.string.resident_sentence_resolved, ResidentRules.minutesToResolve(incident).toInt())
    } else {
        val now by rememberNow()
        stringResource(R.string.resident_sent_at, sentAt, Format.timeAgo(LocalContext.current, incident.createdAt, now))
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
}
