package me.proton.sync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One emitted log line, kept structured rather than pre-formatted.
 *
 * Structure is the point: the log viewer filters by [level] and [tag], and the previous
 * implementation had to guess both by substring-matching the rendered string (` E `,
 * ` W/`), which mis-classified any message that happened to contain those characters.
 */
data class LogRecord(
    val timeMs: Long,
    /** `D`, `I`, `W` or `E`. */
    val level: Char,
    val tag: String,
    val message: String,
    /**
     * Strictly increasing, assigned by [LogBuffer.append].
     *
     * Lets a live view tell "already drawn" from "new" without comparing contents. Wall
     * time cannot do that job: two records can share a millisecond, and the viewer would
     * then draw one of them twice after a rebuild.
     */
    val seq: Long = 0L,
) {
    /** Rank used by the "show warnings and errors from every tag" rule. */
    val severity: Int
        get() = when (level) {
            'E' -> 3
            'W' -> 2
            'I' -> 1
            else -> 0
        }
}

/**
 * The single in-memory record of everything the app has logged.
 *
 * Every [SyncLog] call lands here, unconditionally and unfiltered. That is the whole
 * design: capture is never filtered, only *display* is. The previous arrangement filtered
 * at capture time against two hand-maintained tag allowlists, which had drifted apart and
 * between them dropped 23 of the tags actually in use — including `LoginActivity` and
 * `Captcha`, so every sign-in and verification failure was invisible in support logs by
 * construction. A wrong display filter now costs a user nothing, because the shared
 * artefact ([DebugLog]) never consults one.
 *
 * Bounded ring: oldest records roll off at [MAX_RECORDS], and the whole thing goes with the
 * process when it dies. Nothing here is written to storage. Anything that has to outlive the
 * process needs the opt-in file copy ([FileLogSink]).
 */
object LogBuffer {

    /**
     * ~5000 lines is roughly 500 KB rendered, which comfortably covers a full sync plus a
     * sign-in attempt while staying small enough to put on the clipboard without tripping
     * the binder transaction limit.
     */
    const val MAX_RECORDS = 5000

    private val lock = Any()
    private val records = ArrayDeque<LogRecord>(MAX_RECORDS)

    /**
     * Live subscribers (the open log viewer). Copy-on-write: appends happen on every
     * thread in the app and must not contend with the UI adding or dropping a listener.
     */
    @Volatile private var listeners: List<(LogRecord) -> Unit> = emptyList()

    private val sequence = java.util.concurrent.atomic.AtomicLong(0L)

    /** Stamps [record] with a sequence number, stores it, and notifies live views. */
    fun append(record: LogRecord) {
        val stamped = record.copy(seq = sequence.incrementAndGet())
        synchronized(lock) {
            records.addLast(stamped)
            while (records.size > MAX_RECORDS) records.removeFirst()
        }
        // Never inside the lock: a listener hops to the main thread, and holding the lock
        // across that would let a busy sync thread stall on the UI.
        for (l in listeners) {
            try { l(stamped) } catch (_: Throwable) { /* a broken listener must not break logging */ }
        }
    }

    fun snapshot(): List<LogRecord> = synchronized(lock) { records.toList() }

    fun size(): Int = synchronized(lock) { records.size }

    @Synchronized
    fun addListener(listener: (LogRecord) -> Unit) {
        listeners = listeners + listener
    }

    @Synchronized
    fun removeListener(listener: (LogRecord) -> Unit) {
        listeners = listeners - listener
    }

    private val lineFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /** Canonical rendering, identical in the viewer, the file log and every export. */
    fun format(record: LogRecord): String =
        "${lineFormat.get()!!.format(Date(record.timeMs))} ${record.level}/${record.tag}: ${record.message}"
}
