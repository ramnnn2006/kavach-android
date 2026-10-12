package app.kavach.android.feature.responder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.domain.Rules
import app.kavach.android.core.ui.components.CallButton
import app.kavach.android.core.ui.components.EscalationBadge
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.ToneBadge
import app.kavach.android.core.ui.components.UrgencyBadge
import app.kavach.android.core.ui.components.icon
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.format.nextActionRes
import app.kavach.android.core.ui.format.timeAgo
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import java.time.Instant

/**
 * Tappable summary (type, place, status / people / age, flags) that opens the detail screen,
 * with [footer] (actions) underneath inside the same card.
 */
@Composable
private fun IncidentCardFrame(
    inc: Incident,
    now: () -> Instant,
    showStatus: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier,
    hasFooter: Boolean,
    footer: @Composable ColumnScope.() -> Unit,
) {
    KavachCard(
        modifier = modifier,
        accent = if (inc.isEscalated) KavachTheme.colors.red else null,
        contentPadding = PaddingValues(0.dp),
    ) {
        IncidentSummary(
            inc = inc,
            now = now,
            showStatus = showStatus,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.responder_open_details), onClick = onOpen)
                .padding(Spacing.md),
        )
        if (hasFooter) {
            Column(
                Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                content = footer,
            )
        }
    }
}

/** [now] is read here only (the waiting time), so the clock tick recomposes just this summary. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IncidentSummary(inc: Incident, now: () -> Instant, showStatus: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val meta = listOfNotNull(
        if (showStatus) stringResource(inc.status.labelRes(true)) else null,
        Format.people(context, inc.peopleAffected),
        timeAgo(inc.createdAt, now()),
    ).joinToString(" · ")
    val vulnerability = if (inc.vulnerable) {
        Format.vulnerabilityShort(context, inc.reporterVulnerability)
            .ifBlank { stringResource(R.string.responder_vulnerable_badge) }
    } else {
        null
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        IconTile(inc.type.icon(), KavachTheme.colors.type(inc.type))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(inc.type.labelRes()), style = MaterialTheme.typography.titleMedium)
            Text(Format.place(context, inc), style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
            FlowRow(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                UrgencyBadge(inc.urgencyScore)
                if (inc.isEscalated) EscalationBadge(inc.escalationLevel)
                if (vulnerability != null) ToneBadge(vulnerability, KavachTheme.colors.purple, Modifier.widthIn(max = 260.dp))
            }
            inc.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = KavachTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A job assigned to me: summary on top, the next step and a call button underneath (MyJob). */
@Composable
internal fun MyJobCard(
    inc: Incident,
    now: () -> Instant,
    busy: Boolean,
    onOpen: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nextLabel = inc.status.nextActionRes()
    val resolves = Rules.nextStatus(inc.status) == IncidentStatus.Resolved
    val phone = inc.reporterPhone?.takeIf { it.isNotBlank() }
    // "Call Ravi", or "Call reporter" when the name is unknown (not "Call Call reporter")
    val callName = inc.reporterName?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.responder_call_name, it) }
        ?: stringResource(R.string.responder_call_reporter)
    IncidentCardFrame(
        inc = inc,
        now = now,
        showStatus = true,
        onOpen = onOpen,
        modifier = modifier,
        hasFooter = nextLabel != null || phone != null,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                if (nextLabel != null) {
                    ActionButton(
                        text = stringResource(nextLabel),
                        onClick = onNext,
                        modifier = Modifier.weight(1f),
                        busy = busy,
                        style = if (resolves) ActionStyle.Success else ActionStyle.Primary,
                    )
                }
                if (phone != null) {
                    CallButton(
                        phone = phone,
                        label = stringResource(R.string.common_call),
                        modifier = Modifier.semantics { contentDescription = callName },
                    )
                }
            }
        },
    )
}

/**
 * An open alert: tap to read, Claim underneath. [blockedReason] (a string resource) disables Claim
 * and is shown as the reason (off duty, or not in my specialties).
 */
@Composable
internal fun OpenAlertCard(
    inc: Incident,
    now: () -> Instant,
    busy: Boolean,
    blockedReason: Int?,
    onOpen: () -> Unit,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val claimCd = stringResource(R.string.responder_claim_cd, stringResource(inc.type.labelRes()))
    IncidentCardFrame(
        inc = inc,
        now = now,
        showStatus = false,
        onOpen = onOpen,
        modifier = modifier,
        hasFooter = true,
        footer = {
            if (blockedReason != null) {
                Text(stringResource(blockedReason), style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
            }
            ActionButton(
                text = stringResource(R.string.common_action_claim),
                onClick = onClaim,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = claimCd },
                busy = busy,
                enabled = blockedReason == null,
                style = ActionStyle.Tonal,
            )
        },
    )
}
