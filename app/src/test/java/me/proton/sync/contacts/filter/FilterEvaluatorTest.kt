package me.proton.sync.contacts.filter

import ezvcard.VCard
import ezvcard.property.Email
import ezvcard.property.FormattedName
import ezvcard.property.Note
import ezvcard.property.StructuredName
import ezvcard.property.Telephone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the contact filter engine.
 *
 * Two things are being guarded here. First, that the five rules the feature was asked for are
 * actually expressible and actually work. Second, and more important, the tri-state logic: a
 * mistake there either decrypts every contact on every sync, or silently treats an unread field
 * as empty and excludes contacts it should have kept.
 */
class FilterEvaluatorTest {

    // --- helpers -------------------------------------------------------------------

    private fun header(
        groups: List<String> = emptyList(),
        labelIds: List<String> = emptyList(),
        displayName: String? = "Someone",
        size: Long? = 500,
    ) = HeaderContactView(
        protonId = "cid-1",
        displayName = displayName,
        uid = "uid-1",
        sizeBytes = size,
        createTime = 1000,
        modifyTime = 2000,
        groupNames = groups,
        labelIds = labelIds,
    )

    /** A contact as Proton auto-creates them: a name and an address, nothing else. */
    private fun autoCreatedCard(): VCard = VCard().apply {
        formattedName = FormattedName("Jane Doe")
        structuredName = StructuredName().apply { given = "Jane"; family = "Doe" }
        addEmail(Email("jane@example.com"))
    }

    private fun realCard(): VCard = autoCreatedCard().apply {
        addTelephoneNumber(Telephone("+49 30 1234"))
        addNote(Note("met at a conference"))
    }

    /**
     * The shape `buildVCardFromAndroid` actually produces: empty strings rather than absent
     * properties, and an invented formatted name. If `populated()` were null-aware instead of
     * blank-aware, this card would look like it had a structured name full of content.
     */
    private fun uploadShapedCard(): VCard = VCard().apply {
        formattedName = FormattedName("Unknown")
        structuredName = StructuredName().apply {
            given = "Jane"
            family = ""
            additionalNames.add("")
            prefixes.add("")
            suffixes.add("")
        }
        addEmail(Email("jane@example.com"))
    }

    private fun rule(
        action: RuleAction,
        vararg conditions: Condition,
        match: Combinator = Combinator.ALL,
        direction: RuleDirection = RuleDirection.BOTH,
    ) = FilterRule(
        id = "r1",
        name = "test",
        action = action,
        direction = direction,
        match = match,
        conditions = conditions.toList(),
    )

    private fun decided(v: FilterEvaluator.Verdict): RuleAction =
        (v as FilterEvaluator.Verdict.Decided).action

    // --- the five required rules ---------------------------------------------------

    @Test
    fun syncsOnlyContactsInAllOfSeveralGroups() {
        val set = FilterRuleSet(
            defaultAction = RuleAction.EXCLUDE,
            rules = listOf(
                rule(
                    RuleAction.SYNC,
                    Condition(RuleField.GROUPS, RuleOp.CONTAINS_ALL, values = listOf("G1", "G2", "G3")),
                )
            ),
        )
        val eval = { groups: List<String> ->
            decided(FilterEvaluator(set).evaluate(header(groups), RuleDirection.DOWNLOAD))
        }
        assertEquals(RuleAction.SYNC, eval(listOf("G1", "G2", "G3", "G4")))
        assertEquals(RuleAction.EXCLUDE, eval(listOf("G1", "G2")))
        assertEquals(RuleAction.EXCLUDE, eval(emptyList()))
    }

