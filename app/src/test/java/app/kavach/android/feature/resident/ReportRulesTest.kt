package app.kavach.android.feature.resident

import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRulesTest {
    private val home = ReportForm(where = ReportWhere.Home)
    private val elsewhere = ReportForm(where = ReportWhere.Else)

    @Test fun homeReportSendsFlatNotZone() {
        val p = ReportRules.buildPayload(IncidentType.Fire, home.copy(note = "  smoke in kitchen  ", people = 3), homeFlatId = "flat-1")
        assertEquals(IncidentType.Fire, p.type)
        assertEquals("flat-1", p.flatId)
        assertNull(p.zoneId)
        assertNull(p.locationNote)
        assertEquals("smoke in kitchen", p.description)
        assertEquals(3, p.peopleAffected)
    }

    @Test fun elsewhereReportSendsZoneAndSpotNotFlat() {
        val form = elsewhere.copy(zoneId = "zone-9", spot = "  near the pool ", floor = 2)
        val p = ReportRules.buildPayload(IncidentType.Medical, form, homeFlatId = "flat-1")
        assertEquals("zone-9", p.zoneId)
        assertNull(p.flatId)
        assertEquals("near the pool", p.locationNote)
        assertEquals(2, p.floor)
    }

    @Test fun blankFieldsBecomeNull() {
        val p = ReportRules.buildPayload(IncidentType.Water, elsewhere.copy(zoneId = "", spot = "   ", note = "  "), homeFlatId = null)
        assertNull(p.zoneId)
        assertNull(p.locationNote)
        assertNull(p.description)
    }

    @Test fun liftAssetOnlyForLiftReports() {
        val form = home.copy(assetId = "lift-2")
        assertEquals("lift-2", ReportRules.buildPayload(IncidentType.Lift, form, "flat-1").assetId)
        assertNull(ReportRules.buildPayload(IncidentType.Fire, form, "flat-1").assetId)
        assertNull(ReportRules.buildPayload(IncidentType.Lift, home.copy(assetId = ""), "flat-1").assetId)
    }

    @Test fun peopleStayWithinLimits() {
        assertEquals(1, ReportRules.buildPayload(IncidentType.Fire, home.copy(people = 0), null).peopleAffected)
        assertEquals(50, ReportRules.buildPayload(IncidentType.Fire, home.copy(people = 99), null).peopleAffected)
    }

    @Test fun atHomeNeedsNoValidation() {
        assertNull(ReportRules.validate(home, zonesLoaded = true))
        assertNull(ReportRules.validate(home, zonesLoaded = false))
    }

    @Test fun elsewhereNeedsAPlaceWhenPlacesLoaded() {
        assertEquals(ReportFieldError.Zone, ReportRules.validate(elsewhere, zonesLoaded = true))
        assertEquals(ReportFieldError.Zone, ReportRules.validate(elsewhere.copy(zoneId = " "), zonesLoaded = true))
        assertNull(ReportRules.validate(elsewhere.copy(zoneId = "z1"), zonesLoaded = true))
    }

    @Test fun elsewhereNeedsAWrittenSpotWhenPlacesUnavailable() {
        assertEquals(ReportFieldError.Spot, ReportRules.validate(elsewhere, zonesLoaded = false))
        assertEquals(ReportFieldError.Spot, ReportRules.validate(elsewhere.copy(spot = "  "), zonesLoaded = false))
        assertNull(ReportRules.validate(elsewhere.copy(spot = "Gate 2"), zonesLoaded = false))
    }

    @Test fun targetZoneFollowsWhere() {
        assertEquals("home-zone", ReportRules.targetZoneId(home.copy(zoneId = "other"), "home-zone"))
        assertEquals("other", ReportRules.targetZoneId(elsewhere.copy(zoneId = "other"), "home-zone"))
        assertNull(ReportRules.targetZoneId(elsewhere.copy(zoneId = ""), "home-zone"))
    }

    @Test fun floorPickerRules() {
        val tower = Zone(id = "z", name = "Tower A", floors = 12)
        assertEquals(12, ReportRules.floorCount(tower))
        assertEquals(0, ReportRules.floorCount(null))
        assertEquals(0, ReportRules.floorCount(tower.copy(floors = null)))
        assertTrue(ReportRules.showFloor(12, ReportWhere.Else, isLift = false))
        assertTrue(ReportRules.showFloor(12, ReportWhere.Home, isLift = true))
        assertFalse(ReportRules.showFloor(12, ReportWhere.Home, isLift = false))
        assertFalse(ReportRules.showFloor(0, ReportWhere.Else, isLift = true))
    }
}
