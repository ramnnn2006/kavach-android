package app.kavach.android.feature.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Member
import app.kavach.android.core.data.model.RollCall
import app.kavach.android.core.data.model.SafetyCheck
import app.kavach.android.core.data.model.SafetyCheckScope
import app.kavach.android.core.data.model.SafetyResponse
import app.kavach.android.core.data.model.SafetyResponseStatus
import app.kavach.android.core.data.model.SafetySummary
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.model.ZoneKind
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.data.repo.SafetyCheckRepository
import app.kavach.android.core.data.repo.SocietyRepository
import app.kavach.android.core.data.repo.TeamRepository
import app.kavach.android.core.domain.Rules
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.ui.components.ConfirmDialog
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.ErrorState
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.MessageQueue
import app.kavach.android.core.ui.components.PulsingDot
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.StatTile
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.format.rememberNow
import app.kavach.android.core.ui.format.timeAgo
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** What the top of the Safety Check screen shows (the active check is "nothing" for most of the year). */
sealed interface CheckUi {
    data object Loading : CheckUi
    data object None : CheckUi
    data class Running(val check: SafetyCheck) : CheckUi
    data class Failed(val error: KavachException) : CheckUi
}

/**
 * `listenActive()` → [CheckUi]. A `Loadable` with no data can mean "still loading" or "no check
 * running"; only a finished fetch (not loading, no error) means None. Refetches keep what is on
 * screen, except after a failure, where a retry shows the spinner again.
 */
