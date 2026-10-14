package app.kavach.android.feature.resident

import androidx.compose.ui.semantics.clearAndSetSemantics
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.config.AppConfig
import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.ui.components.CallButton
import app.kavach.android.core.ui.components.CallButtonStyle
import app.kavach.android.core.ui.components.HoldToSendButton
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.components.rememberHaptics
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.hintRes
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/** One report: where, which lift, people, note, then a review step and hold-to-send. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportDetailsScreen(navigator: KavachNavigator) {
    val vm: ReportDetailsViewModel = hiltViewModel()
    MessageEffect(vm.messages.flow)

    LaunchedEffect(vm) {
        vm.openTracker.collect { navigator.replace(Routes.Tracker(it, justSent = true)) }
    }

    val type = vm.type
    if (type == null) {
        // Unknown type in the route: back to the type list
        LaunchedEffect(Unit) { navigator.replace(Routes.ReportType) }
        return
    }

    val ui by vm.ui.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val zones by vm.zones.collectAsStateWithLifecycle()
    val lifts by vm.lifts.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val securityPhone = profile?.society?.securityPhone?.takeIf { it.isNotBlank() }
    val scroll = rememberScrollState()

    LaunchedEffect(ui.serverError) { if (ui.serverError != null) haptics.reject() }
    // The place / spot message is at the top of the form, the Review button at the bottom: show it
    LaunchedEffect(vm) {
        vm.invalid.collect {
            haptics.reject()
            scroll.animateScrollTo(0)
        }
    }
    val goHome: () -> Unit = { navigator.openTab(Routes.ResidentHome) }
    // Review → back to the form; queued → home (like the top bar), never back to a fresh report
    BackHandler(enabled = ui.step != ReportStep.Details) {
        if (ui.step == ReportStep.Review) vm.backToDetails() else goHome()
    }

    val onBack: () -> Unit = when (ui.step) {
        ReportStep.Review -> vm::backToDetails
        ReportStep.Queued -> goHome
        ReportStep.Details -> navigator::back
    }

    KavachScaffold(
        title = stringResource(type.labelRes()),
        subtitle = if (ui.step == ReportStep.Details) stringResource(type.hintRes()) else null,
        onBack = onBack,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(scroll)
                .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (ui.step) {
                ReportStep.Details -> DetailsStep(
                    type = type,
                    ui = ui,
                    profile = profile,
                    zones = zones.data,
                    zonesFailed = zones.error != null && zones.data == null,
                    lifts = lifts.data,
                    liftsFailed = lifts.error != null && lifts.data == null,
                    securityPhone = securityPhone,
                    vm = vm,
                )
                ReportStep.Review -> ReviewStep(
                    type = type,
                    ui = ui,
                    profile = profile,
                    zones = zones.data,
                    lifts = lifts.data,
                    securityPhone = securityPhone,
                    vm = vm,
                )
                ReportStep.Queued -> QueuedStep(
                    typeLabel = stringResource(type.labelRes()),
                    failed = ui.queuedFailed,
                    securityPhone = securityPhone,
                    retrying = ui.retrying,
                    onTryNow = vm::retryNow,
                    onBackHome = goHome,
                )
            }
        }
    }
}

@Composable
private fun DetailsStep(
    type: IncidentType,
    ui: ReportUiState,
    profile: Profile?,
    zones: List<Zone>?,
    zonesFailed: Boolean,
    lifts: List<Asset>?,
    liftsFailed: Boolean,
    securityPhone: String?,
    vm: ReportDetailsViewModel,
) {
    val context = LocalContext.current
    val form = ui.form
    val isLift = type == IncidentType.Lift
    val homeZoneId = profile?.homeZoneId
    val targetZoneId = ReportRules.targetZoneId(form, homeZoneId)
    val targetZone = zones?.firstOrNull { it.id == targetZoneId }
    val floorCount = ReportRules.floorCount(targetZone)
    val showFloor = ReportRules.showFloor(floorCount, form.where, isLift)
    val liftsHere = lifts?.filter { it.zoneId == targetZoneId }

    // Where
    Column {
        SectionHeader(stringResource(R.string.resident_where_title))
        KavachCard(contentPadding = PaddingValues(0.dp)) {
            if (homeZoneId != null) {
                ChoiceRow(
                    selected = form.where == ReportWhere.Home,
                    onSelect = { vm.chooseWhere(ReportWhere.Home) },
                    title = stringResource(if (isLift) R.string.resident_where_my_tower else R.string.resident_where_home),
                    subtitle = if (isLift) profile?.flat?.zone?.name.orEmpty() else Format.homeLine(context, profile, withFloor = true),
                    icon = { IconTile(Icons.Rounded.Home, KavachTheme.colors.blue, size = 36.dp) },
                )
                HorizontalDivider(color = KavachTheme.colors.separator)
            }
            ChoiceRow(
                selected = form.where == ReportWhere.Else,
                onSelect = { vm.chooseWhere(ReportWhere.Else) },
                title = stringResource(R.string.resident_where_else),
                subtitle = stringResource(R.string.resident_where_else_sub),
                icon = { IconTile(Icons.Rounded.LocationOn, KavachTheme.colors.gray, size = 36.dp) },
            )
        }

        if (form.where == ReportWhere.Else) {
            Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                val zoneNames = zones.orEmpty()
                PickerField(
                    label = stringResource(R.string.resident_place_label),
                    valueText = when {
                        targetZone != null -> targetZone.name
                        zones != null -> stringResource(R.string.resident_choose_place)
                        zonesFailed -> stringResource(R.string.resident_places_unavailable)
                        else -> stringResource(R.string.common_loading)
                    },
                    options = zoneNames.map { PickerOption(it.id, it.name) },
                    onSelect = vm::setZone,
                    enabled = zones != null,
                    errorText = if (ui.fieldError == ReportFieldError.Zone) stringResource(R.string.resident_err_choose_zone) else null,
                )
                if (showFloor && !isLift) {
                    FloorPicker(
                        label = stringResource(R.string.resident_floor_label),
                        floor = form.floor,
                        floorCount = floorCount,
                        onSelect = vm::setFloor,
                    )
                }
                val spotError = ui.fieldError == ReportFieldError.Spot
                OutlinedTextField(
                    value = form.spot,
                    onValueChange = vm::setSpot,
                    label = { Text(stringResource(R.string.resident_spot_label)) },
                    supportingText = {
                        Text(stringResource(if (spotError) R.string.resident_err_describe_where else R.string.resident_spot_hint))
                    },
                    isError = spotError,
                    maxLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // Which lift
    if (isLift) {
        Column {
            SectionHeader(stringResource(R.string.resident_which_lift))
            KavachCard(contentPadding = PaddingValues(0.dp)) {
                when {
                    targetZoneId == null -> HintRow(stringResource(R.string.resident_lift_choose_place))
                    liftsFailed -> Row(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.resident_lifts_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = KavachTheme.colors.textMuted,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = vm::retryLifts) { Text(stringResource(R.string.common_retry)) }
                    }
                    liftsHere == null -> Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    else -> liftsHere.forEach { lift ->
                        val stateText = liftStateText(lift.state)
                        val stateTone = KavachTheme.colors.assetState(lift.state)
                        ChoiceRow(
                            selected = form.assetId == lift.id,
                            onSelect = { vm.setAsset(lift.id) },
                            title = lift.name,
                            subtitle = null,
                            icon = null,
                            trailing = if (stateText != null) {
                                { Text(stateText, style = MaterialTheme.typography.labelMedium, color = stateTone) }
                            } else null,
                        )
                        HorizontalDivider(color = KavachTheme.colors.separator)
                    }
                }
                ChoiceRow(
                    selected = form.assetId == null,
                    onSelect = { vm.setAsset(null) },
                    title = stringResource(R.string.resident_lift_not_sure),
                    subtitle = null,
                    icon = null,
                )
            }
            if (showFloor) {
                Spacer(Modifier.size(Spacing.md))
                FloorPicker(
                    label = stringResource(R.string.resident_lift_floor_label),
                    floor = form.floor,
                    floorCount = floorCount,
                    onSelect = vm::setFloor,
                )
            }
        }
    }

    // People
    KavachCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.resident_people_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.resident_people_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = KavachTheme.colors.textMuted,
                )
            }
            PeopleStepper(form.people, vm::setPeople)
        }
    }

    // Note
    val left = ReportRules.NOTE_MAX - form.note.length
    OutlinedTextField(
        value = form.note,
        onValueChange = vm::setNote,
        label = { Text(stringResource(R.string.resident_note_label)) },
        placeholder = { Text(stringResource(notePlaceholderRes(type))) },
        supportingText = if (left <= ReportRules.NOTE_WARN) {
            { Text(stringResource(R.string.resident_chars_left, left)) }
        } else null,
        minLines = 3,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )

    ui.serverError?.let { error -> ServerErrorBanner(error, securityPhone) }

    LifeDangerFooter()

    Button(
        onClick = vm::review,
        colors = ButtonDefaults.buttonColors(containerColor = KavachTheme.colors.danger, contentColor = KavachTheme.colors.onAccent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Text(stringResource(R.string.resident_review_action), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ReviewStep(
    type: IncidentType,
    ui: ReportUiState,
    profile: Profile?,
    zones: List<Zone>?,
    lifts: List<Asset>?,
    securityPhone: String?,
    vm: ReportDetailsViewModel,
) {
    val context = LocalContext.current
    val form = ui.form
    val place = reviewPlace(context, type, form, profile, zones)
    val lift = if (type == IncidentType.Lift) lifts?.firstOrNull { it.id == form.assetId }?.name else null

    Text(
        stringResource(R.string.resident_review_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    KavachCard {
        KeyValueRow(stringResource(R.string.resident_kv_where), place.ifBlank { stringResource(R.string.common_place_unknown) })
        if (lift != null) KeyValueRow(stringResource(R.string.common_asset_kind_lift), lift)
        KeyValueRow(stringResource(R.string.resident_kv_people), form.people.toString())
        val note = form.note.trim()
        if (note.isNotEmpty()) KeyValueRow(stringResource(R.string.resident_kv_note), note)
    }

    // Refused by the server: shown here, next to the send button the resident just used
    ui.serverError?.let { error -> ServerErrorBanner(error, securityPhone) }

    Text(
        stringResource(R.string.resident_review_hold_hint),
        modifier = Modifier.clearAndSetSemantics { },
        style = MaterialTheme.typography.bodyMedium,
        color = KavachTheme.colors.textMuted,
    )
    HoldToSendButton(
        label = stringResource(R.string.resident_hold_to_send),
        holdingLabel = stringResource(R.string.resident_holding),
        accessibleActionLabel = stringResource(R.string.resident_send_accessible),
        releasedEarlyLabel = stringResource(R.string.resident_hold_released_early),
        onComplete = vm::send,
        enabled = !ui.sending,
    )
    if (ui.sending) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(Spacing.sm))
            Text(stringResource(R.string.resident_sending), style = MaterialTheme.typography.bodyMedium)
        }
    }
    TextButton(onClick = vm::backToDetails, enabled = !ui.sending, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.resident_edit_details))
    }
    LifeDangerFooter()
}

@Composable
private fun QueuedStep(
    typeLabel: String,
    failed: Boolean,
    securityPhone: String?,
    retrying: Boolean,
    onTryNow: () -> Unit,
    onBackHome: () -> Unit,
) {
    val tone = if (failed) KavachTheme.colors.red else KavachTheme.colors.orange
    KavachCard {
        Column(
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            IconTile(if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.CloudOff, tone, size = 56.dp)
            Text(
                if (failed) stringResource(R.string.resident_outbox_failed, typeLabel) else stringResource(R.string.resident_queued_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(if (failed) R.string.resident_outbox_failed_hint else R.string.resident_queued_text),
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTheme.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (securityPhone != null) {
        CallButton(securityPhone, stringResource(R.string.common_call_security), style = CallButtonStyle.Danger, fillWidth = true)
    }
    KavachCard(contentPadding = PaddingValues(0.dp)) {
        CallRow(
            phone = AppConfig.EMERGENCY_112,
            title = stringResource(R.string.resident_call_112),
            detail = stringResource(R.string.resident_contact_112),
            callLabel = stringResource(R.string.resident_call_112),
        )
        // A refused report is never retried, so there is nothing to try again
        if (!failed) {
            HorizontalDivider(color = KavachTheme.colors.separator)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = !retrying, role = Role.Button, onClick = onTryNow)
                    .padding(horizontal = Spacing.md, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (retrying) R.string.resident_sending else R.string.resident_send_now),
                    style = MaterialTheme.typography.bodyLarge,
                    color = KavachTheme.colors.primary,
                    modifier = Modifier.weight(1f),
                )
                if (retrying) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
    TextButton(onClick = onBackHome, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.resident_back_home))
    }
}

/** "If someone's life is in danger, also call 112." */
@Composable
private fun LifeDangerFooter() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.resident_life_danger),
            style = MaterialTheme.typography.bodySmall,
            color = KavachTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        CallButton(AppConfig.EMERGENCY_112, stringResource(R.string.resident_call_112), style = CallButtonStyle.Secondary)
    }
}

