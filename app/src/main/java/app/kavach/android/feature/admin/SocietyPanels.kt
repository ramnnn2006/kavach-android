package app.kavach.android.feature.admin

import app.kavach.android.core.ui.components.hapticClick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.data.model.ComplianceItem
import app.kavach.android.core.data.model.ComplianceStatus
import app.kavach.android.core.data.model.Contact
import app.kavach.android.core.data.model.ContactKind
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.ConfirmDialog
import app.kavach.android.core.ui.components.icon
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.format.rememberNow
import app.kavach.android.core.ui.format.timeAgo
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate

// Society → Assets, Compliance, Contacts (parts/AssetsPanel.jsx, CompliancePanel.jsx, ContactsPanel.jsx).

// ── Assets ──────────────────────────────────────────────────────────────────

@Composable
internal fun AssetsPanel(vm: AdminSocietyViewModel) {
    val assets by vm.assets.collectAsStateWithLifecycle()
    val zones by vm.zones.collectAsStateWithLifecycle()
    val now by rememberNow(60_000)
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    val list = assets.data.orEmpty()
    val groups = remember(assets.data, zones.data) { groupAssets(list, zones.data.orEmpty()) }
    val notOk = assetsNotOk(list)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = ListPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        statusItem(assets, "assets", vm::retry)
        if (assets.data != null) {
            item(key = "summary") {
                Text(
                    if (notOk > 0) stringResource(R.string.admin_assets_not_ok, notOk, list.size)
                    else stringResource(R.string.admin_assets_all_ok, list.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (notOk > 0) KavachTheme.colors.warning else KavachTheme.colors.textMuted,
                )
            }
            if (list.isEmpty()) {
                item(key = "empty") {
                    KavachCard {
                        EmptyState(
                            icon = Icons.Rounded.Build,
                            title = stringResource(R.string.admin_no_assets),
                            text = stringResource(R.string.admin_no_assets_text),
                        )
                    }
                }
            }
        }
        groups.forEach { group ->
            item(key = "group-${group.key}") { SectionHeader(group.zoneName ?: stringResource(R.string.admin_no_zone)) }
            items(group.items, key = { "asset-${it.id}" }) { asset ->
                AssetRow(asset, now, onClick = hapticClick({ editingId = asset.id }))
            }
        }
    }

    list.firstOrNull { it.id == editingId }?.let { asset ->
        AssetSheet(asset, vm, onDismiss = { editingId = null })
    }
}

