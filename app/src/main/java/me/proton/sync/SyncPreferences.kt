package me.proton.sync

import android.content.SharedPreferences

/**
 * Centralized preference key builders for sync adapter settings.
 *
 * All preference keys follow the pattern `"{type}_{accountName}"` where type
 * identifies the setting and accountName scopes it per-account. Centralizing
 * these eliminates 20+ hardcoded key strings scattered across adapters,
 * workers, settings UI, and notification helpers.
 *
 * **Critical:** These key formats must not change without migrating existing
 * user preferences — changing a key string silently resets the user's setting.
 */
object SyncPreferences {

    /** Key for the last successful sync timestamp (epoch millis). */
    fun lastSyncKey(accountName: String, syncType: String) =
        "last_${syncType}_sync_$accountName"

    /** Key for the last failed sync timestamp (epoch millis). */
    fun lastSyncFailedKey(accountName: String, syncType: String) =
        "last_${syncType}_sync_failed_$accountName"

    /** Key for the per-type sync interval (seconds as String, e.g. "3600"). */
    fun syncIntervalKey(accountName: String, syncType: String) =
        "sync_interval_${syncType}_$accountName"

    /** Key for the legacy (pre-split) sync interval before contacts/calendar were separated. */
    fun legacySyncIntervalKey(accountName: String) =
        "sync_interval_$accountName"

    /** Key for the "include external calendars" toggle. */
    fun includeExternalCalendarsKey(accountName: String) =
        "include_external_calendars_$accountName"

    /** Key for the per-type sync failure notification toggle. */
    fun notifySyncFailureKey(syncType: String, accountName: String) =
        "notify_sync_failure_${syncType}_$accountName"

    /** Key for the per-account "show ongoing notification while syncing" toggle (default: true). */
    fun notifySyncInProgressKey(accountName: String) =
        "notify_sync_in_progress_$accountName"

    /**
     * Boolean flag set to true when the user has explicitly chosen a display name for the
     * account — either at login (where the name dialog runs) or via the in-app rename
     * dialog. Used by the v1.3.97+ recovery prompt to avoid asking again after the user
     * has already picked a name (or been offered the prompt).
     */
    fun displayNameUserChosenKey(accountName: String) =
        "display_name_user_chosen_$accountName"

    /**
     * Boolean flag set to true once the v1.3.97+ recovery prompt has been shown for this
     * account. Independent of [displayNameUserChosenKey] so we can fire the prompt even
     * for legacy accounts that pre-date the user-chosen flag, and never re-fire after
     * dismissal.
     */
    fun displayNameRecoveryOfferedKey(accountName: String) =
        "display_name_recovery_offered_$accountName"

    /**
     * Stores the most recent sync error message so the red failure-date row can show
     * a friendly reason in its tap-to-explain dialog. Written alongside the
     * [lastSyncFailedKey] timestamp; never cleared on success (so the user can look back
     * at why the most recent failure happened even if the next sync recovered).
     */
    fun lastSyncErrorMsgKey(syncType: String, accountName: String) =
        "last_sync_error_msg_${syncType}_$accountName"

    /** Key for the "send email invites to attendees" toggle (default: false). */
    fun sendCalendarInvitesKey(accountName: String) =
        "send_calendar_invites_$accountName"

    /** Key for the "send update emails when event changes" toggle (default: false). */
    fun sendCalendarUpdatesKey(accountName: String) =
        "send_calendar_updates_$accountName"

    /** Key for the "send cancellation emails on event delete" toggle (default: false). */
    fun sendCalendarCancellationsKey(accountName: String) =
        "send_calendar_cancellations_$accountName"

    /**
     * Key for the per-account "include event title in invite/update/cancellation
     * email subject" toggle (default: false — opt-in because mail subject lines
     * traverse the wire in clear text, so enabling it leaks the event name to
     * mail-server-level metadata).
     */
    fun inviteSubjectIncludeTitleKey(accountName: String) =
        "invite_subject_include_title_$accountName"

    /** Default sync interval in seconds (1 hour). */
    const val DEFAULT_INTERVAL_SECONDS = "3600"

    // ---- Calendar sync range settings ----

