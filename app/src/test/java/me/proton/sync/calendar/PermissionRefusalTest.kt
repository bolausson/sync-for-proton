package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refusal is final; everything else is worth retrying.
 *
 * Proton answers a write to a calendar the user cannot edit with HTTP 400 and Code 2026
 * ("insufficient permissions") or 2011 ("this calendar does not allow editing"). The upload path
 * treated that like any other error and left the row dirty, so it was retried on every sync for
 * ever. In the field the refusal surfaced only as repeated, unexplained errors because nothing
 * said which event, which calendar, or why.
 *
 * The opposite mistake costs more: giving up on a transient failure means a real edit never
 * reaches Proton. So the test below is mostly about what must STILL be retried.
 */
class PermissionRefusalTest {

    /** The rule the upload path applies to a failed response, on the production predicate. */
    private fun isFinalRefusal(httpCode: Int, body: String): Boolean =
        httpCode == 400 && (hasErrorCode(body, 2026) || hasErrorCode(body, 2011))

    @Test
    fun `a permissions refusal is final`() {
        assertTrue(isFinalRefusal(400, """{"Code":2026,"Error":"You do not have sufficient permissions to perform this action"}"""))
        assertTrue(isFinalRefusal(400, """{"Code":2011,"Error":"This calendar does not allow editing events"}"""))
    }

    /** Transient and unrelated failures must keep their retry, or a real edit is lost. */
    @Test
    fun `everything else is still retried`() {
        assertFalse("a server error is transient", isFinalRefusal(500, """{"Code":2026}"""))
        assertFalse("rate limiting is transient", isFinalRefusal(429, """{"Code":2026}"""))
        assertFalse("a different 400 is not a refusal", isFinalRefusal(400, """{"Code":2000,"Error":"Time window is too big"}"""))
        assertFalse("an empty body is not a refusal", isFinalRefusal(400, "no body"))
        assertFalse("auth failure must not be swallowed", isFinalRefusal(401, """{"Code":401}"""))
    }

    /** The code must match as a whole, not as a substring of a longer number. */
    @Test
    fun `a longer code containing 2026 is not a refusal`() {
        assertFalse(isFinalRefusal(400, """{"Code":20261,"Error":"something else"}"""))
    }

    /**
     * The three values a real Proton account actually returned on 2026-08-29, decoded with
     * Proton's own CALENDAR_PERMISSIONS enum (WRITE = 16, READ = 32, AVAILABILITY = 64).
     *
     * This is the test that would have caught the wrong constant: with WRITE tested as bit 4,
     * 112 - a share the user can genuinely edit - came out read-only, and the phone's calendar
     * app stopped letting them add events to it.
     */
    @Test
    fun `observed permission values decide writability`() {
        assertTrue("127 is an owned calendar", writableByPermissions(127))
        assertTrue("112 = WRITE|READ|AVAILABILITY, an editable share", writableByPermissions(112))
        assertFalse("96 = READ|AVAILABILITY, a view-only share", writableByPermissions(96))
    }

    /** Bit 4 is ADMIN, not WRITE. A plain member who may write is not an admin. */
    @Test
    fun `admin is not what grants write`() {
        assertFalse("4 alone is ADMIN and carries no write", writableByPermissions(4))
        assertTrue("16 alone is WRITE", writableByPermissions(16))
    }

    /**
     * Absent permissions must NOT be read as read-only. Wrongly locking a user out of their own
     * calendar is worse than the retry loop this change set out to stop.
     */
    @Test
    fun `an absent permission set stays writable`() {
        assertTrue("an empty member list is not evidence of read-only", writableByPermissions(null))
        assertTrue("Permissions is a non-null Int, so an absent field arrives as 0", writableByPermissions(0))
    }

    // ---- the two wordings the user actually reads ----

    /**
     * A create and an update must not read alike. The two verbs are the only thing that tells a user whether
     * their new event never reached Proton or their edit did not, and nothing pinned them before.
     */
    @Test
    fun `each upload path keeps its own wording`() {
        val body = """{"Code":2000,"Error":"Server busy"}"""
        assertEquals(
            "Could not save an event to Proton: HTTP 500 (Code 2000: Server busy)",
            uploadSwallowedFailure("save an event to Proton", 500, body)
        )
        assertEquals(
            "Could not update an event on Proton: HTTP 500 (Code 2000: Server busy)",
            uploadSwallowedFailure("update an event on Proton", 500, body)
        )
    }

    /** The text is persisted and logged, so a body that is not Proton's JSON (a proxy page) is left out. */
    @Test
    fun `a body that is not Proton's JSON is left out`() {
        val out = uploadSwallowedFailure("save an event to Proton", 502, "x".repeat(400))
        assertEquals("Could not save an event to Proton: HTTP 502", out)
    }

    /** A Proton message longer than a notification can carry is cut, not dropped. */
    @Test
    fun `a long Proton error is cut`() {
        val body = """{"Code":2000,"Error":"${"y".repeat(400)}"}"""
        val out = uploadSwallowedFailure("save an event to Proton", 500, body)
        assertEquals("Could not save an event to Proton: HTTP 500 (Code 2000: " + "y".repeat(200) + "…)", out)
    }

    /**
     * The refusal reason: Proton's own message wins, the calendar is named when known, and an unparseable
     * body must never show the user raw JSON.
     */
    @Test
    fun `a refusal names the reason and the calendar`() {
        val refused =
            """{"Code":2026,"Error":"You do not have sufficient permissions to perform this action"}"""
        assertEquals(
            "You do not have sufficient permissions to perform this action (calendar: Work)",
            refusedUploadReason(refused, "Work", "guests")
        )
        assertEquals(
            "You do not have sufficient permissions to perform this action",
            refusedUploadReason(refused, null, "guests")
        )
        assertEquals(
            "Proton does not allow writing to this calendar (calendar: Work)",
            refusedUploadReason("no body", "Work", "guests")
        )
    }

    /** A guest-list refusal arrives as 2011 and needs its own explanation, not the calendar wording. */
    @Test
    fun `a guest list refusal uses the guest wording`() {
        val body = """{"Code":2011,"Error":"You cannot modify the attendee list of this event"}"""
        assertEquals("guests", refusedUploadReason(body, "Work", "guests"))
    }}
