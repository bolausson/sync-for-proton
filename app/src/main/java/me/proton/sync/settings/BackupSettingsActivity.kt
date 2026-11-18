package me.proton.sync.settings

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.sync.R
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.backup.BackupArchiveCrypto
import me.proton.sync.backup.BackupDataExporter
import me.proton.sync.backup.BackupScheduleWorker
import me.proton.sync.backup.SafBackupStorage
import me.proton.sync.crypto.SecureStorage
import me.proton.sync.databinding.ActivityBackupSettingsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings for the local data-backup feature (calendars + contacts → .ics/.vcf, optionally
 * gpg-encrypted, into a SAF-chosen folder). Reads/writes the `KEY_BACKUP_*` prefs and
 * (re)schedules [BackupScheduleWorker]. Manual "Back up now" runs [BackupDataExporter]
 * directly so it works even when scheduled backups are off; the in-app "Decrypt a backup"
 * action reverses an encrypted run for users without desktop `gpg`.
 */
class BackupSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupSettingsBinding
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private val pickFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                SafBackupStorage.persist(this, uri)
                refreshUi()
            }
        }

    private val pickEncryptedBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) promptDecryptPassphrase(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityBackupSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Edge-to-edge (forced on Android 15 / targetSdk 35): pad the root by the system bars so
        // the toolbar sits below the status bar and content doesn't scroll under it or the nav bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Every control here is drawn from the stored settings, on create and on every resume, never from Android's
        // saved view state. Restored after a rotation, a language change or process death, a position saved before the
        // setting changed elsewhere (an import, another copy of this screen) fired its listener and stored the old value
        // again, and a restored number was written back when the screen was left.
        binding.backupContent.isSaveFromParentEnabled = false
        drawFromPrefs(fresh = true)
        savedInstanceState?.let { restoreNumberEdits(it) }
        wireListeners()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        drawFromPrefs(fresh = false)
        refreshUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The two number fields only, each with what it was drawn with, so restoreNumberEdits can tell an edit in
        // progress from an old number.
        outState.putString(KEY_INTERVAL_TEXT, binding.etIntervalDays.text?.toString())
        outState.putString(KEY_INTERVAL_DRAWN, intervalField.drawn)
        outState.putString(KEY_RETENTION_TEXT, binding.etRetention.text?.toString())
        outState.putString(KEY_RETENTION_DRAWN, retentionField.drawn)
    }

    /** True while [drawFromPrefs] sets the controls, so their listeners store nothing. */
    private var drawing = false

    /**
     * True while the encryption switch's own "Set password" dialog is open: the switch shows on there and nothing is
     * stored until Save, so a redraw must not turn it off under the dialog.
     */
    private var encryptionAwaitingPassword = false

    private val intervalField = BackupNumberField(1..365, 7)
    private val retentionField = BackupNumberField(1..999, 14)

    private fun storedInterval() =
        prefs.getString(SyncPreferences.KEY_BACKUP_INTERVAL_DAYS, null) ?: SyncPreferences.DEFAULT_BACKUP_INTERVAL_DAYS

    private fun storedRetention() =
        prefs.getString(SyncPreferences.KEY_BACKUP_RETENTION, null) ?: SyncPreferences.DEFAULT_BACKUP_RETENTION

    /** Every control from the stored settings; [fresh] on create, otherwise a number field being edited keeps its edit. */
    private fun drawFromPrefs(fresh: Boolean) {
        drawing = true
        try {
            binding.switchBackupEnabled.isChecked = prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENABLED, false)
            binding.checkCalendars.isChecked = prefs.getBoolean(SyncPreferences.KEY_BACKUP_INCLUDE_CALENDARS, true)
            binding.checkContacts.isChecked = prefs.getBoolean(SyncPreferences.KEY_BACKUP_INCLUDE_CONTACTS, true)
            if (!encryptionAwaitingPassword) {
                binding.switchEncrypt.isChecked = prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED, false)
            }
            showText(binding.etIntervalDays, intervalField.redraw(storedInterval(), binding.etIntervalDays.text?.toString(), fresh))
            showText(binding.etRetention, retentionField.redraw(storedRetention(), binding.etRetention.text?.toString(), fresh))
        } finally {
            drawing = false
        }
    }

    /** After a re-creation: a number field's edit in progress comes back while its setting is still what it was. */
    private fun restoreNumberEdits(saved: Bundle) {
        showText(
            binding.etIntervalDays,
            intervalField.restore(saved.getString(KEY_INTERVAL_TEXT), saved.getString(KEY_INTERVAL_DRAWN), storedInterval())
        )
        showText(
            binding.etRetention,
            retentionField.restore(saved.getString(KEY_RETENTION_TEXT), saved.getString(KEY_RETENTION_DRAWN), storedRetention())
        )
    }

    /** [value] into [field] with the cursor after it, or nothing for null. */
    private fun showText(field: android.widget.EditText, value: String?) {
        if (value == null) return
        field.setText(value)
        field.setSelection(value.length)
    }

    private fun wireListeners() {
        binding.btnChooseFolder.setOnClickListener { pickFolderLauncher.launch(null) }

        binding.switchBackupEnabled.setOnCheckedChangeListener { _, checked ->
            if (drawing) return@setOnCheckedChangeListener
            if (checked && !SafBackupStorage.hasValidPermission(this)) {
                binding.switchBackupEnabled.isChecked = false
                promptChooseFolderFirst(); return@setOnCheckedChangeListener
            }
            if (checked && binding.switchEncrypt.isChecked && !hasPassphrase()) {
                binding.switchBackupEnabled.isChecked = false
                Toast.makeText(this, R.string.backup_toast_set_passphrase_first, Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean(SyncPreferences.KEY_BACKUP_ENABLED, checked).apply()
            BackupScheduleWorker.reschedule(this)
        }

        binding.checkCalendars.setOnCheckedChangeListener { _, c ->
            if (drawing) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(SyncPreferences.KEY_BACKUP_INCLUDE_CALENDARS, c).apply()
        }
        binding.checkContacts.setOnCheckedChangeListener { _, c ->
            if (drawing) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(SyncPreferences.KEY_BACKUP_INCLUDE_CONTACTS, c).apply()
        }

        binding.switchEncrypt.setOnCheckedChangeListener { _, checked ->
            if (drawing) return@setOnCheckedChangeListener
            if (checked && !hasPassphrase()) {
                encryptionAwaitingPassword = true
                promptSetPassphrase(
                    onSet = {
                        encryptionAwaitingPassword = false
                        prefs.edit().putBoolean(SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED, true).apply()
                        refreshUi()
                    },
                    onCancel = {
                        // Nothing was stored when the dialog opened, so nothing is stored now: the switch shows the stored
                        // choice again, which another copy of this screen may have changed meanwhile.
                        encryptionAwaitingPassword = false
                        drawing = true
                        try {
                            binding.switchEncrypt.isChecked =
                                prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED, false)
                        } finally {
                            drawing = false
                        }
                        refreshUi()
                    }
                )
            } else {
                prefs.edit().putBoolean(SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED, checked).apply()
                refreshUi()
            }
        }

        binding.btnSetPassphrase.setOnClickListener {
            promptSetPassphrase(onSet = { refreshUi() }, onCancel = {})
        }
        binding.btnTestPassphrase.setOnClickListener { promptTestPassphrase() }
        binding.btnBackupNow.setOnClickListener { runManualBackup() }
        binding.btnDecryptBackup.setOnClickListener { pickEncryptedBackupLauncher.launch(arrayOf("*/*")) }
    }

    override fun onPause() {
        super.onPause()
        saveIntervalAndRetention()
    }

    private fun saveIntervalAndRetention() {
        // Only a field the user changed: one still showing what it was drawn with leaves the stored value alone, so a
        // value an import or another copy of this screen stored meanwhile is not overwritten with the old one.
        val days = intervalField.toStore(binding.etIntervalDays.text?.toString())
        val keep = retentionField.toStore(binding.etRetention.text?.toString())
        if (days != null || keep != null) {
            prefs.edit().apply {
                if (days != null) putString(SyncPreferences.KEY_BACKUP_INTERVAL_DAYS, days)
                if (keep != null) putString(SyncPreferences.KEY_BACKUP_RETENTION, keep)
            }.apply()
        }
        // reschedule() reads the stored settings itself and keeps the schedule's timing (UPDATE), so this stays on every
        // pause: it also applies an interval an import stored.
        if (prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENABLED, false)) BackupScheduleWorker.reschedule(this)
    }

    private fun hasPassphrase(): Boolean =
        !prefs.getString(SyncPreferences.KEY_BACKUP_PASSPHRASE_ENC, null).isNullOrEmpty()

    private fun refreshUi() {
        binding.textBackupFolder.text = SafBackupStorage.displayPath(this)
            ?.let { getString(R.string.backup_folder_current, it) }
            ?: getString(R.string.backup_folder_none)

        val encOn = binding.switchEncrypt.isChecked
        binding.btnSetPassphrase.visibility = if (encOn) View.VISIBLE else View.GONE
        binding.textPassphraseStatus.visibility = if (encOn) View.VISIBLE else View.GONE
        // "Test password" is only meaningful once a password has actually been set.
        binding.btnTestPassphrase.visibility = if (encOn && hasPassphrase()) View.VISIBLE else View.GONE
        binding.textPassphraseStatus.text =
            getString(if (hasPassphrase()) R.string.backup_passphrase_set else R.string.backup_passphrase_not_set)

        val lastMs = prefs.getLong(SyncPreferences.KEY_BACKUP_LAST_RUN_MS, 0L)
        binding.textLastBackup.text = if (lastMs <= 0L) {
            getString(R.string.backup_last_never)
        } else {
            val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastMs))
            val summary = prefs.getString(SyncPreferences.KEY_BACKUP_LAST_RESULT, "") ?: ""
            getString(R.string.backup_last_run, when_, summary)
        }
    }

    private fun promptChooseFolderFirst() {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_data_title)
            .setMessage(R.string.backup_need_folder_body)
            .setPositiveButton(R.string.backup_choose_folder) { _, _ -> pickFolderLauncher.launch(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptSetPassphrase(onSet: () -> Unit, onCancel: () -> Unit) {
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
        }
        // Material text fields with a password-visibility (eye) toggle, so the user can reveal
        // what they typed and verify it.
        val passLayout = passwordField(R.string.backup_passphrase_hint)
        val pass = passLayout.editText!!
        val confirmLayout = passwordField(R.string.backup_passphrase_confirm_hint)
        val confirm = confirmLayout.editText!!
        val warn = TextView(this).apply {
            text = getString(R.string.backup_passphrase_warning)
            textSize = 12f; alpha = 0.7f
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        container.addView(passLayout); container.addView(confirmLayout); container.addView(warn)

        var committed = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.backup_set_passphrase)
            .setView(container)
            .setPositiveButton(R.string.backup_btn_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = pass.text?.toString() ?: ""
                val p2 = confirm.text?.toString() ?: ""
                passLayout.error = null; confirmLayout.error = null
                if (p1.length < MIN_PASSPHRASE_LENGTH) {
                    passLayout.error = getString(R.string.backup_error_passphrase_short); return@setOnClickListener
                }
                if (p1 != p2) {
                    confirmLayout.error = getString(R.string.backup_error_passphrase_mismatch); return@setOnClickListener
                }
                prefs.edit().putString(SyncPreferences.KEY_BACKUP_PASSPHRASE_ENC, SecureStorage.encrypt(p1)).apply()
                committed = true
                dialog.dismiss()
                onSet()
            }
        }
        dialog.setOnDismissListener {
            // Clear the passphrase from the EditText buffers and guard the revert against a
            // destroyed activity (the dialog can outlive a rotation mid-flow).
            pass.setText(""); confirm.setText("")
            if (!committed && !isFinishing && !isDestroyed) onCancel()
        }
        dialog.show()
    }

    /** A Material password field with a visibility (eye) toggle, so the user can reveal and verify what they type. */
    private fun passwordField(hintRes: Int): com.google.android.material.textfield.TextInputLayout {
        val layout = com.google.android.material.textfield.TextInputLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            hint = getString(hintRes)
            isErrorEnabled = true
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = com.google.android.material.textfield.TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(edit)
        return layout
    }

    /**
     * Let the user verify they still remember their backup password without changing it or
     * decrypting any real backup. Security note: the passphrase is already stored recoverably
     * (Keystore-backed [SecureStorage]) so the unattended worker can encrypt backups; this
     * read-only check therefore exposes nothing an attacker with an unlocked device + the app
     * open couldn't already obtain. It touches no backup data and works even before any backup
     * has been made.
     */
    private fun promptTestPassphrase() {
        val storedEnc = prefs.getString(SyncPreferences.KEY_BACKUP_PASSPHRASE_ENC, null)
        if (storedEnc.isNullOrEmpty()) {
            Toast.makeText(this, R.string.backup_passphrase_not_set, Toast.LENGTH_SHORT).show()
            return
        }
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val inputLayout = passwordField(R.string.backup_passphrase_hint)
        val input = inputLayout.editText!!
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
            addView(inputLayout)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_test_passphrase_title)
            .setMessage(R.string.backup_test_passphrase_body)
            .setView(container)
            .setPositiveButton(R.string.backup_btn_test) { _, _ ->
                val correct = verifyBackupPassphrase(input.text?.toString() ?: "", storedEnc)
                input.setText("")
                AlertDialog.Builder(this)
                    .setMessage(if (correct) R.string.backup_test_passphrase_correct else R.string.backup_test_passphrase_wrong)
                    .setPositiveButton(R.string.btn_info_dismiss, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { input.setText("") } // clear the field buffer
            .show()
    }

    /** Constant-time comparison of [candidate] against the stored (Keystore-encrypted) passphrase. */
    private fun verifyBackupPassphrase(candidate: String, storedEnc: String): Boolean {
        // decryptStrict, not decrypt: the lenient reader answers a failed Keystore decryption with
        // Base64-decoded ciphertext, which is a string of U+FFFD that no typed password can match.
        // "Test password" would then reject the correct password with no hint as to why.
        val actual = try { SecureStorage.decryptStrict(storedEnc) } catch (_: Exception) { return false }
        if (actual.isBlank()) return false
        val a = candidate.toByteArray(Charsets.UTF_8)
        val b = actual.toByteArray(Charsets.UTF_8)
        val equal = java.security.MessageDigest.isEqual(a, b) // constant-time for equal-length inputs
        java.util.Arrays.fill(a, 0)
        java.util.Arrays.fill(b, 0)
        return equal
    }

    private fun runManualBackup() {
        if (!SafBackupStorage.hasValidPermission(this)) { promptChooseFolderFirst(); return }
        if (binding.switchEncrypt.isChecked && !hasPassphrase()) {
            Toast.makeText(this, R.string.backup_toast_set_passphrase_first, Toast.LENGTH_LONG).show(); return
        }
        saveIntervalAndRetention()

        val progress = buildProgressDialog(getString(R.string.backup_running))
        progress.show()
        val label = SimpleDateFormat("yyyy-MM-dd'T'HHmmss", Locale.US).format(Date())
        // Logged at INFO because a manual run used to leave no trace at all: a debug report from a
        // user who had just pressed this button contained not one backup line, so there was no way
        // to tell whether the run had happened, failed, or never started.
        SyncLog.i(TAG, "Manual backup started ($label)")
        // The run itself belongs to the application, not to this screen. Started on lifecycleScope
        // it was cancelled at ON_DESTROY, so backing out mid-backup killed it partway and recorded
        // nothing, leaving the label describing some older run. startRecorded owns both the run and
        // the recording; awaiting it here is only so this screen can update its own dialog, and
        // dropping the await changes nothing about the backup.
        val run = BackupDataExporter.startRecorded(this, label)
        lifecycleScope.launch {
            val outcome = run.await()
            val summary = BackupDataExporter.summaryOf(this@BackupSettingsActivity, outcome)
            SyncLog.i(TAG, "Manual backup finished: $summary")
            if (!isFinishing && !isDestroyed) {
                progress.dismiss()
                when (outcome) {
                    is BackupDataExporter.Outcome.Ran -> showBackupResultDialog(outcome.result)
                    is BackupDataExporter.Outcome.Threw -> AlertDialog.Builder(this@BackupSettingsActivity)
                        .setTitle(R.string.backup_data_title)
                        .setMessage(summary)
                        .setPositiveButton(R.string.btn_info_dismiss, null)
                        .show()
                }
                refreshUi()
            }
        }
    }


    private fun showBackupResultDialog(result: BackupDataExporter.Result) {
        val msg = when (result) {
            is BackupDataExporter.Result.Success -> BackupDataExporter.successText(this, result)
            BackupDataExporter.Result.SkippedEmptyGuard -> getString(R.string.backup_notif_skipped_body)
            BackupDataExporter.Result.NoFolder -> getString(R.string.backup_folder_none)
            is BackupDataExporter.Result.Failure -> getString(R.string.backup_result_failed, result.reason)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_data_title)
            .setMessage(msg)
            .setPositiveButton(R.string.btn_info_dismiss, null)
            .show()
    }

    // ---- Decrypt a backup (.gpg) ----

    private fun promptDecryptPassphrase(uri: Uri) {
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val inputLayout = passwordField(R.string.backup_passphrase_hint)
        val input = inputLayout.editText!!
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * d).toInt(), pad, 0)
            addView(inputLayout)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_decrypt_action)
            .setMessage(R.string.backup_decrypt_body)
            .setView(container)
            .setPositiveButton(R.string.backup_btn_unlock) { _, _ ->
                runDecrypt(uri, input.text?.toString() ?: "")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { input.setText("") } // clear passphrase from the field buffer
            .show()
    }

    private fun runDecrypt(uri: Uri, passphrase: String) {
        if (!SafBackupStorage.hasValidPermission(this)) { promptChooseFolderFirst(); return }
        val progress = buildProgressDialog(getString(R.string.backup_decrypting))
        progress.show()
        lifecycleScope.launch {
            val outcome: Result<String> = withContext(Dispatchers.IO) {
                try {
                    val pass = passphrase.toCharArray()
                    val files = try {
                        contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input)
                            BackupArchiveCrypto.decrypt(input, pass)
                        }
                    } finally {
                        java.util.Arrays.fill(pass, ' ') // zero the passphrase on every path
                    }
                    val runName = "decrypted-" + SimpleDateFormat("yyyy-MM-dd'T'HHmmss", Locale.US).format(Date())
                    if (SafBackupStorage.writeRun(this@BackupSettingsActivity, runName, files)) Result.success(runName)
                    else Result.failure(IllegalStateException("write_failed"))
                } catch (e: BackupArchiveCrypto.WrongPassphraseException) {
                    Result.failure(e)
                } catch (e: Exception) {
                    SyncLog.w(TAG, "Decrypt failed: ${e.javaClass.simpleName}")
                    Result.failure(e)
                }
            }
            if (isFinishing || isDestroyed) return@launch
            progress.dismiss()
            val msg = outcome.fold(
                onSuccess = { getString(R.string.backup_decrypt_done, it) },
                onFailure = {
                    if (it is BackupArchiveCrypto.WrongPassphraseException)
                        getString(R.string.backup_error_wrong_passphrase)
                    else getString(R.string.backup_decrypt_failed)
                }
            )
            AlertDialog.Builder(this@BackupSettingsActivity)
                .setTitle(R.string.backup_decrypt_action)
                .setMessage(msg)
                .setPositiveButton(R.string.btn_info_dismiss, null)
                .show()
        }
    }

    private fun buildProgressDialog(message: String): AlertDialog {
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(this@BackupSettingsActivity).apply {
                val s = (28 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s)
            })
            addView(TextView(this@BackupSettingsActivity).apply {
                text = message
                setPadding((16 * d).toInt(), 0, 0, 0)
                textSize = 15f
            })
        }
        return AlertDialog.Builder(this).setView(row).setCancelable(false).create()
    }

    companion object {
        private const val TAG = "BackupSettings"
        private const val MIN_PASSPHRASE_LENGTH = 8
        private const val KEY_INTERVAL_TEXT = "backup_settings_interval_text"
        private const val KEY_INTERVAL_DRAWN = "backup_settings_interval_drawn"
        private const val KEY_RETENTION_TEXT = "backup_settings_retention_text"
        private const val KEY_RETENTION_DRAWN = "backup_settings_retention_drawn"
    }
}

