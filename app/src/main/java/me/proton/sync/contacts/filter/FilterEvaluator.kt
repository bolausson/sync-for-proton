package me.proton.sync.contacts.filter

/**
 * Decides whether one contact should sync, given a rule set.
 *
 * Rules are walked top to bottom and the first one that matches decides; if none match, the rule
 * set's [FilterRuleSet.defaultAction] applies. Held as a class rather than an object so a run can
 * cache compiled regexes across the few hundred contacts of a sync instead of recompiling per
 * contact.
 */
class FilterEvaluator(private val ruleSet: FilterRuleSet) {

    /**
     * The outcome of evaluating a contact.
     *
     * [NeedsCard] is not a failure. It means a rule might still match but cannot be judged from
     * what has been read so far, so the caller has to fetch and decrypt the contact and ask again.
     */
    sealed interface Verdict {
        data class Decided(val action: RuleAction) : Verdict
        data object NeedsCard : Verdict
    }

    /** What happens to a contact no rule claimed. Exposed for callers that resolve their own fallbacks. */
    val defaultAction: RuleAction get() = ruleSet.defaultAction

    /** Compiled once per run. An invalid user pattern is stored as null and never matches. */
    private val regexCache = HashMap<String, Regex?>()

    fun evaluate(view: ContactView, direction: RuleDirection): Verdict {
        for (rule in ruleSet.rules) {
            if (!rule.enabled || !rule.appliesTo(direction)) continue
            when (evaluateRule(rule, view)) {
                Tri.TRUE -> return Verdict.Decided(rule.action)
                Tri.UNKNOWN ->
                    // Downward there is a recourse: fetch the card and ask again, and a later rule
                    // must not decide a contact this one might still claim. Upward there is none.
                    // The card is all there is, and fields that only exist in Proton's contact list
                    // header can never be answered, so returning NeedsCard there stopped the walk
                    // for good and silently disabled every rule below the unanswerable one. Such a
                    // rule is skipped instead, and the rest still get their say.
                    if (direction != RuleDirection.UPLOAD) {
                        return Verdict.NeedsCard
                    } else if (rule.action == RuleAction.EXCLUDE) {
                        // Skipping is only safe when the rule would have said "send it", which is
                        // what happens anyway. A rule that says hold this back cannot be skipped:
                        // the contact would go to Proton on an unanswerable condition and nothing
                        // there ever takes it off again. Undecidable plus "do not send" means wait.
                        return Verdict.Decided(RuleAction.EXCLUDE)
                    }
                Tri.FALSE -> Unit
            }
        }
        return Verdict.Decided(fallbackFor(direction))
    }

    /**
     * The fall-through applies downward only.
     *
     * Every rule declares a direction; the fall-through does not, and the screen that sets it asks
     * "which contacts sync to this phone". Reading it as an upload instruction as well turns
     * "everything else: do not sync" into "stop sending anything to Proton", so an edit made on the
     * phone is dropped, its dirty flag cleared, and the next download overwrites it. The user is
     * never told, because the dialog offers no upload wording and the dry run only ever evaluates
     * the download direction.
     *
     * So upward, "no rule claimed this contact" means send it. Excluding an upload stays possible,
     * but only by writing a rule that says so.
     */
    private fun fallbackFor(direction: RuleDirection): RuleAction =
        if (direction == RuleDirection.UPLOAD) RuleAction.SYNC else ruleSet.defaultAction

    /**
     * A rule with no conditions never matches, rather than matching everything.
     *
     * A half-finished rule is far more likely than a deliberate "match all", and the difference
     * between the two readings is somebody's address book.
     */
    private fun evaluateRule(rule: FilterRule, view: ContactView): Tri {
        if (rule.conditions.isEmpty()) return Tri.FALSE

        var sawUnknown = false
        for (condition in rule.conditions) {
            when (evaluateCondition(condition, view)) {
                Tri.TRUE -> if (rule.match == Combinator.ANY) return Tri.TRUE
                // A definite false settles an ALL rule even with unknowns outstanding. This is the
                // short-circuit that lets group rules be judged from the header alone.
                Tri.FALSE -> if (rule.match == Combinator.ALL) return Tri.FALSE
                Tri.UNKNOWN -> sawUnknown = true
            }
        }
        if (sawUnknown) return Tri.UNKNOWN
        return if (rule.match == Combinator.ALL) Tri.TRUE else Tri.FALSE
    }

