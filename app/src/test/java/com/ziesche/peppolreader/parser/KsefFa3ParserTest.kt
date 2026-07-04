package com.ziesche.peppolreader.parser

import com.ziesche.peppolreader.data.model.DocumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests the Polish KSeF FA(3) parser against a realistic invoice (VAT) and a correction (KOR),
 * covering the P_xx monetary coding, NIP extraction, the FormaPlatnosci→4461 mapping and the
 * credit-note / CorrectionInfo path. The only untested parser before this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KsefFa3ParserTest {

    private val namespace = "http://crd.gov.pl/wzor/2025/06/25/13775/"

    /** Same contract as PeppolParserTest: broken XML must surface as XmlPullParserException. */
    @Test
    fun malformedXmlThrowsXmlPullParserException() {
        val truncated = """<?xml version="1.0"?><Faktura xmlns="$namespace"><Fa><P_2>FK/2026"""
        try {
            KsefFa3Parser(truncated, RuntimeEnvironment.getApplication()).parse()
            org.junit.Assert.fail("expected XmlPullParserException")
        } catch (expected: org.xmlpull.v1.XmlPullParserException) {
            // categorized as ImportError.XML_MALFORMED
        }
    }

    private fun invoiceXml(
        rodzaj: String = "VAT",
        correctionBlock: String = ""
    ) = """
<?xml version="1.0" encoding="UTF-8"?>
<Faktura xmlns="$namespace">
  <Fa>
    <KodWaluty>PLN</KodWaluty>
    <P_1>2026-06-15</P_1>
    <P_2>FK/2026/001</P_2>
    <RodzajFaktury>$rodzaj</RodzajFaktury>
    $correctionBlock
    <FaWiersz>
      <NrWierszaFa>1</NrWierszaFa>
      <P_7>Usługi konsultingowe</P_7>
      <P_8A>godz</P_8A>
      <P_8B>2</P_8B>
      <P_9A>100.00</P_9A>
      <P_11>200.00</P_11>
    </FaWiersz>
    <P_13_1>200.00</P_13_1>
    <P_14_1>46.00</P_14_1>
    <P_15>246.00</P_15>
    <Platnosc>
      <FormaPlatnosci>6</FormaPlatnosci>
      <TerminPlatnosci>
        <Termin>2026-07-15</Termin>
      </TerminPlatnosci>
      <RachunekBankowy>
        <NrRB>PL61109010140000071219812874</NrRB>
        <NazwaBanku>Bank Polski</NazwaBanku>
      </RachunekBankowy>
    </Platnosc>
  </Fa>
  <Podmiot1>
    <DaneIdentyfikacyjne>
      <NIP>1234567890</NIP>
      <Nazwa>Sprzedawca Sp. z o.o.</Nazwa>
    </DaneIdentyfikacyjne>
    <Adres>
      <AdresL1>ul. Główna 1</AdresL1>
      <AdresL2>00-001 Warszawa</AdresL2>
      <KodKraju>PL</KodKraju>
    </Adres>
  </Podmiot1>
  <Podmiot2>
    <DaneIdentyfikacyjne>
      <NIP>9876543210</NIP>
      <Nazwa>Nabywca S.A.</Nazwa>
    </DaneIdentyfikacyjne>
  </Podmiot2>
</Faktura>
    """.trimIndent()

    @Test
    fun parsesStandardVatInvoice() {
        val parsed = KsefFa3Parser(invoiceXml(), RuntimeEnvironment.getApplication()).parse()

        assertEquals("FK/2026/001", parsed.invoice.id)
        assertEquals("2026-06-15", parsed.invoice.issueDate)
        assertEquals("2026-07-15", parsed.invoice.dueDate)
        assertEquals("PLN", parsed.invoice.currency)

        assertEquals("Sprzedawca Sp. z o.o.", parsed.supplier.name)
        assertEquals("1234567890", parsed.supplier.taxId)
        assertEquals("Nabywca S.A.", parsed.customer.name)

        assertEquals(1, parsed.items.size)
        assertEquals("Usługi konsultingowe", parsed.items[0].description)
        assertEquals(2.0, parsed.items[0].quantity, 0.001)
        assertEquals(100.0, parsed.items[0].price, 0.001)
        assertEquals(200.0, parsed.items[0].lineTotal, 0.001)

        assertEquals(200.0, parsed.totals.netAmount, 0.001)
        assertEquals(46.0, parsed.totals.taxAmount, 0.001)
        assertEquals(246.0, parsed.totals.grossAmount, 0.001)

        assertEquals("KSeF FA(3)", parsed.formatLabel)
        assertEquals(DocumentType.INVOICE, parsed.documentTypeCode)
        assertEquals("VAT", parsed.invoiceSubtype)
        assertNull(parsed.correctionInfo)

        // FormaPlatnosci "6" (przelew) → UN/EDIFACT 4461 "30" (credit transfer) + IBAN.
        assertEquals("30", parsed.paymentMeans?.code)
        assertEquals("PL61109010140000071219812874", parsed.paymentMeans?.payeeFinancialAccount?.id)
    }

    @Test
    fun correctionInvoiceMapsToCreditNoteWithCorrectionInfo() {
        val correction = """
            <PrzyczynaKorekty>Błędna ilość</PrzyczynaKorekty>
            <DaneFaKorygowanej>
              <NrFaKorygowanej>FK/2026/000</NrFaKorygowanej>
              <DataWystFaKorygowanej>2026-05-01</DataWystFaKorygowanej>
            </DaneFaKorygowanej>
        """.trimIndent()

        val parsed = KsefFa3Parser(
            invoiceXml(rodzaj = "KOR", correctionBlock = correction),
            RuntimeEnvironment.getApplication()
        ).parse()

        assertEquals("KOR", parsed.invoiceSubtype)
        assertEquals(DocumentType.CREDIT_NOTE, parsed.documentTypeCode)
        assertNotNull(parsed.correctionInfo)
        assertEquals("Błędna ilość", parsed.correctionInfo?.reason)
        assertEquals("FK/2026/000", parsed.correctionInfo?.originalInvoiceNumber)
        assertEquals("2026-05-01", parsed.correctionInfo?.originalIssueDate)
    }
}
