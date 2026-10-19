package me.proton.sync.billing

/**
 * Which Google Play offer the plan picker launches for a base plan, and whether the app may promise a
 * free trial for it.
 *
 * `ProductDetails.subscriptionOfferDetails` holds, per base plan, the plain base-plan offer
 * (`offerId == null`) and the offers Google returns for this account, in no documented order. Taking
 * the first entry could launch the base plan and charge on day 0 while the app said "free for 7 days".
 *
 * Google filters offers whose eligibility it decides itself, such as the trials' "never had this
 * subscription": an account that had the subscription before gets no trial offer at all. It does NOT
 * filter offers with developer-determined eligibility; those reach every account and leave the choice
 * to the app. So a free offer is launched only when it is one of the known trial offers, never merely
 * because its first phase is free: a later free promo or win-back offer would otherwise be handed to
 * every past subscriber. A new trial offer id has to be added here to be used.
 *
 * Works on plain data because `ProductDetails` cannot be constructed in unit tests; the play
 * [BillingUi] maps it with `offersOf`.
 */
object PlayOfferSelection {

    /** `ProductDetails.RecurrenceMode.INFINITE_RECURRING`, pinned to the library's value by a unit test. */
    const val INFINITE_RECURRING = 1

    /** The free-trial offers configured in Play Console on 2026-09-17, one per base plan. */
    const val TRIAL_OFFER_MONTHLY = "trial-7d-monthly"
    const val TRIAL_OFFER_YEARLY = "trial-7d-yearly"

    /**
     * ISO 8601 periods the "7 days" wording may describe. Play Console takes P7D, and Google returns
     * the same phase as P1W (seen on a device on 2026-09-17).
     */
    private val SEVEN_DAYS = setOf("P7D", "P1W")

    data class Phase(
        val priceAmountMicros: Long,
        val formattedPrice: String,
        val billingPeriod: String,
        val recurrenceMode: Int,
        val billingCycleCount: Int,
    )

    data class Offer(
        val basePlanId: String,
        val offerId: String?,
        val offerTags: List<String>,
        val offerToken: String,
        val phases: List<Phase>,
    )

    data class Choice(val offer: Offer, val showsSevenDayTrial: Boolean) {
        /** The price charged once any introductory phase is over: the infinitely recurring phase. */
        val recurringPrice: String
            get() = (offer.phases.lastOrNull { it.recurrenceMode == INFINITE_RECURRING } ?: offer.phases.lastOrNull())
                ?.formattedPrice.orEmpty()
    }

    fun trialOfferId(basePlanId: String): String? = when (basePlanId) {
        SubscriptionManager.BASE_PLAN_MONTHLY -> TRIAL_OFFER_MONTHLY
        SubscriptionManager.BASE_PLAN_ANNUAL -> TRIAL_OFFER_YEARLY
        else -> null
    }

    /**
     * The offer to launch for [basePlanId]: its known trial offer when Google returned it with a free
     * first phase, else the plain base-plan offer, else, only when neither exists, the first offer for
     * the plan. A paid introductory offer, or any other free offer, is therefore never picked while the
     * trial or the base plan is available. Null when the plan has no offer at all.
     */
    fun select(offers: List<Offer>, basePlanId: String): Choice? {
        val forPlan = offers.filter { it.basePlanId == basePlanId }
        if (forPlan.isEmpty()) return null
        val trialId = trialOfferId(basePlanId)
        val chosen = forPlan.firstOrNull { trialId != null && it.offerId == trialId && hasFreeFirstPhase(it) }
            ?: forPlan.firstOrNull { it.offerId == null }
            ?: forPlan.first()
        return Choice(chosen, isSevenDayFreeTrial(chosen))
    }

    /** Monthly, then annual, as the picker lists them; a plan with no offer is left out. */
    fun plans(offers: List<Offer>): List<Choice> = listOfNotNull(
        select(offers, SubscriptionManager.BASE_PLAN_MONTHLY),
        select(offers, SubscriptionManager.BASE_PLAN_ANNUAL),
    )

    /**
     * Whether text shared by all plans (the picker's title and "no charge before day 7", the card's
     * "Start 7-day free trial") may promise the trial: only when every plan listed carries it. With a
     * trial on one plan only, those texts stay plain and the trial is named on its own row.
     */
    fun allShowSevenDayTrial(offers: List<Offer>): Boolean {
        val plans = plans(offers)
        return plans.isNotEmpty() && plans.all { it.showsSevenDayTrial }
    }

    fun hasFreeFirstPhase(offer: Offer): Boolean = offer.phases.firstOrNull()?.priceAmountMicros == 0L

    /**
     * Exactly what "Free for 7 days, then <price>" says: two phases, one free cycle of 7 days, then the
     * recurring price. A longer free period (several cycles), or a paid step before the full price,
     * gets the plain wording, because the text would misstate what is charged.
     */
    fun isSevenDayFreeTrial(offer: Offer): Boolean {
        val phases = offer.phases
        if (phases.size != 2) return false
        val (free, recurring) = phases
        return free.priceAmountMicros == 0L &&
            free.billingPeriod in SEVEN_DAYS &&
            free.billingCycleCount == 1 &&
            recurring.recurrenceMode == INFINITE_RECURRING &&
            recurring.priceAmountMicros > 0L
    }
}
