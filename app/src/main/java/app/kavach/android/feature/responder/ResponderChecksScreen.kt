package app.kavach.android.feature.responder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.data.model.ComplianceItem
import app.kavach.android.core.data.model.ComplianceStatus
import app.kavach.android.core.domain.Rules
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.icon
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/** Responder "Checks" tab (Checks.jsx): scheduled equipment checks I cover, grouped by urgency. */
@Composable
fun ResponderChecksScreen(navigator: KavachNavigator, viewModel: ResponderChecksViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val markDone by viewModel.markDone.collectAsStateWithLifecycle()

    MessageEffect(viewModel.messages.flow)
    FeedbackEffect(viewModel.feedback)

    KavachScaffold(
        title = stringResource(R.string.nav_checks),
        subtitle = specialtySummary(ui.profile),
    ) { padding ->
        LiveContent(
            state = ui.checks,
            onRetry = viewModel::retry,
            modifier = Modifier.fillBelowBar(padding),
        ) { data ->
            ChecksList(data = data, onMarkDone = viewModel::openMarkDone)
        }
    }

    markDone.target?.let { item ->
        NoteDialog(
            title = stringResource(R.string.responder_mark_done_title),
            text = stringResource(R.string.responder_mark_done_text, item.title),
            label = stringResource(R.string.responder_check_note_label),
            placeholder = stringResource(R.string.responder_check_note_placeholder),
            confirmLabel = stringResource(R.string.responder_mark_done),
            onConfirm = viewModel::confirmMarkDone,
            onDismiss = viewModel::dismissMarkDone,
            busy = markDone.busy,
            errorRes = markDone.error,
        )
    }
}

@Composable
private fun ChecksList(data: ChecksData, onMarkDone: (ComplianceItem) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (data.total == 0) {
            item(key = "empty") {
                KavachCard {
                    EmptyState(
                        icon = Icons.Rounded.Checklist,
                        title = stringResource(R.string.responder_checks_empty_title),
                        text = stringResource(R.string.responder_checks_empty_text),
                    )
                }
            }
        } else if (data.attention == 0) {
            item(key = "all-ok") {
                InfoBanner(Icons.Rounded.CheckCircle, KavachTheme.colors.green, stringResource(R.string.responder_all_checks_ok))
            }
        }

        for (section in data.sections) {
            if (section.items.isEmpty()) continue
            item(key = "header-${section.status.wire}") {
                SectionHeader(
                    stringResource(
                        R.string.responder_section_count,
                        stringResource(section.status.labelRes()),
                        section.items.size,
                    ),
                )
            }
            item(key = "section-${section.status.wire}") {
                KavachCard(contentPadding = PaddingValues(0.dp)) {
                    section.items.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = KavachTheme.colors.separator)
                        CheckRow(item, section.status, onMarkDone = { onMarkDone(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(item: ComplianceItem, status: ComplianceStatus, onMarkDone: () -> Unit) {
    val specialty = Rules.checkSpecialty(item)
    val where = listOfNotNull(item.assetName, item.zoneName).filter { it.isNotBlank() }.joinToString(" · ")
    // Overdue and due-soon rows are flagged in colour; the section header carries the same words
    val dueTone = if (status == ComplianceStatus.Ok) KavachTheme.colors.textMuted else KavachTheme.colors.compliance(status)
    val lastDone = item.lastDoneAt?.let { stringResource(R.string.responder_last_done, Format.shortDate(it)) }
        ?: stringResource(R.string.responder_never_done)
    val markDoneCd = stringResource(R.string.responder_mark_done_cd, item.title)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val kindTone = specialty?.let { KavachTheme.colors.type(it) } ?: KavachTheme.colors.gray
        IconTile(specialty?.icon() ?: Icons.Rounded.Checklist, kindTone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            if (where.isNotEmpty()) {
                Text(where, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
            }
            Text(dueLine(item), style = MaterialTheme.typography.bodyMedium, color = dueTone)
            Text(lastDone, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
        }
        TextButton(onClick = onMarkDone, modifier = Modifier.semantics { contentDescription = markDoneCd }) {
            Text(stringResource(R.string.responder_mark_done))
        }
    }
}

/** "Due 8 Oct · 3 days overdue" (dueText on the website). */
@Composable
private fun dueLine(item: ComplianceItem): String {
    val date = stringResource(R.string.responder_due_on, Format.shortDate(item.dueAt))
    val distance = when (val due = dueText(Rules.daysUntil(item.dueAt))) {
        is DueText.Overdue -> pluralStringResource(R.plurals.responder_days_overdue, due.days, due.days)
        DueText.Today -> stringResource(R.string.responder_due_today)
        DueText.Tomorrow -> stringResource(R.string.responder_due_tomorrow)
        is DueText.InDays -> stringResource(R.string.responder_due_in_days, due.days)
    }
    return stringResource(R.string.responder_due_line, date, distance)
}
