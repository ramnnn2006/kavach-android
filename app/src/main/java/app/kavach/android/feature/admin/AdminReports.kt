package app.kavach.android.feature.admin

import app.kavach.android.core.ui.components.hapticClick

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.kavach.android.core.data.model.ReportBundle
import app.kavach.android.core.data.model.ReportByAssetRow
import app.kavach.android.core.data.model.ReportByZoneRow
import app.kavach.android.core.data.model.ReportSummaryRow
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.repo.ReportRepository
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.LoadableContent
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.StatTile
import app.kavach.android.core.ui.components.TypeIcon
import app.kavach.android.core.ui.components.icon
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.shortLabelRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.scan
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AdminReportsViewModel @Inject constructor(
    repo: ReportRepository,
) : ViewModel() {
    private val retryTick = MutableStateFlow(0)
    fun retry() { retryTick.value = retryTick.value + 1 }

    private val _days = MutableStateFlow(30)
    val days: StateFlow<Int> = _days
    fun setDays(days: Int) { _days.value = days }

    /** The report for the chosen period; the previous one stays while a new period loads. */
    val report: StateFlow<Loadable<ReportBundle>> = combine(_days, retryTick) { days, _ -> days }
        .flatMapLatest { days -> repo.listenReports(days, REPORT_MONTHS) }
        .scan(Loadable<ReportBundle>()) { previous, next ->
            if (next.data == null && previous.data != null) {
                Loadable(data = previous.data, error = next.error, loading = next.loading)
            } else {
                next
            }
        }
        .inState(viewModelScope)
}

@Composable
fun AdminReportsScreen(navigator: KavachNavigator) {
    val vm: AdminReportsViewModel = hiltViewModel()
    val days by vm.days.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val data = report.data

    KavachScaffold(
        title = stringResource(R.string.nav_insights),
        subtitle = data?.let { stringResource(R.string.admin_range_line, Format.shortDate(it.from), Format.shortDate(it.to)) },
        onBack = navigator::back,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SegmentedPicker(
                options = REPORT_PERIODS.map { PickerOption(it, stringResource(R.string.admin_last_n_days, it)) },
                selected = days,
                onSelect = vm::setDays,
                modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            )
            // The previous period stays on screen while the new one loads: say so
            if (data != null && report.loading && periodDays(data) != days) {
                val loadingLabel = stringResource(R.string.admin_m_loading_period)
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screen)
                        .semantics { contentDescription = loadingLabel },
                )
            }
            LoadableContent(report, vm::retry, Modifier.fillMaxSize()) { bundle ->
                ReportContent(bundle, periodDays(bundle), onReviewCompliance = {
                    SocietyTabHint.request(SocietyTab.Compliance)
                    navigator.openTab(Routes.AdminSociety)
                })
            }
        }
    }
}

/** Length of the period a loaded report covers (its [ReportBundle.from]..[ReportBundle.to]). */
private fun periodDays(bundle: ReportBundle): Int = Duration.between(bundle.from, bundle.to).toDays().toInt()

