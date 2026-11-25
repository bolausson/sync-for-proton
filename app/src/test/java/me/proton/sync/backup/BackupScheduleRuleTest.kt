package me.proton.sync.backup

import me.proton.sync.SyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Scheduled data backups follow Android's own automatic sync switch, as every background sync does since 2.2.0, unless
 * the user turned on "Back up even when automatic sync is off" (owner, 2026-11-21): a backup downloads every calendar
 * and contact from Proton. A skipped run records its own line for the backup screen, under the last run's, and nothing
 * else, and keeps the schedule (BackupResultLinesTest). "Back up now" is the user's own and runs whatever either switch says. The rule is pure; the worker, the
 * screen and the settings backup are pinned in the app's own sources, with their comments taken out.
 */
class BackupScheduleRuleTest {

    @Test
    fun `a scheduled backup runs while automatic sync is on, and while it is off only when the user chose so`() {
        assertTrue(BackupScheduleWorker.runsScheduled(masterSyncOn = true, whenAutoSyncOff = false))
        assertTrue(BackupScheduleWorker.runsScheduled(masterSyncOn = true, whenAutoSyncOff = true))
        assertTrue(BackupScheduleWorker.runsScheduled(masterSyncOn = false, whenAutoSyncOff = true))
        assertFalse(BackupScheduleWorker.runsScheduled(masterSyncOn = false, whenAutoSyncOff = false))
        // Off by default: with Android's switch off, nothing is downloaded until the user turns it on.
        assertFalse(SyncPreferences.DEFAULT_BACKUP_WHEN_AUTO_SYNC_OFF)
        // The literal travels in users' settings backups: renaming it would drop the choice on an import.
        assertEquals("data_backup_when_auto_sync_off", SyncPreferences.KEY_BACKUP_WHEN_AUTO_SYNC_OFF)
    }

    @Test
    fun `the switch is one setting for the phone, and travels with the settings backup as a Boolean`() {
        val key = SyncPreferences.KEY_BACKUP_WHEN_AUTO_SYNC_OFF
        assertTrue(key in SyncPreferences.DEVICE_WIDE_KEYS)
        assertTrue(BackupSettings.isExportable(key))
        assertEquals(listOf(BackupSettings.PrefType.BOOLEAN), BackupSettings.typesFor(key))
        assertEquals(true, BackupSettings.importValue(key, true))
        assertEquals(false, BackupSettings.importValue(key, "false"))
        // An unreadable value is dropped, so the default applies: never taken as on.
        assertNull(BackupSettings.importValue(key, "1"))
        assertFalse(key in BackupSettings.UNREADABLE_AS)
    }

    private fun source(path: String) = File("src/main/$path").readText().replace("\r\n", "\n")

