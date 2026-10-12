package app.kavach.android.feature.responder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.nav.incidentSharedKey
import app.kavach.android.core.nav.sharedBoundsFor
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.ListRow
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.SecondaryButton
import app.kavach.android.core.ui.components.icon
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.format.rememberNow
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/** Responder "History" tab (History.jsx): jobs I handled, with a few totals. */
@Composable
fun ResponderHistoryScreen(navigator: KavachNavigator, viewModel: ResponderHistoryViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val now by rememberNow(60_000)

    KavachScaffold(
        title = stringResource(R.string.nav_history),
        subtitle = specialtySummary(profile),
    ) { padding ->
        LiveContent(
            state = rows,
            onRetry = viewModel::retry,
            modifier = Modifier.fillBelowBar(padding),
        ) { list ->
            HistoryList(
                list = list,
                stats = remember(list, now) { historyStats(list, now) },
                showGoToAlerts = profile?.role == UserRole.Responder,
                onOpen = { id -> navigator.navigate(Routes.IncidentDetail(id)) },
                onGoToAlerts = { navigator.openTab(Routes.ResponderAlerts) },
            )
        }
    }
}

@Composable
private fun HistoryList(
    list: List<Incident>,
    stats: HistoryStats,
    showGoToAlerts: Boolean,
    onOpen: (String) -> Unit,
    onGoToAlerts: () -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (list.isEmpty()) {
            item(key = "empty") {
                KavachCard {
                    EmptyState(
                        icon = Icons.Rounded.History,
                        title = stringResource(R.string.responder_history_empty_title),
                        text = stringResource(R.string.responder_history_empty_text),
                        action = {
                            if (showGoToAlerts) SecondaryButton(stringResource(R.string.responder_go_to_alerts), onClick = onGoToAlerts)
                        },
                    )
                }
            }
        } else {
            item(key = "stats") {
                KavachCard(contentPadding = PaddingValues(0.dp)) {
                    StatRow(stringResource(R.string.responder_stat_resolved_week), stats.resolvedThisWeek.toString())
                    HorizontalDivider(color = KavachTheme.colors.separator)
                    StatRow(stringResource(R.string.responder_stat_avg_resolve), Format.duration(context, stats.avgResolveMinutes))
                    HorizontalDivider(color = KavachTheme.colors.separator)
                    StatRow(stringResource(R.string.responder_stat_total), stats.total.toString())
                }
            }
            item(key = "jobs-header") { SectionHeader(stringResource(R.string.responder_history_jobs)) }
            items(list, key = { it.id }) { inc ->
                HistoryCard(inc, onClick = { onOpen(inc.id) })
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    ListRow(
        title = label,
        trailing = { Text(value, style = MaterialTheme.typography.titleMedium) },
    )
}

@Composable
private fun HistoryCard(inc: Incident, onClick: () -> Unit) {
    val context = LocalContext.current
    val at = inc.resolvedAt ?: inc.cancelledAt ?: inc.createdAt
    val outcome = if (inc.status == IncidentStatus.Resolved) {
        stringResource(R.string.responder_resolved_in, Format.duration(context, resolveMinutes(inc)))
    } else {
        stringResource(inc.status.labelRes(true))
    }
    KavachCard(modifier = Modifier.sharedBoundsFor(incidentSharedKey(inc.id)), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(inc.type.icon(), KavachTheme.colors.type(inc.type))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(inc.type.labelRes()), style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(Format.place(context, inc), Format.dateTime(at)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                )
                Text(outcome, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = KavachTheme.colors.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
