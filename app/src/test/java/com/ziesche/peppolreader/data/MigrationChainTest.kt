package com.ziesche.peppolreader.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
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

            // 5. The creator tables added in v9/v10 are present and usable (no crash on query).
            assertNull(db.outgoingInvoiceDao().getById(1))
            assertEquals(0, db.creatorCustomerDao().getAll().size)
        }
        db.close()
    }
}