    /**
     * Calendar sync mode — `"all"` syncs every event in the calendar (epoch..2037);
     * `"custom"` uses [calendarPastDaysKey] and [calendarFutureDaysKey] to compute
     * a window from `now`. Default is `"all"` to match the typical user expectation.
     */
    fun calendarSyncModeKey(accountName: String) =
        "calendar_sync_mode_$accountName"

    const val SYNC_MODE_ALL = "all"
    const val SYNC_MODE_CUSTOM = "custom"
    const val DEFAULT_SYNC_MODE = SYNC_MODE_ALL

    /** Key for the calendar past-days sync range (String, e.g. "180"). Used only when mode = "custom". */
    fun calendarPastDaysKey(accountName: String) =
        "calendar_past_days_$accountName"

    /** Key for the calendar future-days sync range (String, e.g. "365"). Used only when mode = "custom". */
    fun calendarFutureDaysKey(accountName: String) =
        "calendar_future_days_$accountName"

    /** Key for caching the last sync range per calendar (detects range changes). */
    fun lastSyncRangeKey(accountName: String, calId: String) =
        "last_sync_range_${accountName}_$calId"

    /**
     * Which time-zone rules this account's stored event times were last checked against
     * (TimeZoneRuleSource.retimeIdentity), plus "#n" after n failed attempts or "#u<n>@<process>" while the
     * check is incomplete (calendar.RetimeState). Ends with the account name, so a rename carries it. Not
     * exported: it describes this phone's data, not a choice.
     */
    fun timeZoneRetimeKey(accountName: String) = "tz_retime_$accountName"

    /** Default number of past days to sync for calendar events. */
    const val DEFAULT_PAST_DAYS = 180L

    /** Default number of future days to sync for calendar events. */
    const val DEFAULT_FUTURE_DAYS = 365L

    // ---- Event loop settings ----

    /** Key for the stored event loop ID (last processed event ID per account). */
    fun eventLoopIdKey(accountName: String) =
        "event_loop_id_$accountName"

    /** Key for the calendar-settings-dirty flag (triggers re-read on next sync). */
    fun calendarSettingsDirtyKey(accountName: String) =
        "calendar_settings_dirty_$accountName"

    // ---- Contact filter rules ----

    /**
     * Key for the per-account contact filter rules, stored as a gson JSON string.
     *
     * The account name must stay a *suffix*: [me.proton.sync.ProtonSyncApp] rewrites preference
     * keys on an account rename with a regex anchored on `_<oldName>`, and only a suffixed key
     * survives that. See [me.proton.sync.contacts.filter.ContactFilterStore].
     */
    fun contactFilterRulesKey(accountName: String) =
        "contact_filter_rules_$accountName"

    /**
     * Set when the rules change, so the next sync re-evaluates every contact.
     *
     * Mirrors [calendarSettingsDirtyKey]. On its own a trigger is not enough here: the contacts
     * adapter skips unchanged contacts by comparing timestamps, so the cached verdict stamped into
     * each mapping row also has to be invalidated. This flag is the trigger half.
     */
    fun contactFilterDirtyKey(accountName: String) =
        "contact_filter_dirty_$accountName"

    /**
     * Raw contact ids the **upload** filter deliberately declined to send, and which have no
     * mapping because they were never on Proton.
     *
     * These must be remembered or they are destroyed. The upload path clears the dirty flag when it
     * skips a contact, and the orphan sweep hard-deletes any local raw contact that is not dirty
     * and has no mapping. Without this set, creating a contact that your own upload rules exclude
     * would silently delete it on the next sync.
     *
     * Deliberately **not** in the backup allowlist: these ids are local to one device and mean
     * nothing on another.
     */
    fun contactFilterKeptLocalKey(accountName: String) =
        "contact_filter_kept_local_$accountName"

    /**
     * Refusal counts for contacts created on the phone that Proton would not create ("rawId:n,...").
     * See [me.proton.sync.contacts.ContactUploadRefusals]. Local raw contact ids, so like the kept
     * set above deliberately not in the backup allowlist.
     */
    fun contactCreateStrikesKey(accountName: String) =
        "contact_create_strikes_$accountName"

