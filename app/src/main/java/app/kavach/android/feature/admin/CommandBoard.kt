package app.kavach.android.feature.admin

import app.kavach.android.core.ui.components.hapticClick

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.PowerSource
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.SafetyCheck
import app.kavach.android.core.data.model.SafetyCheckScope
import app.kavach.android.core.data.model.SafetySummary
import app.kavach.android.core.data.model.Society
import app.kavach.android.core.data.model.TeamMember
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.repo.AssetRepository
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.repo.SafetyCheckRepository
import app.kavach.android.core.data.repo.SocietyRepository
import app.kavach.android.core.data.repo.TeamRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.nav.incidentSharedKey
import app.kavach.android.core.nav.sharedBoundsFor
import app.kavach.android.core.ui.components.EscalationBadge
import app.kavach.android.core.ui.components.ErrorBanner
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.StatTile
import app.kavach.android.core.ui.components.ToneBadge
import app.kavach.android.core.ui.components.TypeIcon
import app.kavach.android.core.ui.components.icon
import app.kavach.android.core.ui.components.rememberReduceMotion
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.format.peopleLabel
import app.kavach.android.core.ui.format.placeLine
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CommandBoardViewModel @Inject constructor(
    session: SessionManager,
    societyRepo: SocietyRepository,
    incidentRepo: IncidentRepository,
    teamRepo: TeamRepository,
    assetRepo: AssetRepository,
    private val safetyRepo: SafetyCheckRepository,
) : ViewModel() {
    private val retryTick = MutableStateFlow(0)
    fun retry() { retryTick.value = retryTick.value + 1 }

    val profile: StateFlow<Profile?> = session.profile

    val society: StateFlow<Loadable<Society?>> =
        retryTick.flatMapLatest { societyRepo.listenSociety() }.inState(viewModelScope)
    val queue: StateFlow<Loadable<List<Incident>>> =
        retryTick.flatMapLatest { incidentRepo.listenQueue() }.inState(viewModelScope)
    val team: StateFlow<Loadable<List<TeamMember>>> =
        retryTick.flatMapLatest { teamRepo.listenTeam() }.inState(viewModelScope)
    val assets: StateFlow<Loadable<List<Asset>>> =
        retryTick.flatMapLatest { assetRepo.listenAssets() }.inState(viewModelScope)
    val activeCheck: StateFlow<Loadable<SafetyCheck?>> =
        retryTick.flatMapLatest { safetyRepo.listenActive() }.inState(viewModelScope)

    /** Running / none / not known yet; doesn't blink while the active check refetches. */
    val checkUi: StateFlow<CheckUi> = activeCheck
        .toCheckUi()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckUi.Loading)

    /** Counts of the running check: one roll-call subscription per check, not one per refetch. */
    val rollSummary: StateFlow<SafetySummary?> = activeCheck
        .map { it.data?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> summaryFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun summaryFlow(checkId: String?): Flow<SafetySummary?> =
        if (checkId == null) flowOf(null) else safetyRepo.listenRollCall(checkId).map { it.data?.summary }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Landscape, screen kept on and system bars hidden while the board is open; all restored when it
 * closes (Exit, back button or gesture, sign-out). Rotation doesn't recreate MainActivity
 * (configChanges), but a theme or font change still does: the orientation from before the board is
 * kept in saved state, so the new activity restores the right one and nothing stays stuck in
 * landscape (also after process death).
 */
@Composable
private fun BoardWindowEffect() {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() } ?: return
    val originalOrientation = rememberSaveable { activity.requestedOrientation }
    DisposableEffect(activity, lifecycleOwner) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        // Switching apps and coming back can bring the bars back: hide them again on every resume
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
            // Only when the board really closes; on a recreate the new activity keeps landscape
            if (!activity.isChangingConfigurations) activity.requestedOrientation = originalOrientation
        }
    }
}

/**
 * The board's clock: ticks on each second boundary, only while the board is visible. Pass the
 * [State] down and read it in the small text that shows it, so the board itself doesn't
 * recompose every second.
 */
@Composable
private fun rememberBoardNow(): State<Instant> {
    val now = remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now.value = Instant.now()
                delay(1_000L - System.currentTimeMillis() % 1_000L)
            }
        }
    }
    return now
}

