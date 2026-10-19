package me.proton.sync.contacts.filter

import android.accounts.Account
import ezvcard.Ezvcard
import me.proton.sync.SyncLog
import me.proton.sync.SyncUtils
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.models.ContactCard
import me.proton.sync.api.models.ContactMeta
import me.proton.sync.crypto.ContactDecryptor
import me.proton.sync.crypto.KeyManager
import me.proton.sync.db.daos.ContactSyncDao
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The accurate dry run: evaluates the rules against the real Proton contacts.
 *
 * The phone cannot answer for itself. Android's contacts provider manufactures a structured name
 * for every contact it stores, so a local row can never show that a contact has no name on Proton,
 * which is exactly what the auto-created rules test. A preview built on local rows reported that
 * the flagship rule would remove nothing while the sync went on to remove sixty contacts.
 *
 * So this fetches. It walks the same three steps the sync walks, in the same order, using the same
 * evaluator and the same [ContactView] implementations, which is the point: a dry run that reasons
 * differently from the sync is a dry run that will eventually disagree with it.
 *
 * The two-phase evaluation is what keeps it bearable. Group, name, size and timestamp rules are
 * decided from the contact headers alone, so a rule like "only sync people in a group" costs a few
 * list requests and no decryption at all. Only contacts a rule cannot settle from the header are
 * fetched and decrypted.
 */