    @Test
    fun syncsContactsInAnyOfSeveralGroups() {
        val set = FilterRuleSet(
            defaultAction = RuleAction.EXCLUDE,
            rules = listOf(
                rule(
                    RuleAction.SYNC,
                    Condition(RuleField.GROUPS, RuleOp.IN_SET, values = listOf("G1", "G2", "G3")),
                )
            ),
        )
        val eval = { groups: List<String> ->
            decided(FilterEvaluator(set).evaluate(header(groups), RuleDirection.DOWNLOAD))
        }
        assertEquals(RuleAction.SYNC, eval(listOf("G2")))
        assertEquals(RuleAction.EXCLUDE, eval(listOf("Other")))
    }

    @Test
    fun syncsOnlyContactsThatAreInSomeGroup() {
        val set = FilterRuleSet(
            defaultAction = RuleAction.EXCLUDE,
            rules = listOf(
                rule(RuleAction.SYNC, Condition(RuleField.GROUP_COUNT, RuleOp.COUNT_GTE, number = 1))
            ),
        )
        assertEquals(
            RuleAction.SYNC,
            decided(FilterEvaluator(set).evaluate(header(listOf("Anything")), RuleDirection.DOWNLOAD))
        )
        assertEquals(
            RuleAction.EXCLUDE,
            decided(FilterEvaluator(set).evaluate(header(emptyList()), RuleDirection.DOWNLOAD))
        )
    }

    @Test
    fun excludesContactsThatOnlyHaveAnEmail() {
        val set = FilterRuleSet(
            rules = listOf(
                rule(
                    RuleAction.EXCLUDE,
                    Condition(RuleField.POPULATED_FIELDS, RuleOp.ONLY, values = listOf("EMAIL")),
                )
            ),
        )
        val onlyEmail = VCard().apply { addEmail(Email("a@example.com")) }
        assertEquals(
            RuleAction.EXCLUDE,
            decided(FilterEvaluator(set).evaluate(VCardContactView(onlyEmail), RuleDirection.DOWNLOAD))
        )
        assertEquals(
            RuleAction.SYNC,
            decided(FilterEvaluator(set).evaluate(VCardContactView(realCard()), RuleDirection.DOWNLOAD))
        )
    }

    @Test
    fun excludesProtonAutoCreatedContacts() {
        val set = FilterRuleSet(
            rules = listOf(
                rule(
                    RuleAction.EXCLUDE,
                    Condition(
                        RuleField.POPULATED_FIELDS, RuleOp.ONLY,
                        values = listOf("FORMATTED_NAME", "STRUCTURED_NAME", "EMAIL"),
                    ),
                )
            ),
        )
        assertEquals(
            RuleAction.EXCLUDE,
            decided(FilterEvaluator(set).evaluate(VCardContactView(autoCreatedCard()), RuleDirection.DOWNLOAD))
        )
        // A contact with a phone number is a real one and must survive.
        assertEquals(
            RuleAction.SYNC,
            decided(FilterEvaluator(set).evaluate(VCardContactView(realCard()), RuleDirection.DOWNLOAD))
        )
    }

    // --- tri-state behaviour -------------------------------------------------------

    @Test
    fun headerOnlyRuleDecidesWithoutTheCard() {
        val set = FilterRuleSet(
            defaultAction = RuleAction.EXCLUDE,
            rules = listOf(rule(RuleAction.SYNC, Condition(RuleField.GROUPS, RuleOp.IN_SET, values = listOf("G1")))),
        )
        val v = FilterEvaluator(set).evaluate(header(listOf("G1")), RuleDirection.DOWNLOAD)
        assertTrue("a group rule must not force a fetch", v is FilterEvaluator.Verdict.Decided)
    }

    @Test
    fun cardFieldOnAHeaderForcesAFetch() {
        val set = FilterRuleSet(
            rules = listOf(rule(RuleAction.EXCLUDE, Condition(RuleField.NOTE, RuleOp.EXISTS))),
        )
        assertEquals(
            FilterEvaluator.Verdict.NeedsCard,
            FilterEvaluator(set).evaluate(header(), RuleDirection.DOWNLOAD)
        )
    }

