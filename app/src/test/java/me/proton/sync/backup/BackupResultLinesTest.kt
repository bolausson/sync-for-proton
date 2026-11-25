package me.proton.sync.backup

import me.proton.sync.R
import me.proton.sync.SyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The lines under "Back up now" on the Backups screen (owner, 2026-11-22): "Last backup: <date>, <result>" for the last
 * run that recorded a result, and a scheduled backup skipped since because Android's automatic sync is off on a line of
 * its own, "Scheduled backup skipped on <date>: Android's automatic sync is off", which the next run takes away. Until
 * then the skip replaced the first line, and with it the date of the last real backup. The skip line shows only while
 * its sentence is true, while a scheduled backup would still be skipped (review, 2026-11-22). The record and the rule
 * are pure; the screen, the worker, the exporter and the debug verb are pinned in the app's own sources, comments taken
 * out.
 */
class BackupResultLinesTest {

    private val prefs = StrictPrefs()

    /**
     * The two lines, each date written as "at <ms>", so an assertion reads which time each line shows; [skipping]: a
     * scheduled backup would still be skipped (BackupResultLines.skipsNow), as on the day of the skip.
     */
    private fun lines(skipping: Boolean = true) = BackupResultLines.lines(BackupResultLines.read(prefs), skipping) { "at $it" }

    private val never = BackupResultLines.Line(R.string.backup_last_never, emptyList())

    private fun lastRun(at: Long, result: String) = BackupResultLines.Line(R.string.backup_last_run, listOf("at $at", result))

    private fun skipped(at: Long) = BackupResultLines.Line(R.string.backup_last_skipped_auto_sync_off, listOf("at $at"))

    private val done = "Backed up 2 calendars, 340 events and 1 contact."

    @Test
    fun `before any run the screen says no backup yet, with no skip line`() {
        assertEquals(BackupResultLines.Stored(null, "", null), BackupResultLines.read(prefs))
        assertEquals(Pair(never, null), lines())
    }

    @Test
    fun `a run shows its date and result, and no skip line`() {
        BackupResultLines.recordRun(prefs, done, 1_000L)
        assertEquals(BackupResultLines.Stored(1_000L, done, null), BackupResultLines.read(prefs))
        assertEquals(Pair(lastRun(1_000L, done), null), lines())
    }

    @Test
    fun `a skip keeps the last backup's date and result, and adds its own line`() {
        BackupResultLines.recordRun(prefs, done, 1_000L)
        BackupResultLines.recordSkip(prefs, 2_000L)
        assertEquals(Pair(lastRun(1_000L, done), skipped(2_000L)), lines())
        assertEquals(1_000L, prefs.getLong(SyncPreferences.KEY_BACKUP_LAST_RUN_MS, 0L))
        assertEquals(done, prefs.getString(SyncPreferences.KEY_BACKUP_LAST_RESULT, null))
    }

    @Test
    fun `after weeks of skips the screen still says when the last backup was made, and shows the newest skip`() {
        BackupResultLines.recordRun(prefs, done, 1_000L)
        for (at in listOf(2_000L, 3_000L, 4_000L)) BackupResultLines.recordSkip(prefs, at)
        assertEquals(Pair(lastRun(1_000L, done), skipped(4_000L)), lines())
    }

    @Test
    fun `a skip minutes after Back up now leaves its success line, and the next run takes the skip line away`() {
        val outcomes = listOf(
            done, "Backup failed: timeout", "Backup folder is no longer accessible.",
            "Skipped: the source returned no data, so the last good backup was kept.",
        )
        var at = 1_000L
        var shown = lastRun(at, done)
        BackupResultLines.recordRun(prefs, done, at)
        for (summary in outcomes) {
            BackupResultLines.recordSkip(prefs, ++at)
            assertEquals(summary, Pair(shown, skipped(at)), lines())
            // The next run, scheduled or Back up now, whatever it recorded.
            BackupResultLines.recordRun(prefs, summary, ++at)
            shown = lastRun(at, summary)
            assertEquals(summary, Pair(shown, null), lines())
            assertFalse(summary, prefs.contains(SyncPreferences.KEY_BACKUP_LAST_SKIP_MS))
        }
    }

    @Test
    fun `a skip before any backup shows no backup yet, and the skip line`() {
        BackupResultLines.recordSkip(prefs, 2_000L)
        assertEquals(Pair(never, skipped(2_000L)), lines())
    }

    @Test
    fun `the order of the records decides, not the clock`() {
        // A clock set back between two records: the later record still wins.
        BackupResultLines.recordRun(prefs, done, 5_000L)
        BackupResultLines.recordSkip(prefs, 4_000L)
        assertEquals(Pair(lastRun(5_000L, done), skipped(4_000L)), lines())
        BackupResultLines.recordRun(prefs, "Backup failed: timeout", 3_000L)
        assertEquals(Pair(lastRun(3_000L, "Backup failed: timeout"), null), lines())
    }

