package app.kavach.android.feature.admin

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.TeamMember
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.repo.TeamRepository
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.ErrorState
import app.kavach.android.core.ui.components.LoadingState
import app.kavach.android.core.ui.components.MessageQueue
import app.kavach.android.core.ui.components.rememberHaptics
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.format.placeLine
import app.kavach.android.core.ui.format.shortLabelRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

// Assign / reassign an incident to a responder (parts/AssignSheet.jsx). Used by Overview and
// Incidents; each host screen collects `vm.messages.flow` with MessageEffect.

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AssignViewModel @Inject constructor(
    private val teamRepo: TeamRepository,
    private val incidentRepo: IncidentRepository,
) : ViewModel() {
    val messages = MessageQueue()

    private val retryTick = MutableStateFlow(0)
    fun retry() { retryTick.value = retryTick.value + 1 }

    val team: StateFlow<Loadable<List<TeamMember>>> =
        retryTick.flatMapLatest { teamRepo.listenTeam() }.inStateKeepingData(viewModelScope)

    private val _busyId = MutableStateFlow<String?>(null)
    val busyId: StateFlow<String?> = _busyId

    /** The last failure, shown inside that incident's sheet only (never in a sheet opened later for another one). */
    data class Failure(val incidentId: String, @StringRes val res: Int)

    private val _failure = MutableStateFlow<Failure?>(null)
    val failure: StateFlow<Failure?> = _failure

    fun clearError() { _failure.value = null }

    fun assign(incidentId: String, responder: TeamMember, onDone: () -> Unit) {
        if (_busyId.value != null) return
        _busyId.value = responder.id
        _failure.value = null
        viewModelScope.launch {
            try {
                incidentRepo.assign(incidentId, responder.id)
                messages.send(R.string.admin_assigned_to, responder.fullName)
                onDone()
            } catch (e: KavachException) {
                _failure.value = Failure(incidentId, e.messageRes())
                messages.error(e)
            } finally {
                _busyId.value = null
            }
        }
    }
}

@Composable
internal fun AssignSheet(
    incident: Incident,
    vm: AssignViewModel,
    onDismiss: () -> Unit,
    onOpenTeam: () -> Unit,
) {
    val team by vm.team.collectAsStateWithLifecycle()
    val busyId by vm.busyId.collectAsStateWithLifecycle()
    val failure by vm.failure.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val teamData = team.data
    val teamError = team.error
    val candidates = remember(teamData, incident.type) { assignCandidates(teamData.orEmpty(), incident.type) }
    val close = {
        vm.clearError()
        onDismiss()
    }

    AdminSheet(
        title = stringResource(R.string.admin_assign_title),
        subtitle = stringResource(incident.type.labelRes()) + " · " + placeLine(incident),
        onDismiss = close,
        busy = busyId != null,
    ) {
        failure?.takeIf { it.incidentId == incident.id }?.let { f ->
            Text(stringResource(f.res), color = KavachTheme.colors.danger, style = MaterialTheme.typography.bodyMedium)
        }
        when {
            teamData == null && teamError != null -> ErrorState(teamError, vm::retry)
            teamData == null -> LoadingState()
            candidates.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Groups,
                title = stringResource(R.string.admin_no_responders),
                text = stringResource(R.string.admin_no_responders_text),
                action = {
                    TextButton(onClick = {
                        close()
                        onOpenTeam()
                    }) { Text(stringResource(R.string.admin_open_team)) }
                },
            )
            else -> Column(Modifier.fillMaxWidth()) {
                candidates.forEachIndexed { index, member ->
                    if (index > 0) HorizontalDivider()
                    ResponderRow(
                        member = member,
                        incident = incident,
                        current = incident.assignedTo == member.id,
                        busy = busyId == member.id,
                        enabled = busyId == null && incident.assignedTo != member.id,
                        onClick = {
                            vm.assign(incident.id, member) {
                                haptics.confirm()
                                onDismiss()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponderRow(
    member: TeamMember,
    incident: Incident,
    current: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val handles = incident.type in member.specialties
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = stringResource(R.string.admin_assign_to_name, member.fullName),
                onClick = onClick,
            )
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InitialsAvatar(member.fullName)
        Column(Modifier.weight(1f)) {
            Text(member.fullName, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DutyDot(member.onDuty)
                Text(
                    stringResource(R.string.admin_active_load, member.activeLoad),
                    style = MaterialTheme.typography.bodySmall,
                    color = KavachTheme.colors.textMuted,
                )
            }
            val specs = specialtiesText(member.specialties)
            val line = if (handles) {
                stringResource(R.string.admin_handles_type, stringResource(incident.type.shortLabelRes())) + " · " + specs
            } else {
                specs
            }
            if (line.isNotBlank()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (handles) KavachTheme.colors.success else KavachTheme.colors.textMuted,
                )
            }
        }
        when {
            busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            current -> Icon(
                Icons.Rounded.Check,
                contentDescription = stringResource(R.string.admin_assigned),
                tint = KavachTheme.colors.success,
            )
            else -> Unit
        }
    }
}
