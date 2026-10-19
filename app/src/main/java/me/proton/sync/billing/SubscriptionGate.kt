package me.proton.sync.billing

import android.content.Context
import androidx.preference.PreferenceManager
import me.proton.sync.SyncPreferences

/**
 * Flavor-agnostic read of the subscription gate from SharedPreferences, without a
 * billing client. Safe to call from sync adapters / workers on any thread.
 *
 * Both distribution flavors persist the same cached state name under
 * [SyncPreferences.KEY_SUBSCRIPTION_STATE]:
 *  - `play`   — written by the Google Play Billing [SubscriptionManager]
 *  - `direct` — written by the Paddle-backed [SubscriptionManager]
 *
 * so this single gate works for both. Returns true only when the last cached state
 * was `Trial` or `Active`. During early startup (before any successful refresh) it
 * returns false — sync stays blocked until the gate is confirmed.
 *
 * Hoisted out of `SubscriptionManager`'s companion so the two flavor implementations
 * can't drift on the gate logic.
 */
object SubscriptionGate {
    fun isSyncAllowedCached(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val name = prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_STATE, null) ?: return false
        return name == "Trial" || name == "Active"
    }
}
