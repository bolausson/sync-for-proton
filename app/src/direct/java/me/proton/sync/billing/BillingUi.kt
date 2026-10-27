package me.proton.sync.billing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
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
     * subscription was bought with and emails a sign-in link there (the card says so, see [manageNote]).
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
     * Paddle asks for the email address the subscription was bought with and emails a sign-in link there.
     */
    fun manageNote(context: Context): String? = context.getString(R.string.spp_manage_note)

    /**
     * The status line of an active subscription's card, in place of "renews <date>": none here, because the
     * licence check returns Paddle's renewal date (current_period_ends_at), so the card shows that.
     */
    @Suppress("UNUSED_PARAMETER")
    fun activeStatusText(context: Context): String? = null

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

    /**
     * The card's Enter subscription ID dialog: takes a Paddle subscription id (sub_…) to activate sync on this
     * phone, or the transaction id from Paddle's invoice (txn_…), which the licence proxy turns into the
     * subscription id. The field is pre-filled with the stored id, the only place the app shows it, which a second
     * phone needs. Buying happens on the checkout page ([startPurchase]); the dialog only takes the id.
     *
     * The card offers it ([enterIdLabel]) with no subscription, on the trial, and when the subscription is
     * paused, unpaid or expired; not while it is active (past due included) or in grace, where sync runs and
     * the card offers Paddle's portal instead. The debug verbs plan-picker and license-enter open it too.
     *
     * The dialog stays open until an id is taken. Text that is not an id is answered under the field without
     * asking anyone ([parseSubscriptionEntry]). An id is checked with the licence proxy before it is stored
     * ([SubscriptionManager.activate]): a spinner while it runs, then the dialog closes with a toast, or the
     * reason shows under the field with the text kept for a correction. A refused or unanswered entry never
     * touches the id this phone has.
     */
    fun showPurchaseOptions(activity: SyncSettingsActivity, sm: SubscriptionManager) {
        showActivationDialog(activity, sm)
    }

    /** A debug verb's entry: [text] goes into the field and is submitted as the button would; [onResult] hears how it went. */
    private class DebugEntry(
        val text: String,
        val offline: Boolean,
        val onResult: (result: String, message: String) -> Unit,
    )

    /** [showPurchaseOptions]'s dialog, shared with [debugLicenseEnter], which hands in a [debug] entry. */
    private fun showActivationDialog(activity: SyncSettingsActivity, sm: SubscriptionManager, debug: DebugEntry? = null) {
        val density = activity.resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padTop = (8 * density).toInt()
        // Read once: the parser must know what the field started with, even if a licence check removes the stored id
        // while the dialog is open.
        val prefilledId = sm.currentSubId()
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.spp_subscribe_hint)
            // No suggestions: an id is not a word, and a keyboard's correction could change it. Not a password
            // field either, where some keyboards hide their paste suggestion; the parser takes care of case.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            // One character more than the parser reads, so a longer paste is answered as too long instead of being
            // cut silently. Set after setSingleLine(): on Android 13 and later that adds a cut at 5,000 characters
            // of its own unless the field has a length filter, and this replaces it (other filters stay).
            filters = filters.filterNot { it is InputFilter.LengthFilter }.toTypedArray() +
                InputFilter.LengthFilter(MAX_ENTRY_CHARS + 1)
            setText(prefilledId.orEmpty())
            // Focus selects the pre-filled id, so a paste replaces it instead of landing next to it.
            setSelectAllOnFocus(true)
        }
        val status = StatusLine(activity)
        // A container that supplies only the horizontal/top insets (so the field lines up with the dialog
        // title/message). The EditText keeps its own background padding, which keeps the hint text above the
        // underline instead of sitting on top of it. The status line sits under the field.
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padTop, padH, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(status.row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.spp_subscribe_dialog_title)
            .setMessage(R.string.spp_subscribe_dialog_message)
            .setView(container)
            // No listener here: one set on the builder closes the dialog on every tap. Set after show() instead.
            .setPositiveButton(R.string.spp_subscribe_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        // A screen recreated mid-check (a rotation, dark mode, a language change) cancels the wait with its
        // scope: close the dialog while its window still exists, or Android reports a leaked window. The check
        // itself runs on in the manager, and the recreated screen's card shows what it found.
        val closeOnDestroy = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        activity.lifecycle.addObserver(closeOnDestroy)
        dialog.setOnDismissListener { activity.lifecycle.removeObserver(closeOnDestroy) }
        dialog.show()

        val activate = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        var busy = false
        fun updateButton() {
            activate.isEnabled = !busy && !input.text.isNullOrBlank()
        }
        fun setBusy(on: Boolean) {
            busy = on
            input.isEnabled = !on
            if (on) status.showBusy() else status.hide()
            updateButton()
        }
        fun submit() {
            val raw = input.text?.toString().orEmpty()
            // The stored id the field starts with: a transaction id pasted beside it is looked up, not the old id.
            val entry = parseSubscriptionEntry(raw, prefilled = prefilledId)
            // The kind and the length only: the text may be an email address or a real id.
            SyncLog.i(
                TAG,
                "Subscription id entered: ${entry.kind}, ${raw.length} characters" +
                    if (entry is SubscriptionEntry.Sendable) "" else "; not sent to the licence proxy"
            )
            if (entry !is SubscriptionEntry.Sendable) {
                val message = entryMessage(entry)
                status.showError(message)
                debug?.onResult?.invoke(entry.kind, activity.getString(message))
                return
            }
            // The same spinner for a transaction id: its lookup and the check of the id it leads to are one wait.
            setBusy(true)
            activity.lifecycleScope.launch {
                val outcome = sm.activate(entry, debugOffline = debug?.offline == true)
                setBusy(false)
                val (closes, message) = outcomeUi(outcome)
                if (closes) {
                    dialog.dismiss()
                    toast(activity, message)
                } else {
                    status.showError(message)
                }
                debug?.onResult?.invoke(outcome.toString(), activity.getString(message))
            }
        }

        updateButton()
        // Editing the text clears the message about the old text.
        input.doAfterTextChanged {
            if (!busy) {
                status.hide()
                updateButton()
            }
        }
        activate.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            if (activate.isEnabled && !busy) submit()
            true
        }
        if (debug != null) {
            input.setText(debug.text)
            submit()
        }
    }

    /** What the dialog says about text that is neither one subscription id nor one transaction id. */
    private fun entryMessage(entry: SubscriptionEntry): Int = when (entry) {
        SubscriptionEntry.PlayOrder -> R.string.spp_subscribe_error_play_order
        SubscriptionEntry.MistypedTransactionId -> R.string.spp_subscribe_error_transaction_mistyped
        SubscriptionEntry.Email -> R.string.spp_subscribe_error_email
        SubscriptionEntry.MistypedSubId -> R.string.spp_subscribe_error_mistyped
        SubscriptionEntry.SeveralIds -> R.string.spp_subscribe_error_several_ids
        SubscriptionEntry.TooLong -> R.string.spp_subscribe_error_too_long
        SubscriptionEntry.Empty, SubscriptionEntry.NotAnId, is SubscriptionEntry.Valid, is SubscriptionEntry.ValidTransaction ->
            R.string.spp_subscribe_error_not_an_id
    }

    /**
     * Whether the dialog closes on [outcome] (with a toast) or stays open, and the message either way. Here and
     * not next to [ActivationOutcome] because the strings are the direct version's.
     */
    private fun outcomeUi(outcome: ActivationOutcome): Pair<Boolean, Int> = when (outcome) {
        is ActivationOutcome.Activated -> true to R.string.spp_subscribe_activated
        ActivationOutcome.StoredPaused -> true to R.string.label_subscription_paused
        ActivationOutcome.StoredUnpaid -> true to R.string.label_subscription_unpaid
        is ActivationOutcome.KeptCurrent -> false to R.string.spp_subscribe_error_kept_current
        is ActivationOutcome.Refused -> false to when (outcome.error) {
            "subscription_wrong_product" -> R.string.spp_subscribe_error_wrong_product
            "subscription_canceled" -> R.string.spp_subscribe_error_cancelled
            // subscription_not_found, invalid_request and any other refusal.
            else -> R.string.spp_subscribe_error_not_found
        }
        is ActivationOutcome.NotChecked -> false to
            if (outcome.error == "network_error") R.string.spp_manage_no_connection else R.string.spp_manage_unavailable
        ActivationOutcome.TransactionNotFound -> false to R.string.spp_subscribe_error_transaction_not_found
        ActivationOutcome.TransactionWrongProduct -> false to R.string.spp_subscribe_error_transaction_wrong_product
        ActivationOutcome.TransactionNotReady -> false to R.string.spp_subscribe_error_transaction_not_ready
    }

    /**
     * The line under the subscription dialog's field: a spinner and "Checking your subscription" while an id
     * is checked, or the reason the text was not taken. Announced politely by screen readers when it changes.
     */
    private class StatusLine(private val activity: SyncSettingsActivity) {
        private val density = activity.resources.displayMetrics.density
        private val spinner = ProgressBar(activity).apply {
            val s = (16 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            visibility = View.GONE
        }
        private val text = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, activity.resources.getDimension(R.dimen.sfp_text_row_sub))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, (4 * density).toInt(), 0, 0)
            addView(spinner)
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        fun showBusy() {
            spinner.visibility = View.VISIBLE
            text.setPaddingRelative((8 * density).toInt(), 0, 0, 0)
            text.setTextColor(activity.getColor(R.color.sfp_text_secondary))
            text.text = activity.getString(R.string.spp_subscribe_validating)
            row.visibility = View.VISIBLE
        }

        fun showError(message: Int) {
            spinner.visibility = View.GONE
            text.setPaddingRelative(0, 0, 0, 0)
            text.setTextColor(activity.getColor(R.color.sfp_danger))
            text.text = activity.getString(message)
            row.visibility = View.VISIBLE
        }

        fun hide() {
            row.visibility = View.GONE
        }
    }

    /**
     * Status line and button of the subscription card when there is no subscription. The status line says
     * sync is off. The Paddle checkout always starts with the 7-day free trial, so the direct version always
     * says so; the button opens the checkout page itself ([startPurchase]), and the id goes in through
     * [enterIdLabel]'s button.
     */
    fun noSubscriptionTexts(context: android.content.Context, sm: SubscriptionManager): Pair<String, String> =
        context.getString(R.string.label_subscription_none) to context.getString(R.string.btn_start_trial)

    /**
     * The card's Start 7-day free trial and Renew: straight to the checkout page, where Paddle's trial starts.
     * The subscription id it ends with goes in through the card's Enter subscription ID button.
     */
    @Suppress("UNUSED_PARAMETER")
    fun startPurchase(activity: SyncSettingsActivity, sm: SubscriptionManager) {
        SyncLog.i(TAG, "Opening the checkout page")
        openUrl(activity, SubscriptionManager.CHECKOUT_URL)
    }

    /**
     * The card's Enter subscription ID button, which opens [showPurchaseOptions]: on the no-subscription, trial,
     * paused, unpaid and expired cards (SyncSettingsActivity.showEnterIdButton).
     */
    fun enterIdLabel(context: Context): String? = context.getString(R.string.spp_btn_enter_subscription_id)

    /**
     * No button on the trial card: a Paddle trial turns into the paid subscription by itself, and a checkout
     * from here would start a second one.
     */
    @Suppress("UNUSED_PARAMETER")
    fun upgradeLabel(context: Context): String? = null

    /** Debug builds: Google Play offers do not exist in this version; logs that, and the card wording. */
    fun debugLogOffers(activity: SyncSettingsActivity, sm: SubscriptionManager) {
        val (status, button) = noSubscriptionTexts(activity, sm)
        me.proton.sync.SyncLog.i(
            "DebugCommand",
            "billing_offers: not applicable in the direct flavor (Paddle checkout, no Google Play offers); " +
                "card: \"$status\" / \"$button\" (checkout page) / \"${enterIdLabel(activity)}\" (subscription id dialog)"
        )
    }

    /** Debug builds: feed a licence answer through the real handling (see SubscriptionManager.debugApplyFixture). */
    @Suppress("UNUSED_PARAMETER")
    fun debugLicenseFixture(activity: SyncSettingsActivity, sm: SubscriptionManager, status: String) {
        sm.debugApplyFixture(status)
    }

    /**
     * Debug builds: the subscription dialog's Activate with [text] in the field, through the button's own
     * submit, so the inline message can be checked on screen as well as in the log. {stored} and {STORED}
     * stand for the stored id (in lower or upper case), so a real licence never crosses adb; a real transaction
     * id comes in a file instead (the verb license-enter-file, read in SyncSettingsActivity). [offline] answers
     * the check of a different id, and a transaction lookup, with "no connection". An entry Paddle does not
     * accept never changes the stored id. Logs the outcome and whether the stored id changed, never an id or the text.
     */
    fun debugLicenseEnter(activity: SyncSettingsActivity, sm: SubscriptionManager, text: String, offline: Boolean) {
        if (!me.proton.sync.BuildConfig.DEBUG) return
        val before = sm.currentSubId()?.trim()
        if ((text.contains("{stored}") || text.contains("{STORED}")) && before.isNullOrEmpty()) {
            SyncLog.w("DebugCommand", "license_enter: no subscription id is stored, so {stored} has nothing to stand for")
            return
        }
        val raw = text.replace("{stored}", before.orEmpty()).replace("{STORED}", before.orEmpty().uppercase())
        SyncLog.i("DebugCommand", "license_enter: ${raw.length} characters into the subscription dialog${if (offline) ", offline" else ""}")
        showActivationDialog(activity, sm, DebugEntry(raw, offline) { result, message ->
            val after = sm.currentSubId()?.trim()
            val change = when {
                after == before -> "unchanged"
                after.isNullOrEmpty() -> "removed"
                before.isNullOrEmpty() -> "stored"
                else -> "replaced"
            }
            SyncLog.i("DebugCommand", "license_enter: $result -> \"$message\"; stored id $change")
        })
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
