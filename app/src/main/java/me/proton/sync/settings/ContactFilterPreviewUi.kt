package me.proton.sync.settings

import android.accounts.AccountManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.sync.ProtonSyncApp
import me.proton.sync.R
import me.proton.sync.backup.BackupDependenciesEntryPoint
import me.proton.sync.contacts.filter.ContactFilterPreview
import me.proton.sync.contacts.filter.ContactFilterServerPreview
import me.proton.sync.contacts.filter.FilterRuleSet
import me.proton.sync.db.SyncDatabase

/**
 * Presentation for the dry run, shared by the rules list and the rule editor.
 *
 * One place on purpose. Two screens that each phrase "these rules would remove 60 contacts"
 * slightly differently is how somebody ends up trusting one number over the other.
 */
internal object ContactFilterPreviewUi {

    /**
     * How many names the FIRST view of a long list shows before offering "show all".
     *
     * This is a reading convenience, never a limit on what the dialog is willing to tell you.
     * "...and 3 more." on its own was a dead end: it admitted the list was incomplete and gave no
     * way to see the rest, so a rule could be judged on a list the user could not finish reading.
     *
     * It is NOT applied to the removal list. That one is the list somebody acts on, and a cap
     * there means approving the removal of contacts that were never shown.
     */
    private const val MAX_NAMES = 60

    /**
     * What the dialog renders, from whichever run produced it.
     *
     * The server run and the on-phone fallback answer slightly different questions, so both are
     * flattened into this rather than the dialog branching on which one ran. [serverBacked] is the
     * only thing the copy needs to distinguish.
     */
    private data class Outcome(
        val total: Int,
        val synced: Int,
        val removedFromPhone: List<String>,
        val notDownloaded: List<String>,
        val unreadable: Int,
        val undecided: Int,
        val serverBacked: Boolean,
    )

