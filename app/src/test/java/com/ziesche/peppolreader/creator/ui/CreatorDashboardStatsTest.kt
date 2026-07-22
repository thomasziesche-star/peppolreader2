package com.ziesche.peppolreader.creator.ui

import com.ziesche.peppolreader.creator.model.CreatorLine
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric only for org.json (CreatorLine JSON round-trip); the stats logic itself is pure.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CreatorDashboardStatsTest {

    private val today = "2026-07-03"

    /** One line: 10 × 100 € net at 19 % VAT → net 1000, tax 190, gross 1190. */
    private fun linesJson(unitPrice: Double = 100.0, quantity: Double = 10.0, vat: Double = 19.0) =
        CreatorLine.listToJson(listOf(CreatorLine("Pos", quantity, "C62", unitPrice, vat)))

    private fun invoice(
        number: String = "RE-1",
        issueDate: String = "2026-05-10",
        dueDate: String? = "2026-06-10",
        status: String = OutgoingInvoice.STATUS_GENERATED,
        docType: String = "380",
        buyer: String = "ACME",
        paidAt: Long? = null,
        lineItemsJson: String = linesJson()
    ) = OutgoingInvoice(
        invoiceNumber = number,
        issueDate = issueDate,
        dueDate = dueDate,
        documentTypeCode = docType,
        sellerName = "Me",
        buyerName = buyer,
        lineItemsJson = lineItemsJson,
        status = status,
        paidAt = paidAt
    )

    @Test
    fun `drafts are excluded`() {
        val result = CreatorDashboardStats.compute(
            listOf(invoice(), invoice(number = "D-1", status = OutgoingInvoice.STATUS_DRAFT)),
            todayIso = today
        )
        assertEquals(1, result.invoiceCount)
        assertEquals(1190.0, result.totalRevenue, 0.001)
    }

    @Test
    fun `amounts derive from line items`() {
        val result = CreatorDashboardStats.compute(listOf(invoice()), todayIso = today)
        assertEquals(1000.0, result.taxTotals.net, 0.001)
        assertEquals(190.0, result.taxTotals.tax, 0.001)
        assertEquals(1190.0, result.taxTotals.gross, 0.001)
    }

    @Test
    fun `credit notes count negatively`() {
        val result = CreatorDashboardStats.compute(
            listOf(
                invoice(),
                invoice(number = "GS-1", docType = "381", lineItemsJson = linesJson(unitPrice = 40.0))
            ),
            todayIso = today
        )
        // 1190 − 476 = 714
        assertEquals(714.0, result.totalRevenue, 0.001)
    }

    @Test
    fun `overdue rule and status breakdown slices add up`() {
        val paid = invoice(number = "P", paidAt = 1L)
        val open = invoice(number = "O", dueDate = "2026-12-31")
        val overdue = invoice(number = "U", dueDate = "2026-06-01")
        val result = CreatorDashboardStats.compute(listOf(paid, open, overdue), todayIso = today)

        assertEquals(1190.0, result.statusBreakdown.paid, 0.001)
        assertEquals(1190.0, result.statusBreakdown.open, 0.001)
        assertEquals(1190.0, result.statusBreakdown.overdue, 0.001)
        assertEquals(result.totalRevenue,
            result.statusBreakdown.paid + result.statusBreakdown.open + result.statusBreakdown.overdue,
            0.001)
        assertEquals(2380.0, result.openAmount, 0.001) // open KPI includes the overdue part
        assertEquals(1190.0, result.overdueAmount, 0.001)
    }

    @Test
    fun `partial payments reduce the outstanding amount`() {
        // Grand total 1190; 400 recorded, not fully paid → 790 still outstanding and overdue.
        val overdue = invoice(number = "U", dueDate = "2026-06-01").copy(id = 1)
        val result = CreatorDashboardStats.compute(
            listOf(overdue), todayIso = today, paidByInvoice = mapOf(1L to 400.0)
        )
        assertEquals(790.0, result.openAmount, 0.001)
        assertEquals(790.0, result.overdueAmount, 0.001)
        assertEquals(400.0, result.statusBreakdown.paid, 0.001)     // money received
        assertEquals(790.0, result.statusBreakdown.overdue, 0.001)  // remaining, overdue
        // The three donut slices still add up to the full gross.
        assertEquals(
            result.totalRevenue,
            result.statusBreakdown.paid + result.statusBreakdown.open + result.statusBreakdown.overdue,
            0.001
        )
    }

    @Test
    fun `quarter grouping is derived from issue date`() {
        val q1 = invoice(number = "A", issueDate = "2026-02-01")
        val q2 = invoice(number = "B", issueDate = "2026-05-01")
        val result = CreatorDashboardStats.compute(listOf(q1, q2), todayIso = today)
        assertEquals(listOf("2026-Q2", "2026-Q1"), result.perQuarter.map { it.label })
        assertEquals(190.0, result.perQuarter[0].tax, 0.001)
    }

    @Test
    fun `top customers grouped by buyer`() {
        val result = CreatorDashboardStats.compute(
            listOf(
                invoice(number = "1", buyer = "Big"),
                invoice(number = "2", buyer = "Big"),
                invoice(number = "3", buyer = "Small", lineItemsJson = linesJson(unitPrice = 1.0))
            ),
            todayIso = today
        )
        assertEquals("Big", result.topCustomers.first().customerName)
        assertEquals(2380.0, result.topCustomers.first().amount, 0.001)
        assertEquals(2, result.topCustomers.size)
    }

    @Test
    fun `empty input yields empty result`() {
        val result = CreatorDashboardStats.compute(emptyList(), todayIso = today)
        assertTrue(result.isEmpty)
        assertEquals(0.0, result.totalRevenue, 0.001)
        assertTrue(result.perMonth.isEmpty())
    }
}
