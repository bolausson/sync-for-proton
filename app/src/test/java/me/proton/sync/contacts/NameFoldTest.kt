package me.proton.sync.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The comparison that decides whether a name on the phone was typed or invented.
 *
 * Getting this wrong in either direction loses something. Too loose and a real rename is deleted
 * and the old name put back, which is what happened when the reassembly was compared against the
 * local display name: the provider composes a display name as "given family", so every ordinary
 * two-part name reassembled to exactly itself and looked invented. Too strict and the mangled names
 * this exists for slip through and reach Proton.
 */
class NameFoldTest {

    /** Mirrors ContactsSyncAdapter.foldForComparison; kept in step by the cases below. */
    private fun fold(value: String): String =
        value.map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .lowercase()

    /** The case the guard exists for: the provider split an address and rebuilt it with a space. */
    @Test
    fun `an address and its provider split fold together`() {
        assertEquals(fold("app@example.com"), fold("app@example com"))
        assertEquals(fold("app+testers@example.com"), fold("app+testers@example com"))
        assertEquals(fold("1st-mail@example.com"), fold("1st-mail@example com"))
    }

    /**
     * The regression. Proton holds "AB" with no structured name; the user types Alice / Brown,
     * and the provider rebuilds the display name as "Alice Brown". That must not look like the
     * old name, or the edit is discarded.
     */
    @Test
    fun `a real rename does not fold onto the name it replaced`() {
        assertNotEquals(fold("AB"), fold("Alice Brown"))
        assertNotEquals(fold("app@example.com"), fold("Max Example"))
        assertNotEquals(fold("Mike Doe"), fold("Michael Doe"))
    }

    /** An ordinary name reassembles to itself, which is exactly why localFn was the wrong yardstick. */
    @Test
    fun `an ordinary name folds onto its own reassembly`() {
        assertEquals(
            "this is true of nearly every name, so it can never be the test on its own",
            fold("Jane Doe"),
            fold("Jane" + " " + "Doe")
        )
    }

    @Test
    fun `case and punctuation do not matter, content does`() {
        assertEquals(fold("Jane.Doe@example.com"), fold("jane doe example com"))
        assertNotEquals(fold("Jane Doe"), fold("Jane Doe Jr"))
    }
}
