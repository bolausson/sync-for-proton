package me.proton.sync.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "Update time zones online": every gate between a tzurl.org answer and an event time, and the client that
 * makes the request. No test reaches the real tzurl.org: the fetch is a fake, and the client is pointed at a
 * loopback server.
 */
class OnlineTimeZoneRulesTest {

    private fun fixture(tzid: String) = javaClass.getResource("/tzurl/${tzid.replace('/', '_')}.ics")!!.readText()

    private class Source(
        phoneVersion: String? = "2021a",
        canFetch: () -> Boolean = { true },
        answer: (String) -> String
    ) {
        val requests = CopyOnWriteArrayList<String>()
        val seen = CopyOnWriteArrayList<String>()
        val rules = OnlineTimeZoneRules(
            fetch = { requests.add(it); answer(it) },
            phoneVersion = { phoneVersion },
            phoneRules = { ZoneId.of(it).rules },
            canFetch = canFetch,
            onVersionSeen = { seen.add(it) }
        )
    }

    @Test
    fun `newer than the phone's data - used, fetched once per zone per run`() {
        val s = Source { fixture(it) }
        assertNotNull(s.rules.rulesFor("America/Vancouver"))
        assertNotNull(s.rules.rulesFor("America/Vancouver"))
        assertNotNull(s.rules.rulesFor("Africa/Cairo"))
        assertEquals(listOf("America/Vancouver", "Africa/Cairo"), s.requests.toList())
        assertEquals(listOf("2026d", "2026d"), s.seen.toList())
        assertEquals("online 2026d", s.rules.snapshot()["America/Vancouver"])
    }

    @Test
    fun `not newer than the phone's data - not used, and nothing more is fetched in this run`() {
        for (phone in listOf("2026d", "2026e", "2027a")) {
            val s = Source(phoneVersion = phone) { fixture(it) }
            assertNull(phone, s.rules.rulesFor("America/Vancouver"))
            assertNull(phone, s.rules.rulesFor("Africa/Cairo"))
            assertEquals(phone, listOf("America/Vancouver"), s.requests.toList())
            assertNotNull(s.rules.stopReason)
        }
    }

    @Test
    fun `an unknown phone version cannot be compared - not used`() {
        assertNull(Source(phoneVersion = null) { fixture(it) }.rules.rulesFor("America/Vancouver"))
        assertNull(Source(phoneVersion = "garbage") { fixture(it) }.rules.rulesFor("America/Vancouver"))
    }

    @Test
    fun `a failed fetch falls back, counts as transient, and is not retried in this run`() {
        val s = Source { throw IOException("unreachable") }
        assertNull(s.rules.rulesFor("America/Vancouver"))
        assertNull(s.rules.rulesFor("America/Vancouver"))
        assertEquals(1, s.requests.size)
        assertTrue(s.rules.isTransientlyUnavailable("America/Vancouver"))
    }

    @Test
    fun `an unusable answer falls back and is not transient`() {
        val s = Source { "<html><body>502 Bad Gateway</body></html>" }
        assertNull(s.rules.rulesFor("America/Vancouver"))
        assertFalse(s.rules.isTransientlyUnavailable("America/Vancouver"))
        // Truncated mid-file: rejected too.
        val t = Source { fixture(it).let { body -> body.substring(0, body.length / 2) } }
        assertNull(t.rules.rulesFor("America/Vancouver"))
    }

    @Test
    fun `an answer for another zone is refused unless the phone has both identical since 1970`() {
        val s = Source { fixture("Europe/Berlin") }
        assertNull(s.rules.rulesFor("Asia/Tokyo"))
        assertTrue(s.rules.snapshot()["Asia/Tokyo"]!!.contains("Europe/Berlin"))
        // Neighbours that share 2000 to 2015, which the settled-history check alone let through: Paris has its
        // own DST history in the 1970s, Toronto missed the US's 1974 year-round DST.
        assertNull(Source { fixture("Europe/Berlin") }.rules.rulesFor("Europe/Paris"))
        assertNull(Source { fixture("America/New_York") }.rules.rulesFor("America/Toronto"))
        // A link's answer is its target's rules, identical since 1970 by IANA's own rule: Oslo is served as Berlin.
        val oslo = Source { fixture("Europe/Oslo") }
        assertNotNull(oslo.rules.rulesFor("Europe/Oslo"))
        assertEquals("online 2026d (as Europe/Berlin)", oslo.rules.snapshot()["Europe/Oslo"])
    }

