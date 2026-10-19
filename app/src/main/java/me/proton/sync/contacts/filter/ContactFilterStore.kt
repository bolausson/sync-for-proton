package me.proton.sync.contacts.filter

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences

/**
 * Reads and writes the per-account filter rules.
 *
 * Stored as a gson JSON string in the default SharedPreferences under a key ending in the account
 * name. That suffix is load-bearing: `ProtonSyncApp.renameAccountInPrefs` rewrites keys on an
 * account rename with a regex anchored on `_<oldName>` and re-puts string values unchanged, so a
 * string-valued suffixed key survives a rename with no extra code. The same shape also round-trips
 * through `.sppbackup`, whose importer writes each value with the put of its key's declared type
 * (BackupSettings.writeImported): this key is text, so the rules come back as the JSON text they were
 * saved as. A hand-edited backup that holds the rules as a JSON object rather than as text, or as
 * true or false, has them skipped and logged by key, never stored as another type.
 */
object ContactFilterStore {

    private const val TAG = "ContactFilter"
    private val gson = Gson()

    /**
     * Load the rules for an account, **failing open**.
     *
     * Any problem at all yields [FilterRuleSet.EMPTY], which means "sync everything". Failing the
     * other way would let a corrupt preference silently strip an address book, so a rule set that
     * cannot be understood is treated as no rule set rather than as an aggressive one.
     */
    fun load(context: Context, accountName: String): FilterRuleSet {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(SyncPreferences.contactFilterRulesKey(accountName), null)
        if (raw.isNullOrBlank()) return FilterRuleSet.EMPTY
        return try {
            sanitise(gson.fromJson(raw, FilterRuleSet::class.java)) ?: FilterRuleSet.EMPTY
        } catch (e: Exception) {
            SyncLog.w(TAG, "Unreadable contact filter for $accountName, syncing everything: ${e.message}")
            FilterRuleSet.EMPTY
        }
    }

    fun save(context: Context, accountName: String, ruleSet: FilterRuleSet) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(SyncPreferences.contactFilterRulesKey(accountName), gson.toJson(ruleSet))
            .putBoolean(SyncPreferences.contactFilterDirtyKey(accountName), true)
            .apply()
        SyncLog.i(
            TAG,
            "Saved contact filter for $accountName: ${ruleSet.rules.size} rule(s), " +
                "default=${ruleSet.defaultAction}, revision=${ruleSet.revision}"
        )
    }

    /** True when the rules changed since the last sync read them, so cached verdicts are stale. */
    fun isDirty(context: Context, accountName: String): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(SyncPreferences.contactFilterDirtyKey(accountName), false)

    fun clearDirty(context: Context, accountName: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(SyncPreferences.contactFilterDirtyKey(accountName))
            .apply()
    }

    // --- Local-only contacts the upload filter declined to send -----------------------------
    //
    // See SyncPreferences.contactFilterKeptLocalKey for why this exists: without it, a contact the
    // user creates that their own upload rules exclude is deleted by the orphan sweep on the next
    // sync, because the upload path cleared its dirty flag and it has no mapping.

    fun keptLocalIds(context: Context, accountName: String): Set<Long> =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(SyncPreferences.contactFilterKeptLocalKey(accountName), emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    fun addKeptLocal(context: Context, accountName: String, rawContactId: Long) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = SyncPreferences.contactFilterKeptLocalKey(accountName)
        val current = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (current.add(rawContactId.toString())) {
            prefs.edit().putStringSet(key, current).apply()
        }
    }

    /**
     * Forget ids that no longer need protecting.
     *
     * Called with the ids still present locally *and* still excluded. Anything else drops out: a
     * contact the user deleted, or one that a rules change now allows to upload, in which case the
     * ordinary machinery should own it again.
     */
    fun retainKeptLocal(context: Context, accountName: String, stillKept: Set<Long>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = SyncPreferences.contactFilterKeptLocalKey(accountName)
        val current = prefs.getStringSet(key, emptySet()).orEmpty()
        val retained = current.filter { it.toLongOrNull() in stillKept }.toSet()
        if (retained.size != current.size) {
            prefs.edit().putStringSet(key, retained).apply()
        }
    }

    /**
     * Drop anything gson could not make sense of.
     *
     * Gson yields **null** rather than throwing for an unknown enum constant, so a rule set written
     * by a newer version can arrive with null fields and null operators sitting inside non-null
     * Kotlin types. Left alone those become a `NullPointerException` in the middle of a sync. A
     * condition that cannot be understood is dropped, and a rule left with no conditions is dropped
     * with it, because the evaluator treats a conditionless rule as never matching and a silently
     * weakened rule is worse than an absent one.
     */
    private fun sanitise(parsed: FilterRuleSet?): FilterRuleSet? {
        if (parsed == null) return null
        @Suppress("SENSELESS_COMPARISON")
        val defaultAction = parsed.defaultAction ?: RuleAction.SYNC
        val rules = parsed.rules.orEmpty().mapNotNull { rule ->
            @Suppress("SENSELESS_COMPARISON")
            if (rule == null || rule.id == null || rule.action == null || rule.direction == null) {
                return@mapNotNull null
            }
            val conditions = rule.conditions.orEmpty().filter { c ->
                @Suppress("SENSELESS_COMPARISON")
                c != null && c.field != null && c.op != null && c.op.isValidFor(c.field)
            }
            if (conditions.size != rule.conditions.orEmpty().size) {
                SyncLog.w(TAG, "Dropped unreadable condition(s) from rule '${rule.name}'")
            }
            if (conditions.isEmpty()) null
            else rule.copy(
                conditions = conditions,
                match = rule.match ?: Combinator.ALL,
            )
        }
        return parsed.copy(defaultAction = defaultAction, rules = rules)
    }
}
