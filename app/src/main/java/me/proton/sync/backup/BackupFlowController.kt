package me.proton.sync.backup

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.sync.R
import me.proton.sync.SyncLog
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.billing.SubscriptionManager
import me.proton.sync.db.SyncDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Orchestrates the SAF + dialog flow for `.sppbackup` export and import.
 *
 * This class was extracted from [me.proton.sync.settings.SyncSettingsActivity] in v1.5.2
 * to keep the Activity focused on its own widget-binding job. The actual data work lives
 * in [ExportManager] and [ImportManager]; this class just chains the user-visible
 * choices (include-accounts checkbox, passphrase prompts, preflight summary, collision
 * handling, completion message).
 *
 * **Lifecycle ownership.** The two [ActivityResultLauncher] instances must still live on
 * the Activity (Android requires `registerForActivityResult` to be called before STARTED,
 * which property initialisers can satisfy but a non-Activity class cannot). The Activity
 * therefore owns the launchers and delegates their callbacks here:
 *
 * ```kotlin
 * private lateinit var backupController: BackupFlowController
 * private val exportBackupLauncher = registerForActivityResult(
 *     ActivityResultContracts.CreateDocument(BackupFormat.MIME)
 * ) { uri -> if (uri != null) backupController.onExportUriChosen(uri) }
 * private val importBackupLauncher = registerForActivityResult(
 *     ActivityResultContracts.OpenDocument()
 * ) { uri -> if (uri != null) backupController.startImport(uri) }
 * ```
 *
 * **State.** The single piece of flow state ([pendingExportIncludeAccounts]) is exposed
 * via [saveState] / [restoreState] so the Activity can forward `onSaveInstanceState` —
 * this preserves the user's "include accounts" choice if the device rotates while the
 * SAF create-document picker is foreground.
 *
 * **Lifecycle guards.** Every IO-then-Main hop checks `activity.isFinishing ||
 * activity.isDestroyed` before touching dialogs, so a navigate-away mid-flow does not
 * crash on a dead window token.
 *
 * @param activity Used as Context for dialogs, string lookup, lifecycleScope, and
 *   isFinishing/isDestroyed checks.
 * @param sessionManager Forwarded to [ExportManager] and [ImportManager].
 * @param syncDatabase Forwarded to [ImportManager] (Room database for sync-state rows).
 * @param subscriptionManager Re-`connect`-ed after a successful import because new
 *   accounts can shift the device's subscription gate (e.g. a Paddle-tied account being
 *   restored on a direct-flavor install needs a fresh billing probe).
 * @param onImportComplete Called on Main after [applyImport] completes so the Activity
 *   can rebuild its account-card list. Wired by the Activity as `{ buildAccountCards() }`.
 */
