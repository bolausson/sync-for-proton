package me.proton.sync.account

import android.content.Context
import me.proton.sync.R
import me.proton.sync.api.ProtonApiError
import me.proton.sync.api.SrpAuthHandler

/**
 * What the sign-in screen says when a sign-in step fails.
 *
 * The screen used to show the exception's own text, so a wrong password read
 * "Authentication failed: HTTP 422" and a dropped connection read "UnknownHostException: Unable to
 * resolve host ...", both in English whatever the phone's language. Each failure is now mapped to
 * a translated message by its typed fields ([SrpAuthHandler.AuthException.protonCode] and friends),
 * never by its text; the exception's message stays technical and goes to the log only.
 *
 * The codes mapped are the ones Proton's own sign-in handles (WebClients LoginForm.tsx for 8100
 * and 8002, LoginContainer.tsx for 10003) plus 12087, a solved CAPTCHA Proton did not accept.
 * Any other refusal gets a generic message with the status and code appended, so a user reporting
 * it can still quote something useful.
 */
internal object SignInErrors {

    enum class Kind {
        WRONG_CREDENTIALS,
        VERIFICATION_REFUSED,
        ACCOUNT_DISABLED,
        SSO_ONLY,
        UNTRUSTED_SERVER,
        SESSION_EXPIRED,
        NETWORK,
        PROTON_REFUSED,
        UNEXPECTED,
    }

    fun kindOf(e: Throwable): Kind = when (e) {
        is SrpAuthHandler.AuthException -> when {
            // First: a check that failed means the answer may not be Proton's, so its code means nothing.
            e.untrustedServer -> Kind.UNTRUSTED_SERVER
            e.protonCode == ProtonApiError.CODE_INVALID_LOGIN -> Kind.WRONG_CREDENTIALS
            e.protonCode == ProtonApiError.CODE_TOKEN_INVALID -> Kind.VERIFICATION_REFUSED
            e.protonCode == ProtonApiError.CODE_ACCOUNT_DISABLED -> Kind.ACCOUNT_DISABLED
            e.protonCode == ProtonApiError.CODE_SWITCH_TO_SSO -> Kind.SSO_ONLY
            e.httpStatus == 401 -> Kind.SESSION_EXPIRED
            else -> Kind.PROTON_REFUSED
        }
        is java.io.IOException -> Kind.NETWORK
        else -> Kind.UNEXPECTED
    }

    /**
     * `HTTP 422, code 2001`, `HTTP 503` or `code 2001` for a Proton refusal, or null when there is
     * nothing to quote. Appended to the generic message so a support request can name the cause.
     */
    fun supportCode(e: Throwable): String? {
        if (e !is SrpAuthHandler.AuthException) return null
        val parts = listOfNotNull(e.httpStatus?.let { "HTTP $it" }, e.protonCode?.let { "code $it" })
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * True when Proton no longer accepts the session, so the sign-in has to start over.
     *
     * An [SrpAuthHandler.AuthException] that carries its status is judged by that status alone: its
     * message now holds Proton's Code and Error, where "401" can turn up inside an unrelated code
     * (24010) or text. Every other exception keeps the old match on its message.
     */
    fun isSessionExpired(e: Throwable): Boolean {
        if (e is SrpAuthHandler.AuthException && e.httpStatus != null) return e.httpStatus == 401
        val msg = e.message ?: ""
        return msg.contains("401") || msg.contains("Unauthorized") ||
            msg.contains("Invalid access token") || msg.contains("Expired access token")
    }

    /**
     * The translated text for [e]. In the two-factor step ([twoFactor]) a refusal or an unexpected
     * failure reads as a failed verification, as it always has, since the password was already accepted.
     */
    fun message(context: Context, e: Throwable, twoFactor: Boolean = false): String {
        val kind = kindOf(e)
        return when (kind) {
            Kind.WRONG_CREDENTIALS ->
                // At auth/v4/2fa, 8002 means the code was wrong, not the password.
                context.getString(if (twoFactor) R.string.error_2fa_failed else R.string.error_login_failed)
            Kind.VERIFICATION_REFUSED -> context.getString(R.string.error_login_verification_refused)
            Kind.ACCOUNT_DISABLED -> context.getString(R.string.error_login_account_disabled)
            Kind.SSO_ONLY -> context.getString(R.string.error_login_sso_unsupported)
            Kind.UNTRUSTED_SERVER -> context.getString(R.string.error_login_untrusted_server)
            Kind.SESSION_EXPIRED -> context.getString(R.string.error_session_expired)
            Kind.NETWORK -> context.getString(R.string.error_network)
            Kind.PROTON_REFUSED -> {
                val text = context.getString(if (twoFactor) R.string.error_2fa_failed else R.string.error_login_proton_refused)
                supportCode(e)?.let { "$text ($it)" } ?: text
            }
            Kind.UNEXPECTED ->
                if (twoFactor) context.getString(R.string.error_2fa_failed)
                else context.getString(R.string.error_login_unexpected, e.javaClass.simpleName)
        }
    }
}
