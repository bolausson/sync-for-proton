package me.proton.sync.crypto

import ezvcard.Ezvcard
import me.proton.sync.api.models.ContactCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which UID survives when a contact's cards are merged.
 *
 * The signed card's UID is the one Proton stores. Proton Mail for Android 4.x also wrote a second,
 * random UID into the encrypted card, and the merge keeps the last UID it meets, so that one used to
 * win, and a name repair or a backup then carried a UID Proton does not have. The merge tests run the
 * real [ContactDecryptor.mergeVCards]; the one line in decryptAndMergeCards that applies the rule needs
 * a KeyManager, so it was checked on a device instead.
 */
class ContactDecryptorUidTest {

    private fun card(vararg lines: String) =
        (listOf("BEGIN:VCARD", "VERSION:4.0") + lines + "END:VCARD").joinToString("\r\n")

    private fun uidLines(parts: List<String>) = parts.flatMap { it.lines() }.filter { it.startsWith("UID") }

    @Test
    fun `an encrypted card's uid gives way to the signed card's`() {
        val parts = ContactDecryptor.withoutStrayUids(
            listOf(
                ContactCard.TYPE_SIGNED to card("FN:Test Contact", "UID:urn:uuid:signed"),
                ContactCard.TYPE_ENCRYPTED_AND_SIGNED to card("UID:urn:uuid:encrypted", "TEL:12345"),
            )
        )
        assertEquals(listOf("UID:urn:uuid:signed"), uidLines(parts))
        assertEquals(listOf("BEGIN:VCARD", "VERSION:4.0", "TEL:12345", "END:VCARD"), parts[1].lines())
    }

    @Test
    fun `an encrypted card keeps its uid when no other card has one`() {
        val parts = ContactDecryptor.withoutStrayUids(
            listOf(
                ContactCard.TYPE_SIGNED to card("FN:Test Contact"),
                ContactCard.TYPE_ENCRYPTED to card("UID:urn:uuid:only-here"),
            )
        )
        assertEquals(listOf("UID:urn:uuid:only-here"), uidLines(parts))
    }

    @Test
    fun `a folded uid line goes with its continuation`() {
        val parts = ContactDecryptor.withoutStrayUids(
            listOf(
                ContactCard.TYPE_SIGNED to card("UID:urn:uuid:signed"),
                ContactCard.TYPE_ENCRYPTED to card("UID:urn:uuid:folded", " -continued", "NOTE:kept"),
            )
        )
        assertEquals(listOf("BEGIN:VCARD", "VERSION:4.0", "NOTE:kept", "END:VCARD"), parts[1].lines())
    }

    private fun mergedUid(vararg parts: Pair<Int, String>): String =
        Ezvcard.parse(ContactDecryptor.mergeVCards(ContactDecryptor.withoutStrayUids(parts.toList()))).first().uid.value

    @Test
    fun `the merged card carries the signed card's uid in either card order`() {
        val clear = ContactCard.TYPE_CLEARTEXT to card("CATEGORIES:Friends")
        val signed = ContactCard.TYPE_SIGNED to card("FN:Test Contact", "UID:urn:uuid:signed")
        val encrypted = ContactCard.TYPE_ENCRYPTED_AND_SIGNED to card("UID:urn:uuid:encrypted", "TEL:12345")
        // Proton Mail for Android 4.x sent cleartext, signed, encrypted, so its stray UID came last.
        assertEquals("urn:uuid:signed", mergedUid(clear, signed, encrypted))
        assertEquals("urn:uuid:signed", mergedUid(encrypted, signed, clear))
        val merged = ContactDecryptor.mergeVCards(ContactDecryptor.withoutStrayUids(listOf(clear, signed, encrypted)))
        assertTrue(merged, merged.lines().contains("TEL:12345"))
    }

    @Test
    fun `without the rule the merge keeps the encrypted card's uid`() {
        val merged = ContactDecryptor.mergeVCards(listOf(card("UID:urn:uuid:signed"), card("UID:urn:uuid:encrypted")))
        assertEquals("urn:uuid:encrypted", Ezvcard.parse(merged).first().uid.value)
    }

    @Test
    fun `an encrypted card without a uid is passed through as it came, line endings included`() {
        val lf = listOf("BEGIN:VCARD", "VERSION:4.0", "TEL:12345", "END:VCARD").joinToString("\n")
        val parts = ContactDecryptor.withoutStrayUids(
            listOf(ContactCard.TYPE_SIGNED to card("UID:urn:uuid:signed"), ContactCard.TYPE_ENCRYPTED to lf)
        )
        assertEquals(lf, parts[1])
    }

    @Test
    fun `a blank signed uid does not cost the encrypted card its uid`() {
        val parts = ContactDecryptor.withoutStrayUids(
            listOf(ContactCard.TYPE_SIGNED to card("UID:"), ContactCard.TYPE_ENCRYPTED to card("UID:urn:uuid:real"))
        )
        assertEquals(listOf("UID:", "UID:urn:uuid:real"), uidLines(parts))
    }

    @Test
    fun `a value that mentions UID is not a uid, and cards without one are left alone`() {
        val signed = card("UID:urn:uuid:signed")
        val encrypted = card("NOTE:UID:not-a-uid", "X-NOTE;UID=1:kept")
        val parts = ContactDecryptor.withoutStrayUids(
            listOf(ContactCard.TYPE_SIGNED to signed, ContactCard.TYPE_ENCRYPTED_AND_SIGNED to encrypted)
        )
        assertEquals(listOf(signed, encrypted), parts)
    }
}
