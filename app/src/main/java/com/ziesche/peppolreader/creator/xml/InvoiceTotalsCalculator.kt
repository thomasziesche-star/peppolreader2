package com.ziesche.peppolreader.creator.xml

import com.ziesche.peppolreader.creator.model.CreatorLine
import com.ziesche.peppolreader.creator.model.CreatorTotals
import com.ziesche.peppolreader.creator.model.VatBreakdownEntry
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Computes the EN 16931 monetary summation (BG-22) and the per-rate VAT breakdown (BG-23)
 * from the entered [CreatorLine]s. Centralised so the form, the CII XML and the PDF table all
 * agree on the same rounded figures.
 *
 * Step 1 scope: no document-level allowances/charges, so the tax basis equals the line total.
 * Rounding is HALF_UP to 2 decimals, applied per VAT group (the EN 16931 convention).
 */
object InvoiceTotalsCalculator {

    fun calculate(lines: List<CreatorLine>): CreatorTotals {
        // Group by VAT rate, summing the *per-line rounded* net basis of each group. Rounding
        // each line first (then grouping) keeps the displayed line amounts, the header
        // LineTotalAmount and the CII line LineTotalAmounts all consistent (EN 16931 BR-CO-10).
        val byRate = LinkedHashMap<Double, BigDecimal>()
        for (line in lines) {
            val net = (BigDecimal(line.quantity) * BigDecimal(line.unitPrice)).scale2()
            byRate[line.vatRate] = (byRate[line.vatRate] ?: BigDecimal.ZERO) + net
        }

        val breakdown = byRate.entries
            .sortedByDescending { it.key }
            .map { (rate, rawBasis) ->
                val basis = rawBasis.scale2()
                val tax = (basis * BigDecimal(rate) / BigDecimal(100)).scale2()
                VatBreakdownEntry(rate = rate, basis = basis.toDouble(), tax = tax.toDouble())
            }

        val lineTotal = breakdown.fold(BigDecimal.ZERO) { acc, e -> acc + BigDecimal(e.basis) }.scale2()
        val taxTotal = breakdown.fold(BigDecimal.ZERO) { acc, e -> acc + BigDecimal(e.tax) }.scale2()
        val grand = (lineTotal + taxTotal).scale2()

        return CreatorTotals(
            lineTotal = lineTotal.toDouble(),
            taxBasisTotal = lineTotal.toDouble(),
            taxTotal = taxTotal.toDouble(),
            grandTotal = grand.toDouble(),
            payable = grand.toDouble(),
            vatBreakdown = breakdown
        )
    }

    private fun BigDecimal.scale2(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
}
