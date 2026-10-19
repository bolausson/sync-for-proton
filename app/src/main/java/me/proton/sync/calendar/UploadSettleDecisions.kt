package me.proton.sync.calendar

/**
 * The two decisions an upload makes once the server has answered. Pure, so they can be tested
 * without a Context, a ContentProviderClient or android.util.Base64, none of which exist on the JVM.
 *
 * Both were written as private methods inside CalendarSyncAdapter and could not be reached by a
 * test at all. Both fail SILENTLY when they are wrong, which is the argument for pulling them out:
 * nothing crashes, nothing is logged by the code itself, and the damage only shows up later as an
 * edit that never went up or a server change that never came down.
 */

/**
 * The server's own `LastEditTime` for the event an `events/sync` response just wrote, or null when
 * the response did not carry one.
 *
 * The download decides an event is unchanged with `mapping.lastModified >= event.lastEditTime`. A
 * download stamps that field with the server's value, so an upload must too. Stamping it with the
 * PHONE's clock compared two different clocks: a phone running ahead of Proton skipped every
 * server-side change to an event it had uploaded, silently, for as long as the skew lasted, because
 * the local stamp was already newer than anything the server would report. A phone running behind
 * merely re-applied its own upload once, which is why only one direction of this was ever noticed.
 *
 * Returning null is the fallback to the phone clock, which reinstates exactly that bug, so the shape
 * of the response matters and is worth pinning down in tests. Device-verified on 2026-09-21 that
 * Proton does send it: `Stamped the mapping with the server's LastEditTime (1789996039)`.
 *
 * Deliberately tolerant about the NUMBER: gson hands back a Double for a JSON number when the target
 * is `Any`, so reading it as Long only would have thrown away every real response.
 */
@Suppress("UNCHECKED_CAST")
internal fun serverLastEditTime(responseBody: Map<String, Any>?): Long? {
    val responses = responseBody?.get("Responses") as? List<Map<String, Any>> ?: return null
    val eventResp = responses.firstOrNull()?.get("Response") as? Map<String, Any> ?: return null
    val event = eventResp["Event"] as? Map<String, Any> ?: return null
    return (event["LastEditTime"] as? Number)?.toLong()
}

/**
 * Whether the dirty flag may be cleared after an upload whose invitation emails have gone out.
 *
 * Sending takes seconds: a draft per recipient, an attachment upload, a send, and a settle between
 * them. An edit the user makes in that window sets DIRTY again, and clearing it unconditionally
 * threw that edit away with no log, no retry and nothing the user could see.
 *
 * [before] is the fingerprint taken before the mail went out and [now] the one taken after. They are
 * the collision guard's fingerprints, which cover only user-visible content columns, never
 * `_SYNC_ID` or `DIRTY`, so the app's own write-back after a create cannot trip this and pin a row
 * dirty for ever.
 *
 * A null on either side means the row could not be read, and then clearing is the safe answer: a row
 * left dirty for ever is re-uploaded on every sync, and mails its guests every time.
 */
internal fun mayClearDirtyAfterInvites(before: String?, now: String?): Boolean =
    before == null || now == null || before == now
