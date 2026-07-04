package com.ziesche.peppolreader.creator

import com.ziesche.peppolreader.creator.model.CompanyProfile
import com.ziesche.peppolreader.creator.model.CreatorLine
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.creator.xml.CreatorValidator
import com.ziesche.peppolreader.creator.xml.CreatorValidator.Code
import com.ziesche.peppolreader.creator.xml.CreatorValidator.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the EN 16931 pre-flight [CreatorValidator]. The validator itself is pure, but
 * [OutgoingInvoice.lines] deserialises via org.json, so the test runs under Robolectric (which
 * provides a real org.json) — same reason as ZugferdXmlBuilderTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CreatorValidatorTest {

    private fun completeProfile() = CompanyProfile(
        name = "Ziesche Software", street = "Hauptstr. 1", zip = "10115", city = "Berlin",
        country = "DE", vatId = "DE123456789", iban = "DE89370400440532013000"
    )

    private fun validDraft(lines: List<CreatorLine> = listOf(CreatorLine("Consulting", 10.0, "HUR", 100.0, 19.0))) =
        OutgoingInvoice(
            invoiceNumber = "RE-2026-001",
            issueDate = "2026-06-04",
            dueDate = "2026-07-04",
            sellerName = "Ziesche Software",
            buyerName = "ACME GmbH",
            lineItemsJson = CreatorLine.listToJson(lines)
        )

    private fun codes(draft: OutgoingInvoice, profile: CompanyProfile) =
        CreatorValidator.validate(draft, profile).map { it.code }

    @Test
    fun cleanInvoiceHasNoIssues() {
        assertTrue(CreatorValidator.validate(validDraft(), completeProfile()).isEmpty())
    }

    @Test
    fun incompleteSellerProfileIsAnError() {
        val issues = CreatorValidator.validate(validDraft(), CompanyProfile(name = "X"))
        val seller = issues.first { it.code == Code.SELLER_PROFILE_INCOMPLETE }
        assertEquals(Severity.ERROR, seller.severity)
    }

    @Test
    fun missingNumberBuyerAndLinesAreErrors() {
        val draft = validDraft(lines = emptyList()).copy(invoiceNumber = "", buyerName = "")
        val codes = codes(draft, completeProfile())
        assertTrue(codes.contains(Code.INVOICE_NUMBER_MISSING))
        assertTrue(codes.contains(Code.BUYER_NAME_MISSING))
        assertTrue(codes.contains(Code.NO_LINES))
    }

    @Test
    fun invalidIssueDateIsAnError() {
        assertTrue(codes(validDraft().copy(issueDate = "04.06.2026"), completeProfile())
            .contains(Code.ISSUE_DATE_INVALID))
    }

    @Test
    fun lineWithoutDescriptionButWithPriceIsFlagged() {
        val draft = validDraft(lines = listOf(CreatorLine(description = "", unitPrice = 50.0)))
        val issue = CreatorValidator.validate(draft, completeProfile())
            .first { it.code == Code.LINE_NO_DESCRIPTION }
        assertEquals(Severity.ERROR, issue.severity)
        assertEquals(0, issue.lineIndex)
    }

    @Test
    fun dueBeforeIssueIsAWarningNotAnError() {
        val issues = CreatorValidator.validate(
            validDraft().copy(issueDate = "2026-07-04", dueDate = "2026-06-04"), completeProfile()
        )
        val due = issues.first { it.code == Code.DUE_BEFORE_ISSUE }
        assertEquals(Severity.WARNING, due.severity)
    }

    @Test
    fun missingAndMalformedIbanAreWarnings() {
        assertTrue(codes(validDraft(), completeProfile().copy(iban = ""))
            .contains(Code.IBAN_MISSING))
        assertTrue(codes(validDraft(), completeProfile().copy(iban = "DE123"))
            .contains(Code.IBAN_INVALID))
    }

    // ----- tax modes + allowances ----------------------------------------------------------

    @Test
    fun reverseChargeWithoutBuyerVatIdIsAnError() {
        val draft = validDraft().copy(
            taxMode = OutgoingInvoice.TAX_MODE_REVERSE_CHARGE,
            exemptionReason = "Reverse charge."
        )
        val issue = CreatorValidator.validate(draft, completeProfile())
            .first { it.code == Code.RC_BUYER_VAT_ID_MISSING }
        assertEquals(Severity.ERROR, issue.severity)

        // With a buyer VAT id the error disappears.
        assertTrue(
            !codes(draft.copy(buyerVatId = "DE987654321"), completeProfile())
                .contains(Code.RC_BUYER_VAT_ID_MISSING)
        )
    }

    @Test
    fun exemptWithoutReasonIsAnError() {
        val draft = validDraft().copy(taxMode = OutgoingInvoice.TAX_MODE_EXEMPT)
        val issue = CreatorValidator.validate(draft, completeProfile())
            .first { it.code == Code.EXEMPTION_REASON_MISSING }
        assertEquals(Severity.ERROR, issue.severity)
    }

    @Test
    fun exemptDraftWithVatRateOnLineIsAWarning() {
        val draft = validDraft().copy(  // line carries 19 %
            taxMode = OutgoingInvoice.TAX_MODE_EXEMPT,
            exemptionReason = "§ 19 UStG"
        )
        val issue = CreatorValidator.validate(draft, completeProfile())
            .first { it.code == Code.EXEMPT_LINE_HAS_VAT }
        assertEquals(Severity.WARNING, issue.severity)
    }

    @Test
    fun allowanceWithoutReasonOrAmountIsFlagged() {
        val draft = validDraft().copy(
            allowancesJson = com.ziesche.peppolreader.creator.model.CreatorAllowanceCharge.listToJson(
                listOf(
                    com.ziesche.peppolreader.creator.model.CreatorAllowanceCharge(
                        isCharge = false, reason = "", amount = 50.0, vatRate = 19.0
                    ),
                    com.ziesche.peppolreader.creator.model.CreatorAllowanceCharge(
                        isCharge = true, reason = "Versand", amount = 0.0, vatRate = 19.0
                    )
                )
            )
        )
        val issues = CreatorValidator.validate(draft, completeProfile())
        assertEquals(
            Severity.ERROR,
            issues.first { it.code == Code.ALLOWANCE_REASON_MISSING }.severity
        )
        assertEquals(
            Severity.WARNING,
            issues.first { it.code == Code.ALLOWANCE_NON_POSITIVE }.severity
        )
    }
}
