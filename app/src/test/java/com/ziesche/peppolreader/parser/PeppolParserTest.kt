package com.ziesche.peppolreader.parser

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PeppolParserTest {

    /**
     * Pins the exception type for broken XML: ImportError.from() relies on
     * XmlPullParserException to categorize the failure as XML_MALFORMED.
     */
    @Test
    fun malformedXmlThrowsXmlPullParserException() {
        val truncated = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2">
              <ID>14413574
        """.trimIndent()
        try {
            PeppolParser(truncated, RuntimeEnvironment.getApplication()).parse()
            fail("expected XmlPullParserException")
        } catch (expected: org.xmlpull.v1.XmlPullParserException) {
            // categorized as ImportError.XML_MALFORMED
        }
    }

    @Test
    fun parseAokInvoiceStructure() {
        val xml = """
<?xml version="1.0" encoding="UTF-8"?>
<StandardBusinessDocument xmlns="http://www.unece.org/cefact/namespaces/StandardBusinessDocumentHeader">
   <StandardBusinessDocumentHeader>
      <HeaderVersion>1.0</HeaderVersion>
      <Sender>
         <Identifier Authority="iso6523-actorid-upis">9925:BE0465015822</Identifier>
      </Sender>
      <Receiver>
         <Identifier Authority="iso6523-actorid-upis">9925:BE0727448134</Identifier>
      </Receiver>
      <DocumentIdentification>
         <Standard>urn:oasis:names:specification:ubl:schema:xsd:Invoice-2</Standard>
         <TypeVersion>2.1</TypeVersion>
         <InstanceIdentifier>aae9e980-f270-45c4-8be0-590d19cbff36</InstanceIdentifier>
         <Type>Invoice</Type>
         <CreationDateAndTime>2026-01-12T23:44:28+00:00</CreationDateAndTime>
      </DocumentIdentification>
   </StandardBusinessDocumentHeader>
   <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
             xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
             xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
             xmlns:ext="urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2">
      <cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0</cbc:CustomizationID>
      <cbc:ID>14413574</cbc:ID>
      <cbc:IssueDate>2026-01-09</cbc:IssueDate>
      <cac:AccountingSupplierParty>
        <cac:Party>
            <cac:PartyName>
                <cbc:Name>AOK Mock Supplier</cbc:Name>
            </cac:PartyName>
        </cac:Party>
      </cac:AccountingSupplierParty>
      <cac:AccountingCustomerParty>
        <cac:Party>
            <cac:PartyName>
                <cbc:Name>Mock Customer</cbc:Name>
            </cac:PartyName>
        </cac:Party>
      </cac:AccountingCustomerParty>
      <cac:InvoiceLine>
         <cbc:InvoicedQuantity unitCode="EA">1.0</cbc:InvoicedQuantity>
         <cac:Item>
            <cbc:Description>Frais de port</cbc:Description>
            <cbc:Name>Frais de port</cbc:Name>
            <cac:SellersItemIdentification>
               <cbc:ID>LIV_BE</cbc:ID>
            </cac:SellersItemIdentification>
            <cac:AdditionalItemProperty>
               <cbc:Name>ItemType</cbc:Name>
               <cbc:Value>S</cbc:Value>
            </cac:AdditionalItemProperty>
         </cac:Item>
         <cac:Price>
            <cbc:PriceAmount currencyID="EUR">9.90</cbc:PriceAmount>
         </cac:Price>
      </cac:InvoiceLine>
   </Invoice>
</StandardBusinessDocument>
        """.trimIndent()

        val parser = PeppolParser(xml, RuntimeEnvironment.getApplication())
        val invoice = parser.parse()

        assertEquals("14413574", invoice.invoice.id)
        assertEquals("AOK Mock Supplier", invoice.supplier.name)
        assertEquals("Mock Customer", invoice.customer.name)
        assertEquals(1, invoice.items.size)
        assertEquals("Frais de port", invoice.items[0].description)
        assertEquals(9.90, invoice.items[0].price, 0.01)
    }
}
