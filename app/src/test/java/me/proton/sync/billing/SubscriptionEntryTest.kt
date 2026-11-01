package me.proton.sync.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.system.measureNanoTime

/**
 * What the subscription dialog makes of the text a customer enters. Only [SubscriptionEntry.Valid] reaches
 * the licence proxy, so anything else must be named here, and an id wrapped in a link, a labelled line or
 * invisible characters from a copy must still come out as the id.
 *
 * Made-up vectors only: [V] has the format of a Paddle subscription id (checked against the proxy's pattern)
 * but belongs to no one. It starts with 07, not the 01 that starts every real Paddle id (a ULID, whose first
 * characters are its creation time), so it can never be one; the source snapshot refuses anything shaped like
 * a real id or a real Google Play order number, which is why the order numbers below are not in capitals.
 * [T] and [T2] are transaction ids made the same way.
 */
class SubscriptionEntryTest {

    private val V = "sub_07h04vsc0qhwtsbsxh3422wjs4"
    private val FAKE = "sub_01" + "a".repeat(24)
    private val T = "txn_07h04vsc0qhwtsbsxh3422wjs4"
    private val T2 = "txn_07" + "b".repeat(24)

    /** Every vector below, for the privacy check at the end. */
    private val seen = mutableListOf<SubscriptionEntry>()

    private fun parse(raw: String): SubscriptionEntry = parseSubscriptionEntry(raw).also { seen += it }

    private fun valid(raw: String, inText: Boolean) {
        assertEquals(raw, SubscriptionEntry.Valid(V, inText), parse(raw))
    }

    private fun transaction(raw: String, inText: Boolean) {
        assertEquals(raw, SubscriptionEntry.ValidTransaction(T, inText), parse(raw))
    }

    private fun kind(raw: String, expected: SubscriptionEntry) {
        assertEquals(raw, expected, parse(raw))
    }

    /** [raw] as the dialog reads it when its field started with the stored id [V]. */
    private fun prefilled(raw: String, expected: SubscriptionEntry) {
        assertEquals(raw, expected, parseSubscriptionEntry(raw, prefilled = V).also { seen += it })
    }

    /** The full-width form of [s], as some keyboards and East Asian input methods type it. */
    private fun fullWidth(s: String) = s.map { if (it in '!'..'~') (it.code + 0xFEE0).toChar() else it }.joinToString("")

    // ---------------------------------------------------------------- the id alone

    @Test
    fun `the id alone, however it was copied, is valid and not in text`() {
        valid(V, inText = false)
        valid("  $V\n", inText = false)
        valid(V.uppercase(), inText = false)
        valid("\uFEFF$V", inText = false)
        valid(V.substring(0, 8) + "\u200B" + V.substring(8, 14) + "\u00AD" + V.substring(14, 20) + "\u2060" + V.substring(20), inText = false)
        valid("\u00A0$V\u00A0", inText = false)
        valid("\u3000$V\u3000", inText = false)
        valid(fullWidth(V), inText = false)
    }

    @Test
    fun `whitespace that only the phone's regex engine knows is whitespace in the tests too`() {
        // Android's regex (ICU) counts these as whitespace, the JVM does not, and NFKC keeps them: cleaned() makes them
        // spaces, so the answers here are the phone's (compared on Android 12 and 16 over 15,000 texts).
        for (sp in listOf("\u2028", "\u2029", "\u0085", "\u1680")) {
            valid(V.substring(0, 12) + sp + V.substring(12), inText = true)
            valid("s${sp}u${sp}b${sp}_$sp" + V.substring(4), inText = true)
            transaction(T.substring(0, 10) + sp + T.substring(10), inText = true)
            kind("name${sp}@example.com", SubscriptionEntry.NotAnId)
            valid("$sp$V$sp", inText = false)
        }
    }

    // ---------------------------------------------------------------- the id in other text

    @Test
    fun `an id with punctuation after it is found, not cut or refused`() {
        for (end in listOf(".", ",", ";", ")", "\"", "\u201D")) valid(V + end, inText = true)
    }

    @Test
    fun `an id in a labelled line or a link is found`() {
        valid("Subscription ID: $V", inText = true)
        valid("$V is my id", inText = true)
        valid("https://customer-portal.paddle.com/cpl_01abc?action=cancel_subscription&subscription_id=$V", inText = true)
        valid(
            "https://eur01.safelinks.protection.outlook.com/?url=https%3A%2F%2Fcustomer-portal.paddle.com%2Fcpl_01abc" +
                "%3Fsubscription_id%3D$V%26a&data=05",
            inText = true,
        )
        valid("https://apps.example/success.html?_sub=$V", inText = true)
    }

    @Test
    fun `the same id twice is one id`() {
        valid("$V $V", inText = true)
    }

    @Test
    fun `an id broken by a space or a line wrap is found in the second pass`() {
        valid(V.substring(0, 15) + " " + V.substring(15), inText = true)
        valid(V.substring(0, 15) + "\n" + V.substring(15), inText = true)
    }

