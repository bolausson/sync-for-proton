package me.proton.sync.calendar

import me.proton.sync.NetworkCause

/**
 * What becomes of a calendar whose keys or passphrases could not be read this run (CalendarSyncAdapter.prepareCalendarJob).
 * Pure, so it is tested without a device.
 *
 * A personal calendar is skipped for the run: nothing of it is downloaded, nothing on the phone is overwritten, and the
 * run fails with the reason. An external one (subscribed or holiday, Type other than 0) used to go on without its keys
 * whatever the cause, because one that has none is expected. When the network was the cause, the events that run
 * downloaded were rebuilt from their unencrypted part alone (buildExternalEventICal) and replaced what the phone showed,
 * and the reason was recorded without a failure counted, so the run reported success. Since 2.2.0 the next sync waits
 * for the user while Android's automatic sync is off, so that could last (owner, 2026-11-23).
 *
 * A personal calendar skipped because Proton refused its keys records Proton's status ([reason]): recorded only as
 * "could not be opened", a 503 or a 429 read as an unexpected error, and it came first in the run, ahead of every later
 * failure's status. The note an external calendar without keys records is no failure (SyncUtils.recordSwallowedNote).
 *
 * Proton answering an external calendar's keys with a passing failure (a timeout, too many requests, a server error:
 * [isTransientStatus]) said no more about its keys than a network failure does, and the calendar still synced without
 * them and rebuilt its events from their unencrypted part. It is skipped for these statuses too (owner, 2026-11-23), and
 * for a 401 the token refresh did not cure, which says nothing about its keys either (owner, 2026-11-23): the
 * same statuses the contacts and the event deletions count as passing (ContactUploadRefusals.isTransientHttp).
 */
internal object CalendarKeysFailure {

    /**
     * Whether the calendar is skipped for this run, as a personal calendar whose keys could not be read always is: true
     * for a personal calendar ([external] false), and for an external one when the network is why [error] was thrown
     * (NetworkCause) or Proton answered with a passing failure ([isTransientStatus] of [refusedStatus]). An external
     * calendar whose keys Proton did not give for another reason, typically because it has none, still syncs from the
     * unencrypted part of its events, as before.
     */
    fun skipsCalendar(external: Boolean, error: Throwable): Boolean =
        !external || NetworkCause.of(error) != null || isTransientStatus(refusedStatus(error))

    /**
     * Whether Proton's status [httpCode], answering a calendar's keys or passphrases, is about the moment rather than the
     * calendar: a session the token refresh did not renew (401), a request timeout (408), too many requests (429) or a
     * server error (5xx), which a later sync, or signing in again, gets past. Any other status, the answer of a calendar
     * that has no keys among them, or none at all, is not.
     */
    fun isTransientStatus(httpCode: Int?): Boolean =
        httpCode != null && (httpCode == 401 || httpCode == 408 || httpCode == 429 || httpCode >= 500)

    /**
     * What fetchCalendarKeys and fetchCalendarPassphrases throw when Proton answered with a failure: its HTTP status
     * [httpCode], with the same message as before. Still an IOException, so every catch treats it as before.
     */
    class Refused(val httpCode: Int, message: String) : java.io.IOException(message)

    /** Proton's HTTP status when [error] is its refusal of a calendar's keys or passphrases ([Refused]), else null. */
    fun refusedStatus(error: Throwable?): Int? = (error as? Refused)?.httpCode

    /**
     * The reason a calendar that is skipped because Proton refused its keys or passphrases ([Refused]) gives its run, with
     * the status [httpCode], which picks the text the failure shows (the server error, too many requests, an expired
     * session, a rejected request: SyncNotificationHelper.friendlyMessageRes). The calendar is named by the start of its
     * id, never by its name: a name can hold the very words the text is read by, and the text also goes to the log.
     */
    fun reason(calendarId: String, httpCode: Int): String =
        "The keys of calendar '${calendarId.take(8)}' could not be read, so none of its events were downloaded: HTTP $httpCode"
}
