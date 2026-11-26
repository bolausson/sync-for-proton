package me.proton.sync.contacts

import ezvcard.property.Birthday
import ezvcard.util.PartialDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * A birthday read from a vCard is written with ASCII digits whatever the phone's language. With the default locale,
 * String.format wrote the locale's own digits (Arabic, Persian, Devanagari), which neither Android's contacts nor
 * Proton read as a date.
 */
class ContactDateFormatTest {

    private val saved = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(saved)

    @Test
    fun `a birthday keeps ASCII digits whatever the phone's language`() {
        for (tag in listOf("ar-EG", "fa-IR", "hi-IN-u-nu-deva", "de-DE")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            assertEquals(tag, "1990-02-15", ContactsMapper.parseDateProperty(Birthday(LocalDate.of(1990, 2, 15))))
            assertEquals(tag, "--02-15", ContactsMapper.parseDateProperty(Birthday(PartialDate.builder().month(2).date(15).build())))
            assertEquals(
                tag,
                "1990-02-15",
                ContactsMapper.parseDateProperty(Birthday(PartialDate.builder().year(1990).month(2).date(15).build())),
            )
            // Text in the basic form, as this app's own uploads store it, gets its dashes on the phone (2.2.1).
            assertEquals(tag, "1990-02-15", ContactsMapper.parseDateProperty(Birthday("19900215")))
            assertEquals(tag, "--02-15", ContactsMapper.parseDateProperty(Birthday("--0215")))
        }
    }

    @Test
    fun `a birthday stored as a UTC date-time keeps its date`() {
        // ez-vcard reads BDAY:19900215T000000Z as an Instant, which has no calendar date of its own: before 2.2.1 the
        // birthday was dropped on download. Since 2.2.1 it reaches the phone whenever the contact is downloaded.
        assertEquals("1990-02-15", ContactsMapper.parseDateProperty(Birthday(Instant.parse("1990-02-15T00:00:00Z"))))
        assertEquals("1990-02-15", ContactsMapper.parseDateProperty(Birthday(Instant.parse("1990-02-15T23:30:00Z"))))
    }
}
