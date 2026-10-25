package me.proton.sync.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.proton.sync.SyncLog
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.protonErrorSuffix
import me.proton.sync.api.models.CalendarEventDetail
import me.proton.sync.api.models.CalendarInfo
import me.proton.sync.api.models.CalendarKey
import me.proton.sync.api.models.CalendarPassphrase
import me.proton.sync.crypto.CalendarDecryptor
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.component.VEvent

/**
 * Read-only exporter: fetches a Proton account's **owned** calendars and their events
 * straight from the server, decrypts them, and serializes each calendar to an
 * import-valid `.ics`. Touches neither CalendarContract nor any Room mapping — by
 * design, so a glitched/empty phone copy can never poison the backup.
 *
 * Reuses the proven decrypt path ([CalendarDecryptor.decryptEvent]); the only ical4j
 * addition over the sync flow is serialization via [CalendarOutputter]. Validation is
 * disabled on output because Proton VEVENTs are parsed under the relaxed flags set in
 * `ProtonSyncApp` — strict validation would reject permissively-parsed properties.
 *
 * Subscribed (Type 1) and holiday (Type 2) calendars are skipped: they are external,
 * read-only, re-addable data — not the user's own content a backup exists to protect.
 */
class CalendarBackupBuilder(
    private val apiClient: ProtonApiClient,
    private val calendarDecryptor: CalendarDecryptor,
) {
    /** One serialized calendar. [eventCount] feeds the emptiness guard upstream. */
    data class CalendarIcs(val displayName: String, val fileBaseName: String, val ics: String, val eventCount: Int)

    /** Full calendar backup for one account, with failure counts for the incomplete guard. */
    data class CalendarBackupResult(
        val calendars: List<CalendarIcs>,
        val skippedCalendars: Int,  // couldn't fetch keys → whole calendar unreadable
        val failedEvents: Int,      // events that failed to fetch or decrypt
        val fetchFailed: Boolean,   // getCalendars itself failed
    )

    suspend fun buildAll(accountName: String): CalendarBackupResult {
        val calResp = try {
            apiClient.getCalendars(accountName)
        } catch (e: Exception) {
            SyncLog.e(TAG, "getCalendars failed for $accountName: ${e.message}")
            return CalendarBackupResult(emptyList(), 0, 0, true)
        }
        if (!calResp.isSuccessful) {
            SyncLog.e(TAG, "getCalendars HTTP ${calResp.code()}${calResp.protonErrorSuffix()} for $accountName")
            return CalendarBackupResult(emptyList(), 0, 0, true)
        }
        val calendars = calResp.body()?.calendars ?: emptyList()
        val out = mutableListOf<CalendarIcs>()
        var skipped = 0
        var fetchFailed = false
        var failedEvents = 0
        for (cal in calendars) {
            if (cal.type != CALENDAR_TYPE_PERSONAL) continue // skip subscribed/holiday (read-only external)
            val keys = fetchKeys(accountName, cal.id)
            val passphrases = fetchPassphrases(accountName, cal.id)
            if (keys.isEmpty()) {
                SyncLog.w(TAG, "Backup INCOMPLETE: no keys for calendar '${cal.name ?: cal.id}', skipping")
                skipped++
                continue
            }
            val (events, fetchFailures, pagingFailed) = fetchAllEvents(accountName, cal.id)
            failedEvents += fetchFailures
            if (pagingFailed) fetchFailed = true
            val vevents = ArrayList<VEvent>(events.size)
            // The VTIMEZONE definitions travel with their events. Dropped, the file names zones it
            // never defines, which RFC 5545 forbids and importers reject, and ical4j quietly
            // rewrites an unresolved Europe/Amsterdam as Europe/Brussels on the way out.
            val zones = LinkedHashMap<String, net.fortuna.ical4j.model.component.VTimeZone>()
            for (ev in events) {
                try {
                    val ical = calendarDecryptor.decryptEvent(accountName, ev, keys, passphrases)
                    val parsed = IcsSerializer.parseFaithfully(ical)
                    IcsSerializer.zonesOf(parsed).forEach { (id, tz) -> zones.putIfAbsent(id, tz) }
                    parsed.getComponents<VEvent>("VEVENT").forEach {
                        IcsSerializer.addReminders(it, ev.notifications)
                        vevents.add(it)
                    }
                } catch (e: Exception) {
                    failedEvents++
                    SyncLog.d(TAG, "Skipping undecryptable event ${ev.id} in backup: ${e.javaClass.simpleName}")
                }
            }
            val name = resolveName(accountName, cal)
            out.add(CalendarIcs(name, safeFileName(name), IcsSerializer.serialize(PRODID, name, vevents, zones), vevents.size))
            SyncLog.i(TAG, "Backup: calendar '$name' → ${vevents.size} event(s)")
        }
        if (skipped > 0 || failedEvents > 0) {
            SyncLog.w(TAG, "Backup INCOMPLETE for $accountName: $skipped calendar(s) skipped, $failedEvents event(s) failed")
        }
        return CalendarBackupResult(out, skipped, failedEvents, fetchFailed)
    }

    /**
     * Resolve the calendar's friendly display name (mirrors CalendarSyncAdapter): the API
     * `Name`, else the bootstrap endpoint's `CalendarSettings.Name` / `Members[].Name`, else
     * a generic fallback. Without this the backup file would be named after the raw calendar id.
     */
    private suspend fun resolveName(accountName: String, cal: CalendarInfo): String {
        var name = cal.name ?: cal.description
        if (name.isNullOrBlank()) {
            try {
                val resp = apiClient.getCalendarBootstrap(accountName, cal.id)
                if (resp.isSuccessful) {
                    val body = resp.body()
                    @Suppress("UNCHECKED_CAST")
                    val settings = body?.get("CalendarSettings") as? Map<String, Any>
                    name = settings?.get("Name") as? String
                    if (name.isNullOrBlank()) {
                        @Suppress("UNCHECKED_CAST")
                        val members = body?.get("Members") as? List<Map<String, Any>>
                        name = members?.firstNotNullOfOrNull { it["Name"] as? String }
                    }
                }
            } catch (e: Exception) {
                SyncLog.w(TAG, "Bootstrap name fetch failed for ${cal.id}: ${e.message}")
            }
        }
        return name?.takeIf { it.isNotBlank() } ?: "My Calendar"
    }

    private suspend fun fetchKeys(account: String, calId: String): List<CalendarKey> {
        return try {
            val r = apiClient.getCalendarKeys(account, calId)
            if (r.isSuccessful) r.body()?.keys ?: emptyList() else emptyList()
        } catch (e: Exception) {
            SyncLog.w(TAG, "Calendar keys fetch failed for $calId: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchPassphrases(account: String, calId: String): List<CalendarPassphrase> {
        return try {
            val r = apiClient.getCalendarPassphrase(account, calId)
            if (r.isSuccessful) r.body()?.getAllPassphrases() ?: emptyList() else emptyList()
        } catch (e: Exception) {
            SyncLog.w(TAG, "Calendar passphrase fetch failed for $calId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Page all event IDs, then parallel-fetch each event's full (encrypted) detail.
     *
     * @return the events, the number of per-event GETs that failed, and whether the ID PAGING
     *   itself failed. That third value used to not exist, and its absence was the most dangerous
     *   thing in this file: a calendar pages at 100, and a dropped connection, a 5xx or a 429 on
     *   any page after the first simply `break`s the loop. The ids gathered so far were then
     *   treated as the whole calendar, every one of them fetched successfully, and the run was
     *   written and reported "Backup complete" while silently missing everything after the failed
     *   page. A truncated backup that announces itself as whole is worse than a failed one.
     */
    private suspend fun fetchAllEvents(account: String, calId: String): Triple<List<CalendarEventDetail>, Int, Boolean> {
        val ids = mutableListOf<String>()
        var afterId: String? = null
        var pagingFailed = false
        while (true) {
            val resp = try {
                apiClient.getCalendarEventIds(account, calId, limit = 100, afterId = afterId)
            } catch (e: Exception) {
                SyncLog.w(TAG, "getCalendarEventIds failed for $calId: ${e.message}")
                pagingFailed = true
                break
            }
            if (!resp.isSuccessful) {
                SyncLog.w(TAG, "getCalendarEventIds returned HTTP ${resp.code()}${resp.protonErrorSuffix()} for $calId; id list is incomplete")
                pagingFailed = true
                break
            }
            @Suppress("UNCHECKED_CAST")
            val page = resp.body()?.get("IDs") as? List<String> ?: emptyList()
            if (page.isEmpty()) break
            ids.addAll(page)
            if (page.size < 100) break
            afterId = page.last()
        }
        if (ids.isEmpty()) return Triple(emptyList(), 0, pagingFailed)

        val sem = Semaphore(EVENT_FETCH_CONCURRENCY)
        val out = ArrayList<CalendarEventDetail>(ids.size)
        var failed = 0
        // Chunk so we don't allocate one coroutine per event up-front on very large calendars.
        for (batch in ids.chunked(EVENT_FETCH_BATCH)) {
            val fetched = coroutineScope {
                batch.map { id ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            try {
                                apiClient.getCalendarEvent(account, calId, id).body()?.event
                            } catch (e: Exception) {
                                SyncLog.d(TAG, "getCalendarEvent $id failed: ${e.message}")
                                null
                            }
                        }
                    }
                }.awaitAll()
            }
            val nonNull = fetched.filterNotNull()
            failed += (batch.size - nonNull.size)
            out.addAll(nonNull)
        }
        return Triple(out, failed, pagingFailed)
    }

    private fun safeFileName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().ifBlank { "calendar" }

    companion object {
        private const val TAG = "CalendarBackup"
        private const val CALENDAR_TYPE_PERSONAL = 0
        private const val EVENT_FETCH_CONCURRENCY = 6
        private const val EVENT_FETCH_BATCH = 100

        /**
         * The backup's PRODID: this app and its maker, without the version, so unchanged events give the
         * same file whichever release wrote it. Proton Mail files a one-event .ics without METHOD under a
         * hash of its bytes, and a version here would make it offer an event already added from an older
         * backup as new.
         */
        internal const val PRODID = "-//BEO//Sync for Proton//EN"
    }
}