internal fun Flow<Loadable<SafetyCheck?>>.toCheckUi(): Flow<CheckUi> =
    scan<Loadable<SafetyCheck?>, CheckUi>(CheckUi.Loading) { previous, next ->
        val check = next.data
        when {
            check != null -> CheckUi.Running(check)
            next.loading -> if (previous is CheckUi.Failed) CheckUi.Loading else previous
            next.error != null ->
                if (previous is CheckUi.Running || previous is CheckUi.None) previous else CheckUi.Failed(next.error)
            else -> CheckUi.None
        }
    }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AdminSafetyCheckViewModel @Inject constructor(
    private val safetyRepo: SafetyCheckRepository,
    teamRepo: TeamRepository,
    societyRepo: SocietyRepository,
) : ViewModel() {
    val messages = MessageQueue()

    private val retryTick = MutableStateFlow(0)
    fun retry() { retryTick.value = retryTick.value + 1 }

    val checkUi: StateFlow<CheckUi> = retryTick
        .flatMapLatest { safetyRepo.listenActive() }
        .toCheckUi()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckUi.Loading)

    /** Roll call of the running check; Retry restarts it too (the check id alone doesn't change). */
    val rollCall: StateFlow<Loadable<RollCall>> = combine(
        checkUi.map { (it as? CheckUi.Running)?.check?.id }.distinctUntilChanged(),
        retryTick,
    ) { id, _ -> id }
        .flatMapLatest { id -> rollCallFlow(id) }
        .inState(viewModelScope)

    private fun rollCallFlow(checkId: String?): Flow<Loadable<RollCall>> =
        if (checkId == null) flowOf(Loadable<RollCall>(loading = false)) else safetyRepo.listenRollCall(checkId)

    /** Every account, to show names, flats and numbers next to roll-call answers. */
    val members: StateFlow<Loadable<List<Member>>> =
        retryTick.flatMapLatest { teamRepo.listenMembers() }.inState(viewModelScope)

    val zones: StateFlow<Loadable<List<Zone>>> =
        retryTick.flatMapLatest { societyRepo.listenZones() }.inState(viewModelScope)

    val history: StateFlow<Loadable<List<SafetyCheck>>> =
        retryTick.flatMapLatest { safetyRepo.listenHistory(20) }.inState(viewModelScope)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error
    fun clearError() { _error.value = null }

    fun start(scope: SafetyCheckScope, zoneId: String?, message: String, type: IncidentType) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                safetyRepo.start(scope, zoneId, message.trim(), type)
                messages.send(R.string.admin_check_started)
                // Keep the form locked until the new check is on screen: the server allows more
                // than one running check, so a second tap in this gap would start another one
                withTimeoutOrNull(SCREEN_CATCH_UP_MS) { checkUi.first { it is CheckUi.Running } }
            } catch (e: KavachException) {
                _error.value = e.messageRes()
                messages.error(e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun end(checkId: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                safetyRepo.end(checkId)
                messages.send(R.string.admin_check_ended)
                // Same for End: keep it disabled until the ended check leaves the screen
                withTimeoutOrNull(SCREEN_CATCH_UP_MS) {
                    checkUi.first { (it as? CheckUi.Running)?.check?.id != checkId }
                }
            } catch (e: KavachException) {
                messages.error(e)
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        /** How long a start/end waits for the live query to show the change. */
        const val SCREEN_CATCH_UP_MS = 10_000L
    }
}

/** The three roll-call lists of a running check (computed once per data change, not per frame). */
private data class Roster(
    val byId: Map<String, Member>,
    val needHelp: List<SafetyResponse>,
    val notAnswered: List<Member>,
    val safe: List<SafetyResponse>,
)

private fun buildRoster(check: SafetyCheck, members: List<Member>, responses: List<SafetyResponse>): Roster = Roster(
    byId = members.associateBy { it.id },
    needHelp = responses.filter { it.status == SafetyResponseStatus.NeedHelp },
    notAnswered = unansweredResidents(members, responses, check),
    safe = responses.filter { it.status == SafetyResponseStatus.Safe }.sortedByDescending { it.respondedAt },
)

@Composable
fun AdminSafetyCheckScreen(navigator: KavachNavigator) {
    val vm: AdminSafetyCheckViewModel = hiltViewModel()
    MessageEffect(vm.messages.flow)

    val checkUi by vm.checkUi.collectAsStateWithLifecycle()
    val rollCall by vm.rollCall.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val zones by vm.zones.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirmEnd by remember { mutableStateOf(false) }

    val ended = remember(history.data) { history.data.orEmpty().filter { it.endedAt != null } }
    val runningCheck = (checkUi as? CheckUi.Running)?.check
    val roll = rollCall.data
    val roster = remember(runningCheck, members.data, roll) {
        if (runningCheck == null || roll == null) null else buildRoster(runningCheck, members.data.orEmpty(), roll.responses)
    }

    KavachScaffold(
        title = stringResource(R.string.nav_safety_check),
        subtitle = stringResource(R.string.admin_safety_subtitle),
        onBack = navigator::back,
    ) { padding ->
        LazyColumn(
            // imePadding: the start form's message field is low on the screen
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            when (val ui = checkUi) {
                CheckUi.Loading -> loadingItem("check")
                is CheckUi.Failed -> item(key = "check-error") { ErrorState(ui.error, vm::retry) }
                CheckUi.None -> item(key = "start-form") {
                    StartForm(zones = zones.data.orEmpty(), busy = busy, vm = vm)
                }
                is CheckUi.Running -> {
                    val check = ui.check

                    item(key = "live") {
                        LiveCheckCard(
                            check = check,
                            summary = roll?.summary,
                            loading = roll == null,
                            busy = busy,
                            onEnd = { confirmEnd = true },
                        )
                    }
                    statusItem(rollCall, "roll", vm::retry)
                    statusItem(members, "members", vm::retry)
                    if (roster != null) {
                        val byId = roster.byId
                        val needHelp = roster.needHelp
                        val notAnswered = roster.notAnswered
                        val safe = roster.safe
                        item(key = "help-head") {
                            SectionHeader("${stringResource(R.string.admin_need_help)} · ${needHelp.size}")
                        }
                        if (needHelp.isEmpty()) item(key = "help-none") { MutedText(stringResource(R.string.admin_none_need_help)) }
                        items(needHelp, key = { "help-${it.id}" }) { r ->
                            val person = r.profileId?.let { byId[it] }
                            PersonCard(person) {
                                if (!r.note.isNullOrBlank()) {
                                    Text(r.note, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.danger)
                                }
                                if (r.incidentId != null) {
                                    TextButton(onClick = { navigator.navigate(Routes.IncidentDetail(r.incidentId)) }) {
                                        Text(stringResource(R.string.admin_open_alert))
                                    }
                                }
                            }
                        }

                        item(key = "waiting-head") {
                            SectionHeader("${stringResource(R.string.admin_not_answered)} · ${notAnswered.size}")
                        }
                        if (notAnswered.isEmpty()) item(key = "waiting-none") { MutedText(stringResource(R.string.admin_everyone_answered)) }
                        items(notAnswered, key = { "waiting-${it.id}" }) { m ->
                            PersonCard(m) {
                                if (m.vulnerability.any) {
                                    Text(
                                        Format.vulnerabilityFull(LocalContext.current, m.vulnerability),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = KavachTheme.colors.warning,
                                    )
                                }
                            }
                        }

                        item(key = "safe-head") {
                            SectionHeader("${stringResource(R.string.admin_safe)} · ${safe.size}")
                        }
                        if (safe.isEmpty()) item(key = "safe-none") { MutedText(stringResource(R.string.admin_none_safe_yet)) }
                        items(safe, key = { "safe-${it.id}" }) { r ->
                            PersonCard(r.profileId?.let { byId[it] }, showCall = false, trailing = Format.clock(r.respondedAt))
                        }
                    }
                }
            }

            item(key = "past-head") { SectionHeader(stringResource(R.string.admin_past_checks)) }
            statusItem(history, "history", vm::retry)
            if (history.data != null && ended.isEmpty()) {
                item(key = "past-empty") {
                    KavachCard {
                        EmptyState(
                            icon = Icons.Rounded.History,
                            title = stringResource(R.string.admin_no_past_checks),
                            text = stringResource(R.string.admin_no_past_checks_text),
                        )
                    }
                }
            }
            items(ended, key = { "past-${it.id}" }) { c -> PastCheckCard(c) }
        }
    }

    if (confirmEnd && runningCheck != null) {
        ConfirmDialog(
            title = stringResource(R.string.admin_end_check_title),
            confirmLabel = stringResource(R.string.admin_end_check),
            onConfirm = {
                confirmEnd = false
                vm.end(runningCheck.id)
            },
            onDismiss = { confirmEnd = false },
            text = stringResource(R.string.admin_end_check_text, roll?.summary?.unanswered ?: 0L),
            destructive = true,
        )
    }
}

@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
}

