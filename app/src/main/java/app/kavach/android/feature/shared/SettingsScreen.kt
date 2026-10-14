package app.kavach.android.feature.shared

import app.kavach.android.core.ui.components.hapticClick

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.data.prefs.SettingsStore
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val store: SettingsStore) : ViewModel() {
    val notifications = store.alertsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val glass = store.glass.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun glass(on: Boolean) { viewModelScope.launch { store.setGlass(on) } }
    val theme = store.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")
    val textScale = store.textScale.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1f)
    fun notifications(on: Boolean) { viewModelScope.launch { store.setAlertsEnabled(on) } }
    fun theme(value: String) { viewModelScope.launch { store.setTheme(value) } }
    fun textScale(value: Float) { viewModelScope.launch { store.setTextScale(value) } }
}

@Composable
fun SettingsScreen(navigator: KavachNavigator) {
    val vm: SettingsViewModel = hiltViewModel()
    val enabled by vm.notifications.collectAsStateWithLifecycle()
    val glass by vm.glass.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val scale by vm.textScale.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val feedbackView = androidx.compose.ui.platform.LocalView.current
    var askedPermission by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(context.notificationsAllowed()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = context.notificationsAllowed() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = context.notificationsAllowed()
        vm.notifications(granted && allowed)
    }
    SettingsContent(navigator, enabled, allowed, theme, scale, vm::theme, vm::textScale,
        glassEnabled = glass, onGlass = vm::glass,
        openNotificationSettings = { context.openAppNotificationSettings() },
        onNotifications = { on ->
                        app.kavach.android.core.ui.components.ClickFeedback.pulse(feedbackView)
                        when {
                            !on -> vm.notifications(false)
                            allowed -> vm.notifications(true)
                            Build.VERSION.SDK_INT >= 33 && (!askedPermission || context.canAskNotificationPermissionAgain()) -> {
                                askedPermission = true
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            else -> context.openAppNotificationSettings()
                        }
        })
}

@Composable
internal fun SettingsContent(navigator: KavachNavigator, enabled: Boolean, allowed: Boolean, theme: String,
    scale: Float, onTheme: (String) -> Unit, onScale: (Float) -> Unit,
    openNotificationSettings: () -> Unit, onNotifications: (Boolean) -> Unit,
    glassEnabled: Boolean = true, onGlass: (Boolean) -> Unit = {}) {
    KavachScaffold(title = stringResource(R.string.settings_title), onBack = navigator::back) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.md).padding(bottom = app.kavach.android.core.ui.components.LocalFloatingBarInset.current)) {
            SectionHeader(stringResource(R.string.settings_notifications))
            KavachCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.settings_notifications), Modifier.weight(1f))
                    Switch(checked = enabled && allowed, onCheckedChange = onNotifications)
                }
                Text(stringResource(if (enabled && allowed) R.string.settings_notifications_on else R.string.settings_notifications_off))
                if (!allowed) {
                    Text(stringResource(R.string.shared_setup_notif_blocked))
                    TextButton(onClick = hapticClick(openNotificationSettings)) {
                        Text(stringResource(R.string.shared_profile_notification_settings))
                    }
                }
            }
            SectionHeader(stringResource(R.string.settings_glass))
            KavachCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.settings_glass), Modifier.weight(1f))
                    Switch(checked = glassEnabled, onCheckedChange = onGlass)
                }
                Text(stringResource(R.string.settings_glass_hint), style = MaterialTheme.typography.bodySmall)
            }
            SectionHeader(stringResource(R.string.settings_appearance))
            KavachCard {
                listOf("system" to R.string.settings_system, "light" to R.string.settings_light, "dark" to R.string.settings_dark).forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth()) {
                        RadioButton(selected = theme == value, onClick = hapticClick({ onTheme(value) }))
                        TextButton(onClick = hapticClick({ onTheme(value) })) { Text(stringResource(label)) }
                    }
                }
            }
            SectionHeader(stringResource(R.string.settings_text_size))
            KavachCard {
                Text(stringResource(R.string.settings_text_percent, (scale * 100).toInt()))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = hapticClick({ onScale(scale - 0.1f) }), enabled = scale > 0.81f) { Text(stringResource(R.string.settings_smaller)) }
                    OutlinedButton(onClick = hapticClick({ onScale(scale + 0.1f) }), enabled = scale < 1.59f) { Text(stringResource(R.string.settings_larger)) }
                }
                TextButton(onClick = hapticClick({ onScale(1f) })) { Text(stringResource(R.string.settings_reset)) }
                Text(stringResource(R.string.settings_preview))
            }
        }
    }
}
