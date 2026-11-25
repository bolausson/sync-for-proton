package me.proton.sync.calendar

import kotlinx.coroutines.runBlocking
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.models.UpdateCalendarUserSettingsRequest
import me.proton.sync.calendar.DefaultCalendarChoices.SaveOutcome
import me.proton.sync.calendar.PrimaryCalendarChooser.Settings
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Default calendar row's settings requests through the real ProtonApiClient, Retrofit with its Gson converter, and
 * OkHttp, with an interceptor standing in for the network: what CalendarUserSettingsFetch.fetch makes of each answer to
 * `GET settings/calendar` (every failure built from an answer carries its HTTP status, which a 401 needs), and what
 * DefaultCalendarChoices.send makes of each answer to `PUT settings/calendar`, read once. Made-up ids and addresses only.
 */
class CalendarUserSettingsWireTest {

    private companion object {
        const val ACCOUNT = "user@example.test"
    }

    /** What would have gone out, as the stand-in network saw it. */
    private val sent: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    private fun api(answer: (Request) -> Response): ProtonApiClient {
        val wire = Interceptor { chain ->
            sent += chain.request()
            answer(chain.request())
        }
        return Retrofit.Builder()
            .baseUrl("https://mail-api.proton.me/")
            .client(OkHttpClient.Builder().addInterceptor(wire).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ProtonApiClient::class.java)
    }

    private fun reply(request: Request, code: Int, json: String?): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("answer")
        .body((json ?: "").toResponseBody("application/json".toMediaType()))
        .build()

    private fun fetch(code: Int, json: String?): Settings = runBlocking {
        CalendarUserSettingsFetch.fetch(api { reply(it, code, json) }, ACCOUNT)
    }

    private fun change(timeoutMs: Long = 10_000, answer: (Request) -> Response): SaveOutcome = runBlocking {
        val api = api(answer)
        DefaultCalendarChoices.send(timeoutMs, "cal-a") {
            api.updateCalendarUserSettings(ACCOUNT, UpdateCalendarUserSettingsRequest("cal-a"))
        }
    }

    private fun change(code: Int, json: String?): SaveOutcome = change { reply(it, code, json) }

    // ---- GET settings/calendar ----

    @Test
    fun `the settings read gives Proton's default, or why not with the status of the answer`() {
        assertEquals(Settings.Loaded("cal-a"), fetch(200, """{"Code":1000,"CalendarUserSettings":{"DefaultCalendarID":"cal-a","WeekStart":1}}"""))
        assertEquals(Settings.Loaded(null), fetch(200, """{"Code":1000,"CalendarUserSettings":{"DefaultCalendarID":null}}"""))
        assertEquals(Settings.Failed("HTTP 200, Code 2001", 200), fetch(200, """{"Code":2001}"""))
        assertEquals(Settings.Failed("HTTP 200, no CalendarUserSettings", 200), fetch(200, """{"Code":1000}"""))
        assertEquals(Settings.Failed("HTTP 204, empty body", 204), fetch(204, null))
        val request = sent.last()
        assertEquals("GET", request.method)
        assertTrue(request.url.toString(), request.url.encodedPath.endsWith("/settings/calendar"))
    }

    @Test
    fun `a 401 on the settings read asks for a new sign-in, any other error does not`() {
        val expired = fetch(401, """{"Code":10013,"Error":"Invalid access token"}""")
        assertTrue("$expired", expired is Settings.Failed)
        expired as Settings.Failed
        assertEquals(401, expired.httpStatus)
        assertEquals("HTTP 401 (Code 10013: Invalid access token)", expired.detail)
        assertTrue(DefaultCalendarChoices.needsSignIn(expired))

        for (code in listOf(403, 422, 429, 503)) {
            val failed = fetch(code, """{"Code":2028,"Error":"Busy"}""") as Settings.Failed
            assertEquals("$code", code, failed.httpStatus)
            assertFalse("$code", DefaultCalendarChoices.needsSignIn(failed))
        }
    }

