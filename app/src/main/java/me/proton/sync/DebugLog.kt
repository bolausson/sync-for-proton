package me.proton.sync

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one and only shareable log artefact.
 *
 * Every route out of the app — copy, save, share, send report — goes through [build], so
 * all of them produce identical bytes under an identical file name. That was not
 * previously true: six entry points read from three different sources with two different
 * line caps and two different tag filters, and three of them silently returned whatever
 * the log viewer's clear button had left behind. Which button a user pressed decided
 * whether their report was usable, and nothing on screen said so.
 *
 * The *sync log* (see [SyncLogTags]) is a filtered view for reading on screen. The *debug
 * log* is this: everything, with a header, for sending to the developer.
 */
object DebugLog {

    /** Bounded so the artefact stays clipboard-safe and mail-attachable. */
    private const val MAX_BODY_LINES = 5000

    /**
     * The system section is supplementary and sits on top of [MAX_BODY_LINES], so it is kept
     * short: the combined artefact still has to fit on a clipboard, and a long tail of
     * chromium chatter is rarely the interesting part.
     */
    private const val MAX_SYSTEM_LINES = 800

    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /**
     * Bearer-style secrets, stripped defensively.
     *
     * Our own lines should not carry these — the human-verification token was removed from
     * [me.proton.sync.account.LoginActivity] once it turned out to be sitting in the CAPTCHA
     * URL — but the appended system section is written by WebView and OkHttp, which we do
     * not control, and a support log is a file people forward around.
     */
    private val REDACTIONS: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)(token=)[A-Za-z0-9._~+/=-]{8,}""") to "$1<redacted>",
        Regex("""(?i)("?(?:HumanVerificationToken|AccessToken|RefreshToken|access_token|refresh_token)"?\s*[:=]\s*"?)[A-Za-z0-9._~+/=-]{8,}""") to "$1<redacted>",
        Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]{8,}""") to "$1<redacted>",
    )

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Same shape every time, so a mailbox full of reports sorts and reads sensibly. */
    fun fileName(): String = "sync-for-proton-debug-${stamp()}.txt"

    /**
     * Header first: it answers, without a single follow-up question, which build and which
     * ROM produced the log and whether verbose was on. Getting that wrong by assumption is
     * what turns a support report into a multi-day round-trip.
     */
    private fun header(bodyLines: Int, truncated: Boolean): String {
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val debugSuffix = if (BuildConfig.DEBUG) " debug" else ""
        return buildString {
            appendLine("=== Sync for Proton debug log ===")
            appendLine("App:      ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.FLAVOR}$debugSuffix")
            appendLine("Android:  ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device:   ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Verbose:  ${if (SyncLog.isVerboseEnabled()) "on" else "off"}")
            appendLine("Captured: $at")
            appendLine("Lines:    $bodyLines${if (truncated) " (older lines omitted)" else ""}")
            appendLine("=================================")
        }
    }

    /**
     * The complete artefact, built from the in-memory ring.
     *
     * Memory is the only source, because the app writes nothing to storage on its own. That
     * bounds what the log can cover to the current run of the app: a crash takes the buffer
     * with it. Anyone who needs a log that outlives the process turns on "Save logs to a
     * file", which is exactly the switch that decision belongs to.
     */
    fun build(): String {
        val raw = LogBuffer.snapshot().joinToString("\n") { LogBuffer.format(it) }

        val all = raw.lines()
        val truncated = all.size > MAX_BODY_LINES
        val body = if (truncated) all.takeLast(MAX_BODY_LINES) else all

        return buildString {
            append(header(body.size, truncated))
            appendLine()
            append(redact(body.joinToString("\n")))
            appendLine()
            val system = systemSection()
            if (system.isNotBlank()) {
                appendLine()
                appendLine("=== System log (this process) ===")
                append(system)
                appendLine()
            }
        }
    }

    /**
     * Supplementary `logcat`, filtered by **pid** rather than by a tag list.
     *
     * Two things this is not: it is not the source of our own lines any more (those come
     * from [LogBuffer]), and it is not required — on a ROM that denies it the section
     * is simply absent and nothing is lost. It earns its place only because crashes and
     * WebView/chromium errors never pass through [SyncLog], and a CAPTCHA that fails to
     * render says so nowhere else.
     */
    private fun systemSection(): String = try {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}", "*:V")
        )
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        val lines = text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("---------") }
        redact(lines.takeLast(MAX_SYSTEM_LINES).joinToString("\n"))
    } catch (_: Exception) {
        ""
    }

    fun redact(text: String): String {
        var out = text
        for ((pattern, replacement) in REDACTIONS) out = pattern.replace(out, replacement)
        return out
    }

    // --- delivery -------------------------------------------------------------------

    /** Write the artefact into the app cache and hand back a shareable content URI. */
    private fun toCacheFile(context: Context): Pair<File, android.net.Uri> {
        // Sweep earlier exports first: each share writes a fresh timestamped copy, and
        // without this the cache grows by a megabyte every time someone sends a report.
        try {
            context.cacheDir.listFiles { f -> f.name.startsWith("sync-for-proton-debug-") }
                ?.forEach { it.delete() }
        } catch (_: Exception) { /* a stale copy is harmless; never block the export */ }

        val file = File(context.cacheDir, fileName())
        file.writeText(build())
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        return file to uri
    }

    /**
     * Share sheet with the attachment intact.
     *
     * The MIME type is deliberately not `text/plain`, even though that is the true type of
     * the file: Proton Mail discards the attachment of any ACTION_SEND declaring exactly
     * `text/plain` (IntentShareExtensions in ProtonMail/android-mail, commit 0ba69fd5), so
     * the log arrived as an empty compose window. The receiver still learns the real type
     * from the provider. ClipData item 0 has to be the URI itself, because Proton Mail reads
     * ClipData and never falls back to EXTRA_STREAM once it is non-null.
     */
    fun shareIntent(context: Context, subject: String = "", body: String? = null): Intent {
        val (file, uri) = toCacheFile(context)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject.ifBlank { file.name })
            if (body != null) putExtra(Intent.EXTRA_TEXT, body)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Mail-app variant: same attachment, addressed to the developer. */
    fun mailIntent(context: Context, to: String, subject: String, body: String): Intent =
        shareIntent(context, subject, body).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        }

    fun writeTo(context: Context, uri: android.net.Uri) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(build().toByteArray(Charsets.UTF_8))
        } ?: throw java.io.IOException("Could not open $uri for writing")
    }
}
