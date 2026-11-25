package me.proton.sync

import me.proton.sync.calendar.AddressKeysMissing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The account whose addresses were read but whose stored mailbox password opens none of their keys (AddressKeysLocked,
 * SyncUtils.doEnsureKeysUnlocked and runSyncPreflight). Since 2.2.0 every sync skipped its calendars and said
 * "unexpected error" (calendar.AddressKeysMissing); the user is now led to the sign-in-again flow (KEYS_LOCKED), and only
 * in that state: a successful read in this run, keys Proton marks active that this app can read, and none of them
 * unlocked (owner, 2026-11-23). A read that failed, an account with no active key or none this app can read, and
 * an account with any address key loaded keep what they had. Made-up accounts and calendar ids only.
 */
class AddressKeysLockedTest {

    private companion object {
        const val CALENDAR = "calLockTest0123456789abcdefghijklmnopqrstuvwxyz=="
        val NO_HOST = UnknownHostException("Unable to resolve host \"api.example.test\": No address associated with hostname")
    }

    private fun source(path: String) = File("src/$path").readText().replace("\r\n", "\n")

    /** [text] without KDoc, block comment lines and line comments. */
    private fun code(text: String): String =
        text.lines()
            .filterNot { it.trim().let { t -> t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") } }
            .joinToString("\n") { it.substringBefore(" // ") }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The text of the function that starts with [signature], up to its closing brace at four spaces. */
    private fun body(src: String, signature: String): String {
        assertTrue("$signature not found", src.contains(signature))
        return src.substringAfter(signature).substringBefore("\n    }\n")
    }

    private fun count(text: String, what: String): Int = Regex(Regex.escape(what)).findAll(text).count()

    // ---- The rule ---------------------------------------------------------------------------------------------------

    @Test
    fun `only active keys this app can read, none of them unlocked, ask for a new sign-in`() {
        val state = AddressKeysLocked::state
        // Any address key loaded: the account is fine, whatever became of its other keys.
        assertEquals(AddressKeysLocked.State.LOADED, state(3, 3, true))
        assertEquals(AddressKeysLocked.State.LOADED, state(3, 0, true))
        assertEquals(AddressKeysLocked.State.LOADED, state(0, 0, true))
        // No active key at all: signing in again would read the same answer.
        assertEquals(AddressKeysLocked.State.NO_ACTIVE_KEY, state(0, 0, false))
        // Active keys this app cannot read: a new sign-in reads the same keys.
        assertEquals(AddressKeysLocked.State.UNREADABLE, state(2, 0, false))
        // Active keys it can read, and the stored mailbox password opens none of them.
        assertEquals(AddressKeysLocked.State.SIGN_IN_AGAIN, state(2, 2, false))
        assertEquals(AddressKeysLocked.State.SIGN_IN_AGAIN, state(2, 1, false))
        assertEquals(AddressKeysLocked.State.SIGN_IN_AGAIN, state(1, 1, false))
    }

    @Test
    fun `the flow is asked for only by a read that found the account locked out, while still no key is loaded`() {
        for (s in AddressKeysLocked.State.entries) {
            val read = AddressKeysLocked.Read(s, 2)
            assertEquals("$s", s == AddressKeysLocked.State.SIGN_IN_AGAIN, AddressKeysLocked.signsInAgain(read, anyAddressKeyLoaded = false))
            // Never while a key is loaded, as after the user signed in again.
            assertFalse("$s", AddressKeysLocked.signsInAgain(read, anyAddressKeyLoaded = true))
        }
        // No successful read in this run (it failed, or none was made): never.
        assertFalse(AddressKeysLocked.signsInAgain(null, anyAddressKeyLoaded = false))
    }

    // ---- Over a sequence of syncs (SyncUtils' record, as doEnsureKeysUnlocked and runSyncPreflight use it) ------------

    /** What runSyncPreflight decides after a read of the addresses: the sign-in-again message, or null to go on. */
    private fun preflight(account: String, loaded: Boolean): String? = SyncUtils.addressKeysLockedOut(account, loaded)

    @Test
    fun `the sign-in-again flow fires for the locked-out account only, never for a passing failure or a healthy account`() {
        val a = "keys-locked-a@example.test"
        // A healthy account: its keys loaded.
        SyncUtils.recordAddressRead(a, null)
        SyncUtils.recordAddressKeysRead(a, AddressKeysLocked.Read(AddressKeysLocked.State.LOADED, 2))
        assertNull(preflight(a, loaded = true))
        // The stored mailbox password no longer opens them (a password changed elsewhere): the flow, once per run.
        SyncUtils.recordAddressRead(a, null)
        SyncUtils.recordAddressKeysRead(a, AddressKeysLocked.Read(AddressKeysLocked.State.SIGN_IN_AGAIN, 2))
        val message = preflight(a, loaded = false)
        assertNotNull(message)
        assertEquals(AddressKeysLocked.message(a, 2), message)
        assertTrue(message!!.contains("none of their 2 active key(s)") && message.contains("sign in again"))
        // The next run's read fails, on the network or with Proton's status: a passing state, never the flow.
        SyncUtils.recordAddressRead(a, SyncUtils.AddressReadFailure(null, NO_HOST))
        assertNull(SyncUtils.addressKeysRead(a))
        assertNull(preflight(a, loaded = false))
        SyncUtils.recordAddressRead(a, SyncUtils.AddressReadFailure(null, SocketTimeoutException("timeout")))
        assertNull(preflight(a, loaded = false))
        for (status in listOf(401, 403, 404, 429, 500, 503)) {
            SyncUtils.recordAddressRead(a, null)
            SyncUtils.recordAddressKeysRead(a, AddressKeysLocked.Read(AddressKeysLocked.State.SIGN_IN_AGAIN, 2))
            SyncUtils.recordAddressRead(a, SyncUtils.AddressReadFailure(status, null))
            assertNull("HTTP $status", preflight(a, loaded = false))
        }
        // Read again with the same result: the flow again.
        SyncUtils.recordAddressRead(a, null)
        SyncUtils.recordAddressKeysRead(a, AddressKeysLocked.Read(AddressKeysLocked.State.SIGN_IN_AGAIN, 1))
        assertEquals(AddressKeysLocked.message(a, 1), preflight(a, loaded = false))
        // The user signed in again: the keys are loaded, and a record from before that changes nothing.
        assertNull(preflight(a, loaded = true))
        // No active key, or none this app can read: the calendars stay skipped, with a reason of their own; no flow.
        SyncUtils.recordAddressKeysRead(a, AddressKeysLocked.Read(AddressKeysLocked.State.NO_ACTIVE_KEY, 0))
        assertNull(preflight(a, loaded = false))
        SyncUtils.recordAddressKeysRead(a, AddressKeysLocked.Read(AddressKeysLocked.State.UNREADABLE, 3))
        assertNull(preflight(a, loaded = false))
        // Kept per account.
        val b = "keys-locked-b@example.test"
        SyncUtils.recordAddressKeysRead(a, AddressKeysLocked.Read(AddressKeysLocked.State.SIGN_IN_AGAIN, 2))
        assertNull(SyncUtils.addressKeysRead(b))
        assertNull(preflight(b, loaded = false))
        SyncUtils.recordAddressRead(a, SyncUtils.AddressReadFailure(503, null))
        SyncUtils.recordAddressRead(a, null)
    }

    // ---- The skip's reason when the read succeeded ------------------------------------------------------------------

    @Test
    fun `a calendar skipped after a read that succeeded says why no key loaded, by the start of its id`() {
        val words = AddressKeysMissing.reason(CALENDAR, null)
        assertEquals(
            "$words: the account has no active address key",
            AddressKeysLocked.reason(CALENDAR, AddressKeysLocked.Read(AddressKeysLocked.State.NO_ACTIVE_KEY, 0))
        )
        assertEquals(
            "$words: this app cannot read any of the account's 2 active address key(s)",
            AddressKeysLocked.reason(CALENDAR, AddressKeysLocked.Read(AddressKeysLocked.State.UNREADABLE, 2))
        )
        assertEquals(
            "$words: the stored mailbox password opens none of the account's 2 active address key(s)",
            AddressKeysLocked.reason(CALENDAR, AddressKeysLocked.Read(AddressKeysLocked.State.SIGN_IN_AGAIN, 2))
        )
        assertTrue(words.contains("calendar 'calLockT'"))
        // A failed read (its status or cause is recorded instead) or a key loaded: nothing of this.
        assertNull(AddressKeysLocked.reason(CALENDAR, null))
        assertNull(AddressKeysLocked.reason(CALENDAR, AddressKeysLocked.Read(AddressKeysLocked.State.LOADED, 2)))
        // No status and no network cause in them: the text shown stays the plain failure, never a session or a network text.
        for (s in listOf(AddressKeysLocked.State.NO_ACTIVE_KEY, AddressKeysLocked.State.UNREADABLE, AddressKeysLocked.State.SIGN_IN_AGAIN)) {
            val reason = AddressKeysLocked.reason(CALENDAR, AddressKeysLocked.Read(s, 2))!!
            for (retries in listOf(true, false)) {
                assertEquals("$s", R.string.error_unexpected, SyncNotificationHelper.friendlyMessageRes(reason, retries))
            }
        }
    }

    // ---- Where it is wired ------------------------------------------------------------------------------------------

    @Test
    fun `a read that succeeded records what it left the account with, after the unlock, and a failed one forgets it`() {
        val utils = source("main/java/me/proton/sync/SyncUtils.kt")
        val unlock = squash(code(body(utils, "private fun doEnsureKeysUnlocked(")))
        val success = unlock.substringAfter("if (addressesResponse.isSuccessful) {").substringBefore("} else {")
        assertTrue(success, success.contains(
            "keyManager.unlockAddressKeysWithMailboxPassword(account.name, mbPassword.toCharArray(), allAddressKeys) " +
                "addressKeysRefreshed = true } recordAddressKeys(account.name, allAddressKeys, keyManager, tag)"
        ))
        // Only the successful branch records it.
        assertEquals(unlock, 1, count(unlock, "recordAddressKeys("))
        // What it records: the active keys, the readable ones asked only when none loaded, the rule's state.
        val record = squash(code(body(utils, "private fun recordAddressKeys(")))
        assertTrue(record, record.contains("val loaded = keyManager.hasAddressKeys(accountName) val active = addressKeys.filter { it.active == 1 }"))
        assertTrue(record, record.contains(
            "val readable = if (loaded || active.isEmpty()) active.size else active.count { keyManager.canParseKey(it.privateKey) }"
        ))
        assertTrue(record, record.contains("val state = AddressKeysLocked.state(active.size, readable, loaded)"))
        assertTrue(record, record.contains("recordAddressKeysRead(accountName, AddressKeysLocked.Read(state, active.size))"))
        // A failed read forgets what an earlier one found.
        val read = squash(code(body(utils, "internal fun recordAddressRead(")))
        assertTrue(read, read.contains("addressReadFailures[accountName] = failure addressKeyReads.remove(accountName)"))
        assertFalse(utils.contains("suspend fun recordAddressKeys("))
    }

    @Test
    fun `the preflight leads to the existing sign-in-again flow, keys locked, right after the unlock`() {
        val flight = squash(code(body(source("main/java/me/proton/sync/SyncUtils.kt"), "fun runSyncPreflight(")))
        val unlock = "try { ensureKeysUnlocked(parentAccount, session, keyManager, apiClient, tag) " +
            "addressKeysLockedOut(parentAccount.name, keyManager.hasAddressKeys(parentAccount.name))?.let { " +
            "throw KeysNotUnlockedException(parentAccount.name, it) } } catch (e: KeysNotUnlockedException) {"
        val at = flight.indexOf(unlock)
        assertTrue(flight, at >= 0)
        assertEquals(flight, 1, count(flight, "addressKeysLockedOut("))
        // The branch a wrong user key passphrase already takes, unchanged: what the flow stops and how it asks.
        val branch = flight.substring(at + unlock.length).substringBefore("return PreflightResult.Proceed(session)")
        assertTrue(branch, branch.contains(
            "sessionManager.clearKeyMaterial(parentAccount.name) pauseSyncAfterAuthFailure(context, parentAccount.name) " +
                "ProtonSyncApp.notifyReLoginRequired(context, parentAccount.name, ProtonSyncApp.AuthFailReason.KEYS_LOCKED) " +
                "syncResult.stats.numAuthExceptions++ return PreflightResult.Abort }"
        ))
        // Its existing texts, no new one: "Could not unlock data", "Tap to sign in again".
        val app = squash(code(source("main/java/me/proton/sync/ProtonSyncApp.kt")))
        assertTrue(app, app.contains(
            "AuthFailReason.KEYS_LOCKED -> R.string.notification_decrypt_failed_title to R.string.notification_decrypt_failed_message"
        ))
        val english = source("main/res/values/strings.xml")
        assertTrue(english.contains("<string name=\"notification_decrypt_failed_message\">Could not decrypt %s. Tap to sign in again"))
    }

    /**
     * Signing in again loads the keys the stale password could not open. KeyManager marked a key as in
     * its ring before adding it, and the mark stayed when the add failed: an address key that would not open with the
     * stale mailbox password was skipped for the rest of the process, so the sign-in with the new password never loaded
     * it, and the next sync asked again and cleared the password just entered, until the process died. A key is now
     * marked only once it is in, so the next unlock tries a failed one again.
     */
    @Test
    fun `a key that did not go into a ring is tried again by the next unlock, so signing in again loads it`() {
        val keys = source("main/java/me/proton/sync/crypto/KeyManager.kt")
        // The account ring: looked up before the add, never claimed; marked right after each of the two ways in.
        val add = squash(code(body(keys, "private fun addKeyToGoRing(")))
        assertTrue(add, add.contains("val mark = \"\$accountName:\${armoredKey.hashCode()}\" if (mark in goRingKeysAdded) {"))
        assertTrue(add, add.contains(
            "val ring = bridge.Bridge.newKeyRing(armoredKey, passphrase) goKeyRings[accountName] = ring goRingKeysAdded.add(mark)"
        ))
        assertTrue(add, add.contains("existing.addKey(armoredKey, passphrase) goRingKeysAdded.add(mark)"))
        // The per-address signing ring: claimed first, so two passes at once build it once, and dropped when not built.
        val unlock = squash(code(body(keys, "fun unlockAddressKeysWithMailboxPassword(")))
        assertTrue(unlock, unlock.contains(
            "if (goAddressSigningRingsAdded.add(ringKey)) { val signingRing = try { " +
                "bridge.Bridge.newKeyRing(addressKey.privateKey, passphrase) } catch (e: Throwable) { " +
                "goAddressSigningRingsAdded.remove(ringKey) throw e }"
        ))
        assertEquals(unlock, 1, count(unlock, "bridge.Bridge.newKeyRing("))
        // Nothing else marks a key in either set.
        val all = squash(code(keys))
        assertEquals(2, count(all, "goRingKeysAdded.add("))
        assertEquals(1, count(all, "goAddressSigningRingsAdded.add("))
    }

    @Test
    fun `a calendar skipped for want of an address key records why first when the read succeeded`() {
        val adapter = source("main/java/me/proton/sync/calendar/CalendarSyncAdapter.kt")
        val skip = squash(code(body(adapter, "private fun skippedForMissingAddressKeys(")))
        assertTrue(skip, skip.contains("val read = SyncUtils.addressKeysRead(account.name)"))
        val first = skip.indexOf(
            "me.proton.sync.AddressKeysLocked.reason(calInfo.id, read)?.let { SyncUtils.recordSwallowedFailure(\"calendar\", account.name, it) }"
        )
        val then = skip.indexOf("SyncUtils.recordSwallowedFailure(\"calendar\", account.name, AddressKeysMissing.reason(calInfo.id, failure?.httpCode), failure?.cause)")
        assertTrue(skip, first >= 0 && then > first)
    }
}
