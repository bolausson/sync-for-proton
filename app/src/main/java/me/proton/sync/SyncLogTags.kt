package me.proton.sync

/**
 * Which tags belong to the user-facing **sync log** view.
 *
 * Display only. Nothing here affects what is captured or what gets shared: [LogBuffer]
 * takes every line and [DebugLog] exports every line regardless of this set. That is
 * deliberate, and it is the difference between this list and the two capture-time
 * allowlists it replaces — those silently discarded whatever they forgot, so a tag missing
 * from them destroyed the evidence. A tag missing from *this* set merely means the line is
 * one tap away under "All".
 */
object SyncLogTags {

    /**
     * Tags that answer "what did the sync actually do?" — the question the viewer exists
     * to answer. Crypto, auth, mappers, decryptors and log plumbing are deliberately
     * absent: they are diagnostic detail, not sync activity.
     */
    private val SYNC_TAGS = setOf(
        "ProtonSync",
        "CalendarSync",
        "ContactsSync",
        "SyncWorker",
        "EventLoop",
        "ConflictResolver",
        "ConflictGuard",
        "EventConflict",
        "RemoteDeletion",
        "BulkDeletion",
        "CalendarInvite",
        "SimVanished",
        "CalendarBackup",
        "ContactsBackup",
        "BackupWorker",
        "UserAction",
        "SyncNotify",
    )

    /**
     * True if [record] belongs in the sync view.
     *
     * Warnings and errors pass from *any* tag. Without that escape hatch the view would
     * hide the one line a stuck user most needs to see — a sign-in or verification failure
     * is tagged `LoginActivity` or `Captcha`, neither of which is sync activity.
     */
    fun isSyncLine(record: LogRecord): Boolean =
        record.severity >= 2 || (record.tag in SYNC_TAGS && record.severity >= 1)
}
