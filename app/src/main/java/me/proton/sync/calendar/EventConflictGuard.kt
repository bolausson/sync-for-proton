package me.proton.sync.calendar

import android.content.ContentProviderClient
import android.content.Context
import android.provider.CalendarContract
import androidx.preference.PreferenceManager
import me.proton.sync.SyncLog
import java.security.MessageDigest

/**
 * Collision handling for events changed on BOTH sides since the last sync.
 *
 * The problem this exists for: the download phase runs before the upload phase and
 * `CalendarMapper.buildEventValues` writes `Events.DIRTY = 0`, so when the server also changed an
 * event the user had edited locally, the local edit was overwritten and its dirty flag cleared
 * before it was ever sent. Silently, with no prompt and no log line.
 *
 * The design mirrors [me.proton.sync.RemoteDeletionGuard], which already handles the equivalent
 * "server deleted things, ask before applying" decision: the conflicting item is HELD, the rest of
 * the sync proceeds normally, and a notification lets the user choose. Sync is never blocked
 * globally. A sync adapter cannot wait on user input, and one unanswered prompt must not be able to
 * freeze every calendar on the account.
 *
 * ## Not every dirty event is a collision
 *
 * Android sets `Events.DIRTY = 1` on the parent row when a REMINDER or ATTENDEE child row changes,
 * not just when the event itself is edited. Since v1.6.25 reminders and colour are this member's
 * personal part and travel via `PUT .../events/{id}/personal`, which does not touch shared event
 * data at all, so "user added a reminder" and "server changed the title" are not in conflict: both
 * can be applied. Only a change to a SHARED field is a real collision.
 *
 * Telling those apart needs a baseline, which is what `shared_fingerprint` on the mapping row is.
 * Comparing the local row against the REMOTE one cannot do it: when the server changed the title,
 * local and remote differ whether or not the user touched it.
 */
object EventConflictGuard {

    private const val TAG = "ConflictGuard"
    private val lock = Any()

    /**
     * ASCII unit separator (0x1F), used to join fields before hashing. It cannot occur in calendar
     * text, so "ab" + "c" and "a" + "bc" can never collide into the same fingerprint. Built from
     * its code point rather than written as an escape so the source stays plain ASCII.
     */
    private val FIELD_SEPARATOR: Char = 31.toChar()