    /** Contacts the app gave up creating on Proton; kept on the phone and exempt from the orphan sweep. */
    fun contactCreateRefusedKey(accountName: String) =
        "contact_create_refused_$accountName"

    /** The latest failure reason per tracked contact ("rawId:reason"), for the notification only. */
    fun contactCreateReasonsKey(accountName: String) =
        "contact_create_reasons_$accountName"

    /**
     * Contacts too large to write to this phone, with the modify time they had ("time:kind:protonId").
     * See [me.proton.sync.contacts.ContactDownloadHolds]. About this phone's storage, so not in the
     * backup allowlist.
     */
    fun contactDownloadTooLargeKey(accountName: String) =
        "contact_download_too_large_$accountName"

    // ---- Fallback / event loop interval settings (added v1.3.x) ----

    /** Key for the event loop polling interval (seconds as String). */
    fun eventLoopIntervalKey(accountName: String) =
        "event_loop_interval_$accountName"

    /** Key for the contacts fallback sync interval (seconds as String). */
    fun fallbackContactsIntervalKey(accountName: String) =
        "fallback_contacts_interval_$accountName"

    /** Key for the contacts fallback sync enabled toggle. */
    fun fallbackContactsEnabledKey(accountName: String) =
        "fallback_contacts_enabled_$accountName"

    /** Key for the calendar fallback sync interval (seconds as String). */
    fun fallbackCalendarIntervalKey(accountName: String) =
        "fallback_calendar_interval_$accountName"

    /** Key for the calendar fallback sync enabled toggle. */
    fun fallbackCalendarEnabledKey(accountName: String) =
        "fallback_calendar_enabled_$accountName"

    /** Key for the attendee RSVP status polling toggle. */
    fun pollAttendeeStatusKey(accountName: String) =
        "poll_attendee_status_$accountName"

    /** Key for caching the set of attendee emails for a Proton event (change detection). */
    fun eventAttendeesKey(protonEventId: String) =
        "event_attendees_$protonEventId"

    /** Key for the last-used sync date display format (global, not per-account). */
    const val LAST_SYNC_DATE_FORMAT_KEY = "last_sync_date_format"

    /** Default fallback sync interval in seconds (12 hours). */
    const val DEFAULT_FALLBACK_INTERVAL_SECONDS = "43200"

    // ---- Subscription settings (device-wide, not per-account) ----

    /** Key for the cached subscription state enum name. */
    const val KEY_SUBSCRIPTION_STATE = "subscription_state"

    /** Key for the cached subscription expiry timestamp (epoch millis, 0 if none). */
    const val KEY_SUBSCRIPTION_EXPIRY = "subscription_expiry_ms"

    /** Key for the cached active subscription product ID. */
    const val KEY_SUBSCRIPTION_PRODUCT = "subscription_product_id"

    /** Key for the timestamp of the last successful BillingClient state refresh. */
    const val KEY_SUBSCRIPTION_LAST_CHECK = "subscription_last_check_ms"

    /** Key for the cached Google Play purchase token of the active subscription. */
    const val KEY_SUBSCRIPTION_PURCHASE_TOKEN = "subscription_purchase_token"

    /** (direct flavor) Key for the stored Paddle subscription id (sub_xxx) — the license key. */
    const val KEY_PADDLE_SUB_ID = "paddle_subscription_id"

    /**
     * (direct flavor) Key for the epoch-millis timestamp of the first transient license
     * validation failure in the current streak. Drives the 7-day offline grace window;
     * cleared on any successful validation. 0/absent means no failure streak.
     */
    const val KEY_LICENSE_FIRST_FAILED = "license_first_failed_ms"

    /**
     * (direct flavor) Paddle reported the subscription past due on the last validation. Paired with an
     * Active state (a renewal failed; sync continues while Paddle retries) or an Expired one (it never
     * paid; sync is off). Either way the settings card asks for a new payment method. Persisted with the
     * cached state, so the card survives a restart and the notification is not shown twice.
     */
    const val KEY_SUBSCRIPTION_PAYMENT_FAILED = "subscription_payment_failed"

    /**
     * (direct flavor) Paddle reported the subscription paused on the last validation. Sync is off, the
     * subscription id is kept and re-checked, and sync resumes by itself when the subscription does.
     */
    const val KEY_SUBSCRIPTION_PAUSED = "subscription_paused"