/**
 * One of the backup screen's two number fields (interval days, retention count): the stored value it was last drawn
 * with or stored as, [drawn], and the text it showed then, which decide what the field shows and what leaving the screen
 * stores.
 *
 * Leaving the screen stores a field only when the user typed in it since it was last drawn or stored. Coming back, the
 * field stays as it is (an emptied field stays empty, so the next digits are not added to a refilled number) unless the
 * setting was changed elsewhere meanwhile (an import, another copy of the screen): then it shows the new value. Android's
 * own saved view state is not used: it once restored an old number over a newer setting, and the old number was then
 * stored again.
 */
internal class BackupNumberField(private val range: IntRange, private val fallback: Int) {
    var drawn: String? = null
        private set

    /** The field's text when it was last drawn or stored: typed since, it differs. */
    private var seen: String? = null

    /**
     * The text to put in the field for the [stored] value, or null to leave its [text] alone. [fresh] is a new view (on
     * create); otherwise the field changes only when the setting moved since it was drawn or stored.
     */
    fun redraw(stored: String, text: String?, fresh: Boolean): String? {
        if (!fresh && stored == drawn) return null
        drawn = stored
        seen = stored
        return if (text == stored) null else stored
    }

    /**
     * After a re-creation: the field's text saved with the screen, [savedText] (with [savedDrawn] stored then), comes back
     * only while the setting is still [savedDrawn]. Changed since, the [stored] value the field was just drawn with stands.
     */
    fun restore(savedText: String?, savedDrawn: String?, stored: String): String? {
        if (savedText == null || savedDrawn == null || savedDrawn != stored) return null
        drawn = savedDrawn
        seen = savedText
        return if (savedText == stored) null else savedText
    }

    /**
     * What to store when the screen is left showing [text], or null when nothing was typed since the field was drawn or
     * stored: then the stored setting stays as it is, whatever changed it meanwhile. A typed text is stored as a whole
     * number in [range], [fallback] when it holds none.
     */
    fun toStore(text: String?): String? {
        if (text == seen) return null
        seen = text
        return (text?.toIntOrNull()?.coerceIn(range) ?: fallback).toString().also { drawn = it }
    }
}
