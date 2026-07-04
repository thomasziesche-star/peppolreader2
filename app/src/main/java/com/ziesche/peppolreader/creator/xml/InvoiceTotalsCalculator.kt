package com.ziesche.peppolreader.creator.xml

import com.ziesche.peppolreader.creator.model.CreatorAllowanceCharge
import com.ziesche.peppolreader.creator.model.CreatorLine
import com.ziesche.peppolreader.creator.model.CreatorTotals
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.creator.model.VatBreakdownEntry
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Computes the EN 16931 monetary summation (BG-22) and the per-rate VAT breakdown (BG-23)
 * from the entered [CreatorLine]s plus optional document-level allowances/charges (BG-20/21).
 * Centralised so the form, the CII XML and the PDF table all agree on the same rounded figures.
 *
 * An allowance reduces the basis of its VAT-rate group, a charge increases it, so
 * taxBasisTotal = lineTotal − allowanceTotal + chargeTotal (BR-CO-13). With a non-STANDARD
 * [OutgoingInvoice.taxMode] every rate is forced to 0 (BR-E-05 / BR-AE-05).
 * Rounding is HALF_UP to 2 decimals, applied per VAT group (the EN 16931 convention).
 */
object InvoiceTotalsCalculator {

    /** Full figures for a draft, including allowances/charges and the tax mode. */
    fun calculate(invoice: OutgoingInvoice): CreatorTotals =
        calculate(invoice.lines, invoice.allowances, invoice.taxMode)

    fun calculate(
        lines: List<CreatorLine>,
        allowances: List<CreatorAllowanceCharge> = emptyList(),
        taxMode: String = OutgoingInvoice.TAX_MODE_STANDARD
    ): CreatorTotals {
        val exempt = taxMode != OutgoingInvoice.TAX_MODE_STANDARD
        fun effectiveRate(rate: Double) = if (exempt) 0.0 else rate

        // Group by VAT rate, summing the *per-line rounded* net basis of each group. Rounding
        // each line first (then grouping) keeps the displayed line amounts, the header
        // LineTotalAmount and the CII line LineTotalAmounts all consistent (EN 16931 BR-CO-10).
        val byRate = LinkedHashMap<Double, BigDecimal>()
        for (line in lines) {
            val net = (BigDecimal(line.quantity) * BigDecimal(line.unitPrice)).scale2()
            val rate = effectiveRate(line.vatRate)
            byRate[rate] = (byRate[rate] ?: BigDecimal.ZERO) + net
        }

        val lineTotal = byRate.values.fold(BigDecimal.ZERO) { acc, b -> acc + b }.scale2()

        // Document-level allowances/charges shift the basis of their VAT-rate group.
        var allowanceTotal = BigDecimal.ZERO
        var chargeTotal = BigDecimal.ZERO
        for (entry in allowances) {
            val amount = BigDecimal(entry.amount).scale2()
            val rate = effectiveRate(entry.vatRate)
            val current = byRate[rate] ?: BigDecimal.ZERO
            if (entry.isCharge) {
                chargeTotal += amount
                byRate[rate] = current + amount
            } else {
                allowanceTotal += amount
                byRate[rate] = current - amount
            }
        }

        val breakdown = byRate.entries
            .sortedByDescending { it.key }
            .map { (rate, rawBasis) ->
                val basis = rawBasis.scale2()
                val tax = (basis * BigDecimal(rate) / BigDecimal(100)).scale2()
                VatBreakdownEntry(rate = rate, basis = basis.toDouble(), tax = tax.toDouble())
            }

        val taxBasisTotal = breakdown.fold(BigDecimal.ZERO) { acc, e -> acc + BigDecimal(e.basis) }.scale2()
        val taxTotal = breakdown.fold(BigDecimal.ZERO) { acc, e -> acc + BigDecimal(e.tax) }.scale2()
        val grand = (taxBasisTotal + taxTotal).scale2()

        return CreatorTotals(
            lineTotal = lineTotal.toDouble(),
            taxBasisTotal = taxBasisTotal.toDouble(),
            taxTotal = taxTotal.toDouble(),
            grandTotal = grand.toDouble(),
            payable = grand.toDouble(),
            vatBreakdown = breakdown,
            allowanceTotal = allowanceTotal.scale2().toDouble(),
            chargeTotal = chargeTotal.scale2().toDouble()
        )
    }

    private fun BigDecimal.scale2(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
}
