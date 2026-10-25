package me.proton.sync.billing

/**
 * What entering a subscription id in the direct version's dialog does, decided from the licence proxy's
 * answer for that id. Pure, so it can be tested; SubscriptionManager.activate does the asking and storing.
 *
 * An id the user types is checked before it is stored, so the answer decides whether it is stored at all:
 *  - Paddle names it a subscription of this product: stored, replacing whatever id was there.
 *  - Paddle says it is paused or unpaid: stored only when the id this phone has does not sync anyway. One
 *    paste must not turn a phone that syncs into one that does not; when sync is already off nothing is
 *    lost, and the newer id brings sync back by itself once Paddle resumes it or collects the payment.
 *  - A refusal or no answer: nothing is stored and nothing changes, so the id this phone has is never lost
 *    to a typo or an outage, and the user is told why under the field.
 *
 * A transaction id is looked up first ([TransactionLookup]). The subscription id it leads to goes through the
 * above as if it had been typed; a lookup that leads to none ends in one of the Transaction outcomes, or in
 * [NotChecked] for a fault, with nothing stored ([transactionOutcome]).
 */
internal sealed class ActivationOutcome {
    data class Activated(val trial: Boolean) : ActivationOutcome()
    data object StoredPaused : ActivationOutcome()
    data object StoredUnpaid : ActivationOutcome()
    data class KeptCurrent(val paused: Boolean) : ActivationOutcome()
    data class Refused(val error: String) : ActivationOutcome()
    data class NotChecked(val error: String) : ActivationOutcome()
    /** No subscription for the transaction id: Paddle does not know the transaction. */
    data object TransactionNotFound : ActivationOutcome()
    /** The transaction paid for another product. */
    data object TransactionWrongProduct : ActivationOutcome()
    /**
     * The transaction names no subscription: paid moments ago (Paddle is still creating it, worth another try in
     * a minute) or never paid (the subscription id is then the way on). The proxy does not tell the two apart.
     */
    data object TransactionNotReady : ActivationOutcome()
}

/** Whether the entered id is to be stored, and what the dialog says about it. Carries no id. */
internal data class ActivationPlan(val store: Boolean, val outcome: ActivationOutcome)

/**
 * The plan for an id other than the stored one. [storedIdSyncing] is true when an id is stored and the
 * subscription state lets sync run (Trial or Active).
 */
internal fun planActivation(verdict: LicenseVerdict, storedIdSyncing: Boolean): ActivationPlan = when (verdict) {
    is LicenseVerdict.Entitled -> ActivationPlan(true, ActivationOutcome.Activated(verdict.trial))
    LicenseVerdict.Unpaid ->
        if (storedIdSyncing) ActivationPlan(false, ActivationOutcome.KeptCurrent(paused = false))
        else ActivationPlan(true, ActivationOutcome.StoredUnpaid)
    LicenseVerdict.Suspended ->
        if (storedIdSyncing) ActivationPlan(false, ActivationOutcome.KeptCurrent(paused = true))
        else ActivationPlan(true, ActivationOutcome.StoredPaused)
    is LicenseVerdict.Ended -> ActivationPlan(false, ActivationOutcome.Refused(verdict.error))
    is LicenseVerdict.Unknown -> ActivationPlan(false, ActivationOutcome.NotChecked(verdict.error))
}

/**
 * What the dialog says when the stored id is entered again (the field is pre-filled with it). That is a
 * re-check, not a new id: SubscriptionManager.applyVerdict acts on the answer as on any settings visit's
 * check, so this only names the outcome and stores nothing itself.
 */
internal fun recheckOutcome(verdict: LicenseVerdict): ActivationOutcome = when (verdict) {
    is LicenseVerdict.Entitled -> ActivationOutcome.Activated(verdict.trial)
    LicenseVerdict.Unpaid -> ActivationOutcome.StoredUnpaid
    LicenseVerdict.Suspended -> ActivationOutcome.StoredPaused
    is LicenseVerdict.Ended -> ActivationOutcome.Refused(verdict.error)
    is LicenseVerdict.Unknown -> ActivationOutcome.NotChecked(verdict.error)
}

/**
 * What the dialog says when an entered transaction id leads to no subscription id, or null when it does
 * ([TransactionLookup.Resolved]: that id is then checked as if it had been entered). Nothing is stored for any
 * of these. invalid_request, the proxy's answer to text not shaped like a transaction id, is "not found" too:
 * the dialog sends only ids of that shape, as it does for a subscription id. A fault on the way, network or
 * proxy, is [ActivationOutcome.NotChecked] like a subscription id's.
 */
internal fun transactionOutcome(lookup: TransactionLookup): ActivationOutcome? = when (lookup) {
    is TransactionLookup.Resolved -> null
    is TransactionLookup.Refused ->
        if (lookup.error == "subscription_wrong_product") ActivationOutcome.TransactionWrongProduct
        else ActivationOutcome.TransactionNotFound
    TransactionLookup.NotReady -> ActivationOutcome.TransactionNotReady
    is TransactionLookup.Unknown -> ActivationOutcome.NotChecked(lookup.error)
}