    /**
     * (direct flavor) A change Paddle has scheduled for the subscription, as of the last validation: its action
     * ("cancel" or "pause") here and when it takes effect (epoch millis) in [KEY_SUBSCRIPTION_SCHEDULED_CHANGE_AT].
     * Persisted with a Trial or Active state, absent with any other or when nothing is scheduled.
     */
    const val KEY_SUBSCRIPTION_SCHEDULED_CHANGE = "subscription_scheduled_change"

    /** (direct flavor) When the change in [KEY_SUBSCRIPTION_SCHEDULED_CHANGE] takes effect, in epoch millis. */
    const val KEY_SUBSCRIPTION_SCHEDULED_CHANGE_AT = "subscription_scheduled_change_at_ms"

    /**
     * (direct flavor) The licence proxy has accepted the stored subscription id at least once. Until it
     * has, checks are sent as "activation", so a mistyped id is refused; after, as "refresh", and an
     * answer that the subscription cannot be found is treated as a fault instead of deleting the id.
     * Absent on installs that predate it; see licenseConfirmedByDefault.
     */
    const val KEY_LICENSE_CONFIRMED = "license_confirmed"

    // ---- Diagnostic / debug settings (device-wide) ----

    /**
     * The app language the user picked, as a BCP-47 tag ("" means follow the system).
     *
     * AppCompat is the authority at runtime; this is a shadow copy kept purely so the settings
     * backup can carry it, since setApplicationLocales stores framework state that a
     * SharedPreferences export cannot see.
     */
    const val KEY_APP_LANGUAGE_TAG = "app_language_tag"

    /** Key for the runtime verbose-logging toggle (see SyncLog.setVerboseEnabled). */
    const val KEY_VERBOSE_LOGGING = "verbose_logging_enabled"

    /**
     * Default for [KEY_VERBOSE_LOGGING]: off. The extra detail can include account addresses and
     * calendar names, so a user turns it on only to capture a log for a report (the switch's help
     * text says so). Until 2.0.0 it was on, for the closed test's reports; an install where the
     * user never touched the switch follows the new default.
     */
    const val DEFAULT_VERBOSE_LOGGING = false

    /**
     * Master on/off for mirroring [me.proton.sync.SyncLog] output to a file (default false,
     * opt-in). Lets users on logcat-blocked ROMs (GrapheneOS) capture a real diagnostic log.
     */
    const val KEY_FILE_LOGGING = "file_logging_enabled"

    /** Persisted SAF tree URI (string) of the user-chosen folder the log file is written into. */
    const val KEY_LOG_FOLDER_URI = "log_folder_uri"

    /**
     * "Update time zones online": whether one-off event times use current rules from www.tzurl.org
     * instead of the phone's own tz data (see [me.proton.sync.calendar.TimeZoneRuleSource]). Never
     * ical4j's updater, which is off for good ([me.proton.sync.calendar.IcalTimeZoneUpdates]).
     *
     * Device-wide, not per account: the rules of a zone are the same whichever account's event uses it.
     * Exported with the settings backup like its neighbours.
     */
    const val KEY_TIMEZONE_UPDATES = "online_timezone_updates_enabled"

    /** Default for [KEY_TIMEZONE_UPDATES]: off, so nothing reaches tzurl.org unless the user asks. */
    const val DEFAULT_TIMEZONE_UPDATES = false

    /**
     * The answer to the one-time "time zone data is out of date" notice: "on" (Turn on) or "off" (Keep
     * off). Present means answered, and the notice never shows again. Device-wide, not exported: the
     * notice is about this phone's data.
     */
    const val KEY_TZ_OUTDATED_NOTICE_ANSWER = "tz_outdated_notice_answer"

    /** When that notice was last shown without an answer (epoch millis): it comes back at most weekly. */
    const val KEY_TZ_OUTDATED_NOTICE_LAST_SHOWN_MS = "tz_outdated_notice_last_shown_ms"

    /** Days between two showings of the unanswered notice. */
    const val TZ_OUTDATED_NOTICE_INTERVAL_DAYS = 7L

