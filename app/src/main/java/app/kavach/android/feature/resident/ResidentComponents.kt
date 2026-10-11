package app.kavach.android.feature.resident

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.MedicalAlert
import app.kavach.android.core.data.model.PowerSource
import app.kavach.android.core.data.model.PowerState
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.SafetyCheckScope
import app.kavach.android.core.data.model.SafetyResponseStatus
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.outbox.OutboxSnapshot
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.domain.Rules
import app.kavach.android.core.ui.components.CallButton
import app.kavach.android.core.ui.components.CallButtonStyle
import app.kavach.android.core.ui.components.ErrorBanner
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.PulsingDot
import app.kavach.android.core.ui.components.SecondaryButton
import app.kavach.android.core.ui.components.StatusBadge
import app.kavach.android.core.ui.components.TypeIcon
import app.kavach.android.core.ui.components.dial
import app.kavach.android.core.ui.components.rememberReduceMotion
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import java.time.Instant

// Small building blocks shared by the resident screens (website: screens/resident/parts/*).

/** Smooth re-sorting of a lazy list item; nothing when the system asks for reduced motion. */
@Composable
fun LazyItemScope.reorderModifier(): Modifier =
    if (rememberReduceMotion()) Modifier else Modifier.animateItem()

/** Tinted notice (AlertBanner on the website): icon + text, announced politely to screen readers. */
@Composable
fun NoticeBanner(
    tone: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(tone.copy(alpha = 0.12f))
            .padding(Spacing.md)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs), content = content)
    }
}

/** A list row that opens the dialler: title, detail, number, trailing phone glyph (CallRow). */
@Composable
fun CallRow(
    phone: String?,
    title: String,
    callLabel: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    number: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    if (phone.isNullOrBlank()) return
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = callLabel, role = Role.Button) { context.dial(phone) }
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
            }
            if (!number.isNullOrBlank()) {
                Text(number, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
            }
        }
        Icon(Icons.Rounded.Call, contentDescription = null, tint = KavachTheme.colors.primary, modifier = Modifier.size(20.dp))
    }
}

/** One of my reports in a list: type, time, status badge, place. Taps through to the tracker. */
@Composable
fun IncidentRow(
    incident: Incident,
    profile: Profile?,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val place = Format.residentPlace(context, incident, profile)
    KavachCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TypeIcon(incident.type)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(incident.type.labelRes()),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        Format.timeAgo(context, incident.createdAt, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = KavachTheme.colors.textMuted,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatusBadge(incident.status, staff = false)
                    if (place.isNotBlank()) {
                        Text(
                            place,
                            style = MaterialTheme.typography.bodyMedium,
                            color = KavachTheme.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = KavachTheme.colors.textTertiary)
        }
    }
}

/** The latest news about an open report in plain words (activeSentence). */
@Composable
fun activeSentence(incident: Incident): String {
    val name = incident.assignedName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.resident_someone)
    return when (incident.status) {
        IncidentStatus.Acknowledged -> stringResource(R.string.resident_sentence_accepted, name)
        IncidentStatus.EnRoute -> stringResource(R.string.resident_sentence_en_route, name)
        IncidentStatus.OnScene -> stringResource(R.string.resident_sentence_on_scene)
        else -> stringResource(pendingSentenceRes(incident.type))
    }
}

/** "We've alerted the maintenance / security team". */
fun pendingSentenceRes(type: IncidentType): Int =
    if (Rules.teamFor(type) == Rules.Team.Maintenance) R.string.resident_sentence_pending_maintenance
    else R.string.resident_sentence_pending_security

/** An open report on Home: what, the latest news, when it was sent. */
@Composable
fun ActiveReportRow(incident: Incident, now: Instant, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    KavachCard(modifier = modifier, onClick = onClick, accent = KavachTheme.colors.status(incident.status)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TypeIcon(incident.type)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(incident.type.labelRes()),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        Format.timeAgo(context, incident.createdAt, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = KavachTheme.colors.textMuted,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (incident.status == IncidentStatus.Pending) PulsingDot(KavachTheme.colors.orange, size = 8.dp)
                    Text(activeSentence(incident), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = KavachTheme.colors.textTertiary)
        }
    }
}