    @Test
    fun `two different ids are several ids`() {
        kind("$V $FAKE", SubscriptionEntry.SeveralIds)
        kind("$V\n$FAKE.", SubscriptionEntry.SeveralIds)
        // Run together: a new id pasted in front of, or after, the dialog's pre-filled one. In front, the old
        // id alone was found and checked again while the new one never reached the proxy.
        kind("$FAKE$V", SubscriptionEntry.SeveralIds)
        kind("$V$FAKE", SubscriptionEntry.SeveralIds)
        // Pasted into the middle of the pre-filled id: neither is whole, so nothing is sent.
        kind(V.substring(0, 10) + FAKE + V.substring(10), SubscriptionEntry.MistypedSubId)
        // A broken new id against the pre-filled one, on either side: refused, never the old id checked again.
        kind(V + FAKE.dropLast(3), SubscriptionEntry.MistypedSubId)
        kind(FAKE.dropLast(3) + V, SubscriptionEntry.MistypedSubId)
        kind(FAKE + "a" + V, SubscriptionEntry.MistypedSubId)
        kind(V + FAKE.dropLast(1) + "o", SubscriptionEntry.MistypedSubId)
        kind(V + "sub_", SubscriptionEntry.MistypedSubId)
        // The same id twice is still one id.
        valid("$V $V", inText = true)
        // "sub_" typed by hand, then the whole id pasted after it, is that id (accepted before 2.0.2's checks).
        valid("sub_$V", inText = true)
        valid("sub_ $V", inText = true)
        valid("sub_sub_$V", inText = true)
        valid("sub_$V$V", inText = true)
        // A "sub_" typed first, then a link, a label line, a quoted id or an email address pasted after it.
        valid("sub_https://customer-portal.paddle.com/cpl_01abc?action=overview&subscription_id=$V&token=pga_abc", inText = true)
        valid("sub_Subscription ID: $V", inText = true)
        valid("sub_ Subscription ID: $V", inText = true)
        valid("sub_\"$V\"", inText = true)
        valid("sub_name@example.com $V", inText = true)
        // A broken id after a typed "sub_" is still refused.
        kind("sub_sub_" + FAKE.dropLast(3) + V, SubscriptionEntry.MistypedSubId)
        kind("sub_ " + FAKE.dropLast(3) + V, SubscriptionEntry.MistypedSubId)
        // The same id twice in a copied receipt, one copy split by a line wrap, is that id.
        valid("Subscription ID: $V\nhttps://customer-portal.paddle.com/cpl_01abc?subscription_id=${V.take(14)}\n${V.drop(14)}", inText = true)
        // The "sub-" typo of a broken second id is refused too.
        kind("$V sub-01j" + "a".repeat(20), SubscriptionEntry.MistypedSubId)
    }

    // ---------------------------------------------------------------- not an id

    @Test
    fun `an id that is cut short, too long or mistyped is a mistyped id, never a shorter valid one`() {
        kind(V + "x", SubscriptionEntry.MistypedSubId)
        kind(V.dropLast(1), SubscriptionEntry.MistypedSubId)
        kind(V.dropLast(1) + "u", SubscriptionEntry.MistypedSubId)
        for (letter in listOf("i", "l", "o")) kind(V.substring(0, 10) + letter + V.substring(11), SubscriptionEntry.MistypedSubId)
        kind("sub_81" + V.drop(6), SubscriptionEntry.MistypedSubId)
        kind("sub-" + V.drop(4), SubscriptionEntry.MistypedSubId)
    }

    // ---------------------------------------------------------------- transaction ids

    @Test
    fun `a transaction id alone or in other text is a transaction to look up`() {
        transaction(T, inText = false)
        transaction("  ${T.uppercase()}\n", inText = false)
        transaction(fullWidth(T), inText = false)
        transaction(T.substring(0, 8) + "\u200B" + T.substring(8), inText = false)
        transaction("$T.", inText = true)
        transaction("Transaction ID: $T", inText = true)
        transaction("https://apps.example/checkout.html?_ptxn=$T", inText = true)
        transaction("$T $T", inText = true)
        transaction(T.substring(0, 15) + "\n" + T.substring(15), inText = true)
        // A prefix typed in front of the pasted id, or "sub_" alone after it, is no second id.
        transaction("txn_$T", inText = true)
        transaction("sub_$T", inText = true)
        transaction("$T sub_", inText = true)
    }

    @Test
    fun `a subscription id decides over a transaction id in the same text`() {
        valid("Subscription ID: $V, transaction ID: $T", inText = true)
        valid("$T $V", inText = true)
        valid("$V\n$T", inText = true)
        valid("https://apps.example/success.html?_ptxn=$T&_sub=$V", inText = true)
        // Broken by a line wrap, the subscription id is still found in the second pass, and still decides.
        valid("$T\n" + V.substring(0, 15) + "\n" + V.substring(15), inText = true)
        // Two subscription ids stay two, whatever transaction id comes with them.
        kind("$V $FAKE $T", SubscriptionEntry.SeveralIds)
    }

    @Test
    fun `two different transaction ids are several ids`() {
        kind("$T $T2", SubscriptionEntry.SeveralIds)
        kind("$T\n$T2.", SubscriptionEntry.SeveralIds)
        kind("$T$T2", SubscriptionEntry.SeveralIds)
        // The second split by a line wrap, on either side: still two, never the first looked up alone.
        kind(T + " " + T2.substring(0, 15) + "\n" + T2.substring(15), SubscriptionEntry.SeveralIds)
        kind(T2.substring(0, 15) + "\n" + T2.substring(15) + " " + T, SubscriptionEntry.SeveralIds)
        // A broken second one beside a whole one: refused, so the whole one is not looked up in its place.
        kind("$T " + T2.dropLast(3), SubscriptionEntry.MistypedTransactionId)
        kind(T + T2.dropLast(3), SubscriptionEntry.MistypedTransactionId)
    }