@Composable
private fun ReportContent(bundle: ReportBundle, days: Int, onReviewCompliance: () -> Unit) {
    val context = LocalContext.current
    val totals = remember(bundle) { reportTotals(bundle.summary) }
    val repeats = remember(bundle) { repeatProblems(bundle.byAsset) }
    val zones = remember(bundle) { busyZones(bundle.byZone) }
    val bars = remember(bundle) { monthBars(bundle.monthly, REPORT_MONTHS, LocalDate.now()) }
    val monthlyTotal = bars.sumOf { it.total }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = ListPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "totals") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatTile("${totals.total}", stringResource(R.string.admin_total_incidents), Modifier.weight(1f))
                    StatTile("${totals.resolved}", stringResource(R.string.admin_resolved), Modifier.weight(1f), KavachTheme.colors.success)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatTile("${totals.escalated}", stringResource(R.string.admin_escalated), Modifier.weight(1f), KavachTheme.colors.red)
                    StatTile("${totals.cancelled}", stringResource(R.string.admin_cancelled), Modifier.weight(1f))
                }
            }
        }

        if (totals.total == 0L) {
            item(key = "no-data") {
                KavachCard {
                    EmptyState(
                        icon = Icons.Rounded.BarChart,
                        title = stringResource(R.string.admin_no_data_title, days),
                        text = stringResource(R.string.admin_no_data_text),
                        action = {
                            OutlinedButton(onClick = hapticClick(onReviewCompliance)) { Text(stringResource(R.string.admin_review_compliance)) }
                        },
                    )
                }
            }
        } else {
            item(key = "by-type-head") { SectionHeader(stringResource(R.string.admin_by_type)) }
            item(key = "by-type") {
                GroupedCard {
                    bundle.summary.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider()
                        TypeSummaryRow(row)
                    }
                }
            }
            item(key = "by-type-note") {
                Text(
                    stringResource(R.string.admin_by_type_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = KavachTheme.colors.textMuted,
                )
            }

            item(key = "repeat-head") { SectionHeader(stringResource(R.string.admin_repeat_problems)) }
            if (repeats.isEmpty()) {
                item(key = "repeat-none") {
                    Text(
                        stringResource(R.string.admin_no_repeat),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KavachTheme.colors.textMuted,
                    )
                }
            } else {
                item(key = "repeat") {
                    GroupedCard {
                        repeats.forEachIndexed { index, row ->
                            if (index > 0) HorizontalDivider()
                            RepeatRow(row, context)
                        }
                    }
                }
            }

            item(key = "zones-head") { SectionHeader(stringResource(R.string.admin_by_zone)) }
            if (zones.isEmpty()) {
                item(key = "zones-none") {
                    Text(
                        stringResource(R.string.admin_no_zone_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KavachTheme.colors.textMuted,
                    )
                }
            } else {
                item(key = "zones") {
                    val zoneMax = zones.maxOf { it.incidents }.coerceAtLeast(1L)
                    KavachCard {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            zones.forEach { zone -> ZoneBar(zone, zoneMax, context) }
                        }
                    }
                }
            }
        }

        item(key = "monthly-head") { SectionHeader(stringResource(R.string.admin_last_n_months, REPORT_MONTHS)) }
        item(key = "monthly") {
            KavachCard {
                if (monthlyTotal == 0L) {
                    Text(
                        stringResource(R.string.admin_no_monthly_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KavachTheme.colors.textMuted,
                    )
                } else {
                    MonthlyChart(bars)
                }
            }
        }
    }
}

@Composable
private fun TypeSummaryRow(row: ReportSummaryRow) {
    val context = LocalContext.current
    fun avg(v: Double?): String = Format.duration(context, v?.let { Math.round(it) })
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TypeIcon(row.type, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(row.type.shortLabelRes()), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.admin_type_counts, row.total, row.resolved, row.cancelled, row.escalated),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.admin_type_averages, avg(row.avgClaimMinutes), avg(row.avgResolveMinutes)),
                style = MaterialTheme.typography.bodySmall,
                color = KavachTheme.colors.textMuted,
            )
        }
    }
}

@Composable
private fun RepeatRow(row: ReportByAssetRow, context: Context) {
    val meta = listOfNotNull(
        row.zoneName,
        row.avgResolveMinutes?.let { context.getString(R.string.admin_avg_min, Format.duration(context, Math.round(it))) },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(row.kind.icon(), KavachTheme.colors.assetKind(row.kind))
        Column(Modifier.weight(1f)) {
            Text(row.assetName, style = MaterialTheme.typography.bodyLarge)
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
        }
        Text(
            incidentsText(context, row.incidents),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTheme.colors.textMuted,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ZoneBar(zone: ReportByZoneRow, max: Long, context: Context) {
    val fraction = share(zone.incidents, max)
    val detail = incidentsText(context, zone.incidents) +
        (zone.avgResolveMinutes?.let { " · " + context.getString(R.string.admin_avg_min, Format.duration(context, Math.round(it))) } ?: "")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(zone.zoneName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(KavachTheme.colors.fill)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(KavachTheme.colors.primary))
        }
    }
}
