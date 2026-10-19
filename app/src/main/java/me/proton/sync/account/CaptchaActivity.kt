package me.proton.sync.account

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import me.proton.sync.SyncLog
import me.proton.sync.api.CaptchaHostPolicy
import java.io.ByteArrayInputStream

/**
 * Shows a Proton CAPTCHA verification page in a WebView.
 * When the CAPTCHA is solved, the page posts a message with the token
 * which we capture via a JavaScript interface and return as a result.
 *
 * Hardened (1.6.18): the WebView is locked to the [CaptchaHostPolicy] allowlist
 * over https only — the Intent URL is re-validated, main-frame navigations and
 * subresource requests are checked (both WebViewClient and the ServiceWorker
 * intercept path), file/content access is off, and the injected message listener
 * only accepts the token from a Proton origin. WebSocket (wss:) handshakes are the
 * one class WebView cannot intercept, but they can only be opened by already-running
 * allowlisted-origin script, so they add no new attack surface.
 */
class CaptchaActivity : ComponentActivity() {

    companion object {
        private const val TAG = "Captcha"
        const val EXTRA_VERIFY_URL = "verify_url"
        const val EXTRA_TOKEN = "token"
        const val RESULT_TOKEN = "captcha_token"
    }

    /**
     * Monotonic start of the verification screen. Elapsed times matter here: the
     * field report that prompted this instrumentation said the puzzle took several
     * minutes and then failed, and without timings a log cannot tell a slow load
     * apart from a user who walked away.
     */
    private var openedAt = 0L

    /** Set once the page hands us a token, so [onDestroy] can tell solved from abandoned. */
    private var solved = false

    private fun sinceOpened(): String =
        if (openedAt == 0L) "?" else "${(SystemClock.elapsedRealtime() - openedAt) / 1000}s"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val verifyUrl = intent.getStringExtra(EXTRA_VERIFY_URL)
        val token = intent.getStringExtra(EXTRA_TOKEN)

        if (verifyUrl == null || token == null) {
            finish()
            return
        }

        // Never trust the Intent extra: re-validate the URL before creating any
        // WebView. Finishing without RESULT_OK lands in LoginActivity's existing
        // captcha-cancelled handling.
        if (!CaptchaHostPolicy.isAllowedUrl(verifyUrl)) {
            SyncLog.w(TAG, "Captcha blocked: refusing to load non-allowlisted verification URL")
            finish()
            return
        }

