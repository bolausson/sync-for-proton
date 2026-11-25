package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The reason a failed sync shows (SyncNotificationHelper.friendlyMessage) in the failure notification and in the account
 * card's failure dialog. Four of its texts say the sync will retry. While Android retries no sync the user started, with
 * its automatic sync off or the account on Manual only (SyncScheduling.userSyncRetries says no), they give way to a
 * variant that asks for a tap on Sync (owner, 2026-11-22). The choice is a pure function; which account's state decides
 * it, where it is made and the English texts are read from the app's sources, on the code with its comments taken out,
 * as BackgroundSyncRuleTest does. Made-up hosts only.
 */
class FailureMessageTest {

    private companion object {
        /** U+2014 and U+2013, written by code point so this source holds neither character. */
        val EM_DASH = Char(0x2014)
        val EN_DASH = Char(0x2013)

        const val HELPER = "main/java/me/proton/sync/SyncNotificationHelper.kt"
        const val SCHEDULING = "main/java/me/proton/sync/SyncScheduling.kt"
        const val UTILS = "main/java/me/proton/sync/SyncUtils.kt"
        const val ACTIVITY = "main/java/me/proton/sync/settings/SyncSettingsActivity.kt"
        const val BACKUP = "main/java/me/proton/sync/backup/BackupScheduleWorker.kt"
        const val STRINGS = "main/res/values/strings.xml"

        /** Raw errors as the adapters record them, each with the text that promises a retry and its variant. */
        val RETRYING = listOf(
            Triple("Unable to resolve host \"api.example.test\": No address associated with hostname", R.string.error_no_internet, R.string.error_no_internet_tap_sync),
            Triple("java.net.UnknownHostException: api.example.test", R.string.error_no_internet, R.string.error_no_internet_tap_sync),
            Triple("HTTP 429 Too Many Requests", R.string.error_too_many_requests, R.string.error_too_many_requests_tap_sync),
            Triple("HTTP 503 Service Unavailable", R.string.error_server_error, R.string.error_server_error_tap_sync),
            Triple("java.net.SocketException: Connection reset", R.string.error_connection_interrupted, R.string.error_connection_interrupted_tap_sync),
            Triple("Broken pipe", R.string.error_connection_interrupted, R.string.error_connection_interrupted_tap_sync),
        )

        /** The four keys that promise a retry, each with its variant's key and today's English text, which stays. */
        val KEYS = listOf(
            Triple("error_no_internet", "error_no_internet_tap_sync", "No internet connection. Sync will retry automatically."),
            Triple("error_too_many_requests", "error_too_many_requests_tap_sync", "Too many requests. Sync will retry later."),
            Triple("error_server_error", "error_server_error_tap_sync", "Proton server error. Sync will retry automatically."),
            Triple("error_connection_interrupted", "error_connection_interrupted_tap_sync", "Connection was interrupted. Sync will retry."),
        )
    }

    private fun source(path: String) = File("src/$path").readText().replace("\r\n", "\n")

