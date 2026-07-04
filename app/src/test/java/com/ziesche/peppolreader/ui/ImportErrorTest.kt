package com.ziesche.peppolreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import java.io.IOException

/** Exception→category mapping plus the batch-summary assembly incl. plural forms. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportErrorTest {

    private val res get() = RuntimeEnvironment.getApplication().resources

    // ----- ImportError.from -------------------------------------------------------------------

    @Test
    fun `exception mapping covers parser, io and fallback`() {
        assertEquals(ImportError.XML_MALFORMED, ImportError.from(XmlPullParserException("boom")))
        assertEquals(ImportError.FILE_READ, ImportError.from(IOException("io")))
        assertEquals(ImportError.FILE_READ, ImportError.from(FileNotFoundException("gone")))
        assertEquals(ImportError.STORAGE, ImportError.from(IllegalStateException("db")))
    }

    // ----- ImportSummaryFormatter -------------------------------------------------------------

    @Test
    fun `plain success summary has no suffixes`() {
        val text = ImportSummaryFormatter.format(res, successCount = 5, total = 5, duplicateCount = 0, errorCount = 0)
        assertEquals("5 of 5 invoices imported", text)
    }

    @Test
    fun `singular and plural suffixes are grammatical`() {
        val one = ImportSummaryFormatter.format(res, 2, 5, duplicateCount = 1, errorCount = 0)
        assertTrue(one, one.endsWith(", 1 duplicate"))
        val many = ImportSummaryFormatter.format(res, 1, 5, duplicateCount = 2, errorCount = 2)
        assertTrue(many, many.contains(", 2 duplicates"))
        assertTrue(many, many.contains(", 2 errors"))
    }

    @Test
    fun `exactly one error appends its localized cause`() {
        val text = ImportSummaryFormatter.format(
            res, 4, 5, duplicateCount = 0, errorCount = 1, singleError = ImportError.PDF_NO_XML
        )
        assertTrue(text, text.contains(", 1 error"))
        assertTrue(text, text.endsWith("(" + res.getString(ImportError.PDF_NO_XML.messageRes) + ")"))
    }

    @Test
    fun `multiple errors do not name a single cause`() {
        val text = ImportSummaryFormatter.format(
            res, 3, 5, duplicateCount = 0, errorCount = 2, singleError = null
        )
        assertFalse(text, text.contains("("))
    }
}
