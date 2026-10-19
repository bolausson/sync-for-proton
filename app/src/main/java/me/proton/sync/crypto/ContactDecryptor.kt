package me.proton.sync.crypto

import me.proton.sync.SyncLog
import me.proton.sync.api.models.ContactCard
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decrypts Proton contact cards and merges them into a single vCard string.
 *
 * Proton contacts store data across multiple cards:
 * - Type 0 (Cleartext): plaintext vCard, no signature
 * - Type 1 (Encrypted): PGP-encrypted vCard (rare)
 * - Type 2 (Signed): plaintext vCard with detached PGP signature
 * - Type 3 (Encrypted+Signed): PGP-encrypted vCard with detached signature
 *
 * Typically Type 3 contains sensitive fields (phone, address, notes)
 * while Type 0/2 contains email+name for server-side search.
 */
@Singleton
class ContactDecryptor @Inject constructor(
    private val keyManager: KeyManager
) {
    /**
     * Decrypt and merge all contact cards into a single vCard string.
     *
     * @param accountName The account whose key ring to use for decryption
     * @param cards The contact's card array from the API
     * @return Merged vCard string containing all properties from all cards, the UID from the signed
     *   card when it has one ([withoutStrayUids])
     */
    fun decryptAndMergeCards(accountName: String, cards: List<ContactCard>): String {
        val vCardParts = mutableListOf<Pair<Int, String>>()

        for (card in cards) {
            val vCardData = when (card.type) {
                ContactCard.TYPE_CLEARTEXT -> {
                    card.data
                }
                ContactCard.TYPE_SIGNED -> {
                    // Verify signature, return plaintext data
                    if (card.signature != null) {
                        keyManager.verifySignature(accountName, card.data, card.signature)
                    }
                    card.data
                }
                ContactCard.TYPE_ENCRYPTED -> {
                    // Decrypt PGP message (check if actually encrypted first)
                    if (card.data.trimStart().startsWith("-----BEGIN PGP")) {
                        keyManager.decrypt(accountName, card.data)
                    } else {
                        // Data is plaintext despite type=1 flag
                        card.data
                    }
                }
                ContactCard.TYPE_ENCRYPTED_AND_SIGNED -> {
                    // Decrypt, then verify signature
                    if (card.data.trimStart().startsWith("-----BEGIN PGP")) {
                        val decrypted = keyManager.decrypt(accountName, card.data)
                        if (card.signature != null) {
                            keyManager.verifySignature(accountName, decrypted, card.signature)
                        }
                        decrypted
                    } else {
                        card.data
                    }
                }
                else -> continue
            }
            vCardParts.add(card.type to vCardData)
        }

        return mergeVCards(withoutStrayUids(vCardParts))
    }

    /** Thrown rather than silently downgrading a contact to cleartext; see the catch below. */
    class EncryptionUnavailable(cause: Throwable) :
        Exception("Contact cards could not be signed or encrypted", cause)

    /**
     * Create encrypted contact cards from a vCard string for upload.
     * Proton expects:
     * - Card Type 0 (Cleartext): VERSION, PRODID, CATEGORIES, only when the contact has CATEGORIES
     * - Card Type 2 (Signed): cleartext vCard with FN, UID, EMAIL and the key properties (for server
     *   search)
     * - Card Type 3 (Signed+Encrypted): everything else, including all sensitive data
     *
     * @throws EncryptionUnavailable if the cards cannot be signed or encrypted
     */
    fun encryptContactCards(accountName: String, vCardString: String): List<ContactCard> {
        val cards = mutableListOf<ContactCard>()

        // Add ITEM group prefixes to EMAIL and TEL fields (required by Proton API)
        val groupedVCard = addItemGroups(vCardString)

        // Check if contact has CATEGORIES (group membership)
        val hasCategories = groupedVCard.lines().any {
            val prop = it.substringBefore(":").substringBefore(";").uppercase()
            val cleanProp = if (prop.contains(".")) prop.substringAfter(".") else prop
            cleanProp == "CATEGORIES"
        }

        // Card Type 0: Cleartext with VERSION, PRODID, CATEGORIES (only when CATEGORIES exists)
        if (hasCategories) {
            val clearVCard = extractClearTextVCard(groupedVCard)
            cards.add(ContactCard(
                type = ContactCard.TYPE_CLEARTEXT,
                data = clearVCard,
                signature = null
            ))
        }

        try {
            // Card Type 2: Signed cleartext with minimal info (FN, UID, EMAIL for server search)
            // Contacts are signed with the PRIMARY USER KEY (not address key)
            val minimalVCard = extractMinimalVCard(groupedVCard)
            val signature = keyManager.signWithUserKey(accountName, minimalVCard, stripTrailingSpaces = true)
            cards.add(ContactCard(
                type = ContactCard.TYPE_SIGNED,
                data = minimalVCard,
                signature = signature
            ))

            // Card Type 3: Encrypted with user key, signed with primary user key
            // Strip properties already in type 2 to avoid duplicates on export
            val encryptedVCard = stripType2Properties(groupedVCard)
            val encrypted = keyManager.encrypt(accountName, encryptedVCard)
            val fullSignature = keyManager.signWithUserKey(accountName, encryptedVCard, stripTrailingSpaces = true)
            cards.add(ContactCard(
                type = ContactCard.TYPE_ENCRYPTED_AND_SIGNED,
                data = encrypted,
                signature = fullSignature
            ))
        } catch (e: Exception) {
            // Deliberately fatal. This used to fall back to uploading the whole card as cleartext,
            // notes and addresses included, and return it looking like an ordinary success: a key
            // ring that hiccuped once published that contact to the server unencrypted and told
            // nobody. Callers are expected to leave the contact dirty and try again rather than
            // trade the user's privacy for a completed sync.
            SyncLog.e("ContactDecryptor", "Refusing to upload a contact unencrypted", e)
            throw EncryptionUnavailable(e)
        }

        return cards
    }

    /**
     * Add ITEM group prefixes to EMAIL fields.
     * Proton API requires "Contact email must have a unique group".
     * TEL and ADR do NOT need groups.
     */
    private fun addItemGroups(vCardString: String): String {
        var itemCounter = 1
        val needsGroup = setOf("EMAIL")
        return buildString {
            for (line in vCardString.lines()) {
                if (line.isEmpty()) continue
                // Continuation lines (folded) start with space/tab — pass through unchanged
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    appendLine(line)
                    continue
                }
                val propName = line.substringBefore(":").substringBefore(";").uppercase()
                // Skip lines that already have a group prefix (contain a dot before the property name)
                val hasGroup = line.contains(".") && line.indexOf(".") < line.indexOf(":").coerceAtLeast(0)
                if (propName in needsGroup && !hasGroup) {
                    appendLine("ITEM${itemCounter}.$line")
                    itemCounter++
                } else {
                    appendLine(line)
                }
            }
        }.trimEnd()
    }

    /**
     * Extract a minimal vCard for the signed card: FN, EMAIL, UID, VERSION, PRODID and the key
     * properties. N and other fields go in the encrypted card only.
     */
    private fun extractMinimalVCard(vCardString: String): String {
        // As Proton's web client splits a card (WebClients SIGNED_FIELDS), the Type 2 signed card
        // holds FN, EMAIL, UID, VERSION, PRODID, KEY, X-PM-MIMETYPE, X-PM-ENCRYPT,
        // X-PM-ENCRYPT-UNTRUSTED, X-PM-SIGN, X-PM-SCHEME, X-PM-TLS
        val keepPrefixes = setOf("FN", "EMAIL", "UID", "VERSION", "PRODID",
            "KEY", "X-PM-MIMETYPE", "X-PM-ENCRYPT", "X-PM-ENCRYPT-UNTRUSTED",
            "X-PM-SIGN", "X-PM-SCHEME", "X-PM-TLS")
        var keeping = false
        return buildString {
            appendLine("BEGIN:VCARD")
            for (line in vCardString.lines()) {
                if (line.isEmpty()) continue
                // Continuation lines belong to the previous property
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    if (keeping) appendLine(line)
                    continue
                }
                if (line.equals("BEGIN:VCARD", ignoreCase = true) ||
                    line.equals("END:VCARD", ignoreCase = true)) {
                    keeping = false
                    continue
                }
                // Handle ITEM-grouped properties like ITEM1.EMAIL
                var propName = line.substringBefore(":").substringBefore(";").uppercase()
                if (propName.contains(".")) {
                    propName = propName.substringAfter(".")
                }
                keeping = propName in keepPrefixes
                if (keeping) {
                    appendLine(line)
                }
            }
            appendLine("END:VCARD")
        }
    }

    /**
     * Extract a cleartext vCard containing only VERSION, PRODID, and CATEGORIES for the Type 0 card.
     * As in Proton's web client, the Type 0 card is only created when CATEGORIES exists.
     */
    private fun extractClearTextVCard(vCardString: String): String {
        val keepPrefixes = setOf("VERSION", "PRODID", "CATEGORIES")
        var keeping = false
        return buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:4.0")
            for (line in vCardString.lines()) {
                if (line.isEmpty()) continue
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    if (keeping) appendLine(line)
                    continue
                }
                if (line.equals("BEGIN:VCARD", ignoreCase = true) ||
                    line.equals("END:VCARD", ignoreCase = true)) {
                    keeping = false
                    continue
                }
                var propName = line.substringBefore(":").substringBefore(";").uppercase()
                if (propName.contains(".")) propName = propName.substringAfter(".")
                // Skip VERSION (already added above) and only keep PRODID + CATEGORIES
                keeping = propName in keepPrefixes && propName != "VERSION"
                if (keeping) appendLine(line)
            }
            appendLine("END:VCARD")
        }
    }

    /**
     * Strip properties that are already in the type 2 (signed) or type 0 (clear) card
     * to avoid duplicates when Proton merges all cards on export.
     * Removes: FN, EMAIL, UID, PRODID, CATEGORIES and the key properties. Keeps everything else.
     */
    private fun stripType2Properties(vCardString: String): String {
        // Strip properties already in the type-2 card, UID included: Proton's web client puts UID
        // in the signed card only, never in type-3
        val stripPrefixes = setOf("FN", "EMAIL", "UID", "PRODID", "CATEGORIES",
            "KEY", "X-PM-MIMETYPE", "X-PM-ENCRYPT", "X-PM-ENCRYPT-UNTRUSTED",
            "X-PM-SIGN", "X-PM-SCHEME", "X-PM-TLS")
        var stripping = false
        return buildString {
            for (line in vCardString.lines()) {
                if (line.isEmpty()) continue
                // Continuation lines belong to the previous property
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    if (!stripping) appendLine(line)
                    continue
                }
                // Handle ITEM-grouped properties like ITEM1.EMAIL
                var propName = line.substringBefore(":").substringBefore(";").uppercase()
                if (propName.contains(".")) {
                    propName = propName.substringAfter(".")
                }
                stripping = propName in stripPrefixes
                if (!stripping) {
                    appendLine(line)
                }
            }
        }.trimEnd()
    }

    companion object {
        /**
         * Merge multiple vCard strings into one.
         * Proton splits contact data across cards, so we need to combine
         * all properties into a single vCard for Android's ContactsContract.
         */
        internal fun mergeVCards(vCards: List<String>): String {
            if (vCards.isEmpty()) return ""
            if (vCards.size == 1) return vCards.first()

            val allProperties = mutableListOf<String>()
            var version = "4.0"

            for (vCard in vCards) {
                val lines = vCard.lines()
                for (line in lines) {
                    // Folded continuation lines start with space or tab: append to the previous property
                    if ((line.startsWith(" ") || line.startsWith("\t")) && allProperties.isNotEmpty()) {
                        allProperties[allProperties.lastIndex] = allProperties.last() + line.substring(1)
                        continue
                    }
                    val trimmed = line.trim()
                    when {
                        trimmed.equals("BEGIN:VCARD", ignoreCase = true) -> {}
                        trimmed.equals("END:VCARD", ignoreCase = true) -> {}
                        trimmed.startsWith("VERSION:", ignoreCase = true) -> {
                            version = trimmed.substringAfter(":")
                        }
                        trimmed.isNotEmpty() -> allProperties.add(trimmed)
                    }
                }
            }

            // Deduplicate properties (keep last occurrence of single-value props)
            val singleValueProps = setOf("FN", "N", "BDAY", "ANNIVERSARY", "GENDER", "REV", "UID", "PRODID")
            val seen = mutableMapOf<String, Int>()
            val deduped = mutableListOf<String>()

            for (prop in allProperties) {
                val propName = prop.substringBefore(":").substringBefore(";").uppercase()
                if (propName in singleValueProps) {
                    val existingIndex = seen[propName]
                    if (existingIndex != null) {
                        deduped[existingIndex] = prop
                    } else {
                        seen[propName] = deduped.size
                        deduped.add(prop)
                    }
                } else {
                    deduped.add(prop)
                }
            }

            return buildString {
                appendLine("BEGIN:VCARD")
                appendLine("VERSION:$version")
                for (prop in deduped) {
                    appendLine(prop)
                }
                appendLine("END:VCARD")
            }
        }

        private val ENCRYPTED_TYPES = setOf(ContactCard.TYPE_ENCRYPTED, ContactCard.TYPE_ENCRYPTED_AND_SIGNED)

        /**
         * The decrypted cards with the UID left where it belongs, in the signed card, the one Proton
         * stores. Proton Mail for Android 4.x also wrote a second, random UID into the encrypted card,
         * and [mergeVCards] keeps the last UID it meets, so such a contact came out under a UID Proton
         * does not have, and a name repair or a backup carried it on. Proton web drops UIDs from
         * encrypted cards on read for the same reason. An encrypted card keeps its UID when no other
         * card has one.
         */
        internal fun withoutStrayUids(parts: List<Pair<Int, String>>): List<String> {
            val plainUid = parts.any { (type, data) -> type !in ENCRYPTED_TYPES && hasUid(data, nonBlank = true) }
            return parts.map { (type, data) ->
                if (plainUid && type in ENCRYPTED_TYPES && hasUid(data)) dropUid(data) else data
            }
        }

        private fun isUidLine(line: String) =
            line.substringBefore(":").substringBefore(";").trim().equals("UID", ignoreCase = true)

        private fun hasUid(vCard: String, nonBlank: Boolean = false) = vCard.lineSequence().any {
            !it.startsWith(" ") && !it.startsWith("\t") && isUidLine(it) && (!nonBlank || it.substringAfter(":").isNotBlank())
        }

        /** [vCard] without its UID lines, folded continuations included. */
        private fun dropUid(vCard: String): String {
            val kept = mutableListOf<String>()
            var dropping = false
            for (line in vCard.lines()) {
                if (!line.startsWith(" ") && !line.startsWith("\t")) dropping = isUidLine(line)
                if (!dropping) kept.add(line)
            }
            return kept.joinToString("\r\n")
        }
    }
}