        openedAt = SystemClock.elapsedRealtime()
        SyncLog.i(TAG, "Captcha screen opened (host=${android.net.Uri.parse(verifyUrl).host})")

        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // The verify page needs nothing beyond https + JS + DOM storage — turn the
        // rest off so page content cannot reach local files or downgrade to http.
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        webView.settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        webView.settings.allowUniversalAccessFromFileURLs = false
        webView.settings.setGeolocationEnabled(false)
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Service-worker fetches bypass WebViewClient.shouldInterceptRequest, so apply
        // the same allowlist to them (API 24+; the controller is process-global, which
        // is fine — this CAPTCHA WebView is the app's only WebView).
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.scheme?.lowercase() == "https" && CaptchaHostPolicy.isAllowedHost(url.host)) {
                        return null
                    }
                    SyncLog.w(TAG, "Captcha blocked service-worker request to ${url.host}")
                    return WebResourceResponse(
                        "text/plain", "utf-8", 403, "Blocked by captcha host allowlist",
                        emptyMap(), ByteArrayInputStream(ByteArray(0))
                    )
                }
            })
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not install service-worker allowlist: ${e.javaClass.simpleName}")
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onCaptchaSolved(captchaToken: String) {
                solved = true
                SyncLog.i(TAG, "Captcha solved after ${sinceOpened()}")
                val result = Intent()
                result.putExtra(RESULT_TOKEN, captchaToken)
                setResult(RESULT_OK, result)
                finish()
            }
        }, "ProtonCaptcha")

        webView.webViewClient = object : WebViewClient() {
            // Log each allowed host once so a diagnostic log shows exactly what a
            // live challenge loads — field evidence if the allowlist ever needs widening.
            private val seenHosts = mutableSetOf<String>()

            /**
             * Main-frame failure for the current navigation, so [onPageFinished] does not
             * cheerfully report "page loaded" straight after a load error. WebView calls
             * onPageFinished even for a failed page, and a log that says both is worse than
             * one that says neither.
             */
            private var mainFrameError: String? = null

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                mainFrameError = null
            }

            // Main-frame navigations and redirects: stay on allowlisted hosts.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.scheme?.lowercase() == "https" && CaptchaHostPolicy.isAllowedHost(url.host)) {
                    return false // allow
                }
                SyncLog.w(TAG, "Captcha blocked navigation to ${url.scheme}://${url.host}")
                return true // block
            }

            // Every resource load (frames, scripts, XHR, images) — the primary control.
            // Runs on a WebView worker thread; keep it cheap and thread-safe.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                val host = url.host
                if (url.scheme?.lowercase() == "https" && CaptchaHostPolicy.isAllowedHost(host)) {
                    if (host != null && synchronized(seenHosts) { seenHosts.add(host) }) {
                        SyncLog.d(TAG, "Captcha loading from allowed host: $host")
                    }
                    return null // allow default loading
                }
                SyncLog.w(TAG, "Captcha blocked request to ${url.scheme}://$host")
                return WebResourceResponse(
                    "text/plain", "utf-8", 403, "Blocked by captcha host allowlist",
                    emptyMap(), ByteArrayInputStream(ByteArray(0))
                )
            }

            // Load failures were previously invisible: a resource that stalls or 404s
            // produced no log at all, so a challenge that took minutes and then failed
            // left no trace. Requests our own allowlist rejected are skipped here because
            // shouldInterceptRequest already logged them, so anything that reaches these
            // two handlers is a genuine network or Proton-side failure.
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                val url = request?.url ?: return
                if (!CaptchaHostPolicy.isAllowedHost(url.host)) return
                val what = if (request.isForMainFrame) "page" else "resource"
                val detail = "${error?.errorCode}: ${error?.description}"
                if (request.isForMainFrame) mainFrameError = detail
                SyncLog.w(TAG, "Captcha $what load failed after ${sinceOpened()}: ${url.host} ($detail)")
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                val url = request?.url ?: return
                if (!CaptchaHostPolicy.isAllowedHost(url.host)) return
                val what = if (request.isForMainFrame) "page" else "resource"
                SyncLog.w(
                    TAG,
                    "Captcha $what HTTP ${errorResponse?.statusCode} from ${url.host} after ${sinceOpened()}"
                )
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val host = android.net.Uri.parse(url).host
                val failure = mainFrameError
                if (failure != null) {
                    SyncLog.w(TAG, "Captcha page finished WITH ERROR after ${sinceOpened()} ($host): $failure")
                } else {
                    SyncLog.i(TAG, "Captcha page loaded after ${sinceOpened()} ($host)")
                }
                // Inject JS to listen for the CAPTCHA solution message. Only accept
                // the token from a Proton https origin so third-party code loaded by
                // the page cannot forge it into the native bridge.
                view?.evaluateJavascript("""
                    window.addEventListener('message', function(event) {
                        if (!/^https:\/\/([a-z0-9-]+\.)*(proton\.me|protonmail\.com)$/.test(event.origin)) return;
                        if (event.data && event.data.type === 'pm_captcha' && event.data.token) {
                            ProtonCaptcha.onCaptchaSolved(event.data.token);
                        }
                    });
                """.trimIndent(), null)
            }
        }

        setContentView(webView)
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        webView.loadUrl(verifyUrl)
    }

    /**
     * Closing without a token is the most common real-world outcome (back button, or the
     * challenge never completing) and used to leave no trace at all, so a support log
     * showed the screen opening and then simply stopped. Logging the elapsed time here is
     * what separates "gave up after 4 seconds" from "fought it for nine minutes".
     */
    override fun onDestroy() {
        if (!solved) {
            SyncLog.w(TAG, "Captcha screen closed WITHOUT a token after ${sinceOpened()}")
        }
        super.onDestroy()
    }
}
