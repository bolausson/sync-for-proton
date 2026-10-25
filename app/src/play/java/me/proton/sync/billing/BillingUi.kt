package me.proton.sync.billing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.sync.R
import me.proton.sync.SyncLog
import me.proton.sync.settings.SyncSettingsActivity

/**
 * play-flavor purchase/manage UI. Wraps Google Play Billing's `ProductDetails`-typed
 * plan picker and the Play subscriptions deep link, so the shared settings screen only
 * calls the flavor-neutral entry points below. The matching `direct` implementation
 * (Paddle web checkout + paste) lives in `src/direct`.
 *
 * What is offered and promised follows what Google returns for this account, never the
 * Play Console configuration: see [PlayOfferSelection].
 */
object BillingUi {

    private const val DEBUG_TAG = "DebugCommand"

    /** Open the Google Play subscription-management page for the current product. */
    fun openManageSubscription(
        activity: SyncSettingsActivity,
        sm: SubscriptionManager,
        state: SubscriptionState
    ) {
        val sku = when (state) {
            is SubscriptionState.Trial -> state.productId
            is SubscriptionState.Active -> state.productId
            is SubscriptionState.Grace -> state.productId
            is SubscriptionState.Expired -> state.productId
            else -> SubscriptionManager.PRODUCT_ID
        }
        val url = "https://play.google.com/store/account/subscriptions?sku=$sku&package=${activity.packageName}"
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.error_billing_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The payment-failed card's button. Only the direct version flags a failed payment on an active
     * subscription, but Google's subscription page is also where a Play payment method is fixed.
     */
    fun openUpdatePayment(activity: SyncSettingsActivity, sm: SubscriptionManager, state: SubscriptionState) =
        openManageSubscription(activity, sm, state)

    /** The direct version's note about signing in at Paddle; Google's subscription page needs none. */
    @Suppress("UNUSED_PARAMETER")
    fun manageNote(context: Context): String? = null

    /**
     * The status line of an active subscription's card. A Billing 8.3.0 `Purchase` carries no renewal date,
     * so the line names who manages the subscription instead of the direct version's "renews <date>": the
     * card's Manage subscription opens Google Play's page, which shows the real date.
     */
    fun activeStatusText(context: Context): String? = context.getString(R.string.label_subscription_active_play)

    /**
     * Status line and button of the subscription card when there is no subscription. The 7-day trial
     * wording only when Google offers this account that trial on every plan (an account that had this
     * subscription before gets none), judged from the cached product details; neutral wording until they
     * have loaded, and the card refreshes when they arrive (SubscriptionManager.productDetailsVersion).
     */
    fun noSubscriptionTexts(context: Context, sm: SubscriptionManager): Pair<String, String> {
        val details = sm.getCachedProductDetails()
        val trial = details != null && PlayOfferSelection.allShowSevenDayTrial(offersOf(details))
        return if (trial) {
            context.getString(R.string.label_subscription_none) to context.getString(R.string.btn_start_trial)
        } else {
            context.getString(R.string.label_subscription_required) to context.getString(R.string.btn_choose_plan)
        }
    }

    /** What the card's trial, plan and renew buttons open: the plan picker. */
    fun startPurchase(activity: SyncSettingsActivity, sm: SubscriptionManager) = showPurchaseOptions(activity, sm)

    /** The direct version's Enter subscription ID button; Google Play needs no id. */
    @Suppress("UNUSED_PARAMETER")
    fun enterIdLabel(context: Context): String? = null

    /** The trial card's button. */
    fun upgradeLabel(context: Context): String? = context.getString(R.string.btn_upgrade)

    /** The offers of [details] as plain data for [PlayOfferSelection]. */
    private fun offersOf(details: ProductDetails): List<PlayOfferSelection.Offer> =
        details.subscriptionOfferDetails.orEmpty().map { offer ->
            PlayOfferSelection.Offer(
                basePlanId = offer.basePlanId,
                offerId = offer.offerId,
                offerTags = offer.offerTags,
                offerToken = offer.offerToken,
                phases = offer.pricingPhases.pricingPhaseList.map { phase ->
                    PlayOfferSelection.Phase(
                        priceAmountMicros = phase.priceAmountMicros,
                        formattedPrice = phase.formattedPrice,
                        billingPeriod = phase.billingPeriod,
                        recurrenceMode = phase.recurrenceMode,
                        billingCycleCount = phase.billingCycleCount,
                    )
                },
            )
        }

    private data class PlanRow(
        val title: String,
        val subtitle: String,
        val offerToken: String
    )

    /** Show the monthly/annual plan picker and launch the Play purchase sheet on tap. */
    fun showPurchaseOptions(activity: SyncSettingsActivity, sm: SubscriptionManager) {
        CoroutineScope(Dispatchers.IO).launch {
            val details = sm.queryProducts() ?: sm.getCachedProductDetails()
            withContext(Dispatchers.Main) {
                if (details == null) {
                    Toast.makeText(activity, activity.getString(R.string.error_billing_unavailable), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val plans = PlayOfferSelection.plans(offersOf(details))
                val rows = plans.map { plan ->
                    val price = plan.recurringPrice
                    val monthly = plan.offer.basePlanId == SubscriptionManager.BASE_PLAN_MONTHLY
                    PlanRow(
                        title = activity.getString(if (monthly) R.string.plan_monthly_title else R.string.plan_annual_title, price),
                        subtitle = activity.getString(
                            when {
                                monthly && plan.showsSevenDayTrial -> R.string.plan_monthly_subtitle
                                monthly -> R.string.plan_monthly_price
                                plan.showsSevenDayTrial -> R.string.plan_annual_subtitle
                                else -> R.string.plan_annual_price
                            },
                            price
                        ),
                        offerToken = plan.offer.offerToken
                    )
                }
                if (rows.isEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.error_billing_unavailable), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                // The title and the disclosure speak for every row: "no charge before day 7" only when it
                // holds for each plan. A trial on one plan alone is named on that row only.
                val allTrial = plans.all { it.showsSevenDayTrial }

                val density = activity.resources.displayMetrics.density
                val dp = { n: Int -> (n * density).toInt() }

                val adapter = object : ArrayAdapter<PlanRow>(
                    activity,
                    android.R.layout.simple_list_item_2,
                    android.R.id.text1,
                    rows
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val v = super.getView(position, convertView, parent)
                        v.findViewById<TextView>(android.R.id.text1).apply {
                            text = rows[position].title
                            textSize = 16f
                        }
                        v.findViewById<TextView>(android.R.id.text2).apply {
                            text = rows[position].subtitle
                            textSize = 13f
                        }
                        return v
                    }
                }

                val listView = ListView(activity).apply {
                    this.adapter = adapter
                    divider = null
                }

                val disclosure = TextView(activity).apply {
                    text = activity.getString(
                        if (allTrial) R.string.plan_picker_disclosure else R.string.plan_picker_disclosure_plain
                    )
                    textSize = 12f
                    alpha = 0.6f
                    setPadding(dp(16), dp(8), dp(16), dp(4))
                }

                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(listView)
                    addView(disclosure)
                }

                val dialog = AlertDialog.Builder(activity)
                    .setTitle(if (allTrial) R.string.dialog_title_choose_plan else R.string.dialog_title_choose_plan_plain)
                    .setView(container)
                    .setNegativeButton(R.string.btn_cancel, null)
                    .create()

                listView.setOnItemClickListener { _, _, which, _ ->
                    sm.launchPurchaseFlow(activity, details, rows[which].offerToken)
                    dialog.dismiss()
                }

                dialog.show()
            }
        }
    }

    /**
     * Debug builds: log every offer Google returns for this account, each pricing phase, what the
     * selection picks per base plan, and the wording the picker and the card would use.
     */
    fun debugLogOffers(activity: SyncSettingsActivity, sm: SubscriptionManager) {
        CoroutineScope(Dispatchers.IO).launch {
            val details = awaitProductDetails(sm)
            if (details == null) {
                SyncLog.w(DEBUG_TAG, "billing_offers: no product details (Billing not connected, or the query failed)")
                return@launch
            }
            val offers = offersOf(details)
            SyncLog.i(DEBUG_TAG, "billing_offers: product ${details.productId}, ${offers.size} offer(s)")
            for (basePlan in offers.map { it.basePlanId }.distinct()) {
                for (offer in offers.filter { it.basePlanId == basePlan }) {
                    SyncLog.i(DEBUG_TAG, "billing_offers: $basePlan offerId=${offer.offerId ?: "null (base plan)"} offerTags=${offer.offerTags}")
                    offer.phases.forEachIndexed { i, p ->
                        SyncLog.i(
                            DEBUG_TAG,
                            "billing_offers:   phase $i priceAmountMicros=${p.priceAmountMicros} formattedPrice=${p.formattedPrice} " +
                                "billingPeriod=${p.billingPeriod} recurrenceMode=${p.recurrenceMode} billingCycleCount=${p.billingCycleCount}"
                        )
                    }
                }
                val choice = PlayOfferSelection.select(offers, basePlan)
                SyncLog.i(
                    DEBUG_TAG,
                    "billing_offers: $basePlan SELECTED offerId=${choice?.offer?.offerId ?: "null (base plan)"} " +
                        "trialText=${choice?.showsSevenDayTrial} recurringPrice=${choice?.recurringPrice}"
                )
            }
            val allTrial = PlayOfferSelection.allShowSevenDayTrial(offers)
            val (status, button) = noSubscriptionTexts(activity, sm)
            SyncLog.i(DEBUG_TAG, "billing_offers: picker shared trial wording=$allTrial; card: \"$status\" / \"$button\"")
        }
    }

    /** Debug builds: the licence fixtures are Paddle answers, which this version never gets. */
    @Suppress("UNUSED_PARAMETER")
    fun debugLicenseFixture(activity: SyncSettingsActivity, sm: SubscriptionManager, status: String) {
        SyncLog.i(DEBUG_TAG, "license_fixture: not applicable in the play flavor (Google Play Billing)")
    }

    /** Debug builds: subscription ids are Paddle's, which this version never takes. */
    @Suppress("UNUSED_PARAMETER")
    fun debugLicenseEnter(activity: SyncSettingsActivity, sm: SubscriptionManager, text: String, offline: Boolean) {
        SyncLog.i(DEBUG_TAG, "license_enter: not applicable in the play flavor (Google Play Billing)")
    }

    /** Debug builds: these verbs drive the direct version's Paddle portal links; this version opens Google's page. */
    @Suppress("UNUSED_PARAMETER")
    fun debugManage(activity: SyncSettingsActivity, sm: SubscriptionManager, updatePayment: Boolean) {
        val verb = if (updatePayment) "billing_update_payment" else "billing_manage"
        SyncLog.i(DEBUG_TAG, "$verb: not applicable in the play flavor (Google Play's subscription page)")
    }

    /** Debug builds: open Google's purchase sheet for the offer the picker would launch for the plan. */
    fun debugLaunchSheet(activity: SyncSettingsActivity, sm: SubscriptionManager, yearly: Boolean) {
        val basePlanId = if (yearly) SubscriptionManager.BASE_PLAN_ANNUAL else SubscriptionManager.BASE_PLAN_MONTHLY
        CoroutineScope(Dispatchers.IO).launch {
            val details = awaitProductDetails(sm)
            val choice = details?.let { PlayOfferSelection.select(offersOf(it), basePlanId) }
            if (details == null || choice == null) {
                SyncLog.w(DEBUG_TAG, "billing_sheet: no offer for base plan $basePlanId")
                return@launch
            }
            SyncLog.i(DEBUG_TAG, "billing_sheet: launching $basePlanId offerId=${choice.offer.offerId ?: "null (base plan)"} trialText=${choice.showsSevenDayTrial}")
            withContext(Dispatchers.Main) { sm.launchPurchaseFlow(activity, details, choice.offer.offerToken) }
        }
    }

    /** Product details, connecting first if needed; null after about ten seconds without them. */
    private suspend fun awaitProductDetails(sm: SubscriptionManager): ProductDetails? {
        repeat(20) { attempt ->
            sm.queryProducts()?.let { return it }
            if (attempt == 0) sm.connect()
            delay(500)
        }
        return null
    }
}
