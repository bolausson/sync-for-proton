package me.proton.sync.contacts.filter

/**
 * The filter verdict cached against a contact, stored in `contact_sync.sync_tag`.
 *
 * That column is declared on the entity and was read and written nowhere, so this needs no Room
 * migration. It carries two things a null `android_raw_contact_id` could not: *why* a contact is in
 * its current state, and *which revision of the rules* decided that. Without the revision a rules
 * change would re-evaluate nothing, because the adapter skips contacts whose modify time has not
 * moved, and the new rules would silently never take effect.
 *
 * Format: `f|<verdict>|<revision>|<inputsHash>`, for example `f|X|7|a3f19c`.
 */
object FilterTag {

    private const val PREFIX = "f"
    private const val VERDICT_EXCLUDE = "X"
    private const val VERDICT_SYNC = "S"

    fun encode(action: RuleAction, revision: Int, inputsHash: String): String =
        listOf(
            PREFIX,
            if (action == RuleAction.EXCLUDE) VERDICT_EXCLUDE else VERDICT_SYNC,
            revision.toString(),
            inputsHash,
        ).joinToString("|")

    /** The cached verdict, or null if this contact carries no filter tag. */
    fun verdictOf(tag: String?): RuleAction? = when (parts(tag)?.getOrNull(1)) {
        VERDICT_EXCLUDE -> RuleAction.EXCLUDE
        VERDICT_SYNC -> RuleAction.SYNC
        else -> null
    }

    fun revisionOf(tag: String?): Int? = parts(tag)?.getOrNull(2)?.toIntOrNull()

    fun inputsHashOf(tag: String?): String? = parts(tag)?.getOrNull(3)

    /**
     * True when a cached verdict can no longer be trusted.
     *
     * Either the rules changed, or the header inputs the verdict was based on did.
     *
     * The second half matters more than it looks. Proton assigns groups to contact *emails* rather
     * than to contacts, so adding somebody to a group may not move the contact's ModifyTime at all.
     * A group rule would then never re-evaluate, and "I put them in a group on the web and they
     * still do not sync" would be the bug report. Hashing the header inputs catches that, and costs
     * nothing because the headers are fetched on every sync regardless.
     */
    fun isStale(tag: String?, currentRevision: Int, currentInputsHash: String): Boolean {
        val parts = parts(tag) ?: return true
        val revision = parts.getOrNull(2)?.toIntOrNull() ?: return true
        val hash = parts.getOrNull(3) ?: return true
        return revision != currentRevision || hash != currentInputsHash
    }

    /**
     * Fingerprint of everything a header-phase verdict can depend on.
     *
     * Label ids are sorted so a reordering of the same memberships is not mistaken for a change.
     */
    fun inputsHash(labelIds: List<String>, displayName: String?, sizeBytes: Long?): String {
        val material = buildString {
            append(labelIds.sorted().joinToString(","))
            append('|')
            append(displayName.orEmpty())
            append('|')
            append(sizeBytes ?: -1L)
        }
        return Integer.toHexString(material.hashCode())
    }

    private fun parts(tag: String?): List<String>? {
        if (tag.isNullOrEmpty()) return null
        val parts = tag.split('|')
        if (parts.size < 4 || parts[0] != PREFIX) return null
        return parts
    }
}
