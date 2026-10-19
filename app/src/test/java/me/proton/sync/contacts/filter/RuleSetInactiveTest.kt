package me.proton.sync.contacts.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Filtered" has to describe the effect, not the length of the list.
 *
 * A rule list where everything is switched off filters nothing, and the account card used to
 * announce "Filtered" anyway because it asked whether the list was empty. The sync already knew
 * better: it skips filtering entirely when [FilterRuleSet.isInactive], so the card was the one
 * screen disagreeing with what the app actually did.
 */
class RuleSetInactiveTest {

    private fun rule(enabled: Boolean, action: RuleAction = RuleAction.EXCLUDE) = FilterRule(
        id = "r", name = "r", enabled = enabled, action = action,
        direction = RuleDirection.DOWNLOAD,
        conditions = listOf(Condition(field = RuleField.EMAIL, op = RuleOp.EXISTS)),
    )

    @Test
    fun `no rules at all is inactive`() {
        assertTrue(FilterRuleSet.EMPTY.isInactive)
    }

    @Test
    fun `a list of switched-off rules is inactive`() {
        val set = FilterRuleSet(rules = listOf(rule(enabled = false), rule(enabled = false)))
        assertTrue("nothing is being filtered", set.isInactive)
    }

    @Test
    fun `one switched-on rule is enough to be active`() {
        val set = FilterRuleSet(rules = listOf(rule(enabled = false), rule(enabled = true)))
        assertFalse(set.isInactive)
    }

    /** The fall-through filters on its own, even with no rules to go with it. */
    @Test
    fun `an exclude fall-through is active by itself`() {
        val set = FilterRuleSet(defaultAction = RuleAction.EXCLUDE)
        assertFalse(set.isInactive)
    }
}