    @Test
    fun `a transaction id that is cut short, too long or mistyped is a mistyped transaction id`() {
        kind(T.dropLast(1), SubscriptionEntry.MistypedTransactionId)
        kind(T + "x", SubscriptionEntry.MistypedTransactionId)
        kind(T.dropLast(1) + "u", SubscriptionEntry.MistypedTransactionId)
        kind("txn_81" + T.drop(6), SubscriptionEntry.MistypedTransactionId)
        kind("https://apps.example/checkout.html?_ptxn=${T.dropLast(2)}", SubscriptionEntry.MistypedTransactionId)
        // The prefix alone, too.
        kind("txn_", SubscriptionEntry.MistypedTransactionId)
        kind("TXN_", SubscriptionEntry.MistypedTransactionId)
        // A cut id run into the "txn-" typo once ended in that "txn" and came out whole, made up, and was sent.
        kind("sub_07" + "a".repeat(21) + "txn-" + T.drop(4), SubscriptionEntry.MistypedTransactionId)
        kind(T.dropLast(3) + "txn-", SubscriptionEntry.MistypedTransactionId)
        // The "txn-" typo, as "sub-" is handled.
        kind("txn-" + T.drop(4), SubscriptionEntry.MistypedTransactionId)
        kind("TXN-" + T.drop(4), SubscriptionEntry.MistypedTransactionId)
        kind("txn-", SubscriptionEntry.MistypedTransactionId)
        // "txn_" typed in front of an email address is answered as the email address, as "sub_" is.
        kind("txn_name@example.com", SubscriptionEntry.Email)
        kind("txn_ name@example.com", SubscriptionEntry.Email)
        kind("TXN_ name@example.com", SubscriptionEntry.Email)
    }

    @Test
    fun `a broken subscription id beside a transaction id is a mistyped subscription id`() {
        // The customer meant the subscription id, so the transaction id is not looked up in its place.
        kind("$T ${V.dropLast(1)}", SubscriptionEntry.MistypedSubId)
        // Three characters short: without the space, "txn" would make it whole, and that made-up id was sent.
        kind("${V.dropLast(3)} $T", SubscriptionEntry.MistypedSubId)
        kind("${V.dropLast(3)}\n$T", SubscriptionEntry.MistypedSubId)
        kind("$T sub-" + V.drop(4), SubscriptionEntry.MistypedSubId)
        kind(T + V.dropLast(1), SubscriptionEntry.MistypedSubId)
    }

    @Test
    fun `a transaction id run straight against the pre-filled subscription id is refused, never the old id checked`() {
        // Pasted in front of or after the dialog's pre-filled id with nothing between: no receipt line, so the
        // subscription id does not decide, and the pre-filled id is not checked again in place of the paste.
        kind("$T$V", SubscriptionEntry.SeveralIds)
        kind("$V$T", SubscriptionEntry.SeveralIds)
        // A whole one with a line wrap or a space inside, from the invoice, is still two ids: the old id was checked.
        kind(T.substring(0, 15) + "\n" + T.substring(15) + V, SubscriptionEntry.SeveralIds)
        kind(T.substring(0, 15) + " " + T.substring(15) + V, SubscriptionEntry.SeveralIds)
        kind(T.substring(0, 8) + "\n" + T.substring(8, 20) + "\n" + T.substring(20) + V, SubscriptionEntry.SeveralIds)
        // With whitespace between the two it is a receipt line, where the subscription id decides.
        valid("$T $V", inText = true)
        valid(T.substring(0, 15) + "\n" + T.substring(15) + "\n" + V, inText = true)
        valid("$T\nSubscription ID $V", inText = true)
        kind(T.dropLast(3) + V, SubscriptionEntry.MistypedTransactionId)
        kind(V + T.dropLast(3), SubscriptionEntry.MistypedTransactionId)
        // "txn_" typed in front of a pasted subscription id is a typed prefix, as "sub_" is.
        valid("txn_$V", inText = true)
    }

    @Test
    fun `beside the pre-filled stored id, a pasted transaction id is looked up, never the old id checked again`() {
        // Whatever separates them: a line end, a space, punctuation, the success page's technical details line.
        val looked = SubscriptionEntry.ValidTransaction(T, inText = true)
        prefilled("Transaction ID: $T\n$V", looked)
        prefilled("$T $V", looked)
        prefilled("$T.$V", looked)
        prefilled("$T,$V", looked)
        prefilled("txn=$T, last_error=not_ready$V", looked)
        prefilled("$V\nTransaction $T", looked)
        prefilled(T.substring(0, 15) + "\n" + T.substring(15) + "\n" + V, looked)
        // The stored id split by a line wrap or a space in a pasted receipt is still the stored id.
        val wrapped = V.substring(0, 15) + "\n" + V.substring(15)
        prefilled("$T $wrapped", looked)
        prefilled("$V Subscription $wrapped Transaction $T", looked)
        prefilled("$T " + V.substring(0, 15) + " " + V.substring(15), looked)
        // Two pasted, or a broken one: refused as without the stored id.
        prefilled("$T $T2 $V", SubscriptionEntry.SeveralIds)
        prefilled(T.dropLast(3) + " " + V, SubscriptionEntry.MistypedTransactionId)
        // The field left as it was, or only a prefix typed: the stored id is what is meant.
        prefilled(V, SubscriptionEntry.Valid(V, inText = false))
        prefilled("$V $V", SubscriptionEntry.Valid(V, inText = true))
        prefilled("sub_$V", SubscriptionEntry.Valid(V, inText = true))
        prefilled("txn_$V", SubscriptionEntry.Valid(V, inText = true))
        prefilled("txn_ $V", SubscriptionEntry.Valid(V, inText = true))
        // A receipt line for another subscription than the stored one: that subscription id decides, as before.
        prefilled("$T $FAKE", SubscriptionEntry.Valid(FAKE, inText = true))
        // Run straight together, as without the stored id: two ids.
        prefilled("$T$V", SubscriptionEntry.SeveralIds)
    }

