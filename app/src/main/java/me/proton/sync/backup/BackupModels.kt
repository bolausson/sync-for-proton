package me.proton.sync.backup

import android.content.SharedPreferences
import me.proton.sync.backup.BackupSettings.PrefType.BOOLEAN
import me.proton.sync.backup.BackupSettings.PrefType.INT
import me.proton.sync.backup.BackupSettings.PrefType.LONG
import me.proton.sync.backup.BackupSettings.PrefType.STRING
import me.proton.sync.contacts.ContactNameMode
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Data model + (de)serialization for the `.sppbackup` portable backup file.
 *
 * The file is a UTF-8 JSON *envelope*. In **settings-only** mode the `payload`
 * member is a nested cleartext JSON object; in **full** mode it is a base64
 * string holding the AES-GCM ciphertext of that same object (see [BackupCrypto]).
 *
 * Nothing in this file touches the Keystore or the network: it shapes already-gathered
 * data, and [BackupSettings] writes only to the SharedPreferences it is handed. Account
 * gathering lives in `ExportManager`, account recreation in `ImportManager`/`AccountProvisioner`.
 */
object BackupFormat {
    /** Envelope discriminator — refuse to import anything else. */
    const val FORMAT = "sync-for-proton-backup"

    /** Bump only on a breaking payload-shape change; importer refuses unknown versions. */
    const val VERSION = 1

    /** User-facing file extension. */
    const val EXTENSION = "sppbackup"

    const val MIME = "application/octet-stream"
}

/**
 * One Proton account, fully self-contained so device B can rebuild it with **no
 * re-login**. [mailboxPassword] and [encPrivateKey] together can decrypt user
 * data, so a payload carrying any account MUST be passphrase-encrypted.
 *
 * [accessToken] is deliberately absent — it is short-lived; the importer obtains
 * a fresh one from [refreshToken].
 */
data class BackupAccount(
    val displayName: String,
    val protonUsername: String,
    val uid: String,
    val refreshToken: String,
    val keySalt: String?,
    val encPrivateKey: String?,
    val mailboxPassword: String?,
    val passwordMode: Int,
    val addressBookName: String,
    /**
     * Whether each sync type was switched on when the backup was taken.
     *
     * Null means an older backup that predates these fields, and only then does the importer fall
     * back to guessing. They are not SharedPreferences - the switches are
     * ContentResolver.setSyncAutomatically, framework state the settings allowlist cannot see -
     * which is why a "settings backup" restored everything about an account except whether it
     * actually synced.
     */
    val syncContacts: Boolean? = null,
    val syncCalendar: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("display_name", displayName)
        put("proton_username", protonUsername)
        put("uid", uid)
        put("refresh_token", refreshToken)
        put("key_salt", keySalt ?: JSONObject.NULL)
        put("enc_private_key", encPrivateKey ?: JSONObject.NULL)
        put("mailbox_password", mailboxPassword ?: JSONObject.NULL)
        put("password_mode", passwordMode)
        put("address_book_name", addressBookName)
        if (syncContacts != null) put("sync_contacts", syncContacts)
        if (syncCalendar != null) put("sync_calendar", syncCalendar)
    }

    companion object {
        fun fromJson(o: JSONObject) = BackupAccount(
            displayName = o.getString("display_name"),
            protonUsername = o.optString("proton_username", o.getString("display_name")),
            uid = o.optString("uid", ""),
            refreshToken = o.optString("refresh_token", ""),
            keySalt = o.optNullableString("key_salt"),
            encPrivateKey = o.optNullableString("enc_private_key"),
            mailboxPassword = o.optNullableString("mailbox_password"),
            passwordMode = o.optInt("password_mode", 1),
            syncContacts = if (o.has("sync_contacts")) o.getBoolean("sync_contacts") else null,
            syncCalendar = if (o.has("sync_calendar")) o.getBoolean("sync_calendar") else null,
            addressBookName = o.optString("address_book_name", o.getString("display_name"))
        )
    }
}

