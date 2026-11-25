package me.proton.sync.settings

import android.Manifest
import android.accounts.Account
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.sync.Plurals
import me.proton.sync.R
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.calendar.RangeTrimDryRun
import me.proton.sync.calendar.RangeTrimPlanner
import me.proton.sync.calendar.RangeTrimRunner
import me.proton.sync.calendar.RangeTrimRunner.Choice
import me.proton.sync.db.SyncDatabase
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

/**
 * "Remove older events from this phone", per calendar: a dialog listing an account's calendars too large to sync whole,
 * and any it is on for, each with what it would lose (RangeTrimDryRun, read off the main thread, so "Up to"). From the
 * settings row it saves the ticks. When the range was narrowed it is offered, with Remove (the ticks) and Keep them
 * (off for every listed calendar), whenever a large calendar would lose something, also one it is already on for: a
 * narrower range takes more from those. A calendar it is on for that is small now syncs whole and loses nothing, and
 * says so; small calendars it is not on for are not listed, nor is a calendar the sync skips (a holiday or subscribed
 * one while "Include external calendars" is off), from which nothing is removed ([RangeTrimDryRun.listed]).
 */
internal class RangeTrimDialog(private val activity: AppCompatActivity, private val database: SyncDatabase) {

    private class Loaded(val estimates: List<RangeTrimDryRun.Estimate>, val cutoffMillis: Long)

