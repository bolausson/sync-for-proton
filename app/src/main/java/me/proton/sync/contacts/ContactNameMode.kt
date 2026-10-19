package me.proton.sync.contacts

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Where a Proton contact's display name goes when Proton has no first or last name for them.
 *
 * Proton auto-creates a contact for everyone you exchange mail with, and those carry a display
 * name and nothing else. Android's contacts provider does not store a name it cannot complete: give
 * it a display name alone and it splits one out, so `app@example.com` becomes
 * given=`app@example`, family=`com`, and the name the phone then *shows* is `app@example com`,
 * with the dot turned into a space. On real address books, many contacts read that way.
 *
 * The provider only invents the half you leave empty, so filling one in deliberately stops it.
 * Which half is a matter of taste, hence a setting rather than a decision made for everybody.
 */
enum class ContactNameMode {

    /**
     * Write the display name alone and let Android do what it likes with it. Today's behaviour,
     * and the reason the names above are mangled. Kept as the default so no address book changes
     * shape on upgrade without being asked.
     */
    ANDROID,

    /** Store it as the contact's nickname. First and last stay empty, which is the honest answer. */
    NICKNAME,

    /** Store the whole display name as the first name, unsplit. */
    GIVEN,

    /** Store the whole display name as the last name, unsplit. */
    FAMILY,
    ;

    companion object {

        private const val KEY = "contact_name_mode"

        /** Set when the mode changes, so one sync re-applies it to contacts already on the phone. */
        private const val REAPPLY_KEY = "contact_name_mode_reapply"

        /**
         * Per-account, and keyed by *suffix* on purpose: `ProtonSyncApp` rewrites preference keys on
         * an account rename with a regex anchored to the trailing name, so a suffixed key survives a
         * rename with no extra code. Internal, not private, for the settings backup's type test: both
         * keys travel in a backup, the mode as a String and the re-apply flag as a Boolean.
         */
        internal fun key(accountName: String) = "${KEY}_$accountName"

        internal fun reapplyKey(accountName: String) = "${REAPPLY_KEY}_$accountName"

        fun of(context: Context, accountName: String): ContactNameMode {
            val raw = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(key(accountName), null) ?: return ANDROID
            return entries.firstOrNull { it.name == raw } ?: ANDROID
        }

        /** Stores the mode and, if it actually changed, asks the next sync to re-apply it. */
        fun set(context: Context, accountName: String, mode: ContactNameMode) {
            if (of(context, accountName) == mode) return
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(key(accountName), mode.name)
                .putBoolean(reapplyKey(accountName), true)
                .apply()
        }

        /**
         * Whether a sync should rewrite the name of contacts already on the phone.
         *
         * Without this the setting would appear to do nothing: a contact whose modify time has not
         * moved is skipped outright, so the new mode would only ever reach contacts that happened to
         * change on Proton afterwards.
         */
        fun needsReapply(context: Context, accountName: String): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(reapplyKey(accountName), false)

        fun clearReapply(context: Context, accountName: String) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(reapplyKey(accountName))
                .apply()
        }
    }
}
