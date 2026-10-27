package me.proton.sync.billing

import java.text.Normalizer

/**
 * What the text in the direct version's subscription dialog is. Customers paste whatever they find in
 * Paddle's emails or on the success page: the subscription id alone, a line or a link with the id in it,
 * but also a transaction id, a Google Play order number or their email address. Pure, so it can be tested.
 *
 * Only a [Sendable] entry is sent to the licence proxy: a subscription id ([Valid]) to be checked, or a
 * transaction id ([ValidTransaction]) to be looked up first. Anything else is answered under the field
 * straight away, so text that is not an id (it may be an email address) never leaves the phone.
 */
internal sealed class SubscriptionEntry(val kind: String) {
    /** The kinds the dialog sends to the licence proxy: [Valid] and [ValidTransaction]. */
    sealed interface Sendable

    /** One subscription id, [inText] when other text came with it (a link, a labelled line). */
    data class Valid(val subId: String, val inText: Boolean) :
        SubscriptionEntry(if (inText) "valid_in_text" else "valid"), Sendable {
        // The id is a licence key: logging the object must never print it.
        override fun toString() = "Valid(inText=$inText)"
    }

    /**
     * One Paddle transaction id (from the invoice, or a checkout link's _ptxn) and no subscription id, [inText]
     * as for [Valid]. The licence proxy's /txn-to-sub names the subscription it paid for, and that id is then
     * checked as if it had been entered.
     */
    data class ValidTransaction(val txnId: String, val inText: Boolean) :
        SubscriptionEntry(if (inText) "valid_transaction_in_text" else "valid_transaction"), Sendable {
        // It leads to the licence key, so it stays out of the log as the key does.
        override fun toString() = "ValidTransaction(inText=$inText)"
    }
    data object Empty : SubscriptionEntry("empty")
    data object SeveralIds : SubscriptionEntry("several_ids")
    data object PlayOrder : SubscriptionEntry("play_order")
    data object MistypedTransactionId : SubscriptionEntry("mistyped_transaction_id")
    data object MistypedSubId : SubscriptionEntry("mistyped_sub_id")
    data object Email : SubscriptionEntry("email")
    data object NotAnId : SubscriptionEntry("not_an_id")

    /** Longer than [MAX_ENTRY_CHARS]: not searched at all (some of the checks take long on very long text). */
    data object TooLong : SubscriptionEntry("too_long")
}

/**
 * The longest text the dialog searches for an id, as long as Android 13 and later let a single-line field be. A
 * pasted receipt, a Paddle customer-portal link (about 1,500 characters with its token) or an Outlook safelink around
 * one fit easily; tens of thousands of characters kept the main thread busy for seconds. The dialog's field holds one
 * character more, so a longer paste is answered as too long on every version instead of being cut short silently.
 */
internal const val MAX_ENTRY_CHARS = 5_000

// Mirrors the licence proxy's SUB_ID check (a Crockford base32 ULID: no
// i, l, o or u, first character 0-7); change both together. No lookbehind on purpose: in an Outlook safelink
// the id follows "%3D". The lookahead keeps a 27-character typo from being cut silently to 26, except where
// another "sub_" starts: a whole new id pasted straight against the dialog's pre-filled one is then found as a
// second id (SeveralIds). A broken one there is caught by what is left over (BROKEN_ID). Either way the new
// paste is not dropped while the old id is checked again. The proxy only ever gets the one id parsed here, so
// its SUB_ID needs no such exception. Nor does an id end right before an underscore: "sub" cannot be inside an id
// (no u), but "txn" can, so without it a cut-short id would take the "txn" of a transaction id after it as its
// last three characters (in the second pass, with the space between them gone). For the same reason no id ends in
// "txn" right before a hyphen, the "txn-" typo: that made up a whole id out of a cut one and sent it to the proxy.
private val SUB_ID_IN_TEXT = Regex("sub_[0-7][0-9a-hjkmnp-tv-z]{25}(?:(?=sub_)|(?![0-9a-z_])(?!(?<=txn)-))")
// What can be left of another id once the one found is taken out: a prefix and a character an id can start
// with, or a prefix alone at the very end (typed after the id, the start of a new one). A prefix followed by
// anything else ("sub_" typed in front of a link, a label or an email address) is not.
private val BROKEN_ID = Regex("sub[_-][0-7]|sub[_-]$")

