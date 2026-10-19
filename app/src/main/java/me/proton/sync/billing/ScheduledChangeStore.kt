package me.proton.sync.billing

import android.content.SharedPreferences
import me.proton.sync.SyncPreferences

/**
 * Where the direct flavour keeps the change Paddle has scheduled ([ScheduledChange]) between runs. Written
 * with every state the subscription manager persists, so a record never outlives the state it belongs to
 * and is gone once /validate stops reporting the change. Kept apart from the manager so it can be tested.
 */
internal object ScheduledChangeStore {

    /** Record the change [state] carries, or remove the record when it carries none (any state but Trial and Active). */
    fun write(editor: SharedPreferences.Editor, state: SubscriptionState) {
        val change = state.scheduledChangeOrNull()
        if (change == null) {
            editor.remove(SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE)
            editor.remove(SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE_AT)
        } else {
            editor.putString(SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE, change.action.wire)
            editor.putLong(SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE_AT, change.effectiveAtMs)
        }
    }

    /** The recorded change, or null when there is none or it cannot be read (an unknown action, no date). */
    fun read(prefs: SharedPreferences): ScheduledChange? {
        val action = ScheduledChange.Action.fromWire(prefs.getString(SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE, null))
        val at = prefs.getLong(SyncPreferences.KEY_SUBSCRIPTION_SCHEDULED_CHANGE_AT, 0L)
        return if (action != null && at > 0L) ScheduledChange(action, at) else null
    }
}

/** The change Paddle has scheduled, as far as this state records one: only Trial and Active do. */
internal fun SubscriptionState.scheduledChangeOrNull(): ScheduledChange? = when (this) {
    is SubscriptionState.Trial -> scheduledChange
    is SubscriptionState.Active -> scheduledChange
    else -> null
}

/**
 * This state without what Paddle said about one particular subscription id: payment failed, paused, a
 * scheduled change. For a newly entered id, which the proxy has not said anything about yet.
 */
internal fun SubscriptionState.withoutPaddleDetails(): SubscriptionState = when (this) {
    is SubscriptionState.Trial -> if (scheduledChange != null) copy(scheduledChange = null) else this
    is SubscriptionState.Active ->
        if (paymentFailed || scheduledChange != null) copy(paymentFailed = false, scheduledChange = null) else this
    is SubscriptionState.Expired -> if (paused || paymentFailed) copy(paused = false, paymentFailed = false) else this
    else -> this
}