    @Test
    fun definiteFalseShortCircuitsAnAllRuleDespiteUnknowns() {
        // group is knowable and false; the note is unknown. ALL can still be settled as false,
        // which is what lets most contacts skip decryption.
        val set = FilterRuleSet(
            rules = listOf(
                rule(
                    RuleAction.EXCLUDE,
                    Condition(RuleField.GROUPS, RuleOp.IN_SET, values = listOf("G1")),
                    Condition(RuleField.NOTE, RuleOp.EXISTS),
                    match = Combinator.ALL,
                )
            ),
        )
        val v = FilterEvaluator(set).evaluate(header(listOf("Other")), RuleDirection.DOWNLOAD)
        assertEquals(RuleAction.SYNC, decided(v))
    }

    @Test
    fun definiteTrueShortCircuitsAnAnyRuleDespiteUnknowns() {
        val set = FilterRuleSet(
            rules = listOf(
                rule(
                    RuleAction.EXCLUDE,
                    Condition(RuleField.GROUPS, RuleOp.IN_SET, values = listOf("G1")),
                    Condition(RuleField.NOTE, RuleOp.EXISTS),
                    match = Combinator.ANY,
                )
            ),
        )
        val v = FilterEvaluator(set).evaluate(header(listOf("G1")), RuleDirection.DOWNLOAD)
        assertEquals(RuleAction.EXCLUDE, decided(v))
    }

    @Test
    fun anUndecidableEarlierRuleBlocksLaterOnes() {
        // Rule 1 cannot be judged from the header. Rule 2 would match. Deciding on rule 2 would be
        // wrong, because rule 1 might have claimed this contact once its card was read.
        val set = FilterRuleSet(
            rules = listOf(
                rule(RuleAction.SYNC, Condition(RuleField.NOTE, RuleOp.EXISTS)),
                rule(RuleAction.EXCLUDE, Condition(RuleField.GROUPS, RuleOp.IN_SET, values = listOf("G1"))),
            ),
        )
        assertEquals(
            FilterEvaluator.Verdict.NeedsCard,
            FilterEvaluator(set).evaluate(header(listOf("G1")), RuleDirection.DOWNLOAD)
        )
    }

    @Test
    fun firstMatchWins() {
        val set = FilterRuleSet(
            rules = listOf(
                rule(RuleAction.SYNC, Condition(RuleField.GROUPS, RuleOp.IN_SET, values = listOf("VIP"))),
                rule(RuleAction.EXCLUDE, Condition(RuleField.GROUP_COUNT, RuleOp.COUNT_GTE, number = 0)),
            ),
        )
        assertEquals(
            RuleAction.SYNC,
            decided(FilterEvaluator(set).evaluate(header(listOf("VIP")), RuleDirection.DOWNLOAD))
        )
    }

    // --- direction -----------------------------------------------------------------

    @Test
    fun ruleIsIgnoredOnTheOtherDirection() {
        val set = FilterRuleSet(
            rules = listOf(
                rule(
                    RuleAction.EXCLUDE,
                    Condition(RuleField.GROUPS, RuleOp.IN_SET, values = listOf("G1")),
                    direction = RuleDirection.UPLOAD,
                )
            ),
        )
        assertEquals(
            RuleAction.SYNC,
            decided(FilterEvaluator(set).evaluate(header(listOf("G1")), RuleDirection.DOWNLOAD))
        )
        assertEquals(
            RuleAction.EXCLUDE,
            decided(FilterEvaluator(set).evaluate(header(listOf("G1")), RuleDirection.UPLOAD))
        )
    }

    // --- the traps -----------------------------------------------------------------

