package app.kavach.android.feature.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.kavach.android.R
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.nav.Routes
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.ListRow
import app.kavach.android.core.ui.theme.KavachTheme

/** "More" tab: everything that isn't a bottom tab. */
@Composable
fun AdminMoreScreen(navigator: KavachNavigator) {
    val colors = KavachTheme.colors
    KavachScaffold(title = stringResource(R.string.nav_more)) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(ListPadding),
        ) {
            GroupedCard {
                MoreRow(Icons.Rounded.PhoneAndroid, colors.blue, R.string.nav_members) { navigator.navigate(Routes.AdminMembers) }
                HorizontalDivider()
                MoreRow(Icons.Rounded.BarChart, colors.indigo, R.string.nav_insights) { navigator.navigate(Routes.AdminReports) }
                HorizontalDivider()
                MoreRow(Icons.Rounded.Campaign, colors.orange, R.string.nav_notices) { navigator.navigate(Routes.AdminNotices) }
                HorizontalDivider()
                MoreRow(Icons.Rounded.HealthAndSafety, colors.red, R.string.nav_safety_check) { navigator.navigate(Routes.AdminSafetyCheck) }
                HorizontalDivider()
                MoreRow(Icons.Rounded.Tv, colors.purple, R.string.nav_board) { navigator.navigate(Routes.CommandBoard) }
                HorizontalDivider()
                MoreRow(Icons.Rounded.Person, colors.gray, R.string.nav_profile) { navigator.navigate(Routes.Profile) }
            }
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, tone: Color, label: Int, onClick: () -> Unit) {
    ListRow(
        title = stringResource(label),
        leading = { IconTile(icon, tone) },
        trailing = { ChevronIcon() },
        onClick = onClick,
    )
}