// The proxy's TXN_ID, the same shape behind "txn_"; change both together. Found as SUB_ID_IN_TEXT finds a
// subscription id, except that it also ends where a "sub_" starts: in a paste that runs a transaction id into a
// subscription id, each is found.
private val TXN_ID_IN_TEXT = Regex("txn_[0-7][0-9a-hjkmnp-tv-z]{25}(?:(?=txn_|sub_)|(?![0-9a-z_])(?!(?<=txn)-))")
// A subscription id begun beside a transaction id ("sub_" and a character an id starts with) that is not one whole:
// the subscription id is what the customer meant, so the transaction id is not looked up in its place.
private val BROKEN_SUB_ID = Regex("sub[_-][0-7]")
// A whole subscription id: the start of a subscription id run straight into a transaction id, which SUB_ID_IN_TEXT
// cannot find there.
private const val WHOLE_SUB_ID = "sub_[0-7][0-9a-hjkmnp-tv-z]{25}"
private val WHOLE_TXN_ID = Regex("txn_[0-7][0-9a-hjkmnp-tv-z]{25}")

// The proxy's SUB_LIKE, plus the "sub-" typo: something meant as an id, but cut short or mistyped.
private val SUB_LIKE = Regex("sub[_-][0-9a-z]+")
// The prefix alone, as customers type it before giving up (seen in the proxy's logs): also a mistyped id.
private val SUB_PREFIX = Regex("sub[_-]")
// Something meant as a transaction id but not a whole one, the "txn-" typo included, as for "sub-".
private val TXN_LIKE = Regex("txn[_-][0-9a-z]+")
// The prefix alone: a mistyped transaction id, as SUB_PREFIX is a mistyped subscription id.
private val TXN_PREFIX = Regex("txn[_-]")
// What can be left of another transaction id once the one found is taken out: a prefix and a character an id
// can start with. Unlike BROKEN_ID, no prefix alone at the end: "txn_" typed after the id is let through.
private val BROKEN_TXN_ID = Regex("txn[_-][0-7]")
private val PLAY_ORDER = Regex("gpa\\.[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{5}")
// Tried only where a run of non-space, non-"@" characters starts, or where the previous match ended (\G): the same
// matches as without that, but in linear time. Tried at every position, a long paste without a space took seconds
// (16 s for 50,000 characters on a PC) on the main thread.
private val EMAIL = Regex("(?:(?<![^\\s@])|\\G)[^\\s@]+@[^\\s@]+\\.[a-z]{2,}")
private val WHITESPACE = Regex("\\s+")
// A link's token parameter. The payment-method link in Paddle's email carries a 1,300-character base64url one, whose
// random letters spelled "sub-3" or "txn_5" in about 1 link in 4,000: read as a broken second id, the link was refused.
// Left out wherever what is left over is searched for one (never where the id itself is searched for). Also in its
// percent-encoded form, as in an Outlook safelink (%26token%3d); the value ends at "%", which base64url never has.
private val URL_TOKEN = Regex("(?:(?<=[?&])|(?<=%26)|(?<=%3f))token(?:=|%3d)[^&%\\s]*")

/**
 * Read the dialog's text. One subscription id gives [SubscriptionEntry.Valid] with the id as the proxy expects it
 * (lower case); two different ids give [SubscriptionEntry.SeveralIds]. Text without a subscription id but with one
 * transaction id gives [SubscriptionEntry.ValidTransaction]. Anything else is named as well as it can be, a
 * receipt that holds an email address as well as a broken transaction id under the transaction id's kind.
 *
 * A subscription id decides whenever there is one: a pasted receipt line can carry both kinds, and the
 * subscription id is the key itself, where a transaction id has to be looked up first.
 *
 * An email address is taken out before the text is searched for a mistyped id, so "sub_" or "txn_" typed in front
 * of the address (seen in the proxy's logs) is answered as an email address, while a real attempt at an id next
 * to one (a receipt line) is still a mistyped id. A bare "sub_" or "txn_" with nothing else is a mistyped id too.
 *
 * [prefilled] is the stored subscription id the dialog's field starts with. When it is the only subscription id in
 * the text, a transaction id beside it is what was pasted (a new purchase), so that one is looked up instead of the
 * stored id being checked again, also with whitespace or punctuation between the two. Elsewhere, such as a
 * settings backup, there is none, and a subscription id decides over a transaction id as in a receipt line.
 */
