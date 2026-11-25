package me.proton.sync

/**
 * The accounts whose full re-sync is being prepared, the sync types it re-syncs, and the calendar setting changes held
 * for them meanwhile.
 *
 * A full re-sync's preparation deletes the mappings or the phone's copy of the types it re-syncs and marks rows to
 * upload before it posts its own forced request. An ordinary sync of such a type that starts inside it would upload the
 * marked rows as ordinary changes, invitation emails included (only the forced Local to Remote run keeps them off). So
 * no sync the app asks for on its own is sent for that type of the account (SyncScheduling.requestBackgroundSync says
 * RESYNC_PREPARING), and a calendar setting changed while the account's calendar is prepared is held
 * (SyncScheduling.requestSettingsSync) and asked for once the preparation ends ([end]), after the re-sync's own request.
 * So is the calendar sync an answer in one of the app's notifications asks for ([holdAnswerIfPreparing]: a new event's
 * Publish or Move, a collision's answer, a calendar deletion prompt's): the answer is stored at once, and its sync is asked for
 * once, as the user's own sync, after the re-sync's own request ([takeHeldAnswer]).
 * The other type syncs as usual: a contacts re-sync's preparation touches no calendar row, and a calendar one no contact.
 *
 * Process-wide, not the settings screen's: the preparation runs in a coroutine that outlives the screen that started
 * it, and a rotation, a language change or leaving and opening the screen again builds a new one. The screen's sync
 * buttons read it too, so they keep refusing while the preparation runs, whichever type it re-syncs. Thread-safe: the
 * screen marks and reads it on the main thread, the background requests read it from any thread.
 */
internal object FullResyncHold {

    /** The authorities being re-synced per account: SyncConstants.AUTHORITY_CONTACTS, AUTHORITY_CALENDAR or both. */
    private val preparing = HashMap<String, Set<String>>()

    /** The labels of the calendar setting changes held per account, in the order they came. */
    private val held = HashMap<String, LinkedHashSet<String>>()

    /**
     * Per account, whether a calendar sync an answer asked for was held during the preparation ([holdAnswerIfPreparing]),
     * and whether one of those answers wanted the calendar's unchanged-skip bypassed (EXTRA_USER_SYNC_NOW, a collision's).
     */
    private val heldAnswers = HashMap<String, Boolean>()

    /** The authorities a full re-sync of [section] ("contacts", "calendar" or "all") prepares; anything else is both. */
    fun authoritiesOf(section: String): Set<String> = when (section) {
        "contacts" -> setOf(SyncConstants.AUTHORITY_CONTACTS)
        "calendar" -> setOf(SyncConstants.AUTHORITY_CALENDAR)
        else -> setOf(SyncConstants.AUTHORITY_CONTACTS, SyncConstants.AUTHORITY_CALENDAR)
    }

    /** [accountName]'s full re-sync preparation of [section] starts. */
    @Synchronized
    fun start(accountName: String, section: String) {
        preparing[accountName] = preparing[accountName].orEmpty() + authoritiesOf(section)
    }

    /** Whether a full re-sync of [accountName]'s [authority] sync is being prepared. */
    @Synchronized
    fun isPreparing(accountName: String, authority: String): Boolean = preparing[accountName]?.contains(authority) == true

    /** The accounts whose full re-sync of either type is being prepared, as a copy. */
    @Synchronized
    fun preparing(): Set<String> = HashSet(preparing.keys)

    /** Holds the calendar setting [change] for [accountName] when its calendar's full re-sync is being prepared, and says whether it did. */
    @Synchronized
    fun holdIfPreparing(accountName: String, change: String): Boolean {
        if (!isPreparing(accountName, SyncConstants.AUTHORITY_CALENDAR)) return false
        held.getOrPut(accountName) { LinkedHashSet() }.add(change)
        return true
    }

    /**
     * [accountName]'s preparation ended, its own request posted or the preparation failed: the changes held for it,
     * their labels joined for the log line, or null when none was. An answer's held sync stays for [takeHeldAnswer].
     */
    @Synchronized
    fun end(accountName: String): String? {
        preparing.remove(accountName)
        return held.remove(accountName)?.joinToString(", ")
    }

    /**
     * Holds the calendar sync that an answer in one of the app's notifications asks for (a new event's Publish or Move,
     * a collision's answer, a calendar deletion prompt's) while a full re-sync of [accountName]'s calendar is being prepared, and
     * says whether it did; [userSyncNow] as the answer would have asked it. The answer is stored already: the forced run
     * or the sync asked for after it ([takeHeldAnswer]) applies it, and a sync started now would run inside the
     * preparation.
     */
    @Synchronized
    fun holdAnswerIfPreparing(accountName: String, userSyncNow: Boolean = false): Boolean {
        if (!isPreparing(accountName, SyncConstants.AUTHORITY_CALENDAR)) return false
        heldAnswers[accountName] = heldAnswers[accountName] == true || userSyncNow
        return true
    }

    /**
     * Right after [end]: null when no answer's sync was held for [accountName] during its preparation, else whether one
     * of them wanted EXTRA_USER_SYNC_NOW. Taken once: the screen then asks for one calendar sync for all of them.
     */
    @Synchronized
    fun takeHeldAnswer(accountName: String): Boolean? = heldAnswers.remove(accountName)
}
