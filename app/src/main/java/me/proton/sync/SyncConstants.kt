package me.proton.sync

/**
 * Shared constants for the Proton Sync adapter.
 *
 * Centralizes magic strings and numbers that were previously scattered across
 * CalendarSyncAdapter, ContactsSyncAdapter, SyncWorker, and SyncSettingsActivity.
 */
object SyncConstants {

    // ---- Sync trigger extras (passed in Bundle from requestSync) ----

    /** Extra: full Remote→Local re-sync requested by user (long-press in settings). */
    const val EXTRA_FORCE_REMOTE_TO_LOCAL = "forceRemoteToLocal"

    /** Extra: full Local→Remote re-sync requested by user (long-press in settings). */
    const val EXTRA_FORCE_LOCAL_TO_REMOTE = "forceLocalToRemote"

    /** Extra: sync triggered by WorkManager periodic watchdog (not user-initiated). */
    const val EXTRA_SCHEDULED_BY_WORKER = "scheduledByWorker"

    /**
     * Set ONLY by the explicit user-facing "Sync now" buttons in settings. Distinguishes a
     * genuine user tap from other MANUAL+EXPEDITED requests (re-auth completion, deletion
     * receivers, provisioning) so the calendar unchanged-skip bypass fires only for the tap
     * the user actually made.
     */
    const val EXTRA_USER_SYNC_NOW = "userSyncNow"

    /**
     * Set on the one sync [SyncScheduling] requests when the subscription turns active, so its run logs as a
     * catch-up instead of looking like an ordinary periodic sync. Changes nothing the adapters do.
     */
    const val EXTRA_CATCH_UP = "catchUp"

    /**
     * How many consecutive syncs of ONE calendar may re-check because the model-event counter
     * advanced mid-run (see the absorption-race fix in CalendarSyncAdapter).
     *
     * Each re-check costs a full sweep on large calendars, and Proton's background indexing can
     * keep bumping the counter for a while after a large import, so the recovery chain is capped:
     * beyond this many consecutive advances we accept the current counter value. A genuine later
     * change bumps the counter again and is detected normally.
     */
    const val CALENDAR_MODELEVENT_RECHECK_CHAIN_LIMIT = 3

    // ---- Proton iCal field length limits (the ones Proton's own clients apply before upload) ----

    /** Max length for SUMMARY (event title) before upload. */
    const val MAX_SUMMARY_LENGTH = 255

    /** Max length for DESCRIPTION (event details) before upload. */
    const val MAX_DESCRIPTION_LENGTH = 3000

    /** Max length for LOCATION before upload. */
    const val MAX_LOCATION_LENGTH = 255

    /** Max length for iCal UID property. */
    const val MAX_UID_LENGTH = 191

    // ---- Proton Contact field limits (names: the limits Proton's web client applies; photo: this app's choice) ----

    /** Max length for contact FN (formatted name). */
    const val MAX_CONTACT_FN_LENGTH = 190

    /** Max length for contact given/family name components. */
    const val MAX_CONTACT_NAME_PART_LENGTH = 94

    /**
     * Max dimension (width or height in pixels) for contact photos before upload.
     *
     * Empirically calibrated against Proton's API in v1.3.72: Proton's `/contacts/v4`
     * endpoint accepts encrypted vCard payloads up to several hundred KB and does not
     * impose a server-side dimension cap. The 180×180 figure visible in web-uploaded
     * photos is purely Proton's web-client convention, not an API restriction.
     *
     * 1024 picked as a sane balance: sharp on any modern phone display, real-photo
     * encrypted blobs land at ~150–300 KB which leaves headroom under Proton's
     * practical byte budget. Bumping this is safe; lowering it (back to 180) would
     * re-introduce the postage-stamp effect on high-DPI screens reported by testers.
     */
    const val MAX_CONTACT_PHOTO_SIZE = 1024

    // ---- Content provider authorities ----

    /** Authority for Android's Contacts provider (ContactsContract). */
    const val AUTHORITY_CONTACTS = "com.android.contacts"

    /** Authority for Android's Calendar provider (CalendarContract). */
    const val AUTHORITY_CALENDAR = "com.android.calendar"

    // ---- Time constants ----

    /** One day in milliseconds (86 400 000). Used for all-day event duration calculations. */
    const val ONE_DAY_MS = 86_400_000L

    /** One hour in milliseconds (3 600 000). Default event duration fallback. */
    const val ONE_HOUR_MS = 3_600_000L