    /**
     * Show it for [account]; [offer] when the range was just narrowed. [onSaved] gets the calendars this answer
     * switched on; [onNotShown] runs on every way out without the dialog (nothing to offer or list, the calendars
     * unreadable, the screen gone, a load cancelled with it). Both feed the caller's settings sync request: a narrowed
     * range's waits for the answer. Logs what it showed, or why nothing. [press] (the debug verb trim_dialog only)
     * presses that button of the shown dialog, DialogInterface.BUTTON_POSITIVE or BUTTON_NEGATIVE, through its own
     * handler.
     */
    fun show(
        account: Account,
        offer: Boolean,
        press: Int? = null,
        onNotShown: () -> Unit = {},
        onSaved: (newlyOn: Set<Choice>) -> Unit,
    ) {
        activity.lifecycleScope.launch {
            var shown = false
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                val past = days(prefs, SyncPreferences.calendarPastDaysKey(account.name), SyncPreferences.DEFAULT_PAST_DAYS)
                val future = days(prefs, SyncPreferences.calendarFutureDaysKey(account.name), SyncPreferences.DEFAULT_FUTURE_DAYS)
                val loaded = withContext(Dispatchers.IO) { load(account, past, future) }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                if (loaded == null) {
                    SyncLog.w(TAG, "Remove older events: the calendars of '${account.name}' could not be read; nothing shown")
                    if (!offer) Toast.makeText(activity, R.string.trim_read_failed, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val choices = RangeTrimRunner.choices(prefs, account.name)
                if (loaded.estimates.isEmpty()) {
                    // No calendar of the account is mapped: not synced yet, or a forced resync dropped the mappings.
                    SyncLog.i(TAG, "Remove older events: '${account.name}' has no synced calendar yet" + if (offer) "; no offer" else "")
                    if (!offer) {
                        AppDialogBuilder(activity)
                            .setTitle(R.string.trim_dialog_title)
                            .setMessage(R.string.trim_not_synced_yet)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    return@launch
                }
                // The large calendars, and any it is on for, so that it can always be switched off again; not one the sync
                // skips (external calendars while they are off).
                val listed = RangeTrimDryRun.listed(loaded.estimates, choices)
                if (offer && !RangeTrimDryRun.offerWanted(listed)) {
                    SyncLog.i(TAG, "Remove older events: no offer for '${account.name}', no large calendar would lose an event")
                    return@launch
                }
                if (listed.isEmpty()) {
                    // A large calendar left out because the sync skips it is not "too small": the notes say why instead.
                    val leftOut = RangeTrimDryRun.leftOut(loaded.estimates, choices)
                    val notSynced = RangeTrimDryRun.LeftOut.NOT_SYNCED in leftOut
                    SyncLog.i(TAG, "Remove older events: no large calendar in '${account.name}'" +
                        if (notSynced) " that syncs (external calendars are off)" else "")
                    AppDialogBuilder(activity)
                        .setTitle(R.string.trim_dialog_title)
                        .setMessage(if (notSynced) notes(leftOut) else activity.getString(R.string.trim_no_large_calendars))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@launch
                }
                val dialog = present(account, offer, loaded, listed, choices, prefs, onSaved)
                shown = true
                SyncLog.i(TAG, "Remove older events: dialog shown for '${account.name}' with ${listed.size} calendar(s)" +
                    if (offer) " (offered when the range narrowed)" else "")
                if (press != null) dialog.getButton(press)?.performClick()
            } finally {
                if (!shown) onNotShown()
            }
        }
    }

    /**
     * The number of calendars of [account] removal is on for that are synced on this phone, as the dialog lists them:
     * not one the sync skips. While no calendar of the account is mapped (a forced resync drops the mappings until the
     * next sync), every choice still stands.
     */
    suspend fun enabledCount(account: Account): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val choices = RangeTrimRunner.choices(prefs, account.name)
        if (choices.isEmpty()) return 0
        val mapped = withContext(Dispatchers.IO) {
            database.calendarSyncDao().getAllByAccount(account.name)
                .mapNotNullTo(HashSet()) { m -> m.androidCalendarId?.let { Choice(it, m.protonCalendarId) } }
        }
        return if (mapped.isEmpty()) choices.size else choices.count {
            it in mapped && !RangeTrimRunner.skippedBySync(prefs, account.name, it.protonCalendarId)
        }
    }

    private fun notes(leftOut: List<RangeTrimDryRun.LeftOut>): String = leftOut.joinToString("\n\n") {
        activity.getString(
            when (it) {
                RangeTrimDryRun.LeftOut.SMALL -> R.string.trim_small_calendars_note
                RangeTrimDryRun.LeftOut.NOT_SYNCED -> R.string.trim_external_off_note
            }
        )
    }

    private fun days(prefs: SharedPreferences, key: String, default: Long) =
        SyncPreferences.getStringOrDefault(prefs, key, "$default").toLongOrNull() ?: default

    private suspend fun load(account: Account, past: Long, future: Long): Loaded? {
        if (activity.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val client = activity.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY) ?: return null
        return try {
            client.use { provider ->
                val now = System.currentTimeMillis()
                RangeTrimDryRun.run(
                    activity.applicationContext, provider, account, database.calendarSyncDao(), database.calendarEventSyncDao(),
                    past, future, now
                )?.let { Loaded(it, RangeTrimPlanner.cutoffMillis(now, past, ZoneId.systemDefault())) }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Remove older events: reading the calendars of '${account.name}' failed: ${e.message}")
            null
        }
    }

    private fun present(
        account: Account,
        offer: Boolean,
        loaded: Loaded,
        listed: List<RangeTrimDryRun.Estimate>,
        choices: Set<Choice>,
        prefs: SharedPreferences,
        onSaved: (Set<Choice>) -> Unit,
    ): AlertDialog {
        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.dialog_range_trim, null)
        val day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(loaded.cutoffMillis))
        view.findViewById<TextView>(R.id.trim_intro).text = activity.getString(R.string.trim_dialog_intro, day)
        val list = view.findViewById<LinearLayout>(R.id.trim_calendars)
        val boxes = LinkedHashMap<Choice, CheckBox>()
        for (e in listed) {
            val row = inflater.inflate(R.layout.item_range_trim_calendar, list, false)
            val box = row.findViewById<CheckBox>(R.id.trim_check)
            val name = e.displayName.removePrefix("${account.name} - ")
            val count = when {
                // On for it, but it syncs whole now: the sync downloads it whole and removes nothing.
                !e.large -> activity.getString(R.string.trim_calendar_whole)
                e.events > 0 -> Plurals.text(activity, R.plurals.trim_calendar_count, e.events, e.events)
                else -> activity.getString(R.string.trim_calendar_nothing)
            }
            row.findViewById<TextView>(R.id.trim_name).text = name
            row.findViewById<TextView>(R.id.trim_count).text = count
            box.isChecked = e.choice in choices
            row.contentDescription = "$name, $count"
            row.setOnClickListener { box.toggle() }
            ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = CheckBox::class.java.name
                    info.isCheckable = true
                    info.isChecked = box.isChecked
                }
            })
            list.addView(row)
            boxes[e.choice] = box
        }
        // Only when a calendar is left out: a small one it is on for is listed, with its own line; one the sync skips
        // (external calendars off) is not.
        val leftOut = RangeTrimDryRun.leftOut(loaded.estimates, choices)
        if (leftOut.isNotEmpty()) {
            view.findViewById<TextView>(R.id.trim_note).apply {
                text = notes(leftOut)
                visibility = View.VISIBLE
            }
        }
        fun save(chosen: Set<Choice>, how: String) {
            val newlyOn = RangeTrimRunner.saveChoices(prefs, account.name, chosen)
            SyncLog.i(
                "UserAction",
                "Remove older events for '${account.name}' ($how): on for ${boxes.keys.count { it in chosen }} of ${boxes.size} " +
                    "listed calendar(s)" + (if (newlyOn.isEmpty()) "" else ", ${newlyOn.size} newly") +
                    if (offer) " (offered when the range narrowed)" else ""
            )
            onSaved(newlyOn)
        }
        // Calendars it is on for that the dialog did not list (not synced on this phone now, or skipped by the sync)
        // keep their choice: a choice names its Proton calendar too, so it never passes to another calendar.
        val unlisted = choices - boxes.keys
        val builder = AppDialogBuilder(activity)
            .setTitle(R.string.trim_dialog_title)
            .setView(view)
            .setPositiveButton(if (offer) R.string.remove_synced_remove else R.string.trim_dialog_save) { _, _ ->
                save(unlisted + boxes.filterValues { it.isChecked }.keys, if (offer) "Remove" else "Save")
            }
        if (offer) {
            // Keep them keeps every listed calendar's events: off for each. The offer asks, so it is answered.
            builder.setNegativeButton(R.string.trim_offer_keep) { _, _ -> save(unlisted, "Keep them") }
            builder.setCancelable(false)
        } else {
            builder.setNegativeButton(R.string.btn_cancel, null)
        }
        return builder.show()
    }

    private companion object {
        const val TAG = "SyncSettings"
    }
}