@Composable
private fun AssetRow(asset: Asset, now: Instant, onClick: () -> Unit) {
    val context = LocalContext.current
    val colors = KavachTheme.colors
    val notOk = asset.state != AssetState.Ok
    val stateColor = colors.assetState(asset.state)
    val stateLabel = stringResource(asset.state.labelRes())
    val rest = buildString {
        asset.vendor?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        if (notOk && asset.stateChangedAt != null) {
            append(" · ").append(
                context.getString(R.string.admin_changed_ago, Format.timeAgo(context, asset.stateChangedAt, now)),
            )
        }
    }
    KavachCard(onClick = hapticClick(onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(asset.kind.icon(), colors.assetKind(asset.kind))
            Column(Modifier.weight(1f)) {
                Text(asset.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildAnnotatedString {
                        if (notOk) withStyle(SpanStyle(color = stateColor)) { append(stateLabel) } else append(stateLabel)
                        append(rest)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                if (notOk && !asset.notes.isNullOrBlank()) {
                    Text(asset.notes, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
            ChevronIcon()
        }
    }
}

@Composable
private fun AssetSheet(asset: Asset, vm: AdminSocietyViewModel, onDismiss: () -> Unit) {
    val busySet by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy = "asset" in busySet
    var state by remember { mutableStateOf(asset.state) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(asset.id) { vm.clearError() }

    val stateLabels = AssetState.ORDER.associateWith { stringResource(it.labelRes()) }
    val subtitle = listOfNotNull(stringResource(asset.kind.labelRes()), asset.zone?.name, asset.vendor)
        .filter { it.isNotBlank() }.joinToString(" · ")

    AdminSheet(
        title = asset.name,
        subtitle = subtitle,
        onDismiss = onDismiss,
        busy = busy,
        footer = {
            SheetButtons(
                confirmLabel = stringResource(R.string.common_save),
                onConfirm = { vm.saveAsset(asset, state, note, stateLabels.getValue(state), onDismiss) },
                onCancel = onDismiss,
                busy = busy,
                enabled = state != asset.state || note.isNotBlank(),
            )
        },
    ) {
        Text(stringResource(R.string.admin_asset_state), style = MaterialTheme.typography.titleSmall, color = KavachTheme.colors.textMuted)
        AssetState.ORDER.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = state == option, role = Role.RadioButton, onClick = hapticClick({ state = option })),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape).background(KavachTheme.colors.assetState(option)),
                        )
                        Text(stateLabels.getValue(option), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        stringResource(assetHintRes(option)),
                        style = MaterialTheme.typography.bodySmall,
                        color = KavachTheme.colors.textMuted,
                    )
                }
                RadioButton(selected = state == option, onClick = null)
            }
        }
        if (!asset.notes.isNullOrBlank()) {
            Text(
                stringResource(R.string.admin_current_note, asset.notes),
                style = MaterialTheme.typography.bodySmall,
                color = KavachTheme.colors.textMuted,
            )
        }
        LimitedField(
            value = note,
            onValueChange = { note = it },
            label = stringResource(R.string.admin_asset_note),
            max = 500,
            minLines = 3,
        )
        Text(
            stringResource(R.string.admin_asset_note_hint),
            style = MaterialTheme.typography.bodySmall,
            color = KavachTheme.colors.textMuted,
        )
        error?.let { Text(stringResource(it), color = KavachTheme.colors.danger, style = MaterialTheme.typography.bodyMedium) }
    }
}

private fun assetHintRes(state: AssetState): Int = when (state) {
    AssetState.Ok -> R.string.admin_asset_hint_ok
    AssetState.Degraded -> R.string.admin_asset_hint_degraded
    AssetState.Down -> R.string.admin_asset_hint_down
    AssetState.Maintenance -> R.string.admin_asset_hint_maintenance
}

// ── Compliance ──────────────────────────────────────────────────────────────

@Composable
internal fun CompliancePanel(vm: AdminSocietyViewModel) {
    val checks by vm.compliance.collectAsStateWithLifecycle()
    val assets by vm.assets.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var pendingDone by remember { mutableStateOf<ComplianceItem?>(null) }

    val list = checks.data.orEmpty()
    val groups = remember(list) { complianceGroups(list) }
    val renewalList = remember(assets.data) { renewals(assets.data.orEmpty(), LocalDate.now()) }
    val needsWork = list.count { it.complianceStatus != ComplianceStatus.Ok }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = ListPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        statusItem(checks, "checks", vm::retry)
        if (checks.data != null && list.isEmpty()) {
            item(key = "empty") {
                KavachCard {
                    EmptyState(
                        icon = Icons.Rounded.Checklist,
                        title = stringResource(R.string.admin_no_checks),
                        text = stringResource(R.string.admin_no_checks_text),
                    )
                }
            }
        }
        if (list.isNotEmpty() && needsWork == 0) {
            item(key = "all-clear") {
                Text(
                    stringResource(R.string.admin_compliance_all_clear),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                )
            }
        }
        groups.forEach { (status, rows) ->
            item(key = "cg-${status.wire}") {
                SectionHeader("${stringResource(status.labelRes())} · ${rows.size}")
            }
            items(rows, key = { "check-${it.id}" }) { check ->
                CheckRow(check, running = "check:${check.id}" in busy, onMarkDone = { pendingDone = check })
            }
        }

        item(key = "renewals-head") { SectionHeader(stringResource(R.string.admin_renewals)) }
        statusItem(assets, "renewals", vm::retry)
        if (assets.data != null && renewalList.isEmpty()) {
            item(key = "renewals-empty") {
                Text(
                    stringResource(R.string.admin_no_renewals, RENEWAL_WINDOW_DAYS.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                )
            }
        }
        items(renewalList, key = { "renewal-${it.key}" }) { renewal ->
            val colors = KavachTheme.colors
            val past = renewal.days < 0
            KavachCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(renewal.asset.kind.icon(), colors.assetKind(renewal.asset.kind))
                    Column(Modifier.weight(1f)) {
                        Text(renewal.asset.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(
                                stringResource(if (renewal.kind == RenewalKind.Amc) R.string.admin_renewal_amc else R.string.admin_renewal_licence),
                                renewal.asset.vendor?.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                        )
                        Text(
                            stringResource(
                                if (past) R.string.admin_expired_on else R.string.admin_expires_on,
                                Format.shortDate(renewal.date),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (past) colors.danger else colors.warning,
                        )
                    }
                }
            }
        }
    }

    pendingDone?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.admin_mark_done_title, item.title),
            confirmLabel = stringResource(R.string.admin_mark_done),
            onConfirm = {
                pendingDone = null
                vm.markDone(item)
            },
            onDismiss = { pendingDone = null },
            text = stringResource(R.string.admin_mark_done_text, item.intervalDays),
        )
    }
}

