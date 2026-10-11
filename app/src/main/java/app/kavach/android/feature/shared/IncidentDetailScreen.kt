package app.kavach.android.feature.shared

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentEvent
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.domain.Rules
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.incidentSharedKey
import app.kavach.android.core.nav.sharedBoundsFor
import app.kavach.android.core.ui.components.CallButton
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.ErrorBanner
import app.kavach.android.core.ui.components.ErrorState
import app.kavach.android.core.ui.components.EscalationBadge
import app.kavach.android.core.ui.components.EventTimeline
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.ListRow
import app.kavach.android.core.ui.components.LoadingState
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.SecondaryButton
import app.kavach.android.core.ui.components.StatusBadge
import app.kavach.android.core.ui.components.TypeIcon
import app.kavach.android.core.ui.components.UrgencyBadge
import app.kavach.android.core.ui.components.rememberHaptics
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.format.nextActionRes
import app.kavach.android.core.ui.format.rememberNow
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import java.time.Instant

private enum class DetailDialog { Resolve, Cancel, Assign }

/** Timeline items shown before "Show all" (Timeline.jsx VISIBLE). */
private const val TIMELINE_VISIBLE = 6

/**
 * Staff (responder / admin) view of one incident (IncidentDetail.jsx): live header, details, the
 * actions you may take, admin tools and the timeline. Residents use the Tracker instead.
 */
@Composable
fun IncidentDetailScreen(navigator: KavachNavigator) {
    val vm: IncidentDetailViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val firstResponders by vm.firstResponders.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val team by vm.teamUi.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var dialog by rememberSaveable { mutableStateOf<DetailDialog?>(null) }
    // Who is being assigned (spinner on that row)
    var assigningId by rememberSaveable { mutableStateOf<String?>(null) }
    // Kept here so a brief state change doesn't reset the scroll position
    val scroll = rememberScrollState()
    MessageEffect(vm.messages.flow)

    LaunchedEffect(vm) {
        vm.feedback.collect { ok -> if (ok) haptics.confirm() else haptics.reject() }
    }
    LaunchedEffect(vm) {
        vm.reports.collect { report ->
            val text = IncidentReportText.build(context, report)
            if (text == null) {
                vm.messages.send(R.string.common_error_generic)
            } else {
                shareText(context, text, context.getString(R.string.shared_detail_share_chooser))
            }
        }
    }

    val ready = ui as? DetailUi.Ready
    val incident = ready?.incident
    KavachScaffold(
        title = incident?.let { stringResource(it.type.labelRes()) } ?: stringResource(R.string.shared_detail_title),
        subtitle = incident?.let { Format.place(context, it) },
        onBack = { navigator.back() },
    ) { padding ->
        when (val state = ui) {
            DetailUi.Loading -> LoadingState(Modifier.padding(padding))
            DetailUi.NotFound -> Column(Modifier.padding(padding)) {
                EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = stringResource(R.string.shared_detail_not_found_title),
                    text = stringResource(R.string.shared_detail_not_found_text),
                    action = {
                        SecondaryButton(text = stringResource(R.string.shared_detail_back), onClick = { navigator.back() })
                    },
                )
            }
            is DetailUi.Failed -> ErrorState(state.error, onRetry = vm::retry, modifier = Modifier.padding(padding))
            is DetailUi.Ready -> {
                val inc = state.incident
                val isAdmin = profile?.role == UserRole.Admin
                // Drop a dialog whose action no longer applies (closed, reassigned elsewhere)
                val openDialog = dialog
                val dialogAllowed = when (openDialog) {
                    DetailDialog.Resolve -> DetailRules.canAdvance(inc, profile?.id, isAdmin)
                    DetailDialog.Cancel, DetailDialog.Assign -> isAdmin && inc.isActive
                    null -> true
                }
                if (!dialogAllowed) {
                    LaunchedEffect(openDialog) { dialog = null }
                }
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = Spacing.screen)
                        .padding(bottom = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    state.refreshError?.let { ErrorBanner(it, onRetry = vm::retry) }

                    HeaderCard(inc)

                    ActionButtons(
                        incident = inc,
                        profile = profile,
                        busy = busy,
                        onClaim = vm::claim,
                        onAdvance = { next ->
                            if (next == IncidentStatus.Resolved) {
                                dialog = DetailDialog.Resolve
                            } else {
                                vm.advance(next)
                            }
                        },
                        onResolveEarly = { dialog = DetailDialog.Resolve },
                    )

                    DetailsCard(
                        incident = inc,
                        isMine = inc.assignedTo != null && inc.assignedTo == profile?.id,
                        firstResponders = firstResponders,
                    )

                    if (isAdmin) {
                        AdminCard(
                            incident = inc,
                            enabled = busy == null,
                            sharing = busy == DetailAction.Share,
                            onAssign = {
                                vm.loadTeam()
                                dialog = DetailDialog.Assign
                            },
                            onShare = vm::shareReport,
                            onCancel = { dialog = DetailDialog.Cancel },
                        )
                    }

                    SectionHeader(stringResource(R.string.shared_detail_timeline))
                    KavachCard { Timeline(events.orEmpty()) }
                }

                when (if (dialogAllowed) openDialog else null) {
                    DetailDialog.Resolve -> NoteDialog(
                        title = stringResource(R.string.shared_detail_resolve_title),
                        text = stringResource(R.string.shared_detail_resolve_text),
                        label = stringResource(R.string.shared_detail_resolve_note_label),
                        placeholder = stringResource(R.string.shared_detail_resolve_note_placeholder),
                        confirmLabel = stringResource(R.string.common_action_resolve),
                        busy = busy == DetailAction.Resolve,
                        onConfirm = { note -> vm.resolve(note) { dialog = null } },
                        onDismiss = { dialog = null },
                    )
                    DetailDialog.Cancel -> NoteDialog(
                        title = stringResource(R.string.shared_detail_cancel_title),
                        text = stringResource(R.string.shared_detail_cancel_text),
                        label = stringResource(R.string.shared_detail_cancel_reason_label),
                        placeholder = stringResource(R.string.shared_detail_cancel_reason_placeholder),
                        confirmLabel = stringResource(R.string.shared_detail_cancel_incident),
                        dismissLabel = stringResource(R.string.shared_detail_keep_it),
                        destructive = true,
                        busy = busy == DetailAction.Cancel,
                        onConfirm = { reason -> vm.cancel(reason) { dialog = null } },
                        onDismiss = { dialog = null },
                    )
                    DetailDialog.Assign -> AssignDialog(
                        incident = inc,
                        team = team,
                        busy = busy == DetailAction.Assign,
                        pendingId = assigningId,
                        onPick = { responder ->
                            if (responder.id == inc.assignedTo) {
                                dialog = null
                            } else if (busy == null) {
                                assigningId = responder.id
                                vm.assign(responder) { dialog = null }
                            }
                        },
                        onRetry = vm::loadTeam,
                        onDismiss = { dialog = null },
                    )
                    null -> Unit
                }
            }
        }
    }
}