    @Test
    fun `a Google Play order number is named as one`() {
        kind("Gpa.1234-5678-9012-34567", SubscriptionEntry.PlayOrder)
        kind("gpa.1234-5678-9012-34567..0", SubscriptionEntry.PlayOrder)
        kind("  gpa.1234-5678-9012-34567  ", SubscriptionEntry.PlayOrder)
    }

    @Test
    fun `an email address is named as one`() {
        kind("name@example.com", SubscriptionEntry.Email)
        kind("Thanks for your order! A receipt was sent to name@example.com. Total: EUR 1.99", SubscriptionEntry.Email)
    }

    @Test
    fun `the prefix alone, or typed in front of an email address, is answered as what it is`() {
        // Both seen in the proxy's logs: a customer typed "sub_", then "sub_" and their email address.
        kind("sub_", SubscriptionEntry.MistypedSubId)
        kind("sub-", SubscriptionEntry.MistypedSubId)
        kind("sub_name@example.com", SubscriptionEntry.Email)
        kind("sub_ name@example.com", SubscriptionEntry.Email)
        // A real attempt at an id next to an address (a receipt line) is still a mistyped id.
        kind("Subscription ${V.dropLast(1)}, receipt sent to name@example.com", SubscriptionEntry.MistypedSubId)
    }

    @Test(timeout = 10_000)
    fun `a very long paste is answered at once`() {
        // The dialog parses on the main thread. The email pattern once took seconds on a long run without a space
        // (tried at every position), long enough for Android to report the app as not responding; and 12,500 typed
        // "sub_" with an id after them stalled the leftover checks. So text over MAX_ENTRY_CHARS is not searched, and
        // everything up to it is answered quickly (the next test has the prefix runs).
        val limit = "a".repeat(MAX_ENTRY_CHARS)
        kind(limit, SubscriptionEntry.NotAnId)
        kind(limit + "a", SubscriptionEntry.TooLong)
        kind("a".repeat(MAX_ENTRY_CHARS - V.length - 1) + " " + V, SubscriptionEntry.Valid(V, inText = true))
        kind("a".repeat(200_000) + " " + V, SubscriptionEntry.TooLong)
        kind("sub_".repeat(12_500) + "x " + V, SubscriptionEntry.TooLong)
        prefilled("sub_".repeat(12_500) + "x " + V, SubscriptionEntry.TooLong)
        // The worst of it just under the limit, parsed 30 times: a run without a space before an "@", which the email
        // pattern once tried at every position (about a second each on a PC), then runs past the timeout.
        repeat(30) {
            kind("a".repeat(MAX_ENTRY_CHARS - 1) + "@", SubscriptionEntry.NotAnId)
            kind("a".repeat(MAX_ENTRY_CHARS - 2) + "@b", SubscriptionEntry.NotAnId)
            kind("a".repeat(MAX_ENTRY_CHARS - 17) + " name@example.com", SubscriptionEntry.Email)
        }
        kind("txn_".repeat(MAX_ENTRY_CHARS / 4), SubscriptionEntry.MistypedTransactionId)
        prefilled("s".repeat(MAX_ENTRY_CHARS - V.length - 1) + " " + V, SubscriptionEntry.Valid(V, inText = true))
    }

    @Test
    fun `a full field is answered as too long, however short its text cleans to`() {
        // The dialog's field holds MAX_ENTRY_CHARS + 2 characters and cuts a longer paste there, or one less where the
        // cut would split a surrogate pair. Cleaned, the cut text can be back under the limit; searched, it lost the id
        // past the cut without a word.
        val pasted = "   " + "a".repeat(MAX_ENTRY_CHARS - 3) + "  " + V
        for (field in listOf(pasted.take(MAX_ENTRY_CHARS + 2), pasted.take(MAX_ENTRY_CHARS + 1))) {
            assertEquals(SubscriptionEntry.NotAnId, parseSubscriptionEntry(field))
            assertEquals(SubscriptionEntry.TooLong, parseSubscriptionField(field))
            assertEquals(SubscriptionEntry.TooLong, parseSubscriptionField(field, prefilled = V))
        }
        // A field that is not full is read as before.
        val fits = " " + "a".repeat(MAX_ENTRY_CHARS - V.length - 2) + " " + V
        assertEquals(MAX_ENTRY_CHARS, fits.length)
        assertEquals(SubscriptionEntry.Valid(V, inText = true), parseSubscriptionField(fits))
        assertEquals(SubscriptionEntry.Valid(V, inText = true), parseSubscriptionField(fits, prefilled = V))
        assertEquals(SubscriptionEntry.Empty, parseSubscriptionField(""))
    }