    @Test
    fun populatedFieldsIgnoresBlankValuesFromTheUploadPath() {
        val populated = VCardContactView(uploadShapedCard()).populated()
        // Blank additional names, prefixes and suffixes must not register as content, or the
        // auto-created rule would never match anything on the way up.
        assertTrue(RuleField.EMAIL in populated)
        assertTrue(RuleField.FORMATTED_NAME in populated)
        assertTrue("given name is real content", RuleField.GIVEN_NAME in populated)
        // The parts are reported individually now, so the blank ones can be checked one by one
        // rather than hiding inside a single lumped-together name entry.
        assertTrue("family name is blank", RuleField.FAMILY_NAME !in populated)
        assertTrue("additional names are blank", RuleField.ADDITIONAL_NAMES !in populated)
        assertTrue("prefix is blank", RuleField.NAME_PREFIX !in populated)
        assertTrue("suffix is blank", RuleField.NAME_SUFFIX !in populated)
        assertTrue(RuleField.PHONE !in populated)
        assertTrue(RuleField.NOTE !in populated)
    }

    @Test
    fun emptyRuleSetSyncsEverything() {
        val v = FilterEvaluator(FilterRuleSet.EMPTY).evaluate(header(), RuleDirection.DOWNLOAD)
        assertEquals(RuleAction.SYNC, decided(v))
    }

    @Test
    fun ruleWithNoConditionsNeverMatches() {
        // A half-written rule must not behave as "match everything".
        val set = FilterRuleSet(rules = listOf(rule(RuleAction.EXCLUDE)))
        assertEquals(
            RuleAction.SYNC,
            decided(FilterEvaluator(set).evaluate(header(), RuleDirection.DOWNLOAD))
        )
    }

    @Test
    fun invalidRegexNeverMatchesAndNeverThrows() {
        val set = FilterRuleSet(
            rules = listOf(
                rule(
                    RuleAction.EXCLUDE,
                    Condition(RuleField.EMAIL, RuleOp.MATCHES_REGEX, values = listOf("([unclosed")),
                )
            ),
        )
        val v = FilterEvaluator(set).evaluate(VCardContactView(autoCreatedCard()), RuleDirection.DOWNLOAD)
        assertEquals(RuleAction.SYNC, decided(v))
    }

    @Test
    fun regexMatchesEmailDomain() {
        val set = FilterRuleSet(
            rules = listOf(
                rule(
                    RuleAction.EXCLUDE,
                    Condition(RuleField.EMAIL, RuleOp.MATCHES_REGEX, values = listOf("@example\\.com$")),
                )
            ),
        )
        assertEquals(
            RuleAction.EXCLUDE,
            decided(FilterEvaluator(set).evaluate(VCardContactView(autoCreatedCard()), RuleDirection.DOWNLOAD))
        )
    }

    @Test
    fun groupConditionMatchesOnLabelIdSoRenamesDoNotOrphanRules() {
        val set = FilterRuleSet(
            defaultAction = RuleAction.EXCLUDE,
            rules = listOf(
                rule(
                    RuleAction.SYNC,
                    Condition(
                        RuleField.GROUPS, RuleOp.IN_SET,
                        values = listOf("Old name"), refIds = listOf("label-7"),
                    ),
                )
            ),
        )
        // The group was renamed on Proton, so only the id still lines up.
        val view = header(groups = listOf("New name"), labelIds = listOf("label-7"))
        assertEquals(RuleAction.SYNC, decided(FilterEvaluator(set).evaluate(view, RuleDirection.DOWNLOAD)))
    }

    @Test
    fun negatedConditionInverts() {
        val set = FilterRuleSet(
            rules = listOf(
                rule(
                    RuleAction.EXCLUDE,
                    Condition(RuleField.GROUPS, RuleOp.IN_SET, negate = true, values = listOf("Keep")),
                )
            ),
        )
        assertEquals(
            RuleAction.EXCLUDE,
            decided(FilterEvaluator(set).evaluate(header(listOf("Other")), RuleDirection.DOWNLOAD))
        )
        assertEquals(
            RuleAction.SYNC,
            decided(FilterEvaluator(set).evaluate(header(listOf("Keep")), RuleDirection.DOWNLOAD))
        )
    }
}
