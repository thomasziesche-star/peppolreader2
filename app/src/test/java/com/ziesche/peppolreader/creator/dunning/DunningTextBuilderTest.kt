package com.ziesche.peppolreader.creator.dunning

import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DunningTextBuilderTest {

    private val res get() = RuntimeEnvironment.getApplication().resources

    private fun invoice(dunningLevel: Int = 0) = OutgoingInvoice(
        invoiceNumber = "RE-2026-042",
        issueDate = "2026-05-01",
        dueDate = "2026-06-01",
        sellerName = "Ziesche IT",
        buyerName = "ACME GmbH",
        status = OutgoingInvoice.STATUS_GENERATED,
        dunningLevel = dunningLevel
    )

    @Test
    fun `level escalates and caps at three`() {
        assertEquals(1, DunningTextBuilder.nextLevel(0))
        assertEquals(2, DunningTextBuilder.nextLevel(1))
        assertEquals(3, DunningTextBuilder.nextLevel(2))
        assertEquals(3, DunningTextBuilder.nextLevel(3))
        assertEquals(3, DunningTextBuilder.nextLevel(99))
    }

    @Test
    fun `new deadline is today plus seven days`() {
        val today = LocalDate.of(2026, 7, 3)
        val mail = DunningTextBuilder.build(res, invoice(), "119,00 €", today)
        assertEquals("2026-07-10", mail.newDeadlineIso)
    }

    @Test
    fun `all placeholders land in subject and body`() {
        val mail = DunningTextBuilder.build(res, invoice(), "119,00 €", LocalDate.of(2026, 7, 3))
        assertEquals(1, mail.level)
        assertTrue(mail.subject.contains("RE-2026-042"))
        listOf("ACME GmbH", "RE-2026-042", "2026-05-01", "2026-06-01", "119,00 €", "2026-07-10", "Ziesche IT")
            .forEach { assertTrue("body must contain $it", mail.body.contains(it)) }
        assertFalse("no unresolved placeholders", mail.body.contains("%"))
    }

    @Test
    fun `second dunning uses escalated template`() {
        val first = DunningTextBuilder.build(res, invoice(dunningLevel = 0), "1 €", LocalDate.of(2026, 7, 3))
        val second = DunningTextBuilder.build(res, invoice(dunningLevel = 1), "1 €", LocalDate.of(2026, 7, 3))
        val third = DunningTextBuilder.build(res, invoice(dunningLevel = 2), "1 €", LocalDate.of(2026, 7, 3))
        assertEquals(2, second.level)
        assertEquals(3, third.level)
        assertFalse(first.body == second.body)
        assertFalse(second.body == third.body)
    }

    // ----- overdue predicate (OutgoingInvoice.isOverdue) --------------------------------------

    @Test
    fun `overdue predicate covers all edges`() {
        val today = "2026-07-03"
        val base = invoice()
        assertTrue(base.copy(dueDate = "2026-07-02").isOverdue(today))          // due yesterday
        assertFalse(base.copy(dueDate = "2026-07-03").isOverdue(today))         // due today
        assertFalse(base.copy(dueDate = "2026-07-02", paidAt = 1L).isOverdue(today)) // paid
        assertFalse(
            base.copy(dueDate = "2026-07-02", status = OutgoingInvoice.STATUS_DRAFT).isOverdue(today)
        )                                                                       // draft
        assertFalse(base.copy(dueDate = null).isOverdue(today))                 // no due date
        assertFalse(base.copy(dueDate = "").isOverdue(today))                   // blank due date
    }
}
