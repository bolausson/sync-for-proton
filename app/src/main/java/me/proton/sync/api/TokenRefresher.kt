package me.proton.sync.api

import com.google.gson.Gson
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncLog
import me.proton.sync.api.models.RefreshTokenRequest
import me.proton.sync.api.models.RefreshTokenResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One-shot, blocking refresh-token → access-token exchange, independent of the
 * per-account OkHttp/Retrofit stack.
 *
 * [TokenRefreshAuthenticator] only fires reactively on a 401 from a live request.
 * Some flows need to mint an access token *proactively* — notably a backup-restored
 * session, which arrives with only a refresh token (empty access token) and would
 * otherwise be turned away by the sync preflight before any request is made
 * ([me.proton.sync.SyncUtils.runSyncPreflight]). This centralizes the exact
 * `auth/v4/refresh` request the authenticator builds so the two cannot drift.
 *
 * Call on a background thread (it performs a synchronous HTTP call).
 */
object TokenRefresher {

    private const val TAG = "TokenRefresher"
    private val client = OkHttpClient.Builder().build()
    private val gson = Gson()

    data class Tokens(val accessToken: String, val refreshToken: String, val uid: String)

    /** @return fresh tokens, or null on any failure (network, rotated/invalid refresh token, server error). */
    fun refresh(uid: String, refreshToken: String): Tokens? {
        if (uid.isEmpty() || refreshToken.isEmpty()) return null
        val body = gson.toJson(RefreshTokenRequest(refreshToken = refreshToken, uid = uid))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(ProtonSyncApp.BASE_URL + "auth/v4/refresh")
            .post(body)
            .header("x-pm-uid", uid)
            .header("x-pm-appversion", ProtonSyncApp.APP_VERSION)
            .header("x-pm-apiversion", "3")
            .header("Accept", "application/vnd.protonmail.v1+json")
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                if (!resp.isSuccessful) {
                    SyncLog.w(TAG, "Refresh HTTP error: ${resp.code}")  // never log the body (tokens)
                    return null
                }
                val tokens = gson.fromJson(respBody, RefreshTokenResponse::class.java)
                if (tokens != null && tokens.code == 1000 && tokens.accessToken.isNotEmpty()) {
                    Tokens(tokens.accessToken, tokens.refreshToken, tokens.uid)
                } else {
                    SyncLog.w(TAG, "Refresh response error: code=${tokens?.code}")
                    null
                }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Refresh failed: ${e.javaClass.simpleName}")
            null
        }
    }
}
