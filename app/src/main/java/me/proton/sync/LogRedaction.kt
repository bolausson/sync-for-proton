package me.proton.sync

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Bearer-style secrets and email addresses, taken out of every copy of the log.
 *
 * Our own lines should not carry secrets: the human-verification token was removed from
 * [me.proton.sync.account.LoginActivity] once it turned out to be sitting in the CAPTCHA
 * URL. But the system section of the debug log is written by WebView and OkHttp, which we do
 * not control, and a support log is a file people forward around.
 *
 * Email addresses are another matter: sync lines name the guests and organisers of events, the
 * recipients of invitations and the account itself, and error messages quote them. Each becomes a code
 * ([addressCode]) that is the same for one address through a run of the app, so two lines about one
 * guest still match, but says nothing about whose it is.
 *
 * Since 2.0.4 [SyncLog] runs this once on every line before logcat, the in-memory ring the log viewer
 * draws, or the opt-in file copy sees it; until then only the debug log ([DebugLog]) and the file copy
 * ([FileLogSink]) were redacted, and logcat and the viewer showed every address. Those two still run it
 * again, because the debug log's system section is logcat that never passed [SyncLog], and a second
 * pass changes nothing.
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
     * Every pattern above needs one of these words, so a line without any of them cannot match them. Compared
     * ignoring case, as the patterns match; "sub_" and "txn_" are in every id the last two patterns match,
     * wherever it starts. Addresses are gated by their own '@' or "%40" instead ([redact]).
     */
    private val TRIGGERS = listOf("token", "bearer", "sub_", "txn_")

    /**
     * An address, plain or %40-encoded (a key lookup's ?Email=, an ICS link). Explicit classes only: on the
     * phone java.util.regex is ICU, whose \w, \s, \b and (?i) reach beyond ASCII while the JVM the tests run on
     * keeps them ASCII (2.0.3's whitespace lesson); \p{L}, \p{M} and \p{N} mean the same in both, and \p{M} keeps
     * a decomposed letter inside the local part. The lookbehind stops a match starting inside a local part, '%'
     * included: without it a run of percent escapes was tried again from every '%' (quadratic: 16,000 of them
     * took 20 s). An encoded list ("a%40example.com%2Cb%40example.org") is therefore one match, masked whole. Domain labels are
     * bounded, because each group repetition costs the matcher a level of stack, and a punycode ending
     * (xn--p1ai) counts as an ending.
     */
    internal const val EMAIL_PATTERN =
        """(?<![\p{L}\p{M}\p{N}._%+\-])([\p{L}\p{M}\p{N}._%+\-]+)(?:@|%40)((?:[\p{L}\p{M}\p{N}\-]+\.){1,16}(?:\p{L}{2,63}|xn--[\p{L}\p{N}\-]{1,59}))(?![\p{L}\p{M}\p{N}\-])"""
    private val EMAIL = Regex(EMAIL_PATTERN)

    /** The local part of this app's own event UID, "<UUID>@proton.me" (CalendarSyncAdapter): an id, not a person. */
    private val UUID_LOCAL_PART = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")

    /**
     * Mail providers shared by millions keep their name in a code: which one a guest is on decides how Proton
     * and Google treat the answer (GuestStatuses canonicalises these), and it says nothing about who the guest
     * is. Any other domain belongs to someone and names them as surely as the address, so it shows only its
     * ending.
     */
    private val SHARED_DOMAINS = setOf(
        "proton.me", "protonmail.com", "protonmail.ch", "pm.me", "gmail.com", "googlemail.com", "google.com",
        "outlook.com", "hotmail.com", "hotmail.co.uk", "hotmail.fr", "live.com", "msn.com", "icloud.com", "me.com",
        "mac.com", "yahoo.com", "aol.com", "gmx.de", "gmx.net", "web.de", "yandex.ru", "mail.ru",
    )
    private const val HEX = "0123456789abcdef"

    /**
     * Random per run of the app, never stored or logged, so a code cannot be checked against a guessed address
     * and codes from two runs do not match. Lazy: created at the first address, never during the Application's
     * early start (BouncyCastleSetup must come first there).
     */
    private val SALT: ByteArray by lazy { ByteArray(16).also { SecureRandom().nextBytes(it) } }

    /** [text] with the secrets and addresses replaced, or the very same instance when there is nothing to replace. */
    fun redact(text: String): String {
        val secrets = TRIGGERS.any { text.contains(it, ignoreCase = true) }
        val addresses = text.indexOf('@') >= 0 || text.contains("%40")
        if (!secrets && !addresses) return text
        var out = text
        if (secrets) for ((pattern, replacement) in REDACTIONS) out = pattern.replace(out, replacement)
        if (addresses) out = maskAddresses(out, SALT)
        return out
    }

    /**
     * A value that should be an address but may not be (an attendee or organizer field another calendar app wrote,
     * a display name standing in for one): as it is when it holds an address, which [redact] then masks, else only
     * its length, because free text without an '@' is the one thing the address pattern cannot recognise.
     */
    fun addressOrShape(value: String?): String = when {
        value == null -> "null"
        value.indexOf('@') >= 0 || value.contains("%40") -> value
        else -> "<${value.length} characters, no '@'>"
    }

    /**
     * [text] with every address replaced by [addressCode]; this app's own event uids ("<UUID>@proton.me", written
     * with a plain '@') stay as they are. Only at proton.me: alias services hand out real addresses of the same
     * shape (SimpleLogin's and addy.io's random aliases), and those are people.
     */
    internal fun maskAddresses(text: String, salt: ByteArray): String =
        EMAIL.replace(text) { m ->
            val ownUid = m.groupValues[2].equals("proton.me", ignoreCase = true) && '@' in m.value &&
                UUID_LOCAL_PART.matches(m.groupValues[1])
            if (ownUid) m.value else addressCode(m.groupValues[1], m.groupValues[2], salt)
        }

    /**
     * "<email 3f9a2c1d at gmail.com>" or "<email 3f9a2c1d at *.de>": 32 bits of SHA-256 over [salt] and the
     * lower-cased address, with an encoded '+' read as one, so case and encoding give the same code. No '@' in it,
     * so a second pass leaves it alone. A HEX table instead of String.format, so no locale changes the digits.
     */
    internal fun addressCode(local: String, domain: String, salt: ByteArray): String {
        val host = domain.lowercase()
        val user = local.lowercase().replace("%2b", "+")
        val digest = MessageDigest.getInstance("SHA-256").run {
            update(salt)
            digest("$user@$host".toByteArray(Charsets.UTF_8))
        }
        val code = buildString {
            for (i in 0 until 4) {
                append(HEX[(digest[i].toInt() shr 4) and 15])
                append(HEX[digest[i].toInt() and 15])
            }
        }
        val shown = if (host in SHARED_DOMAINS) host else "*." + host.substringAfterLast('.')
        return "<email $code at $shown>"
    }
}
