package me.proton.sync.calendar

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Looper
import androidx.preference.PreferenceManager
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncConstants
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import java.time.ZoneId

/**
 * Where event times get their zone rules, app-wide: the "Update time zones online" switch, the phone's own tz
 * data, and the one process-wide [OnlineTimeZoneRules] cache.
 *
 * OFF (the default): the phone's own rules (java.time, which on Android reads the phone's ICU tz data, the
 * version [phoneTzdataVersion] reports). ON: current rules from www.tzurl.org for one-off events and an
 * override's own times, when they are newer than the phone's ([RuleSnapshot], [OnlineTimeZoneRules]).
 * ical4j's own updater is never involved: it is switched off for good at start-up ([IcalTimeZoneUpdates]).
 */
internal object TimeZoneRuleSource {
    private const val TAG = "TzRules"

    /**
     * Bumped whenever the way stored times are computed changes, so the re-time pass runs once more on every
     * phone (CalendarSyncAdapter.runRetimePass). v1 is 1.11.5's java.time download.
     */
    const val ALGORITHM_VERSION = "v1"

    /** The switch, or the default (off). A value of the wrong type reads as off (see the import guard). */
    fun isOnlineEnabled(prefs: SharedPreferences): Boolean = try {
        prefs.getBoolean(SyncPreferences.KEY_TIMEZONE_UPDATES, SyncPreferences.DEFAULT_TIMEZONE_UPDATES)
    } catch (_: ClassCastException) {
        SyncPreferences.DEFAULT_TIMEZONE_UPDATES
    }

    /**
     * The version of the tz data java.time uses on this phone, "2026c", or null when it cannot be read.
     * On Android java.time's only provider is libcore's IcuZoneRulesProvider, so ICU's version is exactly
     * the data every phone-rules time in this app is computed with. Never the files under /apex or /system:
     * an update can live under /data/misc/zoneinfo (measured on the Note 10+: files 2021a, runtime 2026c).
     */
    fun phoneTzdataVersion(): String? = try {
        android.icu.util.TimeZone.getTZDataVersion()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /**
     * The version the settings screen judges and shows: [phoneTzdataVersion], or in a DEBUG build the
     * override that the tz_warning debug command stores in its "fake" mode, so the warning can be tested on a
     * phone whose data is current. Never used for rules or for the re-time identity.
     */
    fun displayedTzdataVersion(prefs: SharedPreferences): String? {
        if (me.proton.sync.BuildConfig.DEBUG) {
            prefs.getString(SyncPreferences.KEY_DEBUG_TZDATA_VERSION_OVERRIDE, null)?.let { return it }
        }
        return phoneTzdataVersion()
    }

    @Volatile
    private var appContext: Context? = null

    /** The process-wide online cache: one attempt per zone per process, shared by download, upload and the pass. */
    val online: OnlineTimeZoneRules by lazy {
        OnlineTimeZoneRules(
            fetch = TzurlClient::fetch,
            phoneVersion = ::phoneTzdataVersion,
            phoneRules = { id -> try { ZoneId.of(id).rules } catch (_: Exception) { null } },
            canFetch = { Looper.myLooper() != Looper.getMainLooper() },
            onVersionSeen = { version -> rememberSeenVersion(version) },
            log = { SyncLog.i(TAG, it) }
        )
    }

    private fun rememberSeenVersion(version: String) {
        val ctx = appContext ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val stored = prefs.getString(SyncPreferences.KEY_TZURL_SEEN_VERSION, null)
        val newer = stored == null || (OnlineTimeZoneRules.compareVersions(version, stored) ?: 0) > 0
        if (newer) prefs.edit().putString(SyncPreferences.KEY_TZURL_SEEN_VERSION, version).apply()
    }

    /** One sync run's rules: read the switch once. */
    fun snapshot(context: Context): RuleSnapshot {
        appContext = context.applicationContext
        val on = isOnlineEnabled(PreferenceManager.getDefaultSharedPreferences(context))
        return if (on) RuleSnapshot(true, online) else RuleSnapshot.PHONE_ONLY
    }

    /**
     * What the stored event times were computed with, as one string: the algorithm, the switch, the phone's
     * tz data, and in ON mode the newest tzurl.org release seen. The re-time pass runs for an account whenever
     * its stored identity differs, which covers the upgrade, every flip of the switch, every tz data update
     * Android installs (stored instants never move by themselves) and every new tzurl release.
     */
    fun retimeIdentity(context: Context, snapshot: RuleSnapshot): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val base = "$ALGORITHM_VERSION|${if (snapshot.online) "on" else "off"}|${phoneTzdataVersion() ?: "unknown"}"
        return if (snapshot.online) "$base|${prefs.getString(SyncPreferences.KEY_TZURL_SEEN_VERSION, null) ?: "none"}" else base
    }

