package me.proton.sync

/**
 * Bearer-style secrets, stripped defensively from every copy of the log that leaves memory.
 *
 * Our own lines should not carry these: the human-verification token was removed from
 * [me.proton.sync.account.LoginActivity] once it turned out to be sitting in the CAPTCHA
 * URL. But the system section of the debug log is written by WebView and OkHttp, which we do
 * not control, and a support log is a file people forward around.
 *
 * One list for every copy that leaves memory: the debug log ([DebugLog]) and the opt-in file
 * copy ([FileLogSink]), which skipped it until 2.0.2. The in-memory ring the log viewer draws
 * stays verbatim; it is not storage, and its Copy, Save and Share buttons go through [DebugLog].
 *
 * No Android imports, so it is tested on the plain JVM.
 */
object LogRedaction {

    private val REDACTIONS: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)(token=)[A-Za-z0-9._~+/=-]{8,}""") to "$1<redacted>",
        Regex("""(?i)("?(?:HumanVerificationToken|AccessToken|RefreshToken|access_token|refresh_token)"?\s*[:=]\s*"?)[A-Za-z0-9._~+/=-]{8,}""") to "$1<redacted>",
        Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]{8,}""") to "$1<redacted>",
        // A Paddle subscription id, in the exact shape the licence proxy checks (its SUB_ID). No line
        // logs one on purpose; this keeps one out of a forwarded log if a WebView or a future line does. No
        // leading word boundary, as in SubscriptionEntry's SUB_ID_IN_TEXT: in a URL-encoded link the id
        // follows a letter or a digit (%3Dsub_..., %2Fsub_...), where \b never matched. The lookahead keeps a
        // longer token whole.
        Regex("""(?i)sub_[0-7][0-9a-hjkmnp-tv-z]{25}(?![0-9a-z])""") to "sub_<redacted>",
        // A Paddle transaction id, the same shape behind "txn_" (the proxy's TXN_ID). Since 2.0.2 one is redeemed
        // for its subscription id (the proxy's /txn-to-sub), so it leads to the licence key and is masked the same way.
        Regex("""(?i)txn_[0-7][0-9a-hjkmnp-tv-z]{25}(?![0-9a-z])""") to "txn_<redacted>",
    )

    /**
     * Every pattern above needs one of these words, so a line without any of them cannot match and is
     * returned as it came. The file copy runs this once per line, and most lines carry none. Compared
     * ignoring case, as the patterns match; "sub_" and "txn_" are in every id the last two patterns match,
     * wherever it starts.
     */
    private val TRIGGERS = listOf("token", "bearer", "sub_", "txn_")

    /** [text] with the secrets replaced, or the very same instance when there is nothing to replace. */
    fun redact(text: String): String {
        if (TRIGGERS.none { text.contains(it, ignoreCase = true) }) return text
        var out = text
        for ((pattern, replacement) in REDACTIONS) out = pattern.replace(out, replacement)
        return out
    }
}
