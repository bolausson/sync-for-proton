package me.proton.sync

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized logging utility for the Proton Sync adapter.
 *
 * Replaces the repetitive `if (BuildConfig.DEBUG) Log.d(...)` and
 * `if (BuildConfig.DEBUG) Log.e(..., e) else Log.e(...)` patterns that
 * were scattered across 245+ call sites in 15 files.
 *
 * ## Log Level Behavior
 * - **[d]** (debug): Emitted in debug builds OR when verbose logging is enabled at runtime.
 * - **[i]** (info): Always logged. Use for sync milestones visible in log viewer.
 * - **[w]** (warn): Always logged. Use for recoverable issues. Stack trace gated like [d].
 * - **[e]** (error): Always logged. Stack traces gated like [d].
 *
 * ## Where lines go
 * Every emitted line is recorded in [LogBuffer], in memory, which is what the log viewer
 * draws and what [DebugLog] exports. **Nothing reaches storage** unless the user switched on
 * the file copy ([FileLogSink]) or saves or shares the log by hand. Neither path filters by
 * tag; the sync view is the only place a filter is applied, and it applies to display alone.
 *
 * ## Verbose mode
 * Verbose mode flips on the same gate as `BuildConfig.DEBUG`. It is intended
 * for testers running release builds: when enabled, every `SyncLog.d` call site
 * lights up and exception stack traces are included. Set it from
 * [me.proton.sync.SyncPreferences.KEY_VERBOSE_LOGGING] at app start, and
 * re-set it whenever the user toggles the preference.
 */
object SyncLog {

    private val verbose = AtomicBoolean(false)

    /** Toggle runtime verbose logging. Effective immediately, no restart required. */
    fun setVerboseEnabled(enabled: Boolean) {
        verbose.set(enabled)
    }

    fun isVerboseEnabled(): Boolean = verbose.get()

    private fun verboseOrDebug(): Boolean = BuildConfig.DEBUG || verbose.get()

    // ---- Optional user-chosen file sink ----
    // Mirrors every emitted line into a folder the *user* picked (see FileLogSink /
    // FileLogController). Opt-in, and the only standing route from here to storage. When null,
    // which is the default, these calls are a cheap no-op. Lines reach the sink verbatim and
    // FileLogSink.write redacts them (LogRedaction); the in-memory ring stays verbatim for the
    // viewer, and DebugLog redacts its export.

    @Volatile private var fileSink: ((String) -> Unit)? = null

    /** Attach a sink that receives every emitted log line, or null to detach. */
    fun attachFileSink(sink: ((String) -> Unit)?) { fileSink = sink }

    /**
     * Record one line in the in-memory ring the viewer and the export read, and pass it to the
     * user-chosen file copy if one is switched on.
     *
     * Unfiltered, which is the entire point. Capture used to happen by re-reading logcat
     * through a hand-maintained tag allowlist, and anything the list forgot was gone for good
     * — 23 tags were missing from it, including every tag the sign-in and CAPTCHA screens use.
     * Filtering now happens only when drawing the sync view, where being wrong costs a tap
     * rather than an investigation.
     *
     * **Memory only.** Nothing here reaches storage unless the user turned on "Save logs to a
     * file", or saves or shares the log by hand. An earlier revision of this release kept an
     * app-private file unconditionally, to spare people the "switch it on, reproduce it, send
     * it" round trip; that traded away the user's control over what their device writes down,
     * which is not ours to trade.
     */
    private fun emit(level: Char, tag: String, msg: String, throwable: Throwable?) {
        val detail = when {
            throwable == null -> ""
            // Matches what the logcat call sites do: full trace when verbose, class name otherwise.
            verboseOrDebug() -> "\n" + Log.getStackTraceString(throwable)
            else -> " [${throwable.javaClass.simpleName}]"
        }
        val record = LogRecord(System.currentTimeMillis(), level, tag, msg + detail)
        LogBuffer.append(record)
        fileSink?.let { sink -> try { sink(LogBuffer.format(record)) } catch (_: Throwable) {} }
    }

    /**
     * Debug log — emitted in debug builds or when verbose logging is enabled.
     * Use for detailed diagnostic information not needed in production.
     */
    fun d(tag: String, msg: String) {
        if (verboseOrDebug()) { Log.d(tag, msg); emit('D', tag, msg, null) }
    }

    /**
     * Info log — always emitted.
     * Use for sync milestones, progress, and completion messages.
     */
    fun i(tag: String, msg: String) {
        Log.i(tag, msg); emit('I', tag, msg, null)
    }

    /**
     * Warning log — always emitted.
     * Use for recoverable issues that don't stop the sync.
     */
    fun w(tag: String, msg: String) {
        Log.w(tag, msg); emit('W', tag, msg, null)
    }

    /**
     * Warning log with optional throwable — always emitted.
     * Stack trace included when verbose logging is enabled (or in debug builds).
     */
    fun w(tag: String, msg: String, throwable: Throwable?) {
        if (throwable != null) {
            if (verboseOrDebug()) {
                Log.w(tag, msg, throwable)
            } else {
                Log.w(tag, "$msg [${throwable.javaClass.simpleName}]")
            }
        } else {
            Log.w(tag, msg)
        }
        emit('W', tag, msg, throwable)
    }

    /**
     * Error log — message always emitted.
     * Stack trace included when verbose logging is enabled (or in debug builds);
     * otherwise only the exception class name is appended (avoids leaking sensitive
     * data from BouncyCastle/OkHttp internals).
     */
    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            if (verboseOrDebug()) {
                Log.e(tag, msg, throwable)
            } else {
                Log.e(tag, "$msg [${throwable.javaClass.simpleName}]")
            }
        } else {
            Log.e(tag, msg)
        }
        emit('E', tag, msg, throwable)
    }
}