private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
private fun BoardClock(now: State<Instant>) {
    Text(
        clockFormat.format(now.value),
        style = MaterialTheme.typography.headlineMedium,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
fun CommandBoardScreen(navigator: KavachNavigator) {
    val vm: CommandBoardViewModel = hiltViewModel()
    BoardWindowEffect()

    val profile by vm.profile.collectAsStateWithLifecycle()
    val society by vm.society.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val team by vm.team.collectAsStateWithLifecycle()
    val assets by vm.assets.collectAsStateWithLifecycle()
    val activeCheck by vm.activeCheck.collectAsStateWithLifecycle()
    val checkUi by vm.checkUi.collectAsStateWithLifecycle()
    val rollSummary by vm.rollSummary.collectAsStateWithLifecycle()
    // A State, not a value: only the clock and the wait timers read it
    val now = rememberBoardNow()
    val reduce = rememberReduceMotion()
    val colors = KavachTheme.colors

    val lists = remember(queue.data) { boardLists(queue.data.orEmpty()) }
    val open = lists.first
    val inProgress = lists.second
    val onDuty = remember(team.data) { team.data.orEmpty().filter { it.onDuty } }
    val faulty = remember(assets.data) { assets.data.orEmpty().filter { it.state != AssetState.Ok } }
    val onDG = (society.data?.powerSource ?: profile?.society?.powerSource) == PowerSource.Dg
    val societyName = society.data?.name ?: profile?.society?.name.orEmpty()
    val error = queue.error ?: team.error ?: assets.error ?: activeCheck.error ?: society.error

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Header: society, power source, clock, close
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = colors.primary, modifier = Modifier.size(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.nav_board),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textMuted,
                    )
                    Text(societyName, style = MaterialTheme.typography.headlineMedium)
                }
                ToneBadge(
                    stringResource(if (onDG) R.string.common_power_source_dg else R.string.common_power_source_grid),
                    if (onDG) colors.orange else colors.green,
                )
                BoardClock(now)
                OutlinedButton(onClick = hapticClick(navigator::back)) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.admin_exit), modifier = Modifier.padding(start = Spacing.sm))
                }
            }
            if (error != null) ErrorBanner(error, vm::retry)

            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                // Left: open alerts, most urgent first
                LazyColumn(
                    Modifier.weight(1.3f).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    item(key = "open-head") { PanelTitle(stringResource(R.string.admin_open_alerts), open.size, urgent = true) }
                    if (queue.data != null && open.isEmpty()) {
                        item(key = "open-none") { Quiet(stringResource(R.string.admin_no_open_alerts)) }
                    }
                    items(open, key = { "open-${it.id}" }) { incident ->
                        BoardAlert(
                            incident = incident,
                            now = now,
                            onClick = hapticClick({ navigator.navigate(Routes.IncidentDetail(incident.id)) }),
                            modifier = if (reduce) Modifier else Modifier.animateItem(),
                        )
                    }
                }

                // Right: in progress, on duty, equipment, Safety Check
                LazyColumn(
                    Modifier.weight(1f).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    item(key = "progress-head") { PanelTitle(stringResource(R.string.admin_in_progress), inProgress.size, urgent = false) }
                    if (queue.data != null && inProgress.isEmpty()) {
                        item(key = "progress-none") { Quiet(stringResource(R.string.admin_nothing_in_progress)) }
                    }
                    items(inProgress, key = { "progress-${it.id}" }) { incident ->
                        ProgressRow(
                            incident = incident,
                            now = now,
                            onClick = hapticClick({ navigator.navigate(Routes.IncidentDetail(incident.id)) }),
                            modifier = if (reduce) Modifier else Modifier.animateItem(),
                        )
                    }

                    item(key = "duty-head") { PanelTitle(stringResource(R.string.admin_on_duty_title), onDuty.size, urgent = false) }
                    if (team.data != null && onDuty.isEmpty()) {
                        item(key = "duty-none") { Quiet(stringResource(R.string.admin_nobody_on_duty)) }
                    }
                    items(onDuty, key = { "duty-${it.id}" }) { member ->
                        DutyRow(member, modifier = if (reduce) Modifier else Modifier.animateItem())
                    }

                    item(key = "equip-head") { PanelTitle(stringResource(R.string.admin_lifts_equipment), faulty.size, urgent = false) }
                    if (assets.data != null && faulty.isEmpty()) {
                        item(key = "equip-none") { Quiet(stringResource(R.string.admin_all_equipment_ok)) }
                    }
                    items(faulty, key = { "equip-${it.id}" }) { asset ->
                        EquipmentRow(asset, modifier = if (reduce) Modifier else Modifier.animateItem())
                    }

                    item(key = "check-head") { PanelTitle(stringResource(R.string.nav_safety_check), null, urgent = false) }
                    item(key = "check") {
                        BoardCheck((checkUi as? CheckUi.Running)?.check, rollSummary, noneKnown = checkUi is CheckUi.None)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(title: String, count: Int?, urgent: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false))
        if (count != null) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = if (urgent && count > 0) KavachTheme.colors.danger else KavachTheme.colors.textMuted,
            )
        }
    }
}

@Composable
private fun Quiet(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = KavachTheme.colors.success)
        Text(text, style = MaterialTheme.typography.titleMedium, color = KavachTheme.colors.textMuted)
    }
}

