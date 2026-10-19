package me.proton.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager

/**
 * Owns the single [FileLogSink] and wires it to [SyncLog]. Lets the settings screen turn
 * file logging on/off and pick a destination folder, and is re-initialised from prefs at
 * app start ([initFromPrefs]) so logging resumes after a reboot / process death.
 *
 * Off by default, and the only thing that puts the log on storage: the app keeps its running
 * log in memory ([LogBuffer]) and writes nothing by itself, so switching this on is what makes
 * a log outlive the process. See [FileLogSink].
 */
object FileLogController {

    @Volatile private var sink: FileLogSink? = null

    fun isActive(): Boolean = sink != null

    /** Start writing logs to [treeUri]; replaces any existing sink and attaches it to [SyncLog]. */
    fun enable(context: Context, treeUri: Uri) {
        disable()
        val s = FileLogSink.create(context, treeUri)
        sink = s
        SyncLog.attachFileSink { line -> s.write(line) }
        SyncLog.i("FileLog", "File logging enabled → ${folderLabel(context) ?: treeUri}")
    }

    /** Detach from [SyncLog] and flush+close the sink. */
    fun disable() {
        SyncLog.attachFileSink(null)
        sink?.close()
        sink = null
    }

    /** Best-effort: flush pending lines now (e.g. before the user opens the folder to grab the file). */
    fun flushNow() {
        sink?.flushNow()
    }

    /** The active sink's buffered log (oldest→newest), or null if file logging is off. */
    fun snapshot(): String? = sink?.snapshot()

    /** Resume file logging at app start if the user previously enabled it and the grant still holds. */
    fun initFromPrefs(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(SyncPreferences.KEY_FILE_LOGGING, false)) return
        val uriStr = prefs.getString(SyncPreferences.KEY_LOG_FOLDER_URI, null) ?: return
        val uri = Uri.parse(uriStr)
        val granted = context.contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isWritePermission }
        if (granted) enable(context, uri)
    }

    /** True only if a log folder is set AND we still hold a live write grant for it. */
    fun hasValidFolder(context: Context): Boolean {
        val uriStr = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(SyncPreferences.KEY_LOG_FOLDER_URI, null) ?: return false
        val uri = Uri.parse(uriStr)
        return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }

    /** Human-readable tail of the chosen folder (e.g. "Documents/SyncLogs"), or null if unset. */
    fun folderLabel(context: Context): String? {
        val uriStr = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(SyncPreferences.KEY_LOG_FOLDER_URI, null) ?: return null
        val uri = Uri.parse(uriStr)
        val docId = try { DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { null }
        return docId?.substringAfterLast(':')?.ifBlank { docId } ?: uri.lastPathSegment ?: uri.toString()
    }
}
