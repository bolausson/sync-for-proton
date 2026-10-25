package me.proton.sync

import me.proton.sync.contacts.ContactNameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which preferences go with an account that is gone (SyncPreferences.keysOfRemovedAccounts, run by
 * ProtonSyncApp.forgetRemovedAccounts at every start and on every account change). contact_name_mode_ also
 * begins the name mode's re-apply flag, contact_name_mode_reapply_<account>. Read by that prefix alone, the
 * flag looked like the name mode of an account called "reapply_<account>", which does not exist, so it was
 * deleted at the next start: a name mode chosen shortly before the app was stopped then never reached the
 * contacts already on the phone.
 */
class RemovedAccountKeysTest {

    private val account = "me@example.com"

    @Test
    fun aLiveAccountKeepsItsNameModeAndItsReapplyFlag() {
        val keys = listOf(ContactNameMode.key(account), ContactNameMode.reapplyKey(account))

        assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(keys, setOf(account)))
    }

    @Test
    fun theReapplyFlagIsReadAsItsAccountsFirst() {
        assertEquals(
            listOf(account, "reapply_$account"),
            SyncPreferences.accountNamesOfKey(ContactNameMode.reapplyKey(account))
        )
        assertEquals(listOf(account), SyncPreferences.accountNamesOfKey(ContactNameMode.key(account)))
    }

    @Test
    fun aRemovedAccountsKeysGo_namedByThatAccount() {
        val keys = listOf(
            ContactNameMode.key(account),
            ContactNameMode.reapplyKey(account),
            SyncPreferences.displayNameUserChosenKey(account),
            SyncPreferences.eventLoopIntervalKey(account),
            SyncPreferences.syncPausedUntilKey(account),
        )

        val removed = SyncPreferences.keysOfRemovedAccounts(keys, setOf("other@example.com"))

        assertEquals(keys.toSet(), removed.keys)
        assertEquals(setOf(account), removed.values.toSet())
    }

    @Test
    fun theNameModeOfAnAccountCalledReapply_x_staysWhileThatAccountExists() {
        // Its name mode has the very key of the re-apply flag of an account called <x>.
        val oddName = "reapply_$account"
        val key = ContactNameMode.key(oddName)
        assertEquals(ContactNameMode.reapplyKey(account), key)

        assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(listOf(key), setOf(oddName)))
        assertEquals(mapOf(key to account), SyncPreferences.keysOfRemovedAccounts(listOf(key), emptySet()))
    }

    @Test
    fun keysThatAreNotAnAccountsOwnAreNeverRemoved() {
        // Two-argument builders (their suffix is not a bare account name), device-wide keys, a bare prefix.
        val keys = listOf(
            SyncPreferences.notifySyncFailureKey("contacts", account),
            SyncPreferences.syncIntervalKey(account, "calendar"),
            SyncPreferences.lastSyncRangeKey(account, "cal1"),
            SyncPreferences.KEY_TIMEZONE_UPDATES,
            SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED,
            "contact_name_mode_",
            "something_else_$account",
        )
        for (key in keys) assertTrue(key, SyncPreferences.accountNamesOfKey(key).isEmpty())
        assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(keys, emptySet()))
    }

    @Test
    fun aRemovedAccountsLastSyncTimesGo_aLiveAccountKeepsThem() {
        // A kept time told the subscription catch-up that a re-added account of the same name had synced, so
        // with Android's automatic sync off its first sync was never asked for.
        val keys = listOf("contacts", "calendar").flatMap {
            listOf(SyncPreferences.lastSyncKey(account, it), SyncPreferences.lastSyncFailedKey(account, it))
        }
        assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(keys, setOf(account)))
        assertEquals(keys.associateWith { account }, SyncPreferences.keysOfRemovedAccounts(keys, setOf("other@example.com")))
        // The failed time is read as its account's first, like the name mode's re-apply flag.
        assertEquals(
            listOf(account, "failed_$account"),
            SyncPreferences.accountNamesOfKey(SyncPreferences.lastSyncFailedKey(account, "calendar")),
        )
        // An account literally called failed_<x> keeps its last sync time while it exists.
        val oddName = "failed_$account"
        val oddKey = SyncPreferences.lastSyncKey(oddName, "contacts")
        assertEquals(emptyMap<String, String>(), SyncPreferences.keysOfRemovedAccounts(listOf(oddKey), setOf(oddName)))
    }

    @Test
    fun everyListedPrefixGivesBackTheAccountName() {
        for (prefix in SyncPreferences.ACCOUNT_SCOPED_PREFIXES) {
            assertEquals(prefix, account, SyncPreferences.accountNamesOfKey(prefix + account).first())
        }
    }

    @Test
    fun onlyTheNameModeAndLastSyncPrefixesBeginAnotherListedPrefix() {
        // Any other such pair is a key with two readings, like the re-apply flag: give it a test like these.
        val nested = SyncPreferences.ACCOUNT_SCOPED_PREFIXES.flatMap { p ->
            SyncPreferences.ACCOUNT_SCOPED_PREFIXES.filter { it != p && it.startsWith(p) }.map { p to it }
        }
        assertEquals(
            listOf(
                "contact_name_mode_" to "contact_name_mode_reapply_",
                "last_contacts_sync_" to "last_contacts_sync_failed_",
                "last_calendar_sync_" to "last_calendar_sync_failed_",
            ),
            nested,
        )
        assertEquals(SyncPreferences.lastSyncFailedKey("", "contacts"), "last_contacts_sync_failed_")
        assertEquals(SyncPreferences.lastSyncKey("", "calendar"), "last_calendar_sync_")
        assertEquals(ContactNameMode.reapplyKey(""), "contact_name_mode_reapply_")
        assertEquals(ContactNameMode.key(""), "contact_name_mode_")
    }
}
