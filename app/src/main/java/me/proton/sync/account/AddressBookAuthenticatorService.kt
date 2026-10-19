package me.proton.sync.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import me.proton.sync.SyncConstants

/**
 * Stub authenticator for address book accounts.
 * These accounts are managed internally — users add/remove them
 * through the main Proton account, not directly.
 * Must be exported=true so Samsung/Google Contacts can discover it.
 */
class AddressBookAuthenticatorService : Service() {
    private lateinit var authenticator: AddressBookAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = AddressBookAuthenticator(this)
    }

    override fun onBind(intent: Intent?) = authenticator.iBinder

    private class AddressBookAuthenticator(val context: Context) : AbstractAccountAuthenticator(context) {
        override fun addAccount(
            response: AccountAuthenticatorResponse,
            accountType: String,
            authTokenType: String?,
            requiredFeatures: Array<out String>?,
            options: Bundle?
        ): Bundle {
            // Redirect to the main Proton Sync login activity
            val intent = Intent(context, LoginActivity::class.java).apply {
                putExtra(SyncConstants.EXTRA_ADD_ACCOUNT, true)
                putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            }
            return Bundle().apply {
                putParcelable(AccountManager.KEY_INTENT, intent)
            }
        }

        override fun editProperties(response: AccountAuthenticatorResponse, accountType: String) = Bundle()
        override fun confirmCredentials(response: AccountAuthenticatorResponse, account: Account, options: Bundle?) = null
        override fun getAuthToken(response: AccountAuthenticatorResponse, account: Account, authTokenType: String, options: Bundle?) = Bundle()
        override fun getAuthTokenLabel(authTokenType: String) = "Proton Contacts"
        override fun updateCredentials(response: AccountAuthenticatorResponse, account: Account, authTokenType: String?, options: Bundle?) = null
        override fun hasFeatures(response: AccountAuthenticatorResponse, account: Account, features: Array<out String>) = Bundle().apply {
            putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)
        }
    }
}
