package me.proton.sync.crypto

import android.util.Base64
import me.proton.sync.SyncLog
import me.proton.sync.calendar.ICalUtils
import me.proton.sync.api.models.CalendarEventDetail
import me.proton.sync.api.models.CalendarKey
import me.proton.sync.api.models.CalendarPassphrase
import me.proton.sync.api.models.EventPart

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decrypts Proton calendar events using the full key chain:
 *   User Key Ring -> decrypt MemberPassphrase -> unlock CalendarKey
 *     -> decrypt event data (SharedEvents, CalendarEvents, PersonalEvents)
 */
@Singleton
class CalendarDecryptor @Inject constructor(
    private val keyManager: KeyManager
) {
    /**
     * Decrypt all event parts and merge into a single iCal string.
     */
    fun decryptEvent(
        accountName: String,
        event: CalendarEventDetail,
        calendarKeys: List<CalendarKey>,
        calendarPassphrases: List<CalendarPassphrase>
    ): String {
        // Unlock ALL calendar keys (not just the first) to handle key rotation
        val allCalendarKeys = tryUnlockAllCalendarKeys(accountName, calendarKeys, calendarPassphrases)

        val iCalParts = mutableListOf<String>()

        // SharedEvents use AddressKeyPacket (if present) or SharedKeyPacket for the session key.
        // Proton source: readSessionKey(calendarEvent.AddressKeyPacket || calendarEvent.SharedKeyPacket, privateKeys)
        val sharedKeyPacket = event.addressKeyPacket ?: event.sharedKeyPacket
        for (part in event.sharedEvents) {
            val data = decryptEventPartWithMultipleKeys(accountName, part, sharedKeyPacket, allCalendarKeys)
            if (data != null) iCalParts.add(data)
        }

        // CalendarEvents use CalendarKeyPacket for the asymmetric key wrapper
        val calendarKeyPacket = event.calendarKeyPacket
        for (part in event.calendarEvents) {
            val data = decryptEventPartWithMultipleKeys(accountName, part, calendarKeyPacket, allCalendarKeys)
            if (data != null) iCalParts.add(data)
        }

        // PersonalEvents also use SharedKeyPacket
        event.personalEvents?.forEach { part ->
            val data = decryptEventPartWithMultipleKeys(accountName, part, sharedKeyPacket, allCalendarKeys)
            if (data != null) iCalParts.add(data)
        }

        // AttendeesEvents contain encrypted ATTENDEE properties
        event.attendeesEvents?.forEach { part ->
            val data = decryptEventPartWithMultipleKeys(accountName, part, sharedKeyPacket, allCalendarKeys)
            if (data != null) iCalParts.add(data)
        }

        if (iCalParts.isEmpty()) {
            SyncLog.w(TAG, "Decrypted 0 iCal parts for event id=${event.id} uid=${event.uid} (keysUnlocked=${allCalendarKeys}, shared=${event.sharedEvents.size}, cal=${event.calendarEvents.size}) - emitting [Encrypted Event] placeholder")
        }
        return mergeICalParts(iCalParts, event)
    }

    /**
     * Unlock ALL calendar private keys by finding matching passphrases.
     * Returns a list of all successfully unlocked keys.
     * The official Proton code loads all keys into one key ring so that
     * events encrypted with any rotated key can be decrypted.
     */
    private fun tryUnlockAllCalendarKeys(
        accountName: String,
        calendarKeys: List<CalendarKey>,
        calendarPassphrases: List<CalendarPassphrase>
    ): Int {
        if (!keyManager.isKeyUnlocked(accountName)) return 0

        var unlockedCount = 0

        for (calKey in calendarKeys) {
            val passphrase = calendarPassphrases.firstOrNull { it.id == calKey.passphraseId }
            if (passphrase == null) {
                SyncLog.w(TAG, "No matching passphrase found for key ${calKey.id}")
                continue
            }

            // Try each member passphrase (one should be encrypted to our key)
            for (memberPassphrase in passphrase.memberPassphrases) {
                try {
                    keyManager.unlockCalendarKey(
                        accountName = accountName,
                        calendarId = calKey.calendarId,
                        isPrimary = isPrimaryCalendarKey(calKey.flags),
                        encryptedPassphrase = memberPassphrase.passphrase,
                        armoredCalendarKey = calKey.privateKey
                    )
                    unlockedCount++
                    break // This member passphrase worked, move to next calendar key
                } catch (e: Exception) {
                    continue
                }
            }
        }

        if (unlockedCount == 0) {
            SyncLog.w(TAG, "Could not unlock any calendar key for $accountName")
        }

        return unlockedCount
    }

    /**
     * Decrypt an event part. For encrypted types, tryDecryptBinary uses
     * KeyManager.decryptBinaryWithAllKeys, which routes by PKESK keyID across
     * the union of calendar and user/address keys, so the outer calendarKeys
     * list does not need to be iterated here.
     */
    private fun decryptEventPartWithMultipleKeys(
        accountName: String,
        part: EventPart,
        keyPacket: String?,
        unlockedKeyCount: Int
    ): String? {
        return try {
            decryptEventPart(accountName, part, keyPacket)
        } catch (e: Exception) {
            SyncLog.w(TAG, "Event part decryption failed: type=${part.type}, ${e.message}")
            null
        }
    }

    /**
     * Decrypt a single event part.
     *
     * For encrypted types (1, 3) the Proton API uses a split packet model:
     *   - [keyPacket] is a base64-encoded binary PKESK (Public-Key Encrypted Session Key) packet
     *   - [part.data] is a base64-encoded binary SEIPD (Symmetrically Encrypted Integrity Protected
     *     Data) packet containing ONLY the ciphertext — no key material
     *
     * A complete PGP message is formed by concatenating the two decoded byte arrays:
     *   base64decode(keyPacket) + base64decode(part.data)
     *
     * That combined binary blob is then passed to the account's key ring for decryption.
     *
     * @param keyPacket  The base64-encoded KeyPacket for this group of parts, or null if absent
     */
    private fun decryptEventPart(
        accountName: String,
        part: EventPart,
        keyPacket: String?
    ): String? {
        return when (part.type) {
            0 -> part.data  // Cleartext — no decryption needed
            1 -> {
                // Type 1 = encrypted, unsigned
                if (part.data.trimStart().startsWith("BEGIN:")) {
                    part.data
                } else {
                    tryDecryptBinary(accountName, keyPacket, part.data)
                }
            }
            2 -> {
                // Type 2 = signed cleartext — data is plaintext, verify signature
                if (part.signature != null) {
                    keyManager.verifySignature(accountName, part.data, part.signature)
                }
                part.data
            }
            3 -> {
                // Type 3 = encrypted AND signed — decrypt first, then verify
                if (part.data.trimStart().startsWith("BEGIN:")) {
                    part.data
                } else {
                    val decrypted = tryDecryptBinary(accountName, keyPacket, part.data)
                        ?: return null
                    if (part.signature != null) {
                        keyManager.verifySignature(accountName, decrypted, part.signature)
                    }
                    decrypted
                }
            }
            else -> null
        }
    }

    /**
     * Reassemble a complete PGP binary message from the split KeyPacket + DataPacket model
     * and decrypt it.
     *
     * Mirrors gopenpgp's `calKR.Decrypt` approach: combine keyPacket + dataPacket into one
     * binary PGP message and let GopenPGP handle PKESK parsing, keyID matching, and the
     * key agreement + KDF.
     *
     * **Key set:** the account ring holds calendar keys *and* user/address keys, so one
     * call covers both. The previous code split this into "Strategy 1: calendar keys, then
     * Strategy 2: user keys" which always failed Strategy 1 for any part encrypted to
     * the user's address key (Proton's `AddressKeyPacket` shape used by SharedEvents,
     * PersonalEvents, AttendeesEvents). Spurious failures every event, no diagnostic
     * value. One ring lets GopenPGP pick the right key by PKESK keyID on the first attempt.
     *
     * **One attempt:** the binary message is decrypted once. There is no armored retry.
     *
     * The previous manual X25519 implementation had multiple issues:
     *   - Did not match key IDs from the PKESK packet to the correct calendar subkey
     *   - Used the wrong key's fingerprint in the RFC 6637 KDF when multiple keys existed
     *   - Could not handle multiple PKESK packets in a single KeyPacket blob
     *   - Did not support RFC 9580 X25519 (algorithm 25) which uses HKDF instead of RFC 6637 KDF
     *
     * @param keyPacket   Base64-encoded binary KeyPacket (PKESK); may be null
     * @param dataPacket  Base64-encoded binary DataPacket (SEIPD), or armored PGP text, which
     *                    is decrypted on its own; the KeyPacket must still be present but is not used
     */
    private fun tryDecryptBinary(
        accountName: String,
        keyPacket: String?,
        dataPacket: String
    ): String? {
        if (keyPacket == null) {
            SyncLog.w(TAG, "No KeyPacket available for encrypted event part in $accountName")
            return null
        }

        val keyPacketBytes = try {
            Base64.decode(keyPacket, Base64.NO_WRAP)
        } catch (e: Exception) {
            SyncLog.e(TAG, "Failed to base64-decode KeyPacket: ${e.message}")
            return null
        }

        val dataPacketBytes = try {
            Base64.decode(dataPacket, Base64.NO_WRAP)
        } catch (e: Exception) {
            // DataPacket might be armored PGP text instead of base64 binary
            if (dataPacket.trimStart().startsWith("-----BEGIN PGP")) {
                SyncLog.d(TAG, "DataPacket is armored PGP, decrypting directly")
                return try {
                    // The account ring holds the calendar keys too and routes by PKESK, so there
                    // is no longer a separate "decrypt with THIS calendar key" path.
                    keyManager.decrypt(accountName, dataPacket)
                } catch (e2: Exception) {
                    SyncLog.w(TAG, "Armored decryption failed: ${e2.message}")
                    null
                }
            }
            SyncLog.e(TAG, "Failed to base64-decode DataPacket: ${e.message}")
            return null
        }

        val combined = keyPacketBytes + dataPacketBytes

        // Single binary attempt: calendar + user/address keys, GopenPGP routes by PKESK keyID.
        // One attempt is enough now. The old armored retry existed purely to work around
        // BouncyCastle's binary parser confusing EdDSA and ECDH key types on X25519 PKESKs;
        // GopenPGP reads the binary form correctly, so re-armoring the same bytes and trying
        // again would only ever fail twice as slowly.
        return try {
            keyManager.decryptBinaryWithAllKeys(accountName, combined)
        } catch (e: Exception) {
            SyncLog.w(TAG, "Decryption failed for $accountName: ${e.message}")
            null
        }
    }

    /**
     * Merge decrypted iCal parts into a single VCALENDAR.
     */
    private fun mergeICalParts(parts: List<String>, event: CalendarEventDetail): String {
        if (parts.isEmpty()) {
            return buildString {
                appendLine("BEGIN:VCALENDAR")
                appendLine("VERSION:2.0")
                appendLine("PRODID:${ICalUtils.PRODID}")
                appendLine("BEGIN:VEVENT")
                appendLine("UID:${event.uid}")
                appendLine("DTSTART:${ICalUtils.formatTimestampFromSeconds(event.startTime)}")
                appendLine("DTEND:${ICalUtils.formatTimestampFromSeconds(event.endTime)}")
                appendLine("SUMMARY:[Encrypted Event]")
                appendLine("END:VEVENT")
                appendLine("END:VCALENDAR")
            }
        }

        return mergeParts(parts, event.uid)
    }

    companion object {
        private const val TAG = "CalendarDecryptor"
        private val ICAL_PROPERTY_PREFIX = Regex("^[A-Z][A-Z0-9-]*[:;]")

        /**
         * Decrypted and verified iCal [parts] merged into one VCALENDAR with event [uid]: unfolded,
         * re-folded, and legacy raw line breaks stitched back onto their property. Pure.
         */
        internal fun mergeParts(parts: List<String>, uid: String): String {
            val allProperties = mutableListOf<String>()
            for (part in parts) {
                // First, unfold RFC 5545 folded lines: continuation lines start with a space or tab
                // and should be joined to the previous line. We must do this BEFORE splitting into
                // properties, otherwise trim() destroys the folding and corrupts multi-line values.
                val unfolded = part.replace(Regex("\r?\n[ \t]"), "")

                for (line in unfolded.lines()) {
                    val trimmed = line.trim()
                    when {
                        trimmed.equals("BEGIN:VCALENDAR", ignoreCase = true) -> {}
                        trimmed.equals("END:VCALENDAR", ignoreCase = true) -> {}
                        trimmed.equals("BEGIN:VEVENT", ignoreCase = true) -> {}
                        trimmed.equals("END:VEVENT", ignoreCase = true) -> {}
                        trimmed.startsWith("VERSION:", ignoreCase = true) -> {}
                        trimmed.startsWith("PRODID:", ignoreCase = true) -> {}
                        trimmed.isEmpty() -> {}
                        // A "stray" line that doesn't look like an iCal property (no NAME: or NAME;
                        // prefix) is almost certainly the tail of a multi-line value whose embedded
                        // newline wasn't escaped as \n. Stitch it back onto the previous property.
                        !looksLikePropertyLine(trimmed) && allProperties.isNotEmpty() -> {
                            val prev = allProperties.removeAt(allProperties.size - 1)
                            allProperties.add("$prev\\n$trimmed")
                        }
                        else -> allProperties.add(trimmed)
                    }
                }
            }

            return buildString {
                appendLine("BEGIN:VCALENDAR")
                appendLine("VERSION:2.0")
                appendLine("PRODID:${ICalUtils.PRODID}")
                appendLine("BEGIN:VEVENT")
                appendLine("UID:$uid")
                for (prop in allProperties) {
                    // Re-fold long lines per RFC 5545 (max 75 octets per line)
                    appendLine(ICalUtils.foldLine(prop))
                }
                appendLine("END:VEVENT")
                appendLine("END:VCALENDAR")
            }
        }

        /**
         * Heuristic: does this line start with an iCal property name followed by `:` or `;`?
         *
         * RFC 5545 property names are conventionally ALL UPPERCASE (`SUMMARY`, `DTSTART`,
         * `X-PM-PROTON-REPLY` …) — both standard, IANA-registered, and X-extension names.
         * Tightening the match to require an uppercase first letter and uppercase-only name
         * lets us reject orphan lines from broken DESCRIPTION values that *look* like
         * properties because they're written `Word:value` (`Flight:`, `From:`, `Departs:` —
         * seen in TripCase confirmations and similar travel-itinerary text). Without this,
         * those orphans get treated as new properties and ical4j crashes downstream when
         * one of them collides with a real iCal token (e.g. `Duration:` parsed as DURATION).
         *
         * Lines that don't match are stitched back onto the previous property's value with
         * a literal `\n` escape — the encoding the source iCal *should* have used.
         */
        private fun looksLikePropertyLine(line: String): Boolean {
            return ICAL_PROPERTY_PREFIX.containsMatchIn(line)
        }
    }
}



