package me.proton.sync.contacts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The other half of the address book.
 *
 * Proton contacts split into two populations: those the server holds a structured name for, decided
 * by [ContactsSyncAdapter.serverDisplayNameToRestore], and those it does not, decided here. Android
 * invents a given and family for the second group by splitting the display name, and uploading that
 * invention would write a surname onto a contact that never had one.
 *
 * The two functions are siblings answering the same question, and when the
 * case-blind fold was fixed in one of them it was left untouched in the other, so the bug the fix
 * was written for stayed live on this half for a whole release: a user correcting "jane doe" to
 * "Jane Doe" had the correction deleted and the lower-case name put back on every sync, with no
 * way to fix it from the phone at all. The sibling had tests. This one had none, which is how the
 * two came apart. It has them now.
 */
class InventedNameTest {

    /** given and family rejoined the way the provider composes a display name. */
    private fun derived(localFn: String, serverFn: String, rebuilt: String) =
        ContactsSyncAdapter.isAndroidsOwnDerivation(localFn, serverFn, rebuilt)

    // ---- Android's own handiwork: strip the invented name, keep Proton's --------------------

    /** Untouched: the display name still is exactly what Proton has. */
    @Test
    fun `an unchanged name is the provider's own`() {
        assertTrue(derived("app@example.com", "app@example.com", "app@example com"))
    }

    /**
     * The case this exists for. The provider splits "app@example.com" into given "app@example"
     * and family "com", then rebuilds the display name from the pieces and loses the dot.
     */
    @Test
    fun `a name the provider re-spaced is the provider's own`() {
        assertTrue(derived("app@example com", "app@example.com", "app@example com"))
    }

    @Test
    fun `a dotted address is the provider's own`() {
        assertTrue(derived("john q public", "john.q.public", "john q public"))
    }

    // ---- and the half that must travel to Proton --------------------------------------------

    /**
     * The regression this guard was added for. Correcting the capitalisation folds onto the old
     * name, so without the case clause it read as a derivation and was reverted forever.
     */
    @Test
    fun `a capitalisation fix is not the provider's own`() {
        assertFalse(derived("Jane Doe", "jane doe", "Jane Doe"))
        assertFalse(derived("JANE DOE", "jane doe", "JANE DOE"))
        assertFalse(derived("App@Example.com", "app@example.com", "App@Example.com"))
    }

    /** Typing a real name onto one of Proton's nameless contacts must reach the server. */
    @Test
    fun `a name somebody typed is not the provider's own`() {
        assertFalse(derived("John Q Public", "forms@example.com", "John Q Public"))
        assertFalse(derived("Max Mustermann", "max@example.com", "Max Mustermann"))
    }

    /** A contact whose parts the provider never filled has nothing that could be a derivation. */
    @Test
    fun `an empty reassembly is not the provider's own`() {
        assertFalse(derived("Something Else", "app@example.com", ""))
    }

    /**
     * Case AND punctuation both differing is a typed correction too. It is the weaker half of the
     * guard: the fold ignores punctuation, so this leans entirely on the case clause.
     */
    @Test
    fun `a correction that also fixes punctuation is not the provider's own`() {
        assertFalse(derived("App@Example Com", "app@example.com", "App@Example Com"))
    }

    /**
     * But an exact match wins before any of that. A contact whose display name equals the server's
     * verbatim is untouched no matter what the parts say.
     */
    @Test
    fun `an exact match is the provider's own whatever the parts hold`() {
        assertTrue(derived("app@example.com", "app@example.com", "completely unrelated"))
    }
}
