package com.ziesche.peppolreader.creator.ui

import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.creator.xml.InvoiceTotalsCalculator
import com.ziesche.peppolreader.ui.DashboardStats
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pure aggregation of outgoing (created) invoices for the revenue dashboard. Counterpart of
 * [DashboardStats] (whose nested value types are reused), but with two creator-specific rules:
 *
 *  - Only GENERATED invoices count — drafts are working state, not revenue.
 *  - [OutgoingInvoice] has no amount columns; totals are derived once per invoice from the
 *    line items via [InvoiceTotalsCalculator]. Credit notes (type 381) count negatively.
 *
 * No Android dependencies, so it can be unit-tested directly.
 */
object CreatorDashboardStats {

    data class CustomerShare(val customerName: String, val amount: Double)

    data class Result(
        val totalRevenue: Double,
        val invoiceCount: Int,
        val openAmount: Double,
        val overdueAmount: Double,
        val perMonth: List<DashboardStats.MonthlyPoint>,
        val perMonthPaid: List<DashboardStats.MonthlyPoint>,
        val topCustomers: List<CustomerShare>,
        val statusBreakdown: DashboardStats.StatusBreakdown,
        val taxTotals: DashboardStats.TaxTotals,
        val perQuarter: List<DashboardStats.QuarterTotals>
    ) {
        val isEmpty: Boolean get() = invoiceCount == 0
    }

    /** One generated invoice with its signed totals, computed once from the line items. */
    private data class Entry(
        val inv: OutgoingInvoice,
        val net: Double,
        val tax: Double,
        val gross: Double
    )

    private fun todayIso(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())

    private fun monthOf(e: Entry): String? =
        e.inv.issueDate.takeIf { it.length >= 7 }?.substring(0, 7)

    /** "2026-Q1".."2026-Q4" from the ISO issue date, or null if it can't be derived. */
    private fun quarterOf(e: Entry): String? {
        val ym = e.inv.issueDate.takeIf { it.length >= 7 } ?: return null
        val month = ym.substring(5, 7).toIntOrNull() ?: return null
        return "${ym.substring(0, 4)}-Q${(month - 1) / 3 + 1}"
    }

    fun compute(
        invoices: List<OutgoingInvoice>,
        todayIso: String = todayIso(),
        topN: Int = 5,
        paidByInvoice: Map<Long, Double> = emptyMap()
    ): Result {
        val entries = invoices
            .filter { it.status == OutgoingInvoice.STATUS_GENERATED }
            .map { inv ->
                val totals = InvoiceTotalsCalculator.calculate(inv)
                val sign = if (inv.documentTypeCode == "381") -1.0 else 1.0
                // taxBasisTotal = net after document-level allowances/charges (equals
                // lineTotal when there are none).
                Entry(inv, totals.taxBasisTotal * sign, totals.taxTotal * sign, totals.grandTotal * sign)
            }

        // Amount still outstanding: 0 once fully paid, else gross minus recorded partial payments.
        fun remainingOf(e: Entry): Double =
            if (e.inv.paidAt != null) 0.0 else e.gross - (paidByInvoice[e.inv.id] ?: 0.0)

        val total = entries.sumOf { it.gross }
        val overdue = entries.filter { it.inv.isOverdue(todayIso) }.sumOf { remainingOf(it) }
        val open = entries.filter { it.inv.paidAt == null }.sumOf { remainingOf(it) }

        val perMonth = entries
            .mapNotNull { e -> monthOf(e)?.let { it to e.gross } }
            .groupBy({ it.first }, { it.second })
            .map { (month, amounts) -> DashboardStats.MonthlyPoint(month, amounts.sum()) }
            .sortedBy { it.month }

        val perMonthPaid = entries
            .filter { it.inv.paidAt != null }
            .mapNotNull { e -> monthOf(e)?.let { it to e.gross } }
            .groupBy({ it.first }, { it.second })
            .map { (month, amounts) -> DashboardStats.MonthlyPoint(month, amounts.sum()) }
            .sortedBy { it.month }

        val topCustomers = entries
            .groupBy { it.inv.buyerName }
            .map { (name, group) -> CustomerShare(name, group.sumOf { it.gross }) }
            .sortedByDescending { it.amount }
            .take(topN)

        // Received money = full gross for settled invoices + recorded partials for the rest.
        val paidAmount = entries.sumOf { e ->
            if (e.inv.paidAt != null) e.gross else (paidByInvoice[e.inv.id] ?: 0.0)
        }
        // "open" here excludes the overdue part so the three slices add up to the total.
        val openNotOverdue = entries
            .filter { it.inv.paidAt == null && !it.inv.isOverdue(todayIso) }
            .sumOf { remainingOf(it) }
        val status = DashboardStats.StatusBreakdown(
            paid = paidAmount, open = openNotOverdue, overdue = overdue
        )

        val taxTotals = DashboardStats.TaxTotals(
            net = entries.sumOf { it.net },
            tax = entries.sumOf { it.tax },
            gross = entries.sumOf { it.gross }
        )

        val perQuarter = entries
            .mapNotNull { e -> quarterOf(e)?.let { it to e } }
            .groupBy({ it.first }, { it.second })
            .map { (label, group) ->
                DashboardStats.QuarterTotals(
                    label = label,
                    net = group.sumOf { it.net },
                    tax = group.sumOf { it.tax },
                    gross = group.sumOf { it.gross }
                )
            }
            .sortedByDescending { it.label } // most recent quarter first

        return Result(
            totalRevenue = total,
            invoiceCount = entries.size,
            openAmount = open,
            overdueAmount = overdue,
            perMonth = perMonth,
            perMonthPaid = perMonthPaid,
            topCustomers = topCustomers,
            statusBreakdown = status,
            taxTotals = taxTotals,
            perQuarter = perQuarter
        )
    }
}
