package me.proton.sync.contacts

import android.content.SharedPreferences
import me.proton.sync.SyncPreferences

/**
 * The two older contacts notices across an account rename: the refused new contacts (contacts_rejected_<account>,
 * ContactsSyncAdapter.notifyRefusedCreates) and the contacts too large for this phone (contacts_too_large_<account>,
 * ContactsSyncAdapter.reportTooLargeContacts). Pure apart from one stored flag each, so it is tested without a device.
 *
 * Each notice's id is built from the account name, while what it lists moves with the preferences at a rename. Neither
 * comes back by itself: the refused-contacts list is posted only by an upload in which a contact failed, and a contact
 * the app gave up on is not sent again until it is edited; the too-large list only when a download holds one more
 * contact, or while it is still on screen when the list changes. So a notice showing at a rename stayed under the old
 * name for ever, with nothing behind it, and a removed account's notices were never withdrawn (owner, 2026-11-23).
 *
 * Now a rename takes each notice showing under the old name down and marks it ([mark]); the mark moves with the
 * preferences, and the next upload (refused contacts) or download (too large) under the new name posts it again from
 * its own stored list ([refusedCreates], [tooLarge]), then clears the mark. A notice the user had dismissed is not
 * marked, so it stays dismissed. An account's removal withdraws both (ProtonSyncApp.forgetRemovedAccounts), and its
 * marks go with its other keys.
 */
internal object ContactNoticeReposts {

    /** The two notices. */
    enum class Notice { REJECTED, TOO_LARGE }

    private fun key(accountName: String, notice: Notice): String = when (notice) {
        Notice.REJECTED -> SyncPreferences.contactsRejectedRepostKey(accountName)
        Notice.TOO_LARGE -> SyncPreferences.contactsTooLargeRepostKey(accountName)
    }

    /** A rename took [notice] down under [accountName] while it was on screen: the next sync posts it again. */
    fun mark(prefs: SharedPreferences, accountName: String, notice: Notice) {
        prefs.edit().putBoolean(key(accountName, notice), true).apply()
    }

    /** Whether [notice] is to be posted again under [accountName] ([mark]). */
    fun isMarked(prefs: SharedPreferences, accountName: String, notice: Notice): Boolean =
        prefs.getBoolean(key(accountName, notice), false)

    /** [notice] was settled under [accountName]: posted again, withdrawn, or nothing left to post. */
    fun clear(prefs: SharedPreferences, accountName: String, notice: Notice) {
        val key = key(accountName, notice)
        if (prefs.contains(key)) prefs.edit().remove(key).apply()
    }

    /**
     * What an upload does with the refused-contacts notice: withdrawn once no contact is stuck ([stuck] false); posted,
     * listing every contact still stuck, when one failed in this upload ([refusedThisRun]) or a rename took it down
     * ([repost]); otherwise left as it is. With no [repost], exactly the rule it always had.
     */
    fun refusedCreates(refusedThisRun: Boolean, stuck: Boolean, repost: Boolean): ContactDeletionFailures.Notice = when {
        !stuck -> ContactDeletionFailures.Notice.CANCEL
        refusedThisRun || repost -> ContactDeletionFailures.Notice.POST
        else -> ContactDeletionFailures.Notice.KEEP
    }

    /**
     * What a download does with the too-large notice: withdrawn once no contact is held ([held] false); posted, listing
     * every contact still held, when this download held a new one ([newlyHeld]) or a rename took it down ([repost]), and
     * brought up to date when the list changed ([changed]) while it is still on screen ([shown], asked only then);
     * otherwise left as it is. With no [repost], exactly the rule it always had.
     */
    fun tooLarge(
        held: Boolean,
        newlyHeld: Boolean,
        changed: Boolean,
        repost: Boolean,
        shown: () -> Boolean
    ): ContactDeletionFailures.Notice = when {
        !held -> ContactDeletionFailures.Notice.CANCEL
        newlyHeld || repost -> ContactDeletionFailures.Notice.POST
        changed && shown() -> ContactDeletionFailures.Notice.POST
        else -> ContactDeletionFailures.Notice.KEEP
    }
}
