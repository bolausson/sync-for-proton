package me.proton.sync.contacts

import ezvcard.property.Anniversary
import ezvcard.property.Birthday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the phone spells a birthday or anniversary since 2.2.1 ([ContactDates]): the extended ISO forms every calendar
 * and birthday app reads, while Proton keeps the text it has.
 */
class ContactDatesTest {

    @Test
    fun `a basic full date gets its dashes`() {
        assertEquals("1990-02-15", ContactDates.toExtended("19900215"))
        assertEquals("2000-01-01", ContactDates.toExtended("20000101"))
        assertEquals("1992-02-29", ContactDates.toExtended("19920229"))
        assertEquals("0000-02-15", ContactDates.toExtended("00000215"))
    }

    @Test
    fun `a basic date without a year gets its dashes`() {
        assertEquals("--02-15", ContactDates.toExtended("--0215"))
        assertEquals("--02-29", ContactDates.toExtended("--0229"))
    }

    @Test
    fun `anything else is left alone`() {
        val arabicDigits = "19900215".map { (it - '0' + 0x0660).toChar() }.joinToString("")
        for (text in listOf(
            "1990-02-15", "--02-15", "", "19900230", "19901315", "19900229", "--0230", "--1315", "--0015",
            "1990/02/15", "1990-02", "1990", "---15", "0215", "1990021", "199002150", " 19900215", "19900215 ",
            "15.02.1990", "Feb 15", "19900215T120000", "19900215T000000Z", arabicDigits,
        )) {
            assertNull("'$text'", ContactDates.toExtended(text))
        }
    }

    @Test
    fun `the basic spelling of an extended date, for rules written against it`() {
        assertEquals("19900215", ContactDates.toBasic("1990-02-15"))
        assertEquals("--0215", ContactDates.toBasic("--02-15"))
        for (text in listOf("19900215", "--0215", "", "15.02.1990", "1990-2-15", " 1990-02-15")) {
            assertNull("'$text'", ContactDates.toBasic(text))
        }
        for (text in listOf("19900215", "--0215")) {
            assertEquals(text, ContactDates.toBasic(ContactDates.toExtended(text)!!))
        }
    }

    @Test
    fun `a second pass over its own result finds nothing`() {
        for (text in listOf("19900215", "20000101", "--0215", "19920229", "--0229")) {
            assertNull(text, ContactDates.toExtended(ContactDates.toExtended(text)!!))
        }
    }

    @Test
    fun `the download writes text dates in the extended spelling and other text verbatim`() {
        for (text in listOf("19900215", "--0215", "1990-02-15", "--02-15", "15.02.1990", "19900230", "")) {
            val expected = ContactDates.toExtended(text) ?: text
            assertEquals(text, expected, ContactsMapper.parseDateProperty(Birthday(text)))
            assertEquals(text, expected, ContactsMapper.parseDateProperty(Anniversary(text)))
        }
    }

    @Test
    fun `a re-spelled full date goes back to Proton as the text it has`() {
        // The upload's normaliser in ContactsSyncAdapter.buildVCardFromAndroid, kept in step by ContactDateRewriteTest:
        // strip '-' and '/', keep 8 characters, and use them when all are digits.
        fun upload(text: String): String {
            val normalized = text.replace("-", "").replace("/", "").take(8)
            return if (normalized.length == 8 && normalized.all { it.isDigit() }) normalized else text
        }
        for (text in listOf("19900215", "20000101", "19920229", "00000215")) {
            assertEquals(text, upload(ContactDates.toExtended(text)!!))
        }
        // A date without a year keeps its dashes on the way up, so only a real edit of that contact changes the text.
        assertEquals("--02-15", upload(ContactDates.toExtended("--0215")!!))
    }
}
