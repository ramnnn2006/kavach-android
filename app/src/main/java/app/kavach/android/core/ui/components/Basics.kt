package app.kavach.android.core.ui.components

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/**
 * Screen frame: top app bar (title, optional back arrow, actions) + content. The bottom tab bar
 * and snackbars belong to the app shell, not to screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KavachScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        if (subtitle != null) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        content = content,
    )
}

/** Grouped-list section title ("ACTIVE", "Today"…). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = Spacing.xs, end = Spacing.xs, top = Spacing.md, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = KavachTheme.colors.textMuted,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** White (dark: #1C1C1E) rounded card on the grouped background. [accent] draws a left stripe. */
@Composable
fun KavachCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val body: @Composable () -> Unit = {
        // The stripe is drawn, not laid out: a fillMaxHeight() box has no height in a lazy list
        // (unbounded constraints) and would stretch the card in a bounded one
        val stripe = if (accent == null) Modifier else Modifier
            .drawBehind {
                val w = AccentStripe.toPx()
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
                drawRect(accent, topLeft = Offset(x, 0f), size = Size(w, size.height))
            }
            .padding(start = AccentStripe)
        Column(Modifier.fillMaxWidth().then(stripe).padding(contentPadding), content = content)
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors) { body() }
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors) { body() }
    }
}

/** One row of a grouped list: leading icon, title, subtitle, trailing content. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = Spacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
            }
        }
        trailing?.invoke()
    }
}

/** Tinted rounded square with an icon (type-icon on the website). */
@Composable
fun IconTile(icon: ImageVector, tone: Color, modifier: Modifier = Modifier, size: Dp = 40.dp, contentDescription: String? = null) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(size / 4)).background(tone.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tone, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun TypeIcon(type: IncidentType, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    IconTile(type.icon(), KavachTheme.colors.type(type), modifier, size, contentDescription = stringResource(type.labelRes()))
}

/**
 * Text in a tone, blended toward the label colour (website `.badge`/`.stat__value`:
 * color-mix(tone 55%, text)) so orange, green, teal… stay readable (AA) in light and dark mode.
 */
@Composable
internal fun toneText(tone: Color): Color = lerp(tone, MaterialTheme.colorScheme.onSurface, 0.45f)

/** Small pill with tinted background. */
@Composable
fun ToneBadge(text: String, tone: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clip(CircleShape).background(tone.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp),
        color = toneText(tone),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun StatusBadge(status: IncidentStatus, staff: Boolean, modifier: Modifier = Modifier) {
    ToneBadge(stringResource(status.labelRes(staff)), KavachTheme.colors.status(status), modifier)
}

@Composable
fun UrgencyBadge(score: Int, modifier: Modifier = Modifier) {
    ToneBadge(stringResource(R.string.common_urgency_n, score), KavachTheme.colors.urgency(score), modifier)
}

/** "Escalated · level 2" with a pulsing dot (static when motion is reduced). */
@Composable
fun EscalationBadge(level: Int, modifier: Modifier = Modifier) {
    val tone = KavachTheme.colors.red
    Row(
        modifier = modifier.clip(CircleShape).background(tone.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PulsingDot(tone, size = 8.dp)
        Text(
            stringResource(R.string.common_escalated_level, level),
            color = toneText(tone),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Big number + label (Stat). */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, tone: Color = Color.Unspecified, onClick: (() -> Unit)? = null) {
    KavachCard(modifier = modifier, onClick = onClick) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = if (tone.isSpecified) toneText(tone) else tone)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
    }
}

private val AccentStripe = 4.dp