class BackupFlowController(
    private val activity: AppCompatActivity,
    private val sessionManager: AccountSessionManager,
    private val keyManager: me.proton.sync.crypto.KeyManager,
    private val syncDatabase: SyncDatabase,
    private val subscriptionManager: SubscriptionManager,
    private val onImportComplete: () -> Unit,
) {

    private companion object {
        private const val TAG = "BackupFlow"
    }

    // ---- Flow state (forwarded across recreation by the Activity) ----

    /**
     * Whether the in-flight export should include accounts/credentials. Set when the
     * user confirms the options dialog, read after the SAF picker returns. Persisted
     * across recreation (it's a non-secret boolean) so a rotation during the picker
     * can't silently downgrade a full backup to settings-only. The passphrase is
     * deliberately NOT stored here — it's collected only after the destination URI is
     * chosen (see [onExportUriChosen] → [promptExportPassphrase]).
     */
    var pendingExportIncludeAccounts: Boolean = false
        private set

    /** Persist [pendingExportIncludeAccounts] into [outState] under [key]. Called by the Activity's onSaveInstanceState. */
    fun saveState(outState: Bundle, key: String) {
        outState.putBoolean(key, pendingExportIncludeAccounts)
    }

    /** Restore [pendingExportIncludeAccounts] from [inState] under [key], if present. Called by the Activity's onCreate. */
    fun restoreState(inState: Bundle, key: String) {
        pendingExportIncludeAccounts = inState.getBoolean(key, false)
    }

    // ---- Pure helpers ----

    /** Current UTC time as an ISO-8601 instant for the envelope's `created_at`. */
    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    /** Suggested SAF file name, e.g. `sync-for-proton-backup-2026-05-29.sppbackup`. */
    private fun suggestedBackupFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "sync-for-proton-backup-$date.${BackupFormat.EXTENSION}"
    }

    // ===================== Export =====================

    /**
     * Step 1 (export): the options dialog — just the include-accounts checkbox + explainer.
     * The passphrase is collected later, AFTER the destination URI is chosen
     * ([onExportUriChosen]), so it never has to survive the SAF round-trip in a field.
     *
     * @param exportLauncher The Activity-owned [ActivityResultLauncher] that the user
     *   reaches by confirming this dialog. Passed in rather than held as a field so the
     *   Activity remains in control of its result-contract registration.
     */
    fun showExportBackupDialog(exportLauncher: ActivityResultLauncher<String>) {
        val d = activity.resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val gap = (12 * d).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
        }
        val checkbox = MaterialCheckBox(activity).apply {
            text = activity.getString(R.string.backup_include_accounts)
            isChecked = false
        }
        val explainer = TextView(activity).apply {
            text = activity.getString(R.string.backup_explainer_settings_only)
            textSize = 13f
            alpha = 0.7f
            setPadding(0, gap, 0, 0)
        }
        // Only the direct version has a subscription id to carry, and only when one was entered.
        val carriesSubscription = subscriptionManager.subscriptionIdForBackup() != null
        checkbox.setOnCheckedChangeListener { _, checked ->
            explainer.text = when {
                !checked -> activity.getString(R.string.backup_explainer_settings_only)
                carriesSubscription -> activity.getString(R.string.backup_explainer_full) + "\n\n" +
                    activity.getString(R.string.backup_explainer_subscription)
                else -> activity.getString(R.string.backup_explainer_full)
            }
        }
        container.addView(checkbox)
        container.addView(explainer)

        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_export_title)
            .setView(container)
            .setPositiveButton(R.string.backup_btn_export) { _, _ ->
                pendingExportIncludeAccounts = checkbox.isChecked
                exportLauncher.launch(suggestedBackupFileName())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Step 2 (export): destination chosen. Collect a passphrase first if accounts are included. */
    fun onExportUriChosen(uri: Uri) {
        if (pendingExportIncludeAccounts) promptExportPassphrase(uri)
        else runExport(uri, null)
    }

    /**
     * A Material password field with a visibility (eye) toggle, so the user can reveal and
     * verify what they type. Mirrors the helper in
     * [me.proton.sync.settings.BackupSettingsActivity]; the plain [android.widget.EditText]
     * dialogs that preceded it had no show/hide control.
     */
    private fun passwordField(hintRes: Int): com.google.android.material.textfield.TextInputLayout {
        val layout = com.google.android.material.textfield.TextInputLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            hint = activity.getString(hintRes)
            isErrorEnabled = true
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = com.google.android.material.textfield.TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(edit)
        return layout
    }

    /** Step 2b (export, full only): prompt for + confirm the passphrase, then export. */
    private fun promptExportPassphrase(uri: Uri) {
        val d = activity.resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
        }
        val passphraseLayout = passwordField(R.string.backup_passphrase_hint)
        val passphraseConfirmLayout = passwordField(R.string.backup_passphrase_confirm_hint)
        val passphrase = passphraseLayout.editText!!
        val passphraseConfirm = passphraseConfirmLayout.editText!!
        container.addView(passphraseLayout)
        container.addView(passphraseConfirmLayout)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.backup_export_title)
            .setView(container)
            .setPositiveButton(R.string.backup_btn_export, null) // overridden below to validate before dismiss
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = passphrase.text?.toString() ?: ""
                val p2 = passphraseConfirm.text?.toString() ?: ""
                passphraseLayout.error = null
                passphraseConfirmLayout.error = null
                if (p1.length < BackupCrypto.MIN_PASSPHRASE_LENGTH) {
                    passphraseLayout.error = activity.getString(R.string.backup_error_passphrase_short)
                    return@setOnClickListener
                }
                if (p1 != p2) {
                    passphraseConfirmLayout.error = activity.getString(R.string.backup_error_passphrase_mismatch)
                    return@setOnClickListener
                }
                dialog.dismiss()
                runExport(uri, p1)
            }
        }
        dialog.show()
    }

    /** Step 3 (export): gather, (encrypt,) write. [passphrase] is a local — never parked in a field. */
    private fun runExport(uri: Uri, passphrase: String?) {
        val include = pendingExportIncludeAccounts
        val createdAt = isoNow()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val result = ExportManager.export(
                activity, uri, sessionManager, include, passphrase, createdAt,
                subscriptionId = subscriptionManager.subscriptionIdForBackup()
            )
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                when (result) {
                    is ExportManager.Result.Success ->
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.backup_export_success),
                            Toast.LENGTH_LONG
                        ).show()
                    is ExportManager.Result.Failure ->
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.backup_export_failed_title)
                            .setMessage(activity.getString(R.string.backup_export_failed_body))
                            .setPositiveButton(R.string.btn_info_dismiss, null)
                            .show()
                }
            }
        }
    }

    // ===================== Import =====================

    /** Step 1 (import): SAF picked a file — read its header to decide what to prompt for. */
    fun startImport(uri: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val outcome = ImportManager.readEnvelope(activity, uri)
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                when (outcome) {
                    is ImportManager.ReadOutcome.Error -> showImportError(outcome.reason)
                    is ImportManager.ReadOutcome.Ok -> {
                        if (outcome.info.encrypted) promptImportPassphrase(outcome.info)
                        else resolveAndPreflight(outcome.info, null)
                    }
                }
            }
        }
    }

    private fun promptImportPassphrase(info: ImportManager.EnvelopeInfo) {
        val d = activity.resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val inputLayout = passwordField(R.string.backup_passphrase_hint)
        val input = inputLayout.editText!!
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
            addView(inputLayout)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_import_passphrase_title)
            .setMessage(R.string.backup_import_passphrase_body)
            .setView(container)
            .setPositiveButton(R.string.backup_btn_unlock) { _, _ ->
                resolveAndPreflight(info, input.text?.toString() ?: "")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Step 2 (import): decrypt (if needed), detect collisions, show the pre-flight summary. */
    private fun resolveAndPreflight(info: ImportManager.EnvelopeInfo, passphrase: String?) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val payload = ImportManager.decryptPayload(info, passphrase)
                val collisions = ImportManager.collidingAccounts(activity, payload)
                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    showPreflightDialog(info, payload, collisions)
                }
            } catch (e: BackupCrypto.WrongPassphraseException) {
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) showImportError("wrong_passphrase")
                }
            } catch (e: Exception) {
                SyncLog.w(TAG, "Import decrypt/parse failed: ${e.javaClass.simpleName}")
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) showImportError("bad_file")
                }
            }
        }
    }

    /**
     * Step 2c (import): preflight confirmation — last chance to back out before any
     * local state is touched. The message says what the file holds: settings only, or how
     * many accounts. A backup from the other flavour (Play versus direct) adds that the
     * subscription is not carried over, because each flavour has its own billing. A backup
     * from the same flavour that carries a subscription id (only the direct one does) adds
     * that the id is used if this phone has none yet.
     */
    private fun showPreflightDialog(
        info: ImportManager.EnvelopeInfo,
        payload: BackupPayload,
        collisions: List<String>
    ) {
        val n = payload.accounts.size
        val sb = StringBuilder()
        if (n == 0) {
            sb.append(activity.getString(R.string.backup_preflight_settings_only))
        } else {
            sb.append(activity.resources.getQuantityString(R.plurals.backup_preflight_accounts, n, n))
        }
        if (info.flavorMismatch && info.sourceFlavor != null) {
            sb.append("\n\n").append(activity.getString(R.string.backup_preflight_flavor_mismatch, info.sourceFlavor))
        } else if (payload.subscriptionId != null) {
            // Same version the backup came from, which carries a subscription id only in the direct one.
            sb.append("\n\n").append(activity.getString(R.string.backup_preflight_subscription))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_import_title)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.backup_btn_import) { _, _ ->
                if (collisions.isEmpty()) applyImport(payload, emptySet())
                else showCollisionDialog(payload, collisions)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Step 3a (import): collision handler. Overwrite passes the whole collision set as
     * the allowlist; Skip passes emptySet so non-colliding accounts still import; Cancel
     * dismisses with no action.
     */
    private fun showCollisionDialog(payload: BackupPayload, collisions: List<String>) {
        val list = collisions.joinToString("\n") { "• $it" }
        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_collision_title)
            .setMessage(activity.getString(R.string.backup_collision_body, list))
            .setPositiveButton(R.string.backup_btn_overwrite) { _, _ ->
                applyImport(payload, collisions.toSet())
            }
            .setNeutralButton(R.string.backup_btn_skip) { _, _ ->
                applyImport(payload, emptySet())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Step 4 (import): commit the restore. Post-import bookkeeping fires the
     * [onImportComplete] callback so the Activity can rebuild its card list, and checks the
     * subscription because new accounts can shift the device's subscription gate: a
     * subscription id from the backup is taken and validated at once when this install has
     * none, otherwise [SubscriptionManager.connect] refreshes the one it has.
     */
    private fun applyImport(payload: BackupPayload, overwriteNames: Set<String>) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val result = ImportManager.apply(
                activity, sessionManager, keyManager, syncDatabase, payload, overwriteNames, restoreSettings = true
            )
            withContext(Dispatchers.Main) {
                // Before the finishing check: the licence is part of the restore, not of the dialog.
                ImportManager.restoreSubscription(subscriptionManager, payload)
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                val accountsTouched = result.imported + result.overwritten + result.skipped + result.failed
                val message = when {
                    // Settings-only backup (no accounts in the file): report what actually happened.
                    accountsTouched == 0 && result.settingsRestored ->
                        activity.getString(R.string.backup_import_done_settings)
                    accountsTouched == 0 ->
                        activity.getString(R.string.backup_import_done_empty)
                    else -> {
                        val base = activity.getString(
                            R.string.backup_import_done_body,
                            result.imported, result.overwritten, result.skipped, result.failed
                        )
                        if (result.settingsRestored)
                            base + "\n\n" + activity.getString(R.string.backup_import_done_settings)
                        else base
                    }
                }
                // Fire onImportComplete on DISMISS, not before the dialog: a callback that
                // navigates + finishes the Activity (the first-run login screen does this)
                // would otherwise leave this dialog to crash on a finishing window. The
                // settings caller (rebuild cards) is unaffected — the dialog is modal, so
                // the rebuild simply happens the moment the user taps Dismiss.
                AlertDialog.Builder(activity)
                    .setTitle(R.string.backup_import_done_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.btn_info_dismiss, null)
                    .setOnDismissListener { onImportComplete() }
                    .show()
            }
        }
    }

    /**
     * Terminal-failure dialog for import. [reason] is the stable string emitted by
     * [ImportManager.readEnvelope] (`"wrong_format"` / `"unsupported_version"`) or by
     * [resolveAndPreflight]'s catch blocks (`"wrong_passphrase"` /
     * default `"bad_file"`).
     */
    private fun showImportError(reason: String) {
        val bodyRes = when (reason) {
            "wrong_format" -> R.string.backup_error_wrong_format
            "unsupported_version" -> R.string.backup_error_unsupported_version
            "wrong_passphrase" -> R.string.backup_error_wrong_passphrase
            else -> R.string.backup_error_bad_file
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_import_failed_title)
            .setMessage(bodyRes)
            .setPositiveButton(R.string.btn_info_dismiss, null)
            .show()
    }
}
