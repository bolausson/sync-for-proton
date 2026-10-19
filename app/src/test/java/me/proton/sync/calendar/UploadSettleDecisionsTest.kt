package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload's two settle decisions.
 *
 * Both fail silently when they are wrong, so these tests exist to make the failure loud. The
 * response shapes below are the real ones: an events/sync body is a Map the gson converter hands
 * back untyped, so every nested lookup can miss and every number arrives as a Double.
 */
class UploadSettleDecisionsTest {

    private fun body(event: Map<String, Any>?): Map<String, Any> {
        val response = mutableMapOf<String, Any>("Code" to 1000.0)
        if (event != null) response["Event"] = event
        return mapOf("Responses" to listOf(mapOf("Response" to response)))
    }

    // ---- serverLastEditTime ----

    @Test
    fun `reads the server's stamp from a real response shape`() {
        // The value observed on the S23 on 2026-09-21.
        assertEquals(1789996039L, serverLastEditTime(body(mapOf("ID" to "abc", "LastEditTime" to 1789996039.0))))
    }

    @Test
    fun `a gson Double is not thrown away`() {
        // The whole point: gson deserialises a JSON number into Double when the target type is Any.
        // Reading it as Long only would have returned null for every real response, and every upload
        // would have gone on stamping the phone's clock while the log claimed the fix was live.
        val asDouble = serverLastEditTime(body(mapOf("LastEditTime" to 1789996039.0)))
        val asLong = serverLastEditTime(body(mapOf("LastEditTime" to 1789996039L)))
        val asInt = serverLastEditTime(body(mapOf("LastEditTime" to 1789996039)))
        assertEquals(1789996039L, asDouble)
        assertEquals(asDouble, asLong)
        assertEquals(asDouble, asInt)
    }

    @Test
    fun `null at every level rather than an exception`() {
        // Each of these is a shape the server could answer with, and any one of them throwing would
        // fail an upload that actually succeeded.
        assertNull(serverLastEditTime(null))
        assertNull(serverLastEditTime(emptyMap()))
        assertNull(serverLastEditTime(mapOf("Responses" to emptyList<Any>())))
        assertNull(serverLastEditTime(mapOf("Responses" to "not a list")))
        assertNull(serverLastEditTime(mapOf("Responses" to listOf(emptyMap<String, Any>()))))
        assertNull(serverLastEditTime(body(null)))
        assertNull(serverLastEditTime(body(mapOf("ID" to "abc"))))
    }

    @Test
    fun `a LastEditTime that is not a number is not forced into one`() {
        // A string here would mean the API changed shape. Falling back to the phone clock is the
        // documented behaviour, and it is better than parsing something we do not understand.
        assertNull(serverLastEditTime(body(mapOf("LastEditTime" to "1789996039"))))
        assertNull(serverLastEditTime(body(mapOf("LastEditTime" to true))))
    }

    @Test
    fun `only the first operation is read`() {
        // Every caller sends exactly one operation per request, so the first Response is the one that
        // describes the event just written. A second entry must not be able to change the answer.
        val two = mapOf(
            "Responses" to listOf(
                mapOf("Response" to mapOf("Event" to mapOf("LastEditTime" to 111.0))),
                mapOf("Response" to mapOf("Event" to mapOf("LastEditTime" to 999.0)))
            )
        )
        assertEquals(111L, serverLastEditTime(two))
    }

    // ---- mayClearDirtyAfterInvites ----

    @Test
    fun `an unchanged row clears`() {
        assertTrue(mayClearDirtyAfterInvites("fp", "fp"))
    }

    @Test
    fun `a row edited while the invitations went out stays dirty`() {
        // The whole reason the fingerprint is taken before the mail goes out: this edit used to be
        // discarded with no log, no retry and no sign to the user.
        assertFalse(mayClearDirtyAfterInvites("before", "after"))
    }

    @Test
    fun `an unreadable row clears rather than staying dirty for ever`() {
        // Failing the other way would re-upload the event on every sync, and mail its guests each
        // time, which is worse than losing the ability to detect one edit.
        assertTrue(mayClearDirtyAfterInvites(null, "after"))
        assertTrue(mayClearDirtyAfterInvites("before", null))
        assertTrue(mayClearDirtyAfterInvites(null, null))
    }
}
