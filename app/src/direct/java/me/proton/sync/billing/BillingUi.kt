package me.proton.sync.billing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.proton.sync.R
import me.proton.sync.SyncLog
import me.proton.sync.settings.SyncSettingsActivity

/**
 * direct-flavor purchase/manage UI (no Google Play Billing). Payment happens on the
 * Paddle-hosted web checkout; the user pastes the resulting subscription id (`sub_xxx`)
 * back into the app to activate sync. Mirrors the neutral entry points of the play
 * [BillingUi] so the shared settings screen is flavor-agnostic.
 */
object BillingUi {

    private const val TAG = "BillingUi"

    /**
     * The /manage-url fetch on its way, so that a second tap, or a debug verb, while it runs starts nothing.
     * Cleared when the fetch ends, so it never holds on to a closed screen.
     */
    @Volatile
    private var manageFetch: Job? = null

    /**
     * Fetch Paddle's customer-portal links for the subscription (update payment method / cancel) and
     * present a small chooser. Each link is a Paddle-hosted page; we host no management UI of our own.
     */
    fun openManageSubscription(
        activity: SyncSettingsActivity,
        sm: SubscriptionManager,
        state: SubscriptionState
    ) {
        withManageLinks(activity, sm) { links -> showManageChooser(activity, links) }
    }

    /**
     * The payment-failed card's button: straight to Paddle's page for a new payment method, the one
     * thing that clears a past-due subscription, instead of the chooser that also offers cancelling.
     * The links carry no sign-in token: Paddle asks whoever opens one for the email address the
     * subscription was bought with and sends a sign-in code there (the card says so, see [manageNote]).
     * Nothing in them expires, but they are still fetched on every tap rather than kept, so what opens
     * is what Paddle offers for the subscription at that moment.
     * When Paddle offers no such page for the subscription, the chooser shows what it does offer,
     * rather than a "try again" that would fail the same way every time.
     */
    @Suppress("UNUSED_PARAMETER")
    fun openUpdatePayment(activity: SyncSettingsActivity, sm: SubscriptionManager, state: SubscriptionState) {
        withManageLinks(activity, sm) { links ->
            val url = links.updatePaymentMethod
            if (url != null) openUrl(activity, url) else showManageChooser(activity, links)
        }
    }

    /**
     * The card's note under its Manage subscription button, read before either button opens Paddle's portal:
     * Paddle asks for the email address the subscription was bought with, and its emails name the seller as
     * BEO, a name customers may not recognise.
     */
    fun manageNote(context: Context): String? = context.getString(R.string.spp_manage_note)