/** Shown while the society runs on the generator: what power my tower has and what to do. */
@Composable
fun PowerNotice(powerSource: PowerSource?, zone: Zone?, modifier: Modifier = Modifier) {
    if (powerSource != PowerSource.Dg) return
    val tone = if (zone?.powerState == PowerState.Off) KavachTheme.colors.red else KavachTheme.colors.orange
    NoticeBanner(tone, Icons.Rounded.Bolt, modifier) {
        Text(stringResource(R.string.resident_dg_title), style = MaterialTheme.typography.titleSmall)
        if (zone != null) {
            val (state, advice) = when (zone.powerState) {
                PowerState.On -> R.string.resident_power_on to R.string.resident_power_advice_on
                PowerState.Rotating -> R.string.resident_power_rotating to R.string.resident_power_advice_rotating
                PowerState.Off -> R.string.resident_power_off to R.string.resident_power_advice_off
            }
            Text(stringResource(state, zone.name), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(advice), style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
        }
    }
}

/** Reports waiting in the offline outbox (and any the server refused). */
@Composable
fun OutboxNotice(
    snapshot: OutboxSnapshot,
    securityPhone: String?,
    sending: Boolean,
    onSendNow: () -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshot.isEmpty) return
    // Like the website's CallLink: no button at all (rather than a disabled one) without a number
    val callSecurity = !securityPhone.isNullOrBlank()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (snapshot.pending.isNotEmpty()) {
            val n = snapshot.pending.size
            NoticeBanner(KavachTheme.colors.orange, Icons.Rounded.CloudOff) {
                Text(pluralStringResource(R.plurals.resident_outbox_waiting, n, n), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.resident_outbox_hint), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(Spacing.xs))
                if (callSecurity) {
                    CallButton(securityPhone, stringResource(R.string.common_call_security), style = CallButtonStyle.Danger, fillWidth = true)
                }
                SecondaryButton(
                    text = stringResource(if (sending) R.string.resident_sending else R.string.resident_send_now),
                    onClick = onSendNow,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        snapshot.failed.forEach { item ->
            key(item.clientId) {
                NoticeBanner(KavachTheme.colors.red, Icons.Rounded.ErrorOutline) {
                    Text(
                        stringResource(R.string.resident_outbox_failed, stringResource(item.report.type.labelRes())),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(stringResource(R.string.resident_outbox_failed_hint), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(Spacing.xs))
                    if (callSecurity) {
                        CallButton(securityPhone, stringResource(R.string.common_call_security), style = CallButtonStyle.Danger, fillWidth = true)
                    }
                    TextButton(onClick = { onDismiss(item.clientId) }) { Text(stringResource(R.string.common_dismiss)) }
                }
            }
        }
    }
}

/** Where a medical alert is. Place only: never the reporter's name, phone or health details. */
fun medicalPlace(context: android.content.Context, alert: MedicalAlert): String {
    val parts = mutableListOf<String>()
    alert.zoneName?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    if (!alert.flatLabel.isNullOrBlank()) {
        parts.add(context.getString(R.string.common_flat_n, alert.flatLabel))
    } else {
        Format.floor(context, alert.floor)?.let { parts.add(it) }
    }
    alert.locationNote?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

/** Community first responders: active medical alerts in the society with "I'm coming". */
@Composable
fun MedicalAlertsSection(
    state: Loadable<List<MedicalAlert>>,
    coming: Set<String>,
    busyId: String?,
    now: Instant,
    onAck: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val data = state.data
    val error = state.error
    // Quiet when there is nothing to do: the section only appears while someone needs help
    if (state.isInitialLoading) return
    if (data.isNullOrEmpty() && error == null) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(R.string.resident_med_title),
            style = MaterialTheme.typography.titleSmall,
            color = KavachTheme.colors.textMuted,
            modifier = Modifier.padding(horizontal = Spacing.xs).semantics { heading() },
        )
        if (data == null && error != null) {
            ErrorBanner(error, onRetry)
        }
        data?.forEach { alert ->
            key(alert.id) {
                val mine = alert.iAmComing || alert.id in coming
                val others = ResidentRules.otherResponders(alert)
                val place = medicalPlace(context, alert).ifBlank { stringResource(R.string.common_type_medical) }
                KavachCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TypeIcon(IncidentType.Medical)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(place, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOf(
                                    Format.timeAgo(context, alert.createdAt, now),
                                    Format.people(context, alert.peopleAffected),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = KavachTheme.colors.textMuted,
                            )
                            if (!alert.description.isNullOrBlank()) {
                                Text(alert.description, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (others > 0) {
                        Spacer(Modifier.size(Spacing.sm))
                        Text(
                            pluralStringResource(R.plurals.resident_med_others, others, others),
                            style = MaterialTheme.typography.bodySmall,
                            color = KavachTheme.colors.textMuted,
                        )
                    }
                    Spacer(Modifier.size(Spacing.sm))
                    if (mine) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = KavachTheme.colors.success)
                            Text(
                                stringResource(R.string.resident_med_on_your_way),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        Button(
                            onClick = { onAck(alert.id) },
                            enabled = busyId == null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            if (busyId == alert.id) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = KavachTheme.colors.onAccent)
                                Spacer(Modifier.width(Spacing.sm))
                            }
                            Text(stringResource(R.string.resident_med_im_coming))
                        }
                    }
                }
            }
        }
    }
}

