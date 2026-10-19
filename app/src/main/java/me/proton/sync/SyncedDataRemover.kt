package me.proton.sync

import android.accounts.Account
import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import me.proton.sync.db.SyncDatabase

/**
 * Takes the calendars or contacts this app put on the phone back off it again.
 *
 * Switching sync off never used to remove anything, which read as a bug from the outside: a user
 * who turned calendar sync off still had every event sitting in their calendar app, with nothing in
 * this app offering to clear them. A user could be left with events they had never wanted on the
 * device and no way to be rid of them short of removing the account.
 *
 * Three rules govern everything here.
 *
 * **Delete as a sync adapter.** A plain delete only tombstones a row (`DELETED=1, DIRTY=1`). For
 * contacts that is actively dangerous: a tombstone with a surviving mapping is exactly what
 * `uploadDirtyContacts` turns into a *remote* delete, so clearing the phone would empty the account
 * on Proton. A hard delete cannot be pushed anywhere.
 *
 * **Clear the bookkeeping in the same breath.** The Room tables map Proton ids to local rows. Left
 * behind, they claim every contact and event is still on the phone, so switching sync back on would
 * download nothing and leave the user staring at an empty calendar. Every one of these functions
 * removes the rows and the mappings together, so re-enabling is a clean first sync.
 *
 * **Leave the label groups alone.** Deleting the groups a removal leaves empty looks tidy, but an
 * empty group is a perfectly ordinary thing to have on Android, and anyone who later adds a contact
 * to one would have to recreate it first. An earlier implementation was worse than the idea: it removed
 * groups that were ALREADY empty before the removal, so an unrelated action could delete a group
 * the user had made themselves. The groups mirror Proton's labels and the next sync recreates
 * them, so leaving one costs nothing, while deleting one that only ever existed locally cannot be
 * undone.
 */
object SyncedDataRemover {

    private const val TAG = "SyncedDataRemover"