/**
 * The decrypted (or cleartext) inner payload: the settings map plus, in full
 * mode, the account list (empty in settings-only mode).
 *
 * [settings] preserves SharedPreferences value types (String / Boolean) so a
 * round-trip restores `ListPreference`/`EditTextPreference` strings and switch
 * booleans exactly as they were.
 *
 * [subscriptionId] is the direct version's Paddle subscription id (`sub_…`), the key that activates
 * sync. Written only into password-protected backups and read back only from them; the Play version
 * never writes one (its subscription belongs to the Google account). An optional member, so older
 * versions ignore it and the format version stays the same.
 */
data class BackupPayload(
    val settings: Map<String, Any?>,
    val accounts: List<BackupAccount>,
    val subscriptionId: String? = null,
) {
    fun toJson(): JSONObject {
        val settingsJson = JSONObject()
        for ((k, v) in settings) {
            when (v) {
                null -> { /* skip nulls — absence == default */ }
                is Boolean -> settingsJson.put(k, v)
                is Int -> settingsJson.put(k, v)
                is Long -> settingsJson.put(k, v)
                is Float -> settingsJson.put(k, v.toDouble())
                else -> settingsJson.put(k, v.toString())
            }
        }
        val accountsJson = JSONArray()
        accounts.forEach { accountsJson.put(it.toJson()) }
        return JSONObject().apply {
            put("settings", settingsJson)
            put("accounts", accountsJson)
            if (subscriptionId != null) {
                put("subscription", JSONObject().put("paddle_subscription_id", subscriptionId))
            }
        }
    }

    companion object {
        fun fromJson(o: JSONObject): BackupPayload {
            val settings = mutableMapOf<String, Any?>()
            o.optJSONObject("settings")?.let { s ->
                for (key in s.keys()) {
                    // org.json hands back Boolean / Integer / Long / Double / String.
                    settings[key] = s.get(key)
                }
            }
            val accounts = mutableListOf<BackupAccount>()
            o.optJSONArray("accounts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    accounts.add(BackupAccount.fromJson(arr.getJSONObject(i)))
                }
            }
            val subscriptionId = o.optJSONObject("subscription")
                ?.optNullableString("paddle_subscription_id")?.trim()?.takeIf { it.isNotEmpty() }
            return BackupPayload(settings, accounts, subscriptionId)
        }
    }
}

/**
 * Allowlist of SharedPreferences keys that belong in a backup, each with the type the app stores it as.
 *
 * Allowlist (not denylist) on purpose: a future new preference cannot silently leak into a backup, it
 * has to be added here deliberately, and with its type. In particular this keeps flavor-specific
 * entitlement (`subscription_*`, `paddle_subscription_id`, `license_first_failed_ms`) and all transient
 * caches out.
 *
 * The type is the one every writer puts and every reader gets. Android's getters cast, so a value of
 * another type makes its reader throw ClassCastException, and Application.onCreate reads several of these
 * keys (the logging and time-zone switches, display_name_user_chosen_<account>), where a throw stops
 * every start of the app until its data is cleared. A settings backup without a password is plain JSON a
 * user can edit, so a restore writes each value as its key's type or not at all ([importValue]), and the
 * app puts a wrong-typed value back at every start ([repairStoredTypes]) for installs that restored an
 * edited backup with an older version. A value that cannot be read is dropped, so its readers' default
 * applies, except for the two switches whose default is the harmful reading ([UNREADABLE_AS]). No
 * exportable key is an Int or a Long: the numbers (intervals, day counts) are stored as text, as the list
 * pickers have always written them.
 *
 * The direct version's subscription id does travel, but not as a setting: it is its own member of
 * [BackupPayload], only in password-protected backups, and on import it goes through
 * SubscriptionManager.restoreFromBackup (validated at once, never replacing a key this install
 * already has) instead of being written into preferences. The cached state beside it is rebuilt by
 * that check.
 */
object BackupSettings {

