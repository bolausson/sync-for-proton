package me.proton.sync.contacts.filter

import com.google.gson.annotations.SerializedName

/**
 * The persisted shape of a per-account contact filter.
 *
 * Deliberately flat and gson-friendly rather than a sealed condition tree: gson cannot
 * deserialise a sealed hierarchy without a `RuntimeTypeAdapterFactory`, and this project has no
 * kotlinx.serialization. One [Condition] class with a [RuleField] discriminator costs a little
 * expressiveness and saves a whole serialisation layer.
 *
 * **Every field and every enum constant carries [SerializedName] on purpose.** Release builds run
 * R8 with `isMinifyEnabled = true`, and `proguard-rules.pro` keeps only annotated members. An
 * unannotated field would be renamed, and every user's rules would silently vanish on upgrade.
 */
data class FilterRuleSet(
    /** Bumped only if the persisted shape changes incompatibly. Readers ignore newer minor data. */
    @SerializedName("v") val schemaVersion: Int = SCHEMA_VERSION,

    /**
     * Incremented once per editing session. Stamped into each contact's `sync_tag` so a rules
     * change invalidates every cached verdict exactly once; see `FilterTag`.
     */
    @SerializedName("rev") val revision: Int = 0,

    /**
     * What happens to a contact no rule matches.
     *
     * Not optional: "first match wins" is undefined without a fall-through, and rules of the form
     * "sync only people in group X" are *only* expressible as a SYNC rule over an EXCLUDE default.
     * Defaults to [RuleAction.SYNC] so an empty rule set behaves exactly as the app did before
     * filtering existed.
     */
    @SerializedName("def") val defaultAction: RuleAction = RuleAction.SYNC,

    /** Evaluated top to bottom; the first rule that matches decides. */
    @SerializedName("rules") val rules: List<FilterRule> = emptyList(),
) {
    /** True when this rule set would change nothing, so the sync path can skip filtering entirely. */
    val isInactive: Boolean
        get() = defaultAction == RuleAction.SYNC && rules.none { it.enabled }

    companion object {
        const val SCHEMA_VERSION = 1

        /** The "sync everything" set. Also what a malformed stored value falls back to. */
        val EMPTY = FilterRuleSet()
    }
}

data class FilterRule(
    /** Stable UUID. Survives reordering and renaming, unlike a position or a name. */
    @SerializedName("id") val id: String,
    @SerializedName("n") val name: String,
    @SerializedName("on") val enabled: Boolean = true,
    @SerializedName("act") val action: RuleAction,
    @SerializedName("dir") val direction: RuleDirection,
    /** Whether every condition must hold, or just one. */
    @SerializedName("m") val match: Combinator = Combinator.ALL,
    @SerializedName("c") val conditions: List<Condition> = emptyList(),
) {
    fun appliesTo(direction: RuleDirection): Boolean =
        this.direction == RuleDirection.BOTH || this.direction == direction
}

data class Condition(
    @SerializedName("f") val field: RuleField,
    @SerializedName("o") val op: RuleOp,
    /** Inverts the result of this condition alone, after the operator has been applied. */
    @SerializedName("not") val negate: Boolean = false,
    /** Operand(s) for text operators, or the field names for [RuleOp.ONLY] / [RuleOp.EXACTLY]. */
    @SerializedName("val") val values: List<String> = emptyList(),
    /**
     * Proton label ids for group conditions, held alongside the names in [values].
     *
     * Both are stored so a group renamed on Proton does not orphan the rule: the download path
     * matches on id and falls back to name, while the upload path has only names to work with
     * (vCard CATEGORIES carries no ids).
     */
    @SerializedName("ids") val refIds: List<String> = emptyList(),
    @SerializedName("num") val number: Long? = null,
)

enum class RuleAction {
    @SerializedName("sync") SYNC,
    @SerializedName("exclude") EXCLUDE,
}

enum class RuleDirection {
    @SerializedName("download") DOWNLOAD,
    @SerializedName("upload") UPLOAD,
    @SerializedName("both") BOTH,
}

enum class Combinator {
    @SerializedName("all") ALL,
    @SerializedName("any") ANY,
}

/** What kind of operand a field takes, which decides the operators the editor offers. */
enum class FieldKind { TEXT, TEXT_LIST, NUMBER, FIELD_SET }

