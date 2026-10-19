package me.proton.sync.api

import kotlinx.coroutines.runBlocking
import me.proton.sync.account.AccountSession
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Header
import retrofit2.http.Tag
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The account a Proton call is for travels as an OkHttp request TAG, not as the X-Proton-Account-Name
 * header that 1.11.4 sent. That header gave Proton the display name the user chose for the account on
 * every request, and made OkHttp throw on any non-ASCII name, so an account called "Büro" never synced.
 *
 * Runs the real [ProtonApiClient] interface through real Retrofit and OkHttp with the real
 * [AuthInterceptor]; only the session lookup and the network are stood in for.
 */
class AccountNameTagTest {

    private val sessions = mapOf(
        BUERO to AccountSession(BUERO, accessToken = "token-h", refreshToken = "refresh-h", uid = "uid-h"),
        "Work" to AccountSession("Work", accessToken = "token-w", refreshToken = "refresh-w", uid = "uid-w"),
    )

    /** What would have gone out, as AuthInterceptor handed it on. */
    private val sent: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    /** Stands in for the network: records the request and answers 200 {"Code":1000}. */
    private val wire = Interceptor { chain ->
        sent += chain.request()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("""{"Code":1000}""".toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun api(
        baseUrl: String = "https://mail-api.proton.me/",
        client: OkHttpClient = OkHttpClient.Builder().addInterceptor(interceptor()).addInterceptor(wire).build(),
    ): ProtonApiClient = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .validateEagerly(true) // parse every endpoint now, so a malformed one fails here
        .build()
        .create(ProtonApiClient::class.java)

    private fun interceptor() = AuthInterceptor { sessions[it] }

    private val endpoints = ProtonApiClient::class.java.declaredMethods
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic && !it.isDefault }

    @Test
    fun `no endpoint declares the account-name header`() {
        val offenders = endpoints.filter { m ->
            m.parameterAnnotations.flatMap { it.toList() }
                .any { it is Header && it.value.equals("X-Proton-Account-Name", ignoreCase = true) }
        }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `every endpoint except sign-in and token refresh names its account with exactly one String tag`() {
        val untagged = mutableListOf<String>()
        for (m in endpoints) {
            val tagged = m.parameterAnnotations.indices.filter { i -> m.parameterAnnotations[i].any { it is Tag } }
            when (tagged.size) {
                0 -> untagged += m.name
                1 -> assertEquals("${m.name}: the tag must be the account name", String::class.java, m.parameterTypes[tagged[0]])
                else -> throw AssertionError("${m.name} has ${tagged.size} @Tag parameters")
            }
        }
        // A new endpoint that forgets the tag would go out without a token and fail with 401.
        assertEquals(setOf("getAuthInfo", "authenticate", "refreshToken"), untagged.toSet())
    }

    @Test
    fun `a non-ASCII account name works, and neither the name nor the header is sent`() = runBlocking<Unit> {
        // 1.11.4 threw IllegalArgumentException("Unexpected char 0xfc ...") right here.
        val response = api().getCalendars(BUERO)

        assertEquals(200, response.code())
        val request = sent.single()
        assertNull(request.header("X-Proton-Account-Name"))
        assertTrue("the name leaked into a header: ${request.headers}", request.headers.none { (_, value) -> BUERO in value })
        assertEquals(BUERO, request.protonAccountName())
        assertEquals("Bearer token-h", request.header("Authorization"))
        assertEquals("uid-h", request.header("x-pm-uid"))
    }

    @Test
    fun `each account gets its own token`() = runBlocking<Unit> {
        val api = api()
        api.getCalendars(BUERO)
        api.getContacts("Work")

        assertEquals(listOf("Bearer token-h", "Bearer token-w"), sent.map { it.header("Authorization") })
        assertEquals(listOf("uid-h", "uid-w"), sent.map { it.header("x-pm-uid") })
    }

    @Test
    fun `the sign-out keeps the credentials it brought along`() = runBlocking<Unit> {
        api().revokeSession(BUERO, "Bearer captured", "uid-captured")

        val request = sent.single()
        assertEquals("Bearer captured", request.header("Authorization"))
        assertEquals("uid-captured", request.header("x-pm-uid"))
        assertNull(request.header("X-Proton-Account-Name"))
    }

    @Test
    fun `a call that names no account goes out without a token`() = runBlocking<Unit> {
        api().getUser(null)

        val request = sent.single()
        assertNull(request.protonAccountName())
        assertNull(request.header("Authorization"))
    }

    @Test
    fun `a synchronous call without a tag uses the ThreadLocal, and is tagged for the authenticator`() {
        val client = OkHttpClient.Builder().addInterceptor(interceptor()).addInterceptor(wire).build()
        AuthInterceptor.withAccount("Work") {
            client.newCall(Request.Builder().url("https://mail-api.proton.me/calendar/v1").build()).execute().close()
        }

        val request = sent.single()
        assertEquals("Bearer token-w", request.header("Authorization"))
        assertEquals("Work", request.protonAccountName())
        assertNull(request.header("X-Proton-Account-Name"))
    }

    @Test
    fun `on a real 401 the authenticator gets the account from the tag, and the retry keeps it`() = runBlocking<Unit> {
        Http401ThenOk().use { server ->
            val seenByAuthenticator = CopyOnWriteArrayList<String?>()
            // Reads the account exactly as TokenRefreshAuthenticator does. The real one cannot run in a JVM
            // test (SyncLog calls android.util.Log), but what it depends on is OkHttp handing it the request
            // AuthInterceptor built, which is what this proves.
            val authenticator = Authenticator { _, response ->
                seenByAuthenticator += response.request.protonAccountName()
                response.request.newBuilder().header("Authorization", "Bearer refreshed").build()
            }
            val client = OkHttpClient.Builder().addInterceptor(interceptor()).authenticator(authenticator).build()

            val response = api(baseUrl = "http://127.0.0.1:${server.port}/", client = client).getCalendars(BUERO)

            assertEquals(200, response.code())
            assertEquals(listOf<String?>(BUERO), seenByAuthenticator.toList())
            assertEquals(BUERO, response.raw().request.protonAccountName())
            val onTheWire = server.requests.toList()
            assertEquals(2, onTheWire.size)
            assertTrue(onTheWire[0].contains("\r\nAuthorization: Bearer token-h\r\n"))
            assertTrue(onTheWire[1].contains("\r\nAuthorization: Bearer refreshed\r\n"))
            for (raw in onTheWire) {
                assertTrue(raw, !raw.contains("x-proton-account-name", ignoreCase = true))
                assertTrue(raw, !raw.contains(BUERO))
            }
        }
    }

    /**
     * A loopback HTTP server: answers the first request 401 and every later one 200, one request per
     * connection, and keeps each request's raw head (request line and headers, decoded as UTF-8).
     */
    private class Http401ThenOk : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        private val thread = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                socket.use { s ->
                    s.soTimeout = 5_000
                    val input = s.getInputStream()
                    val head = ByteArrayOutputStream()
                    while (!head.toByteArray().endsWith(END_OF_HEAD)) {
                        val b = input.read()
                        if (b < 0) return@use
                        head.write(b)
                    }
                    requests += head.toString(Charsets.UTF_8.name())
                    val status = if (requests.size == 1) "401 Unauthorized" else "200 OK"
                    val body = """{"Code":1000}"""
                    s.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n" +
                                "Connection: close\r\n\r\n$body").toByteArray(Charsets.ISO_8859_1)
                        )
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            server.close()
            thread.join(2_000)
        }

        private companion object {
            val END_OF_HEAD = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            fun ByteArray.endsWith(suffix: ByteArray) =
                size >= suffix.size && suffix.indices.all { this[size - suffix.size + it] == suffix[it] }
        }
    }

    private companion object {
        const val BUERO = "Büro"
    }
}
