package app.kavach.android.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.ui.components.toneText
import app.kavach.android.core.data.model.ComplianceItem
import app.kavach.android.core.data.model.ComplianceStatus
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.PowerSource
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.SafetyCheck
import app.kavach.android.core.data.model.SafetySummary
import app.kavach.android.core.data.model.Society
import app.kavach.android.core.data.model.TeamMember
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.repo.AssetRepository
import app.kavach.android.core.data.repo.IncidentFilters
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.repo.SafetyCheckRepository
import app.kavach.android.core.data.repo.SocietyRepository
import app.kavach.android.core.data.repo.TeamRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.domain.Rules
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.ErrorBanner
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.ListRow
import app.kavach.android.core.ui.components.LoadableContent
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.PulsingDot
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.StatTile
import app.kavach.android.core.ui.components.rememberReduceMotion
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.rememberNow
import app.kavach.android.core.ui.format.shortLabelRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AdminOverviewViewModel @Inject constructor(
    session: SessionManager,
    societyRepo: SocietyRepository,
    incidentRepo: IncidentRepository,
    assetRepo: AssetRepository,
    teamRepo: TeamRepository,
    private val safetyRepo: SafetyCheckRepository,
) : ViewModel() {
    private val retryTick = MutableStateFlow(0)
    fun retry() { retryTick.value = retryTick.value + 1 }

    val profile: StateFlow<Profile?> = session.profile

    val society: StateFlow<Loadable<Society?>> =
        retryTick.flatMapLatest { societyRepo.listenSociety() }.inStateKeepingData(viewModelScope)

    /** All active incidents. */
    val queue: StateFlow<Loadable<List<Incident>>> =
        retryTick.flatMapLatest { incidentRepo.listenQueue() }.inStateKeepingData(viewModelScope)

    /** Midnight in the device zone; re-read every minute so "today" rolls over at midnight. */
    private val startOfDay: Flow<Instant> = flow {
        while (true) {
            emit(Rules.startOfToday())
            delay(60_000)
        }
    }.distinctUntilChanged()

    /** Everything reported since midnight (for "avg response today"). */
    val today: StateFlow<Loadable<List<Incident>>> =
        combine(retryTick, startOfDay) { _, from -> from }
            .flatMapLatest { from -> incidentRepo.listenList(IncidentFilters(from = from)) }
            .inStateKeepingData(viewModelScope)

    val compliance: StateFlow<Loadable<List<ComplianceItem>>> =
        retryTick.flatMapLatest { assetRepo.listenCompliance() }.inStateKeepingData(viewModelScope)

    val team: StateFlow<Loadable<List<TeamMember>>> =
        retryTick.flatMapLatest { teamRepo.listenTeam() }.inStateKeepingData(viewModelScope)

    val zones: StateFlow<Loadable<List<Zone>>> =
        retryTick.flatMapLatest { societyRepo.listenZones() }.inStateKeepingData(viewModelScope)

    val activeCheck: StateFlow<Loadable<SafetyCheck?>> =
        retryTick.flatMapLatest { safetyRepo.listenActive() }.inStateKeepingData(viewModelScope)

    /**
     * Live counts of the running Safety Check, or null when none runs. Keyed on the check id only:
     * every refetch of [activeCheck] would otherwise tear down and reopen the roll-call query.
     */
    val rollSummary: StateFlow<SafetySummary?> = activeCheck
        .map { it.data?.id }
        .distinctUntilChanged()
        .flatMapLatest { checkId -> summaryFlow(checkId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun summaryFlow(checkId: String?): Flow<SafetySummary?> =
        if (checkId == null) flowOf(null) else safetyRepo.listenRollCall(checkId).map { it.data?.summary }
}

@Composable
fun AdminOverviewScreen(navigator: KavachNavigator) {
    val vm: AdminOverviewViewModel = hiltViewModel()
    val assignVm: AssignViewModel = hiltViewModel()
    MessageEffect(assignVm.messages.flow)

    val profile by vm.profile.collectAsStateWithLifecycle()
    val society by vm.society.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val compliance by vm.compliance.collectAsStateWithLifecycle()
    val team by vm.team.collectAsStateWithLifecycle()
    val zones by vm.zones.collectAsStateWithLifecycle()
    val activeCheck by vm.activeCheck.collectAsStateWithLifecycle()
    val rollSummary by vm.rollSummary.collectAsStateWithLifecycle()
    val now by rememberNow(30_000)
    var assigningId by rememberSaveable { mutableStateOf<String?>(null) }

    val societyName = society.data?.name ?: profile?.society?.name

    KavachScaffold(
        title = stringResource(R.string.nav_overview),
        subtitle = societyName,
        actions = {
            IconButton(onClick = { navigator.navigate(Routes.Profile) }) {
                Icon(Icons.Rounded.Person, contentDescription = stringResource(R.string.nav_profile))
            }
        },
    ) { padding ->
        LoadableContent(queue, vm::retry, Modifier.fillMaxSize().padding(padding)) { active ->
            val escMin = escalateMinutes(society.data)
            val stats = remember(active, today.data, escMin, now) {
                overviewStats(active, today.data, escMin, now)
            }
            val onDG = (society.data?.powerSource ?: profile?.society?.powerSource) == PowerSource.Dg
            val reduce = rememberReduceMotion()
            val check = activeCheck.data

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = ListPadding,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (check != null) {
                    item(key = "check-banner") { CheckBanner(rollSummary) { navigator.navigate(Routes.AdminSafetyCheck) } }
                }
                item(key = "stats") {
                    StatGrid(stats, todayLoading = today.data == null, escalateMinutes = escMin)
                }
                item(key = "attention-head") {
                    SectionHeader(
                        stringResource(R.string.admin_needs_attention),
                        trailing = {
                            TextButton(onClick = { navigator.openTab(Routes.AdminIncidents) }) {
                                Text(stringResource(R.string.admin_see_all))
                            }
                        },
                    )
                }
                if (stats.attention.isEmpty()) {
                    item(key = "all-clear") {
                        KavachCard {
                            EmptyState(
                                icon = Icons.Rounded.CheckCircle,
                                title = stringResource(R.string.admin_all_clear),
                                text = if (active.isNotEmpty()) {
                                    stringResource(R.string.admin_all_clear_active, active.size)
                                } else {
                                    stringResource(R.string.admin_all_clear_text)
                                },
                            )
                        }
                    }
                }
                items(stats.attention.take(ATTENTION_LIMIT), key = { it.id }, contentType = { "incident" }) { incident ->
                    val assignLabel = stringResource(R.string.admin_assign_incident, stringResource(incident.type.shortLabelRes()))
                    IncidentRow(
                        incident = incident,
                        now = now,
                        onClick = { navigator.navigate(Routes.IncidentDetail(incident.id)) },
                        modifier = if (reduce) Modifier else Modifier.animateItem(),
                        action = {
                            val assignedAlready = incident.assignedTo != null
                            val actionText = stringResource(if (assignedAlready) R.string.admin_reassign else R.string.admin_assign)
                            if (assignedAlready) {
                                OutlinedButton(
                                    onClick = { assigningId = incident.id },
                                    modifier = Modifier.semantics { contentDescription = assignLabel },
                                ) { Text(actionText) }
                            } else {
                                Button(
                                    onClick = { assigningId = incident.id },
                                    modifier = Modifier.semantics { contentDescription = assignLabel },
                                ) { Text(actionText) }
                            }
                        },
                    )
                }
                if (stats.attention.size > ATTENTION_LIMIT) {
                    item(key = "more-attention") {
                        TextButton(onClick = { navigator.openTab(Routes.AdminIncidents) }) {
                            Text(stringResource(R.string.admin_more_need_attention, stats.attention.size - ATTENTION_LIMIT))
                        }
                    }
                }

                item(key = "society-head") { SectionHeader(stringResource(R.string.nav_society)) }
                // Like the website: the first failure among the status feeds, with retry
                val statusError = society.error ?: compliance.error ?: activeCheck.error ?: team.error
                if (statusError != null) {
                    item(key = "society-error") { ErrorBanner(statusError, vm::retry) }
                }
                item(key = "society-status") {
                    SocietyStatus(
                        onDG = onDG,
                        zones = zones.data,
                        compliance = compliance,
                        team = team,
                        check = check,
                        rollSummary = rollSummary,
                        onPower = {
                            SocietyTabHint.request(SocietyTab.Power)
                            navigator.openTab(Routes.AdminSociety)
                        },
                        onCompliance = {
                            SocietyTabHint.request(SocietyTab.Compliance)
                            navigator.openTab(Routes.AdminSociety)
                        },
                        onSafety = { navigator.navigate(Routes.AdminSafetyCheck) },
                        onTeam = { navigator.openTab(Routes.AdminTeam) },
                    )
                }

                item(key = "actions-head") { SectionHeader(stringResource(R.string.admin_quick_actions)) }
                item(key = "actions") {
                    GroupedCard {
                        ListRow(
                            title = stringResource(R.string.admin_post_notice),
                            leading = { IconTile(Icons.Rounded.Campaign, KavachTheme.colors.orange) },
                            trailing = { ChevronIcon() },
                            onClick = { navigator.navigate(Routes.AdminNotices) },
                        )
                        HorizontalDivider()
                        ListRow(
                            title = stringResource(R.string.nav_board),
                            leading = { IconTile(Icons.Rounded.Tv, KavachTheme.colors.indigo) },
                            trailing = { ChevronIcon() },
                            onClick = { navigator.navigate(Routes.CommandBoard) },
                        )
                        HorizontalDivider()
                        ListRow(
                            title = stringResource(R.string.nav_insights),
                            leading = { IconTile(Icons.Rounded.BarChart, KavachTheme.colors.blue) },
                            trailing = { ChevronIcon() },
                            onClick = { navigator.navigate(Routes.AdminReports) },
                        )
                    }
                }
            }
        }
    }

    val assigning = (queue.data ?: emptyList()).firstOrNull { it.id == assigningId }
    if (assigning != null) {
        AssignSheet(
            incident = assigning,
            vm = assignVm,
            onDismiss = { assigningId = null },
            onOpenTeam = { navigator.openTab(Routes.AdminTeam) },
        )
    }
}

@Composable
private fun CheckBanner(summary: SafetySummary?, onClick: () -> Unit) {
    KavachCard(onClick = onClick, accent = KavachTheme.colors.red) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PulsingDot(KavachTheme.colors.red)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.admin_check_running),
                    style = MaterialTheme.typography.titleMedium,
                    color = KavachTheme.colors.danger,
                )
                Text(
                    rollCallLine(summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                )
            }
            ChevronIcon()
        }
    }
}