    /** How a setting is stored: the put a restore uses for it and the getter every reader uses. */
    enum class PrefType { BOOLEAN, INT, LONG, STRING }

    /**
     * Global (account-independent) keys copied verbatim, with their types. The single source of truth for
     * what a backup carries and how it is written back, together with [PREFIXES].
     */
    internal val EXACT_KEYS: Map<String, PrefType> = mapOf(
        "last_sync_date_format" to STRING,       // SyncPreferences.LAST_SYNC_DATE_FORMAT_KEY
        "verbose_logging_enabled" to BOOLEAN,    // SyncPreferences.KEY_VERBOSE_LOGGING
        // Its sibling, missed when verbose logging was added. Both are switches on the same screen.
        "file_logging_enabled" to BOOLEAN,
        // The data-backup schedule and shape. Everything the user chose on that screen, and
        // nothing that is device-bound or historical: the folder URI is a SAF grant this device
        // holds, the passphrase is sealed to this device's Keystore, and last-run/result/total are
        // a log of what happened rather than a setting. The interval and the retention count are
        // numbers stored as text.
        "data_backup_enabled" to BOOLEAN,
        "data_backup_interval_days" to STRING,
        "data_backup_include_calendars" to BOOLEAN,
        "data_backup_include_contacts" to BOOLEAN,
        "data_backup_encryption_enabled" to BOOLEAN,
        "data_backup_retention_count" to STRING,
        "data_backup_reminder_opted_out" to BOOLEAN,
        // The chosen app language, stored by the settings screen when the user picks one.
        "app_language_tag" to STRING,
        // "Update time zones online" (SyncPreferences.KEY_TIMEZONE_UPDATES), a device-wide switch on
        // the same card as the two logging switches. A choice, not device-bound and not secret.
        // ImportManager applies it at once, as it does the language.
        "online_timezone_updates_enabled" to BOOLEAN,
    )

    /**
     * Per-account key prefixes, with their types. Each builder in SyncPreferences appends the
     * account name, so a prefix match captures every account's copy. None of these prefixes is a
     * prefix of an excluded key. Ordering is irrelevant: where two match one key, the longer one
     * decides its type ([typesFor]).
     */
    internal val PREFIXES: List<Pair<String, PrefType>> = listOf(
        "sync_interval_" to STRING,                // contacts/calendar + legacy; no longer read or written
        "include_external_calendars_" to BOOLEAN,
        "notify_sync_failure_" to BOOLEAN,          // _contacts_<account> and _calendar_<account>
        "notify_sync_in_progress_" to BOOLEAN,
        "display_name_user_chosen_" to BOOLEAN,     // read in Application.onCreate
        "display_name_recovery_offered_" to BOOLEAN,
        "send_calendar_invites_" to BOOLEAN,
        "send_calendar_updates_" to BOOLEAN,
        "send_calendar_cancellations_" to BOOLEAN,
        "invite_subject_include_title_" to BOOLEAN,
        "calendar_sync_mode_" to STRING,
        // Day counts and intervals in seconds: numbers, stored as text by the list pickers.
        "calendar_past_days_" to STRING,
        "calendar_future_days_" to STRING,
        "event_loop_interval_" to STRING,
        "fallback_contacts_interval_" to STRING,
        "fallback_contacts_enabled_" to BOOLEAN,
        "fallback_calendar_interval_" to STRING,
        "fallback_calendar_enabled_" to BOOLEAN,
        "poll_attendee_status_" to BOOLEAN,
        // Contact filter rules (a gson JSON string) and its re-evaluate flag. This allowlist gates
        // export AND import, so omitting these would silently drop a user's rules in both
        // directions on a restore.
        "contact_filter_rules_" to STRING,
        "contact_filter_dirty_" to BOOLEAN,
        "contact_name_mode_" to STRING,
        // Adds nothing to the allowlist (the prefix above already covers it), only a type: ContactNameMode's
        // re-apply flag, contact_name_mode_reapply_<account>, is a Boolean under the name mode's prefix.
        "contact_name_mode_reapply_" to BOOLEAN,
    )