    /**
     * Runs the dry run and shows the result.
     *
     * @param confirm when given, the dialog becomes a decision rather than a report: the positive
     *   button applies the change the preview was describing, and dismissing leaves it unmade.
     */
    fun run(
        activity: AppCompatActivity,
        database: SyncDatabase,
        accountName: String,
        ruleSet: FilterRuleSet,
        confirm: (() -> Unit)? = null,
    ) {
        val progress = AlertDialog.Builder(activity)
            .setTitle(R.string.contact_filter_preview_title)
            .setMessage(R.string.contact_filter_preview_reading)
            .setNegativeButton(R.string.btn_cancel, null)
            .setCancelable(false)
            .create()

        val job = activity.lifecycleScope.launch {
            val outcome = try {
                serverRun(activity, database, accountName, ruleSet) { done, total ->
                    withContext(Dispatchers.Main) {
                        progress.setMessage(
                            activity.getString(R.string.contact_filter_preview_progress, done, total)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Reaching Proton is what makes the answer exact, but failing to reach it is not a
                // reason to show nothing: the on-phone run still answers group and contact-point
                // rules, and says plainly which contacts it could not judge.
                localFallback(activity, database, accountName, ruleSet, e)
            }
            progress.dismiss()
            if (outcome != null) show(activity, outcome, confirm)
        }

        progress.setOnShowListener {
            progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                job.cancel()
                progress.dismiss()
            }
        }
        progress.show()
    }

    private suspend fun serverRun(
        activity: AppCompatActivity,
        database: SyncDatabase,
        accountName: String,
        ruleSet: FilterRuleSet,
        onProgress: suspend (Int, Int) -> Unit,
    ): Outcome {
        val account = AccountManager.get(activity)
            .getAccountsByType(ProtonSyncApp.ACCOUNT_TYPE)
            .firstOrNull { it.name == accountName }
            ?: throw ContactFilterServerPreview.Unavailable("account not found")

        val deps = EntryPointAccessors.fromApplication(
            activity.applicationContext, BackupDependenciesEntryPoint::class.java
        )
        val preview = ContactFilterServerPreview(
            deps.apiClient(), deps.contactDecryptor(), deps.sessionManager(), deps.keyManager()
        )
        val result = withContext(Dispatchers.IO) {
            preview.run(
                activity.applicationContext, account, database.contactSyncDao(), ruleSet, onProgress
            )
        }
        return Outcome(
            total = result.total,
            synced = result.synced,
            removedFromPhone = result.removedFromPhone,
            notDownloaded = result.notDownloaded,
            unreadable = result.unreadable,
            undecided = 0,
            serverBacked = true,
        )
    }

    /** Null when even the on-phone run failed, having already told the user why. */
    private suspend fun localFallback(
        activity: AppCompatActivity,
        database: SyncDatabase,
        accountName: String,
        ruleSet: FilterRuleSet,
        cause: Exception,
    ): Outcome? = try {
        val result = withContext(Dispatchers.IO) {
            ContactFilterPreview.run(
                activity.applicationContext, database.contactSyncDao(), accountName, ruleSet
            )
        }
        Outcome(
            total = result.total,
            synced = result.kept,
            removedFromPhone = result.removed,
            notDownloaded = emptyList(),
            unreadable = 0,
            undecided = result.undecided,
            serverBacked = false,
        )
    } catch (_: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                activity,
                activity.getString(
                    R.string.contact_filter_preview_failed,
                    cause.message ?: cause.javaClass.simpleName
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
        null
    }

    private fun show(
        activity: AppCompatActivity,
        outcome: Outcome,
        confirm: (() -> Unit)?,
        expanded: Boolean = false,
    ) {
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.contact_filter_preview_title)
            .setMessage(message(activity, outcome, expanded))
        if (confirm == null) {
            builder.setPositiveButton(android.R.string.ok, null)
        } else {
            builder.setPositiveButton(R.string.contact_filter_save) { _, _ -> confirm() }
                .setNegativeButton(R.string.btn_cancel, null)
        }
        // Only offered when something is actually hidden, and it re-opens the same decision with
        // the full list rather than navigating away from it: the answer to "and how many more?"
        // has to be reachable without abandoning the save.
        if (!expanded && hidesNames(outcome)) {
            builder.setNeutralButton(R.string.contact_filter_preview_show_all) { _, _ ->
                show(activity, outcome, confirm, expanded = true)
            }
        }
        builder.show()
    }

    /** Whether the first view leaves any name unshown. The removal list never does. */
    private fun hidesNames(outcome: Outcome): Boolean =
        outcome.notDownloaded.size > MAX_NAMES

    private fun message(activity: AppCompatActivity, outcome: Outcome, expanded: Boolean): String {
        if (outcome.total == 0) return activity.getString(R.string.contact_filter_preview_empty)
        val lines = mutableListOf<String>()

        if (outcome.serverBacked) {
            lines += activity.getString(
                R.string.contact_filter_preview_server_result, outcome.total, outcome.synced
            )
        } else {
            lines += activity.getString(R.string.contact_filter_preview_offline)
            val checked = outcome.total - outcome.undecided
            if (checked > 0) {
                lines += activity.getString(
                    R.string.contact_filter_preview_result,
                    checked, outcome.synced, outcome.removedFromPhone.size
                )
            }
            if (outcome.undecided > 0) {
                lines += activity.getString(R.string.contact_filter_preview_undecided, outcome.undecided)
            }
        }
        if (outcome.unreadable > 0) {
            lines += activity.getString(R.string.contact_filter_preview_unreadable, outcome.unreadable)
        }

        // Named, not counted. A number cannot tell somebody that the rule they just wrote is about
        // to take their dentist off the phone along with the noise. The destructive list comes
        // first and always appears, even when empty, because its absence is the answer people are
        // looking for.
        lines += ""
        if (outcome.removedFromPhone.isEmpty()) {
            // "Nothing would be removed" is a promise, so it is only made about contacts that were
            // actually judged. A run that could not judge anything used to say it too, which is the
            // strongest possible reassurance offered in exactly the case with the least behind it.
            val judgedSomething = outcome.total > outcome.undecided + outcome.unreadable
            if (judgedSomething) {
                lines += activity.getString(
                    if (outcome.serverBacked || outcome.undecided == 0) {
                        R.string.contact_filter_preview_nothing_removed
                    } else {
                        R.string.contact_filter_preview_none
                    }
                )
            }
        } else {
            lines += activity.getString(
                R.string.contact_filter_preview_removed_heading, outcome.removedFromPhone.size
            )
            // Deliberately uncapped: this is what the user is being asked to approve.
            lines += names(activity, outcome.removedFromPhone, limit = null)
        }

        if (outcome.notDownloaded.isNotEmpty()) {
            lines += ""
            lines += activity.getString(
                R.string.contact_filter_preview_skipped_heading, outcome.notDownloaded.size
            )
            lines += names(activity, outcome.notDownloaded, if (expanded) null else MAX_NAMES)
        }
        return lines.joinToString("\n")
    }

    private fun names(activity: AppCompatActivity, all: List<String>, limit: Int?): String {
        if (limit == null || all.size <= limit) return all.joinToString("\n")
        val shown = all.take(limit).joinToString("\n")
        return shown + "\n" + activity.getString(
            R.string.contact_filter_preview_and_more, all.size - limit
        )
    }
}
