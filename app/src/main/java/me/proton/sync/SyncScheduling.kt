package me.proton.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.work.WorkManager
import me.proton.sync.billing.SubscriptionGate
import me.proton.sync.billing.SubscriptionState
import java.util.concurrent.TimeUnit

/**
 * The background schedules (the fallback watchdogs and Android's periodic syncs) as one thing that follows the
 * subscription: armed while it lets sync run, cancelled while it does not.
 *
 * The subscription observer in [ProtonSyncApp] drives it through [Tracker]. Paths that create or change one
 * account (provisioning, a rename, a restore) arm through the gated helpers here, so nothing schedules work
 * that the gate would only decline.
 *
 * Syncs the app asks for on its own (a calendar setting changed, a Fallback full sync, a re-time) go through
 * [requestBackgroundSync]: ordinary requests, never SYNC_EXTRAS_MANUAL or SYNC_EXTRAS_EXPEDITED, sent only while
 * [backgroundRequest] allows one. The catch-up at activation has its own rule ([catchUpRequest]). Syncs the user
 * starts take [userSyncExtras], or [forcedResyncExtras] for a full re-sync: manual, never expedited. A manual run that
 * Android retries after the retry rule stopped allowing it ends at its start ([endsStaleManualRun]). Every request the
 * app makes carries [SyncConstants.EXTRA_APP_REQUEST], and on "Manual only" an ordinary run without it, which Android
 * started by itself, ends at its start too ([endsUnaskedRun]).
 */
object SyncScheduling {

    /** The observer's tag, so the "arming" and "cancelling" lines keep reading as one story. */
    private const val TAG = ProtonSyncApp.TAG

    /** What the subscription observer does with one state. */
    enum class Action { NONE, ARM, ARM_AND_CATCH_UP, DISARM }

    /**
     * Decides, state by state, what the subscription observer does.
     *
     * "Activated" means active now while the gate was closed before: an inactive state was seen in this
     * process, or no active state was cached when the process started. Only an activation catches up,
     * because only then can the gate have dropped a sync that nothing replays (the setup chooser's first
     * sync, a restore's). A plain start of an active install re-arms and nothing more, or every app start
     * would sync.
     *
     * [activeAtStart] is the cached state's verdict, read before either backend could change it. Without it
     * a start after Unknown (gate closed, so the sign-in's syncs were dropped) and a plain start of an active
     * install both look like null to true.
     *
     * The starts it was checked against:
     *  - direct, cached Trial/Active: ARM; later refreshes with the same verdict: NONE, so no sync.
     *  - direct, cached NoSubscription/Expired: DISARM; then a pasted id, a restored id, a resume or a
     *    collected payment: ARM_AND_CATCH_UP.
     *  - direct, first start ever: Unknown gives NONE, the refresh without an id gives NoSubscription and
     *    DISARM, and the activation catches up.
     *  - play, cached Active (also with a pre-2.0.1 expiry): billing's Active(0) is a new value with the same
     *    verdict, so NONE.
     *  - play, cached NoSubscription/Expired: DISARM; onPurchasesUpdated gives ARM_AND_CATCH_UP, and the
     *    refreshState after it emits an equal value, so nothing.
     *  - play, Expired with suspended (Google Play's account hold or a pause): DISARM like any Expired; the
     *    refresh that finds the purchase unsuspended again gives ARM_AND_CATCH_UP.
     *  - play, first start or after a data clear: billing answering Active gives ARM_AND_CATCH_UP, which syncs
     *    nothing when there is no account yet.
     *  - a StateFlow conflates: with a cached inactive state, an already active first value still catches up,
     *    because [activeAtStart] is false.
     */
    class Tracker(private val activeAtStart: Boolean) {
        private var lastActive: Boolean? = null

        fun next(state: SubscriptionState): Action {
            // No answer yet from the backend: do not thrash the schedules before it reports.
            if (state is SubscriptionState.Unknown) return Action.NONE
            val active = state.isActive
            if (active == lastActive) return Action.NONE
            val before = lastActive
            lastActive = active
            return when {
                !active -> Action.DISARM
                before == false || !activeAtStart -> Action.ARM_AND_CATCH_UP
                else -> Action.ARM
            }
        }
    }

    /**
     * Arm every account's schedules from its stored settings. The periodic syncs are told the subscription
     * allows sync rather than reading the stored state: the observer acts on the state it was just given.
     */
    fun armAll(context: Context) {
        SyncWorker.scheduleAll(context)
        restorePeriodicSyncs(context)
    }

    /** Cancel every account's schedules. The type switches are left alone, so a renewal finds them as they were. */
    fun disarmAll(context: Context) {
        SyncWorker.cancelAll(context)
        clearPeriodicSyncs(context)
    }

    /**
     * Arm one account's fallback watchdogs from its stored settings, or nothing while the subscription is
     * inactive. Returns whether it armed.
     *
     * Arming nothing is enough there: the observer's DISARM has already cancelled everything, and arms it
     * all again from the stored settings when the subscription turns active.
     */
    fun scheduleWorkers(context: Context, accountName: String): Boolean {
        if (!SubscriptionGate.isSyncAllowedCached(context)) {
            SyncLog.i(TAG, "Background schedules for '$accountName' not armed: the subscription is inactive; they are armed when it turns active")
            return false
        }
        SyncWorker.scheduleFromSettings(context, accountName)
        return true
    }

    /** After restored settings are written: every account's schedules, from them, while the gate is open. */
    fun reconcileAll(context: Context) {
        if (SubscriptionGate.isSyncAllowedCached(context)) {
            armAll(context)
        } else {
            SyncLog.i(TAG, "Schedules not re-armed after the restore: the subscription is inactive")
        }
    }

