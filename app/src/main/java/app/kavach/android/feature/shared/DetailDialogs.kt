package app.kavach.android.feature.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.TeamMember
import app.kavach.android.core.ui.components.ErrorState
import app.kavach.android.core.ui.format.shortLabelRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/**
 * Confirm with an optional note (NoteDialog.jsx): used to resolve and to cancel. The dialog
 * stays open while [busy]; the caller closes it from [onConfirm]'s success.
 */
@Composable
internal fun NoteDialog(
    title: String,
    text: String,
    label: String,
    placeholder: String,
    confirmLabel: String,
    busy: Boolean,
    onConfirm: (note: String) -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.common_cancel),
    destructive: Boolean = false,
) {
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(NOTE_MAX) },
                    label = { Text(label) },
                    placeholder = { Text(placeholder) },
                    minLines = 3,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(note.trim()) },
                enabled = !busy,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = KavachTheme.colors.danger)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(dismissLabel) } },
    )
}

private const val NOTE_MAX = 500

/**
 * Admin: pick a responder (AssignDialog.jsx). On-duty responders who handle the type come first.
 * [pendingId] is the responder being assigned while [busy].
 */
@Composable
internal fun AssignDialog(
    incident: Incident,
    team: TeamUi,
    busy: Boolean,
    onPick: (TeamMember) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    pendingId: String? = null,
) {
    val context = LocalContext.current
    val typeName = context.getString(incident.type.shortLabelRes())
    // Reopened after the process was restored: nothing has loaded the team yet
    if (team is TeamUi.Idle) {
        LaunchedEffect(Unit) { onRetry() }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.shared_detail_assign_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    stringResource(R.string.shared_detail_assign_text, typeName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (team) {
                    TeamUi.Idle, TeamUi.Loading -> Row(
                        Modifier.fillMaxWidth().padding(vertical = Spacing.md),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                    is TeamUi.Failed -> ErrorState(team.error, onRetry = onRetry)
                    is TeamUi.Ready -> {
                        val sorted = remember(team, incident.type) { DetailRules.sortForAssign(team.responders, incident.type) }
                        if (sorted.isEmpty()) {
                            Text(
                                stringResource(R.string.shared_detail_no_responders),
                                style = MaterialTheme.typography.bodyMedium,
                                color = KavachTheme.colors.textMuted,
                            )
                        } else {
                            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                                sorted.forEachIndexed { index, responder ->
                                    if (index > 0) HorizontalDivider(color = KavachTheme.colors.separator)
                                    ResponderRow(
                                        responder = responder,
                                        type = typeName,
                                        handles = incident.type in responder.specialties,
                                        current = responder.id == incident.assignedTo,
                                        enabled = !busy,
                                        pending = busy && responder.id == pendingId,
                                        onClick = { onPick(responder) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ResponderRow(
    responder: TeamMember,
    type: String,
    handles: Boolean,
    current: Boolean,
    enabled: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
) {
    val duty = stringResource(if (responder.onDuty) R.string.shared_detail_on_duty else R.string.shared_detail_off_duty)
    val specialty = if (handles) {
        stringResource(R.string.shared_detail_handles_type, type)
    } else {
        stringResource(R.string.shared_detail_not_their_specialty)
    }
    val status = if (responder.activeLoad > 0) {
        stringResource(
            R.string.shared_d_team_status_load,
            duty,
            specialty,
            stringResource(R.string.shared_d_active_load, responder.activeLoad),
        )
    } else {
        stringResource(R.string.shared_detail_team_status, duty, specialty)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(responder.fullName, style = MaterialTheme.typography.bodyLarge)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (responder.onDuty) KavachTheme.colors.success else KavachTheme.colors.textMuted,
            )
        }
        if (pending) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (current) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = stringResource(R.string.shared_detail_assigned_now),
                tint = KavachTheme.colors.primary,
            )
        }
    }
}
