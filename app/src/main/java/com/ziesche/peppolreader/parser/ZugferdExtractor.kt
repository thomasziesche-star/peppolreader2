package com.ziesche.peppolreader.parser

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import java.io.InputStream

/**
 * Extracts the embedded UBL/CII invoice XML from a ZUGFeRD / Factur-X / XRechnung-Hybrid PDF.
 *
 * Lookup order matches the spec / common practice:
 *   1. factur-x.xml            (Factur-X / ZUGFeRD 2.x)
 *   2. zugferd-invoice.xml     (legacy ZUGFeRD 1.x)
 *   3. xrechnung.xml           (XRechnung Hybrid)
 *   4. first *.xml in /Names/EmbeddedFiles
 */
class ZugferdExtractor {

    sealed class Result {
        data class Success(val xml: String, val embeddedName: String) : Result()
        data object NoEmbeddedXml : Result()
        data object Encrypted : Result()
        data class Error(val message: String) : Result()
    }

    fun extract(input: InputStream): Result {
        return try {
            PDDocument.load(input).use { document ->
                if (document.isEncrypted) return Result.Encrypted

                val catalog = document.documentCatalog ?: return Result.NoEmbeddedXml
                val namesDict = catalog.names ?: return Result.NoEmbeddedXml
                val embeddedFiles: PDNameTreeNode<PDComplexFileSpecification> =
                    namesDict.embeddedFiles ?: return Result.NoEmbeddedXml

                val flat = mutableMapOf<String, PDComplexFileSpecification>()
                collect(embeddedFiles, flat)
                if (flat.isEmpty()) return Result.NoEmbeddedXml

                val priorities = listOf("factur-x.xml", "zugferd-invoice.xml", "xrechnung.xml")
                val match = priorities
                    .firstNotNullOfOrNull { name -> findIgnoreCase(flat, name) }
                    ?: flat.entries.firstOrNull { it.key.endsWith(".xml", ignoreCase = true) }

                if (match == null) return Result.NoEmbeddedXml

                val embedded = pickEmbedded(match.value) ?: return Result.NoEmbeddedXml
                val xml = embedded.toByteArray().toString(Charsets.UTF_8)
                Result.Success(xml, match.key)
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun collect(
        node: PDNameTreeNode<PDComplexFileSpecification>,
        sink: MutableMap<String, PDComplexFileSpecification>
    ) {
        node.names?.forEach { (name, spec) -> sink[name] = spec }
        node.kids?.forEach { child -> collect(child, sink) }
    }

    private fun findIgnoreCase(
        map: Map<String, PDComplexFileSpecification>,
        target: String
    ): Map.Entry<String, PDComplexFileSpecification>? =
        map.entries.firstOrNull { it.key.equals(target, ignoreCase = true) }

    private fun pickEmbedded(spec: PDComplexFileSpecification): PDEmbeddedFile? =
        spec.embeddedFileUnicode
            ?: spec.embeddedFileDos
            ?: spec.embeddedFileMac
            ?: spec.embeddedFileUnix
            ?: spec.embeddedFile
}
