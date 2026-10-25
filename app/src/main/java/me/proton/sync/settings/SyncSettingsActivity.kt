package me.proton.sync.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.animation.ValueAnimator
import android.content.ContentResolver
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import dagger.hilt.android.AndroidEntryPoint
import me.proton.sync.ProtonSyncApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.sync.FileLogController
import me.proton.sync.SyncConstants
import me.proton.sync.SyncLog
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.SyncPreferences
import me.proton.sync.SyncScheduling
import me.proton.sync.SyncUtils
import me.proton.sync.SyncWorker
import me.proton.sync.EventLoopWorker
import me.proton.sync.R
import me.proton.sync.calendar.IcalTimeZoneUpdates
import me.proton.sync.calendar.TimeZoneDebugCommands
import me.proton.sync.calendar.TimeZoneRuleSource
import me.proton.sync.calendar.TzDataFreshness
import me.proton.sync.contacts.ContactNameMode
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.account.LoginActivity
import me.proton.sync.account.SignInErrors
import me.proton.sync.api.protonErrorSuffix
import me.proton.sync.backup.BackupFlowController
import me.proton.sync.backup.BackupFormat
import me.proton.sync.billing.BillingUi
import me.proton.sync.billing.ScheduledChange
import me.proton.sync.billing.SubscriptionGate
import me.proton.sync.billing.SubscriptionManager
import me.proton.sync.billing.SubscriptionState
import me.proton.sync.billing.scheduledChangeLabel
import me.proton.sync.billing.trialDaysLeft
import me.proton.sync.contacts.ConflictResolutionReceiver
import me.proton.sync.databinding.ActivitySettingsBinding
import me.proton.sync.databinding.SettingsAccountCardBinding
import me.proton.sync.databinding.SettingsSubscriptionCardBinding
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class SyncSettingsActivity : AppCompatActivity() {

    @Inject lateinit var sessionManager: AccountSessionManager
    @Inject lateinit var syncDatabase: me.proton.sync.db.SyncDatabase
    @Inject lateinit var subscriptionManager: SubscriptionManager
    @Inject lateinit var photoSizeCalibrator: me.proton.sync.contacts.PhotoSizeCalibrator
    @Inject lateinit var keyManager: me.proton.sync.crypto.KeyManager
    @Inject lateinit var srpAuthHandler: me.proton.sync.api.SrpAuthHandler

    /** Cached AccountManager instance — same singleton each call, so look it up once. */
    private val accountManager: AccountManager by lazy { AccountManager.get(this) }

    // ---- Backup / restore (SAF) ----
    // Launchers must live on the Activity (Android requires registerForActivityResult to
    // be called before STARTED); their callbacks delegate to [backupController], which
    // owns all of the dialog flow. The controller is built in onCreate after Hilt has
    // injected the dependencies it needs.
    private lateinit var backupController: BackupFlowController

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.MIME)
    ) { uri -> if (uri != null) backupController.onExportUriChosen(uri) }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) backupController.startImport(uri) }

    // Folder picker for "Save logs to file". On success we persist a long-lived write grant
    // for the tree, remember it, and start the file sink; on cancel we revert the switch
    // unless logging was already active (e.g. user just wanted to keep the existing folder).
    private val pickLogFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { /* some providers don't grant persistably; sink still tries */ }
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(SyncPreferences.KEY_LOG_FOLDER_URI, uri.toString())
                .putBoolean(SyncPreferences.KEY_FILE_LOGGING, true)
                .apply()
            FileLogController.enable(this, uri)
            SyncLog.i("SyncSettings", "File logging enabled by user")
        } else if (!FileLogController.isActive()) {
            // User backed out without ever choosing a folder — leave the feature off.
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(SyncPreferences.KEY_FILE_LOGGING, false)
                .apply()
        }
        refreshFileLoggingUi()
    }

    private lateinit var binding: ActivitySettingsBinding

    /**
     * Tracks the rendered account cards alongside the accounts they represent so the
     * sync-status listener can flip each card's spinner without re-inflating. Rebuilt
     * by [buildAccountCards] every time the activity resumes or the account list changes.
     */
    private data class AccountCardHandle(
        val parentAccount: Account,
        val abAccount: Account,
        val binding: SettingsAccountCardBinding,
        /**
         * Re-reads the last-sync / last-fail timestamps from SharedPreferences and
         * updates the card's date labels in place. Called from [refreshSyncIndicators]
         * so the labels reflect a fresh sync result the moment it lands, even if the
         * activity is still on screen and hasn't been resumed.
         */
        val refreshSyncDates: () -> Unit
    )
    private val accountCardHandles = mutableListOf<AccountCardHandle>()

    /** Handle returned by [ContentResolver.addStatusChangeListener]; null when unregistered. */
    private var syncStatusObserverHandle: Any? = null

    /**
     * Safety-net poll for the sync indicators. The SYNC_OBSERVER_TYPE_ACTIVE listener can fire
     * just BEFORE [ContentResolver.isSyncActive] reflects the final inactive transition, so the
     * LAST account to finish syncing could keep a stuck spinner until the screen is reopened.
     * While any account is active or queued, [refreshSyncIndicators] re-arms this runnable so the
     * spinner clears within one interval even if no further listener event arrives.
     */
    private val syncPollRunnable = Runnable { refreshSyncIndicators() }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Accounts where the user just pressed "Sync now" / "Sync all accounts" but Android
     * hasn't promoted the request to active yet — typically because another account is
     * holding the per-authority slot. Stored as `accountName → requestTimestampMs` and
     * trimmed by [USER_REQUEST_TTL_MS] so a stale entry doesn't keep the indicator on
     * forever after the activity is resumed days later.
     *
     * Why we track this ourselves instead of using `ContentResolver.isSyncPending`: that
     * flag is also true for any account with auto-sync enabled and a periodic sync
     * scheduled (i.e. always), so it can't distinguish "queued right now" from
     * "scheduled for later".
     */
    private val userRequestedAccounts = mutableMapOf<String, Long>()
    private val USER_REQUEST_TTL_MS = 60_000L

    private companion object {
        /** Recipient of the "Send report" e-mail: the app's support address, as on its website. */
        const val REPORT_EMAIL = "support+spp@olausson.de"

        /** Debug builds only: the adb command extra, see [handleDebugCommand]. */
        const val DEBUG_COMMAND_EXTRA = "debug_command"

        /** How long after returning to this screen the paused or unpaid licence is checked again. */
        const val LICENCE_RECHECK_DELAY_MS = 20_000L

        /** savedInstanceState key for the in-flight export's include-accounts choice. */
        const val KEY_PENDING_EXPORT_INCLUDE = "pending_export_include_accounts"

        /** savedInstanceState key: the outdated-time-zone-data notice was up, unanswered, when the screen went. */
        const val KEY_TZ_NOTICE_SHOWING = "tz_outdated_notice_showing"

        /** Re-check interval for the sync-indicator safety poll (clears a stuck last-account spinner). */
        const val SYNC_POLL_INTERVAL_MS = 1200L
    }

    /** This screen was re-created from saved state (rotation, process death): no start-up notices then. */
    private var restoredInstance = false

    /** Opened by an adb debug command ([handleDebugCommand]): no start-up notices either, except tz_warning show. */
    private var openedByDebugCommand = false

    /** The battery dialog went up in this onCreate: the next start-up dialog follows when it is dismissed. */
    private var batteryDialogShown = false

    /** The outdated-time-zone-data notice, while it is up: kept to re-show it after a re-creation, and to close it. */
    private var tzNoticeDialog: AlertDialog? = null

    /** Re-created while the notice was up unanswered (rotation, dark mode): it comes back, it is not "shown". */
    private var resumeTzNotice = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        restoredInstance = savedInstanceState != null
        resumeTzNotice = savedInstanceState?.getBoolean(KEY_TZ_NOTICE_SHOWING, false) == true
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Build the backup controller now that Hilt has injected sessionManager,
        // syncDatabase, and subscriptionManager. The launchers above already reference
        // backupController in their callbacks — those only fire on user action after
        // onCreate completes, so the lateinit ordering is safe.
        backupController = BackupFlowController(
            activity = this,
            sessionManager = sessionManager,
            keyManager = keyManager,
            syncDatabase = syncDatabase,
            subscriptionManager = subscriptionManager,
            // An import can change the device-wide time-zone switch too (ImportManager applies it).
            onImportComplete = { buildAccountCards(); refreshTimeZoneUpdatesUi() },
        )

        // Restore the in-flight export choice if the Activity was recreated while the SAF
        // create-document picker was in front (otherwise a full backup could silently
        // downgrade to settings-only). The passphrase is collected after the picker, so
        // nothing secret is persisted here.
        savedInstanceState?.let {
            backupController.restoreState(it, KEY_PENDING_EXPORT_INCLUDE)
        }

        binding.btnAddAccount.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            intent.putExtra(SyncConstants.EXTRA_ADD_ACCOUNT, true)
            startActivity(intent)
        }

        binding.textVersion.text = "${getString(R.string.app_name)} ${getString(R.string.version_label)}"
        // Append the live Room schema version, e.g. "… v1.6.13 (db 7)", so a migration can be
        // verified just by reading this line (or asking a user). Read off the main thread since
        // touching readableDatabase opens the DB (and applies any pending migration).
        CoroutineScope(Dispatchers.IO).launch {
            val dbVersion = try { syncDatabase.openHelper.readableDatabase.version } catch (_: Exception) { -1 }
            withContext(Dispatchers.Main) {
                binding.textVersion.text =
                    "${getString(R.string.app_name)} ${getString(R.string.version_label)} (db $dbVersion)"
            }
        }

        binding.linkPrivacyPolicy.setOnClickListener { openExternalUrl(getString(R.string.url_privacy_policy)) }
        binding.linkTermsConditions.setOnClickListener { openExternalUrl(getString(R.string.url_terms_conditions)) }
        binding.linkThirdPartyLicences.setOnClickListener { openLicences() }

        binding.btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        binding.btnSendReport.setOnClickListener { sendReport() }

        binding.btnExportBackup.setOnClickListener {
            backupController.showExportBackupDialog(exportBackupLauncher)
        }
        binding.btnImportBackup.setOnClickListener {
            // "*/*" because the .sppbackup extension has no registered MIME on most devices.
            importBackupLauncher.launch(arrayOf("*/*"))
        }
        binding.btnDataBackups.setOnClickListener {
            startActivity(Intent(this, BackupSettingsActivity::class.java))
        }
        // Start-up dialogs one at a time, posted so they show after layout: the battery dialog (shown
        // synchronously below) goes first, and its dismissal brings the next; then the outdated-time-zone-data
        // notice, otherwise the first-run nudge to set up backups. The battery dialog comes on every open while
        // the app is not exempt, so the notice used to wait behind it for good.
        binding.root.post {
            if (batteryDialogShown) return@post
            showNextStartupNotice()
        }
        bindInfo(binding.infoBackup, R.string.backup_section_title, R.string.info_backup_body)

        // Debug-only: empirical probe for Proton's contact-photo size limit.
        if (me.proton.sync.BuildConfig.DEBUG) {
            binding.labelDebugSection.visibility = View.VISIBLE
            binding.btnCalibratePhotoSize.visibility = View.VISIBLE
            binding.btnCalibratePhotoSize.setOnClickListener { showCalibratePhotoSizeDialog() }
            binding.btnSimulateVanished.visibility = View.VISIBLE
            binding.btnSimulateVanished.setOnClickListener { showSimulateVanishedDialog() }
        }

        setupSyncAllAccountsButton()

        // Debug builds only: run a command passed by adb through DebugCommandActivity. Never on a restore:
        // the system keeps the original intent, extras and all.
        handleDebugCommand(intent, restored = savedInstanceState != null)

        setupVerboseLoggingToggle()
        setupFileLoggingToggle()
        refreshTimeZoneUpdatesUi()

        // Request battery optimization exemption if not already granted
        batteryDialogShown = requestBatteryOptimizationIfNeeded()

        binding.btnBatterySettings.setOnClickListener {
            try {
                // Open app-specific settings where battery optimization can be changed
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    startActivity(Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS))
                }
            }
        }

        // Calm redesign: the status hero + quick-tools delegate to the (hidden) original
        // buttons so every existing handler is reused unchanged.
        binding.btnHeroSyncAll.setOnClickListener { binding.btnSyncAllAccounts.performClick() }
        binding.btnHeroSyncAll.setOnLongClickListener { binding.btnSyncAllAccounts.performLongClick() }
        binding.tileLogs.setOnClickListener { binding.btnViewLogs.performClick() }
        binding.tileBackup.setOnClickListener { binding.btnDataBackups.performClick() }
        binding.tileBattery.setOnClickListener { binding.btnBatterySettings.performClick() }
        binding.btnEmptyConnect.setOnClickListener { binding.btnAddAccount.performClick() }
        binding.btnEmptyImport.setOnClickListener { importBackupLauncher.launch(arrayOf("*/*")) }
        updateHeroStats()

        setupLanguageSelector()

        setupSubscriptionBanner()

        // Tap-to-explain (i) icons on the main-screen settings rows.
        bindInfo(binding.infoLanguage, R.string.label_language, R.string.info_language_body)
        bindTimeZoneUpdatesInfo()
        bindInfo(binding.infoBackgroundSync, R.string.label_background_sync_title, R.string.info_background_sync_body)
        bindInfo(binding.infoVerboseLogging, R.string.label_verbose_logging_title, R.string.info_verbose_logging_body)
        bindInfo(binding.infoFileLogging, R.string.label_file_logging_title, R.string.info_file_logging_body)
        bindInfo(binding.infoBtnSyncAllAccounts, R.string.btn_sync_all_accounts, R.string.info_btn_sync_all_accounts_body)

        buildAccountCards()
    }

    /**
     * Finds names Android invented that an earlier version already wrote to Proton, and offers to
     * take them off again.
     *
     * Two steps on purpose. The scan reads and reports; only the second dialog writes, and it
     * states the count before it does. This is the one place in the feature that changes anything
     * on the server.
     */
    private fun repairInventedNames(account: android.accounts.Account) {
        val progress = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.label_name_repair)
            .setMessage(R.string.name_repair_scanning)
            .setCancelable(false)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        val deps = dagger.hilt.android.EntryPointAccessors.fromApplication(
            applicationContext, me.proton.sync.backup.BackupDependenciesEntryPoint::class.java
        )
        val repair = me.proton.sync.contacts.ContactNameRepair(
            deps.apiClient(), deps.contactDecryptor(), deps.sessionManager(), deps.keyManager()
        )

        val job = lifecycleScope.launch {
            val findings = try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repair.scan(account) { _, _ -> }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    this@SyncSettingsActivity,
                    getString(R.string.name_repair_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            progress.dismiss()
            if (findings.isEmpty()) {
                androidx.appcompat.app.AlertDialog.Builder(this@SyncSettingsActivity)
                    .setTitle(R.string.label_name_repair)
                    .setMessage(R.string.name_repair_none)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            val preview = findings.joinToString(LINE_BREAK) {
                it.displayName + "  ->  " + it.inventedName
            }
            androidx.appcompat.app.AlertDialog.Builder(this@SyncSettingsActivity)
                .setTitle(R.string.label_name_repair)
                .setMessage(
                    getString(R.string.name_repair_found, findings.size) +
                        LINE_BREAK + LINE_BREAK + preview
                )
                .setPositiveButton(R.string.name_repair_action) { _, _ ->
                    lifecycleScope.launch {
                        val outcome = try {
                            withContext(kotlinx.coroutines.Dispatchers.IO) {
                                repair.repair(account, findings, syncDatabase.contactSyncDao())
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // The screen went away. Reporting that as a repair failure told the user
                            // their contacts were left half-fixed by an error, when they had simply
                            // navigated off, and swallowing it here would also break cancellation.
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(
                                this@SyncSettingsActivity,
                                getString(R.string.name_repair_failed, e.message ?: e.javaClass.simpleName),
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        val text = if (outcome.failed == 0) {
                            getString(R.string.name_repair_done, outcome.repaired)
                        } else {
                            getString(R.string.name_repair_partial, outcome.repaired, outcome.failed)
                        }
                        Toast.makeText(this@SyncSettingsActivity, text, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
        progress.setOnShowListener {
            progress.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener {
                    job.cancel()
                    progress.dismiss()
                }
        }
        progress.show()
    }

    /** Separates a choice from the sentence explaining what it does. */
    private val LINE_BREAK = System.lineSeparator()

    private fun nameModeLabel(mode: ContactNameMode): Int = when (mode) {
        ContactNameMode.ANDROID -> R.string.name_mode_android
        ContactNameMode.NICKNAME -> R.string.name_mode_nickname
        ContactNameMode.GIVEN -> R.string.name_mode_given
        ContactNameMode.FAMILY -> R.string.name_mode_family
    }

    /**
     * The four choices, each with the consequence spelled out underneath.
     *
     * Written as outcomes rather than field names because the field name alone does not tell
     * anybody that one of these options is the reason a contact currently reads "app@example com".
     */
    private fun chooseNameMode(accountName: String, onChosen: (ContactNameMode) -> Unit) {
        val modes = listOf(
            ContactNameMode.ANDROID, ContactNameMode.NICKNAME,
            ContactNameMode.GIVEN, ContactNameMode.FAMILY,
        )
        val labels = modes.map {
            val text = getString(nameModeLabel(it)) + LINE_BREAK + getString(nameModeDescription(it))
            android.text.SpannableString(text)
        }.toTypedArray<CharSequence>()
        val current = ContactNameMode.of(this, accountName)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.name_mode_title)
            .setSingleChoiceItems(labels, modes.indexOf(current)) { dialog, which ->
                val chosen = modes[which]
                dialog.dismiss()
                if (chosen == current) return@setSingleChoiceItems
                ContactNameMode.set(this, accountName, chosen)
                onChosen(chosen)
                Toast.makeText(this, R.string.name_mode_changed, Toast.LENGTH_LONG).show()
                // The re-apply rides the next sync rather than starting one here: it only rewrites
                // contacts Proton has no structured name for, and it should not queue a full sync
                // behind a setting the user may still be reading about.
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun nameModeDescription(mode: ContactNameMode): Int = when (mode) {
        ContactNameMode.ANDROID -> R.string.name_mode_android_desc
        ContactNameMode.NICKNAME -> R.string.name_mode_nickname_desc
        ContactNameMode.GIVEN -> R.string.name_mode_given_desc
        ContactNameMode.FAMILY -> R.string.name_mode_family_desc
    }

    /**
     * Wire an (i) info icon to open a dialog explaining the option it sits next to.
     * Title is the option's own label; body is the verbose explanation (with any
     * real-world caveats). Used for nearly every settings row (the time-zone row has its own,
     * [bindTimeZoneUpdatesInfo]); see [bindAccountCardInfoIcons] for the per-account-card icons.
     */
    private fun bindInfo(icon: View, @androidx.annotation.StringRes titleRes: Int, @androidx.annotation.StringRes bodyRes: Int) {
        icon.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setPositiveButton(R.string.btn_info_dismiss, null)
                .show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Non-secret: lets a full export survive a recreation during the SAF picker.
        // The actual backup orchestration lives in [BackupFlowController].
        backupController.saveState(outState, KEY_PENDING_EXPORT_INCLUDE)
        // A notice still waiting for its answer comes back on the re-created screen.
        outState.putBoolean(KEY_TZ_NOTICE_SHOWING, tzNoticeDialog?.isShowing == true)
    }

    override fun onDestroy() {
        // Close the notice with the window it belongs to (no leaked window); not an answer.
        tzNoticeDialog?.dismiss()
        tzNoticeDialog = null
        super.onDestroy()
    }

    /** The start-up dialog after the battery dialog: the outdated-time-zone-data notice, else the backup nudge. */
    private fun showNextStartupNotice() {
        if (isFinishing || isDestroyed) return
        if (maybeShowTzDataNotice()) return
        maybeShowBackupReminder()
    }

    /**
     * One-time first-run nudge recommending regular backups — shown to existing users
     * too (they're the cohort most at risk right now). Offers a one-tap route to enable
     * the in-app automatic backups, or a link to Proton's web export. Suppressed once the
     * user has already enabled auto-backups.
     *
     * [force] (the backup_reminder debug command) shows it whatever those conditions say.
     */
    private fun maybeShowBackupReminder(force: Boolean = false) {
        if (isFinishing || isDestroyed) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!force) {
            if (prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENABLED, false)) return // already set up
            if (prefs.getBoolean(SyncPreferences.KEY_BACKUP_REMINDER_OPTED_OUT, false)) return // user opted out for good

            // Re-show at most once per interval (weekly by default). Existing users who saw the old
            // one-shot have no last-shown stamp, so they get nudged on the next open, then weekly.
            val lastShown = prefs.getLong(SyncPreferences.KEY_BACKUP_REMINDER_LAST_SHOWN_MS, 0L)
            val intervalMs = SyncPreferences.BACKUP_REMINDER_INTERVAL_DAYS * 24L * 60L * 60L * 1000L
            if (lastShown > 0L && System.currentTimeMillis() - lastShown < intervalMs) return
        }

        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val body = android.widget.TextView(this).apply {
            text = getString(R.string.backup_reminder_body)
            textSize = 15f
        }
        val dontRemind = android.widget.CheckBox(this).apply {
            text = getString(R.string.backup_reminder_dont_remind)
            setPadding(paddingLeft, (16 * d).toInt(), paddingRight, paddingBottom)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
            addView(body)
            addView(dontRemind)
        }

        var engaged = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.backup_reminder_title)
            .setView(container)
            .setPositiveButton(R.string.backup_reminder_enable) { _, _ ->
                engaged = true
                startActivity(Intent(this, BackupSettingsActivity::class.java))
            }
            .setNeutralButton(R.string.backup_reminder_proton_export) { _, _ ->
                engaged = true
                showProtonExportHelp()
            }
            .setNegativeButton(R.string.backup_reminder_later, null)
            .create()
        dialog.setOnDismissListener {
            // Honour the checkbox only when the user is declining (Later / back / tap-outside),
            // not when they tapped through to set up backups or open the Proton export.
            if (!engaged && dontRemind.isChecked && !isFinishing && !isDestroyed) {
                confirmBackupReminderOptOut()
            }
        }
        dialog.show()
        // Stamp the show time so the next nudge is at least one interval away.
        prefs.edit().putLong(SyncPreferences.KEY_BACKUP_REMINDER_LAST_SHOWN_MS, System.currentTimeMillis()).apply()
    }

    /**
     * The backup reminder's "Proton web export". Proton's web apps export contacts (.vcf) and calendars
     * (.ics) from different places, and its help centre explains each on its own page, so this asks
     * which one. (It used to open the guide to importing a calendar.)
     */
    private fun showProtonExportHelp() {
        val pages = arrayOf(
            R.string.sync_contacts to "https://proton.me/support/proton-contacts",
            R.string.sync_calendar to "https://proton.me/support/protoncalendar-calendars"
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_reminder_proton_export)
            .setItems(pages.map { getString(it.first) }.toTypedArray()) { _, which ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(pages[which].second)))
                } catch (_: Exception) { /* no browser */ }
            }
            .show()
    }

    /** Second, deliberate confirmation before we stop reminding about backups for good. */
    private fun confirmBackupReminderOptOut() {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_reminder_optout_title)
            .setMessage(R.string.backup_reminder_optout_body)
            .setPositiveButton(R.string.backup_reminder_optout_confirm) { _, _ ->
                PreferenceManager.getDefaultSharedPreferences(this)
                    .edit().putBoolean(SyncPreferences.KEY_BACKUP_REMINDER_OPTED_OUT, true).apply()
            }
            .setNegativeButton(R.string.backup_reminder_optout_cancel, null)
            .show()
    }

    /**
     * Wire the (i) info icons on a single account card. The dialog title reuses each
     * option's own label; the body is its verbose explanation. Several options share
     * a body (the two fallback rows, the two notify-on-failure rows, the two sync-mode
     * radios, the past/future range rows) because the explanation is identical.
     */
    private fun bindAccountCardInfoIcons(c: SettingsAccountCardBinding) {
        bindInfo(c.infoDisplayName, R.string.label_display_name, R.string.info_display_name_body)
        bindInfo(c.infoEventLoop, R.string.label_event_loop_interval, R.string.info_event_loop_body)
        bindInfo(c.infoNotifyInProgress, R.string.label_notify_in_progress, R.string.info_notify_in_progress_body)
        bindInfo(c.infoSyncContacts, R.string.sync_contacts, R.string.info_sync_contacts_body)
        bindInfo(c.infoContactsFallback, R.string.dialog_title_contacts_fallback, R.string.info_fallback_full_sync_body)
        bindInfo(c.infoNotifyContacts, R.string.label_notify_on_failure, R.string.info_notify_on_failure_body)
        bindInfo(c.infoSyncCalendar, R.string.sync_calendar, R.string.info_sync_calendar_body)
        bindInfo(c.infoIncludeExternal, R.string.label_include_external_calendars, R.string.info_include_external_calendars_body)
        bindInfo(c.infoSyncModeAll, R.string.calendar_timeframe_title, R.string.info_sync_mode_body)
        bindInfo(c.infoSyncModeCustom, R.string.calendar_timeframe_title, R.string.info_sync_mode_body)
        bindInfo(c.infoPastEvents, R.string.calendar_timeframe_title, R.string.info_sync_range_window_body)
        bindInfo(c.infoFutureEvents, R.string.calendar_timeframe_title, R.string.info_sync_range_window_body)
        bindInfo(c.infoCalendarFallback, R.string.dialog_title_calendar_fallback, R.string.info_fallback_full_sync_body)
        bindInfo(c.infoPollAttendee, R.string.label_poll_attendee_status, R.string.info_poll_attendee_body)
        bindInfo(c.infoNotifyCalendar, R.string.label_notify_on_failure, R.string.info_notify_on_failure_body)
        bindInfo(c.infoSendInvites, R.string.label_send_email_invites, R.string.info_send_invites_body)
        bindInfo(c.infoSendUpdates, R.string.label_send_email_updates, R.string.info_send_updates_body)
        bindInfo(c.infoSendCancellations, R.string.label_send_email_cancellations, R.string.info_send_cancellations_body)
        bindInfo(c.infoInviteSubjectTitle, R.string.label_invite_subject_include_title, R.string.info_invite_subject_title_body)
        // Action buttons on the card.
        bindInfo(c.infoBtnSyncContacts, R.string.btn_sync_contacts, R.string.info_btn_sync_contacts_body)
        bindInfo(c.infoBtnSyncCalendar, R.string.btn_sync_calendar, R.string.info_btn_sync_calendar_body)
        bindInfo(c.infoBtnSyncNow, R.string.btn_sync_all_now, R.string.info_btn_sync_now_body)
        bindInfo(c.infoBtnReauth, R.string.btn_reauthenticate, R.string.info_btn_reauth_body)
        bindInfo(c.infoBtnRemove, R.string.btn_remove, R.string.info_btn_remove_body)
    }

    /** The second licence check after returning to this screen; see onResume. */
    private var licenceRecheck: Job? = null

    override fun onResume() {
        super.onResume()
        subscriptionManager.connect()
        CoroutineScope(Dispatchers.IO).launch {
            subscriptionManager.refreshState()
            withContext(Dispatchers.Main) { updateSubscriptionBanner() }
        }
        // Back from Paddle's payment page with sync paused or unpaid: Paddle can take a moment to
        // collect and to move the subscription out of past due, and while sync is off nothing else
        // re-checks the licence until the next app start. So look once more, shortly after.
        val sub = subscriptionManager.currentState()
        licenceRecheck?.cancel()
        licenceRecheck = if (sub is SubscriptionState.Expired && (sub.paused || sub.paymentFailed)) {
            lifecycleScope.launch {
                delay(LICENCE_RECHECK_DELAY_MS)
                withContext(Dispatchers.IO) { subscriptionManager.refreshState() }
            }
        } else null
        updateSubscriptionBanner()
        buildAccountCards()
        maybeOfferDisplayNameRecovery()
        // The log viewer offers the same verbose toggle, so this screen has to re-read it on
        // the way back rather than trusting what it drew when it was created.
        refreshVerboseLoggingUi()
        refreshTimeZoneUpdatesUi()

        // Live "syncing now" indicator. Android fires this listener on a binder
        // thread whenever any sync starts or stops on the device — including the
        // periodic / scheduled syncs we don't initiate ourselves. We marshal back
        // to the main thread before touching views.
        if (syncStatusObserverHandle == null) {
            syncStatusObserverHandle = ContentResolver.addStatusChangeListener(
                ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE
            ) {
                mainHandler.post { refreshSyncIndicators() }
            }
        }
        refreshSyncIndicators()
    }

    override fun onPause() {
        super.onPause()
        licenceRecheck?.cancel()
        licenceRecheck = null
        // Push any buffered log lines to disk now, so a user who leaves the app to grab the
        // file (e.g. to send it in) finds it up to date.
        if (FileLogController.isActive()) FileLogController.flushNow()
        mainHandler.removeCallbacks(syncPollRunnable)
        syncStatusObserverHandle?.let {
            // Intentional: listener may already be removed if framework garbage-collected the handle
            try { ContentResolver.removeStatusChangeListener(it) } catch (_: Throwable) {}
        }
        syncStatusObserverHandle = null
    }

    /**
     * Walks the rendered account cards and shows/hides each card's sync-progress
     * indicator based on whether either ContentResolver authority is currently
     * mid-sync for that account. Cheap — `isSyncActive` is a binder read.
     */
    private fun refreshSyncIndicators() {
        // Three visual states per card:
        //   active  — sync currently running     → spinning indeterminate ring
        //   pending — user just pressed sync, Android queued it behind another account's
        //             same-authority sync         → static empty ring (no animation)
        //   idle    — neither                    → indicator hidden
        //
        // isSyncActive() requires READ_SYNC_STATS (manifest, normal protection). If a
        // vendor strips the permission, swallow the SecurityException so the activity
        // still opens.
        val now = System.currentTimeMillis()
        // Drop entries older than the TTL — we never want a stale "queued" indicator
        // after the user comes back to the screen days later.
        userRequestedAccounts.entries.removeAll { it.value < now - USER_REQUEST_TTL_MS }

        // Track whether any sync is active across all accounts so we can disable the
        // global "Sync all accounts" button alongside the per-card ones. v1.4.21.
        // v1.4.22: per-card disable is now per-account; only the global button cares
        // about "any account in flight".
        var anyActive = false
        var anyPending = false
        val anyResyncInFlight = fullResyncInFlightAccounts.isNotEmpty()

        for (handle in accountCardHandles) {
            val active = try {
                ContentResolver.isSyncActive(handle.abAccount, SyncConstants.AUTHORITY_CONTACTS) ||
                    ContentResolver.isSyncActive(handle.parentAccount, SyncConstants.AUTHORITY_CALENDAR)
            } catch (e: SecurityException) {
                SyncLog.w("SyncSettings", "isSyncActive denied: ${e.message}")
                false
            }
            if (active) anyActive = true
            // Once a sync becomes active, it's no longer "queued" — drop the marker.
            if (active) userRequestedAccounts.remove(handle.parentAccount.name)
            val pending = !active && handle.parentAccount.name in userRequestedAccounts
            if (pending) anyPending = true

            val frame = handle.binding.syncIndicator
            val progress = handle.binding.syncIndicatorProgress
            val queued = handle.binding.syncIndicatorQueued
            when {
                active -> {
                    progress.visibility = View.VISIBLE
                    queued.visibility = View.GONE
                    frame.visibility = View.VISIBLE
                }
                pending -> {
                    progress.visibility = View.GONE
                    queued.visibility = View.VISIBLE
                    frame.visibility = View.VISIBLE
                }
                else -> frame.visibility = View.GONE
            }
            // Per-card disable: only this account's force-resync state matters here.
            // A force-resync of a different account is allowed to proceed in parallel
            // (different rows / tables / calendars).
            val cardResyncInFlight = handle.parentAccount.name in fullResyncInFlightAccounts
            val cardEnabled = !cardResyncInFlight && !active && !pending
            handle.binding.btnSyncContacts.isEnabled = cardEnabled
            handle.binding.btnSyncCalendar.isEnabled = cardEnabled
            handle.binding.btnSyncNow.isEnabled = cardEnabled

            // The Android sync framework fires this listener when a sync transitions
            // active ↔ inactive. Re-render the date labels every tick so a sync that
            // just succeeded or failed is reflected on screen without the user having
            // to leave and re-enter Settings. Cheap (4 SharedPreferences reads + a
            // handful of view updates) and idempotent.
            handle.refreshSyncDates()
        }

        // Calm: keep the overview status hero (status + contacts/events counts) in step with
        // live sync results — so the counts climb during a sync and settle when it finishes,
        // without needing to reopen the app.
        if (accountCardHandles.isNotEmpty()) {
            updateHeroStatus(accountCardHandles.map { it.parentAccount })
            updateHeroStats()
        }

        // Global "Sync all accounts" button — disabled if any account is syncing OR a
        // force resync is in flight on any account.
        binding.btnSyncAllAccounts.isEnabled = !anyResyncInFlight && !anyActive

        // Safety-net poll: the ACTIVE observer can fire before isSyncActive() settles, which would
        // otherwise leave the last-finishing account's spinner stuck until the screen is reopened.
        // Re-arm a short re-check while anything is still active or queued; stop once everything is idle.
        mainHandler.removeCallbacks(syncPollRunnable)
        if (anyActive || anyPending) {
            mainHandler.postDelayed(syncPollRunnable, SYNC_POLL_INTERVAL_MS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugCommand(intent, restored = false)
    }

    /**
     * Debug builds: the settings backup buttons without the file picker, on a file in the app's own
     * external files folder (`files/debug/<file>`, default `debug-backup.sppbackup`).
     *
     * - `backup_export`: the export, with accounts and a password when one is given, settings only otherwise.
     * - `backup_import`: the import of that file, as the button runs it when the user confirms and skips
     *   any account already on the phone: accounts, settings, then the subscription step.
     * - `backup_restore_subscription`: only the subscription step, without accounts or settings.
     *
     * Logs outcomes only, never credentials, the subscription id or the password.
     */
    private fun handleDebugBackupCommand(command: String, passphrase: String?, fileName: String?) {
        val tag = "DebugCommand"
        val name = fileName?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }
            ?: "debug-backup.${me.proton.sync.backup.BackupFormat.EXTENSION}"
        val file = java.io.File(getExternalFilesDir("debug"), name)
        val uri = android.net.Uri.fromFile(file)
        lifecycleScope.launch(Dispatchers.IO) {
            if (command == "backup_export") {
                val createdAt = java.time.Instant.now().toString()
                val result = me.proton.sync.backup.ExportManager.export(
                    this@SyncSettingsActivity, uri, sessionManager,
                    includeAccounts = passphrase != null, passphrase = passphrase, createdAtIso = createdAt,
                    subscriptionId = subscriptionManager.subscriptionIdForBackup()
                )
                SyncLog.i(tag, "backup_export: $result -> ${file.absolutePath}")
                return@launch
            }
            val outcome = me.proton.sync.backup.ImportManager.readEnvelope(this@SyncSettingsActivity, uri)
            if (outcome !is me.proton.sync.backup.ImportManager.ReadOutcome.Ok) {
                SyncLog.w(tag, "$command: cannot read ${file.absolutePath}: $outcome")
                return@launch
            }
            val payload = try {
                me.proton.sync.backup.ImportManager.decryptPayload(outcome.info, passphrase)
            } catch (e: Exception) {
                SyncLog.w(tag, "$command: cannot decrypt: ${e.javaClass.simpleName}")
                return@launch
            }
            val info = outcome.info
            SyncLog.i(
                tag,
                "$command: file from the ${info.sourceFlavor} version ${info.sourceVersionCode}, " +
                    "this is the ${me.proton.sync.BuildConfig.FLAVOR} version; encrypted=${info.encrypted}, " +
                    "accounts=${payload.accounts.size}, settings=${payload.settings.size}, " +
                    "subscription id in file=${payload.subscriptionId != null}"
            )
            if (command == "backup_import") {
                val result = me.proton.sync.backup.ImportManager.apply(
                    this@SyncSettingsActivity, sessionManager, keyManager, syncDatabase, payload,
                    overwriteNames = emptySet(), restoreSettings = true
                )
                SyncLog.i(tag, "backup_import: $result")
            }
            val taken = me.proton.sync.backup.ImportManager.restoreSubscription(subscriptionManager, payload)
            SyncLog.i(tag, "$command: subscription id taken=$taken")
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) buildAccountCards()
            }
        }
    }

    /**
     * Debug builds only: run what a button on this screen runs, without tapping it.
     *
     * Driving the app for device tests used to mean finding buttons in UI dumps and tapping screen
     * coordinates, which broke on every scroll position, collapsed card and screen size. adb starts
     * DebugCommandActivity with intent extras instead (`am start` with `-e debug_command sync -e account
     * <name>`, for example), it passes them on to this activity, and the command calls the very same code
     * the button does, minus its confirmation dialog:
     *
     *   debug_command = sync | remove_account | resync_remote | resync_local | backup_export | backup_import |
     *                   backup_restore_subscription   (see handleDebugBackupCommand) |
     *                   billing_offers | plan_picker | billing_start | billing_sheet (plan = monthly | yearly) |
     *                   billing_manage | billing_update_payment | licences | debug_log (file) |
     *                   log_redaction_probe |
     *                   backup_reminder (part = export) | srp_modulus (username) | signin_probe (username) |
     *                   license_fixture (status) | license_enter (text or text_file, offline) |
     *                   tz_updates (state = on | off) | tz_info |
     *                   tz_probe (tzid, local) | tz_warning (mode, version) | schedule_info |
     *                   interval (seconds = a picker value | manual) |
     *                   retime_check | retime_run | retime_fixture (event_id, minutes, mode)
     *   account       = the account name as shown on its card
     *   section       = contacts | calendar | all   (default all)
     *
     * A few of these are test fixtures or read-only probes rather than buttons; the comment on each
     * branch says which. photo_probe never reaches this activity: DebugCommandActivity runs it itself.
     *
     * Every command logs one "DebugCommand" line. A release build ignores the extra entirely. This
     * activity is exported for the system's account settings, so a command is only accepted with a
     * one-time token from the DUMP-protected DebugCommandActivity (see [me.proton.sync.DebugCommandGate]),
     * and never from an intent Android redelivers on a restore or from Recents.
     */
    private fun handleDebugCommand(intent: Intent?, restored: Boolean) {
        if (!me.proton.sync.BuildConfig.DEBUG || intent == null) return
        val command = intent.getStringExtra(DEBUG_COMMAND_EXTRA) ?: return
        val token = intent.getStringExtra("debug_token")
        val passphrase = intent.getStringExtra("passphrase")
        val fileName = intent.getStringExtra("file")
        intent.removeExtra(DEBUG_COMMAND_EXTRA)
        intent.removeExtra("debug_token")
        intent.removeExtra("passphrase")
        intent.removeExtra("file")
        openedByDebugCommand = true
        val tag = "DebugCommand"
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (restored || fromHistory || !me.proton.sync.DebugCommandGate.consume(token)) {
            SyncLog.w(tag, "'$command' ignored: not a fresh command from the debug entry point")
            return
        }
        if (command == "backup_export" || command == "backup_import" || command == "backup_restore_subscription") {
            handleDebugBackupCommand(command, passphrase, fileName)
            return
        }
        // Subscription purchase flow, per flavor (the direct version logs that Play offers do not apply).
        when (command) {
            "billing_offers" -> {
                BillingUi.debugLogOffers(this, subscriptionManager)
                return
            }
            // The play version's plan picker; in the direct version, the Enter subscription ID dialog.
            "plan_picker" -> {
                SyncLog.i(tag, "plan_picker: opening the plan picker (direct version: the Enter subscription ID dialog)")
                BillingUi.showPurchaseOptions(this, subscriptionManager)
                return
            }
            // The card's trial, plan and renew button, through its handler: the direct version opens Paddle's
            // checkout page, the play version the plan picker.
            "billing_start" -> {
                SyncLog.i(tag, "billing_start: as the card's trial, plan or renew button")
                BillingUi.startPurchase(this, subscriptionManager)
                return
            }
            // The footer's "Third-party licences" link.
            "licences" -> {
                SyncLog.i(tag, "licences: opening the licences screen")
                openLicences()
                return
            }
            // The log viewer's Save button, into files/debug/ instead of a picked document.
            "debug_log" -> {
                val name = fileName?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) } ?: "debug-log.txt"
                val file = java.io.File(getExternalFilesDir("debug"), name)
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        me.proton.sync.DebugLog.writeTo(this@SyncSettingsActivity, android.net.Uri.fromFile(file))
                        SyncLog.i(tag, "debug_log: written -> ${file.absolutePath}")
                    } catch (e: Exception) {
                        SyncLog.w(tag, "debug_log: failed: ${e.javaClass.simpleName}")
                    }
                }
                return
            }
            // Test fixture: one line in each form the redaction knows, with made-up values; logcat keeps the raw
            // line, as it does every line, while the file copy and the debug log get it redacted.
            "log_redaction_probe" -> {
                SyncLog.i(
                    tag,
                    "log_redaction_probe: token=probe0000000001 \"AccessToken\":\"probe0000000002\" " +
                        "Bearer probe0000000003 sub_${"0".repeat(26)} txn_${"0".repeat(26)}"
                )
                FileLogController.flushNow()
                SyncLog.i(tag, "log_redaction_probe: file copy ${if (FileLogController.isActive()) "flushed" else "is off"}")
                return
            }
            "billing_sheet" -> {
                val plan = intent.getStringExtra("plan")
                intent.removeExtra("plan")
                BillingUi.debugLaunchSheet(this, subscriptionManager, yearly = plan == "yearly")
                return
            }
            // The card's Manage subscription and Update payment method buttons, through the handlers they call
            // (the direct version's Paddle portal links; the play version logs that they do not apply).
            "billing_manage", "billing_update_payment" -> {
                BillingUi.debugManage(this, subscriptionManager, updatePayment = command == "billing_update_payment")
                return
            }
            // Test fixture, not a user action: a Paddle licence answer (past_due, unpaid, paused, scheduled_cancel,
            // scheduled_pause or trialing) run through the real handling, or "refresh" to ask the licence proxy again.
            "license_fixture" -> {
                val status = intent.getStringExtra("status").orEmpty()
                intent.removeExtra("status")
                BillingUi.debugLicenseFixture(this, subscriptionManager, status)
                return
            }
            // The subscription dialog's Activate button with <text> in its field, through the button's own handler
            // (direct version; the play version logs that it does not apply). Safe on a phone with a real licence:
            // an entry Paddle does not accept never changes the stored id. With text_file instead of text, the text
            // is the trimmed content of files/debug/<text_file> in the app's external files folder, deleted as soon
            // as it is read: a real transaction id then never appears on the am start line, which adbd logs.
            "license_enter" -> {
                val text = intent.getStringExtra("text").orEmpty()
                val textFile = intent.getStringExtra("text_file")
                val offline = intent.getBooleanExtra("offline", false)
                intent.removeExtra("text")
                intent.removeExtra("text_file")
                intent.removeExtra("offline")
                if (textFile == null) {
                    BillingUi.debugLicenseEnter(this, subscriptionManager, text, offline)
                    return
                }
                if (!textFile.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*"))) {
                    SyncLog.w(tag, "license_enter ignored: text_file must be a file name in files/debug/, not a path")
                    return
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val file = java.io.File(getExternalFilesDir("debug"), textFile)
                    val fromFile = try {
                        file.readText().trim()
                    } catch (e: Exception) {
                        SyncLog.w(tag, "license_enter: the text file cannot be read: ${e.javaClass.simpleName}")
                        null
                    } finally {
                        // Read or not, it goes: it may hold a real id.
                        if (file.exists() && !file.delete()) SyncLog.w(tag, "license_enter: the text file could not be deleted")
                    }
                    if (fromFile == null) return@launch
                    // Its length only, as for any entered text.
                    SyncLog.i(tag, "license_enter: ${fromFile.length} characters from the text file, which is deleted")
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            BillingUi.debugLicenseEnter(this@SyncSettingsActivity, subscriptionManager, fromFile, offline)
                        }
                    }
                }
                return
            }
            // The backup reminder as its timer shows it, or (part = export) the chooser behind its
            // "Proton web export" button.
            "backup_reminder" -> {
                val part = intent.getStringExtra("part")
                intent.removeExtra("part")
                if (part == "export") showProtonExportHelp() else maybeShowBackupReminder(force = true)
                SyncLog.i(tag, "backup_reminder: ${if (part == "export") "the Proton web export chooser" else "the reminder"} shown")
                return
            }
            // Read-only probe: the first step of a sign-in for one username, and the check of Proton's
            // signature on the SRP modulus that a sign-in makes next. No proof is computed or sent.
            "srp_modulus" -> {
                val username = intent.getStringExtra("username").orEmpty()
                intent.removeExtra("username")
                if (username.isBlank()) {
                    SyncLog.w(tag, "srp_modulus ignored: no username")
                    return
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val line = try {
                        srpAuthHandler.probeModulus(username)
                    } catch (e: Exception) {
                        "failed: ${e.javaClass.simpleName}"
                    }
                    SyncLog.i(tag, "srp_modulus: $line")
                }
                return
            }
            // Test fixture, not a user action: one sign-in for one username with a random password, so
            // Proton refuses it (Code 8002) and the log shows the Code and the exact message the sign-in
            // screen would show. No real password ever travels over adb. Proton counts it as a failed
            // attempt and may ask for a CAPTCHA, so use the test account, and never in a loop.
            "signin_probe" -> {
                val username = intent.getStringExtra("username").orEmpty()
                intent.removeExtra("username")
                if (username.isBlank()) {
                    SyncLog.w(tag, "signin_probe ignored: no username")
                    return
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val line = try {
                        srpAuthHandler.login(username, "sfp-probe-" + java.util.UUID.randomUUID())
                        "unexpected: Proton accepted a random password"
                    } catch (e: me.proton.sync.api.SrpAuthHandler.CaptchaRequiredException) {
                        "human verification required (Code 9001); the sign-in screen would open the CAPTCHA"
                    } catch (e: Exception) {
                        "${SignInErrors.kindOf(e)}: \"${SignInErrors.message(this@SyncSettingsActivity, e)}\""
                    }
                    SyncLog.i(tag, "signin_probe: $line")
                }
                return
            }
            // The "Update time zones online" switch, through the same path as the switch itself.
            "tz_updates" -> {
                val state = intent.getStringExtra("state")
                intent.removeExtra("state")
                if (state != "on" && state != "off") {
                    SyncLog.w(tag, "tz_updates ignored: state must be on or off, not '$state'")
                    return
                }
                setTimeZoneUpdates(state == "on")
                refreshTimeZoneUpdatesUi()
                SyncLog.i(tag, "tz_updates: $state (rule source ${if (state == "on") "online for one-off events" else "the phone's"}; " +
                    "ical4j updater ${IcalTimeZoneUpdates.PROPERTY}=${System.getProperty(IcalTimeZoneUpdates.PROPERTY)})")
                return
            }
            // Read-only: the phone's tz data, the switch, the notice, each account's re-time state.
            "tz_info" -> {
                val dao = syncDatabase.calendarEventSyncDao()
                lifecycleScope.launch(Dispatchers.IO) {
                    try { TimeZoneDebugCommands.info(applicationContext, dao) } catch (e: Exception) {
                        SyncLog.w(tag, "tz_info failed: ${e.message}")
                    }
                }
                return
            }
            // Read-only: per account, the subscription gate, the interval, the switches, the periodic syncs
            // and the event loop's and watchdogs' WorkManager states (their names are not in dumpsys).
            "schedule_info" -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    try { SyncScheduling.debugLogSchedules(applicationContext) } catch (e: Exception) {
                        SyncLog.w(tag, "schedule_info failed: ${e.message}")
                    }
                }
                return
            }
            // Read-only (may fetch from tzurl.org in ON mode, as a sync would): one local time, converted.
            "tz_probe" -> {
                val tzid = intent.getStringExtra("tzid").orEmpty()
                val local = intent.getStringExtra("local").orEmpty()
                intent.removeExtra("tzid")
                intent.removeExtra("local")
                lifecycleScope.launch(Dispatchers.IO) { TimeZoneDebugCommands.probe(applicationContext, tzid, local) }
                return
            }
            // The outdated-data notice: show it now, forget its answer, or judge a pretended tz data version.
            "tz_warning" -> {
                val mode = intent.getStringExtra("mode")
                val version = intent.getStringExtra("version")
                intent.removeExtra("mode")
                intent.removeExtra("version")
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                when (mode) {
                    "show" -> {
                        val shown = maybeShowTzDataNotice(force = true)
                        SyncLog.i(tag, "tz_warning show: ${if (shown) "shown" else "not shown"}")
                    }
                    "reset" -> {
                        prefs.edit().remove(SyncPreferences.KEY_TZ_OUTDATED_NOTICE_ANSWER)
                            .remove(SyncPreferences.KEY_TZ_OUTDATED_NOTICE_LAST_SHOWN_MS).apply()
                        SyncLog.i(tag, "tz_warning reset: the notice's answer and last showing are forgotten")
                    }
                    "fake" -> {
                        if (version == null || TzDataFreshness.year(version) == null) {
                            SyncLog.w(tag, "tz_warning fake ignored: '$version' is not a tz release such as 2021a")
                            return
                        }
                        prefs.edit().putString(SyncPreferences.KEY_DEBUG_TZDATA_VERSION_OVERRIDE, version).apply()
                        SyncLog.i(tag, "tz_warning fake: the settings screen now judges tz data $version (never the rules)")
                    }
                    "real" -> {
                        prefs.edit().remove(SyncPreferences.KEY_DEBUG_TZDATA_VERSION_OVERRIDE).apply()
                        SyncLog.i(tag, "tz_warning real: the settings screen judges the phone's own tz data again")
                    }
                    else -> SyncLog.w(tag, "tz_warning ignored: mode must be show, reset, fake or real, not '$mode'")
                }
                refreshTimeZoneUpdatesUi()
                return
            }
        }
        val am = AccountManager.get(this)
        val name = intent.getStringExtra("account")
        val account = am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE).firstOrNull { it.name == name }
        if (account == null) {
            SyncLog.w(tag, "'$command' ignored: no account named '$name'")
            return
        }
        val abAccount = Account(SyncUtils.addressBookAccountName(am, account), ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
        val section = intent.getStringExtra("section")?.takeIf { it in setOf("contacts", "calendar", "all") } ?: "all"
        SyncLog.i(tag, "Running '$command' for '${account.name}' (section=$section)")
        when (command) {
            "sync" -> {
                val started = requestManualSync(account, abAccount, section)
                SyncLog.i(
                    tag,
                    "Sync requested for '${account.name}': contacts=${started.contacts} " +
                        "calendar=${started.calendar}"
                )
            }
            // Destructive and deliberately unguarded, like the resync verbs: this is a debug build and
            // the point is to exercise the sign-out that removal performs. Signing back in needs the
            // account password, so do not aim it at an account you cannot re-add.
            "remove_account" -> {
                SyncLog.w(tag, "Removing account '${account.name}' and signing it out of Proton")
                removeAccount(account, abAccount)
                buildAccountCards()
            }
            "resync_remote" -> performFullResyncRemoteToLocal(account, abAccount, section)
            "resync_local" -> performFullResyncLocalToRemote(account, abAccount, section)
            // The account's sync interval picker: stores one of its values as the dialog does, then runs
            // the picker's handler. "manual" is its "Manual only" (-1).
            "interval" -> {
                val raw = intent.getStringExtra("seconds")
                intent.removeExtra("seconds")
                val values = resources.getStringArray(R.array.sync_interval_values)
                val v = if (raw == "manual") "-1" else raw
                if (v == null || v !in values) {
                    SyncLog.w(tag, "interval ignored: '$raw' is not one of ${values.filter { it != "-1" }.joinToString()} or manual")
                } else {
                    PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putString(SyncPreferences.eventLoopIntervalKey(account.name), v).apply()
                    applyEventLoopInterval(account)
                    buildAccountCards()
                    SyncLog.i(tag, "interval: '${account.name}' set to $v")
                }
            }
            // The re-time pass: "check" plans without writing, "run" makes the next sync run it.
            "retime_check" -> {
                val dao = syncDatabase.calendarEventSyncDao()
                lifecycleScope.launch(Dispatchers.IO) {
                    try { TimeZoneDebugCommands.retimeCheck(applicationContext, account, dao) } catch (e: Exception) {
                        SyncLog.w(tag, "retime check failed: ${e.message}")
                    }
                }
            }
            "retime_run" -> TimeZoneDebugCommands.retimeRun(applicationContext, account)
            // Test fixture: one synced row made to look stored with other rules (see TimeZoneDebugCommands).
            "retime_fixture" -> {
                val eventId = intent.getLongExtra("event_id", -1L)
                val minutes = intent.getIntExtra("minutes", 0)
                val mode = intent.getStringExtra("mode")?.takeIf { it in setOf("clean", "dirty", "edited") } ?: "clean"
                intent.removeExtra("event_id")
                intent.removeExtra("minutes")
                intent.removeExtra("mode")
                val dao = syncDatabase.calendarEventSyncDao()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        TimeZoneDebugCommands.retimeFixture(applicationContext, account, dao, eventId, minutes, mode)
                    } catch (e: Exception) {
                        SyncLog.w(tag, "retime_fixture failed: ${e.message}")
                    }
                }
            }
            else -> SyncLog.w(tag, "Unknown command '$command'")
        }
    }

    /** What a manual sync request actually started. requestSync ignores setSyncAutomatically. */
    private data class ManualSyncResult(val contacts: Boolean, val calendar: Boolean)

    /**
     * Ask Android for a manual sync of [section] ("contacts", "calendar" or "all").
     *
     * One implementation for the three buttons and the debug verb. They each built the same bundle and
     * called the same guard, so they agreed by inspection rather than by construction, and the rule for debug
     * commands is that each one runs the same code as the button it stands for.
     *
     * Any section that includes contacts clears the skipped contact-conflict decisions, because that is
     * what the prompt recording them promises. Only "all" marks the account as awaiting a sync.
     */
    private fun requestManualSync(account: Account, abAccount: Account, section: String): ManualSyncResult {
        val bundle = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(SyncConstants.EXTRA_USER_SYNC_NOW, true)
        }
        // Whenever contacts are in scope, not only on "everything". These decisions are contact
        // conflicts and nothing else, and the prompt that records them tells the user both versions
        // stay "until your next manual sync". Asking for a contacts sync is that sync, and it did not
        // re-ask: the question came back only if the user happened to press the larger button instead.
        if (section != "calendar") ConflictResolutionReceiver.clearAllSkipDecisions(this, account.name)
        // Only the types the account actually has switched on. requestSync ignores
        // setSyncAutomatically, so "sync everything" used to run an authority the user had just switched
        // off, and on the very next tap it downloaded back the contacts or calendars they had chosen to
        // remove from the phone. "Everything" means everything this account syncs, not everything it could.
        val contacts = section != "calendar" &&
            requestSyncIfEnabled(abAccount, SyncConstants.AUTHORITY_CONTACTS, bundle)
        val calendar = section != "contacts" &&
            requestSyncIfEnabled(account, SyncConstants.AUTHORITY_CALENDAR, bundle)
        if (section == "all") markUserRequestedSync(account.name)
        return ManualSyncResult(contacts = contacts, calendar = calendar)
    }

    /** Mark account as awaiting sync after a manual user request. Triggers a refresh. */
    private fun markUserRequestedSync(accountName: String) {
        userRequestedAccounts[accountName] = System.currentTimeMillis()
        refreshSyncIndicators()
    }

    private fun setupSubscriptionBanner() {
        // Tied to this screen's lifecycle: a free-standing scope kept every destroyed instance (each
        // rotation or language change) collecting and redrawing for the life of the process. While
        // stopped nothing is drawn; StateFlow hands the current value over again on restart.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch { subscriptionManager.state.collect { updateSubscriptionBanner() } }
                // The play card's wording depends on the offers Google returns, which load after the state.
                launch { subscriptionManager.productDetailsVersion.collect { updateSubscriptionBanner() } }
            }
        }
    }

    private fun updateSubscriptionBanner() {
        val card = binding.subscriptionCard
        val state = subscriptionManager.currentState()
        val dateFmt = DateFormat.getDateInstance(DateFormat.MEDIUM)

        card.btnSubscriptionManage.setOnClickListener { BillingUi.openManageSubscription(this@SyncSettingsActivity, subscriptionManager, state) }
        // Side by side unless a branch below stacks them. The two payment-problem states show two
        // long labels ("Update payment method" and "Manage subscription" run to 40-59 characters),
        // which a horizontal row cannot hold in most languages.
        card.subscriptionButtonRow.orientation = LinearLayout.HORIZONTAL
        // Only the direct version shows it, and only in the branches that call showEnterIdButton.
        card.btnSubscriptionEnterId.visibility = View.GONE

        when (state) {
            SubscriptionState.Unknown,
            SubscriptionState.NoSubscription -> {
                // Per flavor: Paddle always starts with the trial; Google offers one only to accounts
                // that never had this subscription, so the play version promises it only when offered.
                // The direct version's button opens Paddle's checkout, and the id it ends with goes in
                // through Enter subscription ID.
                val (status, action) = BillingUi.noSubscriptionTexts(this@SyncSettingsActivity, subscriptionManager)
                card.textSubscriptionStatus.text = status
                card.btnSubscriptionAction.text = action
                card.btnSubscriptionAction.visibility = View.VISIBLE
                card.btnSubscriptionAction.setOnClickListener { BillingUi.startPurchase(this@SyncSettingsActivity, subscriptionManager) }
                card.btnSubscriptionManage.visibility = View.GONE
                showEnterIdButton(card)
                card.iconSubscription.setImageResource(android.R.drawable.ic_lock_lock)
            }
            is SubscriptionState.Trial -> {
                val daysLeft = trialDaysLeft(state.expiresAtMs, System.currentTimeMillis())
                card.textSubscriptionStatus.text = scheduledChangeText(state.scheduledChange, dateFmt)
                    ?: resources.getQuantityString(R.plurals.label_subscription_trial_days, daysLeft, daysLeft)
                // None in the direct version: its trial already is the subscription (BillingUi.upgradeLabel).
                val upgrade = BillingUi.upgradeLabel(this)
                upgrade?.let { card.btnSubscriptionAction.text = it }
                card.btnSubscriptionAction.visibility = if (upgrade != null) View.VISIBLE else View.GONE
                card.btnSubscriptionAction.setOnClickListener { BillingUi.startPurchase(this@SyncSettingsActivity, subscriptionManager) }
                card.btnSubscriptionManage.visibility = View.VISIBLE
                // Direct version: the trial's id is the one a second phone needs, and the dialog shows it.
                showEnterIdButton(card)
                card.iconSubscription.setImageResource(android.R.drawable.ic_menu_recent_history)
            }
            is SubscriptionState.Active -> if (state.paymentFailed) {
                // Paddle could not take the renewal and is retrying for up to 30 days. Sync keeps
                // running meanwhile, so this only asks for a new payment method; Manage stays for
                // anyone who would rather cancel.
                card.textSubscriptionStatus.text = getString(R.string.label_subscription_grace)
                card.btnSubscriptionAction.text = getString(R.string.btn_update_payment_method)
                card.btnSubscriptionAction.visibility = View.VISIBLE
                card.btnSubscriptionAction.setOnClickListener { BillingUi.openUpdatePayment(this@SyncSettingsActivity, subscriptionManager, state) }
                card.btnSubscriptionManage.visibility = View.VISIBLE
                card.subscriptionButtonRow.orientation = LinearLayout.VERTICAL
                card.iconSubscription.setImageResource(android.R.drawable.stat_sys_warning)
            } else {
                // "Renews" no longer holds once Paddle has a cancellation or a pause scheduled. The play
                // version has no renewal date (a Play purchase carries none), so its line names Google Play.
                card.textSubscriptionStatus.text = scheduledChangeText(state.scheduledChange, dateFmt)
                    ?: BillingUi.activeStatusText(this)
                    ?: getString(R.string.label_subscription_active, dateFmt.format(Date(state.expiresAtMs)))
                card.btnSubscriptionAction.visibility = View.GONE
                card.btnSubscriptionManage.visibility = View.VISIBLE
                card.iconSubscription.setImageResource(android.R.drawable.checkbox_on_background)
            }
            is SubscriptionState.Grace -> {
                card.textSubscriptionStatus.text = getString(R.string.label_subscription_grace)
                card.btnSubscriptionAction.text = getString(R.string.btn_manage_subscription)
                card.btnSubscriptionAction.visibility = View.VISIBLE
                card.btnSubscriptionAction.setOnClickListener { BillingUi.openManageSubscription(this@SyncSettingsActivity, subscriptionManager, state) }
                card.btnSubscriptionManage.visibility = View.GONE
                card.iconSubscription.setImageResource(android.R.drawable.stat_sys_warning)
            }
            is SubscriptionState.Expired -> if (state.paused) {
                // Paused, not ended: the subscription id is kept, so sync comes back by itself if the
                // subscription is resumed. Manage reaches the paused subscription itself. Renew stays
                // too, because a customer cannot usually resume a paused subscription on their own: it
                // opens a new purchase (Paddle's checkout in the direct version), and Enter subscription
                // ID takes the new id, which replaces the kept one.
                card.textSubscriptionStatus.text = getString(R.string.label_subscription_paused)
                card.btnSubscriptionAction.text = getString(R.string.btn_renew)
                card.btnSubscriptionAction.visibility = View.VISIBLE
                card.btnSubscriptionAction.setOnClickListener { BillingUi.startPurchase(this@SyncSettingsActivity, subscriptionManager) }
                card.btnSubscriptionManage.visibility = View.VISIBLE
                card.subscriptionButtonRow.orientation = LinearLayout.VERTICAL
                showEnterIdButton(card)
                card.iconSubscription.setImageResource(android.R.drawable.stat_sys_warning)
            } else if (state.paymentFailed) {
                // Unpaid: the first charge after the trial failed, or a renewal stayed past due too
                // long, so sync is off. The id is kept and the next licence check after Paddle
                // collects turns sync back on, so the one thing to offer is the payment page; Manage
                // stays for cancelling. Enter subscription ID (direct version) takes a new
                // subscription's id instead, and its dialog shows the kept one.
                card.textSubscriptionStatus.text = getString(R.string.label_subscription_unpaid)
                card.btnSubscriptionAction.text = getString(R.string.btn_update_payment_method)
                card.btnSubscriptionAction.visibility = View.VISIBLE
                card.btnSubscriptionAction.setOnClickListener { BillingUi.openUpdatePayment(this@SyncSettingsActivity, subscriptionManager, state) }
                card.btnSubscriptionManage.visibility = View.VISIBLE
                card.subscriptionButtonRow.orientation = LinearLayout.VERTICAL
                showEnterIdButton(card)
                card.iconSubscription.setImageResource(android.R.drawable.stat_sys_warning)
            } else {
                card.textSubscriptionStatus.text = getString(R.string.label_subscription_expired)
                card.btnSubscriptionAction.text = getString(R.string.btn_renew)
                card.btnSubscriptionAction.visibility = View.VISIBLE
                card.btnSubscriptionAction.setOnClickListener { BillingUi.startPurchase(this@SyncSettingsActivity, subscriptionManager) }
                card.btnSubscriptionManage.visibility = View.GONE
                showEnterIdButton(card)
                card.iconSubscription.setImageResource(android.R.drawable.stat_notify_error)
            }
        }

        // With the buttons that open Paddle's portal, so it is read before either is tapped (direct version
        // only). Every state whose card offers Update payment method shows Manage subscription as well.
        val note = BillingUi.manageNote(this)
        card.textSubscriptionNote.text = note
        card.textSubscriptionNote.visibility =
            if (note != null && card.btnSubscriptionManage.visibility == View.VISIBLE) View.VISIBLE else View.GONE

        // The hero says when sync is off for want of a subscription, so it follows the state too.
        if (accountCardHandles.isNotEmpty()) updateHeroStatus(accountCardHandles.map { it.parentAccount })
    }

    /**
     * The card's line for a cancellation or a pause Paddle has scheduled (direct version only), dated like the
     * card's other dates, or null when nothing is scheduled.
     */
    private fun scheduledChangeText(change: ScheduledChange?, dateFmt: DateFormat): String? =
        change?.let { getString(scheduledChangeLabel(it), dateFmt.format(Date(it.effectiveAtMs))) }

    /**
     * The card's Enter subscription ID button (direct version only; the play version has no label for it, so
     * nothing changes on its cards): on the cards where sync is off and a subscription id is the way back (no
     * subscription, paused, unpaid, expired), and on the trial card, whose dialog shows the id a second phone
     * needs. Not on the active cards. Stacked under the other buttons, because two long labels do not fit side
     * by side.
     */
    private fun showEnterIdButton(card: SettingsSubscriptionCardBinding) {
        val label = BillingUi.enterIdLabel(this) ?: return
        card.btnSubscriptionEnterId.text = label
        card.btnSubscriptionEnterId.visibility = View.VISIBLE
        card.btnSubscriptionEnterId.setOnClickListener { BillingUi.showPurchaseOptions(this, subscriptionManager) }
        card.subscriptionButtonRow.orientation = LinearLayout.VERTICAL
    }

    // Purchase / manage UI is flavor-specific (Play billing sheet vs Paddle web
    // checkout + paste): see BillingUi in src/play and src/direct. The shared code
    // above only calls BillingUi.startPurchase(...), enterIdLabel(...) and upgradeLabel(...),
    // showPurchaseOptions(...) and openManageSubscription(...).

    private fun buildAccountCards() {
        val container = binding.settingsContainer
        // Static header children (in order): subscription card, language row, divider.
        // Remove only dynamically added account cards (between static header and footer).
        val staticHeaderCount = 3  // subscription card + status hero + ACCOUNTS label
        val footerIndex = container.childCount - 1
        val dynamicCount = footerIndex - staticHeaderCount
        if (dynamicCount > 0) {
            container.removeViews(staticHeaderCount, dynamicCount)
        }
        // Card-handle list is rebuilt in lockstep with the views; sync-state listener
        // walks it to flip indicators without re-inflating.
        accountCardHandles.clear()

        val accounts = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        if (accounts.isEmpty()) {
            binding.btnAddAccount.text = getString(R.string.btn_sign_in_with_proton)
        }

        // Global sync button is always visible when at least one account exists.
        // Label switches between singular ("Sync now") and plural ("Sync all accounts")
        // based on how many accounts the user has signed in. The long-press hint applies
        // in both cases (long-press still triggers a forced full re-sync).
        val hasAccounts = accounts.isNotEmpty()
        // Calm: the first-run / empty state replaces the whole scroll + app bar when there
        // are no accounts.
        binding.emptyState.visibility = if (hasAccounts) View.GONE else View.VISIBLE
        binding.mainScroll.visibility = if (hasAccounts) View.VISIBLE else View.GONE
        binding.appBar.visibility = if (hasAccounts) View.VISIBLE else View.GONE
        if (hasAccounts) {
            updateHeroStatus(accounts.toList())
            updateHeroStats()
        }
        // Calm: the old "Sync all" row/hint are replaced by the status hero, so keep them
        // hidden regardless of account count (the hero's "Sync all now" delegates to them).
        binding.rowSyncAllAccounts.visibility = View.GONE
        binding.textSyncAllHint.visibility = View.GONE
        binding.btnSyncAllAccounts.text = getString(
            if (accounts.size >= 2) R.string.btn_sync_all_accounts else R.string.btn_sync_now
        )

        for ((index, account) in accounts.withIndex()) {
            val abAccount = Account(SyncUtils.addressBookAccountName(accountManager, account), ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
            // Pass parent + attachToParent=false so the root MaterialCardView's
            // layout_marginBottom from XML is preserved (otherwise inflate() drops
            // the LayoutParams and cards stack edge-to-edge with no breathing room).
            val cardBinding = SettingsAccountCardBinding.inflate(LayoutInflater.from(this), container, false)
            // The label refresher is assigned later, once updateLastSyncLabels is in
            // scope; this lateinit-style holder lets the handle hold a stable reference
            // we can swap before any external caller (the sync-status listener) fires.
            var cardRefreshSyncDates: () -> Unit = {}
            accountCardHandles.add(
                AccountCardHandle(account, abAccount, cardBinding) { cardRefreshSyncDates() }
            )

            // Tap-to-explain (i) icons on every row of this card.
            bindAccountCardInfoIcons(cardBinding)

            // --- Display name ---
            val displayName = getDisplayName(accountManager, account)
            cardBinding.accountName.text = displayName
            cardBinding.avatarLetter.text = displayName.first().uppercase()

            // Show the immutable canonical Proton username under the display name when
            // the two differ. After a rename (or a typo'd rename) the user can still
            // tell which Proton account the card represents.
            val protonUsername = accountManager.getUserData(account, "proton_username")
            val canonical = when {
                protonUsername.isNullOrBlank() -> null
                protonUsername.contains('@') -> protonUsername
                else -> "$protonUsername@proton.me"
            }
            if (canonical != null && canonical != displayName) {
                cardBinding.accountProtonUsername.text = canonical
                cardBinding.accountProtonUsername.visibility = View.VISIBLE
            } else {
                cardBinding.accountProtonUsername.visibility = View.GONE
            }

            // Status line
            updateStatusLine(cardBinding, abAccount, account)

            // Alias value
            val alias = accountManager.getUserData(account, "alias")
            cardBinding.valueAlias.text = alias ?: displayName

            // --- Expand/collapse: always starts collapsed, however many accounts there are ---
            var expanded = false
            cardBinding.accountContent.visibility = View.GONE
            cardBinding.expandIcon.rotation = 0f

            cardBinding.accountHeader.setOnClickListener {
                expanded = !expanded
                if (expanded) {
                    expandView(cardBinding.accountContent)
                    cardBinding.expandIcon.animate().rotation(180f).setDuration(200).start()
                } else {
                    collapseView(cardBinding.accountContent)
                    cardBinding.expandIcon.animate().rotation(0f).setDuration(200).start()
                    updateStatusLine(cardBinding, abAccount, account)
                }
            }

            // --- Advanced disclosure (email + fallback group) — collapsed on each (re)bind ---
            cardBinding.advancedGroup.visibility = View.GONE
            cardBinding.btnAdvancedDisclosure.setText(R.string.sfp_show_advanced)
            cardBinding.btnAdvancedDisclosure.setIconResource(R.drawable.ic_sfp_chevron_down)
            cardBinding.btnAdvancedDisclosure.setOnClickListener {
                if (cardBinding.advancedGroup.visibility != View.VISIBLE) {
                    expandView(cardBinding.advancedGroup)
                    cardBinding.btnAdvancedDisclosure.setText(R.string.sfp_hide_advanced)
                    cardBinding.btnAdvancedDisclosure.setIconResource(R.drawable.ic_sfp_chevron_up)
                } else {
                    collapseView(cardBinding.advancedGroup)
                    cardBinding.btnAdvancedDisclosure.setText(R.string.sfp_show_advanced)
                    cardBinding.btnAdvancedDisclosure.setIconResource(R.drawable.ic_sfp_chevron_down)
                }
            }

            // --- Alias row ---
            cardBinding.rowAlias.setOnClickListener {
                showAliasDialog(accountManager, account)
            }

            // --- Toggles ---
            cardBinding.switchContacts.isChecked =
                ContentResolver.getSyncAutomatically(abAccount, SyncConstants.AUTHORITY_CONTACTS)
            cardBinding.switchContacts.setOnCheckedChangeListener { _, checked ->
                setSyncEnabled(abAccount, SyncConstants.AUTHORITY_CONTACTS, checked, account.name)
                if (!checked) offerToRemoveSyncedData(account, abAccount, calendar = false)
            }

            cardBinding.switchCalendar.isChecked =
                ContentResolver.getSyncAutomatically(account, SyncConstants.AUTHORITY_CALENDAR)
            cardBinding.switchCalendar.setOnCheckedChangeListener { _, checked ->
                setSyncEnabled(account, SyncConstants.AUTHORITY_CALENDAR, checked, account.name)
                if (!checked) offerToRemoveSyncedData(account, abAccount, calendar = true)
            }

            val includeKey = SyncPreferences.includeExternalCalendarsKey(account.name)
            cardBinding.switchExcludeExternal.isChecked = prefs.getBoolean(includeKey, false)
            cardBinding.switchExcludeExternal.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(includeKey, checked)
                    .putBoolean(SyncPreferences.calendarSettingsDirtyKey(account.name), true).apply()
            }

            val pollAttendeeKey = SyncPreferences.pollAttendeeStatusKey(account.name)
            cardBinding.switchPollAttendeeStatus.isChecked = prefs.getBoolean(pollAttendeeKey, false)
            cardBinding.switchPollAttendeeStatus.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(pollAttendeeKey, checked).apply()
            }

            // --- Calendar sync range: All vs Custom radio + dropdowns ---
            val syncModeKey = SyncPreferences.calendarSyncModeKey(account.name)
            val pastKey = SyncPreferences.calendarPastDaysKey(account.name)
            val futureKey = SyncPreferences.calendarFutureDaysKey(account.name)

            // One-time migration to the v1.4.12 syncMode model. Only runs once per
            // account — guarded by absence of the syncMode pref. Existing users keep
            // their behaviour intact:
            //   * Legacy "0 = All" on either dimension → syncMode = "all" (and reset
            //     the matching day count to its default so the Custom UI later shows
            //     real numbers).
            //   * Any other stored past/future value → syncMode = "custom" (preserve
            //     the explicit choice the user made before).
            //   * No stored value at all → no migration; the new "all" default kicks
            //     in via DEFAULT_SYNC_MODE on first read.
            if (!prefs.contains(syncModeKey)) {
                val legacyPast = prefs.getString(pastKey, null)
                val legacyFuture = prefs.getString(futureKey, null)
                val legacyAll = legacyPast == "0" || legacyFuture == "0"
                val hadCustomChoice = (legacyPast != null && legacyPast != "0") ||
                    (legacyFuture != null && legacyFuture != "0")
                val editor = prefs.edit()
                when {
                    legacyAll -> {
                        editor.putString(syncModeKey, SyncPreferences.SYNC_MODE_ALL)
                        if (legacyPast == "0") editor.putString(pastKey, "${SyncPreferences.DEFAULT_PAST_DAYS}")
                        if (legacyFuture == "0") editor.putString(futureKey, "${SyncPreferences.DEFAULT_FUTURE_DAYS}")
                    }
                    hadCustomChoice -> editor.putString(syncModeKey, SyncPreferences.SYNC_MODE_CUSTOM)
                    // else: brand-new account, fall through to DEFAULT_SYNC_MODE on read
                }
                editor.apply()
            }

            val pastEntries = resources.getStringArray(R.array.calendar_past_entries)
            val pastValues = resources.getStringArray(R.array.calendar_past_values)
            bindDropdownPref(
                labelView = cardBinding.valuePastEvents,
                clickableRow = cardBinding.rowPastEvents,
                dialogTitle = getString(R.string.dialog_title_past_events),
                entries = pastEntries,
                values = pastValues,
                prefKey = pastKey,
                defaultValue = "180",
                prefs = prefs,
                formatLabel = { v -> formatDaysLabel(v, pastEntries, pastValues) },
                onChanged = {
                    prefs.edit().putBoolean(SyncPreferences.calendarSettingsDirtyKey(account.name), true).apply()
                }
            )

            val futureEntries = resources.getStringArray(R.array.calendar_future_entries)
            val futureValues = resources.getStringArray(R.array.calendar_future_values)
            bindDropdownPref(
                labelView = cardBinding.valueFutureEvents,
                clickableRow = cardBinding.rowFutureEvents,
                dialogTitle = getString(R.string.dialog_title_future_events),
                entries = futureEntries,
                values = futureValues,
                prefKey = futureKey,
                defaultValue = "365",
                prefs = prefs,
                formatLabel = { v -> formatDaysLabel(v, futureEntries, futureValues) },
                onChanged = {
                    prefs.edit().putBoolean(SyncPreferences.calendarSettingsDirtyKey(account.name), true).apply()
                }
            )

            // Apply current sync mode to the radios + custom group, and wire toggles.
            fun applySyncMode(mode: String) {
                val isAll = mode == SyncPreferences.SYNC_MODE_ALL
                cardBinding.radioSyncModeAll.isChecked = isAll
                cardBinding.radioSyncModeCustom.isChecked = !isAll
                cardBinding.groupCustomRange.visibility = if (isAll) android.view.View.GONE else android.view.View.VISIBLE
            }
            applySyncMode(prefs.getString(syncModeKey, SyncPreferences.DEFAULT_SYNC_MODE) ?: SyncPreferences.DEFAULT_SYNC_MODE)
            cardBinding.rowSyncModeAll.setOnClickListener {
                prefs.edit()
                    .putString(syncModeKey, SyncPreferences.SYNC_MODE_ALL)
                    .putBoolean(SyncPreferences.calendarSettingsDirtyKey(account.name), true)
                    .apply()
                applySyncMode(SyncPreferences.SYNC_MODE_ALL)
            }
            cardBinding.rowSyncModeCustom.setOnClickListener {
                prefs.edit()
                    .putString(syncModeKey, SyncPreferences.SYNC_MODE_CUSTOM)
                    .putBoolean(SyncPreferences.calendarSettingsDirtyKey(account.name), true)
                    .apply()
                applySyncMode(SyncPreferences.SYNC_MODE_CUSTOM)
            }

            // --- Per-account sync settings ---
            val intervalEntries = resources.getStringArray(R.array.sync_interval_entries)
            val intervalValues = resources.getStringArray(R.array.sync_interval_values)
            val fallbackOptions = arrayOf(getString(R.string.label_off)) + intervalEntries
            val fallbackOptionValues = arrayOf("-1") + intervalValues

            // Event loop interval (per account, applies to both contacts + calendar)
            val eventLoopKey = SyncPreferences.eventLoopIntervalKey(account.name)
            bindDropdownPref(
                labelView = cardBinding.valueEventLoop,
                clickableRow = cardBinding.rowEventLoop,
                dialogTitle = getString(R.string.dialog_title_event_loop_interval),
                entries = intervalEntries,
                values = intervalValues,
                prefKey = eventLoopKey,
                defaultValue = "3600",
                prefs = prefs,
                formatLabel = { v -> formatIntervalLabel(v, intervalEntries, intervalValues) },
                onChanged = { applyEventLoopInterval(account) }
            )

            // "Show notification while syncing" — gates the IMPORTANCE_MIN ongoing notif.
            // Default true so users see something is happening; flip off if they find it noisy.
            // Sync adapters consult this pref before posting; clearing already-posted notifs
            // when toggled off happens naturally on the next sync cycle.
            val notifyInProgressKey = SyncPreferences.notifySyncInProgressKey(account.name)
            cardBinding.switchNotifyInProgress.isChecked = prefs.getBoolean(notifyInProgressKey, true)
            cardBinding.switchNotifyInProgress.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(notifyInProgressKey, checked).apply()
                if (!checked) {
                    // Immediate feedback: drop any currently-displayed progress notif for this account.
                    SyncNotificationHelper.cancelOngoingForAccount(this, account.name)
                }
            }

            // Contacts fallback full sync (per account)
            val contactsFallbackKey = SyncPreferences.fallbackContactsIntervalKey(account.name)
            val contactsFallbackEnabledKey = SyncPreferences.fallbackContactsEnabledKey(account.name)
            val contactsFallbackEnabled = prefs.getBoolean(contactsFallbackEnabledKey, false)
            val currentContactsFallback = prefs.getString(contactsFallbackKey, SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS) ?: SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS
            cardBinding.valueContactsFallback.text = if (contactsFallbackEnabled) {
                formatIntervalLabel(currentContactsFallback, intervalEntries, intervalValues)
            } else getString(R.string.label_off)

            cardBinding.rowContactsFallback.setOnClickListener {
                val liveEnabled = prefs.getBoolean(contactsFallbackEnabledKey, false)
                val liveVal = prefs.getString(contactsFallbackKey, SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS) ?: SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS
                val currentVal = if (liveEnabled) liveVal else "-1"
                showListDialog(getString(R.string.dialog_title_contacts_fallback), fallbackOptions, fallbackOptionValues, contactsFallbackKey, prefs, currentVal) { label ->
                    val selectedVal = SyncPreferences.getStringOrDefault(prefs, contactsFallbackKey, "-1")
                    if (selectedVal == "-1") {
                        prefs.edit().putBoolean(contactsFallbackEnabledKey, false).apply()
                        cardBinding.valueContactsFallback.text = getString(R.string.label_off)
                        SyncWorker.scheduleForAccount(this, account.name, "contacts", -1)
                    } else {
                        prefs.edit().putBoolean(contactsFallbackEnabledKey, true).apply()
                        cardBinding.valueContactsFallback.text = label
                        val seconds = selectedVal.toLongOrNull() ?: 43200
                        // Armed only while the subscription lets sync run; the subscription observer
                        // arms it from the stored value when the subscription turns active.
                        if (SubscriptionGate.isSyncAllowedCached(this)) {
                            SyncWorker.scheduleForAccount(this, account.name, "contacts", seconds / 60)
                        } else {
                            SyncLog.i("SyncSettings", "Contacts fallback for '${account.name}' set to ${seconds}s; not armed while the subscription is inactive")
                        }
                    }
                }
            }

            // Calendar fallback full sync (per account)
            val calendarFallbackKey = SyncPreferences.fallbackCalendarIntervalKey(account.name)
            val calendarFallbackEnabledKey = SyncPreferences.fallbackCalendarEnabledKey(account.name)
            val calendarFallbackEnabled = prefs.getBoolean(calendarFallbackEnabledKey, false)
            val currentCalendarFallback = prefs.getString(calendarFallbackKey, SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS) ?: SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS
            cardBinding.valueCalendarFallback.text = if (calendarFallbackEnabled) {
                formatIntervalLabel(currentCalendarFallback, intervalEntries, intervalValues)
            } else getString(R.string.label_off)

            cardBinding.rowCalendarFallback.setOnClickListener {
                val liveEnabled = prefs.getBoolean(calendarFallbackEnabledKey, false)
                val liveVal = prefs.getString(calendarFallbackKey, SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS) ?: SyncPreferences.DEFAULT_FALLBACK_INTERVAL_SECONDS
                val currentVal = if (liveEnabled) liveVal else "-1"
                showListDialog(getString(R.string.dialog_title_calendar_fallback), fallbackOptions, fallbackOptionValues, calendarFallbackKey, prefs, currentVal) { label ->
                    val selectedVal = SyncPreferences.getStringOrDefault(prefs, calendarFallbackKey, "-1")
                    if (selectedVal == "-1") {
                        prefs.edit().putBoolean(calendarFallbackEnabledKey, false).apply()
                        cardBinding.valueCalendarFallback.text = getString(R.string.label_off)
                        SyncWorker.scheduleForAccount(this, account.name, "calendar", -1)
                    } else {
                        prefs.edit().putBoolean(calendarFallbackEnabledKey, true).apply()
                        cardBinding.valueCalendarFallback.text = label
                        val seconds = selectedVal.toLongOrNull() ?: 43200
                        // Armed only while the subscription lets sync run, as above.
                        if (SubscriptionGate.isSyncAllowedCached(this)) {
                            SyncWorker.scheduleForAccount(this, account.name, "calendar", seconds / 60)
                        } else {
                            SyncLog.i("SyncSettings", "Calendar fallback for '${account.name}' set to ${seconds}s; not armed while the subscription is inactive")
                        }
                    }
                }
            }

            // --- Re-authenticate ---
            cardBinding.btnReauth.visibility = View.VISIBLE
            val session = sessionManager.getSession(account)
            if (session.mailboxPassword == null) {
                // Highlight when auth is needed
                cardBinding.accountStatus.text = getString(R.string.label_authentication_required)
                cardBinding.accountStatus.setTextColor(0xFFFFA726.toInt())
            }
            cardBinding.btnReauth.setOnClickListener {
                val intent = Intent(this, LoginActivity::class.java).apply {
                    putExtra(LoginActivity.EXTRA_ACCOUNT_NAME, account.name)
                    putExtra(SyncConstants.EXTRA_REAUTH, true)
                }
                startActivity(intent)
            }

            // --- Notification toggles ---
            val notifyContactsKey = SyncPreferences.notifySyncFailureKey("contacts", account.name)
            cardBinding.switchNotifyContacts.isChecked = prefs.getBoolean(notifyContactsKey, true)
            cardBinding.switchNotifyContacts.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(notifyContactsKey, checked).apply()
            }

            // Which contacts sync. The value on the right states the effect so the card answers
            // the question without being opened; the rules themselves need their own screen.
            bindInfo(
                cardBinding.infoContactFilters,
                R.string.label_contact_filters,
                R.string.info_contact_filters_body
            )
            cardBinding.rowContactFilters.setOnClickListener {
                ContactFilterRulesActivity.start(this, account.name)
            }
            val filterRuleSet = me.proton.sync.contacts.filter.ContactFilterStore
                .load(this, account.name)
            // Deliberately not a count. Plural categories differ per language (Polish needs four
            // forms, Irish five) and a missing one is a lint error, which is a lot of grammar to
            // maintain for a value that sits one tap from the rules themselves.
            // isInactive, not isEmpty: a rule list where everything is switched off filters
            // nothing, and saying "Filtered" there described the screen rather than the effect.
            cardBinding.valueContactFilters.text = getString(
                if (filterRuleSet.isInactive) R.string.contact_filters_value_all
                else R.string.contact_filters_value_filtered
            )

            // Where a display name lands when Proton has no first or last name for the contact.
            bindInfo(
                cardBinding.infoNameMode,
                R.string.label_name_mode,
                R.string.name_mode_info
            )
            cardBinding.valueNameMode.text = getString(nameModeLabel(ContactNameMode.of(this, account.name)))
            cardBinding.rowNameMode.setOnClickListener {
                chooseNameMode(account.name) {
                    cardBinding.valueNameMode.text = getString(nameModeLabel(it))
                }
            }
            cardBinding.rowNameRepair.setOnClickListener { repairInventedNames(account) }

            val notifyCalendarKey = SyncPreferences.notifySyncFailureKey("calendar", account.name)
            cardBinding.switchNotifyCalendar.isChecked = prefs.getBoolean(notifyCalendarKey, true)
            cardBinding.switchNotifyCalendar.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(notifyCalendarKey, checked).apply()
            }

            val sendInvitesKey = SyncPreferences.sendCalendarInvitesKey(account.name)
            cardBinding.switchSendInvites.isChecked = prefs.getBoolean(sendInvitesKey, false)
            cardBinding.switchSendInvites.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(sendInvitesKey, checked).apply()
            }

            val sendUpdatesKey = SyncPreferences.sendCalendarUpdatesKey(account.name)
            cardBinding.switchSendUpdates.isChecked = prefs.getBoolean(sendUpdatesKey, false)
            cardBinding.switchSendUpdates.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(sendUpdatesKey, checked).apply()
            }

            val sendCancellationsKey = SyncPreferences.sendCalendarCancellationsKey(account.name)
            cardBinding.switchSendCancellations.isChecked = prefs.getBoolean(sendCancellationsKey, false)
            cardBinding.switchSendCancellations.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(sendCancellationsKey, checked).apply()
            }

            val inviteSubjectIncludeTitleKey = SyncPreferences.inviteSubjectIncludeTitleKey(account.name)
            cardBinding.switchInviteSubjectIncludeTitle.isChecked =
                prefs.getBoolean(inviteSubjectIncludeTitleKey, false)
            cardBinding.switchInviteSubjectIncludeTitle.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(inviteSubjectIncludeTitleKey, checked).apply()
            }

            // --- Last sync dates (clickable to cycle format) ---
            val dateFormatNames = arrayOf("short", "medium", "long", "iso", "eu")

            fun formatLastSync(millis: Long, formatIdx: Int): String {
                if (millis == 0L) return getString(R.string.label_never)
                val date = java.util.Date(millis)
                return when (dateFormatNames[formatIdx]) {
                    "iso" -> java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.US).format(date)
                    "eu" -> java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.US).format(date)
                    else -> {
                        val style = when (dateFormatNames[formatIdx]) {
                            "short" -> java.text.DateFormat.SHORT
                            "long" -> java.text.DateFormat.LONG
                            else -> java.text.DateFormat.MEDIUM
                        }
                        java.text.DateFormat.getDateTimeInstance(style, java.text.DateFormat.SHORT, java.util.Locale.getDefault()).format(date)
                    }
                }
            }

            // Date format and timestamps are re-read on every label update so a sync that
            // finishes while the activity is still on screen reflects immediately (the
            // sync-status listener re-invokes this lambda).
            fun updateLastSyncLabels() {
                val formatIdx = dateFormatNames.indexOf(
                    prefs.getString(SyncPreferences.LAST_SYNC_DATE_FORMAT_KEY, "medium")
                ).coerceAtLeast(0)
                val lastContactsSync = prefs.getLong(SyncPreferences.lastSyncKey(account.name, "contacts"), 0L)
                val lastContactsFail = prefs.getLong(SyncPreferences.lastSyncFailedKey(account.name, "contacts"), 0L)
                val lastCalendarSync = prefs.getLong(SyncPreferences.lastSyncKey(account.name, "calendar"), 0L)
                val lastCalendarFail = prefs.getLong(SyncPreferences.lastSyncFailedKey(account.name, "calendar"), 0L)

                cardBinding.textContactsLastSync.text = "\u2713 ${formatLastSync(lastContactsSync, formatIdx)}"
                cardBinding.textContactsLastSync.alpha = 0.4f

                // Contacts failure \u2014 only show when the most recent attempt actually failed.
                // Stale red dates (lastFail < lastSync) mislead the user into thinking
                // something is wrong right now when it's months-old history.
                val showContactsFail = lastContactsFail > 0L && lastContactsFail > lastContactsSync
                if (showContactsFail) {
                    cardBinding.textContactsLastFail.text = "\u2717 ${formatLastSync(lastContactsFail, formatIdx)}"
                    cardBinding.textContactsLastFail.setTextColor(0xFFE57373.toInt())
                    cardBinding.textContactsLastFail.alpha = 0.8f
                    cardBinding.textContactsLastFail.visibility = View.VISIBLE
                } else {
                    cardBinding.textContactsLastFail.visibility = View.GONE
                }

                cardBinding.textCalendarLastSync.text = "\u2713 ${formatLastSync(lastCalendarSync, formatIdx)}"
                cardBinding.textCalendarLastSync.alpha = 0.4f

                val showCalendarFail = lastCalendarFail > 0L && lastCalendarFail > lastCalendarSync
                if (showCalendarFail) {
                    cardBinding.textCalendarLastFail.text = "\u2717 ${formatLastSync(lastCalendarFail, formatIdx)}"
                    cardBinding.textCalendarLastFail.setTextColor(0xFFE57373.toInt())
                    cardBinding.textCalendarLastFail.alpha = 0.8f
                    cardBinding.textCalendarLastFail.visibility = View.VISIBLE
                } else {
                    cardBinding.textCalendarLastFail.visibility = View.GONE
                }
            }
            updateLastSyncLabels()

            val onDateFormatClick = android.view.View.OnClickListener {
                val current = dateFormatNames.indexOf(
                    prefs.getString(SyncPreferences.LAST_SYNC_DATE_FORMAT_KEY, "medium")
                ).coerceAtLeast(0)
                val next = (current + 1) % dateFormatNames.size
                prefs.edit().putString(SyncPreferences.LAST_SYNC_DATE_FORMAT_KEY, dateFormatNames[next]).apply()
                updateLastSyncLabels()
            }
            cardBinding.textContactsLastSync.setOnClickListener(onDateFormatClick)
            cardBinding.textCalendarLastSync.setOnClickListener(onDateFormatClick)

            // Red \u2717 rows: tap shows the failure-explanation dialog (with the friendly reason
            // and a "View logs" shortcut), long-press still cycles the date format so the
            // existing feature is reachable without rewiring it onto the green \u2713 rows.
            cardBinding.textContactsLastFail.setOnClickListener {
                val ts = prefs.getLong(SyncPreferences.lastSyncFailedKey(account.name, "contacts"), 0L)
                showSyncFailureDialog(account.name, "contacts", ts)
            }
            cardBinding.textContactsLastFail.setOnLongClickListener {
                onDateFormatClick.onClick(it); true
            }
            cardBinding.textCalendarLastFail.setOnClickListener {
                val ts = prefs.getLong(SyncPreferences.lastSyncFailedKey(account.name, "calendar"), 0L)
                showSyncFailureDialog(account.name, "calendar", ts)
            }
            cardBinding.textCalendarLastFail.setOnLongClickListener {
                onDateFormatClick.onClick(it); true
            }
            // Capture the refresher so refreshSyncIndicators() can re-render labels
            // immediately when a background sync completes.
            cardRefreshSyncDates = ::updateLastSyncLabels

            // --- Action buttons ---
            // --- Per-section sync buttons ---
            cardBinding.btnSyncContacts.setOnClickListener {
                if (!subscriptionManager.isSyncAllowed()) {
                    Toast.makeText(this, getString(R.string.sync_disabled_no_subscription), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (account.name in fullResyncInFlightAccounts) {
                    Toast.makeText(this, getString(R.string.toast_resync_in_flight), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                SyncLog.i("UserAction", "Manual contacts sync requested for '${account.name}'")
                // Announcing a sync that was declined is worse than saying nothing: the user
                // switched the type off, tapped a button, and was told it had started.
                val started = requestManualSync(account, abAccount, "contacts").contacts
                Toast.makeText(
                    this,
                    if (started) getString(R.string.toast_contacts_sync_started)
                    else getString(R.string.toast_resync_skipped, getString(R.string.sync_contacts)),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            cardBinding.btnSyncContacts.setOnLongClickListener {
                showFullResyncDialog(account, abAccount, "contacts")
                true
            }

            cardBinding.btnSyncCalendar.setOnClickListener {
                if (!subscriptionManager.isSyncAllowed()) {
                    Toast.makeText(this, getString(R.string.sync_disabled_no_subscription), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (account.name in fullResyncInFlightAccounts) {
                    Toast.makeText(this, getString(R.string.toast_resync_in_flight), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                SyncLog.i("UserAction", "Manual calendar sync requested for '${account.name}'")
                val started = requestManualSync(account, abAccount, "calendar").calendar
                Toast.makeText(
                    this,
                    if (started) getString(R.string.toast_calendar_sync_started)
                    else getString(R.string.toast_resync_skipped, getString(R.string.sync_calendar)),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            cardBinding.btnSyncCalendar.setOnLongClickListener {
                showFullResyncDialog(account, abAccount, "calendar")
                true
            }

            // --- Sync all ---
            cardBinding.btnSyncNow.setOnClickListener {
                if (!subscriptionManager.isSyncAllowed()) {
                    Toast.makeText(this, getString(R.string.sync_disabled_no_subscription), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (account.name in fullResyncInFlightAccounts) {
                    Toast.makeText(this, getString(R.string.toast_resync_in_flight), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                SyncLog.i("UserAction", "Manual sync (contacts + calendar) requested for '${account.name}'")
                // The two buttons beside this one already refuse to announce a sync that was declined.
                // This one said "Sync started" whatever happened, so an account with both types
                // switched off reported a sync on every tap and never ran one.
                val started = requestManualSync(account, abAccount, "all")
                Toast.makeText(
                    this,
                    if (started.contacts || started.calendar) getString(R.string.toast_sync_started)
                    else getString(R.string.toast_sync_nothing_enabled),
                    Toast.LENGTH_SHORT,
                ).show()
            }

            cardBinding.btnSyncNow.setOnLongClickListener {
                showFullResyncDialog(account, abAccount)
                true
            }

            cardBinding.btnRemoveAccount.setOnClickListener {
                showRemoveDialog(account, abAccount)
            }

            container.addView(cardBinding.root, container.childCount - 1)
        }
    }

    // --- Display name helpers ---

    private fun getDisplayName(am: AccountManager, account: Account): String {
        // Account.name is already the display name (set during login)
        return account.name
    }

    private fun updateStatusLine(
        cardBinding: SettingsAccountCardBinding,
        abAccount: Account,
        account: Account
    ) {
        val items = mutableListOf<String>()
        if (ContentResolver.getSyncAutomatically(abAccount, SyncConstants.AUTHORITY_CONTACTS)) {
            items.add(getString(R.string.sync_contacts))
        }
        if (ContentResolver.getSyncAutomatically(account, SyncConstants.AUTHORITY_CALENDAR)) {
            items.add(getString(R.string.sync_calendar))
        }
        cardBinding.accountStatus.text = if (items.isNotEmpty()) {
            getString(R.string.label_syncing_status, items.joinToString(getString(R.string.label_joiner)))
        } else {
            getString(R.string.label_sync_disabled)
        }
    }

    /**
     * Populate the overview hero's contacts/events tiles from the system providers,
     * off the main thread. Best-effort: leaves the placeholder on any failure (the hero
     * degrades to status line + "Sync all now").
     */
    private fun updateHeroStats() {
        lifecycleScope.launch {
            val (contacts, events) = withContext(Dispatchers.IO) {
                val cr = contentResolver
                val contactsCount = try {
                    cr.query(
                        android.provider.ContactsContract.RawContacts.CONTENT_URI,
                        arrayOf(android.provider.ContactsContract.RawContacts._ID),
                        "${android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                            "${android.provider.ContactsContract.RawContacts.DELETED} = 0",
                        arrayOf(ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK),
                        null
                    )?.use { it.count } ?: 0
                } catch (_: Exception) { -1 }
                val eventsCount = try {
                    val calIds = ArrayList<String>()
                    cr.query(
                        android.provider.CalendarContract.Calendars.CONTENT_URI,
                        arrayOf(android.provider.CalendarContract.Calendars._ID),
                        "${android.provider.CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                        arrayOf(ProtonSyncApp.ACCOUNT_TYPE),
                        null
                    )?.use { c -> while (c.moveToNext()) calIds.add(c.getLong(0).toString()) }
                    if (calIds.isEmpty()) 0 else cr.query(
                        android.provider.CalendarContract.Events.CONTENT_URI,
                        arrayOf(android.provider.CalendarContract.Events._ID),
                        "${android.provider.CalendarContract.Events.CALENDAR_ID} IN " +
                            "(${calIds.joinToString(",") { "?" }}) AND " +
                            "${android.provider.CalendarContract.Events.DELETED} = 0",
                        calIds.toTypedArray(),
                        null
                    )?.use { it.count } ?: 0
                } catch (_: Exception) { -1 }
                contactsCount to eventsCount
            }
            if (!isFinishing && !isDestroyed) {
                if (contacts >= 0) binding.heroContactsCount.text = "%,d".format(contacts)
                if (events >= 0) binding.heroEventsCount.text = "%,d".format(events)
            }
        }
    }

    /**
     * Derive the overview hero status from per-account sync state, first match wins: "nothing is
     * syncing yet" when no account has a sync type switched on; "sync is off" when the subscription
     * lets no sync start; "waiting for the first sync" when nothing enabled has ever completed;
     * "needs attention" when any ENABLED account/authority has a failure newer than its last success
     * (the same rule the account cards use for their red fail line); otherwise the green
     * "everything's synced".
     */
    private fun updateHeroStatus(accounts: List<Account>) {
        val am = AccountManager.get(this)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val needsAttention = accounts.any { account ->
            val abAccount = Account(
                SyncUtils.addressBookAccountName(am, account),
                ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK
            )
            val contactsEnabled = ContentResolver.getSyncAutomatically(abAccount, SyncConstants.AUTHORITY_CONTACTS)
            val calendarEnabled = ContentResolver.getSyncAutomatically(account, SyncConstants.AUTHORITY_CALENDAR)
            val cSync = prefs.getLong(SyncPreferences.lastSyncKey(account.name, "contacts"), 0L)
            val cFail = prefs.getLong(SyncPreferences.lastSyncFailedKey(account.name, "contacts"), 0L)
            val calSync = prefs.getLong(SyncPreferences.lastSyncKey(account.name, "calendar"), 0L)
            val calFail = prefs.getLong(SyncPreferences.lastSyncFailedKey(account.name, "calendar"), 0L)
            (contactsEnabled && cFail > 0L && cFail > cSync) ||
                (calendarEnabled && calFail > 0L && calFail > calSync)
        }
        // Nothing switched on anywhere. Reached most often by a brand new account whose owner
        // answered "not now" to the setup chooser, and the green state is actively wrong there:
        // "Everything's synced" is technically true of an empty set of enabled accounts, but it
        // tells somebody who has just finished signing in that they are done when nothing has been
        // set up at all. Checked before needsAttention because a disabled account cannot fail.
        val nothingEnabled = accounts.none { account ->
            val abAccount = Account(
                SyncUtils.addressBookAccountName(am, account),
                ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK
            )
            ContentResolver.getSyncAutomatically(abAccount, SyncConstants.AUTHORITY_CONTACTS) ||
                ContentResolver.getSyncAutomatically(account, SyncConstants.AUTHORITY_CALENDAR)
        }
        if (nothingEnabled) {
            showHeroWarning(R.string.sfp_hero_nothing_enabled, R.string.sfp_hero_subtitle_nothing_enabled)
            return
        }
        // Sync is switched on, but the subscription gate drops every sync before it starts, so
        // neither "waiting" nor "synced" is true. Checked before both, and before needsAttention,
        // because an old failure is not what stops sync now.
        if (!subscriptionManager.isSyncAllowed()) {
            showHeroWarning(R.string.sfp_hero_sync_off, R.string.sfp_hero_subtitle_sync_off)
            return
        }
        // Enabled, but nothing has ever completed. "Everything's synced" is wrong here in the way
        // that matters most: it is the first thing a new user sees after choosing what to sync, it
        // shows a green tick above two zero counts, and it claims a result before any sync has run.
        // It is also what a user sees when every sync is being skipped, which is exactly when they
        // need to be told that nothing has happened yet. Checked AFTER needsAttention so a first
        // sync that actually failed still reports the failure rather than looking merely pending.
        val awaitingFirstSync = run {
            val lastSuccesses = accounts.flatMap { account ->
                val abAccount = Account(
                    SyncUtils.addressBookAccountName(am, account),
                    ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK
                )
                buildList {
                    if (ContentResolver.getSyncAutomatically(abAccount, SyncConstants.AUTHORITY_CONTACTS)) {
                        add(prefs.getLong(SyncPreferences.lastSyncKey(account.name, "contacts"), 0L))
                    }
                    if (ContentResolver.getSyncAutomatically(account, SyncConstants.AUTHORITY_CALENDAR)) {
                        add(prefs.getLong(SyncPreferences.lastSyncKey(account.name, "calendar"), 0L))
                    }
                }
            }
            lastSuccesses.isNotEmpty() && lastSuccesses.all { it == 0L }
        }
        if (awaitingFirstSync) {
            showHeroWarning(R.string.sfp_hero_awaiting_first_sync, R.string.sfp_hero_subtitle_awaiting_first_sync)
            return
        }
        if (needsAttention) {
            showHeroWarning(R.string.sfp_hero_warn, R.string.sfp_hero_subtitle_warn)
        } else {
            binding.heroStatusBox.setCardBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.sfp_good_container))
            binding.heroStatusIcon.setImageResource(R.drawable.ic_status_check)
            binding.heroStatusIcon.clearColorFilter()
            binding.heroTitle.setText(R.string.sfp_hero_ok)
            binding.heroSubtitle.setText(R.string.sfp_hero_subtitle_ok)
        }
    }

    /** The hero in its amber warning style, with [title] and [subtitle]. */
    private fun showHeroWarning(@StringRes title: Int, @StringRes subtitle: Int) {
        binding.heroStatusBox.setCardBackgroundColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.sfp_warn_container))
        binding.heroStatusIcon.setImageResource(R.drawable.ic_sfp_warn)
        binding.heroStatusIcon.setColorFilter(
            androidx.core.content.ContextCompat.getColor(this, R.color.sfp_warn))
        binding.heroTitle.setText(title)
        binding.heroSubtitle.setText(subtitle)
    }

    // --- Dialogs ---

    private fun showAliasDialog(am: AccountManager, account: Account) {
        showRenameAccountDialog(account)
    }

    /**
     * Editable rename dialog for the parent account's display name. Calls the
     * atomic [ProtonSyncApp.renameParentAccount] helper, which re-points every
     * Room row, CalendarContract.Calendars row, SharedPreferences key, the
     * linked address-book account's parent link, and the in-memory session
     * cache before deleting the old account.
     */
    private fun showRenameAccountDialog(account: Account) {
        val input = android.widget.EditText(this).apply {
            setText(account.name)
            setSelectAllOnFocus(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            hint = getString(R.string.dialog_hint_display_name)
            setPadding(60, 40, 60, 20)
        }
        val container = android.widget.FrameLayout(this).apply {
            val dp = (24 * resources.displayMetrics.density).toInt()
            setPadding(dp, (8 * resources.displayMetrics.density).toInt(), dp, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_display_name))
            .setMessage(getString(R.string.dialog_message_display_name))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                val newName = input.text.toString().trim()
                applyDisplayNameRename(account, newName)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * One-time prompt offered on the first v1.3.97+ launch to accounts whose name was
     * silently overwritten by the v1.3.80 forced rename. Heuristic: account name matches
     * the email-shaped form derived from `proton_username`, AND we have no record that
     * the user has chosen this name themselves (set at login or via the rename dialog),
     * AND the prompt has not already been shown for this account.
     *
     * The user-chosen flag won't be present on legacy accounts that pre-date its
     * introduction, which is the whole point — those are the accounts most likely
     * to have lost their custom name. We accept that users who genuinely picked the
     * canonical email at login will see one prompt they can dismiss.
     */
    private fun maybeOfferDisplayNameRecovery() {
        val am = accountManager
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val candidate = am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE).firstOrNull { account ->
            if (prefs.getBoolean(SyncPreferences.displayNameRecoveryOfferedKey(account.name), false)) return@firstOrNull false
            if (prefs.getBoolean(SyncPreferences.displayNameUserChosenKey(account.name), false)) return@firstOrNull false
            val protonUsername = am.getUserData(account, "proton_username") ?: return@firstOrNull false
            val canonical = if (protonUsername.contains('@')) protonUsername else "$protonUsername@proton.me"
            account.name == canonical
        } ?: return

        // Mark as offered up front so dismissing or backing out also counts. Stops the
        // prompt from re-firing on every onResume during the same session.
        prefs.edit()
            .putBoolean(SyncPreferences.displayNameRecoveryOfferedKey(candidate.name), true)
            .apply()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_display_name_recovery))
            .setMessage(getString(R.string.dialog_message_display_name_recovery))
            .setPositiveButton(getString(R.string.btn_set_display_name)) { _, _ ->
                showRenameAccountDialog(candidate)
            }
            .setNegativeButton(getString(R.string.btn_skip), null)
            .show()
    }

    private fun applyDisplayNameRename(account: Account, newName: String) {
        if (newName.isBlank()) {
            Toast.makeText(this, R.string.toast_display_name_blank, Toast.LENGTH_SHORT).show()
            return
        }
        if (newName == account.name) return

        val am = accountManager
        if (am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE).any { it.name == newName }) {
            Toast.makeText(
                this,
                getString(R.string.toast_display_name_taken, newName),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val app = application as ProtonSyncApp
        val ok = app.renameParentAccount(account, newName)
        if (ok) {
            // Mark name as user-chosen so the recovery prompt skips this account.
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean(SyncPreferences.displayNameUserChosenKey(newName), true)
                .putBoolean(SyncPreferences.displayNameRecoveryOfferedKey(newName), true)
                .apply()
            Toast.makeText(
                this,
                getString(R.string.toast_display_name_renamed, newName),
                Toast.LENGTH_SHORT
            ).show()
            buildAccountCards()
        } else {
            Toast.makeText(this, R.string.toast_display_name_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showListDialog(
        title: String,
        entries: Array<String>,
        values: Array<String>,
        prefKey: String,
        prefs: android.content.SharedPreferences,
        currentValue: String? = null,
        onSelected: (String) -> Unit
    ) {
        val effectiveValue = currentValue ?: prefs.getString(prefKey, values.firstOrNull()) ?: ""
        // If current value is custom (not in preset values), select "Custom" entry
        val checkedItem = values.indexOf(effectiveValue).let {
            if (it < 0) values.indexOf("custom").coerceAtLeast(0) else it
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(entries, checkedItem) { dialog, which ->
                if (values[which] == "custom") {
                    dialog.dismiss()
                    showCustomValueDialog(title, prefKey, prefs, onSelected)
                } else {
                    prefs.edit().putString(prefKey, values[which]).apply()
                    onSelected(entries[which])
                    dialog.dismiss()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showCustomValueDialog(
        title: String,
        prefKey: String,
        prefs: android.content.SharedPreferences,
        onSelected: (String) -> Unit
    ) {
        val isInterval = prefKey.contains("interval")
        val unit = if (isInterval) getString(R.string.label_minutes) else getString(R.string.label_days)
        val hint = if (isInterval) "e.g. 45" else "e.g. 14"

        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            this.hint = hint
            setPadding(60, 40, 60, 20)
        }

        AlertDialog.Builder(this)
            .setTitle(title + getString(R.string.label_custom_suffix))
            .setMessage(getString(R.string.dialog_message_enter_value, unit))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                val text = input.text.toString().trim()
                val number = text.toLongOrNull()
                if (number != null && number > 0) {
                    val storedValue = if (isInterval) (number * 60).toString() else number.toString()
                    prefs.edit().putString(prefKey, storedValue).apply()
                    val label = if (isInterval) "$number ${getString(R.string.label_minutes)}" else getString(R.string.label_days_format, number.toInt())
                    onSelected(label)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * Ask Proton to end this account's session, without blocking the removal that follows.
     *
     * Fired on a scope that outlives this activity, because the user is about to leave the screen and
     * a revoke cancelled half way would be worse than one that never ran: the account would be gone
     * locally with the session still live on Proton.
     */
    private fun revokeProtonSession(accountName: String) {
        val deps = dagger.hilt.android.EntryPointAccessors.fromApplication(
            applicationContext, me.proton.sync.backup.BackupDependenciesEntryPoint::class.java
        )
        val api = deps.apiClient()
        // Read the session HERE, on the calling thread, before the caller tears the account down. The
        // first version launched the coroutine and let it look the session up for itself, which lost the
        // race every time: HTTP 401, and the session still listed on Proton afterwards.
        val session = sessionManager.getSession(accountName)
        if (session == null || session.accessToken.isEmpty() || session.uid.isEmpty()) {
            SyncLog.w(
                "UserAction",
                "No usable session for the account being removed, so Proton cannot be told to end it; " +
                    "it will expire on its own"
            )
            return
        }
        val bearer = "Bearer ${session.accessToken}"
        val uid = session.uid
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = api.revokeSession(accountName, bearer, uid)
                if (response.isSuccessful) {
                    SyncLog.i("UserAction", "Signed the removed account out of Proton; its session is revoked")
                } else {
                    SyncLog.w(
                        "UserAction",
                        "Proton did not accept the sign-out for the removed account (HTTP ${response.code()}${response.protonErrorSuffix()}); " +
                            "its session will stay valid until it expires on its own"
                    )
                }
            } catch (e: Exception) {
                SyncLog.w(
                    "UserAction",
                    "Could not reach Proton to sign the removed account out (${e.javaClass.simpleName}); " +
                        "its session will stay valid until it expires on its own"
                )
            }
        }
    }

    /**
     * Everything removing an account does, in the order it has to happen.
     *
     * One implementation for the dialog and for the debug verb, so the verb cannot drift from the
     * button it stands for. It does NOT refresh the screen: the dialog does that itself, and the debug
     * path has no screen to refresh.
     */
    private fun removeAccount(account: Account, abAccount: Account) {
        val am = accountManager
        // Tell Proton the session is finished, BEFORE the account goes: the call needs the very token
        // and uid that removal destroys. Without it the refresh token stayed valid until it expired on
        // its own and the session went on being listed in Proton's account settings, so "remove account"
        // only ever meant "forget it here".
        //
        // Best effort on purpose, and never in the way: offline, an expired token or Proton being down
        // must not stop somebody removing an account from their own phone. The removal below runs either
        // way.
        revokeProtonSession(account.name)
        am.removeAccountExplicitly(abAccount)
        am.removeAccountExplicitly(account)
        // Unlocked private keys live in memory for the process lifetime; without this they outlive the
        // account they belong to until the app is killed.
        keyManager.clearKeyRing(account.name)
        sessionManager.removeSession(account.name)
    }

    private fun showRemoveDialog(account: Account, abAccount: Account) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_remove_account))
            .setMessage(getString(R.string.dialog_message_remove_account, getDisplayName(accountManager, account)))
            .setPositiveButton(getString(R.string.dialog_title_remove_account)) { _, _ ->
                removeAccount(account, abAccount)
                buildAccountCards()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // --- Sync interval helpers ---

    /**
     * The sync interval picker's handler, run once it has stored the new value (and by the "interval"
     * debug command): the event loop and both periodic syncs follow the stored interval.
     */
    private fun applyEventLoopInterval(account: Account) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val seconds = SyncPreferences.getLongFromStringPref(prefs, SyncPreferences.eventLoopIntervalKey(account.name), 3600)
        val abAcct = Account(
            SyncUtils.addressBookAccountName(accountManager, account),
            ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK
        )
        if (seconds < 0) {
            // "Manual only". The sentinel is -1 SECONDS, and this used to hand
            // scheduleForAccount `seconds / 60`, which truncates -1 to 0. The worker
            // cancels on a NEGATIVE interval, so 0 fell through to
            // maxOf(0, MIN_INTERVAL_MINUTES) and scheduled the event loop every 15
            // minutes - the fastest the app offers, from the option that promises none.
            // The two fallback pickers always branched on the sentinel; this one never
            // did.
            EventLoopWorker.scheduleForAccount(this, account.name, -1)
            // And manual has to mean manual. This is the only schedule control in the
            // UI, but the hourly ContentResolver periodic sync armed at provisioning
            // is what actually runs the adapter, and nothing here ever touched it - so
            // "Manual only" left a background sync running every hour regardless.
            SyncUtils.applyPeriodicSync(this, abAcct, SyncConstants.AUTHORITY_CONTACTS, account.name)
            SyncUtils.applyPeriodicSync(this, account, SyncConstants.AUTHORITY_CALENDAR, account.name)
            SyncLog.i("SyncSettings", "Event loop set to manual for '${account.name}': event loop cancelled and both periodic syncs removed")
        } else {
            // Armed only while the subscription lets sync run, like the periodic syncs below (gated
            // inside applyPeriodicSync). The subscription observer arms it from the stored value when
            // the subscription turns active.
            val allowed = SubscriptionGate.isSyncAllowedCached(this)
            if (allowed) {
                EventLoopWorker.scheduleForAccount(this, account.name, EventLoopWorker.intervalMinutesFor(seconds))
            }
            // The chosen interval now governs the periodic sync too, for whichever
            // types are actually switched on. Arming one for a type the user has
            // turned off would resurrect exactly what the switch is meant to stop.
            SyncUtils.applyPeriodicSync(this, abAcct, SyncConstants.AUTHORITY_CONTACTS, account.name)
            SyncUtils.applyPeriodicSync(this, account, SyncConstants.AUTHORITY_CALENDAR, account.name)
            if (allowed) {
                SyncLog.i("SyncSettings", "Event loop for '${account.name}' set to ${seconds}s; periodic syncs re-armed to match")
            } else {
                SyncLog.i("SyncSettings", "Event loop for '${account.name}' set to ${seconds}s; not armed while the subscription is inactive")
            }
        }
    }

    /**
     * Tap-to-explain dialog for the red ✗ failure date row. Shows the localized
     * failure timestamp and the friendly reason mapped from the stored raw error
     * via [SyncNotificationHelper.friendlyMessage]. Offers a shortcut to the
     * in-app log viewer for users who want the raw stack trace.
     */
    private fun showSyncFailureDialog(accountName: String, syncType: String, failureMillis: Long) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val rawError = prefs.getString(SyncPreferences.lastSyncErrorMsgKey(syncType, accountName), null)
            .orEmpty()
        val friendly = SyncNotificationHelper.friendlyMessage(this, rawError)
        val dateText = if (failureMillis > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, java.util.Locale.getDefault())
                .format(Date(failureMillis))
        } else getString(R.string.label_never)

        val message = buildString {
            append(getString(R.string.dialog_label_sync_last_failure, dateText))
            append("\n")
            append(getString(R.string.dialog_label_sync_reason, friendly))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_sync_failed))
            .setMessage(message)
            .setPositiveButton(getString(R.string.btn_ok), null)
            .setNeutralButton(getString(R.string.notification_action_view_details)) { _, _ ->
                startActivity(Intent(this, LogViewerActivity::class.java))
            }
            .show()
    }

    private fun showFullResyncDialog(account: Account, abAccount: Account, section: String = "all") {
        if (!subscriptionManager.isSyncAllowed()) {
            Toast.makeText(this, getString(R.string.sync_disabled_no_subscription), Toast.LENGTH_SHORT).show()
            return
        }
        if (account.name in fullResyncInFlightAccounts) {
            Toast.makeText(this, getString(R.string.toast_resync_in_flight), Toast.LENGTH_SHORT).show()
            return
        }
        val sectionLabel = when (section) {
            "contacts" -> getString(R.string.label_contacts_lower)
            "calendar" -> getString(R.string.label_calendar_lower)
            else -> getString(R.string.label_all_data)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_full_resync, sectionLabel))
            .setMessage(getString(R.string.dialog_message_full_resync, sectionLabel, sectionLabel))
            .setPositiveButton(getString(R.string.btn_remote_to_local)) { _, _ ->
                Toast.makeText(this, getString(R.string.toast_preparing_resync), Toast.LENGTH_SHORT).show()
                performFullResyncRemoteToLocal(account, abAccount, section)
            }
            .setNegativeButton(getString(R.string.btn_local_to_remote)) { _, _ ->
                Toast.makeText(this, getString(R.string.toast_preparing_resync), Toast.LENGTH_SHORT).show()
                performFullResyncLocalToRemote(account, abAccount, section)
            }
            .setNeutralButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * Names of accounts whose full-resync coroutine (either direction) is currently
     * running. Per-account so that a force-resync of A doesn't grey out B's sync
     * buttons (B is independent — different rows, different calendar IDs, different
     * DB tables; Android's SyncManager already serializes per-authority).
     *
     * Mutated only on the Main thread (dialog confirm increments, finally-block
     * decrements via withContext(Main)). Every change re-runs
     * [refreshSyncIndicators] so the affected card greys out the moment the resync
     * starts — before Android promotes the requestSync to "active" — and re-enables
     * the moment all four type-bucket coroutines complete. v1.4.22.
     */
    private val fullResyncInFlightAccounts: MutableSet<String> = mutableSetOf()

    private fun markResyncStart(accountName: String) {
        fullResyncInFlightAccounts.add(accountName)
        refreshSyncIndicators()
    }

    private fun markResyncEnd(accountName: String) {
        fullResyncInFlightAccounts.remove(accountName)
        refreshSyncIndicators()
    }




    /**
     * Requests a sync only for an authority the account still has switched on.
     *
     * [ContentResolver.requestSync] does not consult `setSyncAutomatically`, so every manual sync
     * button reached authorities the user had disabled. Combined with the new offer to remove
     * synced data that is worse than untidy: switch calendar off, accept the removal, tap "sync
     * everything", and every event comes straight back with no explanation.
     */
    private fun requestSyncIfEnabled(account: Account, authority: String, bundle: Bundle): Boolean {
        if (!ContentResolver.getSyncAutomatically(account, authority)) {
            SyncLog.d("UserAction", "Not syncing $authority for '${account.name}': it is switched off")
            return false
        }
        ContentResolver.requestSync(account, authority, bundle)
        return true
    }

    /**
     * Switches one sync type on or off, including its periodic schedule.
     *
     * Setting `setSyncAutomatically` alone is not enough, and used not to matter: provisioning
     * armed the periodic sync for every new account whether or not it was ever going to be used,
     * so one was always already there to fall back on. Now that a new account starts with nothing
     * enabled, an account switched on from this screen had auto-sync but no hourly poll, and only
     * synced when something else happened to tickle the authority.
     *
     * Removing the schedule on the way down matters less, but leaving an hourly job armed for an
     * account the user has just switched off is a wakeup that can never do any work.
     */
    private fun setSyncEnabled(
        account: Account,
        authority: String,
        enabled: Boolean,
        parentAccountName: String,
    ) {
        ContentResolver.setIsSyncable(account, authority, 1)
        ContentResolver.setSyncAutomatically(account, authority, enabled)
        // Through the shared writer, which reads the account's schedule. This used to arm a
        // hardcoded hour, one row above the schedule setting: choose "Manual only", toggle this
        // switch off and on, and the hourly sync was back. That is the exact tap somebody
        // troubleshooting "it still syncs" makes, so the fix for the schedule never survived
        // that tap.
        SyncUtils.applyPeriodicSync(this, account, authority, parentAccountName)
    }

    /**
     * Asks whether the data this account already put on the phone should come off with the switch.
     *
     * Turning sync off only ever stopped future syncs, so everything already downloaded stayed
     * where it was with nothing in the app offering to clear it. That reads as a bug rather than a
     * choice: a user could be left with a calendar full of events they never wanted on
     * their device and no way to remove them short of deleting the whole account.
     *
     * Keeping is the default and the safer answer, because removal is not reversible from here.
     * The wording says plainly that Proton itself is untouched, since "remove my calendar" is
     * exactly the sentence a user would fear means the opposite.
     */
    private fun offerToRemoveSyncedData(account: Account, abAccount: Account, calendar: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(if (calendar) R.string.remove_synced_calendar_title else R.string.remove_synced_contacts_title)
            .setMessage(if (calendar) R.string.remove_synced_calendar_body else R.string.remove_synced_contacts_body)
            .setNegativeButton(R.string.remove_synced_keep, null)
            .setPositiveButton(R.string.remove_synced_remove) { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    val removed = if (calendar) {
                        me.proton.sync.SyncedDataRemover.removeCalendars(this@SyncSettingsActivity, account, syncDatabase)
                    } else {
                        me.proton.sync.SyncedDataRemover.removeContacts(
                            this@SyncSettingsActivity, abAccount, account.name, syncDatabase,
                        )
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@SyncSettingsActivity,
                            getString(
                                if (calendar) R.string.remove_synced_calendar_done
                                else R.string.remove_synced_contacts_done,
                                removed,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                        refreshSyncIndicators()
                    }
                }
            }
            .show()
    }


    /** Set by [sectionsStillEnabled]: the sync types it declined to touch, already localised. */
    private var skippedSyncTypes: String = ""

    /**
     * Narrows a forced-resync request to the sync types this account still has switched on.
     *
     * Both forced resyncs destroy first and rebuild second: Remote to Local hard-deletes every
     * local row and mapping before asking for a download, and Local to Remote marks everything
     * dirty before asking for an upload. Now that the adapters refuse to run for a switched-off
     * authority, letting a resync touch one would be the worst of both worlds, wiping the data and
     * then being denied the sync that puts it back. Skipping the section entirely is the only safe
     * answer, and doing nothing is exactly what the user asked for when they switched it off.
     *
     * @return the section to act on, or null if nothing is left to do.
     */
    private fun sectionsStillEnabled(account: Account, abAccount: Account, section: String): String? {
        val contactsOn = ContentResolver.getSyncAutomatically(abAccount, SyncConstants.AUTHORITY_CONTACTS)
        val calendarOn = ContentResolver.getSyncAutomatically(account, SyncConstants.AUTHORITY_CALENDAR)
        val wantContacts = (section == "contacts" || section == "all") && contactsOn
        val wantCalendar = (section == "calendar" || section == "all") && calendarOn
        val narrowed = when {
            wantContacts && wantCalendar -> "all"
            wantContacts -> "contacts"
            wantCalendar -> "calendar"
            else -> null
        }
        // Name what was actually skipped. The first version of this message said contacts and
        // calendar were "both switched off", which was wrong whenever only one of them was, and
        // it was long enough to be truncated in a toast. Reusing the type names the rest of the
        // screen already uses keeps it short and keeps it true.
        skippedSyncTypes = buildList {
            if ((section == "contacts" || section == "all") && !contactsOn) add(getString(R.string.sync_contacts))
            if ((section == "calendar" || section == "all") && !calendarOn) add(getString(R.string.sync_calendar))
        }.joinToString(getString(R.string.label_joiner))
        if (narrowed != section) {
            SyncLog.i(
                "UserAction",
                "Resync for '${account.name}' narrowed from '$section' to '${narrowed ?: "nothing"}': " +
                    "contacts=${if (contactsOn) "on" else "off"}, calendar=${if (calendarOn) "on" else "off"}"
            )
        }
        return narrowed
    }

    private fun performFullResyncRemoteToLocal(account: Account, abAccount: Account, requested: String = "all") {
        val section = sectionsStillEnabled(account, abAccount, requested) ?: run {
            Toast.makeText(
                this, getString(R.string.toast_resync_skipped, skippedSyncTypes), Toast.LENGTH_LONG,
            ).show()
            return
        }
        SyncLog.i("UserAction", "Force Remote→Local resync requested for '${account.name}' (section=$section)")
        markResyncStart(account.name)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (section == "contacts" || section == "all") {
                    // Read the mappings BEFORE clearing them: they are the only record of which
                    // rows came from Proton, and everything below depends on knowing that.
                    val fromProton = syncDatabase.contactSyncDao().getAllByAccount(account.name)
                        .mapNotNull { it.androidRawContactId }
                    syncDatabase.contactSyncDao().deleteAllByAccount(account.name)
                    // The local rows any held deletion asked about are about to be wiped and re-downloaded,
                    // so the question is void. Both contact types; the kept sets stay, being user decisions.
                    me.proton.sync.RemoteDeletionGuard.forgetHolds(
                        this@SyncSettingsActivity, me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS, account.name
                    )
                    me.proton.sync.RemoteDeletionGuard.forgetHolds(
                        this@SyncSettingsActivity, me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS_FILTER, account.name
                    )
                    // A reload retries everything, including contacts once found too large for this phone.
                    me.proton.sync.contacts.ContactDownloadHolds.clear(
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@SyncSettingsActivity),
                        account.name
                    )
                    // Delete AS A SYNC ADAPTER, for the same reason the calendar branch below does.
                    // A plain delete only tombstones the rows (DELETED=1, DIRTY=1), so a resync left
                    // one dead row per contact behind for good: nothing clears a tombstone the
                    // adapter never acknowledges, and they accumulate on every resync. Worse, a
                    // tombstone with DIRTY=1 and a surviving mapping is what uploadDirtyContacts
                    // turns into a REMOTE delete, so "reload my phone from Proton" was one
                    // statement-ordering away from emptying the account on the server. Only the
                    // deleteAllByAccount above, which happens to run first and strand the mappings,
                    // stood between this and that. A hard delete cannot be pushed anywhere.
                    // Only the rows that came from Proton. Deleting by account predicate also
                    // destroyed contacts that exist nowhere else, such as one created on the phone
                    // while offline whose upload has not run yet: hard-deleted, no tombstone, and
                    // no copy on the server to re-download. SyncedDataRemover was fixed for exactly
                    // this and this path was left behind.
                    var purged = 0
                    for (chunk in fromProton.chunked(400)) {
                        val placeholders = chunk.joinToString(",") { "?" }
                        purged += contentResolver.delete(
                            SyncUtils.asCallerSyncAdapter(
                                android.provider.ContactsContract.RawContacts.CONTENT_URI,
                                abAccount
                            ),
                            "${android.provider.ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                                "${android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                                "${android.provider.ContactsContract.RawContacts._ID} IN ($placeholders)",
                            (listOf(abAccount.name, abAccount.type) + chunk.map { it.toString() }).toTypedArray()
                        )
                    }
                    SyncLog.d("UserAction", "Force Remote→Local: hard-cleared $purged local contact row(s) for '${account.name}'")
                }

                if (section == "calendar" || section == "all") {
                    // Same reasoning as the contacts branch: capture what came from Proton first.
                    val protonEventIds = syncDatabase.calendarEventSyncDao()
                        .androidEventIdsByAccount(account.name).toSet()
                    syncDatabase.calendarEventSyncDao().deleteAllByAccount(account.name)
                    // Clear stored modelEventId and sync range so the next sync does a full fetch
                    syncDatabase.calendarSyncDao().deleteAllByAccount(account.name)
                    // Same as the contacts branch: the rows those holds were about are going away.
                    me.proton.sync.RemoteDeletionGuard.forgetHolds(
                        this@SyncSettingsActivity, me.proton.sync.RemoteDeletionGuard.TYPE_CALENDAR, account.name
                    )
                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@SyncSettingsActivity)
                    prefs.edit().apply {
                        prefs.all.keys.filter { it.startsWith(SyncPreferences.lastSyncRangeKey(account.name, "")) }.forEach { remove(it) }
                        apply()
                    }
                    val calCursor = contentResolver.query(
                        android.provider.CalendarContract.Calendars.CONTENT_URI,
                        arrayOf(android.provider.CalendarContract.Calendars._ID),
                        "${android.provider.CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${android.provider.CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                        arrayOf(account.name, account.type),
                        null
                    )
                    calCursor?.use { c ->
                        while (c.moveToNext()) {
                            val calId = c.getLong(0)
                            // Delete all events in this calendar (but keep the calendar entry —
                            // deleting calendar entries causes race conditions where just-inserted
                            // events are invisible to reconcileDeletedEvents and get deleted remotely).
                            // Delete AS A SYNC ADAPTER so the provider HARD-deletes the rows instead of
                            // soft-deleting them: a plain (user) delete marks rows DELETED=1 (tombstones),
                            // which the next sync's reconcile then spends ~45s "cleaning up" — and shows
                            // under the "uploading" notification phase, falsely implying an upload. A
                            // hard delete leaves no tombstones (and can never be pushed to the server).
                            // Only Proton's events. An event created on the phone and not yet
                            // uploaded has no mapping and no server copy, so clearing the whole
                            // calendar destroyed it with nothing able to bring it back.
                            // A row carrying a Proton id (_SYNC_ID, or ORIGINAL_SYNC_ID on an exception)
                            // came from Proton too, even without a mapping: a sync that died between
                            // inserting the row and saving its mapping leaves exactly that. Keeping it
                            // as "local" let the re-download adopt it, and when it was a tombstone the
                            // next sync sent its deletion to Proton. An event created on the phone
                            // never has either id.
                            val localIds = mutableListOf<Long>()
                            contentResolver.query(
                                android.provider.CalendarContract.Events.CONTENT_URI,
                                arrayOf(
                                    android.provider.CalendarContract.Events._ID,
                                    android.provider.CalendarContract.Events._SYNC_ID,
                                    android.provider.CalendarContract.Events.ORIGINAL_SYNC_ID
                                ),
                                "${android.provider.CalendarContract.Events.CALENDAR_ID} = ?",
                                arrayOf(calId.toString()),
                                null
                            )?.use { ec ->
                                while (ec.moveToNext()) {
                                    val id = ec.getLong(0)
                                    val fromProton = id in protonEventIds ||
                                        !ec.getString(1).isNullOrEmpty() || !ec.getString(2).isNullOrEmpty()
                                    if (fromProton) localIds.add(id)
                                }
                            }
                            var deleted = 0
                            for (chunk in localIds.chunked(400)) {
                                val ph = chunk.joinToString(",") { "?" }
                                deleted += contentResolver.delete(
                                    SyncUtils.asCallerSyncAdapter(android.provider.CalendarContract.Events.CONTENT_URI, account),
                                    "${android.provider.CalendarContract.Events._ID} IN ($ph)",
                                    chunk.map { it.toString() }.toTypedArray()
                                )
                            }
                            SyncLog.d("UserAction", "Force Remote→Local: hard-cleared $deleted local event row(s) from calId=$calId for '${account.name}'")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    val bundle = Bundle().apply {
                        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        putBoolean(me.proton.sync.SyncConstants.EXTRA_FORCE_REMOTE_TO_LOCAL, true)
                    }
                    if (section == "contacts" || section == "all") {
                        ContentResolver.requestSync(abAccount, SyncConstants.AUTHORITY_CONTACTS, bundle)
                    }
                    if (section == "calendar" || section == "all") {
                        ContentResolver.requestSync(account, SyncConstants.AUTHORITY_CALENDAR, bundle)
                    }
                    val label = when (section) {
                        "contacts" -> getString(R.string.label_contacts_lower)
                        "calendar" -> getString(R.string.label_calendar_lower)
                        else -> getString(R.string.label_all_data)
                    }
                    Toast.makeText(this@SyncSettingsActivity, getString(R.string.toast_redownloading, label), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                SyncLog.e("UserAction", "Force Remote→Local local-wipe/sync-request FAILED for '${account.name}' (section=$section): ${e.message} - mappings/events may persist and the next sync could unchanged-skip and re-download nothing", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SyncSettingsActivity, getString(R.string.toast_resync_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { markResyncEnd(account.name) }
            }
        }
    }

    private fun performFullResyncLocalToRemote(account: Account, abAccount: Account, requested: String = "all") {
        val section = sectionsStillEnabled(account, abAccount, requested) ?: run {
            Toast.makeText(
                this, getString(R.string.toast_resync_skipped, skippedSyncTypes), Toast.LENGTH_LONG,
            ).show()
            return
        }
        SyncLog.i("UserAction", "Force Local→Remote resync requested for '${account.name}' (section=$section)")
        markResyncStart(account.name)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (section == "contacts" || section == "all") {
                    // Keep sync mappings so dirty contacts match existing remote contacts
                    // (update instead of create — deleting mappings would cause duplicates)

                    // Mark all local contacts as dirty so they get uploaded
                    val values = android.content.ContentValues().apply {
                        put(android.provider.ContactsContract.RawContacts.DIRTY, 1)
                    }
                    contentResolver.update(
                        android.provider.ContactsContract.RawContacts.CONTENT_URI,
                        values,
                        // Live rows only. Flagging a dead row that nothing had processed turned it
                        // into a deletion on Proton, of a contact nobody deleted this time.
                        "${android.provider.ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                            "${android.provider.ContactsContract.RawContacts.DELETED} = 0",
                        arrayOf(abAccount.name, abAccount.type)
                    )

                    // Trigger contact sync (will upload all dirty contacts)
                    val contactBundle = Bundle().apply {
                        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        putBoolean(me.proton.sync.SyncConstants.EXTRA_FORCE_LOCAL_TO_REMOTE, true)
                    }
                    ContentResolver.requestSync(abAccount, SyncConstants.AUTHORITY_CONTACTS, contactBundle)
                }

                if (section == "calendar" || section == "all") {
                    // Delete calendar sync mappings so all events appear as "new" dirty events
                    syncDatabase.calendarSyncDao().deleteAllByAccount(account.name)
                    // Every row is about to be uploaded. Make that sync check their stored times first: the
                    // re-time pass only tags a row whose times differ (it writes nothing to the phone's
                    // calendar), the upload sends Proton's own text for every time nobody edited (UploadTimes),
                    // and the download re-applies the tagged row from Proton once its upload has gone.
                    PreferenceManager.getDefaultSharedPreferences(this@SyncSettingsActivity).edit()
                        .remove(SyncPreferences.timeZoneRetimeKey(account.name)).apply()

                    // Mark all local calendar events as dirty so they get uploaded
                    val calIds = mutableListOf<Long>()
                    contentResolver.query(
                        android.provider.CalendarContract.Calendars.CONTENT_URI,
                        arrayOf(android.provider.CalendarContract.Calendars._ID),
                        "${android.provider.CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${android.provider.CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                        arrayOf(account.name, account.type),
                        null
                    )?.use { cursor ->
                        while (cursor.moveToNext()) calIds.add(cursor.getLong(0))
                    }

                    if (calIds.isNotEmpty()) {
                        val placeholders = calIds.joinToString(",") { "?" }
                        val values = android.content.ContentValues().apply {
                            put(android.provider.CalendarContract.Events.DIRTY, 1)
                        }
                        val syncUri = android.provider.CalendarContract.Events.CONTENT_URI.buildUpon()
                            .appendQueryParameter(android.provider.CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                            .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                            .appendQueryParameter(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
                            .build()
                        contentResolver.update(
                            syncUri,
                            values,
                            "${android.provider.CalendarContract.Events.CALENDAR_ID} IN ($placeholders)",
                            calIds.map { it.toString() }.toTypedArray()
                        )
                    }

                    // Trigger calendar sync (will upload all dirty events)
                    val calBundle = Bundle().apply {
                        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        putBoolean(me.proton.sync.SyncConstants.EXTRA_FORCE_LOCAL_TO_REMOTE, true)
                    }
                    ContentResolver.requestSync(account, android.provider.CalendarContract.AUTHORITY, calBundle)
                }

                val label = when (section) {
                    "contacts" -> getString(R.string.label_contacts_lower)
                    "calendar" -> getString(R.string.label_calendar_lower)
                    else -> getString(R.string.label_all_data)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SyncSettingsActivity, getString(R.string.toast_uploading, label), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SyncSettingsActivity, getString(R.string.toast_resync_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { markResyncEnd(account.name) }
            }
        }
    }

    /**
     * Bind a clickable row in an account card to a list-of-values preference.
     *
     * Replaces the boilerplate "read pref → format label → setOnClickListener {
     * re-read → showListDialog → on-select update label }" pattern that buildAccountCards
     * repeats for the past-events, future-events, and event-loop-interval rows.
     *
     * Live re-read inside the click handler matters because the user may have changed
     * the value via another path (e.g. a different card on the same activity) before
     * tapping again — without the re-read the dialog would pre-select a stale value.
     *
     * @param formatLabel Maps a stored pref value to its human-readable label (uses
     *   [formatDaysLabel] / [formatIntervalLabel] as appropriate).
     * @param onChanged Optional side-effect after a new value is persisted (called
     *   with the freshly-read value from prefs to keep semantics consistent with
     *   the inline code that re-reads after `showListDialog` saves).
     */
    private fun bindDropdownPref(
        labelView: android.widget.TextView,
        clickableRow: android.view.View,
        dialogTitle: String,
        entries: Array<String>,
        values: Array<String>,
        prefKey: String,
        defaultValue: String,
        prefs: android.content.SharedPreferences,
        formatLabel: (String) -> String,
        onChanged: (newValue: String) -> Unit = {}
    ) {
        labelView.text = formatLabel(SyncPreferences.getStringOrDefault(prefs, prefKey, defaultValue))
        clickableRow.setOnClickListener {
            val live = SyncPreferences.getStringOrDefault(prefs, prefKey, defaultValue)
            showListDialog(dialogTitle, entries, values, prefKey, prefs, live) { label ->
                labelView.text = label
                onChanged(SyncPreferences.getStringOrDefault(prefs, prefKey, defaultValue))
            }
        }
    }

    // --- Value formatting ---

    private fun formatDaysLabel(value: String, entries: Array<String>, values: Array<String>): String {
        val idx = values.indexOf(value)
        if (idx >= 0 && values[idx] != "custom") return entries[idx]
        val days = value.toLongOrNull() ?: return "6 months"
        return if (days == 0L) getString(R.string.label_all) else getString(R.string.label_days_format, days.toInt())
    }

    private fun formatIntervalLabel(value: String, entries: Array<String>, values: Array<String>): String {
        val idx = values.indexOf(value)
        if (idx >= 0 && values[idx] != "custom") return entries[idx]
        val seconds = value.toLongOrNull() ?: return "1 hour"
        if (seconds < 0) return getString(R.string.label_manual_only)
        val minutes = seconds / 60
        return when {
            minutes == 1L -> "1 minute"
            minutes < 60 -> "$minutes minutes"
            minutes == 60L -> "1 hour"
            minutes < 1440 -> "${minutes / 60} hours"
            minutes == 1440L -> "1 day"
            else -> "${minutes / 1440} days"
        }
    }

    // --- Battery optimization ---

    /** Open an external URL in the user's browser; no-op if no browser is available. */
    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) { /* no browser */ }
    }

    /** The footer's "Third-party licences" link, and the licences debug command. */
    private fun openLicences() {
        startActivity(Intent(this, LicencesActivity::class.java))
    }

    /**
     * Compose a report e-mail with the debug log attached.
     *
     * Delegates to [me.proton.sync.DebugLog], so this produces byte-for-byte what the log
     * viewer produces. It used to run its own `logcat` dump through a second, separately
     * maintained tag allowlist that had drifted from the viewer copy, so the same fault
     * reported from the same phone yielded different evidence depending on which screen the
     * user happened to be on. There is also no longer an "empty log" branch to handle: the
     * log no longer comes from logcat, so a ROM that restricts logcat cannot empty it.
     */
    private fun sendReport() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "?" }
        val body = getString(
            R.string.send_report_body_template,
            versionName,
            if (SyncLog.isVerboseEnabled()) "on" else "off",
            android.os.Build.MANUFACTURER,
            android.os.Build.MODEL,
            android.os.Build.VERSION.RELEASE
        )
        // Off the main thread: building the artefact shells out to logcat for the supplementary
        // section, and the mail intent writes it to a file in the app's cache.
        Thread {
            try {
                val intent = me.proton.sync.DebugLog.mailIntent(
                    this,
                    REPORT_EMAIL,
                    getString(R.string.send_report_subject, versionName),
                    body
                )
                runOnUiThread {
                    startActivity(
                        Intent.createChooser(intent, getString(R.string.send_report_chooser_title))
                    )
                }
            } catch (e: Exception) {
                SyncLog.w("SyncSettings", "Failed to compose report: ${e.message}")
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_send_report_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * DEBUG-ONLY. Reproduce the "events vanished without a tombstone" glitch the
     * bulk-deletion guard protects against ([me.proton.sync.calendar.BulkDeletionGuard]).
     * Hard-deletes a chosen calendar's local event rows AS A SYNC ADAPTER (no tombstones)
     * while keeping the Room mappings, then triggers a sync — so reconcileDeletedEvents sees
     * mappings with no local rows and the guard should HOLD + post the confirm notification.
     * Proton is never touched.
     */
    private fun showSimulateVanishedDialog() {
        data class CalRow(val id: Long, val name: String, val account: String)
        val cals = mutableListOf<CalRow>()
        try {
            contentResolver.query(
                android.provider.CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    android.provider.CalendarContract.Calendars._ID,
                    android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    android.provider.CalendarContract.Calendars.ACCOUNT_NAME
                ),
                "${android.provider.CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(ProtonSyncApp.ACCOUNT_TYPE),
                null
            )?.use { c ->
                while (c.moveToNext()) cals.add(CalRow(c.getLong(0), c.getString(1) ?: "?", c.getString(2) ?: "?"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read calendars: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (cals.isEmpty()) {
            Toast.makeText(this, "No Proton calendars on this device", Toast.LENGTH_LONG).show()
            return
        }
        val labels = cals.map { "${it.name}  (${it.account})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Simulate vanished events")
            .setItems(labels) { _, i ->
                val cal = cals[i]
                AlertDialog.Builder(this)
                    .setTitle("Simulate vanished events")
                    .setMessage(
                        "Hard-delete ALL local events of '${cal.name}' WITHOUT tombstones (mimics a " +
                        "Calendar-Storage glitch), keeping the sync mappings, then sync.\n\n" +
                        "The bulk-deletion guard should HOLD the deletion and show the confirm " +
                        "notification - Proton is NOT touched.\n\nProceed?"
                    )
                    .setPositiveButton("Simulate") { _, _ -> runSimulateVanished(cal.id, cal.name, cal.account) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runSimulateVanished(calId: Long, name: String, accountName: String) {
        val account = Account(accountName, ProtonSyncApp.ACCOUNT_TYPE)
        lifecycleScope.launch(Dispatchers.IO) {
            var deleted = 0
            try {
                deleted = contentResolver.delete(
                    SyncUtils.asCallerSyncAdapter(android.provider.CalendarContract.Events.CONTENT_URI, account),
                    "${android.provider.CalendarContract.Events.CALENDAR_ID} = ?",
                    arrayOf(calId.toString())
                )
            } catch (e: Exception) {
                SyncLog.e("SimVanished", "hard-delete failed", e)
            }
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this@SyncSettingsActivity,
                        "Vanished $deleted local events from '$name' (mappings kept) - syncing…",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            val bundle = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                putBoolean(SyncConstants.EXTRA_USER_SYNC_NOW, true)
            }
            ContentResolver.requestSync(account, SyncConstants.AUTHORITY_CALENDAR, bundle)
        }
    }

    /**
     * DEBUG-ONLY. Empirical probe for Proton's contact-photo size limit.
     *
     * Picks the parent (calendar) account, runs [PhotoSizeCalibrator.calibrate]
     * on a background thread, and shows the summary in a copy-friendly dialog
     * once it's done. Full step-by-step output goes to the sync log.
     */
    private fun showCalibratePhotoSizeDialog() {
        val am = accountManager
        val accounts = am.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
        if (accounts.isEmpty()) {
            Toast.makeText(this, "Sign in to a Proton account first.", Toast.LENGTH_SHORT).show()
            return
        }
        // Multi-account: pick the first; user can re-run for others.
        val account = accounts.first()

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_calibrate_photo_title)
            .setMessage(getString(R.string.dialog_calibrate_photo_message))
            .setPositiveButton(android.R.string.ok) { _, _ -> runPhotoSizeCalibration(account.name) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runPhotoSizeCalibration(accountName: String) {
        Toast.makeText(this, R.string.dialog_calibrate_photo_running, Toast.LENGTH_LONG).show()
        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                photoSizeCalibrator.calibrate(accountName)
            } catch (e: Exception) {
                SyncLog.e("SyncSettings", "Photo size calibration crashed: ${e.message}", e)
                null
            }
            withContext(Dispatchers.Main) {
                val message = result?.let {
                    buildString {
                        appendLine(it.summaryLine())
                        appendLine()
                        appendLine("Per-step results:")
                        for (step in it.steps) {
                            append(if (step.accepted) "  OK  " else "  X   ")
                            append("${step.step.dimensionPx}x${step.step.dimensionPx} ")
                            append("q=${step.step.jpegQuality}  ")
                            append("raw=${step.rawJpegBytes / 1024}KB  ")
                            append("enc=${step.encryptedWireBytes / 1024}KB  ")
                            append("HTTP ${step.httpCode}")
                            if (step.accepted) append(if (step.roundTripVerified) "  ✓rt" else "  ?rt")
                            appendLine()
                        }
                    }
                } ?: "Calibration failed - see Sync Log."

                AlertDialog.Builder(this@SyncSettingsActivity)
                    .setTitle(R.string.dialog_calibrate_photo_done_title)
                    .setMessage(message)
                    .setPositiveButton("Copy") { _, _ ->
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("calibration", message))
                        Toast.makeText(this@SyncSettingsActivity, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /**
     * Wire the global "Sync all accounts" button. Tap = manual sync of contacts +
     * calendar across every Proton account. Long-press = pick a force-resync direction
     * (Remote→Local / Local→Remote) and apply to every account.
     *
     * Hidden when there's <= 1 account because the per-account card's own
     * button is functionally identical and rendering both is redundant.
     */
    private fun setupSyncAllAccountsButton() {
        binding.btnSyncAllAccounts.setOnClickListener {
            if (!subscriptionManager.isSyncAllowed()) {
                Toast.makeText(this, getString(R.string.sync_disabled_no_subscription), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val accounts = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
            if (accounts.isEmpty()) return@setOnClickListener
            SyncLog.i("UserAction", "Manual sync all accounts requested (${accounts.size} accounts)")
            var syncing = 0
            for (account in accounts) {
                val abAccount = Account(SyncUtils.addressBookAccountName(accountManager, account), ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
                // Only what each account still has switched on. This is the button actually hit
                // in the field: calendar sync off, the events removed on request, then
                // one tap here and every event came back. The per-account button was guarded and
                // this one, which is larger and far more prominent, was not. Both now go through
                // the same helper, so that guard cannot be present in one and missing in the other.
                val started = requestManualSync(account, abAccount, "all")
                if (started.contacts || started.calendar) syncing++
            }
            // The count of accounts that actually started one, not of accounts that exist. With every
            // type switched off everywhere this used to report syncing three accounts and sync none.
            Toast.makeText(
                this,
                if (syncing == 0) getString(R.string.toast_sync_nothing_enabled)
                else resources.getQuantityString(R.plurals.toast_sync_all_started, syncing, syncing),
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.btnSyncAllAccounts.setOnLongClickListener {
            showForceResyncAllDialog()
            true
        }
    }

    /**
     * Force-resync dialog for the "Sync all accounts" long-press. Mirrors the per-account
     * dialog ([showFullResyncDialog]) but applies the chosen direction to every account.
     */
    private fun showForceResyncAllDialog() {
        if (!subscriptionManager.isSyncAllowed()) {
            Toast.makeText(this, getString(R.string.sync_disabled_no_subscription), Toast.LENGTH_SHORT).show()
            return
        }
        if (fullResyncInFlightAccounts.isNotEmpty()) {
            Toast.makeText(this, getString(R.string.toast_resync_in_flight), Toast.LENGTH_SHORT).show()
            return
        }
        val accounts = accountManager.getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
        if (accounts.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_full_resync_all, accounts.size))
            .setMessage(getString(R.string.dialog_message_full_resync_all))
            .setPositiveButton(getString(R.string.btn_remote_to_local)) { _, _ ->
                Toast.makeText(this, getString(R.string.toast_preparing_resync), Toast.LENGTH_SHORT).show()
                for (account in accounts) {
                    val abAccount = Account(SyncUtils.addressBookAccountName(accountManager, account), ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
                    performFullResyncRemoteToLocal(account, abAccount, "all")
                }
            }
            .setNegativeButton(getString(R.string.btn_local_to_remote)) { _, _ ->
                Toast.makeText(this, getString(R.string.toast_preparing_resync), Toast.LENGTH_SHORT).show()
                for (account in accounts) {
                    val abAccount = Account(SyncUtils.addressBookAccountName(accountManager, account), ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK)
                    performFullResyncLocalToRemote(account, abAccount, "all")
                }
            }
            .setNeutralButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * Held as a field so [refreshVerboseLoggingUi] can detach it before setting the checked
     * state programmatically, the same pattern the file-logging switch uses. Without that,
     * re-reading the preference would re-fire the listener and log a user action nobody took.
     */
    private val verboseLoggingCheckedListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean(SyncPreferences.KEY_VERBOSE_LOGGING, checked).apply()
            SyncLog.setVerboseEnabled(checked)
            SyncLog.i("SyncSettings", "Verbose logging ${if (checked) "enabled" else "disabled"} by user")
        }

    private fun setupVerboseLoggingToggle() {
        val current = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
            SyncPreferences.KEY_VERBOSE_LOGGING,
            SyncPreferences.DEFAULT_VERBOSE_LOGGING
        )
        binding.switchVerboseLogging.isChecked = current
        SyncLog.setVerboseEnabled(current)
        binding.switchVerboseLogging.setOnCheckedChangeListener(verboseLoggingCheckedListener)
    }

    /**
     * Re-read the verbose preference into the switch.
     *
     * The log viewer carries the same toggle, writing the same preference, so changing it there
     * left this screen showing a stale position until the activity was recreated. Two controls
     * for one setting have to agree, or the one that lies is worse than not having it.
     */
    private fun refreshVerboseLoggingUi() {
        val current = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
            SyncPreferences.KEY_VERBOSE_LOGGING,
            SyncPreferences.DEFAULT_VERBOSE_LOGGING
        )
        if (binding.switchVerboseLogging.isChecked == current) return
        binding.switchVerboseLogging.setOnCheckedChangeListener(null)
        binding.switchVerboseLogging.isChecked = current
        binding.switchVerboseLogging.setOnCheckedChangeListener(verboseLoggingCheckedListener)
    }

    /**
     * "Update time zones online" (device-wide, default off). One path for the switch, the notice's "Turn on",
     * the debug verb and an import, so they cannot drift: the preference, which the next sync reads as its rule
     * source (TimeZoneRuleSource.snapshot), then a calendar sync request for every account, whose re-time pass
     * corrects the events already on the phone. Nothing is fetched here; ical4j's own updater stays off for good.
     */
    private fun setTimeZoneUpdates(enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putBoolean(SyncPreferences.KEY_TIMEZONE_UPDATES, enabled).apply()
        TimeZoneRuleSource.requestRetimeSyncs(applicationContext, "online time zone updates turned ${if (enabled) "on" else "off"}")
    }

    /**
     * A field so [refreshTimeZoneUpdatesUi] can detach it and not log a flip nobody made. A flip by hand is the
     * user's answer to the outdated-time-zone-data notice as well: it is never recommended to someone who
     * chose for themselves.
     */
    private val timeZoneUpdatesCheckedListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            setTimeZoneUpdates(checked)
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(SyncPreferences.KEY_TZ_OUTDATED_NOTICE_ANSWER, if (checked) "on" else "off").apply()
            refreshTimeZoneDataHint()
            SyncLog.i("UserAction", "Online time zone updates ${if (checked) "enabled" else "disabled"} by user")
        }

    /** Show the stored choice (onCreate, onResume, after an import or the debug verb), listener detached. */
    private fun refreshTimeZoneUpdatesUi() {
        val sw = binding.switchTimezoneUpdates
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = TimeZoneRuleSource.isOnlineEnabled(PreferenceManager.getDefaultSharedPreferences(this))
        sw.setOnCheckedChangeListener(timeZoneUpdatesCheckedListener)
        refreshTimeZoneDataHint()
    }

    /**
     * The hint line under the switch: visible exactly while this phone's tz data looks stuck
     * (TzDataFreshness) and the switch is off, whatever the notice's answer was.
     */
    private fun refreshTimeZoneDataHint() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val version = TimeZoneRuleSource.displayedTzdataVersion(prefs)
        val show = !TimeZoneRuleSource.isOnlineEnabled(prefs) &&
            TzDataFreshness.isOutdated(version, java.time.Year.now().value)
        binding.textTimezoneDataOutdated.text = if (show) getString(R.string.sfp_timezone_data_outdated, version) else ""
        binding.textTimezoneDataOutdated.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** The (i) of the switch: like [bindInfo], with this phone's tz data version in the text. */
    private fun bindTimeZoneUpdatesInfo() {
        binding.infoTimezoneUpdates.setOnClickListener {
            val version = TimeZoneRuleSource.displayedTzdataVersion(PreferenceManager.getDefaultSharedPreferences(this))
                ?: getString(R.string.tz_data_version_unknown)
            AlertDialog.Builder(this)
                .setTitle(R.string.label_timezone_updates_title)
                .setMessage(getString(R.string.info_timezone_updates_text, version))
                .setPositiveButton(R.string.btn_info_dismiss, null)
                .show()
        }
    }

    /**
     * The one-time notice that this phone's tz data looks stuck, recommending "Update time zones online" and
     * saying plainly what that sends to whom. Shown on opening this screen when all of these hold: not a
     * re-created screen and not a debug command; the switch is off; the data looks outdated (TzDataFreshness);
     * no answer is stored; at least one account syncs its calendar; and it was not shown in the last week.
     *
     * Only an answer ends it: "Turn on" and "Keep off", or flipping the switch by hand. Back and a tap outside
     * do nothing, and a screen re-created while it is up (rotation, a dark-mode switch) shows it again at once
     * ([resumeTzNotice]). Leaving the app with it unanswered brings it back after a week.
     * [force] (debug "tz-warning show") skips the conditions.
     *
     * @return true when the dialog went up.
     */
    private fun maybeShowTzDataNotice(force: Boolean = false): Boolean {
        if (isFinishing || isDestroyed) return false
        if (tzNoticeDialog?.isShowing == true) return true
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val version = TimeZoneRuleSource.displayedTzdataVersion(prefs)
        val resume = resumeTzNotice
        resumeTzNotice = false
        if (!force) {
            if (!resume && (restoredInstance || openedByDebugCommand)) return false
            if (TimeZoneRuleSource.isOnlineEnabled(prefs)) return false
            if (prefs.contains(SyncPreferences.KEY_TZ_OUTDATED_NOTICE_ANSWER)) return false
            if (!TzDataFreshness.isOutdated(version, java.time.Year.now().value)) return false
            if (!resume) {
                val lastShown = prefs.getLong(SyncPreferences.KEY_TZ_OUTDATED_NOTICE_LAST_SHOWN_MS, 0L)
                val intervalMs = SyncPreferences.TZ_OUTDATED_NOTICE_INTERVAL_DAYS * 24L * 60L * 60L * 1000L
                if (lastShown > 0L && System.currentTimeMillis() - lastShown < intervalMs) return false
                if (!anyAccountSyncsCalendar()) return false
            }
        }
        tzNoticeDialog = AlertDialog.Builder(this)
            .setCancelable(false)
            .setTitle(R.string.tz_outdated_title)
            .setMessage(
                getString(
                    R.string.tz_outdated_body,
                    version ?: getString(R.string.tz_data_version_unknown),
                    getString(R.string.label_timezone_updates_title)
                )
            )
            .setPositiveButton(R.string.tz_outdated_turn_on) { _, _ ->
                prefs.edit().putString(SyncPreferences.KEY_TZ_OUTDATED_NOTICE_ANSWER, "on").apply()
                setTimeZoneUpdates(true)
                refreshTimeZoneUpdatesUi()
                SyncLog.i("UserAction", "Outdated time zone data notice: turned online time zone updates on")
            }
            .setNegativeButton(R.string.tz_outdated_keep_off) { _, _ ->
                prefs.edit().putString(SyncPreferences.KEY_TZ_OUTDATED_NOTICE_ANSWER, "off").apply()
                SyncLog.i("UserAction", "Outdated time zone data notice: kept online time zone updates off")
            }
            .setOnDismissListener { tzNoticeDialog = null }
            .show()
        if (!resume) {
            prefs.edit().putLong(SyncPreferences.KEY_TZ_OUTDATED_NOTICE_LAST_SHOWN_MS, System.currentTimeMillis()).apply()
        }
        SyncLog.i("SyncSettings", "${if (resume) "Showed again" else "Showed"} the outdated time zone data notice (tz data ${version ?: "unknown"})")
        return true
    }

    /** Whether any Proton account on this phone syncs its calendar: the notice is about calendar events. */
    private fun anyAccountSyncsCalendar(): Boolean = try {
        AccountManager.get(this).getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE).any {
            ContentResolver.getSyncAutomatically(it, SyncConstants.AUTHORITY_CALENDAR)
        }
    } catch (_: Exception) {
        false
    }

    // Reused for the "Save logs to file" switch. Held as a field so [refreshFileLoggingUi]
    // can detach it before programmatically setting the checked state (the picker / cancel
    // paths flip the switch themselves and must not re-fire this).
    private val fileLoggingCheckedListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            if (checked) {
                if (FileLogController.hasValidFolder(this)) {
                    // Folder already chosen and grant still held — just resume into it.
                    val uriStr = prefs.getString(SyncPreferences.KEY_LOG_FOLDER_URI, null)!!
                    prefs.edit().putBoolean(SyncPreferences.KEY_FILE_LOGGING, true).apply()
                    FileLogController.enable(this, android.net.Uri.parse(uriStr))
                    SyncLog.i("SyncSettings", "File logging enabled by user")
                    refreshFileLoggingUi()
                } else {
                    launchLogFolderPicker()
                }
            } else {
                prefs.edit().putBoolean(SyncPreferences.KEY_FILE_LOGGING, false).apply()
                FileLogController.disable()
                SyncLog.i("SyncSettings", "File logging disabled by user")
                refreshFileLoggingUi()
            }
        }

    private fun setupFileLoggingToggle() {
        binding.btnChooseLogFolder.setOnClickListener { launchLogFolderPicker() }
        refreshFileLoggingUi()
    }

    /** Open the system folder picker; the result callback persists the grant and starts logging. */
    private fun launchLogFolderPicker() {
        try {
            pickLogFolderLauncher.launch(null)
        } catch (e: Exception) {
            // No document UI available (rare). Don't leave the switch in a lying state.
            SyncLog.e("SyncSettings", "Could not open folder picker for log destination", e)
            refreshFileLoggingUi()
        }
    }

    /** Sync the switch, the folder row visibility, and the path label to the live sink state. */
    private fun refreshFileLoggingUi() {
        val active = FileLogController.isActive()
        val sw = binding.switchFileLogging
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = active
        sw.setOnCheckedChangeListener(fileLoggingCheckedListener)
        binding.rowLogFolder.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            binding.textLogFolder.text =
                getString(R.string.label_log_folder_path, FileLogController.folderLabel(this) ?: "")
        }
    }

    private fun setupLanguageSelector() {
        // Supported languages sorted alphabetically by native name.
        // "Auto (system default)" is always first.
        val sortedLanguages = listOf(
            "bg" to "\u0411\u044a\u043b\u0433\u0430\u0440\u0441\u043a\u0438",  // Bulgarian
            "cs" to "\u010Ce\u0161tina",       // Czech
            "da" to "Dansk",                    // Danish
            "de" to "Deutsch",                  // German
            "et" to "Eesti",                    // Estonian
            "en" to "English",                  // English
            "es" to "Espa\u00f1ol",             // Spanish
            "fr" to "Fran\u00e7ais",            // French
            "ga" to "Gaeilge",                  // Irish
            "hr" to "Hrvatski",                 // Croatian
            "it" to "Italiano",                 // Italian
            "lv" to "Latvie\u0161u",            // Latvian
            "lt" to "Lietuvi\u0173",            // Lithuanian
            "hu" to "Magyar",                   // Hungarian
            "mt" to "Malti",                    // Maltese
            "nl" to "Nederlands",               // Dutch
            "nb" to "Norsk bokm\u00e5l",        // Norwegian Bokmål
            "pl" to "Polski",                   // Polish
            "pt" to "Portugu\u00eas",           // Portuguese
            "ro" to "Rom\u00e2n\u0103",         // Romanian
            "sk" to "Sloven\u010dina",          // Slovak
            "sl" to "Sloven\u0161\u010dina",    // Slovenian
            "fi" to "Suomi",                    // Finnish
            "sv" to "Svenska",                  // Swedish
            "el" to "\u0395\u03bb\u03bb\u03b7\u03bd\u03b9\u03ba\u03ac"  // Greek
        ).sortedBy { it.second.lowercase() }

        val languageTags = arrayOf("") + sortedLanguages.map { it.first }.toTypedArray()
        val languageNames = arrayOf(getString(R.string.label_language_auto)) +
            sortedLanguages.map { it.second }.toTypedArray()

        // Display current language
        val currentLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val currentTag = if (currentLocales.isEmpty) "" else currentLocales.get(0)?.toLanguageTag() ?: ""
        val currentIdx = languageTags.indexOf(currentTag).coerceAtLeast(0)
        binding.valueLanguage.text = languageNames[currentIdx]

        binding.rowLanguage.setOnClickListener {
            val checkedIdx = languageTags.indexOf(
                if (androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().isEmpty) ""
                else androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() ?: ""
            ).coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.label_language))
                .setSingleChoiceItems(languageNames, checkedIdx) { dialog, which ->
                    val tag = languageTags[which]
                    val localeList = if (tag.isEmpty()) {
                        androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    } else {
                        androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                    }
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(localeList)
                    // Also written to our own preferences, because AppCompat keeps the chosen
                    // locale in framework state that the settings backup cannot see. Without this
                    // a restore brought back every preference on this screen except the language.
                    PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().putString(SyncPreferences.KEY_APP_LANGUAGE_TAG, tag).apply()
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    /** @return true when the dialog went up. */
    private fun requestBatteryOptimizationIfNeeded(): Boolean {
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                // Show a dialog explaining the issue and offer to open settings
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_title_battery))
                    .setMessage(getString(R.string.dialog_message_battery))
                    .setPositiveButton(getString(R.string.btn_open_settings)) { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton(getString(R.string.btn_later), null)
                    // Then the next start-up dialog, one at a time (showNextStartupNotice checks the screen is alive).
                    .setOnDismissListener { binding.root.post { showNextStartupNotice() } }
                    .show()
                return true
            }
        } catch (e: Exception) {
            SyncLog.w("SyncSettings", "Could not request battery exemption: ${e.message}")
        }
        return false
    }

    // --- Expand/collapse animation ---

    private fun expandView(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec((view.parent as View).width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetHeight = view.measuredHeight
        view.layoutParams.height = 0
        view.visibility = View.VISIBLE

        val animator = ValueAnimator.ofInt(0, targetHeight)
        animator.addUpdateListener { animation ->
            view.layoutParams.height = animation.animatedValue as Int
            view.requestLayout()
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        })
        animator.duration = 250
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }

    private fun collapseView(view: View) {
        val initialHeight = view.measuredHeight
        val animator = ValueAnimator.ofInt(initialHeight, 0)
        animator.addUpdateListener { animation ->
            view.layoutParams.height = animation.animatedValue as Int
            view.requestLayout()
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                view.visibility = View.GONE
                view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        })
        animator.duration = 200
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }
}