    private fun evaluateCondition(condition: Condition, view: ContactView): Tri {
        val raw = rawResult(condition, view)
        return if (condition.negate) raw.negate() else raw
    }

    private fun rawResult(condition: Condition, view: ContactView): Tri {
        val field = condition.field

        // Group conditions match on Proton label id when the ids are available, so a group renamed
        // on the web keeps working. Names are the fallback, and all the upload path ever has.
        // Only the two set operators, because setOperator is all this path can express. It used to
        // run for every operator, so changing the Test on a group preset to "is not" or
        // "count is at least" silently left the rule meaning "is any of" while the rule list went
        // on displaying what the user had chosen.
        if (field == RuleField.GROUPS && condition.refIds.isNotEmpty() &&
            (condition.op == RuleOp.IN_SET || condition.op == RuleOp.CONTAINS_ALL)
        ) {
            val ids = view.groupIds()
            if (ids != null) return setOperator(condition, ids, condition.refIds)
        }

        if (field.kind == FieldKind.FIELD_SET) {
            val populated = view.populated() ?: return Tri.UNKNOWN
            val wanted = condition.values.mapNotNull { RuleField.fromName(it) }.toSet()
            return when (condition.op) {
                RuleOp.ONLY -> Tri.of(populated.isNotEmpty() && expandNameGroup(wanted).containsAll(populated))
                // Collapsed only when the rule asked for the whole-name shorthand. Collapsing
                // unconditionally made "exactly a first name" and "exactly a first and last name"
                // the same rule, so the one operator whose whole point is precision could not tell
                // them apart. Naming the parts explicitly now means exactly those parts.
                RuleOp.EXACTLY ->
                    if (RuleField.STRUCTURED_NAME in wanted) {
                        Tri.of(collapseNameParts(populated) == collapseNameParts(wanted))
                    } else {
                        Tri.of(populated == wanted)
                    }
                RuleOp.EXISTS -> Tri.of(populated.isNotEmpty())
                else -> Tri.FALSE
            }
        }

        if (field.kind == FieldKind.NUMBER) {
            val n = view.number(field) ?: return Tri.UNKNOWN
            val operand = condition.number
            return when (condition.op) {
                RuleOp.EXISTS -> Tri.TRUE
                RuleOp.EQUALS, RuleOp.COUNT_EQ -> Tri.of(operand != null && n == operand)
                RuleOp.NOT_EQUALS -> Tri.of(operand != null && n != operand)
                RuleOp.COUNT_GTE -> Tri.of(operand != null && n >= operand)
                RuleOp.COUNT_LTE -> Tri.of(operand != null && n <= operand)
                else -> Tri.FALSE
            }
        }

        val values = view.values(field) ?: return Tri.UNKNOWN
        return when (condition.op) {
            RuleOp.EXISTS -> Tri.of(values.isNotEmpty())
            RuleOp.COUNT_EQ -> Tri.of(condition.number != null && values.size.toLong() == condition.number)
            RuleOp.COUNT_GTE -> Tri.of(condition.number != null && values.size.toLong() >= condition.number)
            RuleOp.COUNT_LTE -> Tri.of(condition.number != null && values.size.toLong() <= condition.number)
            RuleOp.IN_SET, RuleOp.CONTAINS_ALL -> setOperator(condition, values, condition.values)
            RuleOp.EQUALS -> anyValue(values) { v -> condition.values.any { it.eqi(v) } }
            // Requires a value to be present. "Email address is not boss@example.com" read as a
            // sentence is about a contact who has an address, and it used to be true of every
            // contact with no address at all, which over an exclude rule quietly swept in everyone
            // the user had never thought about.
            RuleOp.NOT_EQUALS -> Tri.of(
                values.isNotEmpty() && values.none { v -> condition.values.any { it.eqi(v) } }
            )
            RuleOp.CONTAINS -> anyValue(values) { v -> condition.values.any { v.containsi(it) } }
            RuleOp.STARTS_WITH -> anyValue(values) { v -> condition.values.any { v.trim().startsWith(it.trim(), true) } }
            RuleOp.ENDS_WITH -> anyValue(values) { v -> condition.values.any { v.trim().endsWith(it.trim(), true) } }
            RuleOp.MATCHES_REGEX -> anyValue(values) { v ->
                condition.values.any { pattern -> regex(pattern)?.containsMatchIn(v) == true }
            }
            RuleOp.ONLY, RuleOp.EXACTLY -> Tri.FALSE
        }
    }

