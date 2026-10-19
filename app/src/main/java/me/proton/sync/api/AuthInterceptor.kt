package me.proton.sync.api

import me.proton.sync.ProtonSyncApp
import me.proton.sync.account.AccountSession
import me.proton.sync.account.AccountSessionManager
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor internal constructor(
    /** The session for an account name. A function so a JVM test can stand in for AccountSessionManager. */
    private val sessionFor: (String) -> AccountSession?
) : Interceptor {

    @Inject constructor(sessionManager: AccountSessionManager) : this({ sessionManager.getSession(it) })

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val path = originalRequest.url.encodedPath

        // Which /auth/v4 calls go out WITHOUT a token. The rule is in AuthEndpointRules.kt, where it can
        // be tested: it is easy to get subtly wrong and wrong in either direction fails silently.
        val isAuthEndpoint = isUnauthenticatedAuthCall(path, originalRequest.method)

        // The account comes from the request's tag (Retrofit's @Tag, see AccountTag.kt), never from a
        // header: nothing that identifies the account by its local name is sent to Proton.
        val accountName = originalRequest.protonAccountName()
            ?: currentAccount.get()

        val requestBuilder = originalRequest.newBuilder()
            .header("x-pm-appversion", ProtonSyncApp.APP_VERSION)
            .header("x-pm-apiversion", "3")
            .header("Accept", "application/vnd.protonmail.v1+json")

        // Tag the request so TokenRefreshAuthenticator finds the account on a 401. newBuilder() already
        // copied Retrofit's tag; this covers a name that came from the ThreadLocal. A tag, never sent.
        if (accountName != null) {
            requestBuilder.protonAccountName(accountName)
        }

        // A request that already carries its own credentials keeps them. The sign-out on account removal
        // is the reason: it runs while the account is being torn down, so it brings the token it captured
        // beforehand, and looking one up here would find nothing and silently strip it.
        val carriesOwnAuth = originalRequest.header("Authorization") != null
        if (!isAuthEndpoint && !carriesOwnAuth && accountName != null) {
            val session = sessionFor(accountName)
            if (session != null && session.accessToken.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer ${session.accessToken}")
                requestBuilder.header("x-pm-uid", session.uid)
            }
        }

        val captchaToken = pendingCaptchaToken
        if (captchaToken != null && path.endsWith("/auth/v4") && originalRequest.method == "POST") {
            requestBuilder.header("x-pm-human-verification-token", captchaToken)
            requestBuilder.header("x-pm-human-verification-token-type", "captcha")
            pendingCaptchaToken = null
        }

        // Honour any in-progress global rate-limit pause from a sibling request
        // before we send. Without this, all parallel coroutines back off then resume
        // simultaneously and slam Proton's rate limiter again on the very next burst.
        awaitGlobalThrottle()

        // Execute request with HTTP 429 rate limit retry. We retry up to MAX_429_RETRIES
        // times with exponential backoff (capped at 30 s per attempt). The first wait
        // honours the server's Retry-After; subsequent waits double, with light jitter
        // to avoid thundering-herd resumption when many parallel callers retry together.
        var response = chain.proceed(requestBuilder.build())
        var attempt = 0
        while (response.code == 429 && attempt < MAX_429_RETRIES) {
            val serverHint = response.header("Retry-After")?.toLongOrNull()
            response.close()

            val baseSec = serverHint ?: (1L shl attempt).coerceAtMost(16)
            val jitterMs = (Math.random() * 500).toLong()
            val waitMs = (baseSec * 1000 + jitterMs).coerceAtMost(30_000)

            // Publish the pause window so other in-flight requests wait too.
            val until = System.currentTimeMillis() + waitMs
            updateThrottleUntil(until)

            try { Thread.sleep(waitMs) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return chain.proceed(requestBuilder.build())
            }
            attempt++
            response = chain.proceed(requestBuilder.build())
        }
        return response
    }

    /** Block until the shared throttle window has elapsed, if one is active. */
    private fun awaitGlobalThrottle() {
        while (true) {
            val until = throttleUntilMs.get()
            val now = System.currentTimeMillis()
            val remaining = until - now
            if (remaining <= 0) return
            try { Thread.sleep(remaining.coerceAtMost(30_000)) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** Atomically advance the shared throttle deadline (only ever forwards). */
    private fun updateThrottleUntil(target: Long) {
        while (true) {
            val current = throttleUntilMs.get()
            if (target <= current) return
            if (throttleUntilMs.compareAndSet(current, target)) return
        }
    }

    companion object {
        @Volatile var pendingCaptchaToken: String? = null

        /**
         * Fallback account for a request without a tag. It is only seen by a call executed synchronously
         * on the thread that set it. Retrofit's suspend calls run through Call.enqueue on OkHttp's
         * dispatcher threads, where this is never set, so every [ProtonApiClient] call is routed by its
         * own `@Tag accountName` alone, and a call without one goes out with no token.
         */
        val currentAccount = ThreadLocal<String?>()

        /**
         * Shared rate-limit deadline (epoch ms). When one request sees 429 it
         * publishes the Retry-After window here so any sibling coroutine about
         * to fire its own request waits for the same window instead of piling
         * back into Proton's limiter immediately.
         */
        private val throttleUntilMs = AtomicLong(0)

        private const val MAX_429_RETRIES = 5

        inline fun <T> withAccount(accountName: String, block: () -> T): T {
            val previous = currentAccount.get()
            currentAccount.set(accountName)
            try {
                return block()
            } finally {
                currentAccount.set(previous)
            }
        }
    }
}
