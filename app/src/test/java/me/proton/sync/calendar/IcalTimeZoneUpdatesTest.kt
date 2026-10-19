package me.proton.sync.calendar

import android.content.SharedPreferences
import me.proton.sync.SyncPreferences
import me.proton.sync.backup.BackupSettings
import me.proton.sync.contacts.FakePrefs
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.TimeZoneRegistryImpl
import net.fortuna.ical4j.model.TimeZoneUpdater
import net.fortuna.ical4j.model.component.VEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ical4j's own time-zone updater is OFF for good since 1.11.5, whatever "Update time zones online" says: the
 * switch now chooses the rule source (TimeZoneRuleSource, OnlineTimeZoneRules), fetched by the app's own
 * client. Proven against ical4j itself, not by reading the property back.
 *
 * A loopback server stands in for www.tzurl.org. ical4j re-reads its scheme, host and port overrides on every
 * update attempt, so even if the updater were on it could only ever reach that server: nothing here can contact
 * the real tzurl.org. Each case parses an event whose DTSTART names a zone no other test uses, through
 * CalendarBuilder exactly as CalendarSyncAdapter.parseEventICal does, and first checks that the zone is not yet
 * in ical4j's process-wide cache: a cached zone is never fetched, so without that check a test could pass while
 * proving nothing.
 */
class IcalTimeZoneUpdatesTest {

    /** Records the path of every request and answers 404, as an unreachable tzurl.org effectively would. */
    private class Tripwire : AutoCloseable {
        val paths = CopyOnWriteArrayList<String>()
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        private val thread = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                socket.use { s ->
                    s.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                    val requestLine = reader.readLine() ?: return@use
                    while (reader.readLine()?.isNotEmpty() == true) { /* skip the headers */ }
                    paths += requestLine.split(' ').getOrElse(1) { requestLine }
                    s.getOutputStream().apply {
                        write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            server.close()
            thread.join(2_000)
        }
    }

    private lateinit var tripwire: Tripwire
    private val saved = HashMap<String, String?>()

    @Before
    fun setUp() {
        TOUCHED.forEach { saved[it] = System.getProperty(it) }
        tripwire = Tripwire()
        System.setProperty("net.fortuna.ical4j.timezone.update.scheme", "http")
        System.setProperty("net.fortuna.ical4j.timezone.update.host", "127.0.0.1")
        System.setProperty("net.fortuna.ical4j.timezone.update.port", tripwire.port.toString())
    }

    @After
    fun tearDown() {
        tripwire.close()
        // Put back exactly what was there: under Gradle that is "false" (app/build.gradle.kts).
        saved.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
    }

    @Test
    fun `disable writes exactly false and both timeouts`() {
        System.clearProperty(IcalTimeZoneUpdates.PROPERTY)
        System.clearProperty(IcalTimeZoneUpdates.CONNECT_TIMEOUT_PROPERTY)
        System.clearProperty(IcalTimeZoneUpdates.READ_TIMEOUT_PROPERTY)
        IcalTimeZoneUpdates.disable()
        assertEquals("false", System.getProperty(IcalTimeZoneUpdates.PROPERTY))
        assertFalse(TimeZoneUpdater().isEnabled)
        assertEquals("10000", System.getProperty(IcalTimeZoneUpdates.CONNECT_TIMEOUT_PROPERTY))
        assertEquals("10000", System.getProperty(IcalTimeZoneUpdates.READ_TIMEOUT_PROPERTY))
    }

    @Test
    fun `off - the zone resolves from the bundled data and no request is made`() {
        IcalTimeZoneUpdates.disable()
        val zone = "Pacific/Marquesas"
        assertNotLoaded(zone)
        assertEquals(zone, parseStartZone(zone))
        assertTrue("the zone really went through ical4j's loader", isLoaded(zone))
        assertEquals(emptyList<String>(), tripwire.paths.toList())
    }

    @Test
    fun `with the switch ON, ical4j still makes no request - the switch never reaches it`() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean(SyncPreferences.KEY_TIMEZONE_UPDATES, true).apply()
        assertTrue(TimeZoneRuleSource.isOnlineEnabled(prefs))
        // What the app does at start, whatever the switch says.
        IcalTimeZoneUpdates.disable()
        val zone = "Atlantic/Azores"
        assertNotLoaded(zone)
        assertEquals(zone, parseStartZone(zone))
        assertEquals(emptyList<String>(), tripwire.paths.toList())
        assertEquals("false", System.getProperty(IcalTimeZoneUpdates.PROPERTY))
    }

