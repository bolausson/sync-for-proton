package me.proton.sync.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether Proton's display name survives a local edit, or is replaced by Android's shortening.
 *
 * Android derives a contact's display name from the given and family names alone. Anything else in
 * Proton's FN, a title, a middle name, a parenthetical, a second surname, never reaches the phone
 * and is stored nowhere on it. Since the upload takes the FN straight from that display name, the
 * first local edit of any kind replaces Proton's full name with the short one, and nothing puts it
 * back. Every case below is an invented example of a shape seen on real address books, where
 * contacts had already diverged this way and were one edit away from losing the difference for good.
 *
 * The opposite error costs just as much: reverting a name somebody actually typed. So the tests
 * come in pairs, and the second half is the half that must keep working.
 */
class ServerDisplayNameTest {

    /** Mirrors what the sync passes: the server's record, then what the phone now holds. */
    private fun decide(
        serverFn: String,
        serverGiven: String?,
        serverFamily: String?,
        localFn: String,
        given: String?,
        family: String?,
        extraParts: List<String> = emptyList(),
        serverHasN: Int = 1,
    ): String? = ContactsSyncAdapter.serverDisplayNameToRestore(
        serverHasN = serverHasN,
        serverFn = serverFn,
        serverGiven = serverGiven,
        serverFamily = serverFamily,
        localFn = localFn,
        given = given,
        family = family,
        extraParts = extraParts,
    )

    @Test
    fun `a title and a middle name are kept`() {
        assertEquals(
            "Dr. Max Erik Mustermann",
            decide("Dr. Max Erik Mustermann", "Max", "Mustermann", "Max Mustermann", "Max", "Mustermann")
        )
    }

    @Test
    fun `a second surname is kept`() {
        assertEquals(
            "Ana Lopez Garcia",
            decide("Ana Lopez Garcia", "Ana", "Garcia", "Ana Garcia", "Ana", "Garcia")
        )
    }

    @Test
    fun `a parenthetical is kept`() {
        assertEquals(
            "Erika (Mama von Paul) Musterfrau",
            decide(
                "Erika (Mama von Paul) Musterfrau", "Erika", "Musterfrau",
                "Erika Musterfrau", "Erika", "Musterfrau"
            )
        )
    }

    /** Half a name is the worst of these: the phone shows one word and Proton holds the rest. */
    @Test
    fun `a contact reduced to one word is kept`() {
        assertEquals(
            "Otto Normalverbraucher",
            decide("Otto Normalverbraucher", "", "Normalverbraucher", "Normalverbraucher", "", "Normalverbraucher")
        )
        assertEquals(
            "WEG Muster-Allee 13",
            decide("WEG Muster-Allee 13", "WEG", "", "WEG", "WEG", "")
        )
    }

    /** Even whitespace is Proton's to decide; the phone cannot represent a double space. */
    @Test
    fun `punctuation-only differences are kept`() {
        assertEquals(
            "John  Doe",
            decide("John  Doe", "John", "Doe", "John Doe", "John", "Doe")
        )
    }

    // ---- and now the half that must still travel -------------------------------------------

    /** The user renamed the contact. Given and family moved, so this is not a derivation. */
    @Test
    fun `a real rename is not reverted`() {
        assertNull(decide("Dr. Max Erik Mustermann", "Max", "Mustermann", "Alice Brown", "Alice", "Brown"))
    }

    /** Clearing the surname on the phone looks like a shortening but is a deliberate edit. */
    @Test
    fun `deleting the surname is not reverted`() {
        assertNull(decide("Richard Roe", "Richard", "Roe", "Richard", "Richard", ""))
    }

    /** A title typed on the phone is content Proton's FN does not have; it must not be lost. */
    @Test
    fun `a title the user added is not reverted`() {
        assertNull(
            decide(
                "Max Mustermann", "Max", "Mustermann", "Dr. Max Mustermann", "Max", "Mustermann",
                extraParts = listOf("Dr.")
            )
        )
    }

    /** A display name that is not the plain reassembly was typed by somebody. */
    @Test
    fun `a custom display name is not reverted`() {
        assertNull(decide("Max Mustermann", "Max", "Mustermann", "Max from work", "Max", "Mustermann"))
    }

    @Test
    fun `an unchanged name needs no restoring`() {
        assertNull(decide("Max Mustermann", "Max", "Mustermann", "Max Mustermann", "Max", "Mustermann"))
    }

    // ---- and the cases where the answer is simply not known ---------------------------------

    /**
     * Rows written before the columns existed. Sending what the phone has is recoverable; guessing
     * at Proton's name and overwriting with it is not.
     */
    @Test
    fun `an unbackfilled row is left alone`() {
        assertNull(decide("Dr. Max Erik Mustermann", null, null, "Max Mustermann", "Max", "Mustermann"))
    }

    /**
     * The provider does not always derive the display name from the parts. Handed a name it cannot
     * parse it keeps the whole string and re-spaces the punctuation, leaving given and family
     * alone. Folded, the two names are identical, which is not something a person edits a name
     * into, so Proton's punctuation wins.
     */
    @Test
    fun `a name the provider only re-spaced is kept`() {
        assertEquals(
            "Jana.beispiel@example.org",
            decide(
                "Jana.beispiel@example.org", "Jana", "org",
                "Jana beispiel@example. org", "Jana", "org"
            )
        )
    }

    /** But identical letters in a different order is a reordering, and reorderings are edits. */
    @Test
    fun `reordering the words is not reverted`() {
        assertNull(decide("Max Mustermann", "Max", "Mustermann", "Mustermann Max", "Mustermann", "Max"))
    }

    /**
     * The fold path exists for punctuation the provider re-spaces, not for case. Correcting
     * "jane doe" to "Jane Doe" folds onto the old name, and reverting it made the name
     * impossible to fix from the phone: every sync put the lower-case version back.
     */
    @Test
    fun `a capitalisation fix is not reverted`() {
        assertNull(decide("jane doe", "jane", "doe", "Jane Doe", "jane", "doe"))
        assertNull(decide("JANE DOE", "JANE", "DOE", "Jane Doe", "JANE", "DOE"))
    }

    /**
     * The residual half of the same bug, found when the sibling stripInventedName was finally given
     * tests of its own. The case guard used to be "differs by more than case", which only rescued a
     * correction when the punctuation happened to be identical. Correct the capitalisation of a name
     * the provider had ALSO re-spaced and it folded onto the old one, was read as pure punctuation,
     * and was reverted on every sync. The fold now preserves case, so the two causes stay apart.
     */
    @Test
    fun `a capitalisation fix on a re-spaced name is not reverted`() {
        assertNull(
            decide(
                "jana.beispiel@example.org", "jana", "org",
                "Jana Beispiel@Example. Org", "jana", "org"
            )
        )
    }

    /** But punctuation the provider moved is still Proton's to keep. */
    @Test
    fun `punctuation the provider re-spaced is still restored`() {
        assertEquals(
            "Jana.beispiel@example.org",
            decide(
                "Jana.beispiel@example.org", "Jana", "org",
                "Jana beispiel@example. org", "Jana", "org"
            )
        )
    }

    /** Contacts Proton has no structured name for belong to stripInventedName, not here. */
    @Test
    fun `a contact with no server structured name is left alone`() {
        assertNull(
            decide(
                "app@example.com", null, null, "app@example com", "app@example", "com",
                serverHasN = 0
            )
        )
    }
}