    /** The re-time identity the app last requested syncs for at start-up (TimeZoneRuleSource). */
    const val KEY_TZ_RETIME_REQUESTED_IDENTITY = "tz_retime_requested_identity"

    /** The newest tz release a tzurl.org answer carried ("2026d"), part of the ON-mode re-time identity. */
    const val KEY_TZURL_SEEN_VERSION = "tz_tzurl_seen_version"

    /**
     * DEBUG builds only: a tz data version the settings screen pretends the phone has ("2021a"), so the
     * outdated-data notice and hint can be tested on a phone whose data is current. Never used for rules.
     */
    const val KEY_DEBUG_TZDATA_VERSION_OVERRIDE = "debug_tzdata_version_override"

    // ---- Re-auth backoff ----

    /**
     * Key for the per-account "skip sync until" timestamp (epoch millis). Set when a sync
     * adapter detects an authentication or key-unlock failure so subsequent scheduled
     * syncs early-return without hammering the API. Cleared on (re-)login completion or
     * by an explicit manual sync request.
     */
    fun syncPausedUntilKey(accountName: String) =
        "sync_paused_until_$accountName"

    /** How long to back off scheduled syncs after an auth/unlock failure (30 minutes). */
    const val SYNC_PAUSE_AFTER_AUTH_FAIL_MS = 30L * 60L * 1000L

    // ---- Local data backup (calendars + contacts → .ics/.vcf) settings (device-wide) ----
    //
    // Distinct from the `.sppbackup` settings/account export (BackupModels/ExportManager).
    // This feature snapshots the *content* (events + contacts) from Proton to a user-chosen
    // folder so a phone-side glitch can never cost the user their data. Deliberately NOT in
    // BackupSettings.isExportable: the folder URI is device-specific and the passphrase is
    // a secret, so neither belongs in a portable `.sppbackup`.

    /** Master on/off for scheduled automatic data backups. */
    const val KEY_BACKUP_ENABLED = "data_backup_enabled"

    /** Persisted SAF tree URI (string) of the user-chosen backup folder. */
    const val KEY_BACKUP_FOLDER_URI = "data_backup_folder_uri"

    /** Backup interval in days (String for ListPreference compatibility). */
    const val KEY_BACKUP_INTERVAL_DAYS = "data_backup_interval_days"

    /** Include calendars in the backup (default true). */
    const val KEY_BACKUP_INCLUDE_CALENDARS = "data_backup_include_calendars"

    /** Include contacts in the backup (default true). */
    const val KEY_BACKUP_INCLUDE_CONTACTS = "data_backup_include_contacts"

    /** Encrypt each run as one gpg-compatible OpenPGP archive (default false, opt-in). */
    const val KEY_BACKUP_ENCRYPTION_ENABLED = "data_backup_encryption_enabled"

    /** The backup passphrase, Keystore-wrapped via [me.proton.sync.crypto.SecureStorage]. */
    const val KEY_BACKUP_PASSPHRASE_ENC = "data_backup_passphrase_enc"

    /** How many timestamped runs to keep before pruning the oldest (String). */
    const val KEY_BACKUP_RETENTION = "data_backup_retention_count"

    /** Epoch-millis of the last completed backup run (0/absent = never). */
    const val KEY_BACKUP_LAST_RUN_MS = "data_backup_last_run_ms"

    /** Short human-readable summary of the last run (success/skip/failure + counts). */
    const val KEY_BACKUP_LAST_RESULT = "data_backup_last_result"

    /** Total item count (events + contacts) of the last successful run — feeds the emptiness guard. */
    const val KEY_BACKUP_LAST_TOTAL = "data_backup_last_total"

    /** Legacy one-shot flag from when the backup popup showed only once. No longer read —
     *  superseded by the weekly re-show gated on [KEY_BACKUP_REMINDER_LAST_SHOWN_MS] +
     *  [KEY_BACKUP_REMINDER_OPTED_OUT]. Kept so the old key isn't reused with new meaning. */
    const val KEY_BACKUP_REMINDER_SHOWN = "data_backup_reminder_shown"

    /** True once the user has deliberately (double-confirmed) opted out of backup reminders. */
    const val KEY_BACKUP_REMINDER_OPTED_OUT = "data_backup_reminder_opted_out"

