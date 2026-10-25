package me.proton.sync.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A settings backup's subscription id field is read as the subscription dialog reads its text. Until 2.0.2
 * restoreFromBackup stored the field's trimmed text, whatever it was, and sent it to the licence proxy as an
 * activation: an email address or a transaction id in a hand-edited backup left the phone. Now only
 * [SubscriptionEntry.Valid]'s id is taken. The manager needs an Android context, so its source is checked,
 * as in [ActiveStatusLineTest].
 *
 * Made-up vectors only: [V] has the format of a Paddle subscription id but belongs to no one, and starts with 07,
 * not the 01 of every real Paddle id (see SubscriptionEntryTest).
 */
class BackupSubscriptionIdTest {

    private val V = "sub_07h04vsc0qhwtsbsxh3422wjs4"

    @Test
    fun `a backup field that is one id gives that id, as the proxy expects it`() {
        assertEquals(SubscriptionEntry.Valid(V, inText = false), parseSubscriptionEntry(V))
        assertEquals("upper case and a line end", SubscriptionEntry.Valid(V, inText = false), parseSubscriptionEntry(" ${V.uppercase()}\n"))
        assertEquals("a hand-pasted link", SubscriptionEntry.Valid(V, inText = true), parseSubscriptionEntry("https://example.com/?subscription_id=$V"))
    }

    @Test
    fun `a backup field that is not one id is named, and nothing in it is taken`() {
        val notIds = listOf(
            "someone@example.com" to "email",
            // A whole transaction id is one the dialog looks up, but a backup's field is never looked up: it holds
            // the subscription id the backup was made with, and anything else in it is ignored.
            "txn_07h04vsc0qhwtsbsxh3422wjs4" to "valid_transaction",
            "txn_07h04vsc" to "mistyped_transaction_id",
            "sub_01h04vsc" to "mistyped_sub_id",
            "$V sub_01${"a".repeat(24)}" to "several_ids",
            "Gpa.1234-5678-9012-34567" to "play_order",
            "hello" to "not_an_id",
            "   " to "empty",
        )
        for ((text, kind) in notIds) {
            val entry = parseSubscriptionEntry(text)
            assertFalse(text, entry is SubscriptionEntry.Valid)
            assertEquals(text, kind, entry.kind)
        }
    }

    @Test
    fun `restoreFromBackup parses the field before it stores or sends anything, and logs no text`() {
        val restore = File("src/direct/java/me/proton/sync/billing/SubscriptionManager.kt").readText().replace("\r\n", "\n")
            .substringAfter("fun restoreFromBackup(subId: String): Boolean {").substringBefore("\n    }\n")
        val parse = restore.indexOf("parseSubscriptionEntry(subId)")
        val store = restore.indexOf("applyLicense(")
        assertTrue(restore, parse >= 0 && store > parse)
        assertTrue(restore, restore.contains("if (entry !is SubscriptionEntry.Valid) {"))
        assertTrue("only the parsed id is stored and sent", restore.contains("applyLicense(entry.subId)"))
        assertTrue(restore, restore.contains("not an id (\${entry.kind}, \${subId.length} characters); ignored"))
        // The field's text itself is never in a log line: its length is.
        assertFalse(restore, Regex("""\$\{?subId(?!\.length)""").containsMatchIn(restore))
    }
}
