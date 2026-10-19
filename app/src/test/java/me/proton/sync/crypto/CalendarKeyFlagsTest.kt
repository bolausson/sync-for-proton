package me.proton.sync.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the calendar key flag decode against Proton's own definitions, calling the real production
 * function rather than a re-implementation of it.
 *
 * WebClients CalendarKeyFlags: INACTIVE = 0, ACTIVE = 1, PRIMARY = 2.
 * android-calendar 2.34.0 CalendarKeyEntity: "0: Inactive - 1: Active - 3: Active and primary",
 * isActiveAndPrimary = flags == 3.
 */
class CalendarKeyFlagsTest {

    @Test
    fun `an active primary key is the encryption key`() {
        assertTrue("3 = ACTIVE | PRIMARY", isPrimaryCalendarKey(3))
    }

    /**
     * The defect: bit value 1 is ACTIVE, not PRIMARY. Reading it as primary made every active key a
     * candidate, so after a key rotation new events were encrypted to whichever active key came last.
     */
    @Test
    fun `an active key that is not primary is not the encryption key`() {
        assertFalse("1 = ACTIVE only", isPrimaryCalendarKey(1))
    }

    @Test
    fun `an inactive key is never the encryption key`() {
        assertFalse("0 = INACTIVE", isPrimaryCalendarKey(0))
    }

    /** Cannot occur in practice; not treated as primary if it ever did. */
    @Test
    fun `primary without active is not the encryption key`() {
        assertFalse("2 = PRIMARY without ACTIVE", isPrimaryCalendarKey(2))
    }

    /** A further bit Proton might add later must not stop a genuine primary key from qualifying. */
    @Test
    fun `an unknown extra bit does not hide a primary key`() {
        assertTrue("7 = ACTIVE | PRIMARY | 4", isPrimaryCalendarKey(7))
    }
}
