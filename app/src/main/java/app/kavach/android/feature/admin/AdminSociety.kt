package app.kavach.android.feature.admin

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.data.model.ComplianceItem
import app.kavach.android.core.data.model.Contact
import app.kavach.android.core.data.model.ContactKind
import app.kavach.android.core.data.model.PowerEvent
import app.kavach.android.core.data.model.PowerSource
import app.kavach.android.core.data.model.PowerState
import app.kavach.android.core.data.model.PriorityTier
import app.kavach.android.core.data.model.Society
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.data.repo.AssetRepository
import app.kavach.android.core.data.repo.ContactRepository
import app.kavach.android.core.data.repo.SocietyRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.ui.components.ConfirmDialog
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.MessageQueue
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.ToneBadge
import app.kavach.android.core.ui.components.UiMessage
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The four sections of the Society screen. */
internal enum class SocietyTab(@StringRes val label: Int) {
    Power(R.string.admin_tab_power),
    Assets(R.string.admin_tab_assets),
    Compliance(R.string.admin_tab_compliance),
    Contacts(R.string.admin_tab_contacts),
}

/** Lets another admin screen open Society on a given section (the website's `#compliance`). */
internal object SocietyTabHint {
    private val _requested = MutableStateFlow<SocietyTab?>(null)
    val requested: StateFlow<SocietyTab?> = _requested
    fun request(tab: SocietyTab) { _requested.value = tab }
    fun clear() { _requested.value = null }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AdminSocietyViewModel @Inject constructor(
    private val societyRepo: SocietyRepository,
    private val assetRepo: AssetRepository,
    private val contactRepo: ContactRepository,
    private val session: SessionManager,
) : ViewModel() {
    val messages = MessageQueue()

    private val retryTick = MutableStateFlow(0)
    fun retry() { retryTick.value = retryTick.value + 1 }

    val society: StateFlow<Loadable<Society?>> =
        retryTick.flatMapLatest { societyRepo.listenSociety() }.inState(viewModelScope)
    val zones: StateFlow<Loadable<List<Zone>>> =
        retryTick.flatMapLatest { societyRepo.listenZones() }.inState(viewModelScope)
    val powerEvents: StateFlow<Loadable<List<PowerEvent>>> =
        retryTick.flatMapLatest { societyRepo.listenPowerEvents(8) }.inState(viewModelScope)
    val assets: StateFlow<Loadable<List<Asset>>> =
        retryTick.flatMapLatest { assetRepo.listenAssets() }.inState(viewModelScope)
    val compliance: StateFlow<Loadable<List<ComplianceItem>>> =
        retryTick.flatMapLatest { assetRepo.listenCompliance() }.inState(viewModelScope)
    val contacts: StateFlow<Loadable<List<Contact>>> =
        retryTick.flatMapLatest { contactRepo.listenContacts() }.inState(viewModelScope)

    /** Keys of the actions in flight ("source", "zone:<id>", "check:<id>", "asset", "contact-add", "contact:<id>"). */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy

    /** String resource of the last failure (shown inside the asset sheet). */
    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error
    fun clearError() { _error.value = null }

    private fun launchAction(key: String, onDone: () -> Unit = {}, block: suspend () -> UiMessage?) {
        if (key in _busy.value) return
        _busy.update { it + key }
        _error.value = null
        viewModelScope.launch {
            try {
                val message = block()
                if (message != null) messages.send(message)
                onDone()
            } catch (e: KavachException) {
                _error.value = e.messageRes()
                messages.error(e)
            } finally {
                _busy.update { it - key }
            }
        }
    }

    fun switchSource(next: PowerSource, note: String, onDone: () -> Unit) = launchAction("source", onDone) {
        societyRepo.setPowerSource(next, note.trim())
        UiMessage(if (next == PowerSource.Dg) R.string.admin_now_on_dg else R.string.admin_now_on_grid)
    }

    fun setZonePower(zone: Zone, state: PowerState, stateLabel: String) = launchAction("zone:${zone.id}") {
        societyRepo.setZonePower(zone.id, state)
        UiMessage(R.string.admin_zone_power_set, listOf(zone.name, stateLabel))
    }

    fun saveAsset(asset: Asset, state: AssetState, note: String, stateLabel: String, onDone: () -> Unit) =
        launchAction("asset", onDone) {
            assetRepo.setStatus(asset.id, state, note.trim())
            UiMessage(R.string.admin_asset_saved, listOf(asset.name, stateLabel))
        }

    fun markDone(item: ComplianceItem) = launchAction("check:${item.id}") {
        assetRepo.logCheck(item.id)
        UiMessage(R.string.admin_marked_done, listOf(item.title))
    }

    fun addContact(name: String, roleLabel: String, phone: String, kind: ContactKind, onDone: () -> Unit) {
        val societyId = session.profile.value?.societyId
        if (societyId == null) {
            messages.send(R.string.common_error_forbidden)
            return
        }
        val sortOrder = (contacts.value.data.orEmpty().filter { it.kind == kind }.maxOfOrNull { it.sortOrder } ?: 0) + 1
        launchAction("contact-add", onDone) {
            contactRepo.add(
                societyId = societyId,
                name = name,
                phone = phone,
                kind = kind,
                roleLabel = roleLabel.ifBlank { null },
                available = null,
                sortOrder = sortOrder,
            )
            UiMessage(R.string.admin_contact_added, listOf(name.trim()))
        }
    }

    fun deleteContact(contact: Contact) = launchAction("contact:${contact.id}") {
        contactRepo.delete(contact.id)
        UiMessage(R.string.admin_contact_deleted, listOf(contact.name))
    }
}

@Composable
fun AdminSocietyScreen(navigator: KavachNavigator) {
    val vm: AdminSocietyViewModel = hiltViewModel()
    MessageEffect(vm.messages.flow)

    val society by vm.society.collectAsStateWithLifecycle()
    val requested by SocietyTabHint.requested.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    // Consume the hint once: it becomes the saved tab and is cleared so it can't apply again later
    LaunchedEffect(requested) {
        requested?.let {
            tabIndex = it.ordinal
            SocietyTabHint.clear()
        }
    }
    val tabs = SocietyTab.entries
    // The requested tab shows from the first frame (no flash of the previously saved tab)
    val current = requested ?: tabs[tabIndex.coerceIn(0, tabs.lastIndex)]

    KavachScaffold(title = stringResource(R.string.nav_society), subtitle = society.data?.name) { padding ->
        // imePadding: the note and "Add contact" fields sit low in their lists
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            ScrollableTabRow(selectedTabIndex = current.ordinal, edgePadding = Spacing.screen) {
                tabs.forEach { tab ->
                    Tab(
                        selected = tab == current,
                        onClick = { tabIndex = tab.ordinal },
                        text = { Text(stringResource(tab.label)) },
                    )
                }
            }
            when (current) {
                SocietyTab.Power -> PowerPanel(vm)
                SocietyTab.Assets -> AssetsPanel(vm)
                SocietyTab.Compliance -> CompliancePanel(vm)
                SocietyTab.Contacts -> ContactsPanel(vm)
            }
        }
    }
}

