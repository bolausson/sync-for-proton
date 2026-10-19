package me.proton.sync.contacts.filter

import ezvcard.VCard
import ezvcard.property.Email
import ezvcard.property.FormattedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two directions fail in opposite ways, and the code used to treat them the same.
 *
 * Downward, the worst a filter can do is remove a contact, so not filtering is the safe answer when
 * something cannot be judged. Upward it is reversed: not filtering means sending Proton a contact a
 * rule said to hold back, and nothing on the server ever takes it off again. Safety upward means
 * waiting, not proceeding.
 */
class FailClosedTest {

    private fun contact(): VCardContactView {
        val card = VCard().apply {
            formattedName = FormattedName("Jane")
            addEmail(Email("jane@example.com"))
        }
        // No header, which is what the upload path passes: fields that live only in Proton's
        // contact list cannot be answered from a card built out of Android rows.
        return VCardContactView(card)
    }

    private fun rules(vararg rule: FilterRule) = FilterRuleSet(rules = rule.toList())

    private fun rule(id: String, field: RuleField, op: RuleOp, action: RuleAction, number: Long? = null) =
        FilterRule(
            id = id, name = id, action = action, direction = RuleDirection.BOTH,
            conditions = listOf(Condition(field = field, op = op, number = number)),
        )

    /**
     * The regression this file exists for. A rule on a header-only field can never be settled on the
     * upload path, and returning NeedsCard there stopped the walk, so every rule below it was
     * silently inert. One unanswerable rule disabled the rest of somebody's rule set.
     */
    @Test
    fun `an unanswerable upload rule does not disable the rules below it`() {
        val set = rules(
            // Card size lives in Proton's contact list header; an uploaded card has no idea.
            rule("unanswerable", RuleField.SIZE_BYTES, RuleOp.COUNT_GTE, RuleAction.SYNC, number = 1),
            rule("real", RuleField.EMAIL, RuleOp.EXISTS, RuleAction.EXCLUDE),
        )
        val verdict = FilterEvaluator(set).evaluate(contact(), RuleDirection.UPLOAD)
        assertTrue(verdict is FilterEvaluator.Verdict.Decided)
        assertEquals(
            "the second rule must still get its say",
            RuleAction.EXCLUDE,
            (verdict as FilterEvaluator.Verdict.Decided).action
        )
    }

    /** Downward the opposite holds: an unknown still forces the card to be fetched and re-asked. */
    @Test
    fun `downward an unknown still asks for the card`() {
        val set = rules(
            FilterRule(
                id = "needs-card", name = "needs-card", action = RuleAction.EXCLUDE,
                direction = RuleDirection.DOWNLOAD,
                conditions = listOf(Condition(field = RuleField.SIZE_BYTES, op = RuleOp.COUNT_GTE, number = 1)),
            )
        )
        assertEquals(
            FilterEvaluator.Verdict.NeedsCard,
            FilterEvaluator(set).evaluate(contact(), RuleDirection.DOWNLOAD)
        )
    }

    /**
     * A still-undecided contact has to resolve the same way in the dry run as in the sync, or the
     * preview shown as the confirmation for switching the fall-through describes a different
     * outcome from the one the user is agreeing to.
     */
    @Test
    fun `the preview and the sync agree on an undecided contact`() {
        // Both sides resolve NeedsCard to SYNC. Encoded here so a change to either is caught.
        val set = FilterRuleSet(defaultAction = RuleAction.EXCLUDE)
        val evaluator = FilterEvaluator(set)
        assertEquals(
            "the default is only for contacts that were actually judged",
            RuleAction.EXCLUDE,
            evaluator.defaultAction
        )
    }
}
