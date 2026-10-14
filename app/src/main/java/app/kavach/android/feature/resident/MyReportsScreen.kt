package app.kavach.android.feature.resident

import app.kavach.android.core.ui.components.hapticClick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.nav.incidentSharedKey
import app.kavach.android.core.nav.sharedBoundsFor
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.LoadableContent
import app.kavach.android.core.ui.components.SecondaryButton
import app.kavach.android.core.ui.format.rememberNow
import app.kavach.android.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MyReportsViewModel @Inject constructor(
    session: SessionManager,
    incidents: IncidentRepository,
) : ViewModel() {
    val profile: StateFlow<Profile?> = session.profile

    private val tick = MutableStateFlow(0)
    fun retry() { tick.value += 1 }

    val reports: StateFlow<Loadable<List<Incident>>> = tick
        .flatMapLatest { incidents.listenMyIncidents() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable<List<Incident>>())

    private val _showAll = MutableStateFlow(false)
    /** false = the "Active" tab (default), true = "All". */
    val showAll: StateFlow<Boolean> = _showAll.asStateFlow()
    fun showAll(all: Boolean) { _showAll.value = all }
}

/** My reports: Active / All, active ones first, newest first. */
@Composable
fun MyReportsScreen(navigator: KavachNavigator) {
    val vm: MyReportsViewModel = hiltViewModel()
    val state by vm.reports.collectAsStateWithLifecycle()
    val showAll by vm.showAll.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val now by rememberNow()

    KavachScaffold(title = stringResource(R.string.resident_reports_title)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                FilterChip(
                    selected = !showAll,
                    onClick = hapticClick({ vm.showAll(false) }),
                    label = { Text(stringResource(R.string.resident_tab_active)) },
                )
                FilterChip(
                    selected = showAll,
                    onClick = hapticClick({ vm.showAll(true) }),
                    label = { Text(stringResource(R.string.resident_tab_all)) },
                )
            }
            LoadableContent(state, vm::retry, Modifier.weight(1f)) { reports ->
                // Re-sorted only when the list or the tab changes, not on every clock tick
                val list = remember(reports, showAll) {
                    // "All" is newest first, as on the website (Reports.jsx); "Active" filters it
                    if (showAll) reports else ResidentRules.activeReports(reports)
                }
                if (list.isEmpty()) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Rounded.ListAlt,
                        title = stringResource(if (showAll) R.string.resident_no_reports_title else R.string.resident_no_active_title),
                        text = stringResource(if (showAll) R.string.resident_no_reports_text else R.string.resident_no_active_text),
                        action = {
                            SecondaryButton(
                                text = stringResource(R.string.resident_report_emergency),
                                onClick = hapticClick({ navigator.navigate(Routes.ReportType) }),
                            )
                        },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        items(list, key = { it.id }) { incident ->
                            IncidentRow(
                                incident = incident,
                                profile = profile,
                                now = now,
                                onClick = hapticClick({ navigator.navigate(Routes.Tracker(incident.id)) }),
                                modifier = reorderModifier().sharedBoundsFor(incidentSharedKey(incident.id)),
                            )
                        }
                    }
                }
            }
        }
    }
}