    @Test
    fun `the skip line shows only while a scheduled backup would still be skipped, and its record waits for the next run`() {
        // It says Android's automatic sync "is off". With that switch back on, "Back up even when automatic sync is off"
        // on or scheduled backups off, it would be untrue the moment the user acted on it: a scheduled run can be a
        // whole interval away.
        BackupResultLines.recordRun(prefs, done, 1_000L)
        BackupResultLines.recordSkip(prefs, 2_000L)
        var shown = 0
        for (enabled in listOf(true, false)) for (masterSyncOn in listOf(true, false)) for (whenOff in listOf(true, false)) {
            prefs.fake.values[SyncPreferences.KEY_BACKUP_ENABLED] = enabled
            prefs.fake.values[SyncPreferences.KEY_BACKUP_WHEN_AUTO_SYNC_OFF] = whenOff
            val case = "scheduled backups $enabled, Android's automatic sync $masterSyncOn, back up even when off $whenOff"
            val skips = enabled && !masterSyncOn && !whenOff
            assertEquals(case, skips, BackupResultLines.skipsNow(prefs, masterSyncOn))
            // The worker's own rule, so the screen never disagrees with what a scheduled run would do now.
            assertEquals(case, enabled && !BackupScheduleWorker.runsScheduled(masterSyncOn, whenOff), BackupResultLines.skipsNow(prefs, masterSyncOn))
            val (first, second) = BackupResultLines.of(prefs, masterSyncOn)
            assertEquals(case, BackupResultLines.Line(R.string.backup_last_run, listOf(BackupResultLines.date(1_000L), done)), first)
            val skipLine = BackupResultLines.Line(R.string.backup_last_skipped_auto_sync_off, listOf(BackupResultLines.date(2_000L)))
            assertEquals(case, if (skips) skipLine else null, second)
            if (second != null) shown++
            // Hidden is not forgotten: back in the skipping state, the same line returns, until the next run removes it.
            assertEquals(case, 2_000L, prefs.getLong(SyncPreferences.KEY_BACKUP_LAST_SKIP_MS, 0L))
        }
        assertEquals(1, shown)
        BackupResultLines.recordRun(prefs, done, 3_000L)
        prefs.fake.values[SyncPreferences.KEY_BACKUP_ENABLED] = true
        prefs.fake.values[SyncPreferences.KEY_BACKUP_WHEN_AUTO_SYNC_OFF] = false
        assertEquals(Pair(BackupResultLines.Line(R.string.backup_last_run, listOf(BackupResultLines.date(3_000L), done)), null),
            BackupResultLines.of(prefs, masterSyncOn = false))
    }

    @Test
    fun `not skipping, only the skip line goes, before any backup too`() {
        BackupResultLines.recordSkip(prefs, 2_000L)
        assertEquals(Pair(never, null), lines(skipping = false))
        BackupResultLines.recordRun(prefs, done, 3_000L)
        BackupResultLines.recordSkip(prefs, 4_000L)
        assertEquals(Pair(lastRun(3_000L, done), null), lines(skipping = false))
        assertEquals(Pair(lastRun(3_000L, done), skipped(4_000L)), lines(skipping = true))
    }

    @Test
    fun `the switches read as the worker reads them, their defaults included`() {
        // Scheduled backups are off until the user turns them on: nothing is skipped then.
        assertFalse(BackupResultLines.skipsNow(prefs, masterSyncOn = false))
        prefs.fake.values[SyncPreferences.KEY_BACKUP_ENABLED] = true
        // "Back up even when automatic sync is off" is off until the user turns it on.
        assertTrue(BackupResultLines.skipsNow(prefs, masterSyncOn = false))
        assertFalse(BackupResultLines.skipsNow(prefs, masterSyncOn = true))
        // Reading changes nothing.
        assertEquals(0, prefs.editsOpened)
    }

    @Test
    fun `recording touches the three keys of the lines and nothing else`() {
        prefs.fake.values[SyncPreferences.KEY_BACKUP_LAST_TOTAL] = 12
        prefs.fake.values[SyncPreferences.KEY_BACKUP_ENABLED] = true
        BackupResultLines.recordSkip(prefs, 2_000L)
        assertEquals(
            setOf(SyncPreferences.KEY_BACKUP_LAST_TOTAL, SyncPreferences.KEY_BACKUP_ENABLED, SyncPreferences.KEY_BACKUP_LAST_SKIP_MS),
            prefs.fake.values.keys,
        )
        BackupResultLines.recordRun(prefs, done, 3_000L)
        assertEquals(
            mapOf<String, Any>(
                SyncPreferences.KEY_BACKUP_LAST_TOTAL to 12, SyncPreferences.KEY_BACKUP_ENABLED to true,
                SyncPreferences.KEY_BACKUP_LAST_RUN_MS to 3_000L, SyncPreferences.KEY_BACKUP_LAST_RESULT to done,
            ),
            prefs.fake.values,
        )
    }

