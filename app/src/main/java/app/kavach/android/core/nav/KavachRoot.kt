package app.kavach.android.core.nav

import app.kavach.android.core.ui.components.hapticClick
import app.kavach.android.core.ui.components.LocalGlassEnabled
import dev.chrisbanes.haze.*
import android.os.Build
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding


import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
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
import app.kavach.android.feature.shared.SettingsScreen
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
    val haze = rememberHazeState()
    val glassAllowed = LocalGlassEnabled.current && Build.VERSION.SDK_INT >= 31 && LocalView.current.isHardwareAccelerated
    val tabs = Tabs.forRole(role)
    val reduceMotion = rememberReduceMotion()
    val pager = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val haptics = app.kavach.android.core.ui.components.rememberHaptics()
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { haptics.tick() }
    }
    val navigator = remember(nav, tabs, pager, reduceMotion) {
        NavControllerNavigator(nav, tabs) { index ->
            scope.launch { if (reduceMotion) pager.scrollToPage(index) else pager.animateScrollToPage(index) }
        }
    }
    // The back stack is read only where it's needed (bar, snackbar offset) and through derived
    // states, so this shell (and the NavHost call) doesn't recompose on every navigation.
    val backStack = nav.currentBackStackEntryAsState()
    val onTab = remember(backStack, tabs) {
        derivedStateOf { backStack.value?.destination?.let { d -> d.hasRoute<Routes.TabPager>() } == true }
    }
    val graphReady by remember(backStack) { derivedStateOf { backStack.value != null } }

    // Notification tap: open its screen once the graph is set
    LaunchedEffect(pendingLink, graphReady) {
        val link = pendingLink ?: return@LaunchedEffect
        if (!graphReady) return@LaunchedEffect
        DeepLinks.toRoute(link, role)?.let(navigator::navigate) ?: navigator.openTab(Tabs.home(role))
        onLinkHandled()
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.then(if (glassAllowed) Modifier.hazeSource(haze) else Modifier),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            // The Scaffold lifts it above the tab bar; on pushed screens keep it above the system
            // navigation bar, and above the keyboard in both cases
            val insets = if (onTab.value) {
                WindowInsets.ime.exclude(WindowInsets.navigationBars)
            } else {
                WindowInsets.ime.union(WindowInsets.navigationBars)
            }
            SnackbarHost(LocalSnackbarHostState.current, Modifier.windowInsetsPadding(insets).padding(bottom = if (onTab.value) 108.dp else 0.dp))
        },
        bottomBar = {},
    ) { padding ->
        val online = LocalIsOnline.current
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!online) OfflineBanner(Modifier.statusBarsPadding())
            // While the banner shows it covers the status bar: screens' top bars mustn't pad for it again
            SharedTransitionLayout(
                Modifier.weight(1f).then(if (!online) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            ) {
                CompositionLocalProvider(app.kavach.android.core.ui.components.LocalFloatingBarInset provides if (onTab.value) 108.dp else 0.dp, LocalSharedTransitionScope provides this) {
                    NavHost(
                        navController = nav,
                        startDestination = Routes.TabPager,
                        enterTransition = { if (reduceMotion) EnterTransition.None else fadeIn() },
                        exitTransition = { if (reduceMotion) ExitTransition.None else fadeOut() },
                    ) {
                        screen<Routes.TabPager> {
                            val savedTabs = rememberSaveableStateHolder()
                            // Hoisted outside page composition: offscreen disposal must not
                            // destroy a tab's navigation entry or ViewModelStore.
                            val tabControllers = tabs.map { tab ->
                                key(tab.route) { rememberNavController() }
                            }
                            HorizontalPager(
                                state = pager,
                                modifier = Modifier.fillMaxSize(),
                                key = { tabs[it].route.toString() },
                                // Android owns the system edge-back gesture; no exclusions or
                                // custom horizontal pointer handlers are installed here.
                            ) { page ->
                                savedTabs.SaveableStateProvider(tabs[page].route.toString()) {
                                    // A separate tab-root entry keeps each tab's ViewModels and
                                    // SavedStateHandle alive, even when the pager disposes its UI.
                                    val tabNav = tabControllers[page]
                                    NavHost(tabNav, startDestination = tabs[page].route) {
                                        tabRoot(tabs[page].route, navigator)
                                    }
                                }
                            }
                        }
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
    if (onTab.value) RoleBottomBar(tabs, pager.currentPage, navigator, haze, glassAllowed,
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

}

/** The role's bottom tabs. Recomposes on navigation, without taking the shell with it. */
@Composable
@OptIn(ExperimentalHazeApi::class)
internal fun RoleBottomBar(tabs: List<Tab>, selectedPage: Int, navigator: KavachNavigator,
    haze: HazeState? = null, glassEnabled: Boolean = false, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val renderGlass = glassEnabled && Build.VERSION.SDK_INT >= 31 && LocalView.current.isHardwareAccelerated
    val glass = if (haze != null && renderGlass) Modifier.hazeEffect(haze,
        style = HazeStyle(backgroundColor = MaterialTheme.colorScheme.background,
            tint = HazeTint(surface.copy(alpha = 0.64f)), blurRadius = 20.dp,
            noiseFactor = 0.025f, fallbackTint = HazeTint(surface))) {
        blurEnabled = true
        inputScale = HazeInputScale.Fixed(0.5f)
    } else Modifier

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(32.dp)).then(glass),
        shape = RoundedCornerShape(32.dp),
        color = if (renderGlass && haze != null) androidx.compose.ui.graphics.Color.Transparent else surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
    Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        tabs.forEachIndexed { index, tab ->
            val selected = selectedPage == index
            Surface(
                onClick = { navigator.openTab(tab.route) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                color = if (selected) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 10.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(tab.icon, contentDescription = null)
                    Text(stringResource(tab.label), style = MaterialTheme.typography.labelSmall,
                        maxLines = 2, overflow = TextOverflow.Clip, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
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

/** Tab roots live only inside the pager, never in the outer detail stack. */
private fun NavGraphBuilder.tabRoot(route: Route, navigator: KavachNavigator) {
    when (route) {
        Routes.ResidentHome -> screen<Routes.ResidentHome> { ResidentHomeScreen(navigator) }
        Routes.MyReports -> screen<Routes.MyReports> { MyReportsScreen(navigator) }
        Routes.ResidentNotices -> screen<Routes.ResidentNotices> { ResidentNoticesScreen(navigator) }
        Routes.Contacts -> screen<Routes.Contacts> { ContactsScreen(navigator) }
        Routes.ResponderAlerts -> screen<Routes.ResponderAlerts> { ResponderAlertsScreen(navigator) }
        Routes.ResponderChecks -> screen<Routes.ResponderChecks> { ResponderChecksScreen(navigator) }
        Routes.ResponderAssets -> screen<Routes.ResponderAssets> { ResponderAssetsScreen(navigator) }
        Routes.ResponderHistory -> screen<Routes.ResponderHistory> { ResponderHistoryScreen(navigator) }
        Routes.AdminOverview -> screen<Routes.AdminOverview> { AdminOverviewScreen(navigator) }
        Routes.AdminIncidents -> screen<Routes.AdminIncidents> { AdminIncidentsScreen(navigator) }
        Routes.AdminTeam -> screen<Routes.AdminTeam> { AdminTeamScreen(navigator) }
        Routes.AdminSociety -> screen<Routes.AdminSociety> { AdminSocietyScreen(navigator) }
        Routes.AdminMore -> screen<Routes.AdminMore> { AdminMoreScreen(navigator) }
        Routes.Profile -> screen<Routes.Profile> { ProfileScreen(navigator) }
        else -> error("Not a tab root: $route")
    }
}

private fun NavGraphBuilder.sharedRoutes(navigator: KavachNavigator, role: UserRole) {
    screen<Routes.Settings> { SettingsScreen(navigator) }
    if (role == UserRole.Admin) screen<Routes.Profile> { ProfileScreen(navigator) }
    screen<Routes.Walkthrough> { WalkthroughScreen(role, onDone = navigator::back) }
    if (role != UserRole.Resident) screen<Routes.IncidentDetail> { IncidentDetailScreen(navigator) }
}

private fun NavGraphBuilder.residentRoutes(navigator: KavachNavigator) {
    screen<Routes.ReportType> { ReportTypeScreen(navigator) }
    screen<Routes.ReportDetails> { ReportDetailsScreen(navigator) }
    screen<Routes.Tracker> { TrackerScreen(navigator) }
}

private fun NavGraphBuilder.responderRoutes(navigator: KavachNavigator) {
}

private fun NavGraphBuilder.adminRoutes(navigator: KavachNavigator) {
    screen<Routes.AdminMembers> { AdminMembersScreen(navigator) }
    screen<Routes.AdminReports> { AdminReportsScreen(navigator) }
    screen<Routes.AdminNotices> { AdminNoticesScreen(navigator) }
    screen<Routes.AdminSafetyCheck> { AdminSafetyCheckScreen(navigator) }
    screen<Routes.CommandBoard> { CommandBoardScreen(navigator) }
}

private class NavControllerNavigator(
    private val nav: NavHostController,
    private val tabs: List<Tab>,
    private val selectPage: (Int) -> Unit,
) : KavachNavigator {

    override fun navigate(route: Route) {
        if (tabs.any { it.route == route }) { openTab(route); return }
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
        val index = tabs.indexOfFirst { it.route == route }
        require(index >= 0) { "Not a tab for this role: $route" }
        // Only detail screens are discarded. The pager entry and tab owners remain alive.
        nav.popBackStack<Routes.TabPager>(inclusive = false)
        selectPage(index)
    }

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