    /**
     * Text settings whose readers know only a fixed set of values, by exact key or prefix. Only the name mode is
     * listed, for its re-apply flag: contact_name_mode_ also begins contact_name_mode_reapply_<x>, which is the
     * flag of the account <x> (read with getBoolean) unless an account is literally called "reapply_<x>", whose
     * name mode has the same key. Text there is that name mode only when it is a mode's name. Anything else
     * ("yes", 1) would be kept as text where the flag's reader throws, failing every contacts sync of <x>.
     * ContactNameMode.of reads text it does not know as ANDROID, so this changes nothing for the name mode.
     */
    internal val TEXT_CHOICES: Map<String, Set<String>> = mapOf(
        "contact_name_mode_" to ContactNameMode.entries.mapTo(HashSet()) { it.name },
    )

    /**
     * What a value that is there but cannot be read as its key's type stands for, by exact key or prefix. Listed
     * only where the readers' default is the harmful reading; every other such value is skipped by a restore and
     * removed by the repair, so its readers' default applies.
     *  - display_name_user_chosen_<account>: the app only ever writes true (at sign-in and in the rename
     *    dialog). Absent, the next start renames a bare account name the user chose to its email form
     *    (ProtonSyncApp.migrateParentAccountNames), the forced rename this flag exists to prevent.
     *  - data_backup_encryption_enabled: absent means off, and the next scheduled data backup would write
     *    every contact and event unencrypted into the chosen folder. On, a run without a stored password fails
     *    and writes nothing (BackupDataExporter), as every run did while the value was unreadable.
     * data_backup_enabled is not listed: turning scheduled exports on is no safer than leaving them off, and
     * with them off the backup reminder asks again (unless the user opted out of it).
     */
    internal val UNREADABLE_AS: Map<String, Any> = mapOf(
        "display_name_user_chosen_" to true,
        "data_backup_encryption_enabled" to true,
    )

    /** [PREFIXES], longest first, so a key's most specific declaration is found first. */
    private val PREFIXES_BY_LENGTH = PREFIXES.sortedByDescending { it.first.length }

    fun isExportable(key: String): Boolean =
        key in EXACT_KEYS || PREFIXES.any { key.startsWith(it.first) }

    /**
     * The declarations that apply to [key], the most specific first: the exact key, or each prefix it starts
     * with, longest first. Empty when the key is not exportable. One for every key except
     * contact_name_mode_reapply_<x> ([TEXT_CHOICES]).
     */
    private fun declarationsOf(key: String): List<Pair<String, PrefType>> {
        EXACT_KEYS[key]?.let { return listOf(key to it) }
        return PREFIXES_BY_LENGTH.filter { key.startsWith(it.first) }
    }

    /** The types [key] may be stored as, the most specific declaration first; empty when it is not exportable. */
    internal fun typesFor(key: String): List<PrefType> = declarationsOf(key).map { it.second }

    /**
     * [value] read as the first declaration of [key] it fits ([coerce], and [TEXT_CHOICES] for text), or null
     * when it fits none or the key is not exportable. So for contact_name_mode_reapply_<x>, "true" is the flag
     * and "NICKNAME" stays a name mode.
     */
    internal fun read(key: String, value: Any?): Any? {
        for ((declared, type) in declarationsOf(key)) {
            val read = coerce(value, type) ?: continue
            val choices = TEXT_CHOICES[declared]
            if (choices == null || read !is String || read in choices) return read
        }
        return null
    }

    /** What a value of [key] that cannot be read is written as ([UNREADABLE_AS]), or null when it is dropped. */
    internal fun unreadableAs(key: String): Any? =
        declarationsOf(key).firstOrNull()?.let { UNREADABLE_AS[it.first] }

    /** For the log: what [key] takes, such as "boolean", or "boolean or string (ANDROID, FAMILY, GIVEN, NICKNAME)". */
    internal fun describe(key: String): String = declarationsOf(key).joinToString(" or ") { (declared, type) ->
        type.name.lowercase() + (TEXT_CHOICES[declared]?.let { " (" + it.sorted().joinToString() + ")" } ?: "")
    }

