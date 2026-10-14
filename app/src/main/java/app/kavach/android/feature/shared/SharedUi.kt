package app.kavach.android.feature.shared

import app.kavach.android.core.ui.components.hapticClick

import androidx.compose.ui.res.painterResource
import app.kavach.android.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/**
 * Frame for the screens shown before the role shell (splash, login, setup, welcome): system-bar
 * and keyboard aware, centred, scrollable, one comfortable column.
 */
@Composable
internal fun AuthFrame(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                content = content,
            )
        }
    }
}

/** The Kavach mark: a shield on the primary colour. */
@Composable
internal fun AppMark(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    Icon(
        painter = painterResource(R.drawable.ic_brand),
        contentDescription = null,
        tint = androidx.compose.ui.graphics.Color.Unspecified,
        modifier = modifier.size(size),
    )
}

/** First letter of the name in a circle (the website's Avatar). */
@Composable
internal fun InitialAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(KavachTheme.colors.primary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = MaterialTheme.typography.titleLarge, color = KavachTheme.colors.primary)
    }
}

/** A row with a title, optional hint and a switch. The whole row toggles; one switch semantic. */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!hint.isNullOrBlank()) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Small muted explanation under a group (the website's footnote). */
@Composable
internal fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = KavachTheme.colors.textMuted,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs),
    )
}

/** Full-width button with an in-button spinner while [busy]. [tone] overrides the container colour. */
@Composable
internal fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    tone: Color? = null,
) {
    val colors = if (tone != null) {
        ButtonDefaults.buttonColors(containerColor = tone, contentColor = KavachTheme.colors.onAccent)
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = hapticClick(onClick),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        enabled = enabled && !busy,
        colors = colors,
    ) {
        if (busy) {
            CircularProgressIndicator(
                // TalkBack still names the button while it works
                modifier = Modifier.size(20.dp).semantics { contentDescription = text },
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        } else {
            Text(text)
        }
    }
}

/**
 * A read-only text field that opens a menu of [options] (labels) when tapped. [onSelect] gets the
 * index of the chosen option.
 */
@Composable
internal fun PickerField(
    label: String,
    valueText: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    errorText: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Box {
            OutlinedTextField(
                value = valueText,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) },
                isError = errorText != null,
                enabled = enabled,
                singleLine = true,
                // TalkBack reads the overlay below instead (one node: label, value, drop-down)
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            )
            // Catches the tap on the whole field (a read-only field would only focus)
            Box(
                Modifier
                    .matchParentSize()
                    .semantics {
                        contentDescription = label
                        if (valueText.isNotEmpty()) stateDescription = valueText
                    }
                    .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = hapticClick({
                            expanded = false
                            onSelect(index)
                        }),
                    )
                }
            }
        }
        if (errorText != null) {
            Text(
                errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = Spacing.md, top = Spacing.xs),
            )
        }
    }
}
