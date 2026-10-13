package app.kavach.android.core

import app.kavach.android.R
import app.kavach.android.core.data.model.parseInstant
import app.kavach.android.core.data.remote.ErrorCode
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.data.remote.toKavach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class ErrorsTest {
    @Test fun knownServerDetailsGetTheirOwnText() {
        assertEquals(R.string.common_err_closed, KavachException(ErrorCode.Invalid, "Incident is already closed").messageRes())
        assertEquals(R.string.common_err_phone_taken, KavachException(ErrorCode.Invalid, "This phone number is already on the list").messageRes())
        // Unknown detail text never reaches the screen
        assertEquals(R.string.common_error_invalid, KavachException(ErrorCode.Invalid, "some raw database text").messageRes())
        assertEquals(R.string.common_err_already_claimed, KavachException(ErrorCode.AlreadyClaimed, "x").messageRes())
        assertEquals(R.string.common_error_generic, KavachException(ErrorCode.Duplicate).messageRes())
        assertEquals(R.string.common_error_generic, IllegalStateException("boom").messageRes())
    }

    @Test fun outboxRulesMatchWebsite() {
        // outbox.js PERMANENT and ReportDetails QUEUE_CODES
        assertTrue(ErrorCode.Invalid.isPermanent)
        assertTrue(ErrorCode.Forbidden.isPermanent)
        assertFalse(ErrorCode.Unknown.isPermanent)
        assertTrue(ErrorCode.Offline.shouldQueue)
        assertTrue(ErrorCode.Timeout.shouldQueue)
        assertTrue(ErrorCode.Unknown.shouldQueue)
        assertFalse(ErrorCode.Invalid.shouldQueue)
        assertFalse(ErrorCode.AlreadyClaimed.shouldQueue)
    }

    @Test fun networkFailuresAreOffline() {
        assertEquals(ErrorCode.Offline, IOException("reset").toKavach(online = true).code)
        assertEquals(ErrorCode.Offline, IllegalStateException("x").toKavach(online = false).code)
        assertEquals(ErrorCode.Unknown, IllegalStateException("x").toKavach(online = true).code)
        val known = KavachException(ErrorCode.NotFound)
        assertSame(known, known.toKavach(online = false))
    }

    @Test fun parsesPostgresTimestampsWithoutT() {
        val expected = Instant.parse("2026-10-08T12:34:56Z")
        assertEquals(expected, parseInstant("2026-10-08T12:34:56+00:00"))
        assertEquals(expected, parseInstant("2026-10-08 12:34:56+00"))
        assertEquals(expected, parseInstant("2026-10-08 18:04:56+05:30"))
        assertEquals(expected, parseInstant("2026-10-08T12:34:56Z"))
    }
}
