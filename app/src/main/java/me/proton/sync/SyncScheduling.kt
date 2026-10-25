package me.proton.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import androidx.preference.PreferenceManager
import androidx.work.WorkManager
import me.proton.sync.billing.SubscriptionGate
import me.proton.sync.billing.SubscriptionState

/**
 * The background schedules (the event loop, the fallback watchdogs and Android's periodic syncs) as one
 * thing that follows the subscription: armed while it lets sync run, cancelled while it does not.
 *
 * The subscription observer in [ProtonSyncApp] drives it through [Tracker]. Paths that create or change one
 * account (provisioning, a rename, a restore) arm through the gated helpers here, so nothing schedules work
 * that the gate would only decline.
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
        EventLoopWorker.scheduleAll(context)
        SyncWorker.scheduleAll(context)
        restorePeriodicSyncs(context)
    }

    /** Cancel every account's schedules. The type switches are left alone, so a renewal finds them as they were. */
    fun disarmAll(context: Context) {
        EventLoopWorker.cancelAll(context)
        SyncWorker.cancelAll(context)
        clearPeriodicSyncs(context)
    }

    /**
     * Arm one account's event loop and fallback watchdogs from its stored settings, or nothing while the
     * subscription is inactive. Returns whether it armed.
     *
     * Arming nothing is enough there: the observer's DISARM has already cancelled everything, and arms it
     * all again from the stored settings when the subscription turns active.
     */
    fun scheduleWorkers(context: Context, accountName: String): Boolean {
        if (!SubscriptionGate.isSyncAllowedCached(context)) {
            SyncLog.i(TAG, "Background schedules for '$accountName' not armed: the subscription is inactive; they are armed when it turns active")
            return false
        }
        EventLoopWorker.scheduleFromSettings(context, accountName)
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

    /** How one type's catch-up sync is asked for: not at all, EXPEDITED, or EXPEDITED and SYNC_EXTRAS_MANUAL. */
    internal enum class CatchUp { NONE, EXPEDITED, MANUAL }

    /**
     * Whether, and how, one type of one account gets its catch-up sync. A switched-off type gets none.
     *
     * On "Manual only" (-1), a type whose sync was never even attempted ([everAttempted] false: no success and no
     * failure recorded) is owed the first sync the gate dropped, the setup chooser's or a restore's own MANUAL
     * request, which would have run there. One that ran and failed is not: the gate dropped nothing, and that
     * setting promises no sync the user did not start, so it gets nothing, like one that has synced.
     *
     * Otherwise, with Android's automatic sync on: EXPEDITED. With it off, Android runs only SYNC_EXTRAS_MANUAL
     * requests, so a type that has never completed a sync ([everSynced] false) is asked for with MANUAL, and a type
     * that has synced before waits for automatic sync, as before 2.0.2. Never MANUAL while the account is paused
     * after an auth failure ([paused]), which MANUAL would get through.
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
        masterSyncOn -> CatchUp.EXPEDITED
        !everSynced && !paused -> CatchUp.MANUAL
        else -> CatchUp.NONE
    }

    /**
     * One sync per switched-on type of every account, when the subscription turns active.
     *
     * EXPEDITED, like TimeZoneRuleSource.requestRetimeSyncs: not SYNC_EXTRAS_MANUAL, which would let these
     * automatic requests through an account's pause after an auth failure (SyncUtils.isSyncPaused) and retry
     * a dead session with nobody asking. The one exception is a first sync while Android's automatic sync is
     * off or on "Manual only" ([catchUpRequest]). Otherwise "Manual only" accounts are left alone, since that
     * setting promises no sync the user did not start. The switches are checked here, not left to the
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
                    ContentResolver.requestSync(account, authority, catchUpExtras(manual = how == CatchUp.MANUAL))
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
                        "$calendar calendar and $contacts contacts account(s) that never completed one; " +
                        "types that synced before wait for automatic sync$left"
                )
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not request the catch-up sync: ${e.message}")
        }
    }

    /** A new bundle per request: the framework keeps the one it is given. */
    private fun catchUpExtras(manual: Boolean) = Bundle().apply {
        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        if (manual) putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        putBoolean(SyncConstants.EXTRA_CATCH_UP, true)
    }

    /**
     * The parent's address-book child, by the parent link set at sign-in. One made by an older version can
     * carry another name until the start-up migration renames it, so the name is only the fallback.
     */
    private fun linkedAddressBook(am: AccountManager, parent: Account, addressBooks: Array<Account>): Account? =
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

    /**
     * Debug builds' schedule_info: per account, what is armed and what it was armed from. The unique work
     * names are not visible in dumpsys jobscheduler, so this is the only way to see them on a phone. Names
     * the account and nothing else about it: no token, no id.
     */
    internal fun debugLogSchedules(context: Context) {
        val tag = "DebugCommand"
        val am = AccountManager.get(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val wm = WorkManager.getInstance(context)
        val parents = am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
        val addressBooks = am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
        SyncLog.i(
            tag,
            "schedule_info: subscription allows sync=${SubscriptionGate.isSyncAllowedCached(context)}, " +
                "Android automatic sync=${ContentResolver.getMasterSyncAutomatically()}, ${parents.size} account(s)"
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
                    "event loop ${work(EventLoopWorker.workName(name))}; " +
                    "contacts watchdog ${work(SyncWorker.workName(name, "contacts"))} (fallback " +
                    "${fallback(SyncPreferences.fallbackContactsEnabledKey(name), SyncPreferences.fallbackContactsIntervalKey(name))}); " +
                    "calendar watchdog ${work(SyncWorker.workName(name, "calendar"))} (fallback " +
                    "${fallback(SyncPreferences.fallbackCalendarEnabledKey(name), SyncPreferences.fallbackCalendarIntervalKey(name))})"
            )
        }
    }
}