    @Test(timeout = 60_000)
    fun `a run of typed prefixes is read once, not again from every prefix in it`() {
        // Runs of prefixes that do not lead to the id, just under the limit (with a stored id, the longest ran the
        // matcher out of stack before 2.0.3). Read again from every prefix in them, each took 20 to 180 times as long
        // as a plain text of the same length; read once, about as long. Measured against that text on the same
        // machine, as the best of several runs, so a slow or busy one does not fail it (a fixed timeout did).
        val prefixes = "sub_".repeat((MAX_ENTRY_CHARS - V.length - 2) / 4) + "x " + V
        val spaced = "s u b _ ".repeat((MAX_ENTRY_CHARS - V.length - 2) / 8) + "x " + V
        val txnPrefixes = "txn_".repeat((MAX_ENTRY_CHARS - T.length - 2) / 4) + "x " + T
        kind(prefixes, SubscriptionEntry.Valid(V, inText = true))
        prefilled(prefixes, SubscriptionEntry.Valid(V, inText = true))
        prefilled(spaced, SubscriptionEntry.Valid(V, inText = true))
        transaction(txnPrefixes, inText = true)
        fun best(text: String, stored: String?): Long {
            repeat(5) { parseSubscriptionEntry(text, stored) }
            return (1..15).minOf { measureNanoTime { parseSubscriptionEntry(text, stored) } }
        }
        val plain = best("a".repeat(MAX_ENTRY_CHARS - V.length - 1) + " " + V, V)
        for ((text, stored) in listOf(prefixes to null, prefixes to V, spaced to V, txnPrefixes to null)) {
            val ratio = best(text, stored).toDouble() / plain
            assertTrue("${text.take(12)}... (stored id: ${stored != null}) took ${"%.1f".format(ratio)} times the plain text", ratio < 10)
        }
    }

    @Test
    fun `the payment-method link in Paddle's email and the portal page's address give the id, whatever the token spells`() {
        // Made up: the cpl_ id and the tokens. The real token is 1,300 base64url characters; these spell, case-folded,
        // what once read as a broken second id ("sub-3", "sub_5") and refused about 1 such link in 4,000.
        val portal = "https://customer-portal.paddle.com/cpl_07" + "a".repeat(24)
        for (token in listOf("pga_ab12", "pga_x9SUB-3yz", "pga_q_sub_5w", "pga_k-TXN_7m", "pga_aSub-0b_txn-1c")) {
            val link = "$portal?action=update_subscription_payment_method&subscription_id=$V&token=$token&source=email"
            // The sign-in email's link, once the first one's token has expired.
            val signIn = "$portal?action=update_subscription_payment_method&origin-intended=true&source=email&subscription_id=$V&token=$token"
            // The same link as Outlook's Safe Links rewrites it: "%26token%3D" there.
            val safelink = "https://eur01.safelinks.protection.outlook.com/?url=" +
                java.net.URLEncoder.encode(link, "UTF-8") + "&data=05%7C02%7C%7C0&sdata=AbCd%3D&reserved=0"
            for (pasted in listOf(link, signIn, safelink)) {
                valid(pasted, inText = true)
                // Pasted over the dialog's pre-filled id, or beside it: a token spelling a broken transaction id
                // once read as one there too.
                prefilled(pasted, SubscriptionEntry.Valid(V, inText = true))
                prefilled("$V $pasted", SubscriptionEntry.Valid(V, inText = true))
                // A transaction id beside it: the link's subscription id decides, as in a receipt line, unless that
                // is the stored id (then the transaction id is what was pasted).
                valid("$T $pasted", inText = true)
                prefilled("$T $pasted", SubscriptionEntry.ValidTransaction(T, inText = true))
            }
        }
        // Beside a link that carries no subscription id, a transaction id is looked up, whatever the token spells.
        transaction("$T $portal?token=pga_k-TXN_7m", inText = true)
        prefilled("$V $T $portal?subscription_id=$V&token=pga_k-TXN_7m", SubscriptionEntry.ValidTransaction(T, inText = true))
        // The address of the subscription's page in the portal.
        valid("https://customer-portal.paddle.com/subscriptions/$V/cpl_07" + "a".repeat(24) + "?origin-intended=true&source=email", inText = true)
        // Its own link pasted beside the dialog's pre-filled id: the stored id is meant.
        prefilled("$V $portal?subscription_id=$V&token=pga_x9SUB-3yz", SubscriptionEntry.Valid(V, inText = true))
        // A broken second id outside the token is still refused, also after a wrapped link.
        kind("$portal?subscription_id=$V&token=pga_ab12 sub_07aaa", SubscriptionEntry.MistypedSubId)
        kind(
            "https://eur01.safelinks.protection.outlook.com/?url=https%3A%2F%2Fcustomer-portal.paddle.com%2Fcpl_07" +
                "a".repeat(24) + "%3Fsubscription_id%3D$V%26token%3Dpga_ab12%26source%3Demail&data=05 sub_07aaa",
            SubscriptionEntry.MistypedSubId,
        )
        prefilled("$V $portal?subscription_id=$V&token=pga_ab12 txn_07aaa", SubscriptionEntry.MistypedTransactionId)
    }

    @Test
    fun `a transaction id wins over an email address in the same text`() {
        transaction("Order $T, receipt sent to name@example.com", inText = true)
        kind("Order ${T.dropLast(3)}, receipt sent to name@example.com", SubscriptionEntry.MistypedTransactionId)
    }

    // ---------------------------------------------------------------- email addresses beside or around an id

