package app.kavach.android.core.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.ui.components.LocalIsOnline
import app.kavach.android.core.ui.components.LocalMessageScope
import app.kavach.android.core.ui.components.LocalSnackbarHostState
import app.kavach.android.core.ui.components.OfflineBanner
import app.kavach.android.core.ui.components.rememberReduceMotion
import app.kavach.android.feature.admin.AdminIncidentsScreen
import app.kavach.android.feature.admin.AdminMembersScreen
import app.kavach.android.feature.admin.AdminMoreScreen
import app.kavach.android.feature.admin.AdminNoticesScreen
import app.kavach.android.feature.admin.AdminOverviewScreen
import app.kavach.android.feature.admin.AdminReportsScreen
import app.kavach.android.feature.admin.AdminSafetyCheckScreen
import app.kavach.android.feature.admin.AdminSocietyScreen
import app.kavach.android.feature.admin.AdminTeamScreen
import app.kavach.android.feature.admin.CommandBoardScreen
import app.kavach.android.feature.resident.ContactsScreen
import app.kavach.android.feature.resident.MyReportsScreen
import app.kavach.android.feature.resident.ReportDetailsScreen
import app.kavach.android.feature.resident.ReportTypeScreen
import app.kavach.android.feature.resident.ResidentHomeScreen
import app.kavach.android.feature.resident.ResidentNoticesScreen
import app.kavach.android.feature.resident.TrackerScreen
import app.kavach.android.feature.responder.ResponderAlertsScreen
import app.kavach.android.feature.responder.ResponderAssetsScreen
import app.kavach.android.feature.responder.ResponderChecksScreen
import app.kavach.android.feature.responder.ResponderHistoryScreen
import app.kavach.android.feature.shared.IncidentDetailScreen
import app.kavach.android.feature.shared.LoginScreen
import app.kavach.android.feature.shared.ProfileScreen
import app.kavach.android.feature.shared.SetupScreen
import app.kavach.android.feature.shared.SplashScreen
import app.kavach.android.feature.shared.WalkthroughScreen
import app.kavach.android.feature.shared.WelcomeScreen

