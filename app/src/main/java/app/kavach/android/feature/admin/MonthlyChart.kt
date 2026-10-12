package app.kavach.android.feature.admin

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.ui.format.shortLabelRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/** "1 incident" / "6 incidents". */
internal fun incidentsText(context: Context, n: Long): String =
    if (n == 1L) context.getString(R.string.admin_incidents_one) else context.getString(R.string.admin_incidents_n, n)

private fun breakdownText(context: Context, bar: MonthBar, present: List<IncidentType>): String =
    present.filter { bar.count(it) > 0 }
        .joinToString(", ") { "${context.getString(it.shortLabelRes())} ${bar.count(it)}" }

/** What a screen reader says for one month column. */
private fun monthDescription(context: Context, bar: MonthBar, present: List<IncidentType>): String {
    val breakdown = breakdownText(context, bar, present)
    return "${monthLongLabel(bar.month)}: ${incidentsText(context, bar.total)}" +
        if (breakdown.isNotEmpty()) " ($breakdown)" else ""
}

/**
 * Stacked bars, one column per month, drawn with a Canvas (MonthlyChart.jsx). The text legend and
 * the "Show as table" list repeat the numbers, so nothing depends on colour alone.
 */
@Composable
internal fun MonthlyChart(bars: List<MonthBar>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = KavachTheme.colors
    val present = remember(bars) { presentTypes(bars) }
    val max = remember(bars) { maxOf(1L, bars.maxOfOrNull { it.total } ?: 0L) }
    var showTable by rememberSaveable { mutableStateOf(false) }
    val chartLabel = stringResource(R.string.admin_monthly_chart_label, bars.size)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(
            Modifier.fillMaxWidth().semantics { contentDescription = chartLabel },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { bar ->
                MonthColumn(
                    bar = bar,
                    present = present,
                    max = max,
                    description = remember(bar, present) { monthDescription(context, bar, present) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Legend: swatch + type name, three per line
        present.chunked(3).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                line.forEach { type ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(colors.type(type)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(type.shortLabelRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }

        TextButton(onClick = { showTable = !showTable }) {
            Text(stringResource(if (showTable) R.string.admin_hide_table else R.string.admin_show_as_table))
        }
        if (showTable) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                bars.forEach { bar ->
                    val breakdown = breakdownText(context, bar, present)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            monthLongLabel(bar.month),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (breakdown.isEmpty()) bar.total.toString() else "$breakdown · ${bar.total}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1.5f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthColumn(
    bar: MonthBar,
    present: List<IncidentType>,
    max: Long,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = KavachTheme.colors
    // Segment counts and colours in stacking order, prepared once so drawing allocates nothing
    val counts = remember(bar, present) { LongArray(present.size) { bar.count(present[it]) } }
    val segmentColors = remember(present, colors) { present.map { colors.type(it) } }
    val baseline = colors.separator
    val gap = MaterialTheme.colorScheme.surfaceContainer
    Column(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (bar.total > 0) bar.total.toString() else " ",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val barWidth = size.width * 0.6f
            val left = (size.width - barWidth) / 2f
            val unit = size.height / max.toFloat()
            var bottom = size.height
            for (i in counts.indices) {
                val count = counts[i]
                if (count <= 0L) continue
                val h = count * unit
                drawRect(
                    color = segmentColors[i],
                    topLeft = Offset(left, bottom - h),
                    size = Size(barWidth, h),
                )
                // thin separator so neighbouring segments stay distinguishable
                drawLine(
                    color = gap,
                    start = Offset(left, bottom - h),
                    end = Offset(left + barWidth, bottom - h),
                    strokeWidth = 1.dp.toPx(),
                )
                bottom -= h
            }
            drawLine(
                color = baseline,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        Text(monthShortLabel(bar.month), style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
    }
}
