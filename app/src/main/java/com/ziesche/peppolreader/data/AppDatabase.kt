package com.ziesche.peppolreader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ziesche.peppolreader.data.model.Invoice

@Database(entities = [Invoice::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun invoiceDao(): InvoiceDao

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "peppol_reader_database"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
