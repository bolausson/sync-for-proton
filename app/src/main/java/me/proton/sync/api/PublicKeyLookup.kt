package me.proton.sync.api

/**
 * `RecipientType` in a key-lookup response, from Proton's own definitions (go-proton-api
 * `contact_types.go`: RecipientTypeInternal = 1, RecipientTypeExternal = 2; WebClients
 * `RECIPIENT_TYPES.TYPE_INTERNAL = 1`).
 */
internal const val RECIPIENT_TYPE_INTERNAL = 1

/**
 * The armored public key to encrypt to when [body], a `GET keys?Email=` response, describes a
 * Proton user; null for anyone else.
 *
 * A Proton user is `RecipientType == 1`, exactly as Proton's web client decides it
 * (`getIsInternalUser` in WebClients `packages/shared/lib/keys/publicKeys.ts`). It is NOT "the response
 * has keys": an ordinary external address normally comes back as HTTP 200 with `RecipientType` 2,
 * and an external domain that publishes WKD keys puts those keys in the same `Keys` array. The old
 * "first key wins" rule therefore treated such an address as a Proton user.
 *
 * If `RecipientType` is missing the old rule is kept. Guessing "external" there would quietly strip
 * end-to-end encryption from real Proton users should the response ever change shape, which is the
 * more harmful mistake of the two.
 */
internal fun protonRecipientKey(body: Map<String, Any>?): String? {
    val firstKey = ((body?.get("Keys") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("PublicKey") as? String
    return when ((body?.get("RecipientType") as? Number)?.toInt()) {
        null -> firstKey
        RECIPIENT_TYPE_INTERNAL -> firstKey
        else -> null
    }
}

/**
 * Proton error codes that are the ordinary answer for an address outside Proton, so not worth a
 * warning. Numeric values from WebClients `packages/shared/lib/errors.ts`:
 *
 *   33102 KEY_GET_ADDRESS_MISSING   no such address
 *   33103 KEY_GET_DOMAIN_EXTERNAL   the domain is not Proton's
 *
 * Deliberately NOT included, and so still logged: 33101 KEY_GET_INPUT_INVALID and 33104
 * KEY_GET_INVALID_KT. Proton's web client maps only 33103 to "external recipient" and surfaces the
 * others as recipient errors; Drive and Pass accept 33102 and 33103. A key-transparency failure in
 * particular is not something to hide.
 */
internal val NOT_A_PROTON_ADDRESS_CODES: Set<Int> = setOf(33102, 33103)

private val PROTON_CODE = Regex("\"Code\"\\s*:\\s*(\\d+)")

/** The numeric Proton `Code` in a response body, or null when the body carries none. */
internal fun protonErrorCode(body: String?): Int? =
    body?.let { PROTON_CODE.find(it)?.groupValues?.get(1)?.toIntOrNull() }

/**
 * Why a failed recipient key lookup deserves a warning, or null when the failure is just the normal
 * answer for an address outside Proton.
 *
 * Both callers turn a failed lookup into "not a Proton user". For a genuinely external or missing
 * address that is right. For a lookup that failed for any other reason it silently handles a Proton
 * attendee as external, so the invitation loses end-to-end encryption to them, and until this
 * existed nothing was logged. The route is a legacy one (Proton's web client moved to `keys/all` in
 * 2023; Proton Mail Bridge still calls it), so a retirement would show up as a run of these warnings
 * rather than as silence.
 *
 * Matching is on the whole number, so a code such as 331020 is not mistaken for 33102.
 */
internal fun unexpectedKeyLookupFailure(httpCode: Int, errorBody: String?): String? {
    val code = protonErrorCode(errorBody)
    if (code != null && code in NOT_A_PROTON_ADDRESS_CODES) return null
    return if (code != null) "HTTP $httpCode, Proton code $code" else "HTTP $httpCode"
}
