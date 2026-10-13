package app.kavach.android.feature.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.ui.components.ConfirmDialog
import app.kavach.android.core.ui.components.ErrorState
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/**
 * For a resident whose account has no flat yet (email sign-ups): choose tower, floor and flat
 * number. When saved, the profile updates and KavachRoot moves on by itself.
 */
@Composable
fun WelcomeScreen() {
    val vm: WelcomeViewModel = hiltViewModel()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val towers by vm.towers.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Submitting hides the keyboard, so a save error (snackbar) isn't hidden behind it
    val focusManager = LocalFocusManager.current
    MessageEffect(vm.messages.flow)

    var zoneId by rememberSaveable { mutableStateOf("") }
    var floor by rememberSaveable { mutableStateOf<Int?>(null) }
    var flat by rememberSaveable { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var confirmLogOut by rememberSaveable { mutableStateOf(false) }

    AuthFrame {
        AppMark()
        profile?.society?.name?.let { name ->
            Text(name, style = MaterialTheme.typography.labelLarge, color = KavachTheme.colors.textMuted)
        }
        Text(
            stringResource(R.string.shared_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.shared_welcome_text),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xs))

        if (profile?.societyId == null) {
            Text(
                stringResource(R.string.shared_welcome_no_society),
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTheme.colors.warning,
                textAlign = TextAlign.Center,
            )
        } else {
            when (val ui = towers) {
                TowersUi.Loading -> CircularProgressIndicator()
                is TowersUi.Failed -> ErrorState(ui.error, onRetry = vm::loadTowers)
                is TowersUi.Ready -> {
                    if (ui.towers.isEmpty()) {
                        Text(
                            stringResource(R.string.shared_welcome_no_towers),
                            style = MaterialTheme.typography.bodyMedium,
                            color = KavachTheme.colors.textMuted,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        val tower = ui.towers.firstOrNull { it.id == zoneId }
                        val floors = WelcomeLogic.floorOptions(tower?.floors)
                        val towerError = showErrors && tower == null
                        val flatError = showErrors && flat.isBlank()

                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            PickerField(
                                label = stringResource(R.string.common_tower),
                                valueText = tower?.name.orEmpty(),
                                options = ui.towers.map { it.name },
                                onSelect = { index ->
                                    zoneId = ui.towers[index].id
                                    floor = null
                                },
                                enabled = !saving,
                                errorText = if (towerError) stringResource(R.string.shared_welcome_err_tower) else null,
                            )
                            if (floors.isNotEmpty()) {
                                PickerField(
                                    label = stringResource(R.string.common_floor),
                                    valueText = floor?.let { Format.floor(context, it) }.orEmpty(),
                                    options = floors.map { Format.floor(context, it).orEmpty() },
                                    onSelect = { index -> floor = floors[index] },
                                    enabled = !saving,
                                    errorText = if (showErrors && floor == null) stringResource(R.string.shared_welcome_err_floor) else null,
                                )
                            }
                            OutlinedTextField(
                                value = flat,
                                onValueChange = { flat = it.take(12) },
                                label = { Text(stringResource(R.string.shared_welcome_flat_number)) },
                                supportingText = {
                                    Text(
                                        stringResource(
                                            if (flatError) R.string.shared_welcome_err_flat else R.string.shared_welcome_flat_hint,
                                        ),
                                    )
                                },
                                isError = flatError,
                                singleLine = true,
                                enabled = !saving,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        focusManager.clearFocus()
                                        submit(vm, zoneId, floor, floors.isNotEmpty(), flat) { showErrors = true }
                                    },
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ActionButton(
                                text = stringResource(R.string.shared_welcome_save),
                                onClick = {
                                    focusManager.clearFocus()
                                    submit(vm, zoneId, floor, floors.isNotEmpty(), flat) { showErrors = true }
                                },
                                busy = saving,
                            )
                        }
                    }
                }
            }
        }

        TextButton(
            onClick = { confirmLogOut = true },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text(stringResource(R.string.shared_log_out)) }
    }

    if (confirmLogOut) {
        ConfirmDialog(
            title = stringResource(R.string.shared_profile_logout_title),
            text = stringResource(R.string.shared_profile_logout_text),
            confirmLabel = stringResource(R.string.shared_log_out),
            destructive = true,
            onConfirm = {
                confirmLogOut = false
                vm.signOut()
            },
            onDismiss = { confirmLogOut = false },
        )
    }
}

/** Validates the form; shows field errors via [onInvalid] or saves. */
private fun submit(vm: WelcomeViewModel, zoneId: String, floor: Int?, needsFloor: Boolean, flat: String, onInvalid: () -> Unit) {
    if (zoneId.isBlank() || flat.isBlank() || (needsFloor && floor == null)) {
        onInvalid()
        return
    }
    vm.save(zoneId, floor, flat)
}