    @Test
    fun `both lines write their date as the last backup line always has`() {
        val at = 1_795_316_400_000L // 2026-11-22 03:00 UTC
        assertEquals("2026-11-22 03:00", BackupResultLines.date(at, Locale.US, TimeZone.getTimeZone("UTC")))
        assertEquals("2026-11-22 04:00", BackupResultLines.date(at, Locale.US, TimeZone.getTimeZone("Europe/Berlin")))
        // Without a date of its own, the rule writes both with that format, in the phone's language and zone.
        val (last, skip) = BackupResultLines.lines(BackupResultLines.Stored(at, done, at + 3_600_000L), skipping = true)
        assertEquals(listOf(BackupResultLines.date(at), done), last.args)
        assertEquals(listOf(BackupResultLines.date(at + 3_600_000L)), skip!!.args)
    }

    @Test
    fun `the skip's key is one for the phone, and a log that never travels in a settings backup`() {
        val key = SyncPreferences.KEY_BACKUP_LAST_SKIP_MS
        // A new literal, not one an older version wrote with another meaning.
        assertEquals("data_backup_last_skip_ms", key)
        assertTrue(key in SyncPreferences.DEVICE_WIDE_KEYS)
        assertFalse(BackupSettings.isExportable(key))
        assertTrue(BackupSettings.typesFor(key).isEmpty())
    }