    /**
     * "Any of" versus "all of" over a multi-valued field.
     *
     * [RuleOp.IN_SET] is the OR of the user's list, [RuleOp.CONTAINS_ALL] the AND, which is exactly
     * the difference between "in group G1 or G2" and "in G1 and G2".
     */
    private fun setOperator(condition: Condition, actual: List<String>, wanted: List<String>): Tri =
        when (condition.op) {
            RuleOp.CONTAINS_ALL -> Tri.of(wanted.isNotEmpty() && wanted.all { w -> actual.any { it.eqi(w) } })
            else -> Tri.of(wanted.any { w -> actual.any { it.eqi(w) } })
        }

    private inline fun anyValue(values: List<String>, predicate: (String) -> Boolean): Tri =
        Tri.of(values.any(predicate))

    /**
     * Lets the whole-name shorthand stand in for any of the name's parts.
     *
     * The populated set names the parts individually, so a rule can single out a first name. A rule
     * that only cares that there is *some* name ticks the group instead, and would otherwise match
     * nothing at all, since no contact has a field literally called "first or last name".
     */
    private fun expandNameGroup(wanted: Set<RuleField>): Set<RuleField> =
        if (RuleField.STRUCTURED_NAME in wanted) wanted + VCardContactView.NAME_PARTS else wanted

    /** The inverse, for the one operator that compares the two sets for equality. */
    private fun collapseNameParts(fields: Set<RuleField>): Set<RuleField> =
        fields.mapTo(mutableSetOf()) {
            if (it in VCardContactView.NAME_PARTS) RuleField.STRUCTURED_NAME else it
        }

    /** Null for an unparseable pattern, cached so one bad rule is not recompiled per contact. */
    private fun regex(pattern: String): Regex? {
        // Not getOrPut: it treats a stored null as a miss, so the one thing worth caching, a pattern
        // that does not compile, was the one thing recompiled and re-thrown for every contact.
        if (regexCache.containsKey(pattern)) return regexCache[pattern]
        val compiled = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            null
        }
        regexCache[pattern] = compiled
        return compiled
    }

    private fun String.eqi(other: String) =
        canonicalType(this).equals(canonicalType(other), ignoreCase = true)

    /**
     * Folds the synonyms Android and vCard use for the same contact-point type.
     *
     * The address book shows "Mobile"; the card says `CELL`. Comparing them literally meant the
     * most obvious rule anybody would write, "Phone label is Mobile", matched nothing at all and
     * gave no hint why. Everything that is not one of these few words passes through untouched, so
     * this cannot affect an ordinary value.
     */
    private fun canonicalType(value: String): String {
        val trimmed = value.trim()
        return TYPE_SYNONYMS[trimmed.lowercase()] ?: trimmed
    }

    private fun String.containsi(other: String) = this.contains(other.trim(), ignoreCase = true)

    private companion object {
        /** Maps each spelling to one canonical token; see [canonicalType]. */
        val TYPE_SYNONYMS = mapOf(
            "mobile" to "cell",
            "cell" to "cell",
            "cellular" to "cell",
            "handy" to "cell",
        )
    }
}
