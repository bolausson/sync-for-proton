package me.proton.sync.backup

import me.proton.sync.SyncPreferences
import me.proton.sync.backup.BackupSettings.PrefType
import me.proton.sync.backup.BackupSettings.PrefType.BOOLEAN
import me.proton.sync.backup.BackupSettings.PrefType.STRING
import me.proton.sync.contacts.ContactNameMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The type of every setting a backup carries (BackupSettings.EXACT_KEYS and PREFIXES), against the keys the
 * app really builds. Android's getters cast, so a key restored as another type than its readers ask for
 * throws in every one of them; several are read in Application.onCreate.
 */
class BackupSettingsTypeMapTest {

    private val account = "me@example.com"

    /**
     * Every exportable key the app builds, through the app's own key builders, with the type its writers put
     * and its readers get. Each was checked by hand against every put and get call site (putBoolean and
     * getBoolean for the switches; putString and getString, getStringOrDefault or getLongFromStringPref for
     * the rest: numbers are stored as text). A new exact key or prefix in BackupSettings fails
     * `every declaration is checked against a key the app builds` until its readers have been checked the
     * same way and it is added here. A new key the app builds under an existing prefix, which would take that
     * prefix's type unchecked, fails `no key the app builds extends an exportable prefix without its own
     * declaration`.
     */
    private val built: Map<String, PrefType> = linkedMapOf(
        SyncPreferences.LAST_SYNC_DATE_FORMAT_KEY to STRING,
        SyncPreferences.KEY_VERBOSE_LOGGING to BOOLEAN,
        SyncPreferences.KEY_FILE_LOGGING to BOOLEAN,
        SyncPreferences.KEY_BACKUP_ENABLED to BOOLEAN,
        SyncPreferences.KEY_BACKUP_INTERVAL_DAYS to STRING,
        SyncPreferences.KEY_BACKUP_INCLUDE_CALENDARS to BOOLEAN,
        SyncPreferences.KEY_BACKUP_INCLUDE_CONTACTS to BOOLEAN,
        SyncPreferences.KEY_BACKUP_ENCRYPTION_ENABLED to BOOLEAN,
        SyncPreferences.KEY_BACKUP_RETENTION to STRING,
        SyncPreferences.KEY_BACKUP_REMINDER_OPTED_OUT to BOOLEAN,
        SyncPreferences.KEY_APP_LANGUAGE_TAG to STRING,
        SyncPreferences.KEY_TIMEZONE_UPDATES to BOOLEAN,
        SyncPreferences.syncIntervalKey(account, "contacts") to STRING,
        SyncPreferences.syncIntervalKey(account, "calendar") to STRING,
        SyncPreferences.legacySyncIntervalKey(account) to STRING,
        SyncPreferences.includeExternalCalendarsKey(account) to BOOLEAN,
        SyncPreferences.notifySyncFailureKey("contacts", account) to BOOLEAN,
        SyncPreferences.notifySyncFailureKey("calendar", account) to BOOLEAN,
        SyncPreferences.notifySyncInProgressKey(account) to BOOLEAN,
        SyncPreferences.displayNameUserChosenKey(account) to BOOLEAN,
        SyncPreferences.displayNameRecoveryOfferedKey(account) to BOOLEAN,
        SyncPreferences.sendCalendarInvitesKey(account) to BOOLEAN,
        SyncPreferences.sendCalendarUpdatesKey(account) to BOOLEAN,
        SyncPreferences.sendCalendarCancellationsKey(account) to BOOLEAN,
        SyncPreferences.inviteSubjectIncludeTitleKey(account) to BOOLEAN,
        SyncPreferences.calendarSyncModeKey(account) to STRING,
        SyncPreferences.calendarPastDaysKey(account) to STRING,
        SyncPreferences.calendarFutureDaysKey(account) to STRING,
        SyncPreferences.eventLoopIntervalKey(account) to STRING,
        SyncPreferences.fallbackContactsIntervalKey(account) to STRING,
        SyncPreferences.fallbackContactsEnabledKey(account) to BOOLEAN,
        SyncPreferences.fallbackCalendarIntervalKey(account) to STRING,
        SyncPreferences.fallbackCalendarEnabledKey(account) to BOOLEAN,
        SyncPreferences.pollAttendeeStatusKey(account) to BOOLEAN,
        SyncPreferences.contactFilterRulesKey(account) to STRING,
        SyncPreferences.contactFilterDirtyKey(account) to BOOLEAN,
        ContactNameMode.key(account) to STRING,
        ContactNameMode.reapplyKey(account) to BOOLEAN,
    )

