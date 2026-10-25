package me.proton.sync.backup

import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.property.RawProperty
import me.proton.sync.SyncLog
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.protonErrorSuffix
import me.proton.sync.api.models.ContactCard
import me.proton.sync.crypto.ContactDecryptor

/**
 * Read-only exporter: fetches a Proton account's contacts straight from the server,
 * decrypts every card, and serializes them into a single multi-vCard `.vcf` string.
 *
 * Reuses the exact production path: the metadata list ([ProtonApiClient.getContacts])
 * for the authoritative ID set, the bulk export ([ProtonApiClient.exportContacts]) for
 * the card payloads, and an **individual-fetch fallback** ([ProtonApiClient.getContact])
 * for any contact the export endpoint misses. Without it the backup would
 * silently drop contacts. Decryption is [ContactDecryptor.decryptAndMergeCards];
 * the same `base64\,`→`base64,` data-URI unescape the sync uses is applied before parse.
 *
 * Photos are kept at full resolution as decrypted (no Android display-resize) for a
 * faithful backup.
 */
class ContactsBackupBuilder(
    private val apiClient: ProtonApiClient,
    private val contactDecryptor: ContactDecryptor,
) {
    data class VcfResult(val vcf: String, val count: Int, val attempted: Int, val fetchFailed: Boolean)

    suspend fun buildVcf(accountName: String): VcfResult {
        var fetchFailed = false
        // 1) Authoritative ID set from the metadata list.
        val ids = LinkedHashSet<String>()
        var page = 0
        while (true) {
            val r = try {
                apiClient.getContacts(accountName, page, 100)
            } catch (e: Exception) {
                SyncLog.w(TAG, "getContacts page $page failed: ${e.message}"); fetchFailed = true; break
            }
            if (!r.isSuccessful) {
                SyncLog.w(TAG, "getContacts page $page: HTTP ${r.code()}${r.protonErrorSuffix()}"); fetchFailed = true; break
            }
            val metas = r.body()?.contacts ?: emptyList()
            metas.forEach { ids.add(it.id) }
            if (metas.size < 100) break
            page++
        }

        // 2) Bulk export → card payloads, keyed by id.
        val cardsById = HashMap<String, List<ContactCard>>()
        var exportPage = 0
        while (true) {
            val r = try {
                apiClient.exportContacts(accountName, exportPage, 50)
            } catch (e: Exception) {
                SyncLog.w(TAG, "exportContacts page $exportPage failed: ${e.message}"); fetchFailed = true; break
            }
            if (!r.isSuccessful) {
                SyncLog.w(TAG, "exportContacts page $exportPage: HTTP ${r.code()}${r.protonErrorSuffix()}"); fetchFailed = true; break
            }
            val details = r.body()?.contacts ?: emptyList()
            details.forEach { cardsById[it.id] = it.cards }
            if (details.size < 50) break
            exportPage++
            if (ids.isNotEmpty() && cardsById.keys.containsAll(ids)) break
        }

        // 3) Individual-fetch fallback for any IDs the export missed.
        val missed = ids.filter { it !in cardsById }
        if (missed.isNotEmpty()) {
            SyncLog.i(TAG, "Export missed ${missed.size} contact(s); fetching individually for backup")
            for (id in missed) {
                try {
                    apiClient.getContact(accountName, id).body()?.contact?.let { cardsById[id] = it.cards }
                } catch (e: Exception) {
                    SyncLog.d(TAG, "getContact $id failed: ${e.message}")
                }
            }
        }

        // If the metadata list hiccuped but export returned data, fall back to export's ids.
        val orderedIds = if (ids.isEmpty()) cardsById.keys.toList() else ids.toList()

        // 4) Decrypt → parse → collect. Ids travel with the cards so a later failure can name
        // the contact without logging anybody's name.
        val vcards = ArrayList<Pair<String, VCard>>(orderedIds.size)
        for (id in orderedIds) {
            val cards = cardsById[id] ?: continue
            try {
                val merged = contactDecryptor.decryptAndMergeCards(accountName, cards)
                val fixed = merged.replace("base64\\,", "base64,")
                val parsed = Ezvcard.parse(fixed).all()
                if (parsed.isNotEmpty()) vcards.add(id to parsed.first())
            } catch (e: Exception) {
                SyncLog.d(TAG, "Skipping undecryptable contact $id in backup: ${e.javaClass.simpleName}")
            }
        }

        val (vcf, unwritable) = writeVcf(vcards)
        val failed = orderedIds.size - vcards.size + unwritable
        val written = vcards.size - unwritable
        if (failed > 0 || fetchFailed) {
            SyncLog.w(TAG, "Backup INCOMPLETE: contacts $written/${orderedIds.size} backed up " +
                "($failed left out, $unwritable of them unwritable, fetchFailed=$fetchFailed) - " +
                "locked keys, a network error, or a card ez-vcard refuses to write")
        } else {
            SyncLog.i(TAG, "Backup: contacts → $written vCard(s)")
        }
        return VcfResult(vcf, written, orderedIds.size, fetchFailed)
    }

    /**
     * Serialises the cards, and never lets one bad card cost the user all the others.
     *
     * ez-vcard validates on WRITE what it does not validate on READ. `VObjectWriter.validate`
     * throws IllegalArgumentException for a property name holding a character it dislikes or
     * starting with whitespace, for a group name the same, for a parameter with a null name, and
     * for a parameter value containing an invalid character. Its reader checks none of that, so a
     * card Proton stores and that parses perfectly here can still throw on the way out.
     *
     * That throw used to escape buildVcf, escape runBackup and land in the worker's catch-all,
     * which recorded the four words "Backup failed: IllegalArgumentException". Every scheduled
     * backup for that account then failed, deterministically, and took the CALENDARS down with it,
     * because nothing is written to disk until every builder has finished. A user reported exactly
     * that, and the message that would have named the offending
     * property had already been thrown away.
     *
     * The batch write stays the fast path: one writer, one pass, unchanged for everyone whose
     * contacts are fine. Only when it throws do we write card by card, which costs a second pass
     * but isolates the offender, names its id in the log, and lets the rest through. The skipped
     * count feeds `incomplete`, so a partial run is reported as partial instead of passing for whole.
     */
    private fun writeVcf(cards: List<Pair<String, VCard>>): Pair<String, Int> {
        if (cards.isEmpty()) return "" to 0
        try {
            return Ezvcard.write(cards.map { it.second }).go() to 0
        } catch (e: Exception) {
            SyncLog.w(TAG, "vCard write failed for the whole set (${e.javaClass.simpleName}: ${e.message}); " +
                "retrying one contact at a time to find the bad card")
        }
        val sb = StringBuilder()
        var skipped = 0
        for ((id, card) in cards) {
            try {
                sb.append(Ezvcard.write(card).go())
                continue
            } catch (e: Exception) {
                SyncLog.d(TAG, "Contact $id will not write as-is (${e.javaClass.simpleName}: ${e.message}); repairing")
            }
            // Second chance before giving up on somebody's contact. Dropping the card would lose a
            // name, a number and an address to save a property nobody can read anyway.
            val repairs = VCardRepair.repair(card)
            try {
                sb.append(Ezvcard.write(card).go())
                SyncLog.w(TAG, "Contact $id needed $repairs property name repair(s) to be written; " +
                    "the contact is backed up, the offending property name is not verbatim")
            } catch (e: Exception) {
                skipped++
                SyncLog.w(TAG, "Contact $id cannot be written as a vCard even after $repairs repair(s) " +
                    "and was left out of the backup: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return sb.toString() to skipped
    }

    companion object {
        private const val TAG = "ContactsBackup"
    }
}