@Composable
private fun rollCallLine(summary: SafetySummary?): String =
    if (summary == null) {
        stringResource(R.string.common_loading)
    } else {
        stringResource(R.string.admin_roll_call_line, summary.safe, summary.needHelp, summary.unanswered)
    }

@Composable
private fun StatGrid(stats: OverviewStats, todayLoading: Boolean, escalateMinutes: Int) {
    val context = LocalContext.current
    val colors = KavachTheme.colors
    fun tone(count: Int, color: Color) = if (count > 0) color else Color.Unspecified
    val avg = if (todayLoading) {
        Format.duration(context, null)
    } else {
        Format.duration(context, stats.avgResponseMinutes?.let { Math.round(it) })
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatTile("${stats.open}", stringResource(R.string.admin_stat_open), Modifier.weight(1f), tone(stats.open, colors.orange))
            StatTile(
                "${stats.unclaimed}",
                stringResource(R.string.admin_stat_unclaimed, escalateMinutes),
                Modifier.weight(1f),
                tone(stats.unclaimed, colors.red),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatTile("${stats.inProgress}", stringResource(R.string.admin_stat_in_progress), Modifier.weight(1f), tone(stats.inProgress, colors.blue))
            StatTile("${stats.escalated}", stringResource(R.string.admin_stat_escalated), Modifier.weight(1f), tone(stats.escalated, colors.red))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatTile(if (todayLoading) "–" else "${stats.today}", stringResource(R.string.admin_stat_today), Modifier.weight(1f))
            StatTile(avg, stringResource(R.string.admin_stat_avg_response), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SocietyStatus(
    onDG: Boolean,
    zones: List<Zone>?,
    compliance: Loadable<List<ComplianceItem>>,
    team: Loadable<List<TeamMember>>,
    check: SafetyCheck?,
    rollSummary: SafetySummary?,
    onPower: () -> Unit,
    onCompliance: () -> Unit,
    onSafety: () -> Unit,
    onTeam: () -> Unit,
) {
    val colors = KavachTheme.colors
    val counts = zonePowerCounts(zones.orEmpty())
    val overdue = compliance.data.orEmpty().count { it.complianceStatus == ComplianceStatus.Overdue }
    val dueSoon = compliance.data.orEmpty().count { it.complianceStatus == ComplianceStatus.DueSoon }
    val onDuty = team.data.orEmpty().filter { it.onDuty }

    GroupedCard {
        StatusRow(
            icon = Icons.Rounded.Bolt,
            tone = if (onDG) colors.orange else colors.green,
            title = stringResource(R.string.admin_power_title),
            value = stringResource(if (onDG) R.string.common_power_source_dg else R.string.common_power_source_grid),
            valueTone = if (onDG) colors.orange else Color.Unspecified,
            detail = when {
                onDG -> stringResource(R.string.admin_dg_summary, counts.off, counts.rotating)
                counts.off > 0 -> stringResource(R.string.admin_zones_manual_off, counts.off)
                else -> stringResource(R.string.admin_all_zones_on)
            },
            onClick = onPower,
        )
        HorizontalDivider()
        StatusRow(
            icon = Icons.Rounded.Checklist,
            tone = if (overdue > 0) colors.red else colors.green,
            title = stringResource(R.string.admin_compliance_title),
            value = when {
                compliance.data == null -> "–"
                overdue > 0 -> stringResource(R.string.admin_overdue_count, overdue)
                else -> stringResource(R.string.admin_all_up_to_date)
            },
            valueTone = if (overdue > 0) colors.red else Color.Unspecified,
            detail = if (compliance.data == null) null else stringResource(R.string.admin_due_soon_count, dueSoon),
            onClick = onCompliance,
        )
        HorizontalDivider()
        StatusRow(
            icon = if (check != null) Icons.Rounded.Warning else Icons.Rounded.HealthAndSafety,
            tone = if (check != null) colors.red else colors.gray,
            title = stringResource(R.string.nav_safety_check),
            value = if (check != null) stringResource(R.string.admin_check_running) else null,
            valueTone = colors.red,
            detail = if (check != null) rollCallLine(rollSummary) else stringResource(R.string.admin_no_check_running),
            onClick = onSafety,
        )
        HorizontalDivider()
        StatusRow(
            icon = Icons.Rounded.Groups,
            tone = colors.blue,
            title = stringResource(R.string.admin_team_on_duty),
            value = when {
                team.data == null -> "–"
                onDuty.isNotEmpty() -> onDuty.size.toString()
                else -> stringResource(R.string.admin_nobody_on_duty)
            },
            valueTone = if (team.data != null && onDuty.isEmpty()) colors.red else Color.Unspecified,
            detail = onDuty.joinToString(", ") { it.fullName }.ifEmpty { null },
            onClick = onTeam,
        )
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    tone: Color,
    title: String,
    detail: String?,
    value: String?,
    valueTone: Color,
    onClick: () -> Unit,
) {
    ListRow(
        title = title,
        subtitle = detail,
        leading = { IconTile(icon, tone) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = toneText(valueTone),
                    )
                }
                ChevronIcon()
            }
        },
        onClick = onClick,
    )
}
