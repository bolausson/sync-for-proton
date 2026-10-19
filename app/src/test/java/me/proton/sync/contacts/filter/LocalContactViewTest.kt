package me.proton.sync.contacts.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one thing a local contact row must never claim to know.
 *
 * Android's contacts provider does not store the name it was given, it completes it. A contact
 * inserted with only a display name comes back with a given and family name split out of it, so
 * "John Quincy Doe" acquires given=John, middle=Quincy, family=Doe and "app@example.com" acquires
 * given=app@example, family=com. Verified against a real account, where every one of the
 * contacts Proton had auto-created carried a manufactured structured name.
 *
 * That makes the local row unable to answer the question the auto-created rules ask. If it answers
 * anyway, the dry run reports that the rule removes nothing and the very next sync removes sixty
 * contacts, which is the worst outcome available: a safety feature that gives false assurance.
 */
class LocalContactViewTest {

    private fun autoCreated() = LocalContactView(
        protonId = "abc",
        displayName = "John Quincy Doe",
        modifyTime = 1_700_000_000L,
        groups = emptyList(),
        fields = mapOf(
            RuleField.FORMATTED_NAME to listOf("John Quincy Doe"),
            RuleField.EMAIL to listOf("john@example.com"),
            // What the provider manufactured. These are present in the cursor and must still not
            // reach the evaluator.
            RuleField.GIVEN_NAME to listOf("John"),
            RuleField.FAMILY_NAME to listOf("Doe"),
            RuleField.ADDITIONAL_NAMES to listOf("Quincy"),
            RuleField.STRUCTURED_NAME to listOf("John", "Doe", "Quincy"),
        ),
    )

    private fun onlyNameAndEmailRule() = FilterRuleSet(
        rules = listOf(
            FilterRule(
                id = "r1",
                name = "Auto-created",
                action = RuleAction.EXCLUDE,
                direction = RuleDirection.DOWNLOAD,
                conditions = listOf(
                    Condition(
                        field = RuleField.POPULATED_FIELDS,
                        op = RuleOp.ONLY,
                        values = listOf("FORMATTED_NAME", "EMAIL"),
                    )
                ),
            )
        )
    )

    @Test
    fun `structured name is never answered from a local row`() {
        val view = autoCreated()
        assertNull(view.values(RuleField.STRUCTURED_NAME))
        assertNull(view.values(RuleField.GIVEN_NAME))
        assertNull(view.values(RuleField.FAMILY_NAME))
        assertNull(view.values(RuleField.ADDITIONAL_NAMES))
        assertNull(view.values(RuleField.NAME_PREFIX))
        assertNull(view.values(RuleField.NAME_SUFFIX))
    }

    @Test
    fun `populated field set is not knowable from a local row`() {
        assertNull(autoCreated().populated())
    }

    /**
     * The regression this file exists for. Before the fix this returned Decided(SYNC), because the
     * manufactured name made the populated set {FORMATTED_NAME, STRUCTURED_NAME, EMAIL} and the
     * subset test failed. The preview then promised no removals for a rule that removes sixty.
     */
    @Test
    fun `an only-fields rule is undecided rather than wrongly kept`() {
        val verdict = FilterEvaluator(onlyNameAndEmailRule())
            .evaluate(autoCreated(), RuleDirection.DOWNLOAD)
        assertEquals(FilterEvaluator.Verdict.NeedsCard, verdict)
    }

    @Test
    fun `fields the provider does store are answered normally`() {
        val view = autoCreated()
        assertEquals(listOf("john@example.com"), view.values(RuleField.EMAIL))
        assertEquals(listOf("John Quincy Doe"), view.values(RuleField.FORMATTED_NAME))
        // Knowably absent, not unknown: the row was read, there simply is no phone number.
        assertEquals(emptyList<String>(), view.values(RuleField.PHONE))
    }

    /** Group rules are the ones the dry run can still answer exactly, so they must stay exact. */
    @Test
    fun `group rules still decide without the card`() {
        val view = LocalContactView(
            protonId = "abc",
            displayName = "Someone",
            modifyTime = null,
            groups = listOf("Family"),
            fields = mapOf(RuleField.EMAIL to listOf("a@example.com")),
        )
        val rules = FilterRuleSet(
            defaultAction = RuleAction.EXCLUDE,
            rules = listOf(
                FilterRule(
                    id = "g1",
                    name = "In a group",
                    action = RuleAction.SYNC,
                    direction = RuleDirection.DOWNLOAD,
                    conditions = listOf(
                        Condition(field = RuleField.GROUP_COUNT, op = RuleOp.COUNT_GTE, number = 1)
                    ),
                )
            )
        )
        val verdict = FilterEvaluator(rules).evaluate(view, RuleDirection.DOWNLOAD)
        assertTrue(verdict is FilterEvaluator.Verdict.Decided)
        assertEquals(RuleAction.SYNC, (verdict as FilterEvaluator.Verdict.Decided).action)
    }

    /** Proton's own bookkeeping is not on the device, so a size rule cannot be previewed either. */
    @Test
    fun `card size is unknown on the phone`() {
        assertNull(autoCreated().number(RuleField.SIZE_BYTES))
        assertNull(autoCreated().number(RuleField.CREATE_TIME))
        assertEquals(1_700_000_000L, autoCreated().number(RuleField.MODIFY_TIME))
    }
}
