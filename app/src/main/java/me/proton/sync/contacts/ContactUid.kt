package me.proton.sync.contacts

/**
 * The UID a contact gets when it has none: `urn:uuid:` and a random UUID, the form RFC 6350 6.7.6
 * recommends and what ez-vcard's `Uid.random()` makes. Proton reads a contact's UID as an opaque
 * string: Proton web writes `proton-web-<hex>`, and Proton Mail for Android 4.x wrote this same form.
 * Before 2.0.0 this app wrote `<base64url>@proton.me`, Proton's format for calendar events, which put
 * Proton's domain on contacts Proton never made.
 */
internal fun newContactUid(): String = ezvcard.property.Uid.random().value

/**
 * The UID an upload of a contact Proton already has carries: the one Proton stores for it
 * ([storedUid], the contact's header UID), else the one in its card, and a new one only if it has
 * neither. The card the upload rebuilds from the phone has no UID, and minting one there gave the
 * contact a new UID at every phone-side edit. Proton's import and merge replace a contact by its UID,
 * so restoring an older export or backup then duplicated every contact edited on the phone since.
 */
internal fun uploadUidFor(storedUid: String?, cardUid: String?): String =
    storedUid?.takeIf { it.isNotBlank() } ?: cardUid?.takeIf { it.isNotBlank() } ?: newContactUid()