/** Type, status, urgency, place and age. Shares bounds with the list card it was opened from. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderCard(incident: Incident) {
    val context = LocalContext.current
    val resolvedAt = incident.resolvedAt
    KavachCard(modifier = Modifier.sharedBoundsFor(incidentSharedKey(incident.id))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TypeIcon(incident.type, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(incident.type.labelRes()), style = MaterialTheme.typography.titleLarge)
                Text(
                    Format.place(context, incident),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            StatusBadge(incident.status, staff = true)
            UrgencyBadge(incident.urgencyScore)
            if (incident.isEscalated) EscalationBadge(incident.escalationLevel)
        }
        if (incident.status == IncidentStatus.Resolved && resolvedAt != null) {
            Spacer(Modifier.height(Spacing.sm))
            AgeText(
                stringResource(
                    R.string.shared_detail_took,
                    Format.duration(context, Rules.minutesBetween(incident.createdAt, resolvedAt)),
                ),
            )
        } else if (incident.isActive) {
            Spacer(Modifier.height(Spacing.sm))
            OpenForText(incident.createdAt)
        }
    }
}

@Composable
private fun AgeText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
}

/** "12 min since it was reported". Only this line follows the clock. */
@Composable
private fun OpenForText(createdAt: Instant) {
    val context = LocalContext.current
    val now by rememberNow()
    AgeText(stringResource(R.string.shared_detail_open_for, Format.duration(context, Rules.minutesBetween(createdAt, now))))
}

