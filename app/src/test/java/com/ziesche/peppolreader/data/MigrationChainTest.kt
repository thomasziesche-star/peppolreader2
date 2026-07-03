package com.ziesche.peppolreader.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.ziesche.peppolreader.creator.model.CreatorArticle
import com.ziesche.peppolreader.creator.model.CreatorCustomer
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * End-to-end test of the real Room migration chain (v4 → v10) against the actual entity
 * schema. Guards the most expensive bug class for a bookkeeping app: now that the blanket
 * `fallbackToDestructiveMigration()` is gone (see [AppDatabase]), a column added to an entity
 * without a matching migration would silently corrupt or — previously — wipe user data. Here
 * a hand-built v4 database with one invoice row is opened through the production migration
 * list; Room runs every migration and then validates the final schema against the compiled
 * entities. A missing or wrong migration makes this test throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationChainTest {

    private val dbName = "migration_chain_test.db"
    private lateinit var dbPath: String

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        dbPath = file.absolutePath
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.getApplication().getDatabasePath(dbName).delete()
    }

    /**
     * The `invoices` table exactly as it looked at schema version 4 — i.e. the current
     * [com.ziesche.peppolreader.data.model.Invoice] minus every column added by
     * MIGRATION_4_5 … MIGRATION_8_9.
     */
    private val createInvoicesV4 = """
        CREATE TABLE invoices (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            invoiceId TEXT NOT NULL,
            issueDate TEXT NOT NULL,
            dueDate TEXT,
            currency TEXT NOT NULL,
            supplierName TEXT NOT NULL,
            supplierStreet TEXT,
            supplierCity TEXT,
            supplierZip TEXT,
            supplierCountry TEXT,
            supplierTaxId TEXT,
            customerName TEXT NOT NULL,
            customerStreet TEXT,
            customerCity TEXT,
            customerZip TEXT,
            customerCountry TEXT,
            customerTaxId TEXT,
            netAmount REAL NOT NULL,
            taxAmount REAL NOT NULL,
            grossAmount REAL NOT NULL,
            payableAmount REAL NOT NULL,
            xmlContent TEXT NOT NULL,
            fileName TEXT NOT NULL,
            embeddedDocumentFilename TEXT,
            embeddedDocumentPath TEXT,
            createdAt INTEGER NOT NULL
        )
    """.trimIndent()

    @Test
    fun migratesFromV4ToLatestAndKeepsData() {
        // 1. Build a v4 database with one invoice, the way a long-time user's device would have it.
        val legacy = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        legacy.execSQL(createInvoicesV4)
        legacy.execSQL(
            """
            INSERT INTO invoices
            (invoiceId, issueDate, dueDate, currency, supplierName, customerName,
             netAmount, taxAmount, grossAmount, payableAmount, xmlContent, fileName, createdAt)
            VALUES
            ('INV-OLD-1', '2024-01-15', '2024-02-15', 'EUR', 'Legacy Supplier', 'Legacy Customer',
             100.0, 19.0, 119.0, 119.0, '<Invoice/>', 'old.xml', 1700000000000)
            """.trimIndent()
        )
        legacy.version = 4
        legacy.close()

        // 2. Open through the production migration chain. No fallback → a broken chain throws.
        val db = Room.databaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
            dbName
        )
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

        runBlocking {
            // 3. The legacy row survived the full v4 → v10 migration unchanged.
            assertEquals(1, db.invoiceDao().getInvoiceCount())
            val row = db.invoiceDao().getInvoiceByInvoiceId("INV-OLD-1")
            assertNotNull("legacy invoice must survive migration", row)
            assertEquals("Legacy Supplier", row!!.supplierName)
            assertEquals(119.0, row.payableAmount, 0.001)

            // 4. Columns added by later migrations exist and default to null for old rows.
            assertNull(row.documentTypeCode)
            assertNull(row.formatLabel)
            assertNull(row.paidAt)
            assertNull(row.invoiceSubtype)
            assertNull(row.note)
            assertNull(row.category)

            // 5. The creator tables added in v9/v10/v12 are present and usable (no crash on query).
            assertNull(db.outgoingInvoiceDao().getById(1))
            assertEquals(0, db.creatorCustomerDao().getAll().size)
            assertEquals(0, db.creatorArticleDao().getAll().size)

            // 6. The customer email column added in v12 defaults to null and round-trips.
            db.creatorCustomerDao().upsert(CreatorCustomer(name = "Migrated Buyer"))
            assertNull(db.creatorCustomerDao().getAll().single().email)

            // 7. Article upsert respects the unique name index (REPLACE, no duplicate).
            db.creatorArticleDao().upsert(CreatorArticle(name = "Consulting", unitPrice = 90.0))
            db.creatorArticleDao().upsert(CreatorArticle(name = "Consulting", unitPrice = 95.0))
            val articles = db.creatorArticleDao().getAll()
            assertEquals(1, articles.size)
            assertEquals(95.0, articles.single().unitPrice, 0.001)

            // 8. Payment/dunning columns added in v13 default correctly and update as designed.
            val outId = db.outgoingInvoiceDao().insert(
                OutgoingInvoice(invoiceNumber = "RE-1", issueDate = "2026-01-01", sellerName = "Me", buyerName = "Buyer")
            )
            val fresh = db.outgoingInvoiceDao().getById(outId)!!
            assertNull(fresh.paidAt)
            assertEquals(0, fresh.dunningLevel)
            assertNull(fresh.lastDunningAt)
            assertNull(fresh.lastOverdueNotifiedAt)

            db.outgoingInvoiceDao().setPaid(outId, 123L)
            assertEquals(123L, db.outgoingInvoiceDao().getById(outId)!!.paidAt)
            db.outgoingInvoiceDao().setPaid(outId, null)
            assertNull(db.outgoingInvoiceDao().getById(outId)!!.paidAt)

            // Dunning level caps at 3 no matter how often it is recorded.
            repeat(4) { db.outgoingInvoiceDao().recordDunningSent(outId, 456L) }
            val dunned = db.outgoingInvoiceDao().getById(outId)!!
            assertEquals(3, dunned.dunningLevel)
            assertEquals(456L, dunned.lastDunningAt)
        }
        db.close()
    }
}