@Composable
private fun CheckRow(check: ComplianceItem, running: Boolean, onMarkDone: () -> Unit) {
    val colors = KavachTheme.colors
    val overdue = check.complianceStatus == ComplianceStatus.Overdue
    val due = dueKind(check)?.let { dueText(it) }
    val doneLabel = stringResource(R.string.admin_mark_done_name, check.title)
    KavachCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(
                check.assetKind?.icon() ?: Icons.Rounded.Checklist,
                check.assetKind?.let { colors.assetKind(it) } ?: colors.gray,
            )
            Column(Modifier.weight(1f)) {
                Text(check.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        (check.assetName ?: check.zoneName),
                        stringResource(R.string.admin_due_on, Format.shortDate(check.dueAt)),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
                if (due != null) {
                    Text(due, style = MaterialTheme.typography.bodyMedium, color = if (overdue) colors.danger else colors.warning)
                }
                Text(
                    if (check.lastDoneAt != null) {
                        stringResource(R.string.admin_last_done, Format.shortDate(check.lastDoneAt))
                    } else {
                        stringResource(R.string.admin_never_done)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            if (running) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                TextButton(
                    onClick = hapticClick(onMarkDone),
                    modifier = Modifier.semantics { contentDescription = doneLabel },
                ) { Text(stringResource(R.string.admin_mark_done)) }
            }
        }
    }
}

@Composable
private fun dueText(kind: DueKind): String = when (kind) {
    DueKind.OverdueOneDay -> stringResource(R.string.admin_day_overdue)
    is DueKind.OverdueDays -> stringResource(R.string.admin_days_overdue, kind.n)
    DueKind.Today -> stringResource(R.string.admin_due_today)
    DueKind.Tomorrow -> stringResource(R.string.admin_due_tomorrow)
    is DueKind.InDays -> stringResource(R.string.admin_due_in_days, kind.n)
}

// ── Contacts ────────────────────────────────────────────────────────────────

@Composable
internal fun ContactsPanel(vm: AdminSocietyViewModel) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Contact?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var roleLabel by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var kind by remember { mutableStateOf(ContactKind.Society) }
    var nameError by remember { mutableStateOf(false) }
    var phoneError by remember { mutableStateOf(false) }

    val list = contacts.data.orEmpty()
    val kinds = ContactKind.entries.filter { k -> list.any { it.kind == k } }
    val kindOptions = ContactKind.entries.map { PickerOption(it, stringResource(contactKindRes(it))) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = ListPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        statusItem(contacts, "contacts", vm::retry)
        if (contacts.data != null && list.isEmpty()) {
            item(key = "empty") {
                KavachCard {
                    EmptyState(
                        icon = Icons.Rounded.Call,
                        title = stringResource(R.string.admin_no_contacts),
                        text = stringResource(R.string.admin_no_contacts_text),
                    )
                }
            }
        }
        kinds.forEachIndexed { index, k ->
            item(key = "ck-${k.wire}") {
                SectionHeader(
                    stringResource(contactKindRes(k)),
                    trailing = if (index == 0) {
                        {
                            val editLabel = stringResource(if (editing) R.string.common_done else R.string.admin_m_edit_contacts)
                            TextButton(
                                onClick = hapticClick({ editing = !editing }),
                                modifier = Modifier.semantics { contentDescription = editLabel },
                            ) {
                                Text(stringResource(if (editing) R.string.common_done else R.string.common_edit))
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            items(list.filter { it.kind == k }, key = { "contact-${it.id}" }) { contact ->
                KavachCard(contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (editing) {
                            IconButton(
                                onClick = hapticClick({ pendingDelete = contact }),
                                enabled = "contact:${contact.id}" !in busy,
                            ) {
                                Icon(
                                    Icons.Rounded.RemoveCircle,
                                    contentDescription = stringResource(R.string.admin_delete_contact_name, contact.name),
                                    tint = KavachTheme.colors.danger,
                                )
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = if (editing) 0.dp else Spacing.sm)) {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium)
                            val meta = listOfNotNull(contact.roleLabel, contact.available).filter { it.isNotBlank() }.joinToString(" · ")
                            if (meta.isNotEmpty()) {
                                Text(meta, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
                            }
                            Text(contact.phone, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
                        }
                        if (!editing) CallIconButton(contact.phone, contact.name)
                    }
                }
            }
        }

        item(key = "add-head") { SectionHeader(stringResource(R.string.admin_add_contact)) }
        item(key = "add-form") {
            KavachCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    LimitedField(
                        value = name,
                        onValueChange = { name = it; nameError = false },
                        label = stringResource(R.string.common_name),
                        max = 80,
                        singleLine = true,
                        placeholder = stringResource(R.string.admin_contact_name_placeholder),
                        errorText = if (nameError) stringResource(R.string.common_err_name_required) else null,
                        showCounter = false,
                    )
                    LimitedField(
                        value = roleLabel,
                        onValueChange = { roleLabel = it },
                        label = stringResource(R.string.admin_contact_role),
                        max = 80,
                        singleLine = true,
                        placeholder = stringResource(R.string.admin_contact_role_placeholder),
                        showCounter = false,
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { if (it.length <= 20) { phone = it; phoneError = false } },
                        label = { Text(stringResource(R.string.common_phone)) },
                        singleLine = true,
                        isError = phoneError,
                        supportingText = if (phoneError) {
                            { Text(stringResource(R.string.common_err_phone_number)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PickerField(
                        label = stringResource(R.string.admin_contact_kind),
                        selectedText = stringResource(contactKindRes(kind)),
                        options = kindOptions,
                        onSelect = { kind = it },
                    )
                    val adding = "contact-add" in busy
                    Button(
                        onClick = hapticClick({
                            nameError = name.isBlank()
                            phoneError = !isValidContactPhone(phone)
                            if (!nameError && !phoneError) {
                                vm.addContact(name, roleLabel, phone, kind) {
                                    name = ""
                                    roleLabel = ""
                                    phone = ""
                                    kind = ContactKind.Society
                                }
                            }
                        }),
                        enabled = !adding,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (adding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.admin_add_contact), modifier = Modifier.padding(start = if (adding) Spacing.sm else 0.dp))
                    }
                }
            }
        }
    }

    pendingDelete?.let { contact ->
        ConfirmDialog(
            title = stringResource(R.string.admin_delete_contact_title, contact.name),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                pendingDelete = null
                vm.deleteContact(contact)
            },
            onDismiss = { pendingDelete = null },
            text = stringResource(R.string.admin_delete_contact_text),
            destructive = true,
        )
    }
}

private fun contactKindRes(kind: ContactKind): Int = when (kind) {
    ContactKind.Society -> R.string.admin_contact_kind_society
    ContactKind.Emergency -> R.string.admin_contact_kind_emergency
}