internal fun parseSubscriptionEntry(raw: String, prefilled: String? = null): SubscriptionEntry {
    val text = cleaned(raw)
    if (text.isEmpty()) return SubscriptionEntry.Empty
    if (text.length > MAX_ENTRY_CHARS) return SubscriptionEntry.TooLong
    // Whitespace kept first, so the lookahead ends an id at the space in "sub_... is my id". Only when
    // that finds nothing is the text searched again without it, for an id broken by a space or a line wrap.
    val ids = idsIn(SUB_ID_IN_TEXT, text).ifEmpty { idsIn(SUB_ID_IN_TEXT, text.replace(WHITESPACE, "")) }
    if (ids.isNotEmpty()) {
        val entry = subscriptionIdEntry(text, ids)
        val stored = prefilled?.let { cleaned(it) }?.takeIf { it.isNotEmpty() }
        if (entry is SubscriptionEntry.Valid && entry.subId == stored) pastedBesidePrefilled(text, stored)?.let { return it }
        return entry
    }
    val txnIds = idsIn(TXN_ID_IN_TEXT, text).ifEmpty { idsIn(TXN_ID_IN_TEXT, text.replace(WHITESPACE, "")) }
    if (txnIds.isNotEmpty()) return transactionIdEntry(text, txnIds)
    val withoutEmail = EMAIL.replace(text, " ")
    return when {
        PLAY_ORDER.containsMatchIn(text) -> SubscriptionEntry.PlayOrder
        TXN_LIKE.containsMatchIn(withoutEmail) -> SubscriptionEntry.MistypedTransactionId
        SUB_LIKE.containsMatchIn(withoutEmail) -> SubscriptionEntry.MistypedSubId
        EMAIL.containsMatchIn(text) -> SubscriptionEntry.Email
        TXN_PREFIX.containsMatchIn(text) -> SubscriptionEntry.MistypedTransactionId
        SUB_PREFIX.containsMatchIn(text) -> SubscriptionEntry.MistypedSubId
        else -> SubscriptionEntry.NotAnId
    }
}

/** [text] with the subscription [ids] found in it; any transaction id beside them is not looked up. */
private fun subscriptionIdEntry(text: String, ids: List<String>): SubscriptionEntry {
    if (ids.size > 1) return SubscriptionEntry.SeveralIds
    val id = ids[0]
    // A broken second id beside the one found, typically a paste next to the dialog's pre-filled id: refused,
    // so the pre-filled id is not checked again in place of what was pasted. Looked for without whitespace,
    // after taking out every copy of the id (a line wrap can split one) together with any "sub_" typed right in
    // front of it: the field says ids start with "sub_", so "sub_" and then a pasted id is still that id. Not tried
    // where a prefix that itself follows a "_" or "-" comes right before (from a run's third prefix on): a run is
    // then read from its first two prefixes only, not from every one in it, which took a quarter of a second for
    // 2,500 of them. The same copies are taken out: past an id's own prefix there is no "_" or "-", so the end of a
    // copy never passes for such a prefix.
    val leftover = text.replace(URL_TOKEN, "").replace(WHITESPACE, "")
        .replace(Regex("(?<![_-]sub[_-])(?:sub[_-])*" + Regex.escape(id)), " ")
    if (BROKEN_ID.containsMatchIn(leftover)) return SubscriptionEntry.MistypedSubId
    // The same for a transaction id pasted straight in front of the pre-filled id: with nothing between the two it
    // is no receipt line, and the pre-filled id would be checked again in place of the paste. Two ids when what was
    // pasted is a whole one, a mistyped one when not. "txn_" alone in front is a typed prefix, as "sub_" is above.
    // A whole one may carry a line wrap or a space inside (copied from the invoice), but not between it and the id:
    // "$txn $id" is a receipt line, where the subscription id decides.
    Regex("txn_[0-9a-z\\s]*[0-9a-z]" + Regex.escape(id)).find(text)?.let { against ->
        if (WHOLE_TXN_ID.matches(against.value.dropLast(id.length).replace(WHITESPACE, ""))) return SubscriptionEntry.SeveralIds
    }
    Regex("txn[_-][0-9a-z]+" + Regex.escape(id)).find(text)?.let { against ->
        val pasted = against.value.dropLast(id.length)
        return if (WHOLE_TXN_ID.matches(pasted)) SubscriptionEntry.SeveralIds else SubscriptionEntry.MistypedTransactionId
    }
    return SubscriptionEntry.Valid(id, inText = text != id)
}

/**
 * What was pasted beside the pre-filled [stored] id, the only subscription id in [text]: a transaction id (or
 * several, or a broken one) once every copy of [stored] and any "sub_" typed in front of it are taken out, or null
 * when there is none and the stored id is what the customer means.
 */
