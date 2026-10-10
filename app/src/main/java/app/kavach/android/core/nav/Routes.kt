package app.kavach.android.core.nav

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation Compose routes. Screens read their arguments with
 * `savedStateHandle.toRoute<Routes.X>()` in their ViewModel.
 *
 * Splash, Login, Setup and Welcome are not routes: KavachRoot shows them from the session state.
 * A route with arguments must also be listed in `NavBackStackEntry.shows()` (KavachRoot.kt), which
 * tells "the same item again" from "another item on the same screen".
 */
@Serializable
sealed interface Route

object Routes {
    // ── Shared ──────────────────────────────────────────────────────────────
    @Serializable data object Profile : Route
    /** The first-run walkthrough again (Profile → How Kavach works). */
    @Serializable data object Walkthrough : Route
    /** Staff view of one incident (claim, advance, assign, timeline). */
    @Serializable data class IncidentDetail(val id: String) : Route

    // ── Resident ────────────────────────────────────────────────────────────
    @Serializable data object ResidentHome : Route
    /** Pick the emergency type. */
    @Serializable data object ReportType : Route
    /** Details → review & send for one type. [type] is IncidentType.wire ("lift", "fire", …). */
    @Serializable data class ReportDetails(val type: String) : Route
    /** Live tracker for my report. [justSent] shows the "sent" confirmation once. */
    @Serializable data class Tracker(val id: String, val justSent: Boolean = false) : Route
    @Serializable data object MyReports : Route
    @Serializable data object ResidentNotices : Route
    @Serializable data object Contacts : Route

    // ── Responder ───────────────────────────────────────────────────────────
    @Serializable data object ResponderAlerts : Route
    @Serializable data object ResponderChecks : Route
    @Serializable data object ResponderAssets : Route
    @Serializable data object ResponderHistory : Route

    // ── Admin ───────────────────────────────────────────────────────────────
    @Serializable data object AdminOverview : Route
    @Serializable data object AdminIncidents : Route
    @Serializable data object AdminTeam : Route
    @Serializable data object AdminSociety : Route
    /** "More" tab: Members, Reports, Notices, Safety Check, Command board, Profile. */
    @Serializable data object AdminMore : Route
    /** Approved phone list (new). */
    @Serializable data object AdminMembers : Route
    /** Reliability reports with charts (the website's Insights). */
    @Serializable data object AdminReports : Route
    @Serializable data object AdminNotices : Route
    @Serializable data object AdminSafetyCheck : Route
    /** Full-screen landscape board for the security cabin. */
    @Serializable data object CommandBoard : Route
}

/** What screens use to move around. Passed to every routed screen. */
interface KavachNavigator {
    /** Push a screen. */
    fun navigate(route: Route)
    /** Replace the current screen (e.g. report sent → tracker, so Back doesn't return to the form). */
    fun replace(route: Route)
    /** Go back one screen, like system Back (no-op on the last screen). */
    fun back()
    /**
     * Switch to a bottom tab (or the role home), clearing pushed screens. Also "back to home" from a
     * pushed screen: pushed screens are dropped, only tab roots keep their state.
     */
    fun openTab(route: Route)
}
