package app.kavach.android.core.nav

import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.prefs.AccessibilityRules
import org.junit.Assert.*
import org.junit.Test

class TabAndAccessibilityTest {
    @Test fun everyRoleHasFiveDistinctRootTabs() {
        UserRole.entries.forEach { role ->
            val routes = Tabs.forRole(role).map { it.route }
            assertEquals(5, routes.size)
            assertEquals(routes.size, routes.distinct().size)
            assertEquals(Tabs.home(role), routes.first())
            assertFalse(routes.contains(Routes.TabPager))
        }
    }
    @Test fun detailAndUtilityScreensAreNeverPagerPages() {
        UserRole.entries.forEach { role ->
            val routes = Tabs.forRole(role).map { it.route }
            listOf(Routes.Tracker("test"), Routes.IncidentDetail("test"), Routes.ReportDetails("fire"),
                Routes.ReportType, Routes.CommandBoard, Routes.Settings, Routes.Walkthrough).forEach {
                assertFalse(routes.contains(it))
            }
        }
    }
    @Test fun textScaleHasBoundsAndExactSteps() {
        assertEquals(0.8f, AccessibilityRules.textScale(-4f), 0f)
        assertEquals(1.6f, AccessibilityRules.textScale(8f), 0f)
        assertEquals(1.2f, AccessibilityRules.textScale(1.2000002f), 0f)
        assertEquals(1f, AccessibilityRules.textScale(Float.NaN), 0f)
    }
    @Test fun onlyKnownThemesAreAccepted() {
        listOf("system", "light", "dark").forEach { assertEquals(it, AccessibilityRules.theme(it)) }
        assertEquals("system", AccessibilityRules.theme("invalid"))
    }
}
