package me.proton.sync.calendar

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Proton's verdicts on events/sync operations and what the upload does with them (SyncOpVerdicts.kt).
 *
 * Bodies are read the way Retrofit hands them over: Gson into a plain Map, so every number is a Double.
 */
class SyncOpVerdictsTest {

    private val gson = Gson()

    private fun body(json: String): Map<*, *>? = gson.fromJson(json, Map::class.java)

    // ---- firstSyncOpResult ----

    @Test
    fun `gson reads the codes as doubles`() {
        val parsed = body("""{"Code":1001,"Responses":[{"Index":0,"Response":{"Code":1000}}]}""")!!
        assertTrue(parsed["Code"] is Double)
    }

    @Test
    fun `code and error come from the operation and not from the request`() {
        val result = firstSyncOpResult(
            body("""{"Code":1001,"Responses":[{"Index":0,"Response":{"Code":2011,"Error":"This calendar does not allow editing events"}}]}""")
        )
        assertEquals(SyncOpResult(2011, "This calendar does not allow editing events"), result)
    }

    @Test
    fun `the entry with Index 0 wins over an earlier one`() {
        val result = firstSyncOpResult(
            body("""{"Code":1001,"Responses":[{"Index":1,"Response":{"Code":2001,"Error":"Invalid"}},{"Index":0,"Response":{"Code":1000}}]}""")
        )
        assertEquals(SyncOpResult(1000, null), result)
    }

    @Test
    fun `without an Index 0 the first entry is used`() {
        assertEquals(
            SyncOpResult(2501, "Event does not exist"),
            firstSyncOpResult(body("""{"Responses":[{"Response":{"Code":2501,"Error":"Event does not exist"}},{"Response":{"Code":1000}}]}"""))
        )
        assertEquals(
            SyncOpResult(2026, "Insufficient permissions"),
            firstSyncOpResult(
                body("""{"Responses":[{"Index":1,"Response":{"Code":2026,"Error":"Insufficient permissions"}},{"Index":2,"Response":{"Code":1000}}]}""")
            )
        )
    }

    @Test
    fun `a blank error is no error`() {
        assertEquals(SyncOpResult(1000, null), firstSyncOpResult(body("""{"Responses":[{"Index":0,"Response":{"Code":1000,"Error":"  "}}]}""")))
        assertEquals(SyncOpResult(1000, null), firstSyncOpResult(body("""{"Responses":[{"Index":0,"Response":{"Code":1000,"Error":""}}]}""")))
    }

    @Test
    fun `a body without an operation response gives no verdict`() {
        val none = SyncOpResult(null, null)
        assertEquals(none, firstSyncOpResult(null))
        assertEquals(none, firstSyncOpResult(body("{}")))
        assertEquals(none, firstSyncOpResult(body("""{"Code":1000}""")))
        assertEquals(none, firstSyncOpResult(body("""{"Responses":[]}""")))
        assertEquals(none, firstSyncOpResult(body("""{"Responses":"none"}""")))
        assertEquals(none, firstSyncOpResult(body("""{"Responses":[{"Index":0}]}""")))
        assertEquals(none, firstSyncOpResult(body("""{"Responses":[{"Index":0,"Response":null}]}""")))
        assertEquals(none, firstSyncOpResult(body("""{"Responses":["x"]}""")))
    }

    @Test
    fun `a response without a code gives only the error`() {
        assertEquals(
            SyncOpResult(null, "Something went wrong"),
            firstSyncOpResult(body("""{"Responses":[{"Index":0,"Response":{"Error":"Something went wrong"}}]}"""))
        )
    }

    // ---- deleteOpVerdict ----

    @Test
    fun `success codes and no code at all mean deleted`() {
        assertEquals(DeleteOpVerdict.DELETED, deleteOpVerdict(null))
        assertEquals(DeleteOpVerdict.DELETED, deleteOpVerdict(1000))
        assertEquals(DeleteOpVerdict.DELETED, deleteOpVerdict(1001))
    }

    @Test
    fun `not found means already gone`() {
        assertEquals(DeleteOpVerdict.ALREADY_GONE, deleteOpVerdict(2501))
    }

    @Test
    fun `the two permission codes are a refusal`() {
        assertEquals(DeleteOpVerdict.REFUSED, deleteOpVerdict(2011))
        assertEquals(DeleteOpVerdict.REFUSED, deleteOpVerdict(2026))
    }

    @Test
    fun `every other code is a rejection`() {
        for (code in listOf(2001, 20110, 2500, 2502, 2000, 0, 500)) {
            assertEquals("code $code", DeleteOpVerdict.REJECTED, deleteOpVerdict(code))
        }
    }

    // ---- cancelledRowAction ----

    private val rowExdate = "20261005T090000"

    @Test
    fun `a finished upload clears the row whatever the columns say`() {
        assertEquals(CancelledRowAction.CLEAR_DIRTY, cancelledRowAction(UploadOutcome.DONE, rowExdate, null))
        assertEquals(CancelledRowAction.CLEAR_DIRTY, cancelledRowAction(UploadOutcome.DONE, null, null))
        assertEquals(CancelledRowAction.CLEAR_DIRTY, cancelledRowAction(UploadOutcome.DONE, rowExdate, "20261012T090000"))
    }