    // ---- Syncs the app asks for on its own ----

    /** What [requestBackgroundSync] did with one request: sent it, or the first reason it did not. */
    internal enum class BackgroundRequest {
        SEND, SUBSCRIPTION_INACTIVE, SWITCHED_OFF, MANUAL_ONLY, AUTO_SYNC_OFF, PAUSED, RESYNC_PREPARING;

        /** Why nothing was sent, for a log line; [type] is "calendar" or "contacts". */
        fun reason(type: String): String = when (this) {
            SEND -> "nothing stopped it"
            SUBSCRIPTION_INACTIVE -> "the subscription is inactive"
            SWITCHED_OFF -> "$type sync is switched off"
            MANUAL_ONLY -> "the sync interval is Manual only"
            AUTO_SYNC_OFF -> "Android's automatic sync is off"
            PAUSED -> "paused after a sign-in problem"
            RESYNC_PREPARING -> "a full re-sync of its $type is being prepared"
        }
    }

    /**
     * Whether a sync the app asks for on its own may be requested now, or the first reason it may not, in this
     * order: the subscription ([syncAllowed]), the type's switch ([switchedOn]), the account's "Manual only"
     * ([manualOnly]; the Fallback full sync passes false, it has its own interval), Android's automatic sync
     * ([masterSyncOn]; Android drops an ordinary request while it is off anyway, so this keeps the log true), the
     * pause after a sign-in problem ([paused]), which only SYNC_EXTRAS_MANUAL would get through, and a full re-sync of
     * this type of the account being prepared ([resyncPreparing], [FullResyncHold]): a sync started inside the
     * preparation would upload the rows it marks as ordinary changes, with invitation emails, and the re-sync's own
     * request follows anyway.
     */
    internal fun backgroundRequest(
        syncAllowed: Boolean,
        switchedOn: Boolean,
        manualOnly: Boolean,
        masterSyncOn: Boolean,
        paused: Boolean,
        resyncPreparing: Boolean = false,
    ): BackgroundRequest = when {
        !syncAllowed -> BackgroundRequest.SUBSCRIPTION_INACTIVE
        !switchedOn -> BackgroundRequest.SWITCHED_OFF
        manualOnly -> BackgroundRequest.MANUAL_ONLY
        !masterSyncOn -> BackgroundRequest.AUTO_SYNC_OFF
        paused -> BackgroundRequest.PAUSED
        resyncPreparing -> BackgroundRequest.RESYNC_PREPARING
        else -> BackgroundRequest.SEND
    }

    /** The extras that let a request past Android's switch, its backoff or a running sync: never on an ordinary one. */
    private val NOT_ORDINARY = listOf(
        ContentResolver.SYNC_EXTRAS_MANUAL,
        ContentResolver.SYNC_EXTRAS_EXPEDITED,
        ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS,
        ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF,
    )

    /**
     * Ask Android for a sync the app wants on its own, as an ordinary request, when [backgroundRequest] allows one,
     * and return the decision for the caller's log line. [parentAccountName] is the account whose interval, pause and
     * full re-sync of [authority] apply ([account] is its address-book child for contacts); [manualOnlyApplies] is
     * false for the Fallback full sync, which has its own interval.
     *
     * [extras] never carry SYNC_EXTRAS_MANUAL: it gets past Android's automatic-sync switch, the pause after a sign-in
     * problem and Android's backoff, and Android retries it. Nor SYNC_EXTRAS_EXPEDITED: it pre-empts a running calendar
     * sync of any Proton account (allowParallelSyncs="false"), which the uninterruptible adapter answers with
     * SYNC_ALREADY_IN_PROGRESS, so the account backs off. Should a caller pass one anyway, it is taken out here, with
     * the IGNORE_ flags. A request made from the settings screen runs at once without them: Android exempts a request
     * from the app in the foreground from the standby delays. What goes out carries [SyncConstants.EXTRA_APP_REQUEST],
     * so [endsUnaskedRun] never takes it for a run Android started by itself (the Fallback full sync runs on "Manual
     * only" too).
     */
    internal fun requestBackgroundSync(
        context: Context,
        account: Account,
        authority: String,
        parentAccountName: String,
        extras: Bundle,
        manualOnlyApplies: Boolean = true,
    ): BackgroundRequest {
        val syncAllowed = SubscriptionGate.isSyncAllowedCached(context)
        val switchedOn = ContentResolver.getSyncAutomatically(account, authority)
        val manualOnly = manualOnlyApplies && isManualOnly(context, parentAccountName)
        val decision = backgroundRequest(
            syncAllowed = syncAllowed,
            switchedOn = switchedOn,
            manualOnly = manualOnly,
            masterSyncOn = ContentResolver.getMasterSyncAutomatically(),
            paused = SyncUtils.isSyncPaused(context, parentAccountName, isManual = false),
            resyncPreparing = FullResyncHold.isPreparing(parentAccountName, authority),
        )
        if (decision == BackgroundRequest.SEND) {
            for (key in NOT_ORDINARY) extras.remove(key)
            extras.putBoolean(SyncConstants.EXTRA_APP_REQUEST, true)
            ContentResolver.requestSync(account, authority, extras)
        }
        return decision
    }

