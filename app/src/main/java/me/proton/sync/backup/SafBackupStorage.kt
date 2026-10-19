package me.proton.sync.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences

/**
 * Thin wrapper over a persisted Storage Access Framework *tree* URI — the
 * user-chosen folder the data-backup feature writes into.
 *
 * Unlike the single-file SAF used by the `.sppbackup` export (CreateDocument,
 * one-shot grant), this persists a *tree* permission via
 * [android.content.ContentResolver.takePersistableUriPermission] so a background
 * [BackupScheduleWorker] can create subfolders + files unattended, surviving reboot
 * and process death. SAF exposes no validity API, so [hasValidPermission] re-checks
 * the live persisted grants and every write path catches SecurityException.
 */
object SafBackupStorage {

    private const val TAG = "SafBackup"

    /** Run folder / encrypted-run file name prefix (timestamped, lexicographically sortable). */
    const val RUN_PREFIX = "proton-backup-"

    const val ICS_MIME = "text/calendar"
    const val VCF_MIME = "text/x-vcard"
    const val GPG_MIME = "application/pgp-encrypted"

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun savedTreeUri(context: Context): Uri? =
        prefs(context).getString(SyncPreferences.KEY_BACKUP_FOLDER_URI, null)?.let { Uri.parse(it) }

    /** Persist the freshly-picked tree URI + write grant, and remember it in prefs. */
    fun persist(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        prefs(context).edit().putString(SyncPreferences.KEY_BACKUP_FOLDER_URI, uri.toString()).apply()
    }

    /** True only if a folder is set AND we still hold a live write grant for it. */
    fun hasValidPermission(context: Context): Boolean {
        val uri = savedTreeUri(context) ?: return false
        return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }

    /** Best-effort human label for the chosen folder (decoded document-id tail). */
    fun displayPath(context: Context): String? {
        val uri = savedTreeUri(context) ?: return null
        val docId = try { DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { null }
        return docId?.substringAfterLast(':')?.ifBlank { docId } ?: uri.lastPathSegment ?: uri.toString()
    }

    private fun rootDir(context: Context): DocumentFile? =
        savedTreeUri(context)?.let { DocumentFile.fromTreeUri(context, it) }

    /** Create (replacing any same-named) a child run directory. Null on failure. */
    fun createRunDir(context: Context, name: String): DocumentFile? {
        val root = rootDir(context) ?: return null
        root.findFile(name)?.delete()
        return root.createDirectory(name)
    }

    /** Write a single child file at the tree root (used for an encrypted single-file run). */
    fun writeRootFile(context: Context, name: String, mime: String, bytes: ByteArray): Boolean {
        val root = rootDir(context) ?: return false
        root.findFile(name)?.delete()
        return writeFile(context, root, name, mime, bytes)
    }

    fun writeFile(context: Context, dir: DocumentFile, name: String, mime: String, bytes: ByteArray): Boolean {
        var file: DocumentFile? = null
        return try {
            // SAF providers derive the extension from `mime` and append it to the display
            // name. Pass the *base* name (no extension) so providers that append
            // unconditionally (e.g. Samsung's) produce "X.gpg", not "X.gpg.gpg".
            val baseName = name.substringBeforeLast('.', name)
            file = dir.createFile(mime, baseName) ?: return false
            val ok = context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write(bytes); it.flush(); true
            } ?: false
            // Don't leave a zero-byte/partial orphan if the stream couldn't open or write.
            if (!ok) { file.delete(); return false }
            true
        } catch (e: SecurityException) {
            SyncLog.e(TAG, "Permission lost writing $name: ${e.message}")
            try { file?.delete() } catch (_: Exception) {}
            false
        } catch (e: Exception) {
            SyncLog.e(TAG, "Failed writing $name: ${e.javaClass.simpleName} ${e.message}")
            try { file?.delete() } catch (_: Exception) {}
            false
        }
    }

    private fun mimeFor(fileName: String): String = when {
        fileName.endsWith(".vcf") -> VCF_MIME
        fileName.endsWith(".ics") -> ICS_MIME
        fileName.endsWith(".gpg") -> GPG_MIME
        else -> "application/octet-stream"
    }

    /**
     * Create [runDirName] under the tree root and write [files] into it. A key of the
     * form `"<subdir>/<name>"` is placed in a created subdirectory (e.g. one per account);
     * a bare name lands at the run-dir root. Returns true only if every file wrote.
     */
    fun writeRun(context: Context, runDirName: String, files: Map<String, ByteArray>): Boolean {
        val dir = createRunDir(context, runDirName) ?: return false
        val subdirs = HashMap<String, DocumentFile>()
        var allOk = true
        for ((path, bytes) in files) {
            val slash = path.indexOf('/')
            val sub = if (slash >= 0) path.substring(0, slash) else ""
            val fileName = if (slash >= 0) path.substring(slash + 1) else path
            // If a subdirectory can't be created, fail the run — never silently dump the file
            // at the run root (that would corrupt the per-account structure).
            val targetDir: DocumentFile? =
                if (sub.isEmpty()) dir
                else subdirs[sub] ?: dir.createDirectory(sub)?.also { subdirs[sub] = it }
            if (targetDir == null || !writeFile(context, targetDir, fileName, mimeFor(fileName), bytes)) allOk = false
        }
        if (!allOk) {
            // All-or-nothing: don't leave a partial run on disk. A partial run would consume a
            // retention slot and could cause a good older backup to be pruned.
            try { dir.delete() } catch (_: Exception) {}
        }
        return allOk
    }

    /**
     * Delete backup runs beyond [keep], oldest first. A "run" is either a timestamped
     * subfolder (unencrypted) or a `proton-backup-*.gpg` file (encrypted) — both carry
     * [RUN_PREFIX] and sort chronologically by name.
     */
    fun pruneOldRuns(context: Context, keep: Int) {
        val root = rootDir(context) ?: return
        val runs = root.listFiles()
            .filter { it.name?.startsWith(RUN_PREFIX) == true }
            .sortedBy { it.name }
        val excess = runs.size - keep
        if (excess > 0) {
            runs.take(excess).forEach { old ->
                if (old.delete()) SyncLog.i(TAG, "Pruned old backup ${old.name}")
            }
        }
    }
}
