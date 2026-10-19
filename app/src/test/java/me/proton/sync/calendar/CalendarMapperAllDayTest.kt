package me.proton.sync.calendar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * Regression tests for [CalendarMapper.normalizeToUtcMidnight] (v1.6.3 fix).
 *
 * An all-day event downloaded from Proton (`DTSTART;VALUE=DATE:20260315`) must map to
 * midnight UTC of 2026-03-15 no matter which timezone the device is in. The previous
 * implementation derived the date by formatting in the device timezone, which shifted
 * all remotely-authored all-day events one day earlier on devices west of UTC
 * (seen in the field, while the same events were correct on Proton web).
 */
class CalendarMapperAllDayTest {

    private lateinit var originalTz: TimeZone

    @Before
    fun saveTz() {
        originalTz = TimeZone.getDefault()
    }

    @After
    fun restoreTz() {
        TimeZone.setDefault(originalTz)
    }

    private val expectedUtcMidnight = Instant.parse("2026-03-15T00:00:00Z").toEpochMilli()

    private fun normalizeIn(tzId: String): Long {
        TimeZone.setDefault(TimeZone.getTimeZone(tzId))
        // Same parse path as a downloaded DTSTART;VALUE=DATE:20260315
        val icalDate = net.fortuna.ical4j.model.Date("20260315")
        return CalendarMapper.normalizeToUtcMidnight(icalDate)
    }

    @Test
    fun allDay_utcDevice() {
        assertEquals(expectedUtcMidnight, normalizeIn("UTC"))
    }

    @Test
    fun allDay_eastOfUtc_berlin() {
        assertEquals(expectedUtcMidnight, normalizeIn("Europe/Berlin"))
    }

    @Test
    fun allDay_westOfUtc_newYork() {
        // The reported bug: this returned 2026-03-14T00:00Z before the fix
        assertEquals(expectedUtcMidnight, normalizeIn("America/New_York"))
    }

    @Test
    fun allDay_extremeWest_midway() {
        assertEquals(expectedUtcMidnight, normalizeIn("Pacific/Midway")) // UTC-11
    }

    @Test
    fun allDay_extremeEast_kiritimati() {
        assertEquals(expectedUtcMidnight, normalizeIn("Pacific/Kiritimati")) // UTC+14
    }

    /**
     * The same pin on the 1.11.5 download path, which reads the DATE from the raw text with java.time
     * (DownloadTimes) and no longer goes through ical4j's Date. The device zone is both the JVM default and
     * the floating zone handed in, so neither can leak into an all-day date.
     */
    private fun downloadIn(tzId: String): EventTimeColumns {
        TimeZone.setDefault(TimeZone.getTimeZone(tzId))
        val ics = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:a", "DTSTART;VALUE=DATE:20260315",
            "EXDATE;VALUE=DATE:20260322", "RRULE:FREQ=WEEKLY", "RECURRENCE-ID;VALUE=DATE:20260315", "END:VEVENT", "END:VCALENDAR"
        ).joinToString("\r\n")
        return DownloadTimes.compute(RawEventTimes.scan(ics)!!, ZoneRulesLookup.PHONE, java.time.ZoneId.of(tzId))
    }

    @Test
    fun allDay_downloadPath_everyDeviceZone() {
        for (tz in listOf("UTC", "Europe/Berlin", "America/New_York", "Pacific/Midway", "Pacific/Kiritimati")) {
            val t = downloadIn(tz)
            assertEquals(tz, expectedUtcMidnight, t.dtStart)
            assertEquals(tz, expectedUtcMidnight, t.originalInstanceTime)
            assertEquals(tz, listOf(Instant.parse("2026-03-22T00:00:00Z").toEpochMilli()), t.exdateInstants)
            assertEquals(tz, "P86400S", t.duration)
        }
    }

    @Test
    fun fallback_plainJavaDate_readInUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        // Defensive branch: a plain java.util.Date holding the UTC-midnight epoch
        val plain = java.util.Date(expectedUtcMidnight)
        assertEquals(expectedUtcMidnight, CalendarMapper.normalizeToUtcMidnight(plain))
    }
}
