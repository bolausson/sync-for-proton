package me.proton.sync.contacts.filter

import ezvcard.VCard
import ezvcard.property.Birthday
import ezvcard.property.Email
import ezvcard.property.FormattedName
import ezvcard.util.PartialDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two ways the filter used to quietly destroy or misjudge data.
 *
 * Both were found by auditing the feature rather than by using it, which is the point of pinning
 * them here: neither shows up as an error, and both look like the app working normally.
 */
class FilterDirectionTest {

    private fun contact(withBirthday: Boolean = false): VCardContactView {
        val card = VCard()
        card.formattedName = FormattedName("Someone")
        card.addEmail(Email("someone@example.com"))
        if (withBirthday) {
            // A birthday with no year, the form iOS and DAVx5 write. ez-vcard parks this in its
            // partialDate slot with text and date both null.
            card.birthday = Birthday(PartialDate.builder().month(2).date(15).build())
        }
        return VCardContactView(card)
    }

    private fun excludeEverythingElse() = FilterRuleSet(
        defaultAction = RuleAction.EXCLUDE,
        rules = listOf(
            FilterRule(
                id = "r1",
                name = "Only my groups",
                action = RuleAction.SYNC,
                // Download-only, which is what every template except the group ones produces.
                direction = RuleDirection.DOWNLOAD,
                conditions = listOf(
                    Condition(field = RuleField.GROUP_COUNT, op = RuleOp.COUNT_GTE, number = 1)
                ),
            )
        )
    )

    /**
     * The fall-through is a statement about what reaches the phone, not about what leaves it.
     *
     * Read as an upload instruction, "everything else: do not sync" stopped every local edit from
     * ever reaching Proton: the upload path cleared the dirty flag and moved on, and the next
     * download overwrote the user's change. Nothing in the UI said so, and the dry run only ever
     * evaluates the download direction, so the confirmation dialog could not have warned about it.
     */
    @Test
    fun `an exclude fall-through does not silently block uploads`() {
        val verdict = FilterEvaluator(excludeEverythingElse())
            .evaluate(contact(), RuleDirection.UPLOAD)
        assertTrue(verdict is FilterEvaluator.Verdict.Decided)
        assertEquals(RuleAction.SYNC, (verdict as FilterEvaluator.Verdict.Decided).action)
    }

    /** The same rule set still excludes on the way down; that is what the user asked for. */
    @Test
    fun `an exclude fall-through still applies on download`() {
        val verdict = FilterEvaluator(excludeEverythingElse())
            .evaluate(contact(), RuleDirection.DOWNLOAD)
        assertEquals(
            RuleAction.EXCLUDE,
            (verdict as FilterEvaluator.Verdict.Decided).action
        )
    }

    /** An explicit upload rule is still honoured, so excluding an upload remains possible. */
    @Test
    fun `an explicit upload rule still excludes`() {
        val rules = FilterRuleSet(
            rules = listOf(
                FilterRule(
                    id = "u1",
                    name = "Never upload",
                    action = RuleAction.EXCLUDE,
                    direction = RuleDirection.UPLOAD,
                    conditions = listOf(Condition(field = RuleField.EMAIL, op = RuleOp.EXISTS)),
                )
            )
        )
        val verdict = FilterEvaluator(rules).evaluate(contact(), RuleDirection.UPLOAD)
        assertEquals(RuleAction.EXCLUDE, (verdict as FilterEvaluator.Verdict.Decided).action)
    }

    /**
     * A year-less birthday exists, and the filter has to see it.
     *
     * Reading only `text` and `date` missed the partialDate form entirely, so the rule said the
     * contact had no birthday while the row the download mapper wrote from the very same card said
     * it did. Any BIRTHDAY rule then judged those contacts the opposite way round from everything
     * else on the phone.
     */
    @Test
    fun `a year-less birthday is not read as absent`() {
        val values = contact(withBirthday = true).values(RuleField.BIRTHDAY)
        assertEquals(listOf("--02-15"), values)
    }

    @Test
    fun `a contact with no birthday still reports none`() {
        assertEquals(emptyList<String>(), contact().values(RuleField.BIRTHDAY))
    }

    /** And the populated set follows, which is what the auto-created rules actually test. */
    @Test
    fun `a year-less birthday counts as populated content`() {
        assertTrue(contact(withBirthday = true).populated().contains(RuleField.BIRTHDAY))
        assertTrue(!contact().populated().contains(RuleField.BIRTHDAY))
    }
}
