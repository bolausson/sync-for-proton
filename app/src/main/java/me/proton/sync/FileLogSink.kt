package me.proton.sync

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.documentfile.provider.DocumentFile

/**
 * Mirrors [SyncLog] output into a plain-text file inside a user-chosen Storage Access
 * Framework folder.
 *
 * Opt-in, and the only standing route from the log to storage: the app otherwise keeps its
 * log in memory only ([LogBuffer]), so a crash or a process death takes it with it. Turn
 * this on to capture a problem that is hard to reproduce, or to keep a copy in a folder you
 * can reach.
 *
 * What it is no longer needed for is making a report non-empty. The report and the log viewer
 * both used to re-read `logcat` through a tag allowlist, which is why they came back blank on
 * ROMs that restrict it and why this switch was the documented workaround. Neither reads
 * logcat for its contents any more.
 *
 * Design (robust over clever):
 * - Every line is appended to a bounded in-memory buffer (capped at [MAX_LINES]). The
 *   newest lines win; old lines roll off. This caps both memory and file size.
 * - A single background thread flushes the **whole** buffer to the SAF file with a
 *   *truncate* write (`"wt"`), coalesced via [FLUSH_DELAY_MS]. Truncate-rewrite avoids
 *   depending on SAF append mode (`"wa"`), which several providers (e.g. Samsung) don't
 *   support — so the file always reflects the current buffer on any provider.
 * - All write paths swallow exceptions: logging must never crash or block the app.
 *
 * Each sink instance writes its own **timestamped** file
 * (`sync-for-proton-log-<yyyy-MM-dd'T'HHmmss>.txt`), so the file name records when logging
 * started — a fresh file per app start / re-enable. Within a session that one file is
 * rewritten in place (truncate) rather than appended to.
 */
class FileLogSink private constructor(
    private val appContext: Context,
    private val treeUri: Uri,
) {
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var dirty = false

    private val thread = HandlerThread("spp-filelog").apply { start() }
    private val handler = Handler(thread.looper)
    private val flushTask = Runnable { doFlush() }

    @Volatile private var fileUri: Uri? = null

    // Per-session file name carrying the creation time (device-local), e.g.
    // sync-for-proton-log-2026-06-30T184530.txt, so the user can tell which log is which.
    private val baseName: String =
        LOG_FILE_PREFIX + java.text.SimpleDateFormat("yyyy-MM-dd'T'HHmmss", java.util.Locale.US).format(java.util.Date())

    /** Append one formatted line; schedules a coalesced background flush. Cheap, thread-safe. */
    fun write(line: String) {
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            dirty = true
        }
        handler.removeCallbacks(flushTask)
        handler.postDelayed(flushTask, FLUSH_DELAY_MS)
    }

    /** Force a flush as soon as the background thread is free (e.g. before the user grabs the file). */
    fun flushNow() {
        handler.removeCallbacks(flushTask)
        handler.post(flushTask)
    }

    /** Current buffer (oldest→newest) as one string — for attaching to a "Send report" e-mail. */
    fun snapshot(): String = synchronized(lock) { lines.joinToString("\n") }

    /** Final flush, then stop the worker thread. */
    fun close() {
        handler.removeCallbacks(flushTask)
        handler.post {
            doFlush()
            thread.quitSafely()
        }
    }

    private fun resolveFile(): Uri? {
        fileUri?.let { return it }
        return try {
            val root = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
            // Tolerant match: some providers append ".txt" from the MIME, some don't.
            val existing = root.listFiles().firstOrNull {
                !it.isDirectory && it.name?.startsWith(baseName) == true
            }
            val file = existing ?: root.createFile("text/plain", baseName)
            file?.uri?.also { fileUri = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun doFlush() {
        val snapshot: String
        synchronized(lock) {
            if (!dirty) return
            snapshot = lines.joinToString("\n", postfix = "\n")
            dirty = false
        }
        val uri = resolveFile() ?: return
        try {
            appContext.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(snapshot.toByteArray(Charsets.UTF_8))
                it.flush()
            }
        } catch (_: Exception) {
            // Logging must never throw. A lost grant / provider hiccup just skips this flush.
        }
    }

    companion object {
        private const val LOG_FILE_PREFIX = "sync-for-proton-log-"
        private const val MAX_LINES = 12_000
        private const val FLUSH_DELAY_MS = 1_200L

        fun create(context: Context, treeUri: Uri): FileLogSink =
            FileLogSink(context.applicationContext, treeUri)
    }
}
