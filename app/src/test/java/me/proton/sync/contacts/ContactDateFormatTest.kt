package me.proton.sync.contacts

import ezvcard.property.Birthday
import ezvcard.util.PartialDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
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
        }
    }
}
