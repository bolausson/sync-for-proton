package me.proton.sync.api

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncLog
import me.proton.sync.account.AccountSession
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.api.models.Fido2Info
import me.proton.sync.api.models.SrpAuthRequest
import me.proton.sync.api.models.TwoFactorInfo
import me.proton.sync.api.models.TwoFactorRequest
import me.proton.sync.crypto.KeyManager
import me.proton.sync.crypto.SrpCrypto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles SRP 6a authentication with Proton's API, including 2FA/TOTP and key unlocking.
 *
 * Flow:
 * 1. POST auth/v4/info -> get Modulus, ServerEphemeral, Salt, SRPSession, Version
 * 2. Compute SRP proofs using SrpCrypto
 * 3. POST auth/v4 -> verify server proof, get tokens
 * 4. If 2FA enabled, POST auth/v4/2fa with TOTP code
 * 5. GET users -> get encrypted private key
 * 6. Unlock user key ring for contact/calendar decryption
 */
@Singleton
class SrpAuthHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: ProtonApiClient,
    private val keyManager: KeyManager,
    private val sessionManager: AccountSessionManager
) {
    data class LoginResult(
        val session: AccountSession,
        val requires2FA: Boolean,
        val password: String // Kept temporarily for key derivation after 2FA
    )

    /**
     * Perform SRP login. Returns a LoginResult indicating whether 2FA is needed.
     */
    class CaptchaRequiredException(
        val token: String,
        val webUrl: String,
        val methods: List<String>
    ) : Exception("CAPTCHA required")

    suspend fun login(username: String, password: String): LoginResult {
        // Step 1: Get auth info
        val authInfoResponse = apiClient.getAuthInfo(mapOf("Username" to username, "Intent" to "Proton"))
        if (!authInfoResponse.isSuccessful) {
            val errorBody = authInfoResponse.errorBody()?.string() ?: "no body"
            // Check for CAPTCHA requirement
            checkForCaptcha(errorBody)
            // Code and Error only, never the body (a 9001 body carries a verification token)
            throw refused("Auth info failed", authInfoResponse.code(), errorBody)
        }
        val authInfo = authInfoResponse.body() ?: run {
            SyncLog.e(TAG, "Auth info failed: HTTP ${authInfoResponse.code()} with an empty body")
            throw AuthException("Failed to get auth info: empty response body", httpStatus = authInfoResponse.code())
        }

        if (authInfo.code != 1000) {
            SyncLog.e(TAG, "Auth info failed: HTTP ${authInfoResponse.code()} (Code ${authInfo.code})")
            throw AuthException(
                "Auth info failed with code: ${authInfo.code}",
                httpStatus = authInfoResponse.code(),
                protonCode = authInfo.code
            )
        }

        // Step 2: Compute SRP proofs (after checking that Proton signed the modulus)
        val proofs = try {
            SrpCrypto.generateSrpProofs(
                password = password,
                version = authInfo.version,
                salt = authInfo.salt,
                signedModulus = authInfo.modulus,
                serverEphemeral = authInfo.serverEphemeral
            )
        } catch (e: SrpCrypto.UnsignedModulusException) {
            SyncLog.e(TAG, "Sign-in stopped before any proof was sent: ${e.message}")
            throw AuthException("Modulus signature verification failed - possible MITM attack", e, untrustedServer = true)
        }

        val clientEphemeralB64 = Base64.encodeToString(proofs.clientEphemeral, Base64.NO_WRAP)
        val clientProofB64 = Base64.encodeToString(proofs.clientProof, Base64.NO_WRAP)

        // Step 3: Authenticate
        val authRequest = SrpAuthRequest(
            username = username,
            clientEphemeral = clientEphemeralB64,
            clientProof = clientProofB64,
            srpSession = authInfo.srpSession
        )

        val authResponse = apiClient.authenticate(authRequest)
        // Before the success check: a skewed clock is most interesting precisely when the
        // sign-in then goes wrong.
        logClockSkew(authResponse.headers())
        if (!authResponse.isSuccessful) {
            val errorBody = authResponse.errorBody()?.string() ?: "no body"
            checkForCaptcha(errorBody)
            // Code and Error only, never the body. A wrong password reads
            // "Authentication failed: HTTP 422 (Code 8002: ...)".
            throw refused("Authentication failed", authResponse.code(), errorBody)
        }
        val auth = authResponse.body() ?: run {
            SyncLog.e(TAG, "Authentication failed: HTTP ${authResponse.code()} with an empty body")
            throw AuthException("Authentication failed: empty response body", httpStatus = authResponse.code())
        }

        if (auth.code != 1000) {
            SyncLog.e(TAG, "Authentication failed: HTTP ${authResponse.code()} (Code ${auth.code})")
            throw AuthException(
                "Authentication failed with code: ${auth.code}",
                httpStatus = authResponse.code(),
                protonCode = auth.code
            )
        }

        // Step 4: Verify server proof
        if (!SrpCrypto.verifyServerProof(proofs.expectedServerProof, auth.serverProof)) {
            SyncLog.e(TAG, "Server proof verification failed; sign-in stopped")
            throw AuthException("Server proof verification failed - possible MITM attack", untrustedServer = true)
        }

        // Log scope, password mode and second factor for diagnostic purposes (no secrets
        // here). The second factor matters on sight: whether an account uses one, and which,
        // changes the shape of the whole sign-in and is the first thing worth knowing when
        // someone reports being unable to get in.
        SyncLog.i(
            TAG,
            "SRP login OK for $username (passwordMode=${auth.passwordMode}, " +
                "scope=${auth.scope}, twoFactor=${describeTwoFactor(auth.twoFactor)})"
        )
        // Separate line, and only when there is something to say: the bitmask cannot
        // distinguish a hardware key from a passkey, but the account holder named each key
        // in Proton's settings, and that name is the most reliable signal available.
        describeFido2Keys(auth.twoFactor?.fido2)?.let { SyncLog.i(TAG, it) }

        val session = AccountSession(
            accountName = username,
            accessToken = auth.accessToken,
            refreshToken = auth.refreshToken,
            uid = auth.uid,
            keySalt = auth.keySalt,
            passwordMode = auth.passwordMode
        )

        // 2FA bitmask in `enabled`: bit 0 = TOTP, bit 1 = FIDO2.
        // We only support TOTP. If any 2FA is required but TOTP is not enabled (FIDO2-only
        // account), bail out with a typed exception so LoginActivity can show a clear message.
        // Continuing would let the API reject the auth at /auth/v4/2fa with an opaque error.
        val anyTwoFactor = auth.twoFactor != null && auth.twoFactor.enabled != 0
        val totpEnabled = auth.twoFactor?.totp == 1
        if (anyTwoFactor && !totpEnabled) {
            SyncLog.w(TAG, "Account $username uses FIDO2-only 2FA - not supported")
            throw Fido2OnlyException()
        }
        val requires2FA = totpEnabled

        return LoginResult(session = session, requires2FA = requires2FA, password = password)
    }

    /**
     * Read-only probe behind the srp_modulus debug command: the first step of a sign-in for
     * [username] (auth info), and the modulus signature check that [login] makes next. No proof is
     * computed or sent. Returns a line for the log, without the username.
     */
    suspend fun probeModulus(username: String): String {
        val response = apiClient.getAuthInfo(mapOf("Username" to username, "Intent" to "Proton"))
        val info = response.body()
        if (!response.isSuccessful || info == null || info.code != 1000) {
            // The error body is read only here, after body() came back empty.
            return "auth info failed: HTTP ${response.code()}" +
                (if (info != null) " (Code ${info.code})" else response.protonErrorSuffix())
        }
        return try {
            "modulus signature verified with Proton's modulus key (${SrpCrypto.readSignedModulus(info.modulus).size} bytes)"
        } catch (e: SrpCrypto.UnsignedModulusException) {
            "modulus REFUSED: ${e.message}"
        } catch (e: Exception) { // the pinned key not loading (a rotation gone wrong): keep GopenPGP's reason
            "modulus check failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Submit a TOTP 2FA code.
     */
    suspend fun submit2fa(session: AccountSession, totpCode: String) {
        run {
            val response = apiClient.submitTwoFactor(
                accountName = session.accountName,
                body = TwoFactorRequest(twoFactorCode = totpCode)
            )

            if (!response.isSuccessful) {
                // Read the API's own verdict before giving up. Without it an HTTP status is
                // all we get, and 401 (the session was rejected) reads identically to 422
                // with Code 8002 (the code was wrong) in a support log. A field report was
                // undiagnosable for exactly this reason: its screenshot carried more
                // information than its log.
                val errorBody = response.errorBody()?.string()
                val apiError = ProtonApiError.parse(errorBody)
                val detail = ProtonApiError.describe(errorBody).ifEmpty { " (no API error body)" }
                SyncLog.w(TAG, "2FA verification failed for ${session.accountName}: HTTP ${response.code()}$detail")
                if (apiError?.first == ProtonApiError.CODE_INVALID_LOGIN) throw TwoFactorRejectedException()
                throw AuthException(
                    "2FA verification failed: HTTP ${response.code()}$detail",
                    httpStatus = response.code(),
                    protonCode = apiError?.first
                )
            }

            @Suppress("UNCHECKED_CAST")
            val body = response.body()
                ?: throw AuthException("2FA response body is null", httpStatus = response.code())
            val code = (body["Code"] as? Double)?.toInt()
            if (code != 1000) {
                SyncLog.w(TAG, "2FA rejected for ${session.accountName}: Code $code")
                if (code == ProtonApiError.CODE_INVALID_LOGIN) throw TwoFactorRejectedException()
                throw AuthException("2FA failed with code: $code", httpStatus = response.code(), protonCode = code)
            }

            // INFO, not DEBUG: this is the milestone that says the second factor was
            // accepted. At debug level it only appeared when verbose happened to be on,
            // which is exactly when a stuck sign-in report would not have it.
            SyncLog.i(TAG, "2FA verification successful for ${session.accountName}")
        }
    }

    /**
     * Fetch user keys and unlock the key ring.
     * Must be called after login (and 2FA if required) completes.
     */
    suspend fun fetchAndUnlockUserKeys(session: AccountSession, password: String): AccountSession {
        return run {
            // Fetch user info to get the encrypted private key. The account name rides along as a
            // request tag, which is never sent.
            val userResponse = apiClient.getUser(session.accountName)
            if (!userResponse.isSuccessful) {
                // Code and Error only: the raw body went into both the log and the exception text.
                val errorBody = userResponse.errorBody()?.string()
                val detail = "HTTP ${userResponse.code()}" + ProtonApiError.describe(errorBody)
                SyncLog.e(TAG, "User info failed: $detail")
                throw AuthException(
                    "Failed to fetch user info: $detail",
                    httpStatus = userResponse.code(),
                    protonCode = ProtonApiError.parse(errorBody)?.first
                )
            }
            val user = userResponse.body()?.user
                ?: throw AuthException("Failed to fetch user info: empty response body")

            // Find the user key to unlock first.
            //
            // An account with post-quantum protection enabled has TWO primary active user keys, a
            // v4 and a v6, and the order the API lists them in is not ours to control. Every other
            // active key is loaded into the ring afterwards (see below), because Proton's own
            // clients hand the whole set to OpenPGP and let it route by PKESK rather than choosing.
            //
            // Falling back across ALL active keys matters: a real account failed here because the
            // only key flagged Primary was one the crypto layer could not parse, and the previous
            // fallback then re-selected that same unparseable key instead of trying its sibling.
            val activeKeys = user.keys?.filter { it.active == 1 }.orEmpty()
            val primaryCandidates = activeKeys.filter { it.primary == 1 }.ifEmpty { activeKeys }
            val primaryKey = primaryCandidates.firstOrNull { keyManager.canParseKey(it.privateKey) }
                ?: activeKeys.firstOrNull { keyManager.canParseKey(it.privateKey) }
                ?: primaryCandidates.firstOrNull()
            if (primaryCandidates.size > 1 || activeKeys.size > 1) {
                SyncLog.i(
                    TAG,
                    "${activeKeys.size} active user keys (${primaryCandidates.size} primary) for " +
                        "${session.accountName}; unlocking ${primaryKey?.id} first"
                )
            }

            val encPrivateKey = primaryKey?.privateKey
            val primaryKeyId = primaryKey?.id

            val totalKeys = user.keys?.size ?: 0
            SyncLog.i(
                TAG,
                "User ${session.accountName}: keys=$totalKeys (active=${activeKeys.size}), " +
                    "primaryKeyId=$primaryKeyId, passwordMode=${session.passwordMode}"
            )

            // Fetch proper key salt from /core/v4/keys/salts
            var keySaltForKey: String? = session.keySalt
            var saltSource = "auth-keySalt"
            var allSalts: List<me.proton.sync.api.models.KeySaltInfo>? = null
            if (primaryKeyId != null) {
                try {
                    val saltsResponse = apiClient.getKeySalts(session.accountName)
                    if (saltsResponse.isSuccessful) {
                        val salts = saltsResponse.body()?.keySalts
                        allSalts = salts
                        val matched = salts?.firstOrNull { it.id == primaryKeyId }?.keySalt
                        when {
                            matched != null -> {
                                keySaltForKey = matched
                                saltSource = "salts-by-keyid"
                            }
                            salts?.firstOrNull()?.keySalt != null -> {
                                keySaltForKey = salts.first().keySalt
                                saltSource = "salts-firstOrNull-fallback"
                            }
                            else -> {
                                saltSource = "auth-keySalt-fallback"
                            }
                        }
                    } else {
                        SyncLog.w(TAG, "Failed to fetch key salts: HTTP ${saltsResponse.code()}${saltsResponse.protonErrorSuffix()}, using auth keySalt")
                        saltSource = "auth-keySalt-on-http-${saltsResponse.code()}"
                    }
                } catch (e: Exception) {
                    SyncLog.w(TAG, "Failed to fetch key salts, using auth keySalt", e)
                    saltSource = "auth-keySalt-on-exception"
                }
            }
            SyncLog.i(
                TAG,
                "Salt selection for ${session.accountName}: source=$saltSource, " +
                    "saltLen=${keySaltForKey?.length ?: -1}"
            )

            // Update session with the encrypted private key and proper key salt
            var updatedSession = session.copy(encPrivateKey = encPrivateKey, keySalt = keySaltForKey)

            // Unlock the user's key ring if we have everything needed
            if (encPrivateKey != null && keySaltForKey != null) {
                // Unlock attempt is its own try/catch — wrong-passphrase failure here is the
                // signal that cached key material is stale (password change, two-password
                // mode set up after onboarding, etc.). Wipe the cached mailboxPassword and
                // surface a typed exception so the caller can show appropriate UI.
                try {
                    val mbPassword = keyManager.unlockUserKey(
                        accountName = session.accountName,
                        password = password,
                        keySalt = keySaltForKey,
                        armoredPrivateKey = encPrivateKey
                    )
                    updatedSession = updatedSession.copy(mailboxPassword = mbPassword)
                    SyncLog.d(TAG, "User key ring unlocked for ${session.accountName}")

                    // Load EVERY other active user key into the same ring. A post-quantum account
                    // holds two primary user keys (a v4 and a v6), Proton encrypts to either, and
                    // its own clients pass the whole set to OpenPGP rather than picking one. Each
                    // key has its OWN salt, so the passphrase has to be derived per key.
                    // Best-effort: a key that will not unlock must not fail a sign-in that has
                    // already succeeded.
                    for (extra in activeKeys) {
                        if (extra.id == primaryKeyId) continue
                        val extraSalt = allSalts?.firstOrNull { it.id == extra.id }?.keySalt
                        if (extraSalt == null) {
                            SyncLog.w(TAG, "No salt for additional user key ${extra.id}, skipping")
                            continue
                        }
                        keyManager.addUserKey(
                            accountName = session.accountName,
                            password = password,
                            keySalt = extraSalt,
                            armoredPrivateKey = extra.privateKey
                        )
                    }
                } catch (e: Exception) {
                    SyncLog.e(TAG, "Failed to unlock user key ring for ${session.accountName}: ${describeCauseChain(e)}", e)
                    sessionManager.clearKeyMaterial(session.accountName)
                    throw KeyUnlockException(
                        accountName = session.accountName,
                        passwordMode = session.passwordMode,
                        cause = e
                    )
                }

                // Fetch address keys and unlock them so contact decryption works.
                // Contacts are encrypted to the address key, not the primary user key.
                try {
                    val addressesResponse = apiClient.getAddresses(session.accountName)
                    if (addressesResponse.isSuccessful) {
                        val addresses = addressesResponse.body()?.addresses ?: emptyList()
                        val allAddressKeys = addresses.flatMap { addr ->
                            (addr.keys ?: emptyList()).onEach { it.addressEmail = addr.email }
                        }
                        if (allAddressKeys.isNotEmpty()) {
                            keyManager.unlockAddressKeys(
                                accountName = session.accountName,
                                password = password,
                                keySalt = keySaltForKey,
                                addressKeys = allAddressKeys
                            )
                            SyncLog.d(TAG, "Address keys fetched for ${session.accountName}: ${allAddressKeys.size} address-key entries across ${addresses.size} email addresses (each entry typically expands to a primary + a subkey when KeyManager unlocks them)")
                        } else {
                            SyncLog.d(TAG, "No address keys found for ${session.accountName}")
                        }
                    } else {
                        SyncLog.w(TAG, "Failed to fetch addresses: HTTP ${addressesResponse.code()}${addressesResponse.protonErrorSuffix()}")
                    }
                } catch (e: Exception) {
                    SyncLog.w(TAG, "Failed to fetch/unlock address keys for ${session.accountName}", e)
                }
            } else {
                SyncLog.w(TAG, "Missing key data for ${session.accountName}: " +
                    "keySalt=${keySaltForKey != null}, encPrivateKey=${encPrivateKey != null}")
            }

            updatedSession
        }
    }

    /**
     * A sign-in step failed. The fields let the sign-in screen name the cause without reading
     * [message], which stays technical and is meant for the log ([me.proton.sync.account.SignInErrors]).
     *
     * [httpStatus] and [protonCode] are null when the step never got that far. [untrustedServer]
     * means a modulus, server-proof or verification-URL check failed, so the answer may not have
     * come from Proton at all.
     */
    class AuthException(
        message: String,
        cause: Throwable? = null,
        val httpStatus: Int? = null,
        val protonCode: Int? = null,
        val untrustedServer: Boolean = false
    ) : Exception(message, cause)

    /**
     * Thrown when the account requires 2FA but only FIDO2 (security key / passkey) is
     * enabled, with no TOTP fallback. This app does not implement WebAuthn/FIDO2: third-party
     * Android clients can't run a FIDO2 ceremony against `proton.me` because Proton's
     * Digital Asset Links only authorise their own apps to claim that origin. Caller is
     * expected to surface a clear message asking the user to add a TOTP code as backup.
     */
    class Fido2OnlyException : Exception("Account uses FIDO2-only 2FA; this app supports TOTP only")

    /**
     * Proton rejected the authenticator code itself ([ProtonApiError.CODE_INVALID_LOGIN], which
     * for `/auth/v4/2fa` means a bad code).
     *
     * Typed so the sign-in screen can say "that code was not accepted, try again" instead of
     * surfacing a raw status. A user shown `2FA verification failed: HTTP 422` learns nothing
     * they can act on, and cannot tell it apart from a failure that has nothing to do with
     * what they typed.
     */
    class TwoFactorRejectedException : Exception("Two-factor code rejected by Proton")

    /**
     * Thrown when SRP login succeeded but the OpenPGP key ring could not be unlocked.
     * Most often this means the supplied passphrase is wrong for the key — either a
     * stale cached mailbox password, a remote password change, or the account uses
     * legacy two-password mode and the SRP login password is not the mailbox password.
     */
    class KeyUnlockException(
        val accountName: String,
        /** [AccountSession.passwordMode] at the time of the failure (1 or 2). */
        val passwordMode: Int,
        cause: Throwable
    ) : Exception("Failed to unlock keys for $accountName (passwordMode=$passwordMode)", cause)

    /**
     * Human-readable form of the two-factor bitmask (bit 0 = TOTP, bit 1 = FIDO2).
     *
     * Decoded rather than logged raw, because a support log should answer the question on
     * sight: `twoFactor=3` needs the encoding to hand, `TOTP+FIDO2` does not. The raw mask
     * is kept alongside so a method Proton adds later is still visible as `unknown` rather
     * than silently reading as "none".
     */
    private fun describeTwoFactor(info: TwoFactorInfo?): String {
        val mask = info?.enabled ?: 0
        if (mask == 0) return "none"
        val methods = mutableListOf<String>()
        if (mask and TWO_FACTOR_TOTP != 0) methods += "TOTP"
        if (mask and TWO_FACTOR_FIDO2 != 0) methods += "FIDO2"
        if (mask and (TWO_FACTOR_TOTP or TWO_FACTOR_FIDO2).inv() != 0) methods += "unknown"
        return "${methods.joinToString("+")} (mask=$mask)"
    }

    /**
     * Summary of the registered security keys, or null when the account has none.
     *
     * Answers the question the bitmask cannot: a YubiKey and a phone passkey both set the
     * FIDO2 bit, so `twoFactor=FIDO2` alone never says which is in play. The names come
     * from the account holder, and [RegisteredKey.attestationFormat] corroborates them
     * (`apple` and `android-key` indicate a platform passkey, `packed` typically a hardware
     * key). Credential IDs are never logged.
     */
    private fun describeFido2Keys(info: Fido2Info?): String? {
        val keys = info?.registeredKeys.orEmpty()
        if (keys.isEmpty()) return null
        val described = keys.joinToString(", ") { key ->
            val name = key.name?.takeIf { it.isNotBlank() } ?: "unnamed"
            val format = key.attestationFormat?.takeIf { it.isNotBlank() }
            if (format != null) "$name [$format]" else name
        }
        return "Security keys registered: ${keys.size} ($described)"
    }

    /**
     * Log a refused sign-in call and build its exception: the status plus Proton's Code and
     * Error, never the body (see [checkForCaptcha]: a 9001 body carries a bearer token). The Code
     * is what separates a wrong password (8002) from everything else a 422 can mean.
     */
    private fun refused(what: String, httpStatus: Int, errorBody: String?): AuthException {
        val line = "$what: HTTP $httpStatus" + ProtonApiError.describe(errorBody).ifEmpty { " (no API error body)" }
        SyncLog.e(TAG, line)
        return AuthException(line, httpStatus = httpStatus, protonCode = ProtonApiError.parse(errorBody)?.first)
    }

    /**
     * Compare the Proton server clock with this device and say so when they disagree.
     *
     * TOTP codes are derived from the clock, so a phone that has drifted rejects every code
     * with an error that looks nothing like "your clock is wrong". Emitting this on every
     * sign-in turns a whole class of unexplainable 2FA failure into one readable line, and it
     * costs a header we already receive.
     */
    private fun logClockSkew(headers: okhttp3.Headers) {
        val serverDate = headers.getDate("Date") ?: return
        val skewSeconds = (System.currentTimeMillis() - serverDate.time) / 1000
        if (kotlin.math.abs(skewSeconds) >= CLOCK_SKEW_WARN_SECONDS) {
            SyncLog.w(
                TAG,
                "Device clock is ${skewSeconds}s away from Proton's; authenticator codes are " +
                    "likely to be rejected until it is corrected"
            )
        } else {
            SyncLog.d(TAG, "Device clock is within ${skewSeconds}s of Proton's")
        }
    }

    private fun checkForCaptcha(errorBody: String) {
        try {
            val json = com.google.gson.JsonParser.parseString(errorBody).asJsonObject
            val code = json.get("Code")?.asInt
            if (code == ProtonApiError.CODE_HUMAN_VERIFICATION) {
                val details = json.getAsJsonObject("Details")
                val token = details?.get("HumanVerificationToken")?.asString ?: ""
                val webUrl = details?.get("WebUrl")?.asString ?: ""
                val methods = details?.getAsJsonArray("HumanVerificationMethods")
                    ?.map { it.asString } ?: listOf("captcha")
                // Fail closed: never hand a non-Proton (or non-https) verification URL
                // to the CAPTCHA WebView. A legitimate WebUrl is always on
                // verify.proton.me; anything else means a tampered response.
                if (webUrl.isNotEmpty() && !CaptchaHostPolicy.isAllowedUrl(webUrl)) {
                    SyncLog.w(TAG, "Captcha blocked: verification URL rejected (host not allowlisted)")
                    throw AuthException(
                        "Verification page rejected: not a Proton URL. Please try again or verify via an official Proton app.",
                        untrustedServer = true
                    )
                }
                // Milestone: without this a support log jumps straight from "SRP login OK"
                // to nothing, and you cannot tell whether Proton demanded verification at
                // all. Host only, never the token: it is a bearer credential for this
                // login attempt.
                SyncLog.i(
                    TAG,
                    "Human verification required (Code 9001): methods=$methods, " +
                        "host=${runCatching { android.net.Uri.parse(webUrl).host }.getOrNull() ?: "(none)"}"
                )
                throw CaptchaRequiredException(token, webUrl, methods)
            }
        } catch (e: CaptchaRequiredException) {
            throw e
        } catch (e: AuthException) {
            throw e
        } catch (_: Exception) {
            // Not a CAPTCHA error, ignore parse failures
        }
    }

    /**
     * Walk the `cause` chain of [t] and produce a compact string like
     * `IllegalStateException: foo <- Exception: bar <- IOException: baz`.
     * Lets the warn/error line carry the actual reason from the crypto layer without
     * relying on stack-trace gating.
     */
    private fun describeCauseChain(t: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 6) {
            parts.add("${current.javaClass.simpleName}: ${current.message ?: "<no message>"}")
            current = current.cause
            depth++
        }
        return parts.joinToString(" <- ")
    }

    companion object {
        private const val TAG = "SrpAuthHandler"

        /** Bits of [TwoFactorInfo.enabled]. Proton may add more; see [describeTwoFactor]. */
        private const val TWO_FACTOR_TOTP = 1
        private const val TWO_FACTOR_FIDO2 = 2

        /**
         * Report clock drift beyond this. A TOTP step is 30 seconds and Proton tolerates
         * roughly one step, so warning at 20 gives notice before codes actually start failing,
         * while staying above ordinary network latency.
         */
        private const val CLOCK_SKEW_WARN_SECONDS = 20
    }
}

