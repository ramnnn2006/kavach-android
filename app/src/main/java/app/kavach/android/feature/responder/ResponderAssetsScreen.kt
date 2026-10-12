package app.kavach.android.feature.responder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.ToneBadge
import app.kavach.android.core.ui.components.icon
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.format.rememberNow
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId

/** Responder "Assets" tab (Assets.jsx): equipment I cover, by tower, tap to change its status. */
@Composable
fun ResponderAssetsScreen(navigator: KavachNavigator, viewModel: ResponderAssetsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val edit by viewModel.edit.collectAsStateWithLifecycle()
    val now by rememberNow(60_000)

    MessageEffect(viewModel.messages.flow)
    FeedbackEffect(viewModel.feedback)

    KavachScaffold(
        title = stringResource(R.string.nav_assets),
        subtitle = specialtySummary(ui.profile),
    ) { padding ->
        LiveContent(
            state = ui.assets,
            onRetry = viewModel::retry,
            modifier = Modifier.fillBelowBar(padding),
        ) { data ->
            AssetsList(data = data, now = now, onEdit = viewModel::openEdit)
        }
    }

    val editing = edit.target?.let { id -> ui.assets.data?.find(id) }
    if (editing != null) {
        AssetDialog(
            asset = editing,
            now = now,
            busy = edit.busy,
            errorRes = edit.error,
            onSave = { state, note, label -> viewModel.save(editing, state, note, label) },
            onDismiss = viewModel::dismissEdit,
        )
    }
}

@Composable
private fun AssetsList(data: AssetsData, now: Instant, onEdit: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (data.isEmpty) {
            item(key = "empty") {
                KavachCard {
                    EmptyState(
                        icon = Icons.Rounded.Settings,
                        title = stringResource(R.string.responder_assets_empty_title),
                        text = stringResource(R.string.responder_assets_empty_text),
                    )
                }
            }
        }
        if (data.attention > 0) {
            item(key = "attention") {
                InfoBanner(
                    Icons.Rounded.Warning,
                    KavachTheme.colors.orange,
                    pluralStringResource(R.plurals.responder_need_attention, data.attention, data.attention),
                )
            }
        }
        for (group in data.groups) {
            item(key = "header-${group.key}") {
                SectionHeader(group.name ?: stringResource(R.string.responder_other_zone))
            }
            item(key = "group-${group.key}") {
                KavachCard(contentPadding = PaddingValues(0.dp)) {
                    group.items.forEachIndexed { index, asset ->
                        if (index > 0) HorizontalDivider(color = KavachTheme.colors.separator)
                        AssetRow(asset, now, onClick = { onEdit(asset.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetRow(asset: Asset, now: Instant, onClick: () -> Unit) {
    val today = remember(now) { now.atZone(ZoneId.systemDefault()).toLocalDate() }
    val warnings = remember(asset, today) { expiryWarnings(asset, today) }
    val kindLine = listOfNotNull(stringResource(asset.kind.labelRes()), asset.vendor?.takeIf { it.isNotBlank() })
        .joinToString(" · ")
    // Working is the normal case: plain grey. Only problems get a colour (the label always says it)
    val stateTone = if (asset.state == AssetState.Ok) KavachTheme.colors.gray else KavachTheme.colors.assetState(asset.state)

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.responder_edit_asset, asset.name), onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(asset.kind.icon(), KavachTheme.colors.assetKind(asset.kind))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(asset.name, style = MaterialTheme.typography.bodyLarge)
            Text(kindLine, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
            for (warning in warnings) {
                Text(
                    expiryText(warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning.expired) KavachTheme.colors.red else KavachTheme.colors.orange,
                )
            }
            asset.notes?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = KavachTheme.colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (asset.stateChangedAt != null) {
                Text(
                    assetChangedLine(asset, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = KavachTheme.colors.textTertiary,
                )
            }
        }
        ToneBadge(stringResource(asset.state.labelRes()), stateTone)
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = KavachTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun expiryText(warning: ExpiryWarning): String {
    val date = Format.shortDate(warning.date)
    val res = when (warning.kind) {
        ExpiryKind.Amc -> if (warning.expired) R.string.responder_amc_expired else R.string.responder_amc_expires
        ExpiryKind.Licence -> if (warning.expired) R.string.responder_licence_expired else R.string.responder_licence_expires
    }
    return stringResource(res, date)
}