/** Audit log; older items collapse behind "Show all" (Timeline.jsx). */
@Composable
private fun Timeline(events: List<IncidentEvent>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hidden = events.size - TIMELINE_VISIBLE
    if (events.isEmpty()) {
        Text(
            stringResource(R.string.shared_detail_no_events),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTheme.colors.textMuted,
        )
        return
    }
    EventTimeline(if (expanded || hidden <= 0) events else events.takeLast(TIMELINE_VISIBLE), staff = true)
    if (hidden > 0) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (expanded) {
                    stringResource(R.string.shared_d_show_less)
                } else {
                    stringResource(R.string.shared_d_show_all, events.size)
                },
            )
        }
    }
}

/** Claim (open alerts) and the next step (your own alerts, or any for admins). */
@Composable
private fun ActionButtons(
    incident: Incident,
    profile: Profile?,
    busy: DetailAction?,
    onClaim: () -> Unit,
    onAdvance: (IncidentStatus) -> Unit,
    onResolveEarly: () -> Unit,
) {
    val isAdmin = profile?.role == UserRole.Admin
    val next = Rules.nextStatus(incident.status)
    val canAdvance = next != null && DetailRules.canAdvance(incident, profile?.id, isAdmin)
    val claimBlock = if (incident.status == IncidentStatus.Pending) DetailRules.claimBlock(profile, incident.type) else null
    val showClaim = incident.status == IncidentStatus.Pending
    if (!showClaim && !canAdvance) return

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (showClaim) {
            ActionButton(
                text = stringResource(R.string.common_action_claim),
                onClick = onClaim,
                busy = busy == DetailAction.Claim,
                enabled = claimBlock == null && busy == null,
            )
            if (claimBlock != null) {
                Text(
                    stringResource(R.string.common_err_wrong_specialty),
                    style = MaterialTheme.typography.bodySmall,
                    color = KavachTheme.colors.textMuted,
                )
            }
        }
        if (canAdvance && next != null) {
            val label = incident.status.nextActionRes()
            ActionButton(
                text = stringResource(label ?: R.string.common_action_resolve),
                onClick = { onAdvance(next) },
                busy = busy == DetailAction.Advance,
                enabled = busy == null,
                tone = if (next == IncidentStatus.Resolved) KavachTheme.colors.success else null,
            )
            if (next != IncidentStatus.Resolved) {
                TextButton(
                    onClick = onResolveEarly,
                    enabled = busy == null,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text(stringResource(R.string.common_action_resolve)) }
            }
        }
    }
}

