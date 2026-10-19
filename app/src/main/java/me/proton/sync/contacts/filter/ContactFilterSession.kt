package me.proton.sync.contacts.filter

import android.content.Context
import ezvcard.VCard
import me.proton.sync.SyncLog

/**
 * One sync's worth of filtering state.
 *
 * Created once per contacts sync so compiled regexes, the label lookup and the counters are shared
 * across every contact rather than rebuilt per contact.
 *
 * Verdicts are acted on: contacts are skipped, excluded and, with the user's confirmation, removed
 * from the phone. What gates that is [applies], which is false only while nothing but the built-in
 * probes are running. The doc here used to say the whole engine was log-only and could not remove
 * anything, which stopped being true when the removal path shipped, and it is the kind of claim a
 * reader checks a destructive change against.
 *
 * The log-only stage did pay for itself once: it showed that "only a name and an email" selects
 * contacts the account holder entered by hand, not just the ones Proton invented.
 */
class ContactFilterSession private constructor(
    private val ruleSet: FilterRuleSet,
    private val labelIdToName: Map<String, String>,
    private val probes: List<Probe>,
    /** True when the rules were edited since the last sync, so every cached verdict is suspect. */
    val rulesChanged: Boolean,
    /** Proton ids the user chose to keep despite the rules; see [isKept]. */
    private val keptIds: Set<String>,
) {

    /**
     * One candidate rule, measured on its own.
     *
     * Each probe gets its own evaluator and its own counters. Folding them into a single rule set
     * would let the broadest rule match first and hide the rest, reporting one number that answers
     * nothing.
     */
    private class Probe(val label: String, rule: FilterRule) {
        val evaluator = FilterEvaluator(
            FilterRuleSet(defaultAction = RuleAction.SYNC, rules = listOf(rule))
        )
        var excluded = 0
        var decidedFromHeader = 0
    }

    private val evaluator = FilterEvaluator(ruleSet)

    private var headerDecidedSync = 0
    private var headerDecidedExclude = 0
    private var neededCard = 0
    private var cardDecidedSync = 0
    private var cardDecidedExclude = 0
    private var uploadExcluded = 0
    private var cachedExclusions = 0
    private var appliedExclusions = 0
    // Counts only. This used to also keep the first dozen names of excluded contacts and log them
    // on every sync, which put contact names and email addresses into the log, against the
    // app's promise that a log never holds the contents of contacts.

    val isActive: Boolean get() = !ruleSet.isInactive || probes.isNotEmpty()

    /**
     * Whether verdicts may actually be acted on.
     *
     * False while only the built-in probes are running: those exist to measure candidate rules
     * against a real account, and must never change what syncs. Only rules the user wrote can
     * exclude anything.
     */
    val applies: Boolean get() = !ruleSet.isInactive

    val revision: Int get() = ruleSet.revision

    /** Fingerprint of the header inputs a verdict rests on; see [FilterTag.isStale]. */
    fun inputsHash(labelIds: List<String>, displayName: String?, sizeBytes: Long?): String =
        FilterTag.inputsHash(labelIds, displayName, sizeBytes)

    fun tagFor(action: RuleAction, labelIds: List<String>, displayName: String?, sizeBytes: Long?): String =
        FilterTag.encode(action, ruleSet.revision, inputsHash(labelIds, displayName, sizeBytes))

    /** True when a cached verdict predates the current rules or the header inputs it rested on. */
    fun isStale(tag: String?, labelIds: List<String>, displayName: String?, sizeBytes: Long?): Boolean =
        FilterTag.isStale(tag, ruleSet.revision, inputsHash(labelIds, displayName, sizeBytes))

    /** Counted separately from live verdicts so the log distinguishes work done from work avoided. */
    fun countCachedExclusion() { cachedExclusions++ }

    fun countAppliedExclusion() { appliedExclusions++ }

    /**
     * Contacts that the rules exclude but which are still on the phone.
     *
     * Collected during the download pass and handed to the confirmation step. Nothing here is
     * removed until the user says so.
     */
    private val _pendingRemovals = linkedMapOf<String, String>()

    /** Proton id to display name, so the confirmation can name contacts rather than count them. */
    val pendingRemovals: Map<String, String> get() = _pendingRemovals

    private val _stillExcluded = linkedSetOf<String>()

    /**
     * Every contact the rules still exclude, whether or not it is waiting on a prompt.
     *
     * Distinct from [pendingRemovals], which holds only the ones to ask about. A contact the user
     * chose to keep is deliberately never recorded as pending again, so pending alone cannot answer
     * "is this still excluded" — and the sweep that prunes the guard's sets asks exactly that. Using
     * pending there meant a kept contact looked like one the rules had released, its keep was
     * dropped, and the prompt came back on the sync after next, for ever.
     */
    val stillExcluded: Set<String> get() = _stillExcluded

    /** Records that the rules exclude this contact, without deciding whether to ask about it. */
    fun recordStillExcluded(protonId: String) {
        _stillExcluded.add(protonId)
    }

    /** Uncapped: this set is what actually gets deleted, so it has to be complete. */
    fun recordPendingRemoval(protonId: String, displayName: String?) {
        _pendingRemovals[protonId] = displayName.orEmpty()
        _stillExcluded.add(protonId)
    }

    /**
     * Contacts the user chose to keep despite the rules.
     *
     * Consulted on the download path so a kept contact resumes syncing normally. Without this it
     * would sit excluded-but-present and re-prompt on every sync, and the user would have no way to
     * make the prompt stop short of rewriting their rules.
     */
    fun isKept(protonId: String): Boolean = keptIds.contains(protonId)

    fun headerVerdict(
        protonId: String,
        displayName: String?,
        uid: String?,
        sizeBytes: Long?,
        createTime: Long?,
        modifyTime: Long?,
        labelIds: List<String>,
    ): FilterEvaluator.Verdict {
        val view = headerView(protonId, displayName, uid, sizeBytes, createTime, modifyTime, labelIds)

        // Probes measure how much each candidate rule can settle without a fetch, which is the
        // difference between a rule that costs nothing and one that decrypts the whole account.
        for (probe in probes) {
            if (probe.evaluator.evaluate(view, RuleDirection.DOWNLOAD) is FilterEvaluator.Verdict.Decided) {
                probe.decidedFromHeader++
            }
        }

        val verdict = evaluator.evaluate(view, RuleDirection.DOWNLOAD)
        when (verdict) {
            is FilterEvaluator.Verdict.Decided ->
                if (verdict.action == RuleAction.EXCLUDE) headerDecidedExclude++ else headerDecidedSync++
            FilterEvaluator.Verdict.NeedsCard -> neededCard++
        }
        return verdict
    }

    fun cardVerdict(
        vCard: VCard,
        protonId: String,
        displayName: String?,
        uid: String?,
        sizeBytes: Long?,
        createTime: Long?,
        modifyTime: Long?,
        labelIds: List<String>,
    ): RuleAction {
        val header = headerView(protonId, displayName, uid, sizeBytes, createTime, modifyTime, labelIds)
        val view = VCardContactView(vCard, header, groupNamesOverride = header.values(RuleField.GROUPS))

        for (probe in probes) {
            if (resolve(probe.evaluator.evaluate(view, RuleDirection.DOWNLOAD)) == RuleAction.EXCLUDE) {
                probe.excluded++
            }
        }

        val action = resolve(evaluator.evaluate(view, RuleDirection.DOWNLOAD))
        if (action == RuleAction.EXCLUDE) {
            cardDecidedExclude++
        } else {
            cardDecidedSync++
        }
        return action
    }

    /**
     * Verdict for a contact on its way up.
     *
     * No header exists here, so header-only fields are not knowable and any rule resting on them is
     * never true on this path. Groups come from the card's own CATEGORIES.
     */
    fun uploadVerdict(vCard: VCard): RuleAction {
        val action = resolve(evaluator.evaluate(VCardContactView(vCard), RuleDirection.UPLOAD))
        if (action == RuleAction.EXCLUDE) uploadExcluded++
        return action
    }

    /** A verdict still saying "needs the card" once the card has been read means: keep the contact. */
    private fun resolve(verdict: FilterEvaluator.Verdict): RuleAction = when (verdict) {
        is FilterEvaluator.Verdict.Decided -> verdict.action
        FilterEvaluator.Verdict.NeedsCard -> RuleAction.SYNC
    }

    private fun headerView(
        protonId: String,
        displayName: String?,
        uid: String?,
        sizeBytes: Long?,
        createTime: Long?,
        modifyTime: Long?,
        labelIds: List<String>,
    ) = HeaderContactView(
        protonId = protonId,
        displayName = displayName,
        uid = uid,
        sizeBytes = sizeBytes,
        createTime = createTime,
        modifyTime = modifyTime,
        groupNames = labelIds.mapNotNull { labelIdToName[it] },
        labelIds = labelIds,
    )

    fun logSummary(accountName: String) {
        if (!isActive) return
        if (!ruleSet.isInactive) {
            SyncLog.i(
                TAG,
                "Contact filter for $accountName (rev ${ruleSet.revision}): " +
                    "header sync=$headerDecidedSync exclude=$headerDecidedExclude needCard=$neededCard, " +
                    "card sync=$cardDecidedSync exclude=$cardDecidedExclude, " +
                    "not downloaded: $appliedExclusions new + $cachedExclusions already known, " +
                    "upload exclude=$uploadExcluded (held back from Proton)"
            )
        }
        for (probe in probes) {
            SyncLog.i(
                TAG,
                "Probe [${probe.label}] for $accountName: would exclude ${probe.excluded}, " +
                    "settled from header without a fetch: ${probe.decidedFromHeader}"
            )
        }
    }

    companion object {
    /**
     * Builds a session without touching Android, for tests of the removal bookkeeping.
     *
     * The real factory reads preferences and the deletion guard, neither of which exists on the JVM,
     * and the bookkeeping this exposes is the part that decides whether somebody's "keep on phone"
     * survives, so it is worth being able to test on its own.
     */
    internal fun forTest(keptIds: Set<String>): ContactFilterSession = ContactFilterSession(
        ruleSet = FilterRuleSet.EMPTY,
        labelIdToName = emptyMap(),
        probes = emptyList(),
        rulesChanged = false,
        keptIds = keptIds,
    )

        private const val TAG = "ContactFilter"

        /**
         * Build the session for a sync, or null when there is nothing to do.
         *
         * **Fails closed on unusable labels.** Both label fetches in the adapter swallow their own
         * exceptions and return an empty map, which reads as "this contact is in no groups". With a
         * default action of EXCLUDE that would propose the entire address book for removal, so a
         * rule set mentioning a group is disabled outright when the labels did not arrive.
         */
        fun create(
            context: Context,
            accountName: String,
            labelIdToName: Map<String, String>,
            labelsUsable: Boolean,
        ): ContactFilterSession? {
            val ruleSet = ContactFilterStore.load(context, accountName)

            // Measure the built-in candidates only while the user has no rules of their own; once
            // they do, their rules are what matters and the probe would just be noise.
            val probes = if (ruleSet.isInactive && SyncLog.isVerboseEnabled()) {
                FilterPresets.probeRules().map { (id, rule) -> Probe(id, rule) }
            } else {
                emptyList()
            }
            if (ruleSet.isInactive && probes.isEmpty()) return null

            if (!labelsUsable && (mentionsGroups(ruleSet) || probes.isNotEmpty())) {
                // Note this disables the UPLOAD filter too, which is why uploadsUnsafe exists: not
                // filtering is the safe answer downward, where the worst case is proposing to
                // remove contacts that are really in a group, and the unsafe one upward, where it
                // means sending Proton a contact a rule said to hold back, permanently.
                SyncLog.w(
                    TAG,
                    "Contact labels unavailable; contact filtering disabled for $accountName this " +
                        "run so a missing group cannot be read as 'in no groups'"
                )
                return null
            }
            return ContactFilterSession(
                ruleSet = ruleSet,
                labelIdToName = labelIdToName,
                probes = probes,
                rulesChanged = ContactFilterStore.isDirty(context, accountName),
                keptIds = me.proton.sync.RemoteDeletionGuard.keptIds(
                    context,
                    me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS_FILTER,
                    accountName,
                ),
            )
        }

        /**
         * Whether uploads have to wait because the rules cannot be judged.
         *
         * The fail-closed rule above was written for deletions and reads as fail-open in the other
         * direction: with the session gone, nothing consults the upload rules and a contact they
         * exclude goes to Proton, where nothing ever removes it. Holding the edit for a later sync
         * costs a delay; sending it cannot be undone.
         */
        fun uploadsUnsafe(context: Context, accountName: String, labelsUsable: Boolean): Boolean {
            if (labelsUsable) return false
            return uploadsUnsafeFor(ContactFilterStore.load(context, accountName))
        }

        /** The rule-set half of [uploadsUnsafe], split out so it can be tested without a Context. */
        fun uploadsUnsafeFor(ruleSet: FilterRuleSet): Boolean {
            // This has to test the same condition that suppresses the session, not a narrower one.
            // Suppression is all-or-nothing: one group rule anywhere, in any direction, and no rule
            // runs this run. Asking only whether the *upload* rules mention groups therefore missed
            // the case that matters most, a download-only group rule beside an upload EXCLUDE rule:
            // the session went away, the upload rule never ran, and the contacts it was holding
            // back went to Proton.
            if (!mentionsGroups(ruleSet)) return false
            // Only an upload rule that can hold something back. A SYNC rule going unread is
            // harmless, because "send it" is what happens with no rules at all.
            return ruleSet.rules.any { rule ->
                rule.enabled &&
                    rule.direction != RuleDirection.DOWNLOAD &&
                    rule.action == RuleAction.EXCLUDE
            }
        }

        private fun mentionsGroups(ruleSet: FilterRuleSet): Boolean =
            ruleSet.rules.any { rule ->
                rule.enabled && rule.conditions.any {
                    it.field == RuleField.GROUPS || it.field == RuleField.GROUP_COUNT
                }
            }
    }
}