    @Test
    fun `a settings read that threw or timed out carries no status`() {
        val thrown = runBlocking { CalendarUserSettingsFetch.fetch(api { throw UnknownHostException("no address") }, ACCOUNT) }
        assertEquals(Settings.Failed("UnknownHostException"), thrown)

        val gate = CountDownLatch(1)
        try {
            val slow = runBlocking {
                CalendarUserSettingsFetch.fetch(api { gate.await(10, TimeUnit.SECONDS); reply(it, 200, "{}") }, ACCOUNT, timeoutMs = 200)
            }
            assertTrue("$slow", slow is Settings.Failed && slow.httpStatus == null && slow.detail.startsWith("no answer within"))
        } finally {
            gate.countDown()
        }
    }

    // ---- PUT settings/calendar ----

    @Test
    fun `the change sends DefaultCalendarID alone, and Proton's yes is a change`() {
        assertEquals(SaveOutcome.Saved, change(200, """{"Code":1000,"CalendarUserSettings":{"DefaultCalendarID":"cal-a","WeekStart":1}}"""))
        val request = sent.single()
        assertEquals("PUT", request.method)
        assertTrue(request.url.toString(), request.url.encodedPath.endsWith("/settings/calendar"))
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertEquals("""{"DefaultCalendarID":"cal-a"}""", body)
        // No settings object, as Proton web reads the answer: the change stands.
        assertEquals(SaveOutcome.Saved, change(200, """{"Code":1000}"""))
    }

    @Test
    fun `an answer that names no change, or has no body, is checked`() {
        for ((code, json) in listOf(
            200 to """{"Code":1000,"CalendarUserSettings":{"DefaultCalendarID":"cal-b"}}""",
            200 to """{"Code":1000,"CalendarUserSettings":{"WeekStart":1}}""",
            204 to null,
        )) {
            val outcome = change(code, json)
            assertTrue("$code $json: $outcome", outcome is SaveOutcome.Uncertain && outcome.refusal?.http == code)
        }
    }

    @Test
    fun `a refusal reads the error body once, for its Code and its cleaned text`() {
        assertEquals(
            SaveOutcome.Refused(422, 2001, " (Code 2001: Invalid calendar for <address>)"),
            change(422, """{"Code":2001,"Error":"Invalid calendar for user@example.test"}""")
        )
        assertEquals(SaveOutcome.Refused(200, 2001), change(200, """{"Code":2001,"Error":"Invalid"}"""))
        assertEquals(SaveOutcome.Refused(403, null), change(403, "<html>Forbidden</html>"))
    }

    @Test
    fun `a sign-in, a busy Proton and a server error each mean what they say`() {
        assertEquals(SaveOutcome.Auth, change(401, """{"Code":10013}"""))
        assertEquals(SaveOutcome.Unreachable("HTTP 429"), change(429, """{"Code":2028}"""))
        assertEquals(SaveOutcome.Uncertain("HTTP 503"), change(503, null))
    }

    @Test
    fun `a change that threw is unreachable only when no attempt can have been sent`() {
        assertEquals(SaveOutcome.Unreachable("UnknownHostException"), change { throw UnknownHostException("no address") })
        // OkHttp's retry after a failure on an open connection: the earlier attempt may have reached Proton.
        val afterSend = change {
            throw UnknownHostException("no address").apply { addSuppressed(SocketException("Software caused connection abort")) }
        }
        assertTrue("$afterSend", afterSend is SaveOutcome.Uncertain)
        // A reset after the request went out.
        assertTrue(change { throw IOException("stream was reset: CANCEL") } is SaveOutcome.Uncertain)
        // A 2xx whose body is not JSON: Proton may have applied the change.
        assertTrue(change(200, "<html>proxy</html>") is SaveOutcome.Uncertain)
    }

    @Test
    fun `no answer in time is uncertain`() {
        val gate = CountDownLatch(1)
        try {
            val slow = change(timeoutMs = 200) { gate.await(10, TimeUnit.SECONDS); reply(it, 200, """{"Code":1000}""") }
            assertTrue("$slow", slow is SaveOutcome.Uncertain && slow.detail.startsWith("no answer within"))
        } finally {
            gate.countDown()
        }
    }
}