    /** The declaration that decides [key]'s type: the exact key, or the longest prefix it starts with. */
    private fun declarationOf(key: String): String? =
        if (key in BackupSettings.EXACT_KEYS) key
        else BackupSettings.PREFIXES.map { it.first }.filter { key.startsWith(it) }.maxByOrNull { it.length }

    @Test
    fun `every key the app builds is exportable, with the type its writers and readers use`() {
        for ((key, type) in built) {
            assertTrue("$key is exportable", BackupSettings.isExportable(key))
            assertEquals(key, type, BackupSettings.typesFor(key).first())
        }
    }

    @Test
    fun `every declaration is checked against a key the app builds`() {
        for ((key, type) in BackupSettings.EXACT_KEYS) {
            assertEquals("exact key $key: add it to `built` once its readers are checked", type, built[key])
        }
        for ((prefix, type) in BackupSettings.PREFIXES) {
            val covered = built.any { (key, t) -> declarationOf(key) == prefix && t == type }
            assertTrue("prefix $prefix ($type): add a key built with it to `built` once its readers are checked", covered)
        }
    }

    @Test
    fun `every exact key and prefix has a type, and a key has one exactly when it is exportable`() {
        for (key in BackupSettings.EXACT_KEYS.keys) assertTrue(key, BackupSettings.typesFor(key).isNotEmpty())
        for ((prefix, _) in BackupSettings.PREFIXES) {
            assertTrue(prefix, BackupSettings.typesFor(prefix + account).isNotEmpty())
        }
        val samples = built.keys + notExported + listOf("", "sync_interval", "contact_name_mode", "x_" + account)
        for (key in samples) {
            assertEquals(key, BackupSettings.isExportable(key), BackupSettings.typesFor(key).isNotEmpty())
        }
    }

    @Test
    fun `one type per key, except the name mode's re-apply flag`() {
        for (key in built.keys) {
            val expected = if (key == ContactNameMode.reapplyKey(account)) 2 else 1
            assertEquals(key, expected, BackupSettings.typesFor(key).size)
        }
        // An account literally called "reapply_<x>" keeps its name mode under the flag's key of account <x>.
        val shared = ContactNameMode.key("reapply_$account")
        assertEquals(ContactNameMode.reapplyKey(account), shared)
        assertEquals(listOf(BOOLEAN, STRING), BackupSettings.typesFor(shared))
        assertEquals(true, BackupSettings.importValue(shared, "true"))
        assertEquals(true, BackupSettings.importValue(shared, true))
        assertEquals("NICKNAME", BackupSettings.importValue(shared, "NICKNAME"))
    }

    /** Device-bound, secret, historical or entitlement state: never in a backup, never repaired. */
    private val notExported = listOf(
        SyncPreferences.KEY_SUBSCRIPTION_STATE,
        SyncPreferences.KEY_SUBSCRIPTION_EXPIRY,
        SyncPreferences.KEY_SUBSCRIPTION_PRODUCT,
        SyncPreferences.KEY_SUBSCRIPTION_LAST_CHECK,
        SyncPreferences.KEY_SUBSCRIPTION_PURCHASE_TOKEN,
        SyncPreferences.KEY_PADDLE_SUB_ID,
        SyncPreferences.KEY_LICENSE_FIRST_FAILED,
        SyncPreferences.KEY_SUBSCRIPTION_PAYMENT_FAILED,
        SyncPreferences.KEY_SUBSCRIPTION_PAUSED,
        SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE,
        SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE_AT,
        SyncPreferences.KEY_LICENSE_CONFIRMED,
        SyncPreferences.KEY_LOG_FOLDER_URI,
        SyncPreferences.KEY_TZ_OUTDATED_NOTICE_ANSWER,
        SyncPreferences.KEY_TZ_OUTDATED_NOTICE_LAST_SHOWN_MS,
        SyncPreferences.KEY_TZ_RETIME_REQUESTED_IDENTITY,
        SyncPreferences.KEY_TZURL_SEEN_VERSION,
        SyncPreferences.KEY_DEBUG_TZDATA_VERSION_OVERRIDE,
        SyncPreferences.KEY_BACKUP_FOLDER_URI,
        SyncPreferences.KEY_BACKUP_PASSPHRASE_ENC,
        SyncPreferences.KEY_BACKUP_LAST_RUN_MS,
        SyncPreferences.KEY_BACKUP_LAST_RESULT,
        SyncPreferences.KEY_BACKUP_LAST_TOTAL,
        SyncPreferences.KEY_BACKUP_REMINDER_SHOWN,
        SyncPreferences.KEY_BACKUP_REMINDER_LAST_SHOWN_MS,
        SyncPreferences.lastSyncKey(account, "contacts"),
        SyncPreferences.lastSyncFailedKey(account, "calendar"),
        SyncPreferences.lastSyncErrorMsgKey("calendar", account),
        SyncPreferences.lastSyncRangeKey(account, "cal1"),
        SyncPreferences.timeZoneRetimeKey(account),
        SyncPreferences.eventLoopIdKey(account),
        SyncPreferences.calendarSettingsDirtyKey(account),
        SyncPreferences.contactFilterKeptLocalKey(account),
        SyncPreferences.contactCreateStrikesKey(account),
        SyncPreferences.contactCreateRefusedKey(account),
        SyncPreferences.contactCreateReasonsKey(account),
        SyncPreferences.contactDownloadTooLargeKey(account),
        SyncPreferences.eventAttendeesKey("evt1"),
        SyncPreferences.syncPausedUntilKey(account),
        "upload_reject_count_42",
        "invite_retry_count_evt1",
    )