    @Test
    fun `an email address is never read as an id, as part of one or as an id begun`() {
        // The local part in front of an address's "@" is left out of every search for an id. The dialog keeps its
        // text when an activation fails, so an address pasted after a cut-short id must not make it whole, and an
        // address beside an id must not refuse it as a second id begun.
        val cutT = T.dropLast(4)
        // A cut-short id is not made whole with the start of an address after it, with or without whitespace
        // between, whatever the address is made of. All but the two at example.org made up an id out of the
        // address, such as "txn_07h04vsc0qhwtsbsxh3422name", and sent it to the proxy.
        kind("https://apps.example/checkout.html?_ptxn=$cutT name@example.com", SubscriptionEntry.MistypedTransactionId)
        kind(cutT + "name@example.com", SubscriptionEntry.Email)
        kind(cutT + "\nname@example.com", SubscriptionEntry.MistypedTransactionId)
        kind("$cutT first.last@example.org", SubscriptionEntry.MistypedTransactionId)
        kind("$cutT ab-c+tag@example.org", SubscriptionEntry.MistypedTransactionId)
        kind(V.dropLast(4) + "\r\nname@example.com", SubscriptionEntry.MistypedSubId)
        kind(V.dropLast(2) + " ab'cd@example.com", SubscriptionEntry.MistypedSubId)
        // An id run straight into an address is part of the address, whatever joins the two (it was the id, except
        // after "_"); with whitespace or other punctuation between them, it is found.
        kind("$V@example.com", SubscriptionEntry.Email)
        kind("$V.support@example.com", SubscriptionEntry.Email)
        kind("$V+tag@example.com", SubscriptionEntry.Email)
        kind("$V-x@example.com", SubscriptionEntry.Email)
        kind("${V}_x@example.com", SubscriptionEntry.Email)
        kind("$V'x@example.com", SubscriptionEntry.Email)
        kind("Your ID is $V.support@example.com", SubscriptionEntry.Email)
        kind("$T+tag@example.com", SubscriptionEntry.Email)
        valid("$V name@example.com", inText = true)
        valid("$V,name@example.com", inText = true)
        valid("$V (name@example.com)", inText = true)
        // Whatever follows the "@", "%40" or "%2540", a domain or nothing, the run in front of it is a local part: an
        // id run into it goes with it (it was that id), and a transaction id beside it is the one found (it was the
        // subscription id).
        kind("$V@", SubscriptionEntry.MistypedSubId)
        kind("$V%40", SubscriptionEntry.MistypedSubId)
        transaction("$T $V@example.com", inText = true)
        // The ends of the ranges a local part is read with ("0" to "9", "a" to "z"). Any other character ends one: an
        // id right in front of it is found with the address after it taken out, and an address right after it is
        // still taken out.
        kind("$V.z9@example.com", SubscriptionEntry.Email)
        for (c in listOf("=", "&", "?", "/", ":", "<", "(", ",", ";")) valid(V + c + "name@example.com", inText = true)
        for (c in listOf("mailto:", "/", "<", "(", "\"", "=")) valid("$V ${c}me+sub_3@example.com", inText = true)
        // Only "@", "%40" and "%2540" end a local part, not an encoding that starts as one of them does.
        for (after in listOf("%4", "%41", "%25", "%2526", "%254", "%2541")) valid(V + after, inText = true)
        // A local part of one character is taken out too, and one of any length.
        kind(T2.dropLast(1) + " b@example.com", SubscriptionEntry.MistypedTransactionId)
        valid(V.substring(0, 15) + "\n" + V.substring(15) + " a@example.com", inText = true)
        kind("$V." + "a".repeat(40) + "@example.com", SubscriptionEntry.Email)
        // A link's token is taken out before the local parts: the other way round, an "@" in its value would take the
        // "3d" of its "%3d" along, and the token, "sub-3" and all, would be left in.
        valid(
            "$V https://eur01.safelinks.protection.outlook.com/?url=https%3A%2F%2Fapps.example%2Fp" +
                "%3Ftoken%3Dpga_x9@sub-3%26a&data=05",
            inText = true,
        )
        // A sentence with an address after it, with or without a space after the full stop.
        valid("Your ID is $V. Questions? support@example.com", inText = true)
        valid("Your ID is $V.Questions? support@example.com", inText = true)
        valid("Your ID is $V. Questions?support@example.com", inText = true)
        valid("Your ID is $V. support@example.com", inText = true)
        valid("Your ID is $V.\nsupport@example.com", inText = true)
        transaction("Your transaction ID is $T.Questions? support@example.com", inText = true)
        // An id at the end of a line, the address on the next one.
        valid("$V\nname@example.com", inText = true)
        valid("Subscription ID: $V\r\nEmail: name@example.com", inText = true)
        valid("> Subscription ID: $V\n> name@example.com", inText = true)
        transaction("Transaction ID: $T\nname@example.com", inText = true)
        transaction("Receipt | $T | EUR 2.49\nname@example.com", inText = true)
        // An id split by a line wrap or a space is found with an address after it, on the same line or the next.
        // All but the one with " - " between were a mistyped id.
        valid(V.substring(0, 16) + "\n" + V.substring(16) + " name@example.com", inText = true)
        valid(V.substring(0, 15) + "\n" + V.substring(15) + " name@example.com", inText = true)
        valid(V.substring(0, 15) + "\n" + V.substring(15) + "\nname@example.com", inText = true)
        valid("Subscription " + V.substring(0, 15) + " " + V.substring(15) + " - name@example.com", inText = true)
        transaction("Transaction " + T.substring(0, 13) + " " + T.substring(13) + " name@example.com", inText = true)
        // A wrapped link whose query carries the address, plain or encoded. The two whose address holds an id begun
        // were a mistyped id.
        transaction(
            "https://apps.example/checkout.html?_ptxn=" + T.substring(0, 18) + "\n" + T.substring(18) +
                "&email=name@example.com",
            inText = true,
        )
        transaction("https://apps.example/checkout.html?_ptxn=$T&email=na\nme@example.com", inText = true)
        transaction(
            "https://apps.example/checkout.html?_ptxn=" + T.substring(0, 18) + "\n" + T.substring(18) +
                "&email=jane.sub-2%40example.com",
            inText = true,
        )
        valid(
            "https://customer-portal.paddle.com/cpl_07" + "a".repeat(24) + "?subscription_id=" + V.substring(0, 18) +
                "\n" + V.substring(18) + "&email=me+sub_3%40example.com",
            inText = true,
        )
        transaction(
            "https://eur01.safelinks.protection.outlook.com/?url=https%3A%2F%2Fapps.example%2Fcheckout.html" +
                "%3F_ptxn%3D" + T.substring(0, 20) + "\n" + T.substring(20) + "%26email%3Dname%2540example.com&data=05",
            inText = true,
        )
        // An address that holds an id begun is no second id beside a whole one, also where a link writes its "@" as
        // "%40", or a link inside a link as "%2540". Each was a mistyped id, except "me+txn_0@" in front of a
        // subscription id.
        valid("$V me+sub_3@example.com", inText = true)
        valid("$V jane.sub-2@example.com", inText = true)
        valid("$V club-sub-1@example.com", inText = true)
        valid("me+txn_0@example.com\n$V", inText = true)
        transaction("$T sub-1@example.com", inText = true)
        transaction("$T txn-2@example.com", inText = true)
        valid(V.substring(0, 15) + "\n" + V.substring(15) + " me+sub_3@example.com", inText = true)
        transaction("https://apps.example/checkout.html?_ptxn=$T&email=me+sub_3@example.com", inText = true)
        transaction("https://apps.example/checkout.html?_ptxn=$T&email=jane.sub-2%40example.com", inText = true)
        valid(
            "https://eur01.safelinks.protection.outlook.com/?url=https%3A%2F%2Fcustomer-portal.paddle.com%2Fcpl_07" +
                "a".repeat(24) + "%3Fsubscription_id%3D$V%26a&data=05%7C02%7Cclub-sub-1%40example.com%7C0",
            inText = true,
        )
        transaction(
            "https://eur01.safelinks.protection.outlook.com/?url=https%3A%2F%2Fapps.example%2Fcheckout.html" +
                "%3F_ptxn%3D$T%26email%3Djane.sub-2%2540example.com&data=05",
            inText = true,
        )
        // Nor is a whole id in an address a second id (they were several ids).
        transaction("$T $T2.x@example.com", inText = true)
        valid("$V sub_07" + "a".repeat(24) + "@example.com", inText = true)
        // A broken second id apart from the address still refuses the id.
        kind("$V sub_07h0 name@example.com", SubscriptionEntry.MistypedSubId)
        kind("$V sub_07h0, name@example.com", SubscriptionEntry.MistypedSubId)
        kind("$V sub_07h0\nname@example.com", SubscriptionEntry.MistypedSubId)
        kind("$V sub_07h0 me+sub_3@example.com", SubscriptionEntry.MistypedSubId)
        kind("$T txn_07h0 name@example.com", SubscriptionEntry.MistypedTransactionId)
        kind("$T sub_07h0 name@example.com", SubscriptionEntry.MistypedSubId)
        // Run straight into the address, it is part of the address, as an id would be, and the id beside it is found
        // (it was a mistyped id).
        valid("$V sub_07h0name@example.com", inText = true)
        // Only the local part goes: an id in an address's domain, where a real address has none, is still found.
        valid("user@$V.example.com", inText = true)
        // A cut-short id with an ordinary word after it, then an address, is character for character an id split by
        // a space with an address after it: nothing tells the two apart, so both are read as one id. Both were a
        // mistyped id, the address run into the word in front of it.
        kind("$cutT data name@example.com", SubscriptionEntry.ValidTransaction(cutT + "data", inText = true))
        transaction("$cutT " + T.takeLast(4) + " name@example.com", inText = true)
    }

