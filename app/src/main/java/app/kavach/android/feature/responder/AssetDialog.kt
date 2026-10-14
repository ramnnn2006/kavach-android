package app.kavach.android.feature.responder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.format.timeAgo
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import java.time.Instant

/** "Changed 2 h ago by Ravi" / "Changed 2 h ago" / "No changes recorded yet". */
@Composable
internal fun assetChangedLine(asset: Asset, now: Instant): String {
    val at = asset.stateChangedAt ?: return stringResource(R.string.responder_never_changed)
    val ago = timeAgo(at, now)
    val name = asset.changedBy?.fullName?.takeIf { it.isNotBlank() }
    return if (name != null) {
        stringResource(R.string.responder_changed_by, ago, name)
    } else {
        stringResource(R.string.responder_changed_ago, ago)
    }
}

/** Change an asset's state (Working / Partly working / Not working / Under maintenance) with an optional note. */
@Composable
internal fun AssetDialog(
    asset: Asset,
    now: Instant,
    busy: Boolean,
    errorRes: Int?,
    onSave: (state: AssetState, note: String, stateLabel: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var state by rememberSaveable(asset.id) { mutableStateOf(asset.state) }
    var note by rememberSaveable(asset.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(asset.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    assetChangedLine(asset, now),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                )
                Text(stringResource(R.string.responder_asset_state_label), style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    for (option in AssetState.ORDER) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = state == option, enabled = !busy, role = Role.RadioButton) { state = option },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            RadioButton(selected = state == option, onClick = null, enabled = !busy)
                            Text(stringResource(option.labelRes()), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                asset.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.responder_asset_note_label)) },
                    placeholder = { Text(stringResource(R.string.responder_asset_note_placeholder)) },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !busy,
                )
                DialogError(errorRes)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(state, note.trim(), context.getString(state.labelRes())) },
                enabled = !busy,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
