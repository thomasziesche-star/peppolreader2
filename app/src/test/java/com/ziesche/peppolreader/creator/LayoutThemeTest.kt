package com.ziesche.peppolreader.creator

import com.ziesche.peppolreader.creator.model.LayoutTheme
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the [LayoutTheme] contract: JSON round-trip, default fallbacks for legacy/garbage
 * input (a user without a saved theme must get the shipped look), preset values and the
 * accent-hex parsing. Robolectric for org.json, same as the other creator model tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LayoutThemeTest {

    @Test
    fun `default theme reproduces the shipped editorial palette`() {
        val t = LayoutTheme()
        assertArrayEquals(intArrayOf(255, 255, 255), t.paper())
        assertArrayEquals(intArrayOf(245, 245, 245), t.panel())
        assertArrayEquals(intArrayOf(217, 215, 206), t.hairline())
        assertArrayEquals(intArrayOf(193, 95, 60), t.accent())
        assertEquals(LayoutTheme.PAIRING_EDITORIAL, t.fontPairing)
        assertEquals(LayoutTheme.HEADER_RULE, t.tableHeaderStyle)
    }

    @Test
    fun `json round-trip preserves every field`() {
        val custom = LayoutTheme(
            presetId = LayoutTheme.PRESET_CUSTOM,
            accentHex = "#2E5E8C",
            fontPairing = LayoutTheme.PAIRING_SERIF,
            paperTint = LayoutTheme.PAPER_IVORY,
            tableHeaderStyle = LayoutTheme.HEADER_BAND
        )
        assertEquals(custom, LayoutTheme.fromJson(JSONObject(custom.toJson().toString())))
    }

    @Test
    fun `empty or foreign json falls back to defaults`() {
        assertEquals(LayoutTheme(), LayoutTheme.fromJson(JSONObject()))
        assertEquals(LayoutTheme(), LayoutTheme.fromJson(JSONObject("""{"unknown":"key"}""")))
    }

    @Test
    fun `garbage accent hex falls back to terracotta`() {
        assertArrayEquals(intArrayOf(193, 95, 60), LayoutTheme(accentHex = "kaputt").accent())
        assertArrayEquals(intArrayOf(193, 95, 60), LayoutTheme(accentHex = "#12").accent())
        assertArrayEquals(intArrayOf(22, 105, 122), LayoutTheme(accentHex = "#16697A").accent())
    }

    @Test
    fun `presets carry their documented values`() {
        val classic = LayoutTheme.preset(LayoutTheme.PRESET_CLASSIC)
        assertEquals("#1F1E1D", classic.accentHex)
        assertEquals(LayoutTheme.PAIRING_SANS, classic.fontPairing)

        val modern = LayoutTheme.preset(LayoutTheme.PRESET_MODERN)
        assertEquals("#16697A", modern.accentHex)
        assertEquals(LayoutTheme.PAPER_COOL, modern.paperTint)
        assertEquals(LayoutTheme.HEADER_BAND, modern.tableHeaderStyle)

        assertEquals(LayoutTheme(), LayoutTheme.preset(LayoutTheme.PRESET_WARM))
    }
}
