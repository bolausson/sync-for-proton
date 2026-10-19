package me.proton.sync.contacts.filter

/**
 * Three-valued logic. [UNKNOWN] means "this contact has not been decrypted yet", not "false".
 *
 * Keeping unknown distinct from false is what lets the download path decide most contacts from
 * the header alone: a rule can be ruled *out* while half its conditions are still unknown, and
 * only a rule that might still match forces a fetch.
 */
enum class Tri {
    TRUE, FALSE, UNKNOWN;

    fun negate(): Tri = when (this) {
        TRUE -> FALSE
        FALSE -> TRUE
        UNKNOWN -> UNKNOWN
    }

    companion object {
        fun of(value: Boolean): Tri = if (value) TRUE else FALSE
    }
}

/**
 * Whatever is currently knowable about one contact.
 *
 * The evaluator never asks where a value came from, which is what allows one rule engine to serve
 * three different contexts: a contact header during download, a fully decrypted card, and a vCard
 * built from Android on the way up.
 *
 * Returning `null` means **not knowable here**, and becomes [Tri.UNKNOWN]. Returning an empty list
 * means **knowably absent**, and is a definite answer. Conflating the two would either force a
 * pointless decryption of every contact or, worse, silently treat every unread field as empty and
 * match rules that should not have matched.
 */
interface ContactView {

    /** Values of a text or list field, empty if knowably absent, null if not knowable here. */
    fun values(field: RuleField): List<String>?

    /** Numeric fields (sizes, timestamps, counts). Null if not knowable here. */
    fun number(field: RuleField): Long?

    /** Fields this contact actually has content in, or null if that needs the decrypted card. */
    fun populated(): Set<RuleField>?

    /**
     * Proton label ids, when they are available.
     *
     * Group conditions prefer these over names so a group renamed on Proton does not orphan a
     * rule. Only the download path has them: a vCard built from Android carries CATEGORIES, which
     * are names with no ids attached, so upload matching falls back to names.
     */
    fun groupIds(): List<String>? = null
}

/**
 * What can be read from a contact list entry, before anything is fetched or decrypted.
 *
 * Backed by `ContactMeta` plus the label-id to label-name map the sync already builds. Everything
 * else answers null, so a rule touching any card field defers to the full view.
 */
class HeaderContactView(
    private val protonId: String,
    private val displayName: String?,
    private val uid: String?,
    private val sizeBytes: Long?,
    private val createTime: Long?,
    private val modifyTime: Long?,
    /** Resolved group names. Empty means "in no groups", which is a definite answer. */
    private val groupNames: List<String>,
    /** Raw Proton label ids, so group conditions can match on id and survive a rename. */
    private val labelIds: List<String>,
) : ContactView {

    override fun values(field: RuleField): List<String>? = when (field) {
        RuleField.PROTON_ID -> listOf(protonId)
        RuleField.DISPLAY_NAME -> listOfNotNull(displayName?.takeIf { it.isNotBlank() })
        RuleField.UID -> listOfNotNull(uid?.takeIf { it.isNotBlank() })
        RuleField.GROUPS -> groupNames
        else -> null
    }

    override fun number(field: RuleField): Long? = when (field) {
        RuleField.SIZE_BYTES -> sizeBytes
        RuleField.CREATE_TIME -> createTime
        RuleField.MODIFY_TIME -> modifyTime
        RuleField.GROUP_COUNT -> groupNames.size.toLong()
        else -> null
    }

    /** Never knowable from a header: which card fields carry content requires the card. */
    override fun populated(): Set<RuleField>? = null

    override fun groupIds(): List<String> = labelIds
}