    @Test
    fun `identical since 1970 - links are, neighbours are not`() {
        fun rules(id: String) = ZoneId.of(id).rules
        assertTrue(OnlineTimeZoneRules.identicalSince1970(rules("Europe/Oslo"), rules("Europe/Berlin")))
        assertTrue(OnlineTimeZoneRules.identicalSince1970(rules("Asia/Calcutta"), rules("Asia/Kolkata")))
        assertFalse(OnlineTimeZoneRules.identicalSince1970(rules("America/Vancouver"), rules("America/Los_Angeles")))
        assertFalse(OnlineTimeZoneRules.identicalSince1970(rules("America/Edmonton"), rules("America/Denver")))
        assertFalse(OnlineTimeZoneRules.identicalSince1970(rules("Europe/Paris"), rules("Europe/Berlin")))
    }

    @Test
    fun `an answer that leaves the phone's offsets by hours after 2015 is refused`() {
        // Tokyo's own history, then +14:00 from 2030: valid syntax, a clean chain, and 2000 to 2015 agree.
        val garbage = listOf(
            "BEGIN:VCALENDAR", "PRODID:-//tzurl.org//NONSGML Olson 2026d//EN", "VERSION:2.0",
            "BEGIN:VTIMEZONE", "TZID:Asia/Tokyo",
            "BEGIN:STANDARD", "TZOFFSETFROM:+091859", "TZOFFSETTO:+0900", "TZNAME:JST", "DTSTART:18880101T000000", "END:STANDARD",
            "BEGIN:STANDARD", "TZOFFSETFROM:+0900", "TZOFFSETTO:+1400", "TZNAME:XXX", "DTSTART:20300101T000000", "END:STANDARD",
            "END:VTIMEZONE", "END:VCALENDAR"
        ).joinToString("\r\n")
        val s = Source { garbage }
        assertNull(s.rules.rulesFor("Asia/Tokyo"))
        assertTrue(s.rules.snapshot()["Asia/Tokyo"]!!, s.rules.snapshot()["Asia/Tokyo"]!!.contains("more than 3 hours"))
        assertFalse(s.rules.isTransientlyUnavailable("Asia/Tokyo"))
        // The bound itself: an hour or two is a real rule change, more is not.
        val plusOne = java.time.zone.ZoneRules.of(java.time.ZoneOffset.ofHours(1))
        assertNull(OnlineTimeZoneRules.futureDisagreement(java.time.zone.ZoneRules.of(java.time.ZoneOffset.ofHours(3)), plusOne))
        assertEquals("2016-01-15", OnlineTimeZoneRules.futureDisagreement(java.time.zone.ZoneRules.of(java.time.ZoneOffset.ofHours(5)), plusOne))
    }

    @Test
    fun `rules from an older release than the newest seen stay in use but are reported stale`() {
        val s = Source { tzid -> fixture(tzid).let { if (tzid == "Africa/Cairo") it.replace("Olson 2026d", "Olson 2026e") else it } }
        val snapshot = RuleSnapshot(true, s.rules)
        assertNotNull(s.rules.rulesFor("America/Vancouver"))
        assertFalse(s.rules.hasStaleEntries())
        assertNotNull(s.rules.rulesFor("Africa/Cairo"))
        assertNotNull("still newer than the phone's, so still used", s.rules.rulesFor("America/Vancouver"))
        assertTrue(s.rules.hasStaleEntries())
        assertTrue(snapshot.usedStaleOnlineRules())
        assertFalse(RuleSnapshot(false, s.rules).usedStaleOnlineRules())
    }

    @Test
    fun `the snapshot records the zones whose one-off times fell back to the phone's rules`() {
        val zone = ZoneId.of("America/Vancouver")
        val december = LocalDateTime.of(2026, 12, 1, 10, 0)
        val failing = RuleSnapshot(true, OnlineTimeZoneRules(fetch = { throw IOException("down") },
            phoneVersion = { "2021a" }, phoneRules = { ZoneId.of(it).rules }))
        assertTrue(failing.rulesFor(zone, false, december) === zone.rules)
        assertTrue("a series never uses the online rules: no fallback", failing.transientFallbacks.isEmpty())
        assertTrue(failing.rulesFor(zone, true, december) === zone.rules)
        assertEquals(setOf("America/Vancouver"), failing.transientFallbacks)
        // A deliberate fallback (the phone is not older) is not one.
        val upToDate = RuleSnapshot(true, OnlineTimeZoneRules(fetch = { fixture(it) },
            phoneVersion = { "2026e" }, phoneRules = { ZoneId.of(it).rules }))
        upToDate.rulesFor(zone, true, december)
        assertTrue(upToDate.transientFallbacks.isEmpty())
    }

