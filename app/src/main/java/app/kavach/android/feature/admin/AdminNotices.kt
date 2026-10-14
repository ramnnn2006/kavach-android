package app.kavach.android.feature.admin

import app.kavach.android.core.ui.components.hapticClick

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.Notice
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.data.repo.NoticeRepository
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.ui.components.ConfirmDialog
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.LoadableContent
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.MessageQueue
import app.kavach.android.core.ui.components.rememberReduceMotion
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AdminNoticesViewModel @Inject constructor(
    private val repo: NoticeRepository,
) : ViewModel() {
    val messages = MessageQueue()

    private val retryTick = MutableStateFlow(0)
    fun retry() { retryTick.value = retryTick.value + 1 }

    val notices: StateFlow<Loadable<List<Notice>>> =
        retryTick.flatMapLatest { repo.listenNotices(includeExpired = true) }.inState(viewModelScope)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Id of the notice a row action is working on. */
    private val _rowBusy = MutableStateFlow<String?>(null)
    val rowBusy: StateFlow<String?> = _rowBusy

    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error
    fun clearError() { _error.value = null }

    /** Post a new notice ([existing] null) or save changes to one. */
    fun save(existing: Notice?, title: String, body: String, pinned: Boolean, expiry: LocalDate?, onDone: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        val expiresAt = expiry?.let { endOfDay(it) }
        viewModelScope.launch {
            try {
                if (existing == null) {
                    repo.post(title, body, pinned, expiresAt)
                    messages.send(R.string.admin_notice_posted)
                } else {
                    repo.update(existing.id, title, body, pinned, expiresAt, clearExpiry = expiresAt == null)
                    messages.send(R.string.admin_notice_updated)
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

    fun togglePin(notice: Notice) {
        if (_rowBusy.value != null) return
        _rowBusy.value = notice.id
        viewModelScope.launch {
            try {
                repo.update(notice.id, pinned = !notice.pinned)
                messages.send(if (notice.pinned) R.string.admin_unpinned else R.string.admin_pinned)
            } catch (e: KavachException) {
                messages.error(e)
            } finally {
                _rowBusy.value = null
            }
        }
    }

    fun delete(notice: Notice, onDone: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                repo.delete(notice.id)
                messages.send(R.string.admin_notice_deleted)
                onDone()
            } catch (e: KavachException) {
                // The sheet stays open; show the reason inside it (the snackbar sits behind the sheet)
                _error.value = e.messageRes()
                messages.error(e)
            } finally {
                _busy.value = false
            }
        }
    }
}

/** Which notice sheet is open. */
private sealed interface NoticeSheetState {
    data object New : NoticeSheetState
    data class Edit(val notice: Notice) : NoticeSheetState
}

@Composable
fun AdminNoticesScreen(navigator: KavachNavigator) {
    val vm: AdminNoticesViewModel = hiltViewModel()
    MessageEffect(vm.messages.flow)

    val notices by vm.notices.collectAsStateWithLifecycle()
    val rowBusy by vm.rowBusy.collectAsStateWithLifecycle()
    val reduceMotion = rememberReduceMotion()
    var sheet by remember { mutableStateOf<NoticeSheetState?>(null) }

    KavachScaffold(
        title = stringResource(R.string.nav_notices),
        subtitle = stringResource(R.string.admin_notices_subtitle),
        onBack = navigator::back,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = hapticClick({ sheet = NoticeSheetState.New }),
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.admin_new_notice)) },
            )
        },
    ) { padding ->
        LoadableContent(notices, vm::retry, Modifier.fillMaxSize().padding(padding)) { list ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.sm, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (list.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Rounded.Campaign,
                            title = stringResource(R.string.admin_no_notices),
                            text = stringResource(R.string.admin_no_notices_text),
                        )
                    }
                }
                items(list, key = { it.id }) { notice ->
                    NoticeCard(
                        notice = notice,
                        busy = rowBusy == notice.id,
                        onClick = hapticClick({ sheet = NoticeSheetState.Edit(notice) }),
                        onTogglePin = { vm.togglePin(notice) },
                        // pinning moves a notice to the top: let it slide there
                        modifier = if (reduceMotion) Modifier else Modifier.animateItem(),
                    )
                }
            }
        }
    }

    sheet?.let { open ->
        NoticeSheet(
            existing = (open as? NoticeSheetState.Edit)?.notice,
            vm = vm,
            onDismiss = { sheet = null },
        )
    }
}