    @Test
    fun `the English line is the owner's text, with the date as its one placeholder`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val text = Regex("<string name=\"backup_last_skipped_auto_sync_off\">(.*?)</string>").find(strings)?.groupValues?.get(1)
        assertEquals("Scheduled backup skipped on %1\$s: Android\\'s automatic sync is off", text)
    }

    // ---- The app's own sources ----

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
    fun `the Backups screen draws both lines from the record, the second only while there is a skip`() {
        val screen = code(source("java/me/proton/sync/settings/BackupSettingsActivity.kt"))
        val refresh = squash(body(screen, "private fun refreshUi() {"))
        // Android's switch is read for the skip line; unreadable, it counts as on, so the line stays hidden.
        val master = refresh.indexOf("val masterSyncOn = try { ContentResolver.getMasterSyncAutomatically() } catch (_: Exception) { true }")
        val read = refresh.indexOf("val (last, skipped) = BackupResultLines.of(prefs, masterSyncOn)")
        assertTrue(refresh, master >= 0 && read > master)
        assertTrue(refresh, refresh.indexOf("binding.textLastBackup.text = last.text(this)") > read)
        assertTrue(refresh, refresh.contains("binding.textBackupSkipped.text = skipped?.text(this).orEmpty()"))
        assertTrue(refresh, refresh.contains("binding.textBackupSkipped.visibility = if (skipped == null) View.GONE else View.VISIBLE"))
        // On create, on every resume and after Back up now.
        assertTrue(body(screen, "override fun onCreate(savedInstanceState: Bundle?) {").contains("refreshUi()"))
        assertTrue(body(screen, "override fun onResume() {").contains("refreshUi()"))
        assertTrue(body(screen, "private fun runManualBackup() {").contains("refreshUi()"))
    }

    @Test
    fun `the skip line follows the three switches it depends on while the screen is in front`() {
        val screen = code(source("java/me/proton/sync/settings/BackupSettingsActivity.kt"))
        // Android's automatic sync switch, changed from the quick settings with the screen in front.
        val resume = squash(body(screen, "override fun onResume() {"))
        val registered = resume.indexOf(
            "autoSyncObserverHandle = ContentResolver.addStatusChangeListener( ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS ) {"
        )
        assertTrue(resume, registered >= 0)
        // Each change redrawn on the main thread, and the screen drawn once more after the listener is in place.
        assertTrue(resume, resume.trim().endsWith("runOnUiThread { if (!isFinishing && !isDestroyed) refreshUi() } } } refreshUi()"))
        val pause = squash(body(screen, "override fun onPause() {"))
        assertTrue(
            pause,
            pause.contains("autoSyncObserverHandle?.let { try { ContentResolver.removeStatusChangeListener(it) } catch (_: Throwable) {} } autoSyncObserverHandle = null")
        )
        // The screen's own two switches: each redraws the lines once it has stored the change.
        val wire = squash(body(screen, "private fun wireListeners() {"))
        assertTrue(
            wire,
            wire.contains(
                "prefs.edit().putBoolean(SyncPreferences.KEY_BACKUP_ENABLED, checked).apply() " +
                    "BackupScheduleWorker.reschedule(this) refreshUi() }"
            )
        )
        assertTrue(wire, wire.contains("prefs.edit().putBoolean(SyncPreferences.KEY_BACKUP_WHEN_AUTO_SYNC_OFF, checked).apply() refreshUi() }"))
    }

    @Test
    fun `the skip line sits right under the last backup's, in its style, hidden until there is a skip`() {
        val layout = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/layout/activity_backup_settings.xml"))
        val all = layout.getElementsByTagName("*")
        val byId = (0 until all.length).map { all.item(it) as Element }.associateBy { it.getAttribute("android:id") }
        val last = byId.getValue("@+id/text_last_backup")
        val skip = byId.getValue("@+id/text_backup_skipped")
        var next = last.nextSibling
        while (next != null && next !is Element) next = next.nextSibling
        assertNotNull(next)
        assertEquals("@+id/text_backup_skipped", (next as Element).getAttribute("android:id"))
        for (attr in listOf("android:textSize", "android:alpha", "android:layout_width")) {
            assertEquals(attr, last.getAttribute(attr), skip.getAttribute(attr))
        }
        assertEquals("gone", skip.getAttribute("android:visibility"))
        assertEquals("", skip.getAttribute("android:text"))
    }

    @Test
    fun `every run records through the exporter's record, the scheduled skip through its own writer`() {
        val exporter = squash(code(source("java/me/proton/sync/backup/BackupDataExporter.kt")))
        assertTrue(
            exporter,
            exporter.contains(
                "fun record(context: Context, summary: String) = BackupResultLines.recordRun(" +
                    "PreferenceManager.getDefaultSharedPreferences(context), summary, System.currentTimeMillis())"
            )
        )
        assertTrue(
            exporter,
            exporter.contains(
                "fun recordSkippedAutoSyncOff(context: Context) = BackupResultLines.recordSkip(" +
                    "PreferenceManager.getDefaultSharedPreferences(context), System.currentTimeMillis())"
            )
        )
        // Back up now: whatever the outcome, thrown or not.
        assertTrue(exporter, exporter.contains("record(app, summaryOf(app, outcome))"))
        // The worker: the lost folder, the timeout, the throw and every result record a run; only the skip does not.
        val worker = squash(code(source("java/me/proton/sync/backup/BackupScheduleWorker.kt")))
        assertEquals(worker, 4, Regex(Regex.escape("BackupDataExporter.record(ctx, ")).findAll(worker).count())
        assertTrue(worker, worker.contains("BackupDataExporter.record(ctx, BackupDataExporter.summaryFor(ctx, result))"))
        assertEquals(worker, 1, Regex(Regex.escape("BackupDataExporter.recordSkippedAutoSyncOff(ctx)")).findAll(worker).count())
    }

    @Test
    fun `only the record reads and writes the three keys`() {
        val keys = listOf("KEY_BACKUP_LAST_RUN_MS", "KEY_BACKUP_LAST_RESULT", "KEY_BACKUP_LAST_SKIP_MS")
        val literals = listOf("data_backup_last_run_ms", "data_backup_last_result", "data_backup_last_skip_ms")
        val files = listOf("src/main", "src/play", "src/direct", "src/debug")
            .flatMap { root -> File(root).walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
        assertTrue("the app's sources are where the test runs (${files.size} files)", files.size > 100)
        val users = files.filter { f -> code(f.readText()).let { text -> keys.any { it in text } || literals.any { it in text } } }
            .map { it.name }.toSet()
        assertEquals(setOf("SyncPreferences.kt", "BackupResultLines.kt"), users)
    }

    @Test
    fun `the backup-scheduled debug verb logs both lines as the screen shows them, after the run`() {
        val activity = source("java/me/proton/sync/settings/SyncSettingsActivity.kt")
        val branch = squash(code(activity.substringAfter("\"backup_scheduled\" -> {").substringBefore("\n            }\n")))
        val run = branch.indexOf("me.proton.sync.backup.BackupScheduleWorker.runScheduled(app)")
        // Android's switch read once, after the run: the skip line by the screen's rule, and the logged state.
        val master = branch.indexOf("val master = ContentResolver.getMasterSyncAutomatically()")
        val read = branch.indexOf("val (last, skipped) = me.proton.sync.backup.BackupResultLines.of(prefs, master)")
        assertTrue(branch, run >= 0 && master > run && read > master)
        assertEquals(branch, 1, Regex(Regex.escape("getMasterSyncAutomatically()")).findAll(branch).count())
        assertTrue(branch, branch.contains("Android's automatic sync \${if (master) \"on\" else \"off\"}, "))
        assertTrue(branch, branch.contains("last.text(app)"))
        assertTrue(branch, branch.contains("skipped?.let { \"and \\\"\${it.text(app)}\\\"\" } ?: \"and no skip line\""))
    }
}
