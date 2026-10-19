package me.proton.sync.api

import android.net.Uri

/**
 * Single source of truth for which hosts the login-verification (CAPTCHA) WebView
 * may talk to. Used in three layers (defense-in-depth):
 *
 *  1. [SrpAuthHandler.checkForCaptcha] — rejects a tampered/off-Proton `WebUrl`
 *     before any WebView exists (fail closed).
 *  2. `CaptchaActivity.onCreate` — re-validates the Intent extra (never trust it).
 *  3. `CaptchaActivity`'s WebViewClient + ServiceWorkerClient — block main-frame
 *     navigations (shouldOverrideUrlLoading) and subresource requests
 *     (shouldInterceptRequest, including service-worker fetches) to non-allowlisted
 *     hosts. (WebSocket handshakes cannot be intercepted by WebView, but can only be
 *     opened by already-running allowlisted-origin script.)
 *
 * The legitimate verification page is verify.proton.me. The allowlist additionally
 * covers Proton's other domains and hCaptcha, which Proton's human-verification
 * flow has historically embedded (and which our privacy policy discloses). Blocked
 * hosts are logged by the callers, so a legitimate host we missed would show up in
 * a diagnostic log as "Captcha blocked ...". Extend the list here if that ever happens.
 */
object CaptchaHostPolicy {

    private val ALLOWED_SUFFIXES = listOf(
        "proton.me",
        "protonmail.com",
        "hcaptcha.com",
    )

    /** True if [host] is an allowlisted domain or a subdomain of one. */
    fun isAllowedHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return ALLOWED_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    /** True if [url] is a well-formed https URL on an allowlisted host. */
    fun isAllowedUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return try {
            val uri = Uri.parse(url)
            uri.scheme?.lowercase() == "https" && isAllowedHost(uri.host)
        } catch (_: Exception) {
            false
        }
    }
}