    /**
     * Ask for the calendar sync that applies a changed calendar setting of [account]; [change] names it for the log.
     * The callers: the calendar range, "Include external calendars", "Remove older events from this phone", a settings
     * restore of an account already on this phone, a change made on 2.1.0 whose sync had not run before the update
     * ([removeEventLoopLeftovers]), and the Default calendar row (DefaultCalendarService.save) when Proton confirmed a
     * new default calendar that has no row on this phone yet, so the sync brings it down and marks it.
     *
     * Through [requestBackgroundSync]: nothing is asked while Android's automatic sync is off, on "Manual only", while
     * paused or with calendar sync switched off, and the next sync applies the setting then. Each request carries its
     * own [SyncConstants.EXTRA_SETTINGS_CHANGE_ID], because Android drops a request whose extras equal a running sync's,
     * and that sync read the older values. Not a user's tap (no EXTRA_USER_SYNC_NOW): the calendar's unchanged-skip
     * applies, and a changed range or switched-on removal is detected without it.
     *
     * While a full re-sync of the account's calendar is being prepared the change is held ([FullResyncHold]) and asked
     * for here again once the preparation ends, after the re-sync's own request: a sync started inside the preparation
     * would upload the rows it marks, with invitation emails. A contacts re-sync's preparation holds nothing here.
     */
    internal fun requestSettingsSync(context: Context, account: Account, change: String) {
        try {
            if (FullResyncHold.holdIfPreparing(account.name, change)) {
                SyncLog.i(
                    TAG,
                    "Calendar setting changed for '${account.name}' ($change) while a full re-sync of its calendar is " +
                        "prepared: its sync is asked for once the re-sync's own request is posted"
                )
                return
            }
            val extras = Bundle().apply {
                putBoolean(SyncConstants.EXTRA_SETTINGS_CHANGED, true)
                putLong(SyncConstants.EXTRA_SETTINGS_CHANGE_ID, SystemClock.elapsedRealtimeNanos())
            }
            val decision = requestBackgroundSync(context, account, SyncConstants.AUTHORITY_CALENDAR, account.name, extras)
            if (decision == BackgroundRequest.SEND) {
                SyncLog.i(TAG, "Calendar setting changed for '${account.name}' ($change): calendar sync requested")
            } else {
                SyncLog.i(
                    TAG,
                    "Calendar setting changed for '${account.name}' ($change): no sync requested, " +
                        "${decision.reason("calendar")}; the next sync applies it"
                )
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not request the calendar sync for '${account.name}' ($change): ${e.message}")
        }
    }

    /** Whether [parentAccountName]'s sync interval is "Manual only" (-1); unset reads as the default interval. */
    private fun isManualOnly(context: Context, parentAccountName: String): Boolean =
        SyncPreferences.getLongFromStringPref(
            PreferenceManager.getDefaultSharedPreferences(context),
            SyncPreferences.eventLoopIntervalKey(parentAccountName),
            SyncConstants.DEFAULT_INITIAL_PERIODIC_SYNC_SECONDS,
        ) < 0

    // ---- Syncs the user starts ----

    /** Whether Android may retry a failed sync the user started: only while its automatic sync is on and not on Manual only. */
    internal fun userSyncRetries(masterSyncOn: Boolean, manualOnly: Boolean): Boolean = masterSyncOn && !manualOnly

    /**
     * [userSyncRetries] for [parentAccountName] as things stand: Android's automatic sync switch now, and the account's
     * stored interval. For the failure texts (SyncNotificationHelper.friendlyMessage; owner, 2026-11-22): while it says
     * no, Android retries no failed sync the user started (it carries SYNC_EXTRAS_DO_NOT_RETRY), no periodic sync runs,
     * and on Manual only the retry of a failed upload ends at its start ([endsUnaskedRun]), so they ask for a tap on Sync
     * instead of promising a retry. The choice follows the account's state, not the run that failed, which the failure
     * record does not name, so two kinds of run that Android does retry in that state show the tap text too: a full
     * re-sync ([forcedResyncExtras]), and on Manual only with automatic sync on a sync the app asked for on its own
     * ([requestBackgroundSync]: the Fallback full sync's, or a settings or re-time request sent before Manual only was
     * chosen).
     */
    internal fun userSyncRetriesFor(context: Context, parentAccountName: String): Boolean =
        userSyncRetries(
            masterSyncOn = ContentResolver.getMasterSyncAutomatically(),
            manualOnly = isManualOnly(context, parentAccountName),
        )

    /**
     * The extras of a sync the user starts for [parentAccountName]: a sync button, the first sync after signing in,
     * signing in again or restoring, an answer in one of the app's notifications. SYNC_EXTRAS_MANUAL,
     * EXTRA_USER_SYNC_NOW with [userSyncNow] (it bypasses the calendar's unchanged-skip), SYNC_EXTRAS_DO_NOT_RETRY while
     * [userSyncRetries] says no, and with [answering] the running sync's [SyncConstants.EXTRA_REQUEST_ID].
     *
     * Android retries a failed manual sync in the background with SYNC_EXTRAS_MANUAL and its IGNORE_SETTINGS kept, so
     * the retry ran while automatic sync was off, hourly at worst and for ever for a calendar that fails on every run.
     * DO_NOT_RETRY stops that, except for SYNC_ALREADY_IN_PROGRESS, which Android always retries. A retry of a request
     * made while retries were still allowed ends at its start once they are not ([endsStaleManualRun]).
     *
     * Not SYNC_EXTRAS_EXPEDITED: with allowParallelSyncs="false" it pre-empted a running calendar sync of any Proton
     * account, which the uninterruptible adapter never stops, so the tap's own start met SYNC_ALREADY_IN_PROGRESS and
     * backed off. Without it a tap during a running sync waits for that sync and runs after it; a manual request still
     * skips Android's backoff and switch, and one from the screen in the foreground skips the standby delays.
     *
     * [answering] for an answer in one of the app's notifications: the account the request goes to, whose sync
     * ([syncAuthorityOf]) may be running already, often the tap the prompt came from, past the answered event. Android
     * drops a request whose extras equal that running sync's, so the request carries the id [putRunningSyncId] gives
     * while it runs: never that sync's own, and the same for every answer given meanwhile, so several answers wait as
     * one sync. Not for the sync buttons, so a double tap stays one sync. A new bundle per call.
     *
     * Every request the user starts is built here (a full re-sync's by [forcedResyncExtras]), and a source guard holds
     * every other file to it: the collision and deletion receivers use it with [answering] set to the account they ask
     * the sync for, and so do the answers to the prompt about a new event written into a calendar someone else shared
     * (publish there, move to the default calendar: calendar.NewEventHoldActions). While a full re-sync of the account's
     * calendar is being prepared, those answers ask for nothing ([FullResyncHold.holdAnswerIfPreparing]): the screen that
     * started the re-sync asks for one such sync once the re-sync's own request is posted.
     */
    internal fun userSyncExtras(
        context: Context,
        parentAccountName: String,
        userSyncNow: Boolean = false,
        answering: Account? = null,
    ): Bundle {
        val retries = userSyncRetries(
            masterSyncOn = ContentResolver.getMasterSyncAutomatically(),
            manualOnly = isManualOnly(context, parentAccountName),
        )
        return manualExtras(retries).apply {
            if (userSyncNow) putBoolean(SyncConstants.EXTRA_USER_SYNC_NOW, true)
            if (answering != null) putRunningSyncId(this, answering, syncAuthorityOf(answering.type))
        }
    }

    /** The authority this app syncs on an account of [accountType]: contacts on an address book, the calendar otherwise. */
    internal fun syncAuthorityOf(accountType: String): String =
        if (accountType == ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK) SyncConstants.AUTHORITY_CONTACTS
        else SyncConstants.AUTHORITY_CALENDAR

    /**
     * The pure half of [putRunningSyncId]: no id unless the account's own sync runs ([accountSyncActive]); then the
     * newest of [startTimes], the start times of the syncs of the authority running now (of any account: without the
     * GET_ACCOUNTS grant Android hides their accounts), or [newId] when none is listed (it ended meanwhile, or the list
     * is withheld), so the request is never taken for the running sync's.
     */
    internal fun runningSyncId(accountSyncActive: Boolean, startTimes: List<Long>, newId: Long): Long? =
        if (!accountSyncActive) null else startTimes.maxOrNull() ?: newId

    /**
     * Puts a [SyncConstants.EXTRA_REQUEST_ID] into [extras] for a request of [account]'s [authority] sync that a sync
     * already running must not swallow, and returns [extras]: an answer in one of the app's notifications
     * ([userSyncExtras] with answering) and the re-time request (TimeZoneRuleSource.requestRetimeSyncs).
     *
     * Android drops a request whose extras equal a running sync's, and that sync may already be past what the request
     * is for; and it merges a request into an equal one still waiting, which then runs once. So while [account]'s
     * [authority] sync runs, the id is the start time of the newest sync of [authority] running ([runningSyncId]):
     * later than the running sync's own id, which was taken before it started, so the two never match, and the same for
     * every request made while it runs, so those wait as one sync. With none of [account]'s running, no id: the request
     * merges with an equal one still waiting, which starts after it anyway. When the sync state cannot be read, or the
     * account's sync runs but no start time is listed, a new id: nothing is lost, and nothing merges.
     */
    internal fun putRunningSyncId(extras: Bundle, account: Account, authority: String): Bundle {
        val id = try {
            val active = ContentResolver.isSyncActive(account, authority)
            runningSyncId(
                accountSyncActive = active,
                startTimes = if (!active) emptyList() else ContentResolver.getCurrentSyncs().filter { it.authority == authority }.map { it.startTime },
                newId = SystemClock.elapsedRealtimeNanos(),
            )
        } catch (e: Exception) {
            SystemClock.elapsedRealtimeNanos()
        }
        if (id != null) extras.putLong(SyncConstants.EXTRA_REQUEST_ID, id)
        return extras
    }

    /**
     * The extras of a full re-sync, [flag] being SyncConstants.EXTRA_FORCE_REMOTE_TO_LOCAL or EXTRA_FORCE_LOCAL_TO_REMOTE:
     * manual, and Android keeps retrying it whatever its automatic sync says. Its preparation already deleted the
     * mappings or the phone's copy and marked the rows, and only a retry that keeps the force flag finishes the job as
     * one (EXTRA_FORCE_LOCAL_TO_REMOTE keeps invitation emails off). A new bundle per call.
     */
    internal fun forcedResyncExtras(flag: String): Bundle = manualExtras(retries = true).apply { putBoolean(flag, true) }

    /**
     * The flags of every manual request the app makes: SYNC_EXTRAS_MANUAL, and SYNC_EXTRAS_DO_NOT_RETRY unless
     * [retries]. Never SYNC_EXTRAS_EXPEDITED ([userSyncExtras]). The one place that asks MANUAL.
     */
    internal fun manualExtraFlags(retries: Boolean): List<String> =
        if (retries) listOf(ContentResolver.SYNC_EXTRAS_MANUAL)
        else listOf(ContentResolver.SYNC_EXTRAS_MANUAL, ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY)

    /**
     * A new bundle with [manualExtraFlags], marked as the app's own request ([SyncConstants.EXTRA_APP_REQUEST]): every
     * sync the user starts ([userSyncExtras]), every full re-sync ([forcedResyncExtras]) and the catch-up's manual first
     * sync are built here.
     */
    private fun manualExtras(retries: Boolean) = Bundle().apply {
        for (flag in manualExtraFlags(retries)) putBoolean(flag, true)
        putBoolean(SyncConstants.EXTRA_APP_REQUEST, true)
    }

    // ---- Manual runs Android starts again ----

    /** What [staleManualRun] makes of one run Android starts. */
    internal enum class StaleRun { NONE, OLD_BACKGROUND_REQUEST, RETRY_NOT_ALLOWED }

    /**
     * Whether a run Android starts must end at once, before it syncs anything. Android runs a SYNC_EXTRAS_MANUAL request
     * whatever its automatic-sync switch says, and retries it while it fails, so two kinds would sync on their own:
     *  - [scheduledByWorker] on a manual run: a background request an older version left queued (2.1.0's event loop and
     *    Fallback full sync asked manual; this version asks the Fallback full sync's as an ordinary request, so none of
     *    its own matches), whether it never ran or Android retries it: [StaleRun.OLD_BACKGROUND_REQUEST].
     *  - Android's retry of a sync the user started ([firstAttempt] false: Android gives every manual request
     *    SYNC_EXTRAS_IGNORE_BACKOFF, and only its retry after a failure drops it), once [userSyncRetries] no longer allows
     *    one: automatic sync switched off or Manual only chosen after the request was made: [StaleRun.RETRY_NOT_ALLOWED].
     * A full re-sync ([forced]) always runs, as its retries finish it. So does a request made with
     * SYNC_EXTRAS_DO_NOT_RETRY ([doNotRetry]): Android retries one only after SYNC_ALREADY_IN_PROGRESS, a tap during a
     * running sync, which then runs after it. A first attempt of anything else is the user's own request and runs.
     */
    internal fun staleManualRun(
        manual: Boolean,
        scheduledByWorker: Boolean,
        firstAttempt: Boolean,
        doNotRetry: Boolean,
        forced: Boolean,
        masterSyncOn: Boolean,
        manualOnly: Boolean,
    ): StaleRun = when {
        !manual || forced -> StaleRun.NONE
        scheduledByWorker -> StaleRun.OLD_BACKGROUND_REQUEST
        firstAttempt || doNotRetry -> StaleRun.NONE
        userSyncRetries(masterSyncOn = masterSyncOn, manualOnly = manualOnly) -> StaleRun.NONE
        else -> StaleRun.RETRY_NOT_ALLOWED
    }

    /**
     * At the start of a run, in both adapters' onPerformSync before the ongoing notification: true when [staleManualRun]
     * says this one must end at once, with a line under [tag] saying why ([syncTypeLabel] is "Calendar" or "Contacts").
     * The run then records no result, so Android counts it done, clears its backoff and retries nothing, and the card
     * keeps showing the last real outcome. [parentAccountName] is the account whose interval applies. An ordinary run
     * returns false at once; anything unreadable lets the run go on, as before this check.
     */
    fun endsStaleManualRun(context: Context, parentAccountName: String, extras: Bundle, syncTypeLabel: String, tag: String): Boolean {
        if (!extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)) return false
        return try {
            val masterSyncOn = ContentResolver.getMasterSyncAutomatically()
            val stale = staleManualRun(
                manual = true,
                scheduledByWorker = extras.getBoolean(SyncConstants.EXTRA_SCHEDULED_BY_WORKER, false),
                firstAttempt = extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, false),
                doNotRetry = extras.getBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, false),
                forced = extras.getBoolean(SyncConstants.EXTRA_FORCE_REMOTE_TO_LOCAL, false) ||
                    extras.getBoolean(SyncConstants.EXTRA_FORCE_LOCAL_TO_REMOTE, false),
                masterSyncOn = masterSyncOn,
                manualOnly = isManualOnly(context, parentAccountName),
            )
            val why = when (stale) {
                StaleRun.NONE -> return false
                StaleRun.OLD_BACKGROUND_REQUEST ->
                    "a manual background request an older version left queued, which this version no longer sends"
                StaleRun.RETRY_NOT_ALLOWED ->
                    "Android's retry of a manual sync that failed earlier, and " +
                        (if (!masterSyncOn) "Android's automatic sync is off now" else "the sync interval is Manual only now")
            }
            SyncLog.i(tag, "$syncTypeLabel sync for $parentAccountName not run: $why; ended without a result, so Android does not retry it")
            true
        } catch (e: Exception) {
            SyncLog.w(tag, "Could not check whether this $syncTypeLabel run for $parentAccountName is a stale retry: ${e.message}")
            false
        }
    }

    // ---- Runs Android starts by itself ----

    /**
     * Whether a run must end at once, before it syncs anything, because Android started it by itself while the account
     * is on "Manual only" ([manualOnly]), which promises no sync the user did not start (owner, 2026-11-22): an ordinary
     * run ([manual] false) that is not Android's upload after an edit on the phone ([upload], SYNC_EXTRAS_UPLOAD, which
     * the owner accepted) and carries no [SyncConstants.EXTRA_APP_REQUEST] ([appRequest]), which every request of the
     * app carries, the Fallback full sync's (it runs on Manual only too) and the catch-up's included. Android starts such
     * a run, with an empty bundle, for every type switched on when its automatic sync is turned on, and for one type
     * when its switch is (in Android's account settings or on the account card); a periodic sync that Manual only
     * removed would look the same. Android's retry of a failed upload drops SYNC_EXTRAS_UPLOAD and runs as such a sync,
     * and ends here as well: on Manual only a failed upload is not tried again by itself, and the failure text asks for
     * a tap on Sync. A manual run (a sync button, Android's own "Sync now") is [staleManualRun]'s.
     */
    internal fun unaskedRun(manual: Boolean, upload: Boolean, appRequest: Boolean, manualOnly: Boolean): Boolean =
        manualOnly && !manual && !upload && !appRequest

    /**
     * At the start of a run, in both adapters' onPerformSync after [endsStaleManualRun] and before the ongoing
     * notification: true when [unaskedRun] says this one must end at once, with a line under [tag] saying why
     * ([syncTypeLabel] is "Calendar" or "Contacts"), as for a stale manual run. The run records no result, so Android
     * counts it done and retries nothing, and the card keeps showing the last real outcome. [parentAccountName] is the
     * account whose interval applies. A manual run returns false at once; anything unreadable lets the run go on.
     */
    fun endsUnaskedRun(context: Context, parentAccountName: String, extras: Bundle, syncTypeLabel: String, tag: String): Boolean {
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)) return false
        return try {
            val ends = unaskedRun(
                manual = false,
                upload = extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false),
                appRequest = extras.getBoolean(SyncConstants.EXTRA_APP_REQUEST, false),
                manualOnly = isManualOnly(context, parentAccountName),
            )
            if (ends) {
                SyncLog.i(
                    tag,
                    "$syncTypeLabel sync for $parentAccountName not run: a sync Android started by itself, none of this " +
                        "app's (its automatic sync or this type's switch turned on, or the retry of a failed upload), and " +
                        "the sync interval is Manual only; ended without a result, so Android does not retry it"
                )
            }
            ends
        } catch (e: Exception) {
            SyncLog.w(tag, "Could not check whether this $syncTypeLabel run for $parentAccountName is one Android started by itself: ${e.message}")
            false
        }
    }

    // ---- The catch-up when the subscription turns active ----

    /** How one type's catch-up sync is asked for: not at all, as an ordinary request, or with SYNC_EXTRAS_MANUAL. */
    internal enum class CatchUp { NONE, ORDINARY, MANUAL }

    /**
     * Whether, and how, one type of one account gets its catch-up sync. A switched-off type gets none.
     *
     * On "Manual only" (-1), a type whose sync was never even attempted ([everAttempted] false: no success and no
     * failure recorded) is owed the first sync the gate dropped, the setup chooser's or a restore's own MANUAL
     * request, which would have run there. One that ran and failed is not: the gate dropped nothing, and that
     * setting promises no sync the user did not start, so it gets nothing, like one that has synced.
     *
     * Otherwise, with Android's automatic sync on: an ordinary request (not expedited, so it never pre-empts a running
     * sync). With it off, Android runs only SYNC_EXTRAS_MANUAL requests, so a type whose first sync was never attempted
     * is asked for with MANUAL (not retried if it fails, [userSyncRetries]); one that ran, failed or not, waits for
     * automatic sync or a tap. Never MANUAL while the account is paused after an auth failure ([paused]), which MANUAL
     * would get through.
     */
    internal fun catchUpRequest(
        storedIntervalSeconds: Long,
        switchedOn: Boolean,
        masterSyncOn: Boolean,
        everSynced: Boolean,
        paused: Boolean,
        everAttempted: Boolean = everSynced,
    ): CatchUp = when {
        !switchedOn -> CatchUp.NONE
        storedIntervalSeconds < 0 -> if (!everAttempted && !paused) CatchUp.MANUAL else CatchUp.NONE
        masterSyncOn -> CatchUp.ORDINARY
        !everAttempted && !paused -> CatchUp.MANUAL
        else -> CatchUp.NONE
    }

    /**
     * One sync per switched-on type of every account, when the subscription turns active.
     *
     * An ordinary request: not SYNC_EXTRAS_MANUAL, which would let these automatic requests through an account's pause
     * after an auth failure (SyncUtils.isSyncPaused) and retry a dead session with nobody asking, and not expedited,
     * which pre-empted a running sync of another account. The one exception is a first sync never attempted while
     * Android's automatic sync is off or on "Manual only" ([catchUpRequest]): asked with MANUAL, as the sync the gate
     * dropped was, and then not retried in the background ([userSyncRetries]). Otherwise "Manual only" accounts are left
     * alone, since that setting promises no sync the user did not start. The switches are checked here, not left to the
     * framework, so the logged counts are true.
     */
    fun requestCatchUpSyncs(context: Context) {
        try {
            val masterSync = ContentResolver.getMasterSyncAutomatically()
            val am = AccountManager.get(context)
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val addressBooks = am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
            var calendar = 0
            var contacts = 0
            var manual = 0
            for (parent in am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)) {
                val interval = SyncPreferences.getLongFromStringPref(
                    prefs,
                    SyncPreferences.eventLoopIntervalKey(parent.name),
                    SyncConstants.DEFAULT_INITIAL_PERIODIC_SYNC_SECONDS,
                )
                val paused = SyncUtils.isSyncPaused(context, parent.name, isManual = false)
                // syncType is "calendar" or "contacts", the last-sync key's own (SyncUtils' outcome bookkeeping).
                fun request(account: Account, authority: String, syncType: String): Boolean {
                    val everSynced = prefs.getLong(SyncPreferences.lastSyncKey(parent.name, syncType), 0L) != 0L
                    val how = catchUpRequest(
                        interval,
                        ContentResolver.getSyncAutomatically(account, authority),
                        masterSync,
                        everSynced = everSynced,
                        paused = paused,
                        // A sync the gate drops records neither time; one that ran and failed records the failure.
                        everAttempted = everSynced ||
                            prefs.getLong(SyncPreferences.lastSyncFailedKey(parent.name, syncType), 0L) != 0L,
                    )
                    if (how == CatchUp.NONE) return false
                    ContentResolver.requestSync(
                        account, authority,
                        catchUpExtras(
                            manual = how == CatchUp.MANUAL,
                            retries = userSyncRetries(masterSyncOn = masterSync, manualOnly = interval < 0),
                        ),
                    )
                    return true
                }
                val calendarAsked = request(parent, SyncConstants.AUTHORITY_CALENDAR, "calendar")
                if (calendarAsked) calendar++
                val ab = linkedAddressBook(am, parent, addressBooks)
                val contactsAsked = ab != null && request(ab, SyncConstants.AUTHORITY_CONTACTS, "contacts")
                if (contactsAsked) contacts++
                // A Manual only account counts as left alone unless one of its first syncs was owed.
                if (interval < 0 && !calendarAsked && !contactsAsked) manual++
            }
            val left = if (manual > 0) "; $manual account(s) on Manual only left alone" else ""
            if (masterSync) {
                SyncLog.i(TAG, "Subscription turned active: catch-up sync requested for $calendar calendar and $contacts contacts account(s)$left")
            } else {
                SyncLog.i(
                    TAG,
                    "Subscription turned active with Android's automatic sync off: manual first sync requested for " +
                        "$calendar calendar and $contacts contacts account(s) that never attempted one; " +
                        "types that ran before wait for automatic sync or a tap$left"
                )
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not request the catch-up sync: ${e.message}")
        }
    }

    /**
     * A new bundle per request: the framework keeps the one it is given. [retries] applies to a [manual] one only; the
     * other is ordinary. Both are marked as the app's own ([SyncConstants.EXTRA_APP_REQUEST]).
     */
    private fun catchUpExtras(manual: Boolean, retries: Boolean): Bundle {
        val extras = if (manual) manualExtras(retries) else Bundle()
        return extras.apply {
            putBoolean(SyncConstants.EXTRA_CATCH_UP, true)
            putBoolean(SyncConstants.EXTRA_APP_REQUEST, true)
        }
    }

    /**
     * The parent's address-book child, by the parent link set at sign-in. One made by an older version can
     * carry another name until the start-up migration renames it, so the name is only the fallback.
     */
    internal fun linkedAddressBook(am: AccountManager, parent: Account, addressBooks: Array<Account>): Account? =
        addressBooks.firstOrNull { am.getUserData(it, SyncConstants.EXTRA_PARENT_ACCOUNT_NAME) == parent.name }
            ?: addressBooks.firstOrNull { it.name == SyncUtils.addressBookAccountName(am, parent) }

    private fun clearPeriodicSyncs(context: Context) {
        val am = AccountManager.get(context)
        for (account in am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)) {
            ContentResolver.removePeriodicSync(account, SyncConstants.AUTHORITY_CALENDAR, Bundle.EMPTY)
        }
        for (abAccount in am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)) {
            ContentResolver.removePeriodicSync(abAccount, SyncConstants.AUTHORITY_CONTACTS, Bundle.EMPTY)
        }
    }

    /**
     * Bring the periodic syncs into line with the schedule the user actually chose.
     *
     * This used to re-arm every enabled type at the hardcoded one-hour default on every start,
     * ignoring the interval setting completely. That did two wrong things at once: it undid a
     * "Manual only" choice on the next launch, so the fix that removed those syncs lasted until
     * the user next opened the app, and it pinned everyone else to an hour no matter which
     * interval they had picked. Both were seen in the field: set to "Manual only", the app still
     * synced after an update and a force-close.
     *
     * Reconciling rather than restoring: the stored interval is the single source of truth, and a
     * negative value means manual, so the sync is removed rather than re-created.
     */
    private fun restorePeriodicSyncs(context: Context) {
        val am = AccountManager.get(context)
        for (account in am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)) {
            val abAccount = Account(SyncUtils.addressBookAccountName(am, account), ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
            SyncUtils.applyPeriodicSync(context, account, SyncConstants.AUTHORITY_CALENDAR, account.name, syncAllowed = true)
            SyncUtils.applyPeriodicSync(context, abAccount, SyncConstants.AUTHORITY_CONTACTS, account.name, syncAllowed = true)
        }
    }

    // ---- What the removed event loop left behind ----

    /**
     * The WorkManager tag of every job 2.1.0's event loop scheduled: WorkRequest.Builder tags a request with its worker's
     * class name, which an R8 rule kept unobfuscated since 1.3.44. One cancel by tag reaches each job, whatever account
     * name its unique work name was made from.
     */
    internal const val RETIRED_EVENT_LOOP_TAG = "me.proton.sync.EventLoopWorker"

    /** The last event id the event loop had read, per account. */
    internal const val RETIRED_EVENT_ID_PREFIX = "event_loop_id_"

    /** The flag a calendar setting change set for the event loop's next run, per account; [requestSettingsSync] replaces it. */
    internal const val RETIRED_SETTINGS_DIRTY_PREFIX = "calendar_settings_dirty_"

    /** The keys among [keys] that the event loop kept, of any account, gone or not. */
    internal fun retiredKeys(keys: Iterable<String>): List<String> =
        keys.filter { k -> k.startsWith(RETIRED_EVENT_ID_PREFIX) || k.startsWith(RETIRED_SETTINGS_DIRTY_PREFIX) }

    /** The accounts among [accountNames] whose calendar setting change still waits for its sync in [all] (a true flag). */
    internal fun accountsWithPendingSettings(all: Map<String, *>, accountNames: Collection<String>): List<String> =
        accountNames.filter { all[RETIRED_SETTINGS_DIRTY_PREFIX + it] == true }

    /** The accounts among [accountNames] whose calendar range or external calendars switch is among the restored [keys]. */
    internal fun accountsWithRestoredCalendarSettings(keys: Collection<String>, accountNames: Collection<String>): List<String> =
        accountNames.filter { n ->
            listOf(
                SyncPreferences.calendarSyncModeKey(n),
                SyncPreferences.calendarPastDaysKey(n),
                SyncPreferences.calendarFutureDaysKey(n),
                SyncPreferences.includeExternalCalendarsKey(n),
            ).any { it in keys }
        }

    /**
     * Remove what 2.1.0's event loop left behind, at every start, off the main thread. Its jobs: cancelled by their tag,
     * only when a query finds one unfinished (or the query fails), so a normal start is one query; one that already ran
     * into the missing worker class is FAILED, finished, and left alone. Its two settings of every account: deleted,
     * after asking for the calendar sync that each current account's still-set calendar_settings_dirty_ flag stood for
     * (a range, external calendars or removal change made on 2.1.0 whose sync had not run before the update), under the
     * background rule. Idempotent: after a downgrade to 2.1.0 and back it cleans again.
     */
    fun removeEventLoopLeftovers(context: Context) {
        val wm = WorkManager.getInstance(context)
        val live = try {
            wm.getWorkInfosByTag(RETIRED_EVENT_LOOP_TAG).get(10, TimeUnit.SECONDS).count { !it.state.isFinished }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not count the event loop's scheduled jobs (${e.message}); cancelling them by tag anyway")
            -1
        }
        if (live != 0) wm.cancelAllWorkByTag(RETIRED_EVENT_LOOP_TAG)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val names = AccountManager.get(context).getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE).map { it.name }
        for (name in accountsWithPendingSettings(prefs.all, names)) {
            requestSettingsSync(context, Account(name, ProtonSyncApp.ACCOUNT_TYPE), "changed before the update")
        }
        val retired = retiredKeys(prefs.all.keys)
        if (retired.isNotEmpty()) {
            val edit = prefs.edit()
            for (key in retired) edit.remove(key)
            edit.apply()
        }
        if (live != 0 || retired.isNotEmpty()) {
            val jobs = if (live < 0) "its" else "$live"
            SyncLog.i(TAG, "The event loop is gone: cancelled $jobs scheduled job(s) and removed ${retired.size} setting(s) it kept")
        }
    }

    /**
     * Debug builds' schedule_info: per account, what is armed and what it was armed from, and on the first line what is
     * left of the removed event loop's jobs. The unique work names are not visible in dumpsys jobscheduler, so this is
     * the only way to see them on a phone. Names the account and nothing else about it: no token, no id.
     */
    internal fun debugLogSchedules(context: Context) {
        val tag = "DebugCommand"
        val am = AccountManager.get(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val wm = WorkManager.getInstance(context)
        val parents = am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
        val addressBooks = am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
        // CANCELLED (or FAILED) for about a day after the update, then none; ENQUEUED or RUNNING means the clean-up failed.
        val eventLoop = wm.getWorkInfosByTag(RETIRED_EVENT_LOOP_TAG).get().map { it.state.name }.ifEmpty { listOf("none") }.joinToString("/")
        SyncLog.i(
            tag,
            "schedule_info: subscription allows sync=${SubscriptionGate.isSyncAllowedCached(context)}, " +
                "Android automatic sync=${ContentResolver.getMasterSyncAutomatically()}, ${parents.size} account(s); " +
                "event loop (removed): $eventLoop"
        )
        fun work(name: String): String =
            wm.getWorkInfosForUniqueWork(name).get().map { it.state.name }.ifEmpty { listOf("none") }.joinToString("/")
        fun periodic(account: Account, authority: String): String =
            ContentResolver.getPeriodicSyncs(account, authority).map { "${it.period}s" }.ifEmpty { listOf("none") }.joinToString("/")
        fun fallback(enabledKey: String, intervalKey: String): String =
            if (prefs.getBoolean(enabledKey, false)) {
                SyncPreferences.getStringOrDefault(prefs, intervalKey, SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS) + "s"
            } else "off"
        for (parent in parents) {
            val name = parent.name
            val interval = SyncPreferences.getLongFromStringPref(
                prefs, SyncPreferences.eventLoopIntervalKey(name), SyncConstants.DEFAULT_INITIAL_PERIODIC_SYNC_SECONDS
            )
            val ab = linkedAddressBook(am, parent, addressBooks)
            val contacts = if (ab == null) "no address book" else
                "switch ${if (ContentResolver.getSyncAutomatically(ab, SyncConstants.AUTHORITY_CONTACTS)) "on" else "off"}, " +
                    "periodic ${periodic(ab, SyncConstants.AUTHORITY_CONTACTS)}"
            SyncLog.i(
                tag,
                "schedule_info: '$name': interval ${if (interval < 0) "manual" else "${interval}s"}; " +
                    "calendar switch ${if (ContentResolver.getSyncAutomatically(parent, SyncConstants.AUTHORITY_CALENDAR)) "on" else "off"}, " +
                    "periodic ${periodic(parent, SyncConstants.AUTHORITY_CALENDAR)}; contacts $contacts; " +
                    "contacts watchdog ${work(SyncWorker.workName(name, "contacts"))} (fallback " +
                    "${fallback(SyncPreferences.fallbackContactsEnabledKey(name), SyncPreferences.fallbackContactsIntervalKey(name))}); " +
                    "calendar watchdog ${work(SyncWorker.workName(name, "calendar"))} (fallback " +
                    "${fallback(SyncPreferences.fallbackCalendarEnabledKey(name), SyncPreferences.fallbackCalendarIntervalKey(name))})"
            )
        }
    }
}
