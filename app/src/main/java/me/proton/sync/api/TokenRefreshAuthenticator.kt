package me.proton.sync.api

import me.proton.sync.SyncLog
import com.google.gson.Gson
import me.proton.sync.ProtonSyncApp
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.api.models.RefreshTokenRequest
import me.proton.sync.api.models.RefreshTokenResponse
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenRefreshAuthenticator @Inject constructor(
    private val sessionManager: AccountSessionManager
) : Authenticator {

    private val lock = Any()
    private val gson = Gson()

    private val refreshClient = OkHttpClient.Builder().build()

    override fun authenticate(route: Route?, response: Response): Request? {
        // response.request is the request AuthInterceptor built, tag included (AccountTag.kt), and every
        // retry below is made with newBuilder(), which keeps the tag. The account name is never a header.
        val accountName = response.request.protonAccountName()
        val path = response.request.url.encodedPath
        SyncLog.d(TAG, "401 on $path")

        // A 401 from an authentication endpoint is an authentication failure, not an expired
        // access token, and refreshing cannot fix it. Worse, it hides the real error: a 401
        // from /auth/v4/2fa sent us off to /auth/v4/refresh with a pre-2FA session (scope
        // "self parent user twofactor"), which answered 422, and the caller was left with
        // refresh noise instead of the reason its 2FA was rejected. Seen in the field, and it
        // cost several rounds of diagnosis.
        if (NO_REFRESH_SUFFIXES.any { path.endsWith(it) }) {
            SyncLog.i(TAG, "Not refreshing: $path is an auth endpoint, so 401 means the request was rejected")
            return null
        }

        if (accountName == null) {
            SyncLog.w(TAG, "No account on the request, cannot refresh token")
            return null
        }

        if (responseCount(response) >= 2) {
            SyncLog.w(TAG, "Too many retries")
            return null
        }

        synchronized(lock) {

            // Re-read session — another thread may have refreshed while we waited for the lock
            val latestSession = sessionManager.getSession(accountName) ?: return null

            if (latestSession.refreshToken.isEmpty()) {
                SyncLog.w(TAG, "No refresh token available")
                return null
            }

            // Check if the token used in the failed request differs from the current stored token
            // If so, another thread already refreshed — just retry with the new token
            val requestToken = response.request.header("Authorization")
                ?.removePrefix("Bearer ")
            if (requestToken != null && requestToken != latestSession.accessToken) {
                SyncLog.d(TAG, "Token already refreshed by another thread, retrying")
                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${latestSession.accessToken}")
                    .header("x-pm-uid", latestSession.uid)
                    .build()
            }

            // Do NOT log the session UID
            SyncLog.i(TAG, "Refreshing token...")
            val refreshRequest = RefreshTokenRequest(
                refreshToken = latestSession.refreshToken,
                uid = latestSession.uid
            )

            val jsonBody = gson.toJson(refreshRequest)
                .toRequestBody("application/json".toMediaType())

            val refreshHttpRequest = Request.Builder()
                .url(ProtonSyncApp.BASE_URL + "auth/v4/refresh")
                .post(jsonBody)
                .header("x-pm-uid", latestSession.uid)
                .header("x-pm-appversion", ProtonSyncApp.APP_VERSION)
                .header("x-pm-apiversion", "3")
                .header("Accept", "application/vnd.protonmail.v1+json")
                .build()

            try {
                val refreshResponse = refreshClient.newCall(refreshHttpRequest).execute()
                val body = refreshResponse.body?.string()

                if (refreshResponse.isSuccessful) {
                    val newTokens = gson.fromJson(body, RefreshTokenResponse::class.java)

                    if (newTokens != null && newTokens.code == 1000) {
                        sessionManager.updateTokens(
                            accountName,
                            newTokens.accessToken,
                            newTokens.refreshToken
                        )

                        SyncLog.i(TAG, "Token refreshed successfully")

                        return response.request.newBuilder()
                            .header("Authorization", "Bearer ${newTokens.accessToken}")
                            .header("x-pm-uid", newTokens.uid)
                            .build()
                    } else {
                        // Code and Error only (ProtonApiError), never the body: a refresh reply carries tokens
                        SyncLog.w(TAG, "Token refresh response error: code=${newTokens?.code}" + ProtonApiError.describe(body))
                    }
                } else {
                    // Code and Error only (ProtonApiError), never the body: a refresh reply carries tokens.
                    // This is where a refused refresh token (10013) shows up.
                    SyncLog.w(TAG, "Token refresh HTTP error: ${refreshResponse.code}" + ProtonApiError.describe(body))
                }
            } catch (e: Exception) {
                SyncLog.e(TAG, "Token refresh failed", e)
            }

            return null
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    companion object {
        private const val TAG = "TokenRefresh"

        /**
         * Endpoints where a 401 is the answer, not a symptom.
         *
         * Matched by suffix rather than equality so a change of API prefix cannot silently
         * re-enable the behaviour. `/auth/v4` does not match `/auth/v4/2fa`, so each entry
         * has to be listed.
         */
        private val NO_REFRESH_SUFFIXES = listOf(
            "/auth/v4",
            "/auth/v4/info",
            "/auth/v4/2fa",
            "/auth/v4/refresh",
        )
    }
}
