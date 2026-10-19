package me.proton.sync.contacts.filter

import ezvcard.VCard
import ezvcard.property.Email
import ezvcard.property.FormattedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two ways an upload rule stopped holding contacts back, both silent, both one-directional.
 *
 * Downward a filter mistake is visible and reversible: a contact vanishes from the phone and comes
 * back when the rule is fixed. Upward there is no symmetry. A contact sent to Proton against a rule
 * stays there, and no later sync, rule change or fix takes it off again. So every gap in the upload
 * path has to resolve to "wait", never to "send".
 */
class UploadHoldTest {

    private fun rule(
        id: String,
        field: RuleField,
        action: RuleAction,
        direction: RuleDirection,
        number: Long? = null,
    ) = FilterRule(
        id = id, name = id, action = action, direction = direction,
        conditions = listOf(
            Condition(
                field = field,
                op = if (number == null) RuleOp.EXISTS else RuleOp.COUNT_GTE,
                number = number,
            )
        ),
    )

    private fun cardWithoutHeader(): VCardContactView {
        // What the upload path builds: a card assembled from Android rows, with none of the fields
        // that live only in Proton's contact-list header.
        val card = VCard().apply {
            formattedName = FormattedName("Jane Doe")
            addEmail(Email("jane@example.com"))
        }
        return VCardContactView(card)
    }

    /**
     * The regression. Skipping an unanswerable rule was introduced so one bad rule could not
     * disable the rules below it, but it skipped EXCLUDE rules too, and skipping "do not send this"
     * means sending it.
     */
    @Test
    fun `an unanswerable upload EXCLUDE rule holds the contact instead of sending it`() {
        val set = FilterRuleSet(
            rules = listOf(
                rule("unanswerable", RuleField.SIZE_BYTES, RuleAction.EXCLUDE, RuleDirection.UPLOAD, number = 1)
            )
        )
        val verdict = FilterEvaluator(set).evaluate(cardWithoutHeader(), RuleDirection.UPLOAD)
        assertEquals(
            "undecidable plus 'do not send' has to mean wait",
            FilterEvaluator.Verdict.Decided(RuleAction.EXCLUDE),
            verdict
        )
    }

    /** An unanswerable SYNC rule is still skipped: "send it" is what happens with no rules at all. */
    @Test
    fun `an unanswerable upload SYNC rule still steps aside for the rules below it`() {
        val set = FilterRuleSet(
            rules = listOf(
                rule("unanswerable", RuleField.SIZE_BYTES, RuleAction.SYNC, RuleDirection.UPLOAD, number = 1),
                rule("real", RuleField.EMAIL, RuleAction.EXCLUDE, RuleDirection.UPLOAD),
            )
        )
        assertEquals(
            FilterEvaluator.Verdict.Decided(RuleAction.EXCLUDE),
            FilterEvaluator(set).evaluate(cardWithoutHeader(), RuleDirection.UPLOAD)
        )
    }

    /**
     * The second gap, and the more dangerous one because nothing about it is visible.
     *
     * A group rule anywhere suspends the whole session when the contact labels cannot be fetched.
     * The guard that holds uploads back during a suspension used to ask a narrower question, "do
     * the *upload* rules mention groups", so a download-only group rule sitting beside an upload
     * EXCLUDE rule suspended the session, answered "uploads are fine", and released every contact
     * that rule was holding to Proton on nothing worse than a dropped connection.
     */
    @Test
    fun `a download-only group rule still holds uploads when the labels are unreadable`() {
        val set = FilterRuleSet(
            rules = listOf(
                rule("groups-down", RuleField.GROUPS, RuleAction.SYNC, RuleDirection.DOWNLOAD),
                rule("hold-up", RuleField.EMAIL, RuleAction.EXCLUDE, RuleDirection.UPLOAD),
            )
        )
        assertTrue(
            "the guard must test the same condition that suspends the session",
            ContactFilterSession.uploadsUnsafeFor(set)
        )
    }

    /** Without a rule that can hold anything back, a suspension costs nothing and uploads proceed. */
    @Test
    fun `a suspension with no upload EXCLUDE rule does not stall uploads`() {
        val set = FilterRuleSet(
            rules = listOf(
                rule("groups-down", RuleField.GROUPS, RuleAction.SYNC, RuleDirection.DOWNLOAD),
                rule("sync-up", RuleField.EMAIL, RuleAction.SYNC, RuleDirection.UPLOAD),
            )
        )
        assertEquals(false, ContactFilterSession.uploadsUnsafeFor(set))
    }

    /** No group anywhere means no suspension, so there is nothing to wait for. */
    @Test
    fun `rules that never mention a group are unaffected by a label failure`() {
        val set = FilterRuleSet(
            rules = listOf(rule("hold-up", RuleField.EMAIL, RuleAction.EXCLUDE, RuleDirection.UPLOAD))
        )
        assertEquals(false, ContactFilterSession.uploadsUnsafeFor(set))
    }
}
