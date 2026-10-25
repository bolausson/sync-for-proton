package me.proton.sync.contacts

import android.accounts.Account
import ezvcard.Ezvcard
import ezvcard.VCard
import me.proton.sync.SyncLog
import me.proton.sync.SyncUtils
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.models.ContactCard
import me.proton.sync.api.models.UpdateContactRequest
import me.proton.sync.api.protonErrorSuffix
import me.proton.sync.crypto.ContactDecryptor
import me.proton.sync.crypto.KeyManager
import me.proton.sync.db.daos.ContactSyncDao
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Finds and removes structured names that Android invented and an earlier upload sent to Proton.
 *
 * Android's contacts provider completes any name it is given. A Proton contact called
 * `someone@example.com` with no structured name came back from the provider as
 * given=`someone@example`, family=`com`, and the upload wrote that to Proton as a real `N`. On real
 * accounts, contacts had already acquired a surname that was really a domain suffix.
 *
 * [ContactsSyncAdapter.stripInventedName] stops it happening again. This cleans up what already
 * happened, and it is the only part of the fix that writes to Proton, so it never runs on its own.
 */
class ContactNameRepair(
    private val apiClient: ProtonApiClient,
    private val contactDecryptor: ContactDecryptor,
    private val sessionManager: AccountSessionManager,
    private val keyManager: KeyManager,
) {

    /** One contact whose surname was manufactured from its email address. */
    data class Finding(
        val protonId: String,
        val displayName: String,
        val given: String,
        val family: String,
    ) {
        /** How the invented name reads, for the confirmation list. */
        val inventedName: String get() = "$given / $family"
    }

    data class Outcome(val repaired: Int, val failed: Int)

    /**
     * Reads every contact and reports the ones carrying a manufactured name. Writes nothing.
     *
     * The test is deliberately narrow. A contact qualifies only when its display name is an email
     * address and its first and last name reassemble into exactly that address, which is the
     * signature of the provider having split it. Anything a person typed fails that test, so a real
     * name is never mistaken for an invented one.
     */
    suspend fun scan(account: Account, onProgress: suspend (Int, Int) -> Unit): List<Finding> {
        unlock(account)
        val cards = fetchAllCards(account.name, allContactIds(account.name))
        val findings = mutableListOf<Finding>()
        var seen = 0
        for ((protonId, list) in cards) {
            coroutineContext.ensureActive()
            seen++
            onProgress(seen, cards.size)
            val vCard = decode(account.name, list)
            if (vCard == null) {
                // The same partial-read trap fetchAllCards guards against, one layer down. Skipping
                // an unreadable card and then reporting a count made "no invented names found" mean
                // "none among the ones that happened to decrypt", which reads as a clean bill of
                // health for an account that was never fully examined.
                throw Incomplete("Contact $protonId could not be decrypted; the scan cannot say the account is clean")
            }
            val finding = inventedName(protonId, vCard)
            if (finding != null) findings += finding
        }
        SyncLog.i(TAG, "Name repair scan for ${account.name}: ${findings.size} of ${cards.size} carry an invented name")
        return findings
    }

    /**
     * Re-uploads each contact with its structured name removed and everything else untouched.
     *
     * @param dao updated for every contact repaired. Without that the mapping still says the server
     *   has a structured name, `stripInventedName` bails on its first line, and the next local edit
     *   sends the invented name straight back: the repair would quietly undo itself.
     */
    suspend fun repair(account: Account, findings: List<Finding>, dao: ContactSyncDao): Outcome {
        unlock(account)
        var repaired = 0
        var failed = 0
        for (finding in findings) {
            coroutineContext.ensureActive()
            try {
                val detail = apiClient.getContact(account.name, finding.protonId).body()?.contact
                val vCard = detail?.cards?.let { decode(account.name, it) }
                if (vCard == null || inventedName(finding.protonId, vCard) == null) {
                    // Changed under us since the scan; leave it alone rather than guess.
                    failed++
                    continue
                }
                vCard.structuredName = null
                // The repair changes the name only: the card keeps the UID Proton stores for it.
                vCard.uid = ezvcard.property.Uid(uploadUidFor(detail?.uid, vCard.uid?.value))
                val cards = try {
                    contactDecryptor.encryptContactCards(account.name, Ezvcard.write(vCard).go())
                } catch (e: ContactDecryptor.EncryptionUnavailable) {
                    // Counted as left alone rather than uploaded unencrypted. This runs over many
                    // contacts at once, from a tool that promises only to remove a name.
                    SyncLog.w(TAG, "Not repairing ${finding.protonId} unencrypted: ${e.message}")
                    failed++
                    continue
                }
                val response = apiClient.updateContact(
                    account.name, finding.protonId, UpdateContactRequest(cards = cards)
                )
                if (response.isSuccessful) {
                    repaired++
                    // The server no longer has a structured name for this contact, so the record
                    // has to say so or the upload guard will not protect it.
                    dao.getByProtonId(account.name, finding.protonId)?.let { row ->
                        dao.upsert(
                            row.copy(serverFn = vCard.formattedName?.value, serverHasN = 0)
                        )
                    }
                } else {
                    // The Code only: a refused card write can quote the card.
                    SyncLog.w(
                        TAG,
                        "Name repair update refused for ${finding.protonId}: HTTP ${response.code()}${response.protonErrorSuffix(withError = false)}"
                    )
                    failed++
                }
            } catch (e: Exception) {
                SyncLog.w(TAG, "Name repair failed for ${finding.protonId}: ${e.message}")
                failed++
            }
        }
        SyncLog.i(TAG, "Name repair for ${account.name}: $repaired repaired, $failed left alone")
        return Outcome(repaired, failed)
    }

    private fun unlock(account: Account) {
        SyncUtils.ensureKeysUnlocked(
            account, sessionManager.getSession(account), keyManager, apiClient, TAG
        )
    }

    private fun decode(accountName: String, cards: List<ContactCard>): VCard? = try {
        val merged = contactDecryptor.decryptAndMergeCards(accountName, cards)
        Ezvcard.parse(merged.replace("base64\\,", "base64,")).first()
    } catch (e: Exception) {
        SyncLog.d(TAG, "Name repair could not read a card: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Every contact's cards, or an exception.
     *
     * A page that fails used to break the loop and return what had been collected so far, and the
     * scan then reported "no contact has a name Android invented" from a partial read: the most
     * reassuring answer the tool can give, produced by not having looked. Contacts the bulk export
     * skips are fetched one at a time, the same gap the backup builder covers, so a scan that
     * returns at all has really seen the whole account.
     */
    private suspend fun fetchAllCards(accountName: String, expected: Set<String>): Map<String, List<ContactCard>> {
        val byId = LinkedHashMap<String, List<ContactCard>>()
        var page = 0
        while (true) {
            coroutineContext.ensureActive()
            val response = try {
                apiClient.exportContacts(accountName, page, EXPORT_PAGE)
            } catch (e: Exception) {
                throw Incomplete("could not read your contacts (${e.javaClass.simpleName})")
            }
            if (!response.isSuccessful) throw Incomplete("Proton returned HTTP ${response.code()}${response.protonErrorSuffix(withError = false)}")
            val details = response.body()?.contacts ?: emptyList()
            details.forEach { byId[it.id] = it.cards }
            if (details.size < EXPORT_PAGE) break
            page++
        }
        val missed = expected.filter { it !in byId }
        for (id in missed) {
            coroutineContext.ensureActive()
            try {
                apiClient.getContact(accountName, id).body()?.contact?.let { byId[id] = it.cards }
            } catch (e: Exception) {
                throw Incomplete("contact $id could not be read (${e.javaClass.simpleName})")
            }
        }
        val stillMissing = expected.count { it !in byId }
        if (stillMissing > 0) throw Incomplete("$stillMissing contacts could not be read")
        return byId
    }

    /** Thrown rather than reporting a clean bill of health from a partial read. */
    class Incomplete(message: String) : Exception(message)

    private suspend fun allContactIds(accountName: String): Set<String> {
        val ids = LinkedHashSet<String>()
        var page = 0
        while (true) {
            coroutineContext.ensureActive()
            val response = apiClient.getContacts(accountName, page, HEADER_PAGE)
            if (!response.isSuccessful) throw Incomplete("Proton returned HTTP ${response.code()}${response.protonErrorSuffix(withError = false)}")
            val metas = response.body()?.contacts ?: emptyList()
            metas.forEach { ids.add(it.id) }
            if (metas.size < HEADER_PAGE) break
            page++
        }
        return ids
    }

    companion object {
        private const val TAG = "ContactNameRepair"
        private const val EXPORT_PAGE = 50
        private const val HEADER_PAGE = 100

            /**
             * Whether this card's structured name is Android's split of its own display name.
             *
             * Pure, and deliberately narrow: it fires only when the display name is an email
             * address and the given and family names put back together spell exactly that address.
             * A name somebody typed does not reassemble into their address, so it survives. A false
             * positive here would strip a real name off a real contact.
             */
            internal fun inventedName(protonId: String, vCard: VCard): Finding? {
            val fn = vCard.formattedName?.value?.trim().orEmpty()
            if (!fn.contains("@")) return null
            val n = vCard.structuredName ?: return null
            val given = n.given?.trim().orEmpty()
            val family = n.family?.trim().orEmpty()
            if (given.isEmpty() || family.isEmpty()) return null
            // Anything beyond a given and a family is not the provider's split of an address.
            val extras = n.additionalNames.orEmpty() + n.prefixes.orEmpty() + n.suffixes.orEmpty()
            if (extras.any { it.isNotBlank() }) return null
            val rebuiltWithDot = "$given.$family"
            val rebuiltWithSpace = "$given $family"
            val matches = rebuiltWithDot.equals(fn, ignoreCase = true) ||
                rebuiltWithSpace.equals(fn, ignoreCase = true)
            return if (matches) Finding(protonId, fn, given, family) else null
        }
    }
}
