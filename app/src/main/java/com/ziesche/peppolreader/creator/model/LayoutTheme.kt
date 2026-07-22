package com.ziesche.peppolreader.creator.model

import org.json.JSONObject

/**
 * Visual theme for generated invoice PDFs. The defaults reproduce the shipped
 * "Warm Editorial" layout exactly, so a missing/legacy configuration renders
 * pixel-identically to before this feature existed.
 *
 * Only the accent is a free (swatch-picked) colour; paper/panel/hairline derive from
 * [paperTint] and the text greys (INK/MUTED/FAINT) stay fixed — they work on all tints
 * and keeping them out of the model protects readability.
 */
data class LayoutTheme(
    val presetId: String = PRESET_WARM,
    val accentHex: String = DEFAULT_ACCENT_HEX,
    val fontPairing: String = PAIRING_EDITORIAL,
    val paperTint: String = PAPER_WHITE,
    val tableHeaderStyle: String = HEADER_RULE
) {

    /** Page background. */
    fun paper(): IntArray = when (paperTint) {
        PAPER_IVORY -> intArrayOf(252, 249, 242)
        PAPER_COOL -> intArrayOf(250, 251, 252)
        else -> intArrayOf(255, 255, 255)
    }

    /** Grand-total panel fill, tinted to match the paper. */
    fun panel(): IntArray = when (paperTint) {
        PAPER_IVORY -> intArrayOf(245, 241, 232)
        PAPER_COOL -> intArrayOf(241, 243, 246)
        else -> intArrayOf(245, 245, 245)
    }

    /** Rule/divider colour, tinted to match the paper. */
    fun hairline(): IntArray = when (paperTint) {
        PAPER_IVORY -> intArrayOf(222, 216, 202)
        PAPER_COOL -> intArrayOf(210, 214, 220)
        else -> intArrayOf(217, 215, 206)
    }

    /** Accent colour parsed from [accentHex]; falls back to Terracotta on garbage. */
    fun accent(): IntArray = parseHex(accentHex) ?: intArrayOf(193, 95, 60)

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_PRESET, presetId)
        put(KEY_ACCENT, accentHex)
        put(KEY_PAIRING, fontPairing)
        put(KEY_PAPER, paperTint)
        put(KEY_TABLE_HEADER, tableHeaderStyle)
    }

    companion object {
        const val PRESET_WARM = "WARM"
        const val PRESET_CLASSIC = "CLASSIC"
        const val PRESET_MODERN = "MODERN"
        const val PRESET_CUSTOM = "CUSTOM"

        const val PAIRING_EDITORIAL = "EDITORIAL" // serif headings + sans body (shipped look)
        const val PAIRING_SANS = "SANS"           // bold sans headings + sans body
        const val PAIRING_SERIF = "SERIF"         // serif headings + serif body

        const val PAPER_WHITE = "WHITE"
        const val PAPER_IVORY = "IVORY"
        const val PAPER_COOL = "COOL"

        const val HEADER_RULE = "RULE" // accent rule above faint uppercase labels (shipped look)
        const val HEADER_BAND = "BAND" // filled accent band with paper-coloured labels

        const val DEFAULT_ACCENT_HEX = "#C15F3C"

        private const val KEY_PRESET = "presetId"
        private const val KEY_ACCENT = "accentHex"
        private const val KEY_PAIRING = "fontPairing"
        private const val KEY_PAPER = "paperTint"
        private const val KEY_TABLE_HEADER = "tableHeaderStyle"

        /** The three shipped presets; [PRESET_CUSTOM] keeps whatever the user picked. */
        fun preset(id: String): LayoutTheme = when (id) {
            PRESET_CLASSIC -> LayoutTheme(
                presetId = PRESET_CLASSIC, accentHex = "#1F1E1D",
                fontPairing = PAIRING_SANS, paperTint = PAPER_WHITE, tableHeaderStyle = HEADER_RULE
            )
            PRESET_MODERN -> LayoutTheme(
                presetId = PRESET_MODERN, accentHex = "#16697A",
                fontPairing = PAIRING_SANS, paperTint = PAPER_COOL, tableHeaderStyle = HEADER_BAND
            )
            else -> LayoutTheme()
        }

        fun fromJson(o: JSONObject): LayoutTheme = LayoutTheme(
            presetId = o.optString(KEY_PRESET, PRESET_WARM),
            accentHex = o.optString(KEY_ACCENT, DEFAULT_ACCENT_HEX),
            fontPairing = o.optString(KEY_PAIRING, PAIRING_EDITORIAL),
            paperTint = o.optString(KEY_PAPER, PAPER_WHITE),
            tableHeaderStyle = o.optString(KEY_TABLE_HEADER, HEADER_RULE)
        )

        private fun parseHex(hex: String): IntArray? {
            val h = hex.trim().removePrefix("#")
            if (h.length != 6 || h.any { Character.digit(it, 16) < 0 }) return null
            return intArrayOf(
                h.substring(0, 2).toInt(16),
                h.substring(2, 4).toInt(16),
                h.substring(4, 6).toInt(16)
            )
        }
    }
}
