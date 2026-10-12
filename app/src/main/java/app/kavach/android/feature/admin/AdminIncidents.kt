package app.kavach.android.feature.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.repo.IncidentFilters
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.repo.SocietyRepository
import app.kavach.android.core.data.repo.StatusFilter
import app.kavach.android.core.domain.Rules
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.LoadableContent
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.rememberReduceMotion
import app.kavach.android.core.ui.format.rememberNow
import app.kavach.android.core.ui.format.shortLabelRes
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
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AdminIncidentsViewModel @Inject constructor(
    incidentRepo: IncidentRepository,
    societyRepo: SocietyRepository,
) : ViewModel() {
    private val retryTick = MutableStateFlow(0)
    fun retry() { retryTick.value = retryTick.value + 1 }

    private val _filters = MutableStateFlow(IncidentFilterState())
    val filters: StateFlow<IncidentFilterState> = _filters

    fun setStatus(status: StatusChoice) { _filters.value = _filters.value.copy(status = status) }
    fun setZone(zoneId: String?) { _filters.value = _filters.value.copy(zoneId = zoneId) }
    fun toggleType(type: IncidentType) {
        val current = _filters.value.types
        _filters.value = _filters.value.copy(types = if (type in current) current - type else current + type)
    }
    fun clearFilters() { _filters.value = _filters.value.copy(types = emptySet(), zoneId = null) }

    /** Status and tower are queried on the server; the type filter is applied on screen. */
    val incidents: StateFlow<Loadable<List<Incident>>> = combine(
        _filters.map { it.status to it.zoneId }.distinctUntilChanged(),
        retryTick,
    ) { query, _ -> query }
        .flatMapLatest { (status, zoneId) ->
            incidentRepo.listenList(
                IncidentFilters(
                    status = when (status) {
                        StatusChoice.Active -> StatusFilter.Active
                        StatusChoice.Closed -> StatusFilter.Closed
                        StatusChoice.All -> StatusFilter.All
                    },
                    zoneId = zoneId,
                ),
            )
        }
        // Previous rows stay on screen while a new filter loads (website keepStale)
        .inStateKeepingData(viewModelScope)

    /** The rows shown: type filter + urgency sort, worked out off the main thread. Null until loaded. */
    val rows: StateFlow<List<Incident>?> = combine(
        incidents.map { it.data }.distinctUntilChanged(),
        _filters,
    ) { data, filter -> data?.let { visibleIncidents(it, filter) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val zones: StateFlow<Loadable<List<Zone>>> =
        retryTick.flatMapLatest { societyRepo.listenZones() }.inStateKeepingData(viewModelScope)
}

@Composable
fun AdminIncidentsScreen(navigator: KavachNavigator) {
    val vm: AdminIncidentsViewModel = hiltViewModel()
    val assignVm: AssignViewModel = hiltViewModel()
    MessageEffect(assignVm.messages.flow)

    val filters by vm.filters.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val zones by vm.zones.collectAsStateWithLifecycle()
    val now by rememberNow(30_000)
    val reduce = rememberReduceMotion()
    var assigningId by rememberSaveable { mutableStateOf<String?>(null) }

    KavachScaffold(
        title = stringResource(R.string.nav_incidents),
        subtitle = rows?.let { stringResource(R.string.admin_showing_n, it.size) },
    ) { padding ->
        LoadableContent(incidents, vm::retry, Modifier.fillMaxSize().padding(padding)) {
            // Null for a moment after new data arrives, while the rows are worked out
            val shown = rows
            val visible = shown.orEmpty()
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = ListPadding,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item(key = "filters") {
                    FilterBar(
                        filters = filters,
                        zones = zones.data.orEmpty(),
                        onStatus = vm::setStatus,
                        onToggleType = vm::toggleType,
                        onZone = vm::setZone,
                        onClear = vm::clearFilters,
                    )
                }
                if (shown != null && shown.isEmpty()) {
                    item(key = "empty") {
                        val active = filters.status == StatusChoice.Active
                        EmptyState(
                            icon = Icons.Rounded.Checklist,
                            title = stringResource(if (active) R.string.admin_no_active_incidents else R.string.admin_no_incidents),
                            text = stringResource(
                                when {
                                    filters.hasFilters -> R.string.admin_no_incidents_filtered
                                    active -> R.string.admin_no_active_text
                                    else -> R.string.admin_no_incidents_text
                                },
                            ),
                            action = when {
                                filters.hasFilters -> ({
                                    OutlinedButton(onClick = vm::clearFilters) { Text(stringResource(R.string.admin_clear_filters)) }
                                })
                                active -> ({
                                    OutlinedButton(onClick = { vm.setStatus(StatusChoice.All) }) {
                                        Text(stringResource(R.string.admin_show_all))
                                    }
                                })
                                else -> null
                            },
                        )
                    }
                }
                items(visible, key = { it.id }, contentType = { "incident" }) { incident ->
                    val assignLabel = stringResource(R.string.admin_assign_incident, stringResource(incident.type.shortLabelRes()))
                    IncidentRow(
                        incident = incident,
                        now = now,
                        onClick = { navigator.navigate(Routes.IncidentDetail(incident.id)) },
                        modifier = if (reduce) Modifier else Modifier.animateItem(),
                        action = if (incident.isActive) {
                            {
                                val assigned = incident.assignedTo != null
                                val text = stringResource(if (assigned) R.string.admin_reassign else R.string.admin_assign)
                                if (assigned) {
                                    OutlinedButton(
                                        onClick = { assigningId = incident.id },
                                        modifier = Modifier.semantics { contentDescription = assignLabel },
                                    ) { Text(text) }
                                } else {
                                    Button(
                                        onClick = { assigningId = incident.id },
                                        modifier = Modifier.semantics { contentDescription = assignLabel },
                                    ) { Text(text) }
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    val assigning = incidents.data.orEmpty().firstOrNull { it.id == assigningId }
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
private fun FilterBar(
    filters: IncidentFilterState,
    zones: List<Zone>,
    onStatus: (StatusChoice) -> Unit,
    onToggleType: (IncidentType) -> Unit,
    onZone: (String?) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SegmentedPicker(
            options = listOf(
                PickerOption(StatusChoice.Active, stringResource(R.string.admin_filter_active)),
                PickerOption(StatusChoice.Closed, stringResource(R.string.admin_filter_closed)),
                PickerOption(StatusChoice.All, stringResource(R.string.admin_filter_all)),
            ),
            selected = filters.status,
            onSelect = onStatus,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Rules.TYPE_ORDER.forEach { type ->
                FilterChip(
                    selected = type in filters.types,
                    onClick = { onToggleType(type) },
                    label = { Text(stringResource(type.shortLabelRes())) },
                )
            }
        }
        val allPlaces = stringResource(R.string.admin_all_places)
        val selectedZone = zones.firstOrNull { it.id == filters.zoneId }
        PickerField(
            label = stringResource(R.string.admin_tower_filter),
            selectedText = selectedZone?.name ?: allPlaces,
            options = listOf(PickerOption<String?>(null, allPlaces)) + zones.map { PickerOption<String?>(it.id, it.name) },
            onSelect = onZone,
        )
        if (filters.hasFilters) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.admin_clear_filters)) }
        }
    }
}
