package me.proton.sync.api

import com.google.gson.JsonParser
import retrofit2.Response

/**
 * Proton's own verdict on a failed call, in a form that is safe to log.
 *
 * Proton answers every failure with `{"Code": NNNN, "Error": "..."}`, and that Code is the only
 * thing that separates causes an HTTP status conflates: a 422 can be a wrong password (8002), a
 * refused verification token (12087) or a disabled account (10003). Logging the status alone made
 * field reports undiagnosable.
 *
 * Only `Code` and `Error` are ever read. The rest of a body is not ours to log: a 9001 carries the
 * human-verification token and its URL in `Details`, a refresh reply carries tokens, and a card
 * write can echo the card (PROTON_AUTH_MANIFEST §8d). `Error` itself is cleaned before it is
 * returned ([clean]), because Proton's messages can carry markup and an address.
 *
 * No Android or SyncLog references, so it is tested on the plain JVM.
 */
internal object ProtonApiError {

    /** Longest cleaned `Error` text kept, so one odd reply cannot flood a log line. */
    const val MAX_ERROR_CHARS = 200

    // Codes from WebClients packages/shared/lib/errors.ts (API_CUSTOM_ERROR_CODES) and
    // go-proton-api response.go. Only the ones the app acts on are named here.

    /** INVALID_LOGIN (go-proton-api PasswordWrong): wrong password, or a wrong 2FA code at auth/v4/2fa. */
    const val CODE_INVALID_LOGIN = 8002

    /** AUTH_SWITCH_TO_SSO: the account signs in through its organisation's SSO only. */
    const val CODE_SWITCH_TO_SSO = 8100

    /** HUMAN_VERIFICATION_REQUIRED: a CAPTCHA first; Details holds the token and the page. */
    const val CODE_HUMAN_VERIFICATION = 9001

    /** AUTH_ACCOUNT_DISABLED: Proton disabled the account. */
    const val CODE_ACCOUNT_DISABLED = 10003

    /** TOKEN_INVALID (go-proton-api HumanValidationInvalidToken): a solved CAPTCHA was not accepted. */
    const val CODE_TOKEN_INVALID = 12087

    private val CODE_FIELD = Regex("\"Code\"\\s*:\\s*(\\d+)")
    private val ERROR_FIELD = Regex("\"Error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val MARKUP = Regex("</?[A-Za-z][^>]*>")
    private val ADDRESS = Regex("[^\\s@\"'<>(),;:\\[\\]]+@[^\\s@\"'<>(),;:\\[\\]]+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * `Code` and raw `Error` from a Proton error body, or null when it carries neither.
     *
     * A body that is not a JSON object (already cut by a `take(500)`, or an HTML page from a proxy)
     * is searched with regexes instead, so a truncated body still yields its Code. A pair rather
     * than a type, because two fields do not earn a class.
     */
    fun parse(body: String?): Pair<Int?, String?>? {
        if (body.isNullOrBlank()) return null
        val json = try {
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        }
        val code: Int?
        val error: String?
        if (json != null) {
            code = json.get("Code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            error = json.get("Error")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        } else {
            code = CODE_FIELD.find(body)?.groupValues?.get(1)?.toIntOrNull()
            error = ERROR_FIELD.find(body)?.groupValues?.get(1)?.let(::unescape)
        }
        return if (code == null && error == null) null else code to error
    }

    /**
     * The suffix to append to a log line: ` (Code 8002: <Error>)`, ` (Code 8002)` when there is no
     * Error or [withError] is false, ` (Error: <Error>)` without a Code, or `""` when [body] carries
     * neither. The empty string lets a call site simply append it to `HTTP <status>`.
     */
    fun describe(body: String?, withError: Boolean = true): String =
        parse(body)?.let { format(it.first, it.second, withError) } ?: ""

    /** [describe] for a Code and Error already in hand, for example per-operation results. */
    fun format(code: Int?, error: String?, withError: Boolean = true): String {
        val text = if (withError) clean(error) else null
        return when {
            code != null && text != null -> " (Code $code: $text)"
            code != null -> " (Code $code)"
            text != null -> " (Error: $text)"
            else -> ""
        }
    }

    /**
     * Proton's `Error` made fit for a shared log, or null when nothing is left: markup dropped
     * (Proton's own clients sanitise the HTML in a 10003 message), every address replaced with
     * `<address>`, one line (a body must not be able to forge log lines), at most
     * [MAX_ERROR_CHARS] characters.
     */
    fun clean(error: String?): String? {
        if (error.isNullOrBlank()) return null
        var text = MARKUP.replace(error, "")
        text = ADDRESS.replace(text, "<address>")
        text = WHITESPACE.replace(text, " ").trim()
        if (text.isEmpty()) return null
        return if (text.length > MAX_ERROR_CHARS) text.take(MAX_ERROR_CHARS) + "…" else text
    }

    /** A JSON string's escapes resolved; the raw text when they do not parse (a cut-off escape). */
    private fun unescape(raw: String): String =
        try {
            JsonParser.parseString("\"$raw\"").asString
        } catch (_: Exception) {
            raw
        }
}

/**
 * [ProtonApiError.describe] for a failed Retrofit call. It reads and closes the error body, so use it
 * only where nothing else reads that body; a site that already holds the body text passes that to
 * [ProtonApiError.describe] instead.
 */
internal fun Response<*>.protonErrorSuffix(withError: Boolean = true): String =
    ProtonApiError.describe(
        try {
            errorBody()?.string()
        } catch (_: Exception) {
            null
        },
        withError
    )
