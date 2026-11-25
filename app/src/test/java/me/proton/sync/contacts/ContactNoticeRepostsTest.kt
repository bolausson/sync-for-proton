package me.proton.sync.contacts

import me.proton.sync.SyncPreferences
import me.proton.sync.backup.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two older contacts notices across an account rename and removal (ContactNoticeReposts): the refused new contacts
 * (contacts_rejected_<account>) and the contacts too large for this phone (contacts_too_large_<account>). Their ids carry
 * the account name while their lists move with the preferences, and neither comes back by itself, so a rename left the
 * one showing under the old name for ever and a removal never withdrew them. Now a rename takes a notice showing down
 * and marks it, the next sync under the new name posts it again from its own list, and a removal withdraws both; a
 * notice the user dismissed stays dismissed (owner, 2026-11-23). Made-up accounts only; no contact anywhere.
 */
class ContactNoticeRepostsTest {

    private val post = ContactDeletionFailures.Notice.POST
    private val cancel = ContactDeletionFailures.Notice.CANCEL
    private val keep = ContactDeletionFailures.Notice.KEEP

    private val old = "notice-old@example.test"
    private val new = "notice-new@example.test"

    /** [text] without KDoc, block comment lines and line comments. */
    private fun code(text: String): String =
        text.lines()
            .filterNot { it.trim().let { t -> t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") } }
            .joinToString("\n") { it.substringBefore(" // ") }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun source(path: String) = File("src/$path").readText().replace("\r\n", "\n")

    /** The text of the function that starts with [signature], up to its closing brace at four spaces. */
    private fun body(src: String, signature: String): String {
        assertTrue("$signature not found", src.contains(signature))
        return src.substringAfter(signature).substringBefore("\n    }\n")
    }

    private fun count(text: String, what: String): Int = Regex(Regex.escape(what)).findAll(text).count()

    /** ProtonSyncApp.renameAccountInPrefs, on [prefs]: every key of [from] moved to [to] by SyncPreferences' own rule. */
    private fun rename(prefs: FakePrefs, from: String, to: String) {
        for ((key, value) in HashMap(prefs.values)) {
            val moved = SyncPreferences.renamedAccountKey(key, from, to) ?: continue
            prefs.values.remove(key)
            prefs.values[moved] = value
        }
    }

    // ---- The rules ----------------------------------------------------------------------------------------------------

    @Test
    fun `without a mark each notice keeps exactly the rule it had`() {
        // Refused new contacts: posted when one failed in this upload, withdrawn once none is stuck, else left.
        assertEquals(post, ContactNoticeReposts.refusedCreates(refusedThisRun = true, stuck = true, repost = false))
        assertEquals(keep, ContactNoticeReposts.refusedCreates(refusedThisRun = false, stuck = true, repost = false))
        assertEquals(cancel, ContactNoticeReposts.refusedCreates(refusedThisRun = false, stuck = false, repost = false))
        assertEquals(cancel, ContactNoticeReposts.refusedCreates(refusedThisRun = true, stuck = false, repost = false))
        // Too large: posted when this download held a new one, brought up to date when the list changed while shown.
        val never = { throw AssertionError("asked whether shown") }
        assertEquals(cancel, ContactNoticeReposts.tooLarge(held = false, newlyHeld = false, changed = true, repost = false, shown = never))
        assertEquals(post, ContactNoticeReposts.tooLarge(held = true, newlyHeld = true, changed = true, repost = false, shown = never))
        assertEquals(keep, ContactNoticeReposts.tooLarge(held = true, newlyHeld = false, changed = false, repost = false, shown = never))
        assertEquals(post, ContactNoticeReposts.tooLarge(held = true, newlyHeld = false, changed = true, repost = false) { true })
        assertEquals(keep, ContactNoticeReposts.tooLarge(held = true, newlyHeld = false, changed = true, repost = false) { false })
    }

    @Test
    fun `a mark posts the notice again from its own list, and never brings back one with nothing behind it`() {
        val never = { throw AssertionError("asked whether shown") }
        assertEquals(post, ContactNoticeReposts.refusedCreates(refusedThisRun = false, stuck = true, repost = true))
        assertEquals(cancel, ContactNoticeReposts.refusedCreates(refusedThisRun = false, stuck = false, repost = true))
        assertEquals(post, ContactNoticeReposts.tooLarge(held = true, newlyHeld = false, changed = false, repost = true, shown = never))
        assertEquals(cancel, ContactNoticeReposts.tooLarge(held = false, newlyHeld = false, changed = false, repost = true, shown = never))
    }

    @Test
    fun `marks are kept per notice and per account, and clearing one writes only when there is one`() {
        val prefs = FakePrefs()
        assertFalse(ContactNoticeReposts.isMarked(prefs, old, ContactNoticeReposts.Notice.REJECTED))
        ContactNoticeReposts.clear(prefs, old, ContactNoticeReposts.Notice.REJECTED)
        assertTrue(prefs.values.isEmpty())
        ContactNoticeReposts.mark(prefs, old, ContactNoticeReposts.Notice.REJECTED)
        assertTrue(ContactNoticeReposts.isMarked(prefs, old, ContactNoticeReposts.Notice.REJECTED))
        assertFalse(ContactNoticeReposts.isMarked(prefs, old, ContactNoticeReposts.Notice.TOO_LARGE))
        assertFalse(ContactNoticeReposts.isMarked(prefs, new, ContactNoticeReposts.Notice.REJECTED))
        ContactNoticeReposts.clear(prefs, old, ContactNoticeReposts.Notice.REJECTED)
        assertFalse(ContactNoticeReposts.isMarked(prefs, old, ContactNoticeReposts.Notice.REJECTED))
        assertTrue(prefs.values.isEmpty())
    }

    @Test
    fun `the marks belong to their account, move with a rename, go with a removal and never travel`() {
        for ((key, builder) in listOf(
            "contacts_rejected_repost_" to SyncPreferences::contactsRejectedRepostKey,
            "contacts_too_large_repost_" to SyncPreferences::contactsTooLargeRepostKey,
        )) {
            val stored = builder(old)
            assertEquals(key + old, stored)
            assertTrue(key, key in SyncPreferences.ACCOUNT_SCOPED_PREFIXES)
            assertEquals(listOf(old), SyncPreferences.accountNamesOfKey(stored))
            assertEquals(mapOf(stored to old), SyncPreferences.keysOfRemovedAccounts(listOf(stored), setOf("other@example.test")))
            assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(listOf(stored), setOf(old)))
            assertEquals(builder(new), SyncPreferences.renamedAccountKey(stored, old, new))
            // About this phone's notifications: never in a settings backup.
            assertFalse(BackupSettings.isExportable(stored))
        }
    }

    // ---- Across a rename and a removal --------------------------------------------------------------------------------

    @Test
    fun `a rename takes a notice showing down, and the next sync under the new name posts it again, once`() {
        val prefs = FakePrefs()
        // A contact refused three times, given up on and listed in the notice, which is on screen at the rename.
        ContactUploadRefusals.recordStrike(prefs, old, 7, "rejected")
        assertTrue(ContactUploadRefusals.markRefused(prefs, old, 7))
        ContactDownloadHolds.hold(prefs, old, ContactDownloadHolds.Hold("proton-id-1", 1_000L, false))
        // renameParentAccount, before the preferences move: taken down under the old name, marked.
        ContactNoticeReposts.mark(prefs, old, ContactNoticeReposts.Notice.REJECTED)
        ContactNoticeReposts.mark(prefs, old, ContactNoticeReposts.Notice.TOO_LARGE)
        rename(prefs, old, new)
        // The lists and the marks moved; nothing is left under the old name.
        assertTrue(prefs.values.keys.none { it.endsWith(old) })
        assertEquals(listOf(7L to "rejected"), ContactUploadRefusals.tracked(prefs, new))
        // The next upload: no contact failed in it, yet the notice is posted again from the stored list, then the mark goes.
        val rejected = ContactNoticeReposts.isMarked(prefs, new, ContactNoticeReposts.Notice.REJECTED)
        assertEquals(post, ContactNoticeReposts.refusedCreates(refusedThisRun = false, stuck = ContactUploadRefusals.tracked(prefs, new).isNotEmpty(), repost = rejected))
        ContactNoticeReposts.clear(prefs, new, ContactNoticeReposts.Notice.REJECTED)
        // The next download: no new hold, the list unchanged, yet posted again, then the mark goes.
        val tooLarge = ContactNoticeReposts.isMarked(prefs, new, ContactNoticeReposts.Notice.TOO_LARGE)
        assertEquals(post, ContactNoticeReposts.tooLarge(held = ContactDownloadHolds.all(prefs, new).isNotEmpty(), newlyHeld = false, changed = false, repost = tooLarge) { false })
        ContactNoticeReposts.clear(prefs, new, ContactNoticeReposts.Notice.TOO_LARGE)
        // From then on, the rules they always had: not posted again at every sync.
        assertEquals(keep, ContactNoticeReposts.refusedCreates(false, true, ContactNoticeReposts.isMarked(prefs, new, ContactNoticeReposts.Notice.REJECTED)))
        assertEquals(keep, ContactNoticeReposts.tooLarge(true, false, false, ContactNoticeReposts.isMarked(prefs, new, ContactNoticeReposts.Notice.TOO_LARGE)) { true })
    }

    @Test
    fun `a notice the user dismissed is not marked at a rename, so it stays dismissed`() {
        val prefs = FakePrefs()
        ContactUploadRefusals.recordStrike(prefs, old, 7, "rejected")
        // Not on screen at the rename: no mark (renameParentAccount asks whether it is shown first).
        rename(prefs, old, new)
        assertFalse(ContactNoticeReposts.isMarked(prefs, new, ContactNoticeReposts.Notice.REJECTED))
        assertEquals(keep, ContactNoticeReposts.refusedCreates(refusedThisRun = false, stuck = true, repost = false))
        // A later refusal posts it, as it always did.
        assertEquals(post, ContactNoticeReposts.refusedCreates(refusedThisRun = true, stuck = true, repost = false))
    }

    // ---- Where it is wired --------------------------------------------------------------------------------------------

    /** ProtonSyncApp without comments, squashed. */
    private val app: String by lazy { squash(code(source("main/java/me/proton/sync/ProtonSyncApp.kt"))) }

    @Test
    fun `a rename marks each notice showing before the preferences move, and a removal withdraws both`() {
        val rename = app.substringAfter("fun renameParentAccount(").substringBefore("renameAccountInPrefs(oldParent.name, desired)")
        for ((shown, cancelled, notice) in listOf(
            Triple("isContactsRejectedShown", "cancelContactsRejected", "REJECTED"),
            Triple("isContactsTooLargeShown", "cancelContactsTooLarge", "TOO_LARGE"),
        )) {
            val marked = "if (SyncNotificationHelper.$shown(this, oldParent.name)) { SyncNotificationHelper.$cancelled(this, oldParent.name) " +
                "me.proton.sync.contacts.ContactNoticeReposts.mark( prefsForHolds, oldParent.name, me.proton.sync.contacts.ContactNoticeReposts.Notice.$notice ) }"
            val at = rename.indexOf(marked)
            assertTrue("$notice: $rename", at >= 0)
            // Before the rename's own work, so the mark moves with the preferences to the new name.
            assertTrue(notice, at < rename.indexOf("return try {"))
        }
        val removal = app.substringAfter("private fun forgetRemovedAccounts(").substringBefore("private fun renameAccountInPrefs(")
            .substringAfter("for (name in stale) {")
        assertTrue(removal, removal.contains("SyncNotificationHelper.cancelContactsRejected(this, name)"))
        assertTrue(removal, removal.contains("SyncNotificationHelper.cancelContactsTooLarge(this, name)"))
        // Withdrawn at a rename and at a removal, and nowhere else in the app's start-up code.
        assertEquals(app, 2, count(app, "SyncNotificationHelper.cancelContactsRejected("))
        assertEquals(app, 2, count(app, "SyncNotificationHelper.cancelContactsTooLarge("))
        // Whether the refused-contacts notice is still up, asked as the too-large one is.
        val helper = squash(code(source("main/java/me/proton/sync/SyncNotificationHelper.kt")))
        assertTrue(helper, helper.contains(
            "fun isContactsRejectedShown(context: Context, accountName: String): Boolean = try { " +
                "val id = (\"contacts_rejected_\$accountName\").hashCode() " +
                "context.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.id == id } == true"
        ))
    }

    @Test
    fun `each notice is settled by its rule with the mark, which goes once it was settled`() {
        val adapter = source("main/java/me/proton/sync/contacts/ContactsSyncAdapter.kt")
        val refused = squash(code(body(adapter, "private fun notifyRefusedCreates(")))
        assertTrue(refused, refused.contains(
            "val repost = ContactNoticeReposts.isMarked(prefs, parentAccountName, ContactNoticeReposts.Notice.REJECTED) " +
                "val notice = ContactNoticeReposts.refusedCreates(refusedCreates.isNotEmpty(), stillStuck.isNotEmpty(), repost) " +
                "if (notice == ContactDeletionFailures.Notice.POST) { SyncNotificationHelper.notifyContactsRejected("
        ))
        assertTrue(refused, refused.contains(
            "} else if (notice == ContactDeletionFailures.Notice.CANCEL) { SyncNotificationHelper.cancelContactsRejected(context, parentAccountName) }"
        ))
        assertTrue(refused, refused.trimEnd().endsWith(
            "if (repost) ContactNoticeReposts.clear(prefs, parentAccountName, ContactNoticeReposts.Notice.REJECTED)"
        ))
        val large = squash(code(body(adapter, "private fun reportTooLargeContacts(")))
        assertTrue(large, large.contains(
            "val repost = ContactNoticeReposts.isMarked(prefs, parentAccountName, ContactNoticeReposts.Notice.TOO_LARGE) " +
                "val notice = ContactNoticeReposts.tooLarge(holds.isNotEmpty(), newlyHeld.isNotEmpty(), holds.keys != heldAtStart, repost) { " +
                "SyncNotificationHelper.isContactsTooLargeShown(context, parentAccountName) }"
        ))
        assertTrue(large, large.contains(
            "if (notice == ContactDeletionFailures.Notice.CANCEL) { SyncNotificationHelper.cancelContactsTooLarge(context, parentAccountName) } " +
                "else if (notice == ContactDeletionFailures.Notice.POST) { SyncNotificationHelper.notifyContactsTooLarge("
        ))
        assertTrue(large, large.trimEnd().endsWith(
            "if (repost) ContactNoticeReposts.clear(prefs, parentAccountName, ContactNoticeReposts.Notice.TOO_LARGE)"
        ))
        // Holds of contacts gone from Proton still go first, as before.
        assertTrue(large, large.indexOf("ContactDownloadHolds.retain(prefs, parentAccountName) { it in metaById }") < large.indexOf("val repost"))
        for (fn in listOf("notifyRefusedCreates(", "reportTooLargeContacts(")) assertFalse(fn, adapter.contains("suspend fun $fn"))
    }
}
