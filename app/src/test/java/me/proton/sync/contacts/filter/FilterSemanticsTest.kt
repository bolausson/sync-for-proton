package me.proton.sync.contacts.filter

import ezvcard.VCard
import ezvcard.property.Email
import ezvcard.property.FormattedName
import ezvcard.property.Impp
import ezvcard.property.Telephone
import ezvcard.parameter.TelephoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules that read as if they work and quietly did not.
 *
 * Each of these came out of an audit rather than out of using the app, which is the point: none of
 * them produces an error, a warning, or any visible sign. They just decide somebody's address book
 * differently from how it reads on screen.
 */
class FilterSemanticsTest {

    private fun view(card: VCard) = VCardContactView(card)

    private fun evaluate(rules: FilterRuleSet, card: VCard): RuleAction? {
        val v = FilterEvaluator(rules).evaluate(view(card), RuleDirection.DOWNLOAD)
        return (v as? FilterEvaluator.Verdict.Decided)?.action
    }

    private fun ruleOn(field: RuleField, op: RuleOp, vararg values: String) = FilterRuleSet(
        rules = listOf(
            FilterRule(
                id = "r", name = "r", action = RuleAction.EXCLUDE,
                direction = RuleDirection.DOWNLOAD,
                conditions = listOf(Condition(field = field, op = op, values = values.toList())),
            )
        )
    )

    /**
     * The address book says "Mobile", the card says CELL. Comparing the two literally meant the
     * most obvious rule anybody would write matched nothing, with nothing on screen to explain it.
     */
    @Test
    fun `a phone type written as Mobile matches a card that says cell`() {
        val card = VCard().apply {
            formattedName = FormattedName("Jane")
            addTelephoneNumber(Telephone("+49 111").apply { types.add(TelephoneType.CELL) })
        }
        assertEquals(RuleAction.EXCLUDE, evaluate(ruleOn(RuleField.PHONE_TYPE, RuleOp.EQUALS, "Mobile"), card))
        assertEquals(RuleAction.EXCLUDE, evaluate(ruleOn(RuleField.PHONE_TYPE, RuleOp.EQUALS, "cell"), card))
    }

    /** An ordinary value must not be touched by that folding. */
    @Test
    fun `the synonym folding leaves normal values alone`() {
        val card = VCard().apply {
            formattedName = FormattedName("Jane")
            addEmail(Email("jane@example.com"))
        }
        assertEquals(RuleAction.EXCLUDE, evaluate(ruleOn(RuleField.EMAIL, RuleOp.EQUALS, "jane@example.com"), card))
        assertEquals(RuleAction.SYNC, evaluate(ruleOn(RuleField.EMAIL, RuleOp.EQUALS, "someone@example.org"), card))
    }

    /**
     * "Email address is not X" reads as a statement about somebody who has an address. It used to
     * be true of every contact with no address at all, which over an exclude rule swept in everyone
     * the user had never considered.
     */
    @Test
    fun `is not requires the field to have a value`() {
        val withEmail = VCard().apply {
            formattedName = FormattedName("Jane")
            addEmail(Email("jane@example.com"))
        }
        val withoutEmail = VCard().apply { formattedName = FormattedName("Bob") }
        assertEquals(
            "a different address still matches",
            RuleAction.EXCLUDE,
            evaluate(ruleOn(RuleField.EMAIL, RuleOp.NOT_EQUALS, "boss@example.com"), withEmail)
        )
        assertEquals(
            "no address at all is not 'is not'",
            RuleAction.SYNC,
            evaluate(ruleOn(RuleField.EMAIL, RuleOp.NOT_EQUALS, "boss@example.com"), withoutEmail)
        )
    }

    /**
     * The one that could take a real contact off somebody's phone. Proton stores instant messaging
     * handles; the sync maps none of them, so a hand-filled contact used to report the same shape
     * as a stub Proton had invented, and the auto-created rule removed it.
     */
    @Test
    fun `a contact carrying content we do not map is not mistaken for a stub`() {
        val stub = VCard().apply {
            formattedName = FormattedName("AB")
            addEmail(Email("ab@example.com"))
        }
        val real = VCard().apply {
            formattedName = FormattedName("Jane")
            addEmail(Email("jane@example.com"))
            addImpp(Impp.skype("jane.doe"))
        }
        val autoCreated = FilterRuleSet(
            rules = listOf(
                FilterRule(
                    id = "r", name = "auto", action = RuleAction.EXCLUDE,
                    direction = RuleDirection.DOWNLOAD,
                    conditions = listOf(
                        Condition(
                            field = RuleField.POPULATED_FIELDS, op = RuleOp.ONLY,
                            values = listOf(RuleField.FORMATTED_NAME.name, RuleField.EMAIL.name),
                        )
                    ),
                )
            )
        )
        assertEquals("the stub is still caught", RuleAction.EXCLUDE, evaluate(autoCreated, stub))
        assertEquals("the real contact is kept", RuleAction.SYNC, evaluate(autoCreated, real))
        assertTrue(RuleField.OTHER_CONTENT in view(real).populated())
        assertFalse(RuleField.OTHER_CONTENT in view(stub).populated())
    }

    /**
     * A group rule carries Proton label ids so it survives a rename. That path could only express
     * "is any of", and it used to run for every operator, so changing the test on a group preset
     * left the rule doing something other than what the list said it did.
     */
    @Test
    fun `a group operator is not overridden by the label ids`() {
        val header = HeaderContactView(
            protonId = "p", displayName = "Jane", uid = "u", sizeBytes = 100,
            createTime = 0, modifyTime = 0,
            groupNames = listOf("Family"), labelIds = listOf("id-family"),
        )
        val inSet = FilterRuleSet(
            rules = listOf(
                FilterRule(
                    id = "r", name = "r", action = RuleAction.EXCLUDE,
                    direction = RuleDirection.DOWNLOAD,
                    conditions = listOf(
                        Condition(
                            field = RuleField.GROUPS, op = RuleOp.IN_SET,
                            values = listOf("Family"), refIds = listOf("id-family"),
                        )
                    ),
                )
            )
        )
        assertEquals("matching by id still works", RuleAction.EXCLUDE,
            (FilterEvaluator(inSet).evaluate(header, RuleDirection.DOWNLOAD)
                as FilterEvaluator.Verdict.Decided).action)

        // Same condition, same ids, but a test the id path cannot express. It must fall through to
        // the names rather than silently behaving as "is any of".
        val notEquals = FilterRuleSet(
            rules = listOf(
                FilterRule(
                    id = "r", name = "r", action = RuleAction.EXCLUDE,
                    direction = RuleDirection.DOWNLOAD,
                    conditions = listOf(
                        Condition(
                            field = RuleField.GROUPS, op = RuleOp.NOT_EQUALS,
                            values = listOf("Family"), refIds = listOf("id-family"),
                        )
                    ),
                )
            )
        )
        assertEquals(
            "in Family, so 'is not Family' must be false",
            RuleAction.SYNC,
            (FilterEvaluator(notEquals).evaluate(header, RuleDirection.DOWNLOAD)
                as FilterEvaluator.Verdict.Decided).action
        )
    }
}
