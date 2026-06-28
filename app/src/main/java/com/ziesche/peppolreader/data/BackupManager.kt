package com.ziesche.peppolreader.data

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full local backup/restore for the offline-only app: bundles the Room database and the
 * `attachments/` directory into a single ZIP the user owns (written via SAF). This is the
 * safety net against device loss — the only place all the user's invoices live.
 *
 * Restore overwrites the live database, so the caller must restart the process afterwards
 * (the Room singleton and all LiveData observers point at the old file otherwise).
 */
object BackupManager {

    private const val DB_NAME = "peppol_reader_database"
    private const val ATTACH_DIR = "attachments"
    private const val DB_PREFIX = "database/"
    private const val ATTACH_PREFIX = "attachments/"

    /** Writes the backup ZIP to [output]. Checkpoints the WAL first so the copy is current. */
    fun export(context: Context, output: OutputStream) {
        // Flush the write-ahead log into the main db file so a plain file copy is consistent.
        runCatching {
            AppDatabase.getDatabase(context).openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }

        ZipOutputStream(output).use { zos ->
            val dbFile = context.getDatabasePath(DB_NAME)
            listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm")).forEach { f ->
                if (f.exists()) {
                    zos.putNextEntry(ZipEntry("$DB_PREFIX${f.name}"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            File(context.filesDir, ATTACH_DIR).takeIf { it.isDirectory }?.listFiles()?.forEach { f ->
                if (f.isFile) {
                    zos.putNextEntry(ZipEntry("$ATTACH_PREFIX${f.name}"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    /**
     * Replaces the live database + attachments with the contents of [input].
     * @return true if the ZIP actually contained a database file. The caller must restart the
     *   process on success. On a malformed/foreign ZIP this returns false and leaves nothing
     *   half-applied beyond what was already written (callers treat false as an error).
     */
    fun restore(context: Context, input: InputStream): Boolean {
        AppDatabase.closeInstance()

        val dbFile = context.getDatabasePath(DB_NAME)
        val dbDir = dbFile.parentFile ?: return false
        val attachDir = File(context.filesDir, ATTACH_DIR).apply { mkdirs() }

        // Clear the current state so leftover rows/attachments can't survive a restore.
        listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm")).forEach { it.delete() }
        attachDir.listFiles()?.forEach { it.delete() }

        var sawDb = false
        ZipInputStream(input).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                // Use only the basename so a crafted entry can't escape the target directory.
                val leaf = File(name).name
                when {
                    name.startsWith(DB_PREFIX) && leaf.isNotEmpty() -> {
                        File(dbDir, leaf).outputStream().use { zis.copyTo(it) }
                        if (leaf == DB_NAME) sawDb = true
                    }
                    name.startsWith(ATTACH_PREFIX) && leaf.isNotEmpty() -> {
                        File(attachDir, leaf).outputStream().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return sawDb
    }
}
