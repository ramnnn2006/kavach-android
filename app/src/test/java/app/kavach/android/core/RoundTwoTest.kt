package app.kavach.android.core

import app.kavach.android.core.data.session.MemoryPreferences
import app.kavach.android.core.ui.theme.DarkKavachColors
import app.kavach.android.core.ui.theme.LightKavachColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class RoundTwoTest {
    @Test fun memorySessionDoesNotSurviveASecondStore() {
        val first = MemoryPreferences()
        first.edit().putString("session", "token").apply()
        assertEquals("token", first.getString("session", null))
        assertNull(MemoryPreferences().getString("session", null))
        first.edit().remove("session").apply()
        assertNull(first.getString("session", null))
    }
    @Test fun memorySessionSupportsClearAndPrimitiveValues() {
        val prefs = MemoryPreferences()
        prefs.edit().putBoolean("b", true).putInt("i", 4).putLong("l", 10L).commit()
        assertTrue(prefs.getBoolean("b", false))
        assertEquals(4, prefs.getInt("i", 0))
        assertEquals(10L, prefs.getLong("l", 0))
        prefs.edit().clear().commit()
        assertTrue(prefs.all.isEmpty())
    }
    @Test fun actionContainersMeetAAInBothThemes() {
        for (palette in listOf(LightKavachColors, DarkKavachColors)) {
            for (container in listOf(palette.primary, palette.danger, palette.success)) {
                assertTrue("$container contrast", contrast(container, palette.onAccent) >= 4.5f)
            }
        }
    }
    @Test fun lightWarningTextMeetsAAOnWhite() {
        assertTrue(contrast(LightKavachColors.warning, Color.White) >= 4.5f)
    }
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance(); val y = b.luminance()
        return (maxOf(x,y)+0.05f)/(minOf(x,y)+0.05f)
    }
}
