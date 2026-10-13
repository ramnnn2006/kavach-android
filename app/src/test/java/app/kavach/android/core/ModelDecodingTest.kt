package app.kavach.android.core

import app.kavach.android.core.data.model.ComplianceItem
import app.kavach.android.core.data.model.ComplianceStatus
import app.kavach.android.core.data.model.FirstResponderSkill
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Profile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ModelDecodingTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Test fun decodesIncidentRowFromPostgrest() {
        val row = """
            {"id":"6b0c","client_id":null,"source":"app","type":"lift","status":"en_route",
             "reporter_vulnerability":{"elderly":true,"note":"Uses a wheelchair"},"vulnerable":true,
             "zone_name":"Tower A","floor":12,"people_affected":2,"urgency_score":71,"escalation_level":2,
             "created_at":"2026-10-08T12:34:56.123456+00:00","en_route_at":"2026-10-08T12:36:00+00:00",
             "unknown_future_column":1}
        """.trimIndent()
        val inc = json.decodeFromString(Incident.serializer(), row)
        assertEquals(IncidentType.Lift, inc.type)
        assertEquals(IncidentStatus.EnRoute, inc.status)
        assertTrue(inc.reporterVulnerability.elderly)
        assertTrue(inc.isEscalated)
        assertEquals(1791462896L, inc.createdAt.epochSecond)
    }

    /** SessionManager keeps the last profile on disk with the app's Json settings. */
    @Test fun profileSurvivesTheDiskCache() {
        val appJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }
        val row = """
            {"id":"u1","society_id":"s1","full_name":"Chloe Dsouza","phone":"+919876543210","role":"resident",
             "specialties":[],"on_duty":false,"flat_id":"f1","language":"en",
             "vulnerability":{"elderly":true,"note":null},"first_responder_skill":"nurse",
             "flat":{"id":"f1","number":"A-1203","floor":12,"zone_id":"z1","zone":{"id":"z1","name":"Tower A","code":"A"}},
             "society":{"id":"s1","name":"Green Park","power_source":"dg"}}
        """.trimIndent()
        val profile = appJson.decodeFromString(Profile.serializer(), row)
        val back = appJson.decodeFromString(Profile.serializer(), appJson.encodeToString(Profile.serializer(), profile))
        assertEquals(profile, back)
        assertEquals("z1", back.homeZoneId)
        assertEquals(FirstResponderSkill.Nurse, back.firstResponderSkill)
    }

    @Test fun decodesComplianceRow() {
        val row = """{"id":"c1","kind":"lift_amc","title":"AMC","due_at":"2026-10-20","compliance_status":"due_soon","days_left":10}"""
        val item = json.decodeFromString(ComplianceItem.serializer(), row)
        assertEquals(LocalDate.of(2026, 10, 20), item.dueAt)
        assertEquals(ComplianceStatus.DueSoon, item.complianceStatus)
    }
}
