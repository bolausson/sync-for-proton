package me.proton.sync.account

import android.accounts.Account
import android.accounts.AccountManager
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncLog
import me.proton.sync.crypto.SecureStorage
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountSessionManager @Inject constructor(
    private val accountManager: AccountManager
) {
    private val sessions = ConcurrentHashMap<String, AccountSession>()

    fun getSession(account: Account): AccountSession {
        return sessions.getOrPut(account.name) { loadFromAccountManager(account) }
    }

    fun getSession(accountName: String): AccountSession? {
        return sessions[accountName] ?: run {
            val account = getAccount(accountName) ?: return null
            loadFromAccountManager(account).also { sessions[accountName] = it }
        }
    }

    fun storeSession(account: Account, session: AccountSession) {
        sessions[account.name] = session
        // Persist to AccountManager (may silently fail if account not added yet — that's OK,
        // it will be persisted when onLoginComplete calls storeSession with the final account)
        try {
            // All secrets are wrapped via SecureStorage before they touch AccountManager
            // (AES-256-GCM via hardware Keystore; B64: obfuscation fallback without one).
            // passwordMode deliberately stays plaintext: it is parsed with toIntOrNull()
            // on load, and a wrapped value would parse to null and silently downgrade a
            // two-password (mode 2) account to mode 1, breaking key unlock.
            accountManager.setAuthToken(account, AUTH_TOKEN_TYPE, SecureStorage.encrypt(session.accessToken))
            accountManager.setUserData(account, KEY_REFRESH_TOKEN, SecureStorage.encrypt(session.refreshToken))
            accountManager.setUserData(account, KEY_UID, SecureStorage.encrypt(session.uid))
            accountManager.setUserData(account, KEY_PASSWORD_MODE, session.passwordMode.toString())
            session.keySalt?.let { accountManager.setUserData(account, KEY_SALT, SecureStorage.encrypt(it)) }
            session.encPrivateKey?.let { accountManager.setUserData(account, KEY_ENC_PRIVATE_KEY, SecureStorage.encrypt(it)) }
            session.mailboxPassword?.let {
                accountManager.setUserData(account, KEY_MAILBOX_PASSWORD, SecureStorage.encrypt(it))
            }
        } catch (_: Exception) {
            // Account may not exist in AccountManager yet during login flow
        }
    }

    /**
     * Wipe cached key material when an unlock failure proves it is stale (wrong
     * passphrase, e.g. password changed remotely or two-password mode set up after
     * onboarding). Tokens are left intact so a re-auth flow can reuse the session.
     *
     * Clears: in-memory `mailboxPassword`, persisted `mailbox_password` user data,
     * and the `KeyManager` is left to re-unlock from fresh credentials on next login.
     */
    fun clearKeyMaterial(accountName: String) {
        sessions[accountName]?.let { sessions[accountName] = it.copy(mailboxPassword = null) }
        val account = getAccount(accountName) ?: return
        try {
            accountManager.setUserData(account, KEY_MAILBOX_PASSWORD, null)
        } catch (_: Exception) {
            // Account may have been removed concurrently; nothing to do.
        }
    }

    fun updateTokens(accountName: String, accessToken: String, refreshToken: String) {
        // Always update the in-memory session (account may not be in AccountManager yet during login)
        val existing = sessions[accountName]
        if (existing != null) {
            sessions[accountName] = existing.copy(accessToken = accessToken, refreshToken = refreshToken)
        }
        // Also persist to AccountManager if the account exists there (encrypted at rest)
        val account = getAccount(accountName)
        if (account != null) {
            accountManager.setAuthToken(account, AUTH_TOKEN_TYPE, SecureStorage.encrypt(accessToken))
            accountManager.setUserData(account, KEY_REFRESH_TOKEN, SecureStorage.encrypt(refreshToken))
        }
    }

    /**
     * Get the latest in-memory session for a given key, without falling back to AccountManager.
     * Used during login flow when the account may not exist in AccountManager yet but tokens
     * may have been refreshed in memory by TokenRefreshAuthenticator.
     */
    fun getLatestSession(accountName: String): AccountSession? {
        return sessions[accountName]
    }

    fun removeSession(accountName: String) {
        sessions.remove(accountName)
    }

    fun removeStaleAccounts(activeNames: Set<String>) {
        val stale = sessions.keys - activeNames
        stale.forEach { sessions.remove(it) }
    }

    fun getAccount(accountName: String): Account? {
        return accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
            .firstOrNull { it.name == accountName }
    }

    fun getAllAccounts(): Array<Account> {
        return accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
    }

    private fun loadFromAccountManager(account: Account): AccountSession {
        // Raw stored values: either wrapped (KS:/B64:, written by 1.6.18+) or legacy
        // plaintext (pre-1.6.18). decryptStrict handles both without heuristics.
        val rawAccessToken = accountManager.peekAuthToken(account, AUTH_TOKEN_TYPE)
        val rawRefreshToken = accountManager.getUserData(account, KEY_REFRESH_TOKEN)
        val rawUid = accountManager.getUserData(account, KEY_UID)
        val rawKeySalt = accountManager.getUserData(account, KEY_SALT)
        val rawEncPrivateKey = accountManager.getUserData(account, KEY_ENC_PRIVATE_KEY)

        val session = AccountSession(
            accountName = account.name,
            accessToken = rawAccessToken?.let { SecureStorage.decryptStrict(it) } ?: "",
            refreshToken = rawRefreshToken?.let { SecureStorage.decryptStrict(it) } ?: "",
            uid = rawUid?.let { SecureStorage.decryptStrict(it) } ?: "",
            keySalt = rawKeySalt?.let { SecureStorage.decryptStrict(it) },
            encPrivateKey = rawEncPrivateKey?.let { SecureStorage.decryptStrict(it) },
            mailboxPassword = accountManager.getUserData(account, KEY_MAILBOX_PASSWORD)?.let {
                SecureStorage.decrypt(it)
            },
            passwordMode = accountManager.getUserData(account, KEY_PASSWORD_MODE)?.toIntOrNull() ?: 1
        )

        // Opportunistic migration: (re-)encrypt any secret still stored as legacy
        // plaintext OR as the B64: obfuscation fallback (which retries once the
        // Keystore recovers). Done as at-rest-only compare-and-set — NOT via
        // storeSession — so it never overwrites the in-memory session, and never
        // clobbers a token a concurrent updateTokens() rotated in the meantime.
        val needsMigration = listOfNotNull(rawAccessToken, rawRefreshToken, rawUid, rawKeySalt, rawEncPrivateKey)
            .any { it.isNotEmpty() && !SecureStorage.isHardwareEncrypted(it) }
        if (needsMigration) {
            SyncLog.i(TAG, "Encrypting stored credentials at rest for ${account.name}")
            migrateSecretsAtRest(account, rawAccessToken, rawRefreshToken, rawUid, rawKeySalt, rawEncPrivateKey)
        }

        return session
    }

    /**
     * Re-persist any not-yet-Keystore-encrypted secret (legacy plaintext, or a B64:
     * fallback from a prior Keystore outage) as real ciphertext, WITHOUT touching the
     * in-memory session. Each field is a compare-and-set against its currently stored
     * value: we only overwrite when it is STILL exactly what [loadFromAccountManager]
     * read, so a token that a concurrent [updateTokens] rotated + wrapped between the
     * read and here is left untouched instead of being clobbered with a stale value.
     */
    private fun migrateSecretsAtRest(
        account: Account,
        rawAccessToken: String?, rawRefreshToken: String?,
        rawUid: String?, rawKeySalt: String?, rawEncPrivateKey: String?,
    ) {
        try {
            rawAccessToken?.let { v ->
                if (v.isNotEmpty() && !SecureStorage.isHardwareEncrypted(v) &&
                    accountManager.peekAuthToken(account, AUTH_TOKEN_TYPE) == v
                ) {
                    accountManager.setAuthToken(account, AUTH_TOKEN_TYPE, SecureStorage.encrypt(v))
                }
            }
            migrateUserData(account, KEY_REFRESH_TOKEN, rawRefreshToken)
            migrateUserData(account, KEY_UID, rawUid)
            migrateUserData(account, KEY_SALT, rawKeySalt)
            migrateUserData(account, KEY_ENC_PRIVATE_KEY, rawEncPrivateKey)
        } catch (_: Exception) {
            // Best-effort; the next load retries. Never fatal to the session.
        }
    }

    private fun migrateUserData(account: Account, key: String, rawObserved: String?) {
        val v = rawObserved ?: return
        if (v.isEmpty() || SecureStorage.isHardwareEncrypted(v)) return
        if (accountManager.getUserData(account, key) == v) {
            accountManager.setUserData(account, key, SecureStorage.encrypt(v))
        }
    }

    companion object {
        private const val TAG = "AccountSession"
        const val AUTH_TOKEN_TYPE = "access"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_UID = "uid"
        private const val KEY_SALT = "key_salt"
        private const val KEY_ENC_PRIVATE_KEY = "enc_private_key"
        private const val KEY_MAILBOX_PASSWORD = "mailbox_password"
        private const val KEY_PASSWORD_MODE = "password_mode"
    }
}
