package com.ziesche.peppolreader.creator.dunning

import android.content.res.Resources
import com.ziesche.peppolreader.R
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import java.time.LocalDate

/**
 * Builds the localized dunning email (subject + body) for an overdue outgoing invoice.
 * Pure text assembly — no intents, no persistence — so the escalation logic is unit-testable.
 *
 * The body templates escalate with the level (polite reminder → firm reminder → final
 * reminder announcing further steps) and share the same seven positional placeholders:
 * buyer, invoice number, issue date, due date, amount, new deadline, seller.
 */
object DunningTextBuilder {

    const val MAX_LEVEL = 3
    const val NEW_DEADLINE_DAYS = 7L

    data class DunningMail(
        val level: Int,
        val subject: String,
        val body: String,
        val newDeadlineIso: String
    )

    /** The level this dunning will have: one above the current, capped at [MAX_LEVEL]. */
    fun nextLevel(current: Int): Int = (current + 1).coerceAtMost(MAX_LEVEL)

    /** [amount] arrives pre-formatted with currency; [today] is injectable for tests. */
    fun build(
        res: Resources,
        invoice: OutgoingInvoice,
        amount: String,
        today: LocalDate = LocalDate.now()
    ): DunningMail {
        val level = nextLevel(invoice.dunningLevel)
        val newDeadline = today.plusDays(NEW_DEADLINE_DAYS).toString()
        val bodyRes = when (level) {
            1 -> R.string.dunning_email_body_1
            2 -> R.string.dunning_email_body_2
            else -> R.string.dunning_email_body_3
        }
        return DunningMail(
            level = level,
            subject = res.getString(R.string.dunning_email_subject, level, invoice.invoiceNumber),
            body = res.getString(
                bodyRes,
                invoice.buyerName,
                invoice.invoiceNumber,
                invoice.issueDate,
                invoice.dueDate.orEmpty(),
                amount,
                newDeadline,
                invoice.sellerName
            ),
            newDeadlineIso = newDeadline
        )
    }
}
