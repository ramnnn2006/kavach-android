package app.kavach.android.feature.shared

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.config.AppConfig
import app.kavach.android.core.domain.Phones
import app.kavach.android.core.ui.components.MessageEffect
import app.kavach.android.core.ui.components.rememberHaptics
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing

/**
 * Sign in. Phone code first; "Use email instead" for email + password. Nothing happens after
 * success here: KavachRoot switches screens from the session state.
 */
@Composable
fun LoginScreen() {
    val vm: LoginViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val unsentCount by vm.unsentCount.collectAsStateWithLifecycle()
    MessageEffect(vm.messages.flow)

    // Wrong code, number not on the list, offline...
    LaunchedEffect(state.errorRes) { if (state.errorRes != null) haptics.reject() }

    AuthFrame {
        AppMark()
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.shared_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))

        if (!AppConfig.isSupabaseConfigured) {
            Text(
                stringResource(R.string.shared_login_not_configured),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        if (unsentCount > 0) {
            Text(stringResource(R.string.shared_login_unsent, unsentCount),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        // Each step shows the error right under its fields, where it stays visible above the keyboard
        when (state.mode) {
            LoginMode.Phone -> when (state.step) {
                PhoneStep.Number -> PhoneNumberStep(state, vm)
                PhoneStep.Code -> PhoneCodeStep(state, vm)
            }
            LoginMode.Email -> EmailStep(state, vm)
        }

        val phoneCodeStep = state.mode == LoginMode.Phone && state.step == PhoneStep.Code
        if (!phoneCodeStep) {
            TextButton(
                onClick = { vm.setMode(if (state.mode == LoginMode.Phone) LoginMode.Email else LoginMode.Phone) },
                enabled = !state.busy,
            ) {
                Text(
                    stringResource(
                        if (state.mode == LoginMode.Phone) R.string.shared_login_use_email else R.string.shared_login_use_phone,
                    ),
                )
            }
        }

        Text(
            stringResource(if (state.mode == LoginMode.Phone) R.string.shared_login_phone_note else R.string.shared_login_staff_note),
            style = MaterialTheme.typography.bodySmall,
            color = KavachTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** The form's error, announced by TalkBack as soon as it appears. */
@Composable
private fun FormError(@StringRes error: Int?) {
    if (error == null) return
    Text(
        stringResource(error),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun PhoneNumberStep(state: LoginUiState, vm: LoginViewModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        StepTitle(stringResource(R.string.shared_login_phone_title))
        Text(
            stringResource(R.string.shared_login_phone_text),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTheme.colors.textMuted,
        )
        OutlinedTextField(
            value = state.phone,
            onValueChange = vm::onPhoneChange,
            label = { Text(stringResource(R.string.shared_login_phone_label)) },
            prefix = { Text(AppConfig.DEFAULT_COUNTRY_CODE) },
            singleLine = true,
            isError = state.errorRes != null,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.sendCode() }),
            // The country code is shown as a prefix, so autofill offers the national number
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.PhoneNumberNational },
        )
        FormError(state.errorRes)
        ActionButton(
            text = stringResource(R.string.shared_login_send_code),
            onClick = vm::sendCode,
            busy = state.busy,
            enabled = state.phone.isNotBlank() && AppConfig.isSupabaseConfigured,
        )
    }
}

@Composable
private fun PhoneCodeStep(state: LoginUiState, vm: LoginViewModel) {
    val codeFocus = remember { FocusRequester() }
    // Ready to type when the step opens, and again after a wrong code or a resend
    LaunchedEffect(state.busy) { if (!state.busy) codeFocus.requestFocus() }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        StepTitle(stringResource(R.string.shared_login_code_title))
        Text(
            stringResource(R.string.shared_login_code_sent, Phones.display(state.codeSentTo)),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTheme.colors.textMuted,
        )
        OutlinedTextField(
            value = state.code,
            onValueChange = vm::onCodeChange,
            label = { Text(stringResource(R.string.shared_login_code_label)) },
            singleLine = true,
            isError = state.errorRes != null,
            enabled = !state.busy,
            textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.verifyCode() }),
            // Lets the keyboard / autofill offer the code from the SMS
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(codeFocus)
                .semantics { contentType = ContentType.SmsOtpCode },
        )
        FormError(state.errorRes)
        ActionButton(
            text = stringResource(R.string.shared_login_verify),
            onClick = vm::verifyCode,
            busy = state.busy,
            enabled = state.code.length == LoginInput.CODE_LENGTH,
        )
        TextButton(
            onClick = vm::resendCode,
            enabled = !state.busy && state.resendSeconds == 0,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                if (state.resendSeconds > 0) {
                    stringResource(R.string.shared_login_resend_in, state.resendSeconds)
                } else {
                    stringResource(R.string.shared_login_resend)
                },
            )
        }
        TextButton(
            onClick = vm::changeNumber,
            enabled = !state.busy,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text(stringResource(R.string.shared_login_change_number)) }
    }
}

@Composable
private fun EmailStep(state: LoginUiState, vm: LoginViewModel) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        StepTitle(stringResource(R.string.shared_login_email_title))
        OutlinedTextField(
            value = state.email,
            onValueChange = vm::onEmailChange,
            label = { Text(stringResource(R.string.common_email)) },
            singleLine = true,
            isError = state.errorRes != null,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = vm::onPasswordChange,
            label = { Text(stringResource(R.string.shared_login_password)) },
            singleLine = true,
            isError = state.errorRes != null,
            enabled = !state.busy,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(
                            if (showPassword) R.string.shared_login_hide_password else R.string.shared_login_show_password,
                        ),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.signInWithEmail() }),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
        )
        FormError(state.errorRes)
        ActionButton(
            text = stringResource(R.string.shared_login_sign_in),
            onClick = vm::signInWithEmail,
            busy = state.busy,
            enabled = AppConfig.isSupabaseConfigured,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        TextButton(
            onClick = {
                // Hide the keyboard so the "reset link sent" message isn't covered by it
                focusManager.clearFocus()
                vm.forgotPassword()
            },
            enabled = !state.busy && AppConfig.isSupabaseConfigured,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text(stringResource(R.string.shared_login_forgot)) }
    }
}
