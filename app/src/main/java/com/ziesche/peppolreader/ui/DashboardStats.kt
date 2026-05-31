package com.ziesche.peppolreader.ui

import com.ziesche.peppolreader.data.model.Invoice
import com.ziesche.peppolreader.data.model.signedGross
import com.ziesche.peppolreader.data.model.signedNet
import com.ziesche.peppolreader.data.model.signedPayable
import com.ziesche.peppolreader.data.model.signedTax
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pure aggregation of a list of invoices for the dashboard. No Android dependencies, so it can be
 * unit-tested directly. All money sums use the signed amount (credit notes count negatively), so
 * invoice 1000 + credit note 400 for one supplier nets to 600.
 *
 * @param todayIso today's date as ISO `yyyy-MM-dd`, injectable for deterministic tests.
 */
object DashboardStats {

    data class MonthlyPoint(val month: String, val amount: Double)

    data class SupplierShare(val supplierName: String, val amount: Double)

    data class StatusBreakdown(val paid: Double, val open: Double, val overdue: Double)

    data class TaxTotals(val net: Double, val tax: Double, val gross: Double)

    data class Result(
        val totalExpenses: Double,
        val invoiceCount: Int,
        val openAmount: Double,
        val overdueAmount: Double,
        val perMonth: List<MonthlyPoint>,
        val perMonthPaid: List<MonthlyPoint>,
        val topSuppliers: List<SupplierShare>,
        val statusBreakdown: StatusBreakdown,
        val taxTotals: TaxTotals
    ) {
        val isEmpty: Boolean get() = invoiceCount == 0
    }

    private fun todayIso(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())

    /** An invoice is overdue when it is unpaid and its due date lies strictly before today. */
    private fun isOverdue(invoice: Invoice, todayIso: String): Boolean {
        val due = invoice.dueDate ?: return false
        return invoice.paidAt == null && due < todayIso
    }

    private fun monthOf(invoice: Invoice): String? =
        invoice.issueDate.takeIf { it.length >= 7 }?.substring(0, 7)

    fun compute(invoices: List<Invoice>, todayIso: String = todayIso(), topN: Int = 5): Result {
        val total = invoices.sumOf { it.signedPayable }
        val open = invoices.filter { it.paidAt == null }.sumOf { it.signedPayable }
        val overdue = invoices.filter { isOverdue(it, todayIso) }.sumOf { it.signedPayable }

        val perMonth = invoices
            .mapNotNull { inv -> monthOf(inv)?.let { it to inv.signedPayable } }
            .groupBy({ it.first }, { it.second })
            .map { (month, amounts) -> MonthlyPoint(month, amounts.sum()) }
            .sortedBy { it.month }

        val perMonthPaid = invoices
            .filter { it.paidAt != null }
            .mapNotNull { inv -> monthOf(inv)?.let { it to inv.signedPayable } }
            .groupBy({ it.first }, { it.second })
            .map { (month, amounts) -> MonthlyPoint(month, amounts.sum()) }
            .sortedBy { it.month }

        val topSuppliers = invoices
            .groupBy { it.supplierName }
            .map { (name, group) -> SupplierShare(name, group.sumOf { it.signedPayable }) }
            .sortedByDescending { it.amount }
            .take(topN)

        val paidAmount = invoices.filter { it.paidAt != null }.sumOf { it.signedPayable }
        // "open" here excludes the overdue part so the three slices add up to the total.
        val openNotOverdue = invoices
            .filter { it.paidAt == null && !isOverdue(it, todayIso) }
            .sumOf { it.signedPayable }
        val status = StatusBreakdown(paid = paidAmount, open = openNotOverdue, overdue = overdue)

        val taxTotals = TaxTotals(
            net = invoices.sumOf { it.signedNet },
            tax = invoices.sumOf { it.signedTax },
            gross = invoices.sumOf { it.signedGross }
        )

        return Result(
            totalExpenses = total,
            invoiceCount = invoices.size,
            openAmount = open,
            overdueAmount = overdue,
            perMonth = perMonth,
            perMonthPaid = perMonthPaid,
            topSuppliers = topSuppliers,
            statusBreakdown = status,
            taxTotals = taxTotals
        )
    }
}