    /**
     * Removes this account's calendars and their events from the phone.
     *
     * Unlike the forced remote-to-local resync, which keeps the calendar rows on purpose so that a
     * sync starting moments later cannot race their recreation, this removes the calendars
     * themselves. Nothing is going to re-fetch them: it runs when the user has just switched
     * calendar sync off, and the empty calendar names would otherwise linger in every calendar app
     * on the device, which is most of what the user was asking to be rid of.
     */
    suspend fun removeCalendars(context: Context, account: Account, db: SyncDatabase): Int {
        // ONLY the events that came from Proton, for the same reason the contacts half spares
        // unmapped rows: an event created on the phone in a Proton calendar while offline, or one
        // whose upload failed, exists nowhere else. It has no mapping and no copy on the server, so
        // a hard delete here is final, and the dialog offering this promises the opposite by saying
        // the Proton copy is safe. This half of the file was left behind when the contacts half was
        // fixed, which is exactly the kind of asymmetry worth looking for.
        val fromProton = db.calendarEventSyncDao().androidEventIdsByAccount(account.name).toSet()
        var events = 0
        var calendars = 0
        var sparedTotal = 0
        try {
            val calIds = mutableListOf<Long>()
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(account.name, account.type),
                null,
            )?.use { c -> while (c.moveToNext()) calIds.add(c.getLong(0)) }

            for (calId in calIds) {
                val inThisCalendar = mutableListOf<Long>()
                var spared = 0
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Events._ID,
                        CalendarContract.Events._SYNC_ID,
                        CalendarContract.Events.ORIGINAL_SYNC_ID,
                    ),
                    "${CalendarContract.Events.CALENDAR_ID} = ?",
                    arrayOf(calId.toString()),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        // A mapping is not the only proof a row came from Proton. The adapter
                        // writes one STATUS_CANCELED child per EXDATE and those "never get a Room
                        // mapping" (CalendarSyncAdapter's own words), so counting every unmapped
                        // row as phone-only meant ANY calendar holding a recurring event with a
                        // deleted occurrence had spared > 0 and was never removed. The user
                        // switched calendar sync off, accepted "remove these calendars", and was
                        // left with exactly the empty calendar entries the whole feature exists to
                        // clear. The _sync_id columns settle it: a row carrying either one came
                        // from the server whatever the mapping table says.
                        val fromServer = !c.getString(1).isNullOrEmpty() || !c.getString(2).isNullOrEmpty()
                        when {
                            id in fromProton -> inThisCalendar.add(id)
                            fromServer -> inThisCalendar.add(id)
                            else -> spared++
                        }
                    }
                }
                for (chunk in inThisCalendar.chunked(400)) {
                    val placeholders = chunk.joinToString(",") { "?" }
                    events += context.contentResolver.delete(
                        SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                        "${CalendarContract.Events._ID} IN ($placeholders)",
                        chunk.map { it.toString() }.toTypedArray(),
                    )
                }
                sparedTotal += spared
                // Removing the calendar would take its remaining events with it, so a calendar that
                // still holds something the phone is the only copy of has to stay. The user asked
                // for Proton's data to go, not for their own unsent work to go with it.
                if (spared == 0) {
                    calendars += context.contentResolver.delete(
                        SyncUtils.asCallerSyncAdapter(CalendarContract.Calendars.CONTENT_URI, account),
                        "${CalendarContract.Calendars._ID} = ?",
                        arrayOf(calId.toString()),
                    )
                }
            }
            if (sparedTotal > 0) {
                SyncLog.i(TAG, "Kept $sparedTotal event(s) that exist only on this phone for '${account.name}'")
            }
        } catch (e: Exception) {
            SyncLog.e(TAG, "Could not remove calendars for '${account.name}'", e)
        }
        // After the rows, never before: if the delete throws halfway, mappings that still point at
        // surviving events are worth more than a tidy table.
        db.calendarEventSyncDao().deleteAllByAccount(account.name)
        db.calendarSyncDao().deleteAllByAccount(account.name)
        clearRangePrefs(context, account.name)
        // The rows those holds asked about are gone, so the question is void.
        me.proton.sync.RemoteDeletionGuard.forgetHolds(
            context, me.proton.sync.RemoteDeletionGuard.TYPE_CALENDAR, account.name
        )
        SyncLog.i(TAG, "Removed $calendars calendar(s) and $events event(s) for '${account.name}'")
        return events
    }

    /**
     * Removes this account's contacts from the phone.
     *
     * The address-book account itself is deliberately left in place. It is what the framework hangs
     * the sync adapter off, and removing it here would take the user's own sync toggle with it;
     * they asked to stop syncing contacts, not to unpick the account.
     */
    suspend fun removeContacts(
        context: Context,
        addressBookAccount: Account,
        parentAccountName: String,
        db: SyncDatabase,
    ): Int {
        // ONLY the rows that came from Proton. Deleting everything in the account also destroyed
        // contacts that live nowhere else: one the user created on the phone and an upload rule
        // held back, or one created while offline that has not been sent yet. Those were never
        // downloaded from anywhere, so nothing can restore them, and the dialog offering this
        // promises the opposite by saying the copy in Proton is safe. A contact with no mapping
        // has no copy in Proton to be safe.
        val fromProton = db.contactSyncDao().getAllByAccount(parentAccountName)
            .mapNotNull { it.androidRawContactId }
        var purged = 0
        try {
            for (chunk in fromProton.chunked(400)) {
                val placeholders = chunk.joinToString(",") { "?" }
                purged += context.contentResolver.delete(
                    SyncUtils.asCallerSyncAdapter(ContactsContract.RawContacts.CONTENT_URI, addressBookAccount),
                    "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                        "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                        "${ContactsContract.RawContacts._ID} IN ($placeholders)",
                    (listOf(addressBookAccount.name, addressBookAccount.type) +
                        chunk.map { it.toString() }).toTypedArray(),
                )
            }
        } catch (e: Exception) {
            SyncLog.e(TAG, "Could not remove contacts for '$parentAccountName'", e)
        }
        val kept = countLocalOnly(context, addressBookAccount, fromProton.toSet())
        if (kept > 0) {
            SyncLog.i(TAG, "Kept $kept contact(s) that exist only on this phone for '$parentAccountName'")
        }
        db.contactSyncDao().deleteAllByAccount(parentAccountName)
        // The removal-prompt bookkeeping goes, because it is all about mapped contacts that have
        // just been deleted. The kept-local set does NOT: it is the only thing standing between a
        // phone-only contact and the orphan sweep, which deletes any row with no mapping and no
        // DIRTY flag. Emptying it here undid the sparing above one sync later, so the app logged
        // "Kept 1 contact" and then destroyed it. Only ids that were actually removed drop out.
        val removedIds = fromProton.toSet()
        try {
            me.proton.sync.contacts.filter.ContactFilterStore.retainKeptLocal(
                context,
                parentAccountName,
                me.proton.sync.contacts.filter.ContactFilterStore
                    .keptLocalIds(context, parentAccountName) - removedIds,
            )
            // Both contact types: the rows are gone, so nothing is left to ask about. The KEPT set stays,
            // because it records the user's own "keep this on the phone"; emptying it here threw that
            // decision away, so the removal prompt returned the next time the rules ran.
            me.proton.sync.RemoteDeletionGuard.forgetHolds(
                context, me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS, parentAccountName
            )
            me.proton.sync.RemoteDeletionGuard.forgetHolds(
                context, me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS_FILTER, parentAccountName
            )
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not clear contact filter bookkeeping: ${e.message}")
        }
        SyncLog.i(TAG, "Removed $purged contact row(s) for '$parentAccountName'")
        return purged
    }



    /** Rows in the account that this app never downloaded, so they exist nowhere else. */
    private fun countLocalOnly(
        context: Context,
        addressBookAccount: Account,
        fromProton: Set<Long>,
    ): Int {
        var n = 0
        try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                    "${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(addressBookAccount.name, addressBookAccount.type),
                null,
            )?.use { c -> while (c.moveToNext()) if (c.getLong(0) !in fromProton) n++ }
        } catch (_: Exception) {
        }
        return n
    }

    /** The stored fetch windows, so a later re-enable starts from a clean full download. */
    private fun clearRangePrefs(context: Context, accountName: String) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val prefix = SyncPreferences.lastSyncRangeKey(accountName, "")
        val stale = prefs.all.keys.filter { it.startsWith(prefix) }
        if (stale.isEmpty()) return
        prefs.edit().apply {
            stale.forEach { remove(it) }
            apply()
        }
    }
}
