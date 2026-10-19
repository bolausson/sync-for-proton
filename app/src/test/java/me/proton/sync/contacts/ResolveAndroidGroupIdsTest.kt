package me.proton.sync.contacts

import me.proton.sync.api.models.ContactMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step that decides which contact groups a downloaded contact is written into.
 *
 * Proton puts each contact into groups by label id; the phone has one group row per label, created by
 * syncContactLabels before any contact is written. [ContactsSyncAdapter.resolveAndroidGroupIds] maps one
 * onto the other, and whatever it returns becomes the contact's complete set of GroupMembership rows:
 * the write replaces the old memberships rather than adding to them. So a label it cannot resolve does
 * not stay as it was on the phone; the contact silently leaves that group. That is the documented
 * suspect for "all my groups are suddenly empty" reports, and why an unresolved label is dropped
 * rather than failing the contact.
 *
 * Moved into the companion object so it can be tested here (the function body is unchanged); it was
 * split out of the download loop and had no test of its own.
 */
class ResolveAndroidGroupIdsTest {

    private fun meta(labels: List<String>?) = ContactMeta(
        id = "proton-contact-1", name = "Jane", uid = "uid-1", size = 100,
        createTime = 0L, modifyTime = 0L, labelIds = labels
    )

    private fun resolve(labels: List<String>?, map: Map<String, Long>) =
        ContactsSyncAdapter.resolveAndroidGroupIds(meta(labels), map)

    @Test
    fun everyLabelResolvesToItsGroupRowInLabelOrder() {
        val map = mapOf("family" to 11L, "work" to 12L, "gym" to 13L)
        assertEquals(listOf(13L, 11L), resolve(listOf("gym", "family"), map))
    }

    /** The contact leaves the group whose label the phone does not know. It does not keep the old row. */
    @Test
    fun anUnknownLabelIsDroppedAndTheKnownOnesStay() {
        val map = mapOf("family" to 11L, "work" to 12L)
        assertEquals(listOf(11L, 12L), resolve(listOf("family", "deleted-on-proton", "work"), map))
    }

    /** The "groups suddenly empty" case: no label could be resolved, so no membership is written. */
    @Test
    fun noKnownLabelsGiveNoGroups() {
        assertTrue(resolve(listOf("family", "work"), emptyMap()).isEmpty())
    }

    @Test
    fun aContactWithoutLabelsIsInNoGroup() {
        val map = mapOf("family" to 11L)
        assertTrue(resolve(null, map).isEmpty())
        assertTrue(resolve(emptyList(), map).isEmpty())
    }

    /** Not deduplicated here: a label listed twice by Proton yields its group row twice. */
    @Test
    fun aRepeatedLabelIsPassedOnAsItCame() {
        val map = mapOf("family" to 11L)
        assertEquals(listOf(11L, 11L), resolve(listOf("family", "family"), map))
    }
}
