package com.ziesche.peppolreader.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full local backup/restore for the offline-only app: bundles the Room database, the
 * `attachments/` directory, the generated invoice PDFs (`created_invoices/`), the creator
 * assets (company logo) and the essential SharedPreferences (company profile, app settings,
 * reminder settings — deliberately NOT the AI credentials) into a single ZIP the user owns
 * (written via SAF). This is the safety net against device loss — the only place all the
 * user's invoices live.
 *
 * Restore overwrites the live database, so the caller must restart the process afterwards
 * (the Room singleton and all LiveData observers point at the old file otherwise). The raw
 * prefs XML copy is safe for the same reason: SharedPreferences only flushes on edit(), so
 * the restored files survive the immediate process kill untouched.
 */
object BackupManager {

    private const val DB_NAME = "peppol_reader_database"
    private const val ATTACH_DIR = "attachments"
    private const val CREATOR_DIR = "creator"
    private const val CREATED_INVOICES_DIR = "created_invoices"
    private const val DB_PREFIX = "database/"
    private const val ATTACH_PREFIX = "attachments/"
    private const val CREATOR_PREFIX = "creator/"
    private const val CREATED_INVOICES_PREFIX = "created_invoices/"
    private const val PREFS_PREFIX = "prefs/"

    /** Prefs files that may be backed up/restored. `ai_prefs.xml` (API keys) is excluded. */
    private val PREFS_WHITELIST = setOf(
        "creator_prefs.xml",    // company profile incl. numbering + storage settings
        "app_preferences.xml",  // theme mode
        "reminder_prefs.xml"    // due-date reminder settings
    )

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
                if (f.exists()) zipFile(zos, f, DB_PREFIX)
            }
            zipDir(zos, File(context.filesDir, ATTACH_DIR), ATTACH_PREFIX)
            zipDir(zos, File(context.filesDir, CREATOR_DIR), CREATOR_PREFIX)
            zipDir(zos, File(context.filesDir, CREATED_INVOICES_DIR), CREATED_INVOICES_PREFIX)
            sharedPrefsDir(context).takeIf { it.isDirectory }?.listFiles()?.forEach { f ->
                if (f.isFile && f.name in PREFS_WHITELIST) zipFile(zos, f, PREFS_PREFIX)
            }
        }
    }

    /**
     * Replaces the live database + files with the contents of [input].
     * @return true if the ZIP actually contained a database file. The caller must restart the
     *   process on success. On a malformed/foreign ZIP this returns false and leaves nothing
     *   half-applied beyond what was already written (callers treat false as an error).
     */
    fun restore(context: Context, input: InputStream): Boolean {
        AppDatabase.closeInstance()

        val dbFile = context.getDatabasePath(DB_NAME)
        val dbDir = dbFile.parentFile ?: return false
        val attachDir = File(context.filesDir, ATTACH_DIR).apply { mkdirs() }
        val creatorDir = File(context.filesDir, CREATOR_DIR).apply { mkdirs() }
        val createdInvoicesDir = File(context.filesDir, CREATED_INVOICES_DIR).apply { mkdirs() }
        val prefsDir = sharedPrefsDir(context).apply { mkdirs() }

        // Clear the current state so leftover rows/files can't survive a restore. Prefs are
        // intentionally NOT cleared: restoring an old backup without a prefs/ section keeps
        // the current settings instead of wiping them.
        listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm")).forEach { it.delete() }
        attachDir.listFiles()?.forEach { it.delete() }
        creatorDir.listFiles()?.forEach { it.delete() }
        createdInvoicesDir.listFiles()?.forEach { it.delete() }

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
                    name.startsWith(CREATOR_PREFIX) && leaf.isNotEmpty() -> {
                        File(creatorDir, leaf).outputStream().use { zis.copyTo(it) }
                    }
                    name.startsWith(CREATED_INVOICES_PREFIX) && leaf.isNotEmpty() -> {
                        File(createdInvoicesDir, leaf).outputStream().use { zis.copyTo(it) }
                    }
                    // Whitelisted prefs only — a crafted ZIP must not plant arbitrary XMLs.
                    name.startsWith(PREFS_PREFIX) && leaf in PREFS_WHITELIST -> {
                        File(prefsDir, leaf).outputStream().use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return sawDb
    }

    /** File-name prefix for backups the app writes; used to recognise/prune its own files. */
    private const val BACKUP_NAME_PREFIX = "PeppolReader-Backup-"
    private const val BACKUP_NAME_SUFFIX = ".peppolbackup.zip"

    private fun timestampedName(): String =
        BACKUP_NAME_PREFIX + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date()) + BACKUP_NAME_SUFFIX

    /**
     * Writes a timestamped backup ZIP into the SAF tree [treeUri] (a user-picked folder, e.g. a
     * Google-Drive-synced one) and prunes to the newest [maxKeep] files. Returns the created file
     * name, or null on failure. No Drive API/credentials involved — the app only writes into the
     * folder the user granted via SAF; Drive's own sync handles the upload.
     */
    fun exportToTree(context: Context, treeUri: Uri, maxKeep: Int = 10): String? {
        val resolver = context.contentResolver
        val dirDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirDocId)
        val name = timestampedName()
        val fileUri = runCatching {
            DocumentsContract.createDocument(resolver, dirUri, "application/zip", name)
        }.getOrNull() ?: return null

        val ok = runCatching {
            resolver.openOutputStream(fileUri)?.use { export(context, it); true } ?: false
        }.getOrDefault(false)
        if (!ok) {
            runCatching { DocumentsContract.deleteDocument(resolver, fileUri) }
            return null
        }
        runCatching { pruneTree(resolver, treeUri, dirDocId, maxKeep) }
        return name
    }

    /** Deletes all but the newest [maxKeep] app backups in the tree (best effort). */
    private fun pruneTree(resolver: ContentResolver, treeUri: Uri, dirDocId: String, maxKeep: Int) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val backups = mutableListOf<Pair<String, String>>() // (displayName, documentId)
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0)
                val id = c.getString(1)
                if (n != null && id != null && n.startsWith(BACKUP_NAME_PREFIX)) backups.add(n to id)
            }
        }
        // Timestamp is in the name, so lexical sort == chronological; drop all but the newest N.
        backups.sortBy { it.first }
        backups.dropLast(maxKeep).forEach { (_, id) ->
            runCatching {
                DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
            }
        }
    }

    /**
     * Writes a backup ZIP into cacheDir (covered by the FileProvider cache-path) for one-off
     * sharing via ACTION_SEND — the simplest way to hand a backup to Google Drive, Gmail, etc.
     * Returns the file, or null on failure.
     */
    fun exportToCacheForShare(context: Context): File? = runCatching {
        val dir = File(context.cacheDir, "backup").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest share file
        val file = File(dir, timestampedName())
        file.outputStream().use { export(context, it) }
        file
    }.getOrNull()

    private fun zipFile(zos: ZipOutputStream, file: File, prefix: String) {
        zos.putNextEntry(ZipEntry("$prefix${file.name}"))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /** Adds every regular file in [dir] (non-recursive) under [prefix] to the ZIP. */
    private fun zipDir(zos: ZipOutputStream, dir: File, prefix: String) {
        dir.takeIf { it.isDirectory }?.listFiles()?.forEach { f ->
            if (f.isFile) zipFile(zos, f, prefix)
        }
    }

    private fun sharedPrefsDir(context: Context): File =
        File(context.applicationInfo.dataDir, "shared_prefs")
}
