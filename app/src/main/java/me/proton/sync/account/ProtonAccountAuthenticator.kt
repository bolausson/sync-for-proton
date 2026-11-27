package me.proton.sync.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The authenticator of the app's main account type (me.proton.sync). It adds accounts through LoginActivity and hands
 * out no tokens: the app reads its own session directly (AccountSessionManager, peekAuthToken), never through
 * getAuthToken.
 */
class ProtonAccountAuthenticator(
    private val context: Context,
) : AbstractAccountAuthenticator(context) {

    override fun addAccount(
        response: AccountAuthenticatorResponse,
        accountType: String,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle {
        val intent = Intent(context, LoginActivity::class.java).apply {
            putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        }
        return Bundle().apply {
            putParcelable(AccountManager.KEY_INTENT, intent)
        }
    }

    /**
     * Refuses every caller. authenticator.xml sets customTokens="true", so Android passes each app's getAuthToken
     * request straight here, without its own permission prompt, and leaves checking the caller to the authenticator.
     * Up to 2.2.1 this returned the access token to any app that named the account. That was of low severity: the token
     * is useless on its own, because Proton's API also requires the session ID, which this never handed out (without it
     * Proton answers 401). customTokens stays true, so getAuthToken never hands out the copy Android stores for the app
     * (setAuthToken, wrapped by SecureStorage) either.
     */
    override fun getAuthToken(
        response: AccountAuthenticatorResponse,
        account: Account,
        authTokenType: String,
        options: Bundle?
    ): Bundle = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
        putString(AccountManager.KEY_ERROR_MESSAGE, "Sync for Proton does not hand out its tokens")
    }

    override fun editProperties(response: AccountAuthenticatorResponse, accountType: String): Bundle {
        val intent = Intent(context, me.proton.sync.settings.SyncSettingsActivity::class.java)
        return Bundle().apply {
            putParcelable(AccountManager.KEY_INTENT, intent)
        }
    }

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse,
        account: Account,
        options: Bundle?
    ): Bundle? = null

    override fun getAuthTokenLabel(authTokenType: String): String = "Proton Access Token"

    override fun updateCredentials(
        response: AccountAuthenticatorResponse,
        account: Account,
        authTokenType: String?,
        options: Bundle?
    ): Bundle? = null

    override fun hasFeatures(
        response: AccountAuthenticatorResponse,
        account: Account,
        features: Array<out String>
    ): Bundle {
        return Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}