class ContactFilterServerPreview(
    private val apiClient: ProtonApiClient,
    private val contactDecryptor: ContactDecryptor,
    private val sessionManager: AccountSessionManager,
    private val keyManager: KeyManager,
) {

    /**
     * @param removedFromPhone contacts that are on this phone now and would be taken off it. The
     *   destructive half, and the reason the whole feature has a preview.
     * @param notDownloaded contacts the rules exclude that are not on the phone anyway. Nothing is
     *   lost here, so it is reported separately rather than folded into a single scary number.
     * @param unreadable cards that would not decrypt. Left alone by the sync, so left alone here.
     */
    data class Result(
        val total: Int,
        val synced: Int,
        val removedFromPhone: List<String>,
        val notDownloaded: List<String>,
        val unreadable: Int,
        /** How many contacts had to be decrypted, so the log can show what the run actually cost. */
        val decrypted: Int,
    )

    /** Thrown when the run cannot produce a trustworthy answer; the message reaches the user. */
    class Unavailable(message: String) : Exception(message)

    /**
     * @param onProgress called as cards are decrypted, so a long run can show where it is. Not
     *   called during the header phase, which is fast and has no meaningful granularity.
     */
    suspend fun run(
        context: android.content.Context,
        account: Account,
        dao: ContactSyncDao,
        ruleSet: FilterRuleSet,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Result {
        val accountName = account.name

        // Address-key material is what most contact cards are encrypted to. Without this the
        // decryption below fails for nearly every contact and the run reports them unreadable.
        try {
            SyncUtils.ensureKeysUnlocked(
                account, sessionManager.getSession(account), keyManager, apiClient, TAG
            )
        } catch (e: Exception) {
            throw Unavailable("keys locked (${e.javaClass.simpleName})")
        }

        val labelNames = fetchLabelNames(accountName, ruleSet)
        val metas = fetchHeaders(accountName)
        if (metas.isEmpty()) return Result(0, 0, emptyList(), emptyList(), 0, 0)

        val onPhone = dao.getAllByAccount(accountName)
            .filter { it.androidRawContactId != null }
            .mapTo(HashSet()) { it.protonContactId }

        // Contacts the user rescued at a removal prompt. The sync will never propose them again
        // (both recording sites gate on isKept), so naming them here would be the dry run
        // over-promising on the exact people the user went out of their way to protect.
        val rescued = me.proton.sync.RemoteDeletionGuard.keptIds(
            context, me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS_FILTER, accountName
        )

        val evaluator = FilterEvaluator(ruleSet)
        val verdicts = HashMap<String, RuleAction>(metas.size)
        val headers = HashMap<String, HeaderContactView>(metas.size)
        val needCard = ArrayList<ContactMeta>()

        for (meta in metas) {
            coroutineContext.ensureActive()
            val labelIds = meta.labelIds ?: emptyList()
            val header = HeaderContactView(
                protonId = meta.id,
                displayName = meta.name,
                uid = meta.uid,
                sizeBytes = meta.size.toLong(),
                createTime = meta.createTime,
                modifyTime = meta.modifyTime,
                groupNames = labelIds.mapNotNull { labelNames[it] },
                labelIds = labelIds,
            )
            headers[meta.id] = header
            when (val verdict = evaluator.evaluate(header, RuleDirection.DOWNLOAD)) {
                is FilterEvaluator.Verdict.Decided -> verdicts[meta.id] = verdict.action
                FilterEvaluator.Verdict.NeedsCard -> needCard += meta
            }
        }

        var unreadable = 0
        var decrypted = 0
        if (needCard.isNotEmpty()) {
            val cardsById = fetchCards(accountName, needCard.map { it.id })
            val missing = needCard.count { it.id !in cardsById }
            if (missing > 0) {
                // Not the same as a card that would not decrypt. These were never fetched, so the
                // run has no opinion on them at all, and reporting them as "left alone" let the
                // dialog go on to claim nothing would be removed.
                throw Unavailable("only $missing of ${needCard.size} contacts could be fetched")
            }
            for ((index, meta) in needCard.withIndex()) {
                coroutineContext.ensureActive()
                val action = cardVerdict(accountName, meta, headers[meta.id], labelNames, cardsById[meta.id], evaluator)
                if (action == null) unreadable++ else {
                    verdicts[meta.id] = action
                    decrypted++
                }
                onProgress(index + 1, needCard.size)
            }
        }

        var synced = 0
        val removedFromPhone = ArrayList<String>()
        val notDownloaded = ArrayList<String>()
        for (meta in metas) {
            when (verdicts[meta.id]) {
                RuleAction.SYNC -> synced++
                RuleAction.EXCLUDE -> when {
                    meta.id in rescued -> synced++
                    meta.id in onPhone -> removedFromPhone += label(meta)
                    else -> notDownloaded += label(meta)
                }
                // Unreadable: the sync leaves these alone, so neither does this.
                null -> Unit
            }
        }

        SyncLog.i(
            TAG,
            "Filter dry run for $accountName (rev ${ruleSet.revision}): ${metas.size} contacts, " +
                "$synced sync, ${removedFromPhone.size} off the phone, ${notDownloaded.size} not " +
                "downloaded, $unreadable unreadable, $decrypted decrypted " +
                "(${metas.size - needCard.size} decided from headers alone)"
        )

        return Result(
            total = metas.size,
            synced = synced,
            removedFromPhone = removedFromPhone.sortedWith(String.CASE_INSENSITIVE_ORDER),
            notDownloaded = notDownloaded.sortedWith(String.CASE_INSENSITIVE_ORDER),
            unreadable = unreadable,
            decrypted = decrypted,
        )
    }

    /** Null means the card could not be read, which is different from "no rule matched". */
    private fun cardVerdict(
        accountName: String,
        meta: ContactMeta,
        header: HeaderContactView?,
        labelNames: Map<String, String>,
        cards: List<ContactCard>?,
        evaluator: FilterEvaluator,
    ): RuleAction? {
        if (cards == null) return null
        return try {
            val merged = contactDecryptor.decryptAndMergeCards(accountName, cards)
            // The same data-URI unescape the sync and the backup apply before parsing.
            val vCard = Ezvcard.parse(merged.replace("base64\\,", "base64,")).first() ?: return null
            val view = VCardContactView(
                vCard = vCard,
                header = header,
                groupNamesOverride = (meta.labelIds ?: emptyList()).mapNotNull { labelNames[it] },
            )
            when (val verdict = evaluator.evaluate(view, RuleDirection.DOWNLOAD)) {
                is FilterEvaluator.Verdict.Decided -> verdict.action
                // Matches ContactFilterSession.resolve exactly. It used to answer defaultAction
                // here and SYNC there, so with the fall-through set to "do not sync" the preview
                // listed still-undecided contacts as about to be removed while the sync kept them,
                // and that list is the confirmation shown for making the change.
                FilterEvaluator.Verdict.NeedsCard -> RuleAction.SYNC
            }
        } catch (e: Exception) {
            SyncLog.d(TAG, "Dry run could not read contact ${meta.id}: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Group names, and a hard stop if a group rule cannot be resolved.
     *
     * An empty label map reads as "this contact is in no groups", which silently turns
     * "sync only my groups" into "sync nothing". The sync fails closed on the same problem and so
     * does this, rather than presenting a confident list of everything the user owns.
     */
    private suspend fun fetchLabelNames(accountName: String, ruleSet: FilterRuleSet): Map<String, String> {
        val names = HashMap<String, String>()
        val failure = try {
            val response = apiClient.getContactLabels(accountName)
            if (response.isSuccessful) {
                response.body()?.labels?.forEach { names[it.id] = it.name }
                null
            } else {
                "HTTP ${response.code()}"
            }
        } catch (e: Exception) {
            e.javaClass.simpleName
        }
        if (failure != null && ruleSet.usesGroups()) {
            throw Unavailable("contact groups could not be read ($failure)")
        }
        return names
    }

    private suspend fun fetchHeaders(accountName: String): List<ContactMeta> {
        val all = ArrayList<ContactMeta>()
        var page = 0
        while (true) {
            coroutineContext.ensureActive()
            val response = try {
                apiClient.getContacts(accountName, page, HEADER_PAGE)
            } catch (e: Exception) {
                throw Unavailable("could not reach Proton (${e.javaClass.simpleName})")
            }
            if (!response.isSuccessful) throw Unavailable("Proton returned HTTP ${response.code()}")
            val metas = response.body()?.contacts ?: emptyList()
            all += metas
            if (metas.size < HEADER_PAGE) break
            page++
        }
        return all
    }

    /**
     * Card payloads for the contacts a header could not settle.
     *
     * Bulk export rather than one request each: the export endpoint is what the sync uses, and for
     * anything past a handful of contacts a page of fifty beats fifty round trips. Contacts the
     * export skips are fetched individually, the same gap the backup builder covers.
     */
    private suspend fun fetchCards(accountName: String, wanted: List<String>): Map<String, List<ContactCard>> {
        val wantedSet = wanted.toHashSet()
        val cardsById = HashMap<String, List<ContactCard>>(wanted.size)
        var page = 0
        while (true) {
            coroutineContext.ensureActive()
            val response = try {
                apiClient.exportContacts(accountName, page, EXPORT_PAGE)
            } catch (e: Exception) {
                SyncLog.w(TAG, "Dry run export page $page failed: ${e.message}")
                break
            }
            if (!response.isSuccessful) break
            val details = response.body()?.contacts ?: emptyList()
            details.forEach { if (it.id in wantedSet) cardsById[it.id] = it.cards }
            if (details.size < EXPORT_PAGE) break
            if (cardsById.keys.containsAll(wantedSet)) break
            page++
        }

        val missed = wanted.filter { it !in cardsById }
        if (missed.isNotEmpty()) {
            SyncLog.i(TAG, "Dry run: export missed ${missed.size} contact(s); fetching individually")
            for (id in missed) {
                coroutineContext.ensureActive()
                try {
                    apiClient.getContact(accountName, id).body()?.contact?.let { cardsById[id] = it.cards }
                } catch (e: Exception) {
                    SyncLog.d(TAG, "Dry run getContact $id failed: ${e.message}")
                }
            }
        }
        return cardsById
    }

    private fun label(meta: ContactMeta): String = meta.name.ifBlank { meta.id }

    companion object {
        private const val TAG = "ContactFilterDryRun"
        private const val HEADER_PAGE = 100
        private const val EXPORT_PAGE = 50
    }
}

/** True when any enabled rule tests group membership, so a failed label fetch must stop the run. */
internal fun FilterRuleSet.usesGroups(): Boolean = rules.any { rule ->
    rule.enabled && rule.conditions.any {
        it.field == RuleField.GROUPS || it.field == RuleField.GROUP_COUNT
    }
}
