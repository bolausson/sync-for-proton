package me.proton.sync.contacts.filter

import ezvcard.VCard

/**
 * A contact whose card has been decrypted, so every syncable field is knowable.
 *
 * Used in two places with slightly different inputs: on download, wrapping the freshly decrypted
 * card, with group names supplied from the contact header; and on upload, wrapping the card built
 * from Android, where group names come from the card's own CATEGORIES. The evaluator cannot tell
 * the difference, which is what lets one rule cover both directions.
 *
 * @param header the header view, when one exists. Download has it; upload does not, so header-only
 *   fields such as size and timestamps answer null there and any rule touching them is simply
 *   never true on the upload path.
 */
class VCardContactView(
    private val vCard: VCard,
    private val header: HeaderContactView? = null,
    /** Group names from the header on download. Null means "read CATEGORIES from the card". */
    private val groupNamesOverride: List<String>? = null,
) : ContactView {

    private val groups: List<String>
        get() = groupNamesOverride
            ?: vCard.categories?.values.orEmpty().filter { it.isNotBlank() }

    override fun values(field: RuleField): List<String>? {
        // Header-only fields are still header-only; the card does not carry them.
        if (field.availability == FieldAvailability.HEADER && field != RuleField.GROUPS) {
            return header?.values(field)
        }
        val name = vCard.structuredName
        return when (field) {
            RuleField.GROUPS -> groups

            RuleField.FORMATTED_NAME -> nonBlank(vCard.formattedName?.value)
            RuleField.GIVEN_NAME -> nonBlank(name?.given)
            RuleField.FAMILY_NAME -> nonBlank(name?.family)
            RuleField.ADDITIONAL_NAMES -> cleaned(name?.additionalNames)
            RuleField.NAME_PREFIX -> cleaned(name?.prefixes)
            RuleField.NAME_SUFFIX -> cleaned(name?.suffixes)
            RuleField.STRUCTURED_NAME -> cleaned(
                listOfNotNull(name?.given, name?.family) +
                    name?.additionalNames.orEmpty() + name?.prefixes.orEmpty() + name?.suffixes.orEmpty()
            )

            RuleField.PHONETIC_FIRST -> nonBlank(ext("X-PHONETIC-FIRST-NAME"))
            RuleField.PHONETIC_MIDDLE -> nonBlank(ext("X-PHONETIC-MIDDLE-NAME"))
            RuleField.PHONETIC_LAST -> nonBlank(ext("X-PHONETIC-LAST-NAME"))
            RuleField.NICKNAME -> cleaned(vCard.nickname?.values)

            RuleField.EMAIL -> cleaned(vCard.emails.map { it.value })
            RuleField.EMAIL_TYPE -> cleaned(vCard.emails.flatMap { e -> e.types.map { it.value } })
            RuleField.PHONE -> cleaned(vCard.telephoneNumbers.map { it.text })
            RuleField.PHONE_TYPE -> cleaned(vCard.telephoneNumbers.flatMap { p -> p.types.map { it.value } })

            RuleField.ADDRESS_STREET -> cleaned(vCard.addresses.map { it.streetAddress })
            RuleField.ADDRESS_LOCALITY -> cleaned(vCard.addresses.map { it.locality })
            RuleField.ADDRESS_REGION -> cleaned(vCard.addresses.map { it.region })
            RuleField.ADDRESS_POSTAL_CODE -> cleaned(vCard.addresses.map { it.postalCode })
            RuleField.ADDRESS_COUNTRY -> cleaned(vCard.addresses.map { it.country })
            RuleField.ADDRESS_TYPE -> cleaned(vCard.addresses.flatMap { a -> a.types.map { it.value } })

            RuleField.ORGANIZATION -> cleaned(vCard.organization?.values)
            RuleField.TITLE -> cleaned(vCard.titles.map { it.value })
            RuleField.ROLE -> cleaned(vCard.roles.map { it.value })

            RuleField.NOTE -> cleaned(vCard.notes.map { it.value })
            RuleField.URL -> cleaned(vCard.urls.map { it.value })
            RuleField.RELATED -> cleaned(vCard.relations.map { it.text ?: it.uri })
            RuleField.BIRTHDAY -> nonBlank(dateText(vCard.birthday))
            RuleField.ANNIVERSARY -> nonBlank(dateText(vCard.anniversary))
            RuleField.CUSTOM_DATE -> cleaned(vCard.getExtendedProperties("X-ABDATE").map { it.value })
            RuleField.SIP -> nonBlank(ext("X-SIP"))
            // Presence is what matters for a photo; the bytes are not something a rule can match.
            RuleField.PHOTO -> if (hasPhoto()) listOf("photo") else emptyList()

            // Answerable as a field in its own right, not only as a member of the populated set.
            // Without this it fell through to null, and since UNKNOWN stops the whole walk, one
            // rule mentioning it left every contact undecided and every rule below it unreachable.
            RuleField.OTHER_CONTENT ->
                if (hasUnmappedContent()) listOf("other") else emptyList()

            else -> null
        }
    }

    override fun number(field: RuleField): Long? = when (field) {
        RuleField.GROUP_COUNT -> groups.size.toLong()
        else -> if (field.availability == FieldAvailability.HEADER) header?.number(field) else null
    }

    /**
     * Fields carrying real content.
     *
     * **Blank-aware, not null-aware, and that distinction is the whole feature.**
     * `buildVCardFromAndroid` adds empty strings for additional names, prefixes and suffixes
     * unconditionally, and invents a formatted name of "Unknown" when a contact has none. A view
     * that merely checked for non-null would report a structured name on practically every
     * uploaded contact, and "only has an email" would then match nothing at all.
     *
     * Structural and crypto-preference properties (VERSION, PRODID, UID, REV, KEY, X-PM-*) are
     * absent by construction: this walks the fields the app maps, and those are not among them.
     */
    override fun populated(): Set<RuleField> {
        val result = linkedSetOf<RuleField>()
        for (field in CONTENT_FIELDS) {
            val v = values(field)
            if (!v.isNullOrEmpty()) result += field
        }
        if (hasUnmappedContent()) result += RuleField.OTHER_CONTENT
        return result
    }

    /**
     * Whether the card holds something this app has no field for.
     *
     * Written as a deny list rather than an allow list, deliberately. Anything unrecognised counts
     * as content, so a property nobody has thought about yet makes an "only these fields" rule fail
     * and the contact is kept. The other way round, an unrecognised property would be invisible and
     * the rule would remove somebody's real contact; that is the direction this exists to prevent.
     *
     * Proton's editor can store instant messaging handles, and the sync maps none of them, so a
     * contact with a name, an address and three IM handles used to report exactly the same shape as
     * a stub Proton had invented from an email.
     */
    private fun hasUnmappedContent(): Boolean = vCard.properties.any { property ->
        when (property) {
            is ezvcard.property.RawProperty -> {
                val name = property.propertyName?.uppercase().orEmpty()
                name !in KNOWN_EXTENDED && !name.startsWith("X-PM-") &&
                    !property.value.isNullOrBlank()
            }
            else -> property.javaClass !in ACCOUNTED_FOR
        }
    }

    private fun ext(name: String): String? = vCard.getExtendedProperty(name)?.value

    private fun hasPhoto(): Boolean =
        vCard.photos.any { (it.data?.isNotEmpty() == true) || !it.url.isNullOrBlank() }

    /**
     * Read through the same parser the download mapper writes with.
     *
     * A two-branch `text ?: date` read misses RFC 6350 truncated dates (`BDAY:--0215`, what iOS
     * and DAVx5 write for a birthday with no year), which ez-vcard puts in its partialDate slot
     * with text and date both null. Those read as *knowably absent* here while `ContactsMapper`
     * wrote a real Event row from the same card, so a rule on BIRTHDAY judged the contact one way
     * and the row on the phone said the opposite. It also spelled dates differently: `date`
     * stringifies Apple's omit-year sentinel as "1604-02-15" where the mapper writes "--02-15",
     * which quietly broke every CONTAINS, STARTS_WITH and regex rule on a birthday.
     */
    private fun dateText(prop: ezvcard.property.DateOrTimeProperty?): String? =
        me.proton.sync.contacts.ContactsMapper.parseDateProperty(prop)?.takeIf { it.isNotBlank() }

    private fun nonBlank(value: String?): List<String> =
        listOfNotNull(value?.takeIf { it.isNotBlank() })

    private fun cleaned(values: Collection<String?>?): List<String> =
        values.orEmpty().mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }

    companion object {
        /**
         * Property types already reflected somewhere in [CONTENT_FIELDS], plus the structural and
         * crypto ones that are not content at all.
         */
        private val ACCOUNTED_FOR: Set<Class<*>> = setOf(
            ezvcard.property.FormattedName::class.java,
            ezvcard.property.StructuredName::class.java,
            ezvcard.property.Nickname::class.java,
            ezvcard.property.Email::class.java,
            ezvcard.property.Telephone::class.java,
            ezvcard.property.Address::class.java,
            ezvcard.property.Organization::class.java,
            ezvcard.property.Title::class.java,
            ezvcard.property.Role::class.java,
            ezvcard.property.Note::class.java,
            ezvcard.property.Url::class.java,
            ezvcard.property.Related::class.java,
            ezvcard.property.Birthday::class.java,
            ezvcard.property.Anniversary::class.java,
            ezvcard.property.Photo::class.java,
            ezvcard.property.Categories::class.java,
            // Structural: identity and bookkeeping, never something the user typed.
            ezvcard.property.Uid::class.java,
            ezvcard.property.Revision::class.java,
            ezvcard.property.ProductId::class.java,
            ezvcard.property.Key::class.java,
            ezvcard.property.Kind::class.java,
            ezvcard.property.Source::class.java,
        )

        /** Extended properties the sync does map, so they are not "something unknown". */
        private val KNOWN_EXTENDED = setOf(
            "X-PHONETIC-FIRST-NAME", "X-PHONETIC-MIDDLE-NAME", "X-PHONETIC-LAST-NAME",
            "X-SIP", "X-ABDATE", "X-ANDROID-CUSTOM",
        )

        /** The five parts Android and vCard split a person's name into. */
        val NAME_PARTS: List<RuleField> = listOf(
            RuleField.GIVEN_NAME,
            RuleField.FAMILY_NAME,
            RuleField.ADDITIONAL_NAMES,
            RuleField.NAME_PREFIX,
            RuleField.NAME_SUFFIX,
        )

        /**
         * The fields [populated] considers, in a stable order.
         *
         * The name is reported by its parts rather than as one lump, so a rule can say "only a
         * first name" and mean it. Writing "only a name and an email" without ticking all five
         * parts still works, because [RuleField.STRUCTURED_NAME] stands for the whole group and
         * the evaluator expands it; see `FilterEvaluator.expandNameGroup`. The group is offered
         * alongside the parts in the picker, so neither the coarse nor the fine rule is out of
         * reach.
         */
        val CONTENT_FIELDS: List<RuleField> = listOf(
            RuleField.FORMATTED_NAME,
        ) + NAME_PARTS + listOf(
            RuleField.PHONETIC_FIRST,
            RuleField.PHONETIC_MIDDLE,
            RuleField.PHONETIC_LAST,
            RuleField.NICKNAME,
            RuleField.EMAIL,
            RuleField.PHONE,
            RuleField.ADDRESS_STREET,
            RuleField.ADDRESS_LOCALITY,
            RuleField.ADDRESS_REGION,
            RuleField.ADDRESS_POSTAL_CODE,
            RuleField.ADDRESS_COUNTRY,
            RuleField.ORGANIZATION,
            RuleField.TITLE,
            RuleField.ROLE,
            RuleField.NOTE,
            RuleField.URL,
            RuleField.RELATED,
            RuleField.BIRTHDAY,
            RuleField.ANNIVERSARY,
            RuleField.CUSTOM_DATE,
            RuleField.SIP,
            RuleField.PHOTO,
            RuleField.GROUPS,
        )

        /**
         * What the "fields with content" picker offers.
         *
         * The same set the evaluation walks, plus the whole-name shorthand. Ticking that is the
         * short way to say "any part of their name"; ticking the parts is the precise way.
         */
        val PICKER_FIELDS: List<RuleField> =
            listOf(RuleField.FORMATTED_NAME, RuleField.STRUCTURED_NAME) +
                CONTENT_FIELDS.filter { it != RuleField.FORMATTED_NAME } +
                RuleField.OTHER_CONTENT
    }
}
