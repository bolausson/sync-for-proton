package me.proton.sync.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The settings screen's line while Android's own automatic sync switch is off (owner, 2026-11-21): with the switch off
 * no account syncs by itself since 2.2.0, so the screen says so once, in the status card above every account card,
 * kept in step on every resume and while the screen is in front. The texts of the interval and the Fallback full sync
 * say the same. The app only reads the switch (BackgroundSyncRuleTest pins that only the debug verb changes it).
 * Read from the app's own sources, with their comments taken out.
 */
class AutoSyncNoticeTest {

    private fun source(path: String) = File("src/main/$path").readText().replace("\r\n", "\n")

    /** [text] without KDoc, block comment lines and line comments. */
    private fun code(text: String): String =
        text.lines().filterNot { it.trim().let { t -> t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") } }
            .joinToString("\n") { it.substringBefore(" // ") }

    private fun squash(text: String) = text.replace(Regex("\\s+"), " ")

    private fun body(src: String, signature: String): String {
        assertTrue("$signature not found", src.contains(signature))
        return src.substringAfter(signature).substringBefore("\n    }\n")
    }

    private val activity by lazy { source("java/me/proton/sync/settings/SyncSettingsActivity.kt") }

    @Test
    fun `the line follows Android's switch on every resume and while the screen is in front, and the listener goes in onPause`() {
        val resume = squash(code(body(activity, "override fun onResume() {")))
        val registered = resume.indexOf(
            "autoSyncObserverHandle = ContentResolver.addStatusChangeListener( ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS ) {"
        )
        assertTrue(resume, registered >= 0)
        assertTrue(resume, resume.contains("mainHandler.post { if (!isFinishing && !isDestroyed) refreshAutoSyncNotice() }"))
        assertTrue(resume, resume.indexOf("refreshAutoSyncNotice()", registered) > registered)
        val pause = squash(code(body(activity, "override fun onPause() {")))
        assertTrue(
            pause,
            pause.contains("autoSyncObserverHandle?.let { try { ContentResolver.removeStatusChangeListener(it) } catch (_: Throwable) {} } autoSyncObserverHandle = null")
        )
        val notice = squash(code(body(activity, "private fun refreshAutoSyncNotice() {")))
        assertTrue(notice, notice.contains("!ContentResolver.getMasterSyncAutomatically()"))
        assertTrue(notice, notice.contains("binding.textAutoSyncOff.visibility = if (off && subscriptionManager.isSyncAllowed()) View.VISIBLE else View.GONE"))
        // With every update of the status card, before any of its branches returns.
        val hero = squash(code(body(activity, "private fun updateHeroStatus(accounts: List<Account>) {"))).trim()
        assertTrue(hero, hero.startsWith("refreshAutoSyncNotice()"))
    }

    @Test
    fun `the line sits once in the status card, above the button that syncs every account, hidden until the switch is read`() {
        val layout = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/layout/activity_settings.xml"))
        val all = layout.getElementsByTagName("*")
        val lines = (0 until all.length).map { all.item(it) as Element }.filter { it.getAttribute("android:id") == "@+id/text_auto_sync_off" }
        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals("@string/sfp_auto_sync_off", line.getAttribute("android:text"))
        assertEquals("gone", line.getAttribute("android:visibility"))
        // Right above the status card's "Sync all now", inside the card: settings_container keeps its three static
        // header children (SyncSettingsActivity.buildAccountCards counts them).
        var next = line.nextSibling
        while (next != null && next !is Element) next = next.nextSibling
        assertNotNull(next)
        assertEquals("@+id/btn_hero_sync_all", (next as Element).getAttribute("android:id"))
        assertTrue((line.parentNode as Element).getAttribute("android:id") != "@+id/settings_container")
        assertEquals(1, (0 until all.length).count { (all.item(it) as Element).getAttribute("android:id") == "@+id/btn_hero_sync_all" })
    }

    @Test
    fun `the interval and Fallback full sync texts say they run only while Android's automatic sync is on`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        for (key in listOf("sfp_event_loop_subtitle", "info_event_loop_body", "info_fallback_full_sync_body", "sfp_auto_sync_off")) {
            val text = Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(strings)?.groupValues?.get(1)
            assertNotNull(key, text)
            assertTrue("$key: $text", text!!.contains("Android\\'s automatic sync is"))
        }
    }

    @Test
    fun `the Remove older events text promises removal at the next sync, not at once`() {
        // With Android's automatic sync off, the dialog's Save asks for no sync, so nothing goes before a sync the user
        // starts (owner, 2026-11-22). Later events still go once they are as old, in the dialog's own words.
        val strings = File("src/main/res/values/strings.xml").readText()
        val text = Regex("<string name=\"info_trim_old_events_body\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(strings)?.groupValues?.get(1)
        assertNotNull(text)
        assertTrue(text, text!!.contains("are removed from this phone at the next sync, and later ones once they are as old."))
        assertTrue(text, !text.contains("now and at later syncs"))
    }
}