private fun pastedBesidePrefilled(text: String, stored: String): SubscriptionEntry? {
    // Every copy, also one split by a line wrap or a space (the whitespace-free second search finds those), with any
    // "sub_" typed in front: a receipt for the stored subscription may wrap its id. The prefixes are taken
    // possessively (++), the id's own "sub_" with them: repeated without that, each one cost the matcher a level
    // of stack, and 2,500 of them ran out of it. Not tried from a run's third
    // prefix on, as in subscriptionIdEntry (a lookbehind must be bounded: up to 8 spaces at each place inside it are
    // seen, wider gaps only cost time). The "s" is checked first: the lookbehind alone, tried at every position, took
    // longer on 10,000 ordinary characters than everything else here together.
    val spread = stored.removePrefix("sub_").map { Regex.escape(it.toString()) }.joinToString("\\s*")
    val rest = text.replace(
        Regex("(?=s)(?<![_-]\\s{0,8}s\\s{0,8}u\\s{0,8}b\\s{0,8}[_-]\\s{0,8})(?:s\\s*u\\s*b\\s*[_-]\\s*)++$spread"),
        " ",
    ).trim()
    if (rest.isEmpty()) return null
    val txnIds = idsIn(TXN_ID_IN_TEXT, rest).ifEmpty { idsIn(TXN_ID_IN_TEXT, rest.replace(WHITESPACE, "")) }
    if (txnIds.isNotEmpty()) {
        // The stored id was in the text too, so the transaction id never stood alone in it.
        return when (val entry = transactionIdEntry(rest, txnIds)) {
            is SubscriptionEntry.ValidTransaction -> entry.copy(inText = true)
            else -> entry
        }
    }
    return if (BROKEN_TXN_ID.containsMatchIn(rest.replace(URL_TOKEN, "").replace(WHITESPACE, ""))) {
        SubscriptionEntry.MistypedTransactionId
    } else {
        null
    }
}

/** [text], which holds no subscription id, with the transaction [txnIds] found in it. */
private fun transactionIdEntry(text: String, txnIds: List<String>): SubscriptionEntry {
    if (txnIds.size > 1) return SubscriptionEntry.SeveralIds
    val txnId = txnIds[0]
    // A whole subscription id with the transaction id pasted straight after it (the dialog's pre-filled id, the
    // cursor put at its end): two ids, as in front of it.
    if (Regex(WHOLE_SUB_ID + Regex.escape(txnId)).containsMatchIn(text)) return SubscriptionEntry.SeveralIds
    // What is left without whitespace, every copy of the transaction id (and any "txn_" typed in front) taken out.
    // Another whole transaction id there (one split by a line wrap, which the first search cannot see) makes two;
    // a broken one is a mistyped transaction id, so the first is not looked up in place of what was pasted, as
    // BROKEN_ID does for subscription ids. A subscription id begun beside it but not a whole one: the customer meant
    // the subscription id, so that one is called mistyped instead of the transaction id being looked up. Not tried
    // from a run's third prefix on, as in subscriptionIdEntry.
    val leftover = text.replace(URL_TOKEN, "").replace(WHITESPACE, "")
        .replace(Regex("(?<![_-]txn[_-])(?:txn[_-])*" + Regex.escape(txnId)), " ")
    if (WHOLE_TXN_ID.containsMatchIn(leftover)) return SubscriptionEntry.SeveralIds
    if (BROKEN_TXN_ID.containsMatchIn(leftover)) return SubscriptionEntry.MistypedTransactionId
    if (BROKEN_SUB_ID.containsMatchIn(leftover)) return SubscriptionEntry.MistypedSubId
    return SubscriptionEntry.ValidTransaction(txnId, inText = text != txnId)
}

/**
 * NFKC turns full-width letters and non-breaking or ideographic spaces into plain ones; invisible format
 * characters a copy can carry along (zero-width space, byte order mark, soft hyphen, word joiner, bidi
 * marks) are dropped. The whitespace NFKC keeps (the line and paragraph separators, NEL, the Ogham space mark)
 * becomes plain spaces: the phone's regex engine (ICU) counts it as whitespace and the JVM the tests run on does
 * not, so the tests see what the phone does (checked on Android 12 and 16). Kotlin's lowercase() ignores the
 * phone's locale, so a Turkish phone gets no dotless i.
 */
private fun cleaned(raw: String): String {
    val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
    val out = StringBuilder(nfkc.length)
    var i = 0
    while (i < nfkc.length) {
        val cp = nfkc.codePointAt(i)
        when {
            cp == 0x85 || cp == 0x1680 || cp == 0x2028 || cp == 0x2029 -> out.append(' ')
            Character.getType(cp) != Character.FORMAT.toInt() -> out.appendCodePoint(cp)
        }
        i += Character.charCount(cp)
    }
    return out.toString().trim().lowercase()
}

private fun idsIn(pattern: Regex, text: String): List<String> = pattern.findAll(text).map { it.value }.distinct().toList()