// ── Power ───────────────────────────────────────────────────────────────────

@Composable
internal fun PowerPanel(vm: AdminSocietyViewModel) {
    val society by vm.society.collectAsStateWithLifecycle()
    val zones by vm.zones.collectAsStateWithLifecycle()
    val events by vm.powerEvents.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var note by rememberSaveable { mutableStateOf("") }
    var pendingSource by remember { mutableStateOf<PowerSource?>(null) }
    var pendingOff by remember { mutableStateOf<Zone?>(null) }

    val source = society.data?.powerSource ?: PowerSource.Grid
    val onDG = source == PowerSource.Dg
    val stateLabels = PowerState.entries.associateWith { stringResource(it.labelRes()) }
    val zoneStateOptions = PowerState.entries.map { PickerOption(it, stateLabels.getValue(it)) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = ListPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        statusItem(society, "society", vm::retry)
        if (society.data != null) {
            item(key = "source") {
                KavachCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(Icons.Rounded.Bolt, if (onDG) KavachTheme.colors.orange else KavachTheme.colors.green, size = 48.dp)
                        Column {
                            Text(
                                stringResource(R.string.admin_power_source),
                                style = MaterialTheme.typography.bodySmall,
                                color = KavachTheme.colors.textMuted,
                            )
                            Text(
                                stringResource(if (onDG) R.string.common_power_source_dg else R.string.common_power_source_grid),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SegmentedPicker(
                            options = listOf(
                                PickerOption(PowerSource.Grid, stringResource(R.string.admin_grid)),
                                PickerOption(PowerSource.Dg, stringResource(R.string.admin_backup_dg)),
                            ),
                            selected = source,
                            onSelect = { if (it != source) pendingSource = it },
                            enabled = "source" !in busy,
                        )
                        LimitedField(
                            value = note,
                            onValueChange = { note = it },
                            label = stringResource(R.string.admin_switch_note),
                            max = 200,
                            singleLine = true,
                            placeholder = stringResource(R.string.admin_switch_note_placeholder),
                            showCounter = false,
                        )
                        Text(
                            stringResource(R.string.admin_switch_note_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = KavachTheme.colors.textMuted,
                        )
                        Text(
                            stringResource(R.string.admin_tier_rule),
                            style = MaterialTheme.typography.bodySmall,
                            color = KavachTheme.colors.textMuted,
                        )
                    }
                }
            }
        }

        item(key = "zones-head") { SectionHeader(stringResource(R.string.admin_zones_by_tier)) }
        statusItem(zones, "zones", vm::retry)
        PriorityTier.entries.forEach { tier ->
            val inTier = zones.data.orEmpty().filter { it.tier == tier }
            if (inTier.isNotEmpty()) {
                item(key = "tier-${tier.wire}") {
                    Row(
                        Modifier.padding(top = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        ToneBadge(tier.wire, KavachTheme.colors.tier(tier))
                        Text(stringResource(tier.labelRes()), style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(inTier, key = { "zone-${it.id}" }) { zone ->
                    val tone = KavachTheme.colors.power(zone.powerState)
                    val label = stateLabels.getValue(zone.powerState)
                    KavachCard(contentPadding = PaddingValues(start = Spacing.md, top = 4.dp, bottom = 4.dp, end = Spacing.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(zone.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            MenuButton(
                                text = label,
                                options = zoneStateOptions,
                                onSelect = { state ->
                                    if (state != zone.powerState) {
                                        if (state == PowerState.Off) pendingOff = zone
                                        else vm.setZonePower(zone, state, stateLabels.getValue(state))
                                    }
                                },
                                contentDescription = stringResource(R.string.admin_zone_power_for, zone.name),
                                enabled = "zone:${zone.id}" !in busy,
                                color = tone,
                            )
                        }
                    }
                }
            }
        }

        item(key = "events-head") { SectionHeader(stringResource(R.string.admin_recent_switches)) }
        statusItem(events, "events", vm::retry)
        if (events.data != null && events.data.orEmpty().isEmpty()) {
            item(key = "events-empty") {
                KavachCard {
                    EmptyState(
                        icon = Icons.Rounded.History,
                        title = stringResource(R.string.admin_no_switches),
                        text = stringResource(R.string.admin_no_switches_text),
                    )
                }
            }
        }
        items(events.data.orEmpty(), key = { "event-${it.id}" }) { event ->
            KavachCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(
                        Icons.Rounded.Bolt,
                        if (event.source == PowerSource.Dg) KavachTheme.colors.orange else KavachTheme.colors.green,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (event.source == PowerSource.Dg) R.string.admin_switched_to_dg else R.string.admin_switched_to_grid),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            listOfNotNull(event.by?.fullName, Format.dateTime(event.switchedAt)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = KavachTheme.colors.textMuted,
                        )
                        if (!event.note.isNullOrBlank()) Text(event.note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    pendingSource?.let { next ->
        val toDG = next == PowerSource.Dg
        ConfirmDialog(
            title = stringResource(if (toDG) R.string.admin_confirm_dg_title else R.string.admin_confirm_grid_title),
            confirmLabel = stringResource(if (toDG) R.string.admin_switch_to_dg else R.string.admin_switch_to_grid),
            onConfirm = {
                pendingSource = null
                vm.switchSource(next, note) { note = "" }
            },
            onDismiss = { pendingSource = null },
            text = stringResource(if (toDG) R.string.admin_confirm_dg_text else R.string.admin_confirm_grid_text),
            destructive = toDG,
        )
    }
    pendingOff?.let { zone ->
        ConfirmDialog(
            title = stringResource(R.string.admin_confirm_zone_off_title, zone.name),
            confirmLabel = stringResource(R.string.admin_turn_off),
            onConfirm = {
                pendingOff = null
                vm.setZonePower(zone, PowerState.Off, stateLabels.getValue(PowerState.Off))
            },
            onDismiss = { pendingOff = null },
            text = stringResource(R.string.admin_confirm_zone_off_text),
            destructive = true,
        )
    }
}
