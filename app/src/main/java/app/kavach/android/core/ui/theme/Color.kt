package app.kavach.android.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.kavach.android.core.data.model.AssetKind
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.data.model.ComplianceStatus
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.PowerState
import app.kavach.android.core.data.model.PriorityTier

/**
 * Kavach colours (kavach-app/src/styles/tokens.css). Material roles come from MaterialTheme;
 * these are the extra system colours and semantic tones. Read with `KavachTheme.colors`.
 */
@Immutable
data class KavachColors(
    val blue: Color,
    val red: Color,
    val orange: Color,
    val yellow: Color,
    val green: Color,
    val teal: Color,
    val indigo: Color,
    val purple: Color,
    val pink: Color,
    val gray: Color,
    /** Accent text / filled buttons (AA contrast in light mode). */
    val primary: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    val onAccent: Color,
    val textMuted: Color,
    val textTertiary: Color,
    val fill: Color,
    val separator: Color,
    val isDark: Boolean,
) {
    /** --t-<type> tints. */
    fun type(type: IncidentType): Color = when (type) {
        IncidentType.Lift -> indigo
        IncidentType.Fire -> red
        IncidentType.Medical -> pink
        IncidentType.Water -> teal
        IncidentType.Power -> orange
        IncidentType.Security -> purple
    }

    /** STATUSES tones. */
    fun status(status: IncidentStatus): Color = when (status) {
        IncidentStatus.Pending -> orange
        IncidentStatus.Acknowledged, IncidentStatus.EnRoute -> blue
        IncidentStatus.OnScene -> indigo
        IncidentStatus.Resolved -> green
        IncidentStatus.Cancelled -> gray
    }

    fun tier(tier: PriorityTier): Color = when (tier) {
        PriorityTier.P1 -> red
        PriorityTier.P2 -> orange
        PriorityTier.P3 -> blue
        PriorityTier.P4 -> green
    }

    fun power(state: PowerState): Color = when (state) {
        PowerState.On -> green
        PowerState.Rotating -> orange
        PowerState.Off -> red
    }

    fun assetState(state: AssetState): Color = when (state) {
        AssetState.Ok -> green
        AssetState.Degraded -> orange
        AssetState.Down -> red
        AssetState.Maintenance -> gray
    }

    fun assetKind(kind: AssetKind): Color = when (kind) {
        AssetKind.Lift -> indigo
        AssetKind.Dg, AssetKind.Transformer -> orange
        AssetKind.WaterPump, AssetKind.Sump -> teal
        AssetKind.FirePump -> red
    }

    fun compliance(status: ComplianceStatus): Color = when (status) {
        ComplianceStatus.Overdue -> red
        ComplianceStatus.DueSoon -> orange
        ComplianceStatus.Ok -> green
    }

    /** Urgency score tone: ≥70 red, ≥40 orange, else gray. */
    fun urgency(score: Int): Color = when {
        score >= 70 -> red
        score >= 40 -> orange
        else -> gray
    }
}

val LightKavachColors = KavachColors(
    blue = Color(0xFF007AFF),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    yellow = Color(0xFFFFCC00),
    green = Color(0xFF34C759),
    teal = Color(0xFF30B0C7),
    indigo = Color(0xFF5856D6),
    purple = Color(0xFFAF52DE),
    pink = Color(0xFFFF2D55),
    gray = Color(0xFF8E8E93),
    primary = Color(0xFF0062CC),
    danger = Color(0xFFD3281E),
    warning = Color(0xFFFF9500),
    success = Color(0xFF1F7A36),
    onAccent = Color.White,
    textMuted = Color(0xC23C3C43),
    textTertiary = Color(0x733C3C43),
    fill = Color(0x1F787880),
    separator = Color(0x4A3C3C43),
    isDark = false,
)

val DarkKavachColors = KavachColors(
    blue = Color(0xFF0A84FF),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    yellow = Color(0xFFFFD60A),
    green = Color(0xFF30D158),
    teal = Color(0xFF40C8E0),
    indigo = Color(0xFF5E5CE6),
    purple = Color(0xFFBF5AF2),
    pink = Color(0xFFFF375F),
    gray = Color(0xFF8E8E93),
    primary = Color(0xFF0A84FF),
    danger = Color(0xFFFF453A),
    warning = Color(0xFFFF9F0A),
    success = Color(0xFF30D158),
    onAccent = Color.White,
    textMuted = Color(0xB3EBEBF5),
    textTertiary = Color(0x59EBEBF5),
    fill = Color(0x3D767680),
    separator = Color(0xA6545458),
    isDark = true,
)

val LocalKavachColors = staticCompositionLocalOf { LightKavachColors }
