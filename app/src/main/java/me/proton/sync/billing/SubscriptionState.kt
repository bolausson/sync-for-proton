package me.proton.sync.billing

import me.proton.sync.R

/**
 * Device-wide subscription state. A single subscription covers every Proton
 * account registered on the device.
 *
 * Only [Trial] and [Active] states grant access to sync features. All other
 * states (including [Unknown] while Play Billing or, in the direct flavour,
 * the Paddle licence check has not answered yet) block sync so users are not
 * billed without a working gate.
 */
sealed class SubscriptionState {
    /** No answer yet from the subscription backend (Play Billing or the licence check), and no cached state. */
    object Unknown : SubscriptionState()

    /** No subscription ever seen on this install (no Play purchase, or no Paddle subscription id). */
    object NoSubscription : SubscriptionState()

    /**
     * 7-day free trial currently running.
     *
     * [scheduledChange] (direct flavour only): a cancellation or pause Paddle will apply; see [ScheduledChange].
     */
    data class Trial(
        val expiresAtMs: Long,
        val productId: String,
        val scheduledChange: ScheduledChange? = null,
    ) : SubscriptionState()

    /**
     * Paid subscription currently active.
     *
     * [paymentFailed] (direct flavour only): Paddle reports the subscription past due. Its last renewal
     * payment failed and Paddle is retrying the card for up to 30 days. Sync keeps running meanwhile, as
     * Paddle recommends, and the settings card asks the user to update the payment method.
     *
     * [scheduledChange] (direct flavour only): a cancellation or pause Paddle will apply; see [ScheduledChange].
     */
    data class Active(
        val expiresAtMs: Long,
        val productId: String,
        val paymentFailed: Boolean = false,
        val scheduledChange: ScheduledChange? = null,
    ) : SubscriptionState()

    /**
     * Payment failed, in a grace period before expiry. Sync is blocked, as in every state but [Trial] and
     * [Active].
     *
     * Neither flavour enters this state. The play version reports every purchased subscription as [Active]:
     * on the device, Play Billing shows a subscription in its grace period as an ordinary purchase. The direct
     * version keeps a past-due Paddle subscription [Active] with [Active.paymentFailed], because Paddle keeps
     * access open while it retries the payment. Both still persist and restore the state under its name, and
     * the settings card has wording for it.
     */
    data class Grace(val expiresAtMs: Long, val productId: String) : SubscriptionState()

    /**
     * Subscription expired or cancelled past its grace period.
     *
     * [paused] (direct flavour only): Paddle paused the subscription. Sync is off, but unlike a
     * cancellation the subscription id is kept and re-checked, so sync comes back by itself if the
     * subscription is resumed.
     *
     * [paymentFailed] (direct flavour only): the subscription is past due and has never paid (the first
     * charge after the trial failed), or has been past due too long. Sync is off and the id is kept;
     * once Paddle collects, the next licence check (every app start, every visit to settings, and a
     * second look shortly after coming back to settings) turns sync back on.
     */
    data class Expired(
        val productId: String,
        val paused: Boolean = false,
        val paymentFailed: Boolean = false,
    ) : SubscriptionState()

    /** Sync is allowed in Trial and Active states only. */
    val isActive: Boolean
        get() = this is Trial || this is Active
}

/**
 * A change Paddle has scheduled for the subscription (direct flavour only), from `scheduled_change` in the
 * licence proxy's /validate answer: the subscription ends ([Action.CANCEL]) or pauses ([Action.PAUSE]) at
 * [effectiveAtMs], usually the end of the period already paid for. Sync runs until then, and the settings card
 * shows the date instead of a renewal that will not happen.
 *
 * Paddle also schedules a resume for a paused subscription. The card has nothing to say about that, so it is
 * not kept.
 */
data class ScheduledChange(val action: Action, val effectiveAtMs: Long) {
    enum class Action(val wire: String) {
        CANCEL("cancel"),
        PAUSE("pause");

        companion object {
            /** The action Paddle calls [wire], or null for one the app does not show. */
            fun fromWire(wire: String?): Action? = entries.firstOrNull { it.wire == wire }
        }
    }
}

/** The settings card's line for [change], to be filled in with its date. */
internal fun scheduledChangeLabel(change: ScheduledChange): Int = when (change.action) {
    ScheduledChange.Action.CANCEL -> R.string.label_subscription_ends
    ScheduledChange.Action.PAUSE -> R.string.label_subscription_pauses
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Whole days left of a trial that ends at [expiresAtMs], rounded up: a trial that started a minute ago has 7,
 * and the last day reads 1 until it ends. 0 once the end has passed and the next licence check has not yet
 * moved the state on. Written out by hand because Math.ceilDiv needs a newer Android than minSdk 29.
 */
internal fun trialDaysLeft(expiresAtMs: Long, nowMs: Long): Int {
    val left = expiresAtMs - nowMs
    if (left <= 0) return 0
    return ((left + DAY_MS - 1) / DAY_MS).toInt()
}