@Composable
private fun DetailsCard(incident: Incident, isMine: Boolean, firstResponders: Int) {
    val context = LocalContext.current
    val needs = Format.vulnerabilityFull(context, incident.reporterVulnerability)
        .ifBlank { if (incident.vulnerable) stringResource(R.string.shared_detail_needs_extra_help) else "" }
    val someone = stringResource(R.string.shared_detail_someone)
    val reporter = incident.reporterName?.takeIf { it.isNotBlank() } ?: someone
    val reporterPhone = incident.reporterPhone?.takeIf { it.isNotBlank() }
    val assigneePhone = incident.assignedPhone?.takeIf { it.isNotBlank() }
    val assigneeName = incident.assignedName?.takeIf { it.isNotBlank() }

    SectionHeader(stringResource(R.string.shared_detail_section_details))
    KavachCard(contentPadding = PaddingValues(horizontal = Spacing.md)) {
        InfoRow(
            label = stringResource(R.string.shared_detail_reported_by),
            showDivider = false,
            action = if (reporterPhone != null) ({ StaffCallButton(reporterPhone, reporter) }) else null,
        ) { Text(reporter, style = MaterialTheme.typography.bodyLarge) }

        if (needs.isNotBlank()) {
            InfoRow(stringResource(R.string.shared_detail_needs)) { Text(needs, style = MaterialTheme.typography.bodyLarge) }
        }
        InfoRow(stringResource(R.string.shared_detail_people_affected)) {
            Text(Format.people(context, incident.peopleAffected), style = MaterialTheme.typography.bodyLarge)
        }
        incident.description?.takeIf { it.isNotBlank() }?.let { text ->
            InfoRow(stringResource(R.string.shared_detail_description)) { Text(text, style = MaterialTheme.typography.bodyLarge) }
        }
        incident.locationNote?.takeIf { it.isNotBlank() }?.let { text ->
            InfoRow(stringResource(R.string.shared_detail_location_note)) { Text(text, style = MaterialTheme.typography.bodyLarge) }
        }
        InfoRow(stringResource(R.string.shared_detail_urgency)) {
            Text(
                stringResource(R.string.shared_detail_urgency_value, incident.urgencyScore),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (incident.isEscalated) {
                Text(
                    stringResource(R.string.common_escalated_level, incident.escalationLevel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.danger,
                )
            }
        }
        InfoRow(stringResource(R.string.shared_detail_reported_at)) {
            val now by rememberNow()
            Text(
                stringResource(
                    R.string.shared_detail_reported_at_value,
                    Format.dateTime(incident.createdAt),
                    Format.timeAgo(context, incident.createdAt, now),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        InfoRow(
            label = stringResource(R.string.shared_detail_assigned_to),
            action = if (!isMine && assigneePhone != null) ({ StaffCallButton(assigneePhone, assigneeName ?: someone) }) else null,
        ) {
            if (assigneeName != null) {
                Text(assigneeName, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(
                    stringResource(R.string.shared_detail_unassigned),
                    style = MaterialTheme.typography.bodyLarge,
                    color = KavachTheme.colors.textMuted,
                )
            }
        }
        incident.resolutionNote?.takeIf { it.isNotBlank() }?.let { text ->
            InfoRow(stringResource(R.string.shared_detail_resolution_note)) { Text(text, style = MaterialTheme.typography.bodyLarge) }
        }
        if (incident.type == IncidentType.Medical || firstResponders > 0) {
            InfoRow(stringResource(R.string.shared_detail_first_responders)) {
                if (firstResponders > 0) {
                    Text(firstResponders.toString(), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(
                        stringResource(R.string.shared_detail_first_responders_none),
                        style = MaterialTheme.typography.bodyLarge,
                        color = KavachTheme.colors.textMuted,
                    )
                }
            }
        }
    }
}

/** Label above a value, with an optional action (a call button) on the right and a divider above. */
@Composable
private fun InfoRow(
    label: String,
    action: (@Composable () -> Unit)? = null,
    showDivider: Boolean = true,
    value: @Composable () -> Unit,
) {
    Column {
        if (showDivider) HorizontalDivider(color = KavachTheme.colors.separator)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = KavachTheme.colors.textMuted)
                value()
            }
            action?.invoke()
        }
    }
}

/** "Call" with the person's name as the spoken label. */
@Composable
private fun StaffCallButton(phone: String, name: String) {
    val description = stringResource(R.string.common_call_number, name)
    CallButton(
        phone = phone,
        label = stringResource(R.string.common_call),
        modifier = Modifier.semantics { contentDescription = description },
    )
}

/** Admin tools: assign / reassign, share the report, cancel. */
@Composable
private fun AdminCard(
    incident: Incident,
    enabled: Boolean,
    sharing: Boolean,
    onAssign: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
) {
    SectionHeader(stringResource(R.string.shared_detail_section_admin))
    KavachCard(contentPadding = PaddingValues(0.dp)) {
        if (incident.isActive) {
            ListRow(
                title = stringResource(
                    if (incident.assignedTo != null) R.string.shared_detail_reassign else R.string.shared_detail_assign,
                ),
                leading = { IconTile(Icons.Rounded.PersonAdd, KavachTheme.colors.blue, size = 36.dp) },
                trailing = { Chevron() },
                onClick = { if (enabled) onAssign() },
            )
            HorizontalDivider(color = KavachTheme.colors.separator)
        }
        ListRow(
            title = stringResource(R.string.shared_detail_share_report),
            leading = { IconTile(Icons.Rounded.Share, KavachTheme.colors.gray, size = 36.dp) },
            trailing = {
                if (sharing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Chevron()
            },
            onClick = { if (enabled) onShare() },
        )
        if (incident.isActive) {
            HorizontalDivider(color = KavachTheme.colors.separator)
            ListRow(
                title = stringResource(R.string.shared_detail_cancel_incident),
                titleColor = KavachTheme.colors.danger,
                leading = { IconTile(Icons.Rounded.Cancel, KavachTheme.colors.red, size = 36.dp) },
                onClick = { if (enabled) onCancel() },
            )
        }
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = null,
        tint = KavachTheme.colors.textTertiary,
    )
}

/** Opens the Android share sheet with plain text. */
private fun shareText(context: Context, text: String, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
