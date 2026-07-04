package com.ziesche.peppolreader.ui

import com.ziesche.peppolreader.data.model.DocumentType
import com.ziesche.peppolreader.data.model.Invoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStatsTest {

    private fun invoice(
        supplier: String = "Acme",
        issueDate: String = "2026-03-10",
        payable: Double = 0.0,
        net: Double = 0.0,
        tax: Double = 0.0,
        gross: Double = 0.0,
        documentTypeCode: String? = DocumentType.INVOICE,
        dueDate: String? = null,
        paidAt: Long? = null
    ) = Invoice(
        invoiceId = "INV",
        issueDate = issueDate,
        dueDate = dueDate,
        supplierName = supplier,
        customerName = "Customer",
        netAmount = net,
        taxAmount = tax,
        grossAmount = gross,
        payableAmount = payable,
        xmlContent = "",
        fileName = "f.xml",
        documentTypeCode = documentTypeCode,
        paidAt = paidAt
    )

    @Test
    fun creditNoteReducesSupplierTotal() {
        // User's example: invoice 1000 + credit note 400 for one supplier => 600.
        val invoices = listOf(
            invoice(supplier = "Lieferant", payable = 1000.0, documentTypeCode = DocumentType.INVOICE),
            invoice(supplier = "Lieferant", payable = 400.0, documentTypeCode = DocumentType.CREDIT_NOTE)
        )

        val result = DashboardStats.compute(invoices, todayIso = "2026-05-30")

        assertEquals(600.0, result.totalExpenses, 0.001)
        assertEquals(1, result.topSuppliers.size)
        assertEquals("Lieferant", result.topSuppliers[0].supplierName)
        assertEquals(600.0, result.topSuppliers[0].amount, 0.001)
    }

    @Test
    fun overdueIsUnpaidAndPastDue() {
        val invoices = listOf(
            invoice(payable = 100.0, dueDate = "2026-05-01", paidAt = null),          // overdue
            invoice(payable = 50.0, dueDate = "2026-05-01", paidAt = 1_000L),         // paid -> not overdue
            invoice(payable = 70.0, dueDate = "2026-12-31", paidAt = null),           // future due -> open
            invoice(payable = 30.0, dueDate = null, paidAt = null)                    // no due date -> open
        )

        val result = DashboardStats.compute(invoices, todayIso = "2026-05-30")

        assertEquals(100.0, result.overdueAmount, 0.001)
        assertEquals(200.0, result.openAmount, 0.001) // 100 + 70 + 30 (all unpaid)
        // status breakdown slices add up to total
        val s = result.statusBreakdown
        assertEquals(50.0, s.paid, 0.001)
        assertEquals(100.0, s.overdue, 0.001)
        assertEquals(100.0, s.open, 0.001) // open excluding overdue: 70 + 30
        assertEquals(result.totalExpenses, s.paid + s.open + s.overdue, 0.001)
    }

    @Test
    fun perMonthAggregatesAndSorts() {
        val invoices = listOf(
            invoice(issueDate = "2026-02-05", payable = 100.0),
            invoice(issueDate = "2026-02-20", payable = 50.0),
            invoice(issueDate = "2026-01-15", payable = 200.0)
        )

        val perMonth = DashboardStats.compute(invoices, todayIso = "2026-05-30").perMonth

        assertEquals(listOf("2026-01", "2026-02"), perMonth.map { it.month })
        assertEquals(200.0, perMonth[0].amount, 0.001)
        assertEquals(150.0, perMonth[1].amount, 0.001)
    }

    @Test
    fun taxTotalsAreSignedAndConsistent() {
        val invoices = listOf(
            invoice(net = 100.0, tax = 19.0, gross = 119.0, documentTypeCode = DocumentType.INVOICE),
            invoice(net = 40.0, tax = 7.6, gross = 47.6, documentTypeCode = DocumentType.CREDIT_NOTE)
        )

        val t = DashboardStats.compute(invoices, todayIso = "2026-05-30").taxTotals

        assertEquals(60.0, t.net, 0.001)   // 100 - 40
        assertEquals(11.4, t.tax, 0.001)   // 19 - 7.6
        assertEquals(71.4, t.gross, 0.001) // 119 - 47.6
    }

    @Test
    fun perQuarterAggregatesSignedAndSortsMostRecentFirst() {
        val invoices = listOf(
            invoice(issueDate = "2026-02-05", net = 100.0, tax = 19.0, gross = 119.0), // Q1
            invoice(issueDate = "2026-03-20", net = 50.0, tax = 9.5, gross = 59.5),     // Q1
            invoice(issueDate = "2026-04-10", net = 200.0, tax = 38.0, gross = 238.0,   // Q2, credit note
                documentTypeCode = DocumentType.CREDIT_NOTE)
        )

        val perQuarter = DashboardStats.compute(invoices, todayIso = "2026-05-30").perQuarter

        assertEquals(listOf("2026-Q2", "2026-Q1"), perQuarter.map { it.label })
        // Q1 = 100+50 net, 19+9.5 tax
        val q1 = perQuarter.first { it.label == "2026-Q1" }
        assertEquals(150.0, q1.net, 0.001)
        assertEquals(28.5, q1.tax, 0.001)
        // Q2 is a credit note → signed negative
        val q2 = perQuarter.first { it.label == "2026-Q2" }
        assertEquals(-200.0, q2.net, 0.001)
        assertEquals(-38.0, q2.tax, 0.001)
    }

    @Test
    fun emptyInput() {
        val result = DashboardStats.compute(emptyList(), todayIso = "2026-05-30")

        assertTrue(result.isEmpty)
        assertEquals(0.0, result.totalExpenses, 0.001)
        assertTrue(result.perMonth.isEmpty())
        assertTrue(result.topSuppliers.isEmpty())
    }
}