@Composable
private fun scopeLabel(check: SafetyCheck): String =
    if (check.scope == SafetyCheckScope.Zone) {
        check.zone?.name ?: stringResource(R.string.admin_one_tower)
    } else {
        stringResource(R.string.admin_whole_society)
    }

@Composable
private fun LiveCheckCard(
    check: SafetyCheck,
    summary: SafetySummary?,
    loading: Boolean,
    busy: Boolean,
    onEnd: () -> Unit,
) {
    val colors = KavachTheme.colors
    // Only this card shows "x min ago", so only it ticks
    val now by rememberNow(30_000)
    KavachCard(accent = colors.red) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PulsingDot(colors.red)
            Text(
                stringResource(R.string.admin_check_running),
                style = MaterialTheme.typography.titleMedium,
                color = colors.danger,
            )
        }
        Text(
            listOf(
                scopeLabel(check),
                stringResource(R.string.admin_started_at, Format.clock(check.startedAt)),
                timeAgo(check.startedAt, now),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Text(
            check.message,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        if (loading || summary == null) {
            Text(
                stringResource(R.string.common_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(top = Spacing.md),
            )
        } else {
            val answered = summary.safe + summary.needHelp
            Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    stringResource(R.string.admin_answered_of, answered, summary.inScope),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                ProgressBar(summary)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatTile("${summary.safe}", stringResource(R.string.admin_safe), Modifier.weight(1f), colors.success)
                    StatTile(
                        "${summary.needHelp}",
                        stringResource(R.string.admin_need_help),
                        Modifier.weight(1f),
                        if (summary.needHelp > 0) colors.red else Color.Unspecified,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatTile("${summary.unanswered}", stringResource(R.string.admin_not_answered), Modifier.weight(1f))
                    StatTile("${summary.inScope}", stringResource(R.string.admin_in_scope), Modifier.weight(1f))
                }
            }
        }
        OutlinedButton(
            onClick = onEnd,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
        ) { Text(stringResource(R.string.admin_end_check), color = colors.danger) }
    }
}

/** Safe (green) and need-help (red) share of everyone in scope. */
@Composable
private fun ProgressBar(summary: SafetySummary) {
    val colors = KavachTheme.colors
    val description = stringResource(R.string.admin_progress_label, summary.safe, summary.needHelp, summary.unanswered)
    val safeShare = share(summary.safe, summary.inScope)
    val helpShare = share(summary.needHelp, summary.inScope)
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(colors.fill)
            .semantics { contentDescription = description },
    ) {
        if (safeShare > 0f) Box(Modifier.fillMaxHeight().weight(safeShare).background(colors.green))
        if (helpShare > 0f) Box(Modifier.fillMaxHeight().weight(helpShare).background(colors.red))
        val rest = 1f - safeShare - helpShare
        if (rest > 0f) Box(Modifier.fillMaxHeight().weight(rest))
    }
}

@Composable
private fun PersonCard(
    member: Member?,
    showCall: Boolean = true,
    trailing: String? = null,
    extra: @Composable () -> Unit = {},
) {
    KavachCard(contentPadding = PaddingValues(start = Spacing.md, top = Spacing.sm, bottom = Spacing.sm, end = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    member?.fullName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.admin_unknown_person),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    member?.let { memberFlatLabel(it) }?.ifEmpty { null } ?: stringResource(R.string.admin_no_flat),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                )
                extra()
            }
            if (trailing != null) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(end = Spacing.sm),
                )
            }
            if (showCall) CallIconButton(member?.phone, member?.fullName)
        }
    }
}

