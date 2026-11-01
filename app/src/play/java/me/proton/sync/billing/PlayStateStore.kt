package me.proton.sync.billing

import android.content.SharedPreferences
import me.proton.sync.SyncPreferences

/**
 * Where the play [SubscriptionManager] keeps its state between runs: SubscriptionGate reads the name, and the
 * manager's first value on the next start reads all of it. Kept apart from the manager, which needs a Context
 * and a BillingClient, so a unit test can write a state and read it back. The suspended flag is written with
 * every state, so it never outlives the Expired state it belongs to. Before 2.0.4 only the name, expiry and
 * product were kept, and a held subscription would have read back as plainly expired on every cold start until
 * Billing answered.
 */
internal object PlayStateStore {

    fun write(editor: SharedPreferences.Editor, s: SubscriptionState, nowMs: Long) {
        editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_LAST_CHECK, nowMs)
        editor.putString(SyncPreferences.KEY_SUBSCRIPTION_STATE, name(s))
        when (s) {
            is SubscriptionState.Trial -> {
                editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, s.expiresAtMs)
                editor.putString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, s.productId)
            }
            is SubscriptionState.Active -> {
                editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, s.expiresAtMs)
                editor.putString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, s.productId)
            }
            is SubscriptionState.Grace -> {
                editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, s.expiresAtMs)
                editor.putString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, s.productId)
            }
            is SubscriptionState.Expired -> {
                editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, 0L)
                editor.putString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, s.productId)
            }
            SubscriptionState.NoSubscription -> {
                editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, 0L)
                editor.remove(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT)
            }
            SubscriptionState.Unknown -> { /* do not persist */ }
        }
        editor.putBoolean(SyncPreferences.KEY_SUBSCRIPTION_SUSPENDED, s is SubscriptionState.Expired && s.suspended)
    }

    fun read(prefs: SharedPreferences): SubscriptionState {
        val name = prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_STATE, null) ?: return SubscriptionState.Unknown
        val expiry = prefs.getLong(SyncPreferences.KEY_SUBSCRIPTION_EXPIRY, 0L)
        val productId = prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_PRODUCT, null) ?: ""
        return when (name) {
            "Trial" -> SubscriptionState.Trial(expiry, productId)
            "Active" -> SubscriptionState.Active(expiry, productId)
            "Grace" -> SubscriptionState.Grace(expiry, productId)
            "Expired" -> SubscriptionState.Expired(
                productId,
                suspended = prefs.getBoolean(SyncPreferences.KEY_SUBSCRIPTION_SUSPENDED, false),
            )
            "NoSubscription" -> SubscriptionState.NoSubscription
            else -> SubscriptionState.Unknown
        }
    }

    fun name(s: SubscriptionState): String = when (s) {
        is SubscriptionState.Trial -> "Trial"
        is SubscriptionState.Active -> "Active"
        is SubscriptionState.Grace -> "Grace"
        is SubscriptionState.Expired -> "Expired"
        SubscriptionState.NoSubscription -> "NoSubscription"
        SubscriptionState.Unknown -> "Unknown"
    }
}