    private fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)
    private fun heldKey(accountName: String) = "conflict_held_$accountName"

    /** Proton event ids currently awaiting a user decision on this account. */
    fun heldIds(c: Context, accountName: String): Set<String> =
        prefs(c).getStringSet(heldKey(accountName), emptySet()) ?: emptySet()

    fun isHeld(c: Context, accountName: String, protonEventId: String): Boolean =
        protonEventId in heldIds(c, accountName)

    fun hold(c: Context, accountName: String, protonEventId: String) = synchronized(lock) {
        val updated = heldIds(c, accountName).toMutableSet().apply { add(protonEventId) }
        prefs(c).edit().putStringSet(heldKey(accountName), updated).apply()
    }

    fun release(c: Context, accountName: String, protonEventId: String) = synchronized(lock) {
        val updated = heldIds(c, accountName).toMutableSet().apply { remove(protonEventId) }
        prefs(c).edit().putStringSet(heldKey(accountName), updated).apply()
    }

    private fun localWinsKey(accountName: String) = "conflict_local_wins_$accountName"

    /**
     * Events the user resolved with "Keep mine" and whose local version has not been uploaded yet.
     *
     * Releasing the hold is NOT enough on its own. The download phase runs before the upload phase,
     * so the next sync would re-run the collision check, find the same disagreement, and re-hold the
     * event before the upload ever got a turn: "Keep mine" could never win, on any calendar. This
     * set tells the download to leave the event alone until the upload has pushed it, at which point
     * [clearLocalWins] retires the marker and normal two-way syncing resumes.
     */
    fun isLocalWins(c: Context, accountName: String, protonEventId: String): Boolean =
        protonEventId in (prefs(c).getStringSet(localWinsKey(accountName), emptySet()) ?: emptySet())

    fun markLocalWins(c: Context, accountName: String, protonEventId: String) = synchronized(lock) {
        val current = prefs(c).getStringSet(localWinsKey(accountName), emptySet()) ?: emptySet()
        prefs(c).edit().putStringSet(localWinsKey(accountName), current + protonEventId).apply()
    }

    fun clearLocalWins(c: Context, accountName: String, protonEventId: String) = synchronized(lock) {
        val current = prefs(c).getStringSet(localWinsKey(accountName), emptySet()) ?: emptySet()
        if (protonEventId in current) {
            prefs(c).edit().putStringSet(localWinsKey(accountName), current - protonEventId).apply()
        }
    }

    /**
     * Forget everything this account was waiting on, and return the ids so their prompts can be
     * taken down.
     *
     * For an account that is being removed. The preference keys are account-scoped, so re-adding the
     * same address later inherited the old holds: events blocked from syncing in either direction by
     * a question asked of a previous installation of that account, about local rows that no longer
     * exist. Nothing on the phone or on Proton is touched.
     */
    fun forgetAll(c: Context, accountName: String): Set<String> = synchronized(lock) {
        val held = heldIds(c, accountName)
        prefs(c).edit().remove(heldKey(accountName)).remove(localWinsKey(accountName)).apply()
        held
    }

    /**
     * Drop holds for events that no longer exist, so a deleted event cannot leave a permanent
     * pending decision behind, and return the ids dropped so the caller can take their
     * notifications down.
     */
    fun prune(c: Context, accountName: String, presentIds: Set<String>): Set<String> = synchronized(lock) {
        val current = heldIds(c, accountName)
        val kept = current.intersect(presentIds)
        if (kept.size == current.size) return@synchronized emptySet()
        val dropped = current - kept
        val localWins = prefs(c).getStringSet(localWinsKey(accountName), emptySet()) ?: emptySet()
        prefs(c).edit()
            .putStringSet(heldKey(accountName), kept)
            // The same event's "Keep mine" marker goes with it. It is only ever retired by an upload
            // of that event, which can no longer happen, so it would sit in preferences for the life
            // of the account and suppress the download check if the id were ever seen again.
            .putStringSet(localWinsKey(accountName), localWins - dropped)
            .apply()
        dropped
    }

    /**
     * Fingerprint of the SHARED fields of a local event row: everything that belongs to the event
     * itself rather than to this member.
     *
     * Deliberately EXCLUDES `EVENT_COLOR` (per-member), reminders and attendees (child rows, and
     * reminders are per-member too), and `DIRTY`/`_SYNC_ID` (bookkeeping). Including any of those
     * would make a personal-part edit look like a collision, which is the exact false positive this
     * whole mechanism exists to avoid.
     *
     * Returns null when the row cannot be read, which callers must treat as "unknown".
     */
    fun fingerprintLocalRow(provider: ContentProviderClient, androidEventId: Long): String? =
        readSharedValues(provider, androidEventId)?.let { fingerprintOf(it) }

    /**
     * The row's [SHARED_COLUMNS] values as a cursor's getString returns them, or null when the row cannot be
     * read. One read serves both the fingerprint and the record of the time columns (UploadTimes.SyncedTimes).
     */
    fun readSharedValues(provider: ContentProviderClient, androidEventId: Long): List<String?>? {
        return try {
            provider.query(
                CalendarContract.Events.CONTENT_URI,
                SHARED_COLUMNS,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(androidEventId.toString()),
                null
            )?.use { c ->
                if (!c.moveToFirst()) return null
                List(c.columnCount) { i -> if (c.isNull(i)) null else c.getString(i) }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not fingerprint event $androidEventId: ${e.message}")
            null
        }
    }

    /**
     * The shared columns the fingerprint covers, in its order. The re-time pass reads these same columns so
     * it can compute a row's fingerprint, and the corrected one, without a second read.
     */
    val SHARED_COLUMNS: Array<String> = arrayOf(
        CalendarContract.Events.TITLE,
        CalendarContract.Events.DESCRIPTION,
        CalendarContract.Events.EVENT_LOCATION,
        CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND,
        CalendarContract.Events.DURATION,
        CalendarContract.Events.ALL_DAY,
        CalendarContract.Events.RRULE,
        CalendarContract.Events.RDATE,
        CalendarContract.Events.EXDATE,
        CalendarContract.Events.EVENT_TIMEZONE,
        CalendarContract.Events.STATUS,
        CalendarContract.Events.AVAILABILITY
    )

    /** Indexes into [SHARED_COLUMNS] the re-time pass and the upload's time record read (pinned by a test). */
    const val COLUMN_DTSTART = 3
    const val COLUMN_DTEND = 4
    const val COLUMN_DURATION = 5
    const val COLUMN_ALL_DAY = 6
    const val COLUMN_RRULE = 7
    const val COLUMN_RDATE = 8
    const val COLUMN_EVENT_TIMEZONE = 10

    /**
     * The fingerprint of [SHARED_COLUMNS] values as a cursor's getString returns them (null for SQL NULL).
     * Pure: [fingerprintLocalRow] is this applied to one row read.
     */
    fun fingerprintOf(values: List<String?>): String {
        val parts = StringBuilder()
        for (value in values) {
            // Normalise null and empty to the same token: a provider that stores "" where
            // another stores NULL must not read as a user edit.
            val raw = value ?: ""
            // ASCII unit separator, which cannot occur in calendar text, so "ab" + "c"
            // and "a" + "bc" can never collide into the same fingerprint.
            parts.append(raw.trim()).append(FIELD_SEPARATOR)
        }
        return sha256(parts.toString())
    }

    /**
     * Proton event ids whose local version the user chose to keep ("Keep mine") and that have not been
     * uploaded yet. The re-time pass leaves them alone like held ones.
     */
    fun localWinsIds(c: Context, accountName: String): Set<String> =
        prefs(c).getStringSet(localWinsKey(accountName), emptySet()) ?: emptySet()

    /**
     * Decide what to do with an event that is locally dirty and newer on the server.
     *
     * @param baseline the fingerprint stored at the last successful sync, or null if none
     * @param current  the fingerprint of the local row right now
     */
    fun classify(baseline: String?, current: String?): Resolution = when {
        // Cannot tell: assume the worst and ask, rather than overwrite something irreplaceable.
        // Only reachable for events whose mapping predates the shared_fingerprint column and that
        // have not been written since, so this fades out on its own.
        baseline == null || current == null -> Resolution.CONFLICT
        // Shared fields untouched locally: the dirty flag came from a reminder or colour change,
        // which is this member's personal part and does not collide with anything the server did.
        baseline == current -> Resolution.PERSONAL_ONLY
        else -> Resolution.CONFLICT
    }

    enum class Resolution {
        /** Apply the server's version, keep DIRTY set so the personal part still uploads. */
        PERSONAL_ONLY,

        /** Hold the event, write nothing, ask the user. */
        CONFLICT
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