    /** [text] without KDoc, block comment lines and line comments. */
    private fun code(text: String): String =
        text.lines().filterNot { it.trim().let { t -> t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") } }
            .joinToString("\n") { it.substringBefore(" // ") }

    private fun squash(text: String) = text.replace(Regex("\\s+"), " ")

    /** The text of the function that starts with [signature], up to its closing brace at [indent]. */
    private fun body(src: String, signature: String, indent: String = "    "): String {
        assertTrue("$signature not found", src.contains(signature))
        return src.substringAfter(signature).substringBefore("\n$indent}\n")
    }

    @Test
    fun `a skipped run records its line only, before the folder check and the backup, and keeps the schedule`() {
        val worker = code(source("java/me/proton/sync/backup/BackupScheduleWorker.kt"))
        val run = squash(body(worker, "internal fun runScheduled(ctx: Context): Run {", indent = "        "))
        val off = run.indexOf("if (!prefs.getBoolean(SyncPreferences.KEY_BACKUP_ENABLED, false)) {")
        val read = run.indexOf("SyncPreferences.KEY_BACKUP_WHEN_AUTO_SYNC_OFF, SyncPreferences.DEFAULT_BACKUP_WHEN_AUTO_SYNC_OFF")
        val rule = run.indexOf("if (!runsScheduled(ContentResolver.getMasterSyncAutomatically(), whenAutoSyncOff)) {")
        val folder = run.indexOf("if (!SafBackupStorage.hasValidPermission(ctx)) {")
        val backup = run.indexOf("BackupDataExporter.from(ctx).runBackup(label)")
        assertTrue(run, off >= 0 && read > off && rule > read && folder > rule && backup > folder)
        val skip = run.substring(rule, folder)
        assertTrue(skip, skip.contains("BackupDataExporter.recordSkippedAutoSyncOff(ctx)"))
        // Its own line: the last run's date and result stay on the screen (owner, 2026-11-22).
        assertFalse(skip, skip.contains("BackupDataExporter.record("))
        assertTrue(skip, skip.contains("return Run.SKIPPED_AUTO_SYNC_OFF"))
        assertFalse(skip, skip.contains("showBackupResult("))
        // To WorkManager a skipped run is a success: no retry storm, and the next period comes as scheduled.
        val doWork = squash(worker.substringAfter("override fun doWork(): Result").substringBefore("\n\n"))
        assertTrue(doWork, doWork.contains("= if (runScheduled(applicationContext) == Run.RETRY) Result.retry() else Result.success()"))
        // Only a timeout and a network failure try again, on WorkManager's backoff.
        assertEquals(run, 2, Regex(Regex.escape("Run.RETRY")).findAll(run).count())
        assertTrue(run, run.contains("if (e is java.io.IOException) Run.RETRY else Run.DONE"))
    }

    @Test
    fun `Back up now never reads either switch`() {
        val screen = code(source("java/me/proton/sync/settings/BackupSettingsActivity.kt"))
        val manual = squash(body(screen, "private fun runManualBackup() {"))
        assertTrue(manual, manual.contains("val run = BackupDataExporter.startRecorded(this, label)"))
        for (forbidden in listOf("getMasterSyncAutomatically", "KEY_BACKUP_WHEN_AUTO_SYNC_OFF", "runScheduled(", "runsScheduled(")) {
            assertFalse(forbidden, manual.contains(forbidden))
        }
        val exporter = code(source("java/me/proton/sync/backup/BackupDataExporter.kt"))
        assertFalse(exporter.contains("getMasterSyncAutomatically"))
        assertFalse(exporter.contains("KEY_BACKUP_WHEN_AUTO_SYNC_OFF"))
    }

    @Test
    fun `the backup screen draws the switch from the stored setting and stores a change only outside a redraw`() {
        val screen = code(source("java/me/proton/sync/settings/BackupSettingsActivity.kt"))
        val draw = squash(body(screen, "private fun drawFromPrefs(fresh: Boolean) {"))
        assertTrue(
            draw,
            draw.contains(
                "binding.switchBackupWhenAutoSyncOff.isChecked = prefs.getBoolean( " +
                    "SyncPreferences.KEY_BACKUP_WHEN_AUTO_SYNC_OFF, SyncPreferences.DEFAULT_BACKUP_WHEN_AUTO_SYNC_OFF )"
            )
        )
        val wire = squash(body(screen, "private fun wireListeners() {"))
        // Stored, then the lines under "Back up now" drawn again: the switch decides whether the skip line shows
        // (BackupResultLinesTest).
        assertTrue(
            wire,
            wire.contains(
                "binding.switchBackupWhenAutoSyncOff.setOnCheckedChangeListener { _, checked -> " +
                    "if (drawing) return@setOnCheckedChangeListener " +
                    "prefs.edit().putBoolean(SyncPreferences.KEY_BACKUP_WHEN_AUTO_SYNC_OFF, checked).apply() refreshUi() }"
            )
        )
        val info = wire.substringAfter("binding.infoBackupWhenAutoSyncOff.setOnClickListener {").substringBefore(".show()")
        assertTrue(info, info.contains(".setTitle(R.string.backup_when_auto_sync_off_label)"))
        assertTrue(info, info.contains(".setMessage(R.string.info_backup_when_auto_sync_off_body)"))
        // The row sits in the Schedule section, after the two number fields, with its info icon and switch.
        val layout = File("src/main/res/layout/activity_backup_settings.xml").readText()
        val retention = layout.indexOf("@+id/et_retention")
        val icon = layout.indexOf("@+id/info_backup_when_auto_sync_off")
        val switch = layout.indexOf("@+id/switch_backup_when_auto_sync_off")
        val contents = layout.indexOf("@string/backup_section_contents")
        assertTrue(retention >= 0 && icon > retention && switch > icon && contents > switch)
    }

    @Test
    fun `the backup-scheduled debug verb runs the worker's own run, in debug builds only`() {
        val activity = source("java/me/proton/sync/settings/SyncSettingsActivity.kt")
        val debug = body(activity, "private fun handleDebugCommand(")
        assertTrue(squash(code(debug)).contains("if (!me.proton.sync.BuildConfig.DEBUG || intent == null) return"))
        val branch = squash(code(debug.substringAfter("\"backup_scheduled\" -> {").substringBefore("\n            }\n")))
        assertTrue(branch, branch.contains("me.proton.sync.backup.BackupScheduleWorker.runScheduled(app)"))
        assertFalse(branch, branch.contains("BackupDataExporter"))
        assertFalse(branch, branch.contains("runsScheduled("))
    }
}
