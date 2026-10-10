package app.kavach.android.core.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.ui.graphics.vector.ImageVector
import app.kavach.android.R
import app.kavach.android.core.data.model.UserRole

data class Tab(val route: Route, val icon: ImageVector, @StringRes val label: Int)

/** Bottom tabs per role (society.js NAV). Admin extras live under the "More" tab. */
object Tabs {
    val resident = listOf(
        Tab(Routes.ResidentHome, Icons.Rounded.Home, R.string.nav_home),
        Tab(Routes.MyReports, Icons.AutoMirrored.Rounded.ListAlt, R.string.nav_reports),
        Tab(Routes.ResidentNotices, Icons.Rounded.Campaign, R.string.nav_notices),
        Tab(Routes.Contacts, Icons.Rounded.Call, R.string.nav_contacts),
        Tab(Routes.Profile, Icons.Rounded.Person, R.string.nav_profile),
    )
    val responder = listOf(
        Tab(Routes.ResponderAlerts, Icons.Rounded.Notifications, R.string.nav_alerts),
        Tab(Routes.ResponderChecks, Icons.Rounded.FactCheck, R.string.nav_checks),
        Tab(Routes.ResponderAssets, Icons.Rounded.Build, R.string.nav_assets),
        Tab(Routes.ResponderHistory, Icons.Rounded.History, R.string.nav_history),
        Tab(Routes.Profile, Icons.Rounded.Person, R.string.nav_profile),
    )
    val admin = listOf(
        Tab(Routes.AdminOverview, Icons.Rounded.Dashboard, R.string.nav_overview),
        Tab(Routes.AdminIncidents, Icons.Rounded.Checklist, R.string.nav_incidents),
        Tab(Routes.AdminTeam, Icons.Rounded.Groups, R.string.nav_team),
        Tab(Routes.AdminSociety, Icons.Rounded.Apartment, R.string.nav_society),
        Tab(Routes.AdminMore, Icons.Rounded.MoreHoriz, R.string.nav_more),
    )

    fun forRole(role: UserRole): List<Tab> = when (role) {
        UserRole.Resident -> resident
        UserRole.Responder -> responder
        UserRole.Admin -> admin
    }

    fun home(role: UserRole): Route = forRole(role).first().route
}
