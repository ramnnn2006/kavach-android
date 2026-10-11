package app.kavach.android.feature.shared

import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.TeamMember
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.model.ZoneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLogicTest {
    @Test fun phoneInputKeepsOnlyPhoneCharacters() {
        assertEquals("98765 43210", LoginInput.sanitizePhone("98765 43210abc"))
        assertEquals("+91-98765", LoginInput.sanitizePhone("+91-98765"))
    }

    @Test fun codeInputIsSixDigits() {
        assertEquals("123456", LoginInput.sanitizeCode("12 34-56789"))
        assertEquals("", LoginInput.sanitizeCode("abc"))
    }

    @Test fun resendCountdownRoundsUpAndIsCapped() {
        assertEquals(LoginInput.RESEND_SECONDS, LoginInput.resendSecondsLeft(resendAt = LoginInput.RESEND_SECONDS * 1_000L, now = 0L))
        assertEquals(1, LoginInput.resendSecondsLeft(resendAt = 1_000L, now = 1L))
        assertEquals(0, LoginInput.resendSecondsLeft(resendAt = 1_000L, now = 1_000L))
        assertEquals(0, LoginInput.resendSecondsLeft(resendAt = 0L, now = 5_000L))
        // Clock moved back: never more than the full wait
        assertEquals(LoginInput.RESEND_SECONDS, LoginInput.resendSecondsLeft(resendAt = 10_000_000L, now = 0L))
    }

    @Test fun emailValidation() {
        assertTrue(LoginInput.isEmail(" a@b.co "))
        assertFalse(LoginInput.isEmail("a@b"))
        assertNull(LoginInput.emailError("a@b.co"))
        assertEquals(app.kavach.android.R.string.shared_login_err_email_required, LoginInput.emailError(""))
    }

    @Test fun floorOptionsRunFromGroundToTop() {
        assertEquals(listOf(0, 1, 2), WelcomeLogic.floorOptions(2))
        assertTrue(WelcomeLogic.floorOptions(null).isEmpty())
        assertTrue(WelcomeLogic.floorOptions(0).isEmpty())
    }

    @Test fun onlyTowersCanBeHome() {
        val zones = listOf(
            Zone(id = "1", name = "Tower A", kind = ZoneKind.Tower),
            Zone(id = "2", name = "Clubhouse", kind = ZoneKind.Clubhouse),
        )
        assertEquals(listOf("1"), WelcomeLogic.towersOf(zones).map { it.id })
    }

    @Test fun samePhoneIgnoresFormatting() {
        assertTrue(ProfileLogic.samePhone("+91 98765 43210", "+919876543210"))
        assertTrue(ProfileLogic.samePhone("9876543210", "+919876543210"))
        assertTrue(ProfileLogic.samePhone("", null))
        assertFalse(ProfileLogic.samePhone("9876543211", "+919876543210"))
    }

    @Test fun phoneToStoreNormalisesOrClears() {
        assertEquals("+919876543210", ProfileLogic.phoneToStore("98765 43210"))
        assertNull(ProfileLogic.phoneToStore("  "))
        assertNull(ProfileLogic.phoneToStore("123"))
    }

    @Test fun nameRules() {
        assertEquals(app.kavach.android.R.string.shared_profile_name_required, ProfileLogic.nameError("  "))
        assertEquals(app.kavach.android.R.string.shared_profile_name_too_long, ProfileLogic.nameError("x".repeat(81)))
        assertNull(ProfileLogic.nameError("Asha"))
    }

    @Test fun claimNeedsDutyAndSpecialtyForResponders() {
        val onDuty = Profile(id = "r", role = UserRole.Responder, onDuty = true, specialties = listOf(IncidentType.Lift))
        val offDuty = onDuty.copy(onDuty = false)
        assertNull(DetailRules.claimBlock(onDuty, IncidentType.Lift))
        assertEquals(ClaimBlock.WrongSpecialty, DetailRules.claimBlock(onDuty, IncidentType.Fire))
        // Off duty doesn't block a claim (website + claim_incident check only the specialty)
        assertNull(DetailRules.claimBlock(offDuty, IncidentType.Lift))
        assertNull(DetailRules.claimBlock(Profile(id = "a", role = UserRole.Admin), IncidentType.Fire))
    }

    @Test fun assignListPutsOnDutyHandlersFirstAndSkipsAdmins() {
        val a = TeamMember(id = "a", fullName = "Zed", role = UserRole.Responder, onDuty = false, specialties = listOf(IncidentType.Lift))
        val b = TeamMember(id = "b", fullName = "Amy", role = UserRole.Responder, onDuty = true, specialties = listOf(IncidentType.Fire))
        val c = TeamMember(id = "c", fullName = "Bob", role = UserRole.Responder, onDuty = true, specialties = listOf(IncidentType.Lift))
        val admin = TeamMember(id = "d", fullName = "Chief", role = UserRole.Admin, onDuty = true)
        val sorted = DetailRules.sortForAssign(listOf(a, b, c, admin), IncidentType.Lift)
        assertEquals(listOf("c", "b", "a"), sorted.map { it.id })
    }
}
