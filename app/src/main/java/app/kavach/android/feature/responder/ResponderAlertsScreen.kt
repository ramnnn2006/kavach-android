package app.kavach.android.feature.responder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.domain.Rules
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.nav.incidentSharedKey
import app.kavach.android.core.nav.sharedBoundsFor
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.SecondaryButton
import app.kavach.android.core.ui.components.rememberReduceMotion
import app.kavach.android.core.ui.format.rememberNow
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import java.time.Instant

/** Responder "Alerts" tab (Alerts.jsx): duty switch, my jobs, then the open queue. */
@Composable
fun ResponderAlertsScreen(navigator: KavachNavigator, viewModel: ResponderAlertsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val resolve by viewModel.resolve.collectAsStateWithLifecycle()
    // Read only inside each card's time line, so the 30 s tick doesn't recompose the whole screen
    val nowState = rememberNow()
    val now = { nowState.value }

    MessageEffect(viewModel.messages.flow)
    FeedbackEffect(viewModel.feedback)

    KavachScaffold(
        title = stringResource(R.string.nav_alerts),
        subtitle = specialtySummary(ui.profile),
    ) { padding ->
        Column(Modifier.fillBelowBar(padding)) {
            // The duty switch stays usable while the queue loads or failed to load (the website
            // shows it above the error); once loaded it is the first row of the list.
            if (ui.queue.data == null) {
                DutyCard(
                    onDuty = ui.onDuty,
                    busy = ui.dutyBusy,
                    onChange = viewModel::setOnDuty,
                    modifier = Modifier.padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.sm),
                )
            }
            LiveContent(
                state = ui.queue,
                onRetry = viewModel::retry,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { split ->
                AlertsList(
                    mine = split.mine,
                    open = split.open,
                    ui = ui,
                    now = now,
                    onOpen = { id -> navigator.navigate(Routes.IncidentDetail(id)) },
                    onDuty = viewModel::setOnDuty,
                    onClaim = viewModel::claim,
                    onNext = viewModel::next,
                    onSeeChecks = { navigator.openTab(Routes.ResponderChecks) },
                )
            }
        }
    }

    resolve.target?.let {
        NoteDialog(
            title = stringResource(R.string.responder_resolve_title),
            text = stringResource(R.string.responder_resolve_text),
            label = stringResource(R.string.responder_resolve_note_label),
            placeholder = stringResource(R.string.responder_resolve_note_placeholder),
            confirmLabel = stringResource(R.string.common_action_resolve),
            onConfirm = viewModel::confirmResolve,
            onDismiss = viewModel::dismissResolve,
            busy = resolve.busy,
            errorRes = resolve.error,
        )
    }
}

@Composable
private fun AlertsList(
    mine: List<Incident>,
    open: List<Incident>,
    ui: AlertsUi,
    now: () -> Instant,
    onOpen: (String) -> Unit,
    onDuty: (Boolean) -> Unit,
    onClaim: (Incident) -> Unit,
    onNext: (Incident) -> Unit,
    onSeeChecks: () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "duty", contentType = "duty") {
            DutyCard(onDuty = ui.onDuty, busy = ui.dutyBusy, onChange = onDuty)
        }

        if (mine.isNotEmpty()) {
            item(key = "mine-header", contentType = "header") {
                SectionHeader(
                    stringResource(R.string.responder_section_count, stringResource(R.string.responder_section_mine), mine.size),
                    modifier = itemMotion(reduceMotion),
                )
            }
            items(mine, key = { it.id }, contentType = { "job" }) { inc ->
                val motion = itemMotion(reduceMotion)
                MyJobCard(
                    inc = inc,
                    now = now,
                    busy = inc.id in ui.busy,
                    onOpen = { onOpen(inc.id) },
                    onNext = { onNext(inc) },
                    modifier = Modifier.sharedBoundsFor(incidentSharedKey(inc.id)).then(motion),
                )
            }
        }

        item(key = "open-header", contentType = "header") {
            SectionHeader(
                if (open.isEmpty()) {
                    stringResource(R.string.responder_section_open)
                } else {
                    stringResource(R.string.responder_section_count, stringResource(R.string.responder_section_open), open.size)
                },
                modifier = itemMotion(reduceMotion),
            )
        }
        if (open.isEmpty()) {
            item(key = "open-empty", contentType = "empty") {
                KavachCard(modifier = itemMotion(reduceMotion)) {
                    EmptyState(
                        icon = Icons.Rounded.Inbox,
                        title = stringResource(R.string.responder_empty_open_title),
                        text = stringResource(
                            if (ui.onDuty) R.string.responder_empty_open_text else R.string.responder_empty_open_off_text,
                        ),
                        action = {
                            if (ui.onDuty) SecondaryButton(stringResource(R.string.responder_see_checks), onClick = onSeeChecks)
                        },
                    )
                }
            }
        } else {
            items(open, key = { it.id }, contentType = { "open" }) { inc ->
                val motion = itemMotion(reduceMotion)
                OpenAlertCard(
                    inc = inc,
                    now = now,
                    busy = inc.id in ui.busy,
                    blockedReason = claimBlockedReason(ui.profile, inc),
                    onOpen = { onOpen(inc.id) },
                    onClaim = { onClaim(inc) },
                    modifier = Modifier.sharedBoundsFor(incidentSharedKey(inc.id)).then(motion),
                )
            }
        }
    }
}

/** Re-sort / move animation for a list item (none when the system removes animations). */
private fun LazyItemScope.itemMotion(reduceMotion: Boolean): Modifier =
    if (reduceMotion) Modifier else Modifier.animateItem()

/**
 * Why Claim is disabled: not one of my specialties. Null = allowed. Being off duty doesn't block a
 * claim (the website and claim_incident only check the specialty); off duty only stops new alerts.
 */
internal fun claimBlockedReason(profile: Profile?, inc: Incident): Int? =
    if (Rules.covers(profile, inc.type)) null else R.string.common_err_wrong_specialty

/** On-duty switch with its one-line explanation (also the off-duty banner). */
@Composable
private fun DutyCard(onDuty: Boolean, busy: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    KavachCard(modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = onDuty, enabled = !busy, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(stringResource(R.string.responder_on_duty), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (onDuty) R.string.responder_on_duty_detail else R.string.responder_off_duty_banner),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                )
            }
            Switch(checked = onDuty, onCheckedChange = null, enabled = !busy)
        }
    }
}
