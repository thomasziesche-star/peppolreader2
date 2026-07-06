package com.ziesche.peppolreader.creator.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDOutputIntent
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.ziesche.peppolreader.creator.model.LayoutTheme
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Assembles a hybrid **PDF/A-3** carrying the human-readable invoice page *and* the embedded
 * `factur-x.xml` (ZUGFeRD 2.x). The embedding structure (/Names/EmbeddedFiles + /AF with
 * AFRelationship=Data) matches exactly what [com.ziesche.peppolreader.parser.ZugferdExtractor]
 * reads back, which is the in-app round-trip guarantee.
 *
 * The visible page layout itself lives in [InvoiceLayoutRenderer].
 *
 * Assets used:
 *  - the embeddable regular TTF ships *inside* pdfbox-android
 *    (`com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf`);
 *  - `assets/creator/LiberationSans-Bold.ttf` and `LiberationSerif-Regular.ttf` (same Liberation
 *    family, SIL OFL — `Liberation-Fonts-LICENSE.txt` bundled alongside) for emphasis and the
 *    editorial headline/totals styling;
 *  - `assets/creator/sRGB.icc` provides the PDF/A OutputIntent.
 */
class ZugferdPdfA3Writer(private val context: Context) {

    /**
     * Builds the hybrid PDF and returns its bytes. [xml] is the factur-x.xml produced for
     * [invoice]; [logoPath] optionally points to the company logo image inside app storage;
     * [theme] styles the visible page (default = shipped "Warm Editorial" look).
     */
    @JvmOverloads
    fun write(
        invoice: OutgoingInvoice,
        xml: String,
        logoPath: String? = null,
        theme: LayoutTheme = LayoutTheme()
    ): ByteArray {
        PDFBoxResourceLoader.init(context.applicationContext)

        PDDocument().use { doc ->
            val regular = loadFont(doc, FONT_REGULAR)
            val bold = loadFont(doc, FONT_BOLD)
            val serif = loadFont(doc, FONT_SERIF)
            InvoiceLayoutRenderer(doc, regular, bold, serif, invoice, loadLogo(logoPath), theme).render()
            addOutputIntent(doc)
            addXmpMetadata(doc, invoice)
            setDocumentInfo(doc, invoice)
            embedXml(doc, xml.toByteArray(Charsets.UTF_8))

            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    /**
     * Builds a plain (non-hybrid) PDF with only the human-readable page — no embedded XML, no
     * PDF/A OutputIntent or Factur-X metadata. Used for quotes/offers (Angebote), which are not
     * EN 16931 documents. Same layout pipeline as [write].
     */
    @JvmOverloads
    fun writePlain(
        invoice: OutgoingInvoice,
        logoPath: String? = null,
        theme: LayoutTheme = LayoutTheme()
    ): ByteArray {
        PDFBoxResourceLoader.init(context.applicationContext)

        PDDocument().use { doc ->
            val regular = loadFont(doc, FONT_REGULAR)
            val bold = loadFont(doc, FONT_BOLD)
            val serif = loadFont(doc, FONT_SERIF)
            InvoiceLayoutRenderer(doc, regular, bold, serif, invoice, loadLogo(logoPath), theme).render()
            setDocumentInfo(doc, invoice)

            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    // ----- assets ---------------------------------------------------------------------------

    private fun loadFont(doc: PDDocument, asset: String): PDType0Font =
        context.assets.open(asset).use { input ->
            PDType0Font.load(doc, input, true /* embedSubset */)
        }

    private fun loadLogo(logoPath: String?): Bitmap? {
        if (logoPath.isNullOrBlank()) return null
        val file = File(logoPath)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    // ----- PDF/A OutputIntent --------------------------------------------------------------

    private fun addOutputIntent(doc: PDDocument) {
        context.assets.open(ICC_ASSET).use { icc ->
            val oi = PDOutputIntent(doc, icc)
            oi.info = "sRGB IEC61966-2.1"
            oi.outputCondition = "sRGB IEC61966-2.1"
            oi.outputConditionIdentifier = "sRGB IEC61966-2.1"
            oi.registryName = "http://www.color.org"
            doc.documentCatalog.addOutputIntent(oi)
        }
    }

    // ----- document info + XMP -------------------------------------------------------------

    private fun docTitle(invoice: OutgoingInvoice): String {
        val label = when (invoice.documentTypeCode) {
            OutgoingInvoice.DOC_TYPE_CREDIT_NOTE -> "Gutschrift "
            OutgoingInvoice.DOC_TYPE_QUOTE -> "Angebot "
            else -> "Rechnung "
        }
        return label + invoice.invoiceNumber
    }

    private fun setDocumentInfo(doc: PDDocument, invoice: OutgoingInvoice) {
        val info: PDDocumentInformation = doc.documentInformation
        info.title = docTitle(invoice)
        info.author = invoice.sellerName
        info.producer = PRODUCER
        info.creator = PRODUCER
        info.creationDate = Calendar.getInstance()
        info.modificationDate = Calendar.getInstance()
    }

    private fun addXmpMetadata(doc: PDDocument, invoice: OutgoingInvoice) {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Calendar.getInstance().time)
        val xmp = buildXmp(title = docTitle(invoice), author = invoice.sellerName, date = now)
        val meta = PDMetadata(doc)
        meta.importXMPMetadata(xmp.toByteArray(Charsets.UTF_8))
        doc.documentCatalog.metadata = meta
    }

    /**
     * Hand-written XMP packet: PDF/A-3B identification plus the Factur-X extension schema
     * description and the fx: document-level properties (DocumentType, DocumentFileName,
     * Version, ConformanceLevel) the ZUGFeRD spec requires.
     */
    private fun buildXmp(title: String, author: String, date: String): String {
        val t = xmlEsc(title)
        val a = xmlEsc(author)
        return """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
      <dc:title><rdf:Alt><rdf:li xml:lang="x-default">$t</rdf:li></rdf:Alt></dc:title>
      <dc:creator><rdf:Seq><rdf:li>$a</rdf:li></rdf:Seq></dc:creator>
    </rdf:Description>
    <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
      <xmp:CreatorTool>$PRODUCER</xmp:CreatorTool>
      <xmp:CreateDate>$date</xmp:CreateDate>
      <xmp:ModifyDate>$date</xmp:ModifyDate>
    </rdf:Description>
    <rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
      <pdf:Producer>$PRODUCER</pdf:Producer>
    </rdf:Description>
    <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
      <pdfaid:part>3</pdfaid:part>
      <pdfaid:conformance>B</pdfaid:conformance>
    </rdf:Description>
    <rdf:Description rdf:about="" xmlns:fx="urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#">
      <fx:DocumentType>INVOICE</fx:DocumentType>
      <fx:DocumentFileName>factur-x.xml</fx:DocumentFileName>
      <fx:Version>1.0</fx:Version>
      <fx:ConformanceLevel>EN 16931</fx:ConformanceLevel>
    </rdf:Description>
    <rdf:Description rdf:about="" xmlns:pdfaExtension="http://www.aiim.org/pdfa/ns/extension/" xmlns:pdfaSchema="http://www.aiim.org/pdfa/ns/schema#" xmlns:pdfaProperty="http://www.aiim.org/pdfa/ns/property#">
      <pdfaExtension:schemas>
        <rdf:Bag>
          <rdf:li rdf:parseType="Resource">
            <pdfaSchema:schema>Factur-X PDFA Extension Schema</pdfaSchema:schema>
            <pdfaSchema:namespaceURI>urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#</pdfaSchema:namespaceURI>
            <pdfaSchema:prefix>fx</pdfaSchema:prefix>
            <pdfaSchema:property>
              <rdf:Seq>
                <rdf:li rdf:parseType="Resource">
                  <pdfaProperty:name>DocumentFileName</pdfaProperty:name>
                  <pdfaProperty:valueType>Text</pdfaProperty:valueType>
                  <pdfaProperty:category>external</pdfaProperty:category>
                  <pdfaProperty:description>Name of the embedded XML invoice file</pdfaProperty:description>
                </rdf:li>
                <rdf:li rdf:parseType="Resource">
                  <pdfaProperty:name>DocumentType</pdfaProperty:name>
                  <pdfaProperty:valueType>Text</pdfaProperty:valueType>
                  <pdfaProperty:category>external</pdfaProperty:category>
                  <pdfaProperty:description>INVOICE</pdfaProperty:description>
                </rdf:li>
                <rdf:li rdf:parseType="Resource">
                  <pdfaProperty:name>Version</pdfaProperty:name>
                  <pdfaProperty:valueType>Text</pdfaProperty:valueType>
                  <pdfaProperty:category>external</pdfaProperty:category>
                  <pdfaProperty:description>The actual version of the Factur-X data</pdfaProperty:description>
                </rdf:li>
                <rdf:li rdf:parseType="Resource">
                  <pdfaProperty:name>ConformanceLevel</pdfaProperty:name>
                  <pdfaProperty:valueType>Text</pdfaProperty:valueType>
                  <pdfaProperty:category>external</pdfaProperty:category>
                  <pdfaProperty:description>The conformance level of the Factur-X data</pdfaProperty:description>
                </rdf:li>
              </rdf:Seq>
            </pdfaSchema:property>
          </rdf:li>
        </rdf:Bag>
      </pdfaExtension:schemas>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    // ----- embed factur-x.xml --------------------------------------------------------------

    private fun embedXml(doc: PDDocument, xmlBytes: ByteArray) {
        val now = Calendar.getInstance()
        val ef = PDEmbeddedFile(doc, ByteArrayInputStream(xmlBytes))
        ef.subtype = "text/xml"
        ef.size = xmlBytes.size
        ef.creationDate = now
        ef.modDate = now

        val fs = PDComplexFileSpecification()
        fs.file = EMBEDDED_NAME
        fs.fileUnicode = EMBEDDED_NAME
        fs.fileDescription = "Factur-X/ZUGFeRD invoice"
        fs.embeddedFile = ef
        fs.embeddedFileUnicode = ef
        // PDF/A-3 association: this attachment is the source data for the document.
        fs.cosObject.setName(COSName.getPDFName("AFRelationship"), "Data")

        val catalog = doc.documentCatalog
        val efTree = PDEmbeddedFilesNameTreeNode()
        efTree.names = mapOf(EMBEDDED_NAME to fs)
        val names = PDDocumentNameDictionary(catalog)
        names.embeddedFiles = efTree
        catalog.names = names

        // Catalog /AF array referencing the associated file.
        val af = COSArray()
        af.add(fs.cosObject)
        catalog.cosObject.setItem(COSName.getPDFName("AF"), af)
    }

    private fun xmlEsc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val FONT_REGULAR = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
        private const val FONT_BOLD = "creator/LiberationSans-Bold.ttf"
        private const val FONT_SERIF = "creator/LiberationSerif-Regular.ttf"
        private const val ICC_ASSET = "creator/sRGB.icc"
        private const val EMBEDDED_NAME = "factur-x.xml"
        private const val PRODUCER = "PeppolReader Invoice Creator"
    }
}
