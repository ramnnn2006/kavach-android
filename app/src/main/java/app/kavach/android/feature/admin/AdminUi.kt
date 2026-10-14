package app.kavach.android.feature.admin

import app.kavach.android.core.ui.components.hapticClick

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.nav.incidentSharedKey
import app.kavach.android.core.nav.sharedBoundsFor
import app.kavach.android.core.domain.Rules
import app.kavach.android.core.ui.components.ErrorBanner
import app.kavach.android.core.ui.components.ErrorState
import app.kavach.android.core.ui.components.EscalationBadge
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.StatusBadge
import app.kavach.android.core.ui.components.ToneBadge
import app.kavach.android.core.ui.components.TypeIcon
import app.kavach.android.core.ui.components.UrgencyBadge
import app.kavach.android.core.ui.components.dial
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.format.placeLine
import app.kavach.android.core.ui.format.shortLabelRes
import app.kavach.android.core.ui.format.timeAgo
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

// Small building blocks shared by the admin screens (parts/ui.jsx).

/** Default padding of scrolling lists. */
internal val ListPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.sm)

internal const val ATTENTION_LIMIT = 8

/** `repo.listenX()` as a StateFlow that keeps the last value for 5 s after the screen leaves. */
internal fun <T> Flow<Loadable<T>>.inState(scope: CoroutineScope): StateFlow<Loadable<T>> =
    stateIn(scope, SharingStarted.WhileSubscribed(5_000), Loadable())

/**
 * Like [inState], but keeps the last loaded data while the query restarts (coming back to the
 * screen after the 5 s timeout, a retry, or a new filter): the live query starts again with no
 * data, which would otherwise swap the list for a full-screen spinner and lose the scroll
 * position. A finished load with no data and no error (e.g. no active check) is passed through.
 */
internal fun <T> Flow<Loadable<T>>.inStateKeepingData(scope: CoroutineScope): StateFlow<Loadable<T>> {
    var lastData: T? = null
    return map { next: Loadable<T> ->
        val data = next.data
        val kept = lastData
        val out: Loadable<T> = when {
            data != null -> {
                lastData = data
                next
            }
            !next.loading && next.error == null -> {
                lastData = null
                next
            }
            kept != null -> Loadable(data = kept, error = next.error, loading = next.loading)
            else -> next
        }
        out
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), Loadable())
}

/** Keep the previous data on screen while a new query (filter change) loads. */
internal fun <T> keepStale(previous: Loadable<List<T>>, next: Loadable<List<T>>): Loadable<List<T>> =
    if (next.data == null && previous.data != null) {
        Loadable(data = previous.data, error = next.error, loading = next.loading)
    } else {
        next
    }

// ── Sheets ──────────────────────────────────────────────────────────────────

/** Bottom sheet frame: title, close button, scrolling body, optional footer buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    busy: Boolean = false,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val busyNow by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> !(busyNow && target == SheetValue.Hidden) },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!busyNow) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
                    }
                }
                IconButton(onClick = hapticClick(onDismiss), enabled = !busy) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                content = content,
            )
            if (footer != null) {
                Row(
                    Modifier.fillMaxWidth().padding(Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer,
                )
            }
        }
    }
}

/** Cancel + primary button for [AdminSheet]'s footer. */
@Composable
internal fun RowScope.SheetButtons(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    busy: Boolean,
    enabled: Boolean = true,
) {
    TextButton(onClick = hapticClick(onCancel), enabled = !busy) { Text(stringResource(R.string.common_cancel)) }
    Button(onClick = hapticClick(onConfirm), enabled = enabled && !busy) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(confirmLabel)
    }
}

// ── Pickers and form rows ───────────────────────────────────────────────────

internal data class PickerOption<T>(val value: T, val label: String)

/** Read-only field that opens a menu (a native "select"). */
@Composable
internal fun <T> PickerField(
    label: String,
    selectedText: String,
    options: List<PickerOption<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    errorText: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            isError = errorText != null,
            supportingText = errorText?.let { { Text(it) } },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .semantics { contentDescription = "$label: $selectedText ${errorText.orEmpty()}" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = hapticClick({
                        open = false
                        onSelect(option.value)
                    }),
                )
            }
        }
    }
}

/** Compact value + arrow button that opens a menu (zone power state). */
@Composable
internal fun <T> MenuButton(
    text: String,
    options: List<PickerOption<T>>,
    onSelect: (T) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(
            onClick = hapticClick({ open = true }),
            enabled = enabled,
            modifier = Modifier.semantics { this.contentDescription = contentDescription },
        ) {
            Text(text, color = color)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = color)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = hapticClick({
                        open = false
                        onSelect(option.value)
                    }),
                )
            }
        }
    }
}

