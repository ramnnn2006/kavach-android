package app.kavach.android.core

import app.kavach.android.core.data.model.AssetCheckKind
import app.kavach.android.core.data.model.AssetKind
import app.kavach.android.core.data.model.IncidentStatus
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.domain.Phones
import app.kavach.android.core.domain.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    @Test fun nextStatusIsForwardOnly() {
        assertEquals(IncidentStatus.EnRoute, Rules.nextStatus(IncidentStatus.Acknowledged))
        assertEquals(IncidentStatus.OnScene, Rules.nextStatus(IncidentStatus.EnRoute))
        assertEquals(IncidentStatus.Resolved, Rules.nextStatus(IncidentStatus.OnScene))
        assertNull(Rules.nextStatus(IncidentStatus.Pending))
        assertNull(Rules.nextStatus(IncidentStatus.Resolved))
    }

    @Test fun specialtiesMirrorSqlHelpers() {
        assertEquals(IncidentType.Power, Rules.assetSpecialty(AssetKind.Transformer))
        assertEquals(IncidentType.Water, Rules.assetSpecialty(AssetKind.Sump))
        assertEquals(IncidentType.Lift, Rules.checkKindSpecialty(AssetCheckKind.LiftAmc))
        assertNull(Rules.checkKindSpecialty(AssetCheckKind.Other))
    }

    @Test fun adminsCoverEverything() {
        val admin = Profile(id = "a", role = UserRole.Admin)
        val guard = Profile(id = "g", role = UserRole.Responder, specialties = Rules.PRESET_SECURITY)
        assertTrue(Rules.covers(admin, IncidentType.Lift))
        assertTrue(Rules.covers(guard, IncidentType.Fire))
        assertFalse(Rules.covers(guard, IncidentType.Lift))
        assertFalse(Rules.covers(guard, null))
    }

    @Test fun intervalMinutesParsesPostgresIntervals() {
        assertEquals(2.0, Rules.intervalMinutes("00:02:00"), 0.0)
        assertEquals(90.5, Rules.intervalMinutes("01:30:30"), 0.0)
        assertEquals(2.0, Rules.intervalMinutes(null), 0.0)
    }

    @Test fun phonesNormalizeToE164() {
        assertEquals("+919876543210", Phones.normalize("98765 43210"))
        assertEquals("+919876543210", Phones.normalize("098765-43210"))
        assertEquals("+919876543210", Phones.normalize("919876543210"))
        assertEquals("+919876543210", Phones.normalize("+91 98765 43210"))
        assertEquals("+919876543210", Phones.normalize("(+91) 98765.43210"))
        assertNull(Phones.normalize("12345"))
        assertEquals("+91 98765 43210", Phones.display("+919876543210"))
    }

    /** Same answers as public.normalise_phone() in the members migration. */
    @Test fun phonesMirrorServerRule() {
        // Indian mobile numbers start with 6-9
        assertNull(Phones.normalize("+91 12345 67890"))
        assertNull(Phones.normalize("5876543210"))
        assertNull(Phones.normalize("05876543210"))
        assertNull(Phones.normalize("915876543210"))
        // A +91 number of the wrong length is refused, not treated as international
        assertNull(Phones.normalize("+91 98765 4321"))
        // Letters or other symbols are refused (only spaces, ( ) . - are ignored)
        assertNull(Phones.normalize("98765a43210"))
        assertNull(Phones.normalize("98765/43210"))
        assertNull(Phones.normalize(""))
        // Other countries need the + and 8-15 digits
        assertEquals("+14155550100", Phones.normalize("+1 415 555 0100"))
        assertNull(Phones.normalize("+0123456789"))
        assertNull(Phones.normalize("+1234567"))
        assertNull(Phones.normalize("14155550100"))
    }
}
