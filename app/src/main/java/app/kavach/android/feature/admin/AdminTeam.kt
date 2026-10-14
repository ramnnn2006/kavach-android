package app.kavach.android.feature.admin

import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import app.kavach.android.core.ui.components.toneText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Member
import app.kavach.android.core.data.model.TeamMember
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.remote.ErrorCode
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.data.repo.TeamRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.MessageQueue
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.rememberHaptics
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.format.rememberNow
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** What the member sheet edits (an account from the team list or the residents list). */
data class MemberEdit(
    val id: String,
    val name: String,
    val role: UserRole,
    val specialties: List<IncidentType>,
    val flatLabel: String,
    /** Incidents currently assigned to this person (staff list only). */
    val activeLoad: Int = 0,
    /** Pre-selected role when opened from "Make staff". */
    val initialRole: UserRole? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AdminTeamViewModel @Inject constructor(
    private val teamRepo: TeamRepository,
    private val session: SessionManager,
) : ViewModel() {
    val messages = MessageQueue()

    private val retryTick = MutableStateFlow(0)
    fun retry() { retryTick.value = retryTick.value + 1 }

    val team: StateFlow<Loadable<List<TeamMember>>> =
        retryTick.flatMapLatest { teamRepo.listenTeam() }.inStateKeepingData(viewModelScope)

    val residents: StateFlow<Loadable<List<Member>>> =
        retryTick.flatMapLatest { teamRepo.listenMembers(UserRole.Resident) }.inStateKeepingData(viewModelScope)

    /** Staff in display order (on duty first, responders, then name), or null until loaded. */
    val staff: StateFlow<List<TeamMember>?> = team
        .map { it.data }
        .distinctUntilChanged()
        .map { data -> data?.let { sortStaff(it) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val query = MutableStateFlow("")

    /** The search box text; the list is filtered off the main thread (residents can be hundreds). */
    fun setQuery(text: String) { query.value = text }

    /** Residents matching the search, or null until the list has loaded. */
    val people: StateFlow<List<Member>?> = combine(
        residents.map { it.data }.distinctUntilChanged(),
        query,
    ) { list, q -> list?.let { filterResidents(it, q) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val myId: String? get() = session.currentUserId

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    /** String resource of the last save failure (shown inside the sheet). */
    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error

    fun clearError() { _error.value = null }

    fun save(target: MemberEdit, role: UserRole, specialties: List<IncidentType>, onDone: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                teamRepo.updateMember(target.id, role, specialtiesForRole(role, specialties))
                messages.send(R.string.admin_member_saved, target.name)
                onDone()
            } catch (e: KavachException) {
                // The server refuses to demote your own admin account (invalid)
                val res = if (target.id == myId && e.code == ErrorCode.Invalid) R.string.common_err_own_admin else e.messageRes()
                _error.value = res
                messages.send(res)
            } finally {
                _saving.value = false
            }
        }
    }
}

@Composable
fun AdminTeamScreen(navigator: KavachNavigator) {
    val vm: AdminTeamViewModel = hiltViewModel()
    MessageEffect(vm.messages.flow)

    val team by vm.team.collectAsStateWithLifecycle()
    val residents by vm.residents.collectAsStateWithLifecycle()
    val sortedStaff by vm.staff.collectAsStateWithLifecycle()
    val matches by vm.people.collectAsStateWithLifecycle()
    val now by rememberNow(60_000)
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemberEdit?>(null) }
    // Also restores the filter after the screen is recreated with a saved query
    LaunchedEffect(query) { vm.setQuery(query) }
    // A new sheet never shows the previous person's save error
    val openSheet: (MemberEdit) -> Unit = { edit ->
        vm.clearError()
        editing = edit
    }

    val staffList = sortedStaff
    val staff = staffList.orEmpty()
    val matchList = matches
    val people = matchList.orEmpty()
    val queryText = query.trim()

    KavachScaffold(
        title = stringResource(R.string.nav_team),
        subtitle = staffList?.let { s -> stringResource(R.string.admin_team_subtitle, s.count { m -> m.onDuty }, s.size) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "staff-head") { SectionHeader(stringResource(R.string.admin_staff)) }
            statusItem(team, "team", vm::retry)
            if (staffList != null && staffList.isEmpty()) {
                item(key = "staff-empty") {
                    KavachCard {
                        EmptyState(
                            icon = Icons.Rounded.Groups,
                            title = stringResource(R.string.admin_no_staff),
                            text = stringResource(R.string.admin_no_staff_text),
                        )
                    }
                }
            }
            items(staff, key = { "staff-${it.id}" }, contentType = { "staff" }) { m ->
                StaffRow(
                    member = m,
                    isSelf = m.id == vm.myId,
                    now = now,
                    onClick = {
                        openSheet(
                            MemberEdit(
                                id = m.id,
                                name = m.fullName,
                                role = m.role,
                                specialties = m.specialties,
                                flatLabel = "",
                                activeLoad = m.activeLoad,
                            ),
                        )
                    },
                )
            }

            item(key = "residents-head") { SectionHeader(stringResource(R.string.admin_residents)) }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.admin_search_residents)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.admin_clear_search))
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            statusItem(residents, "residents", vm::retry)
            if (matchList != null && matchList.isEmpty()) {
                item(key = "residents-empty") {
                    KavachCard {
                        EmptyState(
                            icon = if (queryText.isNotEmpty()) Icons.Rounded.Search else Icons.Rounded.Groups,
                            title = if (queryText.isNotEmpty()) {
                                stringResource(R.string.admin_no_match, queryText)
                            } else {
                                stringResource(R.string.admin_no_residents)
                            },
                            text = stringResource(if (queryText.isNotEmpty()) R.string.admin_no_match_text else R.string.admin_no_residents_text),
                            action = if (queryText.isNotEmpty()) {
                                {
                                    OutlinedButton(onClick = { query = "" }) { Text(stringResource(R.string.admin_clear_search)) }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            items(people, key = { "resident-${it.id}" }, contentType = { "resident" }) { m ->
                ResidentRow(
                    member = m,
                    onMakeStaff = {
                        openSheet(
                            MemberEdit(
                                id = m.id,
                                name = m.fullName,
                                role = m.role,
                                specialties = m.specialties,
                                flatLabel = memberFlatLabel(m),
                                initialRole = UserRole.Responder,
                            ),
                        )
                    },
                )
            }
        }
    }

    editing?.let { target ->
        // Keyed so the role / specialty choices never carry over to another person's sheet
        key(target.id, target.initialRole) {
            MemberSheet(
                edit = target,
                isSelf = target.id == vm.myId,
                vm = vm,
                onDismiss = { editing = null },
            )
        }
    }
}

@Composable
private fun StaffRow(member: TeamMember, isSelf: Boolean, now: Instant, onClick: () -> Unit) {
    val context = LocalContext.current
    val editLabel = stringResource(R.string.admin_edit_member, member.fullName)
    KavachCard(onClick = onClick, modifier = Modifier.semantics { contentDescription = editLabel }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InitialsAvatar(member.fullName)
            Column(Modifier.weight(1f)) {
                Text(
                    if (isSelf) member.fullName + " " + stringResource(R.string.admin_you) else member.fullName,
                    style = MaterialTheme.typography.titleMedium,
                )
                val specs = specialtiesText(member.specialties)
                Text(
                    stringResource(member.role.labelRes()) + if (specs.isNotEmpty()) " · $specs" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    DutyDot(member.onDuty)
                    Text(
                        stringResource(R.string.admin_active_load, member.activeLoad),
                        style = MaterialTheme.typography.bodySmall,
                        color = KavachTheme.colors.textMuted,
                    )
                }
                member.lastResolvedAt?.let { at ->
                    Text(
                        stringResource(R.string.admin_last_resolved, Format.timeAgo(context, at, now)),
                        style = MaterialTheme.typography.bodySmall,
                        color = KavachTheme.colors.textMuted,
                    )
                }
            }
            CallIconButton(member.phone, member.fullName)
        }
    }
}

@Composable
private fun ResidentRow(member: Member, onMakeStaff: () -> Unit) {
    val context = LocalContext.current
    val makeStaffLabel = stringResource(R.string.admin_make_staff_name, member.fullName)
    KavachCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InitialsAvatar(member.fullName)
            Column(Modifier.weight(1f)) {
                Text(
                    member.fullName.ifBlank { stringResource(R.string.admin_unnamed) },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    memberFlatLabel(member).ifEmpty { stringResource(R.string.admin_no_flat) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                )
                member.firstResponderSkill?.let { skill ->
                    Text(
                        stringResource(R.string.admin_first_responder_skill, stringResource(skill.labelRes())),
                        style = MaterialTheme.typography.bodySmall,
                        color = KavachTheme.colors.textMuted,
                    )
                }
                if (member.vulnerability.any) {
                    Text(
                        Format.vulnerabilityFull(context, member.vulnerability),
                        style = MaterialTheme.typography.bodySmall,
                        color = toneText(KavachTheme.colors.warning),
                    )
                }
            }
            TextButton(onClick = onMakeStaff, modifier = Modifier.semantics { contentDescription = makeStaffLabel }) {
                Text(stringResource(R.string.admin_make_staff))
            }
        }
    }
}

@Composable
private fun MemberSheet(edit: MemberEdit, isSelf: Boolean, vm: AdminTeamViewModel, onDismiss: () -> Unit) {
    val errorView = remember { BringIntoViewRequester() }
    val saving by vm.saving.collectAsStateWithLifecycle()
    val serverError by vm.error.collectAsStateWithLifecycle()
    var role by rememberSaveable { mutableStateOf(edit.initialRole ?: edit.role) }
    var specialties by rememberSaveable { mutableStateOf(edit.specialties) }
    var localError by rememberSaveable { mutableStateOf<Int?>(null) }
    val haptics = rememberHaptics()
    LaunchedEffect(edit.id) { vm.clearError() }

    val subtitle = listOf(stringResource(edit.role.labelRes()), edit.flatLabel).filter { it.isNotBlank() }.joinToString(" · ")
    val roles = listOf(UserRole.Resident, UserRole.Responder, UserRole.Admin)

    AdminSheet(
        title = edit.name.ifBlank { stringResource(R.string.admin_unnamed) },
        subtitle = subtitle,
        onDismiss = onDismiss,
        busy = saving,
        footer = {
            SheetButtons(
                confirmLabel = stringResource(R.string.common_save),
                onConfirm = {
                    if (role == UserRole.Responder && specialties.isEmpty()) {
                        localError = R.string.admin_pick_specialty
                    } else if (role == UserRole.Resident && edit.activeLoad > 0) {
                        // Their open incidents would stay assigned to a non-responder
                        localError = R.string.admin_demote_has_load
                    } else {
                        localError = null
                        vm.save(edit, role, specialties) {
                            haptics.confirm()
                            onDismiss()
                        }
                    }
                },
                onCancel = onDismiss,
                busy = saving,
            )
        },
    ) {
        SegmentedPicker(
            options = roles.map { PickerOption(it, stringResource(it.labelRes())) },
            selected = role,
            onSelect = {
                role = it
                localError = null
            },
            enabled = !saving,
        )
        Text(
            stringResource(
                when (role) {
                    UserRole.Resident -> R.string.admin_role_hint_resident
                    UserRole.Responder -> R.string.admin_role_hint_responder
                    UserRole.Admin -> R.string.admin_role_hint_admin
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTheme.colors.textMuted,
        )
        if (isSelf && role != UserRole.Admin) {
            Text(
                stringResource(R.string.admin_self_demote_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = toneText(KavachTheme.colors.warning),
            )
        }
        if (role != UserRole.Resident) {
            SpecialtyPicker(
                selected = specialties,
                onChange = {
                    specialties = it
                    localError = null
                },
            )
        }
        val errorRes = localError ?: serverError
        if (errorRes != null) {
            Text(stringResource(errorRes), color = KavachTheme.colors.danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.bringIntoViewRequester(errorView).semantics { liveRegion = LiveRegionMode.Assertive })
            LaunchedEffect(errorRes) { errorView.bringIntoView() }
        }
    }
}
