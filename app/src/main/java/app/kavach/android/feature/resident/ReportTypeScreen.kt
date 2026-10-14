package app.kavach.android.feature.resident

import app.kavach.android.core.ui.components.hapticClick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.R
import app.kavach.android.core.config.AppConfig
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.domain.Rules
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.ui.components.CallButton
import app.kavach.android.core.ui.components.CallButtonStyle
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.ListRow
import app.kavach.android.core.ui.components.TypeIcon
import app.kavach.android.core.ui.format.hintRes
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

@HiltViewModel
class ReportTypeViewModel @Inject constructor(session: SessionManager) : ViewModel() {
    val securityPhone: StateFlow<String?> = session.profile
        .map { it?.society?.securityPhone?.takeIf { phone -> phone.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** "What's happening?" — one plain-language row per emergency type. */
@Composable
fun ReportTypeScreen(navigator: KavachNavigator) {
    val vm: ReportTypeViewModel = hiltViewModel()
    val securityPhone by vm.securityPhone.collectAsStateWithLifecycle()

    KavachScaffold(
        title = stringResource(R.string.resident_whats_happening),
        subtitle = stringResource(R.string.resident_whats_happening_sub),
        onBack = navigator::back,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            KavachCard(contentPadding = PaddingValues(0.dp)) {
                Rules.TYPE_ORDER.forEachIndexed { index, type ->
                    if (index > 0) HorizontalDivider(color = KavachTheme.colors.separator)
                    ListRow(
                        title = stringResource(type.labelRes()),
                        subtitle = stringResource(type.hintRes()),
                        leading = { TypeIcon(type) },
                        trailing = {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = KavachTheme.colors.textTertiary)
                        },
                        onClick = hapticClick({ navigator.navigate(Routes.ReportDetails(type.wire)) }),
                    )
                }
            }

            Text(
                stringResource(R.string.resident_life_danger),
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTheme.colors.textMuted,
            )
            CallButton(
                phone = AppConfig.EMERGENCY_112,
                label = stringResource(R.string.resident_call_112),
                style = CallButtonStyle.Danger,
                fillWidth = true,
            )
            // No number on file: no button (a disabled one reads as broken)
            if (securityPhone != null) {
                CallButton(
                    phone = securityPhone,
                    label = stringResource(R.string.common_call_security),
                    style = CallButtonStyle.Secondary,
                    fillWidth = true,
                )
            }
        }
    }
}
