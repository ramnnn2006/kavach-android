package app.kavach.android.feature.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.data.model.*
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.nav.*
import app.kavach.android.core.ui.components.*
import app.kavach.android.core.ui.format.labelRes

/** Private account activity, not a social feed. No public followers or invented counters. */
@Composable
internal fun SocialProfileHeader(
    profile: Profile,
    state: Loadable<List<Incident>>,
    navigator: KavachNavigator,
    onEdit: () -> Unit,
    onSafety: () -> Unit,
) {
    val reports = state.data
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(84.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(profile.fullName.trim().take(1).uppercase(), style = MaterialTheme.typography.headlineMedium)
                }
            }
            Spacer(Modifier.width(12.dp))
            ProfileStat(reports?.size?.toString() ?: "-", stringResource(R.string.profile_recent), Modifier.weight(1f))
            ProfileStat(reports?.count { it.status == IncidentStatus.Resolved }?.toString() ?: "-", stringResource(R.string.profile_resolved), Modifier.weight(1f))
            ProfileStat(reports?.count { it.isActive }?.toString() ?: "-", stringResource(R.string.profile_active), Modifier.weight(1f))
        }
        Text(profile.fullName, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(profile.role.labelRes()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.profile_private_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = hapticClick(onEdit), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.profile_edit)) }
            if (profile.role == UserRole.Resident) {
                OutlinedButton(onClick = hapticClick(onSafety), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.profile_safety_short)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (profile.role == UserRole.Resident) {
                ProfileShortcut(Icons.Rounded.Home, stringResource(R.string.profile_home)) { navigator.openTab(Routes.ResidentHome) }
                ProfileShortcut(Icons.Rounded.Call, stringResource(R.string.profile_contacts)) { navigator.openTab(Routes.Contacts) }
                ProfileShortcut(Icons.Rounded.Shield, stringResource(R.string.profile_safety_short), onSafety)
            }
            ProfileShortcut(Icons.Rounded.Settings, stringResource(R.string.profile_settings_short)) { navigator.navigate(Routes.Settings) }
        }
        SectionHeader(stringResource(R.string.profile_recent_reports))
        Text(stringResource(R.string.profile_recent_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.error != null) Text(stringResource(R.string.profile_activity_unavailable), style = MaterialTheme.typography.bodySmall)
        if (reports == null && state.error == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (reports == null || reports.isEmpty()) {
            Text(stringResource(R.string.profile_no_reports), style = MaterialTheme.typography.bodyMedium)
        } else {
            reports.take(9).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { incident ->
                        KavachCard(modifier = Modifier.weight(1f).heightIn(min = 132.dp), contentPadding = PaddingValues(10.dp), onClick = {
                            if (profile.role == UserRole.Resident) navigator.navigate(Routes.Tracker(incident.id))
                            else navigator.navigate(Routes.IncidentDetail(incident.id))
                        }) {
                            TypeIcon(incident.type, modifier = Modifier.padding(vertical = 10.dp), size = 44.dp)
                            Text(stringResource(incident.type.labelRes()), style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(incident.status.labelRes(staff = profile.role != UserRole.Resident)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ProfileShortcut(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 82.dp)) {
        FilledTonalIconButton(onClick = hapticClick(onClick), modifier = Modifier.size(56.dp)) {
            Icon(icon, contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ProfileStat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
