package me.proton.sync.billing

import com.android.billingclient.api.ProductDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offer the Play plan picker launches, and when it may say "free for 7 days". Getting this wrong
 * charges a customer on day 0 after promising a free week, promises a trial Google will not give, or
 * hands a free offer to accounts that should pay.
 */
class PlayOfferSelectionTest {

    private val monthly = SubscriptionManager.BASE_PLAN_MONTHLY
    private val yearly = SubscriptionManager.BASE_PLAN_ANNUAL
    private val infinite = PlayOfferSelection.INFINITE_RECURRING
    private val finite = 2

    private fun base(plan: String, price: String, period: String) = PlayOfferSelection.Offer(
        basePlanId = plan, offerId = null, offerTags = emptyList(), offerToken = "token-$plan-base",
        phases = listOf(PlayOfferSelection.Phase(1_990_000, price, period, infinite, 0)),
    )

    /** A free first phase, then the recurring price; as Google returned it on a device, P1W and one cycle. */
    private fun trial(
        plan: String,
        id: String?,
        price: String,
        period: String,
        trialPeriod: String = "P1W",
        cycles: Int = 1,
    ) = PlayOfferSelection.Offer(
        basePlanId = plan, offerId = id, offerTags = emptyList(), offerToken = "token-$plan-$id",
        phases = listOf(
            PlayOfferSelection.Phase(0, "Free", trialPeriod, finite, cycles),
            PlayOfferSelection.Phase(1_990_000, price, period, infinite, 0),
        ),
    )

    private val monthlyBase = base(monthly, "€1.99", "P1M")
    private val yearlyBase = base(yearly, "€19.99", "P1Y")
    private val monthlyTrial = trial(monthly, "trial-7d-monthly", "€1.99", "P1M")
    private val yearlyTrial = trial(yearly, "trial-7d-yearly", "€19.99", "P1Y")

    @Test
    fun `recurrence mode constant matches the billing library`() {
        assertEquals(ProductDetails.RecurrenceMode.INFINITE_RECURRING, PlayOfferSelection.INFINITE_RECURRING)
    }

    @Test
    fun `trial listed before the base offer is chosen`() {
        val choice = PlayOfferSelection.select(listOf(monthlyTrial, monthlyBase), monthly)!!
        assertEquals("trial-7d-monthly", choice.offer.offerId)
        assertTrue(choice.showsSevenDayTrial)
        assertEquals("€1.99", choice.recurringPrice)
    }

    @Test
    fun `trial listed after the base offer is still chosen`() {
        val offers = listOf(monthlyBase, yearlyBase, yearlyTrial, monthlyTrial)
        val choice = PlayOfferSelection.select(offers, yearly)!!
        assertEquals("trial-7d-yearly", choice.offer.offerId)
        assertEquals("token-yearly-auto-trial-7d-yearly", choice.offer.offerToken)
        assertTrue(choice.showsSevenDayTrial)
        assertEquals("€19.99", choice.recurringPrice)
        assertTrue(PlayOfferSelection.allShowSevenDayTrial(offers))
    }

    @Test
    fun `only the base offer, as for a past subscriber, gives the base plan and no trial text`() {
        val offers = listOf(monthlyBase, yearlyBase)
        val choice = PlayOfferSelection.select(offers, monthly)!!
        assertNull(choice.offer.offerId)
        assertFalse(choice.showsSevenDayTrial)
        assertEquals("€1.99", choice.recurringPrice)
        assertFalse(PlayOfferSelection.allShowSevenDayTrial(offers))
    }

    @Test
    fun `a trial on one base plan only keeps the shared texts plain`() {
        val offers = listOf(monthlyBase, yearlyBase, yearlyTrial)
        val plans = PlayOfferSelection.plans(offers)
        assertEquals(listOf(monthly, yearly), plans.map { it.offer.basePlanId })
        assertNull(plans[0].offer.offerId)
        assertFalse(plans[0].showsSevenDayTrial)
        assertEquals("trial-7d-yearly", plans[1].offer.offerId)
        assertTrue(plans[1].showsSevenDayTrial)
        // "No charge before day 7" and "Start 7-day free trial" would be false for the monthly plan.
        assertFalse(PlayOfferSelection.allShowSevenDayTrial(offers))
    }