    @Test
    fun `beside the pre-filled stored id, an email address is never read as a transaction id or one begun`() {
        val cutT = T.dropLast(4)
        val looked = SubscriptionEntry.ValidTransaction(T, inText = true)
        // A cut-short transaction id beside the stored id is not made whole with the start of an address (both made
        // one up and sent it).
        prefilled("$V $cutT name@example.com", SubscriptionEntry.MistypedTransactionId)
        prefilled(
            "$V https://apps.example/checkout.html?_ptxn=$cutT name@example.com",
            SubscriptionEntry.MistypedTransactionId,
        )
        // Run straight into an address, it is part of the address, so the stored id is what is meant (it made up a
        // transaction id out of the address).
        prefilled("$V " + cutT + "name@example.com", SubscriptionEntry.Valid(V, inText = true))
        // A whole one split by a line wrap is still what was pasted, with an address after it (it was a mistyped
        // transaction id).
        prefilled("$V\nTransaction " + T.substring(0, 15) + "\n" + T.substring(15) + " name@example.com", looked)
        // An address that holds an id begun is no second id begun (each was a mistyped id).
        prefilled("$V $T sub-1@example.com", looked)
        prefilled("$V https://apps.example/checkout.html?_ptxn=$T&email=me+txn_2@example.com", looked)
        prefilled("$V me+txn-2@example.com", SubscriptionEntry.Valid(V, inText = true))
        // A broken transaction id apart from the address is still refused.
        prefilled("$V txn_07h0 name@example.com", SubscriptionEntry.MistypedTransactionId)
    }

