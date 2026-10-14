package app.kavach.android.feature.resident

import app.kavach.android.core.ui.components.hapticClick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.kavach.android.core.config.AppConfig
import app.kavach.android.core.data.model.*
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.outbox.OutboxSnapshot
import java.time.Instant
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.PowerSource
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.ui.components.ErrorBanner
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.TypeIcon
import app.kavach.android.core.ui.components.rememberHaptics
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.rememberNow
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/** Resident Home: report an emergency, live reports, Safety Check, notices of power and outbox. */
@Composable
fun ResidentHomeScreen(navigator: KavachNavigator) {
    val vm: ResidentHomeViewModel = hiltViewModel()
    MessageEffect(vm.messages.flow)
    val haptics = rememberHaptics()
    LaunchedEffect(vm) { vm.feedback.collect { ok -> if (ok) haptics.confirm() else haptics.reject() } }

    val context = LocalContext.current
    val profile by vm.profile.collectAsStateWithLifecycle()
    val reports by vm.myReports.collectAsStateWithLifecycle()
    val society by vm.societyInfo.collectAsStateWithLifecycle()
    val homeZone by vm.homeZone.collectAsStateWithLifecycle()
    val outbox by vm.outboxSnapshot.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val safetyCard by vm.safetyCard.collectAsStateWithLifecycle()
    val medical by vm.medicalAlerts.collectAsStateWithLifecycle()
    val coming by vm.coming.collectAsStateWithLifecycle()
    val ackBusy by vm.ackBusy.collectAsStateWithLifecycle()
    val notices by vm.notices.collectAsStateWithLifecycle()
    val now by rememberNow()

    ResidentHomeContent(navigator, profile, reports, society, homeZone, outbox, sending, safetyCard,
        medical, coming, ackBusy, now, notices, vm::retryReports, vm::retryNotices,
        vm::respondToSafetyCheck, vm::changeSafetyAnswer, vm::keepSafetyAnswer,
        vm::sendNow, vm::dismissFailed, vm::imComing, vm::retryMedical)
}