@Composable
private fun PastCheckCard(check: SafetyCheck) {
    val minutes = check.endedAt?.let { Rules.minutesBetween(check.startedAt, it) } ?: 0L
    KavachCard {
        Text(check.message, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
        Text(
            listOf(
                scopeLabel(check),
                Format.dateTime(check.startedAt),
                stringResource(R.string.admin_lasted_min, minutes),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = KavachTheme.colors.textMuted,
        )
    }
}

@Composable
private fun StartForm(zones: List<Zone>, busy: Boolean, vm: AdminSafetyCheckViewModel) {
    val serverError by vm.error.collectAsStateWithLifecycle()
    val defaultMessage = stringResource(R.string.admin_preset_fire)
    var scope by rememberSaveable { mutableStateOf(SafetyCheckScope.Society) }
    var zoneId by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableStateOf(IncidentType.Fire) }
    // Starts empty like the website: the admin picks a ready-made message or writes one, so a
    // drill can't go out with the fire wording by accident
    var message by rememberSaveable { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var confirmStart by remember { mutableStateOf(false) }

    val towers = remember(zones) { zones.filter { it.kind == ZoneKind.Tower } }
    val tower = towers.firstOrNull { it.id == zoneId }
    val presets = listOf(
        if (tower != null) stringResource(R.string.admin_preset_fire_tower, tower.name) else defaultMessage,
        stringResource(R.string.admin_preset_gas),
        stringResource(R.string.admin_preset_drill),
    )

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    SectionHeader(stringResource(R.string.admin_start_safety_check))
    KavachCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                stringResource(R.string.admin_start_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTheme.colors.textMuted,
            )
            SegmentedPicker(
                options = listOf(
                    PickerOption(SafetyCheckScope.Society, stringResource(R.string.admin_whole_society)),
                    PickerOption(SafetyCheckScope.Zone, stringResource(R.string.admin_one_tower)),
                ),
                selected = scope,
                onSelect = {
                    scope = it
                    errorRes = null
                },
            )
            if (scope == SafetyCheckScope.Zone) {
                PickerField(
                    label = stringResource(R.string.common_tower),
                    selectedText = tower?.name ?: stringResource(R.string.admin_choose_tower),
                    options = towers.map { PickerOption<String?>(it.id, it.name) },
                    onSelect = {
                        zoneId = it
                        errorRes = null
                    },
                )
            }
            PickerField(
                label = stringResource(R.string.admin_help_becomes),
                selectedText = stringResource(type.labelRes()),
                options = Rules.TYPE_ORDER.map { PickerOption(it, stringResource(it.labelRes())) },
                onSelect = { type = it },
            )
            Text(
                stringResource(R.string.admin_help_becomes_hint),
                style = MaterialTheme.typography.bodySmall,
                color = KavachTheme.colors.textMuted,
            )
            LimitedField(
                value = message,
                onValueChange = {
                    message = it
                    errorRes = null
                },
                label = stringResource(R.string.admin_message_to_residents),
                max = SAFETY_MESSAGE_MAX,
                minLines = 3,
            )
            Text(
                stringResource(R.string.admin_presets),
                style = MaterialTheme.typography.titleSmall,
                color = KavachTheme.colors.textMuted,
            )
            presets.forEach { preset ->
                OutlinedButton(
                    onClick = {
                        message = preset
                        errorRes = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(preset, textAlign = TextAlign.Start, modifier = Modifier.weight(1f)) }
            }
            val shown = errorRes ?: serverError
            if (shown != null) {
                Text(stringResource(shown), color = KavachTheme.colors.danger, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    errorRes = when {
                        scope == SafetyCheckScope.Zone && tower == null -> R.string.admin_err_pick_tower
                        message.isBlank() -> R.string.admin_err_message_required
                        else -> null
                    }
                    if (errorRes == null) confirmStart = true
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = KavachTheme.colors.danger,
                    contentColor = KavachTheme.colors.onAccent,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.admin_start_safety_check))
            }
        }
    }
    }

    if (confirmStart) {
        ConfirmDialog(
            title = stringResource(R.string.admin_start_check_title),
            confirmLabel = stringResource(R.string.admin_start_now),
            onConfirm = {
                confirmStart = false
                vm.start(scope, if (scope == SafetyCheckScope.Zone) zoneId else null, message, type)
            },
            onDismiss = { confirmStart = false },
            text = if (scope == SafetyCheckScope.Zone && tower != null) {
                stringResource(R.string.admin_start_check_text_tower, tower.name)
            } else {
                stringResource(R.string.admin_start_check_text)
            },
            destructive = true,
        )
    }
}
