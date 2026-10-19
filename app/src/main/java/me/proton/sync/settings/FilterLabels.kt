package me.proton.sync.settings

import android.content.Context
import androidx.annotation.StringRes
import me.proton.sync.R
import me.proton.sync.contacts.filter.FieldKind
import me.proton.sync.contacts.filter.RuleField
import me.proton.sync.contacts.filter.RuleOp

/**
 * Human names for the fields and tests a rule is built from.
 *
 * These used to be derived from the enum constant names, which meant the rule editor sat in
 * English inside an app translated into twenty-four languages, and showed things like
 * "Adr postal code" to everybody. The mapping is exhaustive on purpose: a `when` with no `else`
 * makes the compiler point at this file the moment a new field is added, which is the only
 * reliable way to stop the next one from silently falling back to a constant name again.
 *
 * Kept in the settings package rather than on the enums so the filter model stays free of any
 * dependency on resources.
 */
internal object FilterLabels {

    fun of(context: Context, field: RuleField): String = context.getString(res(field))

    /**
     * @param field the field the test will run against, because three of the operators mean
     *   different things depending on it: on a list they count the values, on a field that is
     *   itself a number they compare the number. Labelling both "count is at least" told the user
     *   the card size was being counted.
     */
    fun of(context: Context, op: RuleOp, field: RuleField? = null): String =
        context.getString(res(op, field))

    @StringRes
    private fun res(field: RuleField): Int = when (field) {
        RuleField.PROTON_ID -> R.string.filter_field_proton_id
        RuleField.DISPLAY_NAME -> R.string.filter_field_display_name
        RuleField.UID -> R.string.filter_field_uid
        RuleField.SIZE_BYTES -> R.string.filter_field_size_bytes
        RuleField.CREATE_TIME -> R.string.filter_field_create_time
        RuleField.MODIFY_TIME -> R.string.filter_field_modify_time
        RuleField.GROUPS -> R.string.filter_field_groups
        RuleField.GROUP_COUNT -> R.string.filter_field_group_count
        RuleField.FORMATTED_NAME -> R.string.filter_field_formatted_name
        RuleField.GIVEN_NAME -> R.string.filter_field_given_name
        RuleField.FAMILY_NAME -> R.string.filter_field_family_name
        RuleField.ADDITIONAL_NAMES -> R.string.filter_field_additional_names
        RuleField.NAME_PREFIX -> R.string.filter_field_name_prefix
        RuleField.NAME_SUFFIX -> R.string.filter_field_name_suffix
        RuleField.PHONETIC_FIRST -> R.string.filter_field_phonetic_first
        RuleField.PHONETIC_MIDDLE -> R.string.filter_field_phonetic_middle
        RuleField.PHONETIC_LAST -> R.string.filter_field_phonetic_last
        RuleField.NICKNAME -> R.string.filter_field_nickname
        RuleField.EMAIL -> R.string.filter_field_email
        RuleField.EMAIL_TYPE -> R.string.filter_field_email_type
        RuleField.PHONE -> R.string.filter_field_phone
        RuleField.PHONE_TYPE -> R.string.filter_field_phone_type
        RuleField.ADDRESS_STREET -> R.string.filter_field_address_street
        RuleField.ADDRESS_LOCALITY -> R.string.filter_field_address_locality
        RuleField.ADDRESS_REGION -> R.string.filter_field_address_region
        RuleField.ADDRESS_POSTAL_CODE -> R.string.filter_field_address_postal_code
        RuleField.ADDRESS_COUNTRY -> R.string.filter_field_address_country
        RuleField.ADDRESS_TYPE -> R.string.filter_field_address_type
        RuleField.ORGANIZATION -> R.string.filter_field_organization
        RuleField.TITLE -> R.string.filter_field_title
        RuleField.ROLE -> R.string.filter_field_role
        RuleField.NOTE -> R.string.filter_field_note
        RuleField.URL -> R.string.filter_field_url
        RuleField.RELATED -> R.string.filter_field_related
        RuleField.BIRTHDAY -> R.string.filter_field_birthday
        RuleField.ANNIVERSARY -> R.string.filter_field_anniversary
        RuleField.CUSTOM_DATE -> R.string.filter_field_custom_date
        RuleField.SIP -> R.string.filter_field_sip
        RuleField.PHOTO -> R.string.filter_field_photo
        RuleField.POPULATED_FIELDS -> R.string.filter_field_populated_fields
        RuleField.STRUCTURED_NAME -> R.string.filter_field_structured_name
        RuleField.OTHER_CONTENT -> R.string.filter_field_other_content
    }

    @StringRes
    private fun res(op: RuleOp, field: RuleField? = null): Int {
        if (field?.kind == FieldKind.NUMBER) {
            when (op) {
                RuleOp.COUNT_EQ, RuleOp.EQUALS -> return R.string.filter_op_num_eq
                RuleOp.COUNT_GTE -> return R.string.filter_op_num_gte
                RuleOp.COUNT_LTE -> return R.string.filter_op_num_lte
                else -> Unit
            }
        }
        return plain(op)
    }

    @StringRes
    private fun plain(op: RuleOp): Int = when (op) {
        RuleOp.EXISTS -> R.string.filter_op_exists
        RuleOp.EQUALS -> R.string.filter_op_equals
        RuleOp.NOT_EQUALS -> R.string.filter_op_not_equals
        RuleOp.CONTAINS -> R.string.filter_op_contains
        RuleOp.STARTS_WITH -> R.string.filter_op_starts_with
        RuleOp.ENDS_WITH -> R.string.filter_op_ends_with
        RuleOp.MATCHES_REGEX -> R.string.filter_op_matches_regex
        RuleOp.IN_SET -> R.string.filter_op_in_set
        RuleOp.CONTAINS_ALL -> R.string.filter_op_contains_all
        RuleOp.COUNT_EQ -> R.string.filter_op_count_eq
        RuleOp.COUNT_GTE -> R.string.filter_op_count_gte
        RuleOp.COUNT_LTE -> R.string.filter_op_count_lte
        RuleOp.ONLY -> R.string.filter_op_only
        RuleOp.EXACTLY -> R.string.filter_op_exactly
    }
}