@Composable
private fun NoticeCard(
    notice: Notice,
    busy: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pinLabel = stringResource(
        if (notice.pinned) R.string.admin_unpin_notice_name else R.string.admin_pin_notice_name,
        notice.title,
    )
    KavachCard(modifier = modifier, onClick = hapticClick(onClick), contentPadding = PaddingValues(start = Spacing.md, top = Spacing.sm, bottom = Spacing.sm, end = Spacing.xs)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(notice.title, style = MaterialTheme.typography.titleMedium)
                val meta = buildList {
                    if (notice.pinned) add(stringResource(R.string.admin_pinned_badge))
                    notice.authorName?.takeIf { it.isNotBlank() }?.let { add(it) }
                    add(Format.dateTime(notice.createdAt))
                    notice.expiresAt?.let { add(stringResource(R.string.admin_expires_on, Format.shortDate(it))) }
                }.joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
                if (notice.body.isNotBlank()) {
                    Text(
                        notice.body,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = hapticClick(onTogglePin), enabled = !busy) {
                Icon(
                    if (notice.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                    contentDescription = pinLabel,
                    tint = if (notice.pinned) KavachTheme.colors.primary else KavachTheme.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun NoticeSheet(existing: Notice?, vm: AdminNoticesViewModel, onDismiss: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val serverError by vm.error.collectAsStateWithLifecycle()
    LaunchedEffect(existing?.id) { vm.clearError() }

    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var body by remember { mutableStateOf(existing?.body.orEmpty()) }
    var pinned by remember { mutableStateOf(existing?.pinned ?: false) }
    var expiry by remember { mutableStateOf(expiryDate(existing?.expiresAt)) }
    var titleError by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    AdminSheet(
        title = stringResource(if (existing == null) R.string.admin_new_notice else R.string.admin_edit_notice),
        onDismiss = onDismiss,
        busy = busy,
        footer = {
            SheetButtons(
                confirmLabel = stringResource(if (existing == null) R.string.admin_post_notice else R.string.common_save),
                onConfirm = {
                    if (title.isBlank()) {
                        titleError = true
                    } else {
                        vm.save(existing, title, body, pinned, expiry, onDismiss)
                    }
                },
                onCancel = onDismiss,
                busy = busy,
            )
        },
    ) {
        LimitedField(
            value = title,
            onValueChange = { title = it; titleError = false },
            label = stringResource(R.string.admin_notice_title),
            max = NOTICE_TITLE_MAX,
            placeholder = stringResource(R.string.admin_notice_title_placeholder),
            errorText = if (titleError) stringResource(R.string.admin_err_title_required) else null,
        )
        LimitedField(
            value = body,
            onValueChange = { body = it },
            label = stringResource(R.string.admin_notice_body),
            max = NOTICE_BODY_MAX,
            minLines = 5,
            placeholder = stringResource(R.string.admin_notice_body_placeholder),
        )
        SwitchRow(
            title = stringResource(R.string.admin_pin_to_top),
            checked = pinned,
            onCheckedChange = { pinned = it },
            hint = stringResource(if (pinned) R.string.admin_pinned_hint else R.string.admin_not_pinned_hint),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button) { pickingDate = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.admin_expires_optional), style = MaterialTheme.typography.bodyLarge)
                Text(
                    expiry?.let { Format.longDate(it) } ?: stringResource(R.string.admin_expires_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = KavachTheme.colors.textMuted,
                )
            }
            if (expiry != null) {
                TextButton(onClick = hapticClick({ expiry = null })) { Text(stringResource(R.string.admin_clear_date)) }
            }
        }
        serverError?.let {
            Text(stringResource(it), color = KavachTheme.colors.danger, style = MaterialTheme.typography.bodyMedium)
        }
        if (existing != null) {
            TextButton(onClick = hapticClick({ confirmDelete = true }), enabled = !busy) {
                Text(stringResource(R.string.common_delete), color = KavachTheme.colors.danger)
            }
        }
    }

    if (pickingDate) {
        ExpiryPickerDialog(
            initial = expiry,
            onPick = { expiry = it },
            onDismiss = { pickingDate = false },
        )
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = stringResource(R.string.admin_delete_notice_title),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                confirmDelete = false
                vm.delete(existing, onDismiss)
            },
            onDismiss = { confirmDelete = false },
            text = stringResource(R.string.admin_delete_notice_text, existing.title),
            destructive = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiryPickerDialog(initial: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val todayUtc = remember { LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= todayUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = hapticClick({
                    state.selectedDateMillis?.let { ms ->
                        onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    onDismiss()
                }),
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = { TextButton(onClick = hapticClick(onDismiss)) { Text(stringResource(R.string.common_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}