/**
 * Whether a field can be read from the contact *header* alone.
 *
 * The download path lists every contact's header (id, name, uid, size, timestamps, label ids)
 * before fetching any bodies, so a rule built only from [FieldAvailability.HEADER] fields can be
 * decided without fetching and decrypting the contact. That is the difference between judging
 * 400 contacts for free and running GopenPGP over all of them.
 */
enum class FieldAvailability { HEADER, VCARD }

/**
 * Every field a rule can test.
 *
 * The set mirrors what the app actually syncs (see `ContactsMapper.addDataOps` and
 * `ContactsSyncAdapter.buildVCardFromAndroid`), plus the header-only metadata and one synthetic
 * field, [POPULATED_FIELDS].
 */
enum class RuleField(
    val availability: FieldAvailability,
    val kind: FieldKind,
) {
    // --- Header-only: free to evaluate, no fetch and no decryption ---
    @SerializedName("proton_id") PROTON_ID(FieldAvailability.HEADER, FieldKind.TEXT),

    /**
     * `ContactMeta.name`, which is Proton's copy of the formatted name. Distinct from
     * [FORMATTED_NAME]: this one is readable from the header, that one needs the decrypted card.
     */
    @SerializedName("display_name") DISPLAY_NAME(FieldAvailability.HEADER, FieldKind.TEXT),
    @SerializedName("uid") UID(FieldAvailability.HEADER, FieldKind.TEXT),
    @SerializedName("size") SIZE_BYTES(FieldAvailability.HEADER, FieldKind.NUMBER),
    @SerializedName("create_time") CREATE_TIME(FieldAvailability.HEADER, FieldKind.NUMBER),
    @SerializedName("modify_time") MODIFY_TIME(FieldAvailability.HEADER, FieldKind.NUMBER),

    /** Group / label names. Header-derived on download, vCard CATEGORIES on upload. */
    @SerializedName("groups") GROUPS(FieldAvailability.HEADER, FieldKind.TEXT_LIST),
    @SerializedName("group_count") GROUP_COUNT(FieldAvailability.HEADER, FieldKind.NUMBER),

    // --- Name ---
    @SerializedName("fn") FORMATTED_NAME(FieldAvailability.VCARD, FieldKind.TEXT),
    @SerializedName("given") GIVEN_NAME(FieldAvailability.VCARD, FieldKind.TEXT),
    @SerializedName("family") FAMILY_NAME(FieldAvailability.VCARD, FieldKind.TEXT),
    @SerializedName("additional") ADDITIONAL_NAMES(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("prefix") NAME_PREFIX(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("suffix") NAME_SUFFIX(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("phon_first") PHONETIC_FIRST(FieldAvailability.VCARD, FieldKind.TEXT),
    @SerializedName("phon_middle") PHONETIC_MIDDLE(FieldAvailability.VCARD, FieldKind.TEXT),
    @SerializedName("phon_last") PHONETIC_LAST(FieldAvailability.VCARD, FieldKind.TEXT),
    @SerializedName("nickname") NICKNAME(FieldAvailability.VCARD, FieldKind.TEXT),

    // --- Contact points ---
    @SerializedName("email") EMAIL(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("email_type") EMAIL_TYPE(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("phone") PHONE(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("phone_type") PHONE_TYPE(FieldAvailability.VCARD, FieldKind.TEXT_LIST),

    // --- Address ---
    @SerializedName("adr_street") ADDRESS_STREET(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("adr_city") ADDRESS_LOCALITY(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("adr_region") ADDRESS_REGION(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("adr_post") ADDRESS_POSTAL_CODE(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("adr_country") ADDRESS_COUNTRY(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("adr_type") ADDRESS_TYPE(FieldAvailability.VCARD, FieldKind.TEXT_LIST),

    // --- Work ---
    @SerializedName("org") ORGANIZATION(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("title") TITLE(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("role") ROLE(FieldAvailability.VCARD, FieldKind.TEXT_LIST),

    // --- Everything else we map ---
    @SerializedName("note") NOTE(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("url") URL(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("related") RELATED(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("birthday") BIRTHDAY(FieldAvailability.VCARD, FieldKind.TEXT),
    @SerializedName("anniversary") ANNIVERSARY(FieldAvailability.VCARD, FieldKind.TEXT),
    @SerializedName("custom_date") CUSTOM_DATE(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    @SerializedName("sip") SIP(FieldAvailability.VCARD, FieldKind.TEXT),
    @SerializedName("photo") PHOTO(FieldAvailability.VCARD, FieldKind.TEXT_LIST),

    /**
     * Synthetic: the set of fields this contact actually has content in.
     *
     * The whole point of the feature. "Exclude contacts that only have an email and a name" is
     * `POPULATED_FIELDS ONLY [FORMATTED_NAME, STRUCTURED_NAME, EMAIL]`, which is how Proton's
     * auto-created contacts are recognised, since Proton marks them in no other way.
     */
    @SerializedName("populated") POPULATED_FIELDS(FieldAvailability.VCARD, FieldKind.FIELD_SET),

    /**
     * Synthetic grouping of the structured-name components, used by [POPULATED_FIELDS] so a rule
     * does not have to enumerate given/family/additional/prefix/suffix separately.
     */
    @SerializedName("structured_name") STRUCTURED_NAME(FieldAvailability.VCARD, FieldKind.TEXT_LIST),

    /**
     * Synthetic: the card carries something this app does not map.
     *
     * Proton's own editor can store properties the sync has no home for on Android, instant
     * messaging handles among them. Without this, such a contact reported only the fields we do
     * know about, so a hand-filled contact with three IM handles looked exactly like a stub Proton
     * had invented, and the auto-created rule took it off the phone. Counting the unknown as
     * content makes the rule fail closed instead.
     */
    @SerializedName("other_content") OTHER_CONTENT(FieldAvailability.VCARD, FieldKind.TEXT_LIST),
    ;

    companion object {
        /**
         * Tolerant lookup used for the operands of [RuleOp.ONLY] / [RuleOp.EXACTLY], which are
         * field names rather than values.
         *
         * Matches on the constant name rather than reading [SerializedName] reflectively: R8 is
         * free to rewrite reflective field lookups, and a release build that silently failed to
         * resolve a field name here would turn every "only has these fields" rule into a no-op.
         * `proguard-rules.pro` keeps this package explicitly. Unknown names return null so the
         * reader can drop a stale condition instead of throwing mid-sync.
         */
        fun fromName(name: String?): RuleField? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

enum class RuleOp(val appliesTo: Set<FieldKind>) {
    /** Field has at least one non-blank value. The operand is ignored. */
    @SerializedName("exists") EXISTS(setOf(FieldKind.TEXT, FieldKind.TEXT_LIST, FieldKind.NUMBER)),

    @SerializedName("eq") EQUALS(setOf(FieldKind.TEXT, FieldKind.TEXT_LIST, FieldKind.NUMBER)),
    @SerializedName("neq") NOT_EQUALS(setOf(FieldKind.TEXT, FieldKind.TEXT_LIST, FieldKind.NUMBER)),
    @SerializedName("contains") CONTAINS(setOf(FieldKind.TEXT, FieldKind.TEXT_LIST)),
    @SerializedName("starts") STARTS_WITH(setOf(FieldKind.TEXT, FieldKind.TEXT_LIST)),
    @SerializedName("ends") ENDS_WITH(setOf(FieldKind.TEXT, FieldKind.TEXT_LIST)),

    /**
     * Java regex against any value of the field.
     *
     * Compiled once per evaluation run and wrapped: a pattern the user typed must never throw
     * inside the sync loop, and an invalid one evaluates to false rather than aborting the sync.
     */
    @SerializedName("regex") MATCHES_REGEX(setOf(FieldKind.TEXT, FieldKind.TEXT_LIST)),

    /** Any of the operands is present in the field's values. "In group G1 or G2 or G3". */
    @SerializedName("in_set") IN_SET(setOf(FieldKind.TEXT, FieldKind.TEXT_LIST)),

    /** All of the operands are present. "In group G1 and G2 and G3". */
    @SerializedName("all_of") CONTAINS_ALL(setOf(FieldKind.TEXT_LIST)),

    @SerializedName("count_eq") COUNT_EQ(setOf(FieldKind.TEXT_LIST, FieldKind.NUMBER)),
    @SerializedName("count_gte") COUNT_GTE(setOf(FieldKind.TEXT_LIST, FieldKind.NUMBER)),
    @SerializedName("count_lte") COUNT_LTE(setOf(FieldKind.TEXT_LIST, FieldKind.NUMBER)),

    /** Populated fields are a *subset* of the operands. "Only has an email and a name". */
    @SerializedName("only") ONLY(setOf(FieldKind.FIELD_SET)),

    /** Populated fields are exactly the operands, no more and no fewer. */
    @SerializedName("exactly") EXACTLY(setOf(FieldKind.FIELD_SET)),
    ;

    fun isValidFor(field: RuleField): Boolean = field.kind in appliesTo
}