    @Test
    fun `an answer shaped to expand without end is refused at once, never a crash`() {
        val blowup = listOf(
            "BEGIN:VCALENDAR", "PRODID:-//tzurl.org//NONSGML Olson 2026d//EN", "BEGIN:VTIMEZONE", "TZID:Europe/Berlin",
            "BEGIN:STANDARD", "TZOFFSETFROM:+0100", "TZOFFSETTO:+0100", "DTSTART:00010101T000000",
            "RRULE:FREQ=YEARLY;BYMONTH=1,2,3,4,5,6,7,8,9,10,11,12;BYMONTHDAY=" + (1..31).joinToString(","),
            "END:STANDARD", "END:VTIMEZONE", "END:VCALENDAR"
        ).joinToString("\r\n")
        val started = System.nanoTime()
        val s = Source { blowup }
        assertNull(s.rules.rulesFor("Europe/Berlin"))
        assertTrue(s.rules.snapshot()["Europe/Berlin"]!!.contains("unusable answer"))
        assertTrue("refused in ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 2_000_000_000L)
    }

    @Test
    fun `only zone names on Proton's list are ever requested`() {
        val s = Source { fixture(it) }
        for (tzid in listOf("Mars/Olympus", "UTC", "Etc/UTC", "Europe/Kyiv", "US/Pacific", "../../etc/passwd",
            "America/Vancouver?x=1", "America/Vancouver/../Edmonton", "")) {
            assertNull(tzid, s.rules.rulesFor(tzid))
        }
        assertEquals(emptyList<String>(), s.requests.toList())
        assertTrue(OnlineTimeZoneRules.isFetchable("America/Argentina/Buenos_Aires"))
        assertTrue(OnlineTimeZoneRules.isFetchable("America/Port-au-Prince"))
    }

    @Test
    fun `where no request may be made, the cache answers alone and nothing is remembered`() {
        var allowed = false
        val s = Source(canFetch = { allowed }) { fixture(it) }
        assertNull(s.rules.rulesFor("America/Vancouver"))
        assertTrue(s.rules.isTransientlyUnavailable("America/Vancouver"))
        assertEquals(emptyList<String>(), s.requests.toList())
        allowed = true
        assertNotNull(s.rules.rulesFor("America/Vancouver"))
        assertEquals(listOf("America/Vancouver"), s.requests.toList())
    }

    @Test
    fun `tz releases compare by year, then letter`() {
        assertTrue(OnlineTimeZoneRules.compareVersions("2026d", "2026c")!! > 0)
        assertTrue(OnlineTimeZoneRules.compareVersions("2027a", "2026z")!! > 0)
        assertTrue(OnlineTimeZoneRules.compareVersions("2026aa", "2026z")!! > 0)
        assertEquals(0, OnlineTimeZoneRules.compareVersions("2026d", "2026d"))
        assertTrue(OnlineTimeZoneRules.compareVersions("2021a", "2026c")!! < 0)
        assertNull(OnlineTimeZoneRules.compareVersions("2026", "2026c"))
        assertNull(OnlineTimeZoneRules.compareVersions("unknown", "2026c"))
    }

    @Test
    fun `the rule snapshot - OFF and series use the phone's rules, ON one-off times the online ones`() {
        val zone = ZoneId.of("America/Vancouver")
        val december = LocalDateTime.of(2026, 12, 1, 10, 0)
        val online = OnlineTimeZoneRules(fetch = { fixture(it) }, phoneVersion = { "2021a" }, phoneRules = { ZoneId.of(it).rules })
        val on = RuleSnapshot(true, online)
        val onlineRules = on.rulesFor(zone, true, december)
        assertTrue(onlineRules !== zone.rules)
        assertEquals(java.time.ZoneOffset.ofHours(-7), onlineRules.getOffset(LocalTimes.toInstant(december, onlineRules)))
        assertTrue(on.rulesFor(zone, false, december) === zone.rules)
        assertTrue(RuleSnapshot(false, online).rulesFor(zone, true, december) === zone.rules)
        assertTrue(RuleSnapshot.PHONE_ONLY.rulesFor(zone, true, december) === zone.rules)
        // Outside 1900 to 2099 the online rules (expanded through 2100 only) are never used.
        assertTrue(on.rulesFor(zone, true, LocalDateTime.of(2100, 1, 1, 10, 0)) === zone.rules)
        assertTrue(on.rulesFor(zone, true, LocalDateTime.of(1899, 1, 1, 10, 0)) === zone.rules)
        assertFalse(on.onlineUnavailable(zone, 2026))
    }

    @Test
    fun `the rule snapshot reports a failed online zone as unavailable, a deliberate fallback not`() {
        val zone = ZoneId.of("America/Vancouver")
        val failing = RuleSnapshot(true, OnlineTimeZoneRules(fetch = { throw IOException("down") },
            phoneVersion = { "2021a" }, phoneRules = { ZoneId.of(it).rules }))
        assertTrue(failing.onlineUnavailable(zone, 2026))
        val upToDate = RuleSnapshot(true, OnlineTimeZoneRules(fetch = { fixture(it) },
            phoneVersion = { "2026e" }, phoneRules = { ZoneId.of(it).rules }))
        assertFalse(upToDate.onlineUnavailable(zone, 2026))
        assertFalse(RuleSnapshot.PHONE_ONLY.onlineUnavailable(zone, 2026))
    }

    // ---- the client: what www.tzurl.org receives ----

    /** A one-shot HTTP server on the loopback interface: records each request, answers with [response]. */
    private class Loopback(private val response: (String) -> ByteArray) : AutoCloseable {
        val requests = CopyOnWriteArrayList<List<String>>()
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        private val thread = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                socket.use { s ->
                    s.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                    val lines = ArrayList<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        lines.add(line)
                    }
                    requests.add(lines)
                    // The client may hang up mid-answer (the size cap): that is the point, not an error here.
                    try {
                        s.getOutputStream().apply { write(response(lines.firstOrNull() ?: "")); flush() }
                    } catch (_: IOException) {
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            server.close()
            thread.join(2_000)
        }
    }

    private fun ok(body: ByteArray) =
        "HTTP/1.1 200 OK\r\nContent-Type: text/calendar\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.ISO_8859_1) + body

    @Test
    fun `the client sends a generic request - no phone model, no credentials, no cookies`() {
        val body = fixture("Europe/Berlin").toByteArray(Charsets.UTF_8)
        Loopback { ok(body) }.use { server ->
            val text = TzurlClient.fetchUrl("http://127.0.0.1:${server.port}/zoneinfo/Europe/Berlin")
            assertEquals(String(body, Charsets.UTF_8), text)
            val request = server.requests.single()
            assertEquals("GET /zoneinfo/Europe/Berlin HTTP/1.1", request.first())
            val headers = request.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            assertTrue(headers["user-agent"], headers["user-agent"]!!.startsWith("okhttp/"))
            assertEquals("text/calendar", headers["accept"])
            for (name in listOf("authorization", "cookie", "x-pm-uid", "x-pm-appversion")) assertNull(name, headers[name])
            assertEquals(TzurlClient.BASE_URL, "https://www.tzurl.org/zoneinfo/")
        }
    }

    @Test
    fun `a redirect is an error, never followed`() {
        Loopback { "HTTP/1.1 301 Moved Permanently\r\nLocation: /elsewhere\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray() }.use { server ->
            try {
                TzurlClient.fetchUrl("http://127.0.0.1:${server.port}/zoneinfo/Europe/Berlin")
                fail("a redirect must not be followed")
            } catch (e: IOException) {
                assertEquals("HTTP 301", e.message)
            }
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `an answer over 64 KiB is refused`() {
        Loopback { ok(ByteArray(70 * 1024) { 'A'.code.toByte() }) }.use { server ->
            try {
                TzurlClient.fetchUrl("http://127.0.0.1:${server.port}/zoneinfo/Europe/Berlin")
                fail("an oversized answer must be refused")
            } catch (e: IOException) {
                assertTrue(e.message, e.message!!.contains("larger than"))
            }
        }
    }
}