    @Test
    fun `keys that must never travel have no type and are never imported`() {
        for (key in notExported) {
            assertFalse(key, BackupSettings.isExportable(key))
            assertTrue(key, BackupSettings.typesFor(key).isEmpty())
            assertNull(key, BackupSettings.importValue(key, "1"))
            assertNull(key, BackupSettings.importValue(key, true))
            assertNull(key, BackupSettings.importValue(key, JSONObject.NULL))
        }
    }

    @Test
    fun `every safe value and every set of text choices belongs to a declaration of its type`() {
        val declarations = BackupSettings.EXACT_KEYS + BackupSettings.PREFIXES.toMap()
        for ((declared, value) in BackupSettings.UNREADABLE_AS) {
            val type = declarations[declared]
            assertTrue("$declared is an exact key or a prefix", type != null)
            assertEquals(declared, value, BackupSettings.coerce(value, type!!))
        }
        for ((declared, choices) in BackupSettings.TEXT_CHOICES) {
            assertEquals(declared, STRING, declarations[declared])
            assertTrue(declared, choices.isNotEmpty())
        }
        assertEquals(ContactNameMode.entries.map { it.name }.toSet(), BackupSettings.TEXT_CHOICES["contact_name_mode_"])
    }

    // ---- Keys the app builds under an exportable prefix ----

    /**
     * The guard the hand-written [built] map cannot be. A key that starts with an exportable prefix travels in
     * every backup and is read by a restore, and by the repair at every start, as that prefix's type. So a key
     * the app builds that only extends a prefix (as contact_name_mode_reapply_<account>, a Boolean, extends the
     * name mode's contact_name_mode_) must be declared as itself, with its own type and a key in [built], or be
     * named out of the prefix. Otherwise a Long stored under a switch's prefix would be read as an unreadable
     * switch, and a flag under a text prefix as an unreadable text.
     *
     * Reads every string in the app's sources, comments skipped, with the constants it names filled in, up to
     * its first template (and every android:key or app:key in their XML). It sees a key spelled out in one
     * string or built from constants; one put together with + from pieces is beyond it.
     */
    @Test
    fun `no key the app builds extends an exportable prefix without its own declaration`() {
        val files = listOf("src/main", "src/play", "src/direct", "src/debug")
            .flatMap { root -> java.io.File(root).walkTopDown().filter { it.isFile }.toList() }
        val code = files.filter { it.extension == "kt" || it.extension == "java" }.associateWith { it.readText() }
        assertTrue("the app's sources are where the test runs (${code.size} files)", code.size > 100)

        val global = constantsIn(code.values)
        val heads = HashMap<String, MutableList<String>>()
        for ((file, text) in code) for (head in keyHeads(text, global, constantsIn(listOf(text)))) {
            heads.getOrPut(head) { ArrayList() }.add(file.path)
        }
        for (file in files.filter { it.extension == "xml" }) {
            for (m in XML_KEY.findAll(file.readText())) heads.getOrPut(m.groupValues[1]) { ArrayList() }.add(file.path)
        }
        // The scan sees the key builders, one through a constant (ContactNameMode.REAPPLY_KEY), or proves nothing.
        assertTrue(heads.keys.toString(), SyncPreferences.displayNameUserChosenKey("") in heads)
        assertTrue(heads.keys.toString(), ContactNameMode.reapplyKey("") in heads)

        val undeclared = heads.filterKeys { undeclaredUnderPrefix(it) }.map { (head, where) -> "$head in ${where.distinct()}" }
        assertEquals("keys under an exportable prefix with no declaration of their own", emptyList<String>(), undeclared)
    }