/** The server refused the report: its message, and the security desk (when the society has one). */
@Composable
private fun ServerErrorBanner(error: Throwable, securityPhone: String?) {
    NoticeBanner(KavachTheme.colors.red, Icons.Rounded.ErrorOutline) {
        Text(stringResource(error.messageRes()), style = MaterialTheme.typography.bodyMedium)
        if (securityPhone != null) {
            Spacer(Modifier.size(Spacing.xs))
            CallButton(securityPhone, stringResource(R.string.common_call_security), style = CallButtonStyle.Danger, fillWidth = true)
        }
    }
}

@Composable
private fun HintRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = KavachTheme.colors.textMuted,
        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
    )
}

/** A single-choice row (RadioRow): optional icon, title, subtitle, trailing text and a radio mark. */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    onSelect: () -> Unit,
    title: String,
    subtitle: String?,
    icon: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
            }
        }
        trailing?.invoke()
        RadioButton(selected = selected, onClick = null)
    }
}

private data class PickerOption(val key: String?, val label: String)

/** A labelled dropdown (a select on the website): tap, then choose one option. */
@Composable
private fun PickerField(
    label: String,
    valueText: String,
    options: List<PickerOption>,
    onSelect: (String?) -> Unit,
    enabled: Boolean,
    errorText: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val isError = errorText != null
    val danger = KavachTheme.colors.danger
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (isError) danger else KavachTheme.colors.textMuted)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                border = BorderStroke(1.dp, if (isError) danger else MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    // TalkBack: "Place, Tower A" rather than the value alone
                    .semantics {
                        contentDescription = label
                        stateDescription = valueText
                    },
            ) {
                Text(valueText, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = KavachTheme.colors.textMuted)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            expanded = false
                            onSelect(option.key)
                        },
                    )
                }
            }
        }
        if (errorText != null) {
            Text(
                errorText,
                style = MaterialTheme.typography.bodySmall,
                color = danger,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Ground floor up to the tower's top floor, plus "Not sure". */
@Composable
private fun FloorPicker(label: String, floor: Int?, floorCount: Int, onSelect: (Int?) -> Unit) {
    val context = LocalContext.current
    val unknown = stringResource(R.string.resident_floor_unknown)
    val options = buildList {
        add(PickerOption(null, unknown))
        for (n in 0..floorCount) add(PickerOption(n.toString(), Format.floor(context, n).orEmpty()))
    }
    PickerField(
        label = label,
        valueText = Format.floor(context, floor) ?: unknown,
        options = options,
        onSelect = { key -> onSelect(key?.toIntOrNull()) },
        enabled = true,
    )
}

@Composable
private fun liftStateText(state: AssetState): String? = when (state) {
    AssetState.Ok -> null
    AssetState.Degraded -> stringResource(R.string.resident_lift_state_degraded)
    AssetState.Down -> stringResource(R.string.resident_lift_state_down)
    AssetState.Maintenance -> stringResource(R.string.resident_lift_state_maintenance)
}

private fun notePlaceholderRes(type: IncidentType): Int = when (type) {
    IncidentType.Lift -> R.string.resident_note_placeholder_lift
    IncidentType.Fire -> R.string.resident_note_placeholder_fire
    IncidentType.Medical -> R.string.resident_note_placeholder_medical
    IncidentType.Water -> R.string.resident_note_placeholder_water
    IncidentType.Power -> R.string.resident_note_placeholder_power
    IncidentType.Security -> R.string.resident_note_placeholder_security
}

/** "Tower A · Flat A-1204 · Floor 12" or "Clubhouse · Floor 2 · near the pool". */
private fun reviewPlace(context: Context, type: IncidentType, form: ReportForm, profile: Profile?, zones: List<Zone>?): String {
    val parts = mutableListOf<String>()
    when (form.where) {
        ReportWhere.Home -> {
            if (type == IncidentType.Lift) {
                profile?.flat?.zone?.name?.let { parts.add(it) }
                Format.floor(context, form.floor)?.let { parts.add(it) }
            } else {
                Format.homeLine(context, profile, withFloor = true).takeIf { it.isNotBlank() }?.let { parts.add(it) }
            }
        }
        ReportWhere.Else -> {
            zones?.firstOrNull { it.id == form.zoneId }?.name?.let { parts.add(it) }
            Format.floor(context, form.floor)?.let { parts.add(it) }
            form.spot.trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
    }
    return parts.joinToString(" · ")
}
