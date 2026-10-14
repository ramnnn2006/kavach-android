package app.kavach.android.feature.admin

import app.kavach.android.core.ui.components.hapticClick

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.ApprovedMember
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.model.ZoneKind
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.data.repo.SocietyRepository
import app.kavach.android.core.data.repo.TeamRepository
import app.kavach.android.core.domain.Phones
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.ui.components.ConfirmDialog
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.ErrorBanner
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.LoadableContent
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.MessageQueue
import app.kavach.android.core.ui.components.ToneBadge
import app.kavach.android.core.ui.components.rememberHaptics
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
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
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AdminMembersViewModel @Inject constructor(
    private val teamRepo: TeamRepository,
    societyRepo: SocietyRepository,
) : ViewModel() {
    val messages = MessageQueue()

    private val retryTick = MutableStateFlow(0)
    fun retry() { retryTick.value = retryTick.value + 1 }

    val members: StateFlow<Loadable<List<ApprovedMember>>> =
        retryTick.flatMapLatest { teamRepo.listenApprovedMembers() }.inStateKeepingData(viewModelScope)

    val zones: StateFlow<Loadable<List<Zone>>> =
        retryTick.flatMapLatest { societyRepo.listenZones() }.inStateKeepingData(viewModelScope)

    private val query = MutableStateFlow("")

    /** The search box text; the list (can be hundreds) is filtered off the main thread. */
    fun setQuery(text: String) { query.value = text }

    /** Numbers matching the search, or null until the list has loaded. */
    val visible: StateFlow<List<ApprovedMember>?> = combine(
        members.map { it.data }.distinctUntilChanged(),
        query,
    ) { list, q -> list?.let { filterApproved(it, q) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How many on the list have signed in (header line), or null until loaded. */
    val signedInCount: StateFlow<Int?> = members
        .map { state -> state.data?.count { it.profileId != null } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** String resource of the last failure, shown inside the sheet. */
    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error

    fun clearError() { _error.value = null }

    /** Add ([existing] null) or update one number. The form was already checked with [validateMemberForm]. */
    fun save(existing: ApprovedMember?, values: MemberFormValues, onDone: () -> Unit) {
        if (_busy.value) return
        val phone = Phones.normalize(values.phone)
        if (phone == null) {
            _error.value = R.string.common_err_phone_number
            return
        }
        val flat = values.flatNumber.trim().ifBlank { null }
        // A tower alone means nothing for staff: send both or neither
        val zoneId = if (flat == null) null else values.zoneId?.takeIf { it.isNotBlank() }
        // Like the website: staff (responders and admins) keep their specialties, residents none.
        // Dropping an admin's here would also wipe them from a linked account.
        val specialties = specialtiesForRole(values.role, values.specialties)
        val name = values.name.trim()
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                if (existing == null) {
                    teamRepo.addApprovedMember(phone, name, values.role, zoneId, flat, specialties)
                    messages.send(R.string.admin_member_added, name)
                } else {
                    teamRepo.updateApprovedMember(existing.id, phone, name, values.role, zoneId, flat, specialties)
                    messages.send(R.string.admin_member_saved, name)
                }
                onDone()
            } catch (e: KavachException) {
                _error.value = e.messageRes()
                messages.error(e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun remove(member: ApprovedMember, onDone: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                teamRepo.removeApprovedMember(member.id)
                messages.send(R.string.admin_member_removed, member.fullName)
                onDone()
            } catch (e: KavachException) {
                // The snackbar sits behind the sheet: show the reason inside it too
                _error.value = e.messageRes()
                messages.error(e)
            } finally {
                _busy.value = false
            }
        }
    }
}

/** Which sheet is open: a new number, or an existing one being edited. */
private sealed interface MemberSheetState {
    data object Add : MemberSheetState
    data class Edit(val member: ApprovedMember) : MemberSheetState
}

@Composable
fun AdminMembersScreen(navigator: KavachNavigator) {
    val vm: AdminMembersViewModel = hiltViewModel()
    MessageEffect(vm.messages.flow)

    val members by vm.members.collectAsStateWithLifecycle()
    val zones by vm.zones.collectAsStateWithLifecycle()
    val shown by vm.visible.collectAsStateWithLifecycle()
    val signedIn by vm.signedInCount.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sheet by remember { mutableStateOf<MemberSheetState?>(null) }
    // Also restores the filter after the screen is recreated with a saved query
    LaunchedEffect(query) { vm.setQuery(query) }
    // A new sheet never shows the previous one's error
    val openSheet: (MemberSheetState) -> Unit = { next ->
        vm.clearError()
        sheet = next
    }

    val signedInNow = signedIn
    val total = members.data?.size

    KavachScaffold(
        title = stringResource(R.string.nav_members),
        onBack = navigator::back,
        subtitle = if (signedInNow != null && total != null) {
            stringResource(R.string.admin_members_subtitle, signedInNow, total)
        } else {
            null
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = hapticClick({ openSheet(MemberSheetState.Add) }),
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.admin_add_member)) },
            )
        },
    ) { padding ->
        LoadableContent(members, vm::retry, Modifier.fillMaxSize().padding(padding)) { all ->
            // Null for a moment after new data arrives, while the search is applied
            val visibleList = shown
            val visible = visibleList.orEmpty()
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.sm, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item(key = "explain") {
                    Text(
                        stringResource(R.string.admin_members_explain),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KavachTheme.colors.textMuted,
                    )
                }
                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.admin_search_members)) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                IconButton(onClick = hapticClick({ query = "" })) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.admin_clear_search))
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (visibleList != null && visibleList.isEmpty()) {
                    item(key = "empty") {
                        val searching = query.isNotBlank()
                        EmptyState(
                            icon = Icons.Rounded.Groups,
                            title = stringResource(if (searching) R.string.admin_no_match_generic else R.string.admin_no_members),
                            text = stringResource(if (searching) R.string.admin_no_match_text else R.string.admin_no_members_text),
                            action = if (searching && all.isNotEmpty()) {
                                {
                                    OutlinedButton(onClick = hapticClick({ query = "" })) { Text(stringResource(R.string.admin_clear_search)) }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
                items(visible, key = { it.id }, contentType = { "member" }) { member ->
                    ApprovedMemberRow(member) { openSheet(MemberSheetState.Edit(member)) }
                }
            }
        }
    }

    when (val open = sheet) {
        null -> Unit
        else -> {
            // The live row when there is one (e.g. "already signed in" turns up while the sheet is open)
            val existing = (open as? MemberSheetState.Edit)?.member?.let { snapshot ->
                members.data?.firstOrNull { it.id == snapshot.id } ?: snapshot
            }
            // Keyed so the form never keeps another person's values
            key(existing?.id ?: "new") {
                MemberFormSheet(
                    existing = existing,
                    zones = zones.data.orEmpty(),
                    zonesError = zones.error,
                    onRetryZones = vm::retry,
                    vm = vm,
                    onDismiss = { sheet = null },
                )
            }
        }
    }
}

@Composable
private fun ApprovedMemberRow(member: ApprovedMember, onClick: () -> Unit) {
    val context = LocalContext.current
    val signedIn = member.profileId != null
    KavachCard(onClick = hapticClick(onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InitialsAvatar(member.fullName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(member.fullName, style = MaterialTheme.typography.titleMedium)
                Text(
                    Phones.display(member.phone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                )
                val place = listOfNotNull(
                    member.zoneName,
                    member.flatNumber?.let { context.getString(R.string.common_flat_n, it) },
                ).joinToString(" · ")
                Text(
                    listOf(stringResource(member.role.labelRes()), place).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = KavachTheme.colors.textMuted,
                )
                if (member.role != UserRole.Resident) {
                    Text(
                        Format.specialties(context, member.role, member.specialties),
                        style = MaterialTheme.typography.bodySmall,
                        color = KavachTheme.colors.textMuted,
                    )
                }
            }
            ToneBadge(
                stringResource(if (signedIn) R.string.admin_signed_in else R.string.admin_not_signed_in),
                if (signedIn) KavachTheme.colors.green else KavachTheme.colors.gray,
            )
        }
    }
}

@Composable
private fun MemberFormSheet(
    existing: ApprovedMember?,
    zones: List<Zone>,
    zonesError: KavachException?,
    onRetryZones: () -> Unit,
    vm: AdminMembersViewModel,
    onDismiss: () -> Unit,
) {
    val errorView = remember { BringIntoViewRequester() }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val serverError by vm.error.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    LaunchedEffect(existing?.id) { vm.clearError() }

    var name by rememberSaveable { mutableStateOf(existing?.fullName.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(existing?.let { Phones.display(it.phone) }.orEmpty()) }
    var role by rememberSaveable { mutableStateOf(existing?.role ?: UserRole.Resident) }
    var zoneId by rememberSaveable { mutableStateOf(existing?.zoneId) }
    // The list shows the flat label ("A-1204"); the RPCs take the number and add the tower letter
    var flat by rememberSaveable { mutableStateOf(flatDigits(existing?.flatNumber)) }
    var specialties by rememberSaveable { mutableStateOf<List<IncidentType>>(existing?.specialties.orEmpty()) }
    var issue by rememberSaveable { mutableStateOf<MemberFormIssue?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    // Flats belong to towers (member_resolve_flat accepts only kind = tower), like the website
    val towers = remember(zones) { zones.filter { it.kind == ZoneKind.Tower } }
    val roles = listOf(UserRole.Resident, UserRole.Responder, UserRole.Admin)
    val noTower = stringResource(R.string.admin_no_tower)
    val nameError = if (issue == MemberFormIssue.NameRequired) stringResource(R.string.common_err_name_required) else null
    val phoneError = if (issue == MemberFormIssue.PhoneInvalid) stringResource(R.string.common_err_phone_number) else null
    val flatError = when (issue) {
        MemberFormIssue.FlatRequired -> stringResource(R.string.common_err_member_flat)
        else -> null
    }
    // Field problems are shown on their field; this line is for the rest and for server errors
    val formErrorRes = when (issue) {
        MemberFormIssue.NameRequired -> R.string.common_err_name_required
        MemberFormIssue.PhoneInvalid -> R.string.common_err_phone_number
        MemberFormIssue.FlatRequired -> R.string.common_err_member_flat
        MemberFormIssue.TowerRequired -> R.string.admin_err_pick_tower
        MemberFormIssue.SpecialtyRequired -> R.string.admin_pick_specialty
        null -> serverError
    }

    AdminSheet(
        title = stringResource(if (existing == null) R.string.admin_add_member else R.string.admin_edit_number),
        subtitle = existing?.fullName,
        onDismiss = onDismiss,
        busy = busy,
        footer = {
            SheetButtons(
                confirmLabel = stringResource(if (existing == null) R.string.admin_add else R.string.common_save),
                onConfirm = {
                    val values = MemberFormValues(name, phone, role, zoneId, flat, specialties)
                    val problem = validateMemberForm(values)
                    issue = problem
                    if (problem == null) {
                        vm.save(existing, values) {
                            haptics.confirm()
                            onDismiss()
                        }
                    } else {
                        haptics.reject()
                    }
                },
                onCancel = onDismiss,
                busy = busy,
            )
        },
    ) {
        if (existing?.profileId != null) {
            Text(
                stringResource(R.string.admin_member_linked_note),
                style = MaterialTheme.typography.bodySmall,
                color = KavachTheme.colors.textMuted,
            )
        }
        OutlinedTextField(
            value = name,
            onValueChange = {
                if (it.length <= MEMBER_NAME_MAX) {
                    name = it
                    issue = null
                }
            },
            label = { Text(stringResource(R.string.common_name)) },
            isError = nameError != null,
            supportingText = if (nameError != null) {
                { Text(nameError) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it; issue = null },
            label = { Text(stringResource(R.string.common_phone)) },
            isError = phoneError != null,
            supportingText = { Text(phoneError ?: stringResource(R.string.admin_phone_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        SegmentedPicker(
            options = roles.map { PickerOption(it, stringResource(it.labelRes())) },
            selected = role,
            onSelect = { role = it; issue = null },
        )
        if (towers.isEmpty() && zonesError != null) {
            ErrorBanner(zonesError, onRetryZones)
        }
        PickerField(
            errorText = if (issue == MemberFormIssue.TowerRequired) stringResource(R.string.admin_err_pick_tower) else null,
            label = stringResource(R.string.common_tower),
            selectedText = towers.firstOrNull { it.id == zoneId }?.name ?: noTower,
            options = towers.map { PickerOption<String?>(it.id, it.name) },
            onSelect = { zoneId = it; issue = null },
        )
        OutlinedTextField(
            value = flat,
            onValueChange = { flat = it; issue = null },
            label = { Text(stringResource(R.string.common_flat)) },
            isError = flatError != null,
            supportingText = {
                Text(flatError ?: stringResource(if (role == UserRole.Resident) R.string.admin_flat_required else R.string.admin_flat_optional))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        // Responders must pick at least one; admins may (same as the website and the Team sheet)
        if (role != UserRole.Resident) {
            SpecialtyPicker(selected = specialties, onChange = { specialties = it; issue = null })
        }
        if (formErrorRes != null) {
            Text(stringResource(formErrorRes), color = KavachTheme.colors.danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.bringIntoViewRequester(errorView).semantics { liveRegion = LiveRegionMode.Assertive })
            LaunchedEffect(formErrorRes) { errorView.bringIntoView() }
        }
        if (existing != null) {
            TextButton(onClick = hapticClick({ confirmRemove = true }), enabled = !busy) {
                Text(stringResource(R.string.admin_remove_from_list), color = KavachTheme.colors.danger)
            }
        }
    }

    if (confirmRemove && existing != null) {
        ConfirmDialog(
            title = stringResource(R.string.admin_remove_member_title, existing.fullName),
            confirmLabel = stringResource(R.string.admin_remove),
            onConfirm = {
                confirmRemove = false
                vm.remove(existing) {
                    haptics.confirm()
                    onDismiss()
                }
            },
            onDismiss = { confirmRemove = false },
            text = stringResource(R.string.admin_remove_member_text),
            destructive = true,
        )
    }
}