    /**
     * Fetch the links behind a progress dialog and hand them to [onLinks], or say why there are none:
     * nothing to manage, the subscription service unavailable, or no connection. One fetch at a time: a
     * tap while one runs starts nothing, and the dialog's Cancel drops the answer.
     */
    private fun withManageLinks(
        activity: SyncSettingsActivity,
        sm: SubscriptionManager,
        onLinks: (ManageLinks.Offered) -> Unit,
    ) {
        if (manageFetch?.isActive == true) {
            SyncLog.i(TAG, "Manage links: already being fetched; this tap starts nothing")
            return
        }
        // Shown before the fetch starts: on Main.immediate a fetch that ends at once would otherwise run first,
        // and the spinner would come up after it, with nothing left to close it.
        val progress = manageProgressDialog(activity)
        progress.show()
        // A screen recreated mid-fetch (a rotation, dark mode, a language change) cancels the fetch with its
        // scope, so nothing after it runs: close the dialog while its window still exists, or Android reports a
        // leaked window. The answer is dropped with the screen.
        val closeOnDestroy = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) progress.dismiss()
        }
        activity.lifecycle.addObserver(closeOnDestroy)
        val job = activity.lifecycleScope.launch {
            val links = sm.fetchManageLinks()
            activity.lifecycle.removeObserver(closeOnDestroy)
            progress.dismiss()
            when (links) {
                is ManageLinks.Offered -> onLinks(links)
                is ManageLinks.NothingToManage -> toast(activity, R.string.spp_manage_error)
                is ManageLinks.Unavailable -> toast(activity, R.string.spp_manage_unavailable)
                is ManageLinks.NoConnection -> toast(activity, R.string.spp_manage_no_connection)
            }
        }
        manageFetch = job
        job.invokeOnCompletion { if (manageFetch === job) manageFetch = null }
        progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            job.cancel()
            activity.lifecycle.removeObserver(closeOnDestroy)
            progress.dismiss()
        }
    }

    /** A spinner and "Contacting Paddle", as the backup screen shows its runs, with a Cancel. */
    private fun manageProgressDialog(activity: SyncSettingsActivity): AlertDialog {
        val d = activity.resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, (8 * d).toInt())
            addView(ProgressBar(activity).apply {
                val s = (28 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s)
            })
            addView(TextView(activity).apply {
                text = activity.getString(R.string.spp_manage_loading)
                setPadding((16 * d).toInt(), 0, 0, 0)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, activity.resources.getDimension(R.dimen.sfp_text_row_title))
            })
        }
        return AlertDialog.Builder(activity)
            .setView(row)
            .setNegativeButton(R.string.btn_cancel, null)
            .setCancelable(false)
            .create()
    }

    private fun showManageChooser(
        activity: SyncSettingsActivity,
        links: ManageLinks.Offered,
    ) {
        val items = mutableListOf<Pair<String, String>>()  // (label, url)
        links.updatePaymentMethod?.let {
            items.add(activity.getString(R.string.spp_manage_action_update_payment) to it)
        }
        links.cancel?.let {
            items.add(activity.getString(R.string.spp_manage_action_cancel) to it)
        }
        if (items.isEmpty()) {
            toast(activity, R.string.spp_manage_error)
            return
        }
        val labels = items.map { it.first }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.spp_manage_dialog_title)
            .setItems(labels) { _, which ->
                openUrl(activity, items[which].second)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** Open web checkout and/or paste an existing subscription id to activate it. */
    fun showPurchaseOptions(activity: SyncSettingsActivity, sm: SubscriptionManager) {
        val density = activity.resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padTop = (8 * density).toInt()
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.spp_subscribe_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setText(sm.currentSubId().orEmpty())
        }
        // Wrap the field in a container that supplies only the horizontal/top
        // insets (so it lines up with the dialog title/message). The EditText keeps
        // its own background padding, which keeps the hint text above the underline
        // instead of sitting on top of it.
        val container = FrameLayout(activity).apply {
            setPadding(padH, padTop, padH, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.spp_subscribe_dialog_title)
            .setMessage(R.string.spp_subscribe_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.spp_subscribe_save) { _, _ ->
                val subId = input.text?.toString()?.trim().orEmpty()
                if (subId.isNotEmpty()) {
                    sm.applyLicense(subId)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.spp_subscribe_validating),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNeutralButton(R.string.spp_subscribe_open_checkout) { _, _ ->
                openUrl(activity, SubscriptionManager.CHECKOUT_URL)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * Status line and button of the subscription card when there is no subscription. The Paddle
     * checkout always starts with the 7-day free trial, so the direct version always says so.
     */
    fun noSubscriptionTexts(context: android.content.Context, sm: SubscriptionManager): Pair<String, String> =
        context.getString(R.string.label_subscription_none) to context.getString(R.string.btn_start_trial)

    /** Debug builds: Google Play offers do not exist in this version; logs that, and the card wording. */
    fun debugLogOffers(activity: SyncSettingsActivity, sm: SubscriptionManager) {
        val (status, button) = noSubscriptionTexts(activity, sm)
        me.proton.sync.SyncLog.i(
            "DebugCommand",
            "billing_offers: not applicable in the direct flavor (Paddle checkout, no Google Play offers); " +
                "card: \"$status\" / \"$button\""
        )
    }

    /** Debug builds: feed a licence answer through the real handling (see SubscriptionManager.debugApplyFixture). */
    @Suppress("UNUSED_PARAMETER")
    fun debugLicenseFixture(activity: SyncSettingsActivity, sm: SubscriptionManager, status: String) {
        sm.debugApplyFixture(status)
    }

    /** Debug builds: there is no Google purchase sheet in this version. */
    @Suppress("UNUSED_PARAMETER")
    fun debugLaunchSheet(activity: SyncSettingsActivity, sm: SubscriptionManager, yearly: Boolean) {
        me.proton.sync.SyncLog.i("DebugCommand", "billing_sheet: not applicable in the direct flavor (Paddle checkout)")
    }

    /**
     * Debug builds: the card's Manage subscription button, or its Update payment method button when
     * [updatePayment], through the very handler the button calls, progress dialog and all.
     */
    fun debugManage(activity: SyncSettingsActivity, sm: SubscriptionManager, updatePayment: Boolean) {
        val state = sm.currentState()
        val verb = if (updatePayment) "billing_update_payment" else "billing_manage"
        SyncLog.i("DebugCommand", "$verb: as the card's button, subscription state ${state.javaClass.simpleName}")
        if (updatePayment) openUpdatePayment(activity, sm, state) else openManageSubscription(activity, sm, state)
    }

    /**
     * Open [url] in the browser if this version opens it at all ([isAllowedBillingLink]): Paddle's customer
     * portal or this app's website. Anything else is refused and logged by host, and the user is told the
     * subscription service is unavailable, which is what a link the proxy must never send means for them.
     */
    private fun openUrl(activity: SyncSettingsActivity, url: String) {
        if (!isAllowedBillingLink(url)) {
            SyncLog.w(TAG, "Refused to open a link to ${linkForLog(url)}: not https on an allowed host")
            toast(activity, R.string.spp_manage_unavailable)
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            SyncLog.w(TAG, "No app opened ${linkForLog(url)}: ${e.javaClass.simpleName}")
            toast(activity, R.string.spp_no_browser)
        }
    }

    private fun toast(activity: SyncSettingsActivity, message: Int) {
        Toast.makeText(activity, activity.getString(message), Toast.LENGTH_LONG).show()
    }
}