/** Top of the UI tree: picks splash / login / setup / welcome / the role's tabs. */
@Composable
fun KavachRoot(vm: RootViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Snackbars run here, not in the screen that sent them, so leaving a screen doesn't cut one off
    val messageScope = rememberCoroutineScope()

    CompositionLocalProvider(LocalSnackbarHostState provides snackbar, LocalMessageScope provides messageScope) {
        when (val state = ui) {
            is RootUi.Ready -> key(state.role) {
                // A link that arrived before this shell showed (sign-in, setup) waits here
                val pendingLink by vm.pendingLink.collectAsStateWithLifecycle()
                RoleShell(role = state.role, pendingLink = pendingLink, onLinkHandled = vm::consumeLink)
            }
            else -> Box(Modifier.fillMaxSize()) {
                val showBanner = !LocalIsOnline.current && state != RootUi.Loading
                Column(Modifier.fillMaxSize()) {
                    if (showBanner) OfflineBanner(Modifier.statusBarsPadding())
                    // The banner already sits below the status bar: the screen mustn't pad for it again
                    Box(Modifier.weight(1f).then(if (showBanner) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
                        when (state) {
                            RootUi.Loading -> SplashScreen(error = null, onRetry = null, onSignOut = null)
                            RootUi.SignedOut -> LoginScreen()
                            is RootUi.ProfileMissing -> SplashScreen(
                                error = state.error,
                                onRetry = if (state.error != null) vm::retryProfile else null,
                                onSignOut = if (state.error != null) vm::signOut else null,
                            )
                            RootUi.Setup -> SetupScreen(onDone = vm::finishSetup)
                            RootUi.NeedsHome -> WelcomeScreen()
                            is RootUi.Intro -> WalkthroughScreen(state.role, onDone = { vm.finishIntro(state.role) })
                            is RootUi.Ready -> Unit
                        }
                    }
                }
                // Above the system bar and the keyboard (login / welcome forms)
                SnackbarHost(
                    snackbar,
                    Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
                )
            }
        }
    }
}

/** Bottom tabs + NavHost for one role. Rebuilt when the role changes. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun RoleShell(role: UserRole, pendingLink: DeepLink?, onLinkHandled: () -> Unit) {
    val nav = rememberNavController()
    val tabs = Tabs.forRole(role)
    val navigator = remember(nav, tabs) { NavControllerNavigator(nav, tabs) }
    // The back stack is read only where it's needed (bar, snackbar offset) and through derived
    // states, so this shell (and the NavHost call) doesn't recompose on every navigation.
    val backStack = nav.currentBackStackEntryAsState()
    val onTab = remember(backStack, tabs) {
        derivedStateOf { backStack.value?.destination?.let { d -> tabs.any { d.hasRoute(it.route::class) } } == true }
    }
    val graphReady by remember(backStack) { derivedStateOf { backStack.value != null } }
    val reduceMotion = rememberReduceMotion()

    // Notification tap: open its screen once the graph is set
    LaunchedEffect(pendingLink, graphReady) {
        val link = pendingLink ?: return@LaunchedEffect
        if (!graphReady) return@LaunchedEffect
        DeepLinks.toRoute(link, role)?.let(navigator::navigate) ?: navigator.openTab(Tabs.home(role))
        onLinkHandled()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            // The Scaffold lifts it above the tab bar; on pushed screens keep it above the system
            // navigation bar, and above the keyboard in both cases
            val insets = if (onTab.value) {
                WindowInsets.ime.exclude(WindowInsets.navigationBars)
            } else {
                WindowInsets.ime.union(WindowInsets.navigationBars)
            }
            SnackbarHost(LocalSnackbarHostState.current, Modifier.windowInsetsPadding(insets))
        },
        bottomBar = { if (onTab.value) RoleBottomBar(tabs, backStack, navigator) },
    ) { padding ->
        val online = LocalIsOnline.current
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!online) OfflineBanner(Modifier.statusBarsPadding())
            // While the banner shows it covers the status bar: screens' top bars mustn't pad for it again
            SharedTransitionLayout(
                Modifier.weight(1f).then(if (!online) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            ) {
                CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                    NavHost(
                        navController = nav,
                        startDestination = Tabs.home(role),
                        enterTransition = { if (reduceMotion) EnterTransition.None else fadeIn() },
                        exitTransition = { if (reduceMotion) ExitTransition.None else fadeOut() },
                    ) {
                        sharedRoutes(navigator, role)
                        when (role) {
                            UserRole.Resident -> residentRoutes(navigator)
                            UserRole.Responder -> responderRoutes(navigator)
                            UserRole.Admin -> adminRoutes(navigator)
                        }
                    }
                }
            }
        }
    }
}

/** The role's bottom tabs. Recomposes on navigation, without taking the shell with it. */
@Composable
private fun RoleBottomBar(tabs: List<Tab>, backStack: State<NavBackStackEntry?>, navigator: KavachNavigator) {
    val destination = backStack.value?.destination
    NavigationBar {
        tabs.forEach { tab ->
            val selected = destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
            NavigationBarItem(
                selected = selected,
                onClick = { navigator.openTab(tab.route) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = {
                    Text(
                        stringResource(tab.label),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Clip,
                    )
                },
            )
        }
    }
}

/** Registers a routed screen and gives it the AnimatedVisibilityScope for shared elements. */
private inline fun <reified T : Route> NavGraphBuilder.screen(
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) { content(entry) }
    }
}

private fun NavGraphBuilder.sharedRoutes(navigator: KavachNavigator, role: UserRole) {
    screen<Routes.Profile> { ProfileScreen(navigator) }
    screen<Routes.Walkthrough> { WalkthroughScreen(role, onDone = navigator::back) }
    if (role != UserRole.Resident) screen<Routes.IncidentDetail> { IncidentDetailScreen(navigator) }
}

