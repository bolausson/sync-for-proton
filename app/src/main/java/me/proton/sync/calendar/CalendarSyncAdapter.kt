package me.proton.sync.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.*
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import me.proton.sync.SyncConstants
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.SyncTriggerType
import me.proton.sync.SyncUtils
import androidx.preference.PreferenceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.proton.sync.ProtonSyncApp
import me.proton.sync.RemoteDeletionGuard
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.api.AuthInterceptor
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.protonRecipientKey
import me.proton.sync.api.unexpectedKeyLookupFailure
import me.proton.sync.api.models.CalendarEventDetail
import me.proton.sync.api.models.CalendarEventSyncData
import me.proton.sync.api.models.CalendarEventSyncOperation
import me.proton.sync.api.models.CalendarEventSyncRequest
import me.proton.sync.api.models.CalendarInfo
import me.proton.sync.api.models.CalendarKey
import me.proton.sync.api.models.CalendarPassphrase
import me.proton.sync.api.models.CalendarSettingsData
import me.proton.sync.api.models.EventAttendee
import me.proton.sync.api.models.EventNotification
import me.proton.sync.api.models.UpdateEventPersonalPartRequest
import me.proton.sync.api.models.EventPart
import me.proton.sync.api.models.ProtonAttendeeKeyPacket
import me.proton.sync.api.models.SyncAttendee
import me.proton.sync.crypto.CalendarDecryptor
import me.proton.sync.crypto.KeyManager
import me.proton.sync.crypto.isPrimaryCalendarKey
import me.proton.sync.db.daos.CalendarEventSyncDao
import me.proton.sync.db.daos.CalendarSyncDao
import me.proton.sync.db.daos.MappedRow
import me.proton.sync.db.entities.CalendarEventSyncEntity
import me.proton.sync.db.entities.CalendarSyncEntity
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.component.VEvent
import java.io.StringReader

/**
 * Two-way calendar sync adapter for Proton Calendar.
 *
 * ## Sync Flow (Remote → Local)
 * 1. **modelevents/latest check** — skip calendar if unchanged since last sync
 * 2. **MetaDataOnly fetch** — lightweight metadata to detect changed events
 * 3. **Individual/full fetch** — download changed events (or all on fresh sync)
 * 4. **Decrypt & map** — CalendarDecryptor → CalendarMapper → ContentProvider
 * 5. **EXDATE exceptions** — create cancelled exception events for Samsung compatibility
 * 6. **reconcileDeletedEvents** — batch-check for locally deleted events → delete on server
 *
 * ## Sync Flow (Local → Remote)
 * 7. **uploadDirtyEvents** — detect DIRTY/DELETED flags in CalendarContract
 * 8. **Build iCal** — construct SharedEventContent (type-2 + type-3), CalendarEventContent,
 *    AttendeesEventContent with proper encryption and signing
 * 9. **EXDATE handling** — collect cancelled exceptions, merge, upload as parent update
 *
 * ## Key Design Decisions
 * - Per-account [ReentrantLock] prevents concurrent syncs
 * - Uninterruptible sync via [CountDownLatch] (framework thread interrupts don't cancel API calls)
 * - [DIRTY]=0 required for CalendarProvider instance generation (Samsung/Google Calendar)
 * - EXDATE written both as CalendarContract column AND as exception events (Samsung workaround)
 *
 * @see CalendarMapper for VEvent → Android ContentProvider mapping
 * @see CalendarDecryptor for multi-strategy event decryption
 * @see ICalUtils for date/time formatting utilities
 */
class CalendarSyncAdapter(
    context: Context,
    autoInitialize: Boolean,
    private val apiClient: ProtonApiClient,
    private val sessionManager: AccountSessionManager,
    private val calendarDecryptor: CalendarDecryptor,
    private val calendarSyncDao: CalendarSyncDao,
    private val calendarEventSyncDao: CalendarEventSyncDao,
    private val keyManager: KeyManager
) : AbstractThreadedSyncAdapter(context, autoInitialize, false) {

    companion object {
        private const val TAG = "CalendarSync"
        private const val PAGE_SIZE = 50
        private const val METADATA_PAGE_SIZE = 100

    }

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        // Off means off, wherever the request came from, and BEFORE the ongoing notification or
        // any result is recorded. requestSync ignores setSyncAutomatically and a dozen places ask
        // for a sync, so the adapter is the only place this can be settled once. It sits here
        // rather than inside the sync itself because returning from there left err == null, and
        // savePostSyncResult then wrote a lastSync timestamp and cleared the failure notice for a
        // sync that never ran: the status banner would claim everything was up to date, above two
        // counts of zero.
        if (!ContentResolver.getSyncAutomatically(account, SyncConstants.AUTHORITY_CALENDAR)) {
            SyncLog.i(TAG, "Calendar sync is switched off for ${account.name}; not syncing")
            return
        }

        SyncNotificationHelper.showOngoing(context, account.name, "calendar")
        try {
            SyncUtils.withSyncLock("calendar", account.name, TAG) {
                doPerformSync(account, extras, authority, provider, syncResult)
            }
        } finally {
            SyncNotificationHelper.clearOngoing(context, account.name, "calendar")
        }
    }

    private fun doPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        val triggerType = SyncTriggerType.fromExtras(extras)
        SyncLog.i(TAG, "Starting calendar sync for ${account.name} (${triggerType.label})")

        val isManual = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)
        when (val pf = SyncUtils.runSyncPreflight(
            context = context,
            parentAccount = account,
            sessionManager = sessionManager,
            keyManager = keyManager,
            apiClient = apiClient,
            isManual = isManual,
            syncResult = syncResult,
            syncTypeLabel = "Calendar",
            tag = TAG
        )) {
            is SyncUtils.PreflightResult.Abort -> return
            is SyncUtils.PreflightResult.Proceed -> { /* continue below */ }
        }

        // Run sync uninterruptibly — framework thread interrupts must not cancel API calls
        val err = SyncUtils.runUninterruptible {
            AuthInterceptor.withAccount(account.name) {
                performCalendarSync(account, provider, syncResult, triggerType)
            }
        }

        SyncUtils.savePostSyncResult(
            context = context,
            parentAccountName = account.name,
            syncType = "calendar",
            syncTypeLabel = "Calendar",
            err = err,
            syncResult = syncResult,
            tag = TAG
        )
    }

    /** One calendar's prepared metadata, handed to the parallel pass below. */
    private data class CalendarJob(
        val calInfo: CalendarInfo,
        val androidCalId: Long,
        val calKeys: List<CalendarKey>,
        val calPassphrases: List<CalendarPassphrase>,
        val isExternal: Boolean,
        val displayName: String
    )

    /** Guards the read-modify-write of the one-time heal marker, written from parallel calendars. */
    private val healMarkerLock = Any()

    /**
     * This sync run's zone rules (TimeZoneRuleSource.snapshot), taken once at the start of
     * [performCalendarSync] and used by the download, the upload and the re-time pass alike, so a switch
     * flipped mid-run cannot mix rule sources inside one run. The adapter runs one sync at a time
     * (allowParallelSyncs = false); any snapshot is a valid one anyway, and a flip re-times at the next run.
     */
    @Volatile
    private var zoneRules: RuleSnapshot = RuleSnapshot.PHONE_ONLY

    private suspend fun performCalendarSync(
        account: Account,
        provider: ContentProviderClient,
        syncResult: SyncResult,
        triggerType: SyncTriggerType
    ) {
        // Read sync preferences
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val includeExternal = prefs.getBoolean(SyncPreferences.includeExternalCalendarsKey(account.name), false)
        zoneRules = TimeZoneRuleSource.snapshot(context)

        applyBulkDeletionRestore(
            account = account,
            provider = provider
        )

        // Held server→local deletions whose local rows are gone: drop them before anything else.
        // Before the network, so a failed calendar list fetch below cannot leave a prompt up that asks
        // about events no longer on this phone, and before the per-calendar passes, because the held
        // count vetoes their unchanged-skip fast path, so the run that heals gets the fast path back.
        sweepStaleDeletionHolds(
            account = account,
            provider = provider
        )

        // And the collision prompts, for the same reason and with the same evidence.
        sweepStaleConflictHolds(
            account = account,
            provider = provider
        )

        // And the retry counters left behind by events that no longer exist.
        sweepStaleRetryCounters(
            account = account,
            provider = provider
        )

        // Stored event times computed with other rules than this run's (the upgrade from ical4j's times, a
        // flip of "Update time zones online", a tz data update): tag them for the download to re-apply from
        // Proton, a dirty row only once its upload has gone. The pass itself writes nothing to the phone's
        // calendar, only tags and cursor clears in the app's own database (EventRetimePass; EventRetimePlanner
        // lists the rows it leaves alone). Before the calendar list, so the cursor clears it makes are what
        // existingCalMappings reads below, and before the download and the upload.
        runRetimePass(account, provider)

        val calendars = fetchCalendarList(
            account = account,
            syncResult = syncResult
        ) ?: return

        // Complete set of calendar ids the server currently has. Used to detect a calendar that was
        // deleted + recreated under the same name (its OLD protonId is absent here): we re-key its
        // events to the new id rather than silently wiping them. Computed up-front (not the
        // incrementally-built serverCalIds below) so the check is correct regardless of iteration order.
        val allServerCalIds = calendars.map { it.id }.toSet()

        // Log raw calendar data for debugging
        for (cal in calendars) {
            SyncLog.d(TAG, "Calendar: id=${cal.id?.take(20)}.., type=${cal.type}")
        }

        // Disambiguate display names when multiple accounts exist
        val allAccounts = AccountManager.get(context).getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
        val multiAccount = allAccounts.size > 1

        val existingCalMappings = calendarSyncDao.getAllByAccount(account.name)
            .associateBy { it.protonCalendarId }
        val serverCalIds = mutableSetOf<String>()

        val existingLocalCalendars = readAndDedupeLocalCalendars(
            account = account,
            provider = provider,
            existingCalMappings = existingCalMappings
        )

        // Prepare calendar metadata (bootstrap, keys) and sync events in parallel
        val jobs = mutableListOf<CalendarJob>()
        // Android calendars the server still backs, including ones whose job could not be built.
        val serverBackedAndroidCalIds = mutableSetOf<Long>()
        for (calInfo in calendars) {
            serverCalIds.add(calInfo.id)

            // The user turning external and holiday calendars off is a SETTING, not a failure, and it is
            // checked here rather than inside prepareCalendarJob so that a null from that function keeps the
            // single meaning the failure report below depends on. It did not: the skip returned null too, so
            // every sync of an account with a holiday or subscribed calendar and this setting off reported
            // itself FAILED, on every run, naming a calendar called 'null'. Found on the Note 10+ on
            // 2026-09-21, one version after the report was added and before either had been released.
            if (!includeExternal && calInfo.type != 0) {
                SyncLog.d(TAG, "Skipping external/holiday calendar ${calInfo.id} (type=${calInfo.type}) - 'Include external calendars' is OFF for ${account.name}; its events will NOT sync locally")
                continue
            }

            val job = prepareCalendarJob(
                account = account,
                provider = provider,
                calInfo = calInfo,
                multiAccount = multiAccount,
                allServerCalIds = allServerCalIds,
                existingCalMappings = existingCalMappings,
                existingLocalCalendars = existingLocalCalendars,
                serverBackedAndroidCalIds = serverBackedAndroidCalIds
            )
            if (job == null) {
                // Past the skip above, null means the calendar could not be PREPARED: its keys or passphrases
                // could not be read, or its local calendar row could not be created. Either way none of its
                // events can be downloaded this run. Skipping it quietly left savePostSyncResult on its success
                // branch: no retry, the failure notification cleared, and the user told everything had synced.
                synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ }
                SyncUtils.recordSwallowedFailure(
                    "calendar", account.name,
                    "Calendar '${calInfo.name ?: calInfo.id.take(8)}' could not be opened, so none of its " +
                        "events were downloaded"
                )
                continue
            }
            jobs.add(job)
        }

        // Sync all calendars in parallel
        if (jobs.isNotEmpty()) {
            val startTime = System.currentTimeMillis()
            coroutineScope {
                jobs.map { job ->
                    async(Dispatchers.IO) {
                        try {
                            syncCalendarEvents(account, provider, job.calInfo, job.androidCalId,
                                job.calKeys, job.calPassphrases, syncResult, job.isExternal, job.displayName,
                                triggerType)
                        } catch (e: Exception) {
                            SyncLog.e(TAG, "Failed to sync calendar '${job.displayName}': ${e.message}", e)
                            synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ }
                            SyncUtils.recordSwallowedFailure("calendar", account.name, e.message)
                        }
                    }
                }.awaitAll()
            }
            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            SyncLog.i(TAG, "All ${jobs.size} calendars synced in parallel (${elapsed}s)")
        }
        noteZoneRuleFallbacks(account)

        applyConfirmedRemoteDeletions(
            account = account,
            provider = provider,
            syncResult = syncResult
        )

        // Detect locally deleted events by reconciling sync mappings with CalendarContract.
        // Skip reconciliation if we had no pre-existing mappings (fresh sync after full re-sync)
        // to avoid falsely detecting just-inserted events as "deleted locally".
        if (existingCalMappings.isNotEmpty()) {
            reconcileDeletedEvents(account, provider, syncResult)
        } else {
            SyncLog.d(TAG, "Skipping reconcileDeletedEvents (no pre-existing calendar mappings, likely first sync after re-sync)")
        }

        // Upload locally modified events. Pass the trigger type so the upload path can
        // suppress invite/update/cancel emails during a forced full re-sync — those
        // pushes are reconciliation, not user edits, and don't represent intent to
        // notify attendees.
        uploadDirtyEvents(account, provider, syncResult, triggerType)

        removeCalendarsGoneFromServer(
            account = account,
            provider = provider,
            syncResult = syncResult,
            jobs = jobs,
            serverBackedAndroidCalIds = serverBackedAndroidCalIds,
            existingCalMappings = existingCalMappings,
            serverCalIds = serverCalIds
        )
    }

    /**
     * One calendar made ready for the parallel pass: its display name resolved, its local calendar row
     * created, reused or re-keyed, its mapping recorded and its keys fetched. Null means this calendar is
     * skipped for this run, exactly where the loop used to `continue`.
     */
    private suspend fun prepareCalendarJob(
        /**
         * Every Android calendar this run KNOWS the server still has, whether or not its job could be
         * built. removeCalendarsGoneFromServer reads it so that a calendar skipped for a key failure is
         * never mistaken for one deleted on Proton. That matters most right after a delete-and-recreate:
         * the Android row is reused by the new calendar, and treating it as orphaned proposes wiping a
         * calendar that is alive on the server.
         */
        serverBackedAndroidCalIds: MutableSet<Long>,
        account: Account,
        provider: ContentProviderClient,
        calInfo: CalendarInfo,
        multiAccount: Boolean,
        allServerCalIds: Set<String>,
        existingCalMappings: Map<String, CalendarSyncEntity>,
        existingLocalCalendars: MutableMap<String, MutableList<Long>>
    ): CalendarJob? {
        val isExternalCalendar = calInfo.type != 0

        // OUR entry in the member list, not simply the first one: see memberForUser. Everything below
        // that describes "this account on this calendar" reads from this one entry, so the colour, the
        // owner email and the permissions can no longer come from three different members.
        val userEmails = keyManager.getAddressEmails(account.name)
        val memberInfo = memberForUser(calInfo.members, userEmails)

        // Name and color may be at calendar level, member level, or in bootstrap. Colour is PER MEMBER,
        // so on a shared calendar another member's colour is not ours.
        var calName = calInfo.name ?: calInfo.description
        var calColorStr = calInfo.color ?: memberInfo?.color

        // Try bootstrap endpoint if name is missing
        if (calName == null) {
            try {
                val bootstrapResp = apiClient.getCalendarBootstrap(account.name, calInfo.id)
                if (bootstrapResp.isSuccessful) {
                    val body = bootstrapResp.body()
                    @Suppress("UNCHECKED_CAST")
                    val calSettings = (body?.get("CalendarSettings") as? Map<String, Any>)
                    calName = calSettings?.get("Name") as? String
                    if (calColorStr == null) {
                        calColorStr = calSettings?.get("Color") as? String
                    }
                    @Suppress("UNCHECKED_CAST")
                    val members = (body?.get("Members") as? List<Map<String, Any>>)
                    if (calName == null && members != null) {
                        for (member in members) {
                            val mName = member["Name"] as? String
                            val mColor = member["Color"] as? String
                            if (mName != null && calName == null) calName = mName
                            if (mColor != null && calColorStr == null) calColorStr = mColor
                        }
                    }
                    SyncLog.d(TAG, "Resolved name='$calName'")
                }
            } catch (e: Exception) {
                SyncLog.w(TAG, "Failed to fetch bootstrap for ${calInfo.id}: ${e.message}")
            }
        }

        calName = calName ?: if (calInfo.type == 0) "My Calendar" else "Proton Calendar"
        val displayName = if (multiAccount) {
            "${account.name} - $calName"
        } else {
            calName
        }

        val calColor = parseColor(calColorStr ?: "#657ee4")

        // Ensure local calendar exists. Resolve the user's email for OWNER_ACCOUNT —
        // CalendarContract uses this field as the default ATTENDEE_EMAIL when the
        // user adds themselves to a new event in Samsung/Google Calendar. Each
        // CalendarMember carries an immutable Email/AddressID bound at calendar
        // creation. For a personal calendar there's exactly one member; for shared
        // calendars we pick the member whose email matches one of *our* address keys,
        // falling back to the first member (memberForUser).
        // Already resolved above, together with the permissions it must agree with.
        val ownerEmail = memberInfo?.email ?: userEmails.firstOrNull() ?: account.name
        val existingMapping = existingCalMappings[calInfo.id]
        val androidCalId = if (existingMapping?.androidCalendarId != null) {
            updateLocalCalendar(provider, account, existingMapping.androidCalendarId, displayName, calColor, ownerEmail, calInfo.type, memberInfo?.permissions)
            existingMapping.androidCalendarId
        } else {
            // No mapping — check if a calendar already exists in CalendarContract (orphaned from
            // a previous sync, reinstall, or full re-sync that cleared mappings but not calendars)
            val orphanedCalId = existingLocalCalendars[displayName]?.firstOrNull()
            if (orphanedCalId != null) {
                SyncLog.i(TAG, "Reusing existing calendar '$displayName' (androidId=$orphanedCalId) instead of creating duplicate")
                updateLocalCalendar(provider, account, orphanedCalId, displayName, calColor, ownerEmail, calInfo.type, memberInfo?.permissions)
                // Remove from the map so it won't be reused by another Proton calendar
                existingLocalCalendars[displayName]?.remove(orphanedCalId)
                orphanedCalId
            } else {
                insertLocalCalendar(provider, account, displayName, calColor, ownerEmail, calInfo.type, memberInfo?.permissions)
            }
        }

        if (androidCalId == null) {
            SyncLog.e(TAG, "Failed to create/update local calendar for ${calInfo.id}")
            return null
        }

        val existingCalMapping = existingCalMappings[calInfo.id]
        calendarSyncDao.upsert(
            CalendarSyncEntity(
                accountName = account.name,
                protonCalendarId = calInfo.id,
                androidCalendarId = androidCalId,
                displayName = displayName,
                color = calColor,
                lastModelEventId = existingCalMapping?.lastModelEventId
            )
        )

        // Delete+recreate under the same name: this new protonId is reusing an Android calendar
        // that an OLD, now-deleted protonId still maps to. Move the old calendar's event mappings
        // onto the new id and drop the stale mapping, so the now-empty server calendar makes the
        // per-event RemoteDeletionGuard prompt (Delete from phone / Keep on phone) — instead of the
        // "remove calendars no longer on server" pass silently deleting the whole calendar.
        val supersededMappings = existingCalMappings.values.filter {
            it.androidCalendarId == androidCalId &&
                it.protonCalendarId != calInfo.id &&
                it.protonCalendarId !in allServerCalIds
        }
        for (old in supersededMappings) {
            val moved = calendarEventSyncDao.reassignCalendar(account.name, old.protonCalendarId, calInfo.id)
            calendarSyncDao.deleteByProtonId(account.name, old.protonCalendarId)
            SyncLog.w(TAG, "Calendar '$displayName' was deleted+recreated on Proton " +
                "(${old.protonCalendarId.take(8)}…→${calInfo.id.take(8)}…); re-keyed $moved event mapping(s) " +
                "so they go through the deletion prompt instead of a silent wipe")
        }

        serverBackedAndroidCalIds.add(androidCalId)

        // Fetch calendar keys and passphrase for decryption
        var calKeys = emptyList<CalendarKey>()
        var calPassphrases = emptyList<CalendarPassphrase>()
        try {
            calKeys = fetchCalendarKeys(account.name, calInfo.id)
            calPassphrases = fetchCalendarPassphrases(account.name, calInfo.id)
        } catch (e: Exception) {
            if (isExternalCalendar) {
                SyncLog.w(TAG, "No keys for external calendar ${calInfo.id} (expected): ${e.message}")
                // Expected for a subscribed calendar that carries none, and NOT expected when the fetch
                // simply failed: the events are then written from the cleartext structural part alone and
                // quietly lose whatever was encrypted. There is no way to tell the two apart here, so the
                // run stops claiming success and says which calendar to look at.
                SyncUtils.recordSwallowedFailure(
                    "calendar", account.name,
                    "Could not read the keys for the external calendar '$displayName'; its events may be missing details this run"
                )
            } else {
                SyncLog.e(TAG, "Skipping ENTIRE personal calendar '$displayName' (${calInfo.id.take(8)}…) for ${account.name} - key/passphrase fetch failed, none of its events will download this run", e)
                // The CALLER records the failure, because syncResult is in scope there. Returning null here
                // means exactly one thing and the caller relies on it: a personal calendar that cannot be
                // decrypted this run.
                return null
            }
        }

        return CalendarJob(calInfo, androidCalId, calKeys, calPassphrases, isExternalCalendar, displayName)
    }

    /**
     * Every local calendar of this account by display name, with the unmapped duplicates deleted.
     *
     * The SAME mutable lists are handed back, not copies: the preparation loop removes an id from one as it
     * claims an orphaned calendar, so that no second Proton calendar reuses the same Android row. A read-only
     * or deep-copied return would compile in some shapes and silently break that.
     */
    private fun readAndDedupeLocalCalendars(
        account: Account,
        provider: ContentProviderClient,
        existingCalMappings: Map<String, CalendarSyncEntity>
    ): MutableMap<String, MutableList<Long>> {
        // Query ALL existing local calendars for this account (for orphan detection and dedup)
        val existingLocalCalendars = mutableMapOf<String, MutableList<Long>>() // displayName → list of androidCalIds
        try {
            val calCursor = provider.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(account.name, account.type),
                null
            )
            calCursor?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: ""
                    existingLocalCalendars.getOrPut(name) { mutableListOf() }.add(id)
                }
            }
            // Clean up duplicates: if multiple Android calendars share a name, remove the extras — but
            // NEVER delete one a sync mapping points at (that would silently wipe its events, the exact
            // class of bug the deletion guard exists to prevent). Keep every mapped row plus one
            // unmapped, and delete only the unmapped extras.
            val mappedAndroidCalIds = existingCalMappings.values.mapNotNull { it.androidCalendarId }.toSet()
            for ((name, ids) in existingLocalCalendars) {
                if (ids.size > 1) {
                    val keeper = ids.firstOrNull { it in mappedAndroidCalIds } ?: ids.first()
                    val toDelete = ids.filter { it != keeper && it !in mappedAndroidCalIds }
                    if (toDelete.isNotEmpty()) {
                        SyncLog.w(TAG, "Found ${ids.size} calendars named '$name'; removing ${toDelete.size} unmapped duplicate(s)")
                        for (dupId in toDelete) {
                            try {
                                provider.delete(
                                    ContentUris.withAppendedId(
                                        SyncUtils.asCallerSyncAdapter(CalendarContract.Calendars.CONTENT_URI, account),
                                        dupId
                                    ),
                                    null, null
                                )
                            } catch (e: Exception) {
                                SyncLog.w(TAG, "Failed to delete duplicate calendar $dupId", e)
                            }
                        }
                        ids.removeAll(toDelete.toSet())
                    }
                }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to query existing local calendars: ${e.message}")
        }

        return existingLocalCalendars
    }

    /** Proton's calendars for this account, or null when the list could not be read and the sync must stop. */
    private suspend fun fetchCalendarList(account: Account, syncResult: SyncResult): List<CalendarInfo>? {
        // Fetch calendar list from Proton
        val calendarsResponse = apiClient.getCalendars(account.name)
        if (!calendarsResponse.isSuccessful) {
            SyncLog.e(TAG, "Failed to fetch calendars: ${calendarsResponse.code()}")
            syncResult.stats.numIoExceptions++
            // Recorded, or the failure notification and the settings screen say only "Sync
            // incomplete" - the exact unhelpful string SyncUtils' swallowed-failure mechanism was
            // built to avoid, and which its KDoc names. The contacts sibling has always recorded
            // here and this one never did, so during a Proton outage a calendar failure
            // gave the user no reason at all while contacts named one.
            SyncUtils.recordSwallowedFailure(
                "calendar", account.name, "Failed to fetch calendars: HTTP ${calendarsResponse.code()}"
            )
            return null
        }

        val calendars = calendarsResponse.body()?.calendars ?: run {
            SyncLog.e(TAG, "Calendar list body null/empty for ${account.name} (HTTP ${calendarsResponse.code()}) - aborting calendar sync, NO events will download this run")
            syncResult.stats.numIoExceptions++
            SyncUtils.recordSwallowedFailure(
                "calendar", account.name,
                "Calendar list was empty (HTTP ${calendarsResponse.code()}); nothing downloaded"
            )
            return null
        }

        return calendars
    }

    private suspend fun applyBulkDeletionRestore(account: Account, provider: ContentProviderClient) {
        // Bulk-deletion "Keep on Proton" recovery: the user chose to keep the events that had
        // vanished locally. Re-download ONLY the affected calendars: wipe each one's local events
        // + its event mappings + its calendar mapping (so it re-reads as "changed" instead of
        // "unchanged-skip"), then the download below re-creates them from Proton. Untouched
        // calendars keep their mappings and aren't re-downloaded. The wiped calendars have no
        // mapping when existingCalMappings loads below, so reconcileDeletedEvents skips them.
        val restoreCals = BulkDeletionGuard.consumeRestore(context, account.name)
        if (restoreCals != null && restoreCals.isNotEmpty()) {
            SyncLog.i(TAG, "Bulk-deletion restore for ${account.name}: re-downloading ${restoreCals.size} affected calendar(s)")
            for (protonCalId in restoreCals) {
                val androidCalId = calendarSyncDao.getByProtonId(account.name, protonCalId)?.androidCalendarId
                if (androidCalId != null) {
                    try {
                        provider.delete(
                            SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                            "${CalendarContract.Events.CALENDAR_ID} = ?",
                            arrayOf(androidCalId.toString())
                        )
                    } catch (e: Exception) {
                        SyncLog.w(TAG, "Restore: failed to clear events for calendar $protonCalId: ${e.message}")
                    }
                }
                calendarEventSyncDao.deleteByCalendar(account.name, protonCalId)
                calendarSyncDao.deleteByProtonId(account.name, protonCalId)
            }
        }
    }

    private suspend fun applyConfirmedRemoteDeletions(
        account: Account,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        // Server→local deletion confirmation (RemoteDeletionGuard). The per-calendar passes above
        // held any suspicious server-side deletions. Apply them only after the user confirmed —
        // skip-proof: delete by stored id, so it works even if every calendar was unchanged-skipped
        // on this run; otherwise (re)show the prompt, or clear when nothing is held.
        val gType = RemoteDeletionGuard.TYPE_CALENDAR
        if (RemoteDeletionGuard.isApproved(context, gType, account.name)) {
            var deleted = 0
            for (protonId in RemoteDeletionGuard.pendingIds(context, gType, account.name)) {
                val evId = calendarEventSyncDao.getByProtonId(account.name, protonId)?.androidEventId ?: continue
                try {
                    provider.delete(
                        ContentUris.withAppendedId(
                            SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account), evId
                        ),
                        null, null
                    )
                    calendarEventSyncDao.deleteByProtonId(account.name, protonId)
                    deleted++
                } catch (e: Exception) {
                    SyncLog.e(TAG, "Failed to delete confirmed event $protonId", e)
                }
            }
            if (deleted > 0) syncResult.stats.numDeletes += deleted
            SyncLog.i(TAG, "Applied $deleted confirmed server-side event deletion(s) for ${account.name}")
            RemoteDeletionGuard.clearPending(context, gType, account.name)
            SyncNotificationHelper.cancelRemoteDeletionPrompt(context, account.name, gType)
        } else {
            val count = RemoteDeletionGuard.pendingCount(context, gType, account.name)
            if (count > 0) SyncNotificationHelper.showRemoteDeletionPrompt(context, account.name, gType, count)
            else SyncNotificationHelper.cancelRemoteDeletionPrompt(context, account.name, gType)
        }
    }

    private suspend fun removeCalendarsGoneFromServer(
        account: Account,
        provider: ContentProviderClient,
        syncResult: SyncResult,
        jobs: List<CalendarJob>,
        serverBackedAndroidCalIds: Set<Long>,
        existingCalMappings: Map<String, CalendarSyncEntity>,
        serverCalIds: Set<String>
    ) {
        // Remove calendars no longer on server — but NEVER delete an Android calendar that a still-
        // present Proton calendar is now using. Delete+recreate under the same name gives the new
        // calendar a new protonId over the SAME Android calendar; deleting it here would wipe the
        // freshly-synced calendar (and all its events) with no prompt. Those events were already
        // re-keyed to the new id during setup, so for the reused case we only drop the stale mapping.
        // Not just the jobs: a calendar whose keys failed has no job this run, and treating its Android row
        // as orphaned is how a delete-and-recreate ends up proposing to wipe a calendar that is alive.
        val liveAndroidCalIds = jobs.map { it.androidCalId }.toSet() + serverBackedAndroidCalIds
        for ((protonId, mapping) in existingCalMappings) {
            val androidCalId = mapping.androidCalendarId
            if (protonId in serverCalIds || androidCalId == null) continue
            if (androidCalId in liveAndroidCalIds) {
                // Android calendar reused by a live Proton calendar — keep the calendar + its events;
                // just drop the obsolete mapping (events were moved to the new id during setup).
                calendarSyncDao.deleteByProtonId(account.name, protonId)
                SyncLog.w(TAG, "Not deleting Android calendar $androidCalId for removed Proton calendar $protonId - it is reused by a live calendar (delete+recreate same name)")
                continue
            }
            // Genuine calendar removal (Proton calendar gone, Android calendar not reused). ASK before
            // wiping a whole calendar's worth of events: hold them in the shared (account-level)
            // RemoteDeletionGuard and prompt. This always prompts for a non-empty removed calendar
            // (unlike a per-event shrink, which only prompts past ABS_MIN) — a whole-calendar wipe is
            // always worth confirming. Held events share ONE Delete/Keep decision with any per-event
            // shrink held in the same run (account-level guard). The (now-empty) Android calendar row is
            // deleted only once its events are gone, so "Keep on phone" preserves BOTH events and calendar.
            val gType = RemoteDeletionGuard.TYPE_CALENDAR
            val calEventMappings = calendarEventSyncDao.getAllByCalendar(account.name, protonId)
            val keptHere = RemoteDeletionGuard.keptIds(context, gType, account.name)
            val nonKept = calEventMappings.filter { it.androidEventId != null && it.protonEventId !in keptHere }
            val approvedHere = RemoteDeletionGuard.isApproved(context, gType, account.name)
            if (nonKept.isNotEmpty() && !approvedHere) {
                // Hold + prompt: leave the calendar and its events in place until the user decides.
                RemoteDeletionGuard.addPending(context, gType, account.name, nonKept.map { it.protonEventId }.toSet())
                SyncNotificationHelper.showRemoteDeletionPrompt(
                    context, account.name, gType, RemoteDeletionGuard.pendingCount(context, gType, account.name)
                )
                SyncLog.w(TAG, "Held removal of calendar $protonId (${nonKept.size} event(s)) awaiting confirmation")
                continue
            }
            // Approved (the post-parallel pass already deleted the held events) OR nothing left to
            // confirm: delete any still-present non-kept events, then drop the calendar row unless
            // kept events still live on it.
            for (ev in nonKept) {
                val evId = ev.androidEventId ?: continue
                try {
                    provider.delete(
                        ContentUris.withAppendedId(
                            SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account), evId
                        ),
                        null, null
                    )
                    calendarEventSyncDao.deleteByProtonId(account.name, ev.protonEventId)
                    synchronized(syncResult.stats) { syncResult.stats.numDeletes++ }
                } catch (e: Exception) {
                    SyncLog.e(TAG, "Failed to delete event ${ev.protonEventId} of removed calendar $protonId", e)
                }
            }
            if (calendarEventSyncDao.getAllByCalendar(account.name, protonId).isNotEmpty()) {
                // Kept events remain — preserve the Android calendar + its events, but drop the stale
                // Proton calendar mapping so we don't re-evaluate (and re-prompt for) a calendar that no
                // longer exists on Proton on every future sync.
                calendarSyncDao.deleteByProtonId(account.name, protonId)
                SyncLog.w(TAG, "Calendar $protonId removed on Proton but kept event(s) remain - keeping them locally, dropping the stale mapping")
            } else {
                try {
                    provider.delete(
                        ContentUris.withAppendedId(
                            SyncUtils.asCallerSyncAdapter(CalendarContract.Calendars.CONTENT_URI, account),
                            androidCalId
                        ),
                        null, null
                    )
                    calendarSyncDao.deleteByProtonId(account.name, protonId)
                    calendarEventSyncDao.deleteByCalendar(account.name, protonId)
                    SyncLog.i(TAG, "Removed calendar $protonId (androidId=$androidCalId) from phone")
                } catch (e: Exception) {
                    SyncLog.e(TAG, "Failed to delete calendar $protonId", e)
                }
            }
        }
    }

    /**
     * Dump Google Calendar's editability-relevant columns for one event + the
     * parent calendar row. Helps diagnose "events still read-only after fix"
     * reports (from a tester, v1.4.26-v1.4.31) without requiring them to dump
     * SQLite. Verbose-only — short-circuits when verbose logging is off so the
     * cursor query overhead disappears entirely on normal builds. Best-effort:
     * cursor failures are swallowed so sync continues.
     */
    private fun dumpEditabilityDiagnostic(
        provider: ContentProviderClient,
        account: Account,
        androidCalId: Long,
        calInfo: CalendarInfo,
        calDisplayName: String,
        site: String
    ) {
        if (!SyncLog.isVerboseEnabled()) return
        try {
            // Pick any event on this calendar — we just want a representative row.
            provider.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.ORGANIZER,
                    CalendarContract.Events.GUESTS_CAN_MODIFY,
                    CalendarContract.Events.GUESTS_CAN_INVITE_OTHERS,
                    CalendarContract.Events.HAS_ATTENDEE_DATA
                ),
                "${CalendarContract.Events.CALENDAR_ID} = ?",
                arrayOf(androidCalId.toString()),
                "${CalendarContract.Events._ID} ASC LIMIT 1"
            )?.use { c ->
                if (c.moveToFirst()) {
                    SyncLog.d(TAG, "Diag[$site] '$calDisplayName' event[${c.getLong(0)}]: ORGANIZER='${c.getString(1)}', GUESTS_CAN_MODIFY=${c.getInt(2)}, GUESTS_CAN_INVITE_OTHERS=${c.getInt(3)}, HAS_ATTENDEE_DATA=${c.getInt(4)}")
                } else {
                    SyncLog.d(TAG, "Diag[$site] '$calDisplayName': no events on this calendar yet")
                }
            }
            provider.query(
                ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, androidCalId),
                arrayOf(
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.Calendars.OWNER_ACCOUNT,
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    SyncLog.d(TAG, "Diag[$site] '$calDisplayName' calendar[$androidCalId]: ACCOUNT_NAME='${c.getString(0)}', ACCOUNT_TYPE='${c.getString(1)}', OWNER_ACCOUNT='${c.getString(2)}', ACCESS_LEVEL=${c.getInt(3)}, ourMember.email='${memberForUser(calInfo.members, keyManager.getAddressEmails(account.name))?.email}', members=${calInfo.members?.size ?: 0}, ourPermissions=${memberForUser(calInfo.members, keyManager.getAddressEmails(account.name))?.permissions}")
                }
            }
        } catch (e: Exception) {
            SyncLog.d(TAG, "Diag[$site] readback failed: ${e.message}")
        }
    }

    /**
     * Where one calendar's event fetch should look this run, and why.
     *
     * Computed once by [resolveCalendarFetchPlan] so syncCalendarEvents itself carries neither the
     * preference plumbing nor the window arithmetic. See the note above the uploadModifiedEvent
     * support helpers for why this file keeps its big suspend functions thin.
     */
    private class CalendarFetchPlan(
        val syncMode: String,
        val syncStart: Long,
        val syncEnd: Long,
        val rangeKey: String,
        val currentRange: String,
        val lastRange: String?,
        val forceRefetch: Boolean,
        /** Event count at or below which the flat /events/ids path is cheaper than the dense sweep. */
        val sparseThreshold: Int,
        /** Number of dense-sweep windows the range spans; drives [sparseThreshold] and its log line. */
        val rangeWindows: Long
    )

    /**
     * Resolve the fetch window, the range-change/heal flags, and the sparse-vs-dense break-even for
     * one calendar. Pure preference reads and arithmetic — the one side effect is marking the
     * one-time v1.6.21 re-link heal as done, which belongs with the decision that consumes it.
     */
    private fun resolveCalendarFetchPlan(
        prefs: android.content.SharedPreferences,
        account: Account,
        calInfo: CalendarInfo,
        calDisplayName: String,
        storedModelEventId: String?
    ): CalendarFetchPlan {
        val syncMode = prefs.getString(SyncPreferences.calendarSyncModeKey(account.name), SyncPreferences.DEFAULT_SYNC_MODE) ?: SyncPreferences.DEFAULT_SYNC_MODE
        val pastDays = prefs.getString(SyncPreferences.calendarPastDaysKey(account.name), "${SyncPreferences.DEFAULT_PAST_DAYS}")?.toLongOrNull() ?: SyncPreferences.DEFAULT_PAST_DAYS
        val futureDays = prefs.getString(SyncPreferences.calendarFutureDaysKey(account.name), "${SyncPreferences.DEFAULT_FUTURE_DAYS}")?.toLongOrNull() ?: SyncPreferences.DEFAULT_FUTURE_DAYS
        val now = System.currentTimeMillis() / 1000
        // Proton requires Start AND End on every events query (HTTP 400 / Code 2000
        // "The Start is required" otherwise). The `syncMode` flag picks how we compute
        // the bounds: "all" → epoch..2037 (sparse path will fetch every event by ID
        // regardless; dense path chunks across the full span); "custom" → the user-
        // chosen past/future day counts. The legacy 0-as-sentinel encoding for "All"
        // is gone — pastDays/futureDays are now always strict positive day counts and
        // are only consulted when syncMode == "custom".
        val syncStart: Long
        val syncEnd: Long
        if (syncMode == SyncPreferences.SYNC_MODE_ALL) {
            syncStart = SyncConstants.CALENDAR_QUERY_MIN_START
            syncEnd = SyncConstants.CALENDAR_QUERY_MAX_END
        } else {
            syncStart = now - (pastDays * SyncConstants.SECONDS_PER_DAY)
            syncEnd = now + (futureDays * SyncConstants.SECONDS_PER_DAY)
        }
        if (syncMode == SyncPreferences.SYNC_MODE_ALL) {
            SyncLog.d(TAG, "'$calDisplayName': fetch window mode=all (all-time start=$syncStart end=$syncEnd, tz=${java.util.TimeZone.getDefault().id}) - every event is fetched")
        } else {
            SyncLog.d(TAG, "'$calDisplayName': fetch window mode=$syncMode start=$syncStart end=$syncEnd (pastDays=$pastDays futureDays=$futureDays, tz=${java.util.TimeZone.getDefault().id}) - events outside this range are not fetched")
        }

        // Detect if the sync range changed since last sync — if so, force a re-fetch
        val rangeKey = SyncPreferences.lastSyncRangeKey(account.name, calInfo.id)
        val currentRange = "${syncMode}_${pastDays}_${futureDays}"
        val lastRange = prefs.getString(rangeKey, null)

        // One-time heal (v1.6.21): force a single full re-fetch of each subscribed/external
        // (type != 0) calendar so recurring-override rows inserted UNLINKED by older builds get
        // re-processed and attached to their series (fixes the duplicate rendering) without the
        // user needing a manual force-resync. Tracked per-calendar-id so it fires exactly once
        // per calendar; personal (type 0) calendars are untouched.
        val healKey = "override_link_heal_1621_cals"
        val healedCals = synchronized(healMarkerLock) { prefs.getStringSet(healKey, emptySet()) ?: emptySet() }
        val healThisCalendar = calInfo.type != 0 && calInfo.id !in healedCals
        // Range changed explicitly, or range was never stored but we already synced before (upgrade path)
        val forceRefetch = (lastRange != null && lastRange != currentRange) ||
            (lastRange == null && storedModelEventId != null) ||
            healThisCalendar
        if (healThisCalendar) {
            SyncLog.i(TAG, "One-time re-link heal (v1.6.21): full re-fetch of subscribed calendar '$calDisplayName' to attach recurring overrides")
            // Re-read inside the lock. This runs once per calendar from the parallel per-calendar
            // coroutines, so two calendars healing in the same run both read the set before either
            // wrote, and the second write dropped the first calendar's id. Its marker was lost, so it
            // did the full re-fetch again on the next sync, and every sync after that: a one-time heal
            // that never finished on any account with more than one subscribed calendar.
            synchronized(healMarkerLock) {
                val current = prefs.getStringSet(healKey, emptySet()) ?: emptySet()
                prefs.edit().putStringSet(healKey, current + calInfo.id).apply()
            }
        }

        // Window-count-aware threshold: the flat path stays cheaper up to ~(rangeWindows × permits)
        // events; floored at CALENDAR_HYBRID_FETCH_THRESHOLD so bounded ranges keep today's behavior.
        val chunkSeconds = SyncConstants.CALENDAR_QUERY_INITIAL_CHUNK_DAYS * SyncConstants.SECONDS_PER_DAY
        val rangeWindows = ((syncEnd - syncStart) + chunkSeconds - 1) / chunkSeconds
        val sparseThreshold = maxOf(
            SyncConstants.CALENDAR_HYBRID_FETCH_THRESHOLD.toLong(),
            rangeWindows * SyncConstants.CALENDAR_PARALLEL_GET_PERMITS
        ).toInt()

        return CalendarFetchPlan(
            syncMode = syncMode,
            syncStart = syncStart,
            syncEnd = syncEnd,
            rangeKey = rangeKey,
            currentRange = currentRange,
            lastRange = lastRange,
            forceRefetch = forceRefetch,
            sparseThreshold = sparseThreshold,
            rangeWindows = rangeWindows
        )
    }

    /**
     * Map each recurring series' UID to its master Proton event id, so single-occurrence overrides
     * (same UID, carrying a RECURRENCE-ID) can link to the correct parent.
     *
     * A cheap CLEARTEXT pre-pass: the signed type-2 SharedEvents part carries DTSTART/RRULE/
     * RECURRENCE-ID (the same source [extractEventCacheFields] reads), so a master (has RRULE, no
     * RECURRENCE-ID) is classified without decrypting anything. Running it up-front makes the
     * linking order-independent and immune to the per-event "unchanged" skip in the caller.
     *
     * Scans every CLEARTEXT part, not just type-2: personal calendars carry the signed iCal
     * (incl. RRULE) in type-2, subscribed/external (Google) calendars carry it in type-0 (see
     * [buildExternalEventICal]), and some parts arrive as already-cleartext iCal in a type-1/3
     * slot. A master whose RRULE lived outside type-2 was invisible here, so its overrides never
     * linked and rendered as duplicates on subscribed calendars.
     */
    private fun buildMasterIdByUid(events: List<CalendarEventDetail>): Map<String, String> {
        val masterIdByUid = HashMap<String, String>()
        for (event in events) {
            val cleartext = (event.sharedEvents.asSequence() +
                    event.calendarEvents.asSequence() +
                    (event.personalEvents?.asSequence() ?: emptySequence()))
                .filter { it.type == 0 || it.type == 2 || it.data.trimStart().startsWith("BEGIN:") }
                .joinToString("\n") { it.data.replace("\r\n ", "").replace("\r\n\t", "") }
            val isMaster = cleartext.lineSequence().any { it.startsWith("RRULE:") } &&
                cleartext.lineSequence().none { it.startsWith("RECURRENCE-ID") }
            if (isMaster) masterIdByUid[event.uid] = event.id
        }
        return masterIdByUid
    }

    private suspend fun syncCalendarEvents(
        account: Account,
        provider: ContentProviderClient,
        calInfo: CalendarInfo,
        androidCalId: Long,
        calKeys: List<CalendarKey>,
        calPassphrases: List<CalendarPassphrase>,
        syncResult: SyncResult,
        isExternalCalendar: Boolean = false,
        calDisplayName: String = "unknown",
        triggerType: SyncTriggerType = SyncTriggerType.ScheduledSync
    ) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)

        // --- Step 1: Check if calendar has changed at all (modelevents/latest) ---
        val calMapping = calendarSyncDao.getByProtonId(account.name, calInfo.id)
        val plan = resolveCalendarFetchPlan(
            prefs, account, calInfo, calDisplayName, calMapping?.lastModelEventId
        )
        val syncMode = plan.syncMode
        val syncStart = plan.syncStart
        val syncEnd = plan.syncEnd
        val rangeKey = plan.rangeKey
        val currentRange = plan.currentRange
        val lastRange = plan.lastRange
        val forceRefetch = plan.forceRefetch
        val sparseThreshold = plan.sparseThreshold
        val storedModelEventId = calMapping?.lastModelEventId
        var currentModelEventId: String? = null
        try {
            val modelResp = apiClient.getCalendarModelEventLatest(account.name, calInfo.id)
            if (modelResp.isSuccessful) {
                currentModelEventId = modelResp.body()?.get("CalendarModelEventID") as? String
                val wouldSkip = currentModelEventId != null && currentModelEventId == storedModelEventId && !forceRefetch &&
                    // While a server→local deletion is HELD for this account, never take the unchanged-skip
                    // fast path: it would bypass prunePending/pruneKept below, so an already-approved hold
                    // could delete events that have since reappeared on Proton. Transient — only while pending.
                    RemoteDeletionGuard.pendingCount(context, RemoteDeletionGuard.TYPE_CALENDAR, account.name) == 0
                if (wouldSkip) {
                    // WORKAROUND: Proton's CalendarModelEventID does NOT change when an
                    // attendee's RSVP status changes (accept/decline/tentative). Neither the
                    // core event loop nor the modelevents endpoint reports attendee status
                    // updates. The official Proton web calendar has the same limitation —
                    // it fetches full event details on demand rather than detecting RSVP changes.
                    // As a workaround, re-fetch events that have attendees to check for status changes.
                    // Disabled by default — may use extra API calls on large calendars with many attendees.
                    // Runs on the bypass path too: the sweep below cannot see RSVP-only changes
                    // (per-event LastEditTime is unchanged for them), so skipping this poll would
                    // make a user "Sync now" detect strictly less than a scheduled sync.
                    val pollAttendeeKey = SyncPreferences.pollAttendeeStatusKey(account.name)
                    val pollAttendees = PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(pollAttendeeKey, false)
                    if (pollAttendees) {
                        checkAttendeeStatusChanges(account, provider, calInfo, calDisplayName)
                    }
                    if (triggerType == SyncTriggerType.UserSyncNow) {
                        // An explicit user tap on "Sync now" runs a real incremental sweep even when
                        // the cursor says unchanged — the non-destructive recovery for anything the
                        // cursor missed (absorption race, server-side gaps). Only the user-facing
                        // buttons set EXTRA_USER_SYNC_NOW; scheduled/worker syncs and internal
                        // MANUAL requests (re-auth, deletion receivers) keep the cheap skip.
                        SyncLog.i(TAG, "Manual sync (user): bypassing unchanged-skip for '$calDisplayName' (modelEventId=${currentModelEventId?.take(12)}…)")
                    } else {
                        dumpEditabilityDiagnostic(provider, account, androidCalId, calInfo, calDisplayName, "unchanged-skip")
                        SyncLog.i(TAG, "Calendar '$calDisplayName' unchanged, skipping (modelEventId=${currentModelEventId?.take(12)}…)")
                        return
                    }
                }
                if (forceRefetch) {
                    SyncLog.i(TAG, "Sync range changed (${lastRange ?: "unset"} → $currentRange) for calendar '$calDisplayName', forcing re-fetch")
                }
            }
        } catch (e: Exception) {
            SyncLog.d(TAG, "modelevents/latest not available for ${calInfo.id}, doing full sync: ${e.message}")
        }

        val existingEventMappings = calendarEventSyncDao.getAllByCalendar(account.name, calInfo.id)
            .associateBy { it.protonEventId }

        // --- Step 2: Hybrid dispatch — count once, then sparse or dense path ---
        // Flat /events/ids path: ~ceil(N/100) cursor pages + N per-event GETs (parallel, wall
        //   ≈ N / CALENDAR_PARALLEL_GET_PERMITS) — independent of the date range, no recurrence
        //   re-expansion (each recurring master is returned exactly once).
        // Windowed dense /events?Type= path: ~rangeWindows × 4 round-trips, SEQUENTIAL per Type
        //   bucket — independent of event count but driven by the DATE-RANGE WIDTH, and it
        //   re-returns every recurring master in every window it spans.
        // So the flat path wins while N ≲ rangeWindows × permits. The break-even is a function of
        // the WINDOW COUNT, not a fixed event count: ~70 in a bounded range (~8–16 windows) but
        // ~1300 in "All" mode (~276 windows). The old fixed-70 gate wrongly sent moderate calendars
        // down the slow windowed path (the 485 s fresh sync). See sparseThreshold below.
        val totalEventCount: Int = try {
            val countResp = apiClient.getCalendarEventsCount(account.name, calInfo.id)
            if (countResp.isSuccessful) {
                (countResp.body()?.get("Total") as? Number)?.toInt() ?: -1
            } else -1
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to fetch event count for '$calDisplayName': ${e.message}; falling back to dense path")
            -1
        }
        SyncLog.i(TAG, "'$calDisplayName': server reports $totalEventCount events")

        // "All" mode ALWAYS takes the flat /events/ids + per-event GET path (regardless of count):
        // it's window-free and complete by construction, so serverEventIds becomes the authoritative
        // full id set (no dense under-fetch, no purge) and it avoids the ~1100 sequential window
        // calls of the retired dense sweep. The windowed dense path is kept only for custom (bounded)
        // mode, where its small window count makes it efficient.
        val fetch = if (syncMode == SyncPreferences.SYNC_MODE_ALL || totalEventCount in 0..sparseThreshold) {
            fetchEventsFlat(account, calInfo, calDisplayName, totalEventCount, plan)
        } else {
            fetchEventsDense(account, calInfo, calDisplayName, plan, existingEventMappings)
        } ?: return  // CRITICAL: a partial fetch would mark surviving server-side events as deleted
        val filteredEvents = fetch.events
        val serverEventIds = fetch.serverEventIds
        val fetchHadFailures = fetch.hadFailures

        SyncLog.i(TAG, "Fetched ${filteredEvents.size} events for '$calDisplayName'")

        // Compute the user's full address set once per sync — passed into CalendarMapper
        // so it can mark events whose ORGANIZER is a third party as guest-restricted
        // (Events.GUESTS_CAN_MODIFY=0). Hoisted out of the per-event loop because
        // KeyManager.getAddressEmails iterates the addressKeyRings map each call.
        val userAddressEmails = keyManager.getAddressEmails(account.name)
        // v1.4.26: also hand the calendar's owner email to the mapper. Multi-address
        // accounts (sign in as alias@example.com, calendar owned by primary@example.com) were
        // having their downloaded events flagged read-only because the organizer's
        // email wasn't in userAddressEmails at decode time. The user always controls
        // their own calendar regardless of which of their addresses signed an event,
        // so "organizer matches calendar owner" is a valid OR-condition for "user-owned".
        val calendarMemberEmail = memberForUser(calInfo.members, userAddressEmails)?.email

        // Map each recurring series' UID -> its master Proton event id, so single-occurrence
        // overrides (same UID, carrying a RECURRENCE-ID) link to the correct parent. Built in a
        // cheap CLEARTEXT pre-pass over ALL fetched events: the type-2 SharedEvents part is
        // cleartext-signed and carries DTSTART/RRULE/RECURRENCE-ID (same source extractEventCacheFields
        // reads), so we classify a master (has RRULE, no RECURRENCE-ID) without decrypting. Doing this
        // up-front makes linking order-independent and immune to the per-event "unchanged" skip below.
        val masterIdByUid = buildMasterIdByUid(filteredEvents)

        // This calendar's default reminders, applied to every event that carries no Notifications of
        // its own. That is the normal case by a wide margin (measured: nearly all events), and
        // Proton's own editor shows these defaults on such events, so without this the phone shows
        // no reminder where Proton shows two. One request per calendar per sync.
        val calendarSettings = fetchCalendarSettings(account.name, calInfo.id)

        // Events the user has edited locally but not yet uploaded. The download phase runs BEFORE
        // uploadDirtyEvents, so without this an event changed on BOTH sides would be overwritten
        // and its DIRTY flag cleared before the local edit was ever sent. One query per calendar
        // rather than one per event.
        val dirtyRead: Set<Long>? = readDirtyEventIds(provider, androidCalId)
        val locallyDirtyIds: Set<Long> = dirtyRead ?: emptySet()

        // Events that already have reminder rows, so the backfill below can skip them without a
        // query per event. Read once per calendar. Null when the read failed: nothing is backfilled then.
        val eventIdsWithReminders: Set<Long>? = readEventIdsWithReminders(provider)
        val backfilledReminders = applyFetchedEvents(
            account, provider, calInfo, androidCalId, calKeys, calPassphrases, syncResult,
            isExternalCalendar, calDisplayName, filteredEvents, existingEventMappings,
            EventApplyContext(
                userAddressEmails = userAddressEmails,
                calendarMemberEmail = calendarMemberEmail,
                masterIdByUid = masterIdByUid,
                calendarSettings = calendarSettings,
                locallyDirtyIds = locallyDirtyIds,
                eventIdsWithReminders = eventIdsWithReminders,
                dirtyReadOk = dirtyRead != null
            )
        )

        applyServerDeletions(
            account, provider, calDisplayName, syncResult,
            serverEventIds, existingEventMappings, fetchHadFailures
        )

        persistModelEventId(
            prefs, account, calInfo, calDisplayName,
            calMapping, currentModelEventId, fetchHadFailures, backfilledReminders
        )

        // Save the current sync range so we can detect changes next time
        prefs.edit().putString(rangeKey, currentRange).apply()
    }

    /**
     * Apply server-to-local deletions for one calendar, guarding against a transiently short
     * server response wiping local events.
     *
     * Never purges an event that still exists on the server: [serverEventIds] is the authoritative
     * id set from the fetch, and a run whose fetch was incomplete defers the whole pass rather than
     * risk deleting events it merely failed to retrieve. A normal small shrink deletes immediately;
     * a suspicious one is HELD for user confirmation (ids accumulate thread-safely across the
     * parallel calendars) and applied later by the post-parallel pass in performCalendarSync.
     */
    private suspend fun applyServerDeletions(
        account: Account,
        provider: ContentProviderClient,
        calDisplayName: String,
        syncResult: SyncResult,
        serverEventIds: Set<String>,
        existingEventMappings: Map<String, CalendarEventSyncEntity>,
        fetchHadFailures: Boolean
    ) {
        // Delete events no longer on server — CONFIRM before a *suspicious* shrink (a transient
        // server response could otherwise wipe local events). A normal/small shrink deletes now;
        // a large one is HELD (ids accumulate thread-safely across the parallel calendars) and is
        // applied only after the user confirms, in the post-parallel pass in doPerformSync.
        val gType = RemoteDeletionGuard.TYPE_CALENDAR
        RemoteDeletionGuard.prunePending(context, gType, account.name, serverEventIds)
        RemoteDeletionGuard.pruneKept(context, gType, account.name, serverEventIds)
        val keptEvents = RemoteDeletionGuard.keptIds(context, gType, account.name)
        val evToDelete = existingEventMappings.filter { (protonId, m) ->
            protonId !in serverEventIds && m.androidEventId != null && protonId !in keptEvents
        }
        if (evToDelete.isNotEmpty() && fetchHadFailures) {
            // This run's fetch was incomplete (under-fetch the /events/ids backfill couldn't fully
            // recover, or a failed id/body GET). serverEventIds may be missing events that DO exist
            // on the server, so deleting "absent" local events here would be data loss. Defer — the
            // model-event-id is not saved (above), so the next sync re-reconciles against a clean fetch.
            SyncLog.w(TAG, "'$calDisplayName': NOT applying ${evToDelete.size} server-side deletion(s) - this run's fetch was incomplete; deferring to avoid purging un-fetched events")
        } else if (evToDelete.isNotEmpty()) {
            when {
                // Already confirmed: the skip-proof post-parallel pass deletes the confirmed (pending)
                // set. Don't delete here — bounding deletion to what the user actually saw prevents a
                // stale approval from over-deleting if THIS run's server response is transiently short.
                RemoteDeletionGuard.isApproved(context, gType, account.name) -> {
                    /* defer to the post-parallel apply in performCalendarSync */
                }
                // Suspicious shrink: hold for confirmation instead of deleting.
                RemoteDeletionGuard.isSuspicious(evToDelete.size, serverEventIds.size, existingEventMappings.size) -> {
                    RemoteDeletionGuard.addPending(context, gType, account.name, evToDelete.keys)
                    val stillOnServer = existingEventMappings.keys.count { it in serverEventIds }
                    SyncLog.w(
                        TAG,
                        "Held ${evToDelete.size} server-side event deletion(s) for '$calDisplayName' awaiting " +
                            "confirmation (of ${existingEventMappings.size} mapped, $stillOnServer still on the " +
                            "server; server now lists ${serverEventIds.size})"
                    )
                }
                // Normal small shrink: delete immediately.
                else -> {
                    // Worded carefully. This used to read "Removing 4 events no longer on server
                    // (existing=4, server=4)", which is self-contradictory to anybody who does not
                    // know the two counts are of DISJOINT id sets, and reads like the app deleting
                    // a calendar it just confirmed was intact. A user reading their own debug log
                    // would report it as data loss. What actually matters is the overlap.
                    val stillOnServer = existingEventMappings.keys.count { it in serverEventIds }
                    val wholesaleReplacement = stillOnServer == 0 && serverEventIds.isNotEmpty()
                    SyncLog.i(
                        TAG,
                        "Removing ${evToDelete.size} event(s) for '$calDisplayName' whose Proton id no longer exists " +
                            "(of ${existingEventMappings.size} mapped, $stillOnServer still on the server; " +
                            "server now lists ${serverEventIds.size})" +
                            if (wholesaleReplacement) {
                                " - not one mapped id survived, which is what deleting and re-importing " +
                                    "a calendar on Proton looks like; the server's events download as new"
                            } else {
                                ""
                            }
                    )
                    for ((protonId, mapping) in evToDelete) {
                        val evId = mapping.androidEventId ?: continue
                        try {
                            provider.delete(
                                ContentUris.withAppendedId(
                                    SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account), evId
                                ),
                                null, null
                            )
                            calendarEventSyncDao.deleteByProtonId(account.name, protonId)
                            synchronized(syncResult.stats) { syncResult.stats.numDeletes++ }
                        } catch (e: Exception) {
                            SyncLog.e(TAG, "Failed to delete event $protonId", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * Drop held server→local event deletions that have nothing left to delete on this phone.
     *
     * Account-level on purpose. The pending set is per (type, account), while [applyServerDeletions]
     * sees ONE calendar from a parallel coroutine, so a sweep placed next to its prunePending would
     * call every other calendar's holds stale; and the whole-calendar hold never goes through that
     * function at all. Two independent kinds of local evidence are consulted and a hold survives if
     * either one says the phone still has something, because this must never become more willing to
     * delete. Nothing is notified here: the block that shows the prompt already cancels it when the
     * held count reaches zero, and re-posts it with the corrected count otherwise.
     *
     * Costs one preferences read on a sync with nothing held, which is the normal case.
     */
    private suspend fun sweepStaleDeletionHolds(account: Account, provider: ContentProviderClient) {
        val gType = RemoteDeletionGuard.TYPE_CALENDAR
        val held = RemoteDeletionGuard.pendingIds(context, gType, account.name)
        if (held.isEmpty()) return
        // What the mapping table claims, checked against the provider: a mapping outlives a row that
        // was removed behind this app's back, and a hold kept alive by such a mapping is exactly the
        // state this sweep exists to end. A failed look must never read as an absence, so null keeps
        // every hold.
        val claims = mutableListOf<MappedRow>()
        for (chunk in held.chunked(400)) claims.addAll(calendarEventSyncDao.mappedRows(account.name, chunk))
        val liveRows = existingEventRowIds(provider, account, claims.map { it.androidEventId }.toSet())
            ?: return
        val stillMapped = claims.filter { it.androidEventId in liveRows }.map { it.protonEventId }.toSet()
        val unmapped = held - stillMapped
        val stillOnPhone =
            if (unmapped.isEmpty()) emptySet() else protonIdsOnPhone(account, provider, unmapped) ?: held
        val sweep = sweepHolds(held, stillMapped, stillOnPhone)
        if (sweep.stale.isEmpty()) return
        if (sweep.remaining.isEmpty()) {
            // Nothing is held any more, so clear rather than prune: clearPending also drops the
            // approved flag, which must not outlive the set of ids it was given for.
            RemoteDeletionGuard.clearPending(context, gType, account.name)
        } else {
            RemoteDeletionGuard.prunePending(context, gType, account.name, sweep.stale)
        }
        // The mappings of the swept ids describe a row that is gone, for an event Proton no longer has
        // either. Leaving them would re-hold the same ids on the very next sync, because a calendar
        // that is empty on Proton counts as a suspicious shrink every time, so the prompt would come
        // straight back. Nothing on the phone and nothing on Proton is touched here, only the bookkeeping.
        var forgotten = 0
        for (protonId in sweep.stale) {
            calendarEventSyncDao.deleteByProtonId(account.name, protonId)
            forgotten++
        }
        SyncLog.w(
            TAG,
            "Dropped $forgotten held deletion(s) for ${account.name}: gone from Proton and gone from " +
                "this phone, so there is nothing left to delete (${sweep.remaining.size} still held)"
        )
    }

    /**
     * Drop per-event retry counters whose event no longer exists.
     *
     * Three families count strikes against one event and each is cleared only when its operation
     * finally succeeds or gives up, so an event deleted in between leaves its counter behind for the
     * life of the install. Two were found on a test phone at 2 strikes each, naming rows deleted days
     * earlier, having survived two account removals.
     *
     * Mostly bloat, but the Android-id families have an edge: a row id can be handed to a different
     * event later, and a `delete_reject_count_` left at 2 would cut a good delete short on its first
     * attempt. The invite family is keyed by Proton id, so its liveness comes from the mapping table.
     *
     * Fails closed in the only direction that matters: if the provider cannot be read, nothing is
     * swept, because removing a counter for an event that DOES exist hands it a free strike.
     */
    private suspend fun sweepStaleRetryCounters(account: Account, provider: ContentProviderClient) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val allKeys = prefs.all.keys.toSet()
        val uploadPrefix = "upload_reject_count_"
        val deletePrefix = "delete_reject_count_"
        val invitePrefix = "invite_retry_count_"
        if (allKeys.none { it.startsWith(uploadPrefix) || it.startsWith(deletePrefix) || it.startsWith(invitePrefix) }) {
            return
        }

        // The Android-id families, checked against the provider. A tombstone still counts as existing:
        // its deletion has not been sent yet, so its strikes are still live.
        val androidIds = (allKeys.filter { it.startsWith(uploadPrefix) }.map { it.removePrefix(uploadPrefix) } +
            allKeys.filter { it.startsWith(deletePrefix) }.map { it.removePrefix(deletePrefix) })
            .mapNotNull { it.toLongOrNull() }
            .toSet()
        val liveAndroidIds = existingEventRowIds(provider, account, androidIds)
            ?.mapTo(mutableSetOf()) { it.toString() }

        // The Proton-id family, checked against the mapping table, which is this app's own record of
        // what it has on the phone. Only the ids a counter actually names are asked about, so the
        // query stays bounded however many events the account has.
        val countedProtonIds = allKeys.filter { it.startsWith(invitePrefix) }
            .map { it.removePrefix(invitePrefix) }
            .filter { it.isNotEmpty() }
        val mappedProtonIds = mutableSetOf<String>()
        for (chunk in countedProtonIds.chunked(400)) {
            calendarEventSyncDao.mappedRows(account.name, chunk).forEach { mappedProtonIds.add(it.protonEventId) }
        }

        val stale = staleCounterKeys(allKeys, uploadPrefix, liveAndroidIds) +
            staleCounterKeys(allKeys, deletePrefix, liveAndroidIds) +
            staleCounterKeys(allKeys, invitePrefix, mappedProtonIds)
        if (stale.isEmpty()) return

        val edit = prefs.edit()
        for (key in stale) edit.remove(key)
        edit.apply()
        SyncLog.i(
            TAG,
            "Dropped ${stale.size} retry counter(s) for events that no longer exist on this phone"
        )
    }

    /**
     * Drop collision holds whose event is gone from this phone, and take their prompts down.
     *
     * A held event deleted on Proton is deleted locally by [applyServerDeletions] like any other,
     * and its mapping goes with it, but the hold is kept in preferences and the notification stays
     * on screen. It then asks which version of an event to keep when neither version exists, and
     * both answers act on nothing: "Keep theirs" has nothing to download and "Keep mine" has
     * nothing to upload, so the prompt could not be got rid of at all. The hold also vetoes the
     * upload of the id for ever, and the unchanged-skip fast path with it.
     *
     * Same two-evidence rule as [sweepStaleDeletionHolds], and for the same reason: a hold survives
     * if either the mapping or the provider says the phone still has the event, and a failed look
     * keeps every hold. Dropping a hold is not destructive, but it does discard a question the user
     * has not answered, so it must only happen on positive evidence that there is nothing to ask about.
     */
    private suspend fun sweepStaleConflictHolds(account: Account, provider: ContentProviderClient) {
        val held = EventConflictGuard.heldIds(context, account.name)
        if (held.isEmpty()) return
        val claims = mutableListOf<MappedRow>()
        for (chunk in held.chunked(400)) claims.addAll(calendarEventSyncDao.mappedRows(account.name, chunk))
        val liveRows = existingEventRowIds(provider, account, claims.map { it.androidEventId }.toSet())
            ?: return
        val stillMapped = claims.filter { it.androidEventId in liveRows }.map { it.protonEventId }.toSet()
        val unmapped = held - stillMapped
        val stillOnPhone =
            if (unmapped.isEmpty()) emptySet() else protonIdsOnPhone(account, provider, unmapped) ?: held
        val sweep = sweepHolds(held, stillMapped, stillOnPhone)
        if (sweep.stale.isEmpty()) return
        val dropped = EventConflictGuard.prune(context, account.name, sweep.remaining)
        for (protonId in dropped) {
            SyncNotificationHelper.cancelEventConflict(context, account.name, protonId)
        }
        SyncLog.w(
            TAG,
            "Dropped ${dropped.size} collision prompt(s) for ${account.name}: the event is gone from this " +
                "phone, so there is no version left to choose (${sweep.remaining.size} still held)"
        )
    }

    /**
     * Which of [androidEventIds] still exist as rows, or null when the provider could not be read.
     * An empty input is not a question worth asking, so it answers with an empty set.
     */
    private fun existingEventRowIds(
        provider: ContentProviderClient,
        account: Account,
        androidEventIds: Set<Long>
    ): Set<Long>? {
        if (androidEventIds.isEmpty()) return emptySet()
        val found = mutableSetOf<Long>()
        try {
            for (chunk in androidEventIds.chunked(400)) {
                val cursor = provider.query(
                    SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                    arrayOf(CalendarContract.Events._ID),
                    "${CalendarContract.Events._ID} IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map { it.toString() }.toTypedArray(),
                    null
                ) ?: return null
                cursor.use { c -> while (c.moveToNext()) found.add(c.getLong(0)) }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not check which mapped event rows still exist: ${e.message}")
            return null
        }
        return found
    }

    /**
     * Which of [protonIds] a row in one of this account's calendars still carries in `_SYNC_ID`.
     *
     * Asked of the provider rather than of the mapping table, because a sync that died between
     * inserting a row and saving its mapping leaves a row with a Proton id and no mapping, and a
     * "Delete from phone" would still have that row to remove. Read as the sync adapter, so a
     * tombstone waiting to be uploaded counts as present for the same reason. Only `_SYNC_ID` is
     * matched, never ORIGINAL_SYNC_ID: that column carries the *parent's* id on an override row, and
     * a child alone is not what the user is being asked about.
     *
     * Null means the provider could not be read at all, which the caller treats as "keep every hold".
     */
    private fun protonIdsOnPhone(
        account: Account,
        provider: ContentProviderClient,
        protonIds: Set<String>
    ): Set<String>? {
        val found = mutableSetOf<String>()
        try {
            val calIds = mutableListOf<Long>()
            val calCursor = provider.query(
                SyncUtils.asCallerSyncAdapter(CalendarContract.Calendars.CONTENT_URI, account),
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(account.name, account.type),
                null
            ) ?: return null
            calCursor.use { c -> while (c.moveToNext()) calIds.add(c.getLong(0)) }
            if (calIds.isEmpty()) return emptySet()
            val calPlaceholders = calIds.joinToString(",") { "?" }
            val calArgs = calIds.map { it.toString() }
            for (chunk in protonIds.chunked(200)) {
                val idPlaceholders = chunk.joinToString(",") { "?" }
                val cursor = provider.query(
                    SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                    arrayOf(CalendarContract.Events._SYNC_ID),
                    "${CalendarContract.Events.CALENDAR_ID} IN ($calPlaceholders) AND " +
                        "${CalendarContract.Events._SYNC_ID} IN ($idPlaceholders)",
                    (calArgs + chunk).toTypedArray(),
                    null
                ) ?: return null
                cursor.use { c -> while (c.moveToNext()) c.getString(0)?.let { found.add(it) } }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not check which held deletions still have a local row: ${e.message}")
            return null
        }
        return found
    }

    /**
     * Persist this calendar's CalendarModelEventID so the next sync can unchanged-skip it.
     *
     * Deliberately conservative in three ways, each guarding against silently absorbing a change:
     *  - partial fetch failures this run leave the cursor untouched, so the next sync re-fetches
     *    the gap instead of believing the calendar is up to date;
     *  - no pre-run baseline (the opening modelevents read failed) likewise leaves it, since we
     *    cannot tell whether anything landed mid-run;
     *  - if the counter advanced DURING the run, save the id we STARTED from so the next sync sees
     *    "changed" and sweeps once more. Bounded by a re-check chain limit, because Proton's
     *    background indexing keeps advancing the counter for a while after a large import and that
     *    noise is indistinguishable from a real edit landing after our fetch.
     */
    private suspend fun persistModelEventId(
        prefs: android.content.SharedPreferences,
        account: Account,
        calInfo: CalendarInfo,
        calDisplayName: String,
        calMapping: CalendarSyncEntity?,
        currentModelEventId: String?,
        fetchHadFailures: Boolean,
        backfilledReminders: Int
    ) {
        // Save model event ID so next sync can skip unchanged calendars.
        // Re-read AFTER all work is done — captures server-side bumps that happened
        // during our sync run (Proton's background indexing of recent imports keeps
        // advancing the counter even when no user-visible events change). Without
        // this re-read, every scheduled poll runs a full ~4-minute metadata sweep
        // just to discover nothing changed (v1.4.16 fix). Race window: an event
        // created on Proton between our metadata fetch and this final read could be
        // missed; the next genuine change resets the chain.
        if (calMapping != null && fetchHadFailures) {
            // Partial fetch failures this run — leave lastModelEventId as-is so the next sync does
            // NOT unchanged-skip and will re-fetch the events we dropped.
            SyncLog.i(TAG, "Not saving modelEventId for '$calDisplayName' (partial fetch failures) - next sync re-fetches")
        } else if (calMapping != null && currentModelEventId == null) {
            // No pre-run baseline (the modelevents read at the top of this function failed), so we
            // cannot tell whether anything landed mid-run. Saving the end-of-run id could absorb a
            // concurrent edit, so leave the cursor untouched and let the next sync re-check —
            // same conservative stance as the partial-fetch-failure branch above.
            SyncLog.i(TAG, "Not saving modelEventId for '$calDisplayName' (no pre-run baseline) - next sync re-checks")
        } else if (calMapping != null) {
            val preRunId = currentModelEventId
            val finalModelEventId = try {
                val resp = apiClient.getCalendarModelEventLatest(account.name, calInfo.id)
                if (resp.isSuccessful) resp.body()?.get("CalendarModelEventID") as? String else null
            } catch (e: Exception) {
                SyncLog.d(TAG, "Final modelevents/latest read failed for ${calInfo.id}: ${e.message}")
                null
            } ?: preRunId
            if (finalModelEventId != null && preRunId != null) {
                // v1.6.23 absorption-race fix: if the counter advanced DURING this run, something may
                // have landed after our fetch (e.g. another member's edit on a shared calendar).
                // Saving the final id would absorb that change silently — every later poll would log
                // "unchanged, skipping" and the edit would never sync (field-reported). Save the id we
                // STARTED from instead, so the next sync sees "changed" and sweeps once more.
                //
                // Bounded on purpose: Proton's background indexing can keep advancing the counter for
                // a while after a large import (the very reason the end-of-run re-read exists,
                // v1.4.16), and "the sweep applied nothing" cannot distinguish that noise from an edit
                // that landed after our fetch. So cap how many times in a row one calendar may
                // re-sweep; beyond the cap we accept the current id and stop. Any genuine later change
                // bumps the counter again and is picked up normally.
                val chainKey = "modelevent_recheck_chain_${account.name}_${calInfo.id}"
                val chain = prefs.getInt(chainKey, 0)
                val idToSave = when {
                    finalModelEventId == preRunId -> {
                        if (chain != 0) prefs.edit().remove(chainKey).apply()
                        finalModelEventId
                    }
                    chain < SyncConstants.CALENDAR_MODELEVENT_RECHECK_CHAIN_LIMIT -> {
                        prefs.edit().putInt(chainKey, chain + 1).apply()
                        SyncLog.i(TAG, "modelEventId advanced during sync of '$calDisplayName' " +
                            "(${preRunId.take(12)}… → ${finalModelEventId.take(12)}…) - saving pre-run id so " +
                            "the next sync re-checks (${chain + 1}/${SyncConstants.CALENDAR_MODELEVENT_RECHECK_CHAIN_LIMIT})")
                        preRunId
                    }
                    else -> {
                        prefs.edit().remove(chainKey).apply()
                        SyncLog.w(TAG, "modelEventId kept advancing across " +
                            "${SyncConstants.CALENDAR_MODELEVENT_RECHECK_CHAIN_LIMIT} consecutive syncs of '$calDisplayName' " +
                            "(server-side churn) - accepting current id to stop re-sweeping")
                        finalModelEventId
                    }
                }
                calendarSyncDao.upsert(calMapping.copy(lastModelEventId = idToSave))
                if (backfilledReminders > 0) {
                    SyncLog.i(TAG, "'$calDisplayName': added the calendar's default reminders to $backfilledReminders previously-synced event(s)")
                }
                SyncLog.d(TAG, "Saved modelEventId for '$calDisplayName'")
            }
        }
    }

    /** Per-calendar lookups computed once, so the apply loop needn't re-query them per event. */
    private class EventApplyContext(
        val userAddressEmails: Set<String>,
        val calendarMemberEmail: String?,
        val masterIdByUid: Map<String, String>,
        val calendarSettings: CalendarSettingsData?,
        val locallyDirtyIds: Set<Long>,
        /** Null when the Reminders table could not be read: nothing is backfilled then ([needsReminderBackfill]). */
        val eventIdsWithReminders: Set<Long>?,
        /** False when [locallyDirtyIds] stands in for a read that failed: nothing is re-timed then ([isRetimeDue]). */
        val dirtyReadOk: Boolean = true
    )

    /**
     * Decrypt, map and write every fetched event into CalendarContract.
     *
     * This is where the download side's data-loss protections live, and all of them are
     * load-bearing:
     *  - the `existing.lastModified >= event.lastEditTime` skip gate, which is why unchanged
     *    events are cheap — several features had to work around it rather than remove it;
     *  - the [EventConflictGuard] hold / "keep mine" / fingerprint classification, which is what
     *    stops a newer server copy from silently destroying an unsent local edit;
     *  - `preserveDirty`, which keeps a pending personal-part (reminder or colour) edit
     *    uploadable after the server's shared fields have been applied;
     *  - one `applyBatch` per event covering the event row, its reminders and its attendees. A
     *    single rejected column fails the WHOLE batch, so never add an Events column here without
     *    first checking it is writable on UPDATE (this is how SELF_ATTENDEE_STATUS silently voided
     *    every event update for ten releases).
     *
     * Split out of syncCalendarEvents for the coroutine state-machine ceiling. The loop moved
     * verbatim, so every `continue` still refers to this same loop.
     *
     * @return how many previously-synced events were given the calendar's default reminders.
     */
    private suspend fun applyFetchedEvents(
        account: Account,
        provider: ContentProviderClient,
        calInfo: CalendarInfo,
        androidCalId: Long,
        calKeys: List<CalendarKey>,
        calPassphrases: List<CalendarPassphrase>,
        syncResult: SyncResult,
        isExternalCalendar: Boolean,
        calDisplayName: String,
        filteredEvents: List<CalendarEventDetail>,
        existingEventMappings: Map<String, CalendarEventSyncEntity>,
        ctx: EventApplyContext
    ): Int {
        val userAddressEmails = ctx.userAddressEmails
        val calendarMemberEmail = ctx.calendarMemberEmail
        val masterIdByUid = ctx.masterIdByUid
        val calendarSettings = ctx.calendarSettings
        val locallyDirtyIds = ctx.locallyDirtyIds
        val eventIdsWithReminders = ctx.eventIdsWithReminders

        var backfilledReminders = 0

        var processedCount = 0
        for (event in filteredEvents) {
            processedCount++

            val existing = existingEventMappings[event.id]
            if (existing != null && existing.lastModified >= event.lastEditTime && !isRetimeDue(existing, ctx, provider)) {
                backfilledReminders += finishUnchangedEvent(
                    existing = existing,
                    event = event,
                    calendarSettings = calendarSettings,
                    eventIdsWithReminders = eventIdsWithReminders,
                    locallyDirtyIds = locallyDirtyIds,
                    provider = provider,
                    account = account,
                    calInfo = calInfo
                )
                continue
            }

            // Past this point the server copy is newer than ours. If the local row ALSO has unsent
            // edits, applying the server version would silently destroy them.
            var preserveLocalDirty = false
            val collision = classifyLocalCollision(
                account = account,
                provider = provider,
                calInfo = calInfo,
                calDisplayName = calDisplayName,
                event = event,
                existing = existing,
                locallyDirtyIds = locallyDirtyIds
            )
            if (collision == LocalCollision.SKIP_EVENT) continue
            if (collision == LocalCollision.PRESERVE_LOCAL_DIRTY) preserveLocalDirty = true

            // Orphan reclaim: with no Room mapping for this Proton event, a row may still
            // exist in CalendarContract carrying _SYNC_ID == event.id — e.g. the mapping was
            // cleared (forced resync / DB reset) while the OS calendar row survived, or a
            // prior sync threw between the row insert and the mapping upsert. Matching is
            // otherwise mapping-only, so without adopting that row we would INSERT a second
            // copy (the duplicate-event bug, worst on subscribed/custom-window calendars).
            // Reclaim it → UPDATE in place and re-map below instead of duplicating.
            val reclaimedId = if (existing == null) findLocalEventBySyncId(provider, androidCalId, event.id) else null
            if (reclaimedId != null) {
                SyncLog.i(TAG, "Reclaimed orphan local row $reclaimedId for event ${event.id} ('$calDisplayName') - updating in place instead of inserting a duplicate")
            }
            val effectiveExistingId = existing?.androidEventId ?: reclaimedId

            try {
                val downloaded = decryptAndParseVEvent(
                    account = account,
                    event = event,
                    calKeys = calKeys,
                    calPassphrases = calPassphrases,
                    isExternalCalendar = isExternalCalendar,
                    calDisplayName = calDisplayName
                ) ?: continue
                val vEvent = downloaded.vEvent

                // Exception/override handling. A single-occurrence override carries a RECURRENCE-ID
                // and shares its recurring master's UID; the master is the same-UID event that carries
                // an RRULE (an override never does). Record masters as we go, and link an override to
                // the master's Proton id (its _SYNC_ID). Matching by UID — NOT "the first other
                // mapping" — is what makes the override attach to the right series instead of an
                // arbitrary event.
                val recurrenceId = vEvent.getProperty<net.fortuna.ical4j.model.property.RecurrenceId>("RECURRENCE-ID")
                val eventUid = vEvent.uid?.value ?: event.uid
                val parentSyncId = if (recurrenceId != null) {
                    // Master from the cleartext pre-pass (covers every master in this fetch), else an
                    // earlier-synced master still in the DB (same UID + has an RRULE). Matched by UID,
                    // never arbitrarily — this is what attaches the override to its own series.
                    masterIdByUid[eventUid]
                        ?: calendarEventSyncDao.getAllByCalendar(account.name, calInfo.id)
                            .firstOrNull { it.cachedUid == eventUid && it.cachedRrule != null && it.cachedRecurrenceId == null && it.protonEventId != event.id }
                            ?.protonEventId
                } else null
                if (recurrenceId != null) {
                    SyncLog.d(TAG, "Exception event id=${event.id} uid=$eventUid RECURRENCE-ID=${recurrenceId.value} ('$calDisplayName'): parentSyncId=${parentSyncId ?: "<unresolved>"}${if (parentSyncId == null) " - recurring master not found (outside window or not yet synced); inserting UNLINKED" else ""}")
                }

                val androidEventId = writeFetchedEventRows(
                    provider = provider,
                    account = account,
                    calInfo = calInfo,
                    androidCalId = androidCalId,
                    calDisplayName = calDisplayName,
                    event = event,
                    downloaded = downloaded,
                    effectiveExistingId = effectiveExistingId,
                    parentSyncId = parentSyncId,
                    userAddressEmails = userAddressEmails,
                    calendarMemberEmail = calendarMemberEmail,
                    calendarSettings = calendarSettings,
                    locallyDirtyIds = locallyDirtyIds,
                    preserveLocalDirty = preserveLocalDirty,
                    processedCount = processedCount
                )

                calendarEventSyncDao.upsert(
                    buildFetchedMapping(
                        account = account,
                        calInfo = calInfo,
                        event = event,
                        downloaded = downloaded,
                        eventUid = eventUid,
                        recurrenceId = recurrenceId,
                        provider = provider,
                        androidEventId = androidEventId
                    )
                )

                synchronized(syncResult.stats) { syncResult.stats.numInserts++ }
                if (processedCount % 50 == 0 || processedCount == filteredEvents.size) {
                    SyncLog.i(TAG, "'$calDisplayName': decrypted $processedCount/${filteredEvents.size} events")
                    SyncNotificationHelper.updateProgress(
                        context, account.name, "calendar",
                        "decrypting", processedCount, filteredEvents.size
                    )
                }
            } catch (e: NotImplementedError) {
                SyncLog.w(TAG, "Crypto not implemented, skipping event ${event.id}")
            } catch (e: Exception) {
                SyncLog.e(TAG, "Failed to sync event id=${event.id} uid=${event.uid} ('$calDisplayName')", e)
                synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ }
            }
        }

        return backfilledReminders
    }

    /**
     * Whether the server's version may be applied over a local row that is also dirty.
     *
     * This is the guard that keeps a two-sided edit from silently losing one side: a held event and a
     * keep-mine event are skipped, a personal-only change keeps its dirty flag, and a genuine collision
     * holds BOTH versions and asks.
     */
    private fun classifyLocalCollision(
        account: Account,
        provider: ContentProviderClient,
        calInfo: CalendarInfo,
        calDisplayName: String,
        event: CalendarEventDetail,
        existing: CalendarEventSyncEntity?,
        locallyDirtyIds: Set<Long>
    ): LocalCollision {
        val localRowId = existing?.androidEventId
        if (localRowId != null && localRowId in locallyDirtyIds) {
            if (EventConflictGuard.isHeld(context, account.name, event.id)) {
                // Already waiting on the user, so touch neither side. The prompt is re-posted only if
                // it is no longer on screen: a held event syncs in neither direction, so the prompt is
                // the only way out, and it goes away on its own at a reboot, at a swipe, and when the
                // account is renamed (its id carries the account name). Re-posting uses the same id, so
                // a prompt that is still standing is left exactly as it is rather than repeated.
                if (!SyncNotificationHelper.isEventConflictShowing(context, account.name, event.id)) {
                    SyncLog.i(TAG, "Collision prompt for event ${event.id} is no longer showing; posting it again")
                    notifyEventConflict(account, calInfo.id, event, localRowId, provider)
                }
                return LocalCollision.SKIP_EVENT
            }
            if (EventConflictGuard.isLocalWins(context, account.name, event.id)) {
                // User already chose "Keep mine" and the upload has not pushed it yet. Leave
                // the local row alone; applying the server's version here would overwrite the
                // very edit they just told us to keep, and re-running the collision check would
                // simply re-hold the event, which is why this marker exists.
                SyncLog.d(TAG, "Event ${event.id}: keeping the local version pending upload (user chose 'keep mine')")
                return LocalCollision.SKIP_EVENT
            }
            val currentFingerprint = EventConflictGuard.fingerprintLocalRow(provider, localRowId)
            when (EventConflictGuard.classify(existing.sharedFingerprint, currentFingerprint)) {
                EventConflictGuard.Resolution.PERSONAL_ONLY -> {
                    // Only reminders or colour changed locally. Those are this member's personal
                    // part and go via PUT .../events/{id}/personal, so they do not collide with
                    // anything the server did: apply the server's version but KEEP the dirty
                    // flag so the personal change still uploads afterwards.
                    return LocalCollision.PRESERVE_LOCAL_DIRTY
                }
                EventConflictGuard.Resolution.CONFLICT -> {
                    EventConflictGuard.hold(context, account.name, event.id)
                    SyncLog.w(
                        TAG,
                        "Collision on event ${event.id} ('$calDisplayName'): shared fields changed " +
                            "both locally and on the server. Holding BOTH versions and asking the user; " +
                            "this event is skipped by download and upload until answered."
                    )
                    notifyEventConflict(account, calInfo.id, event, localRowId, provider)
                    return LocalCollision.SKIP_EVENT
                }
            }
        }

        return LocalCollision.APPLY_SERVER
    }

    /** A downloaded event as parsed: ical4j's VEVENT, plus its times read from the text ical4j parsed. */
    private class DownloadedEvent(
        val vEvent: VEvent,
        /** Null when the text could not be read with certainty: the event keeps ical4j's times, as before 1.11.5. */
        val times: EventTimeColumns?,
        /** The RECURRENCE-ID line exactly as Proton sent it, when ical4j saw one too: cached_recurrence_id. */
        val recurrenceIdLine: String?
    )

    /** The event's VEVENT, decrypted or rebuilt for an external calendar; null means skip this event. */
    private fun decryptAndParseVEvent(
        account: Account,
        event: CalendarEventDetail,
        calKeys: List<CalendarKey>,
        calPassphrases: List<CalendarPassphrase>,
        isExternalCalendar: Boolean,
        calDisplayName: String
    ): DownloadedEvent? {
        // Try to decrypt/extract iCal data
        var iCalString: String
        try {
            iCalString = calendarDecryptor.decryptEvent(
                account.name, event, calKeys, calPassphrases
            )
        } catch (e: Exception) {
            if (!isExternalCalendar) throw e
            iCalString = buildExternalEventICal(event)
        }

        // Parse iCal — fall back to API metadata for unparseable external events
        val parsed = parseEventICal(event, iCalString, isExternalCalendar)
        val vEvent = parsed.calendar.getComponents<VEvent>("VEVENT").firstOrNull()
        if (vEvent == null) {
            SyncLog.w(TAG, "No VEVENT in parsed iCal - dropping event id=${event.id} uid=${event.uid} ('$calDisplayName'); iCal shape: ${iCalShape(iCalString)}")
            return null
        }

        // The times are read again from the very text ical4j just parsed, never from ical4j's values.
        val raw = RawEventTimes.scan(parsed.text)
        val hasRecurrenceId = vEvent.getProperty<net.fortuna.ical4j.model.property.RecurrenceId>("RECURRENCE-ID") != null
        return DownloadedEvent(
            vEvent = vEvent,
            times = downloadTimesFor(event, raw, vEvent),
            recurrenceIdLine = if (hasRecurrenceId) raw?.recurrenceId?.text else null
        )
    }

    /**
     * The event's time columns computed with java.time from the raw text ([DownloadTimes]) and this run's
     * rules ([zoneRules]), or null to keep ical4j's own values for this one event, as before 1.11.5. Logged
     * with the event id and the reason (a property name or a TZID), never with event content.
     */
    private fun downloadTimesFor(event: CalendarEventDetail, raw: RawEventTimes?, vEvent: VEvent): EventTimeColumns? = try {
        if (raw == null) throw DownloadTimes.Unevaluable("no VEVENT in the text")
        if (!DownloadTimes.agreesWithIcal4j(raw, vEvent)) throw DownloadTimes.Unevaluable("the text and ical4j disagree")
        DownloadTimes.compute(
            raw = raw,
            rules = zoneRules,
            deviceZone = java.time.ZoneId.systemDefault(),
            periodRdate = vEvent.getProperty<net.fortuna.ical4j.model.property.RDate>("RDATE")?.value
        )
    } catch (e: Exception) {
        SyncLog.w(TAG, "Event ${event.id}: times kept as ical4j computes them (${e.message})")
        null
    }

    /**
     * Whether an event the server has not changed must be downloaded again anyway: the re-time pass marked
     * it ([EventRetimePlanner.RETIME_TAG]) because its stored times were computed with other rules than this
     * run's. Only while the local row is clean AND still holds the last agreed state: a row with unsent edits
     * waits until its upload has gone, so the collision handling never sees a change Proton did not make, and a
     * row whose shared fields differ from its baseline keeps them. That is the planner's LEAVE_LOCAL_CHANGE rule,
     * checked again here because the tag outlives the upload: the give-up paths clear DIRTY while deliberately
     * keeping the edit on the phone (a refused update, the third strike), and re-applying Proton's version then
     * would destroy it. Nothing is re-applied when the calendar's dirty rows could not be read, and the row is
     * read afresh, so a failed batch read cannot make a dirty row look clean.
     */
    private fun isRetimeDue(existing: CalendarEventSyncEntity, ctx: EventApplyContext, provider: ContentProviderClient): Boolean {
        if (existing.syncTag != EventRetimePlanner.RETIME_TAG) return false
        val localId = existing.androidEventId ?: return false
        if (!ctx.dirtyReadOk || localId in ctx.locallyDirtyIds) return false
        val state = EventRetimePass.readRowState(provider, localId) ?: return false
        if (state.dirty || state.deleted) return false
        val baseline = existing.sharedFingerprint
        if (baseline != null && state.fingerprint != baseline) {
            SyncLog.d(TAG, "Event ${existing.protonEventId} keeps its stored times: its shared fields differ from the last synced state")
            return false
        }
        SyncLog.d(TAG, "Re-timing event ${existing.protonEventId}")
        return true
    }

    /** An unchanged event still needs its inherited reminders backfilled and its guest baseline recorded. */
    private fun finishUnchangedEvent(
        existing: CalendarEventSyncEntity,
        event: CalendarEventDetail,
        calendarSettings: CalendarSettingsData?,
        eventIdsWithReminders: Set<Long>?,
        locallyDirtyIds: Set<Long>,
        provider: ContentProviderClient,
        account: Account,
        calInfo: CalendarInfo
    ): Int {
        var backfilledReminders = 0
        // Unchanged, so nothing to rewrite. One exception: an event that inherits the
        // calendar's default reminders has never received them, because this gate has
        // always skipped it. Give it them once, touching only the reminder rows.
        //
        // Never do this to an event with unsent local edits. "No reminder rows" is also
        // exactly what "the user just deleted every reminder" looks like, and this runs in
        // the download phase — before uploadDirtyEvents — so backfilling here would restore
        // the rows the user removed and then upload them straight back, making it
        // impossible to clear an event's reminders. Same hazard as the download-clobbers-
        // dirty collision case, which this branch sits above and therefore misses.
        val localId = existing.androidEventId
        // Honour all three Notifications states; only `null` means "inherit".
        //   null  -> inherit the calendar defaults
        //   []    -> explicitly no reminders; back-fill nothing
        //   [...] -> exactly these
        // NOTE (measured 2026-07-26): Proton does not actually round-trip `[]`. We send it,
        // the personal-part endpoint echoes it back, but the events feed then reports
        // `Notifications: null`, i.e. indistinguishable from "inherit". So this branch is
        // currently defensive rather than load-bearing, and clearing every reminder on an
        // event still cannot be made to stick through the server alone.
        //
        // And never on a run whose read of the Reminders table failed (null): that read says nothing
        // about which events lack reminders, so it must not stand in for "none of them has any".
        val toBackfill = event.notifications
            ?: defaultNotificationsFor(calendarSettings, event.fullDay == 1)
        if (localId != null && !toBackfill.isNullOrEmpty() &&
            needsReminderBackfill(localId, eventIdsWithReminders, locallyDirtyIds)
        ) {
            backfillMissingReminders(provider, account, localId, toBackfill)
            backfilledReminders++
        }
        // Never for a row with unsent edits: its guest list may be the user's, not Proton's.
        if (localId != null && localId !in locallyDirtyIds) {
            recordGuestBaseline(provider, account, calInfo, event, localId, onlyIfAbsent = true)
        }
        return backfilledReminders
    }

    /** The mapping row a fetched event leaves behind, cache fields and all. */
    private fun buildFetchedMapping(
        account: Account,
        calInfo: CalendarInfo,
        event: CalendarEventDetail,
        downloaded: DownloadedEvent,
        eventUid: String,
        recurrenceId: net.fortuna.ical4j.model.property.RecurrenceId?,
        provider: ContentProviderClient,
        androidEventId: Long
    ): CalendarEventSyncEntity {
        val vEvent = downloaded.vEvent
        // Extract SEQUENCE from iCal for smart increment on future uploads
        val serverSequence = vEvent.getProperty<net.fortuna.ical4j.model.property.Sequence>("SEQUENCE")
            ?.sequenceNo ?: 0

        // Cache the server fields uploadModifiedEvent normally re-fetches via
        // GET /calendar/events/{id}. With these populated we can skip that GET
        // for organizer-owned events on the next upload.
        val cache = extractEventCacheFields(event)
        val baseline = rowBaseline(provider, androidEventId)

        return CalendarEventSyncEntity(
            accountName = account.name,
            protonCalendarId = calInfo.id,
            protonEventId = event.id,
            androidEventId = androidEventId,
            lastModified = event.lastEditTime,
            sequence = serverSequence,
            cachedSharedKeyPacket = cache.sharedKeyPacket,
            cachedDtStartLine = cache.dtStartLine,
            cachedDtEndLine = cache.dtEndLine,
            // Recurrence-resolution fields come from the fully-decrypted vEvent so
            // subscribed/external masters (whose RRULE is NOT in cleartext type-2) are
            // cached correctly and resolve via the DB fallback on later syncs. cache.*
            // still feeds SEQUENCE / upload diffing for organizer-owned personal events.
            cachedRrule = vEvent.getProperty<net.fortuna.ical4j.model.property.RRule>("RRULE")
                ?.value?.replace("\r", "")?.trim() ?: cache.rrule,
            cachedUid = eventUid.takeIf { it.isNotBlank() } ?: cache.uid,
            cachedIsOrganizer = cache.isOrganizer,
            // The whole line as Proton sent it (the shape the upload stores too), never ical4j's re-formatted
            // value, which drops the TZID and is wrong wherever ical4j misreads the zone. Only null-checked so
            // far; the re-time pass reads it. Null exactly when ical4j saw no RECURRENCE-ID, as before.
            cachedRecurrenceId = recurrenceId?.let { downloaded.recurrenceIdLine ?: it.value } ?: cache.recurrenceId,
            // Baseline for collision detection: the shared fields as they stand now,
            // which is the moment both sides agree. A later local edit that leaves this
            // unchanged was a personal-part change (reminder or colour) and is NOT a
            // collision. Costs one query per event we actually wrote, not per event
            // fetched, since unchanged events never reach here.
            sharedFingerprint = baseline.first,
            // And the time columns just written, which cached_dtstart_line/cached_dtend_line are Proton's text
            // for: while they stay so, the upload sends that text back (UploadTimes).
            cachedTimes = baseline.second
        )
    }

    /**
     * A row's baseline in one read: its shared fingerprint (EventConflictGuard) and the record of its time
     * columns (SyncedTimes); nulls when the row cannot be read, which every reader treats as "not known".
     */
    private fun rowBaseline(provider: ContentProviderClient, androidEventId: Long): Pair<String?, String?> {
        val values = EventConflictGuard.readSharedValues(provider, androidEventId) ?: return null to null
        return EventConflictGuard.fingerprintOf(values) to SyncedTimes.encode(values)
    }

    /** Writes one fetched event to the provider: its row, reminders, attendees, overrides and guest baseline. */
    private fun writeFetchedEventRows(
        provider: ContentProviderClient,
        account: Account,
        calInfo: CalendarInfo,
        androidCalId: Long,
        calDisplayName: String,
        event: CalendarEventDetail,
        downloaded: DownloadedEvent,
        effectiveExistingId: Long?,
        parentSyncId: String?,
        userAddressEmails: Set<String>,
        calendarMemberEmail: String?,
        calendarSettings: CalendarSettingsData?,
        locallyDirtyIds: Set<Long>,
        preserveLocalDirty: Boolean,
        processedCount: Int
    ): Long {
        val vEvent = downloaded.vEvent
        // Proton keeps each attendee's RSVP in the cleartext Attendees[] array,
        // keyed by x-pm-token — the encrypted iCal PARTSTAT is frozen at creation
        // time and never reflects responses. Hand the real statuses to the mapper so a
        // received-and-accepted invite shows correctly (incl. SELF_ATTENDEE_STATUS).
        val attendeeStatusByToken = event.effectiveAttendees
            ?.associate { it.token.lowercase() to it.status }
            ?: emptyMap()

        // Map to Android calendar event
        val ops = CalendarMapper.mapToAndroidOps(
            vEvent = vEvent,
            account = account,
            androidCalendarId = androidCalId,
            existingEventId = effectiveExistingId,
            protonEventId = event.id,
            parentSyncId = parentSyncId,
            userAddressEmails = userAddressEmails,
            calendarMemberEmail = calendarMemberEmail,
            protonAttendeeStatusByToken = attendeeStatusByToken,
            // Per-event colour lives only in this API field, never in the iCal.
            protonEventColor = event.color,
            // Cleartext, so it survives a failed decrypt of the AttendeesEvents part and
            // can be used to tell "genuinely no guests" from "we failed to read them".
            protonHasAttendees = !event.effectiveAttendees.isNullOrEmpty(),
            // Reminders live ONLY in this API field. Proton does not emit VALARMs, so
            // without this the phone never sees a reminder set in Proton. Only `null` means
            // "inherit the calendar's defaults" — an empty list is Proton storing
            // "explicitly no reminders", and substituting the defaults for it put the
            // reminders straight back on the next sync after the user cleared them.
            protonNotifications = event.notifications
                ?: defaultNotificationsFor(calendarSettings, event.fullDay == 1),
            // Personal-part-only local change: leave DIRTY alone so the pending reminder or
            // colour edit still uploads after we apply the server's shared fields.
            preserveDirty = preserveLocalDirty,
            // Every time column from the raw text with java.time (null: ical4j's, as before 1.11.5).
            times = downloaded.times
        )

        val results = provider.applyBatch(ops)

        // effectiveExistingId is non-null for both a mapped event and a reclaimed
        // orphan (both are UPDATEs); only a genuine new event parses the insert URI.
        val androidEventId = effectiveExistingId ?: ContentUris.parseId(results[0].uri!!)

        // A fresh INSERT that carried SELF_ATTENDEE_STATUS leaves the event with TWO rows
        // for the user's own address: the one we just inserted from Proton's attendee list,
        // and the one AOSP's CalendarProvider2 fabricates for OWNER_ACCOUNT. Collapse them.
        if (effectiveExistingId == null) {
            dedupeAttendeeRows(provider, account, androidEventId)
        }

        if (processedCount == 1) {
            dumpEditabilityDiagnostic(provider, account, androidCalId, calInfo, calDisplayName, "post-write")
        }

        // Samsung's CalendarProvider ignores the EXDATE column — create cancelled
        // exception events for each EXDATE so all calendar apps hide excluded occurrences.
        val hasRrule = vEvent.getProperty<net.fortuna.ical4j.model.property.RRule>("RRULE") != null
        val hasExdates = vEvent.getProperties<net.fortuna.ical4j.model.property.ExDate>("EXDATE")?.isNotEmpty() == true
        if (hasRrule && hasExdates) {
            try {
                // Clear any prior cancelled EXDATE exception rows for this master first.
                // These rows never get a Room mapping, so on every re-fetch of a changed
                // master they would otherwise STACK (invisible STATUS_CANCELED rows that
                // inflate the local-vs-mapping count). Delete-then-reinsert keeps exactly
                // one set; harmless on a fresh insert (nothing matches).
                //
                // ONLY the rows this app wrote, which are the ones with DIRTY = 0. A cancelled
                // occurrence the USER deleted is the same shape of row and is the only record of that
                // deletion until it is uploaded; this is a hard delete through the sync-adapter uri, so
                // purging it destroyed the deletion outright and the occurrence came back on the phone.
                //
                // And only rows WITHOUT a _SYNC_ID: the placeholders never carry one, but a Proton single
                // edit with STATUS:CANCELLED is written in exactly this shape (CalendarMapper: STATUS_CANCELED,
                // ORIGINAL_SYNC_ID = the series, DIRTY = 0) and does, with a mapping of its own. Purging it
                // left a mapping without a row, which reconcileDeletedEvents then sent to Proton as a deletion:
                // the cancelled occurrence came back everywhere. The re-time pass re-applies series Proton did
                // not change, so this could fire with nobody having touched anything.
                val purged = provider.delete(
                    SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                    "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.ORIGINAL_SYNC_ID} = ? AND " +
                        "${CalendarContract.Events.STATUS} = ? AND ${CalendarContract.Events.DIRTY} = 0 AND " +
                        "${CalendarContract.Events._SYNC_ID} IS NULL",
                    arrayOf(androidCalId.toString(), event.id, CalendarContract.Events.STATUS_CANCELED.toString())
                )
                if (purged > 0) SyncLog.d(TAG, "Cleared $purged stale EXDATE exception row(s) for master ${event.id} before re-inserting")
                val exOps = CalendarMapper.buildExdateExceptionOps(
                    vEvent, account, androidCalId, androidEventId, event.id, downloaded.times
                )
                if (exOps.isNotEmpty()) {
                    provider.applyBatch(exOps)
                }
            } catch (e: Exception) {
                SyncLog.w(TAG, "Failed to create EXDATE exceptions for event ${event.id}: ${e.message}")
            }
        }

        // Not for a row with unsent guest edits: its rows may be the user's, not Proton's (the mapper
        // leaves them alone when Proton's cleartext list cannot be trusted).
        if (androidEventId !in locallyDirtyIds) {
            recordGuestBaseline(provider, account, calInfo, event, androidEventId, onlyIfAbsent = false)
        }

        return androidEventId
    }

    /** Outcome of one calendar's event fetch, whichever path produced it. */
    private class CalendarFetchResult(
        /** The events whose bodies we actually fetched this run, to decrypt and upsert. */
        val events: List<CalendarEventDetail>,
        /**
         * Every event id the server knows about — NOT only those whose GET succeeded. The
         * server→local deletion pass is driven from this, so a transient per-event failure must
         * never drop an id here, or a still-present event would be deleted locally.
         */
        val serverEventIds: MutableSet<String>,
        /**
         * True when some per-event GET failed. The caller then leaves the model-event-id unsaved,
         * so the next sync won't unchanged-skip and re-fetches the gap, and defers the deletion
         * pass rather than purging events this run may simply have missed.
         */
        val hadFailures: Boolean
    )

    /**
     * FLAT / SPARSE fetch path: page /events/ids with cursor pagination, then parallel-GET each
     * event by id under a semaphore.
     *
     * Window-free and complete by construction, so its id set is authoritative. Taken by "All" mode
     * always, and by bounded ranges whose event count sits under the break-even in
     * [CalendarFetchPlan.sparseThreshold].
     *
     * Returns null when the id listing itself fails — the caller must abort the sync, because a
     * partial id set would make the deletion pass purge events that still exist on the server.
     */
    private suspend fun fetchEventsFlat(
        account: Account,
        calInfo: CalendarInfo,
        calDisplayName: String,
        totalEventCount: Int,
        plan: CalendarFetchPlan
    ): CalendarFetchResult? {
        val syncMode = plan.syncMode
        val sparseThreshold = plan.sparseThreshold
        val rangeWindows = plan.rangeWindows
        val filteredEvents: List<CalendarEventDetail>
        val serverEventIds: MutableSet<String>
        var fetchHadFailures = false

            // ---- FLAT / SPARSE PATH ----
            // 1) Page through /events/ids with cursor pagination
            // 2) Parallel-fetch each event by ID with a concurrency cap
            // 3) No client-side date filter — compare to existingEventMappings
            val flatReason = if (syncMode == SyncPreferences.SYNC_MODE_ALL)
                "All-mode flat full-fetch ($totalEventCount events)"
            else
                "sparse ($totalEventCount events ≤ threshold $sparseThreshold for $rangeWindows window(s))"
            SyncLog.i(TAG, "'$calDisplayName': $flatReason - using /events/ids + per-event GET")
            val allIds = mutableListOf<String>()
            var afterId: String? = null
            while (true) {
                val resp = try {
                    apiClient.getCalendarEventIds(account.name, calInfo.id, limit = 100, afterId = afterId)
                } catch (e: Exception) {
                    SyncLog.e(TAG, "Failed to fetch event IDs for '$calDisplayName': ${e.message}, aborting sync")
                    return null
                }
                if (!resp.isSuccessful) {
                    SyncLog.e(TAG, "Failed to fetch event IDs for '$calDisplayName': HTTP ${resp.code()}, aborting sync")
                    return null
                }
                @Suppress("UNCHECKED_CAST")
                val pageIds = resp.body()?.get("IDs") as? List<String> ?: emptyList()
                if (pageIds.isEmpty()) break
                allIds.addAll(pageIds)
                if (pageIds.size < 100) break
                afterId = pageIds.last()
            }
            if (allIds.size != totalEventCount) {
                SyncLog.w(TAG, "'$calDisplayName': /events/ids returned ${allIds.size} ids but server Total=$totalEventCount - MISMATCH")
            } else {
                SyncLog.d(TAG, "'$calDisplayName': /events/ids returned ${allIds.size} ids (matches server Total)")
            }

            // Parallel GETs, semaphore-bounded, chunked so we don't allocate one coroutine per id
            // up-front on large calendars and can report progress (this is the dominant phase now
            // that calendars up to ~rangeWindows×permits route here). A failed GET (incl. 429/5xx)
            // is counted and dropped from THIS round — its id stays in serverEventIds (so it's never
            // deleted) and we suppress the model-id save below so the next sync re-fetches it.
            // The All-mode full-fetch GETs every event (thousands), so it uses a higher concurrency
            // cap to beat the retired dense sweep; the count-gated sparse path stays at the proven-safe
            // 5 (it's small by definition and needn't risk the higher, less-proven rate).
            val sparsePermits = if (syncMode == SyncPreferences.SYNC_MODE_ALL)
                SyncConstants.CALENDAR_FULLFETCH_GET_PERMITS else SyncConstants.CALENDAR_PARALLEL_GET_PERMITS
            val sem = Semaphore(sparsePermits)
            val failedGets = java.util.concurrent.atomic.AtomicInteger(0)
            val fetched = mutableListOf<CalendarEventDetail>()
            var fetchedSoFar = 0
            for (idBatch in allIds.chunked(100)) {
                val batch = coroutineScope {
                    idBatch.map { id ->
                        async {
                            sem.withPermit {
                                try {
                                    val r = apiClient.getCalendarEvent(account.name, calInfo.id, id)
                                    if (r.isSuccessful) r.body()?.event
                                    else { failedGets.incrementAndGet(); null }
                                } catch (e: Exception) {
                                    SyncLog.w(TAG, "Failed to fetch event $id: ${e.message}")
                                    failedGets.incrementAndGet(); null
                                }
                            }
                        }
                    }.awaitAll()
                }
                fetched.addAll(batch.filterNotNull())
                fetchedSoFar += idBatch.size
                SyncNotificationHelper.updateProgress(context, account.name, "calendar", "downloading", fetchedSoFar, allIds.size)
            }
            if (failedGets.get() > 0) {
                fetchHadFailures = true
                SyncLog.w(TAG, "'$calDisplayName': sparse fetch had ${failedGets.get()} failed GET(s) - not saving model-event-id, so the next sync re-fetches the gap")
            }

            // No client-side date filtering. The seed event's startTime/endTime
            // describe only the FIRST occurrence; for recurring events with no UNTIL
            // a window-intersection check on the seed silently drops events whose
            // recurrences fall inside the window. Android's calendar provider expands
            // the RRULE locally and renders only occurrences in the user's visible
            // view, so over-syncing here is harmless. v1.4.11 fix.
            SyncLog.i(TAG, "'$calDisplayName': sparse path fetched ${fetched.size} events")

            filteredEvents = fetched
            // Authoritative server set = the ids /events/ids reported, NOT only those whose GET
            // succeeded. A transient per-event GET failure must not drop an id here, otherwise the
            // deletion pass would treat a still-present event as "gone from server" and remove it
            // locally. (filteredEvents stays = the events we actually fetched, to upsert this round.)
            serverEventIds = allIds.toMutableSet()

        return CalendarFetchResult(filteredEvents, serverEventIds, fetchHadFailures)
    }

    /**
     * DENSE fetch path: the chunked, windowed /events?Type= sweep across all 4 Type buckets.
     *
     * Kept for custom (bounded) ranges, where the small window count makes it efficient. Because
     * the sweep is window/Type/timezone-shaped it can under-fetch, so the deletion authority is
     * sourced separately from the all-time /events/ids list rather than from what the sweep saw.
     *
     * Returns null on any fetch error — the caller must abort, because proceeding with partial data
     * would mistakenly mark surviving server-side events as deleted.
     */
    private suspend fun fetchEventsDense(
        account: Account,
        calInfo: CalendarInfo,
        calDisplayName: String,
        plan: CalendarFetchPlan,
        existingEventMappings: Map<String, CalendarEventSyncEntity>
    ): CalendarFetchResult? {
        val syncMode = plan.syncMode
        val syncStart = plan.syncStart
        val syncEnd = plan.syncEnd
        val forceRefetch = plan.forceRefetch
        val filteredEvents: List<CalendarEventDetail>
        val serverEventIds: MutableSet<String>
        var fetchHadFailures = false
        var authoritativeServerIds: Set<String>? = null

            // ---- DENSE PATH (existing chunked logic) ----
            // Skip metadata check when there are no existing mappings (fresh sync / re-sync)
            // or when sync range changed (all out-of-range events need re-fetch anyway).
            val skipMetadata = existingEventMappings.isEmpty() || forceRefetch
            if (skipMetadata && existingEventMappings.isEmpty()) {
                SyncLog.i(TAG, "'$calDisplayName': no existing mappings, skipping metadata check - direct full fetch")
            } else if (skipMetadata) {
                SyncLog.i(TAG, "'$calDisplayName': sync range changed, skipping metadata check - direct full fetch")
            }

        // Proton's CalendarEventsQuery requires Start/End/Timezone/Type. Type partitions the
        // event space into 4 buckets (PartDayInsideWindow=0, PartDayBeforeWindow=1,
        // FullDayInsideWindow=2, FullDayBeforeWindow=3) — to retrieve every event in the
        // window we must fetch each bucket separately and merge. See
        // proton-calendar/.../getPaginatedEvents.ts. Timezone shapes which events fall into
        // the FullDay vs PartDay buckets, so we pass the device's local TZ.
        val queryTimezone = java.util.TimeZone.getDefault().id

        /**
         * Iteratively chunk through `[syncStart, syncEnd]` for one Type bucket.
         *
         * Outcome of one fetch attempt at a given chunk size:
         *   * Success → events are collected, window advances by chunkSize.
         *   * "Time window is too big" → halve `chunkSize` (shared across types via the
         *     enclosing closure) and retry the *same* window without advancing. The
         *     newly-discovered chunk size is then reused for every remaining window in
         *     this Type and for all subsequent Type buckets — no re-probing.
         *   * Any other HTTP error → abort the whole sync (caller returns null).
         *
         * This replaces v1.4.6's recursive halving (which re-probed on every subtree
         * and produced O(span/cap) leaf calls + a similar number of internal "too big"
         * probes — roughly 4× more API calls than necessary).
         */
        // Start at the empirically-known cap (90 days, measured 2026-04-30 — see
        // SyncConstants.CALENDAR_QUERY_INITIAL_CHUNK_DAYS). If the user's chosen
        // window is smaller than that we just send the whole thing in one chunk.
        // The halve-on-fail safety net below still kicks in if Proton ever tightens
        // the cap; in the common case no halving fires and no probe round-trips are
        // wasted on every sync.
        // Shared chunk-size state across the 4 parallel Type-bucket fetches. Reads
        // happen via [AtomicLong.get]; halving uses [compareAndSet] so two coroutines
        // simultaneously hitting "Time window is too big" can't double-halve — the
        // loser of the CAS just adopts the new value and retries. (@Volatile would
        // work for a class field but Kotlin disallows it on local vars, hence
        // AtomicLong.)
        val chunkSize = java.util.concurrent.atomic.AtomicLong(
            minOf(
                syncEnd - syncStart,
                SyncConstants.CALENDAR_QUERY_INITIAL_CHUNK_DAYS * SyncConstants.SECONDS_PER_DAY
            )
        )
        val chunkFloor = SyncConstants.CALENDAR_QUERY_MIN_SPAN_SECONDS

        // Progress tracking across all 4 Type buckets — used for log output and the
        // ongoing sync notification. v1.4.20: parallel fetch means these need to be
        // thread-safe. estimatedTotalChunks is the initial estimate; we don't recompute
        // on halving because computing "what's left" is fuzzy with parallel types and a
        // brief progress-bar overshoot is acceptable.
        fun estimateChunksPerType(currentChunk: Long): Int =
            ((syncEnd - syncStart + currentChunk - 1) / currentChunk).toInt().coerceAtLeast(1)
        val estimatedTotalChunks = 4 * estimateChunksPerType(chunkSize.get())
        val chunksDone = java.util.concurrent.atomic.AtomicInteger(0)
        val lastProgressLogChunks = java.util.concurrent.atomic.AtomicInteger(0)
        val lastProgressLogMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        suspend fun fetchTypeRange(
            type: Int,
            metaOnly: Int,
            pageSize: Int,
            phase: String  // "scanning" (metadata sweep) or "downloading" (full event fetch)
        ): List<CalendarEventDetail>? {
            val typeStartMs = System.currentTimeMillis()
            val initialChunk = chunkSize.get()
            val perTypeChunks = estimateChunksPerType(initialChunk)
            SyncLog.i(TAG, "'$calDisplayName': $phase Type=$type - ~$perTypeChunks chunks @ ${initialChunk / SyncConstants.SECONDS_PER_DAY}d each")
            val collected = mutableListOf<CalendarEventDetail>()
            var windowStart = syncStart
            while (windowStart < syncEnd) {
                val currentChunk = chunkSize.get()  // snapshot per window iteration
                val windowEnd = minOf(windowStart + currentChunk, syncEnd)
                var page = 0
                var windowDone = false
                while (!windowDone) {
                    val resp = apiClient.getCalendarEvents(
                        accountName = account.name,
                        calendarId = calInfo.id,
                        page = page,
                        pageSize = pageSize,
                        start = windowStart,
                        end = windowEnd,
                        timezone = queryTimezone,
                        type = type,
                        metaDataOnly = metaOnly
                    )
                    if (!resp.isSuccessful) {
                        val label = if (metaOnly == 1) "event metadata" else "events"
                        val requestUrl = resp.raw().request.url.toString()
                        val errorBody = try { resp.errorBody()?.string()?.take(500) } catch (_: Exception) { null }
                        val isWindowTooBig = resp.code() == 400 &&
                            errorBody != null &&
                            errorBody.contains("Time window is too big")
                        if (isWindowTooBig && currentChunk > chunkFloor) {
                            // CAS-style halve: only the coroutine that "saw" the failing
                            // size halves it; if another coroutine already halved, we
                            // just adopt the new size and retry without halving again.
                            val newChunk = maxOf(currentChunk / 2, chunkFloor)
                            val didHalve = chunkSize.compareAndSet(currentChunk, newChunk)
                            if (didHalve) {
                                val previousDays = currentChunk / SyncConstants.SECONDS_PER_DAY
                                val newDays = newChunk / SyncConstants.SECONDS_PER_DAY
                                SyncLog.i(TAG, "Window too big for '$calDisplayName' Type=$type at ${previousDays}d, reducing chunk to ${newDays}d (shared with other Type buckets)")
                            }
                            // Don't advance windowStart — retry the same start with the smaller chunk.
                            break  // restart inner pagination at page=0 with the new chunk size
                        }
                        val spanDays = (windowEnd - windowStart) / SyncConstants.SECONDS_PER_DAY
                        SyncLog.e(TAG, "Failed to fetch $label for '$calDisplayName' (Type=$type, Page=$page, span=${spanDays}d): HTTP ${resp.code()}, aborting sync")
                        SyncLog.e(TAG, "  Request URL: $requestUrl")
                        SyncLog.e(TAG, "  Response body: ${errorBody ?: "(empty)"}")
                        return null
                    }
                    val events = resp.body()?.events
                    if (events == null) {
                        SyncLog.d(TAG, "'$calDisplayName': null events body Type=$type window starting $windowStart page=$page - treating window as empty (possible silent drop)")
                        windowDone = true
                    } else {
                        collected.addAll(events)
                        if (events.size < pageSize) {
                            windowDone = true
                        } else {
                            page++
                        }
                    }
                }
                if (windowDone) {
                    windowStart = windowEnd
                    val done = chunksDone.incrementAndGet()
                    // Log every 30 chunks or every 5s, whichever fires first; also push
                    // a notification update so the user sees the sweep advancing.
                    val nowMs = System.currentTimeMillis()
                    if (done - lastProgressLogChunks.get() >= 30 || nowMs - lastProgressLogMs.get() >= 5_000) {
                        SyncLog.i(TAG, "'$calDisplayName': $phase $done/$estimatedTotalChunks chunks (Type=$type, ${collected.size} events in this bucket)")
                        SyncNotificationHelper.updateProgress(
                            context, account.name, "calendar",
                            phase, done, estimatedTotalChunks
                        )
                        lastProgressLogChunks.set(done)
                        lastProgressLogMs.set(nowMs)
                    }
                }
                // else: chunk was too big — loop continues with the same windowStart and the smaller chunkSize
            }
            val typeElapsedSec = (System.currentTimeMillis() - typeStartMs) / 1000
            SyncLog.i(TAG, "'$calDisplayName': Type=$type done - ${collected.size} events in ${typeElapsedSec}s")
            return collected
        }

        /**
         * Fetch all pages across all 4 Type buckets and merge into a deduped list (by event ID).
         * Returns null on HTTP error — callers must treat that as fatal and abort the sync,
         * because partial data would mistakenly mark surviving server-side events as deleted.
         *
         * v1.4.20: the 4 Type buckets now run in parallel via [coroutineScope] + [async].
         * They're independent server queries (PartDayInsideWindow / PartDayBeforeWindow /
         * FullDayInsideWindow / FullDayBeforeWindow) so concurrent execution gives a
         * roughly 4× speedup on dense full-fetches. The shared chunk-size [AtomicLong]
         * is halved via compareAndSet so simultaneous "Time window is too big" responses
         * don't double-halve. If any Type returns null we still wait for the others to
         * finish (a few extra seconds in the worst case) before aborting — keeps the
         * code simple.
         *
         * @param phase Notification/log phase label: "scanning" for metadata sweep
         *              (metaOnly=1) or "downloading" for full event fetch (metaOnly=null).
         */
        suspend fun fetchAllEvents(metaOnly: Int?, pageSize: Int, phase: String): List<CalendarEventDetail>? {
            val perTypeResults = coroutineScope {
                (0..3).map { type ->
                    async { fetchTypeRange(type, metaOnly ?: 0, pageSize, phase) }
                }.awaitAll()
            }
            if (perTypeResults.any { it == null }) return null
            val merged = LinkedHashMap<String, CalendarEventDetail>()
            for (typeEvents in perTypeResults) {
                for (e in typeEvents!!) merged.putIfAbsent(e.id, e)
            }
            SyncLog.d(TAG, "'$calDisplayName': dense $phase merge - perType sizes=${perTypeResults.map { it!!.size }} merged=${merged.size} unique")
            return merged.values.toList()
        }

        // Fetch lightweight metadata first (only when we have existing events to compare against)
        val allMetadata = mutableListOf<CalendarEventDetail>()
        if (!skipMetadata) {
            val metadata = fetchAllEvents(metaOnly = 1, pageSize = METADATA_PAGE_SIZE, phase = "scanning")
                ?: return null  // CRITICAL: do not proceed — empty metadata would delete all local events
            allMetadata.addAll(metadata)
            SyncLog.i(TAG, "'$calDisplayName': checking metadata ${allMetadata.size} events...")
        }

        // No client-side date filter — see comment in sparse path. Trust Proton's
        // server-side Type-bucket filtering, which correctly includes recurring events
        // whose seed sits before the window but whose occurrences fall inside it
        // (Type=1 PartDayBeforeWindow / Type=3 FullDayBeforeWindow exist for exactly
        // this case). v1.4.11 fix: the previous client-side intersection check on
        // (startTime, endTime) silently dropped recurring events because those columns
        // describe only the seed instance.
        val filteredMetadata = allMetadata

        // Find which events are new or changed
        val changedEventIds = mutableListOf<String>()
        if (!skipMetadata) {
            for (meta in filteredMetadata) {
                val existing = existingEventMappings[meta.id]
                if (existing == null || existing.lastModified < meta.lastEditTime ||
                    existing.syncTag == EventRetimePlanner.RETIME_TAG
                ) {
                    changedEventIds.add(meta.id)
                }
            }
        }

        // Server→local DELETION must be authoritative — never purge an event that still exists on the
        // server. "All" mode no longer reaches this dense branch (it takes the flat /events/ids path,
        // whose serverEventIds is already the full id set). For custom (bounded-window) mode, source the
        // DELETION set from the all-time /events/ids — but NOT into changedEventIds: the body fetch
        // stays windowed (the user's date window is preserved), while out-of-window events that still
        // exist server-side are KEPT rather than purged. Only when deletion is actually possible
        // (existing mappings + an incremental sweep); skipped on fresh import (nothing to delete).
        if (syncMode != SyncPreferences.SYNC_MODE_ALL && !skipMetadata && existingEventMappings.isNotEmpty()) {
            val ids = fetchAllEventIds(account, calInfo, calDisplayName)
            if (ids != null) {
                authoritativeServerIds = ids
                SyncLog.i(TAG, "'$calDisplayName': custom-mode deletion authority = ${ids.size} all-time /events/ids (body fetch stays windowed)")
            } else {
                fetchHadFailures = true
                SyncLog.w(TAG, "'$calDisplayName': custom-mode /events/ids fetch failed - deferring the deletion pass")
            }
        }

        // --- Step 3: Fetch full data only for changed events ---
        filteredEvents = if (!skipMetadata && changedEventIds.isEmpty()) {
            SyncLog.i(TAG, "No changed events in '$calDisplayName' (${filteredMetadata.size} total)")
            emptyList()
        } else if (skipMetadata || (changedEventIds.size == filteredMetadata.size && changedEventIds.size > PAGE_SIZE)) {
            // Fresh sync OR all events changed with many events — full fetch
            if (!skipMetadata) {
                SyncLog.i(TAG, "'$calDisplayName': ${changedEventIds.size}/${filteredMetadata.size} events changed - full fetch")
            }
            // Custom-mode only: "All" mode now takes the flat /events/ids path (which is complete by
            // construction). The body fetch here stays windowed by design; the deletion set is made
            // authoritative for custom mode earlier (see the fetchAllEventIds reconcile above).
            val allEvents = fetchAllEvents(metaOnly = null, pageSize = PAGE_SIZE, phase = "downloading")
                ?: return null  // CRITICAL: do not proceed with partial data
            SyncLog.i(TAG, "'$calDisplayName': downloaded ${allEvents.size} events")
            allEvents  // No client-side filter — see comment on filteredMetadata.
        } else {
            // Fetch changed events in parallel with a concurrency cap (same pattern
            // as the sparse path). Sequential fetching at ~350 ms/event was the
            // dominant cost on first-import syncs (6m for 1000 events) — 5-way
            // parallelism brings it down to ~1m. v1.4.16.
            SyncLog.i(TAG, "'$calDisplayName': ${changedEventIds.size}/${filteredMetadata.size} events changed - fetching individually")
            val sem = Semaphore(SyncConstants.CALENDAR_PARALLEL_GET_PERMITS)
            val doneCounter = java.util.concurrent.atomic.AtomicInteger(0)
            val failedGets = java.util.concurrent.atomic.AtomicInteger(0)
            val total = changedEventIds.size
            val fetched = coroutineScope {
                changedEventIds.map { eventId ->
                    async {
                        sem.withPermit {
                            val event = try {
                                val r = apiClient.getCalendarEvent(account.name, calInfo.id, eventId)
                                if (r.isSuccessful) r.body()?.event
                                else { failedGets.incrementAndGet(); null }
                            } catch (e: Exception) {
                                SyncLog.w(TAG, "Failed to fetch event $eventId: ${e.message}")
                                failedGets.incrementAndGet(); null
                            }
                            val n = doneCounter.incrementAndGet()
                            if (n % 20 == 0 || n == total) {
                                SyncLog.i(TAG, "'$calDisplayName': fetched $n/$total changed events")
                                SyncNotificationHelper.updateProgress(
                                    context, account.name, "calendar",
                                    "downloading", n, total
                                )
                            }
                            event
                        }
                    }
                }.awaitAll()
            }.filterNotNull()
            if (failedGets.get() > 0) {
                // Without this, the model-event-id save below would mark the calendar as
                // up-to-date and the unchanged-skip would leave the event stale until some
                // OTHER change bumps the calendar's CalendarModelEventID.
                fetchHadFailures = true
                SyncLog.w(TAG, "'$calDisplayName': changed-event fetch had ${failedGets.get()} failed GET(s) - not saving model-event-id, so the next sync re-fetches the gap")
            }
            fetched
        }

        // Track ALL event IDs the server knows about (from metadata or full fetch),
        // not just the ones we fetched in detail.  Otherwise an incremental sync that
        // fetches 0 changed events would see an empty set and delete every local event.
        serverEventIds = authoritativeServerIds?.toMutableSet()
            ?: if (filteredMetadata.isNotEmpty()) {
                filteredMetadata.map { it.id }.toMutableSet()
            } else {
                // When metadata was skipped (fresh sync), use the fetched events
                filteredEvents.map { it.id }.toMutableSet()
            }

        return CalendarFetchResult(filteredEvents, serverEventIds, fetchHadFailures)
    }

    /**
     * Page the authoritative, window/Type/TZ-free /events/ids list for a calendar — the
     * count-authoritative server id set (the same endpoint the sparse path trusts). Returns the
     * full id set, or null if a page request failed (caller treats the run as incomplete).
     */
    private suspend fun fetchAllEventIds(
        account: Account,
        calInfo: CalendarInfo,
        calDisplayName: String
    ): Set<String>? {
        val allIds = LinkedHashSet<String>()
        var afterId: String? = null
        while (true) {
            val resp = try {
                apiClient.getCalendarEventIds(account.name, calInfo.id, limit = 100, afterId = afterId)
            } catch (e: Exception) {
                SyncLog.e(TAG, "'$calDisplayName': /events/ids fetch failed: ${e.message}")
                return null
            }
            if (!resp.isSuccessful) {
                SyncLog.e(TAG, "'$calDisplayName': /events/ids fetch HTTP ${resp.code()}")
                return null
            }
            @Suppress("UNCHECKED_CAST")
            val pageIds = resp.body()?.get("IDs") as? List<String> ?: emptyList()
            if (pageIds.isEmpty()) break
            allIds.addAll(pageIds)
            if (pageIds.size < 100) break
            afterId = pageIds.last()
        }
        return allIds
    }

    /**
     * Map a Proton calendar Type to the CalendarContract access level. Subscribed (Type=1)
     * and holiday (Type=2) calendars are read-only on Proton — every edit is rejected with
     * code=2011 and reverted on the next sync. CAL_ACCESS_READ makes Samsung/Google
     * Calendar gray out editing up-front instead of letting the user make changes that
     * silently bounce. (View-only *shared* Type=0 calendars can still 2011 — the recovery
     * handler in reconcileDeletedEvents stays the authority for those.)
     */
    private fun accessLevelFor(calendarType: Int, memberPermissions: Int? = null): Int = when {
        calendarType != 0 -> CalendarContract.Calendars.CAL_ACCESS_READ
        // A Type-0 calendar SHARED with the user view-only accepts no writes either. Proton answers
        // such an upload with 400 Code 2026, and with the calendar presented as writable the phone's
        // calendar app happily lets the user create an event that then retries for ever. Seen in the
        // field: one event rejected on every sync, with no way for the user to see why.
        //
        // Only DOWNGRADE on positive evidence. A null permission means the field was absent or the
        // member list was empty, and guessing read-only there would lock somebody out of their own
        // calendar - worse than the bug being fixed.
        !writableByPermissions(memberPermissions) -> CalendarContract.Calendars.CAL_ACCESS_READ
        else -> CalendarContract.Calendars.CAL_ACCESS_OWNER
    }


    private fun insertLocalCalendar(
        provider: ContentProviderClient,
        account: Account,
        displayName: String,
        color: Int,
        ownerEmail: String,
        calendarType: Int,
        memberPermissions: Int?,
    ): Long? {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
            put(CalendarContract.Calendars.NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, color)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, accessLevelFor(calendarType, memberPermissions))
            // OWNER_ACCOUNT MUST be the user's email, not the (possibly aliased) account.name
            // — Samsung/Google Calendar auto-fill new events' ATTENDEE_EMAIL from this field
            // when the user marks themselves as a participant. If we wrote the alias here,
            // Proton's `/mail/v4/messages/{id}` send would later reject the invite with
            // code=2001 "Invalid package address" because the attendee row carries a non-email
            // identifier.
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ownerEmail)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
            // Allow both visual (METHOD_ALERT) and email (METHOD_EMAIL) reminders so the
            // Android picker shows "Notification / E-Mail" like Google Calendar does.
            // Proton's API supports both via the Notifications field's Type=0/1 enum.
            put(CalendarContract.Calendars.ALLOWED_REMINDERS,
                "${CalendarContract.Reminders.METHOD_DEFAULT},${CalendarContract.Reminders.METHOD_ALERT},${CalendarContract.Reminders.METHOD_EMAIL}")
            put(CalendarContract.Calendars.ALLOWED_AVAILABILITY, "${CalendarContract.Events.AVAILABILITY_BUSY},${CalendarContract.Events.AVAILABILITY_FREE}")
            put(CalendarContract.Calendars.ALLOWED_ATTENDEE_TYPES, "${CalendarContract.Attendees.TYPE_NONE},${CalendarContract.Attendees.TYPE_REQUIRED},${CalendarContract.Attendees.TYPE_OPTIONAL}")
            put(CalendarContract.Calendars.IS_PRIMARY, 0)
            put(CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE, 1)
            put(CalendarContract.Calendars.CAN_ORGANIZER_RESPOND, 1)
            put(CalendarContract.Calendars.MAX_REMINDERS, 5)
        }

        val uri = provider.insert(
            SyncUtils.asCallerSyncAdapter(CalendarContract.Calendars.CONTENT_URI, account),
            values
        )
        return uri?.let { ContentUris.parseId(it) }
    }

    private fun updateLocalCalendar(
        provider: ContentProviderClient,
        account: Account,
        androidCalId: Long,
        displayName: String,
        color: Int,
        ownerEmail: String,
        calendarType: Int,
        memberPermissions: Int?,
    ) {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, color)
            // Heal legacy calendars whose OWNER_ACCOUNT was set to the alias (account.name)
            // before the v1.3.103 fix. See comment in insertLocalCalendar above.
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ownerEmail)
            // Also heals calendars created before subscribed/holiday calendars were
            // marked read-only (see accessLevelFor above).
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, accessLevelFor(calendarType, memberPermissions))
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
            // Allow both visual (METHOD_ALERT) and email (METHOD_EMAIL) reminders so the
            // Android picker shows "Notification / E-Mail" like Google Calendar does.
            // Proton's API supports both via the Notifications field's Type=0/1 enum.
            put(CalendarContract.Calendars.ALLOWED_REMINDERS,
                "${CalendarContract.Reminders.METHOD_DEFAULT},${CalendarContract.Reminders.METHOD_ALERT},${CalendarContract.Reminders.METHOD_EMAIL}")
            put(CalendarContract.Calendars.ALLOWED_AVAILABILITY, "${CalendarContract.Events.AVAILABILITY_BUSY},${CalendarContract.Events.AVAILABILITY_FREE}")
            put(CalendarContract.Calendars.ALLOWED_ATTENDEE_TYPES, "${CalendarContract.Attendees.TYPE_NONE},${CalendarContract.Attendees.TYPE_REQUIRED},${CalendarContract.Attendees.TYPE_OPTIONAL}")
            put(CalendarContract.Calendars.IS_PRIMARY, 0)
            put(CalendarContract.Calendars.CAN_MODIFY_TIME_ZONE, 1)
            put(CalendarContract.Calendars.CAN_ORGANIZER_RESPOND, 1)
            put(CalendarContract.Calendars.MAX_REMINDERS, 5)
        }
        provider.update(
            ContentUris.withAppendedId(
                SyncUtils.asCallerSyncAdapter(CalendarContract.Calendars.CONTENT_URI, account),
                androidCalId
            ),
            values, null, null
        )
    }

    /**
     * This calendar's keys, or a throw.
     *
     * A failed fetch must never come back as an empty list. Both callers read an empty list as a fact about
     * the calendar, because an external one legitimately has no keys, so a swallowed HTTP failure let a
     * PERSONAL calendar sync with nothing to decrypt with and every event surfaced as [Encrypted Event].
     * The guard that exists for exactly this, "Skipping ENTIRE personal calendar", could never fire.
     *
     * Both callers already do the right thing with a throw: prepareCalendarJob skips the whole calendar for
     * the run, and prepareUploadContexts counts an IO error so the framework retries the sync. An empty list
     * from a 2xx still means what it always meant.
     */
    private suspend fun fetchCalendarKeys(accountName: String, calendarId: String): List<CalendarKey> {
        val response = apiClient.getCalendarKeys(accountName, calendarId)
        if (!response.isSuccessful) {
            throw java.io.IOException("keys fetch failed for calendar $calendarId: HTTP ${response.code()}")
        }
        return response.body()?.keys ?: emptyList()
    }

    /** This calendar's passphrases, or a throw, for the same reason as [fetchCalendarKeys]. */
    private suspend fun fetchCalendarPassphrases(accountName: String, calendarId: String): List<CalendarPassphrase> {
        val response = apiClient.getCalendarPassphrase(accountName, calendarId)
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: "no body"
            throw java.io.IOException(
                "passphrase fetch failed for calendar $calendarId: HTTP ${response.code()} - $errorBody"
            )
        }
        return response.body()?.getAllPassphrases() ?: emptyList()
    }

    /**
     * Collapse duplicate [CalendarContract.Attendees] rows on one event down to one row per
     * address, keeping the most decisive RSVP.
     *
     * An event we were invited to comes out of a fresh INSERT with the user's own address listed
     * TWICE: once from Proton's attendee list, and once fabricated by AOSP's `CalendarProvider2`,
     * which calls `createAttendeeEntry` for `OWNER_ACCOUNT` whenever the INSERT carries
     * `SELF_ATTENDEE_STATUS`. We cannot simply omit that column — it is the field calendar apps
     * read to decide whether to offer the RSVP buttons, and it is rejected outright on UPDATE, so
     * there is no way to set it afterwards. Cleaning up after the provider is the portable fix; how
     * the fabricated row is shaped varies by OEM, so matching on it directly would not travel.
     *
     * Two things went wrong with the duplicate: guest lists showed the user twice, and the RSVP
     * upload keyed its status map by address, so the stale twin decided the answer that got sent.
     * That second one is fixed at the call site too ([rsvpDecisiveness]); this removes the cause.
     */
    private fun dedupeAttendeeRows(
        provider: ContentProviderClient,
        account: Account,
        androidEventId: Long
    ) {
        try {
            // rowId -> (emailLower, status), in cursor order.
            val rows = ArrayList<Triple<Long, String, Int>>()
            provider.query(
                CalendarContract.Attendees.CONTENT_URI,
                arrayOf(
                    CalendarContract.Attendees._ID,
                    CalendarContract.Attendees.ATTENDEE_EMAIL,
                    CalendarContract.Attendees.ATTENDEE_STATUS
                ),
                "${CalendarContract.Attendees.EVENT_ID} = ?",
                arrayOf(androidEventId.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val email = c.getString(1)?.lowercase()?.trim() ?: continue
                    rows.add(Triple(c.getLong(0), email, c.getInt(2)))
                }
            }
            if (rows.size < 2) return

            // Winner per address: most decisive status, ties broken by the earlier row so the one
            // we inserted from Proton's list survives ahead of the fabricated twin.
            val keep = HashMap<String, Long>()
            val best = HashMap<String, Int>()
            for ((rowId, email, status) in rows) {
                val prev = best[email]
                if (prev == null || rsvpDecisiveness(status) < rsvpDecisiveness(prev)) {
                    best[email] = status
                    keep[email] = rowId
                }
            }
            val doomed = rows.map { it.first }.filter { it !in keep.values }
            if (doomed.isEmpty()) return

            val uri = SyncUtils.asCallerSyncAdapter(CalendarContract.Attendees.CONTENT_URI, account)
            val ops = ArrayList<ContentProviderOperation>(doomed.size)
            for (rowId in doomed) {
                ops.add(
                    ContentProviderOperation.newDelete(uri)
                        .withSelection("${CalendarContract.Attendees._ID} = ?", arrayOf(rowId.toString()))
                        .build()
                )
            }
            provider.applyBatch(ops)
            SyncLog.d(TAG, "Removed ${doomed.size} duplicate attendee row(s) on event $androidEventId")
        } catch (e: Exception) {
            // Never fatal: a duplicate guest row is cosmetic next to losing the event.
            SyncLog.w(TAG, "Could not dedupe attendee rows on event $androidEventId: ${e.message}")
        }
    }

    /**
     * Rank an Android attendee status by how decisive an answer it is, lowest first:
     * ACCEPTED / DECLINED / TENTATIVE are real replies; INVITED and NONE are not.
     *
     * Used to pick between several local Attendees rows carrying the SAME address, which is the
     * normal case for an event we were invited to (see the call site).
     */
    private fun rsvpDecisiveness(status: Int): Int = when (status) {
        CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> 0
        CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> 1
        CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> 2
        CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> 3
        else -> 4
    }

    /**
     * Push this member's personal part (reminders + per-event colour) via the dedicated endpoint.
     *
     * Kept separate from the events/sync payload on purpose. A sync update carries the attendee
     * list, which a non-organiser is not allowed to touch, so on a shared calendar Proton rejects
     * the whole update with code 2011 and the member's own reminder or colour change dies with it.
     * This call has nothing to do with the attendee list, so it succeeds regardless. It runs AFTER
     * the sync update, whatever that update's outcome, because an update that carries no
     * Notifications resets this member's reminders to the calendar defaults (see the call site).
     *
     * Failures are logged and swallowed, and the result says whether Proton accepted the call: the
     * personal part is a nicety, and letting it abort the real event update would be a much worse
     * trade. Extracted as its own function deliberately: the upload code that calls it sits near
     * the Kotlin coroutine state-machine size limit, and inlining another suspend call there broke
     * codegen.
     */
    private suspend fun pushPersonalPart(
        account: Account,
        protonCalendarId: String,
        protonEventId: String,
        notifications: List<EventNotification>?,
        color: String?
    ): Boolean {
        return try {
            val response = apiClient.updateEventPersonalPart(
                accountName = account.name,
                calendarId = protonCalendarId,
                eventId = protonEventId,
                body = UpdateEventPersonalPartRequest(notifications = notifications, color = color)
            )
            if (response.isSuccessful) {
                // The endpoint echoes the stored event back, so report what Proton actually kept
                // rather than what we asked for. A 200 with the fields unchanged would otherwise
                // look like success.
                @Suppress("UNCHECKED_CAST")
                val stored = response.body()?.get("Event") as? Map<String, Any>
                val storedNotifications = (stored?.get("Notifications") as? List<*>)?.size
                val storedColor = stored?.get("Color") as? String
                SyncLog.d(
                    TAG,
                    "Personal part updated for $protonEventId (sent notifications=${notifications?.size ?: 0} color=${color ?: "none"}; " +
                        "server now has notifications=${storedNotifications ?: "?"} color=${storedColor ?: "none"})"
                )
                true
            } else {
                SyncLog.w(TAG, "Personal part update failed for $protonEventId: HTTP ${response.code()}")
                response.errorBody()?.close()
                false
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Personal part update failed for $protonEventId: ${e.message}")
            false
        }
    }

    /**
     * True when every local attendee row on this event is a provider-fabricated "phantom" and none
     * of them is a real participant, so the upload must send no attendee list at all.
     *
     * Requires BOTH signals, since either alone has a false positive:
     *  - `HAS_ATTENDEE_DATA = 0`, which we write from Proton's cleartext `Attendees[]` array, so it
     *    is Proton stating the event has no guests. Unknown (-1) counts as "leave it alone".
     *  - every row's address belongs to this user: one of their Proton addresses or the account name
     *    ([ownAddresses]). The calendar's OWNER_ACCOUNT, the address the provider fabricates these
     *    rows for, is our own member's on any calendar this app uploads to, so it is among them; a
     *    member that is not ours never counts, or a real guest at its address would be dropped.
     *    Keying on "lone attendee == OWNER_ACCOUNT" alone would delete a genuine sole guest who
     *    happens to use the owner address.
     */
    private fun isPhantomOnlyAttendeeList(
        provider: ContentProviderClient,
        account: Account,
        androidEventId: Long,
        attendeeEmails: List<String>
    ): Boolean {
        val selfAddresses = ownAddresses(keyManager.getAddressEmails(account.name), account.name)

        for (email in attendeeEmails) {
            if (email.lowercase() !in selfAddresses) return false
        }
        if (readHasAttendeeData(provider, androidEventId) != 0) return false

        SyncLog.i(
            TAG,
            "Dropping ${attendeeEmails.size} phantom attendee row(s) on event $androidEventId " +
                "(${attendeeEmails.joinToString()}): Proton reports no guests, so these are " +
                "provider-fabricated self entries, not participants"
        )
        return true
    }

    /**
     * Read `Events.HAS_ATTENDEE_DATA` for one event. On events that came from Proton this column is
     * written from the cleartext `Attendees[]` array, so 0 is Proton's own answer to "does this
     * event have guests". Returns -1 when it cannot be read, which the phantom guard treats as
     * "unknown" and therefore leaves the attendee rows alone.
     */
    private fun readHasAttendeeData(provider: ContentProviderClient, androidEventId: Long): Int {
        return try {
            provider.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.HAS_ATTENDEE_DATA),
                "${CalendarContract.Events._ID} = ?",
                arrayOf(androidEventId.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) c.getInt(0) else -1 } ?: -1
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not read HAS_ATTENDEE_DATA for event $androidEventId: ${e.message}")
            -1
        }
    }

    /**
     * Android event ids on this calendar with unsent local edits (`DIRTY = 1`), excluding rows the
     * user has deleted (those are handled by the deletion path). One query per calendar; the
     * per-event alternative would be a query per fetched event.
     *
     * Null when the provider could not be read. The collision check then carries on as before with an empty
     * set ("no collisions detected", deliberately NOT "everything might be dirty", which would hold the entire
     * calendar on one failure), but the re-time gate ([isRetimeDue]) re-applies nothing on such a run.
     */
    private fun readDirtyEventIds(provider: ContentProviderClient, androidCalendarId: Long): Set<Long>? {
        return try {
            val ids = HashSet<Long>()
            val cursor = provider.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DIRTY} = 1 AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(androidCalendarId.toString()),
                null
            ) ?: return null
            cursor.use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
            ids
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not read dirty event ids for calendar $androidCalendarId: ${e.message}")
            null
        }
    }

    /**
     * Ask the user which side of a collision to keep. One notification per event, since the choice
     * is irreversible and a grouped "apply to all" is a poor way to make it.
     *
     * On a shared calendar the remote edit may be someone else's, in which case keeping the local
     * version replaces their work. The event part carries an `Author`, so we can name them rather
     * than guess.
     */
    private fun notifyEventConflict(
        account: Account,
        protonCalendarId: String,
        event: CalendarEventDetail,
        androidEventId: Long,
        provider: ContentProviderClient
    ) {
        val title = readEventTitle(provider, androidEventId)
        val remoteAuthor = event.sharedEvents.firstNotNullOfOrNull { it.author }
            ?: event.calendarEvents.firstNotNullOfOrNull { it.author }
        val authorIsSomeoneElse = remoteAuthor != null &&
            remoteAuthor.lowercase() !in keyManager.getAddressEmails(account.name) &&
            !remoteAuthor.equals(account.name, ignoreCase = true)
        SyncNotificationHelper.notifyEventConflict(
            context = context,
            accountName = account.name,
            protonCalendarId = protonCalendarId,
            protonEventId = event.id,
            androidEventId = androidEventId,
            eventTitle = title,
            otherEditor = if (authorIsSomeoneElse) remoteAuthor else null
        )
    }

    private fun readEventTitle(provider: ContentProviderClient, androidEventId: Long): String {
        return try {
            provider.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.TITLE),
                "${CalendarContract.Events._ID} = ?",
                arrayOf(androidEventId.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Android event ids that already have at least one reminder row. Reminders carry no calendar
     * id, so this reads the whole (small) table once rather than querying per event.
     *
     * Null when the table could not be read, never an empty set in its place. Empty reads as "no event
     * has reminders", and the backfill then rewrote the reminder rows of every unchanged event without
     * unsent edits that Proton gives reminders, on every calendar the run fetched. A null backfills
     * nothing this run ([needsReminderBackfill]); the next run that fetches the calendar catches up.
     */
    private fun readEventIdsWithReminders(provider: ContentProviderClient): Set<Long>? {
        return try {
            val ids = HashSet<Long>()
            val cursor = provider.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.EVENT_ID),
                null, null, null
            )
            if (cursor == null) {
                SyncLog.w(TAG, "Could not read existing reminder rows (no cursor); no reminders are backfilled this run")
                return null
            }
            cursor.use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
            ids
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not read existing reminder rows: ${e.message}; no reminders are backfilled this run")
            null
        }
    }

    /**
     * Give an already-synced event the reminders it should have.
     *
     * Needed because the per-event skip gate means an unchanged event is never rewritten, so the
     * ~96% of events that inherit the calendar defaults would otherwise never receive them: the
     * fix would only ever reach events that happen to change later. This backfills them once,
     * touching ONLY the reminder rows and never the event row itself.
     *
     * Deliberately conservative: the caller only reaches here for an unchanged event with no unsent
     * edits that its snapshot says has no reminder rows, and never when the snapshot could not be taken
     * ([needsReminderBackfill]). An event whose reminders the user deleted
     * uploads an explicit empty list, but Proton does not keep it: the events feed then reports
     * `Notifications: null`, so [expected] is the calendar's defaults, if it has any, and they are
     * added back. Clearing every reminder on an event therefore cannot currently stick (see the
     * measured note where the download calls this).
     */
    private fun backfillMissingReminders(
        provider: ContentProviderClient,
        account: Account,
        androidEventId: Long,
        expected: List<EventNotification>?
    ) {
        if (expected.isNullOrEmpty()) return
        try {
            val ops = ArrayList<ContentProviderOperation>()
            // Clear first, so the back-fill is idempotent. The caller only reaches here when its
            // per-calendar "already has reminders" snapshot said this event had none, but that
            // snapshot is taken once per calendar per pass: a manual sync and the scheduled sync
            // that follows it can both read "none" before either writes, and the inserts then
            // stack up. Observed on hardware as two identical 15-minute reminders on one event.
            // Deleting first is a no-op in the normal case and collapses the race in the bad one.
            ops.add(
                ContentProviderOperation
                    .newDelete(SyncUtils.asCallerSyncAdapter(CalendarContract.Reminders.CONTENT_URI, account))
                    .withSelection("${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(androidEventId.toString()))
                    .build()
            )
            CalendarMapper.addReminderOpsForBackfill(ops, account, androidEventId, expected)
            if (ops.size > 1) provider.applyBatch(ops)
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not backfill reminders on event $androidEventId: ${e.message}")
        }
    }

    /**
     * A calendar's default reminders. Every event with no `Notifications` of its own inherits
     * these, which on a real account was nearly all events, so ignoring them means showing no
     * reminders where Proton's own UI shows them.
     *
     * Failure returns null and the caller falls back to "no defaults", i.e. prior behaviour. Never
     * fatal to a sync: missing reminders are worse than no reminders, but neither is worth losing
     * the whole calendar over.
     */
    private suspend fun fetchCalendarSettings(accountName: String, protonCalendarId: String): CalendarSettingsData? {
        return try {
            val response = apiClient.getCalendarSettings(accountName, protonCalendarId)
            if (response.isSuccessful) {
                response.body()?.calendarSettings
            } else {
                response.errorBody()?.close()
                SyncLog.w(TAG, "Could not read calendar settings for $protonCalendarId: HTTP ${response.code()}")
                null
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not read calendar settings for $protonCalendarId: ${e.message}")
            null
        }
    }

    /**
     * The defaults that apply to one event. Full-day and part-day have separate lists because their
     * triggers mean different things: part-day is typically "-PT15M" (before the start), full-day
     * "PT12H" (noon on the day, since the event starts at midnight).
     */
    private fun defaultNotificationsFor(settings: CalendarSettingsData?, isFullDay: Boolean): List<EventNotification>? {
        val list = if (isFullDay) settings?.defaultFullDayNotifications else settings?.defaultPartDayNotifications
        return list?.takeIf { it.isNotEmpty() }
    }

    /** Order-insensitive comparison of two reminder sets. */
    private fun sameNotifications(a: List<EventNotification>?, b: List<EventNotification>?): Boolean {
        val setA = a?.map { it.type to it.trigger.trim().uppercase() }?.toSet() ?: emptySet()
        val setB = b?.map { it.type to it.trigger.trim().uppercase() }?.toSet() ?: emptySet()
        return setA == setB
    }

    /**
     * Android's `Reminders.MINUTES` counts minutes BEFORE the event, so a positive value is a
     * negative ISO duration. A NEGATIVE value means after the start, which is how all-day reminders
     * are stored ("on the day at 12:00" is -720), and must become a POSITIVE duration.
     *
     * The old code hardcoded "-PT{minutes}M", which turned an all-day reminder into the malformed
     * "-PT-720M". Dormant until v1.6.25 started bringing Proton's all-day reminders down to the
     * device in the first place.
     */
    private fun minutesBeforeToTrigger(minutes: Int): String = when {
        minutes > 0 -> "-PT${minutes}M"
        minutes < 0 -> "PT${-minutes}M"
        else -> "PT0S"
    }

    private fun parseColor(hexColor: String): Int {
        return try {
            Color.parseColor(if (hexColor.startsWith("#")) hexColor else "#$hexColor")
        } catch (e: Exception) {
            Color.BLUE
        }
    }

    /**
     * WORKAROUND: Proton's CalendarModelEventID does NOT change when an attendee's
     * RSVP status changes (accept/decline/tentative). Neither the core event loop
     * (/core/v5/events) nor the calendar modelevents endpoint reports these changes.
     * The official Proton web calendar has the same limitation — it fetches full event
     * details on demand when displaying an event popover.
     *
     * This method re-fetches events that have attendees and updates their local
     * status if it changed on the server.
     */
    private suspend fun checkAttendeeStatusChanges(
        account: Account,
        provider: ContentProviderClient,
        calInfo: CalendarInfo,
        calDisplayName: String
    ) {
        // Find local events with attendees in this calendar
        val mappings = calendarEventSyncDao.getAllByCalendar(account.name, calInfo.id)
        if (mappings.isEmpty()) return

        // Query which local events have attendee data.
        //
        // DIRTY events are deliberately excluded. This poll exists to bring Proton's RSVP changes
        // DOWN, and it overwrites the local attendee status whenever the two differ — but it runs
        // in the download phase, BEFORE uploadDirtyEvents. On an event the user has just answered,
        // the local row is the newer truth and has not been sent yet, so polling it reverted the
        // user's accept/decline to Proton's stale value and the upload then dutifully pushed that
        // stale value back. The RSVP could never leave the device. Anything dirty is left alone;
        // the next poll picks it up once the upload has cleared the flag.
        val eventsWithAttendees = mutableListOf<CalendarEventSyncEntity>()
        for (mapping in mappings) {
            val androidId = mapping.androidEventId ?: continue
            try {
                val cursor = provider.query(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, androidId),
                    arrayOf(CalendarContract.Events.HAS_ATTENDEE_DATA, CalendarContract.Events.DIRTY),
                    null, null, null
                )
                cursor?.use { c ->
                    if (c.moveToFirst() && c.getInt(0) == 1 && c.getInt(1) == 0) {
                        eventsWithAttendees.add(mapping)
                    }
                }
            } catch (_: Exception) { /* Intentional: provider query failure for one event is non-fatal — skip and continue */ }
        }

        if (eventsWithAttendees.isEmpty()) return
        SyncLog.d(TAG, "'$calDisplayName': checking attendee status for ${eventsWithAttendees.size} events")

        var updatedCount = 0
        for (mapping in eventsWithAttendees) {
            try {
                val resp = apiClient.getCalendarEvent(account.name, calInfo.id, mapping.protonEventId)
                if (!resp.isSuccessful) { resp.errorBody()?.close(); continue }
                val protonEvent = resp.body()?.event ?: continue
                val protonAttendees = protonEvent.effectiveAttendees ?: continue

                // Read local attendees for this event
                val localCursor = provider.query(
                    CalendarContract.Attendees.CONTENT_URI,
                    arrayOf(
                        CalendarContract.Attendees.ATTENDEE_EMAIL,
                        CalendarContract.Attendees.ATTENDEE_STATUS,
                        CalendarContract.Attendees._ID
                    ),
                    "${CalendarContract.Attendees.EVENT_ID} = ?",
                    arrayOf(mapping.androidEventId.toString()),
                    null
                ) ?: continue

                // Build token→status map from Proton (token is the key, not email)
                // We need to match by reconstructing the token from UID + email
                val protonStatusByToken = protonAttendees.associate { it.token.lowercase() to it.status }

                // Read local attendees and check for status mismatches
                localCursor.use { lc ->
                    while (lc.moveToNext()) {
                        val email = lc.getString(0) ?: continue
                        val localStatus = lc.getInt(1)
                        val attendeeRowId = lc.getLong(2)

                        // Reconstruct the x-pm-token for this attendee
                        val tokenHash = protonAttendeeToken(protonEvent.uid, email)

                        val protonStatus = protonStatusByToken[tokenHash] ?: continue

                        // Map Proton status (0=needs-action,1=tentative,2=declined,3=accepted)
                        // to Android status
                        val expectedAndroidStatus = when (protonStatus) {
                            3 -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
                            2 -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                            1 -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
                            else -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
                        }

                        if (localStatus != expectedAndroidStatus) {
                            val values = ContentValues().apply {
                                put(CalendarContract.Attendees.ATTENDEE_STATUS, expectedAndroidStatus)
                            }
                            provider.update(
                                ContentUris.withAppendedId(
                                    SyncUtils.asCallerSyncAdapter(CalendarContract.Attendees.CONTENT_URI, account),
                                    attendeeRowId
                                ),
                                values, null, null
                            )
                            // NOTE: do NOT mirror this onto Events.SELF_ATTENDEE_STATUS here.
                            // The CalendarProvider rejects that column on UPDATE
                            // (IllegalArgumentException, see CalendarMapper.mapToAndroidOps), so
                            // this threw on every RSVP change. The Attendees row updated just
                            // above is the supported route: the provider derives the event's
                            // self-status from it.
                            val statusLabel = when (protonStatus) { 3 -> "ACCEPTED"; 2 -> "DECLINED"; 1 -> "TENTATIVE"; else -> "NEEDS-ACTION" }
                            SyncLog.i(TAG, "Updated attendee $email status to $statusLabel for event ${mapping.androidEventId}")
                            updatedCount++
                        }
                    }
                }
            } catch (e: Exception) {
                SyncLog.d(TAG, "Failed to check attendee status for event ${mapping.protonEventId}: ${e.message}")
            }
        }

        if (updatedCount > 0) {
            SyncLog.i(TAG, "'$calDisplayName': updated $updatedCount attendee statuses")
        }
    }

    /**
     * Find an existing CalendarContract event row on [androidCalId] whose _SYNC_ID equals
     * [protonEventId], regardless of whether a Room mapping still points to it. Used to
     * re-adopt an orphan row (mapping lost / never written) instead of inserting a duplicate.
     * Returns the row _ID, or null if none. Prefers a NON-cancelled (STATUS != CANCELED) row
     * so a reclaim never latches onto an EXDATE exception carrying the same id.
     */
    private fun findLocalEventBySyncId(
        provider: ContentProviderClient,
        androidCalId: Long,
        protonEventId: String
    ): Long? = try {
        provider.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.STATUS, CalendarContract.Events.ORIGINAL_SYNC_ID),
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events._SYNC_ID} = ?",
            arrayOf(androidCalId.toString(), protonEventId),
            null
        )?.use { c ->
            var best: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val isException = !c.isNull(2) && c.getString(2).isNotEmpty()
                if (isException) continue // never reclaim an EXDATE/override child as the master
                best = id
                break
            }
            best
        }
    } catch (e: Exception) {
        SyncLog.w(TAG, "findLocalEventBySyncId failed for $protonEventId on calId=$androidCalId: ${e.message}")
        null
    }

    /**
     * Heal an install that already accumulated duplicate/orphan LOCAL rows before the
     * reclaim fix. LOCAL-ONLY: never calls Proton, only removes redundant CalendarContract
     * rows via the sync-adapter URI (hard delete, no tombstones). Two classes:
     *  (a) Visible duplicates — 2+ non-exception rows share the same _SYNC_ID. When at least
     *      one is the tracked (mapped) row, delete the untracked sibling(s); the tracked copy
     *      always survives. A group with NO mapped row is left alone (its mapping was merely
     *      lost — the download path re-adopts one via _SYNC_ID next fetch).
     *  (b) Stacked cancelled EXDATE exceptions — duplicate STATUS_CANCELED rows for the same
     *      (ORIGINAL_SYNC_ID, ORIGINAL_INSTANCE_TIME); keep one, delete the rest.
     * Locally-created, not-yet-synced rows (null _SYNC_ID) are never touched.
     */
    private fun sweepDuplicateLocalRows(
        provider: ContentProviderClient,
        account: Account,
        androidCalId: Long,
        eventMappings: List<me.proton.sync.db.entities.CalendarEventSyncEntity>,
        calDisplayName: String
    ) {
        val mappedIds = eventMappings.mapNotNull { it.androidEventId }.toHashSet()
        data class Row(val id: Long, val syncId: String?, val origSyncId: String?, val origInstance: Long, val status: Int)
        val rows = ArrayList<Row>()
        try {
            provider.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events._SYNC_ID,
                    CalendarContract.Events.ORIGINAL_SYNC_ID,
                    CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
                    CalendarContract.Events.STATUS
                ),
                "${CalendarContract.Events.CALENDAR_ID} = ?",
                arrayOf(androidCalId.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    rows.add(
                        Row(
                            id = c.getLong(0),
                            syncId = c.getString(1),
                            origSyncId = c.getString(2),
                            origInstance = if (c.isNull(3)) 0L else c.getLong(3),
                            status = if (c.isNull(4)) -1 else c.getInt(4)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Duplicate sweep: query failed for '$calDisplayName' (calId=$androidCalId): ${e.message}")
            return
        }

        val toDelete = LinkedHashSet<Long>()

        // (a) visible duplicates: non-exception rows grouped by _SYNC_ID
        rows.filter { it.origSyncId.isNullOrEmpty() && !it.syncId.isNullOrEmpty() }
            .groupBy { it.syncId }
            .forEach { (_, group) ->
                if (group.size < 2) return@forEach
                if (group.any { it.id in mappedIds }) {
                    group.filter { it.id !in mappedIds }.forEach { toDelete.add(it.id) }
                }
            }

        // (b) stacked cancelled EXDATE exceptions: keep one per (origSyncId, origInstance)
        rows.filter { it.status == CalendarContract.Events.STATUS_CANCELED && !it.origSyncId.isNullOrEmpty() }
            .groupBy { it.origSyncId to it.origInstance }
            .forEach { (_, group) -> if (group.size >= 2) group.drop(1).forEach { toDelete.add(it.id) } }

        if (toDelete.isEmpty()) return
        SyncLog.i(TAG, "Duplicate sweep: '$calDisplayName' has ${rows.size} local rows, ${mappedIds.size} tracked; removing ${toDelete.size} redundant duplicate row(s) (tracked copies kept)")
        val eventsUri = SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account)
        var removed = 0
        for (id in toDelete) {
            try {
                removed += provider.delete(ContentUris.withAppendedId(eventsUri, id), null, null)
            } catch (e: Exception) {
                SyncLog.w(TAG, "Duplicate sweep: failed to delete row $id: ${e.message}")
            }
        }
        SyncLog.i(TAG, "Duplicate sweep: removed $removed redundant row(s) from '$calDisplayName'")
    }

    /**
     * DIAGNOSTIC ONLY (no mutation). Dumps the structure of a calendar's local rows so the
     * true duplicate mechanism is visible in a user's saved log: orphan re-inserts (two rows
     * with the same _SYNC_ID), recurring-master + un-linked RECURRENCE-ID override (an active
     * exception row whose parent master isn't present / isn't linked, which the OS then renders
     * ALONGSIDE the master's own occurrence = a visible duplicate), or stacked cancelled
     * exceptions. Titles never reach the log, not even hashed: rows with the same title share a
     * "title#n" number that means something only within this one dump. (A plain String.hashCode
     * was logged here before, and a word list recovers short titles such as "Dentist" from it.)
     * Output is capped.
     */
    private fun dumpDuplicateDiagnostics(
        provider: ContentProviderClient,
        androidCalId: Long,
        eventMappings: List<me.proton.sync.db.entities.CalendarEventSyncEntity>,
        calDisplayName: String
    ) {
        val mappedIds = eventMappings.mapNotNull { it.androidEventId }.toHashSet()
        data class Row(
            val id: Long, val syncId: String?, val origSyncId: String?, val origInst: Long,
            val status: Int, val dtStart: Long, val hasRrule: Boolean, val titleKey: String
        )
        val rows = ArrayList<Row>()
        try {
            provider.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID, CalendarContract.Events._SYNC_ID,
                    CalendarContract.Events.ORIGINAL_SYNC_ID, CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
                    CalendarContract.Events.STATUS, CalendarContract.Events.DTSTART,
                    CalendarContract.Events.RRULE, CalendarContract.Events.TITLE
                ),
                "${CalendarContract.Events.CALENDAR_ID} = ?", arrayOf(androidCalId.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(7) ?: ""
                    rows.add(
                        Row(
                            c.getLong(0), c.getString(1), c.getString(2),
                            if (c.isNull(3)) 0L else c.getLong(3),
                            if (c.isNull(4)) -1 else c.getInt(4),
                            if (c.isNull(5)) 0L else c.getLong(5),
                            !c.getString(6).isNullOrEmpty(),
                            "t${title.length}h${title.hashCode()}"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "DupDiag '$calDisplayName': query failed: ${e.message}"); return
        }

        val syncIds = rows.mapNotNull { it.syncId }.toHashSet()
        val cancelled = rows.count { it.status == CalendarContract.Events.STATUS_CANCELED }
        val overrides = rows.count { !it.origSyncId.isNullOrEmpty() }
        val masters = rows.count { it.hasRrule }
        val unmapped = rows.count { it.id !in mappedIds }
        SyncLog.i(TAG, "DupDiag '$calDisplayName': total=${rows.size} tracked=${mappedIds.size} unmapped=$unmapped cancelled=$cancelled masters(rrule)=$masters overrides(origSyncId)=$overrides")

        // (1) Override/exception rows — flag those whose parent master is absent (un-linkable)
        //     or that are ACTIVE (not cancelled), i.e. candidates to render as a visible dup.
        val titleNumbers = HashMap<String, Int>()
        fun titleLabel(key: String) = "title#" + titleNumbers.getOrPut(key) { titleNumbers.size + 1 }
        var shown = 0
        rows.filter { !it.origSyncId.isNullOrEmpty() }.forEach { r ->
            val masterPresent = r.origSyncId in syncIds
            if ((!masterPresent || r.status != CalendarContract.Events.STATUS_CANCELED) && shown++ < 25) {
                SyncLog.i(TAG, "DupDiag override id=${r.id} sync=${r.syncId?.take(8)} origSync=${r.origSyncId?.take(8)} masterPresent=$masterPresent status=${r.status} mapped=${r.id in mappedIds} ${titleLabel(r.titleKey)}")
            }
        }

        // (2) Visually-identical non-cancelled rows grouped by (titleKey, dtStart) = the dupes the user sees
        shown = 0
        rows.filter { it.status != CalendarContract.Events.STATUS_CANCELED }
            .groupBy { it.titleKey to it.dtStart }.filter { it.value.size > 1 }.forEach { (_, g) ->
                if (shown++ < 25) SyncLog.i(TAG, "DupDiag content-dup ${titleLabel(g.first().titleKey)} @${g.first().dtStart} x${g.size}: " +
                    g.joinToString("; ") { "id=${it.id} sync=${it.syncId?.take(8)} origSync=${it.origSyncId?.take(8)} rrule=${it.hasRrule} mapped=${it.id in mappedIds}" })
            }

        // (3) Same _SYNC_ID on >1 row (orphan re-inserts — what the sweep targets; expected empty here)
        shown = 0
        rows.filter { !it.syncId.isNullOrEmpty() }.groupBy { it.syncId }.filter { it.value.size > 1 }.forEach { (sid, g) ->
            if (shown++ < 15) SyncLog.i(TAG, "DupDiag same-syncId ${sid?.take(10)} x${g.size}: " +
                g.joinToString("; ") { "id=${it.id} mapped=${it.id in mappedIds} status=${it.status} origSync=${it.origSyncId != null} rrule=${it.hasRrule}" })
        }
    }

    /**
     * Detect events that were deleted locally by checking which sync mappings
     * no longer have a corresponding event in CalendarContract.
     * Delete those events on the Proton server. A suspiciously large set of deletions
     * is held for the user's confirmation instead (see BulkDeletionGuard).
     */
    private suspend fun reconcileDeletedEvents(
        account: Account,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        // Every deletion goes out through this account's own membership (ownMemberIdForWrites), found by the
        // addresses its keys were loaded for. With none loaded (the key preflight could not fetch them) no
        // membership can be found: skip the whole pass, and above all leave an approved bulk deletion alone,
        // so the next run, which reads the addresses again, carries it out instead of asking again.
        if (keyManager.getAddressEmails(account.name).isEmpty()) {
            SyncLog.w(TAG, "No addresses with keys loaded for this account: local deletions wait for the next sync")
            return
        }
        val allCalMappings = calendarSyncDao.getAllByAccount(account.name)

        // Bulk-deletion safety (see BulkDeletionGuard): if the user already confirmed via
        // the notification, carry the deletions out; otherwise hold back any suspiciously
        // large deletion and accumulate it for the confirm prompt instead of deleting on Proton.
        val deletionApproved = BulkDeletionGuard.isApproved(context, account.name)
        var heldDeletions = 0
        val heldCalendars = mutableSetOf<String>()

        for (calMapping in allCalMappings) {
            val eventMappings = calendarEventSyncDao.getAllByCalendar(account.name, calMapping.protonCalendarId)
            if (eventMappings.isEmpty()) continue

            // Batch query: get ALL existing Android event IDs for this calendar in one query
            val existingAndroidIds = mutableSetOf<Long>()
            val androidCalId = calMapping.androidCalendarId
            if (androidCalId != null) {
                try {
                    val cursor = provider.query(
                        CalendarContract.Events.CONTENT_URI,
                        arrayOf(CalendarContract.Events._ID),
                        "${CalendarContract.Events.CALENDAR_ID} = ?",
                        arrayOf(androidCalId.toString()),
                        null
                    )
                    cursor?.use { c ->
                        while (c.moveToNext()) existingAndroidIds.add(c.getLong(0))
                    }
                } catch (e: Exception) {
                    SyncLog.e(TAG, "Failed to query existing events for calendar $androidCalId", e)
                    continue
                }
            }
            SyncLog.d(TAG, "reconcileDeletedEvents: ${eventMappings.size} mappings, ${existingAndroidIds.size} local events for '${calMapping.displayName ?: calMapping.protonCalendarId}'")

            // DIAGNOSTIC (v1.6.20): dump the row structure so the true duplicate mechanism
            // is visible in a user's log even when the calendar was "unchanged, skipping"
            // (which bypasses the per-event download logging). Titles never reach the log (see
            // dumpDuplicateDiagnostics).
            if (androidCalId != null) {
                dumpDuplicateDiagnostics(provider, androidCalId, eventMappings, calMapping.displayName ?: calMapping.protonCalendarId)
            }

            // Heal already-accumulated duplicate/orphan rows (LOCAL-only, never touches Proton).
            // A local count exceeding the mapping count is the duplicate signature.
            if (androidCalId != null && existingAndroidIds.size > eventMappings.size) {
                sweepDuplicateLocalRows(provider, account, androidCalId, eventMappings, calMapping.displayName ?: calMapping.protonCalendarId)
            }

            // Find mappings whose Android event no longer exists
            val deletedMappings = eventMappings.filter { mapping ->
                mapping.androidEventId != null && mapping.androidEventId !in existingAndroidIds
            }

            if (deletedMappings.isEmpty()) continue

            if (!deletionApproved &&
                BulkDeletionGuard.isSuspicious(deletedMappings.size, existingAndroidIds.size, eventMappings.size)) {
                heldDeletions += deletedMappings.size
                heldCalendars.add(calMapping.protonCalendarId)
                SyncLog.w(TAG, "Suspicious bulk deletion HELD for '${calMapping.displayName ?: calMapping.protonCalendarId}': " +
                    "${deletedMappings.size}/${eventMappings.size} mapped events missing locally (local=${existingAndroidIds.size}); " +
                    "NOT deleting on Proton - awaiting user confirmation")
                continue
            }

            SyncLog.i(TAG, "${deletedMappings.size} events deleted locally, syncing deletions to Proton")

            // Get member ID once for all deletions in this calendar
            val calResponse = apiClient.getCalendars(account.name)
            val calInfo = calResponse.body()?.calendars?.firstOrNull { it.id == calMapping.protonCalendarId }
            // Our own membership. Another member's id is not ours to delete through, not even as a fallback.
            val memberId = ownMemberIdForWrites(account, calInfo)
            if (memberId == null) {
                SyncLog.e(TAG, "Could not find member ID for calendar ${calMapping.protonCalendarId}")
                continue
            }

            for (mapping in deletedMappings) {
                SyncLog.i(TAG, "Event ${mapping.protonEventId} deleted locally (Android ID ${mapping.androidEventId}), deleting on Proton")
                try {
                    val response = apiClient.syncCalendarEvents(
                        accountName = account.name,
                        calendarId = mapping.protonCalendarId,
                        body = CalendarEventSyncRequest(
                            memberId = memberId,
                            events = listOf(CalendarEventSyncOperation(id = mapping.protonEventId, deletionReason = 0))
                        )
                    )
                    // By the operation's own verdict, as in the upload's delete path (settleDeleteResponse).
                    val op = if (response.isSuccessful) firstSyncOpResult(response.body()) else null
                    val errorBody = if (op != null) null else (response.errorBody()?.string() ?: "no body")
                    val verdict = when {
                        op != null -> deleteOpVerdict(op.code, op.error)
                        response.code() == 400 && (hasErrorCode(errorBody!!, 2011) || hasErrorCode(errorBody, 2026)) ->
                            DeleteOpVerdict.REFUSED
                        else -> null
                    }
                    when (verdict) {
                        DeleteOpVerdict.DELETED, DeleteOpVerdict.ALREADY_GONE -> {
                            calendarEventSyncDao.deleteByProtonId(account.name, mapping.protonEventId)
                            clearDeleteRejectionStrikes(deleteStrikeKey(null, mapping.protonEventId))
                            syncResult.stats.numDeletes++
                            SyncLog.i(TAG, "Successfully deleted event ${mapping.protonEventId} from Proton (opCode=${op?.code ?: "none"})")
                        }
                        DeleteOpVerdict.REFUSED -> {
                            // The row is already gone here: drop the mapping and let the next sync download it again.
                            calendarEventSyncDao.deleteByProtonId(account.name, mapping.protonEventId)
                            clearDeleteRejectionStrikes(deleteStrikeKey(null, mapping.protonEventId))
                            resetCalendarCursor(account, mapping.protonCalendarId)
                            SyncLog.w(TAG, "Proton refuses deleting event ${mapping.protonEventId} (code=${op?.code ?: 400}); " +
                                "it comes back with the next sync")
                        }
                        DeleteOpVerdict.REJECTED -> {
                            // Three strikes, as the upload's delete path: an unknown rejection must not be re-sent for
                            // ever, and while the mapping stands the download's per-event skip keeps the event away
                            // from the phone as well.
                            val strikeKey = deleteStrikeKey(null, mapping.protonEventId)
                            if (bumpDeleteRejectionStrike(strikeKey, mapping.protonEventId, op ?: SyncOpResult(null, null))) {
                                calendarEventSyncDao.deleteByProtonId(account.name, mapping.protonEventId)
                                resetCalendarCursor(account, mapping.protonCalendarId)
                                SyncLog.w(TAG, "Giving up the delete of event ${mapping.protonEventId}; it comes back with the next sync")
                            }
                        }
                        null -> SyncLog.e(TAG, "Failed to delete event on Proton: HTTP ${response.code()} - $errorBody")
                    }
                } catch (e: Exception) {
                    SyncLog.e(TAG, "Network error deleting event ${mapping.protonEventId}", e)
                }
            }
        }

        // After the per-calendar pass: raise/refresh the confirm prompt for any held
        // deletions, or — if the user had approved and we just carried them out — clear it.
        if (heldDeletions > 0) {
            BulkDeletionGuard.markPending(context, account.name, heldDeletions, heldCalendars)
            SyncNotificationHelper.showBulkDeletionPrompt(context, account.name, heldDeletions)
        } else if (deletionApproved) {
            BulkDeletionGuard.clear(context, account.name)
            SyncNotificationHelper.cancelBulkDeletionPrompt(context, account.name)
        }
    }

    /**
     * Subset of [CalendarEventDetail] that uploadModifiedEvent's UPDATE path needs.
     * Cached on [CalendarEventSyncEntity] so we can skip the per-event GET round-trip
     * (~230 ms) when re-uploading the same event.
     */
    private data class EventCacheFields(
        val sharedKeyPacket: String?,
        val dtStartLine: String?,
        val dtEndLine: String?,
        val rrule: String?,
        val uid: String?,
        val isOrganizer: Int?,
        // RECURRENCE-ID of a single-occurrence override (null for masters/standalone).
        // Persisted as cached_recurrence_id so the master-selection DB fallback can
        // exclude RANGE=THISANDFUTURE overrides (which also carry an RRULE).
        val recurrenceId: String?
    )

    /**
     * Extract the type-2 (cleartext signed) iCal lines we compare against for SEQUENCE
     * diffing, plus the SharedKeyPacket and isOrganizer flag. Only meaningful for
     * organizer-owned events; for events we were invited to (no SharedKeyPacket) the
     * upload path needs the full GET anyway.
     */
    private fun extractEventCacheFields(event: CalendarEventDetail): EventCacheFields {
        val type2Data = event.sharedEvents
            .filter { it.type == 2 }
            .joinToString("\n") { it.data.replace("\r\n ", "").replace("\r\n\t", "") }
        val structural = ICalUtils.type2StructuralLines(type2Data)
        // No trailing colon: RECURRENCE-ID can carry params (;TZID=, ;VALUE=DATE).
        // Matches the in-memory master pre-pass classifier in syncCalendarEvents.
        val recurrenceId = type2Data.lines().firstOrNull { it.startsWith("RECURRENCE-ID") }
            ?.replace("\r", "")?.trim()
        return EventCacheFields(
            sharedKeyPacket = event.sharedKeyPacket,
            dtStartLine = structural.dtStart,
            dtEndLine = structural.dtEnd,
            rrule = structural.rrule,
            uid = event.uid.takeIf { it.isNotBlank() },
            isOrganizer = event.isOrganizer,
            recurrenceId = recurrenceId
        )
    }

    /** The five values the Proton-attendee rewrap either replaces together or leaves exactly as they were. */
    private class AttendeeRewrap(
        val addedProtonAttendees: List<ProtonAttendeeKeyPacket>?,
        val finalSharedKeyPacketB64: String,
        val finalDataPacketB64: String,
        val finalSessionKey: Pair<Int, ByteArray>?,
        val attendeesEventContent: List<EventPart>?
    )

    /** What the download must do about a local row that also changed. */
    private enum class LocalCollision {
        /** No two-sided edit: apply the server's version and let the dirty flag clear as usual. */
        APPLY_SERVER,
        /** Apply the server's shared fields but KEEP dirty, so the pending personal part still uploads. */
        PRESERVE_LOCAL_DIRTY,
        /** Touch neither side and write nothing: held, keep-mine, or a collision just raised. */
        SKIP_EVENT
    }

    /** What a create needs after its payload is built: the operation, and the three values read afterwards. */
    private class CreatePayload(
        val syncOp: CalendarEventSyncOperation,
        val overridePlan: GuestStatusPlan?,
        val finalSharedKeyPacketB64: String,
        val finalSessionKey: Pair<Int, ByteArray>?
    )

    /** What an update needs to know about the server's copy before it can build anything. */
    private class UpdateBaseline(
        val existingSharedKeyPacket: String?,
        val existingEvent: CalendarEventDetail?,
        val isOrganizerOnServer: Int,
        val authoritativeUid: String?,
        val serverDtStart: String?,
        val serverDtEnd: String?,
        val serverRrule: String?
    )

    /** One row of the dirty/deleted set the upload walks. */
    private data class DirtyRow(val androidEventId: Long, val androidCalId: Long, val deleted: Int)

    /**
     * Per-calendar metadata captured once before the per-event upload loop.
     *
     * Each event upload needs the calendar's memberId (from /calendars), its keys,
     * and unlocked private keys held in KeyManager. Re-fetching these per event
     * costs three sequential HTTP round-trips (~400 ms) and dominates the upload
     * loop wall time. We snapshot them once per unique calendar, then reuse.
     */
    private data class CalendarUploadContext(
        val protonCalendarId: String,
        val calInfo: CalendarInfo,
        val memberId: String,
        /** Needed so an upload can tell "inherits the calendar defaults" from "pinned reminders". */
        val calendarSettings: CalendarSettingsData? = null
    )

    /**
     * Build a [CalendarUploadContext] for the given Android calendar id by
     * resolving the Proton calendar, fetching the calendar entry (for memberId
     * and member email), then fetching keys + passphrases and unlocking each
     * key into KeyManager. Returns null when the calendar can't be prepared
     * (no mapping, no member of this account's own ([ownMemberIdForWrites]), or
     * network failure looking up keys).
     */
    private suspend fun prepareCalendarUploadContext(
        account: Account,
        androidCalId: Long
    ): CalendarUploadContext? {
        val calMapping = calendarSyncDao.getAllByAccount(account.name)
            .firstOrNull { it.androidCalendarId == androidCalId }
            ?: return null
        val protonCalendarId = calMapping.protonCalendarId

        val calResponse = apiClient.getCalendars(account.name)
        val calInfo = calResponse.body()?.calendars?.firstOrNull { it.id == protonCalendarId }
            ?: return null
        val memberId = ownMemberIdForWrites(account, calInfo) ?: return null

        val calKeys = fetchCalendarKeys(account.name, protonCalendarId)
        val calPassphrases = fetchCalendarPassphrases(account.name, protonCalendarId)
        var anyCalendarKeyUnlocked = false
        for (calKey in calKeys) {
            val passphrase = calPassphrases.firstOrNull { it.id == calKey.passphraseId } ?: continue
            for (mp in passphrase.memberPassphrases) {
                try {
                    keyManager.unlockCalendarKey(
                        accountName = account.name,
                        calendarId = protonCalendarId,
                        isPrimary = isPrimaryCalendarKey(calKey.flags),
                        encryptedPassphrase = mp.passphrase,
                        armoredCalendarKey = calKey.privateKey
                    )
                    anyCalendarKeyUnlocked = true
                    break
                } catch (_: Exception) { /* Intentional: try next member passphrase — only one will match for the current address key */ }
            }
        }
        if (calKeys.isNotEmpty() && !anyCalendarKeyUnlocked) {
            // Every member passphrase failing is what a stale key ring looks like: the passphrase
            // is wrapped to the primary ADDRESS key, and Proton generates new address keys on a
            // live account (opting into post-quantum crypto does exactly that). Drop the cached
            // ring so the next sync re-reads the keys, instead of failing identically until the
            // TTL expires or the process dies.
            SyncLog.w(TAG, "No calendar key unlocked for $protonCalendarId; forcing a key refresh on the next sync")
            SyncUtils.invalidateKeyCache(account.name)
            // And refuse the context. Handing one back meant every create in this calendar failed to
            // encrypt, cleared its own dirty flag and was never retried: the event stayed on the phone,
            // never reached Proton, and the sync reported success. With no context the rows are skipped
            // with their dirty flag intact, so the next sync, which re-reads the keys because the cache
            // was just invalidated, uploads them.
            SyncUtils.recordSwallowedFailure(
                "calendar", account.name,
                "None of the keys for one calendar could be unlocked, so nothing was uploaded to it. This usually means the account's address keys changed; the next sync re-reads them."
            )
            return null
        }

        return CalendarUploadContext(
            protonCalendarId = protonCalendarId,
            calInfo = calInfo,
            memberId = memberId,
            calendarSettings = fetchCalendarSettings(account.name, protonCalendarId)
        )
    }

    /**
     * The memberId this account writes [calInfo] through: its own entry ([ownMember]), never simply the
     * first one. Null when the calendar is unknown, or when no entry is ours, which gets a line of its
     * own: the caller then sends nothing to this calendar, and its changes wait on the phone for a later
     * sync instead of going up under somebody else's membership. Non-suspend, so it adds no state to the
     * upload's coroutines.
     */
    private fun ownMemberIdForWrites(account: Account, calInfo: CalendarInfo?): String? {
        if (calInfo == null) return null
        val userAddresses = keyManager.getAddressEmails(account.name)
        val member = ownMember(calInfo.members, userAddresses)
        if (member == null) {
            SyncLog.w(TAG, "No member of calendar ${calInfo.id} is this account's " +
                "(${calInfo.members?.size ?: 0} member(s), ${userAddresses.size} address(es) with keys): " +
                "nothing is written to it this run, and never through another member")
            if (!calInfo.members.isNullOrEmpty()) {
                // A membership on none of the addresses whose keys were loaded is what a key ring older than the
                // account looks like: a calendar on an address added since. Read the keys again at the next sync,
                // as the "No calendar key unlocked" path does, rather than wait out the key cache.
                SyncUtils.invalidateKeyCache(account.name)
                SyncUtils.recordSwallowedFailure(
                    "calendar", account.name,
                    "One calendar belongs to an address this app has not loaded yet, so nothing was uploaded to it. The next sync re-reads the account's addresses."
                )
            }
        }
        return member?.id
    }

    /**
     * Events we gave up uploading this run (after the 3-strike cap in [handleUploadOpRejection]),
     * as (title, reason) pairs keyed by account so concurrent per-account syncs don't collide.
     * Drained at the end of [uploadDirtyEvents] into the aggregated "couldn't sync" notification
     * so a permanently-rejected event is surfaced to the user instead of vanishing silently.
     */
    private val gaveUpByAccount = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<String, String>>>()

    /**
     * After a forced Remote→Local download: clear DIRTY on the rows the download wrote, and keep it
     * on events created on the phone that have not reached Proton yet.
     */
    private fun clearDirtyAfterForcedDownload(provider: ContentProviderClient, account: Account) {
        // v1.4.18: bulk-clear DIRTY on every event we just wrote for this
        // account's calendars. Without this, Android's SyncManager auto-queues a
        // follow-up sync triggered by Samsung's DIRTY=1 flip, and that follow-up
        // runs as a normal "Scheduled sync" (not ManualForceRemoteToLocal) and
        // re-uploads all of them.
        //
        // Only rows this download wrote: every one carries a Proton _SYNC_ID, or an
        // ORIGINAL_SYNC_ID when it is an exception of a Proton series. The local wipe
        // deliberately keeps events created on the phone and not uploaded yet (no
        // mapping, no server copy), and clearing THEIR flag hid them from every later
        // upload, which only selects DIRTY or DELETED rows: kept on the phone, never on
        // Proton, with nothing to say so.
        //
        // And only live rows: a deletion made on the phone while this run was downloading keeps its
        // flag, so the next sync still deletes the event on Proton.
        val downloadedRow =
            "(${CalendarContract.Events._SYNC_ID} IS NOT NULL OR ${CalendarContract.Events.ORIGINAL_SYNC_ID} IS NOT NULL)"
        val liveRow = "${CalendarContract.Events.DELETED} = 0"
        var cleared = 0
        var keptLocal = 0
        try {
            val calIds = mutableListOf<Long>()
            provider.query(
                SyncUtils.asCallerSyncAdapter(CalendarContract.Calendars.CONTENT_URI, account),
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(account.name, account.type),
                null
            )?.use { c -> while (c.moveToNext()) calIds.add(c.getLong(0)) }
            if (calIds.isNotEmpty()) {
                val placeholders = calIds.joinToString(",") { "?" }
                val inCalendars = "${CalendarContract.Events.CALENDAR_ID} IN ($placeholders)"
                val args = calIds.map { it.toString() }.toTypedArray()
                cleared = provider.update(
                    SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                    ContentValues().apply { put(CalendarContract.Events.DIRTY, 0) },
                    "$inCalendars AND $downloadedRow AND $liveRow",
                    args
                )
                provider.query(
                    SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                    arrayOf(CalendarContract.Events._ID),
                    "$inCalendars AND NOT $downloadedRow AND $liveRow AND ${CalendarContract.Events.DIRTY} = 1",
                    args,
                    null
                )?.use { keptLocal = it.count }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Bulk-clear DIRTY failed: ${e.message}")
        }
        SyncLog.i(
            TAG,
            "Skipping dirty-row upload (forced Remote→Local - cleared DIRTY on $cleared downloaded events; " +
                "pending edits to Proton events are intentionally discarded; $keptLocal event(s) created on " +
                "the phone and not uploaded yet stay pending and upload on the next sync)"
        )
    }

    private suspend fun uploadDirtyEvents(
        account: Account,
        provider: ContentProviderClient,
        syncResult: SyncResult,
        triggerType: SyncTriggerType
    ) {
        gaveUpByAccount[account.name] = java.util.Collections.synchronizedList(mutableListOf())
        // Remote→Local force sync wipes the local side and re-fetches everything from
        // Proton. Samsung's CalendarProvider flips DIRTY=1 on rows we just inserted
        // (even via asCallerSyncAdapter), so without this short-circuit we'd then
        // upload every freshly-downloaded event back to Proton — wasting bandwidth,
        // bumping SEQUENCE, and generating CalendarModelEvents that defeat the
        // model-event-id shortcut on the next sync. Skip the upload entirely;
        // pending edits made before the user pressed Remote→Local are intentionally
        // discarded (that's what the user asked for). v1.4.17.
        if (triggerType == SyncTriggerType.ManualForceRemoteToLocal) {
            clearDirtyAfterForcedDownload(provider, account)
            return
        }
        // Forced full re-syncs (either direction) re-upload events as a reconciliation,
        // not because the user just edited them. Treating each one as a "user just
        // edited this event" would spam attendees with hundreds of invite/update emails
        // for events that didn't actually change. Suppress all attendee emails for the
        // duration of a forced sync, regardless of direction.
        val suppressAttendeeEmails = triggerType == SyncTriggerType.ManualForceLocalToRemote
        val dirtyRows = readDirtyRows(
            account = account,
            provider = provider
        ) ?: return
        val calendarContexts = prepareUploadContexts(
            account = account,
            dirtyRows = dirtyRows,
            syncResult = syncResult
        )

        // Run uploads with bounded concurrency. 8-way was triggering Proton's
        // "Too many recent API requests" (HTTP 429) on bulk forced syncs — each event
        // makes 1–2 API calls, so 8 in flight ≈ 24 req/s into the rate limiter. 3 is
        // the empirical sweet spot: still ~3× faster than serial, no 429s observed.
        val uploadSemaphore = Semaphore(3)
        // One parent EXDATE upload per series per run, shared by all its cancelled occurrences.
        val parentExdateLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
        val parentExdateOutcomes = java.util.concurrent.ConcurrentHashMap<Long, UploadOutcome>()
        val tombstoneCount = java.util.concurrent.atomic.AtomicInteger(0)
        // Counts every row that finished its DELETE/MODIFY/SKIP path so the ongoing
        // notification can show "uploading 50/600" progress. Bumped in a finally so
        // failures and skips still advance the bar.
        val processedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val totalRows = dirtyRows.size
        // syncResult.stats fields are plain Longs; we use `syncResult.stats` itself as
        // the lock target so the same monitor is shared with stats++ inside
        // uploadModifiedEvent (called from this parallel block).
        coroutineScope { dirtyRows.map { row ->
            async(Dispatchers.IO) {
                uploadSemaphore.withPermit {
                    try {
                        if (row.deleted == 1) {
                            uploadDeletedRow(
                                account = account,
                                provider = provider,
                                row = row,
                                calendarContexts = calendarContexts,
                                suppressAttendeeEmails = suppressAttendeeEmails,
                                syncResult = syncResult,
                                tombstoneCount = tombstoneCount
                            )
                        } else {
                            uploadModifiedRow(
                                account = account,
                                provider = provider,
                                row = row,
                                calendarContexts = calendarContexts,
                                parentExdateLocks = parentExdateLocks,
                                parentExdateOutcomes = parentExdateOutcomes,
                                suppressAttendeeEmails = suppressAttendeeEmails,
                                syncResult = syncResult
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One row must not take the run down with it. These are siblings in a
                        // coroutineScope, so anything thrown here cancelled every other upload in flight
                        // and the whole upload phase with them: a single unreadable row, or a database
                        // error on one deletion, discarded every other event's upload for that sync,
                        // reported as one failure. The row keeps its dirty flag and comes back next sync.
                        SyncLog.e(
                            TAG,
                            "Upload of event ${row.androidEventId} failed; the rest of this upload continues and the event tries again next sync",
                            e
                        )
                        synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ }
                    } finally {
                        val n = processedCount.incrementAndGet()
                        if (n % 50 == 0 || n == totalRows) {
                            SyncNotificationHelper.updateProgress(
                                context, account.name, "calendar",
                                "uploading", n, totalRows
                            )
                        }
                    }
                }
            }
        }.awaitAll() }
        val finalTombstones = tombstoneCount.get()
        if (finalTombstones > 0) {
            SyncLog.i(TAG, "Cleaned up $finalTombstones unmapped tombstones total")
        }

        // Cancellations an earlier sync could not send. After this run's uploads, so a guest re-added in the
        // same run is already off the queue; ones queued just now wait for the next sync (their turn is later).
        if (!suppressAttendeeEmails) retryPendingCancellations(account, provider)

        // Surface any events we permanently gave up on (3-strike cap) so the user knows
        // — listing each title with the server's reason — instead of them vanishing silently.
        val gaveUp = gaveUpByAccount.remove(account.name)
        if (!gaveUp.isNullOrEmpty()) {
            SyncNotificationHelper.notifyEventsRejected(context, account.name, gaveUp.toList())
        }
    }

    /**
     * The rows this upload has to deal with: every dirty or deleted event of this account's calendars, minus the
     * ones held for a collision decision. Null when there is nothing to do at all (no calendars of ours, or the
     * provider gave no cursor), which keeps the caller from draining anything.
     */
    private suspend fun readDirtyRows(account: Account, provider: ContentProviderClient): MutableList<DirtyRow>? {
        // Collect all local calendar IDs belonging to this account.
        val calIds = mutableListOf<Long>()
        val calCursor = provider.query(
            SyncUtils.asCallerSyncAdapter(CalendarContract.Calendars.CONTENT_URI, account),
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(account.name, account.type),
            null
        )
        calCursor?.use { c -> while (c.moveToNext()) calIds.add(c.getLong(0)) }
        if (calIds.isEmpty()) return null

        // Query dirty events (includes soft-deleted rows where DELETED=1).
        // CalendarContract keeps deleted rows until the sync adapter removes them.
        val placeholders = calIds.joinToString(",") { "?" }
        val cursor = provider.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.DELETED
            ),
            "(${CalendarContract.Events.DIRTY} = 1 OR ${CalendarContract.Events.DELETED} = 1) AND ${CalendarContract.Events.CALENDAR_ID} IN ($placeholders)",
            calIds.map { it.toString() }.toTypedArray(),
            null
        ) ?: return null

        // Snapshot the rows so we can close the cursor before making network calls.
        val dirtyRows = mutableListOf<DirtyRow>()
        try {
            while (cursor.moveToNext()) {
                dirtyRows.add(
                    DirtyRow(
                        androidEventId = cursor.getLong(0),
                        androidCalId   = cursor.getLong(1),
                        deleted        = cursor.getInt(2)
                    )
                )
            }
        } finally {
            cursor.close()
        }

        // Events awaiting a collision decision are HELD: the download already refused to apply the
        // server's version, so the upload must equally refuse to push the local one. Otherwise
        // "hold both until the user chooses" would quietly become "local always wins".
        val heldProtonIds = EventConflictGuard.heldIds(context, account.name)
        if (heldProtonIds.isNotEmpty() && dirtyRows.isNotEmpty()) {
            // One lookup per held id rather than a full-table scan: holds are rare and usually
            // number in the single digits.
            val heldByAndroidId = HashMap<Long, String>()
            for (protonId in heldProtonIds) {
                calendarEventSyncDao.getByProtonId(account.name, protonId)?.androidEventId
                    ?.let { heldByAndroidId[it] = protonId }
            }
            // A DELETION is not a "which version do you want" question: the user has answered it, and the
            // answer is neither. Holding it back like an edit left the deletion unsent on every run, so the
            // event stayed in the user's Proton calendar while the phone showed it gone, and the prompt kept
            // asking about an event that was no longer there. Found on a device, not in review.
            for (row in dirtyRows.filter { it.deleted == 1 && it.androidEventId in heldByAndroidId }) {
                val protonId = heldByAndroidId.getValue(row.androidEventId)
                EventConflictGuard.release(context, account.name, protonId)
                EventConflictGuard.clearLocalWins(context, account.name, protonId)
                SyncNotificationHelper.cancelEventConflict(context, account.name, protonId)
                heldByAndroidId.remove(row.androidEventId)
                SyncLog.i(
                    TAG,
                    "Collision on $protonId resolved by a local deletion: sending the deletion and taking " +
                        "the prompt down"
                )
            }
            val before = dirtyRows.size
            dirtyRows.removeAll { it.androidEventId in heldByAndroidId }
            val held = before - dirtyRows.size
            if (held > 0) {
                SyncLog.i(TAG, "Holding $held event(s) out of this upload: awaiting the user's collision decision")
            }
        }

        if (dirtyRows.isNotEmpty()) {
            SyncLog.i(TAG, "Found ${dirtyRows.size} dirty/deleted events to process")
        }
        return dirtyRows
    }

    /**
     * The upload context of every calendar these rows belong to, fetched once per calendar rather than per event.
     */
    private suspend fun prepareUploadContexts(
        account: Account,
        dirtyRows: List<DirtyRow>,
        syncResult: SyncResult
    ): Map<Long, CalendarUploadContext?> {
        // Pre-fetch per-calendar metadata once so we don't pay 3 round-trips per event
        // (getCalendars + fetchCalendarKeys + fetchCalendarPassphrases). The keys are
        // already memoized inside KeyManager.unlockCalendarKey, but the surrounding
        // HTTP fetches are not.
        val uniqueAndroidCalIds = dirtyRows.map { it.androidCalId }.toSet()
        val calendarContexts = mutableMapOf<Long, CalendarUploadContext?>()
        for (androidCalId in uniqueAndroidCalIds) {
            calendarContexts[androidCalId] = try {
                prepareCalendarUploadContext(account, androidCalId)
            } catch (e: Exception) {
                // Network drops while preparing the upload context (e.g. UnknownHostException
                // from getCalendars / fetchCalendarKeys) used to silently null out this
                // calendar's context — the per-event uploads then no-op and the sync was
                // recorded as success. Bump numIoExceptions so the framework retries AND
                // doPerformSync's hasError() check writes the failure timestamp.
                SyncLog.e(TAG, "Failed to prepare upload context for androidCalId=$androidCalId: ${e.message}", e)
                synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ }
                SyncUtils.recordSwallowedFailure("calendar", account.name, e.message)
                null
            }
        }
        return calendarContexts
    }

    /** One deleted row: tell Proton, or just drop a tombstone that was never synced. */
    private suspend fun uploadDeletedRow(
        account: Account,
        provider: ContentProviderClient,
        row: DirtyRow,
        calendarContexts: Map<Long, CalendarUploadContext?>,
        suppressAttendeeEmails: Boolean,
        syncResult: SyncResult,
        tombstoneCount: java.util.concurrent.atomic.AtomicInteger
    ) {
        // ---- DELETE path ----
        val mapping = calendarEventSyncDao.getByAndroidId(account.name, row.androidEventId)
        if (mapping != null) {
            val uploadContext = calendarContexts[row.androidCalId]
            if (uploadContext == null) {
                SyncLog.e(TAG, "Could not find member ID for calendar ${mapping.protonCalendarId}")
                return
            }
            val deleteCalInfo = uploadContext.calInfo
            val memberId = uploadContext.memberId
            val response = try {
                apiClient.syncCalendarEvents(
                    accountName = account.name,
                    calendarId  = mapping.protonCalendarId,
                    body = CalendarEventSyncRequest(
                        memberId = memberId,
                        events = listOf(CalendarEventSyncOperation(id = mapping.protonEventId, deletionReason = 0))
                    )
                )
            } catch (e: Exception) {
                SyncLog.e(TAG, "Network error deleting event ${mapping.protonEventId}", e)
                synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ }
                return
            }

            settleDeleteResponse(
                account, provider, row.androidEventId, mapping, deleteCalInfo, response, suppressAttendeeEmails, syncResult
            )
        } else {
            // No mapping means the event was never synced to Proton (e.g. created
            // and deleted locally between syncs). Just remove the tombstone.
            val n = tombstoneCount.incrementAndGet()
            if (n % 50 == 0) {
                SyncLog.i(TAG, "Cleaned up $n unmapped tombstones...")
            }
            try {
                provider.delete(
                    ContentUris.withAppendedId(
                        SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                        row.androidEventId
                    ),
                    null, null
                )
            } catch (e: Exception) {
                SyncLog.w(TAG, "Could not remove tombstone for unmapped local event ${row.androidEventId}", e)
            }
        }
    }

    /** One changed row: a cancelled occurrence goes up as its parent's EXDATE set, anything else as itself. */
    private suspend fun uploadModifiedRow(
        account: Account,
        provider: ContentProviderClient,
        row: DirtyRow,
        calendarContexts: Map<Long, CalendarUploadContext?>,
        parentExdateLocks: java.util.concurrent.ConcurrentHashMap<Long, Mutex>,
        parentExdateOutcomes: java.util.concurrent.ConcurrentHashMap<Long, UploadOutcome>,
        suppressAttendeeEmails: Boolean,
        syncResult: SyncResult
    ) {
        // ---- MODIFIED path: upload create/update to Proton ----
        // Check if this is a single-occurrence deletion (exception with STATUS_CANCELED)
        val exceptionCheck = try {
            provider.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, row.androidEventId),
                arrayOf(CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.STATUS),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) Pair(
                    if (!c.isNull(0)) c.getLong(0) else null,
                    c.getInt(1)
                ) else null
            }
        } catch (_: Exception) { null }

        if (exceptionCheck != null && exceptionCheck.first != null &&
            exceptionCheck.second == CalendarContract.Events.STATUS_CANCELED) {
            // This is a single-occurrence deletion of a recurring event.
            // Android does NOT update the parent's EXDATE — the sync adapter must
            // construct EXDATE from the exception's ORIGINAL_INSTANCE_TIME and send
            // it to the server.
            val parentId = exceptionCheck.first!!
            SyncLog.i(TAG, "Local event ${row.androidEventId} is a cancelled occurrence (parent=$parentId)")
            // This row is the only record of the deletion, so it stays pending until the
            // parent's update is settled: accepted by Proton, or given up for good and
            // reported. It used to be cleared after any attempt, so a dropped connection or an
            // HTTP error lost the deletion: the phone hid the occurrence, Proton and every
            // other device kept it, and nothing ever tried again.
            //
            // Every cancelled occurrence of the same series asks for exactly the same upload (the
            // parent carrying the complete EXDATE set), so the parent goes up once per run and the
            // other rows read that outcome. Cancelling two occurrences used to send the same
            // update twice in parallel, racing on SEQUENCE and spending two of the parent's three
            // rejection strikes on one attempt.
            val uploadContext = calendarContexts[row.androidCalId]
            val parentOutcome = if (uploadContext == null) {
                SyncLog.e(TAG, "Skipping cancelled-occurrence parent $parentId: no calendar context for androidCalId=${row.androidCalId}")
                null
            } else {
                parentExdateLocks.computeIfAbsent(parentId) { Mutex() }.withLock {
                    parentExdateOutcomes[parentId]?.also {
                        SyncLog.d(TAG, "Parent $parentId already went up in this run ($it); reusing that outcome")
                    } ?: uploadCancelledOccurrenceParent(
                        account, provider, parentId, row.androidCalId, syncResult, uploadContext, suppressAttendeeEmails
                    ).also { parentExdateOutcomes[parentId] = it }
                }
            }
            settleCancelledOccurrence(provider, account, row.androidEventId, parentId, parentOutcome)
        } else {
            val uploadContext = calendarContexts[row.androidCalId]
            if (uploadContext == null) {
                SyncLog.e(TAG, "Skipping event ${row.androidEventId}: no calendar context for androidCalId=${row.androidCalId}")
            } else {
                SyncLog.i(TAG, "Local event ${row.androidEventId} modified; uploading to Proton")
                try {
                    // The same event can be asked for twice in one run: once as itself, because the user
                    // edited it, and once as the parent carrying a cancelled occurrence's EXDATE. They ran
                    // in parallel on separate coroutines, raced on SEQUENCE, and spent two of the parent's
                    // three rejection strikes on one attempt. One lock per event id serialises them.
                    //
                    // Serialising alone is not enough, because order then decides the outcome: the plain
                    // payload carries no EXDATE, since Android never writes one onto the parent, so if it
                    // landed second it undid the deletion and the occurrence came back everywhere. Both
                    // payloads now carry the same set, collected the same way, so the order stops
                    // mattering. For an event with no cancelled occurrences the set is empty.
                    val (ownExdates, ownExdateTz) = collectCancelledExdates(provider, row.androidEventId)
                    parentExdateLocks.computeIfAbsent(row.androidEventId) { Mutex() }.withLock {
                        uploadModifiedEvent(
                            account, provider, row.androidEventId, row.androidCalId, syncResult,
                            uploadContext, suppressAttendeeEmails,
                            extraExdates = ownExdates, extraExdateTz = ownExdateTz
                        )
                    }
                } catch (e: Exception) {
                    SyncLog.e(TAG, "Failed to upload modified event ${row.androidEventId}", e)
                    synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ }
                    // Without this the run ends "Calendar sync FAILED: no reason recorded" and the
                    // notification says "an unexpected error occurred", while the cause sits one line
                    // above in the log. Every sibling failure path already names its reason.
                    SyncUtils.recordSwallowedFailure(
                        "calendar", account.name, "Could not upload an event to Proton: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Settle a delete Proton answered, by its operation's own verdict: HTTP 200 alone says nothing,
     * and a refused delete used to count as done.
     *
     * Deleted, or already gone (2501): the event's records here go, and the guests get a cancellation
     * when this delete removed it (whoever deleted it elsewhere was offered to tell them by their own
     * client). Refused (2011 or 2026, per operation or as HTTP 400): Proton keeps the event, so it is
     * restored ([restoreRefusedDelete]) instead of its guests being told it is cancelled. Any other
     * rejection is tried twice more, then restored the same way; an HTTP error is retried next sync.
     */
    private suspend fun settleDeleteResponse(
        account: Account,
        provider: ContentProviderClient,
        androidEventId: Long,
        mapping: CalendarEventSyncEntity,
        calInfo: CalendarInfo,
        response: retrofit2.Response<Map<String, Any>>,
        suppressAttendeeEmails: Boolean,
        syncResult: SyncResult
    ) {
        if (response.isSuccessful) {
            val op = firstSyncOpResult(response.body())
            when (val verdict = deleteOpVerdict(op.code, op.error)) {
                DeleteOpVerdict.DELETED, DeleteOpVerdict.ALREADY_GONE -> {
                    if (verdict == DeleteOpVerdict.DELETED) {
                        SyncLog.i(TAG, "Deleted event ${mapping.protonEventId} from Proton (local id $androidEventId, opCode=${op.code ?: "none"})")
                        if (!suppressAttendeeEmails) sendDeletionCancellations(account, provider, androidEventId, mapping, calInfo)
                    } else {
                        SyncLog.i(TAG, "Event ${mapping.protonEventId} was already gone from Proton (code=${op.code}); removing it here, nobody is emailed")
                    }
                    forgetInviteRecords(mapping.protonEventId)
                    clearDeleteRejectionStrikes(deleteStrikeKey(androidEventId, mapping.protonEventId))
                    calendarEventSyncDao.deleteByProtonId(account.name, mapping.protonEventId)
                    removeTombstone(provider, account, androidEventId)
                    synchronized(syncResult.stats) { syncResult.stats.numDeletes++ }
                }
                DeleteOpVerdict.REFUSED -> {
                    SyncLog.w(TAG, "Proton refuses deleting event ${mapping.protonEventId} (code=${op.code} error='${op.error}'); " +
                        "restoring it on the phone, nobody is emailed")
                    restoreRefusedDelete(account, provider, mapping, androidEventId, deleteRefusalReason(), syncResult)
                }
                DeleteOpVerdict.REJECTED -> {
                    if (bumpDeleteRejectionStrike(deleteStrikeKey(androidEventId, mapping.protonEventId), mapping.protonEventId, op)) {
                        restoreRefusedDelete(account, provider, mapping, androidEventId, op.error ?: "Proton error code ${op.code}", syncResult)
                    } else {
                        synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ }
                    }
                }
            }
            return
        }
        // v1.4.24: include calendar ID + response body so we can tell *why* a particular event delete
        // is rejected (e.g. attendee events that need RSVP=declined instead of DELETE).
        val errorBody = try { response.errorBody()?.string()?.take(500) } catch (_: Exception) { null }
        // v1.4.25: Code 2011 = "This calendar does not allow editing events": the event is on a
        // subscribed (Type=1) or holiday (Type=2) calendar that's read-only on Proton. 2026 ("insufficient
        // permissions") means the same for a view-only SHARE of a Type-0 calendar; without handling it the
        // tombstone was re-sent on every sync for ever. Matching is exact, because a plain contains() also
        // matches Code 20110, and throwing away a user's deletion on an unrelated error is not recoverable.
        val isReadOnlyCalendar = response.code() == 400 && errorBody != null &&
            (hasErrorCode(errorBody, 2011) || hasErrorCode(errorBody, 2026))
        if (isReadOnlyCalendar) {
            SyncLog.i(TAG, "Event ${mapping.protonEventId} is on a read-only Proton calendar (${mapping.protonCalendarId}); " +
                "restoring it on the phone")
            restoreRefusedDelete(account, provider, mapping, androidEventId, deleteRefusalReason(), syncResult)
        } else {
            SyncLog.e(TAG, "Failed to delete event ${mapping.protonEventId} on Proton (calendar=${mapping.protonCalendarId}): HTTP ${response.code()} - ${errorBody ?: "(empty body)"}")
            synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ }
            // Leave the tombstone: the delete is retried next sync.
        }
    }

    /**
     * Put back an event whose delete Proton refused for good. The phone only has the deleted row, which
     * cannot be undeleted, so it goes, with the mapping, and the calendar's download cursor is reset:
     * the next sync, scheduled or not, then downloads the event again as Proton has it. Without the
     * reset a scheduled sync took the unchanged-skip (a refused delete does not move Proton's cursor),
     * and the event stayed missing until the calendar next changed. The invite records stay: the event
     * and its guests still exist. The user is told, by the event's name.
     */
    private suspend fun restoreRefusedDelete(
        account: Account,
        provider: ContentProviderClient,
        mapping: CalendarEventSyncEntity,
        androidEventId: Long,
        reason: String,
        syncResult: SyncResult
    ) {
        val title = localEventTitle(provider, androidEventId)
        calendarEventSyncDao.deleteByProtonId(account.name, mapping.protonEventId)
        removeTombstone(provider, account, androidEventId)
        resetCalendarCursor(account, mapping.protonCalendarId)
        clearDeleteRejectionStrikes(deleteStrikeKey(androidEventId, mapping.protonEventId))
        val entry = rejectedEventTitle(title) to reason
        // A series and its edited occurrences are separate rows with one title: name it once.
        gaveUpByAccount[account.name]?.let { list -> synchronized(list) { if (entry !in list) list.add(entry) } }
        synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ }
    }

    /** Make the next sync download [protonCalendarId] again instead of taking its unchanged-skip. */
    private suspend fun resetCalendarCursor(account: Account, protonCalendarId: String) {
        val calMapping = calendarSyncDao.getByProtonId(account.name, protonCalendarId) ?: return
        if (calMapping.lastModelEventId != null) calendarSyncDao.upsert(calMapping.copy(lastModelEventId = null))
    }

    /**
     * The re-time pass: bring event times already on the phone in line with this run's rules ([zoneRules]),
     * once per account and rule identity (TimeZoneRuleSource.retimeIdentity: the algorithm, the switch, the
     * phone's tz data, the newest tzurl release seen). Unchanged events are never downloaded again by
     * themselves (the per-event LastEditTime gate), so without this a fix of the time computation, a flip of
     * "Update time zones online" or a tz data update would never reach the events already stored.
     *
     * What it does is decided by [EventRetimePlanner] from what [EventRetimePass] reads: clean rows that differ
     * are marked (sync_tag RETIME_TAG) and their calendars' cursors cleared, so this very run downloads and
     * re-applies them through the normal path ([isRetimeDue]); dirty rows that differ are only marked and are
     * re-applied once their upload has gone (which sends Proton's own text for the times the user did not
     * touch, UploadTimes). Nothing on the phone or on Proton is written by the pass itself.
     *
     * Bookkeeping in [RetimeState]: a failure is retried at the next two syncs, then given up until the identity
     * changes; rows left unchecked because their online rules could not be had in this process make the check
     * incomplete, and it runs again in the next process. Never fails the sync. Kept out of performCalendarSync for
     * the coroutine size ceiling.
     */
    private suspend fun runRetimePass(account: Account, provider: ContentProviderClient) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = SyncPreferences.timeZoneRetimeKey(account.name)
        val stored = prefs.getString(key, null)
        val before = TimeZoneRuleSource.retimeIdentity(context, zoneRules)
        if (!RetimeState.shouldRun(stored, before, TimeZoneRuleSource.processToken)) return
        SyncLog.i(TAG, "Re-time: checking ${account.name}'s stored event times for $before (previously ${stored ?: "never"})")
        try {
            val entities = calendarEventSyncDao.getAllMappedByAccount(account.name)
            val outcome = EventRetimePass.evaluate(context, provider, account, entities, zoneRules)
            for (chunk in outcome.tagIds.chunked(400)) {
                calendarEventSyncDao.setSyncTag(account.name, chunk, EventRetimePlanner.RETIME_TAG)
            }
            for (protonCalendarId in outcome.calendarsToRefetch) resetCalendarCursor(account, protonCalendarId)
            // Taken again after the check: in ON mode its fetches may have seen a newer tzurl release, and the
            // rows were just checked against exactly that, unless some rules came from an older one (a CDN's stale
            // copy) or could not be had at all: then another process checks again.
            val after = TimeZoneRuleSource.retimeIdentity(context, zoneRules)
            val incomplete = outcome.unavailable > 0 || zoneRules.usedStaleOnlineRules()
            prefs.edit().putString(
                key, RetimeState.afterRun(stored, before, after, incomplete, TimeZoneRuleSource.processToken)
            ).apply()
            SyncLog.i(TAG, "Re-time: ${account.name}: ${outcome.summary}" +
                if (incomplete) " | incomplete: checked again in another process" else "")
        } catch (e: Exception) {
            val next = RetimeState.afterFailure(stored, before)
            prefs.edit().putString(key, next).apply()
            if (next == before) {
                SyncLog.w(TAG, "Re-time: ${account.name} failed ${RetimeState.MAX_FAILURES} times, not retried until the rules change: ${e.message}")
            } else {
                SyncLog.w(TAG, "Re-time: ${account.name} failed ($next), retried at the next sync: ${e.message}")
            }
        }
    }

    /**
     * After the downloads: rows written with the phone's rules while the online ones could not be had, or with
     * rules from an older tz release, make the account's re-time check run again in another process
     * ([TimeZoneRuleSource.noteDownloadFallbacks]). Non-suspend, for the coroutine size ceiling.
     */
    private fun noteZoneRuleFallbacks(account: Account) {
        TimeZoneRuleSource.noteDownloadFallbacks(context, account.name, zoneRules)
    }

    /** Why a refused delete is back, in the user's language, for the rejection notification. */
    private fun deleteRefusalReason(): String =
        context.getString(me.proton.sync.R.string.notification_events_rejected_delete)

    /**
     * Count one more rejection of a delete. Its own key, never `upload_reject_count_`: the deleted row
     * keeps the Android id of the event, and strikes left from failed updates would cut the delete short.
     * @return true on the third strike: give up.
     */
    private fun bumpDeleteRejectionStrike(strikeKey: String, protonEventId: String, op: SyncOpResult): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = strikeKey
        val strike = prefs.getInt(key, 0) + 1
        if (strike >= 3) {
            SyncLog.e(TAG, "Delete of event $protonEventId rejected by Proton (code=${op.code} error='${op.error}') " +
                "- attempt $strike/3, giving up and restoring it on the phone")
            prefs.edit().remove(key).apply()
            return true
        }
        SyncLog.e(TAG, "Delete of event $protonEventId rejected by Proton (code=${op.code} error='${op.error}') " +
            "- attempt $strike/3, retrying next sync")
        prefs.edit().putInt(key, strike).apply()
        return false
    }

    private fun clearDeleteRejectionStrikes(strikeKey: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.contains(strikeKey)) prefs.edit().remove(strikeKey).apply()
    }

    /** The strike key of a delete: the row's id while it exists, the Proton id once only the mapping is left. */
    private fun deleteStrikeKey(androidEventId: Long?, protonEventId: String): String =
        if (androidEventId != null) "delete_reject_count_$androidEventId" else "delete_reject_count_$protonEventId"

    /** Drop what was recorded about an event's invitations, once the event is gone. */
    private fun forgetInviteRecords(protonEventId: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(SyncPreferences.eventAttendeesKey(protonEventId))
            .remove("invite_sent_$protonEventId")
            .remove("invite_failed_$protonEventId")
            .remove("invite_reask_$protonEventId")
            .remove("invite_pending_$protonEventId")
            .remove("invite_retry_count_$protonEventId")
            .apply()
    }

    /** Remove a deleted row for good, as sync adapter: the provider refuses it otherwise. */
    private fun removeTombstone(provider: ContentProviderClient, account: Account, androidEventId: Long) {
        try {
            provider.delete(
                ContentUris.withAppendedId(
                    SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account), androidEventId
                ),
                null, null
            )
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not remove tombstone for local event $androidEventId", e)
        }
    }

    /** An event row's title (a deleted row keeps it), empty when it cannot be read. */
    private fun localEventTitle(provider: ContentProviderClient, androidEventId: Long): String = try {
        provider.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, androidEventId),
            arrayOf(CalendarContract.Events.TITLE), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: ""
    } catch (_: Exception) {
        ""
    }

    /**
     * Email the guests of an event this account just deleted on Proton: a cancellation built from the
     * deleted row, which Android keeps with its guests until the sync adapter removes it
     * ([deletedEventCancellation]). What is not sent now is queued ([PendingCancellations]) before the
     * event's records here go, as nothing else would be left to rebuild it from. Non-suspend.
     */
    private fun sendDeletionCancellations(
        account: Account,
        provider: ContentProviderClient,
        androidEventId: Long,
        mapping: CalendarEventSyncEntity,
        calInfo: CalendarInfo
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(SyncPreferences.sendCalendarCancellationsKey(account.name), false)) return
        // Only for an event this account organises on a calendar it owns (Proton's OWNER / SUPER_OWNER bits on
        // this account's own member): deleting an invitation, or an event of a calendar merely shared with this
        // account, must not email anybody's guests. The recorded guest list is keyed by the Proton event id,
        // which two accounts on this phone share for a calendar they both have, so this check cannot be left
        // to the fallback.
        if (mapping.cachedIsOrganizer != 1 || !ownsCalendar(account, calInfo)) {
            SyncLog.d(TAG, "No cancellations for event ${mapping.protonEventId}: not this account's own event to cancel")
            return
        }
        // An event this account organised in Proton has no record here, yet its guests were invited (by
        // Proton's own client): fall back to the deleted event's own guest rows.
        val guests = prefs.getStringSet(SyncPreferences.eventAttendeesKey(mapping.protonEventId), null)
            ?: readAttendees(provider, androidEventId).map { it.email.lowercase().trim() }.toSet()
        if (guests.isEmpty()) return
        val userAddrs = keyManager.getAddressEmails(account.name)
        val calendarMemberEmail = memberForUser(calInfo.members, userAddrs)?.email ?: account.name
        val excludeEmails = userAddrs.map { it.lowercase() }.toSet() + calendarMemberEmail.lowercase()
        val recipients = guests.filter { it.lowercase() !in excludeEmails }
        if (recipients.isEmpty()) return

        val tomb = try { readEventRow(provider, androidEventId) } catch (e: Exception) {
            SyncLog.w(TAG, "Could not read deleted event $androidEventId: ${e.message}")
            null
        }
        // The UID the guests received. Never an invented one: readEventRow makes one up for a row
        // without UID, and a cancellation naming it would cancel nothing.
        val uid = mapping.cachedUid?.takeIf { it.isNotBlank() } ?: storedEventUid(provider, androidEventId)
        if (tomb == null || uid == null) {
            SyncLog.w(TAG, "Not sending cancellations for deleted event ${mapping.protonEventId}: " +
                (if (tomb == null) "the deleted row could not be read" else "its UID is unknown"))
            return
        }
        // An override whose RECURRENCE-ID cannot be rebuilt (an unlinked exception row: no ORIGINAL_ID) would
        // go out as a cancellation of the whole series, which is what its UID names. Say nothing instead.
        val parts = buildIcalParts(tomb, emptyList(), null, null, null)
        if (parts.recurrenceIdStr.isEmpty() && mapping.cachedRecurrenceId != null) {
            SyncLog.w(TAG, "Not sending cancellations for deleted event ${mapping.protonEventId}: it is one occurrence " +
                "of a series and the phone no longer says which one")
            return
        }
        val event = deletedEventCancellation(
            uid = uid, summary = tomb.title, location = tomb.location, dtStart = tomb.dtStartMillis,
            effectiveDtEnd = parts.effectiveDtEndMillis, allDay = tomb.allDay == 1, timezone = tomb.timezone,
            rruleLine = parts.rruleStr, recurrenceIdLine = parts.recurrenceIdStr, organizer = calendarMemberEmail,
            organizerName = account.name, storedSequence = mapping.sequence, userAddresses = userAddrs,
            recipients = recipients
        )
        sendOrQueueCancellations(account, prefs, event, event.attendees, wholeEvent = true, "deleted event ${mapping.protonEventId}")
    }

    /**
     * The UID stored on an event row: its own, or for an edited occurrence its series' (as the guests
     * received it). Null when neither is stored.
     */
    private fun storedEventUid(provider: ContentProviderClient, androidEventId: Long): String? = try {
        provider.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, androidEventId),
            arrayOf(CalendarContract.Events.UID_2445, CalendarContract.Events.ORIGINAL_ID), null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val own = c.getString(0)?.takeIf { it.isNotBlank() }
            val parentId = if (c.isNull(1)) null else c.getLong(1)
            val parentUid = parentId?.let { id ->
                provider.query(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                    arrayOf(CalendarContract.Events.UID_2445), null, null, null
                )?.use { p -> if (p.moveToFirst()) p.getString(0)?.takeIf { it.isNotBlank() } else null }
            }
            (parentUid ?: own)?.take(SyncConstants.MAX_UID_LENGTH)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Send [event]'s cancellation to [recipients], and queue whoever it did not reach
     * ([PendingCancellations]) so a later sync sends it again. Non-suspend.
     */
    private fun sendOrQueueCancellations(
        account: Account,
        prefs: android.content.SharedPreferences,
        event: CalendarInviteSender.EventInfo,
        recipients: List<CalendarInviteSender.AttendeeInfo>,
        wholeEvent: Boolean,
        what: String
    ) {
        if (recipients.isEmpty()) return
        val inviteSender = CalendarInviteSender(
            apiClient, account.name, keyManager,
            includeTitleInSubject = prefs.getBoolean(SyncPreferences.inviteSubjectIncludeTitleKey(account.name), false)
        )
        // Safe: runs on Dispatchers.IO via SyncUtils.runUninterruptible, not UI thread
        val failed = try {
            runBlocking { inviteSender.sendCancellations(event, recipients, wholeEvent) }.failed
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to send cancellations for $what: ${e.message}")
            recipients.map { it.email.lowercase() }.toSet()
        }
        if (failed.isEmpty()) return
        val now = System.currentTimeMillis()
        val entry = PendingCancellations.firstFailure(
            PendingCancellations.of(event, recipients.filter { it.email.lowercase() in failed }, wholeEvent), now
        )
        PendingCancellationStore.update(prefs, account.name) { PendingCancellations.enqueue(it, entry) }
        SyncLog.w(TAG, "Cancellation for $what did not reach ${failed.joinToString(", ")}; queued, next attempt " +
            "in ${PendingCancellations.backoffMillis(1) / 60_000} min")
    }

    /**
     * Send the queued cancellations whose turn has come ([PendingCancellations]): at most
     * [PendingCancellations.MAX_PER_RUN] per run, at the start of each upload that may email (never in a
     * forced sync), so a failed cancellation goes out with a later sync of any kind. Switched-off
     * cancellation emails drop the queue. Non-suspend.
     */
    private fun retryPendingCancellations(account: Account, provider: ContentProviderClient) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val queued = PendingCancellationStore.read(prefs, account.name)
        if (queued.isEmpty()) return
        if (!prefs.getBoolean(SyncPreferences.sendCalendarCancellationsKey(account.name), false)) {
            PendingCancellationStore.update(prefs, account.name) { emptyList() }
            SyncLog.i(TAG, "Cancellation emails are switched off: dropped ${queued.size} queued cancellation(s)")
            return
        }
        val now = System.currentTimeMillis()
        val due = PendingCancellations.due(queued, now)
        if (due.isEmpty()) {
            SyncLog.d(TAG, "${queued.size} queued cancellation(s), none due yet")
            return
        }
        val userAddrs = keyManager.getAddressEmails(account.name)
        if (userAddrs.isEmpty()) {
            SyncLog.w(TAG, "${due.size} queued cancellation(s) wait: this account's addresses are not known yet")
            return
        }
        val ownAddrs = userAddrs.map { it.lowercase() }.toSet()
        val inviteSender = CalendarInviteSender(
            apiClient, account.name, keyManager,
            includeTitleInSubject = prefs.getBoolean(SyncPreferences.inviteSubjectIncludeTitleKey(account.name), false)
        )
        for (entry in due) {
            // A guest who is on the event again must not be told it was cancelled: their invitation and this
            // cancellation would race, and the event would vanish from their calendar.
            val guestsNow = currentGuestsOfUid(provider, entry.uid, entry.recurrenceIdLine)
            val target = entry.copy(recipients = entry.recipients.filter { normalizeGuestEmail(it.email) !in guestsNow })
            if (target.recipients.isEmpty()) {
                SyncLog.i(TAG, "Dropping the queued cancellation to ${entry.recipients.joinToString(", ") { it.email }}: " +
                    "they are guests of this ${if (entry.recurrenceIdLine.isNullOrBlank()) "event" else "occurrence"} again")
                PendingCancellationStore.update(prefs, account.name) { q -> q.filter { it.key != entry.key } }
                continue
            }
            val names = target.recipients.joinToString(", ") { it.email }
            val next = if (entry.organizer.lowercase() !in ownAddrs) {
                SyncLog.w(TAG, "Dropping the queued cancellation to $names: it was to be sent from ${entry.organizer}, " +
                    "which is no longer an address of this account")
                null
            } else {
                val event = PendingCancellations.eventOf(target, userAddrs)
                SyncLog.i(TAG, "Retrying the cancellation to $names (attempt ${entry.attempts + 1}/${PendingCancellations.MAX_ATTEMPTS})")
                // Safe: runs on Dispatchers.IO via SyncUtils.runUninterruptible, not UI thread
                val failed = try {
                    runBlocking { inviteSender.sendCancellations(event, event.attendees, target.wholeEvent) }.failed
                } catch (e: Exception) {
                    target.recipients.map { it.email.lowercase() }.toSet()
                }
                val after = PendingCancellations.afterAttempt(target, failed, System.currentTimeMillis())
                if (after != null && PendingCancellations.isExhausted(after, now)) {
                    SyncLog.w(TAG, "Giving up the cancellation to ${after.recipients.joinToString(", ") { it.email }} " +
                        "after ${after.attempts} attempts")
                    null
                } else after
            }
            // Against the queue as it stands now: another upload may have queued or purged recipients meanwhile.
            PendingCancellationStore.update(prefs, account.name) { q ->
                val merged = PendingCancellations.mergeAfterAttempt(q.firstOrNull { it.key == entry.key }, target, next)
                q.filter { it.key != entry.key } + listOfNotNull(merged)
            }
        }
    }

    /**
     * Who is a guest of the event with this UID RIGHT NOW, used to decide whether a queued cancellation is still
     * owed: it skips whoever is back.
     *
     * [recurrenceIdLine] makes it occurrence-aware, and that is the whole point. An occurrence's queued
     * entry carries its PARENT's UID, because that is the UID the iTIP message must use, so asking by UID
     * alone returned the guests of the whole series: they were always still guests, the cancellation was
     * always dropped as "they are guests of this event again", and nobody was ever told that one
     * occurrence had been called off. With a line given, only a LIVE override of that same instance
     * counts, rebuilt through the same formatter the upload used to write it.
     */
    private fun currentGuestsOfUid(
        provider: ContentProviderClient,
        uid: String,
        recurrenceIdLine: String?
    ): Set<String> = try {
        val wanted = recurrenceIdLine?.trim().orEmpty()
        val ids = mutableListOf<Long>()
        provider.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.ORIGINAL_ID,
                CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
                CalendarContract.Events.ORIGINAL_ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE
            ),
            "${CalendarContract.Events.UID_2445} = ? AND ${CalendarContract.Events.DELETED} = 0", arrayOf(uid), null
        )?.use { c ->
            while (c.moveToNext()) {
                if (wanted.isEmpty()) {
                    ids.add(c.getLong(0))
                    continue
                }
                val instance = if (c.isNull(2)) null else c.getLong(2)
                if (c.isNull(1) || instance == null) continue
                val line = ICalUtils.recurrenceIdLine(
                    instance, c.getInt(3) == 1, c.getString(4) ?: "UTC"
                )
                if (line.trim() == wanted) ids.add(c.getLong(0))
            }
        }
        ids.flatMap { id -> readAttendees(provider, id).map { normalizeGuestEmail(it.email) } }.toSet()
    } catch (e: Exception) {
        SyncLog.w(TAG, "Could not read the current guests of a queued cancellation: ${e.message}")
        emptySet()
    }

    /**
     * Settle a cancelled occurrence once its parent's EXDATE upload has ended ([cancelledRowAction]):
     * accepted clears the row, pending keeps it for the next sync, and a refusal for good shows the
     * occurrence again, as Proton and every other device still have it (the refusal was reported).
     */
    private fun settleCancelledOccurrence(
        provider: ContentProviderClient,
        account: Account,
        rowId: Long,
        parentId: Long,
        outcome: UploadOutcome?
    ) {
        val row = if (outcome == UploadOutcome.GAVE_UP) cancelledRowExdates(provider, rowId, parentId)
            else CancelledRow(null, null, false)
        var action = cancelledRowAction(outcome, row.exdate, row.parentExdateColumn)
        if (action == CancelledRowAction.RESTORE && row.onProton) {
            // This row is Proton's own single edit of the series, not a local-only exclusion. Deleting it here
            // would leave its sync mapping without a row, which the next sync reads as "deleted on the phone"
            // and then deletes that edit on Proton as well: the occurrence would come back everywhere at the
            // series' time, with the edit's own content lost. Leave the row as it is instead.
            SyncLog.w(TAG, "Occurrence $rowId of parent $parentId is Proton's own single edit, so it stays as it is " +
                "although its deletion was refused")
            action = CancelledRowAction.CLEAR_DIRTY
        }
        when (action) {
            CancelledRowAction.CLEAR_DIRTY -> SyncUtils.clearCalendarEventDirtyFlag(provider, account, rowId, TAG)
            CancelledRowAction.KEEP_PENDING -> SyncLog.w(TAG, "Cancelled occurrence $rowId stays pending: the EXDATE " +
                "update of parent $parentId did not reach Proton, retrying next sync")
            CancelledRowAction.RESTORE -> try {
                provider.delete(
                    ContentUris.withAppendedId(SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account), rowId),
                    null, null
                )
                SyncLog.w(TAG, "Proton kept occurrence $rowId of parent $parentId (its deletion was refused for good); " +
                    "showing it again on the phone")
            } catch (e: Exception) {
                SyncLog.w(TAG, "Could not show refused-deleted occurrence $rowId again; it stays pending", e)
            }
        }
    }

    /** What [settleCancelledOccurrence] needs about a cancelled occurrence row. */
    private class CancelledRow(val exdate: String?, val parentExdateColumn: String?, val onProton: Boolean)

    /** A cancelled occurrence's EXDATE value, its parent's EXDATE column, and whether it is an event of its own on Proton. */
    private fun cancelledRowExdates(provider: ContentProviderClient, rowId: Long, parentId: Long): CancelledRow = try {
        var parentTz = "UTC"
        var parentColumn: String? = null
        provider.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, parentId),
            arrayOf(CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.EXDATE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                parentTz = ICalUtils.normalizeTimezone(c.getString(0) ?: "UTC")
                parentColumn = c.getString(1)
            }
        }
        var onProton = false
        val rowExdate = provider.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, rowId),
            arrayOf(
                CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
                CalendarContract.Events.ORIGINAL_ALL_DAY,
                CalendarContract.Events._SYNC_ID
            ), null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) null else {
                onProton = !c.isNull(2) && c.getString(2).isNotBlank()
                if (c.isNull(0)) null else exdateValueFor(c.getLong(0), !c.isNull(1) && c.getInt(1) == 1, parentTz)
            }
        }
        CancelledRow(rowExdate, parentColumn, onProton)
    } catch (e: Exception) {
        SyncLog.w(TAG, "Could not read cancelled occurrence $rowId: ${e.message}")
        CancelledRow(null, null, false)
    }

    /**
     * Upload the parent of a cancelled occurrence with the complete EXDATE set, and once Proton accepted
     * it, write that set back to the local parent row so every calendar app hides the excluded
     * occurrences, not just the app that created the exception.
     *
     * Only once accepted: the write-back used to follow any outcome, so an upload Proton refused for
     * good still hid the occurrence on the phone for ever while Proton, and every other device, kept it,
     * under a notification saying the change was not saved. While pending, the cancelled row itself
     * hides the occurrence; a refusal shows it again ([settleCancelledOccurrence]).
     *
     * Called once per parent per run (see the caller's lock): all its cancelled occurrences want the same
     * upload. Never throws - a failure is reported and returns [UploadOutcome.RETRY], which keeps every
     * one of those occurrences pending for the next sync.
     */
    private suspend fun uploadCancelledOccurrenceParent(
        account: Account,
        provider: ContentProviderClient,
        parentId: Long,
        androidCalId: Long,
        syncResult: SyncResult,
        uploadContext: CalendarUploadContext,
        suppressAttendeeEmails: Boolean
    ): UploadOutcome = try {
        // Collect ALL cancelled exceptions for this parent to build complete EXDATE
        val (exdates, exdateTz) = collectCancelledExdates(provider, parentId)
        SyncLog.i(TAG, "Uploading parent $parentId EXDATE update (${exdates.size} exclusion(s))")
        val startedAt = System.currentTimeMillis() / 1000
        val outcome = uploadModifiedEvent(
            account, provider, parentId, androidCalId, syncResult, uploadContext, suppressAttendeeEmails,
            extraExdates = exdates, extraExdateTz = exdateTz
        )

        // Write EXDATE back to local parent event in CalendarContract
        // so ALL calendar apps (Google, Samsung) see the exclusion, not just
        // the app that created the exception.
        if (outcome == UploadOutcome.DONE && exdates.isNotEmpty()) {
            val existingExdate = try {
                provider.query(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, parentId),
                    arrayOf(CalendarContract.Events.EXDATE),
                    null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (_: Exception) { null }

            val allExdates = mutableSetOf<String>()
            if (!existingExdate.isNullOrEmpty()) allExdates.addAll(existingExdate.split(","))
            allExdates.addAll(exdates)

            val values = ContentValues().apply {
                put(CalendarContract.Events.EXDATE, allExdates.joinToString(","))
            }
            try {
                provider.update(
                    ContentUris.withAppendedId(
                        SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                        parentId
                    ),
                    values, null, null
                )
                SyncLog.d(TAG, "Wrote EXDATE back to local parent $parentId: ${allExdates.size} exclusions")
            } catch (e: Exception) {
                SyncLog.w(TAG, "Failed to write EXDATE to local parent $parentId: ${e.message}")
            }
            // The upload re-baselined the shared fingerprint BEFORE this write-back, so it lacks the
            // new EXDATE, and the next reminder-only edit would look like a shared change (emailing
            // every guest). The mapping is newer than this call when the upload itself saved it.
            val parentMapping = calendarEventSyncDao.getByAndroidId(account.name, parentId)
            if (parentMapping != null && parentMapping.lastModified >= startedAt) {
                val baseline = rowBaseline(provider, parentId)
                calendarEventSyncDao.upsert(
                    parentMapping.copy(sharedFingerprint = baseline.first, cachedTimes = baseline.second)
                )
            }
        }
        outcome
    } catch (e: Exception) {
        SyncLog.e(TAG, "Failed to upload the EXDATE update of parent $parentId", e)
        synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ }
        SyncUtils.recordSwallowedFailure(
            "calendar", account.name, "Could not send a deleted occurrence to Proton: ${e.message}"
        )
        UploadOutcome.RETRY
    }

    /**
     * Collect ORIGINAL_INSTANCE_TIME from all cancelled exception events for a parent.
     * Returns a list of EXDATE strings (VALUE=DATE for all-day, TZID for timed).
     */
    private fun collectCancelledExdates(
        provider: ContentProviderClient,
        parentAndroidId: Long
    ): Pair<List<String>, String> {
        val exdates = mutableListOf<String>()
        val cursor = provider.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events.ORIGINAL_INSTANCE_TIME, // 0
                CalendarContract.Events.ORIGINAL_ALL_DAY,       // 1
                CalendarContract.Events.EVENT_TIMEZONE           // 2
            ),
            "${CalendarContract.Events.ORIGINAL_ID} = ? AND ${CalendarContract.Events.STATUS} = ?",
            arrayOf(parentAndroidId.toString(), CalendarContract.Events.STATUS_CANCELED.toString()),
            null
        ) ?: return Pair(exdates, "UTC")

        // Also read the parent's timezone for proper EXDATE formatting
        var parentTz = "UTC"
        try {
            val parentCursor = provider.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, parentAndroidId),
                arrayOf(CalendarContract.Events.EVENT_TIMEZONE),
                null, null, null
            )
            parentCursor?.use { pc ->
                if (pc.moveToFirst()) parentTz = ICalUtils.normalizeTimezone(pc.getString(0) ?: "UTC")
            }
        } catch (_: Exception) { /* Intentional: parent timezone lookup failed — UTC default already set */ }

        cursor.use { c ->
            while (c.moveToNext()) {
                // The parent's zone: Proton requires localized datetimes.
                exdates.add(exdateValueFor(c.getLong(0), !c.isNull(1) && c.getInt(1) == 1, parentTz))
            }
        }
        SyncLog.d(TAG, "Collected ${exdates.size} cancelled EXDATEs for parent $parentAndroidId (tz=$parentTz): $exdates")
        return Pair(exdates, parentTz)
    }

    /**
     * Handle a per-operation rejection from the events/sync endpoint (HTTP 200 but op
     * Code != 1000/1001). These are server validation verdicts (2xxx codes) and usually
     * permanent, but a few resolve on their own (stale SharedKeyPacket after a passphrase
     * rotation, concurrent remote edits) — so keep the dirty flag for up to three
     * consecutive sync rounds before giving up. Without a cap a permanently-rejected
     * event re-uploads on every sync forever (battery + API load); with an immediate
     * clear a recoverable one is silently dropped. Mirrors the invite 3-strike pattern.
     *
     * One refusal is final at once: Proton does not let this account change the guest list, which is
     * the case on every calendar the user is only a member of ([isAttendeeListRefusal]). Proton's own
     * clients never send such an upload, because they offer no guests there; a calendar app on the
     * phone does. The same payload can never succeed, so it is not retried, and the user is told in
     * their own language what happened instead of reading Proton's English text. The event stays on
     * the phone: after a refused CREATE it exists nowhere else.
     *
     * [sharedBaseline] and [guestsMatchServer] let an UPDATE that changed nothing but this member's own
     * reminders or colour pass quietly: those were already saved on their own endpoint before the
     * shared update was refused, so there is nothing to report.
     *
     * @return true when the event was given up (the third rejection, or an attendee-list refusal).
     */
    private fun handleUploadOpRejection(
        provider: ContentProviderClient,
        account: Account,
        androidEventId: Long,
        eventTitle: String,
        opCode: Int,
        opError: String?,
        phase: String,
        syncResult: SyncResult,
        sharedBaseline: String? = null,
        guestsMatchServer: Boolean = false,
        personalSaved: Boolean = true
    ): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val retryKey = "upload_reject_count_$androidEventId"
        if (isAttendeeListRefusal(opCode, opError)) {
            val calendarName = localCalendarNameForEvent(provider, androidEventId)
            val personalOnly = phase == "Update" && guestsMatchServer && sharedBaseline != null &&
                sharedBaseline == EventConflictGuard.fingerprintLocalRow(provider, androidEventId)
            if (personalOnly && !personalSaved) {
                // The only thing this edit changed is exactly what did not save: try it again, a few times.
                val retryCount = prefs.getInt(retryKey, 0) + 1
                if (retryCount < 3) {
                    SyncLog.w(TAG, "$phase of event $androidEventId: this member's reminders or colour could not be " +
                        "saved (attempt $retryCount/3), keeping it for the next sync")
                    prefs.edit().putInt(retryKey, retryCount).apply()
                    synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ }
                    return false
                }
                SyncLog.w(TAG, "$phase of event $androidEventId: this member's reminders or colour could not be saved " +
                    "after $retryCount attempts; giving up")
            }
            prefs.edit().remove(retryKey).apply()
            SyncUtils.clearCalendarEventDirtyFlag(provider, account, androidEventId, TAG)
            synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ }
            if (personalOnly) {
                SyncLog.i(TAG, "$phase of event $androidEventId on calendar '${calendarName ?: "(unknown)"}' " +
                    "changed only this member's reminders or colour${if (personalSaved) ", which are saved" else ""}; " +
                    "Proton does not let this account change the event itself (code=$opCode), so nothing else was sent")
                if (!personalSaved) reportUnsavedPersonalPart(account, eventTitle)
                return true
            }
            // Measured 2026-09-21: this is not limited to guest-list changes. A title-only edit to an
            // event that HAS guests, on a calendar shared to this account, is refused with 2011 just the
            // same. Only the organiser may change such an event; guest-free events on the same calendar
            // upload fine.
            SyncLog.w(TAG, "$phase refused (code=$opCode): only the organiser may change an event that has " +
                "guests on calendar '${calendarName ?: "(unknown)"}'. Keeping event $androidEventId on the " +
                "phone and not retrying it.")
            gaveUpByAccount[account.name]?.add(rejectedEventTitle(eventTitle) to attendeeRefusalReason(phase))
            return true
        }
        val retryCount = prefs.getInt(retryKey, 0) + 1
        val gaveUp = retryCount >= 3
        if (gaveUp) {
            SyncLog.e(TAG, "$phase rejected by server (code=$opCode error='$opError') for event " +
                "$androidEventId - attempt $retryCount/3, giving up (clearing dirty flag)")
            prefs.edit().remove(retryKey).apply()
            SyncUtils.clearCalendarEventDirtyFlag(provider, account, androidEventId, TAG)
            // Record for the aggregated "couldn't sync" notification, with the server's raw
            // reason (falls back to the bare op-code when the API returns no error string).
            val reason = opError?.takeIf { it.isNotBlank() } ?: "Proton error code $opCode"
            gaveUpByAccount[account.name]?.add(rejectedEventTitle(eventTitle) to reason)
        } else {
            SyncLog.e(TAG, "$phase rejected by server (code=$opCode error='$opError') for event " +
                "$androidEventId - attempt $retryCount/3, keeping dirty flag for retry")
            prefs.edit().putInt(retryKey, retryCount).apply()
        }
        synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ }
        return gaveUp
    }

    /**
     * Tell the user this member's reminders or colour did not reach Proton although the sync went on:
     * they now differ between the phone and Proton (which, after an update without them, has the
     * calendar's defaults), and nothing else would say so. It used to be a log line only.
     */
    private fun reportUnsavedPersonalPart(account: Account, eventTitle: String) {
        SyncLog.w(TAG, "This member's reminders or colour were not saved to Proton; telling the user")
        gaveUpByAccount[account.name]?.add(
            rejectedEventTitle(eventTitle) to context.getString(me.proton.sync.R.string.notification_events_rejected_personal)
        )
    }

    /** The title a rejection notification shows, with the translated "(no title)" for an untitled event. */
    private fun rejectedEventTitle(eventTitle: String): String =
        eventTitle.ifBlank { context.getString(me.proton.sync.R.string.notification_conflict_untitled_event) }

    /** Why an attendee-list refusal happened, in the user's language, for the rejection notification. */
    private fun attendeeRefusalReason(phase: String): String = context.getString(
        if (phase == "Create") me.proton.sync.R.string.notification_events_rejected_guests_create
        else me.proton.sync.R.string.notification_events_rejected_guests_update
    )

    /**
     * Retire an upload Proton has permanently refused, and report it the way the other give-ups
     * are reported. Returns true when it took ownership of the failure.
     *
     * Code 2026 ("insufficient permissions") and 2011 ("calendar does not allow editing") mean the
     * user cannot write to this calendar. No amount of retrying changes that, and left dirty the
     * row is re-sent on every sync, for ever, with the user seeing only a list of errors.
     *
     * The dirty flag is cleared so the app stops asking, and the event is deliberately LEFT ON THE
     * PHONE. Proton never accepted it, so it exists nowhere else, and deleting it to tidy up would
     * destroy the only copy of something the user wrote. A later edit dirties the row again and
     * costs exactly one more attempt, which is the right price for finding out whether the
     * permission has since been granted.
     *
     * It counts as a SKIPPED entry rather than an IO exception, and goes through the same
     * gaveUpByAccount channel as the three-strike give-up, so the user gets the notification that
     * names the event and the server's reason. Counting it as an IO error would report a
     * permanent, user-unfixable refusal as though it were a transient server glitch, and the next
     * clean sync would then quietly restore "Everything's synced" over the top of it.
     */
    private fun retireRefusedUpload(
        provider: ContentProviderClient,
        account: Account,
        httpCode: Int,
        errorBody: String,
        androidEventId: Long,
        eventTitle: String,
        syncResult: SyncResult,
        phase: String
    ): Boolean {
        if (httpCode != 400) return false
        if (!hasErrorCode(errorBody, 2026) && !hasErrorCode(errorBody, 2011)) return false
        val calendarName = localCalendarNameForEvent(provider, androidEventId)
        SyncLog.w(
            TAG,
            "Proton refuses writes to calendar '${calendarName ?: "(unknown)"}', so event " +
                "$androidEventId cannot be saved. Keeping it on the phone and no longer retrying " +
                "it on every sync."
        )
        SyncUtils.clearCalendarEventDirtyFlag(provider, account, androidEventId, TAG)
        val reason = refusedUploadReason(errorBody, calendarName, attendeeRefusalReason(phase))
        gaveUpByAccount[account.name]?.add(rejectedEventTitle(eventTitle) to reason)
        synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ }
        return true
    }

    /** The display name the user sees for an event's calendar, for messages that must name it. */
    private fun localCalendarNameForEvent(
        provider: ContentProviderClient,
        androidEventId: Long
    ): String? = try {
        provider.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, androidEventId),
            arrayOf(CalendarContract.Events.CALENDAR_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        SyncLog.w(TAG, "Could not read the calendar name for event $androidEventId", e)
        null
    }

    /** Reset the [handleUploadOpRejection] strike counter after a successful upload. */
    private fun clearUploadRejectionCounter(androidEventId: Long) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val retryKey = "upload_reject_count_$androidEventId"
        if (prefs.contains(retryKey)) prefs.edit().remove(retryKey).apply()
    }

    // ------------------------------------------------------------------------
    // uploadModifiedEvent support.
    //
    // These are deliberately NON-SUSPEND. uploadModifiedEvent is a suspend function that sits at
    // the Kotlin coroutine state-machine ceiling — the compiler must spill every local that is
    // live across each suspension point, and inlining this much work alongside them broke JVM
    // codegen outright ("Couldn't transform method node"). Keeping these as plain functions moves
    // the bytecode out of the state machine entirely and collapses ~40 locals into a few holders.
    // Do not inline them back, and prefer adding to them over adding to uploadModifiedEvent.
    // ------------------------------------------------------------------------

    /** One CalendarContract attendee row, as read for upload. */
    private data class AttendeeInfo(
        val email: String,
        val name: String?,
        val status: Int,
        val relationship: Int,
        val type: Int
    )

    /**
     * Raw CalendarContract state for one event, read once at the top of an upload.
     *
     * RDATE and EXRULE stay in the cursor projection (unchanged) but are not carried here, and
     * neither is the DTEND string for the pre-DURATION end time: nothing downstream ever read them.
     */
    private class LocalEventRow(
        val title: String,
        val description: String,
        val location: String,
        val dtStartMillis: Long,
        val dtEndMillis: Long,
        val allDay: Int,
        val timezone: String,
        val uid2445: String,
        val rrule: String?,
        val exdate: String?,
        val durationStr: String?,
        /** Not uploaded (Proton does not author RDATE); only tells a series from a one-off event. */
        val rdate: String? = null,
        val eventStatus: Int,
        val availability: Int,
        val organizer: String,
        val eventColor: Int?,
        val originalId: Long?,
        val originalInstanceTime: Long?,
        val originalAllDay: Int?
    )

    /** The iCal fragments and API-field values derived from a [LocalEventRow]. */
    private class EventIcalParts(
        val nowStamp: String,
        val prodId: String,
        val timeBlock: String,
        val rruleStr: String,
        val exdateStr: String,
        val statusStr: String,
        val transpStr: String,
        val recurrenceIdStr: String,
        val effectiveDtEndMillis: Long,
        val apiNotifications: List<EventNotification>?,
        val apiColor: String?,
        /** The upload deletes occurrences the local row does not exclude yet (cancelled exceptions). */
        val hasExtraExdates: Boolean = false,
        /**
         * The local values DTSTART and DTEND were written with, for a timed row in a zone (UploadTimes.plan), so an
         * invitation states exactly what Proton got; null for all-day and UTC rows.
         */
        val startText: String? = null,
        val endText: String? = null,
        /** The rules an invitation's VTIMEZONE describes (UploadTimes.Plan.inviteRules), null for the phone's own. */
        val inviteZoneRules: java.time.zone.ZoneRules? = null
    )

    /**
     * The event a create's invitations describe: what was uploaded with its key, the shared event id Proton
     * answered with, and the series it belongs to. One object, because as twenty parameters it gave sendCreateInvites more than
     * 100 JVM locals.
     */
    private class CreateInviteEvent(
        val uid2445: String,
        val title: String,
        val description: String,
        val location: String,
        val dtStartMillis: Long,
        val effectiveDtEndMillis: Long,
        val timezone: String,
        val allDay: Int,
        val availability: Int,
        val effectiveOrganizer: String,
        val sharedEventId: String?,
        val responseSessionKey: String?,
        val createSequence: Int,
        val recurrenceIdStr: String,
        val rruleStr: String,
        val exdateStr: String,
        val originalId: Long?,
        /** EventIcalParts.inviteZoneRules: what the invitation's VTIMEZONE describes, null for the phone's rules. */
        val inviteZoneRules: java.time.zone.ZoneRules? = null,
        /** EventIcalParts.startText and endText: the local times Proton got, so the invitation states the same. */
        val dtStartText: String? = null,
        val dtEndText: String? = null
    )

    /** Outcome of the RFC 5546 organizer decision. */
    private class OrganizerDecision(
        val organizerStr: String,
        val effectiveOrganizer: String,
        val calendarMemberEmail: String?
    )

    /** ATTENDEE iCal lines plus the matching cleartext Attendees[] array. */
    private class AttendeeIcsParts(
        val icsLines: String,
        val apiAttendees: List<SyncAttendee>
    )

    /**
     * Read the event row we are about to upload. Returns null when the row cannot be read at all.
     * The DTSTART range gate stays at the call site so its dirty-flag side effect stays visible.
     */
    private fun readEventRow(
        provider: ContentProviderClient,
        androidEventId: Long
    ): LocalEventRow? {
        val cursor = provider.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, androidEventId),
            arrayOf(
                CalendarContract.Events.TITLE,          // 0
                CalendarContract.Events.DESCRIPTION,    // 1
                CalendarContract.Events.EVENT_LOCATION, // 2
                CalendarContract.Events.DTSTART,        // 3
                CalendarContract.Events.DTEND,          // 4
                CalendarContract.Events.ALL_DAY,        // 5
                CalendarContract.Events.EVENT_TIMEZONE,  // 6
                CalendarContract.Events.UID_2445,       // 7
                CalendarContract.Events.RRULE,          // 8
                CalendarContract.Events.EXDATE,         // 9
                CalendarContract.Events.DURATION,       // 10
                CalendarContract.Events.STATUS,         // 11
                CalendarContract.Events.AVAILABILITY,   // 12
                CalendarContract.Events.ORGANIZER,      // 13
                CalendarContract.Events.RDATE,          // 14
                CalendarContract.Events.EXRULE,         // 15
                CalendarContract.Events.EVENT_COLOR,     // 16
                CalendarContract.Events.ORIGINAL_ID,     // 17
                CalendarContract.Events.ORIGINAL_INSTANCE_TIME, // 18
                CalendarContract.Events.ORIGINAL_ALL_DAY // 19
            ),
            null, null, null
        ) ?: run {
            SyncLog.e(TAG, "Could not query event $androidEventId")
            return null
        }

        if (!cursor.moveToFirst()) {
            cursor.close()
            SyncLog.e(TAG, "Event $androidEventId not found")
            return null
        }

        // Proton's field length limits, which its clients apply before escaping
        val title = ICalUtils.truncateText(cursor.getString(0) ?: "", SyncConstants.MAX_SUMMARY_LENGTH)
        val description = ICalUtils.truncateText(cursor.getString(1) ?: "", SyncConstants.MAX_DESCRIPTION_LENGTH)
        val location = ICalUtils.truncateText(cursor.getString(2) ?: "", SyncConstants.MAX_LOCATION_LENGTH)
        val dtStartMillis = cursor.getLong(3)
        val dtEndMillis = cursor.getLong(4)
        val allDay = cursor.getInt(5)
        val rawTimezone = cursor.getString(6) ?: "UTC"
        val timezone = ICalUtils.normalizeTimezone(rawTimezone)
        // An event made on the phone has no UID yet, so it gets one in Proton's own form for calendar events,
        // `<random>@proton.me`, as Proton's clients make them (generateProtonCalendarUID in WebClients). This is for
        // compatibility: Proton's web app treats an event whose UID matches neither that form nor the
        // `proton-calendar-` prefix as imported (getIsProtonUID), and then does not let the user edit its participants.
        var uid2445 = (cursor.getString(7) ?: "${java.util.UUID.randomUUID()}@proton.me").take(SyncConstants.MAX_UID_LENGTH)
        // Clamp RRULE to Proton's limits (COUNT ≤ 499, UNTIL ≤ 2037-12-31). Stock
        // calendar apps can carry e.g. an Outlook-style "forever" UNTIL=45000831 into
        // CalendarContract; unclamped, that upload is rejected server-side on every sync.
        val rawRrule = cursor.getString(8)
        val rrule = ICalUtils.clampRrule(rawRrule)
        if (rrule != rawRrule) {
            SyncLog.w(TAG, "Event $androidEventId RRULE exceeds Proton limits - clamped '$rawRrule' → '$rrule'")
        }
        val exdate = cursor.getString(9)
        val durationStr = cursor.getString(10)
        val rdate = cursor.getString(14)
        val eventStatus = cursor.getInt(11)
        val availability = cursor.getInt(12)
        val organizer = cursor.getString(13) ?: ""
        val eventColor = if (!cursor.isNull(16)) cursor.getInt(16) else null
        val originalId = if (!cursor.isNull(17)) cursor.getLong(17) else null
        val originalInstanceTime = if (!cursor.isNull(18)) cursor.getLong(18) else null
        val originalAllDay = if (!cursor.isNull(19)) cursor.getInt(19) else null
        cursor.close()

        // Single-occurrence exceptions: per RFC 5545/5546, the override VEVENT MUST share
        // the parent series' UID and use RECURRENCE-ID to identify which occurrence is
        // overridden. Android stores the exception as a separate row with its own
        // (often empty/random) UID_2445 and ORIGINAL_ID pointing to the parent. If we
        // ship the exception row's local UID, Google/Outlook treat it as a brand-new
        // event instead of merging it into the existing series — duplicate visible.
        // Override uid2445 with the parent's UID upfront so every downstream consumer
        // (Proton iCal payloads, X-PM-TOKEN, iTIP invitation, cache) sees the right value.
        if (originalId != null) {
            val parentCursor = provider.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, originalId),
                arrayOf(CalendarContract.Events.UID_2445),
                null, null, null
            )
            val parentUid = parentCursor?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            if (!parentUid.isNullOrEmpty()) {
                uid2445 = parentUid.take(SyncConstants.MAX_UID_LENGTH)
                SyncLog.d(TAG, "Exception event $androidEventId: inheriting parent UID '$uid2445' for iTIP series matching")
            }
        }

        val tzLogSuffix = if (timezone != rawTimezone) " (normalized from $rawTimezone)" else ""
        SyncLog.d(TAG, "Upload event $androidEventId: allDay=$allDay tz=$timezone$tzLogSuffix " +
            "rrule=${rrule ?: "(none)"} duration=${durationStr ?: "(none)"} " +
            "exdate=${exdate ?: "(none)"}")

        return LocalEventRow(
            title = title,
            description = description,
            location = location,
            dtStartMillis = dtStartMillis,
            dtEndMillis = dtEndMillis,
            allDay = allDay,
            timezone = timezone,
            uid2445 = uid2445,
            rrule = rrule,
            exdate = exdate,
            durationStr = durationStr,
            rdate = rdate,
            eventStatus = eventStatus,
            availability = availability,
            organizer = organizer,
            eventColor = eventColor,
            originalId = originalId,
            originalInstanceTime = originalInstanceTime,
            originalAllDay = originalAllDay
        )
    }

    /**
     * Reminders for one event. METHOD travels alongside MINUTES so the user's choice between
     * device-alert and email reminders round-trips through Proton's Notifications API.
     * Pair.first = minutes-before-event, Pair.second = CalendarContract.Reminders.METHOD_*
     */
    private fun readReminders(
        provider: ContentProviderClient,
        androidEventId: Long
    ): List<Pair<Int, Int>> {
        val reminders = mutableListOf<Pair<Int, Int>>()
        try {
            val reminderCursor = provider.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(androidEventId.toString()),
                null
            )
            reminderCursor?.use { rc ->
                while (rc.moveToNext()) {
                    reminders.add(rc.getInt(0) to rc.getInt(1))
                }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to read reminders for event $androidEventId: ${e.message}")
        }
        return reminders
    }

    /** Attendee rows for one event, minus the legacy non-email aliases Proton rejects with 2001. */
    private fun readAttendees(
        provider: ContentProviderClient,
        androidEventId: Long
    ): MutableList<AttendeeInfo> {
        val attendees = mutableListOf<AttendeeInfo>()
        try {
            val attendeeCursor = provider.query(
                CalendarContract.Attendees.CONTENT_URI,
                arrayOf(
                    CalendarContract.Attendees.ATTENDEE_EMAIL,
                    CalendarContract.Attendees.ATTENDEE_NAME,
                    CalendarContract.Attendees.ATTENDEE_STATUS,
                    CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                    CalendarContract.Attendees.ATTENDEE_TYPE
                ),
                "${CalendarContract.Attendees.EVENT_ID} = ?",
                arrayOf(androidEventId.toString()),
                null
            )
            attendeeCursor?.use { ac ->
                while (ac.moveToNext()) {
                    val email = ac.getString(0) ?: continue
                    // Defensive: legacy events created before the v1.3.103 OWNER_ACCOUNT fix
                    // can carry an attendee row whose email is the user's alias (e.g.
                    // "Alice") because Samsung/Google Calendar copied it from the
                    // calendar's OWNER_ACCOUNT when the user marked themselves a participant.
                    // Proton's send endpoint rejects non-email package addresses with code=2001
                    // ("Invalid package address"). Skip these rows so the rest of the
                    // attendees still get their invites; the user can re-add themselves
                    // properly if needed.
                    if (!email.contains('@')) {
                        SyncLog.w(TAG, "Skipping non-email attendee on event $androidEventId: '$email' (likely legacy OWNER_ACCOUNT alias)")
                        continue
                    }
                    attendees.add(AttendeeInfo(
                        email = email,
                        name = ac.getString(1),
                        status = ac.getInt(2),
                        relationship = ac.getInt(3),
                        type = ac.getInt(4)
                    ))
                }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to read attendees for event $androidEventId: ${e.message}")
        }
        return attendees
    }

    /**
     * Which of the row's times the user has left alone since the last sync wrote them ([SyncedTimes.edits]):
     * nothing is known for an event without a mapping (a create). Non-suspend, read afresh.
     */
    private fun timeEditsOf(provider: ContentProviderClient, androidEventId: Long, mapping: CalendarEventSyncEntity?): TimeEdits {
        if (mapping == null) return TimeEdits.UNKNOWN
        return SyncedTimes.edits(
            EventConflictGuard.readSharedValues(provider, androidEventId), mapping.sharedFingerprint, mapping.cachedTimes
        )
    }

    /**
     * Derive every iCal fragment and API-field value the upload needs from the local row.
     *
     * Pure string building — no I/O, no suspension. The one piece of real judgement here is the
     * Notifications tri-state, documented inline below; get that wrong and reminders silently
     * revert to the calendar defaults.
     */
    private fun buildIcalParts(
        row: LocalEventRow,
        reminders: List<Pair<Int, Int>>,
        calendarSettings: CalendarSettingsData?,
        extraExdates: List<String>?,
        extraExdateTz: String?,
        /** The server's DTSTART and DTEND lines from the mapping, or null: Proton's own text for an untouched time. */
        cachedDtStartLine: String? = null,
        cachedDtEndLine: String? = null,
        /** Which of the row's times the user left alone since the last sync (SyncedTimes.edits). */
        timeEdits: TimeEdits = TimeEdits.UNKNOWN
    ): EventIcalParts {
        val cr = "\r\n"
        val allDay = row.allDay
        val timezone = row.timezone

        val nowStamp = ICalUtils.formatTimestamp(System.currentTimeMillis())

        // Build RRULE/EXDATE strings for type-2
        val rruleStr = if (!row.rrule.isNullOrEmpty()) "RRULE:${row.rrule}${cr}" else ""

        // For recurring events, Android stores DURATION instead of DTEND.
        // Proton doesn't support DURATION — calculate DTEND from DTSTART + DURATION.
        val effectiveDtEndMillis = if (rruleStr.isNotEmpty() && !row.durationStr.isNullOrEmpty()) {
            val durationMs = ICalUtils.parseDurationToMillis(row.durationStr)
            row.dtStartMillis + durationMs
        } else {
            row.dtEndMillis
        }

        // A timed row in a zone: a time the user did not touch goes back as Proton's own text, whatever rules
        // the stored instant was computed with, and a time they typed as the phone's rules give it, the rules
        // their calendar app read it with (UploadTimes.plan). Reformatting every stored instant moved events
        // whose instants came from other rules (the online ones, an older tz data, an older version's).
        val timePlan = if (allDay != 1 && timezone != "UTC") {
            UploadTimes.plan(
                rules = zoneRules,
                timezone = timezone,
                startMillis = row.dtStartMillis,
                endMillis = effectiveDtEndMillis,
                standalone = row.rrule.isNullOrEmpty() && row.rdate.isNullOrEmpty(),
                cachedDtStartLine = cachedDtStartLine,
                cachedDtEndLine = cachedDtEndLine,
                edits = timeEdits
            )
        } else null

        val dtStartStr = if (allDay == 1) {
            ICalUtils.formatDate(row.dtStartMillis)
        } else if (timePlan != null) {
            timePlan.startText
        } else {
            ICalUtils.formatTimestamp(row.dtStartMillis)
        }
        // Merge CalendarContract EXDATE with extra EXDATEs from cancelled exceptions
        val allExdates = mutableSetOf<String>() // Set for dedup
        if (!row.exdate.isNullOrEmpty()) allExdates.addAll(row.exdate.split(",").map { it.trim() })
        if (!extraExdates.isNullOrEmpty()) allExdates.addAll(extraExdates)
        val exdateStr = if (allExdates.isNotEmpty()) {
            // Use proper iCal parameter syntax: EXDATE;TZID=...:value (semicolon, not colon)
            val tzParam = if (!extraExdateTz.isNullOrEmpty() && extraExdateTz != "UTC") {
                ";TZID=$extraExdateTz"
            } else if (timezone != "UTC" && allDay != 1) {
                ";TZID=$timezone"
            } else ""
            // Sort chronologically and join
            allExdates.sorted().joinToString(",", prefix = "EXDATE${tzParam}:", postfix = cr)
        } else ""

        val effectiveDtEndStr = if (allDay == 1) {
            ICalUtils.formatDate(effectiveDtEndMillis)
        } else if (timePlan != null) {
            timePlan.endText
        } else {
            ICalUtils.formatTimestamp(effectiveDtEndMillis)
        }

        // Always use DTSTART + DTEND (never DURATION — Proton rejects it)
        // Proton strips redundant DTEND (same as DTSTART for timed, <=1 day for all-day)
        val stripDtEnd = if (allDay == 1) {
            effectiveDtEndMillis - row.dtStartMillis <= SyncConstants.ONE_DAY_MS
        } else {
            effectiveDtEndMillis == row.dtStartMillis
        }
        val timeBlock = if (allDay == 1) {
            "DTSTART;VALUE=DATE:$dtStartStr${cr}" +
                if (!stripDtEnd) "DTEND;VALUE=DATE:$effectiveDtEndStr${cr}" else ""
        } else if (timezone != "UTC") {
            "DTSTART;TZID=$timezone:$dtStartStr${cr}" +
                if (!stripDtEnd) "DTEND;TZID=$timezone:$effectiveDtEndStr${cr}" else ""
        } else {
            "DTSTART:$dtStartStr${cr}" +
                if (!stripDtEnd) "DTEND:$effectiveDtEndStr${cr}" else ""
        }

        // Build Notifications API field (VALARM goes as API field, not in ICS).
        // Map Android reminder method to Proton's Type enum: METHOD_EMAIL → 0 (EMAIL),
        // anything else (METHOD_ALERT, METHOD_DEFAULT, METHOD_ALARM, METHOD_SMS) → 1 (DEVICE).
        val localNotifications = reminders.map { (minutes, method) ->
            val type = if (method == CalendarContract.Reminders.METHOD_EMAIL) 0 else 1
            EventNotification(type = type, trigger = minutesBeforeToTrigger(minutes))
        }

        // Three distinct states, and Proton tells them apart:
        //   null          -> "this event has no reminders of its own, use the calendar defaults"
        //   []            -> "this event explicitly has NO reminders"
        //   [ ... ]       -> these exact reminders
        //
        // Sending null whenever the list is empty (the pre-v1.6.25 behaviour) meant deleting every
        // reminder on an event silently put it back on the calendar defaults, so the reminders
        // reappeared. And sending the explicit list when it merely equals the defaults would pin an
        // event that was inheriting them, so a later change to the calendar's defaults would stop
        // reaching it. Match on the defaults instead.
        val eventDefaults = defaultNotificationsFor(calendarSettings, allDay == 1)
        val apiNotifications: List<EventNotification>? = when {
            sameNotifications(localNotifications, eventDefaults) -> null
            else -> localNotifications
        }

        // Build Color API field (not in ICS). Proton accepts exactly the 20 accent colors
        // from its fixed palette and rejects any other hex value, which used to bounce the
        // whole event upload when third-party calendar apps (Business Calendar, Google
        // Calendar) assign per-event colors. Mirror Proton's own web importer
        // (getClosestProtonColor in WebClients colors.ts) and snap to the nearest
        // accepted color instead (v1.6.23).
        val apiColor = if (row.eventColor != null) {
            val requested = "#${String.format("%06X", row.eventColor and 0xFFFFFF)}"
            val snapped = ProtonColors.nearestAccentHex(row.eventColor)
            if (!snapped.equals(requested, ignoreCase = true)) {
                SyncLog.i(TAG, "Event color $requested is not in Proton's 20-color palette - using nearest accepted $snapped")
            }
            snapped
        } else null

        // Build STATUS string from CalendarContract status
        // Note: STATUS_TENTATIVE=0 which is also the default for NULL columns,
        // and Proton rejects TENTATIVE. Map both TENTATIVE and NULL to CONFIRMED.
        val statusStr = when (row.eventStatus) {
            CalendarContract.Events.STATUS_CANCELED -> "STATUS:CANCELLED${cr}"
            CalendarContract.Events.STATUS_CONFIRMED -> "STATUS:CONFIRMED${cr}"
            else -> "STATUS:CONFIRMED${cr}"
        }

        // Build TRANSP string from CalendarContract availability
        val transpStr = when (row.availability) {
            CalendarContract.Events.AVAILABILITY_FREE -> "TRANSP:TRANSPARENT${cr}"
            else -> "TRANSP:OPAQUE${cr}"
        }

        // Build RECURRENCE-ID for exception events (single-occurrence modifications)
        // Android stores exceptions as separate events with ORIGINAL_ID pointing to the parent
        val recurrenceIdStr = if (row.originalId != null && row.originalInstanceTime != null) {
            ICalUtils.recurrenceIdLine(row.originalInstanceTime, row.originalAllDay == 1, timezone)
        } else ""

        return EventIcalParts(
            nowStamp = nowStamp,
            prodId = "PRODID:${ICalUtils.PRODID}${cr}",
            timeBlock = timeBlock,
            rruleStr = rruleStr,
            exdateStr = exdateStr,
            statusStr = statusStr,
            transpStr = transpStr,
            recurrenceIdStr = recurrenceIdStr,
            effectiveDtEndMillis = effectiveDtEndMillis,
            apiNotifications = apiNotifications,
            apiColor = apiColor,
            hasExtraExdates = !extraExdates.isNullOrEmpty() && extraExdates.any { ex ->
                row.exdate.isNullOrEmpty() || row.exdate.split(",").none { it.trim() == ex }
            },
            startText = timePlan?.startText,
            endText = timePlan?.endText,
            inviteZoneRules = timePlan?.inviteRules
        )
    }

    /**
     * Decide the ORGANIZER line. Per RFC 5546 (iTIP) the organizer of an event must
     * not change once set, so we preserve a real third-party organizer when one is
     * present — otherwise events imported from Google Calendar (or any other source)
     * get their original organizer overwritten on every update and Proton's WebUI
     * then rejects further edits with "Organizer modification detected".
     *
     * CalendarContract.Events.ORGANIZER may hold one of:
     *   1. A real third-party email — events imported from Google, invitations the
     *      user accepted, etc. We must preserve these verbatim.
     *   2. Our own account.name (typically "<username>@proton.me") — for events the
     *      user just created in the Android Calendar app. AOSP's EditEventHelper
     *      sets Events.ORGANIZER = Calendars.OWNER_ACCOUNT, and we set
     *      OWNER_ACCOUNT = account.name. account.name *contains* an @ but isn't
     *      necessarily the right Proton sender (e.g. multi-address users whose
     *      calendar was created from an alias). Upgrade to the canonical Proton
     *      calendar member email when we know it.
     *   3. account.name without @ — pre-account-rename edge case. Synthesize a
     *      proper organizer from the calendar member email.
     *   4. Empty — same fallback as (3).
     *
     * RFC 5545 §3.6.1: the ORGANIZER property is REQUIRED only for group-scheduled
     * events (those with ATTENDEEs). Personal events should omit it. Adding an
     * ORGANIZER to an event that originally had none is a structural change
     * Proton's WebUI flags as "Organizer modification detected", even though the
     * API accepts the upload (opCode=1000). So we emit it only when:
     *   - hasAttendees → MUST emit (per RFC 5546 iTIP for group-scheduled events)
     *   - storedHasEmail → emit and preserve/canonicalize. Either the event was
     *     group-scheduled at some point (organizer set then), or it was imported
     *     with an organizer; either way, removing it now would itself be a
     *     structural change Proton would flag.
     *   - neither → omit. Personal event whose stored organizer is just our
     *     CalendarMapper fallback `accountName` (no @). Proton's stored event has
     *     no ORGANIZER and we keep it that way so subsequent WebUI edits work.
     *
     * [OrganizerDecision.effectiveOrganizer] is returned even when the line is omitted, because the
     * downstream invite and attendee paths still need to know who the organizer is.
     */
    private fun resolveOrganizer(
        account: Account,
        androidEventId: Long,
        organizer: String,
        calInfo: CalendarInfo?,
        attendeeCount: Int
    ): OrganizerDecision {
        val hasAttendees = attendeeCount > 0
        val storedHasEmail = organizer.contains('@')
        val needsOrganizer = hasAttendees || storedHasEmail

        // Primary source: KeyManager's address-key map, populated by
        // unlockAddressKeys[WithMailboxPassword]. Contains every Proton address the user owns on
        // this account (primary, alias, custom-domain), all lowercased. Defensive fallback when the
        // map is empty (race during fast successive ops).
        val userAddressEmails: Set<String> = keyManager.getAddressEmails(account.name)
            .ifEmpty {
                buildSet {
                    add(account.name.lowercase())
                    if (!account.name.contains('@')) add("${account.name}@proton.me".lowercase())
                }
            }
        val calendarMemberEmail = memberForUser(calInfo?.members, userAddressEmails)?.email
        val isUserOwned = organizer.lowercase() in userAddressEmails
        val (effectiveOrganizer, organizerSource) = when {
            organizer.isEmpty() ->
                // Nothing stored → synthesize from calendar member email.
                (calendarMemberEmail ?: account.name) to "synthesized-empty"
            isUserOwned ->
                // Stored organizer is one of this user's own Proton addresses → upgrade
                // to the canonical calendar member email so Proton's WebUI accepts edits.
                (calendarMemberEmail ?: organizer) to "canonical-user-owned"
            organizer.contains('@') ->
                // Real third-party organizer (Google import, external invitation, etc.)
                // → preserve verbatim per RFC 5546 (iTIP).
                organizer to "preserved-third-party"
            else ->
                // Stored organizer is a bare name (e.g. CalendarMapper fallback).
                // Synthesize from canonical.
                (calendarMemberEmail ?: organizer) to "synthesized-name-only"
        }

        val organizerStr = if (!needsOrganizer) {
            SyncLog.i(TAG, "Event $androidEventId organizer: omitted (personal event - " +
                "no attendees, no stored email; RFC 5545 §3.6.1) [stored='$organizer']")
            ""
        } else {
            SyncLog.i(TAG, "Event $androidEventId organizer: stored='$organizer' " +
                "→ uploading='$effectiveOrganizer' " +
                "(source=$organizerSource, calendarOwner='$calendarMemberEmail', " +
                "accountName='${account.name}', userOwned=$isUserOwned, " +
                "knownAddrs=${userAddressEmails.size}, attendees=$attendeeCount)")
            "ORGANIZER:mailto:$effectiveOrganizer\r\n"
        }

        return OrganizerDecision(
            organizerStr = organizerStr,
            effectiveOrganizer = effectiveOrganizer,
            calendarMemberEmail = calendarMemberEmail
        )
    }

    /**
     * Proton's `x-pm-token` for one attendee: SHA-1 of the event UID concatenated with the
     * canonical (lowercased, trimmed) address. This token, not the email, is how Proton identifies
     * an attendee, so every call site must canonicalise identically — hence one helper taking
     * (uid, email) separately rather than a pre-joined string, which makes the concatenation order
     * impossible to get wrong.
     *
     * Verifiable against production: Proton returns each attendee's token in cleartext as
     * `CalendarEventDetail.effectiveAttendees[].token`.
     */
    private fun protonAttendeeToken(uid: String, email: String): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest("$uid${email.lowercase().trim()}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Build the AttendeesEventContent type-3 ICS lines and the matching clear-text Attendees array.
     * The layout Proton's own clients write: ATTENDEE goes in a separate encrypted AttendeesEventContent,
     * with an x-pm-token parameter; the clear-text Attendees array carries Token + Status for each.
     */
    private fun buildAttendeeIcs(uid: String, attendees: List<AttendeeInfo>): AttendeeIcsParts {
        val cr = "\r\n"
        val attendeeIcsLines = StringBuilder()
        val apiAttendees = mutableListOf<SyncAttendee>()
        for (att in attendees) {
            val role = when {
                att.relationship == CalendarContract.Attendees.RELATIONSHIP_ORGANIZER -> "CHAIR"
                att.type == CalendarContract.Attendees.TYPE_OPTIONAL -> "OPT-PARTICIPANT"
                att.type == CalendarContract.Attendees.TYPE_NONE -> "NON-PARTICIPANT"
                else -> "REQ-PARTICIPANT"
            }
            val cnParam = if (!att.name.isNullOrEmpty()) ";CN=${CalendarInviteSender.icsParamValue(att.name)}" else ""
            val tokenHash = protonAttendeeToken(uid, att.email)
            attendeeIcsLines.append("ATTENDEE;ROLE=$role;RSVP=TRUE${cnParam};X-PM-TOKEN=$tokenHash:mailto:${att.email}${cr}")
            // Map Android status to Proton status
            val protonStatus = when (att.status) {
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> 3
                CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> 2
                CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> 1
                else -> 0  // NEEDS_ACTION
            }
            apiAttendees.add(SyncAttendee(
                token = tokenHash,
                status = protonStatus
            ))
        }
        return AttendeeIcsParts(icsLines = attendeeIcsLines.toString(), apiAttendees = apiAttendees)
    }

    /** Proton's cleartext answers for an event, keyed by lower-cased token. */
    private class ServerAttendees(
        val statusByToken: Map<String, Int>,
        val count: Int,
        val truncated: Boolean,
        val sharedEventId: String? = null
    )

    private fun serverAttendeesOf(account: Account, calendarId: String, event: CalendarEventDetail): ServerAttendees? {
        val (list, complete) = allAttendeesOf(account, calendarId, event) ?: return null
        return ServerAttendees(
            statusByToken = list.associate { it.token.lowercase() to it.status },
            count = list.size,
            truncated = !complete,
            sharedEventId = event.sharedEventId
        )
    }

    /**
     * An event's complete guest list: the page that came with it, then the rest page by page
     * ([collectAttendeePages]) when Proton says there is more. It used to stop at the first page, so on
     * a large event a guest past it lost their answer to the phone's copy, and an RSVP of ours past it
     * was never sent. Null when the event lists none; a page that cannot be read leaves the list
     * incomplete (second = false), as before. Blocking like [fetchServerAttendees]: the upload
     * functions are at the coroutine codegen ceiling, and this adds no suspension point to them.
     */
    private fun allAttendeesOf(account: Account, calendarId: String, event: CalendarEventDetail): Pair<List<EventAttendee>, Boolean>? {
        val first = event.effectiveAttendees ?: return null
        val more = event.attendeesInfo?.moreAttendees == 1
        if (!more) return first to true
        val all = collectAttendeePages(first, true, { page ->
            try {
                val response = runBlocking { apiClient.getCalendarEventAttendees(account.name, calendarId, event.id, page) }
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    body.attendees.orEmpty() to (body.moreAttendees == 1)
                } else {
                    SyncLog.w(TAG, "Could not read page $page of the guests of event ${event.id} (HTTP ${response.code()})")
                    null
                }
            } catch (e: Exception) {
                SyncLog.w(TAG, "Could not read page $page of the guests of event ${event.id} (${e.message})")
                null
            }
        })
        SyncLog.d(TAG, "Event ${event.id}: ${first.size} guest(s) on the first page, ${all.first.size} in all" +
            if (all.second) "" else " (incomplete)")
        return all
    }

    /**
     * Read the guests' answers Proton holds for an event. Null on any failure: the caller then keeps
     * the phone's copy, which is what every upload did before, so a failed read can never stop an
     * upload or start a retry loop. Blocking on purpose: the upload functions are suspend functions at
     * the coroutine codegen ceiling, and a call here adds no suspension point to them.
     */
    private fun fetchServerAttendees(account: Account, calendarId: String, eventId: String): ServerAttendees? = try {
        val response = runBlocking { apiClient.getCalendarEvent(account.name, calendarId, eventId) }
        if (response.isSuccessful) {
            response.body()?.event?.let { serverAttendeesOf(account, calendarId, it) }
        } else {
            SyncLog.w(TAG, "Could not read guests' answers for event $eventId (HTTP ${response.code()}); using the phone's copy")
            null
        }
    } catch (e: Exception) {
        SyncLog.w(TAG, "Could not read guests' answers for event $eventId (${e.message}); using the phone's copy")
        null
    }

    /**
     * The user's own addresses ([ownAddresses]) and the organiser: rows whose answer is made on this phone,
     * never reset. No calendar member in it: another member's address is a guest like any other, to be
     * invited, updated and asked again.
     */
    private fun ownGuestEmails(account: Account, organizer: String): Set<String> =
        (ownAddresses(keyManager.getAddressEmails(account.name), account.name) + organizer)
            .map { normalizeGuestEmail(it) }
            .toSet()

    private fun toLocalGuests(attendees: List<AttendeeInfo>, uids: List<String>, own: Set<String>): List<LocalGuest> =
        buildLocalGuests(attendees.map { it.email }, attendees.map { it.status }, own, uids)

    /**
     * Record the guests of an event this account organises, as the download just wrote them from Proton
     * ([downloadedGuestRecord]), so the first phone edit of an event created in Proton can tell a guest
     * the phone added from one Proton already invited. Only while an invitation switch is on (the record
     * is unused otherwise), only on calendars this account owns. [onlyIfAbsent]: an unchanged event
     * keeps a record it already has. Non-suspend, never throws.
     */
    private fun recordGuestBaseline(
        provider: ContentProviderClient,
        account: Account,
        calInfo: CalendarInfo,
        event: CalendarEventDetail,
        androidEventId: Long,
        onlyIfAbsent: Boolean
    ) {
        try {
            if (event.isOrganizer != 1 || !ownsCalendar(account, calInfo)) return
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!prefs.getBoolean(SyncPreferences.sendCalendarInvitesKey(account.name), false) &&
                !prefs.getBoolean(SyncPreferences.sendCalendarUpdatesKey(account.name), false) &&
                !prefs.getBoolean(SyncPreferences.sendCalendarCancellationsKey(account.name), false)
            ) return
            val key = SyncPreferences.eventAttendeesKey(event.id)
            if (onlyIfAbsent && prefs.contains(key)) return
            val record = downloadedGuestRecord(
                hasGuests = !event.effectiveAttendees.isNullOrEmpty(),
                readGuests = { readAttendees(provider, androidEventId).map { it.email } },
                current = prefs.getStringSet(key, null)?.map { normalizeGuestEmail(it) }?.toSet(),
                appOwnsRecord = prefs.contains("invite_sent_${event.id}"),
                pending = prefs.getStringSet("invite_pending_${event.id}", null).orEmpty()
            ) ?: return
            prefs.edit().putStringSet(key, record).apply()
            SyncLog.d(TAG, "Recorded ${record.size} guest(s) of event ${event.id} as Proton lists them")
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not record the guests of event ${event.id}: ${e.message}")
        }
    }

    /**
     * This account owns the calendar (Proton's OWNER / SUPER_OWNER bits on its OWN member, [isCalendarOwner]),
     * not merely a share of it. False when no member is this account's.
     */
    private fun ownsCalendar(account: Account, calInfo: CalendarInfo?): Boolean =
        isCalendarOwner(calInfo?.members, keyManager.getAddressEmails(account.name))

    /**
     * Plan an organiser's UPDATE of an event with guests: who is emailed, and what each guest's answer
     * is on Proton and in the invitation (see GuestStatuses.kt for the rules).
     *
     * Guests' answers are read from Proton when the event has guests. The upload's own GET is reused
     * when it ran; otherwise a separate read is made, so the upload's cached SEQUENCE baseline is left
     * exactly as it was. Without that read every phone edit sent the phone's copy of the answers back
     * to Proton, and the phone's copy is stale by design: an answer never marks an event as changed.
     */
    private fun planUpdateGuests(
        account: Account,
        provider: ContentProviderClient,
        androidEventId: Long,
        protonCalendarId: String,
        protonEventId: String,
        row: LocalEventRow,
        parts: EventIcalParts,
        attendees: List<AttendeeInfo>,
        existingMapping: CalendarEventSyncEntity,
        existingEvent: CalendarEventDetail?,
        updateUid: String,
        organizerInfo: OrganizerDecision,
        suppressAttendeeEmails: Boolean,
        serverDtStart: String?,
        serverDtEnd: String?,
        serverRrule: String?,
        currentDtStart: String?,
        currentDtEnd: String?,
        currentRrule: String?
    ): GuestStatusPlan {
        val own = ownGuestEmails(account, organizerInfo.effectiveOrganizer)
        val guests = toLocalGuests(attendees, listOf(row.uid2445, updateUid), own)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val hasGuests = guests.any { !it.own }
        val previous = prefs.getStringSet(SyncPreferences.eventAttendeesKey(protonEventId), null)
            ?.map { normalizeGuestEmail(it) }?.toSet()
        // Proton first: who it already lists decides who is new, and what each answer is. Also when the
        // phone removed every guest, so Proton can be told whom it no longer has.
        val server = when {
            !hasGuests && (previous.orEmpty() - own).isEmpty() -> null
            existingEvent != null -> serverAttendeesOf(account, protonCalendarId, existingEvent)
            else -> fetchServerAttendees(account, protonCalendarId, protonEventId)
        }
        // A record without any email sent is Proton's own list, recorded by the download (recordGuestBaseline).
        val previousFromProton = previous != null && !prefs.contains("invite_sent_$protonEventId")
        val onServer = server?.let { s ->
            val listed = guestsListedByProton(guests, s.statusByToken.keys)
            // On an event created in Proton, a guest Proton does not list counts as added on the phone.
            // That only holds when the list is complete, and when the tokens are understood at all:
            // guests matching none of them means the tokens, not the guests, are unknown here.
            if (previous == null && (s.truncated || (listed.isEmpty() && s.count > 0))) null else listed
        }
        val currentEmails = guests.map { it.email }.toSet()
        val recordedOnServer = if (server == null || server.truncated || previous == null) null else
            emailsListedByProton(
                previous - currentEmails, listOf(row.uid2445, updateUid), server.statusByToken.keys,
                taken = guests.flatMap { g -> listOf(row.uid2445, updateUid).map { attendeeToken(it, g.email) } }.toSet()
            )
        val pendingKey = "invite_pending_$protonEventId"
        val pending = prefs.getStringSet(pendingKey, null).orEmpty().map { normalizeGuestEmail(it) }.toSet()
        val recipients = if (suppressAttendeeEmails) InviteRecipients.NONE else {
            val baseline = existingMapping.sharedFingerprint
            planInviteRecipients(
                previous = previous,
                current = currentEmails,
                own = own,
                prevSent = prefs.getStringSet("invite_sent_$protonEventId", null).orEmpty().map { normalizeGuestEmail(it) }.toSet(),
                prevFailed = prefs.getStringSet("invite_failed_$protonEventId", null).orEmpty().map { normalizeGuestEmail(it) }.toSet(),
                sendInvites = prefs.getBoolean(SyncPreferences.sendCalendarInvitesKey(account.name), false),
                sendUpdates = prefs.getBoolean(SyncPreferences.sendCalendarUpdatesKey(account.name), false),
                sendCancellations = prefs.getBoolean(SyncPreferences.sendCalendarCancellationsKey(account.name), false),
                // Occurrences deleted on the phone reach the local row only after this upload.
                sharedChanged = parts.hasExtraExdates || baseline == null ||
                    baseline != EventConflictGuard.fingerprintLocalRow(provider, androidEventId),
                onServer = onServer,
                pending = pending,
                recordedOnServer = recordedOnServer,
                previousFromProton = previousFromProton
            )
        }
        // Remember who is about to be invited before the upload puts them on Proton: if this run dies
        // between the upload and the email, the next one must still see them as new, not as guests
        // Proton's own client invited. sendUpdateInviteEmails forgets them once it has a result.
        if (recipients.invitees.isNotEmpty()) {
            prefs.edit().putStringSet(pendingKey, pending + recipients.invitees).apply()
        }
        val breaking = isBreakingChange(serverDtStart, serverDtEnd, serverRrule, currentDtStart, currentDtEnd, currentRrule)
        val reaskPending = prefs.getStringSet("invite_reask_$protonEventId", null).orEmpty().map { normalizeGuestEmail(it) }.toSet()
        val plan = planGuestStatuses(
            guests, server?.statusByToken, server?.count ?: 0, recipients, breaking, reaskPending,
            addedToProton = guestsNewToProton(
                guests, server?.statusByToken?.keys, server?.truncated == false,
                recordedMatched = !recordedOnServer.isNullOrEmpty()
            ),
            removedFromProton = recordedOnServer.orEmpty() - own,
            sharedEventId = server?.sharedEventId ?: existingEvent?.sharedEventId
        )
        if (hasGuests || plan.removedFromProton.isNotEmpty()) logGuestPlan("Update", androidEventId, plan, server, breaking)
        return plan
    }

    /**
     * Plan the first upload of a single-occurrence edit of a series with guests (a CREATE with
     * RECURRENCE-ID). For the guests this is a change to a meeting they already answered: they keep
     * their answer to the series, read from the parent on Proton, unless the occurrence moved, when
     * the invitation asks them again. Before, every such edit asked everybody again.
     */
    private fun planOverrideGuests(
        account: Account,
        provider: ContentProviderClient,
        protonCalendarId: String,
        androidEventId: Long,
        row: LocalEventRow,
        attendees: List<AttendeeInfo>,
        organizerInfo: OrganizerDecision,
        parts: EventIcalParts,
        suppressAttendeeEmails: Boolean
    ): GuestStatusPlan {
        val own = ownGuestEmails(account, organizerInfo.effectiveOrganizer)
        val guests = toLocalGuests(attendees, listOf(row.uid2445), own)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val emailed = !suppressAttendeeEmails && prefs.getBoolean(SyncPreferences.sendCalendarInvitesKey(account.name), false)
        val recipients = if (emailed) {
            InviteRecipients(emptySet(), guests.filter { !it.own }.map { it.email }.toSet(), emptySet())
        } else InviteRecipients.NONE
        val parentId = row.originalId
        val parentMapping = parentId?.let { runBlocking { calendarEventSyncDao.getByAndroidId(account.name, it) } }
        val server = parentMapping?.let { fetchServerAttendees(account, protonCalendarId, it.protonEventId) }
        val moved = isOccurrenceMoved(
            row.dtStartMillis, parts.effectiveDtEndMillis, row.originalInstanceTime,
            parentId?.let { parentDurationMillis(provider, it) }
        )
        val plan = planGuestStatuses(guests, server?.statusByToken, server?.count ?: 0, recipients, breaking = moved)
        if (guests.any { !it.own }) logGuestPlan("Occurrence edit", androidEventId, plan, server, moved)
        return plan
    }

    /** How long one occurrence of the series [parentId] lasts, or null when the row cannot tell. */
    private fun parentDurationMillis(provider: ContentProviderClient, parentId: Long): Long? = try {
        provider.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, parentId),
            arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.DURATION),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val duration = if (c.isNull(2)) null else c.getString(2)
            when {
                !duration.isNullOrBlank() -> parseIcalDurationMillis(duration)
                !c.isNull(1) && !c.isNull(0) -> c.getLong(1) - c.getLong(0)
                else -> null
            }
        }
    } catch (e: Exception) {
        SyncLog.w(TAG, "Could not read the duration of series $parentId: ${e.message}")
        null
    }

    /** One line naming each guest's answer and where it came from. Addresses only, never the title. */
    private fun logGuestPlan(phase: String, androidEventId: Long, plan: GuestStatusPlan, server: ServerAttendees?, breaking: Boolean) {
        val answers = plan.guests.indices.filter { !plan.guests[it].own }.joinToString(", ") { i ->
            val email = plan.guests[i].email
            val source = when {
                email in plan.recipients.invitees -> "new"
                email in plan.askedAgain -> "asked again"
                plan.fromServer[i] -> "Proton"
                else -> "phone"
            }
            "$email=${protonStatusToPartstat(plan.statuses[i])} ($source)"
        }
        val r = plan.recipients
        SyncLog.i(TAG, "$phase of event $androidEventId, guests: $answers; timeChanged=$breaking, askAgain=${plan.reset}, " +
            "protonRead=${server != null}${if (server?.truncated == true) " (incomplete)" else ""}; " +
            "invite=${r.invitees}, update=${r.updatees}, cancel=${r.removed}" +
            (if (plan.addedToProton.isNotEmpty()) ", addToProton=${plan.addedToProton}" else "") +
            (if (plan.removedFromProton.isNotEmpty()) ", removeFromProton=${plan.removedFromProton}" else ""))
    }

    /** The upload's Attendees array with the planned answers (index-aligned, see [buildAttendeeIcs]). */
    private fun applyGuestPlan(apiAttendees: List<SyncAttendee>, plan: GuestStatusPlan): List<SyncAttendee> =
        if (apiAttendees.size != plan.statuses.size) apiAttendees
        else apiAttendees.mapIndexed { i, a -> a.copy(status = plan.statuses[i]) }

    /**
     * After Proton accepted the upload, give the phone's guest rows the answers Proton now holds, so
     * the calendar app shows them and a later upload starts from them. Written as the sync adapter, so
     * the event is not marked changed again; the user's own rows are left alone. Never throws: the
     * upload has already succeeded.
     */
    private fun mirrorGuestStatusesLocally(
        provider: ContentProviderClient,
        account: Account,
        androidEventId: Long,
        plan: GuestStatusPlan
    ) {
        if (plan.guests.none { !it.own }) return
        try {
            var changed = 0
            provider.query(
                CalendarContract.Attendees.CONTENT_URI,
                arrayOf(
                    CalendarContract.Attendees.ATTENDEE_EMAIL,
                    CalendarContract.Attendees.ATTENDEE_STATUS,
                    CalendarContract.Attendees._ID
                ),
                "${CalendarContract.Attendees.EVENT_ID} = ?",
                arrayOf(androidEventId.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val email = c.getString(0) ?: continue
                    val target = plan.statusFor(email)?.let { protonToAndroidStatus(it) } ?: continue
                    if (c.getInt(1) == target) continue
                    provider.update(
                        ContentUris.withAppendedId(
                            SyncUtils.asCallerSyncAdapter(CalendarContract.Attendees.CONTENT_URI, account),
                            c.getLong(2)
                        ),
                        ContentValues().apply { put(CalendarContract.Attendees.ATTENDEE_STATUS, target) },
                        null, null
                    )
                    changed++
                }
            }
            if (changed > 0) SyncLog.i(TAG, "Event $androidEventId: $changed guest answer(s) on the phone now match Proton")
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not update guests' answers on the phone for event $androidEventId: ${e.message}")
        }
    }

    /**
     * The event's key as an invitation carries it (X-PM-SESSION-KEY, base64 of the raw key), from its
     * key packet, so a Proton guest answering from the email gets a copy of this event they can open.
     * Update emails used to go without it and without the shared event ID. Null when it cannot be read.
     */
    private fun invitationSessionKey(account: Account, sharedKeyPacket: String?): String? = try {
        sharedKeyPacket?.let { packet ->
            keyManager.recoverSessionKey(android.util.Base64.decode(packet, android.util.Base64.DEFAULT), account.name)
                ?.second?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        }
    } catch (e: Exception) {
        SyncLog.w(TAG, "Could not read the event key for the invitation: ${e.message}")
        null
    }

    /**
     * The event's key wrapped for each Proton guest this update adds ([GuestStatusPlan.addedToProton]),
     * so Proton gives them their own copy, as it does for a create. Proton's clients wrap the existing
     * key; the event keeps its key packet. An update used to send none, so a Proton guest added on the
     * phone got an invitation but no copy of the event. A guest whose key cannot be looked up is left
     * out, as an outside address. Null when there is nobody to add. Non-suspend, never throws.
     */
    private fun protonGuestKeyPackets(
        account: Account,
        plan: GuestStatusPlan,
        symAlg: Int,
        sessionKey: ByteArray
    ): List<ProtonAttendeeKeyPacket>? {
        if (plan.addedToProton.isEmpty()) return null
        return try {
            val keys = lookupProtonRecipientKeys(account, plan.addedToProton.sorted())
            if (keys.isEmpty()) return null
            val packets = keyManager.wrapSessionKeyForRecipients(symAlg, sessionKey, keys.map { it.second })
            SyncLog.i(TAG, "Generated AddressKeyPackets for ${keys.size} added Proton guest(s) (update)")
            keys.mapIndexed { i, (email, _) ->
                ProtonAttendeeKeyPacket(email, android.util.Base64.encodeToString(packets[i], android.util.Base64.NO_WRAP))
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to generate AddressKeyPackets for the added guests: ${e.message}")
            null
        }
    }

    /**
     * The public key of each of [emails] that is a Proton address (RecipientType 1), in order; other
     * addresses, and any whose lookup fails, are left out. Non-suspend: one blocking request each.
     */
    private fun lookupProtonRecipientKeys(account: Account, emails: List<String>): List<Pair<String, String>> =
        emails.mapNotNull { email ->
            try {
                val resp = runBlocking { apiClient.getPublicKeys(account.name, email) }
                if (!resp.isSuccessful) {
                    val body = try { resp.errorBody()?.string() } catch (_: Exception) { null }
                    // An external address normally comes back as a 200 with RecipientType
                    // 2, and a missing or non-Proton address can also fail with 33102 or
                    // 33103. Anything else is a real failure that would otherwise quietly
                    // treat a Proton attendee as external.
                    unexpectedKeyLookupFailure(resp.code(), body)?.let { reason ->
                        SyncLog.w(TAG, "Key lookup for attendee $email failed ($reason); " +
                            "treating them as non-Proton, so this event's shared content is not encrypted to their key")
                    }
                    return@mapNotNull null
                }
                // RecipientType 1 only. An external domain with WKD keys also returns keys,
                // and must not be sent an AddressKeyPacket as if it were a Proton attendee.
                protonRecipientKey(resp.body())?.let { Pair(email, it) }
            } catch (e: Exception) {
                SyncLog.w(TAG, "Key lookup for attendee $email failed (${e.message}); " +
                    "treating them as non-Proton, so this event's shared content is not encrypted to their key")
                null
            }
        }

    /** The invitation's guest lines, each with what it says about that guest's answer. */
    private fun inviteAttendeesFor(
        attendees: List<AttendeeInfo>,
        uid: String,
        plan: GuestStatusPlan?
    ): List<CalendarInviteSender.AttendeeInfo> = attendees.map { att ->
        CalendarInviteSender.AttendeeInfo(
            att.email, att.name, protonAttendeeToken(uid, att.email),
            plan?.invitePartstat(att.email) ?: "NEEDS-ACTION"
        )
    }

    /**
     * The emails after an organiser's successful UPDATE: cancellations to removed guests, invitations
     * to new ones, updates to existing ones, as [plan] decided. Returns true when a send failed, for
     * the invite retry counter. Non-suspend, like every helper of the upload functions.
     */
    private fun sendUpdateInviteEmails(
        account: Account,
        androidEventId: Long,
        protonEventId: String,
        row: LocalEventRow,
        parts: EventIcalParts,
        organizerInfo: OrganizerDecision,
        attendees: List<AttendeeInfo>,
        existingMapping: CalendarEventSyncEntity,
        newSequence: Int,
        suppressAttendeeEmails: Boolean,
        plan: GuestStatusPlan,
        sharedKeyPacket: String?
    ): Boolean {
        if (suppressAttendeeEmails) return false
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val sendInvites = prefs.getBoolean(SyncPreferences.sendCalendarInvitesKey(account.name), false)
            val sendUpdates = prefs.getBoolean(SyncPreferences.sendCalendarUpdatesKey(account.name), false)
            val sendCancellations = prefs.getBoolean(SyncPreferences.sendCalendarCancellationsKey(account.name), false)
            if (!sendInvites && !sendUpdates && !sendCancellations) return false

            val prevAttendeesKey = SyncPreferences.eventAttendeesKey(protonEventId)
            val currentAttendeeEmails = attendees.map { it.email.lowercase().trim() }.toSet()
            val sentKey = "invite_sent_$protonEventId"
            val failedKey = "invite_failed_$protonEventId"
            val prevSent = prefs.getStringSet(sentKey, emptySet()) ?: emptySet()
            val recipients = plan.recipients

            val inviteSender = CalendarInviteSender(
                apiClient, account.name, keyManager,
                includeTitleInSubject = prefs.getBoolean(
                    SyncPreferences.inviteSubjectIncludeTitleKey(account.name), false
                )
            )
            val eventInfo = CalendarInviteSender.EventInfo(
                uid = row.uid2445, summary = row.title, description = row.description,
                location = row.location, dtStart = row.dtStartMillis, dtEnd = parts.effectiveDtEndMillis,
                timezone = row.timezone, allDay = row.allDay == 1, organizer = organizerInfo.effectiveOrganizer,
                attendees = inviteAttendeesFor(attendees, row.uid2445, plan),
                sharedEventId = plan.sharedEventId, sessionKey = invitationSessionKey(account, sharedKeyPacket),
                sequence = newSequence, userAddresses = keyManager.getAddressEmails(account.name),
                created = existingMapping.lastModified * 1000,
                lastModified = System.currentTimeMillis(), availability = row.availability,
                rruleLine = parts.rruleStr.ifEmpty { null },
                exdateLines = parts.exdateStr.ifEmpty { null },
                recurrenceIdLine = parts.recurrenceIdStr.ifEmpty { null },
                organizerName = account.name,
                zoneRules = parts.inviteZoneRules,
                dtStartText = parts.startText,
                dtEndText = parts.endText
            )

            // A queued cancellation to someone who is a guest again would take the event away from them.
            if (sendCancellations) {
                PendingCancellationStore.update(prefs, account.name) {
                    PendingCancellations.purgeReinvited(it, row.uid2445, eventInfo.recurrenceIdLine, currentAttendeeEmails)
                }
            }

            // 1. CANCEL to removed guests (only when cancellations are on; see planInviteRecipients): the
            // event naming only them, without STATUS:CANCELLED, which would cancel it for everybody.
            if (recipients.removed.isNotEmpty()) {
                val removedAttendees = recipients.removed.map { email ->
                    CalendarInviteSender.AttendeeInfo(email, null, protonAttendeeToken(row.uid2445, email))
                }
                sendOrQueueCancellations(account, prefs, eventInfo, removedAttendees, wholeEvent = false, "event $protonEventId")
            }

            // Guests Proton's own client invited count as reached, so a failed update to them is later
            // retried as an update, never as a fresh invitation that would reset their answer.
            // Asked-again guests whose email fails stay asked-again until an email reaches them.
            val reaskKey = "invite_reask_$protonEventId"
            val reaskPending = prefs.getStringSet(reaskKey, emptySet()) ?: emptySet()

            // 2. Invitations to new guests and updates to existing ones, each with its own wording
            if (recipients.invitees.isNotEmpty() || recipients.updatees.isNotEmpty()) {
                // Safe: runs on Dispatchers.IO via SyncUtils.runUninterruptible, not UI thread
                val result = runBlocking {
                    inviteSender.sendInvitationsAndUpdates(eventInfo, recipients.invitees, recipients.updatees)
                }
                val newSent = (prevSent - recipients.removed) + recipients.alreadyReached + result.sent
                val newFailed = result.failed
                val newReask = ((reaskPending - result.sent) + (plan.askedAgain intersect result.failed))
                    .filter { it in currentAttendeeEmails && (plan.statusFor(it) ?: PROTON_STATUS_NEEDS_ACTION) == PROTON_STATUS_NEEDS_ACTION }
                    .toSet()
                prefs.edit()
                    .putStringSet(sentKey, newSent)
                    .putStringSet(failedKey, newFailed)
                    .putStringSet(prevAttendeesKey, currentAttendeeEmails)
                    .putStringSet(reaskKey, newReask)
                    .remove("invite_pending_$protonEventId")
                    .apply()
                newFailed.isNotEmpty()
            } else {
                prefs.edit()
                    .putStringSet(sentKey, prevSent + recipients.alreadyReached)
                    .putStringSet(prevAttendeesKey, currentAttendeeEmails)
                    .remove("invite_pending_$protonEventId")
                    .apply()
                false
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to send invite/cancel for event $androidEventId: ${e.message}")
            true
        }
    }

    // UploadOutcome lives in SyncOpVerdicts.kt.

    /**
     * Read an event from CalendarContract and upload it to Proton as a create or update.
     *
     * Either way the upload carries up to four iCal parts:
     *   - shared, type 2 (signed cleartext): UID, DTSTAMP, times, RRULE, EXDATE, ORGANIZER, SEQUENCE
     *   - shared, type 3 (encrypted): SUMMARY, DESCRIPTION, LOCATION
     *   - calendar, type 2 (signed cleartext): STATUS, TRANSP, EXDATE; a create leaves it out
     *     when it holds only the defaults
     *   - attendees, type 3 (encrypted): the ATTENDEE lines, when the event has guests
     *
     * A create ([uploadCreateEvent], [buildCreatePayload]) encrypts the shared type-3 part with the
     * calendar key and splits the PGP message into KeyPacket (PKESK) and DataPacket (SEIPD), both
     * base64-encoded for the API; the attendees part reuses that session key. An update
     * ([uploadUpdateEvent], [buildUpdateSyncOp]) re-encrypts under the event's existing session key
     * and sends no SharedKeyPacket.
     */
    private suspend fun uploadModifiedEvent(
        account: Account,
        provider: ContentProviderClient,
        androidEventId: Long,
        androidCalId: Long,
        syncResult: SyncResult,
        uploadContext: CalendarUploadContext,
        suppressAttendeeEmails: Boolean,
        extraExdates: List<String>? = null,
        extraExdateTz: String? = null
    ): UploadOutcome {
        val row = readEventRow(provider, androidEventId) ?: return UploadOutcome.DONE

        // Validate date range (Proton supports 1970-01-01 to 2037-12-31)
        val minEpoch = 0L // 1970-01-01
        val maxEpoch = 2145916800000L // 2038-01-01
        if (row.dtStartMillis < minEpoch || row.dtStartMillis > maxEpoch) {
            SyncLog.w(TAG, "Event $androidEventId DTSTART out of Proton range, skipping")
            SyncUtils.clearCalendarEventDirtyFlag(provider, account, androidEventId, TAG)
            synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ }
            return UploadOutcome.DONE
        }

        val reminders = readReminders(provider, androidEventId)
        val attendees = readAttendees(provider, androidEventId)

        // 2-4. Calendar metadata, member id, and keys come from the upload context
        // prepared once per calendar in uploadDirtyEvents — saves 3 round-trips per event.
        val protonCalendarId = uploadContext.protonCalendarId
        val calInfo: CalendarInfo? = uploadContext.calInfo
        val memberId = uploadContext.memberId

        // Drop phantom attendee rows before they can reach Proton.
        //
        // AOSP's CalendarProvider2 fabricates an Attendees row for the calendar's OWNER_ACCOUNT
        // whenever an event was INSERTed with SELF_ATTENDEE_STATUS, which we did for guest-less
        // events until v1.6.25. Nobody invited those people. Uploading one either silently adds a
        // participant to a Proton event that had none, or gets the ENTIRE update rejected with
        // code 2011 "Not allowed to update attendee list" on a calendar the user does not organise.
        //
        // v1.6.25 stopped creating them, and an existing one is cleared the next time its event
        // updates from the server. But an event that never changes server-side keeps its phantom
        // indefinitely, so the upload has to defend itself rather than rely on that cleanup.
        //
        // BOTH conditions are required, deliberately. HAS_ATTENDEE_DATA=0 is written by us from
        // Proton's CLEARTEXT Attendees[] array, so it is Proton itself saying "this event has no
        // guests"; and every row must be one of the user's own addresses. A real guest list fails
        // the first test, and an event where the user invited a third party fails the second, so
        // neither is ever suppressed. Must run before resolveOrganizer(), which decides whether to
        // emit ORGANIZER at all from the attendee count.
        //
        // The decision lives in isPhantomOnlyAttendeeList() rather than here: uploadModifiedEvent
        // is a suspend function already at the Kotlin coroutine state-machine ceiling, and
        // inlining this block broke JVM codegen outright ("Couldn't transform method node").
        if (attendees.isNotEmpty() &&
            isPhantomOnlyAttendeeList(provider, account, androidEventId, attendees.map { it.email })
        ) {
            attendees.clear()
        }

        // Create or update? The mapping is the only thing that decides. Everything create-specific (SEQUENCE
        // inheritance, the type-2/type-3 iCal, and the calendar-key encryption) lives in uploadCreateEvent: an
        // update re-encrypts under the event's existing session key and never used any of it. Read before the
        // parts, whose time text is the server's own for a time the user did not touch (UploadTimes.plan).
        val existingMapping = calendarEventSyncDao.getByAndroidId(account.name, androidEventId)
        val parts = buildIcalParts(
            row, reminders, uploadContext.calendarSettings, extraExdates, extraExdateTz,
            existingMapping?.cachedDtStartLine, existingMapping?.cachedDtEndLine,
            timeEditsOf(provider, androidEventId, existingMapping)
        )
        val organizerInfo = resolveOrganizer(account, androidEventId, row.organizer, calInfo, attendees.size)
        val attendeeIcs = buildAttendeeIcs(row.uid2445, attendees)

        val protonEventId = existingMapping?.protonEventId

        if (existingMapping != null && protonEventId != null) {
            return uploadUpdateEvent(
                account, provider, androidEventId, syncResult, uploadContext, suppressAttendeeEmails,
                row, parts, organizerInfo, attendeeIcs, attendees,
                existingMapping, protonEventId
            )
        }

        return uploadCreateEvent(
            account, provider, androidEventId, syncResult, uploadContext, suppressAttendeeEmails,
            row, parts, organizerInfo, attendeeIcs, attendees
        )
    }

    /**
     * The UPDATE half of [uploadModifiedEvent]: the Proton event already exists, so the shared
     * content is re-encrypted under that event's existing session key rather than a fresh one,
     * and no SharedKeyPacket is sent.
     *
     * Split out of uploadModifiedEvent so each half gets its own coroutine state machine. The
     * combined function exceeded what the Kotlin JVM back-end can transform ("Couldn't transform
     * method node") — see the note above the uploadModifiedEvent support helpers.
     */
    private suspend fun uploadUpdateEvent(
        account: Account,
        provider: ContentProviderClient,
        androidEventId: Long,
        syncResult: SyncResult,
        uploadContext: CalendarUploadContext,
        suppressAttendeeEmails: Boolean,
        row: LocalEventRow,
        parts: EventIcalParts,
        organizerInfo: OrganizerDecision,
        attendeeIcs: AttendeeIcsParts,
        attendees: List<AttendeeInfo>,
        existingMapping: CalendarEventSyncEntity,
        protonEventId: String
    ): UploadOutcome {
        val protonCalendarId = uploadContext.protonCalendarId
        val calInfo: CalendarInfo? = uploadContext.calInfo
        val memberId = uploadContext.memberId
        val title = row.title
        val uid2445 = row.uid2445
        val rrule = row.rrule
        val originalId = row.originalId
        val timeBlock = parts.timeBlock
        val recurrenceIdStr = parts.recurrenceIdStr
        val apiNotifications = parts.apiNotifications
        val apiColor = parts.apiColor

        // ---- UPDATE path: re-encrypt with the existing session key ----
        SyncLog.i(TAG, "Uploading updated event $androidEventId (protonId=$protonEventId) to Proton")

        val baseline = resolveUpdateBaseline(
            account = account,
            protonCalendarId = protonCalendarId,
            protonEventId = protonEventId,
            existingMapping = existingMapping,
            syncResult = syncResult
        ) ?: return UploadOutcome.RETRY
        val existingSharedKeyPacket = baseline.existingSharedKeyPacket
        val existingEvent = baseline.existingEvent
        val isOrganizerOnServer = baseline.isOrganizerOnServer
        val authoritativeUid = baseline.authoritativeUid
        val serverDtStart = baseline.serverDtStart
        val serverDtEnd = baseline.serverDtEnd
        val serverRrule = baseline.serverRrule

        // If no SharedKeyPacket, this is an event we were invited to (not organizer).
        // Only update our attendee participation status, not the event content.
        if (existingSharedKeyPacket == null || isOrganizerOnServer == 0) {
            return uploadAttendeeRsvp(
                account = account,
                provider = provider,
                androidEventId = androidEventId,
                calInfo = calInfo,
                protonCalendarId = protonCalendarId,
                protonEventId = protonEventId,
                existingEvent = existingEvent
            )
        }

        // Use the authoritative UID from the API response — Proton rejects updates with a
        // different UID (error 2008). The top-level UID field is reliable for all events,
        // including third-party imports (Google etc.) where the iCal cleartext may omit it.
        val serverUid = authoritativeUid
        val updateUid = serverUid ?: uid2445
        if (serverUid != null && serverUid != uid2445) {
            SyncLog.d(TAG, "Using server UID '$serverUid' instead of local UID '$uid2445' for event $androidEventId")
        }

        // Recover the session key from the PKESK packet
        val keyPacketBytesForUpdate = android.util.Base64.decode(existingSharedKeyPacket, android.util.Base64.DEFAULT)
        val (symAlg, sessionKeyBytes) = keyManager.recoverSessionKey(keyPacketBytesForUpdate, account.name)
            ?: run {
                SyncLog.e(TAG, "Could not recover session key from SharedKeyPacket for $protonEventId; keeping the edit for the next sync")
                // The edit is the user's, and it is the only copy: clearing the dirty flag here threw it
                // away and reported success. A session key that will not open is usually a key ring the
                // account has since rotated, which heals on its own once the ring is re-read, so the row
                // stays dirty and the framework retries.
                synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ }
                SyncUtils.invalidateKeyCache(account.name)
                SyncUtils.recordSwallowedFailure(
                    "calendar", account.name,
                    "Could not open the key for an event that was edited on this phone, so the change has not been sent yet"
                )
                return UploadOutcome.RETRY
            }

        // Smart SEQUENCE: only increment when DTSTART, DTEND, or RRULE changed.
        // Proton's web app does the same (withVeventSequence in WebClients): non-structural changes
        // (title, description) keep the same sequence.
        // serverDtStart / serverDtEnd / serverRrule come from the cache or the GET above.
        val storedSequence = existingMapping?.sequence ?: 0
        // Compare structural properties — build current values for comparison
        val (currentDtStartLine, currentDtEndLine, currentRrule) = ICalUtils.structuralLines(timeBlock, rrule)
        val newSequence = decideUpdateSequence(
            account = account,
            androidEventId = androidEventId,
            originalId = originalId,
            storedSequence = storedSequence,
            serverDtStart = serverDtStart,
            serverDtEnd = serverDtEnd,
            serverRrule = serverRrule,
            currentDtStartLine = currentDtStartLine,
            currentDtEndLine = currentDtEndLine,
            currentRrule = currentRrule
        )

        // Each guest's answer, and who is emailed about this change, decided once: the same plan feeds
        // the Attendees array below and the invitation after the upload, so Proton and the guests are
        // told the same thing. See planUpdateGuests.
        val guestPlan = planUpdateGuests(
            account, provider, androidEventId, protonCalendarId, protonEventId, row, parts, attendees,
            existingMapping, existingEvent, updateUid, organizerInfo, suppressAttendeeEmails,
            serverDtStart, serverDtEnd, serverRrule, currentDtStartLine, currentDtEndLine, currentRrule
        )

        val updateSyncOp = buildUpdateSyncOp(
            account = account,
            provider = provider,
            androidEventId = androidEventId,
            syncResult = syncResult,
            row = row,
            parts = parts,
            organizerInfo = organizerInfo,
            attendeeIcs = attendeeIcs,
            attendees = attendees,
            protonEventId = protonEventId,
            updateUid = updateUid,
            newSequence = newSequence,
            symAlg = symAlg,
            sessionKeyBytes = sessionKeyBytes,
            guestPlan = guestPlan,
            memberId = memberId
        ) ?: return UploadOutcome.GAVE_UP

        val updateResult = runCatching {
            apiClient.syncCalendarEvents(
                accountName = account.name,
                calendarId = protonCalendarId,
                body = CalendarEventSyncRequest(
                    memberId = memberId,
                    events = listOf(updateSyncOp)
                )
            )
        }
        // Personal part AFTER the update, whatever its outcome, so a member who cannot modify the
        // shared event still gets their own reminders and colour saved.
        //
        // It used to go first, and the update then undid it: an update operation that carries no
        // Notifications resets this member's reminders to "inherit the calendar defaults". Seen on
        // hardware: the personal endpoint answered "server now has notifications=2" for reminders
        // at 0 and 5 minutes, and after the update and a full re-download the event had the
        // calendar's defaults instead (15 minutes as a notification plus 15 as an email). So every
        // reminder change made on the phone to an existing event was replaced on Proton by the
        // defaults, while the phone kept showing the user's own reminders.
        val personalSaved = pushPersonalPart(account, protonCalendarId, protonEventId, apiNotifications, apiColor)
        val updateResponse = updateResult.getOrThrow()

        if (updateResponse.isSuccessful) {
            settleUpdateOpResponse(
                updateResponse = updateResponse,
                provider = provider,
                account = account,
                androidEventId = androidEventId,
                title = title,
                syncResult = syncResult,
                parts = parts,
                existingMapping = existingMapping,
                guestPlan = guestPlan,
                personalSaved = personalSaved,
                apiNotifications = apiNotifications,
                apiColor = apiColor,
                protonEventId = protonEventId,
                newSequence = newSequence
            )?.let { return it }
            // Refresh the cache with what we just sent so the next upload can skip
            // the GET. SharedKeyPacket is invariant for the event lifetime; structural
            // fields and UID get updated to the values we just signed.
            calendarEventSyncDao.upsert(
                buildUpdatedMapping(
                    existingMapping = existingMapping,
                    serverLastEditTime = serverStamp(updateResponse.body()),
                    newSequence = newSequence,
                    existingSharedKeyPacket = existingSharedKeyPacket,
                    updateUid = updateUid,
                    currentDtStartLine = currentDtStartLine,
                    currentDtEndLine = currentDtEndLine,
                    currentRrule = currentRrule,
                    recurrenceIdStr = recurrenceIdStr,
                    provider = provider,
                    androidEventId = androidEventId
                )
            )

            finishUpdateSuccess(
                provider = provider,
                account = account,
                androidEventId = androidEventId,
                protonEventId = protonEventId,
                row = row,
                parts = parts,
                organizerInfo = organizerInfo,
                attendees = attendees,
                existingMapping = existingMapping,
                newSequence = newSequence,
                suppressAttendeeEmails = suppressAttendeeEmails,
                guestPlan = guestPlan,
                existingSharedKeyPacket = existingSharedKeyPacket
            )
            synchronized(syncResult.stats) { syncResult.stats.numUpdates++ }
        } else {
            return handleUpdateHttpFailure(
                updateResponse = updateResponse,
                provider = provider,
                account = account,
                androidEventId = androidEventId,
                title = title,
                syncResult = syncResult
            )
        }
        return UploadOutcome.DONE
    }

    /** Null means the GET failed: the log and the counter are already done, and the caller must RETRY. */
    private suspend fun resolveUpdateBaseline(
        account: Account,
        protonCalendarId: String,
        protonEventId: String,
        existingMapping: CalendarEventSyncEntity,
        syncResult: SyncResult
    ): UpdateBaseline? {
        // Fast path: if we cached the server fields on a previous sync or upload, skip
        // GET /calendar/events/{id} for organizer-owned events (~230 ms saved per event).
        // The RSVP-only branch below still requires the GET because it walks the server's
        // attendee list to find our token, which we don't cache.
        val cachedSharedKeyPacket = existingMapping.cachedSharedKeyPacket
        val canUseCache = existingMapping.cachedIsOrganizer == 1 && cachedSharedKeyPacket != null

        val existingSharedKeyPacket: String?
        val existingEvent: CalendarEventDetail?
        val isOrganizerOnServer: Int
        val authoritativeUid: String?
        val serverDtStart: String?
        val serverDtEnd: String?
        val serverRrule: String?

        if (canUseCache) {
            SyncLog.d(TAG, "Using cached event fields for $protonEventId, skipping GET")
            existingSharedKeyPacket = cachedSharedKeyPacket
            existingEvent = null
            isOrganizerOnServer = 1
            authoritativeUid = existingMapping.cachedUid
            serverDtStart = existingMapping.cachedDtStartLine
            serverDtEnd = existingMapping.cachedDtEndLine
            serverRrule = existingMapping.cachedRrule
        } else {
            val eventDetailResponse = apiClient.getCalendarEvent(account.name, protonCalendarId, protonEventId!!)
            if (!eventDetailResponse.isSuccessful) {
                SyncLog.e(TAG, "Failed to fetch event detail for update: HTTP ${eventDetailResponse.code()}")
                synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ }
                return null
            }
            existingEvent = eventDetailResponse.body()?.event
            // The same reader the cache writer uses, so the GET branch and the cached branch cannot
            // drift apart. A null event gives nulls, exactly as the hand-rolled joins did: their
            // ?: "" left one empty line, which matched no property.
            val cache = existingEvent?.let { extractEventCacheFields(it) }
            existingSharedKeyPacket = cache?.sharedKeyPacket
            isOrganizerOnServer = cache?.isOrganizer ?: 0
            authoritativeUid = cache?.uid
            serverDtStart = cache?.dtStartLine
            serverDtEnd = cache?.dtEndLine
            serverRrule = cache?.rrule
        }

        return UpdateBaseline(
            existingSharedKeyPacket = existingSharedKeyPacket,
            existingEvent = existingEvent,
            isOrganizerOnServer = isOrganizerOnServer,
            authoritativeUid = authoritativeUid,
            serverDtStart = serverDtStart,
            serverDtEnd = serverDtEnd,
            serverRrule = serverRrule
        )
    }

    /**
     * Everything an update sends: the two shared iCals, the calendar-level one, their signatures, the type-3
     * re-encryption under the event's existing session key, and the operation itself.
     *
     * Null means the upload is over and the caller must return GAVE_UP: the type-3 failure inside has already
     * counted the skip and cleared the dirty flag, exactly as it did inline.
     *
     * Not suspend, deliberately, so this bytecode leaves the caller's coroutine state machine. It must only be
     * called from an IO thread: the added-guest key lookup blocks on the network through runBlocking.
     */
    private fun buildUpdateSyncOp(
        account: Account,
        provider: ContentProviderClient,
        androidEventId: Long,
        syncResult: SyncResult,
        row: LocalEventRow,
        parts: EventIcalParts,
        organizerInfo: OrganizerDecision,
        attendeeIcs: AttendeeIcsParts,
        attendees: List<AttendeeInfo>,
        protonEventId: String,
        updateUid: String,
        newSequence: Int,
        symAlg: Int,
        sessionKeyBytes: ByteArray,
        guestPlan: GuestStatusPlan,
        memberId: String?
    ): CalendarEventSyncOperation? {
        val cr = "\r\n"
        val title = row.title
        val description = row.description
        val location = row.location
        val nowStamp = parts.nowStamp
        val prodId = parts.prodId
        val timeBlock = parts.timeBlock
        val rruleStr = parts.rruleStr
        val exdateStr = parts.exdateStr
        val statusStr = parts.statusStr
        val transpStr = parts.transpStr
        val recurrenceIdStr = parts.recurrenceIdStr
        val organizerStr = organizerInfo.organizerStr
        val calendarMemberEmail = organizerInfo.calendarMemberEmail
        val attendeeIcsLines = attendeeIcs.icsLines
        val apiAttendees = attendeeIcs.apiAttendees

        // SharedEventContent type-2: signed structural data for update
        val updateType2ICal = ICalUtils.sharedStructuralPart(
            prodIdLine = prodId,
            uid = updateUid,
            dtStamp = nowStamp,
            recurrenceIdLine = recurrenceIdStr,
            timeBlock = timeBlock,
            rruleLine = rruleStr,
            exdateLine = exdateStr,
            organizerLine = organizerStr,
            sequence = newSequence
        )

        SyncLog.d(TAG, "Update type-2 for event $androidEventId: seq=$newSequence hasRrule=${rruleStr.isNotEmpty()} hasExdate=${exdateStr.isNotEmpty()}")

        // SharedEventContent type-3: encrypted sensitive data for update
        val updateType3ICal = ICalUtils.sharedEncryptedPart(prodId, updateUid, nowStamp, null, recurrenceIdStr, title, description, location)
        SyncLog.d(TAG, "Update type-3 for event $androidEventId: hasDesc=${description.isNotEmpty()} hasLoc=${location.isNotEmpty()}")

        // CalendarEventContent type-2: calendar-level signed data (STATUS, TRANSP, EXDATE)
        val updateCalICal = "BEGIN:VCALENDAR${cr}${prodId}VERSION:2.0${cr}BEGIN:VEVENT${cr}" +
            "UID:$updateUid${cr}" +
            "DTSTAMP:$nowStamp${cr}" +
            exdateStr +
            statusStr +
            transpStr +
            "END:VEVENT${cr}END:VCALENDAR"

        // Sign all three parts with the calendar owner's address key — Proton verifies
        // signatures against the calendar-owner address key, not the account default
        // (multi-address accounts otherwise fail with code=2001).
        val updateType2Sig = keyManager.sign(account.name, calendarMemberEmail, updateType2ICal)
        val updateType3Sig = keyManager.sign(account.name, calendarMemberEmail, updateType3ICal)
        val updateCalSig = keyManager.sign(account.name, calendarMemberEmail, updateCalICal)

        // Encrypt type-3 with the recovered session key → raw SEIPD packet bytes
        val seipdBytes = try {
            keyManager.encryptWithSessionKey(updateType3ICal, symAlg, sessionKeyBytes)
        } catch (e: Exception) {
            SyncLog.e(TAG, "Failed to encrypt update type-3 with session key: ${e.message}", e)
            synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ }
            SyncUtils.clearCalendarEventDirtyFlag(provider, account, androidEventId, TAG)
            return null
        }
        val updateDataPacketB64 = android.util.Base64.encodeToString(seipdBytes, android.util.Base64.NO_WRAP)

        // Build AttendeesEventContent for update (encrypt with reused session key).
        //
        // Deliberately NOT ICalUtils.attendeesEncryptedPart, which the create's two sites share: this one
        // passes the SERVER's UID and they pass the row's own, and nothing would catch a later edit that
        // swapped them, because both are non-blank strings of the same shape. Proton would then answer 2008
        // to every update, in silence. So the envelope is written twice on purpose: keep the two in step.
        var updateAttendeesContent: List<EventPart>? = null
        var updateApiAttendees: List<SyncAttendee>? = null
        if (attendees.isNotEmpty()) {
            val attendeesICal = "BEGIN:VCALENDAR${cr}${prodId}VERSION:2.0${cr}BEGIN:VEVENT${cr}" +
                "UID:$updateUid${cr}" +
                "DTSTAMP:$nowStamp${cr}" +
                attendeeIcsLines +
                "END:VEVENT${cr}END:VCALENDAR"
            val attendeesSig = keyManager.sign(account.name, calendarMemberEmail, attendeesICal)
            val attendeesSeipdBytes = try {
                keyManager.encryptWithSessionKey(attendeesICal, symAlg, sessionKeyBytes)
            } catch (e: Exception) {
                SyncLog.w(TAG, "Failed to encrypt attendees for update: ${e.message}")
                null
            }
            if (attendeesSeipdBytes != null) {
                val attendeesDataB64 = android.util.Base64.encodeToString(attendeesSeipdBytes, android.util.Base64.NO_WRAP)
                updateAttendeesContent = listOf(
                    EventPart(type = 3, data = attendeesDataB64, signature = attendeesSig, memberId = null, author = null)
                )
            }
            updateApiAttendees = applyGuestPlan(apiAttendees, guestPlan)
        }

        // Build update sync operation — NO SharedKeyPacket, NO CalendarKeyPacket
        return CalendarEventSyncOperation(
            id = protonEventId,
            event = CalendarEventSyncData(
                sharedKeyPacket = null,
                sharedEventContent = listOf(
                    EventPart(type = 2, data = updateType2ICal, signature = updateType2Sig, memberId = null, author = null),
                    EventPart(type = 3, data = updateDataPacketB64, signature = updateType3Sig, memberId = null, author = null)
                ),
                calendarKeyPacket = null,
                calendarEventContent = listOf(
                    EventPart(type = 2, data = updateCalICal, signature = updateCalSig, memberId = null, author = null)
                ),
                attendeesEventContent = updateAttendeesContent,
                attendees = updateApiAttendees,
                addedProtonAttendees = protonGuestKeyPackets(account, guestPlan, symAlg, sessionKeyBytes),
                removedAttendeeAddresses = guestPlan.removedAttendeeAddresses
                // Notifications and Color are deliberately NOT sent here: they are this
                // member's personal part and go via the dedicated endpoint below, so they
                // survive even when this update is rejected for touching the attendee list.
            )
        )
    }

    /** The SEQUENCE this update will carry: one more only on a structural change, and never below the parent's. */
    private suspend fun decideUpdateSequence(
        account: Account,
        androidEventId: Long,
        originalId: Long?,
        storedSequence: Int,
        serverDtStart: String?,
        serverDtEnd: String?,
        serverRrule: String?,
        currentDtStartLine: String?,
        currentDtEndLine: String?,
        currentRrule: String?
    ): Int {
        val structuralChanged = serverDtStart != currentDtStartLine ||
            serverDtEnd != currentDtEndLine ||
            (serverRrule ?: "") != (currentRrule ?: "")
        var newSequence = if (structuralChanged) storedSequence + 1 else storedSequence
        // For single-edits (RECURRENCE-ID), SEQUENCE must be >= parent's SEQUENCE
        if (originalId != null) {
            val parentMapping = calendarEventSyncDao.getByAndroidId(account.name, originalId)
            if (parentMapping != null && newSequence < parentMapping.sequence) {
                newSequence = parentMapping.sequence
            }
        }
        if (structuralChanged) {
            SyncLog.d(TAG, "Structural change detected for event $androidEventId, incrementing SEQUENCE $storedSequence → $newSequence")
        }

        return newSequence
    }

    /**
     * The verdict on the operation Proton actually applied.
     *
     * Non-null means the caller must return it: a rejection is GAVE_UP after three strikes and RETRY before
     * that. NULL MEANS ACCEPTED, carry on, which is the opposite of the create half's payload helper, where
     * null means stop. Do not copy `?: return` onto this call.
     */
    private fun settleUpdateOpResponse(
        updateResponse: retrofit2.Response<Map<String, Any>>,
        provider: ContentProviderClient,
        account: Account,
        androidEventId: Long,
        title: String,
        syncResult: SyncResult,
        parts: EventIcalParts,
        existingMapping: CalendarEventSyncEntity,
        guestPlan: GuestStatusPlan,
        personalSaved: Boolean,
        apiNotifications: List<EventNotification>?,
        apiColor: String?,
        protonEventId: String,
        newSequence: Int
    ): UploadOutcome? {
        val op = firstSyncOpResult(updateResponse.body())
        if (op.code != null && op.code != 1000 && op.code != 1001) {
            val gaveUp = handleUploadOpRejection(
                provider, account, androidEventId, title, op.code, op.error, "Update", syncResult,
                // Deleted occurrences are a shared change the local row does not show yet.
                if (parts.hasExtraExdates) null else existingMapping.sharedFingerprint,
                guestPlan.matchesServer, personalSaved
            )
            return if (gaveUp) UploadOutcome.GAVE_UP else UploadOutcome.RETRY
        }
        clearUploadRejectionCounter(androidEventId)
        // Only when there was something to save: with no reminders and no colour of this member's own, the
        // call carried nulls and its failure changed nothing.
        if (!personalSaved && (apiNotifications != null || apiColor != null)) reportUnsavedPersonalPart(account, title)
        SyncLog.i(TAG, "Successfully updated event $androidEventId on Proton (protonId=$protonEventId, opCode=${op.code}, seq=$newSequence)")

        return null
    }

    /**
     * [serverLastEditTime] with a line in the log, because falling back to this phone's clock is what
     * brings the two-clock comparison back. If the "carried no LastEditTime" line ever starts
     * appearing, the response shape changed and uploads are stamping local time again.
     *
     * The parsing itself lives in UploadSettleDecisions.kt so it can be tested.
     */
    private fun serverStamp(responseBody: Map<String, Any>?): Long? {
        val stamp = serverLastEditTime(responseBody)
        if (stamp == null) {
            SyncLog.d(TAG, "events/sync response carried no LastEditTime; stamping the mapping with this phone's clock")
        } else {
            SyncLog.d(TAG, "Stamped the mapping with the server's LastEditTime ($stamp)")
        }
        return stamp
    }

    /** The mapping row a successful update leaves behind, cache fields and all. */
    private fun buildUpdatedMapping(
        existingMapping: CalendarEventSyncEntity,
        serverLastEditTime: Long?,
        newSequence: Int,
        existingSharedKeyPacket: String?,
        updateUid: String,
        currentDtStartLine: String?,
        currentDtEndLine: String?,
        currentRrule: String?,
        recurrenceIdStr: String,
        provider: ContentProviderClient,
        androidEventId: Long
    ): CalendarEventSyncEntity {
        val baseline = rowBaseline(provider, androidEventId)
        return existingMapping.copy(
            // The server's clock, falling back to this phone's only when the response did not say.
            lastModified = serverLastEditTime ?: (System.currentTimeMillis() / 1000),
            sequence = newSequence,
            cachedSharedKeyPacket = existingSharedKeyPacket,
            cachedDtStartLine = currentDtStartLine,
            cachedDtEndLine = currentDtEndLine,
            cachedRrule = currentRrule,
            cachedUid = updateUid,
            cachedIsOrganizer = 1,
            // Keep the override marker current on edits (recurrenceIdStr is "" for
            // non-overrides -> null), so the master-selection fallback stays correct.
            cachedRecurrenceId = recurrenceIdStr.takeIf { it.isNotEmpty() },
            // Re-baseline the collision fingerprint: this upload is a new moment where
            // both sides agree. Leaving the old value would describe the state BEFORE
            // the edit we just sent, so the next time this event went dirty for a
            // personal-part change the comparison would differ and raise a collision
            // prompt for a change that collides with nothing.
            sharedFingerprint = baseline.first,
            // The times the cached lines above are the text for, as they stand now (UploadTimes).
            cachedTimes = baseline.second
        )
    }

    /** We are an attendee, not the organizer: only our own participation status goes up, never the event content. */
    private suspend fun uploadAttendeeRsvp(
        account: Account,
        provider: ContentProviderClient,
        androidEventId: Long,
        calInfo: CalendarInfo?,
        protonCalendarId: String,
        protonEventId: String,
        existingEvent: CalendarEventDetail?
    ): UploadOutcome {
    SyncLog.i(TAG, "Event $protonEventId: we are attendee (not organizer), updating participation status")
    var rsvpRetryable = false
    try {
        // Read our local attendee status from CalendarContract, matching every address that is this
        // account (ownAddresses) and never another member's: on a calendar shared with this account an
        // invitation may be its owner's, and that answer is theirs. Our own member's address breaks ties.
        val userAddrs = keyManager.getAddressEmails(account.name).map { it.lowercase() }.toSet()
        val calMemberEmail = ownMember(calInfo?.members, userAddrs)?.email?.lowercase()
        val allMyEmails = ownAddresses(userAddrs, account.name)
                val ownRsvp = resolveOwnRsvp(
                    provider = provider,
                    androidEventId = androidEventId,
                    allMyEmails = allMyEmails,
                    calMemberEmail = calMemberEmail
                )
        val localStatus = ownRsvp.status

        // Map Android status to Proton status
        // Android: NONE=0, ACCEPTED=1, DECLINED=2, INVITED=3, TENTATIVE=4
        // Proton:  NEEDS_ACTION=0, TENTATIVE=1, DECLINED=2, ACCEPTED=3
        val protonStatus = when (localStatus) {
            CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> 3  // Android 1 → Proton 3
            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> 2  // Android 2 → Proton 2
            CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> 1 // Android 4 → Proton 1
            CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> 0   // Android 3 → Proton 0 (needs-action)
            else -> 0
        }

        // Find our attendee ID from the Proton event, by token: UID + our address (ownAttendeeId). With two of
        // our addresses invited, the answer belongs to the one whose row the phone has it on, then to our own
        // member's: never to whichever address happens to come first in the set.
        val protonAttendees = existingEvent?.let { allAttendeesOf(account, protonCalendarId, it)?.first } ?: emptyList()
        val matchedAttendeeId = ownAttendeeId(
            protonAttendees, existingEvent?.uid ?: "",
            (listOfNotNull(ownRsvp.email, calMemberEmail) + allMyEmails).distinct()
        )

        if (matchedAttendeeId == null) {
            SyncLog.w(TAG, "Could not find our attendee ID in event $protonEventId")
        } else {
            val statusPayload = com.google.gson.Gson().toJson(mapOf(
                "Status" to protonStatus,
                "UpdateTime" to (System.currentTimeMillis() / 1000)
            )).toRequestBody("application/json; charset=utf-8".toMediaType())

            val statusResp = apiClient.updateAttendeeStatus(
                account.name, protonCalendarId, protonEventId, matchedAttendeeId, statusPayload
            )
            if (statusResp.isSuccessful) {
                val statusLabel = when (protonStatus) { 3 -> "ACCEPTED"; 2 -> "DECLINED"; 1 -> "TENTATIVE"; else -> "NEEDS-ACTION" }
                SyncLog.i(TAG, "Updated attendance to $statusLabel for event $protonEventId")
            } else {
                val err = statusResp.errorBody()?.string() ?: "no body"
                // 429/408/5xx are transient — a cleared dirty flag here would
                // silently drop the user's RSVP (the organizer never learns of it).
                rsvpRetryable = statusResp.code() == 429 || statusResp.code() == 408 ||
                    statusResp.code() >= 500
                SyncLog.w(TAG, "Failed to update attendance: HTTP ${statusResp.code()} - $err" +
                    if (rsvpRetryable) " (transient - will retry next sync)" else " (permanent - giving up)")
            }
        }
    } catch (e: Exception) {
        rsvpRetryable = true
        SyncLog.w(TAG, "Failed to update attendee status for $protonEventId: ${e.message} - will retry next sync")
    }
    if (!rsvpRetryable) {
        SyncUtils.clearCalendarEventDirtyFlag(provider, account, androidEventId, TAG)
    }
    return if (rsvpRetryable) UploadOutcome.RETRY else UploadOutcome.DONE
    }

    /** Our answer as the phone has it ([resolveOwnRsvp]): the status, and the address of the row it is on. */
    private class OwnRsvp(val status: Int, val email: String?)

    /** Our own answer as the phone has it: the most decisive status across our addresses, our own member's ([ownMember]) first. */
    private fun resolveOwnRsvp(
        provider: ContentProviderClient,
        androidEventId: Long,
        allMyEmails: Set<String>,
        calMemberEmail: String?
    ): OwnRsvp {
    // Read all our attendee statuses — prefer calendar member email,
    // then pick the one with the most decisive status (ACCEPTED > DECLINED > TENTATIVE > INVITED > NONE)
    var localStatus = CalendarContract.Attendees.ATTENDEE_STATUS_NONE
    var matchedEmail: String? = null
    val allAttendeeEmails = mutableListOf<String>()
    val myStatuses = mutableMapOf<String, Int>()
    provider.query(
        CalendarContract.Attendees.CONTENT_URI,
        arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL, CalendarContract.Attendees.ATTENDEE_STATUS),
        "${CalendarContract.Attendees.EVENT_ID} = ?",
        arrayOf(androidEventId.toString()), null
    )?.use { c ->
        while (c.moveToNext()) {
            val email = c.getString(0)?.lowercase()?.trim() ?: continue
            val status = c.getInt(1)
            allAttendeeEmails.add("$email(status=$status)")
            if (email in allMyEmails) {
                // Keep the most DECISIVE answer for an address, not whichever row the
                // cursor happened to return last. An event we were invited to reliably
                // carries TWO rows for our own address: the one we insert from Proton's
                // attendee list, plus the one AOSP's CalendarProvider2 fabricates for
                // OWNER_ACCOUNT because the INSERT carries SELF_ATTENDEE_STATUS. With
                // last-write-wins, that stale INVITED twin silently overwrote the
                // ACCEPTED/DECLINED the user had just chosen, and the RSVP uploaded as
                // NEEDS-ACTION every time.
                val prev = myStatuses[email]
                if (prev == null || rsvpDecisiveness(status) < rsvpDecisiveness(prev)) {
                    myStatuses[email] = status
                }
            }
        }
    }
    // Priority: pick the attendee with the most decisive RSVP response
    // (ACCEPTED > DECLINED > TENTATIVE > INVITED > NONE)
    // Business Calendar updates the account-name attendee row, while
    // the calendar-member-email row may stay at INVITED from Proton sync.
    if (myStatuses.isNotEmpty()) {
        val priorityOrder = listOf(
            CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
            CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
        )
        val best = myStatuses.entries.sortedBy { (email, s) ->
            val idx = priorityOrder.indexOf(s)
            // Decisive status first, then prefer calendar member email as tiebreaker
            if (idx >= 0) idx else priorityOrder.size + (if (email == calMemberEmail) 0 else 1)
        }.first()
        matchedEmail = best.key
        localStatus = best.value
    }
    SyncLog.d(TAG, "RSVP: our emails=$allMyEmails, attendees=$allAttendeeEmails, matched=$matchedEmail, localStatus=$localStatus")

        return OwnRsvp(localStatus, matchedEmail)
    }

    /**
     * After the mapping is stored: release the conflict hold, mirror the guests' answers, send the mail,
     * clear the dirty flag and count the invite strike.
     */
    private fun finishUpdateSuccess(
        provider: ContentProviderClient,
        account: Account,
        androidEventId: Long,
        protonEventId: String,
        row: LocalEventRow,
        parts: EventIcalParts,
        organizerInfo: OrganizerDecision,
        attendees: List<AttendeeInfo>,
        existingMapping: CalendarEventSyncEntity,
        newSequence: Int,
        suppressAttendeeEmails: Boolean,
        guestPlan: GuestStatusPlan,
        existingSharedKeyPacket: String?
    ) {
        // The local version is now on the server, so a "keep mine" decision has been
        // carried out and the download may resume applying server changes to this event.
        EventConflictGuard.clearLocalWins(context, account.name, protonEventId)
        mirrorGuestStatusesLocally(provider, account, androidEventId, guestPlan)

        // Taken before the mail goes out, so an edit made during the send is not mistaken for the version
        // that was just uploaded.
        val beforeInvites = EventConflictGuard.fingerprintLocalRow(provider, androidEventId)

        // Invitations to new guests, updates to existing ones, cancellations to removed ones.
        val updateHasFailedInvites = sendUpdateInviteEmails(
            account, androidEventId, protonEventId, row, parts, organizerInfo, attendees,
            existingMapping, newSequence, suppressAttendeeEmails, guestPlan, existingSharedKeyPacket
        )

        // The upload succeeded, so the dirty flag goes, unless the user changed the event while the mail
        // was being sent. Invite retries are tracked separately.
        clearDirtyUnlessEditedSince(provider, account, androidEventId, beforeInvites)
        recordInviteRetryStrike(
            androidEventId = androidEventId,
            protonEventId = protonEventId,
            hasFailedInvites = updateHasFailedInvites
        )
    }

    /** The update came back non-2xx: retire the upload if Proton refuses this calendar for good, otherwise retry. */
    private fun handleUpdateHttpFailure(
        updateResponse: retrofit2.Response<Map<String, Any>>,
        provider: ContentProviderClient,
        account: Account,
        androidEventId: Long,
        title: String,
        syncResult: SyncResult
    ): UploadOutcome {
        val errorBody = updateResponse.errorBody()?.string() ?: "no body"
        SyncLog.e(TAG, "Failed to update event on Proton: HTTP ${updateResponse.code()} - $errorBody")
        // The UPDATE half needs exactly what the CREATE half needs. v1.10.53 first shipped the
        // refusal handling and the recorded reason on CREATE only, so EDITING an event on a
        // calendar Proton refuses still retried for ever and still ended the run with
        // "no reason recorded" - the very sibling-path omission that fix was written to correct.
        if (!retireRefusedUpload(provider, account, updateResponse.code(), errorBody, androidEventId, title, syncResult, "Update")) {
            synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ }
            SyncUtils.recordSwallowedFailure(
                "calendar", account.name,
                uploadSwallowedFailure("update an event on Proton", updateResponse.code(), errorBody)
            )
            return UploadOutcome.RETRY
        }
        return UploadOutcome.GAVE_UP
    }

    /**
     * The CREATE half of [uploadModifiedEvent]: no Proton event exists yet, so the type-3 content
     * is encrypted to a fresh session key wrapped for the calendar key (and, when there are Proton
     * attendees, re-wrapped for their address keys too) and the server assigns the event id.
     *
     * Split out of uploadModifiedEvent so each half gets its own coroutine state machine.
     */
    private suspend fun uploadCreateEvent(
        account: Account,
        provider: ContentProviderClient,
        androidEventId: Long,
        syncResult: SyncResult,
        uploadContext: CalendarUploadContext,
        suppressAttendeeEmails: Boolean,
        row: LocalEventRow,
        parts: EventIcalParts,
        organizerInfo: OrganizerDecision,
        attendeeIcs: AttendeeIcsParts,
        attendees: List<AttendeeInfo>,
    ): UploadOutcome {
        val protonCalendarId = uploadContext.protonCalendarId
        val memberId = uploadContext.memberId
        val title = row.title
        val description = row.description
        val location = row.location
        val dtStartMillis = row.dtStartMillis
        val allDay = row.allDay
        val timezone = row.timezone
        val uid2445 = row.uid2445
        val rrule = row.rrule
        val durationStr = row.durationStr
        val availability = row.availability
        val originalId = row.originalId
        val timeBlock = parts.timeBlock
        val rruleStr = parts.rruleStr
        val exdateStr = parts.exdateStr
        val recurrenceIdStr = parts.recurrenceIdStr
        val effectiveDtEndMillis = parts.effectiveDtEndMillis
        val effectiveOrganizer = organizerInfo.effectiveOrganizer

        // SharedEventContent type-2: signed cleartext structural data.
        // SEQUENCE on creates is normally 0, but for exception events (RECURRENCE-ID, i.e.
        // a single-occurrence override of a recurring parent) RFC 5546 / Proton iTIP
        // require the exception's SEQUENCE to be ≥ the parent's. Without this the iTIP
        // processors at attendees' inboxes (Gmail, Outlook) silently discard the
        // exception as "old version" when the parent has already been edited at least
        // once. uploadUpdateEvent enforces the same rule for edits — but the *first* upload
        // of a freshly-created exception goes through this path, so we mirror it here.
        val createSequence = if (originalId != null) {
            val parentMapping = calendarEventSyncDao.getByAndroidId(account.name, originalId)
            val parentSeq = parentMapping?.sequence ?: 0
            SyncLog.d(TAG, "Create event $androidEventId is exception of parent androidId=$originalId; using SEQUENCE=$parentSeq (parent mapping ${if (parentMapping == null) "MISSING - falling back to 0" else "found"})")
            parentSeq
        } else 0
        val payload = buildCreatePayload(
            account = account,
            provider = provider,
            androidEventId = androidEventId,
            syncResult = syncResult,
            uploadContext = uploadContext,
            suppressAttendeeEmails = suppressAttendeeEmails,
            row = row,
            parts = parts,
            organizerInfo = organizerInfo,
            attendeeIcs = attendeeIcs,
            attendees = attendees,
            createSequence = createSequence
        ) ?: return UploadOutcome.DONE
        val syncOp = payload.syncOp
        val overridePlan = payload.overridePlan
        val finalSharedKeyPacketB64 = payload.finalSharedKeyPacketB64
        val finalSessionKey = payload.finalSessionKey

        // 10. Send to API
        val response = apiClient.syncCalendarEvents(
            accountName = account.name,
            calendarId = protonCalendarId,
            body = CalendarEventSyncRequest(
                memberId = memberId,
                events = listOf(syncOp)
            )
        )

        @Suppress("UNCHECKED_CAST")
        if (response.isSuccessful) {
            val responseBody = response.body()
            val responses = responseBody?.get("Responses") as? List<Map<String, Any>>

            // Check per-operation response code (API returns HTTP 200 but individual ops can fail)
            val op = firstSyncOpResult(responseBody)
            if (op.code != null && op.code != 1000 && op.code != 1001) {
                // A single-occurrence edit is a change to an existing meeting, not a new event: it gets the
                // update's explanation when Proton refuses it.
                val gaveUp = handleUploadOpRejection(
                    provider, account, androidEventId, title, op.code, op.error,
                    if (originalId != null) "Occurrence edit" else "Create", syncResult
                )
                return if (gaveUp) UploadOutcome.DONE else UploadOutcome.RETRY
            }

            clearUploadRejectionCounter(androidEventId)
            SyncLog.i(TAG, "Successfully uploaded event $androidEventId to Proton (opCode=${op.code})")
            if (overridePlan != null) mirrorGuestStatusesLocally(provider, account, androidEventId, overridePlan)

            run {
                val newEventId = responses?.firstOrNull()?.let { resp ->
                    val eventResp = resp["Response"] as? Map<String, Any>
                    val event = eventResp?.get("Event") as? Map<String, Any>
                    event?.get("ID") as? String
                }
                if (newEventId != null) {
                    calendarEventSyncDao.upsert(
                        buildCreatedMapping(
                            serverLastEditTime = serverStamp(responseBody),
                            provider = provider,
                            account = account,
                            androidEventId = androidEventId,
                            protonCalendarId = protonCalendarId,
                            newEventId = newEventId,
                            createSequence = createSequence,
                            finalSharedKeyPacketB64 = finalSharedKeyPacketB64,
                            timeBlock = timeBlock,
                            rrule = rrule,
                            uid2445 = uid2445,
                            recurrenceIdStr = recurrenceIdStr
                        )
                    )
                    writeSyncIdBack(
                        provider = provider,
                        account = account,
                        androidEventId = androidEventId,
                        newEventId = newEventId
                    )
                }
            }

            // Extract SharedEventID and session key from API response for P2P sharing
            val responseEvent = responses?.firstOrNull()?.let { resp ->
                @Suppress("UNCHECKED_CAST")
                (resp["Response"] as? Map<String, Any>)?.get("Event") as? Map<String, Any>
            }
            val sharedEventId = responseEvent?.get("SharedEventID") as? String
            // The key of the key packet this create sent, not sharedSessionKey: with Proton attendees
            // the content was re-encrypted under a new key. Proton Mail wraps this key into the
            // attendee's own copy when they answer from the invitation, so the first key gave them a
            // copy that nothing could decrypt.
            val responseSessionKey = finalSessionKey?.let {
                android.util.Base64.encodeToString(it.second, android.util.Base64.NO_WRAP)
            }

            // Send invitation emails and track results
            val createdProtonEventId = responses?.firstOrNull()?.let { resp ->
                @Suppress("UNCHECKED_CAST")
                val eventResp = (resp["Response"] as? Map<String, Any>)?.get("Event") as? Map<String, Any>
                eventResp?.get("ID") as? String
            }
            // Taken before the mail goes out, for the same reason as the update path.
            val beforeCreateInvites = EventConflictGuard.fingerprintLocalRow(provider, androidEventId)
            val hasFailedInvites = sendCreateInvites(
                account = account,
                androidEventId = androidEventId,
                createdProtonEventId = createdProtonEventId,
                attendees = attendees,
                suppressAttendeeEmails = suppressAttendeeEmails,
                overridePlan = overridePlan,
                event = CreateInviteEvent(
                    uid2445 = uid2445,
                    title = title,
                    description = description,
                    location = location,
                    dtStartMillis = dtStartMillis,
                    effectiveDtEndMillis = effectiveDtEndMillis,
                    timezone = timezone,
                    allDay = allDay,
                    availability = availability,
                    effectiveOrganizer = effectiveOrganizer,
                    sharedEventId = sharedEventId,
                    responseSessionKey = responseSessionKey,
                    createSequence = createSequence,
                    recurrenceIdStr = recurrenceIdStr,
                    rruleStr = rruleStr,
                    exdateStr = exdateStr,
                    originalId = originalId,
                    inviteZoneRules = parts.inviteZoneRules,
                    dtStartText = parts.startText,
                    dtEndText = parts.endText
                )
            )

            writeUidBack(
                provider = provider,
                account = account,
                androidEventId = androidEventId,
                uid2445 = uid2445
            )

            // The upload succeeded, so the dirty flag goes, unless the user changed the event while the
            // invitations were being sent. Invite retries are tracked separately.
            clearDirtyUnlessEditedSince(provider, account, androidEventId, beforeCreateInvites)
            recordInviteRetryStrike(
                androidEventId = androidEventId,
                protonEventId = createdProtonEventId,
                hasFailedInvites = hasFailedInvites
            )
            synchronized(syncResult.stats) { syncResult.stats.numUpdates++ }
        } else {
            val errorBody = response.errorBody()?.string() ?: "no body"
            SyncLog.e(TAG, "Failed to upload event to Proton: HTTP ${response.code()} - $errorBody")
            SyncLog.e(TAG, "Failed event $androidEventId: rrule=${rrule ?: "(none)"} duration=${durationStr ?: "(none)"}")
            // Name the reason. Without this the run ends "Calendar sync FAILED: no reason
            // recorded" and the notification says "an unexpected error occurred", while the cause
            // sits two lines above in the log. The fetch paths were given this and the upload path
            // was not; a field report arrived showing exactly that.
            if (!retireRefusedUpload(
                    provider, account, response.code(), errorBody, androidEventId, title, syncResult,
                    if (originalId != null) "Occurrence edit" else "Create"
                )) {
                synchronized(syncResult.stats) { syncResult.stats.numIoExceptions++ }
                SyncUtils.recordSwallowedFailure(
                    "calendar", account.name,
                    uploadSwallowedFailure("save an event to Proton", response.code(), errorBody)
                )
                return UploadOutcome.RETRY
            }
        }
        return UploadOutcome.DONE
    }

    /**
     * Everything a create sends, built and encrypted: the two iCals, the calendar-key encryption, the shared
     * session key, the Proton-attendee rewrap and the sync operation itself.
     *
     * Null means the upload is over and the caller must return DONE: the two crypto failures inside have
     * already cleared the dirty flag and recorded the failure, exactly as they did inline.
     *
     * Not suspend, deliberately, so this bytecode leaves the caller's coroutine state machine. It must only
     * ever be called from an IO thread: three things inside block on network or database through runBlocking.
     */
    private fun buildCreatePayload(
        account: Account,
        provider: ContentProviderClient,
        androidEventId: Long,
        syncResult: SyncResult,
        uploadContext: CalendarUploadContext,
        suppressAttendeeEmails: Boolean,
        row: LocalEventRow,
        parts: EventIcalParts,
        organizerInfo: OrganizerDecision,
        attendeeIcs: AttendeeIcsParts,
        attendees: List<AttendeeInfo>,
        createSequence: Int
    ): CreatePayload? {
        val protonCalendarId = uploadContext.protonCalendarId
        val memberId = uploadContext.memberId
        val cr = "\r\n"
        val title = row.title
        val description = row.description
        val location = row.location
        val uid2445 = row.uid2445
        val originalId = row.originalId
        val nowStamp = parts.nowStamp
        val prodId = parts.prodId
        val timeBlock = parts.timeBlock
        val rruleStr = parts.rruleStr
        val exdateStr = parts.exdateStr
        val statusStr = parts.statusStr
        val transpStr = parts.transpStr
        val recurrenceIdStr = parts.recurrenceIdStr
        val apiNotifications = parts.apiNotifications
        val apiColor = parts.apiColor
        val organizerStr = organizerInfo.organizerStr
        val effectiveOrganizer = organizerInfo.effectiveOrganizer
        val calendarMemberEmail = organizerInfo.calendarMemberEmail
        val attendeeIcsLines = attendeeIcs.icsLines
        val apiAttendees = attendeeIcs.apiAttendees

        val type2ICal = ICalUtils.sharedStructuralPart(
            prodIdLine = prodId,
            uid = uid2445,
            dtStamp = nowStamp,
            recurrenceIdLine = recurrenceIdStr,
            timeBlock = timeBlock,
            rruleLine = rruleStr,
            exdateLine = exdateStr,
            organizerLine = organizerStr,
            sequence = createSequence
        )

        // SharedEventContent type-3: encrypted sensitive data (SUMMARY, DESCRIPTION, LOCATION, CREATED)
        val type3ICal = ICalUtils.sharedEncryptedPart(prodId, uid2445, nowStamp, nowStamp, recurrenceIdStr, title, description, location)

        // 8. Encrypt type-3 with calendar key and split into KeyPacket + DataPacket.
        //
        // This is create-only and must stay that way. It used to run before the create/update fork,
        // where an update paid for a full public-key encryption it then discarded — and worse, a
        // failure here cleared the dirty flag, silently throwing away the user's edit for
        // ciphertext the update path never touches. Updates re-encrypt under the event's existing
        // session key instead, so they must never reach this block.
        val armoredEncrypted = try {
            keyManager.encryptWithCalendarKey(account.name, protonCalendarId, type3ICal)
        } catch (e: Exception) {
            SyncLog.e(TAG, "Failed to encrypt event with calendar key: ${e.message}", e)
            // Clear dirty flag to avoid infinite retry on crypto errors
            SyncUtils.clearCalendarEventDirtyFlag(provider, account, androidEventId, TAG)
            synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ }
            return null
        }

        val (keyPacketBytes, dataPacketBytes) = try {
            keyManager.splitPgpMessage(armoredEncrypted)
        } catch (e: Exception) {
            SyncLog.e(TAG, "Failed to split PGP message: ${e.message}", e)
            SyncUtils.clearCalendarEventDirtyFlag(provider, account, androidEventId, TAG)
            synchronized(syncResult.stats) { syncResult.stats.numSkippedEntries++ }
            return null
        }

        val sharedKeyPacketB64 = android.util.Base64.encodeToString(keyPacketBytes, android.util.Base64.NO_WRAP)
        val dataPacketB64 = android.util.Base64.encodeToString(dataPacketBytes, android.util.Base64.NO_WRAP)

        // Recover the shared session key for reuse by AttendeesEventContent on creates
        val sharedSessionKey = keyManager.recoverSessionKey(keyPacketBytes, account.name)

        // For creates: use type2ICal (already built above with RRULE/DURATION/EXDATE)
        SyncLog.d(TAG, "Create type-2 for event $androidEventId: seq=$createSequence hasRrule=${rruleStr.isNotEmpty()} hasExdate=${exdateStr.isNotEmpty()}")
        SyncLog.d(TAG, "Create type-3 for event $androidEventId: hasDesc=${description.isNotEmpty()} hasLoc=${location.isNotEmpty()}")

        // CalendarEventContent type-2: calendar-level signed data (STATUS, TRANSP, EXDATE)
        // CalendarEventContent optimization: skip if only UID+DTSTAMP (all defaults)
        val hasCalendarData = exdateStr.isNotEmpty() ||
            statusStr != "STATUS:CONFIRMED${cr}" ||
            transpStr != "TRANSP:OPAQUE${cr}"
        val calendarICal = if (hasCalendarData) {
            "BEGIN:VCALENDAR${cr}${prodId}VERSION:2.0${cr}BEGIN:VEVENT${cr}" +
                "UID:$uid2445${cr}" +
                "DTSTAMP:$nowStamp${cr}" +
                exdateStr +
                statusStr +
                transpStr +
                "END:VEVENT${cr}END:VCALENDAR"
        } else null

        // A single-occurrence edit of a series with guests: they keep their answer to the series unless
        // this occurrence moved. Null for a new event, whose guests have not answered anything yet.
        val overridePlan = if (originalId != null && attendees.isNotEmpty()) {
            planOverrideGuests(
                account, provider, protonCalendarId, androidEventId, row, attendees,
                organizerInfo, parts, suppressAttendeeEmails
            )
        } else null

        // Build AttendeesEventContent type-3 ICS (encrypted with shared session key)
        var attendeesEventContentList: List<EventPart>? = null
        var apiAttendeesList: List<SyncAttendee>? = null
        if (attendees.isNotEmpty()) {
            val attendeesICal = ICalUtils.attendeesEncryptedPart(
                prodIdLine = prodId, uid = uid2445, dtStamp = nowStamp, attendeeLines = attendeeIcsLines
            )
            val attendeesSig = keyManager.sign(account.name, calendarMemberEmail, attendeesICal)
            // Reuse the SHARED session key (not a new one): Proton's clients encrypt all shared content,
            // the attendees part included, under the same session key. Previously used
            // encryptWithCalendarKey which generated a separate key, making attendee data
            // potentially undecryptable.
            if (sharedSessionKey != null) {
                val (symAlg, sessionKeyBytes) = sharedSessionKey
                val attendeesSeipdBytes = try {
                    keyManager.encryptWithSessionKey(attendeesICal, symAlg, sessionKeyBytes)
                } catch (e: Exception) {
                    SyncLog.w(TAG, "Failed to encrypt attendees with shared session key: ${e.message}")
                    null
                }
                if (attendeesSeipdBytes != null) {
                    val attendeesDataB64 = android.util.Base64.encodeToString(attendeesSeipdBytes, android.util.Base64.NO_WRAP)
                    attendeesEventContentList = listOf(
                        EventPart(type = 3, data = attendeesDataB64, signature = attendeesSig, memberId = null, author = null)
                    )
                }
            }
            apiAttendeesList = if (overridePlan != null) applyGuestPlan(apiAttendees, overridePlan) else apiAttendees
        }

        // Sign each part with the calendar owner's address key (Proton verifies against
        // the calendar-owner key on multi-address accounts; default-key signatures fail 2001).
        val signedICalSig = keyManager.sign(account.name, calendarMemberEmail, type2ICal)
        val type3Sig = keyManager.sign(account.name, calendarMemberEmail, type3ICal) // sign the plaintext before encryption
        val calendarICalSig = calendarICal?.let { keyManager.sign(account.name, calendarMemberEmail, it) }

        val rewrap = rewrapForProtonAttendees(
            account = account,
            attendees = attendees,
            protonCalendarId = protonCalendarId,
            memberId = memberId,
            prodId = prodId,
            uid2445 = uid2445,
            nowStamp = nowStamp,
            type3ICal = type3ICal,
            attendeeIcsLines = attendeeIcsLines,
            effectiveOrganizer = effectiveOrganizer,
            calendarMemberEmail = calendarMemberEmail,
            sharedKeyPacketB64 = sharedKeyPacketB64,
            dataPacketB64 = dataPacketB64,
            sharedSessionKey = sharedSessionKey,
            attendeesEventContent = attendeesEventContentList
        )
        val addedProtonAttendees = rewrap.addedProtonAttendees
        val finalSharedKeyPacketB64 = rewrap.finalSharedKeyPacketB64
        val finalDataPacketB64 = rewrap.finalDataPacketB64
        val finalSessionKey = rewrap.finalSessionKey
        attendeesEventContentList = rewrap.attendeesEventContent

        val syncOp = CalendarEventSyncOperation(
            id = null, // null for creates, server assigns the ID
            overwrite = 0,
            event = CalendarEventSyncData(
                sharedKeyPacket = finalSharedKeyPacketB64,
                sharedEventContent = listOf(
                    EventPart(type = 2, data = type2ICal, signature = signedICalSig, memberId = null, author = null),
                    EventPart(type = 3, data = finalDataPacketB64, signature = type3Sig, memberId = null, author = null)
                ),
                calendarEventContent = if (calendarICal != null && calendarICalSig != null) listOf(
                    EventPart(type = 2, data = calendarICal, signature = calendarICalSig, memberId = null, author = null)
                ) else null,
                attendeesEventContent = attendeesEventContentList,
                attendees = apiAttendeesList,
                notifications = apiNotifications,
                color = apiColor,
                addedProtonAttendees = addedProtonAttendees
            )
        )

        return CreatePayload(
            syncOp = syncOp,
            overridePlan = overridePlan,
            finalSharedKeyPacketB64 = finalSharedKeyPacketB64,
            finalSessionKey = finalSessionKey
        )
    }

    /**
     * Re-encrypts the shared content to the calendar key plus every Proton attendee's key, so the server
     * creates a shared event. That makes a NEW session key, so the key packet, the content, the attendee part
     * and the invitation's key are rebuilt under it and switched over together; switching the key packet on
     * its own would send one the other parts cannot be opened with.
     *
     * Nothing here throws: the try whose catch travels with it returns the values unchanged, which is the
     * identity only because the five are assigned in one place, at the end, together.
     */
    private fun rewrapForProtonAttendees(
        account: Account,
        attendees: List<AttendeeInfo>,
        protonCalendarId: String,
        memberId: String?,
        prodId: String,
        uid2445: String,
        nowStamp: String,
        type3ICal: String,
        attendeeIcsLines: String,
        effectiveOrganizer: String,
        calendarMemberEmail: String?,
        sharedKeyPacketB64: String,
        dataPacketB64: String,
        sharedSessionKey: Pair<Int, ByteArray>?,
        attendeesEventContent: List<EventPart>?
    ): AttendeeRewrap {
        // A parameter cannot be reassigned, so the moved lines keep working through an alias.
        var attendeesEventContentList = attendeesEventContent
        // For Proton attendees: generate AddressKeyPackets so the server creates shared events.
        // Encrypt SharedEventContent to calendar key + each Proton attendee's public key.
        var addedProtonAttendees: List<ProtonAttendeeKeyPacket>? = null
        var finalSharedKeyPacketB64 = sharedKeyPacketB64
        var finalDataPacketB64 = dataPacketB64
        // The session key the invitation carries (X-PM-SESSION-KEY). It has to open the key packet
        // this create sends, so it only ever changes together with finalSharedKeyPacketB64.
        var finalSessionKey = sharedSessionKey
        if (attendees.isNotEmpty()) {
            try {
                val userAddrs = keyManager.getAddressEmails(account.name).map { it.lowercase() }.toSet()
                val protonAttendeeEmails = lookupProtonRecipientKeys(
                    account,
                    attendees.map { it.email }
                        .filter { it.lowercase() !in userAddrs && it.lowercase() != effectiveOrganizer.lowercase() }
                )

                if (protonAttendeeEmails.isNotEmpty()) {
                    // Re-encrypt SharedEventContent to calendar key + all Proton attendees. This makes a
                    // NEW session key, so everything built under the first one is rebuilt under it first
                    // and then switched over at once: the key packet, the content, the attendee part and
                    // the invitation's key. Switching the key packet early left the attendee part (when
                    // the new key could not be recovered, or a later step threw) and the invitation
                    // under the old key, which the sent key packet no longer opens.
                    val attendeePubKeys = protonAttendeeEmails.map { it.second }
                    val result = keyManager.encryptWithCalendarKeyAndRecipients(
                        account.name, protonCalendarId, type3ICal, attendeePubKeys
                    )
                    val newSharedKey = keyManager.recoverSessionKey(result.first, account.name)
                        ?: throw IllegalStateException("the re-encrypted key packet does not open")
                    val attendeesICal2 = ICalUtils.attendeesEncryptedPart(
                        prodIdLine = prodId, uid = uid2445, dtStamp = nowStamp, attendeeLines = attendeeIcsLines
                    )
                    val attendeesSig2 = keyManager.sign(account.name, calendarMemberEmail, attendeesICal2)
                    val seipdBytes = keyManager.encryptWithSessionKey(attendeesICal2, newSharedKey.first, newSharedKey.second)
                    val dataB64 = android.util.Base64.encodeToString(seipdBytes, android.util.Base64.NO_WRAP)
                    val keyPackets = protonAttendeeEmails.mapIndexed { i, (email, _) ->
                        ProtonAttendeeKeyPacket(
                            email = email,
                            addressKeyPacket = android.util.Base64.encodeToString(result.second[i], android.util.Base64.NO_WRAP)
                        )
                    }

                    // Use the new calendarPKESK + SEIPD (shared session key with attendees)
                    finalSharedKeyPacketB64 = android.util.Base64.encodeToString(result.first, android.util.Base64.NO_WRAP)
                    finalDataPacketB64 = android.util.Base64.encodeToString(result.third, android.util.Base64.NO_WRAP)
                    finalSessionKey = newSharedKey
                    attendeesEventContentList = listOf(
                        EventPart(type = 3, data = dataB64, signature = attendeesSig2, memberId = null, author = null)
                    )
                    addedProtonAttendees = keyPackets
                    SyncLog.i(TAG, "Generated AddressKeyPackets for ${keyPackets.size} Proton attendees")
                }
            } catch (e: Exception) {
                SyncLog.w(TAG, "Failed to generate AddressKeyPackets: ${e.message}")
            }
        }

        return AttendeeRewrap(
            addedProtonAttendees = addedProtonAttendees,
            finalSharedKeyPacketB64 = finalSharedKeyPacketB64,
            finalDataPacketB64 = finalDataPacketB64,
            finalSessionKey = finalSessionKey,
            attendeesEventContent = attendeesEventContentList
        )
    }

    /**
     * Sends the invitations a create owes its guests, and reports whether any of them failed.
     *
     * The attendee-set store runs first and outside the try on purpose: the update path needs it to tell
     * "created locally with no attendees" from "synced from remote", so it must happen even when e-mails
     * are suppressed. It is also the one statement here that can throw, exactly as it can today.
     */
    private fun sendCreateInvites(
        account: Account,
        androidEventId: Long,
        createdProtonEventId: String?,
        attendees: List<AttendeeInfo>,
        suppressAttendeeEmails: Boolean,
        overridePlan: GuestStatusPlan?,
        event: CreateInviteEvent
    ): Boolean {
        var hasFailedInvites = false
        // Always store attendee set after CREATE so UPDATE path can distinguish
        // "locally created with no attendees" from "synced from remote"
        if (createdProtonEventId != null) {
            val prefs0 = PreferenceManager.getDefaultSharedPreferences(context)
            val allEmails0 = attendees.map { it.email.lowercase().trim() }.toSet()
            prefs0.edit().putStringSet(
                SyncPreferences.eventAttendeesKey(createdProtonEventId),
                allEmails0 + setOf(event.effectiveOrganizer.lowercase())
            ).apply()
        }
        if (attendees.isNotEmpty() && createdProtonEventId != null && !suppressAttendeeEmails) {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)

                // Check if invite sending is enabled for this account
                if (!prefs.getBoolean(SyncPreferences.sendCalendarInvitesKey(account.name), false)) {
                    // Still track attendees so we don't send duplicates if enabled later
                    val allEmails = attendees.map { it.email.lowercase().trim() }.toSet()
                    prefs.edit().putStringSet(SyncPreferences.eventAttendeesKey(createdProtonEventId), allEmails).apply()
                } else {

                val inviteSender = CalendarInviteSender(
                    apiClient, account.name, keyManager,
                    includeTitleInSubject = prefs.getBoolean(
                        SyncPreferences.inviteSubjectIncludeTitleKey(account.name), false
                    )
                )

                // Check for previously failed invites (retry)
                val failedKey = "invite_failed_$createdProtonEventId"
                val prevFailed = prefs.getStringSet(failedKey, emptySet()) ?: emptySet()
                val sentKey = "invite_sent_$createdProtonEventId"
                val prevSent = prefs.getStringSet(sentKey, emptySet()) ?: emptySet()

                // Determine who needs an invite: not yet sent + previously failed
                val allAttendeeEmails = attendees.map { it.email.lowercase().trim() }.toSet() - setOf(event.effectiveOrganizer.lowercase())
                val needsInvite = allAttendeeEmails - prevSent + prevFailed

                if (needsInvite.isNotEmpty()) {
                    val inviteAttendees = inviteAttendeesFor(attendees, event.uid2445, overridePlan)
                    val userAddrs = keyManager.getAddressEmails(account.name)
                    val createNowMs = System.currentTimeMillis()
                    val eventInfo = CalendarInviteSender.EventInfo(
                        uid = event.uid2445, summary = event.title, description = event.description,
                        location = event.location, dtStart = event.dtStartMillis, dtEnd = event.effectiveDtEndMillis,
                        timezone = event.timezone, allDay = event.allDay == 1, organizer = event.effectiveOrganizer,
                        attendees = inviteAttendees, sharedEventId = event.sharedEventId,
                        sessionKey = event.responseSessionKey,
                        // For single-occurrence overrides (createSequence inherited from
                        // the parent), the iTIP invitation SEQUENCE must match what we
                        // wrote into the Proton iCal. Per RFC 5546 §3.2.2.2 receivers
                        // discard a REQUEST whose SEQUENCE is older than the one already
                        // stored for the same (UID, RECURRENCE-ID); shipping 0 here
                        // makes Google ignore the override and keep the original time.
                        sequence = event.createSequence,
                        userAddresses = userAddrs,
                        created = createNowMs, lastModified = createNowMs,
                        availability = event.availability,
                        rruleLine = event.rruleStr.ifEmpty { null },
                        exdateLines = event.exdateStr.ifEmpty { null },
                        recurrenceIdLine = event.recurrenceIdStr.ifEmpty { null },
                        organizerName = account.name,
                        zoneRules = event.inviteZoneRules,
                        dtStartText = event.dtStartText,
                        dtEndText = event.dtEndText
                    )
                    val result = // Safe: runs on Dispatchers.IO via SyncUtils.runUninterruptible, not UI thread
                        runBlocking {
                        // Single-occurrence overrides (originalId != null → RECURRENCE-ID present)
                        // are technically a CREATE on the Proton side but a modification of an
                        // already-invited series from the recipient's view. Use "Updated invitation"
                        // so Outlook/Gmail/Proton merge it into the existing series instead of
                        // showing a duplicate "Invitation".
                        val isExceptionUpdate = event.originalId != null
                        inviteSender.sendInvitations(eventInfo, onlyTo = needsInvite, isUpdate = isExceptionUpdate)
                    }
                    // Track results
                    // The guests of a single-occurrence edit have the series already: a failed email
                    // here is retried as an update later, never as a fresh invitation.
                    val newSent = prevSent + result.sent + (if (event.originalId != null) allAttendeeEmails else emptySet())
                    val newFailed = result.failed  // only keep current failures
                    prefs.edit()
                        .putStringSet(sentKey, newSent)
                        .putStringSet(failedKey, newFailed)
                        // A moved occurrence whose email failed must still ask again when it is retried.
                        .putStringSet("invite_reask_$createdProtonEventId", (overridePlan?.askedAgain ?: emptySet()) intersect newFailed)
                        .putStringSet(SyncPreferences.eventAttendeesKey(createdProtonEventId), allAttendeeEmails + setOf(event.effectiveOrganizer.lowercase()))
                        .apply()
                    hasFailedInvites = newFailed.isNotEmpty()
                } else {
                    // All invites already sent
                    prefs.edit()
                        .putStringSet(SyncPreferences.eventAttendeesKey(createdProtonEventId), allAttendeeEmails + setOf(event.effectiveOrganizer.lowercase()))
                        .apply()
                }

                } // end else (invites enabled)
            } catch (e: Exception) {
                SyncLog.w(TAG, "Failed to send invitations for event $androidEventId: ${e.message}")
                hasFailedInvites = true
            }
        }

        return hasFailedInvites
    }

    /** The mapping row a successful create leaves behind, cache fields and all. */
    private fun buildCreatedMapping(
        serverLastEditTime: Long?,
        provider: ContentProviderClient,
        account: Account,
        androidEventId: Long,
        protonCalendarId: String,
        newEventId: String,
        createSequence: Int,
        finalSharedKeyPacketB64: String,
        timeBlock: String,
        rrule: String?,
        uid2445: String,
        recurrenceIdStr: String
    ): CalendarEventSyncEntity {
        // Populate the cache up-front so the next upload of this event skips
        // GET /calendar/events/{id}. SharedKeyPacket is what we just sent;
        // structural fields and UID are what we just signed; we are organizer.
        val (createDtStartLine, createDtEndLine, createRrule) = ICalUtils.structuralLines(timeBlock, rrule)
        val baseline = rowBaseline(provider, androidEventId)
            return CalendarEventSyncEntity(
                accountName = account.name,
                protonCalendarId = protonCalendarId,
                protonEventId = newEventId,
                androidEventId = androidEventId,
                // The server's clock, falling back to this phone's only when the response did not say.
                lastModified = serverLastEditTime ?: (System.currentTimeMillis() / 1000),
                // Persist the SEQUENCE we actually uploaded so the next update
                // can apply the smart-increment rule from a correct baseline.
                // Without this, exception events created against an edited parent
                // would re-compare against 0 on their first edit and break the
                // ≥-parent invariant the second time around.
                sequence = createSequence,
                // Cache the PKESK we actually sent (finalSharedKeyPacketB64), not
                // the calendar-key-only one we computed first. With Proton attendees
                // we rewrap to a fresh session key in encryptWithCalendarKeyAndRecipients;
                // caching the original PKESK leaks SK1 into the update path while the
                // server stores SK2 → updated SEIPDs can't be decrypted.
                cachedSharedKeyPacket = finalSharedKeyPacketB64,
                cachedDtStartLine = createDtStartLine,
                cachedDtEndLine = createDtEndLine,
                cachedRrule = createRrule,
                cachedUid = uid2445,
                cachedIsOrganizer = 1,
                // Mark locally-created overrides consistently with the download path.
                // recurrenceIdStr (built above) is "" for non-overrides -> store null.
                cachedRecurrenceId = recurrenceIdStr.takeIf { it.isNotEmpty() },
                // Baseline for "did a shared field change" (collisions, and whether guests
                // are emailed): without it the first edit after a create always looked
                // like one, and emailed every guest for a reminder change.
                sharedFingerprint = baseline.first,
                // The times the lines above are the text for (UploadTimes).
                cachedTimes = baseline.second
            )
    }

    /**
     * Clear DIRTY only if the row still looks like the version that was just uploaded.
     *
     * Sending the invitation emails takes seconds: a draft per recipient, an attachment upload, a send,
     * and a settle between them. An edit made by the user in that window sets DIRTY again, and clearing
     * it unconditionally threw that edit away with no log, no retry and no sign to the user. The
     * fingerprint is the same one the collision guard uses, taken before the mail went out.
     *
     * A null fingerprint on either side means the row could not be read, and then the old behaviour is
     * the safe one: clear, because leaving a row dirty for ever re-uploads it on every sync.
     */
    private fun clearDirtyUnlessEditedSince(
        provider: ContentProviderClient,
        account: Account,
        androidEventId: Long,
        fingerprintBefore: String?
    ) {
        val now = EventConflictGuard.fingerprintLocalRow(provider, androidEventId)
        if (!mayClearDirtyAfterInvites(fingerprintBefore, now)) {
            SyncLog.i(
                TAG,
                "Event $androidEventId was edited while its invitations were going out; keeping it dirty so the next sync sends the newer version"
            )
            return
        }
        SyncUtils.clearCalendarEventDirtyFlag(provider, account, androidEventId, TAG)
    }

    /** The three-strike invite retry bookkeeping after a create whose invitations did not all get out. */
    private fun recordInviteRetryStrike(
        androidEventId: Long,
        protonEventId: String?,
        hasFailedInvites: Boolean
    ) {
        if (hasFailedInvites && protonEventId != null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val retryKey = "invite_retry_count_$protonEventId"
            val retryCount = prefs.getInt(retryKey, 0) + 1
            if (retryCount >= 3) {
                SyncLog.w(TAG, "Invite retry limit reached for event $androidEventId, giving up")
                prefs.edit().remove(retryKey).apply()
            } else {
                SyncLog.i(TAG, "Invite send failed ($retryCount/3) for event $androidEventId")
                prefs.edit().putInt(retryKey, retryCount).apply()
            }
        }
    }

    private fun writeUidBack(
        provider: ContentProviderClient,
        account: Account,
        androidEventId: Long,
        uid2445: String
    ) {
        // Write UID back to CalendarContract so future updates use the same UID
        try {
            val uidValues = ContentValues().apply { put(CalendarContract.Events.UID_2445, uid2445) }
            provider.update(
                ContentUris.withAppendedId(
                    SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                    androidEventId
                ),
                uidValues, null, null
            )
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not write UID back to CalendarContract for event $androidEventId", e)
        }
    }

    private fun writeSyncIdBack(
        provider: ContentProviderClient,
        account: Account,
        androidEventId: Long,
        newEventId: String
    ) {
        // Write _SYNC_ID back to CalendarContract so other apps (Business Calendar)
        // can identify this as a synced event with a server-side counterpart
        try {
            val syncIdValues = ContentValues().apply {
                put(CalendarContract.Events._SYNC_ID, newEventId)
            }
            provider.update(
                ContentUris.withAppendedId(
                    SyncUtils.asCallerSyncAdapter(CalendarContract.Events.CONTENT_URI, account),
                    androidEventId
                ),
                syncIdValues, null, null
            )
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not write _SYNC_ID for event $androidEventId: ${e.message}")
        }
    }

    /**
     * Parse a downloaded event's iCal; an external calendar's unparseable event falls back to the API
     * metadata. The ParserException is never logged or rethrown as it is: its message quotes event
     * content, and the caller's catch prints whole stack traces when debug logging is on. See ICalShape.kt.
     */
    private fun parseEventICal(
        event: CalendarEventDetail,
        iCalString: String,
        isExternalCalendar: Boolean
    ): ParsedICal {
        try {
            return ParsedICal(CalendarBuilder().build(StringReader(iCalString)), iCalString)
        } catch (e: net.fortuna.ical4j.data.ParserException) {
            val failure = iCalParseFailure(iCalString, e)
            SyncLog.d(TAG, "Parse failed for event ${event.id}: $failure")
            if (!isExternalCalendar) throw UnparseableEventException(failure)
        }
        val fallback = buildExternalEventICal(event)
        try {
            return ParsedICal(CalendarBuilder().build(StringReader(fallback)), fallback)
        } catch (e: net.fortuna.ical4j.data.ParserException) {
            throw UnparseableEventException("API metadata fallback: ${iCalParseFailure(fallback, e)}")
        }
    }

    /** What [parseEventICal] built, and the exact text it built it from, which the times are read from. */
    private class ParsedICal(val calendar: net.fortuna.ical4j.model.Calendar, val text: String)

    /**
     * Build an iCal string for an external/subscribed calendar event from available data.
     * External events may not have encrypted CalendarEvents with SUMMARY, so we extract
     * whatever we can from cleartext SharedEvents + API metadata.
     */
    private fun buildExternalEventICal(event: CalendarEventDetail): String {
        val cr = "\r\n"
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR${cr}VERSION:2.0${cr}BEGIN:VEVENT${cr}")

        // UID from API
        sb.append("UID:${event.uid}${cr}")

        // DTSTAMP
        sb.append("DTSTAMP:${ICalUtils.formatTimestamp(System.currentTimeMillis())}${cr}")

        // Collect all cleartext properties from event parts
        val cleartextProperties = mutableListOf<String>()
        var foundSummary = false

        // Extract cleartext data from all event part types
        val allParts = mutableListOf<EventPart>()
        allParts.addAll(event.sharedEvents)
        allParts.addAll(event.calendarEvents)
        event.personalEvents?.let { allParts.addAll(it) }

        for (part in allParts) {
            if (part.type == 0) {
                // Type 0 = cleartext — parse iCal properties directly
                val data = part.data.trim()
                if (data.isNotEmpty()) {
                    // Extract individual properties from the cleartext iCal fragment
                    val lines = data.lines()
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        if (trimmed.startsWith("BEGIN:") || trimmed.startsWith("END:") ||
                            trimmed.startsWith("VERSION:")) continue
                        cleartextProperties.add(trimmed)
                        if (trimmed.startsWith("SUMMARY")) foundSummary = true
                    }
                }
            }
        }

        // Add all extracted cleartext properties
        for (prop in cleartextProperties) {
            sb.append("${prop}${cr}")
        }

        // If no DTSTART was found in cleartext, use API metadata
        if (cleartextProperties.none { it.startsWith("DTSTART") }) {
            if (event.fullDay == 1) {
                sb.append("DTSTART;VALUE=DATE:${ICalUtils.formatDate(event.startTime * 1000)}${cr}")
                sb.append("DTEND;VALUE=DATE:${ICalUtils.formatDate(event.endTime * 1000)}${cr}")
            } else {
                sb.append("DTSTART:${ICalUtils.formatTimestamp(event.startTime * 1000)}${cr}")
                sb.append("DTEND:${ICalUtils.formatTimestamp(event.endTime * 1000)}${cr}")
            }
        }

        // If no SUMMARY found, use a placeholder
        if (!foundSummary) {
            sb.append("SUMMARY:(External event)${cr}")
        }

        sb.append("END:VEVENT${cr}END:VCALENDAR")
        return sb.toString()
    }

}

/**
 * Member.Permissions bitmask, from Proton's own CALENDAR_PERMISSIONS enum
 * (WebClients/packages/shared/lib/calendar/constants.ts):
 *
 *   SUPER_OWNER 1, OWNER 2, ADMIN 4, READ_MEMBER_LIST 8, WRITE 16, READ 32, AVAILABILITY 64
 *
 * WRITE is 16. Bit 4 is ADMIN. This code once tested bit 4 for WRITE, and that was simply wrong; a
 * device run against a real view-only share is what exposed it, because the three calendars on
 * that account read 127 (owner), 112 = WRITE|READ|AVAILABILITY (share the user CAN edit) and
 * 96 = READ|AVAILABILITY (view-only share). Testing bit 4 asks "is this member an admin?", so
 * it wrongly locked the editable share to read-only, and let owned calendars through only
 * because 127 happens to carry bit 4 too.
 */
    internal const val MEMBER_PERMISSION_WRITE = 16

/**
 * Whether a member with these permissions may write to the calendar.
 *
 * Null means the member list was empty. Zero means the same thing in practice: Permissions is a
 * non-null Int, so an absent field deserialises to 0, and a calendar that is listed to you always
 * carries at least AVAILABILITY. Neither is evidence of read-only, and guessing read-only would
 * lock somebody out of their own calendar, which is worse than the retry loop this set out to
 * stop. Only a permission set that is genuinely present and lacks WRITE downgrades.
 */
internal fun writableByPermissions(memberPermissions: Int?): Boolean =
    memberPermissions == null || memberPermissions == 0 ||
        (memberPermissions and MEMBER_PERMISSION_WRITE) != 0
