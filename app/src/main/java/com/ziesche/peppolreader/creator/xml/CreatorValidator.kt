package com.ziesche.peppolreader.creator.xml

import com.ziesche.peppolreader.creator.model.CompanyProfile
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import java.time.LocalDate

/**
 * EN 16931 pre-flight check for an invoice draft, run before the ZUGFeRD PDF is generated.
 *
 * This is a *business-rule* check, not a schema validation: it catches the mistakes that would
 * make a produced invoice non-compliant or unpayable (missing seller tax id, no buyer, empty
 * lines, due date before issue date, …) and reports them with a stable [Code] so the UI can show
 * a localized, actionable message. Pure JVM logic — no Android, fully unit-testable.
 *
 * [Severity.ERROR] blocks generation; [Severity.WARNING] is advisory (the user may proceed).
 */
object CreatorValidator {

    enum class Severity { ERROR, WARNING }

    enum class Code {
        SELLER_PROFILE_INCOMPLETE, // BG-4: seller address + VAT id / tax number
        INVOICE_NUMBER_MISSING,    // BT-1
        ISSUE_DATE_INVALID,        // BT-2 (missing or not ISO yyyy-MM-dd)
        BUYER_NAME_MISSING,        // BT-44
        NO_LINES,                  // BR-16: at least one invoice line
        LINE_NO_DESCRIPTION,       // BT-153: each line needs a name/description
        LINE_NON_POSITIVE_QTY,     // BT-129: quantity should be > 0
        DUE_BEFORE_ISSUE,          // BT-9 must not precede BT-2
        IBAN_MISSING,              // no payment account → buyer has no SEPA target
        IBAN_INVALID               // IBAN length outside the ISO 13616 range
    }

    /** One finding. [lineIndex] is set (0-based) for the per-line codes, null otherwise. */
    data class Issue(val severity: Severity, val code: Code, val lineIndex: Int? = null)

    /** A line is "present" once the user has typed anything meaningful into it. */
    private fun OutgoingInvoice.presentLines() =
        lines.withIndex().filter { (_, l) -> l.description.isNotBlank() || l.unitPrice != 0.0 }

    fun validate(draft: OutgoingInvoice, profile: CompanyProfile): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (!profile.isComplete()) issues += Issue(Severity.ERROR, Code.SELLER_PROFILE_INCOMPLETE)
        if (draft.invoiceNumber.isBlank()) issues += Issue(Severity.ERROR, Code.INVOICE_NUMBER_MISSING)
        if (draft.buyerName.isBlank()) issues += Issue(Severity.ERROR, Code.BUYER_NAME_MISSING)

        val issue = parseIso(draft.issueDate)
        if (issue == null) issues += Issue(Severity.ERROR, Code.ISSUE_DATE_INVALID)

        val present = draft.presentLines()
        if (present.isEmpty()) {
            issues += Issue(Severity.ERROR, Code.NO_LINES)
        } else {
            for ((index, line) in present) {
                if (line.description.isBlank()) {
                    issues += Issue(Severity.ERROR, Code.LINE_NO_DESCRIPTION, index)
                }
                if (line.quantity <= 0.0) {
                    issues += Issue(Severity.WARNING, Code.LINE_NON_POSITIVE_QTY, index)
                }
            }
        }

        val due = draft.dueDate?.takeIf { it.isNotBlank() }?.let { parseIso(it) }
        if (issue != null && due != null && due.isBefore(issue)) {
            issues += Issue(Severity.WARNING, Code.DUE_BEFORE_ISSUE)
        }

        val iban = profile.iban.replace(" ", "")
        when {
            iban.isBlank() -> issues += Issue(Severity.WARNING, Code.IBAN_MISSING)
            iban.length !in 15..34 -> issues += Issue(Severity.WARNING, Code.IBAN_INVALID)
        }

        return issues
    }

    private fun parseIso(value: String): LocalDate? =
        runCatching { LocalDate.parse(value.trim()) }.getOrNull()
}