private fun NavGraphBuilder.residentRoutes(navigator: KavachNavigator) {
    screen<Routes.ResidentHome> { ResidentHomeScreen(navigator) }
    screen<Routes.ReportType> { ReportTypeScreen(navigator) }
    screen<Routes.ReportDetails> { ReportDetailsScreen(navigator) }
    screen<Routes.Tracker> { TrackerScreen(navigator) }
    screen<Routes.MyReports> { MyReportsScreen(navigator) }
    screen<Routes.ResidentNotices> { ResidentNoticesScreen(navigator) }
    screen<Routes.Contacts> { ContactsScreen(navigator) }
}

private fun NavGraphBuilder.responderRoutes(navigator: KavachNavigator) {
    screen<Routes.ResponderAlerts> { ResponderAlertsScreen(navigator) }
    screen<Routes.ResponderChecks> { ResponderChecksScreen(navigator) }
    screen<Routes.ResponderAssets> { ResponderAssetsScreen(navigator) }
    screen<Routes.ResponderHistory> { ResponderHistoryScreen(navigator) }
}

private fun NavGraphBuilder.adminRoutes(navigator: KavachNavigator) {
    screen<Routes.AdminOverview> { AdminOverviewScreen(navigator) }
    screen<Routes.AdminIncidents> { AdminIncidentsScreen(navigator) }
    screen<Routes.AdminTeam> { AdminTeamScreen(navigator) }
    screen<Routes.AdminSociety> { AdminSocietyScreen(navigator) }
    screen<Routes.AdminMore> { AdminMoreScreen(navigator) }
    screen<Routes.AdminMembers> { AdminMembersScreen(navigator) }
    screen<Routes.AdminReports> { AdminReportsScreen(navigator) }
    screen<Routes.AdminNotices> { AdminNoticesScreen(navigator) }
    screen<Routes.AdminSafetyCheck> { AdminSafetyCheckScreen(navigator) }
    screen<Routes.CommandBoard> { CommandBoardScreen(navigator) }
}

private class NavControllerNavigator(
    private val nav: NavHostController,
    private val tabs: List<Tab>,
) : KavachNavigator {

    override fun navigate(route: Route) {
        val top = nav.currentBackStackEntry
        if (top != null && top.destination.hasRoute(route::class)) {
            // Same screen again. A repeated tap does nothing, but another item (a notification
            // for a different incident) gets its own entry: launchSingleTop would reuse the old
            // entry, and its ViewModel would go on showing the old item.
            if (top.shows(route)) return
            nav.navigate(route)
        } else {
            nav.navigate(route) { launchSingleTop = true }
        }
    }

    override fun replace(route: Route) {
        val current = nav.currentBackStackEntry?.destination?.id
        nav.navigate(route) {
            if (current != null) popUpTo(current) { inclusive = true }
            launchSingleTop = true
        }
    }

    override fun back() {
        // Never pop the last screen: an empty back stack is a blank page (e.g. Back tapped twice)
        if (nav.previousBackStackEntry != null) nav.popBackStack()
    }

    override fun openTab(route: Route) {
        // Screens pushed on the current tab are dropped, not saved: "Back to home" from a report
        // or the tracker must not keep it (and its ViewModel) in a saved back stack. Saving them
        // also made "go to the tab I'm already in" a no-op: the save/restore pair below put them
        // straight back. Only tab roots keep their state when switching tabs.
        val root = currentTabRoot()
        nav.popBackStack(root, inclusive = false, saveState = false)
        if (route == root) return
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /** Root of the tab in use. Switching tabs pops back to home first, so at most one other tab is on the stack. */
    private fun currentTabRoot(): Route =
        tabs.drop(1).firstOrNull { tab -> isOnStack(tab.route) }?.route ?: tabs.first().route

    private fun isOnStack(route: Route): Boolean = runCatching { nav.getBackStackEntry(route) }.isSuccess
}

/**
 * Whether this entry already shows [route]: same screen and, for screens with arguments, the same
 * item. Routes with arguments must be listed here (argument names = the route's property names).
 */
private fun NavBackStackEntry.shows(route: Route): Boolean {
    val args = arguments
    return when (route) {
        is Routes.IncidentDetail -> args?.getString("id") == route.id
        is Routes.Tracker -> args?.getString("id") == route.id
        is Routes.ReportDetails -> args?.getString("type") == route.type
        else -> true // objects: one screen, no arguments
    }
}