    /** Seconds in a day (86 400). Used for converting day counts to Unix timestamp ranges. */
    const val SECONDS_PER_DAY = 86_400L

    /**
     * Outer bounds for the calendar events query window when the user picks the
     * "All events" sync mode (v1.4.12+). Proton's API requires non-null `Start`/
     * `End` on every events query — sending null returns HTTP 400 `Code 2000 "The
     * Start is required"`. The full 1970..2037 documented range is well over the
     * undocumented per-request span cap (93 days, measured 2026-04-30) but the
     * sparse-vs-dense hybrid handles both correctly: sparse calendars use
     * `/events/ids` + per-event GETs (no window) and dense calendars chunk the
     * span at 90-day intervals.
     */
    const val CALENDAR_QUERY_MIN_START = 0L
    const val CALENDAR_QUERY_MAX_END = 2_145_916_799L  // 2037-12-31 23:59:59 UTC

    /**
     * Floor on the iterative halving of a calendar query window. If even a 7-day
     * window comes back "too big" something is fundamentally wrong upstream — bail
     * with the error rather than fragment further.
     */
    const val CALENDAR_QUERY_MIN_SPAN_SECONDS = 7L * 86_400L  // 7 days

    /**
     * Initial chunk size for the iterative calendar events fetch. Set just under the
     * empirically discovered Proton cap (93 days accepted, 94 rejected, measured
     * 2026-04-30 with a one-shot binary-search probe in v1.4.8). 90 days gives a
     * 3-day safety margin against transient rejections without changing the chunk
     * count for any realistic window. The halve-on-fail safety net in
     * `CalendarSyncAdapter.fetchTypeRange` still kicks in if Proton ever tightens
     * the cap further; this constant just makes the cap discovery a no-op in the
     * common case (no probe round-trips needed on every sync).
     */
    const val CALENDAR_QUERY_INITIAL_CHUNK_DAYS = 90L

    /**
     * Hybrid fetch threshold: calendars with at most this many events take the
     * `/events/ids` + per-event GET path (1 + N calls); calendars with more take
     * the chunked `/events?Type=…` path (~16 chunks × 4 Type buckets = ~64 calls
     * for a 4-year window). The break-even point is roughly the chunked path's
     * call count — below it, individual GETs beat chunked even with their per-event
     * overhead because chunked-on-sparse-calendars wastes calls on empty buckets.
     */
    const val CALENDAR_HYBRID_FETCH_THRESHOLD = 70

    /**
     * Concurrency limit for parallel per-event GETs in the sparse-calendar path.
     * Matches the upload semaphore (`uploadDirtyEvents`) — empirically Proton
     * accepts 5 concurrent calls without rate-limiting.
     */
    const val CALENDAR_PARALLEL_GET_PERMITS = 5

    /**
     * Concurrency cap for the "All"-mode flat full-fetch (/events/ids + per-event GET on EVERY
     * server event, count-independent). Higher than [CALENDAR_PARALLEL_GET_PERMITS] because this
     * path can GET thousands of bodies and must beat the retired dense window-sweep (~6 min for
     * several thousand events). 10 ≈ ~5 min on the field-extrapolated ~3 events/s/permit. 429s are
     * handled (AuthInterceptor: Retry-After + global throttle), so this degrades to slower-but-safe.
     * MUST stay ≤ the OkHttp Dispatcher.maxRequestsPerHost set in NetworkModule, or the extra permits
     * just queue inside OkHttp and the speedup never appears. UNPROVEN above 5 — field-test before raising.
     */
    const val CALENDAR_FULLFETCH_GET_PERMITS = 10

    /**
     * The schedule of an account whose interval was never chosen (event_loop_interval_<account> unset),
     * as SyncUtils.applyPeriodicSync and the catch-up sync read it. Never armed directly.
     */
    const val DEFAULT_INITIAL_PERIODIC_SYNC_SECONDS = 3600L

    // ---- Intent extras ----

    /** Intent extra: launch LoginActivity in "add account" mode. */
    const val EXTRA_ADD_ACCOUNT = "add_account"

    /** Intent extra: launch LoginActivity in "re-authenticate" mode. */
    const val EXTRA_REAUTH = "reauth"

    /** AccountManager user data key: parent account name for address-book accounts. */
    const val EXTRA_PARENT_ACCOUNT_NAME = "parent_account_name"

    /** AccountManager user data key: parent account type for address-book accounts. */
    const val EXTRA_PARENT_ACCOUNT_TYPE = "parent_account_type"
}
