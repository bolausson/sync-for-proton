package me.proton.sync.calendar

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The only code that contacts www.tzurl.org, and only for "Update time zones online" (OnlineTimeZoneRules).
 *
 * Its OWN OkHttpClient, never the app's Hilt one: that one's AuthInterceptor adds the Proton session
 * (Authorization, x-pm-uid) and the TokenRefreshAuthenticator, none of which may leave for a third party.
 * What tzurl.org (and the CDN in front of it) receives: the phone's IP address, the zone name in the URL,
 * OkHttp's generic User-Agent ("okhttp/4.12.0": no phone model, no Android version, no app name) and an
 * Accept header. No cookies, no cache, no redirects (a redirect could send the request elsewhere), short
 * timeouts. The URL is fixed; OnlineTimeZoneRules only passes zone names from Proton's own list.
 *
 * retryOnConnectionFailure stays at OkHttp's default (on): in OkHttp 4 the same flag also governs falling back
 * to the host's other addresses after a failed connect (RetryAndFollowUpInterceptor.recover returns false at
 * once without it), and www.tzurl.org resolves to several IPv4 and IPv6 addresses. Off, one dead route (broken
 * IPv6, say) failed the zone for the whole process. It repeats the same GET, which reveals nothing new, within
 * the call timeout; it never retries an HTTP answer.
 */
internal object TzurlClient {
    const val BASE_URL = "https://www.tzurl.org/zoneinfo/"
    private const val MAX_BYTES = 64 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .build()
    }

    /** The VTIMEZONE text for [tzid]; throws IOException on anything but a 200 with a small body. */
    fun fetch(tzid: String): String = fetchUrl(BASE_URL + tzid)

    /** [fetch] for a whole URL: only the tests call it directly, against a loopback server. */
    internal fun fetchUrl(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/calendar")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("no body")
            val bytes = body.byteStream().use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                    if (buffer.size() > MAX_BYTES) throw IOException("answer larger than $MAX_BYTES bytes")
                }
                buffer.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        }
    }
}
