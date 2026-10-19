package me.proton.sync.contacts

import ezvcard.VCard
import ezvcard.property.FormattedName
import ezvcard.property.StructuredName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The test that decides what gets rewritten on somebody's Proton account.
 *
 * A false positive here strips a name a person typed, so the rule is deliberately narrow: it fires
 * only when the display name is an email address and the first and last name put back together
 * spell exactly that address, which is the fingerprint of Android's contacts provider having split
 * it. Everything else is left alone, including cases that merely look similar.
 */
class ContactNameRepairTest {

    private fun card(fn: String?, given: String? = null, family: String? = null,
                     middle: String? = null): VCard {
        val v = VCard()
        if (fn != null) v.formattedName = FormattedName(fn)
        if (given != null || family != null || middle != null) {
            v.structuredName = StructuredName().apply {
                this.given = given
                this.family = family
                if (middle != null) additionalNames.add(middle)
            }
        }
        return v
    }

    /** The shape the provider leaves behind: a domain split off as a surname. */
    @Test
    fun `a surname split off an email address is recognised`() {
        val found = ContactNameRepair.inventedName("id", card("someone@example.com", "someone@example", "com"))
        assertNotNull(found)
        assertEquals("someone@example.com", found!!.displayName)
        assertEquals("com", found.family)
    }

    /** The provider also rebuilds the display name with a space, so that form counts too. */
    @Test
    fun `the space-joined form is recognised`() {
        assertNotNull(ContactNameRepair.inventedName("id", card("app@example com", "app@example", "com")))
    }

    @Test
    fun `a real person is never touched`() {
        assertNull(ContactNameRepair.inventedName("id", card("Jane Doe", "Jane", "Doe")))
    }

    /**
     * The one that matters most. A person whose display name happens to be an address but whose
     * name was typed by hand does not reassemble into it, so it survives.
     */
    @Test
    fun `a typed name on an email-shaped contact is left alone`() {
        assertNull(ContactNameRepair.inventedName("id", card("jane.d@example.com", "Jane", "Doe")))
    }

    @Test
    fun `a contact with no structured name has nothing to repair`() {
        assertNull(ContactNameRepair.inventedName("id", card("app@example.com")))
    }

    @Test
    fun `a display name that is not an address is ignored`() {
        assertNull(ContactNameRepair.inventedName("id", card("Acme Ltd", "Acme", "Ltd")))
    }

    /** A middle name is more than the provider's two-way split, so it is somebody's own doing. */
    @Test
    fun `a middle name disqualifies the match`() {
        assertNull(
            ContactNameRepair.inventedName("id", card("a@example.com", given = "a@example", family = "com", middle = "X"))
        )
    }

    /**
     * The recomposed form, which is what a phone actually holds after the provider has been at it.
     * The repair has to recognise this one or the contacts most in need of it are the ones it skips.
     */
    @Test
    fun `the recomposed display name is recognised as a split`() {
        assertNotNull(
            ContactNameRepair.inventedName("id", card("app@example com", "app@example", "com"))
        )
    }

    @Test
    fun `half a structured name is not the split`() {
        assertNull(ContactNameRepair.inventedName("id", card("a@example.com", given = "a@example")))
        assertNull(ContactNameRepair.inventedName("id", card("a@example.com", family = "com")))
    }
}
