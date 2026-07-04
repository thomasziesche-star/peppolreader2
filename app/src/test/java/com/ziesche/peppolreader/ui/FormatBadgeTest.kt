package com.ziesche.peppolreader.ui

import com.ziesche.peppolreader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the label→pill mapping of [FormatBadge]: known formats get their short label and colour
 * pair, precedence between overlapping keywords is stable, unknown labels fall back to a
 * truncated neutral pill and blank labels to no badge at all.
 */
class FormatBadgeTest {

    @Test
    fun `blank or null label yields no badge`() {
        assertNull(FormatBadge.forLabel(null))
        assertNull(FormatBadge.forLabel(""))
        assertNull(FormatBadge.forLabel("   "))
    }

    @Test
    fun `known formats map to their pill label`() {
        assertEquals("Peppol", FormatBadge.forLabel("Peppol BIS 3.0")?.label)
        assertEquals("XRechnung", FormatBadge.forLabel("XRechnung (UBL)")?.label)
        assertEquals("XRechnung", FormatBadge.forLabel("XRechnung (CII)")?.label)
        assertEquals("ZUGFeRD", FormatBadge.forLabel("ZUGFeRD 2.1")?.label)
        assertEquals("Factur-X", FormatBadge.forLabel("Factur-X 1.0")?.label)
        assertEquals("KSeF FA(3)", FormatBadge.forLabel("KSeF FA(3)")?.label)
        assertEquals("EN 16931", FormatBadge.forLabel("EN 16931 (CII)")?.label)
        assertEquals("UBL", FormatBadge.forLabel("UBL Invoice")?.label)
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals("ZUGFeRD", FormatBadge.forLabel("zugferd")?.label)
        assertEquals("Peppol", FormatBadge.forLabel("PEPPOL")?.label)
    }

    @Test
    fun `combined zugferd factur-x label prefers factur-x but shares the colour pair`() {
        val combined = FormatBadge.forLabel("ZUGFeRD / Factur-X")!!
        assertEquals("Factur-X", combined.label)
        val zugferd = FormatBadge.forLabel("ZUGFeRD")!!
        assertEquals(zugferd.bgColorRes, combined.bgColorRes)
        assertEquals(zugferd.fgColorRes, combined.fgColorRes)
    }

    @Test
    fun `xrechnung wins over the generic ubl and cii keywords`() {
        assertEquals("XRechnung", FormatBadge.forLabel("XRechnung (UBL)")?.label)
        assertEquals(R.color.badge_xrechnung_bg, FormatBadge.forLabel("XRechnung (CII)")?.bgColorRes)
    }

    @Test
    fun `unknown label falls back to truncated neutral pill`() {
        val style = FormatBadge.forLabel("Some Exotic Custom Format Name")!!
        assertEquals("Some Exotic C".take(12), style.label)
        assertEquals(12, style.label.length)
        assertEquals(R.color.badge_neutral_bg, style.bgColorRes)
        assertEquals(R.color.badge_neutral_fg, style.fgColorRes)
    }
}
