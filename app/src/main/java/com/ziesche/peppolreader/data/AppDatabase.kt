package com.ziesche.peppolreader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ziesche.peppolreader.data.model.Invoice
import com.ziesche.peppolreader.creator.data.CreatorArticleDao
import com.ziesche.peppolreader.creator.data.CreatorCustomerDao
import com.ziesche.peppolreader.creator.data.OutgoingInvoiceDao
import com.ziesche.peppolreader.creator.data.OutgoingInvoicePaymentDao
import com.ziesche.peppolreader.creator.model.CreatorArticle
import com.ziesche.peppolreader.creator.model.CreatorCustomer
import com.ziesche.peppolreader.creator.model.OutgoingInvoice
import com.ziesche.peppolreader.creator.model.OutgoingInvoicePayment

@Database(
    entities = [
        Invoice::class, OutgoingInvoice::class, CreatorCustomer::class, CreatorArticle::class,
        OutgoingInvoicePayment::class
    ],
    version = 17,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun invoiceDao(): InvoiceDao

    abstract fun outgoingInvoiceDao(): OutgoingInvoiceDao

    abstract fun creatorCustomerDao(): CreatorCustomerDao

    abstract fun creatorArticleDao(): CreatorArticleDao

    abstract fun outgoingInvoicePaymentDao(): OutgoingInvoicePaymentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v4 → v5: adds [Invoice.documentTypeCode] so invoices and credit notes can be told
         * apart in the UI without re-parsing the XML on every access.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoices ADD COLUMN documentTypeCode TEXT")
            }
        }

        /**
         * v5 → v6: adds [Invoice.formatLabel] so the source profile (Peppol BIS 3.0,
         * XRechnung, ZUGFeRD, Factur-X, …) can be shown as a badge in the list without
         * re-parsing the XML.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoices ADD COLUMN formatLabel TEXT")
            }
        }

        /**
         * v6 → v7: adds [Invoice.paidAt] (marks invoices as paid) and
         * [Invoice.lastReminderShownAt] (anti-spam for due-date notifications).
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoices ADD COLUMN paidAt INTEGER")
                db.execSQL("ALTER TABLE invoices ADD COLUMN lastReminderShownAt INTEGER")
            }
        }

        /**
         * v7 → v8: adds [Invoice.invoiceSubtype] and [Invoice.correctionInfoJson]
         * for KSeF FA(3) Polish e-invoices.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoices ADD COLUMN invoiceSubtype TEXT")
                db.execSQL("ALTER TABLE invoices ADD COLUMN correctionInfoJson TEXT")
            }
        }

        /**
         * v8 → v9: adds the `outgoing_invoices` table for the separate Invoice Creator mode.
         * The reader's `invoices` table is untouched.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS outgoing_invoices (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        invoiceNumber TEXT NOT NULL,
                        issueDate TEXT NOT NULL,
                        dueDate TEXT,
                        documentTypeCode TEXT NOT NULL,
                        currency TEXT NOT NULL,
                        sellerName TEXT NOT NULL,
                        sellerStreet TEXT,
                        sellerZip TEXT,
                        sellerCity TEXT,
                        sellerCountry TEXT,
                        sellerVatId TEXT,
                        sellerTaxNumber TEXT,
                        sellerIban TEXT,
                        sellerBic TEXT,
                        sellerEmail TEXT,
                        sellerPhone TEXT,
                        buyerName TEXT NOT NULL,
                        buyerStreet TEXT,
                        buyerZip TEXT,
                        buyerCity TEXT,
                        buyerCountry TEXT,
                        buyerVatId TEXT,
                        lineItemsJson TEXT NOT NULL,
                        paymentTermsNote TEXT,
                        status TEXT NOT NULL,
                        generatedXml TEXT,
                        pdfPath TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v9 → v10: adds the `creator_customers` table (customer master for the Invoice
         * Creator). Unique name index so re-invoicing a known buyer updates them in place.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS creator_customers (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        street TEXT,
                        zip TEXT,
                        city TEXT,
                        country TEXT,
                        vatId TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_creator_customers_name ON creator_customers (name)"
                )
            }
        }

        /**
         * v10 → v11: adds [Invoice.note] and [Invoice.category] — user-entered bookkeeping
         * metadata that is not part of the source XML.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoices ADD COLUMN note TEXT")
                db.execSQL("ALTER TABLE invoices ADD COLUMN category TEXT")
            }
        }

        /**
         * v11 → v12: adds the `creator_articles` table (article/service catalog for the
         * Invoice Creator, unique name index like the customer master) and
         * [CreatorCustomer.email] so an invoice recipient address can be kept on file.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS creator_articles (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        articleNumber TEXT,
                        unit TEXT NOT NULL,
                        unitPrice REAL NOT NULL,
                        vatRate REAL NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_creator_articles_name ON creator_articles (name)"
                )
                db.execSQL("ALTER TABLE creator_customers ADD COLUMN email TEXT")
            }
        }

        /**
         * v12 → v13: payment tracking + dunning lifecycle for outgoing invoices —
         * [OutgoingInvoice.paidAt], [OutgoingInvoice.dunningLevel],
         * [OutgoingInvoice.lastDunningAt] and [OutgoingInvoice.lastOverdueNotifiedAt].
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outgoing_invoices ADD COLUMN paidAt INTEGER")
                db.execSQL("ALTER TABLE outgoing_invoices ADD COLUMN dunningLevel INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE outgoing_invoices ADD COLUMN lastDunningAt INTEGER")
                db.execSQL("ALTER TABLE outgoing_invoices ADD COLUMN lastOverdueNotifiedAt INTEGER")
            }
        }

        /**
         * v13 → v14: document-wide tax handling for the creator — [OutgoingInvoice.taxMode]
         * (§19 exemption / reverse charge), the frozen [OutgoingInvoice.exemptionReason] and
         * document-level allowances/charges in [OutgoingInvoice.allowancesJson].
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outgoing_invoices ADD COLUMN taxMode TEXT NOT NULL DEFAULT 'STANDARD'")
                db.execSQL("ALTER TABLE outgoing_invoices ADD COLUMN exemptionReason TEXT")
                db.execSQL("ALTER TABLE outgoing_invoices ADD COLUMN allowancesJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * v14 → v15: adds [OutgoingInvoice.buyerReference] — the BT-10 buyer reference
         * (German Leitweg-ID for B2G invoices), an optional field on outgoing invoices.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outgoing_invoices ADD COLUMN buyerReference TEXT")
            }
        }

        /**
         * v15 → v16: per-customer payment terms — [CreatorCustomer.paymentDays] and
         * [CreatorCustomer.paymentNote], pre-filled on new invoices for that buyer.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE creator_customers ADD COLUMN paymentDays INTEGER")
                db.execSQL("ALTER TABLE creator_customers ADD COLUMN paymentNote TEXT")
            }
        }

        /**
         * v16 → v17: partial-payment ledger — the `outgoing_invoice_payments` table records one
         * row per incoming (partial) payment against an outgoing invoice.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS outgoing_invoice_payments (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        invoiceId INTEGER NOT NULL,
                        amount REAL NOT NULL,
                        paidAtMs INTEGER NOT NULL,
                        note TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_outgoing_invoice_payments_invoiceId " +
                        "ON outgoing_invoice_payments (invoiceId)"
                )
            }
        }

        /**
         * All schema migrations in order. Exposed (internal) so the migration test can
         * exercise the exact same chain the app uses.
         */
        internal val ALL_MIGRATIONS = arrayOf(
            MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
            MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
            MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17
        )

        /**
         * Closes and clears the singleton so the underlying file can be replaced (used by
         * [com.ziesche.peppolreader.data.BackupManager] during restore). The caller must restart
         * the process afterwards — existing LiveData observers still reference the closed instance.
         */
        internal fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "peppol_reader_database"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    // No blanket destructive fallback: a missing/buggy migration from v4+ must
                    // FAIL LOUDLY (crash on open, fixable) rather than silently wipe a user's
                    // invoices and customer master. Only the unreachable pre-v4 schemas (which
                    // never had a migration path) are allowed to be recreated destructively.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