    @Test(timeout = 60_000)
    fun `a local part is tried only where a run of address characters starts`() {
        // Runs of address characters before an "@" or its "%2540" form, cut short by a "=" or by a "%254" that never
        // becomes "%2540", many dots or "@" between spaces, one run of every character a local part is read with and
        // no "@", all just under the limit, and an id beside many addresses that each hold an id begun. A local part
        // is tried only where a run starts, so each run is read from its start only, not again from every character
        // in it. Measured against a plain text of the same length on the same machine, as the best of several runs.
        val beside = "$V me+sub_3@example.com ".repeat(MAX_ENTRY_CHARS / 52)
        val texts = listOf(
            "a.".repeat((MAX_ENTRY_CHARS - 1) / 2) + "@",
            "a@ ".repeat(MAX_ENTRY_CHARS / 3),
            "  a@".repeat(MAX_ENTRY_CHARS / 4),
            " .".repeat(MAX_ENTRY_CHARS / 2),
            ("a".repeat(99) + "=").repeat(MAX_ENTRY_CHARS / 100 - 1) + "@",
            ("a".repeat(96) + "%254").repeat(MAX_ENTRY_CHARS / 100),
            "a%2540".repeat(MAX_ENTRY_CHARS / 6),
            "09az._+'-".repeat(MAX_ENTRY_CHARS / 9),
        )
        for (text in texts) kind(text, SubscriptionEntry.NotAnId)
        valid(beside, inText = true)
        prefilled(beside, SubscriptionEntry.Valid(V, inText = true))
        fun best(text: String, stored: String?): Long {
            repeat(5) { parseSubscriptionEntry(text, stored) }
            return (1..15).minOf { measureNanoTime { parseSubscriptionEntry(text, stored) } }
        }
        val plain = best("a".repeat(MAX_ENTRY_CHARS - V.length - 1) + " " + V, V)
        val cases: List<Pair<String, String?>> = texts.map { it to null } + listOf(beside to null, beside to V)
        for ((text, stored) in cases) {
            val ratio = best(text, stored).toDouble() / plain
            assertTrue("${text.take(12)}... (stored id: ${stored != null}) took ${"%.1f".format(ratio)} times the plain text", ratio < 10)
        }
        // The plain text is such a run too, so it would be as slow as they are: both are also measured against short
        // words, which take as long however a local part is tried.
        val words = best("id is ".repeat((MAX_ENTRY_CHARS - V.length) / 6) + V, V)
        val runs = listOf("a".repeat(MAX_ENTRY_CHARS - V.length - 1) + " " + V, "09az._+'-".repeat(MAX_ENTRY_CHARS / 9))
        for (text in runs) {
            val ratio = best(text, V).toDouble() / words
            assertTrue("${text.take(12)}... took ${"%.1f".format(ratio)} times short words", ratio < 10)
        }
    }

    @Test
    fun `blank text is empty`() {
        kind("", SubscriptionEntry.Empty)
        kind("   ", SubscriptionEntry.Empty)
        kind("\u200B", SubscriptionEntry.Empty)
    }

    @Test
    fun `anything else is not an id`() {
        kind("hello", SubscriptionEntry.NotAnId)
        kind("12345", SubscriptionEntry.NotAnId)
        kind("pri_01h04vsc0qhwtsbsxh3422wjs4", SubscriptionEntry.NotAnId)
        kind("https://apps.olausson.de/sync_provider_for_proton/checkout.html", SubscriptionEntry.NotAnId)
    }

    // ---------------------------------------------------------------- privacy and locale

    @Test
    fun `no entry prints an id or an email address`() {
        `the id alone, however it was copied, is valid and not in text`()
        `an id with punctuation after it is found, not cut or refused`()
        `an id in a labelled line or a link is found`()
        `two different ids are several ids`()
        `an id that is cut short, too long or mistyped is a mistyped id, never a shorter valid one`()
        `a transaction id alone or in other text is a transaction to look up`()
        `a subscription id decides over a transaction id in the same text`()
        `two different transaction ids are several ids`()
        `a transaction id that is cut short, too long or mistyped is a mistyped transaction id`()
        `a broken subscription id beside a transaction id is a mistyped subscription id`()
        `a transaction id run straight against the pre-filled subscription id is refused, never the old id checked`()
        `a transaction id wins over an email address in the same text`()
        `an email address is named as one`()
        `the prefix alone, or typed in front of an email address, is answered as what it is`()
        `an email address is never read as an id, as part of one or as an id begun`()
        `beside the pre-filled stored id, an email address is never read as a transaction id or one begun`()
        `anything else is not an id`()
        val secret = Regex("sub_[0-9]|txn_[0-9]|@")
        for (entry in seen) {
            assertFalse(entry.toString(), secret.containsMatchIn(entry.toString()))
            assertFalse(entry.kind, secret.containsMatchIn(entry.kind))
        }
        assertEquals("Valid(inText=true)", SubscriptionEntry.Valid(V, inText = true).toString())
        assertEquals("valid_in_text", SubscriptionEntry.Valid(V, inText = true).kind)
        assertEquals("valid", SubscriptionEntry.Valid(V, inText = false).kind)
        assertEquals("ValidTransaction(inText=false)", SubscriptionEntry.ValidTransaction(T, inText = false).toString())
        assertEquals("valid_transaction", SubscriptionEntry.ValidTransaction(T, inText = false).kind)
        assertEquals("valid_transaction_in_text", SubscriptionEntry.ValidTransaction(T, inText = true).kind)
        assertEquals("mistyped_transaction_id", SubscriptionEntry.MistypedTransactionId.kind)
    }

    @Test
    fun `a Turkish phone reads an upper-case id the same`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            valid(V.uppercase(), inText = false)
            valid("SUBSCRIPTION ID: ${V.uppercase()}", inText = true)
            transaction(T.uppercase(), inText = false)
        } finally {
            Locale.setDefault(before)
        }
    }
}