    @Test
    fun `a paid introductory offer is never chosen over the base plan`() {
        val intro = PlayOfferSelection.Offer(
            basePlanId = monthly, offerId = "intro-half-price", offerTags = emptyList(), offerToken = "token-intro",
            phases = listOf(
                PlayOfferSelection.Phase(990_000, "€0.99", "P1M", finite, 3),
                PlayOfferSelection.Phase(1_990_000, "€1.99", "P1M", infinite, 0),
            ),
        )
        val choice = PlayOfferSelection.select(listOf(intro, monthlyBase), monthly)!!
        assertNull(choice.offer.offerId)
        assertFalse(choice.showsSevenDayTrial)
        assertEquals("€1.99", choice.recurringPrice)
    }

    @Test
    fun `a free offer that is not the known trial is never launched`() {
        // A developer-determined free promo reaches every account, past subscribers included.
        val promo = trial(monthly, "winback-free", "€1.99", "P1M")
        val choice = PlayOfferSelection.select(listOf(promo, monthlyBase), monthly)!!
        assertNull(choice.offer.offerId)
        assertFalse(choice.showsSevenDayTrial)
        // Nor the known id on the wrong plan.
        val swapped = trial(monthly, "trial-7d-yearly", "€1.99", "P1M")
        assertNull(PlayOfferSelection.select(listOf(swapped, monthlyBase), monthly)!!.offer.offerId)
        // With both present, the trial wins.
        assertEquals("trial-7d-monthly", PlayOfferSelection.select(listOf(promo, monthlyBase, monthlyTrial), monthly)!!.offer.offerId)
    }

    @Test
    fun `a free phase that is not exactly 7 days is never described as 7 days`() {
        val fourteenDays = trial(monthly, "trial-7d-monthly", "€1.99", "P1M", trialPeriod = "P14D")
        val choice = PlayOfferSelection.select(listOf(monthlyBase, fourteenDays), monthly)!!
        assertEquals("trial-7d-monthly", choice.offer.offerId)
        assertFalse(choice.showsSevenDayTrial)
        // One week for two cycles is 14 days.
        assertFalse(PlayOfferSelection.isSevenDayFreeTrial(trial(monthly, "x", "€1.99", "P1M", cycles = 2)))
        // P7D as configured in the Console counts as well as P1W as Google returns it.
        assertTrue(PlayOfferSelection.isSevenDayFreeTrial(trial(monthly, "x", "€1.99", "P1M", trialPeriod = "P7D")))
    }

    @Test
    fun `a paid step between the free week and the full price gets the plain wording`() {
        val stepped = PlayOfferSelection.Offer(
            basePlanId = monthly, offerId = "trial-7d-monthly", offerTags = emptyList(), offerToken = "t",
            phases = listOf(
                PlayOfferSelection.Phase(0, "Free", "P1W", finite, 1),
                PlayOfferSelection.Phase(990_000, "€0.99", "P1M", finite, 3),
                PlayOfferSelection.Phase(1_990_000, "€1.99", "P1M", infinite, 0),
            ),
        )
        val choice = PlayOfferSelection.select(listOf(monthlyBase, stepped), monthly)!!
        assertFalse(choice.showsSevenDayTrial)
        assertEquals("€1.99", choice.recurringPrice)
    }

    @Test
    fun `empty lists give nothing`() {
        assertNull(PlayOfferSelection.select(emptyList(), monthly))
        assertEquals(emptyList<PlayOfferSelection.Choice>(), PlayOfferSelection.plans(emptyList()))
        assertFalse(PlayOfferSelection.allShowSevenDayTrial(emptyList()))
        // A plan with offers but no pricing phases is not a free trial.
        val bare = PlayOfferSelection.Offer(monthly, null, emptyList(), "t", emptyList())
        val choice = PlayOfferSelection.select(listOf(bare), monthly)!!
        assertFalse(choice.showsSevenDayTrial)
        assertEquals("", choice.recurringPrice)
    }

    @Test
    fun `only when neither the trial nor a base offer exists is the first offer used`() {
        val intro = PlayOfferSelection.Offer(
            basePlanId = monthly, offerId = "intro", offerTags = emptyList(), offerToken = "token-intro",
            phases = listOf(PlayOfferSelection.Phase(990_000, "€0.99", "P1M", finite, 3)),
        )
        assertEquals("intro", PlayOfferSelection.select(listOf(intro), monthly)!!.offer.offerId)
    }
}