    /**
     * What an import may write for [key]: [value] as the key's type, or null to leave the key alone. Null for a
     * key outside the allowlist. A value that cannot be read as the key's type (only an edited backup has one)
     * and a JSON null (org.json hands back JSONObject.NULL, which is not a Kotlin null and was once stored as the
     * string "null") are never stored as another type: they are skipped, or written as the key's safe value where
     * its readers' default would do harm ([UNREADABLE_AS]).
     */
    fun importValue(key: String, value: Any?): Any? = readImported(key, value) ?: unreadableAs(key)

    /** [value] read as [key]'s type, or null when it cannot be, a JSON null included. */
    private fun readImported(key: String, value: Any?): Any? =
        if (value == null || value == JSONObject.NULL) null else read(key, value)

    /**
     * [value] read as [type], or null when it cannot be. The rules a hand-edited value is held to:
     *  - BOOLEAN: a JSON boolean, or "true" or "false" in quotes (trimmed, any case). Never a number.
     *  - INT, LONG: a whole JSON number within the type's range, or such a number in quotes (" 7 ", "-3",
     *    "7.0"). Never a fraction, a Boolean, or text that is not a number.
     *  - STRING: text exactly as it is, and a JSON number as its text, as every import stored it before the
     *    types were declared. Never a JSON boolean: no text setting takes "true" or "false", and the calendar
     *    sync mode reads any text but "all" as a custom window, so its text would quietly narrow what syncs.
     *    Never an object or an array.
     */
    internal fun coerce(value: Any?, type: PrefType): Any? = when (type) {
        BOOLEAN -> when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
        INT -> wholeNumber(value)?.takeIf { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
        LONG -> wholeNumber(value)
        STRING -> when (value) {
            is String -> value
            is Number -> value.toString()
            else -> null
        }
    }

    /** [value] as a Long when it is a whole number Long can hold, given as a JSON number or as text. */
    private fun wholeNumber(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        // org.json's own types for numbers outside Long's range or with a fraction (the reference
        // implementation, not Android's, which hands back a Double for both).
        is BigInteger -> if (value.bitLength() <= 63) value.toLong() else null
        is BigDecimal -> wholeDecimal(value)
        is Double -> wholeDouble(value)
        is Float -> wholeDouble(value.toDouble())
        is String -> value.trim().let { text ->
            // Length-capped before BigDecimal sees it: it is only a fallback for "7.0" or "1e3".
            text.toLongOrNull() ?: text.takeIf { it.length <= 40 }?.toBigDecimalOrNull()?.let { wholeDecimal(it) }
        }
        else -> null
    }

    private fun wholeDouble(d: Double): Long? =
        // 2^63 is the first double past Long.MAX_VALUE; -2^63 is Long.MIN_VALUE exactly.
        if (d.isFinite() && d == Math.rint(d) && d >= -9.223372036854775808E18 && d < 9.223372036854775808E18) {
            d.toLong()
        } else {
            null
        }

    private fun wholeDecimal(d: BigDecimal): Long? {
        if (d.signum() == 0) return 0L
        val stripped = d.stripTrailingZeros()
        // A fraction is not whole. And the digits are counted before anything is expanded: 1e999999999 is a
        // valid BigDecimal that no Long holds, and turning it into an integer would build a billion digits.
        if (stripped.scale() > 0 || stripped.precision() - stripped.scale() > 19) return null
        return try {
            stripped.longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** Writes [value], as [coerce] returns it (Boolean, Int, Long or String), with the matching put. */
    private fun put(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
            else -> throw IllegalArgumentException("$key: a ${value.javaClass.simpleName} is not a setting type")
        }
    }

    /**
     * What [writeImported] did: how many settings it wrote, which keys it skipped for their type, and which it
     * wrote as their safe value because their own could not be read ([UNREADABLE_AS]). Key names only.
     */
    internal class ImportWrite(val written: Int, val skipped: List<String>, val assumed: List<String>)

    /**
     * Puts every allowlisted setting of a restored backup into [editor], each with the put its type needs
     * ([importValue]). A value that cannot be read as its key's type is skipped and its key returned (for
     * the log, by name only), or written as the key's safe value and its key returned as assumed. Keys
     * outside the allowlist, and JSON nulls of keys without a safe value, are skipped silently, as always.
     * Does not apply [editor].
     */
    internal fun writeImported(editor: SharedPreferences.Editor, settings: Map<String, Any?>): ImportWrite {
        var written = 0
        val skipped = ArrayList<String>()
        val assumed = ArrayList<String>()
        for ((key, raw) in settings) {
            if (!isExportable(key)) continue
            val read = readImported(key, raw)
            val value = read ?: unreadableAs(key)
            if (value != null) {
                put(editor, key, value)
                written++
                if (read == null) assumed.add(key)
            } else if (raw != null && raw != JSONObject.NULL) {
                skipped.add(key)
            }
        }
        return ImportWrite(written, skipped, assumed)
    }

    /**
     * What [repairStoredTypes] changed, by key name only, never a value: read as their type ([converted]),
     * unreadable and set to their safe value ([assumed], [UNREADABLE_AS]), or unreadable and removed, so
     * their readers' default applies ([removed]).
     */
    class TypeRepair(val converted: List<String>, val assumed: List<String>, val removed: List<String>) {
        val isEmpty: Boolean get() = converted.isEmpty() && assumed.isEmpty() && removed.isEmpty()
    }

    /**
     * Puts every stored exportable setting that an older restore can have left with the wrong type back into
     * its type, read exactly as a restore reads it ([importValue]): converted when it can be ("true" for a
     * switch), set to its safe value where the readers' default would do harm ([UNREADABLE_AS]), and removed
     * otherwise, so its readers' default applies. Nothing is written when nothing is wrong, so it is cheap
     * enough for every start, and a second run finds nothing.
     *
     * Every restore before this one stored a JSON boolean as a Boolean and any other value as a String,
     * whatever the key: "true" in quotes for a switch then made every getBoolean reader of it throw (and true
     * without quotes for a text setting every getString reader), and display_name_user_chosen_<account> is
     * read in Application.onCreate. So only Booleans and Strings are looked at. An Int, Long, Float or set
     * under an exportable key was put there by the app itself, never by a restore, and is left alone: a key
     * the app builds under an exportable prefix with another type than the prefix declares is for the guard
     * in BackupSettingsTypeMapTest to stop, not for this to rewrite at every start.
     */
    fun repairStoredTypes(prefs: SharedPreferences): TypeRepair {
        // Read in full before anything is written: a key mapped to null is removed.
        val fixes = LinkedHashMap<String, Any?>()
        val assumed = ArrayList<String>()
        for ((key, stored) in prefs.all) {
            // The type check first: it allocates nothing, and most stored keys are not settings at all.
            if ((stored !is Boolean && stored !is String) || !isExportable(key)) continue
            val read = read(key, stored)
            if (read == stored) continue
            if (read != null) {
                fixes[key] = read
            } else {
                val safe = unreadableAs(key)
                fixes[key] = safe
                if (safe != null) assumed.add(key)
            }
        }
        if (fixes.isEmpty()) return TypeRepair(emptyList(), emptyList(), emptyList())
        val editor = prefs.edit()
        for ((key, value) in fixes) {
            if (value == null) editor.remove(key) else put(editor, key, value)
        }
        editor.apply()
        return TypeRepair(
            converted = fixes.filter { it.value != null && it.key !in assumed }.keys.sorted(),
            assumed = assumed.sorted(),
            removed = fixes.filterValues { it == null }.keys.sorted(),
        )
    }
}

/** org.json has no nullable-string accessor; `null`/`JSONObject.NULL` → Kotlin null. */
internal fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name, null)
