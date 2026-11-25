package me.proton.sync.calendar

/**
 * A calendar whose keys were read while none of the account's address keys is loaded (CalendarSyncAdapter
 * .prepareCalendarJob, and the backup's CalendarBackupBuilder). Pure, so it is tested without a device.
 *
 * Every member passphrase of a calendar is encrypted to an address key. A sync goes on when the account's addresses
 * could not be read at its start (SyncUtils.doEnsureKeysUnlocked: a dropped connection or a Proton error on that one
 * request, typically right after Android restarted the app) as long as the user key unlocks, and then no key of any
 * calendar unlocks. The event parts were dropped or kept unverified, so every event that run downloaded was written
 * without its title, location and description, or as "[Encrypted Event]", the run reported success, and later syncs
 * kept those copies as unchanged. Such a calendar is now skipped for the run like one whose keys could not be read:
 * nothing of it is downloaded or overwritten, and the run fails with the reason (owner, 2026-11-23). The next sync reads
 * the addresses again. A calendar without keys has nothing to unlock and syncs as before, and nothing changes while any
 * address key is loaded.
 */
internal object AddressKeysMissing {

    /** Whether the calendar is skipped for this run: it has keys ([calendarHasKeys]) and no address key is loaded. */
    fun skipsCalendar(calendarHasKeys: Boolean, addressKeysLoaded: Boolean): Boolean = calendarHasKeys && !addressKeysLoaded

    /**
     * The reason such a calendar gives its run, with Proton's HTTP status [httpCode] where Proton answered the read of the
     * addresses with a failure (SyncUtils.addressReadFailure); the network cause of a read that threw is added by the
     * recording itself (NetworkCause.describe). Both pick the text the failure shows (SyncNotificationHelper
     * .friendlyMessageRes). The calendar is named by the start of its id, never by its name: a name can hold the very
     * words the text is read by, and the text also goes to the log.
     */
    fun reason(calendarId: String, httpCode: Int?): String =
        "No address key of the account could be loaded, so calendar '${calendarId.take(8)}' could not be opened and none of its events were downloaded" +
            (if (httpCode != null) ": HTTP $httpCode" else "")
}