/** Segmented control with one option selected. */
@Composable
internal fun <T> SegmentedPicker(
    options: List<PickerOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option.value == selected,
                onClick = hapticClick({ onSelect(option.value) }),
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                enabled = enabled,
                icon = {},
                label = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** Title + hint on the left, switch on the right; the whole row toggles. */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Text field with a character counter and a hard length limit. */
@Composable
internal fun LimitedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    max: Int,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    singleLine: Boolean = false,
    placeholder: String? = null,
    errorText: String? = null,
    showCounter: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= max) onValueChange(it) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = errorText != null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        supportingText = when {
            errorText != null -> ({ Text(errorText) })
            showCounter -> ({ Text(stringResource(R.string.admin_chars_left, max - value.length)) })
            else -> null
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Preset buttons + one checkbox per incident type (MemberSheet "Handles these alerts"). */
@Composable
internal fun SpecialtyPicker(
    selected: List<IncidentType>,
    onChange: (List<IncidentType>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(R.string.admin_handles),
            style = MaterialTheme.typography.titleSmall,
            color = KavachTheme.colors.textMuted,
            modifier = Modifier.semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(
                selected = presetActive(selected, Rules.PRESET_MAINTENANCE),
                onClick = hapticClick({ onChange(orderedSpecialties(Rules.PRESET_MAINTENANCE)) }),
                label = { Text(stringResource(R.string.common_preset_maintenance)) },
            )
            FilterChip(
                selected = presetActive(selected, Rules.PRESET_SECURITY),
                onClick = hapticClick({ onChange(orderedSpecialties(Rules.PRESET_SECURITY)) }),
                label = { Text(stringResource(R.string.common_preset_security)) },
            )
        }
        Rules.TYPE_ORDER.forEach { type ->
            val checked = type in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onChange(toggleSpecialty(selected, type)) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TypeIcon(type, Modifier.clearAndSetSemantics { }, 32.dp)
                Text(stringResource(type.labelRes()), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Checkbox(checked = checked, onCheckedChange = null)
            }
        }
    }
}

// ── Small display pieces ────────────────────────────────────────────────────

@Composable
internal fun InitialsAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val initials = name.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(KavachTheme.colors.primary.copy(alpha = 0.15f))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = MaterialTheme.typography.labelLarge, color = KavachTheme.colors.primary)
    }
}

/** "On duty" / "Off duty" with a dot (the word is always there, colour is only a hint). */
@Composable
internal fun DutyDot(on: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (on) KavachTheme.colors.green else KavachTheme.colors.gray),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(if (on) R.string.admin_on_duty else R.string.admin_off_duty),
            style = MaterialTheme.typography.bodySmall,
            color = if (on) KavachTheme.colors.success else KavachTheme.colors.textMuted,
        )
    }
}

/** Specialties as plain text ("Lift, Power, Water"). */
@Composable
internal fun specialtiesText(types: List<IncidentType>): String {
    val context = LocalContext.current
    return types.joinToString(", ") { context.getString(it.shortLabelRes()) }
}

/** Phone icon button (opens the dialler); nothing when there is no number. */
@Composable
internal fun CallIconButton(phone: String?, name: String?, modifier: Modifier = Modifier) {
    if (phone.isNullOrBlank()) return
    val context = LocalContext.current
    IconButton(onClick = hapticClick({ context.dial(phone) }), modifier = modifier) {
        Icon(
            Icons.Rounded.Call,
            contentDescription = stringResource(R.string.common_call_number, name?.takeIf { it.isNotBlank() } ?: phone),
            tint = KavachTheme.colors.primary,
        )
    }
}

@Composable
internal fun ChevronIcon() {
    Icon(
        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = null,
        tint = KavachTheme.colors.textTertiary,
    )
}

/** Card whose rows sit edge to edge (put [ListRow]s and dividers inside). */
@Composable
internal fun GroupedCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    KavachCard(modifier = modifier, contentPadding = PaddingValues(0.dp), content = content)
}

/** One incident as a card (Mail-style): type, time, place, status, who, urgency, flags. */
@Composable
internal fun IncidentRow(
    incident: Incident,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val active = incident.isActive
    val escalated = active && incident.isEscalated
    KavachCard(
        modifier = modifier.sharedBoundsFor(incidentSharedKey(incident.id)),
        onClick = hapticClick(onClick),
        accent = if (escalated) KavachTheme.colors.red else null,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TypeIcon(incident.type)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        stringResource(incident.type.labelRes()),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        if (active) timeAgo(incident.createdAt, now) else Format.dateTime(incident.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = KavachTheme.colors.textMuted,
                    )
                }
                val place = placeLine(incident)
                val reporter = incident.reporterName?.takeIf { it.isNotBlank() }
                Text(
                    if (reporter != null) "$place · $reporter" else place,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KavachTheme.colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatusBadge(incident.status, staff = true)
                    if (active) UrgencyBadge(incident.urgencyScore)
                }
                val assignee = incident.assignedName?.takeIf { it.isNotBlank() }
                Text(
                    assignee ?: stringResource(R.string.admin_unassigned),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (assignee == null && active) KavachTheme.colors.warning else KavachTheme.colors.textMuted,
                )
                if (escalated || incident.vulnerable) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (escalated) EscalationBadge(incident.escalationLevel)
                        if (incident.vulnerable) ToneBadge(stringResource(R.string.admin_vulnerable), KavachTheme.colors.orange)
                    }
                }
            }
        }
        if (action != null) {
            Spacer(Modifier.size(Spacing.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { action() }
        }
    }
}

internal fun LazyListScope.loadingItem(key: String) {
    item(key = "$key-loading") {
        Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

/** Spinner / error / stale-data banner for one [Loadable] shown inside a LazyColumn section. */
internal fun LazyListScope.statusItem(state: Loadable<*>, key: String, onRetry: () -> Unit) {
    val error = state.error
    when {
        state.data == null && error != null -> item(key = "$key-error") { ErrorState(error, onRetry) }
        state.data == null -> loadingItem(key)
        error != null -> item(key = "$key-banner") { ErrorBanner(error, onRetry) }
    }
}