    /**
     * This process's token in the re-time bookkeeping (RetimeState): a zone is fetched at most once per process,
     * so a pass left incomplete for want of rules runs again only in another process.
     */
    val processToken: String = java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    /**
     * Ask for a calendar sync of every Proton account, so a new rule source re-times stored events soon
     * instead of at the next scheduled sync. Correctness never depends on it: the pass runs at the next sync
     * whenever it happens. EXPEDITED only: never SYNC_EXTRAS_MANUAL, which would let these automatic requests
     * through an account's pause after an auth failure (SyncUtils.isSyncPaused) and retry a dead session with
     * nobody asking, and never EXTRA_USER_SYNC_NOW: the pass clears the cursors of exactly the calendars it needs
     * downloaded, durably.
     */
    fun requestRetimeSyncs(context: Context, reason: String) {
        try {
            val accounts = AccountManager.get(context).getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
            for (account in accounts) {
                ContentResolver.requestSync(account, SyncConstants.AUTHORITY_CALENDAR, Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                })
            }
            if (accounts.isNotEmpty()) SyncLog.i(TAG, "Calendar sync requested for ${accounts.size} account(s): $reason")
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not request the re-time sync: ${e.message}")
        }
    }

    /**
     * At app start, off the main thread: when the identity changed since the last request (an app update, a
     * tz data update), request the syncs once, so the upgrade's pass does not wait for the next schedule.
     */
    fun requestRetimeIfIdentityChanged(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val identity = retimeIdentity(context, snapshot(context))
        if (prefs.getString(SyncPreferences.KEY_TZ_RETIME_REQUESTED_IDENTITY, null) == identity) return
        prefs.edit().putString(SyncPreferences.KEY_TZ_RETIME_REQUESTED_IDENTITY, identity).apply()
        requestRetimeSyncs(context, "event times are now computed as $identity")
    }

    /**
     * After a sync run's downloads: when they wrote one-off times with the phone's rules because the online
     * rules could not be had ([RuleSnapshot.transientFallbacks]), or used rules from an older tz release than the
     * newest seen ([RuleSnapshot.usedStaleOnlineRules]), mark the account's check incomplete, so the next process
     * runs the re-time pass again and corrects those rows once the rules can be had. Never throws.
     */
    fun noteDownloadFallbacks(context: Context, accountName: String, snapshot: RuleSnapshot) {
        try {
            val fallbacks = snapshot.transientFallbacks
            val stale = snapshot.usedStaleOnlineRules()
            if (fallbacks.isEmpty() && !stale) return
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val key = SyncPreferences.timeZoneRetimeKey(accountName)
            val identity = retimeIdentity(context, snapshot)
            val next = RetimeState.afterDownloadFallback(prefs.getString(key, null), identity, processToken) ?: return
            prefs.edit().putString(key, next).apply()
            SyncLog.i(TAG, "Re-time for $accountName to run again in another process: " +
                (if (fallbacks.isNotEmpty()) "the phone's rules stood in for ${fallbacks.sorted().joinToString(", ")}" else "") +
                (if (stale) "${if (fallbacks.isNotEmpty()) "; " else ""}some online rules came from an older tz release" else ""))
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not note the time zone fallbacks: ${e.message}")
        }
    }
}