/** Safety Check roll call on Home: "I'm safe" / "I need help", then my answer with a Change button. */
@Composable
fun SafetyCheckCard(
    card: SafetyCardState,
    onRespond: (SafetyResponseStatus, String?) -> Unit,
    onChange: () -> Unit,
    onKeep: () -> Unit,
    onTrack: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = KavachTheme.colors
    val answer = card.answer
    val asking = (answer == null && card.answerLoaded) || card.changing
    val safe = answer?.status == SafetyResponseStatus.Safe
    val tone = when {
        asking -> colors.red
        safe -> colors.success
        else -> colors.warning
    }
    val where = if (card.check.scope == SafetyCheckScope.Zone) {
        card.check.zone?.name.orEmpty()
    } else {
        stringResource(R.string.resident_sc_whole_society)
    }
    val meta = listOf(where, stringResource(R.string.resident_sc_started, Format.clock(card.check.startedAt)))
        .filter { it.isNotBlank() }.joinToString(" · ")
    var note by rememberSaveable(card.check.id) { mutableStateOf("") }

    KavachCard(modifier = modifier, accent = tone) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.Rounded.Warning, colors.red)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.resident_sc_eyebrow),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.danger,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    card.check.message,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        Spacer(Modifier.size(Spacing.md))
        if (asking) {
            Text(stringResource(R.string.resident_sc_ask), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(Spacing.sm))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(300) },
                label = { Text(stringResource(R.string.resident_sc_note_label)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(Spacing.sm))
            val busy = card.busy
            Button(
                onClick = { onRespond(SafetyResponseStatus.Safe, note) },
                enabled = busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = colors.success, contentColor = colors.onAccent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (busy == SafetyResponseStatus.Safe) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onAccent)
                    Spacer(Modifier.width(Spacing.sm))
                } else {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(stringResource(R.string.resident_sc_safe))
            }
            Spacer(Modifier.size(Spacing.sm))
            Button(
                onClick = { onRespond(SafetyResponseStatus.NeedHelp, note) },
                enabled = busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = colors.danger, contentColor = colors.onAccent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (busy == SafetyResponseStatus.NeedHelp) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onAccent)
                    Spacer(Modifier.width(Spacing.sm))
                } else {
                    Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(stringResource(R.string.resident_sc_need_help))
            }
            if (card.changing) {
                TextButton(onClick = onKeep) { Text(stringResource(R.string.resident_sc_keep)) }
            }
        } else if (answer != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Icon(
                    if (safe) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (safe) R.string.resident_sc_answered_safe else R.string.resident_sc_answered_help) +
                            " · " + Format.clock(answer.respondedAt),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!safe) {
                        Text(
                            stringResource(R.string.resident_sc_help_coming),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                        )
                        val incidentId = answer.incidentId
                        if (incidentId != null) {
                            TextButton(onClick = { onTrack(incidentId) }) { Text(stringResource(R.string.resident_sc_track_help)) }
                        }
                    }
                }
                TextButton(onClick = onChange) { Text(stringResource(R.string.resident_sc_change)) }
            }
        }
    }
}

private fun tipsRes(type: IncidentType): Int = when (type) {
    IncidentType.Lift -> R.array.resident_tips_lift
    IncidentType.Fire -> R.array.resident_tips_fire
    IncidentType.Medical -> R.array.resident_tips_medical
    IncidentType.Water -> R.array.resident_tips_water
    IncidentType.Power -> R.array.resident_tips_power
    IncidentType.Security -> R.array.resident_tips_security
}

/** Collapsible "What to do now" tips for an incident type. */
@Composable
fun SafetyTips(type: IncidentType, modifier: Modifier = Modifier) {
    val tips = stringArrayResource(tipsRes(type))
    var open by rememberSaveable { mutableStateOf(true) }
    val reduce = rememberReduceMotion()
    // TalkBack hears whether the tips are open (the chevron alone is visual only)
    val openState = stringResource(if (open) R.string.resident_tips_expanded else R.string.resident_tips_collapsed)
    KavachCard(modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = openState }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(Icons.Rounded.Lightbulb, KavachTheme.colors.orange, size = 32.dp)
            Text(
                stringResource(R.string.resident_tips_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        if (reduce) {
            if (open) TipList(tips.toList())
        } else {
            AnimatedVisibility(visible = open) { TipList(tips.toList()) }
        }
    }
}

@Composable
private fun TipList(tips: List<String>) {
    Column(
        Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        tips.forEachIndexed { i, tip ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
                Text(tip, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** − 3 + counter for people affected (1..50). */
@Composable
fun PeopleStepper(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(1)) }, enabled = value > 1) {
            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.resident_fewer))
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.widthIn(min = 32.dp).semantics { liveRegion = LiveRegionMode.Polite },
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onChange((value + 1).coerceAtMost(ReportRules.MAX_PEOPLE)) }, enabled = value < ReportRules.MAX_PEOPLE) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.resident_more))
        }
    }
}

/** Round initial for a person (assigned responder). */
@Composable
fun InitialAvatar(name: String, modifier: Modifier = Modifier) {
    Box(
        // Decorative: the name is always shown next to it, so TalkBack skips the lone letter
        modifier.size(40.dp).clip(CircleShape).background(KavachTheme.colors.blue.copy(alpha = 0.15f)).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = KavachTheme.colors.primary,
        )
    }
}

/** Label on the left, value on the right ("Where  Tower A · Flat A-1204"). */
@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted, modifier = Modifier.width(72.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
