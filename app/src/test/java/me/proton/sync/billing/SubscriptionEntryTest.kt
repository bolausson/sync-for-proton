package me.proton.sync.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

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
        // (tried at every position), long enough for Android to report the app as not responding.
        val long = "a".repeat(200_000)
        kind(long, SubscriptionEntry.NotAnId)
        kind("$long@", SubscriptionEntry.NotAnId)
        kind("$long@b", SubscriptionEntry.NotAnId)
        kind("$long $V", SubscriptionEntry.Valid(V, inText = true))
        kind("$long name@example.com", SubscriptionEntry.Email)
        kind("sub_".repeat(50_000), SubscriptionEntry.MistypedSubId)
        kind("txn_".repeat(50_000), SubscriptionEntry.MistypedTransactionId)
        prefilled("s".repeat(100_000) + " " + V, SubscriptionEntry.Valid(V, inText = true))
    }

    @Test
    fun `a transaction id wins over an email address in the same text`() {
        transaction("Order $T, receipt sent to name@example.com", inText = true)
        kind("Order ${T.dropLast(3)}, receipt sent to name@example.com", SubscriptionEntry.MistypedTransactionId)
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