    @Test
    fun `a retry or an unknown outcome keeps the row pending`() {
        assertEquals(CancelledRowAction.KEEP_PENDING, cancelledRowAction(UploadOutcome.RETRY, rowExdate, null))
        assertEquals(CancelledRowAction.KEEP_PENDING, cancelledRowAction(UploadOutcome.RETRY, rowExdate, rowExdate))
        assertEquals(CancelledRowAction.KEEP_PENDING, cancelledRowAction(null, rowExdate, null))
        assertEquals(CancelledRowAction.KEEP_PENDING, cancelledRowAction(null, null, null))
    }

    @Test
    fun `a given up upload restores the occurrence Proton still has`() {
        assertEquals(CancelledRowAction.RESTORE, cancelledRowAction(UploadOutcome.GAVE_UP, rowExdate, null))
        assertEquals(CancelledRowAction.RESTORE, cancelledRowAction(UploadOutcome.GAVE_UP, rowExdate, ""))
        assertEquals(CancelledRowAction.RESTORE, cancelledRowAction(UploadOutcome.GAVE_UP, rowExdate, "20261012T090000,20261019T090000"))
        // Only a whole value counts, not a date inside a longer one.
        assertEquals(CancelledRowAction.RESTORE, cancelledRowAction(UploadOutcome.GAVE_UP, "20261005", "20261005T090000"))
    }

    /** The download writes the parent's EXDATE column from Proton's EXDATEs: Proton excluded it too. */
    @Test
    fun `a given up upload clears the row when the parent already excludes it`() {
        assertEquals(
            CancelledRowAction.CLEAR_DIRTY,
            cancelledRowAction(UploadOutcome.GAVE_UP, rowExdate, "20261001T090000, 20261005T090000 ,20261005T090000")
        )
        assertEquals(CancelledRowAction.CLEAR_DIRTY, cancelledRowAction(UploadOutcome.GAVE_UP, rowExdate, rowExdate))
    }

    @Test
    fun `a given up upload leaves an unreadable row alone`() {
        assertEquals(CancelledRowAction.CLEAR_DIRTY, cancelledRowAction(UploadOutcome.GAVE_UP, null, null))
        assertEquals(CancelledRowAction.CLEAR_DIRTY, cancelledRowAction(UploadOutcome.GAVE_UP, null, "20261012T090000"))
    }

    // ---- exdateValueFor ----

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `an all-day series gets a date`() {
        assertEquals("20261005", exdateValueFor(millis("2026-10-05T00:00:00Z"), true, "Europe/Berlin"))
        assertEquals("20261005", exdateValueFor(millis("2026-10-05T00:00:00Z"), true, "UTC"))
    }

    @Test
    fun `a series in a zone gets its local time without Z`() {
        assertEquals("20261005T090000", exdateValueFor(millis("2026-10-05T07:00:00Z"), false, "Europe/Berlin"))
        assertEquals("20261207T090000", exdateValueFor(millis("2026-12-07T08:00:00Z"), false, "Europe/Berlin"))
        assertEquals("20261005T090000", exdateValueFor(millis("2026-10-05T13:00:00Z"), false, "America/New_York"))
    }

    @Test
    fun `a UTC series gets a UTC time with Z`() {
        assertEquals("20261005T070000Z", exdateValueFor(millis("2026-10-05T07:00:00Z"), false, "UTC"))
    }

    // ---- an operation that failed without naming a code ----

    @Test
    fun `an error without a code is a rejection, not a silent success`() {
        assertEquals(DeleteOpVerdict.REJECTED, deleteOpVerdict(null, "Invalid event data"))
        assertEquals(DeleteOpVerdict.DELETED, deleteOpVerdict(null, null))
        assertEquals(DeleteOpVerdict.DELETED, deleteOpVerdict(null, "   "))
        // A code always decides on its own, whatever text came with it.
        assertEquals(DeleteOpVerdict.DELETED, deleteOpVerdict(1000, "odd but successful"))
        assertEquals(DeleteOpVerdict.ALREADY_GONE, deleteOpVerdict(2501, "Event does not exist"))
    }

    // ---- a send Proton refused because it cannot read the draft yet ----

    @Test
    fun `a send refused because the draft is not readable yet is asked again`() {
        val real = """{"Code":2001,"Error":"Invalid or missing message signature, please sign out and back in again","Details":{}}"""
        assertTrue(isDraftNotReadable(400, real))
        // Spacing in the JSON must not matter.
        assertTrue(isDraftNotReadable(400, """{"Code" : 2001, "Error":"Invalid or missing MESSAGE SIGNATURE"}"""))
    }

    @Test
    fun `any other refusal is taken at its word`() {
        // Another 2001, about something we really did send wrong: rebuilding is needed, not another attempt.
        assertFalse(isDraftNotReadable(400, """{"Code":2001,"Error":"Invalid package address"}"""))
        // The right text under a different code, and the right code under a different status.
        assertFalse(isDraftNotReadable(400, """{"Code":2026,"Error":"Invalid or missing message signature"}"""))
        assertFalse(isDraftNotReadable(422, """{"Code":2001,"Error":"Invalid or missing message signature"}"""))
        assertFalse(isDraftNotReadable(400, null))
        assertFalse(isDraftNotReadable(400, "no body"))
        // 20010 must not pass for 2001.
        assertFalse(isDraftNotReadable(400, """{"Code":20010,"Error":"Invalid or missing message signature"}"""))
    }

}
