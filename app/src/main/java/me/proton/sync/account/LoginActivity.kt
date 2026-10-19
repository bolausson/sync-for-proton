package me.proton.sync.account

import android.Manifest
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.AndroidEntryPoint
import me.proton.sync.ProtonSyncApp
import me.proton.sync.api.SrpAuthHandler
import me.proton.sync.backup.BackupFlowController
import me.proton.sync.billing.SubscriptionManager
import me.proton.sync.db.SyncDatabase
import me.proton.sync.databinding.ActivityLoginBinding
import me.proton.sync.SyncConstants
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.SyncUtils
import me.proton.sync.R
import me.proton.sync.api.AuthInterceptor
import me.proton.sync.settings.SyncSettingsActivity
import javax.inject.Inject
import kotlinx.coroutines.*

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    @Inject lateinit var sessionManager: AccountSessionManager
    @Inject lateinit var srpAuthHandler: SrpAuthHandler
    @Inject lateinit var syncDatabase: SyncDatabase
    @Inject lateinit var subscriptionManager: SubscriptionManager
    @Inject lateinit var keyManager: me.proton.sync.crypto.KeyManager

    private lateinit var binding: ActivityLoginBinding
    private var accountAuthenticatorResponse: AccountAuthenticatorResponse? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Built in onCreate; drives the first-run "Import accounts & settings" restore. */
    private lateinit var backupController: BackupFlowController

    /** First-run only: true when this is an empty install (no accounts, not add/re-auth). */
    private var allowImport = false

    /**
     * SAF picker for restoring a `.sppbackup` before login. Registered as a property
     * initialiser so it exists before STARTED; the callback delegates to [backupController]
     * (assigned in onCreate, before any user tap can fire this).
     */
    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null && ::backupController.isInitialized) backupController.startImport(uri) }

    private var pendingLoginResult: SrpAuthHandler.LoginResult? = null
    private var pendingUsername: String? = null
    private var pendingPassword: String? = null

    /**
     * Set when SRP + 2FA succeeded but the account is in legacy two-password mode and
     * we are now waiting for the user to enter their mailbox password. While non-null,
     * the sign-in button submits the mailbox password instead of restarting login.
     */
    private var pendingMailboxPasswordResult: SrpAuthHandler.LoginResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Edge-to-edge wipes any XML padding on the root, so we apply the screen's
        // design padding here on top of the system-bar insets — otherwise the inputs
        // and login button render flush against the screen edges on gesture-nav phones.
        val hPad = resources.getDimensionPixelSize(R.dimen.login_screen_horizontal_padding)
        val vPad = resources.getDimensionPixelSize(R.dimen.login_screen_vertical_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(hPad + bars.left, vPad + bars.top, hPad + bars.right, vPad + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        @Suppress("DEPRECATION")
        accountAuthenticatorResponse =
            intent.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)

        binding.btnLogin.setOnClickListener { onButtonClick() }

        // If already logged in and not adding/re-authenticating, open settings
        val existingAccounts = AccountManager.get(this).getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
        val isAddAccount = accountAuthenticatorResponse != null
                || intent.getBooleanExtra(SyncConstants.EXTRA_ADD_ACCOUNT, false)
        val isReauth = intent.getBooleanExtra(SyncConstants.EXTRA_REAUTH, false)
        if (existingAccounts.isNotEmpty() && !isAddAccount && !isReauth) {
            startActivity(android.content.Intent(this, SyncSettingsActivity::class.java))
            finish()
            return
        }

        // First-run restore: offer to import accounts + settings from a .sppbackup before
        // signing in. Shown whenever there are NO accounts yet (fresh install, or reached
        // via "Connect" from the empty-state after the last account was removed) — hidden
        // only when adding a further account to an existing set, or re-authenticating.
        // Prime use-case: migrating between the Play and direct builds, which are signed
        // with different keys and so can't update over each other.
        allowImport = existingAccounts.isEmpty() && !isReauth
        backupController = BackupFlowController(
            activity = this,
            sessionManager = sessionManager,
            keyManager = keyManager,
            syncDatabase = syncDatabase,
            subscriptionManager = subscriptionManager,
            onImportComplete = {
                // Fired when the "Import done" dialog is dismissed. If accounts now exist,
                // continue to settings; a settings-only restore leaves the user on the
                // login screen (the restored settings apply once they sign in).
                if (!isFinishing && !isDestroyed &&
                    AccountManager.get(this).getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE).isNotEmpty()
                ) {
                    startActivity(android.content.Intent(this, SyncSettingsActivity::class.java))
                    finish()
                }
            },
        )
        binding.btnImportSettings.setOnClickListener { importBackupLauncher.launch(arrayOf("*/*")) }
        binding.importRow.visibility = if (allowImport) View.VISIBLE else View.GONE
        // Not gated on anything: the whole point is that it works when login does not.
        // LogViewerActivity reads logcat directly and needs no account.
        binding.btnViewLogs.setOnClickListener {
            startActivity(Intent(this, me.proton.sync.settings.LogViewerActivity::class.java))
        }

        // Request runtime permissions for contacts and calendar
        requestSyncPermissions()
    }

    private fun requestSyncPermissions() {
        val needed = mutableListOf<String>()
        val perms = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        for (p in perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun onButtonClick() {
        when {
            pendingMailboxPasswordResult != null -> submitMailboxPassword()
            pendingLoginResult != null -> submitTwoFactor()
            else -> performLogin()
        }
    }

    private fun performLogin() {
        val username = binding.inputUsername.text?.toString()?.trim() ?: ""
        val password = binding.inputPassword.text?.toString() ?: ""

        if (username.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.error_empty_credentials))
            return
        }

        // Until it finishes, this sign-in's session is stored under the typed username, which
        // storeSession below writes onto any account of that name. If that account was added with
        // another sign-in, its tokens and keys would be replaced by these and it would sync the other
        // Proton account's data (AccountIdentity). Refuse before anything is sent.
        if (AccountIdentity.takenByOtherSignIn(storedUsernames(), username, username)) {
            SyncLog.w("LoginActivity", "Sign-in refused: an account with another sign-in is already named like the typed username")
            showError(getString(R.string.error_login_name_taken, username))
            return
        }

        setLoading(true)
        clearError()

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    srpAuthHandler.login(username, password)
                }

                // Store session temporarily for subsequent API calls
                val account = Account(result.session.accountName, ProtonSyncApp.ACCOUNT_TYPE)
                sessionManager.storeSession(account, result.session)

                SyncLog.d("LoginActivity", "Login result: requires2FA=${result.requires2FA}")
                if (result.requires2FA) {
                    pendingLoginResult = result
                    show2FAInput()
                } else {
                    SyncLog.d("LoginActivity", "Skipping 2FA, going straight to finishLogin")
                    finishLogin(result)
                }
            } catch (e: SrpAuthHandler.CaptchaRequiredException) {
                // Launch CAPTCHA WebView
                // Host only. e.webUrl carries ?token=<HumanVerificationToken>, a bearer credential
                // for this login attempt, and this log is one users are asked to send to support.
                // Logging the full URL would hand that credential to whoever reads the log.
                SyncLog.i(
                    "LoginActivity",
                    "CAPTCHA required, launching WebView on " +
                        (runCatching { android.net.Uri.parse(e.webUrl).host }.getOrNull() ?: "(unknown host)")
                )
                val intent = android.content.Intent(this@LoginActivity, CaptchaActivity::class.java)
                intent.putExtra(CaptchaActivity.EXTRA_VERIFY_URL, e.webUrl)
                intent.putExtra(CaptchaActivity.EXTRA_TOKEN, e.token)
                pendingUsername = username
                pendingPassword = password
                @Suppress("DEPRECATION")
                startActivityForResult(intent, CAPTCHA_REQUEST_CODE)
            } catch (e: SrpAuthHandler.Fido2OnlyException) {
                showError(getString(R.string.error_fido2_not_supported))
            } catch (e: SrpAuthHandler.AuthException) {
                showError(e.message ?: getString(R.string.error_login_failed))
            } catch (e: Exception) {
                SyncLog.e("LoginActivity", "Login error", e)
                showError("${e.javaClass.simpleName}: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun submitTwoFactor() {
        val totpCode = binding.inputTotp.text?.toString()?.trim() ?: ""
        val loginResult = pendingLoginResult

        if (totpCode.isEmpty() || totpCode.length != 6) {
            showError(getString(R.string.error_invalid_totp))
            return
        }

        if (loginResult == null) {
            showError(getString(R.string.error_login_failed))
            return
        }

        setLoading(true)
        clearError()

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    srpAuthHandler.submit2fa(loginResult.session, totpCode)
                }
                pendingLoginResult = null
                // Don't setLoading(false) — finishLogin keeps the spinner
                finishLogin(loginResult)
                return@launch
            } catch (e: SrpAuthHandler.TwoFactorRejectedException) {
                // Proton rejected the code itself (Code 8002). Explicit rather than falling
                // through to the generic handler, which reaches the same string only by
                // accident of ordering, and says what the user can act on instead of a status.
                showError(getString(R.string.error_2fa_failed))
            } catch (e: SrpAuthHandler.AuthException) {
                if (isSessionExpired(e)) {
                    resetToLogin("Session expired. Please sign in again.")
                } else {
                    showError(e.message ?: getString(R.string.error_2fa_failed))
                }
            } catch (e: Exception) {
                if (isSessionExpired(e)) {
                    resetToLogin("Session expired. Please sign in again.")
                } else {
                    showError(getString(R.string.error_2fa_failed))
                }
            }
            setLoading(false)
        }
    }

    /**
     * After SRP + optional 2FA: fetch user keys, unlock key ring, finalize account.
     *
     * Branches on [AccountSession.passwordMode]:
     *  - `1` (single-password mode): the SRP login password also unlocks the keys, so
     *    `fetchAndUnlockUserKeys` is called immediately with `loginResult.password`.
     *  - `2` (legacy two-password mode): the SRP password will NOT unlock the keys.
     *    Show a separate mailbox-password prompt; on submit we re-call this method
     *    via [submitMailboxPassword] using the entered mailbox password.
     */
    private fun finishLogin(loginResult: SrpAuthHandler.LoginResult, unlockPassword: String? = null) {
        if (loginResult.session.passwordMode == 2 && unlockPassword == null) {
            // Two-password account, no mailbox password yet — prompt the user and wait.
            SyncLog.i("LoginActivity", "Two-password mode detected for ${loginResult.session.accountName}, prompting for mailbox password")
            pendingMailboxPasswordResult = loginResult
            setLoading(false)
            showMailboxPasswordInput()
            return
        }

        val effectivePassword = unlockPassword ?: loginResult.password
        setLoading(true)

        scope.launch {
            try {
                val finalSession = withContext(Dispatchers.IO) {
                    srpAuthHandler.fetchAndUnlockUserKeys(loginResult.session, effectivePassword)
                }
                SyncLog.d("LoginActivity", "Login complete with keys for ${finalSession.accountName}")
                setLoading(false)
                val reauthAccountName = resolveExistingAccount(finalSession)
                if (reauthAccountName != null) {
                    onReauthComplete(finalSession, reauthAccountName)
                } else {
                    showAliasDialog(finalSession)
                }
            } catch (e: SrpAuthHandler.KeyUnlockException) {
                // Wrong passphrase — for two-password mode, re-prompt; otherwise reset to login.
                setLoading(false)
                if (isPostQuantumKeyFailure(e)) {
                    // Not a password problem at all, so re-prompting would send the user round a
                    // loop that cannot succeed. Say what actually happened instead.
                    SyncLog.e("LoginActivity", "Key unlock failed: account uses post-quantum (v6) keys", e)
                    resetToLogin(getString(R.string.error_pqc_keys_unsupported))
                } else if (loginResult.session.passwordMode == 2) {
                    showError(getString(R.string.error_mailbox_password_wrong))
                    pendingMailboxPasswordResult = loginResult
                    showMailboxPasswordInput()
                } else {
                    SyncLog.w("LoginActivity", "Key unlock failed in single-password mode", e)
                    resetToLogin(getString(R.string.error_unlock_failed))
                }
            } catch (e: Exception) {
                if (isSessionExpired(e)) {
                    resetToLogin("Session expired. Please sign in again.")
                    return@launch
                }
                SyncLog.w("LoginActivity", "Key fetch failed (non-unlock error)", e)
                setLoading(false)
                resetToLogin(getString(R.string.error_login_failed))
            }
        }
    }

    private fun showMailboxPasswordInput() {
        binding.loginFields.visibility = View.GONE
        binding.totpFields.visibility = View.GONE
        binding.mailboxPasswordFields.visibility = View.VISIBLE
        binding.importRow.visibility = View.GONE
        binding.btnLogin.text = getString(R.string.btn_unlock)
        binding.inputMailboxPassword.requestFocus()
    }

    private fun submitMailboxPassword() {
        val mailboxPassword = binding.inputMailboxPassword.text?.toString() ?: ""
        val loginResult = pendingMailboxPasswordResult

        if (mailboxPassword.isEmpty()) {
            showError(getString(R.string.error_mailbox_password_empty))
            return
        }

        if (loginResult == null) {
            showError(getString(R.string.error_login_failed))
            return
        }

        clearError()
        SyncLog.i("LoginActivity", "Mailbox password submitted for ${loginResult.session.accountName}, attempting key unlock")
        // Consume the pending state so the button routes correctly while we work.
        pendingMailboxPasswordResult = null
        finishLogin(loginResult, unlockPassword = mailboxPassword)
    }

    /**
     * True when key unlock failed because a key uses an OpenPGP algorithm the parser does not know.
     *
     * Proton's opt-in post-quantum protection (rolled out 2026) issues OpenPGP **v6** keys using
     * the RFC 9980 composite algorithms, whose IDs start at 30. When keys went through
     * BouncyCastle's OpenPGP layer, which defines none of those IDs, such a key ring failed to
     * PARSE, long before any passphrase was tried, as
     * `IOException: unknown signature key algorithm: 30` from `SignaturePacket.parseV6`.
     *
     * Keys now go through GopenPGP, which reads v6 keys, and key selection prefers a key GopenPGP
     * can parse (KeyManager.canParseKey). So this is a fallback that is not expected to match on
     * the current key path. It is kept so that such a failure, should one still occur, is not
     * reported as a wrong password.
     *
     * Matched on the message rather than the algorithm number so that the other composite IDs
     * (30-34 signatures, 35-36 KEM) are covered as Proton widens the rollout. Deliberately narrow:
     * a genuinely wrong passphrase throws from the decryption path with a different message and
     * must keep its existing handling.
     */
    private fun isPostQuantumKeyFailure(e: Throwable): Boolean {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 6) {
            val msg = current.message?.lowercase().orEmpty()
            if ("unknown signature key algorithm" in msg || "unknown public key algorithm" in msg ||
                "unknown pgp public key algorithm" in msg
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * The account this sign-in re-authenticates, or null if it genuinely adds a new one.
     *
     * The test used to be `EXTRA_REAUTH && EXTRA_ACCOUNT_NAME != null`, and only one of the three
     * places that start a re-authentication ever set that boolean. The session-expired
     * notification, which is how nearly every re-authentication actually begins, set
     * EXTRA_ADD_ACCOUNT instead, and the authenticator's `getAuthToken` set neither. Both fell
     * through to the new-account path, so the user was asked to name an account they already had,
     * and accepting the offered default would have created a second account beside the first while
     * the first stayed signed out.
     *
     * The flag is no longer trusted on its own. An account already on the phone is decisive
     * whether or not the caller said so, which also means a caller added later cannot reintroduce
     * this by forgetting. Matching falls back to `proton_username` because the account name is
     * only ever whatever the user typed at setup and may have been renamed since, while the Proton
     * username is what they just signed in with.
     */
    private fun resolveExistingAccount(session: AccountSession): String? {
        val am = AccountManager.get(this)
        val accounts = am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
        if (accounts.isEmpty()) return null
        // The name extra says which account the CALLER had in mind. It does not say who just
        // signed in, and those can differ: the session-expired notification names one account
        // while the password manager fills another, or the user simply picks the wrong one. Taken
        // on trust, onReauthComplete would then store the signed-in account's tokens and keys onto
        // the named account's row - one Proton identity wearing another's name, syncing the wrong
        // person's calendar. So the named account is accepted only when it IS the identity that
        // signed in; a mismatch falls through to the username scan below rather than being trusted.
        // An account predating proton_username has none to compare, and is accepted as before.
        // "alice" and "alice@proton.me" count as the same identifier (AccountIdentity.canonical).
        fun isSameIdentity(account: android.accounts.Account): Boolean {
            val username = am.getUserData(account, "proton_username") ?: return true
            return AccountIdentity.sameSignIn(username, session.accountName)
        }
        intent.getStringExtra(EXTRA_ACCOUNT_NAME)?.let { named ->
            accounts.firstOrNull { it.name == named && isSameIdentity(it) }?.let { return it.name }
        }
        return accounts.firstOrNull {
            val username = am.getUserData(it, "proton_username")
            username != null && AccountIdentity.sameSignIn(username, session.accountName)
        }?.name
    }

    /** Each account on this phone by name, with the proton_username it was added with (null if it predates that). */
    private fun storedUsernames(): Map<String, String?> {
        val am = AccountManager.get(this)
        return am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
            .associate { it.name to am.getUserData(it, "proton_username") }
    }

    /**
     * Asks what to call the new account. [prefill] and [takenName] are set when the previous answer
     * was refused because another account already has that name.
     */
    private fun showAliasDialog(session: AccountSession, prefill: String? = null, takenName: String? = null) {
        val defaultName = if (session.accountName.contains("@")) {
            session.accountName
        } else {
            "${session.accountName}@proton.me"
        }

        val input = android.widget.EditText(this).apply {
            setText(prefill ?: defaultName)
            hint = defaultName
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setSelection(text.length)
        }
        val container = android.widget.FrameLayout(this).apply {
            val dp = (24 * resources.displayMetrics.density).toInt()
            setPadding(dp, (8 * resources.displayMetrics.density).toInt(), dp, 0)
            addView(input)
        }

        val message = getString(R.string.dialog_message_account_name)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_account_name))
            .setMessage(
                if (takenName == null) message
                else getString(R.string.error_account_name_taken, takenName) + "\n\n" + message
            )
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                val chosenName = input.text.toString().trim().ifEmpty { defaultName }
                // This dialog is only reached when no account on the phone is this sign-in
                // (resolveExistingAccount), so an account already called chosenName belongs to
                // another one, and onLoginComplete would provision this session over it. Ask again.
                if (AccountIdentity.takenByOtherSignIn(storedUsernames(), chosenName, session.accountName)) {
                    showAliasDialog(session, prefill = chosenName, takenName = chosenName)
                    return@setPositiveButton
                }
                // Mark the name as user-chosen so the v1.3.97+ recovery prompt
                // skips this account.
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .edit()
                    .putBoolean(SyncPreferences.displayNameUserChosenKey(chosenName), true)
                    .putBoolean(SyncPreferences.displayNameRecoveryOfferedKey(chosenName), true)
                    .apply()
                onLoginComplete(session, chosenName)
            }
            .show()
    }

    private fun onLoginComplete(session: AccountSession, displayName: String) {
        val protonUsername = session.accountName

        // Merge: take refreshed tokens from memory + keys/mailboxPassword from the returned session
        val latestTokens = sessionManager.getLatestSession(protonUsername)
        val displaySession = session.copy(
            accountName = displayName,
            accessToken = latestTokens?.accessToken ?: session.accessToken,
            refreshToken = latestTokens?.refreshToken ?: session.refreshToken
        )

        // Whether this sign-in ADDS an account or merely re-authenticates one that already
        // exists. It has to be read before provisioning, which creates the account, and it decides
        // everything below: a re-authentication must not be treated as a new setup.
        val isNewAccount = AccountManager.get(this)
            .getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
            .none { it.name == displayName }

        // Provision the parent + address-book accounts, persist the session, enable sync,
        // and fire an immediate sync. Shared verbatim with backup import so the two paths
        // can never drift (see AccountProvisioner).
        me.proton.sync.backup.AccountProvisioner.provision(
            context = this,
            sessionManager = sessionManager,
            finalSession = displaySession,
            protonUsername = protonUsername,
            // For a NEW account both are false on purpose: nothing is copied to the phone until
            // the chooser below has an answer, because the app used to start pulling every contact
            // and every calendar the moment an account was added. A re-authentication is the
            // opposite situation. The account is already set up, the user already chose what to
            // sync, and the only reason they are here is that the session expired, so it keeps its
            // settings and syncs straight away.
            requestInitialSync = !isNewAccount,
            enableSync = false,
        )

        val result = Bundle().apply {
            putString(AccountManager.KEY_ACCOUNT_NAME, displayName)
            putString(AccountManager.KEY_ACCOUNT_TYPE, ProtonSyncApp.ACCOUNT_TYPE)
        }
        android.widget.Toast.makeText(this, getString(R.string.toast_signed_in_as, displayName), android.widget.Toast.LENGTH_LONG).show()
        accountAuthenticatorResponse?.onResult(result)
        setResult(RESULT_OK)

        val goToSettings = {
            // Open settings instead of just closing
            startActivity(android.content.Intent(this, SyncSettingsActivity::class.java))
            finish()
        }
        // Only a new account is asked. Showing the chooser after a re-authentication asked somebody
        // to decide something they had already decided, on an account that was syncing perfectly
        // well a moment earlier, and reads as though signing in again had reset the whole thing.
        if (isNewAccount) askWhatToSync(displayName, goToSettings) else goToSettings()
    }

    /**
     * Asks which of the two sync types this account should use, before either has run.
     *
     * Both start unticked. That is the point: the first sync is now something the user chose rather
     * than something they discover afterwards, and somebody who only wants contacts never sees a
     * calendar appear on their phone. Dismissing without choosing leaves both off, which is safe,
     * recoverable, and clearly explained by the settings screen they land on next.
     */
    private fun askWhatToSync(displayName: String, then: () -> Unit) {
        val account = android.accounts.Account(displayName, ProtonSyncApp.ACCOUNT_TYPE)
        val abAccount = android.accounts.Account(displayName, ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
        val labels = arrayOf(getString(R.string.sync_contacts), getString(R.string.sync_calendar))
        val picked = booleanArrayOf(false, false)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.setup_choose_title)
            .setMultiChoiceItems(labels, picked) { _, which, isChecked -> picked[which] = isChecked }
            .setCancelable(false)
            .setNeutralButton(R.string.setup_choose_none) { _, _ -> then() }
            .setPositiveButton(R.string.setup_choose_save) { _, _ ->
                if (picked[0]) {
                    me.proton.sync.backup.AccountProvisioner
                        .enableSyncFor(abAccount, SyncConstants.AUTHORITY_CONTACTS)
                }
                if (picked[1]) {
                    me.proton.sync.backup.AccountProvisioner
                        .enableSyncFor(account, SyncConstants.AUTHORITY_CALENDAR)
                }
                then()
            }
            .show()
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            SyncLog.w("LoginActivity", "Could not request battery optimization exemption: ${e.message}")
        }
    }

    private fun show2FAInput() {
        binding.loginFields.visibility = View.GONE
        binding.totpFields.visibility = View.VISIBLE
        binding.importRow.visibility = View.GONE
        binding.btnLogin.text = getString(R.string.btn_verify)
        binding.inputTotp.requestFocus()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.btnImportSettings.isEnabled = !loading
        binding.inputUsername.isEnabled = !loading
        binding.inputPassword.isEnabled = !loading
        binding.inputTotp.isEnabled = !loading
        binding.inputMailboxPassword.isEnabled = !loading
    }

    /**
     * Re-authentication completed — update the existing account's session without creating a new one.
     */
    private fun onReauthComplete(session: AccountSession, existingAccountName: String) {
        val account = Account(existingAccountName, ProtonSyncApp.ACCOUNT_TYPE)
        val accountManager = AccountManager.get(this)
        SyncUtils.clearSyncPause(this, existingAccountName)
        // Merge: refreshed tokens from memory + keys/mailboxPassword from returned session
        val latestTokens = sessionManager.getLatestSession(session.accountName)
        val updatedSession = session.copy(
            accountName = existingAccountName,
            accessToken = latestTokens?.accessToken ?: session.accessToken,
            refreshToken = latestTokens?.refreshToken ?: session.refreshToken
        )
        sessionManager.storeSession(account, updatedSession)

        SyncLog.i("LoginActivity", "Re-authentication complete, keys unlocked")

        // Trigger sync
        val syncExtras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        val abAccount = Account(SyncUtils.addressBookAccountName(accountManager, account), ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
        ContentResolver.requestSync(abAccount, SyncConstants.AUTHORITY_CONTACTS, syncExtras)
        ContentResolver.requestSync(account, SyncConstants.AUTHORITY_CALENDAR, syncExtras)

        android.widget.Toast.makeText(this, getString(R.string.toast_reauth_success), android.widget.Toast.LENGTH_LONG).show()
        startActivity(android.content.Intent(this, SyncSettingsActivity::class.java))
        finish()
    }

    private fun showError(message: String) {
        binding.textError.text = message
        binding.textError.visibility = View.VISIBLE
    }

    private fun clearError() {
        binding.textError.visibility = View.GONE
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTCHA_REQUEST_CODE && resultCode == RESULT_OK) {
            val captchaToken = data?.getStringExtra(CaptchaActivity.RESULT_TOKEN)
            if (captchaToken != null && pendingUsername != null && pendingPassword != null) {
                SyncLog.i("LoginActivity", "CAPTCHA solved, retrying login")
                // Store the CAPTCHA token for the next auth attempt
                AuthInterceptor.pendingCaptchaToken = captchaToken
                // Retry login
                performLogin()
            }
        } else {
            showError(getString(R.string.error_captcha_cancelled))
            setLoading(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun isSessionExpired(e: Exception): Boolean {
        val msg = e.message ?: ""
        return msg.contains("401") || msg.contains("Unauthorized") ||
            msg.contains("Invalid access token") || msg.contains("Expired access token")
    }

    private fun resetToLogin(message: String) {
        pendingLoginResult = null
        pendingMailboxPasswordResult = null
        binding.totpFields.visibility = View.GONE
        binding.mailboxPasswordFields.visibility = View.GONE
        binding.loginFields.visibility = View.VISIBLE
        binding.importRow.visibility = if (allowImport) View.VISIBLE else View.GONE
        binding.btnLogin.text = getString(R.string.btn_sign_in)
        binding.inputTotp.text?.clear()
        binding.inputMailboxPassword.text?.clear()
        showError(message)
    }

    companion object {
        const val EXTRA_ACCOUNT_NAME = "extra_account_name"
        private const val CAPTCHA_REQUEST_CODE = 2001
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}