    /** Epoch millis the backup reminder was last shown — gates the weekly re-show. */
    const val KEY_BACKUP_REMINDER_LAST_SHOWN_MS = "data_backup_reminder_last_shown_ms"

    /** How often (days) to re-show the backup reminder when backups are off and the user
     *  hasn't opted out. Tunable — bump this to nag less / more often. */
    const val BACKUP_REMINDER_INTERVAL_DAYS = 7

    const val DEFAULT_BACKUP_INTERVAL_DAYS = "7"
    const val DEFAULT_BACKUP_RETENTION = "14"

    // ---- Account rename ----

    /**
     * Every device-wide key above: one value for the whole app, never an account's. An account
     * rename must leave them alone. [renamedAccountKey] finds an account's keys by an `_<name>` token
     * followed by `_` or the end, and these keys are made of such tokens, so an account called, say,
     * "updates" or "enabled" would otherwise move "online_timezone_updates_enabled" (and with it the
     * device-wide choice) to a key nothing reads. Add every new device-wide key here.
     */
    val DEVICE_WIDE_KEYS: Set<String> = setOf(
        LAST_SYNC_DATE_FORMAT_KEY,
        KEY_SUBSCRIPTION_STATE, KEY_SUBSCRIPTION_EXPIRY, KEY_SUBSCRIPTION_PRODUCT,
        KEY_SUBSCRIPTION_LAST_CHECK, KEY_SUBSCRIPTION_PURCHASE_TOKEN, KEY_PADDLE_SUB_ID,
        KEY_LICENSE_FIRST_FAILED, KEY_SUBSCRIPTION_PAYMENT_FAILED, KEY_SUBSCRIPTION_PAUSED,
        KEY_SUBSCRIPTION_SCHEDULED_CHANGE, KEY_SUBSCRIPTION_SCHEDULED_CHANGE_AT, KEY_LICENSE_CONFIRMED,
        KEY_APP_LANGUAGE_TAG, KEY_VERBOSE_LOGGING, KEY_FILE_LOGGING, KEY_LOG_FOLDER_URI,
        KEY_TIMEZONE_UPDATES, KEY_TZ_OUTDATED_NOTICE_ANSWER, KEY_TZ_OUTDATED_NOTICE_LAST_SHOWN_MS,
        KEY_TZ_RETIME_REQUESTED_IDENTITY, KEY_TZURL_SEEN_VERSION, KEY_DEBUG_TZDATA_VERSION_OVERRIDE,
        KEY_BACKUP_ENABLED, KEY_BACKUP_FOLDER_URI, KEY_BACKUP_INTERVAL_DAYS,
        KEY_BACKUP_INCLUDE_CALENDARS, KEY_BACKUP_INCLUDE_CONTACTS, KEY_BACKUP_ENCRYPTION_ENABLED,
        KEY_BACKUP_PASSPHRASE_ENC, KEY_BACKUP_RETENTION, KEY_BACKUP_LAST_RUN_MS,
        KEY_BACKUP_LAST_RESULT, KEY_BACKUP_LAST_TOTAL, KEY_BACKUP_REMINDER_SHOWN,
        KEY_BACKUP_REMINDER_OPTED_OUT, KEY_BACKUP_REMINDER_LAST_SHOWN_MS,
    )

    /**
     * The key [key] becomes when the account [oldName] is renamed to [newName], or null when it is
     * not one of that account's keys. Account keys are `prefix_<name>` or `prefix_<name>_suffix`, so
     * an `_<oldName>` token followed by `_` or the end is replaced; device-wide keys never are
     * ([DEVICE_WIDE_KEYS]). [newName] is inserted literally: a `$` or `\` in it is not a group
     * reference (Regex.replace would otherwise read "$1" as one and throw on a name like "A$1").
     */
    fun renamedAccountKey(key: String, oldName: String, newName: String): String? {
        if (key in DEVICE_WIDE_KEYS) return null
        val pattern = Regex("_${Regex.escape(oldName)}(?=_|$)")
        if (!pattern.containsMatchIn(key)) return null
        val newKey = pattern.replace(key, Regex.escapeReplacement("_$newName"))
        return newKey.takeIf { it != key }
    }

