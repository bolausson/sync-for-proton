package me.proton.sync.calendar

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract

/**
 * The calendar apps on this phone, for [NewEventHoldRules.verdict]: a calendar editor is a package with an activity
 * that handles ACTION_INSERT for vnd.android.cursor.dir/event or vnd.android.cursor.item/event, the "new event" screen
 * of every calendar app and what an app's "add to calendar" button opens. An event saved there was put into its
 * calendar by the user, in a screen that shows the calendar, so it is never held.
 *
 * The manifest's <queries> lists those two intents and nothing else (INSERT only: EDIT would also let viewers and
 * widgets through), so package visibility shows exactly the editors here. The query uses a content: URI, which also
 * matches a filter that names only the type, as Proton Calendar's item/event filter does.
 */
internal object CalendarEditors {

    /** The two MIME types of a calendar app's new-event screen. */
    val EVENT_TYPES = listOf("vnd.android.cursor.dir/event", "vnd.android.cursor.item/event")

    /**
     * Every package with an activity for ACTION_INSERT on content://com.android.calendar/events with one of
     * [EVENT_TYPES] (MATCH_DEFAULT_ONLY, as an app's startActivity resolves it), or null when the package manager
     * could not be asked: the caller then knows no editor, so every new event in a shared calendar is held.
     */
    fun packages(context: Context): Set<String>? = try {
        val pm = context.packageManager
        val out = HashSet<String>()
        for (type in EVENT_TYPES) {
            val intent = Intent(Intent.ACTION_INSERT).setDataAndType(CalendarContract.Events.CONTENT_URI, type)
            val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            found.mapNotNullTo(out) { it.activityInfo?.packageName }
        }
        out
    } catch (e: Exception) {
        null
    }

    /**
     * Whether the MUTATORS entry [entry] is a calendar editor: one of [editors], or a shared-uid or bare-uid entry
     * ([NewEventHoldRules.uidOf]) whose uid [packagesForUid] (PackageManager.getPackagesForUid) resolves to a package
     * among them. A uid whose packages this app cannot see resolves to nothing, and a lookup that fails to nothing,
     * which holds the event.
     */
    fun isEditor(entry: String, editors: Set<String>, packagesForUid: (Int) -> Array<String>?): Boolean {
        if (entry in editors) return true
        val uid = NewEventHoldRules.uidOf(entry) ?: return false
        val packages = try {
            packagesForUid(uid)
        } catch (e: Exception) {
            null
        } ?: return false
        return packages.any { it in editors }
    }
}