    /** [text] without KDoc, block comment lines and line comments. */
    private fun code(text: String): String =
        text.lines()
            .filterNot { it.trim().let { t -> t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") } }
            .joinToString("\n") { it.substringBefore(" // ") }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The text of the function that starts with [signature], up to its closing brace at four spaces. */
    private fun body(src: String, signature: String): String {
        assertTrue("$signature not found", src.contains(signature))
        return src.substringAfter(signature).substringBefore("\n    }\n")
    }

    /** A declaration without braces (an expression body), from [signature] up to the next blank line. */
    private fun declaration(src: String, signature: String): String {
        assertTrue("$signature not found", src.contains(signature))
        return signature + src.substringAfter(signature).substringBefore("\n\n")
    }

    /** Every Kotlin file of the app's own sources, as the public snapshot exports them. */
    private fun kotlinSources(): List<File> =
        listOf("main", "direct", "play", "debug")
            .map { File("src/$it/java") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** The text of the string named [key] in [xml], or null. */
    private fun value(xml: String, key: String): String? =
        Regex("<string name=\"${Regex.escape(key)}\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    @Test
    fun `the four texts that promise a retry ask for a tap on Sync while nothing retries the sync`() {
        for ((raw, retrying, tap) in RETRYING) {
            assertEquals(raw, retrying, SyncNotificationHelper.friendlyMessageRes(raw, retries = true))
            assertEquals(raw, tap, SyncNotificationHelper.friendlyMessageRes(raw, retries = false))
        }
        assertEquals(4, RETRYING.map { it.third }.toSet().size)
        assertTrue(RETRYING.none { it.second == it.third })
    }

    @Test
    fun `every other text is the same whether or not the sync retries`() {
        val same = listOf(
            "java.net.SocketTimeoutException: timeout" to R.string.error_timeout,
            "HTTP 401 Unauthorized" to R.string.error_session_expired,
            "HTTP 422 Unprocessable Entity" to R.string.error_request_rejected,
            "javax.net.ssl.SSLHandshakeException: Chain validation failed" to R.string.error_ssl,
            // SyncUtils.savePostSyncResult's own fallback, chosen to match none of the words above.
            "Sync incomplete" to R.string.error_unexpected,
            "" to R.string.error_unexpected,
        )
        for ((raw, res) in same) {
            for (retries in listOf(true, false)) {
                assertEquals("$raw, retries=$retries", res, SyncNotificationHelper.friendlyMessageRes(raw, retries))
            }
        }
    }

    @Test
    fun `the text follows the retry rule, which retries only with automatic sync on and an interval`() {
        val raw = "HTTP 502 Bad Gateway"
        for (masterSyncOn in listOf(true, false)) {
            for (manualOnly in listOf(true, false)) {
                val retries = SyncScheduling.userSyncRetries(masterSyncOn = masterSyncOn, manualOnly = manualOnly)
                val expected = if (masterSyncOn && !manualOnly) R.string.error_server_error else R.string.error_server_error_tap_sync
                assertEquals("master=$masterSyncOn manualOnly=$manualOnly", expected, SyncNotificationHelper.friendlyMessageRes(raw, retries))
            }
        }
        // For one account as things stand: Android's switch now and the account's stored interval, as userSyncExtras
        // reads them for the retry its requests allow.
        val forAccount = squash(code(declaration(source(SCHEDULING), "internal fun userSyncRetriesFor(")))
        assertTrue(
            forAccount,
            forAccount.contains(
                "(context: Context, parentAccountName: String): Boolean = userSyncRetries( " +
                    "masterSyncOn = ContentResolver.getMasterSyncAutomatically(), manualOnly = isManualOnly(context, parentAccountName), )"
            ),
        )
    }

    @Test
    fun `the notification and the card's dialog choose by the failing account, and the backup keeps the texts that retry`() {
        val helper = source(HELPER)
        assertTrue(
            squash(code(helper)).contains(
                "fun friendlyMessage(context: Context, rawError: String, retries: Boolean): String = " +
                    "context.getString(friendlyMessageRes(rawError, retries))"
            )
        )
        // The failure notification, for the account whose sync failed: savePostSyncResult passes the parent account.
        val notify = squash(code(body(helper, "fun notifyFailure(")))
        assertTrue(notify, notify.contains("val friendly = friendlyMessage(context, errorMsg, SyncScheduling.userSyncRetriesFor(context, accountName))"))
        val post = squash(code(body(source(UTILS), "fun savePostSyncResult(")))
        assertTrue(post, post.contains("SyncNotificationHelper.notifyFailure(context, parentAccountName, syncType, errMsg)"))
        // The card's failure dialog, for the card's account, as it opens.
        val dialog = squash(code(body(source(ACTIVITY), "private fun showSyncFailureDialog(")))
        assertTrue(
            dialog,
            dialog.contains("val friendly = SyncNotificationHelper.friendlyMessage(this, rawError, SyncScheduling.userSyncRetriesFor(this, accountName))"),
        )
        // The scheduled backup follows no account's interval, and WorkManager retries a network failure of it.
        val backup = squash(code(source(BACKUP)))
        assertTrue(backup, backup.contains("val friendly = SyncNotificationHelper.friendlyMessage(ctx, e.message ?: \"\", retries = true)"))
        assertTrue(backup, backup.contains("if (e is java.io.IOException) Run.RETRY else Run.DONE"))
        // No other caller: a new one has to choose too, and gets a check of its own here.
        val callers = kotlinSources().filter { f ->
            code(f.readText()).lines().any { it.contains("friendlyMessage(") && !it.contains("fun friendlyMessage(") }
        }
        assertEquals(listOf("BackupScheduleWorker.kt", "SyncNotificationHelper.kt", "SyncSettingsActivity.kt"), callers.map { it.name }.sorted())
    }

    @Test
    fun `each variant keeps its text's first sentence, asks for a tap on Sync and has a translator note`() {
        val xml = source(STRINGS)
        for ((retrying, tap, today) in KEYS) {
            // Today's text stays where the sync does retry.
            assertEquals(retrying, today, value(xml, retrying))
            val text = value(xml, tap)
            assertNotNull("$tap is missing", text)
            assertEquals(tap, today.substringBefore(". ") + ". Tap Sync to try again.", text)
            assertFalse(tap, text!!.contains(EM_DASH) || text.contains(EN_DASH))
            assertEquals(tap, 1, Regex("<string name=\"${Regex.escape(tap)}\"").findAll(xml).count())
            // A translator note right above it, saying when it shows.
            val note = Regex("<!--((?:(?!-->).)*)-->\\s*<string name=\"${Regex.escape(tap)}\">", RegexOption.DOT_MATCHES_ALL).find(xml)
            assertNotNull("$tap has no translator note", note)
            assertTrue(tap, note!!.groupValues[1].contains("Manual only") || note.groupValues[1].contains("error_no_internet_tap_sync"))
        }
    }
}
