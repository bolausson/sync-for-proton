package me.proton.sync.contacts.filter

import java.util.UUID

/**
 * Ready-made rules for the cases people actually ask for.
 *
 * Two of these exist because nobody would assemble them by hand: recognising the contacts Proton
 * creates automatically means saying "the set of fields with content is a subset of {formatted
 * name, structured name, email}", which is precise, correct, and impossible to discover in a
 * generic condition editor.
 *
 * Also used by the diagnostic probe, which reports how many contacts each preset would affect
 * without changing anything.
 */
object FilterPresets {

    const val ID_ONLY_EMAIL = "only_email"
    const val ID_NAME_AND_EMAIL = "name_and_email"
    const val ID_NO_STRUCTURED_NAME = "no_structured_name"
    const val ID_ADDRESS_AS_NAME = "address_as_name"
    const val ID_IN_ANY_GROUP = "in_any_group"
    const val ID_IN_ALL_GROUPS = "in_all_groups"
    const val ID_IN_ANY_OF_GROUPS = "in_any_of_groups"

    /**
     * Contacts carrying nothing but an email address.
     *
     * Subsumed by [looksAutoCreated]; kept separate because some accounts have bare address entries
     * with no name at all, and a user may want only those gone.
     */
    fun onlyEmail(name: String): FilterRule = FilterRule(
        id = UUID.randomUUID().toString(),
        name = name,
        action = RuleAction.EXCLUDE,
        direction = RuleDirection.DOWNLOAD,
        match = Combinator.ALL,
        conditions = listOf(
            Condition(
                field = RuleField.POPULATED_FIELDS,
                op = RuleOp.ONLY,
                values = listOf(RuleField.EMAIL.name),
            )
        ),
    )

    /**
     * Contacts carrying nothing but a name and an address.
     *
     * Named for what it actually tests, not for what it is usually used for. Measured against a
     * real account, it also selected entries the account holder
     * had clearly typed themselves: a committee, a building address. **"Has only a name and an
     * email" is not the same as "Proton invented this"**, and calling this preset "auto-created"
     * would have invited people to bulk-remove contacts they had entered by hand. Use
     * [looksAutoCreated] for the stricter test.
     */
    fun onlyNameAndEmail(name: String): FilterRule = FilterRule(
        id = UUID.randomUUID().toString(),
        name = name,
        action = RuleAction.EXCLUDE,
        direction = RuleDirection.DOWNLOAD,
        match = Combinator.ALL,
        conditions = listOf(nameAndEmailOnly()),
    )

    /**
     * The contacts Proton creates from your correspondence.
     *
     * The test is **the absence of a structured name**, not the shape of the display name. A
     * contact entered by hand in Proton has First and Last filled in, which is the structured
     * name; one Proton created from an email has only a display name and an address. Because
     * [RuleOp.ONLY] is a subset test, anything carrying a structured name fails to be a subset of
     * {formatted name, email} and is left alone.
     *
     * This replaced an earlier attempt that tested whether the display name contained an `@`. That
     * was wrong: Proton names an auto-created contact after the **display name in the sender's From
     * header**, not after the address, so a contact from `AB <news@example.com>` is
     * stored as "AB" and the address test missed it entirely.
     */
    fun looksAutoCreated(name: String): FilterRule = FilterRule(
        id = UUID.randomUUID().toString(),
        name = name,
        action = RuleAction.EXCLUDE,
        direction = RuleDirection.DOWNLOAD,
        match = Combinator.ALL,
        conditions = listOf(
            Condition(
                field = RuleField.POPULATED_FIELDS,
                op = RuleOp.ONLY,
                values = listOf(RuleField.FORMATTED_NAME.name, RuleField.EMAIL.name),
            )
        ),
    )

    /**
     * Narrower still: the display name is itself an address.
     *
     * Kept only for comparison. It catches the subset of auto-created contacts whose sender set no
     * display name, and misses the rest, so it is not the right default.
     */
    fun addressAsName(name: String): FilterRule = FilterRule(
        id = UUID.randomUUID().toString(),
        name = name,
        action = RuleAction.EXCLUDE,
        direction = RuleDirection.DOWNLOAD,
        match = Combinator.ALL,
        conditions = listOf(
            Condition(field = RuleField.DISPLAY_NAME, op = RuleOp.CONTAINS, values = listOf("@")),
            nameAndEmailOnly(),
        ),
    )

    /** Populated fields are a subset of {formatted name, structured name, email}. */
    private fun nameAndEmailOnly() = Condition(
        field = RuleField.POPULATED_FIELDS,
        op = RuleOp.ONLY,
        values = listOf(
            RuleField.FORMATTED_NAME.name,
            RuleField.STRUCTURED_NAME.name,
            RuleField.EMAIL.name,
        ),
    )

    /**
     * Sync only contacts that belong to at least one group.
     *
     * Pair with a default action of EXCLUDE; on its own over a SYNC default it does nothing, which
     * is why the rule list screen has to show the fall-through action.
     */
    fun inAnyGroup(name: String): FilterRule = FilterRule(
        id = UUID.randomUUID().toString(),
        name = name,
        action = RuleAction.SYNC,
        direction = RuleDirection.BOTH,
        match = Combinator.ALL,
        conditions = listOf(
            Condition(field = RuleField.GROUP_COUNT, op = RuleOp.COUNT_GTE, number = 1)
        ),
    )

    /** Sync contacts in every one of the chosen groups. */
    fun inAllGroups(name: String, groups: List<Pair<String, String>>): FilterRule =
        groupRule(name, groups, RuleOp.CONTAINS_ALL)

    /** Sync contacts in any one of the chosen groups. */
    fun inAnyOfGroups(name: String, groups: List<Pair<String, String>>): FilterRule =
        groupRule(name, groups, RuleOp.IN_SET)

    /** @param groups label id to label name, both stored so a rename on Proton cannot orphan it. */
    private fun groupRule(
        name: String,
        groups: List<Pair<String, String>>,
        op: RuleOp,
    ): FilterRule = FilterRule(
        id = UUID.randomUUID().toString(),
        name = name,
        action = RuleAction.SYNC,
        direction = RuleDirection.BOTH,
        match = Combinator.ALL,
        conditions = listOf(
            Condition(
                field = RuleField.GROUPS,
                op = op,
                values = groups.map { it.second },
                refIds = groups.map { it.first },
            )
        ),
    )

    /**
     * The rules the diagnostic probe measures, each counted **independently**.
     *
     * Not persisted and never applied. Measured separately rather than as one rule set because
     * first-match-wins would otherwise let the broadest rule absorb the others and report a single
     * meaningless total. Existing so a single verbose sync can size each candidate rule against a
     * real account before any of this is allowed to remove anything.
     */
    fun probeRules(): List<Pair<String, FilterRule>> = listOf(
        ID_NAME_AND_EMAIL to onlyNameAndEmail("probe: name (incl. first/last) and email only"),
        ID_NO_STRUCTURED_NAME to looksAutoCreated("probe: display name and email, no first/last"),
        ID_ADDRESS_AS_NAME to addressAsName("probe: display name is an address"),
        ID_ONLY_EMAIL to onlyEmail("probe: only an email"),
    )
}