    // ---- Removed accounts ----

    /**
     * Preference key prefixes whose remainder is the account name, for dropping the settings of an
     * account that is gone (ProtonSyncApp.forgetRemovedAccounts).
     *
     * Taken from the single-argument key builders here, in ContactNameMode, EventConflictGuard and
     * PendingCancellations. Deliberately excludes the two-argument builders (last_sync_, sync_interval_
     * by type, notify_sync_failure_,
     * last_sync_error_, last_sync_range_), whose suffix is not a bare account name - stripping
     * those would invent account names out of "contacts" and "calendar". contact_name_mode_ also
     * begins the name mode's re-apply flag, contact_name_mode_reapply_<account>, so that has its own
     * entry: read by the shorter prefix alone, every flag looked like the name mode of an account called
     * "reapply_<account>" and was deleted at the next start, before the sync that was to apply it.
     */
    val ACCOUNT_SCOPED_PREFIXES: List<String> = listOf(
        "event_loop_interval_", "event_loop_id_",
        "calendar_sync_mode_", "calendar_past_days_", "calendar_future_days_",
        "calendar_settings_dirty_", "calendar_pending_cancels_",
        "contact_filter_rules_", "contact_filter_dirty_", "contact_filter_kept_local_",
        "contact_create_strikes_", "contact_create_refused_", "contact_create_reasons_",
        "contact_download_too_large_",
        "conflict_held_", "conflict_local_wins_",
        "display_name_user_chosen_", "display_name_recovery_offered_",
        "fallback_contacts_enabled_", "fallback_contacts_interval_",
        "fallback_calendar_enabled_", "fallback_calendar_interval_",
        "include_external_calendars_", "invite_subject_include_title_",
        "notify_sync_in_progress_", "poll_attendee_status_",
        "send_calendar_invites_", "send_calendar_updates_", "send_calendar_cancellations_",
        "sync_paused_until_", "contact_name_mode_", "contact_name_mode_reapply_",
    )

    /**
     * The accounts [key] can belong to: what follows each of [ACCOUNT_SCOPED_PREFIXES] it starts with,
     * the longest prefix first. Empty for a key that is not per-account. One name for every key but
     * contact_name_mode_reapply_<x>, which is the re-apply flag of the account <x> or the name mode of an
     * account literally called "reapply_<x>".
     */
    fun accountNamesOfKey(key: String): List<String> =
        ACCOUNT_SCOPED_PREFIXES.filter { key.startsWith(it) && key.length > it.length }
            .sortedByDescending { it.length }
            .map { key.substring(it.length) }

    /**
     * The per-account keys among [keys] that belong to no account in [activeNames], each with the
     * account it is dropped for (its most specific reading). A key stays while any reading of it names
     * an account that exists ([accountNamesOfKey]).
     */
    fun keysOfRemovedAccounts(keys: Iterable<String>, activeNames: Set<String>): Map<String, String> {
        val removed = LinkedHashMap<String, String>()
        for (key in keys) {
            val names = accountNamesOfKey(key)
            if (names.isEmpty() || names.any { it in activeNames }) continue
            removed[key] = names.first()
        }
        return removed
    }

    // ---- Reader helpers ----

    /**
     * Read a String preference, returning [default] if the value is null or absent.
     *
     * Eliminates the repeated `prefs.getString(k, d) ?: d` pattern. SharedPreferences
     * already accepts a default, but Android can return null in rare cases (e.g. the
     * stored value was explicitly cleared to null), so the explicit `?: d` is needed.
     */
    fun getStringOrDefault(prefs: SharedPreferences, key: String, default: String): String =
        prefs.getString(key, default) ?: default

    /**
     * Read a Long preference stored as a string (the format used by Android's
     * ListPreference / EditTextPreference). Returns [default] if absent or malformed.
     *
     * Replaces the `prefs.getString(k, "$d")?.toLongOrNull() ?: d` pattern.
     */
    fun getLongFromStringPref(prefs: SharedPreferences, key: String, default: Long): Long =
        prefs.getString(key, default.toString())?.toLongOrNull() ?: default
}
