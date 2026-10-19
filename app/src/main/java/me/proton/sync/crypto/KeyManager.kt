@file:Suppress("DEPRECATION")

package me.proton.sync.crypto

import android.util.Base64
import me.proton.sync.SyncLog
import me.proton.sync.account.AccountSessionManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages per-account PGP key rings for decrypting Proton data.
 *
 * Key hierarchy per account:
 *   Password + KeySalt -> bcrypt -> Mailbox Passphrase
 *     -> decrypt EncPrivateKey (armored PGP private key) -> User Key Ring
 *       -> decrypt contact vCards (Type 1/3)
 *       -> decrypt calendar passphrases -> Calendar Key Ring -> decrypt events
 */
@javax.inject.Singleton
class KeyManager @javax.inject.Inject constructor(
    private val sessionManager: AccountSessionManager
) {

    init {
        // Normally a no-op: ProtonSyncApp.onCreate installs the bundled provider
        // before any TLS/PGP use. Kept as a safety net for non-Application entry
        // points (see BouncyCastleSetup for why the install must happen early).
        BouncyCastleSetup.ensureInstalled()
    }

    /**
     * Whether GopenPGP can parse this armored key at all, passphrase aside.
     *
     * Used during key SELECTION. A Proton account with post-quantum protection enabled carries two
     * primary user keys, a v4 and a v6; GopenPGP reads both, which is the entire reason this app
     * no longer uses BouncyCastle's OpenPGP layer — it has no RFC 9980 algorithm (30-36) on any
     * release or on main, and threw "unknown signature key algorithm: 30" before any passphrase
     * was involved, which is exactly how sign-in was failing on such accounts.
     */
    fun canParseKey(armoredPrivateKey: String): Boolean {
        if (bridge.Bridge.canParseKey(armoredPrivateKey)) return true
        SyncLog.w(TAG, "GopenPGP cannot parse this key; it will be skipped during selection")
        return false
    }

    /**
     * Unlock the user's private key: derive the mailbox password from the password and key salt,
     * then unlock the key with it.
     * Must be called after login, before any decrypt operations.
     *
     * @param accountName The account name
     * @param password The user's plaintext password
     * @param keySalt Base64-encoded key salt from auth response
     * @param armoredPrivateKey The user's armored PGP private key
     * @return The derived mailbox password
     */
    fun unlockUserKey(accountName: String, password: String, keySalt: String, armoredPrivateKey: String): String {
        val saltBytes = Base64.decode(keySalt, Base64.DEFAULT)
        val mailboxPassword = deriveMailboxPassword(password.toByteArray(Charsets.UTF_8), saltBytes)
        unlockUserKeyWithMailboxPassword(accountName, mailboxPassword, armoredPrivateKey)
        return String(mailboxPassword)
    }

    /**
     * Add a FURTHER user key to the account's decryption ring.
     *
     * A post-quantum account holds two primary user keys, a v4 and a v6, and Proton may encrypt to
     * either. Its own clients hand the whole set to OpenPGP and let it route by PKESK, so holding
     * only the one key we happened to unlock first would leave the other's data unreadable.
     *
     * Each user key has its OWN salt, so the passphrase must be derived per key rather than reusing
     * the primary's mailbox password.
     *
     * Best effort by design: this runs after sign-in has already succeeded, and one key that will
     * not unlock must not turn a working sign-in into a failure.
     *
     * @return true if the key went into the ring
     */
    fun addUserKey(accountName: String, password: String, keySalt: String, armoredPrivateKey: String): Boolean = try {
        val saltBytes = Base64.decode(keySalt, Base64.DEFAULT)
        val mailboxPassword = deriveMailboxPassword(password.toByteArray(Charsets.UTF_8), saltBytes)
        val before = goKeyRings[accountName]?.countKeys() ?: 0
        addKeyToGoRing(accountName, armoredPrivateKey, String(mailboxPassword), "user(additional)")
        val after = goKeyRings[accountName]?.countKeys() ?: 0
        if (after > before) {
            SyncLog.i(
                TAG,
                "Additional user key added for $accountName " +
                    "(v${bridge.Bridge.keyVersion(armoredPrivateKey)}, alg ${bridge.Bridge.keyAlgorithm(armoredPrivateKey)})"
            )
        }
        after > before
    } catch (e: Throwable) {
        SyncLog.w(TAG, "Could not add an additional user key for $accountName: ${e.message}")
        false
    }

    // ---------------------------------------------------------------------------------------
    // GopenPGP key ring.
    //
    // One ring per account holding EVERY key we unlock (user, address, calendar). That mirrors
    // decryptBinaryWithAllKeys, and matches how Proton's own clients decrypt: hand over the whole
    // key set and let OpenPGP route by PKESK rather than choosing a key up front.
    //
    // Crucially the ring accepts post-quantum v6 keys, which BouncyCastle could not even parse.
    //
    // The differential harness that licensed this migration decrypted everything twice and
    // compared: 30/30 byte-identical on a real account, all three key kinds present. It is gone,
    // because leaving it in would double the crypto work on every sync for no new information.
    // ---------------------------------------------------------------------------------------

    private val goKeyRings = java.util.concurrent.ConcurrentHashMap<String, bridge.KeyRing>()

    /**
     * Per-address GopenPGP signing rings: "accountName" (default) or "accountName:email@addr".
     *
     * Signing has to name ONE key rather than let OpenPGP route by
     * PKESK the way decryption does, so the account-wide ring is not enough: Proton signs as a
     * specific address, and signing as the wrong one produces a message the recipient rejects.
     */
    private val goAddressRings = java.util.concurrent.ConcurrentHashMap<String, bridge.KeyRing>()

    /**
     * Single-key rings used as ENCRYPTION targets, keyed by account (user key) and
     * "accountName:cal:calendarId" (that calendar's key).
     *
     * Deliberately not [goKeyRings]: that one holds every key on the account — user, address and
     * calendar, seven on a typical account — and encrypting to it would emit a key packet per key
     * rather than the single one Proton expects. Decryption wants the whole set so OpenPGP can
     * route by PKESK; encryption wants exactly one recipient. Same reason [goAddressRings] exists
     * for signing.
     */
    private val goUserRings = java.util.concurrent.ConcurrentHashMap<String, bridge.KeyRing>()
    private val goCalendarRings = java.util.concurrent.ConcurrentHashMap<String, bridge.KeyRing>()

    /** Address emails seen per account, for getAddressEmails(). */
    private val goAddressEmails = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Which calendar keys have unlocked, and which one each calendar encrypts with.
     *
     * unlockCalendarKey is called once per calendar per sync, and decrypting a calendar passphrase
     * is not free, so this keeps that work to once per key while still letting a primary key take
     * over from a non-primary one. See [CalendarKeySelection] for why it is not a plain set.
     */
    private val calendarKeySelection = CalendarKeySelection()

    /** Keys already in an account ring, so re-authentication does not append duplicates. */
    private val goRingKeysAdded = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * Per-address signing rings already built, keyed by account + email + key.
     *
     * Separate from [goRingKeysAdded] because that one guards the shared account ring, while this
     * guards the one-key rings in [goAddressRings]. Rebuilding those is not merely wasteful: the
     * replaced instance may still be aliased by the account-level default slot. See
     * [unlockAddressKeysWithMailboxPassword].
     */
    private val goAddressSigningRingsAdded = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * Add one unlocked key to this account's GopenPGP ring, creating the ring on first use.
     *
     * Re-adding the same key is skipped rather than duplicated. In the normal flow this cannot
     * happen — SyncUtils.ensureKeysUnlocked short-circuits on isKeyUnlocked() so keys are unlocked
     * once per process, which a second sync in the same process confirmed — but re-authentication
     * calls the unlock path again on a live ring, and without this guard every such round would
     * add another copy of every key. Unlike the BouncyCastle maps this replaced, a ring appends.
     */
    private fun addKeyToGoRing(accountName: String, armoredKey: String, passphrase: String, kind: String) {
        try {
            synchronized(goKeyRings) {
                if (!goRingKeysAdded.add("$accountName:${armoredKey.hashCode()}")) {
                    SyncLog.d(TAG, "GOPGP-RING $kind key already in the ring for $accountName, not re-adding")
                    return
                }
                val existing = goKeyRings[accountName]
                if (existing == null) {
                    val ring = bridge.Bridge.newKeyRing(armoredKey, passphrase)
                    goKeyRings[accountName] = ring
                    SyncLog.i(
                        TAG,
                        "GOPGP-RING $accountName created from $kind key " +
                            "(v${bridge.Bridge.keyVersion(armoredKey)}, alg ${bridge.Bridge.keyAlgorithm(armoredKey)})"
                    )
                } else {
                    existing.addKey(armoredKey, passphrase)
                    // Log every add. A silent success here is what let two missing key kinds
                    // (address, and calendar on a warm cache) masquerade as GopenPGP failures.
                    SyncLog.i(
                        TAG,
                        "GOPGP-RING $accountName added $kind key " +
                            "(v${bridge.Bridge.keyVersion(armoredKey)}, alg ${bridge.Bridge.keyAlgorithm(armoredKey)}), " +
                            "ring now holds ${existing.countKeys()}"
                    )
                }
            }
        } catch (e: Throwable) {
            // Logged rather than thrown, so one key that will not go in does not stop the others.
            // A post-quantum key that GopenPGP cannot read would show up here.
            SyncLog.w(TAG, "GOPGP-RING could not add $kind key for $accountName: ${e.message}")
        }
    }

    // All OpenPGP work goes through the GopenPGP rings above; BouncyCastle is only the JCA
    // provider (see BouncyCastleSetup). This counter is left from when GopenPGP was a fallback
    // behind BouncyCastle, and nothing uses it now.
    private val goFallbackHits = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Unlock user key with an already-derived mailbox password.
     * Used by sync adapters when re-unlocking from stored credentials.
     */
    fun unlockUserKeyWithMailboxPassword(accountName: String, mailboxPassword: CharArray, armoredPrivateKey: String) {
        // Shared path, so this also covers accounts already signed in that re-unlock from stored
        // credentials on every sync.
        addKeyToGoRing(accountName, armoredPrivateKey, String(mailboxPassword), "user")

        // Single-key ring used as an encryption target and for signing with the user key.
        goUserRings.put(accountName, bridge.Bridge.newKeyRing(armoredPrivateKey, String(mailboxPassword)))?.close()

        val ring = goKeyRings[accountName]
            ?: throw IllegalStateException("Failed to unlock the user key for $accountName")
        SyncLog.i(TAG, "User key unlocked for $accountName (ring holds ${ring.countKeys()})")
    }

    /**
     * Unlock address keys and add them to the account's GopenPGP rings.
     *
     * Address keys use one of two unlocking strategies:
     *   - If the key has a Token field: decrypt the token with the user key to obtain the
     *     passphrase, then use that passphrase to unlock the address key.
     *   - If there is no Token field: unlock the address key directly with the mailbox password
     *     (same passphrase used for the user key).
     *
     * Each unlocked key goes into the account-wide ring, so decrypt() can route to it, and into a
     * per-address ring used for signing.
     *
     * @param accountName The account name
     * @param password The user's plaintext password (used to re-derive mailboxPassword)
     * @param keySalt Base64-encoded key salt (from the session)
     * @param addressKeys List of address UserKey objects (may include a Token field)
     */
    fun unlockAddressKeys(
        accountName: String,
        password: String,
        keySalt: String,
        addressKeys: List<me.proton.sync.api.models.UserKey>
    ) {
        val saltBytes = Base64.decode(keySalt, Base64.DEFAULT)
        val mailboxPassword = deriveMailboxPassword(password.toByteArray(Charsets.UTF_8), saltBytes)
        unlockAddressKeysWithMailboxPassword(accountName, mailboxPassword, addressKeys)
    }

    /**
     * Unlock address keys with an already-derived mailbox password.
     */
    fun unlockAddressKeysWithMailboxPassword(
        accountName: String,
        mailboxPassword: CharArray,
        addressKeys: List<me.proton.sync.api.models.UserKey>
    ) {
        var unlocked = 0
        for (addressKey in addressKeys) {
            if (addressKey.active != 1) continue
            try {
                // Address keys are unlocked one of two ways, mirroring Proton's own clients:
                //  - with a Token: decrypt the token with the user key to get the passphrase
                //  - without:      the mailbox password unlocks it directly
                val passphrase = if (addressKey.token != null) {
                    decrypt(accountName, addressKey.token)
                } else {
                    String(mailboxPassword)
                }

                // Into the account-wide ring, so decrypt() can route to it by PKESK.
                addKeyToGoRing(accountName, addressKey.privateKey, passphrase, "address")

                // And into a per-address ring, because signing must name ONE key: Proton signs as
                // a specific address, and signing as the wrong one produces a message the
                // recipient rejects.
                //
                // Built at most once per key. Rebuilding on a later call orphaned whatever the
                // account-level default was aliasing and closed it: the old code pinned the first
                // ring into goAddressRings[accountName] with putIfAbsent, then the per-email put()
                // returned that same instance and closed it, leaving the account-level slot
                // pointing at a dead ring for the rest of the process ("key ring is closed" out of
                // every `?: goAddressRings[accountName]` fallback). That needed two unlock passes,
                // so it used to take a re-authentication; it also fires on the FIRST pass whenever
                // one address carries two active keys, which is exactly what a post-quantum
                // account looks like (a v4 and a v6 key sharing one addressEmail).
                val ringKey = "$accountName:${addressKey.addressEmail?.lowercase()}:${addressKey.privateKey.hashCode()}"
                if (goAddressSigningRingsAdded.add(ringKey)) {
                    val signingRing = bridge.Bridge.newKeyRing(addressKey.privateKey, passphrase)
                    addressKey.addressEmail?.let { email ->
                        // Replace, never close: the displaced ring may still be aliased by the
                        // account-level slot below or by an earlier key for the same address.
                        // Leaking one ring per genuine key rotation is far cheaper than a
                        // use-after-close, and dedup means that is the only time we get here.
                        goAddressRings["$accountName:${email.lowercase()}"] = signingRing
                        goAddressEmails.getOrPut(accountName) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                            .add(email.lowercase())
                    }
                    // Account-level default tracks the newest live ring instead of pinning the
                    // first one, so it can never be left referencing a ring that was replaced.
                    goAddressRings[accountName] = signingRing
                }
                unlocked++
            } catch (e: Throwable) {
                SyncLog.w(TAG, "Failed to unlock address key ${addressKey.id}: ${e.message}")
            }
        }
        SyncLog.d(TAG, "Address keys unlocked for $accountName: $unlocked")
    }

    /**
     * Check if a user's key ring is unlocked.
     */
    fun isKeyUnlocked(accountName: String): Boolean = goKeyRings.containsKey(accountName)

    /** Whether any address key has been unlocked for this account. */
    fun hasAddressKeys(accountName: String): Boolean = goAddressRings.containsKey(accountName)

    /**
     * Decrypt an armored PGP message using the account's key ring.
     *
     * The ring holds the user, address and calendar keys, and OpenPGP routes to whichever one the
     * PKESK names — including a post-quantum v6 key, which is the whole point of the migration.
     */
    fun decrypt(accountName: String, armoredMessage: String): String {
        val ring = goKeyRings[accountName]
            ?: throw IllegalStateException("User key not unlocked for $accountName. Call unlockUserKey() first.")
        return ring.decryptArmored(armoredMessage)
    }

    /**
     * Decrypt an armored PGP message. Retained for call sites that used to pass a specific key;
     * the account ring routes by PKESK, so naming a key is no longer necessary or possible.
     */
    fun decryptWithKey(armoredMessage: String, accountName: String): String =
        decrypt(accountName, armoredMessage)

    /**
     * Decrypt raw binary PGP data (not armored) using the account's key ring.
     *
     * Used for calendar event parts, where the full PGP message is formed by
     * concatenating the binary-decoded KeyPacket and DataPacket blobs.
     */
    fun decryptBinary(accountName: String, combinedData: ByteArray): String {
        val ring = goKeyRings[accountName]
            ?: throw IllegalStateException("User key not unlocked for $accountName. Call unlockUserKey() first.")
        return ring.decryptBinary(combinedData)
    }

    /**
     * Decrypt raw binary PGP data across every key on the account.
     *
     * OpenPGP routes by the PKESK key ID, so one ring holding the user, address and calendar keys
     * replaces the old "merge two keyID maps and try each in turn" loop. It also means a key
     * BouncyCastle could not parse — a post-quantum v6 key — participates like any other.
     */
    fun decryptBinaryWithAllKeys(accountName: String, combinedData: ByteArray): String {
        val ring = goKeyRings[accountName]
            ?: throw CryptoException("No keys available for $accountName (keys not unlocked)")
        return ring.decryptBinary(combinedData)
    }

    /**
     * Verify a detached PGP signature against every key in the account's ring.
     *
     * GopenPGP finds the key the signature names among the user, address and calendar keys, so
     * mixed-algorithm key rings (e.g. RSA primary + Ed448 signing subkey) need no special care.
     * A signature no key in the ring verifies, such as one made by another Proton user's address
     * key, returns false, which the caller treats as unverified rather than aborting the
     * operation. With no ring at all this returns true (see the body).
     */
    fun verifySignature(accountName: String, data: String, armoredSignature: String): Boolean {
        // No ring at all still means "cannot verify", which callers treat as acceptable rather
        // than as a failure. Preserved deliberately: turning this into false would make every
        // event look tampered with on an account whose keys have not been unlocked yet.
        val ring = goKeyRings[accountName] ?: return true

        // One ring holds the user, address and calendar keys, so the keyID search the
        // BouncyCastle version did by hand across three separate caches is now just "does any key
        // in the ring verify this". Proton signs calendar events and contact cards with the
        // ADDRESS key, and some with the calendar key, not the login key — which is why all three
        // kinds have to be present, and why they are.
        return ring.verifyDetachedArmored(data, armoredSignature)
    }

    /**
     * Decrypt a calendar passphrase using the user's key ring,
     * then use it to unlock the calendar's private key.
     *
     * @param accountName The account name
     * @param calendarId  The Proton calendar ID this key belongs to (used to route encrypts
     *                    to the correct calendar's key on multi-calendar accounts)
     * @param isPrimary   True if this is the calendar's active primary key, per
     *                    [isPrimaryCalendarKey] (Flags ACTIVE=1 and PRIMARY=2 both set, so 3).
     *                    Primary wins over non-primary in the per-calendar encryption ring.
     * @param encryptedPassphrase Armored PGP message containing the passphrase
     * @param armoredCalendarKey Armored PGP private key for the calendar
     * @throws CryptoException if the key could not be unlocked
     */
    fun unlockCalendarKey(
        accountName: String,
        calendarId: String,
        isPrimary: Boolean,
        encryptedPassphrase: String,
        armoredCalendarKey: String
    ) {
        val keyId = "$accountName:${armoredCalendarKey.hashCode()}"
        val slot = "$accountName:cal:$calendarId"

        // Nothing to do only when this key has ALREADY unlocked and the calendar's encryption key
        // is settled with respect to it. Deciding that from a set filled before the unlock, as this
        // used to, let a failed attempt (another member's passphrase, tried first by our callers)
        // pass as success, and stopped a primary key from ever replacing a non-primary one.
        // The key must also still be in the account ring: sign-out clears that set, so a record
        // left behind by an unlock that raced a sign-out can never skip putting the key back.
        if (keyId in goRingKeysAdded &&
            calendarKeySelection.isSettled(keyId, slot, isPrimary, slotHasRing = goCalendarRings.containsKey(slot))
        ) return

        val passphrase = try {
            decrypt(accountName, encryptedPassphrase)
        } catch (e: Exception) {
            throw CryptoException("Could not decrypt the calendar passphrase for cal=$calendarId: ${e.message}")
        }

        // Into the account-wide ring, so events encrypted to ANY calendar key (including rotated
        // ones) decrypt without the caller knowing which key was used. It de-duplicates itself.
        addKeyToGoRing(accountName, armoredCalendarKey, passphrase, "calendar")

        // And into a single-key ring used as the encryption target for this calendar. The active
        // primary key wins: encrypting to whichever key happened to be seen first mis-routes the
        // upload (the server rejects it with code 2001), and after a key rotation it encrypts new
        // events to a key Proton no longer treats as current. The ring is built before anything is
        // recorded, so a failure here leaves the next attempt free to try again.
        synchronized(goCalendarRings) {
            // Signed out while this key was unlocking. clearKeyRing drops the account ring first and
            // clears the calendar slots under this lock, so anything installed or recorded now would
            // outlive the account, and after a sign-in with the same name would pass as settled.
            if (!goKeyRings.containsKey(accountName)) {
                SyncLog.w(TAG, "Calendar key for $accountName cal=$calendarId finished unlocking after sign-out; discarded")
                return
            }
            val takes = calendarKeySelection.takesRing(keyId, slot, isPrimary, slotHasRing = goCalendarRings.containsKey(slot))
            if (takes) {
                goCalendarRings.put(slot, bridge.Bridge.newKeyRing(armoredCalendarKey, passphrase))?.close()
            }
            calendarKeySelection.recordUnlocked(keyId, slot, isPrimary, tookRing = takes)
            SyncLog.d(
                TAG,
                "Calendar key unlocked for $accountName cal=$calendarId primary=$isPrimary encryptionKey=$takes"
            )
        }
    }

    /**
     * Recover the session key from an existing SharedKeyPacket (a PKESK packet).
     *
     * GopenPGP opens the PKESK with the account's ring, which holds the user, address and
     * calendar keys, and uses whichever key the packet names.
     *
     * @param keyPacketBytes Raw binary PKESK packet bytes (base64-decoded SharedKeyPacket)
     * @param accountName    Account whose key ring to use
     * @return Pair of (symAlgId, sessionKeyBytes), or null if no key succeeded
     */
    fun recoverSessionKey(keyPacketBytes: ByteArray, accountName: String): Pair<Int, ByteArray>? {
        val ring = goKeyRings[accountName]
        if (ring == null) {
            SyncLog.w(TAG, "recoverSessionKey: no key ring for $accountName")
            return null
        }
        return try {
            val sessionKey = ring.decryptSessionKey(keyPacketBytes)
            // gomobile binds Go's int as Java long.
            val algorithmId = sessionKey.algorithmID().toInt()
            val keyBytes = sessionKey.bytes()
            SyncLog.d(TAG, "Session key recovered: algo=$algorithmId, len=${keyBytes.size}")
            Pair(algorithmId, keyBytes)
        } catch (e: Throwable) {
            SyncLog.w(TAG, "recoverSessionKey: no key in the ring opened the SharedKeyPacket for $accountName: ${e.message}")
            null
        }
    }

    /**
     * Encrypt and SIGN plaintext using a session key, producing a raw binary SEIPD packet.
     * Used for mail sending — the body must be signed inside the encrypted message.
     *
     * Structure: SEIPD { Compressed { OnePassSig + LiteralData + Signature } }
     *
     * @param plaintext  The text to encrypt and sign
     * @param symAlg     Symmetric algorithm ID (9=AES-256)
     * @param sessionKey The raw session key bytes
     * @param accountName Account for signing key lookup
     * @param senderEmail Sender's email for signing key lookup
     * @return Raw binary SEIPD packet bytes (base64-encode before sending)
     */
    fun encryptAndSignWithSessionKey(
        plaintext: String,
        symAlg: Int,
        sessionKey: ByteArray,
        accountName: String,
        senderEmail: String
    ): ByteArray {
        val emailKey = "$accountName:${senderEmail.lowercase()}"
        val signingRing = goAddressRings[emailKey]
            ?: goAddressRings[accountName]
            ?: throw IllegalStateException("No address signing key for $senderEmail")

        val sk = bridge.Bridge.newSessionKeyFromBytes(sessionKey, symAlg.toLong())
        val dataPacket = bridge.Bridge.encryptDataWithSessionKeyAndSign(sk, plaintext, signingRing)
        SyncLog.d(TAG, "encryptAndSignWithSessionKey: produced ${dataPacket.size} byte signed data packet (algo=$symAlg)")
        return dataPacket
    }

    /**
     * Wrap an EXISTING session key for each recipient public key: one key packet each, in order. An
     * update gives the guests it adds the event's key this way, as Proton's clients do; the event
     * keeps its key and its key packet.
     */
    fun wrapSessionKeyForRecipients(symAlg: Int, sessionKey: ByteArray, recipientArmoredPubKeys: List<String>): List<ByteArray> {
        val sk = bridge.Bridge.newSessionKeyFromBytes(sessionKey, symAlg.toLong())
        return recipientArmoredPubKeys.map { armored -> bridge.Bridge.newPublicKeyRing(armored).encryptSessionKey(sk) }
    }

    /**
     * Encrypt plaintext under an existing session key, producing the raw binary data packet.
     *
     * The result is NOT armored and NOT base64. Base64-encode it before putting it in the API's
     * DataPacket field.
     *
     * @param plaintext  The iCal string to encrypt
     * @param symAlg     Symmetric algorithm ID (7=AES-128, 8=AES-192, 9=AES-256)
     * @param sessionKey The raw session key bytes
     * @return Raw binary data packet bytes
     */
    fun encryptWithSessionKey(plaintext: String, symAlg: Int, sessionKey: ByteArray): ByteArray {
        val sk = bridge.Bridge.newSessionKeyFromBytes(sessionKey, symAlg.toLong())
        val dataPacket = bridge.Bridge.encryptDataWithSessionKey(sk, plaintext)
        SyncLog.d(TAG, "encryptWithSessionKey: produced ${dataPacket.size} byte data packet (algo=$symAlg)")
        return dataPacket
    }

    /**
     * Encrypt a string with the user's public key.
     */
    fun encrypt(accountName: String, plaintext: String): String {
        val ring = goUserRings[accountName]
            ?: throw IllegalStateException("User key not unlocked for $accountName")
        return encryptPgpMessage(plaintext, ring)
    }

    /**
     * Encrypt with the address key (for contacts — Proton uses address key ring).
     */
    fun encryptWithAddressKey(accountName: String, plaintext: String): String {
        val ring = goAddressRings[accountName]
            ?: throw IllegalStateException("Address key not unlocked for $accountName")
        return encryptPgpMessage(plaintext, ring)
    }

    /**
     * Encrypt and sign a string with the user's key pair.
     */
    fun encryptAndSign(accountName: String, plaintext: String): String {
        val ring = goUserRings[accountName]
            ?: throw IllegalStateException("User key not unlocked for $accountName")
        return encryptPgpMessage(plaintext, ring, ring)
    }

    /**
     * Encrypt and sign with the address key pair (for contacts).
     * Proton WebUI verifies contact signatures using address keys.
     */
    fun encryptAndSignWithAddressKey(accountName: String, plaintext: String): String {
        val ring = goAddressRings[accountName]
            ?: throw IllegalStateException("Address key not unlocked for $accountName")
        return encryptPgpMessage(plaintext, ring, ring)
    }

    /**
     * Encrypt (without signing) using a specific address email's key.
     */
    fun encryptForAddress(accountName: String, senderEmail: String, plaintext: String): String {
        val emailKey = "$accountName:${senderEmail.lowercase()}"
        val ring = goAddressRings[emailKey]
            ?: goAddressRings[accountName]
            ?: throw IllegalStateException("No address key for $senderEmail ($accountName)")
        SyncLog.d(TAG, "Encrypting (no sign) for address $senderEmail")
        return encryptPgpMessage(plaintext, ring)
    }

    /**
     * Encrypt data to both sender and recipient in a single PGP message.
     * Returns Triple(senderPKESK, recipientPKESK, SEIPD) — all sharing the same session key.
     * This avoids session key recovery/re-wrapping issues.
     */
    fun encryptForAddressAndRecipient(
        accountName: String,
        senderEmail: String,
        plaintext: String,
        recipientArmoredPubKey: String
    ): Triple<ByteArray, ByteArray, ByteArray> {
        val (senderPkesk, recipientPkesks, seipd) =
            encryptForAddressAndRecipients(accountName, senderEmail, plaintext, listOf(recipientArmoredPubKey))
        return Triple(senderPkesk, recipientPkesks.first(), seipd)
    }

    /**
     * Encrypt data to sender + multiple recipients in a single PGP message.
     * All PKESKs share the same session key. Returns sender PKESK, list of
     * recipient PKESKs (in same order as input), and the SEIPD.
     */
    fun encryptForAddressAndRecipients(
        accountName: String,
        senderEmail: String,
        plaintext: String,
        recipientArmoredPubKeys: List<String>
    ): Triple<ByteArray, List<ByteArray>, ByteArray> {
        val emailKey = "$accountName:${senderEmail.lowercase()}"
        val senderRing = goAddressRings[emailKey]
            ?: goAddressRings[accountName]
            ?: throw IllegalStateException("No address key for $senderEmail ($accountName)")

        // One session key, wrapped once per party. Recipient order is preserved because the
        // callers pair each PKESK back up with the attendee it belongs to.
        val sessionKey = bridge.Bridge.generateSessionKey()
        val seipd = bridge.Bridge.encryptDataWithSessionKey(sessionKey, plaintext)
        val senderPkesk = senderRing.encryptSessionKey(sessionKey)
        val recipientPkesks = recipientArmoredPubKeys.map { armored ->
            bridge.Bridge.newPublicKeyRing(armored).encryptSessionKey(sessionKey)
        }

        SyncLog.d(TAG, "encryptForAddressAndRecipients: senderPKESK=${senderPkesk.size}b, ${recipientPkesks.size} recipientPKESKs, SEIPD=${seipd.size}b")
        return Triple(senderPkesk, recipientPkesks, seipd)
    }

    /**
     * Encrypt to a RECIPIENT's public key and sign with sender's address key.
     * Returns Pair(keyPacketBytes, dataPacketBytes) — split PGP message.
     * Used for internal Proton recipients in mail sending (Type=1).
     */
    fun encryptToRecipientAndSign(
        accountName: String,
        senderEmail: String,
        plaintext: String,
        recipientArmoredPubKey: String
    ): Pair<ByteArray, ByteArray> {
        val emailKey = "$accountName:${senderEmail.lowercase()}"
        val signingRing = goAddressRings[emailKey]
            ?: goAddressRings[accountName]
            ?: throw IllegalStateException("No address key for $senderEmail")
        val recipientRing = bridge.Bridge.newPublicKeyRing(recipientArmoredPubKey)

        val message = recipientRing.encryptAndSign(plaintext, signingRing)
        val keyPacket = message.keyPacket()
        val dataPacket = message.dataPacket()
        if (keyPacket == null || keyPacket.isEmpty() || dataPacket == null || dataPacket.isEmpty()) {
            throw CryptoException("encryptToRecipientAndSign produced no key/data packet split")
        }
        SyncLog.d(TAG, "encryptToRecipientAndSign: PKESK=${keyPacket.size}b, SEIPD=${dataPacket.size}b")
        return Pair(keyPacket, dataPacket)
    }

    fun encryptAndSignForAddress(accountName: String, senderEmail: String, plaintext: String): String {
        val emailKey = "$accountName:${senderEmail.lowercase()}"
        val ring = goAddressRings[emailKey]
            ?: goAddressRings[accountName]
            ?: throw IllegalStateException("No address key for $senderEmail ($accountName)")
        SyncLog.d(TAG, "Encrypting and signing for address $senderEmail")
        return encryptPgpMessage(plaintext, ring, ring)
    }

    /**
     * Decrypt the session key from a PKESK packet using the address key ring.
     * Used for mail sending (encSplit pattern) where the PKESK was encrypted
     * to the sender's own address key.
     */
    fun decryptSessionKeyWithAddressKey(keyPacketBytes: ByteArray, dataPacketBytes: ByteArray, accountName: String, senderEmail: String): Pair<Int, ByteArray>? {
        // dataPacketBytes is unused now: BouncyCastle needed the whole message to parse a
        // PGPEncryptedDataList, GopenPGP works from the key packet alone. Kept in the signature
        // because the mail-sending call sites pass it and changing them is not this change's job.
        val ring = goKeyRings[accountName] ?: return null
        return try {
            val sessionKey = ring.decryptSessionKey(keyPacketBytes)
            val symAlg = sessionKey.algorithmID().toInt()
            val keyBytes = sessionKey.bytes()
            SyncLog.d(TAG, "Recovered mail session key: algo=$symAlg, len=${keyBytes.size}")
            Pair(symAlg, keyBytes)
        } catch (e: Throwable) {
            SyncLog.d(TAG, "No address key opened the mail PKESK for $senderEmail: ${e.message}")
            null
        }
    }

    /**
     * Create a detached PGP signature using the default address key.
     * Prefer [sign] with an explicit addressEmail for calendar events on
     * multi-address accounts — Proton verifies signatures against the
     * calendar-owner address key, not the account default.
     */
    fun sign(accountName: String, data: String): String =
        signWithAddressKey(accountName, null, data)

    /**
     * Create a detached PGP signature using the address key for [addressEmail].
     * Falls back to the account-default address key, then to the user key, when
     * the per-email key isn't cached.
     */
    fun sign(accountName: String, addressEmail: String?, data: String): String =
        signWithAddressKey(accountName, addressEmail, data)

    fun signWithAddressKey(accountName: String, data: String): String =
        signWithAddressKey(accountName, null, data)

    fun signWithAddressKey(accountName: String, addressEmail: String?, data: String): String {
        val emailKey = addressEmail?.lowercase()?.let { "$accountName:$it" }
        val ring = (emailKey?.let { goAddressRings[it] }) ?: goAddressRings[accountName]
        if (ring != null) {
            return createDetachedSignature(data, ring)
        }
        if (emailKey != null) {
            SyncLog.w(TAG, "signWithAddressKey: no address key cached for $emailKey, falling back to user key")
        }
        return signWithUserKey(accountName, data)
    }

    /**
     * Sign with the primary user key (used for contacts).
     */
    fun signWithUserKey(accountName: String, data: String, stripTrailingSpaces: Boolean = false): String {
        val ring = goUserRings[accountName]
            ?: throw IllegalStateException("User key not unlocked for $accountName")
        val signData = if (stripTrailingSpaces) {
            data.lines().joinToString("\n") { it.trimEnd() }
        } else data
        return createDetachedSignature(signData, ring)
    }

    /**
     * Encrypt a string with the SPECIFIC calendar's key (for uploading events).
     * Returns the armored PGP message.
     *
     * @param accountName The account name
     * @param calendarId  The Proton calendar ID being uploaded to. Required so we route to
     *                    the correct key on multi-calendar accounts.
     * @param plaintext   The plaintext to encrypt
     * @return Armored PGP message encrypted to that calendar's key
     */
    fun encryptWithCalendarKey(accountName: String, calendarId: String, plaintext: String): String {
        val ring = goCalendarRings["$accountName:cal:$calendarId"]
            ?: throw IllegalStateException("No calendar key ring for $accountName cal=$calendarId - ensure unlockCalendarKey was called for this calendar first")
        return encryptPgpMessage(plaintext, ring)
    }

    /**
     * Encrypt plaintext with the calendar key AND additional recipient public keys.
     * Returns Triple(calendarPKESK, List<recipientPKESK>, SEIPD) — all sharing the same session key.
     * Used for AddedProtonAttendees: each attendee gets an AddressKeyPacket that wraps
     * the shared event session key, enabling them to decrypt the SharedEventContent.
     */
    fun encryptWithCalendarKeyAndRecipients(
        accountName: String,
        calendarId: String,
        plaintext: String,
        recipientArmoredPubKeys: List<String>
    ): Triple<ByteArray, List<ByteArray>, ByteArray> {
        val calRing = goCalendarRings["$accountName:cal:$calendarId"]
            ?: throw IllegalStateException("No calendar key ring for $accountName cal=$calendarId - ensure unlockCalendarKey was called for this calendar first")

        // Generate the session key explicitly and wrap it once per party. This is what the format
        // actually is, so no packet splitting is needed: BouncyCastle could only encrypt to all
        // recipients at once and hand back one blob, which then had to be taken apart by walking
        // packet headers and computing tag/length boundaries to find each PKESK.
        val sessionKey = bridge.Bridge.generateSessionKey()
        val seipd = bridge.Bridge.encryptDataWithSessionKey(sessionKey, plaintext)
        val calPkesk = calRing.encryptSessionKey(sessionKey)
        val recipientPkesks = recipientArmoredPubKeys.map { armored ->
            bridge.Bridge.newPublicKeyRing(armored).encryptSessionKey(sessionKey)
        }

        SyncLog.d(TAG, "encryptWithCalendarKeyAndRecipients: calPKESK=${calPkesk.size}b, ${recipientPkesks.size} recipientPKESKs, SEIPD=${seipd.size}b")
        return Triple(calPkesk, recipientPkesks, seipd)
    }

    /**
     * Split an armored PGP message into its KeyPacket (PKESK) and DataPacket (SEIPD) parts.
     * Returns a Pair of (keyPacketBytes, dataPacketBytes).
     *
     * The PKESK packet(s) come first, then the SEIPD packet follows. GopenPGP parses the message
     * and finds the boundary.
     *
     * @throws CryptoException if the message does not hold both a key packet and a data packet
     */
    fun splitPgpMessage(armoredMessage: String): Pair<ByteArray, ByteArray> {
        // GopenPGP parses the packet stream and hands back the two halves Proton stores
        // separately. This replaced a hand-written packet walker that computed tag and length
        // boundaries itself to locate the SEIPD.
        val parsed = bridge.Bridge.parseArmoredMessage(armoredMessage)
        val keyPacket = parsed.keyPacket()
        val dataPacket = parsed.dataPacket()
        if (keyPacket == null || keyPacket.isEmpty() || dataPacket == null || dataPacket.isEmpty()) {
            throw CryptoException("Message did not split into a key packet and a data packet")
        }
        return Pair(keyPacket, dataPacket)
    }

    /**
     * Get all email addresses associated with an account (from address key rings).
     */
    fun getAddressEmails(accountName: String): Set<String> =
        goAddressEmails[accountName]?.toSet() ?: emptySet()

    /**
     * Drop every cached key for one account (sign-out).
     */
    fun clearKeyRing(accountName: String) {
        goKeyRings.remove(accountName)?.close()
        goUserRings.remove(accountName)?.close()
        goAddressEmails.remove(accountName)
        // The account-level slot deliberately aliases one of the per-email rings, so collect by
        // identity and close each distinct ring exactly once rather than closing the same one twice.
        val addressRings = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<bridge.KeyRing, Boolean>())
        goAddressRings.keys.filter { it == accountName || it.startsWith("$accountName:") }
            .forEach { key -> goAddressRings.remove(key)?.let { addressRings.add(it) } }
        addressRings.forEach { it.close() }
        // Under the lock unlockCalendarKey installs rings with, after the account ring is gone: an
        // unlock finishing concurrently either lands before this and is cleared, or sees no account
        // ring and discards itself.
        synchronized(goCalendarRings) {
            goCalendarRings.keys.filter { it.startsWith("$accountName:") }
                .forEach { goCalendarRings.remove(it)?.close() }
            calendarKeySelection.clearAccount(accountName)
        }
        goRingKeysAdded.removeAll { it.startsWith("$accountName:") }
        goAddressSigningRingsAdded.removeAll { it.startsWith("$accountName:") }
        SyncLog.d(TAG, "Cleared key rings for $accountName")
    }

    /**
     * Drop every cached key for every account.
     */
    fun clearAll() {
        goKeyRings.values.forEach { it.close() }
        goUserRings.values.forEach { it.close() }
        // As in clearKeyRing: account-level and per-email slots alias, so close by identity.
        val addressRings = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<bridge.KeyRing, Boolean>())
        addressRings.addAll(goAddressRings.values)
        addressRings.forEach { it.close() }
        goCalendarRings.values.forEach { it.close() }
        goKeyRings.clear(); goUserRings.clear(); goAddressRings.clear(); goCalendarRings.clear()
        goAddressEmails.clear(); calendarKeySelection.clear(); goRingKeysAdded.clear()
        goAddressSigningRingsAdded.clear()
        SyncLog.d(TAG, "Cleared all key rings")
    }

    // ---- Internal helpers ----

    /**
     * Derive the key password (mailbox password) from the user's password and key salt.
     *
     * IMPORTANT: This is different from SRP password hashing!
     * - No "proton" suffix on the salt
     * - Result is last 31 chars of bcrypt output (hash.slice(29))
     *
     * As Proton's own clients derive it:
     *   bcrypt(password, $2a$10$ + bcryptBase64(rawSalt, 16)) -> take last 31 chars
     */
    private fun deriveMailboxPassword(password: ByteArray, keySalt: ByteArray): CharArray {
        // Encode raw salt bytes to bcrypt's custom base64 (no "proton" suffix!)
        val encodedSalt = SrpCrypto.bcryptBase64Encode(keySalt.copyOf(16))
            .let { if (it.length > 22) it.substring(0, 22) else it }

        val rawSalt = SrpCrypto.bcryptBase64Decode(encodedSalt).copyOf(16)
        val hashResult = SrpCrypto.PROTON_BCRYPT // a password over 72 bytes counts by its first 72, as at Proton
            .hash(10, rawSalt, password)

        // Key password is last 31 chars: skip "$2a$10$" (7) + salt (22) = 29
        val hashStr = String(hashResult, Charsets.UTF_8)
        return hashStr.substring(29).toCharArray()
    }

    /**
     * Encrypt to one recipient ring, optionally signing with another.
     *
     * GopenPGP picks the right encryption subkey out of each key in the recipient ring, which this
     * class used to do by hand. Pass a SINGLE-key ring unless several recipients are genuinely
     * intended: one key packet is emitted per key.
     */
    private fun encryptPgpMessage(
        plaintext: String,
        recipients: bridge.KeyRing,
        signingRing: bridge.KeyRing? = null
    ): String {
        val message = if (signingRing != null) {
            recipients.encryptAndSign(plaintext, signingRing)
        } else {
            recipients.encrypt(plaintext)
        }
        return message.armored()
    }

    /** Create an armored detached signature over text. */
    private fun createDetachedSignature(data: String, signingRing: bridge.KeyRing): String =
        signingRing.signDetachedArmored(data)

    /**
     * Create a detached PGP signature in BINARY format (not armored).
     * Used for attachment uploads where the server expects raw binary.
     */
    fun createBinaryDetachedSignature(
        data: ByteArray,
        accountName: String,
        senderEmail: String
    ): ByteArray {
        val emailKey = "$accountName:${senderEmail.lowercase()}"
        val signingRing = goAddressRings[emailKey]
            ?: goAddressRings[accountName]
            ?: throw IllegalStateException("No address signing key for $senderEmail")
        return signingRing.signDetachedRaw(data)
    }

    companion object {
        private const val TAG = "KeyManager"
    }
}




/** Raised when an OpenPGP operation fails. Replaces BouncyCastle's PGPException. */
class CryptoException(message: String) : Exception(message)