/** One open alert, big: type, place, tags and the running wait time. */
@Composable
private fun BoardAlert(incident: Incident, now: State<Instant>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KavachTheme.colors
    val escalated = incident.isEscalated
    KavachCard(
        modifier = modifier.sharedBoundsFor(incidentSharedKey(incident.id)),
        onClick = hapticClick(onClick),
        accent = if (escalated) colors.red else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            TypeIcon(incident.type, size = 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    stringResource(incident.type.labelRes()),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    placeLine(incident),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textMuted,
                )
                if (escalated || incident.vulnerable || incident.peopleAffected > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (escalated) EscalationBadge(incident.escalationLevel)
                        if (incident.vulnerable) ToneBadge(stringResource(R.string.admin_vulnerable), colors.orange)
                        if (incident.peopleAffected > 1) {
                            Text(peopleLabel(incident.peopleAffected), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                        }
                    }
                }
            }
            WaitTimer(incident.createdAt, now, if (escalated) colors.danger else Color.Unspecified)
        }
    }
}

/** The big running wait time of an open alert (the only part of the card that changes each second). */
@Composable
private fun WaitTimer(since: Instant, now: State<Instant>, color: Color) {
    val elapsed = Format.elapsed(since, now.value)
    val waiting = stringResource(R.string.admin_waiting_for, elapsed)
    Text(
        elapsed,
        style = MaterialTheme.typography.displaySmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = color,
        textAlign = TextAlign.End,
        modifier = Modifier.clearAndSetSemantics { contentDescription = waiting },
    )
}

/** "Assignee · 12:34" under an in-progress alert. */
@Composable
private fun ProgressMeta(assignee: String, since: Instant, now: State<Instant>) {
    Text(
        "$assignee · ${Format.elapsed(since, now.value)}",
        style = MaterialTheme.typography.bodyLarge,
        color = KavachTheme.colors.textMuted,
    )
}

@Composable
private fun ProgressRow(incident: Incident, now: State<Instant>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KavachTheme.colors
    KavachCard(modifier = modifier.sharedBoundsFor(incidentSharedKey(incident.id)), onClick = hapticClick(onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TypeIcon(incident.type, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(incident.type.shortLabelRes()) + " · " + placeLine(incident),
                    style = MaterialTheme.typography.titleMedium,
                )
                ProgressMeta(
                    assignee = incident.assignedName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.admin_unassigned),
                    since = incident.createdAt,
                    now = now,
                )
            }
            Text(
                stringResource(incident.status.labelRes(staff = true)),
                style = MaterialTheme.typography.titleSmall,
                color = colors.status(incident.status),
            )
        }
    }
}

@Composable
private fun DutyRow(member: TeamMember, modifier: Modifier = Modifier) {
    val colors = KavachTheme.colors
    KavachCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DutyDot(true)
            Column(Modifier.weight(1f)) {
                Text(member.fullName, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (member.specialties.isNotEmpty()) specialtiesText(member.specialties) else stringResource(member.role.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
            }
            if (member.activeLoad > 0) {
                Text(
                    stringResource(R.string.admin_active_load, member.activeLoad),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun EquipmentRow(asset: Asset, modifier: Modifier = Modifier) {
    val colors = KavachTheme.colors
    KavachCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(asset.kind.icon(), colors.assetKind(asset.kind))
            Column(Modifier.weight(1f)) {
                Text(asset.name, style = MaterialTheme.typography.titleMedium)
                if (!asset.notes.isNullOrBlank()) {
                    Text(asset.notes, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                }
            }
            Text(
                stringResource(asset.state.labelRes()),
                style = MaterialTheme.typography.titleSmall,
                color = colors.assetState(asset.state),
            )
        }
    }
}

@Composable
private fun BoardCheck(check: SafetyCheck?, summary: SafetySummary?, noneKnown: Boolean) {
    val colors = KavachTheme.colors
    if (check == null) {
        if (noneKnown) Quiet(stringResource(R.string.admin_no_check_running))
        return
    }
    KavachCard(accent = colors.red) {
        Text(check.message, style = MaterialTheme.typography.titleMedium)
        Text(
            listOf(
                if (check.scope == SafetyCheckScope.Zone) {
                    check.zone?.name ?: stringResource(R.string.admin_one_tower)
                } else {
                    stringResource(R.string.admin_whole_society)
                },
                stringResource(R.string.admin_started_at, Format.clock(check.startedAt)),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textMuted,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        if (summary != null) {
            Row(
                Modifier.padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                StatTile("${summary.safe}", stringResource(R.string.admin_safe), Modifier.weight(1f), colors.success)
                StatTile(
                    "${summary.needHelp}",
                    stringResource(R.string.admin_need_help),
                    Modifier.weight(1f),
                    if (summary.needHelp > 0) colors.red else Color.Unspecified,
                )
                StatTile("${summary.unanswered}", stringResource(R.string.admin_not_answered), Modifier.weight(1f))
            }
        }
    }
}