    @Test
    fun `the control - were the updater on, the same parse would ask the stand-in, so the cases above prove something`() {
        // Not something the app can do any more (nothing writes anything but "false"); set by hand here only to
        // show that the tripwire does see ical4j's request when there is one.
        System.setProperty(IcalTimeZoneUpdates.PROPERTY, "true")
        val zone = "Indian/Mauritius"
        assertNotLoaded(zone)
        parseStartZone(zone)
        assertEquals(listOf("/zoneinfo/$zone"), tripwire.paths.toList())
        IcalTimeZoneUpdates.disable()
    }

    @Test
    fun `only the exact lowercase false turns the updater off, which is all disable ever writes`() {
        System.setProperty(IcalTimeZoneUpdates.PROPERTY, "False")
        assertTrue("\"False\" leaves updates on", TimeZoneUpdater().isEnabled)
        System.clearProperty(IcalTimeZoneUpdates.PROPERTY)
        assertTrue("no value at all means on, so the property must never be cleared", TimeZoneUpdater().isEnabled)
        IcalTimeZoneUpdates.disable()
        assertFalse(TimeZoneUpdater().isEnabled)
    }

    @Test
    fun `the switch defaults to off and is read as stored`() {
        val prefs = FakePrefs()
        assertFalse(TimeZoneRuleSource.isOnlineEnabled(prefs))
        prefs.edit().putBoolean(SyncPreferences.KEY_TIMEZONE_UPDATES, true).apply()
        assertTrue(TimeZoneRuleSource.isOnlineEnabled(prefs))
        prefs.edit().putBoolean(SyncPreferences.KEY_TIMEZONE_UPDATES, false).apply()
        assertFalse(TimeZoneRuleSource.isOnlineEnabled(prefs))
    }

    @Test
    fun `the choice travels with the settings backup`() {
        assertTrue(BackupSettings.isExportable(SyncPreferences.KEY_TIMEZONE_UPDATES))
    }

    /** Like Android's SharedPreferencesImpl, whose getBoolean casts and throws on another type. */
    private class StrictPrefs(val fake: FakePrefs = FakePrefs()) : SharedPreferences by fake {
        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            val stored = fake.values[key] ?: return defValue
            return stored as Boolean
        }
    }

    @Test
    fun `a stored value of the wrong type reads as off instead of throwing`() {
        val prefs = StrictPrefs()
        prefs.fake.values[SyncPreferences.KEY_TIMEZONE_UPDATES] = "true"
        try {
            prefs.getBoolean(SyncPreferences.KEY_TIMEZONE_UPDATES, false)
            org.junit.Assert.fail("the fake must throw like Android, or this case proves nothing")
        } catch (_: ClassCastException) { }
        assertFalse(TimeZoneRuleSource.isOnlineEnabled(prefs))
        prefs.fake.values[SyncPreferences.KEY_TIMEZONE_UPDATES] = true
        assertTrue(TimeZoneRuleSource.isOnlineEnabled(prefs))
    }

    /** Parse an event the way the sync does and return the zone ical4j resolved its DTSTART to. */
    private fun parseStartZone(zone: String): String {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//t//EN\r\nBEGIN:VEVENT\r\nUID:tz-switch-test\r\n" +
            "DTSTAMP:20261004T000000Z\r\nDTSTART;TZID=$zone:20261105T100000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val event = CalendarBuilder().build(StringReader(ics)).getComponents<VEvent>("VEVENT").first()
        return event.startDate.timeZone.id
    }

    /** Whether ical4j has already loaded [zone] in this JVM (its static, never-evicted zone cache). */
    @Suppress("UNCHECKED_CAST")
    private fun isLoaded(zone: String): Boolean {
        val field = TimeZoneRegistryImpl::class.java.getDeclaredField("DEFAULT_TIMEZONES")
        field.isAccessible = true
        return (field.get(null) as Map<String, *>).containsKey(zone)
    }

    private fun assertNotLoaded(zone: String) = assertFalse(
        "$zone is already loaded in this JVM, so this case could not see a request either way. " +
            "Pick a zone no other test parses.",
        isLoaded(zone)
    )

    private companion object {
        val TOUCHED = listOf(
            IcalTimeZoneUpdates.PROPERTY,
            IcalTimeZoneUpdates.CONNECT_TIMEOUT_PROPERTY,
            IcalTimeZoneUpdates.READ_TIMEOUT_PROPERTY,
            "net.fortuna.ical4j.timezone.update.scheme",
            "net.fortuna.ical4j.timezone.update.host",
            "net.fortuna.ical4j.timezone.update.port",
        )
    }
}
