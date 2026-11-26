package me.proton.sync.contacts.filter

import ezvcard.VCard
import ezvcard.property.Anniversary
import ezvcard.property.Birthday
import ezvcard.property.FormattedName
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Rules on a birthday or an anniversary across 2.2.1, which spells dates with dashes on the phone and in the readers
 * (contacts.ContactDates). Before, every full date this app had uploaded read "19900215" and a date Proton web stored
 * read "1990-02-15", so a user's rule could be written either way; now both spellings match every reading of the same
 * date, on the upload card, a web card and the phone's rows. A positive condition therefore only gains matches, and an
 * EXCLUDE rule made of them keeps holding back what it held back, unless a SYNC rule above it now claims the contact
 * first. NOT_EQUALS and negated conditions lose matches: "Birthday is not 1900-01-01" no longer excludes the contact
 * born that day. Either way such a contact, held back by 2.2.0, goes up as the rule's text says (see the contacts
 * manifest). A date given as a UTC date-time reads as a date where 2.2.0 read none, so every condition on it can start
 * matching, NOT_EQUALS included. Other fields compare as they always did.
 */
class FilterDateSpellingTest {

    private val dateFields = listOf(RuleField.BIRTHDAY, RuleField.ANNIVERSARY)

    private fun exclude(field: RuleField, op: RuleOp, vararg values: String, negate: Boolean = false) = FilterRule(
        id = "r1",
        name = "test",
        action = RuleAction.EXCLUDE,
        direction = RuleDirection.BOTH,
        match = Combinator.ALL,
        conditions = listOf(Condition(field, op, values = values.toList(), negate = negate)),
    )

    private fun verdict(view: ContactView, rule: FilterRule, direction: RuleDirection): RuleAction {
        val set = FilterRuleSet(defaultAction = RuleAction.SYNC, rules = listOf(rule))
        return (FilterEvaluator(set).evaluate(view, direction) as FilterEvaluator.Verdict.Decided).action
    }

    private fun card(build: VCard.() -> Unit) = VCard().apply {
        formattedName = FormattedName("Someone")
        build()
    }

    /** As buildVCardFromAndroid builds it: the date as text without dashes. */
    private fun uploadCard(field: RuleField) = card {
        if (field == RuleField.BIRTHDAY) birthday = Birthday("19000101") else anniversary = Anniversary("19000101")
    }

    /** As Proton web stores it: a real date. */
    private fun webCard(field: RuleField) = card {
        val date = LocalDate.of(1900, 1, 1)
        if (field == RuleField.BIRTHDAY) birthday = Birthday(date) else anniversary = Anniversary(date)
    }

    /** A phone row as LocalContactView reads it since 2.2.1, for the download's dry run. */
    private fun phoneRow(field: RuleField, date: String) = LocalContactView(
        protonId = "a",
        displayName = "Someone",
        modifyTime = 1L,
        groups = emptyList(),
        fields = mapOf(field to listOf(date)),
    )

    private fun views(field: RuleField): List<Pair<String, Pair<ContactView, RuleDirection>>> = listOf(
        "$field upload card" to (VCardContactView(uploadCard(field)) to RuleDirection.UPLOAD),
        "$field web card" to (VCardContactView(webCard(field)) to RuleDirection.DOWNLOAD),
        "$field phone row" to (phoneRow(field, "1900-01-01") to RuleDirection.DOWNLOAD),
    )

    @Test
    fun `a rule written in either spelling matches every reading of the same date`() {
        for (field in dateFields) {
            val rules = listOf(
                exclude(field, RuleOp.EQUALS, "19000101"),
                exclude(field, RuleOp.EQUALS, "1900-01-01"),
                exclude(field, RuleOp.ENDS_WITH, "0101"),
                exclude(field, RuleOp.CONTAINS, "0101"),
                exclude(field, RuleOp.STARTS_WITH, "1900"),
                exclude(field, RuleOp.MATCHES_REGEX, "^[0-9]{8}$"),
                exclude(field, RuleOp.IN_SET, "19000101", "20000101"),
                exclude(field, RuleOp.EXISTS),
            )
            for ((name, viewAndDirection) in views(field)) {
                val (view, direction) = viewAndDirection
                for (rule in rules) {
                    val c = rule.conditions.single()
                    assertEquals("$name: ${c.op} ${c.values}", RuleAction.EXCLUDE, verdict(view, rule, direction))
                }
            }
        }
    }

    @Test
    fun `the same date in either spelling is not 'not equal'`() {
        for (field in dateFields) {
            for ((name, viewAndDirection) in views(field)) {
                val (view, direction) = viewAndDirection
                assertEquals(name, RuleAction.SYNC, verdict(view, exclude(field, RuleOp.NOT_EQUALS, "19000101"), direction))
                assertEquals(name, RuleAction.SYNC, verdict(view, exclude(field, RuleOp.NOT_EQUALS, "1900-01-01"), direction))
                assertEquals(name, RuleAction.SYNC, verdict(view, exclude(field, RuleOp.CONTAINS, "0101", negate = true), direction))
            }
        }
    }

    @Test
    fun `another date still does not match`() {
        for (field in dateFields) {
            for ((name, viewAndDirection) in views(field)) {
                val (view, direction) = viewAndDirection
                assertEquals(name, RuleAction.SYNC, verdict(view, exclude(field, RuleOp.EQUALS, "19000102"), direction))
                assertEquals(name, RuleAction.SYNC, verdict(view, exclude(field, RuleOp.EQUALS, "1900-01-02"), direction))
            }
        }
    }

    @Test
    fun `other fields that hold a date compare only as written`() {
        val note = card { addNote("1900-01-01") }
        val customDate = card { addExtendedProperty("X-ABDATE", "1900-01-01") }
        for ((field, view) in listOf(RuleField.NOTE to note, RuleField.CUSTOM_DATE to customDate)) {
            val cardView = VCardContactView(view)
            assertEquals("$field", RuleAction.SYNC, verdict(cardView, exclude(field, RuleOp.EQUALS, "19000101"), RuleDirection.DOWNLOAD))
            assertEquals("$field", RuleAction.EXCLUDE, verdict(cardView, exclude(field, RuleOp.EQUALS, "1900-01-01"), RuleDirection.DOWNLOAD))
        }
    }
}