@Composable
internal fun ResidentHomeContent(
    navigator: KavachNavigator,
    profile: Profile?, reports: Loadable<List<Incident>>, society: SocietyInfo,
    homeZone: Zone?, outbox: OutboxSnapshot, sending: Boolean, safetyCard: SafetyCardState?,
    medical: Loadable<List<MedicalAlert>>, coming: Set<String>, ackBusy: String?, now: Instant,
    notices: Loadable<List<Notice>>,
    retryReports: () -> Unit, retryNotices: () -> Unit,
    respondToSafetyCheck: (SafetyResponseStatus, String?) -> Unit,
    changeSafetyAnswer: () -> Unit, keepSafetyAnswer: () -> Unit,
    sendNow: () -> Unit, dismissFailed: (String) -> Unit,
    imComing: (String) -> Unit, retryMedical: () -> Unit,
) {
    val context = LocalContext.current
    val name = profile?.firstName.orEmpty()
    val title = if (name.isNotBlank()) stringResource(R.string.resident_hi_name, name) else stringResource(R.string.resident_hi)
    val subtitle = listOfNotNull(society.name, Format.homeLine(context, profile).ifBlank { null }).joinToString(" · ").ifBlank { null }

    val all = reports.data.orEmpty()
    val active = remember(all) { ResidentRules.activeReports(all) }
    val recent = remember(all) { ResidentRules.recentReports(all) }
    val securityPhone = society.securityPhone
    val openTracker: (String) -> Unit = { navigator.navigate(Routes.Tracker(it)) }

    KavachScaffold(title = title, subtitle = subtitle) { padding ->
        LazyColumn(
            // imePadding: the Safety Check note field stays above the keyboard
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.sm, bottom = Spacing.sm + app.kavach.android.core.ui.components.LocalFloatingBarInset.current),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "report") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    EmergencyAction(onClick = { navigator.navigate(Routes.ReportType) })
                    KavachCard(onClick = hapticClick({ navigator.navigate(Routes.ReportDetails(IncidentType.Lift.wire)) })) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TypeIcon(IncidentType.Lift, size = 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.resident_stuck_in_lift), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.resident_stuck_in_lift_sub),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KavachTheme.colors.textMuted,
                                )
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = KavachTheme.colors.textTertiary)
                        }
                    }
                }
            }


            safetyCard?.let { card ->
                item(key = "safety-check") {
                    SafetyCheckCard(
                        card = card,
                        onRespond = respondToSafetyCheck,
                        onChange = changeSafetyAnswer,
                        onKeep = keepSafetyAnswer,
                        onTrack = openTracker,
                    )
                }
            }

            if (!outbox.isEmpty) {
                item(key = "outbox") {
                    OutboxNotice(
                        snapshot = outbox,
                        securityPhone = securityPhone,
                        sending = sending,
                        onSendNow = sendNow,
                        onDismiss = dismissFailed,
                    )
                }
            }

            val reportsError = reports.error
            if (reportsError != null && reports.data == null) {
                item(key = "reports-error") { ErrorBanner(reportsError, retryReports) }
            }

            if (active.isNotEmpty()) {
                item(key = "active-title") {
                    SectionHeader(stringResource(R.string.resident_active_title), trailing = {
                        TextButton(onClick = hapticClick({ navigator.openTab(Routes.MyReports) })) { Text(stringResource(R.string.resident_see_all)) }
                    })
                }
                items(active.take(2), key = { it.id }, contentType = { "active" }) { incident ->
                    ActiveReportRow(
                        incident = incident,
                        now = now,
                        onClick = hapticClick({ openTracker(incident.id) }),
                        modifier = reorderModifier(),
                    )
                }
            }


            if (active.isEmpty() && reports.data != null) {
                item(key = "active-empty") {
                    SectionHeader(stringResource(R.string.resident_active_title))
                    KavachCard {
                        Text(stringResource(R.string.resident_no_active_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.resident_no_active_text), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (reports.data == null && reports.error == null) {
                item(key = "reports-loading") { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            item(key = "notices-preview") {
                Column {
                    SectionHeader(stringResource(R.string.resident_notices_title), trailing = {
                        TextButton(onClick = hapticClick({ navigator.openTab(Routes.ResidentNotices) })) {
                            Text(stringResource(R.string.resident_see_all))
                        }
                    })
                    val visible = ResidentRules.visibleNotices(notices.data.orEmpty(), now).take(2)
                    if (notices.error != null && notices.data == null) ErrorBanner(notices.error, retryNotices)
                    else if (notices.data == null) Text(stringResource(R.string.home_notices_loading), style = MaterialTheme.typography.bodySmall)
                    else if (visible.isEmpty()) KavachCard {
                        Text(stringResource(R.string.resident_no_notices_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.resident_no_notices_text), style = MaterialTheme.typography.bodySmall)
                    }
                    else visible.forEach { notice ->
                        KavachCard(onClick = { navigator.openTab(Routes.ResidentNotices) }) {
                            Text(notice.title, style = MaterialTheme.typography.titleMedium)
                            Text(notice.body, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            item(key = "quick-call") {
                Column {
                    SectionHeader(stringResource(R.string.resident_quick_call))
                    KavachCard(contentPadding = PaddingValues(0.dp)) {
                        if (!securityPhone.isNullOrBlank()) {
                            CallRow(
                                phone = securityPhone,
                                title = stringResource(R.string.resident_security_desk),
                                detail = securityPhone,
                                callLabel = stringResource(R.string.common_call_number, stringResource(R.string.resident_security_desk)),
                                leading = { IconTile(Icons.Rounded.Shield, KavachTheme.colors.blue, size = 36.dp) },
                            )
                            HorizontalDivider(color = KavachTheme.colors.separator)
                        }
                        CallRow(
                            phone = AppConfig.EMERGENCY_112,
                            title = stringResource(R.string.common_emergency_112),
                            detail = stringResource(R.string.resident_contact_112),
                            callLabel = stringResource(R.string.resident_call_112),
                            leading = { IconTile(Icons.Rounded.Warning, KavachTheme.colors.red, size = 36.dp) },
                        )
                    }
                }
            }

            if (society.powerSource == PowerSource.Dg) {
                item(key = "power") { PowerNotice(society.powerSource, homeZone) }
            }

            if (profile?.isFirstResponder == true) {
                item(key = "medical") {
                    MedicalAlertsSection(
                        state = medical,
                        coming = coming,
                        busyId = ackBusy,
                        now = now,
                        onAck = imComing,
                        onRetry = retryMedical,
                    )
                }
            }

            if (recent.isNotEmpty()) {
                item(key = "recent-title") {
                    SectionHeader(
                        stringResource(R.string.resident_recent_title),
                        trailing = {
                            TextButton(onClick = hapticClick({ navigator.openTab(Routes.MyReports) })) {
                                Text(stringResource(R.string.resident_see_all))
                            }
                        },
                    )
                }
                items(recent, key = { "recent-${it.id}" }, contentType = { "recent" }) { incident ->
                    IncidentRow(
                        incident = incident,
                        profile = profile,
                        now = now,
                        onClick = hapticClick({ openTracker(incident.id) }),
                        modifier = reorderModifier(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmergencyAction(onClick: () -> Unit) {
                    Button(
                        onClick = hapticClick(onClick),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = KavachTheme.colors.onAccent,
                        ),
                        contentPadding = PaddingValues(Spacing.md),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 192.dp),
                    ) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(40.dp))
                        Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                            Text("SOS", style = MaterialTheme.typography.displaySmall)
                            Text(stringResource(R.string.resident_report_emergency), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.resident_report_emergency_sub), style = MaterialTheme.typography.bodySmall)
                        }
                    }
}
