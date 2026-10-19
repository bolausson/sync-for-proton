/**
 * Proton Contacts API data classes.
 *
 * Models for listing, creating, updating, and deleting contacts via the Proton REST API.
 * Contact data is stored in vCard "cards" with different encryption levels:
 * - Type 0: Cleartext (no encryption, no signature)
 * - Type 1: Encrypted only
 * - Type 2: Signed only (cleartext with detached PGP signature)
 * - Type 3: Encrypted and signed (PGP-encrypted vCard with signature)
 */
package me.proton.sync.api.models

import com.google.gson.annotations.SerializedName

/** Paginated list of contact metadata returned by `GET contacts/v4`. */
data class ContactListResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("Contacts") val contacts: List<ContactMeta>,
    @SerializedName("Total") val total: Int
)

/** Lightweight contact summary (no card data). Returned in list and export endpoints. */
data class ContactMeta(
    /** Server-side contact ID. */
    @SerializedName("ID") val id: String,
    @SerializedName("Name") val name: String,
    /**
     * The contact's vCard UID as Proton stores it, an opaque string: `proton-web-<hex>` from Proton
     * web, `urn:uuid:<uuid>` from this app since 2.0.0.
     */
    @SerializedName("UID") val uid: String,
    @SerializedName("Size") val size: Int,
    /** Unix epoch seconds. */
    @SerializedName("CreateTime") val createTime: Long,
    /** Unix epoch seconds. */
    @SerializedName("ModifyTime") val modifyTime: Long,
    /** Contact group label IDs this contact belongs to. */
    @SerializedName("LabelIDs") val labelIds: List<String>?
)

/** Single-contact response from `GET contacts/v4/{id}`. */
data class ContactDetailResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("Contact") val contact: ContactDetail
)

/** Full contact with vCard cards and associated email addresses. */
data class ContactDetail(
    @SerializedName("ID") val id: String,
    @SerializedName("Name") val name: String,
    @SerializedName("UID") val uid: String,
    @SerializedName("Size") val size: Int,
    @SerializedName("CreateTime") val createTime: Long,
    @SerializedName("ModifyTime") val modifyTime: Long,
    @SerializedName("Cards") val cards: List<ContactCard>,
    @SerializedName("ContactEmails") val contactEmails: List<ContactEmail>?
)

/**
 * A single vCard card within a contact.
 *
 * Card types as Proton's contacts API uses them: 0=Cleartext, 1=Encrypted, 2=Signed, 3=Encrypted+Signed.
 * Most contacts have a type-2 (signed) card for basic fields and a type-3
 * (encrypted+signed) card for sensitive data like phone numbers and addresses.
 */
data class ContactCard(
    /** One of [TYPE_CLEARTEXT], [TYPE_ENCRYPTED], [TYPE_SIGNED], or [TYPE_ENCRYPTED_AND_SIGNED]. */
    @SerializedName("Type") val type: Int,
    /** vCard payload -- cleartext for types 0/2, PGP-encrypted for types 1/3. */
    @SerializedName("Data") val data: String,
    /** Detached PGP signature (present for types 2 and 3, null otherwise). */
    @SerializedName("Signature") val signature: String?
) {
    companion object {
        const val TYPE_CLEARTEXT = 0
        const val TYPE_ENCRYPTED = 1
        const val TYPE_SIGNED = 2
        const val TYPE_ENCRYPTED_AND_SIGNED = 3
    }
}

/** An email address associated with a contact, used for label/group membership. */
data class ContactEmail(
    @SerializedName("ID") val id: String,
    @SerializedName("Name") val name: String,
    @SerializedName("Email") val email: String,
    @SerializedName("Type") val type: List<String>?,
    @SerializedName("ContactID") val contactId: String,
    @SerializedName("LabelIDs") val labelIds: List<String>?
)

/** Batch contact creation request for `POST contacts/v4`. */
data class CreateContactRequest(
    @SerializedName("Contacts") val contacts: List<CreateContactBody>,
    @SerializedName("Overwrite") val overwrite: Int = 0,
    @SerializedName("Labels") val labels: Int = 0,
    @SerializedName("Import") val import_: Int = 0
)

/** Wrapper holding the vCard cards for a single new contact. */
data class CreateContactBody(
    @SerializedName("Cards") val cards: List<ContactCard>
)

/** Contact update request for `PUT contacts/v4/{id}`. */
data class UpdateContactRequest(
    @SerializedName("Cards") val cards: List<ContactCard>
)

/** Batch contact deletion request for `PUT contacts/v4/delete`. */
data class DeleteContactsRequest(
    @SerializedName("IDs") val ids: List<String>
)

/** Response from `GET core/v4/labels?Type=2` listing contact group labels. */
data class ContactLabelsResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("Labels") val labels: List<ProtonContactLabel>
)

/** A contact group label. Type 2 = contact group, type 1 = message label. */
data class ProtonContactLabel(
    @SerializedName("ID") val id: String,
    @SerializedName("Name") val name: String,
    /** Hex color code for display (e.g. "#7272a7"). */
    @SerializedName("Color") val color: String?,
    @SerializedName("Path") val path: String?,
    /** Label type: 2 for contact groups. */
    @SerializedName("Type") val type: Int
)

/** Bulk export response from `GET contacts/v4/export` containing full contact details. */
data class ContactExportResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("Contacts") val contacts: List<ContactDetail>
)

/**
 * Request to create a new contact group label via `POST core/v4/labels`. Also the body of
 * `PUT core/v4/labels/{id}`, which updates one.
 */
data class CreateLabelRequest(
    @SerializedName("Name") val name: String,
    /** Hex color for the label; defaults to Proton purple. */
    @SerializedName("Color") val color: String = "#7272a7",
    /** Label type: 2 = contact group. */
    @SerializedName("Type") val type: Int = 2
)

/**
 * Request to add contact emails to a label via `PUT contacts/v4/contacts/emails/label`, or to
 * remove them via `PUT contacts/v4/contacts/emails/unlabel`.
 */
data class LabelContactEmailsRequest(
    @SerializedName("LabelID") val labelId: String,
    @SerializedName("ContactEmailIDs") val contactEmailIds: List<String>
)