    @Test
    fun `the guard finds a new key under a prefix, spelled out or through a constant, and nothing in a comment`() {
        val source = "object Keys {\n" +
            "    private const val SNOOZED = \"notify_sync_failure_snoozed\"\n" +
            "    fun changed(a: String) = \"calendar_sync_mode_changed_\$a\"\n" +
            "    fun until(a: String) = \"\${SNOOZED}_until_\$a\"\n" +
            "    fun mode(a: String) = \"calendar_sync_mode_\$a\" // \"calendar_sync_mode_in_a_comment\"\n" +
            "    /* \"poll_attendee_status_in_a_block_comment\" */\n" +
            "    val quote = '\"'\n" +
            "    fun flag(a: String) = \"\${if (a.isEmpty()) \"x\" else \"y\"}_\$a\"\n" +
            "}\n"
        val heads = keyHeads(source, emptyMap(), constantsIn(listOf(source)))

        assertEquals(
            setOf("notify_sync_failure_snoozed", "calendar_sync_mode_changed_", "notify_sync_failure_snoozed_until_"),
            heads.filter { undeclaredUnderPrefix(it) }.toSet()
        )
        assertTrue("calendar_sync_mode_" in heads)
        assertFalse(heads.any { "comment" in it })
    }

    /** Whether [head] starts with an exportable prefix without being one, or the stem of one ("<prefix>" + "_"). */
    private fun undeclaredUnderPrefix(head: String): Boolean {
        val prefixes = BackupSettings.PREFIXES.map { it.first }
        return prefixes.any { head.startsWith(it) } && head !in prefixes && head + "_" !in prefixes
    }

    /** `val NAME = "text"` in [sources]: every value each upper-case name is given. */
    private fun constantsIn(sources: Collection<String>): Map<String, Set<String>> {
        val found = HashMap<String, MutableSet<String>>()
        for (text in sources) for (m in CONSTANT.findAll(text)) found.getOrPut(m.groupValues[1]) { HashSet() }.add(m.groupValues[2])
        return found
    }

    /**
     * The fixed start of every string in [source]: its text up to the first template it cannot fill in. A
     * `$NAME` or `${Owner.NAME}` is filled in from [local] (the same file's constants), else from [global].
     */
    private fun keyHeads(source: String, global: Map<String, Set<String>>, local: Map<String, Set<String>>): Set<String> {
        val heads = HashSet<String>()
        for (literal in stringLiterals(source)) {
            var texts = listOf("")
            var at = 0
            for (m in REFERENCE.findAll(literal)) {
                val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
                val values = local[name] ?: global[name] ?: continue
                val before = literal.substring(at, m.range.first)
                texts = texts.flatMap { t -> values.map { t + before + it } }
                at = m.range.last + 1
            }
            texts.mapTo(heads) { (it + literal.substring(at)).substringBefore('$') }
        }
        return heads
    }

    /** The text of every string literal in Kotlin or Java [source], comments and char literals skipped. */
    private fun stringLiterals(source: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < source.length) {
            i = when {
                source.startsWith("//", i) -> source.indexOf('\n', i).let { if (it < 0) source.length else it }
                source.startsWith("/*", i) -> source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it }
                    out.add(source.substring(i + 3, end))
                    end + 3
                }
                source[i] == '"' -> readString(source, i, out)
                // '"' and '\'' must not open or close a string.
                source[i] == '\'' && i + 2 < source.length ->
                    source.indexOf('\'', if (source[i + 1] == '\\') i + 3 else i + 2).let { if (it < 0) source.length else it + 1 }
                else -> i + 1
            }
        }
        return out
    }

    /** Reads the one-line string opening at [start] into [out], and any inside its `${...}`; the index after it. */
    private fun readString(source: String, start: Int, out: MutableList<String>): Int {
        var i = start + 1
        while (i < source.length && source[i] != '"' && source[i] != '\n') {
            i = when {
                source[i] == '\\' -> i + 2
                source.startsWith("\${", i) -> {
                    // A template expression: braces counted, strings inside it read too (they hold keys as well).
                    var depth = 1
                    var j = i + 2
                    while (j < source.length && depth > 0 && source[j] != '\n') {
                        when (source[j]) {
                            '{' -> { depth++; j++ }
                            '}' -> { depth--; j++ }
                            '"' -> j = readString(source, j, out)
                            else -> j++
                        }
                    }
                    j
                }
                else -> i + 1
            }
        }
        out.add(source.substring(start + 1, minOf(i, source.length)))
        return i + 1
    }

    private companion object {
        val CONSTANT = Regex("\\bval\\s+([A-Z][A-Z0-9_]*)\\s*(?::\\s*String)?\\s*=\\s*\"([^\"\\\\\$]*)\"")
        val REFERENCE = Regex("\\\$\\{(?:[A-Za-z_][A-Za-z0-9_]*\\.)*([A-Z][A-Z0-9_]*)\\}|\\\$([A-Z][A-Z0-9_]*)")
        val XML_KEY = Regex("(?:android|app):key\\s*=\\s*\"([^\"]*)\"")
    }
}
