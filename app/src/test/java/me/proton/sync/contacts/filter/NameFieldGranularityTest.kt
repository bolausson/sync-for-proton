package me.proton.sync.contacts.filter

import ezvcard.VCard
import ezvcard.property.Email
import ezvcard.property.FormattedName
import ezvcard.property.StructuredName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Fields with content" now names the parts of a name instead of one lump.
 *
 * The picker used to offer a single "first or last name" entry and nothing finer, so a rule could
 * not say "only a first name". It offers both now, and the group has to keep working as a shorthand
 * for any part: the whole auto-created feature rests on a rule written that way, and it would be a
 * poor trade to gain precision by breaking it.
 */
class NameFieldGranularityTest {

    private fun contact(
        fn: String? = "Someone",
        given: String? = null,
        family: String? = null,
        email: String? = "someone@example.com",
    ): VCardContactView {
        val card = VCard()
        if (fn != null) card.formattedName = FormattedName(fn)
        if (given != null || family != null) {
            card.structuredName = StructuredName().apply {
                this.given = given
                this.family = family
            }
        }
        if (email != null) card.addEmail(Email(email))
        return VCardContactView(card)
    }

    private fun rule(vararg fields: RuleField, op: RuleOp = RuleOp.ONLY) = FilterRuleSet(
        rules = listOf(
            FilterRule(
                id = "r", name = "r", action = RuleAction.EXCLUDE,
                direction = RuleDirection.DOWNLOAD,
                conditions = listOf(
                    Condition(
                        field = RuleField.POPULATED_FIELDS,
                        op = op,
                        values = fields.map { it.name },
                    )
                ),
            )
        )
    )

    private fun matches(rules: FilterRuleSet, view: VCardContactView): Boolean {
        val verdict = FilterEvaluator(rules).evaluate(view, RuleDirection.DOWNLOAD)
        return verdict is FilterEvaluator.Verdict.Decided && verdict.action == RuleAction.EXCLUDE
    }

    /** The parts are reported individually, which is what makes a precise rule possible at all. */
    @Test
    fun `the populated set names the parts of a name`() {
        val populated = contact(given = "Jane", family = "Doe").populated()
        assertTrue(RuleField.GIVEN_NAME in populated)
        assertTrue(RuleField.FAMILY_NAME in populated)
        assertFalse("the lump should be gone", RuleField.STRUCTURED_NAME in populated)
    }

    /** The flagship rule. Ticking the group must still cover a contact with any name at all. */
    @Test
    fun `the whole-name shorthand still covers every part`() {
        val rules = rule(RuleField.FORMATTED_NAME, RuleField.STRUCTURED_NAME, RuleField.EMAIL)
        assertTrue(matches(rules, contact(given = "Jane", family = "Doe")))
        assertTrue(matches(rules, contact(given = "Jane")))
        assertTrue(matches(rules, contact(family = "Doe")))
        assertTrue(matches(rules, contact()))
    }

    /** And it still lets a contact with anything more escape. */
    @Test
    fun `the shorthand does not cover fields outside the name`() {
        val card = VCard().apply {
            formattedName = FormattedName("Jane Doe")
            structuredName = StructuredName().apply { given = "Jane" }
            addEmail(Email("jane@example.com"))
            addTelephoneNumber(ezvcard.property.Telephone("+49 111"))
        }
        val rules = rule(RuleField.FORMATTED_NAME, RuleField.STRUCTURED_NAME, RuleField.EMAIL)
        assertFalse(matches(rules, VCardContactView(card)))
    }

    /** The new ability: a rule can single out one part of the name. */
    @Test
    fun `a rule can now say only a first name`() {
        val rules = rule(RuleField.FORMATTED_NAME, RuleField.GIVEN_NAME, RuleField.EMAIL)
        assertTrue("a contact with only a first name qualifies", matches(rules, contact(given = "Jane")))
        assertFalse(
            "one with a surname as well does not",
            matches(rules, contact(given = "Jane", family = "Doe"))
        )
    }

    /** The auto-created rule: no name parts at all, just a display name and an address. */
    @Test
    fun `a contact Proton invented still matches the auto-created rule`() {
        val rules = rule(RuleField.FORMATTED_NAME, RuleField.EMAIL)
        assertTrue(matches(rules, contact(fn = "AB")))
        assertFalse(
            "somebody with a real first name is not auto-created",
            matches(rules, contact(fn = "Jane Doe", given = "Jane"))
        )
    }

    /** EXACTLY compares with the parts folded back together, so the group keeps its old meaning. */
    @Test
    fun `exactly treats the name as one thing`() {
        val rules = rule(
            RuleField.FORMATTED_NAME, RuleField.STRUCTURED_NAME, RuleField.EMAIL,
            op = RuleOp.EXACTLY,
        )
        assertTrue(matches(rules, contact(given = "Jane")))
        assertTrue(matches(rules, contact(given = "Jane", family = "Doe")))
        assertFalse("no name part at all is not the same set", matches(rules, contact()))
    }

    /** The picker offers the group and the parts; the evaluation walks only the parts. */
    @Test
    fun `the picker offers both the group and the parts`() {
        assertTrue(RuleField.STRUCTURED_NAME in VCardContactView.PICKER_FIELDS)
        assertTrue(RuleField.GIVEN_NAME in VCardContactView.PICKER_FIELDS)
        assertTrue(RuleField.FAMILY_NAME in VCardContactView.PICKER_FIELDS)
        assertFalse(RuleField.STRUCTURED_NAME in VCardContactView.CONTENT_FIELDS)
        assertEquals(
            "no field should be offered twice",
            VCardContactView.PICKER_FIELDS.size,
            VCardContactView.PICKER_FIELDS.toSet().size
        )
    }
}
