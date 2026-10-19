package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SyncUtils] utility functions.
 *
 * Note: ensureKeysUnlocked and withSyncLock require Android framework or mocks
 * and are tested via manual integration testing. These tests cover pure-logic functions.
 */
class SyncUtilsTest {

    // ---- isAuthFailure ----

    @Test
    fun isAuthFailure_detects401() {
        assertTrue(SyncUtils.isAuthFailure(Exception("HTTP 401 Unauthorized")))
    }

    @Test
    fun isAuthFailure_detects403() {
        assertTrue(SyncUtils.isAuthFailure(Exception("HTTP 403 Forbidden")))
    }

    @Test
    fun isAuthFailure_detectsUnauthorized() {
        assertTrue(SyncUtils.isAuthFailure(Exception("Unauthorized")))
    }

    @Test
    fun isAuthFailure_detectsInvalidToken() {
        assertTrue(SyncUtils.isAuthFailure(Exception("Invalid access token")))
    }

    @Test
    fun isAuthFailure_detectsExpiredToken() {
        assertTrue(SyncUtils.isAuthFailure(Exception("Expired access token")))
    }

    @Test
    fun isAuthFailure_returnsFalseForNetworkErrors() {
        assertFalse(SyncUtils.isAuthFailure(Exception("Connection timed out")))
        assertFalse(SyncUtils.isAuthFailure(Exception("Unable to resolve host")))
        assertFalse(SyncUtils.isAuthFailure(Exception("HTTP 500 Internal Server Error")))
    }

    @Test
    fun isAuthFailure_returnsFalseForNullMessage() {
        assertFalse(SyncUtils.isAuthFailure(Exception()))
    }

    // ---- runUninterruptible ----

    @Test
    fun runUninterruptible_returnsNullOnSuccess() {
        val result = SyncUtils.runUninterruptible {
            // no-op
        }
        assertNull(result)
    }

    @Test
    fun runUninterruptible_returnsExceptionOnFailure() {
        val result = SyncUtils.runUninterruptible {
            throw IllegalStateException("test error")
        }
        assertNotNull(result)
        assertTrue(result is IllegalStateException)
        assertTrue(result!!.message == "test error")
    }

    // ---- swallowedFailures map ----

    @Test
    fun swallowedFailures_recordAndConsumeRoundTrip() {
        SyncUtils.recordSwallowedFailure("contacts", "alice@example.com", "boom")
        assertEquals("boom", SyncUtils.consumeSwallowedFailure("contacts", "alice@example.com"))
        // Map drained — second consume returns null
        assertNull(SyncUtils.consumeSwallowedFailure("contacts", "alice@example.com"))
    }

    @Test
    fun swallowedFailures_putIfAbsentSemantics() {
        SyncUtils.recordSwallowedFailure("contacts", "bob@example.com", "first")
        SyncUtils.recordSwallowedFailure("contacts", "bob@example.com", "second")
        // First message wins — putIfAbsent
        assertEquals("first", SyncUtils.consumeSwallowedFailure("contacts", "bob@example.com"))
    }

    @Test
    fun swallowedFailures_keyIsolationByType() {
        // Calendar and contacts failures for the same account stay separate
        SyncUtils.recordSwallowedFailure("calendar", "carol@example.com", "calendar-msg")
        SyncUtils.recordSwallowedFailure("contacts", "carol@example.com", "contacts-msg")
        assertEquals("calendar-msg", SyncUtils.consumeSwallowedFailure("calendar", "carol@example.com"))
        assertEquals("contacts-msg", SyncUtils.consumeSwallowedFailure("contacts", "carol@example.com"))
    }

    @Test
    fun swallowedFailures_keyIsolationByAccount() {
        // Two accounts on the same sync type stay separate
        SyncUtils.recordSwallowedFailure("contacts", "dave@example.com", "dave-msg")
        SyncUtils.recordSwallowedFailure("contacts", "eve@example.com", "eve-msg")
        assertEquals("dave-msg", SyncUtils.consumeSwallowedFailure("contacts", "dave@example.com"))
        assertEquals("eve-msg", SyncUtils.consumeSwallowedFailure("contacts", "eve@example.com"))
    }

    @Test
    fun swallowedFailures_nullAndBlankIgnored() {
        SyncUtils.recordSwallowedFailure("contacts", "frank@example.com", null)
        SyncUtils.recordSwallowedFailure("contacts", "frank@example.com", "")
        SyncUtils.recordSwallowedFailure("contacts", "frank@example.com", "   ")
        assertNull(SyncUtils.consumeSwallowedFailure("contacts", "frank@example.com"))
    }

    @Test
    fun swallowedFailures_unknownAccountReturnsNull() {
        assertNull(SyncUtils.consumeSwallowedFailure("contacts", "never-seen@example.com"))
    }
}
