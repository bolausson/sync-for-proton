package me.proton.sync.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Locale

/**
 * The backup's own timezone definitions are written with ASCII digits whatever the phone's language. With the default
 * locale, an Arabic, Persian, Hindi or Thai phone wrote its own digits into DTSTART and TZOFFSETFROM, the definition did
 * not parse, and the backup fell back to ical4j's bundled zones, which are an hour out for Asuncion from 2025.
 */
class IcsSerializerLocaleTest {

    private val saved = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(saved)

    @Test
    fun `the platform zone definition is the same whatever the phone's language`() {
        Locale.setDefault(Locale.ROOT)
        val expected = IcsSerializer.platformDefinition("America/Asuncion")?.toString()
        assertNotNull(expected)
        // Thai digits with the Gregorian calendar: the desktop JDK gives a Thai locale the Buddhist calendar, and ical4j's
        // own date text then counts years from 543 BC; Android's java.util.Calendar is always Gregorian.
        for (tag in listOf("ar-EG", "fa-IR", "hi-IN-u-nu-deva", "th-TH-u-ca-gregory-nu-thai")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            assertEquals(tag, expected, IcsSerializer.platformDefinition("America/Asuncion")?.toString())
        }
    }
}
