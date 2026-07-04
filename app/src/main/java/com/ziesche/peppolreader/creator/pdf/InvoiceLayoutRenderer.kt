package com.ziesche.peppolreader.creator.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.ziesche.peppolreader.creator.model.CreatorLine
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.creator.xml.InvoiceTotalsCalculator
import java.util.Locale

/**
 * Draws the human-readable invoice page(s) in a warm, editorial letterhead style (DIN-5008-like
 * structure): ivory paper, a serif company wordmark top left, the logo top right, sender line
 * above the recipient address window, a key-data block on the right, a serif document title,
 * a hairline-ruled item table under a terracotta rule, a cream totals panel and a three-column
 * footer with master data on every page.
 *
 * Pure layout — all PDF/A plumbing (fonts, OutputIntent, XMP, embedding) stays in
 * [ZugferdPdfA3Writer]. Page numbers are stamped in a second pass once the page count is known.
 */
class InvoiceLayoutRenderer(
    private val doc: PDDocument,
    private val regular: PDFont,
    private val bold: PDFont,
    private val serif: PDFont,
    private val invoice: OutgoingInvoice,
    private val logo: Bitmap?
) {

    private val lines: List<CreatorLine> = invoice.lines.filter { it.description.isNotBlank() }
    private val totals = InvoiceTotalsCalculator.calculate(lines)
    private val currency = invoice.currency.ifBlank { "EUR" }
    private val isCreditNote = invoice.documentTypeCode == "381"

    private val pages = mutableListOf<PDPage>()
    private lateinit var cs: PDPageContentStream
    /** Baseline for the next text row; decreases while drawing. */
    private var y = 0f

    fun render() {
        startPage()
        drawLetterhead()
        drawTableHeader()
        lines.forEachIndexed { index, line -> drawItemRow(index + 1, line) }
        drawTotals()
        drawPaymentBlock()
        cs.close()
        stampPageNumbers()
    }

    // ----- page management ------------------------------------------------------------------

    private fun startPage() {
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        pages.add(page)
        cs = PDPageContentStream(doc, page)
        // Ivory paper background under everything else on the page.
        fillRect(0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height, PAPER)
        drawFooter()
        y = CONTENT_TOP
    }

    /** Breaks to a fresh page when fewer than [needed] points remain above the footer. */
    private fun ensureSpace(needed: Float, repeatTableHeader: Boolean = false) {
        if (y - needed >= CONTENT_BOTTOM) return
        cs.close()
        startPage()
        if (repeatTableHeader) drawTableHeader()
    }

    // ----- letterhead (first page only) -------------------------------------------------------

    private fun drawLetterhead() {
        // Serif company wordmark top left; logo (when present) top right.
        text(serif, 17f, MARGIN_L, CONTENT_TOP - 14f, invoice.sellerName, ACCENT)
        if (logo != null && logo.width > 0 && logo.height > 0) {
            val image = LosslessFactory.createFromImage(doc, logo)
            val scale = minOf(LOGO_MAX_WIDTH / logo.width, LOGO_MAX_HEIGHT / logo.height)
            val w = logo.width * scale
            val h = logo.height * scale
            cs.drawImage(image, MARGIN_R - w, CONTENT_TOP - h, w, h)
        }

        // Tiny sender line above the address window, as on a windowed envelope.
        val sender = listOfNotNull(
            invoice.sellerName.takeIf { it.isNotBlank() },
            invoice.sellerStreet?.takeIf { it.isNotBlank() },
            listOfNotNull(invoice.sellerZip, invoice.sellerCity).joinToString(" ").takeIf { it.isNotBlank() }
        ).joinToString("  ·  ")
        y = 716f
        text(regular, 7f, MARGIN_L, y, sender, FAINT)
        rule(MARGIN_L, y - 4f, MARGIN_L + 240f, HAIRLINE)
        y -= 22f

        // Recipient address block (left).
        val recipientTop = y
        val recipient = buildList {
            add(invoice.buyerName)
            invoice.buyerStreet?.takeIf { it.isNotBlank() }?.let { add(it) }
            listOfNotNull(invoice.buyerZip, invoice.buyerCity).joinToString(" ").takeIf { it.isNotBlank() }?.let { add(it) }
            invoice.buyerCountry?.takeIf { it.isNotBlank() && !it.equals(invoice.sellerCountry ?: "DE", true) }?.let { add(countryName(it)) }
        }
        for (row in recipient) {
            text(regular, 11f, MARGIN_L, y, row)
            y -= 14.5f
        }
        val recipientBottom = y

        // Key-data block (right): label left, value right-aligned at the margin.
        var infoY = recipientTop
        val numberLabel = if (isCreditNote) "Gutschrift-Nr." else "Rechnungs-Nr."
        val info = buildList {
            add(numberLabel to invoice.invoiceNumber)
            add("Rechnungsdatum" to displayDate(invoice.issueDate))
            invoice.dueDate?.takeIf { it.isNotBlank() }?.let { add("Fällig am" to displayDate(it)) }
            invoice.sellerVatId?.takeIf { it.isNotBlank() }?.let { add("USt-IdNr." to it) }
                ?: invoice.sellerTaxNumber?.takeIf { it.isNotBlank() }?.let { add("Steuernummer" to it) }
            invoice.buyerVatId?.takeIf { it.isNotBlank() }?.let { add("USt-IdNr. Kunde" to it) }
        }
        for ((label, value) in info) {
            text(regular, 8.5f, INFO_X, infoY, label, FAINT)
            textRight(regular, 10f, MARGIN_R, infoY, value)
            infoY -= 15f
        }

        // Editorial document title: large serif word, the number in terracotta beneath it.
        y = minOf(recipientBottom, infoY) - 38f
        val title = if (isCreditNote) "Gutschrift" else "Rechnung"
        text(serif, 24f, MARGIN_L, y, title)
        y -= 17f
        text(regular, 12f, MARGIN_L, y, invoice.invoiceNumber, ACCENT)
        y -= 20f
    }

    // ----- line-item table --------------------------------------------------------------------

    private fun drawTableHeader() {
        ensureSpace(60f)
        // Terracotta rule on top, quiet uppercase column labels, hairline underneath.
        fillRect(MARGIN_L, y + 8f, MARGIN_R - MARGIN_L, 1.8f, ACCENT)
        y -= 6f
        text(regular, 8.5f, COL_POS, y, "POS.", FAINT)
        text(regular, 8.5f, COL_DESC, y, "BESCHREIBUNG", FAINT)
        textRight(regular, 8.5f, COL_QTY, y, "MENGE", FAINT)
        textRight(regular, 8.5f, COL_PRICE, y, "EINZELPREIS", FAINT)
        textRight(regular, 8.5f, COL_VAT, y, "UST. %", FAINT)
        textRight(regular, 8.5f, COL_TOTAL, y, "BETRAG", FAINT)
        rule(MARGIN_L, y - 5f, MARGIN_R, HAIRLINE)
        y -= 19f
    }

    private fun drawItemRow(position: Int, line: CreatorLine) {
        val descWidth = COL_QTY - 78f - COL_DESC
        val descLines = line.description.split("\n").flatMap { wrap(it, regular, 9.5f, descWidth) }
        val rowHeight = descLines.size * 12f + 6f
        ensureSpace(rowHeight + 10f, repeatTableHeader = true)

        val net = line.quantity * line.unitPrice
        text(regular, 9.5f, COL_POS, y, position.toString(), FAINT)
        textRight(regular, 9.5f, COL_QTY, y, "${trimNum(line.quantity)} ${unitLabel(line.unit)}")
        textRight(regular, 9.5f, COL_PRICE, y, money(line.unitPrice))
        textRight(regular, 9.5f, COL_VAT, y, trimNum(line.vatRate))
        textRight(regular, 9.5f, COL_TOTAL, y, money(net))
        descLines.forEach { row ->
            text(regular, 9.5f, COL_DESC, y, row)
            y -= 12f
        }
        y -= 6f
        rule(MARGIN_L, y + 4f, MARGIN_R, HAIRLINE)
        y -= 8f
    }

    // ----- totals + payment --------------------------------------------------------------------

    private fun drawTotals() {
        val rows = 1 + totals.vatBreakdown.size
        ensureSpace(rows * 15f + 46f)
        y -= 4f

        textRight(regular, 10f, TOTALS_LABEL_X, y, "Zwischensumme (netto)", MUTED)
        textRight(regular, 10f, COL_TOTAL, y, money(totals.lineTotal) + " " + currencySymbol())
        y -= 15f
        for (e in totals.vatBreakdown) {
            textRight(regular, 10f, TOTALS_LABEL_X, y, "zzgl. ${trimNum(e.rate)} % USt.", MUTED)
            textRight(regular, 10f, COL_TOTAL, y, money(e.tax) + " " + currencySymbol(), MUTED)
            y -= 15f
        }

        // Grand total in a cream panel, set in terracotta serif.
        fillRect(TOTALS_BOX_X, y - 8f, MARGIN_R - TOTALS_BOX_X, 24f, PANEL)
        textRight(serif, 14f, TOTALS_LABEL_X, y - 1f, "Gesamtbetrag", ACCENT)
        textRight(serif, 14f, COL_TOTAL - 10f, y - 1f, money(totals.grandTotal) + " " + currencySymbol(), ACCENT)
        y -= 38f
    }

    private fun drawPaymentBlock() {
        val rows = buildList {
            invoice.dueDate?.takeIf { it.isNotBlank() }?.let {
                add(if (isCreditNote) "Der Betrag wird bis zum ${displayDate(it)} erstattet." else "Zahlbar ohne Abzug bis zum ${displayDate(it)}.")
            }
            if (!invoice.sellerIban.isNullOrBlank() && !isCreditNote) {
                add("Bitte geben Sie bei der Überweisung die Rechnungs-Nr. ${invoice.invoiceNumber} als Verwendungszweck an.")
            }
            invoice.paymentTermsNote?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (rows.isEmpty()) return

        val wrapped = rows.flatMap { wrap(it, regular, 9.5f, MARGIN_R - MARGIN_L) }
        ensureSpace(wrapped.size * 13f + 10f)
        for (row in wrapped) {
            text(regular, 9.5f, MARGIN_L, y, row)
            y -= 13f
        }
    }

    // ----- footer -------------------------------------------------------------------------------

    /** Three-column master-data footer; drawn on every page right after it is created. */
    private fun drawFooter() {
        rule(MARGIN_L, FOOTER_RULE_Y, MARGIN_R, HAIRLINE)

        val col1 = buildList {
            add(invoice.sellerName)
            invoice.sellerStreet?.takeIf { it.isNotBlank() }?.let { add(it) }
            listOfNotNull(invoice.sellerZip, invoice.sellerCity).joinToString(" ").takeIf { it.isNotBlank() }?.let {
                add(it + (invoice.sellerCountry?.takeIf { c -> c.isNotBlank() && c != "DE" }?.let { c -> ", ${countryName(c)}" } ?: ""))
            }
        }
        val col2 = buildList {
            invoice.sellerVatId?.takeIf { it.isNotBlank() }?.let { add("USt-IdNr.: $it") }
            invoice.sellerTaxNumber?.takeIf { it.isNotBlank() }?.let { add("Steuernr.: $it") }
            invoice.sellerEmail?.takeIf { it.isNotBlank() }?.let { add(it) }
            invoice.sellerPhone?.takeIf { it.isNotBlank() }?.let { add("Tel. $it") }
        }
        val col3 = buildList {
            invoice.sellerIban?.takeIf { it.isNotBlank() }?.let { add("IBAN: ${groupIban(it)}") }
            invoice.sellerBic?.takeIf { it.isNotBlank() }?.let { add("BIC: $it") }
        }

        var fy = FOOTER_RULE_Y - 11f
        for (i in 0 until maxOf(col1.size, col2.size, col3.size).coerceAtMost(4)) {
            col1.getOrNull(i)?.let { text(regular, 7f, MARGIN_L, fy, it, FAINT) }
            col2.getOrNull(i)?.let { text(regular, 7f, FOOTER_COL2_X, fy, it, FAINT) }
            col3.getOrNull(i)?.let { text(regular, 7f, FOOTER_COL3_X, fy, it, FAINT) }
            fy -= 9.5f
        }
    }

    /** Second pass: "Seite n von m" centered below the footer, now that m is known. */
    private fun stampPageNumbers() {
        if (pages.size < 2) return
        pages.forEachIndexed { index, page ->
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { c ->
                val label = "Seite ${index + 1} von ${pages.size}"
                val width = regular.getStringWidth(label) / 1000f * 7f
                c.setNonStrokingColor(FAINT[0], FAINT[1], FAINT[2])
                c.beginText()
                c.setFont(regular, 7f)
                c.newLineAtOffset((PDRectangle.A4.width - width) / 2f, PAGE_NUMBER_Y)
                c.showText(label)
                c.endText()
            }
        }
    }

    // ----- low-level drawing helpers -------------------------------------------------------------

    private fun text(font: PDFont, size: Float, x: Float, baseline: Float, s: String, color: IntArray = INK) {
        cs.setNonStrokingColor(color[0], color[1], color[2])
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset(x, baseline)
        cs.showText(sanitize(font, s))
        cs.endText()
    }

    private fun textRight(font: PDFont, size: Float, xRight: Float, baseline: Float, s: String, color: IntArray = INK) {
        text(font, size, xRight - strWidth(font, size, s), baseline, s, color)
    }

    private fun strWidth(font: PDFont, size: Float, s: String): Float =
        font.getStringWidth(sanitize(font, s)) / 1000f * size

    private fun rule(x1: Float, ry: Float, x2: Float, color: IntArray) {
        cs.setStrokingColor(color[0], color[1], color[2])
        cs.setLineWidth(0.6f)
        cs.moveTo(x1, ry)
        cs.lineTo(x2, ry)
        cs.stroke()
    }

    private fun fillRect(x: Float, ry: Float, w: Float, h: Float, color: IntArray) {
        cs.setNonStrokingColor(color[0], color[1], color[2])
        cs.addRect(x, ry, w, h)
        cs.fill()
    }

    /** Greedy word wrap; words wider than [maxWidth] are hard-split. */
    private fun wrap(s: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (rawWord in s.trim().split(Regex("\\s+"))) {
            var word = rawWord
            while (strWidth(font, size, word) > maxWidth && word.length > 1) {
                var cut = word.length - 1
                while (cut > 1 && strWidth(font, size, word.take(cut)) > maxWidth) cut--
                if (current.isNotEmpty()) { out.add(current.toString()); current = StringBuilder() }
                out.add(word.take(cut))
                word = word.drop(cut)
            }
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (strWidth(font, size, candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) out.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out.ifEmpty { listOf("") }
    }

    // ----- formatting helpers ----------------------------------------------------------------

    private fun money(value: Double): String = String.format(Locale.GERMANY, "%,.2f", value)

    private fun currencySymbol(): String = if (currency == "EUR") "€" else currency

    private fun trimNum(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.GERMANY, "%.2f", value)

    private fun displayDate(iso: String): String = runCatching {
        val parts = iso.split("-")
        "${parts[2]}.${parts[1]}.${parts[0]}"
    }.getOrDefault(iso)

    /** Common UN/ECE Rec 20 unit codes → short German labels; unknown codes pass through. */
    private fun unitLabel(code: String): String = when (code.uppercase(Locale.ROOT)) {
        "C62", "H87", "EA" -> "Stk."
        "HUR" -> "Std."
        "DAY" -> "Tage"
        "MON" -> "Monate"
        "ANN" -> "Jahre"
        "KGM" -> "kg"
        "GRM" -> "g"
        "TNE" -> "t"
        "LTR" -> "l"
        "MTR" -> "m"
        "KMT" -> "km"
        "MTK" -> "m²"
        "MTQ" -> "m³"
        "KWH" -> "kWh"
        "LS" -> "pausch."
        else -> code
    }

    /** IBAN in readable groups of four. */
    private fun groupIban(iban: String): String =
        iban.replace(" ", "").chunked(4).joinToString(" ")

    private fun countryName(code: String): String = when (code.uppercase(Locale.ROOT)) {
        "DE" -> "Deutschland"
        "AT" -> "Österreich"
        "CH" -> "Schweiz"
        "NL" -> "Niederlande"
        "BE" -> "Belgien"
        "FR" -> "Frankreich"
        "LU" -> "Luxemburg"
        "PL" -> "Polen"
        "IT" -> "Italien"
        "ES" -> "Spanien"
        else -> code
    }

    /** Replaces characters the embedded font cannot encode so showText never throws. */
    private fun sanitize(font: PDFont, s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(runCatching { font.getStringWidth(ch.toString()); ch }.getOrDefault('?'))
        }
        return sb.toString()
    }

    companion object {
        // Geometry (PDF points; A4 = 595.28 x 841.89).
        private const val MARGIN_L = 56f
        private const val MARGIN_R = 539f
        private const val CONTENT_TOP = 792f
        private const val CONTENT_BOTTOM = 116f
        private const val FOOTER_RULE_Y = 92f
        private const val PAGE_NUMBER_Y = 36f
        private const val INFO_X = 360f
        private const val FOOTER_COL2_X = 240f
        private const val FOOTER_COL3_X = 412f
        private const val LOGO_MAX_WIDTH = 170f
        private const val LOGO_MAX_HEIGHT = 64f

        // Table columns: Pos | Beschreibung | Menge | Einzelpreis | USt % | Betrag.
        private const val COL_POS = 56f
        private const val COL_DESC = 80f
        private const val COL_QTY = 352f
        private const val COL_PRICE = 432f
        private const val COL_VAT = 478f
        private const val COL_TOTAL = 539f

        // Totals block.
        private const val TOTALS_BOX_X = 330f
        private const val TOTALS_LABEL_X = 460f

        // Warm editorial palette (0..255 RGB): ivory paper, terracotta accent, warm grays.
        private val PAPER = intArrayOf(250, 249, 245)
        private val PANEL = intArrayOf(240, 238, 229)
        private val INK = intArrayOf(31, 30, 29)
        private val MUTED = intArrayOf(107, 106, 100)
        private val FAINT = intArrayOf(155, 154, 147)
        private val ACCENT = intArrayOf(193, 95, 60)
        private val HAIRLINE = intArrayOf(217, 215, 206)
    }
}
