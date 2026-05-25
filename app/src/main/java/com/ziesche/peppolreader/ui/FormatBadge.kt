package com.ziesche.peppolreader.ui

import androidx.annotation.ColorRes
import com.ziesche.peppolreader.R

/**
 * Maps the long human-readable format labels produced by the parsers (e.g. "Peppol BIS 3.0",
 * "XRechnung (UBL)", "ZUGFeRD / Factur-X") to a short pill label + a colour pair for the
 * overview list.
 *
 * Light/dark palette is selected automatically via the `values-night/` resource qualifier.
 */
object FormatBadge {

    data class Style(
        val label: String,
        @ColorRes val bgColorRes: Int,
        @ColorRes val fgColorRes: Int
    )

    fun forLabel(label: String?): Style? {
        if (label.isNullOrBlank()) return null
        val l = label.lowercase()
        return when {
            "xrechnung" in l -> Style("XRechnung", R.color.badge_xrechnung_bg, R.color.badge_xrechnung_fg)
            "peppol" in l -> Style("Peppol", R.color.badge_peppol_bg, R.color.badge_peppol_fg)
            "factur-x" in l || "facturx" in l ->
                Style("Factur-X", R.color.badge_zugferd_bg, R.color.badge_zugferd_fg)
            "zugferd" in l -> Style("ZUGFeRD", R.color.badge_zugferd_bg, R.color.badge_zugferd_fg)
            "en 16931" in l || "en16931" in l ->
                Style("EN 16931", R.color.badge_neutral_bg, R.color.badge_neutral_fg)
            "cii" in l -> Style("CII", R.color.badge_neutral_bg, R.color.badge_neutral_fg)
            "ubl" in l -> Style("UBL", R.color.badge_neutral_bg, R.color.badge_neutral_fg)
            else -> Style(label.take(12), R.color.badge_neutral_bg, R.color.badge_neutral_fg)
        }
    }
}
