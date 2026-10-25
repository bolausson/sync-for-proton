package me.proton.sync.contacts

import android.accounts.Account
import android.accounts.AccountManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.os.Bundle
import android.provider.ContactsContract
import me.proton.sync.SyncConstants
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.SyncTriggerType
import me.proton.sync.SyncUtils
import androidx.core.app.NotificationCompat
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.property.StructuredName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncNotificationHelper
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.api.AuthInterceptor
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.protonErrorSuffix
import me.proton.sync.api.models.*
import me.proton.sync.crypto.ContactDecryptor
import me.proton.sync.crypto.KeyManager
import me.proton.sync.db.daos.ContactSyncDao
import me.proton.sync.db.entities.ContactSyncEntity

/**
 * Two-way contacts sync adapter for Proton Contacts.
 *
 * ## Sync Flow (Remote → Local)
 * 1. **Fetch contact headers** — paginated list with ModifyTime for incremental detection
 * 2. **Bulk export** — GET contacts/v4/export (50 per page) with fallback to individual GET
 * 3. **Decrypt & map** — ContactDecryptor merges card types 0/1/2/3 → ContactsMapper
 * 4. **Group sync** — Proton labels (Type=2) → Android ContactsContract.Groups
 * 5. **Orphan cleanup** — remove local contacts with no server mapping
 *
 * ## Sync Flow (Local → Remote)
 * 6. **Detect dirty contacts** — DIRTY=1 in ContactsContract
 * 7. **Build vCard** — ContactDecryptor.encryptContactCards() splits into Type 0/2/3
 * 8. **Batch upload** — max 10 contacts per createContacts API call
 * 9. **Conflict resolution** — notification with local/remote/skip options
 *
 * ## Card Type Split (as Proton's web client splits a contact)
 * - Type 0 (cleartext): VERSION, PRODID, CATEGORIES (only when groups exist)
 * - Type 2 (signed): FN, EMAIL, UID, KEY, X-PM-* crypto preferences
 * - Type 3 (encrypted+signed): N, TEL, ADR, BDAY, ORG, NOTE, PHOTO, etc.
 *
 * @see ContactDecryptor for card encryption/decryption and type splitting
 * @see ContactsMapper for vCard → Android ContactsContract mapping
 */
class ContactsSyncAdapter(
    context: Context,
    autoInitialize: Boolean,
    private val apiClient: ProtonApiClient,
    private val sessionManager: AccountSessionManager,
    private val contactDecryptor: ContactDecryptor,
    private val contactSyncDao: ContactSyncDao,
    private val keyManager: KeyManager
) : AbstractThreadedSyncAdapter(context, autoInitialize, false) {

    companion object {
        /** [downloadTargetFor]: insert a new raw contact rather than write into a mapped one. */
        private const val NEW_ROW = -1L



        /**
         * What to store for a name part, so the backfill can tell "not looked at" from "looked at,
         * and there was nothing there".
         *
         * Null means unknown and keeps the backfill trigger armed. A card can carry an N with
         * neither a given nor a family, holding only a prefix or a suffix, and storing null for
         * both then left the trigger permanently true: every sync re-fetched and re-decrypted
         * those contacts for ever without being able to record anything that would stop it.
         * An empty string says the card was read and the part was absent.
         */
        internal fun recordedNamePart(vCard: VCard, value: String?): String? = when {
            value != null -> value
            vCard.structuredName != null -> ""
            else -> null
        }

        /**
         * Proton's display name when the phone's is only Android's shortening of it, else null.
         *
         * Split out from [preserveServerDisplayName] so the decision can be exercised directly;
         * every condition here exists to keep a name somebody typed from being reverted.
         */
        internal fun serverDisplayNameToRestore(
            serverHasN: Int?,
            serverFn: String?,
            serverGiven: String?,
            serverFamily: String?,
            localFn: String?,
            given: String?,
            family: String?,
            extraParts: List<String>,
        ): String? {
            // Contacts Proton has no structured name for belong to stripInventedName.
            if (serverHasN != 1) return null
            val server = serverFn?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val local = localFn?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (local == server) return null
            // The provider does not always derive the display name from the parts: given a name it
            // cannot parse it keeps the whole string and re-spaces the punctuation, so
            // "Jana.beispiel@example.org" comes back as
            // "Jana beispiel@example. org" while given and family stay untouched. Folded
            // to letters and digits the two are identical, and identical letters in identical order
            // is not something a person edits a name into. Proton's punctuation wins.
            // Case is PRESERVED in this comparison. The provider re-spaces punctuation; it never
            // changes a letter's case. So folding case away meant a correction that fixed the
            // capitalisation AND happened to sit on a name the provider had re-spaced looked like
            // pure punctuation and was reverted forever. Respecting case makes the two causes
            // distinguishable, and makes the extra "differs by more than case" clause that used to
            // sit here unnecessary.
            if (foldPreservingCase(local) == foldPreservingCase(server)) {
                return server
            }
            // Null on rows written before the columns existed. Not knowing what Proton held is a
            // reason to send what the phone has, not to overwrite it with a guess.
            if (serverGiven == null && serverFamily == null) return null

            val g = given.orEmpty().trim()
            val f = family.orEmpty().trim()
            if (g != serverGiven.orEmpty().trim()) return null
            if (f != serverFamily.orEmpty().trim()) return null
            // A title or suffix the user typed is real content Proton's FN does not have, so the
            // card goes up as it is rather than reverting to a name predating the addition.
            if (extraParts.any { it.isNotBlank() }) return null
            // The clincher: the display name is exactly what Android builds from the two parts.
            // Anything else means somebody typed it, and a typed name is never overwritten.
            val derived = listOf(g, f).filter { it.isNotEmpty() }.joinToString(" ")
            if (local != derived) return null
            return server
        }

        /**
         * Whether a display name on a Proton contact with NO structured name is Android's own
         * doing, rather than something a person typed.
         *
         * Pulled out of [stripInventedName] and made internal for the same reason
         * [serverDisplayNameToRestore] is: the two are siblings deciding the same question for the
         * two halves of the address book, and the last time only one of them was fixed, the bug the
         * fix was written for stayed live on the other half for a whole release. A sibling that
         * cannot be tested is how that happens twice.
         *
         * @param rebuilt given and family rejoined with a single space, which is what the provider
         *   composes a display name from.
         */
        internal fun isAndroidsOwnDerivation(
            localFn: String,
            serverFn: String,
            rebuilt: String,
        ): Boolean = localFn == serverFn ||
            // Case preserved, exactly as in the sibling: the provider re-spaces punctuation and
            // never touches case, so a difference in case is somebody typing. Folding it away made
            // a user fixing "jane doe" to "Jane Doe" look like a derivation, and the
            // correction was deleted and the lower-case name put back on every sync.
            (rebuilt.isNotEmpty() && foldPreservingCase(rebuilt) == foldPreservingCase(serverFn))

        /** Letters and digits only, single-spaced: how the provider's split mangles punctuation. */
        internal fun foldPreservingCase(value: String): String =
            value.map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ")

        /** As above, and case-blind. Kept for callers that genuinely want to ignore case. */
        internal fun foldForComparison(value: String): String = foldPreservingCase(value).lowercase()

        private const val TAG = "ContactsSync"
        private const val PAGE_SIZE = 100
        private const val PARALLEL_FETCHES = 5
        private const val NOTIFICATION_ID_CONFLICT_BASE = 20000

        /** ez-vcard escapes the comma in a data URI; the readers all undo it. */
        private val ESCAPED_BASE64 = "base64" + "\\" + ","
        /**
         * Samsung's ContactsProvider flips DIRTY=1 on rows we insert even through the
         * sync-adapter URI, and the resulting change notification makes the framework
         * auto-queue one more sync right after ours finishes (~60-90ms later in the field).
         * That follow-up re-fetches every contact header from Proton and finds nothing to do.
         *
         * Remember when each account last finished successfully, plus a fingerprint of the
         * local state that sync would have pushed, so a non-manual sync arriving inside this
         * window against unchanged local state can be skipped. Both are in-memory on purpose:
         * the echo always lands in the same process, and after a restart the debounce simply
         * does not apply, which is the safe direction to fail.
         */
        private const val FOLLOW_UP_SYNC_WINDOW_MS = 30_000L
        private val lastCompletedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val lastUploadSignature = java.util.concurrent.ConcurrentHashMap<String, String>()

        // Proton's system label names, which a contact group may not take (WebClients
        // FORBIDDEN_LABEL_NAMES). Internal rather than private so the filter preview screens out
        // the same system groups the upload path does; two copies of this list would eventually
        // disagree about whether "Starred" is a group a rule can match.
        internal val FORBIDDEN_GROUP_NAMES = setOf(
            "inbox", "drafts", "sent", "starred", "archive", "spam", "trash", "outbox"
        )

        /** Resolve a contact's Proton label ids to the Android group rows they were written as. */
        internal fun resolveAndroidGroupIds(
            contactMeta: ContactMeta,
            labelMap: Map<String, Long>
        ): List<Long> {
            // Resolve contact's Proton label IDs to Android group IDs
            val androidGroupIds = contactMeta.labelIds
                ?.mapNotNull { labelMap[it] }
                ?: emptyList()
            // Verbose-only diagnostic: log when Proton has labels for this
            // contact but we couldn't resolve them all. Shouldn't happen in
            // steady state (syncContactLabels runs first, populates labelMap).
            // If it does, the contact's GroupMembership rows get rewritten
            // with fewer (or zero) groups, the prime suspect when groups
            // turn up empty after a sync.
            val protonLabelCount = contactMeta.labelIds?.size ?: 0
            if (protonLabelCount > 0 && androidGroupIds.size < protonLabelCount &&
                SyncLog.isVerboseEnabled()) {
                val unresolved = contactMeta.labelIds!!.filter { it !in labelMap }
                SyncLog.d(TAG, "Contact ${contactMeta.id}: ${unresolved.size}/${protonLabelCount} Proton labels missing from local labelMap (unresolved=$unresolved); GroupMembership rows will be incomplete")
            }
            return androidGroupIds
        }
    }

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        // Off means off, wherever the request came from, and BEFORE the ongoing notification or
        // any result is recorded. requestSync ignores setSyncAutomatically and a dozen places ask
        // for a sync, so the adapter is the only place this can be settled once. It sits here
        // rather than inside the sync itself because returning from there left err == null, and
        // savePostSyncResult then wrote a lastSync timestamp and cleared the failure notice for a
        // sync that never ran: the status banner would claim everything was up to date, above two
        // counts of zero.
        if (!ContentResolver.getSyncAutomatically(account, SyncConstants.AUTHORITY_CONTACTS)) {
            SyncLog.i(TAG, "Contact sync is switched off for ${account.name}; not syncing")
            return
        }

        // Resolve parent account up front so the ongoing-progress notification groups
        // contacts + calendar under one logical account regardless of which adapter
        // started first. Falls back to the address-book account name if no parent.
        val parentForNotif = AccountManager.get(context)
            .getUserData(account, SyncConstants.EXTRA_PARENT_ACCOUNT_NAME) ?: account.name
        SyncNotificationHelper.showOngoing(context, parentForNotif, "contacts")
        try {
            SyncUtils.withSyncLock("contacts", account.name, TAG) {
                doPerformSync(account, extras, authority, provider, syncResult)
            }
        } finally {
            SyncNotificationHelper.clearOngoing(context, parentForNotif, "contacts")
        }
    }

    private fun doPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        val triggerType = SyncTriggerType.fromExtras(extras)
        SyncLog.i(TAG, "Starting contacts sync for ${account.name} (${triggerType.label})")

        // The sync adapter receives the address book account, but the API session
        // is stored on the main account. Look up the parent account.
        val parentAccountName = AccountManager.get(context).getUserData(account, SyncConstants.EXTRA_PARENT_ACCOUNT_NAME)
            ?: account.name
        val parentAccount = Account(parentAccountName, ProtonSyncApp.ACCOUNT_TYPE)

        val isManual = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)
        when (val pf = SyncUtils.runSyncPreflight(
            context = context,
            parentAccount = parentAccount,
            sessionManager = sessionManager,
            keyManager = keyManager,
            apiClient = apiClient,
            isManual = isManual,
            syncResult = syncResult,
            syncTypeLabel = "Contacts",
            tag = TAG
        )) {
            is SyncUtils.PreflightResult.Abort -> return
            is SyncUtils.PreflightResult.Proceed -> { /* continue below */ }
        }

        if (!isManual && isRedundantFollowUp(account, provider)) return

        // Run sync uninterruptibly — framework thread interrupts must not cancel API calls
        val err = SyncUtils.runUninterruptible {
            AuthInterceptor.withAccount(parentAccountName) {
                performContactsSync(account, parentAccountName, provider, syncResult, triggerType)
            }
        }

        if (err == null) {
            val key = "${account.type}:${account.name}"
            // Snapshot first: an unreadable signature must leave no stale entry behind, or the
            // next echo would compare against a fingerprint from an older run.
            val signature = localUploadSignature(account, provider)
            if (signature != null) lastUploadSignature[key] = signature else lastUploadSignature.remove(key)
            lastCompletedAt[key] = System.currentTimeMillis()
        }

        SyncUtils.savePostSyncResult(
            context = context,
            parentAccountName = parentAccountName,
            syncType = "contacts",
            syncTypeLabel = "Contacts",
            err = err,
            syncResult = syncResult,
            tag = TAG
        )
    }

    /**
     * True when this looks like the provider's own echo of the sync we just finished:
     * scheduled (not user-requested), landed inside [FOLLOW_UP_SYNC_WINDOW_MS] of a successful
     * run, and the local state that sync would push is byte-for-byte what it was when that run
     * ended. Skipping it saves a full contact-header fetch per sync on devices whose provider
     * re-dirties sync-adapter writes.
     *
     * Anything the user actually asked for always runs, as does anything whose local state moved.
     */
    private fun isRedundantFollowUp(account: Account, provider: ContentProviderClient): Boolean {
        val key = "${account.type}:${account.name}"
        val last = lastCompletedAt[key] ?: return false
        val sinceMs = System.currentTimeMillis() - last
        if (sinceMs !in 0 until FOLLOW_UP_SYNC_WINDOW_MS) return false

        val previous = lastUploadSignature[key] ?: return false
        // Fail open: if the state cannot be read, run the sync rather than risk dropping changes.
        val current = localUploadSignature(account, provider) ?: return false
        if (current != previous) {
            SyncLog.d(TAG, "Local state changed since the last sync, not skipping. was=[$previous] now=[$current]")
            return false
        }

        SyncLog.i(TAG, "Skipping scheduled sync ${sinceMs}ms after the last one (no local changes pending)")
        return true
    }

    /**
     * Fingerprint of everything in the provider a sync would push upward. Null if it cannot be
     * read, which callers must treat as "run the sync".
     *
     * Compares as a *set*, not a count, because both halves have a trap:
     *
     * - Dirty raw contacts: [uploadDirtyContacts] deliberately leaves DIRTY=1 on rows the user
     *   answered "Skip" for, and that decision is persisted, so a count would stay non-zero
     *   forever and silently disable the debounce for the account. The same row staying dirty
     *   leaves the signature unchanged, which is the behaviour we want.
     * - Group titles: deleting a group sets DIRTY on no raw contact at all (see
     *   [markContactsDirtyForDeletedGroups]), so group work is invisible to the dirty set and
     *   needs its own channel. Live titles rather than tombstones, because nothing ever hard-
     *   deletes a Groups row, so counting tombstones would pin this non-zero permanently.
     */
    private fun localUploadSignature(account: Account, provider: ContentProviderClient): String? {
        return try {
            val dirtyIds = StringBuilder()
            provider.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.DIRTY} = 1 AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf(account.name, account.type),
                "${ContactsContract.RawContacts._ID} ASC"
            )?.use { c ->
                while (c.moveToNext()) dirtyIds.append(c.getLong(0)).append(',')
            } ?: return null

            val titles = sortedSetOf<String>()
            provider.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups.TITLE),
                "${ContactsContract.Groups.DELETED} = 0 AND " +
                    "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.Groups.ACCOUNT_TYPE} = ?",
                arrayOf(account.name, account.type),
                null
            )?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { titles.add(it) }
            } ?: return null

            "$dirtyIds|${titles.joinToString(",")}"
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not read local state, running sync anyway: ${e.message}")
            null
        }
    }

    private suspend fun performContactsSync(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
        triggerType: SyncTriggerType
    ) {
        // Fetch Proton contact labels/groups and sync to Android groups
        val contactLabels = syncContactLabels(parentAccountName, account, provider)
        val labelMap = contactLabels.groupRowIds

        // Contact filter. Verdicts are acted on: contacts the rules exclude are not downloaded or
        // uploaded, and removing excluded ones already on the phone waits for the user's
        // confirmation. When no rules are configured it runs the built-in presets as a log-only
        // probe, so a single verbose sync can report how much of this account looks auto-created
        // by Proton.
        val filterSession = me.proton.sync.contacts.filter.ContactFilterSession.create(
            context = context,
            accountName = parentAccountName,
            labelIdToName = contactLabels.names,
            labelsUsable = contactLabels.fetched,
        )

        // Where a display name goes when Proton has no first or last name for the contact.
        val nameMode = ContactNameMode.of(context, parentAccountName)
        // Set when the user changes that setting. Contacts already on the phone are otherwise
        // skipped as unchanged, so the new setting would silently do nothing to them.
        val nameModeReapply = ContactNameMode.needsReapply(context, parentAccountName)
        if (nameModeReapply) {
            SyncLog.i(TAG, "Contact name mode is now $nameMode for $parentAccountName; re-applying it to existing contacts")
        }

        // Fetch all contacts from Proton (paginated)
        val allContacts = fetchAllContacts(parentAccountName)
        if (allContacts == null) {
            syncResult.stats.numIoExceptions++
            SyncUtils.recordSwallowedFailure("contacts", parentAccountName, "Failed to fetch contacts from server")
            return
        }
        SyncLog.i(TAG, "Fetched ${allContacts.size} contact headers from Proton for $parentAccountName")

        // Before the mappings are read: it can unlink a mapping from a dead row, and the download
        // below must see that to bring the contact back in this same run.
        purgeAbandonedTombstones(account, parentAccountName, provider)
        purgePartialWrites(account, provider)
        adoptPendingCreates(account, parentAccountName, provider, allContacts)

        // Get existing sync mappings (keyed by parentAccountName for DB consistency)
        val existingMappings = contactSyncDao.getAllByAccount(parentAccountName)
            .associateBy { it.protonContactId }
        SyncLog.d(TAG, "Existing mappings: ${existingMappings.size}")

        val serverContactIds = allContacts.map { it.id }.toSet()

        // Collect locally dirty raw contact IDs to detect conflicts
        val dirtyRawContactIds = mutableSetOf<Long>()
        val dirtyCursor = provider.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.DIRTY} = 1 AND " +
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
            arrayOf(account.name, account.type),
            null
        )
        dirtyCursor?.use { c -> while (c.moveToNext()) dirtyRawContactIds.add(c.getLong(0)) }

        // Contacts found too large to write to this phone, skipped until they change on Proton.
        val downloadHolds = ContactDownloadHolds.all(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context), parentAccountName
        )
        val newlyHeld = mutableListOf<String>()

        val plan = planHeaders(
            account, parentAccountName, provider, allContacts, existingMappings, dirtyRawContactIds,
            downloadHolds, filterSession, nameModeReapply
        )
        val toFetch = plan.toFetch
        val backfillOnly = plan.backfillOnly
        val rejudgeNoWrite = plan.rejudgeNoWrite
        val conflictUseRemote = plan.conflictUseRemote
        val activeConflictIds = plan.activeConflictIds
        val skipped = plan.skipped
        val conflicts = plan.conflicts
        // For "use remote" contacts, clear their dirty flag before download
        for (contactMeta in conflictUseRemote) {
            val existing = existingMappings[contactMeta.id]
            if (existing?.androidRawContactId != null) {
                SyncUtils.clearContactDirtyFlag(provider, account, existing.androidRawContactId, TAG)
            }
        }
        SyncLog.i(TAG, "Contacts to fetch: ${toFetch.size}, unchanged: $skipped, conflicts: $conflicts")

        val fetched = fetchContactDetails(parentAccountName, toFetch)

        val synced = applyFetchedContacts(
            account, parentAccountName, provider, syncResult, fetched, existingMappings, labelMap, nameMode,
            filterSession, downloadHolds, newlyHeld, activeConflictIds, backfillOnly, rejudgeNoWrite
        )

        reportTooLargeContacts(parentAccountName, allContacts, newlyHeld, downloadHolds.keys)

        SyncLog.i(TAG, "Contact sync done: synced=$synced, skipped=$skipped, total=${allContacts.size}")

        applyServerDeletions(account, parentAccountName, provider, syncResult, serverContactIds, existingMappings)

        // Removal step: contacts a filter rule would remove from the phone.
        //
        // Sits here on purpose: after the server-deletion path above, so the two prompts cannot
        // interleave, and before the orphan sweep below, so that sweep still sees these mappings
        // with their android id intact and leaves them alone.
        //
        // Unlike a server deletion this is never applied unprompted, however few contacts are
        // involved. There the destructive input is a glitch in someone else's data and the guard's
        // job is to catch an implausible shrink; here it is a rule the user typed, and a rule that
        // is one condition away from correct can quietly empty an address book.
        applyFilterRemovals(account, parentAccountName, provider, filterSession, existingMappings)

        // The re-apply is done once the whole download pass has run. Cleared here rather than at
        // the top so a sync that dies halfway tries again next time instead of leaving half the
        // address book in the old layout.
        if (nameModeReapply) {
            ContactNameMode.clearReapply(context, parentAccountName)
            SyncLog.i(TAG, "Contact name mode re-applied for $parentAccountName")
        }

        sweepOrphanedLocalContacts(account, parentAccountName, provider, syncResult, filterSession)

        val existingLabelNames = prepareUploadLabels(account, parentAccountName, provider)

        // Two-way sync: upload locally modified contacts
        if (triggerType == SyncTriggerType.ManualForceRemoteToLocal) {
            // Ahead of the held-uploads check: a forced run uploads nothing either way, but it
            // must still clear the flags its own writes raised, or the next normal sync uploads
            // every contact it just downloaded.
            clearDirtyAfterForcedDownload(account, provider, pendingLocal = dirtyRawContactIds)
        } else if (me.proton.sync.contacts.filter.ContactFilterSession
                .uploadsUnsafe(context, parentAccountName, contactLabels.fetched)
        ) {
            SyncLog.w(
                TAG,
                "Holding uploads for $parentAccountName this run: an upload rule tests group " +
                    "membership and the contact labels could not be read, so a contact it excludes " +
                    "would reach Proton and stay there"
            )
        } else {
            uploadDirtyContacts(account, parentAccountName, provider, syncResult, activeConflictIds, existingLabelNames, filterSession)
        }

        filterSession?.logSummary(parentAccountName)
        // Cleared only after a full pass, so a sync that dies partway leaves the flag set and the
        // next one re-evaluates rather than trusting half-applied verdicts.
        if (filterSession?.rulesChanged == true) {
            me.proton.sync.contacts.filter.ContactFilterStore.clearDirty(context, parentAccountName)
        }
    }

    /**
     * Which row a downloaded card goes into: the mapped row, [NEW_ROW] for a fresh insert, or null
     * for "write nothing".
     *
     * Never into a deleted row, where Proton's card would sit invisible. A deleted row that is still
     * DIRTY holds a deletion made on the phone after this sync took its snapshot, so nothing is
     * written and the upload sends that deletion. One that is not dirty is a deletion nobody will
     * send any more (typically "use Proton's version" on a conflict for a contact deleted on the
     * phone, whose flag was cleared to discard the local change), so it is removed and the contact
     * is written as a new row: the user's choice takes effect in this run. Writing into it instead
     * left the contact missing until a later sync, and a provider that re-flags rows the sync adapter
     * writes (Samsung does) turned the choice into a deletion on Proton. A mapped row that no longer
     * exists at all also gets a new row, and so does one that belongs to a different account (a
     * stale id after Contacts storage was cleared can point at, say, a Google contact, which must
     * never receive Proton's card). If the row cannot be read or removed, nothing is written this
     * run: the mapping keeps its old modify time, so the next sync fetches the contact again, and a
     * dead row left behind is restored by [purgeAbandonedTombstones].
     */
    private fun downloadTargetFor(provider: ContentProviderClient, account: Account, rawContactId: Long?): Long? {
        if (rawContactId == null) return NEW_ROW
        data class RowState(val deleted: Int, val dirty: Int, val accountName: String?, val accountType: String?)
        val state = try {
            provider.query(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId),
                arrayOf(
                    ContactsContract.RawContacts.DELETED, ContactsContract.RawContacts.DIRTY,
                    ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE
                ),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) RowState(c.getInt(0), c.getInt(1), c.getString(2), c.getString(3)) else null }
                ?: return NEW_ROW
        } catch (e: Exception) {
            SyncLog.w(TAG, "Not writing raw contact $rawContactId this sync: it could not be read (${e.message})")
            return null
        }
        if (state.accountName != account.name || state.accountType != account.type) {
            SyncLog.w(TAG, "Raw contact $rawContactId belongs to another account; writing the contact as a new row")
            return NEW_ROW
        }
        if (state.deleted == 0) return rawContactId
        if (state.dirty == 1) {
            SyncLog.i(TAG, "Not writing raw contact $rawContactId: deleted on the phone during this sync; the deletion uploads instead")
            return null
        }
        return try {
            // The directory URI with an _ID selection, not the item URI: ContactsProvider2 ignores the
            // selection on a raw-contact item delete, and the DELETED/DIRTY re-check must hold.
            val removed = provider.delete(
                SyncUtils.asCallerSyncAdapter(ContactsContract.RawContacts.CONTENT_URI, account),
                "${ContactsContract.RawContacts._ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 1 AND " +
                    "${ContactsContract.RawContacts.DIRTY} = 0",
                arrayOf(rawContactId.toString())
            )
            if (removed > 0) NEW_ROW else {
                SyncLog.w(TAG, "Not writing raw contact $rawContactId this sync: its deleted row changed while it was checked")
                null
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Not writing raw contact $rawContactId this sync: its deleted row could not be removed (${e.message})")
            null
        }
    }

    /**
     * Remove deleted raw contacts that nothing will ever process: DELETED=1 but DIRTY=0.
     *
     * A deletion made on the phone leaves a tombstone with DIRTY=1, and the upload turns it into a
     * delete on Proton and then removes the row. A tombstone whose DIRTY was cleared without that
     * happening is invisible to the upload (it selects DIRTY rows only) and stays forever. Forced
     * Remote→Local runs before v1.10.57 made them in bulk: the local wipe of older versions left
     * a tombstone per contact, and the flag clearing that followed reset DIRTY on every row. One
     * test phone carried 1254 of them.
     *
     * Most have no mapping and are simply removed. A tombstone a mapping still points to is a
     * contact that has already vanished from the phone (a deleted raw contact leaves its
     * aggregate) while it is still on Proton, and nothing would ever change that: the download skips
     * it as unchanged, or writes Proton's changes into the invisible row. Two paths made them: a
     * phone deletion whose upload threw (the flag was cleared anyway, fixed alongside this), and
     * "use Proton's version" on a conflict for a contact deleted on the phone. Which outcome the
     * user wanted cannot be told from the row, so the repair is the one that loses nothing: the row
     * is removed and the mapping unlinked, and the download restores the contact from Proton in
     * this same run. A contact the user still does not want can simply be deleted again.
     */
    private suspend fun purgeAbandonedTombstones(account: Account, parentAccountName: String, provider: ContentProviderClient) {
        try {
            val deadRows = "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                "${ContactsContract.RawContacts.DELETED} = 1 AND ${ContactsContract.RawContacts.DIRTY} = 0"
            val ids = mutableListOf<Long>()
            provider.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                deadRows,
                arrayOf(account.name, account.type),
                null
            )?.use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
            if (ids.isEmpty()) return

            // Unlink first, then delete. The other order stranded a mapping for good if anything
            // failed in between (the contacts provider process dying on a later chunk, or this
            // process being killed): its row was gone, so no later pass could find it again. This
            // order leaves at worst an unmapped dead row, which the next pass removes.
            val dead = ids.toHashSet()
            var restored = 0
            for (mapping in contactSyncDao.getAllByAccount(parentAccountName)) {
                val rawId = mapping.androidRawContactId ?: continue
                if (rawId !in dead) continue
                contactSyncDao.upsert(mapping.copy(androidRawContactId = null))
                restored++
            }

            var purged = 0
            for (chunk in ids.chunked(400)) {
                purged += provider.delete(
                    SyncUtils.asCallerSyncAdapter(ContactsContract.RawContacts.CONTENT_URI, account),
                    "$deadRows AND ${ContactsContract.RawContacts._ID} IN (${chunk.joinToString(",") { "?" }})",
                    (listOf(account.name, account.type) + chunk.map { it.toString() }).toTypedArray()
                )
            }
            SyncLog.i(
                TAG,
                "Removed $purged abandoned deleted contact row(s) for ${account.name}" +
                    if (restored > 0) "; restoring $restored contact(s) that are still on Proton" else ""
            )
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not remove abandoned deleted contact rows: ${e.message}")
        }
    }

    /**
     * After a forced Remote→Local download: clear DIRTY on the contacts the download wrote, and keep
     * it on contacts created on the phone that have not reached Proton yet.
     *
     * Remote→Local re-fetches every contact from Proton, and Samsung's ContactsProvider flips DIRTY=1
     * on rows we just inserted (even via the sync-adapter URI). Left alone, the framework's
     * auto-queued follow-up sync, which runs as a normal sync, uploads every one of them back
     * (v1.4.17 skipped the upload, v1.4.18 added clearing the flags).
     *
     * [pendingLocal] is the set of rows that were dirty when this sync STARTED. The local wipe has
     * hard-deleted every contact that came from Proton before the sync is requested, so those rows
     * are contacts created or edited on the phone and not uploaded yet; contacts carry no Proton id
     * of their own to tell them apart any later. Clearing their flag as well, as this used to, hid
     * them from every later upload, which only selects dirty rows: kept on the phone, never on
     * Proton, with nothing to say so. Pending edits to contacts that came from Proton are still
     * discarded, by the wipe, as the user asked.
     */
    private fun clearDirtyAfterForcedDownload(account: Account, provider: ContentProviderClient, pendingLocal: Set<Long>) {
        val rawContacts = SyncUtils.asCallerSyncAdapter(ContactsContract.RawContacts.CONTENT_URI, account)
        var cleared = 0
        var keptLocal = 0
        try {
            val dirtyNow = mutableListOf<Long>()
            provider.query(
                rawContacts,
                arrayOf(ContactsContract.RawContacts._ID),
                // DELETED = 0: a deletion made on the phone while this run was downloading keeps its
                // flag, so the next sync still deletes the contact on Proton. Clearing it left a
                // tombstone that no upload ever processes.
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                    "${ContactsContract.RawContacts.DIRTY} = 1 AND ${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(account.name, account.type),
                null
            )?.use { c -> while (c.moveToNext()) dirtyNow.add(c.getLong(0)) }
            val (kept, downloaded) = dirtyNow.partition { it in pendingLocal }
            keptLocal = kept.size
            for (chunk in downloaded.chunked(400)) {
                cleared += provider.update(
                    rawContacts,
                    ContentValues().apply { put(ContactsContract.RawContacts.DIRTY, 0) },
                    "${ContactsContract.RawContacts._ID} IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map { it.toString() }.toTypedArray()
                )
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Bulk-clear DIRTY failed: ${e.message}")
        }
        SyncLog.i(
            TAG,
            "Skipping dirty-contact upload (forced Remote→Local - cleared DIRTY on $cleared downloaded contacts; " +
                "pending edits to Proton contacts are intentionally discarded; $keptLocal contact(s) created on " +
                "the phone and not uploaded yet stay pending and upload on the next sync)"
        )
    }

    /** Apply the deletions the server reports, behind [me.proton.sync.RemoteDeletionGuard]. */
    private suspend fun applyServerDeletions(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
        serverContactIds: Set<String>,
        existingMappings: Map<String, me.proton.sync.db.entities.ContactSyncEntity>
    ) {
        // Delete contacts no longer on server — but CONFIRM before a *suspicious* shrink instead of
        // silently skipping (which left genuinely-deleted contacts stuck on the phone forever). A
        // small/normal shrink deletes immediately; a large one (server empty, or >=10 AND >=50%) is
        // held and the user is asked: Delete from phone / Keep on phone. See RemoteDeletionGuard.
        val gType = me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS
        me.proton.sync.RemoteDeletionGuard.prunePending(context, gType, parentAccountName, serverContactIds)
        me.proton.sync.RemoteDeletionGuard.pruneKept(context, gType, parentAccountName, serverContactIds)
        val keptContacts = me.proton.sync.RemoteDeletionGuard.keptIds(context, gType, parentAccountName)
        val toDelete = existingMappings.filter { (protonId, m) ->
            protonId !in serverContactIds && m.androidRawContactId != null && protonId !in keptContacts
        }
        if (toDelete.isEmpty()) {
            me.proton.sync.RemoteDeletionGuard.clearPending(context, gType, parentAccountName)
            SyncNotificationHelper.cancelRemoteDeletionPrompt(context, parentAccountName, gType)
        } else {
            val approved = me.proton.sync.RemoteDeletionGuard.isApproved(context, gType, parentAccountName)
            if (!approved && me.proton.sync.RemoteDeletionGuard.isSuspicious(toDelete.size, serverContactIds.size, existingMappings.size)) {
                me.proton.sync.RemoteDeletionGuard.addPending(context, gType, parentAccountName, toDelete.keys)
                SyncNotificationHelper.showRemoteDeletionPrompt(context, parentAccountName, gType, toDelete.size)
                val stillOnServer = existingMappings.keys.count { it in serverContactIds }
                SyncLog.w(
                    TAG,
                    "Held ${toDelete.size} server-side contact deletion(s) for confirmation " +
                        "(of ${existingMappings.size} mapped, $stillOnServer still on the server; " +
                        "server now lists ${serverContactIds.size})"
                )
            } else {
                // approved → delete ONLY the confirmed (pending) ids that are still gone, so a stale
                // approval can't let a later transient glitch over-delete; normal small shrink → all.
                val confirmed = if (approved) me.proton.sync.RemoteDeletionGuard.pendingIds(context, gType, parentAccountName) else null
                val target = if (confirmed != null) toDelete.filterKeys { it in confirmed } else toDelete
                for ((protonId, mapping) in target) {
                    val rawId = mapping.androidRawContactId ?: continue
                    try {
                        provider.delete(
                            SyncUtils.asCallerSyncAdapter(
                                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
                                account
                            ),
                            null, null
                        )
                        contactSyncDao.deleteByProtonId(parentAccountName, protonId)
                        syncResult.stats.numDeletes++
                    } catch (e: Exception) {
                        SyncLog.e(TAG, "Failed to delete contact $protonId", e)
                    }
                }
                me.proton.sync.RemoteDeletionGuard.clearPending(context, gType, parentAccountName)
                SyncNotificationHelper.cancelRemoteDeletionPrompt(context, parentAccountName, gType)
            }
        }
    }

    /** Remove from the phone the contacts a filter rule now excludes, never without asking first. */
    private suspend fun applyFilterRemovals(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        filterSession: me.proton.sync.contacts.filter.ContactFilterSession?,
        existingMappings: Map<String, me.proton.sync.db.entities.ContactSyncEntity>
    ) {
        if (filterSession != null && filterSession.applies) {
            val filterType = me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS_FILTER
            // The complete "are these still excluded?" answer, rescued contacts included, which is what
            // may release a hold. It is deliberately NOT the set to ask about; see planFilterRemovals.
            val stillExcluded = filterSession.stillExcluded

            // Only the contacts it is legitimate to ask about may be held, counted or deleted: excluded, on
            // the phone, and NOT rescued by an earlier "Keep on phone". Holding stillExcluded instead put a
            // rescued id straight back into the pending set, so the prompt returned on the next sync for
            // ever, and a later approval then deleted a contact the user had explicitly rescued.
            val plan = me.proton.sync.contacts.filter.planFilterRemovals(
                pending = me.proton.sync.RemoteDeletionGuard.pendingIds(context, filterType, parentAccountName),
                stillExcluded = stillExcluded,
                toAsk = filterSession.pendingRemovals.keys,
                approved = me.proton.sync.RemoteDeletionGuard.isApproved(context, filterType, parentAccountName)
            )
            if (plan.prune.isNotEmpty()) {
                me.proton.sync.RemoteDeletionGuard.prunePending(context, filterType, parentAccountName, plan.prune)
            }

            if (plan.delete.isEmpty() && plan.clear) {
                // Nothing left to ask: every excluded contact is one the user rescued, or the rules
                // released them all. The prompt must go away rather than be re-posted saying zero.
                me.proton.sync.RemoteDeletionGuard.clearPending(context, filterType, parentAccountName)
                SyncNotificationHelper.cancelRemoteDeletionPrompt(context, parentAccountName, filterType)
            } else if (plan.delete.isNotEmpty()) {
                var removed = 0
                for (protonId in plan.delete) {
                    val rawId = existingMappings[protonId]?.androidRawContactId ?: continue
                    try {
                        // As a sync adapter, so no tombstone is written. A plain delete would set
                        // DELETED=1, and the upload phase would then read that as "the user deleted
                        // this" and call deleteContacts, turning a local visibility filter into
                        // deletion of the contact on Proton.
                        provider.delete(
                            SyncUtils.asCallerSyncAdapter(
                                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
                                account
                            ),
                            null, null
                        )
                        // Same success path: the row must never be left alive with a mapping that
                        // has no android id, because the orphan sweep below hard-deletes exactly
                        // that shape without a prompt.
                        contactSyncDao.upsert(
                            me.proton.sync.db.entities.ContactSyncEntity(
                                accountName = parentAccountName,
                                protonContactId = protonId,
                                androidRawContactId = null,
                                lastModified = existingMappings[protonId]?.lastModified ?: 0L,
                                syncTag = existingMappings[protonId]?.syncTag,
                                // Carried across: an upsert replaces the whole row, so a column not
                                // restated here would be silently dropped.
                                serverFn = existingMappings[protonId]?.serverFn,
                                serverHasN = existingMappings[protonId]?.serverHasN,
                                serverGiven = existingMappings[protonId]?.serverGiven,
                                serverFamily = existingMappings[protonId]?.serverFamily,
                            )
                        )
                        removed++
                    } catch (e: Exception) {
                        SyncLog.e(TAG, "Failed to remove filtered contact $protonId from the phone", e)
                    }
                }
                SyncLog.i(TAG, "Contact filter removed $removed contact(s) from the phone (still on Proton)")
                me.proton.sync.RemoteDeletionGuard.clearPending(context, filterType, parentAccountName)
                SyncNotificationHelper.cancelRemoteDeletionPrompt(context, parentAccountName, filterType)
            } else {
                me.proton.sync.RemoteDeletionGuard.addPending(context, filterType, parentAccountName, plan.ask)
                SyncNotificationHelper.showRemoteDeletionPrompt(
                    context, parentAccountName, filterType, plan.ask.size
                )
                // The count only. This used to append up to eight display names, which put contact
                // names into the log on every sync until the prompt was answered.
                SyncLog.w(
                    TAG,
                    "Contact filter would remove ${plan.ask.size} contact(s) from the phone; holding for confirmation"
                )
            }
        } else if (
            me.proton.sync.RemoteDeletionGuard
                .pendingIds(context, me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS_FILTER, parentAccountName)
                .isNotEmpty() &&
            me.proton.sync.contacts.filter.ContactFilterStore.load(context, parentAccountName).isInactive
        ) {
            // The rules that asked the question are gone, so the question goes with them. Everything
            // above is reached only while rules apply, so switching filtering off used to strand the
            // prompt: it went on asking to remove contacts that nothing excludes any more, and it could
            // not be answered either, because approving it sets a flag that only the branch above reads.
            //
            // Deliberately not on filterSession == null alone. A session suppressed because the contact
            // labels could not be read is also null, and withdrawing on that would throw away a pending
            // question on a network error. Only the stored rules being inactive settles it.
            val stranded = me.proton.sync.RemoteDeletionGuard
                .pendingIds(context, me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS_FILTER, parentAccountName)
            me.proton.sync.RemoteDeletionGuard
                .clearPending(context, me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS_FILTER, parentAccountName)
            SyncNotificationHelper.cancelRemoteDeletionPrompt(
                context, parentAccountName, me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS_FILTER
            )
            SyncLog.i(TAG, "Contact filtering is off; withdrew the prompt asking about ${stranded.size} contact(s)")
        }
    }

    /**
     * Delete local rows that belong to nothing: no mapping, not held back by a rule, not refused.
     *
     * Reads the mappings fresh rather than taking the caller's snapshot, because the download pass has
     * just written new ones and a stale snapshot would delete contacts it had only now created.
     */
    private suspend fun sweepOrphanedLocalContacts(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
        filterSession: me.proton.sync.contacts.filter.ContactFilterSession?
    ) {
        // Remove local contacts that have no sync mapping and don't exist on the server.
        // These are orphaned contacts from failed uploads or deleted remote contacts.
        // DIRTY=1 contacts are pending upload (e.g., user just created locally) — never delete those.
        val allLocalIds = mutableListOf<Long>()
        provider.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.DELETED} = 0 AND ${ContactsContract.RawContacts.DIRTY} = 0",
            arrayOf(account.name, account.type),
            null
        )?.use { c ->
            while (c.moveToNext()) allLocalIds.add(c.getLong(0))
        }
        val mappedAndroidIds = contactSyncDao.getAllByAccount(parentAccountName)
            .mapNotNull { it.androidRawContactId }
            .toSet()

        // Contacts the upload filter declined to send. They have no mapping (they were never on
        // Proton) and they are not dirty (the upload path cleared the flag), which is exactly the
        // shape this sweep deletes. Without this they would be destroyed one sync after the user
        // created them. Pruned to what still exists, so the set cannot grow without bound.
        val keptLocalIds = me.proton.sync.contacts.filter.ContactFilterStore
            .keptLocalIds(context, parentAccountName)
        if (keptLocalIds.isNotEmpty()) {
            val filteringReallyOff = filterSession == null &&
                me.proton.sync.contacts.filter.ContactFilterStore
                    .load(context, parentAccountName).isInactive
            if (filteringReallyOff) {
                // Only when the rules themselves are gone. A session suppressed because the contact
                // labels could not be read is also null, and releasing on that would push contacts
                // an upload rule is holding back straight to Proton on a transient network error,
                // where nothing ever takes them off again. With the rules genuinely gone, these
                // are not dirty and have no mapping, so without this they would sit on the phone
                // for ever: never uploaded, and invisible to a rules change (there are no rules).
                SyncLog.i(TAG, "Contact filtering is off; releasing ${keptLocalIds.size} held-back contacts for upload")
                val reopened = markKeptLocalDirty(account, provider, keptLocalIds)
                // Only the rows that are genuinely open again lose their protection. One the provider
                // refused to mark is still unmapped and still clean, which is the exact shape the orphan
                // sweep hard-deletes, and it exists nowhere else: no mapping, nothing on Proton, no
                // tombstone. It keeps its protection until a later run can re-open it.
                me.proton.sync.contacts.filter.ContactFilterStore
                    .retainKeptLocal(context, parentAccountName, keptLocalIds - reopened)
            } else if (filterSession != null && filterSession.rulesChanged) {
                // The rules changed, so these have to be judged again. They are not dirty, so the
                // upload phase would never look at them; marking them dirty puts them back in its
                // path this same run, and the upload filter either sends them or re-adds them.
                markKeptLocalDirty(account, provider, keptLocalIds)
            } else {
                me.proton.sync.contacts.filter.ContactFilterStore.retainKeptLocal(
                    context, parentAccountName, keptLocalIds.intersect(allLocalIds.toSet())
                )
            }
        }

        // Exempt from the sweep everything that was protected when this run began.
        //
        // The protection used to be dropped in the branch above, on the theory that marking those
        // rows dirty handed them to the upload phase. It does not: allLocalIds was already
        // snapshotted with DIRTY = 0, so the rows stay in it, and uploadDirtyContacts runs *after*
        // this sweep. Clearing the set therefore deleted every contact the upload filter had
        // deliberately saved, with no prompt and no copy on Proton, one sync after any rule edit.
        // The stored set is left alone here and rewritten by the upload phase, which is the only
        // place that knows whether a contact still needs protecting.
        // And contacts created on the phone that Proton kept refusing: they exist nowhere else.
        val refusedIds = refusedCreateIds(account, parentAccountName, provider)
        val unmappedIds = allLocalIds.filter { it !in mappedAndroidIds && it !in keptLocalIds && it !in refusedIds }
        if (unmappedIds.isNotEmpty()) {
            SyncLog.i(TAG, "Removing ${unmappedIds.size} orphaned local contacts (no mapping, not on server)")
            for (id in unmappedIds) {
                try {
                    provider.delete(
                        SyncUtils.asCallerSyncAdapter(
                            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id),
                            account
                        ),
                        null, null
                    )
                    syncResult.stats.numDeletes++
                } catch (e: Exception) {
                    SyncLog.e(TAG, "Failed to delete orphaned contact $id", e)
                }
            }
        }
    }

    /**
     * The names of the labels Proton has, plus the group deletions that follow from them.
     *
     * @return those names, which the upload phase reuses instead of fetching them again.
     */
    private suspend fun prepareUploadLabels(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient
    ): MutableSet<String> {
        // Build set of existing Proton labels for group deletion detection and label creation
        val existingLabelNames = mutableSetOf<String>()
        val protonLabels = mutableListOf<ProtonContactLabel>()
        try {
            val labelsResp = apiClient.getContactLabels(parentAccountName)
            labelsResp.body()?.labels?.let { labels ->
                protonLabels.addAll(labels)
                labels.forEach { existingLabelNames.add(it.name) }
            }
        } catch (_: Exception) { /* Intentional: label fetch failure is non-fatal — proceed with empty label set (treat as no server-side groups) */ }

        // Detect deleted groups: delete Proton labels and mark contacts dirty.
        // Android removes GroupMembership rows when a group is deleted but does NOT
        // set DIRTY=1 on the affected contacts, so group removal goes undetected.
        markContactsDirtyForDeletedGroups(account, parentAccountName, provider, existingLabelNames, protonLabels)
        return existingLabelNames
    }


    /** A contact header from the listing, together with the detail fetched for it. */
    private data class FetchedContact(
        val meta: ContactMeta,
        val detail: me.proton.sync.api.models.ContactDetail
    )

    /**
     * Fetch the cards of the contacts that need downloading.
     *
     * A handful of contacts are fetched one by one in parallel; more than that goes through the paginated
     * bulk export, with a per-contact fallback for anything the export did not return.
     */
    private suspend fun fetchContactDetails(
        parentAccountName: String,
        toFetch: List<ContactMeta>
    ): List<FetchedContact> {
        // Fetch contact details using bulk export endpoint (50 per page instead of N+1 individual calls)
        val fetched = mutableListOf<FetchedContact>()
        val metaById = toFetch.associateBy { it.id }

        // Small incremental fetch: skip paginated bulk export and fetch the specific IDs directly.
        // The export endpoint scans the full contact list 50-at-a-time; for a handful of known IDs
        // it can take many pages on large accounts (over a thousand contacts) before reaching them.
        val SMALL_FETCH_THRESHOLD = 10
        if (toFetch.size in 1..SMALL_FETCH_THRESHOLD) {
            SyncLog.i(TAG, "Small fetch (${toFetch.size} contacts) - using individual GETs in parallel")
            val semaphore = Semaphore(PARALLEL_FETCHES)
            coroutineScope {
                val deferreds = toFetch.map { meta ->
                    async {
                        semaphore.withPermit {
                            val detail = fetchContactDetail(parentAccountName, meta.id)
                            if (detail != null) FetchedContact(meta, detail) else null
                        }
                    }
                }
                fetched.addAll(deferreds.awaitAll().filterNotNull())
            }
        } else {
            var exportPage = 0
            var hasMore = toFetch.isNotEmpty()
            while (hasMore) {
                val exportResponse = apiClient.exportContacts(parentAccountName, page = exportPage, pageSize = 50)
                if (!exportResponse.isSuccessful) {
                    SyncLog.e(TAG, "Export contacts page $exportPage failed: HTTP ${exportResponse.code()}${exportResponse.protonErrorSuffix()}")
                    break
                }
                val exportedContacts = exportResponse.body()?.contacts ?: emptyList()
                for (contact in exportedContacts) {
                    val meta = metaById[contact.id]
                    if (meta != null) {
                        fetched.add(FetchedContact(meta, contact))
                    }
                }
                if ((exportPage + 1) % 2 == 0 || fetched.size == toFetch.size) {
                    SyncLog.i(TAG, "Progress: ${fetched.size}/${toFetch.size} contacts fetched (page $exportPage)")
                }
                // Stop when all needed contacts found or no more pages
                hasMore = exportedContacts.size >= 50 && fetched.size < toFetch.size
                exportPage++
                kotlinx.coroutines.delay(100) // Rate limit between pages
            }
        }

        // Fallback: fetch individually any contacts missed by export
        if (fetched.size < toFetch.size) {
            val fetchedIds = fetched.map { it.meta.id }.toSet()
            val missed = toFetch.filter { it.id !in fetchedIds }
            SyncLog.i(TAG, "Export missed ${missed.size} contacts, fetching individually")
            for (contactMeta in missed) {
                val detail = fetchContactDetail(parentAccountName, contactMeta.id)
                if (detail != null) fetched.add(FetchedContact(contactMeta, detail))
                kotlinx.coroutines.delay(100)
            }
        }

        SyncLog.i(TAG, "Fetched ${fetched.size}/${toFetch.size} contact details")
        return fetched
    }

    /**
     * Decrypt every fetched card and write it to the phone, one contact at a time.
     *
     * Holds the download-path protections: an unsent local edit is never overwritten, a contact too large
     * for the provider is held instead of half-written, and a cancelled sync stops without clearing
     * anything. [newlyHeld] and [activeConflictIds] are the caller's own collections and are added to
     * here, because the phases after this one read them.
     *
     * @return how many contacts were written.
     */
    private suspend fun applyFetchedContacts(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
        fetched: List<FetchedContact>,
        existingMappings: Map<String, me.proton.sync.db.entities.ContactSyncEntity>,
        /** Proton label id to the Android group row it was written as, from [syncContactLabels]. */
        labelMap: Map<String, Long>,
        nameMode: ContactNameMode,
        filterSession: me.proton.sync.contacts.filter.ContactFilterSession?,
        downloadHolds: Map<String, ContactDownloadHolds.Hold>,
        newlyHeld: MutableList<String>,
        activeConflictIds: MutableSet<String>,
        backfillOnly: Set<String>,
        rejudgeNoWrite: Set<String>
    ): Int {
        // Apply fetched contacts to Android (must be sequential for ContentProvider)
        var synced = 0
        for ((contactMeta, contactDetail) in fetched) {
            // Check if sync was cancelled before processing each contact
            if (Thread.currentThread().isInterrupted) {
                SyncLog.i(TAG, "Sync interrupted after applying $synced contacts, will resume next sync")
                break
            }
            val existing = existingMappings[contactMeta.id]
            try {
                val vCard = decodeFetchedCard(parentAccountName, contactDetail) ?: continue

                val androidGroupIds = resolveAndroidGroupIds(contactMeta, labelMap)

                if (recordWithoutWriting(
                        parentAccountName, contactMeta, vCard, existing, existingMappings, filterSession,
                        backfillOnly, rejudgeNoWrite
                    )
                ) continue

                if (!writeFetchedContact(
                        account, parentAccountName, provider, syncResult, contactMeta, vCard, existing,
                        androidGroupIds, nameMode, filterSession, downloadHolds, newlyHeld, activeConflictIds
                    )
                ) continue

                synced++
                syncResult.stats.numInserts++
            } catch (e: NotImplementedError) {
                SyncLog.w(TAG, "Crypto not implemented, skipping contact ${contactMeta.id}: ${e.message}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                SyncLog.d(TAG, "Sync cancelled during contact ${contactMeta.id}")
                break
            } catch (e: Exception) {
                if (e is InterruptedException || e.cause is InterruptedException
                    || e.cause is kotlinx.coroutines.CancellationException) {
                    SyncLog.d(TAG, "Sync interrupted during contact apply phase")
                    break
                }
                SyncLog.e(TAG, "Failed to sync contact ${contactMeta.id}", e)
                syncResult.stats.numSkippedEntries++
            }
        }
        return synced
    }

    /**
     * What the header pass decided for every contact on the server, before anything is fetched.
     *
     * [activeConflictIds] stays mutable on purpose: the apply pass adds the contacts it holds, and the
     * upload pass then skips exactly those.
     */
    private data class HeaderPlan(
        val toFetch: List<ContactMeta>,
        val backfillOnly: Set<String>,
        val rejudgeNoWrite: Set<String>,
        val conflictUseRemote: List<ContactMeta>,
        val activeConflictIds: MutableSet<String>,
        val skipped: Int,
        val conflicts: Int
    )

    /**
     * Decide from the headers alone what to fetch, what to skip and what is in conflict.
     *
     * Every contact goes through the filter verdict first, and a contact held back for being too large
     * keeps the timestamp it had, so nothing is lost by skipping it.
     */
    private suspend fun planHeaders(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        allContacts: List<ContactMeta>,
        existingMappings: Map<String, me.proton.sync.db.entities.ContactSyncEntity>,
        dirtyRawContactIds: Set<Long>,
        downloadHolds: Map<String, ContactDownloadHolds.Hold>,
        filterSession: me.proton.sync.contacts.filter.ContactFilterSession?,
        nameModeReapply: Boolean
    ): HeaderPlan {
        // Partition into contacts that need fetching vs ones we can skip
        val toFetch = mutableListOf<ContactMeta>()
        /** Contacts fetched only to record what their card said; see the note at the skip below. */
        val backfillOnly = mutableSetOf<String>()
        // Fetched to be re-judged, but carrying an unsent local edit, so the card must not be
        // written to the phone. See the header-phase branch that fills this.
        val rejudgeNoWrite = mutableSetOf<String>()
        val conflictUseRemote = mutableListOf<ContactMeta>() // User chose "use remote"
        val activeConflictIds = mutableSetOf<String>() // Proton IDs with unresolved conflicts
        val tally = HeaderTally()
        for (contactMeta in allContacts) {
            val existing = existingMappings[contactMeta.id]
            val step = judgeHeaderFilter(parentAccountName, contactMeta, existing, filterSession)
            if (step == HeaderFilterStep.CONSUMED) continue

            routeHeader(
                parentAccountName, provider, contactMeta, existing, dirtyRawContactIds, downloadHolds,
                filterStale = step == HeaderFilterStep.STALE, nameModeReapply = nameModeReapply,
                toFetch = toFetch, backfillOnly = backfillOnly, rejudgeNoWrite = rejudgeNoWrite,
                conflictUseRemote = conflictUseRemote, activeConflictIds = activeConflictIds, tally = tally
            )
        }
        // Add "use remote" conflicts to the fetch list — they'll be downloaded and overwrite local
        toFetch.addAll(conflictUseRemote)
        return HeaderPlan(
            toFetch, backfillOnly, rejudgeNoWrite, conflictUseRemote, activeConflictIds,
            tally.skipped, tally.conflicts
        )
    }

    /** Running totals of one upload pass, shared with the per-contact helpers. */
    private class UploadTally {
        var processedUpdates = 0
    }

    /**
     * Whether this contact may be uploaded at all, given the conflicts of this run and any decision
     * the user has already made.
     *
     * @return false when the contact is to be skipped; the caller moves on to the next one.
     */
    private fun passesConflictGate(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        rawContactId: Long,
        mapping: ContactSyncEntity?,
        activeConflictIds: Set<String>
    ): Boolean {
        // Check if this contact has an active conflict from this sync run
        if (mapping != null && mapping.protonContactId in activeConflictIds) {
            SyncLog.i(TAG, "Skipping dirty contact $rawContactId (active conflict, awaiting user decision)")
            return false
        }

        // Check if there's a stored conflict decision for this contact
        if (mapping != null) {
            val decision = ConflictResolutionReceiver.getDecision(
                context, parentAccountName, mapping.protonContactId
            )
            if (decision == ConflictResolutionReceiver.DECISION_SKIP) {
                SyncLog.i(TAG, "Skipping dirty contact $rawContactId (conflict decision: skip)")
                return false
            }
            if (decision == ConflictResolutionReceiver.DECISION_REMOTE) {
                // User chose remote — discard local changes by clearing dirty flag
                SyncLog.i(TAG, "Discarding local changes for $rawContactId (conflict decision: use remote)")
                SyncUtils.clearContactDirtyFlag(provider, account, rawContactId, TAG)
                ConflictResolutionReceiver.clearDecision(context, parentAccountName, mapping.protonContactId)
                return false
            }
            if (decision == ConflictResolutionReceiver.DECISION_LOCAL) {
                // User chose local — proceed with upload and clear the decision
                SyncLog.i(TAG, "Uploading local version for $rawContactId (conflict decision: use local)")
                ConflictResolutionReceiver.clearDecision(context, parentAccountName, mapping.protonContactId)
            }
        }
        return true
    }

    /** Send a contact deleted on the phone to Proton, and drop the local tombstone once it is gone. */
    private suspend fun uploadLocalDeletion(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
        rawContactId: Long,
        mapping: ContactSyncEntity
    ) {
        // Contact was deleted locally — delete on server
        val response = apiClient.deleteContacts(
            parentAccountName,
            DeleteContactsRequest(ids = listOf(mapping.protonContactId))
        )
        if (response.isSuccessful) {
            contactSyncDao.deleteByProtonId(parentAccountName, mapping.protonContactId)
            provider.delete(
                SyncUtils.asCallerSyncAdapter(
                    ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId),
                    account
                ),
                null, null
            )
            syncResult.stats.numDeletes++
        } else {
            // Silent until 2.0.2, while the tombstone stayed and the delete was simply retried.
            SyncLog.w(
                TAG,
                "Could not delete contact ${mapping.protonContactId} on Proton: HTTP ${response.code()}${response.protonErrorSuffix()}; it stays pending"
            )
        }
    }

    /**
     * Build the card of a contact changed on the phone and send it to Proton, as an update or as part of
     * the next batch create.
     *
     * Returns early where the loop used to `continue`: no card could be built, or a filter rule excludes
     * the contact on the way up. Both leave the same state behind as before.
     */
    private suspend fun uploadModifiedContact(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
        rawContactId: Long,
        mapping: ContactSyncEntity?,
        nameMode: ContactNameMode,
        filterSession: me.proton.sync.contacts.filter.ContactFilterSession?,
        existingLabelNames: MutableSet<String>,
        pendingCreates: MutableList<Pair<Long, List<ContactCard>>>,
        refusedCreates: MutableList<Pair<Long, String>>,
        tally: UploadTally,
        totalDirty: Int
    ) {
        // Contact was modified — build vCard and upload
        val vCard = buildVCardFromAndroid(provider, rawContactId, account, nameMode)
            ?.also { built -> stripInventedName(built, mapping) }
            ?.also { built -> preserveServerDisplayName(built, mapping) }
        if (vCard == null) {
            SyncLog.w(TAG, "No vCard data for contact $rawContactId, clearing dirty flag")
            SyncUtils.clearContactDirtyFlag(provider, account, rawContactId, TAG, onlyIfNotDeleted = true)
            syncResult.stats.numSkippedEntries++
            return
        }
        // Filter verdict on the way up. Deliberately after the local-delete
        // branch above: an upload exclusion means "do not push this contact's
        // content", never "keep a contact on Proton that was deleted on the phone".
        if (filterSession != null && filterSession.applies &&
            filterSession.uploadVerdict(vCard) == me.proton.sync.contacts.filter.RuleAction.EXCLUDE
        ) {
            // Clear DIRTY rather than leaving it set. The conflict-skip path above
            // deliberately leaves it, but a permanently dirty row pins
            // localUploadSignature non-zero, which disables the follow-up-sync
            // debounce for the whole account, and rebuilds the vCard (full data
            // cursor, photo read, Samsung portrait) on every sync forever.
            SyncUtils.clearContactDirtyFlag(provider, account, rawContactId, TAG, onlyIfNotDeleted = true)
            if (mapping == null) {
                // Never uploaded, so no mapping exists, so the orphan sweep below
                // would hard-delete this row now that it is no longer dirty.
                // Remember it, or creating a contact your own rules exclude
                // silently destroys it.
                me.proton.sync.contacts.filter.ContactFilterStore.addKeptLocal(
                    context, parentAccountName, rawContactId
                )
                // The user's own rule holds it now, and the kept set protects it: it is no
                // longer a contact Proton refused, and must not keep the notice alive.
                ContactUploadRefusals.forget(
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(context),
                    parentAccountName, rawContactId
                )
            }
            syncResult.stats.numSkippedEntries++
            return
        }

        run {
            // Proton's API requires a UID. A new contact keeps ONE uid across attempts, stored on its
            // raw contact: a create that reached Proton although the reply was lost is then recognised
            // by adoptPendingCreates on the next sync instead of being created a second time. A contact
            // Proton already has keeps the UID Proton stores for it, which preserveServerOnlyProperties
            // sets below; minting one here gave it a new UID at every phone-side edit.
            if (vCard.uid == null && mapping == null) {
                vCard.uid = ezvcard.property.Uid(pendingCreateUid(provider, account, rawContactId) ?: newContactUid())
            }
            vCard.version = ezvcard.VCardVersion.V4_0

            // Create any missing Proton labels for CATEGORIES
            vCard.categories?.values?.forEach { catName ->
                if (catName !in existingLabelNames) {
                    try {
                        val resp = apiClient.createContactLabel(
                            parentAccountName,
                            CreateLabelRequest(name = catName)
                        )
                        if (resp.isSuccessful) {
                            existingLabelNames.add(catName)
                            SyncLog.i(TAG, "Created Proton label '$catName'")
                        } else {
                            SyncLog.w(TAG, "Failed to create label '$catName': HTTP ${resp.code()}${resp.protonErrorSuffix()}")
                        }
                    } catch (e: Exception) {
                        SyncLog.w(TAG, "Error creating label '$catName': ${e.message}")
                    }
                }
            }

            if (mapping != null) {
                preserveServerOnlyProperties(vCard, parentAccountName, mapping.protonContactId)
            }
            // Deliberately NOT repaired, unlike the backup builder.
            //
            // The two look like the same problem and are not. A repair in the
            // backup renames a property inside a local file; a repair here is PUT
            // straight back to Proton, so a name this app decided it disliked would
            // overwrite the user's server-side data. VCardRepair is also stricter
            // than ez-vcard actually requires, so it would rename properties that
            // would have written perfectly well. Rewriting somebody's contact on
            // the server to make our own upload succeed is not a trade worth
            // making, and the first version of this fix made it.
            //
            // A card that will not serialise is HELD instead: see the
            // IllegalArgumentException catch below, which leaves the dirty flag set
            // so the edit waits rather than being discarded.
            val vCardString = Ezvcard.write(vCard).go()
            // Left dirty on purpose when the keys are not usable: the contact tries
            // again on the next sync rather than going up in the clear.
            val cards = contactDecryptor.encryptContactCards(parentAccountName, vCardString)

            if (mapping != null) {
                // Update existing — log progress every 50 instead of per-contact
                // (saves ~600 lines of noise on a forced full re-sync).
                val response = apiClient.updateContact(
                    parentAccountName,
                    mapping.protonContactId,
                    UpdateContactRequest(cards = cards)
                )
                if (response.isSuccessful) {
                    // Update lastModified so next sync doesn't re-download
                    contactSyncDao.upsert(
                        ContactSyncEntity(
                            accountName = parentAccountName,
                            protonContactId = mapping.protonContactId,
                            androidRawContactId = rawContactId,
                            lastModified = System.currentTimeMillis() / 1000,
                            syncTag = mapping.syncTag,
                            // The server card is now the one we just sent, so the
                            // record follows it instead of the old download.
                            serverFn = vCard.formattedName?.value,
                            serverHasN = if (hasStructuredName(vCard)) 1 else 0,
                            serverGiven = recordedNamePart(vCard, vCard.structuredName?.given),
                            serverFamily = recordedNamePart(vCard, vCard.structuredName?.family)
                        )
                    )
                    // Spares a row deleted on the phone while this upload ran, so the
                    // deletion still goes out next sync. The loop works from a snapshot
                    // taken before it started, and it can run for minutes.
                    SyncUtils.clearContactDirtyFlag(provider, account, rawContactId, TAG, onlyIfNotDeleted = true)
                    syncResult.stats.numUpdates++
                    tally.processedUpdates++
                    if (tally.processedUpdates % 50 == 0) {
                        SyncLog.i(TAG, "Uploaded ${tally.processedUpdates} contact updates...")
                        SyncNotificationHelper.updateProgress(
                            context, parentAccountName, "contacts",
                            "uploading", tally.processedUpdates, totalDirty
                        )
                    }
                } else {
                    // A refused update used to be handled by doing nothing at all: no log, no count,
                    // and a sync that reported itself clean. The edit does survive, because the dirty
                    // flag is only cleared on the success path above, but nothing anywhere said that
                    // the contact had not reached Proton, so an edit could sit unsent indefinitely
                    // while every sync looked healthy. Reported the same way a refused create is.
                    val code = response.code()
                    val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                    val protonCode = me.proton.sync.api.protonErrorCode(errorBody)
                    // The Code only, never Proton's Error: a refused card write can quote the card, and no
                    // log holds contact contents (see recordCreateRefusal).
                    val detail = "HTTP $code" + (protonCode?.let { ", Proton code $it" } ?: "")
                    if (ContactUploadRefusals.isTransientHttp(code)) {
                        SyncLog.w(
                            TAG,
                            "Could not update contact ${mapping.protonContactId} on Proton ($detail); it stays pending for the next sync"
                        )
                        syncResult.stats.numIoExceptions++
                    } else {
                        SyncLog.e(
                            TAG,
                            "Proton refused the update to contact ${mapping.protonContactId} ($detail)" +
                                "; the edit stays on the phone and this sync will try again"
                        )
                        syncResult.stats.numSkippedEntries++
                    }
                }
            } else {
                // Queue for batch create (max 10 per API call, as Proton's web client batches them)
                pendingCreates.add(Pair(rawContactId, cards))
                if (pendingCreates.size >= 10) {
                    // Copy, then clear, then send: a failure below must not leave these
                    // queued to be sent a second time by the final flush.
                    val batch = pendingCreates.toList()
                    pendingCreates.clear()
                    flushBatchCreate(batch, parentAccountName, provider, account, syncResult, refusedCreates)
                }
            }
        }
    }

    /** What the header-phase filter verdict decided for one contact. */
    private enum class HeaderFilterStep {
        /** Handled entirely by the verdict; the caller moves to the next contact. */
        CONSUMED,

        /** The recorded verdict still holds. */
        FRESH,

        /** The rules moved on since the verdict was recorded, so this contact is judged again. */
        STALE,
    }

    /** The counts the header pass reports, kept in one place so the per-contact helper can raise them. */
    private class HeaderTally {
        var skipped = 0
        var conflicts = 0
    }

    /**
     * Apply the contact filter rules to one header, before anything is fetched or decrypted.
     *
     * @return CONSUMED when this contact needs nothing further, otherwise whether the recorded verdict is
     *         still fresh or has gone stale and the contact must be judged again from its card.
     */
    private suspend fun judgeHeaderFilter(
        parentAccountName: String,
        contactMeta: ContactMeta,
        existing: me.proton.sync.db.entities.ContactSyncEntity?,
        filterSession: me.proton.sync.contacts.filter.ContactFilterSession?
    ): HeaderFilterStep {
        val metaLabelIds = contactMeta.labelIds ?: emptyList()
        // Header phase: filter verdict from the header alone, before anything is fetched or decrypted.
        // Most rules (groups, display name, size, timestamps) resolve here, which is what keeps
        // a filtered account from decrypting every contact on every sync.
        val headerVerdict = filterSession?.headerVerdict(
            protonId = contactMeta.id,
            displayName = contactMeta.name,
            uid = contactMeta.uid,
            sizeBytes = contactMeta.size.toLong(),
            createTime = contactMeta.createTime,
            modifyTime = contactMeta.modifyTime,
            labelIds = metaLabelIds,
        )

        // Exclusion is applied ONLY to contacts with no local row yet. A contact already on the
        // phone is left completely alone here: removing it is a separate, confirmed step, and
        // doing it from this loop would race the orphan sweep further down, which deletes any
        // live raw contact whose mapping has no android id. Nothing in this block can delete.
        var filterStale = false
        if (filterSession != null && filterSession.applies) {
            filterStale = filterSession.isStale(
                existing?.syncTag, metaLabelIds, contactMeta.name, contactMeta.size.toLong()
            )
            val hasLocalRow = existing?.androidRawContactId != null
            // Recorded even when the contact is one the user chose to keep. The sweep at the
            // end prunes the guard's sets by asking which contacts the rules still exclude, and
            // a kept contact that answered "no" there had its keep thrown away.
            if (hasLocalRow && filterSession.isKept(contactMeta.id)) {
                val cachedVerdict = me.proton.sync.contacts.filter.FilterTag.verdictOf(existing?.syncTag)
                val excludedNow =
                    (!filterStale && cachedVerdict == me.proton.sync.contacts.filter.RuleAction.EXCLUDE) ||
                        (headerVerdict is me.proton.sync.contacts.filter.FilterEvaluator.Verdict.Decided &&
                            headerVerdict.action == me.proton.sync.contacts.filter.RuleAction.EXCLUDE)
                if (excludedNow) filterSession.recordStillExcluded(contactMeta.id)
            }
            if (hasLocalRow && !filterSession.isKept(contactMeta.id)) {
                // Excluded but already on the phone. Nothing is removed here: the id is
                // collected and the user is asked once the whole pass has run, so the prompt
                // can state a real total rather than growing as the sync proceeds.
                val cachedVerdict = me.proton.sync.contacts.filter.FilterTag.verdictOf(existing?.syncTag)
                val excludedNow =
                    (!filterStale && cachedVerdict == me.proton.sync.contacts.filter.RuleAction.EXCLUDE) ||
                        (headerVerdict is me.proton.sync.contacts.filter.FilterEvaluator.Verdict.Decided &&
                            headerVerdict.action == me.proton.sync.contacts.filter.RuleAction.EXCLUDE)
                if (excludedNow) {
                    filterSession.recordPendingRemoval(contactMeta.id, contactMeta.name)
                    contactSyncDao.upsert(
                        me.proton.sync.db.entities.ContactSyncEntity(
                            accountName = parentAccountName,
                            protonContactId = contactMeta.id,
                            androidRawContactId = existing?.androidRawContactId,
                            // The old time on purpose. This contact is being held for a prompt,
                            // not downloaded, so recording the server's time would mark an edit
                            // as applied that never reached the phone: answer "keep on phone"
                            // and the change made on Proton in the meantime was gone for good.
                            lastModified = existing?.lastModified ?: 0L,
                            syncTag = filterSession.tagFor(
                                me.proton.sync.contacts.filter.RuleAction.EXCLUDE,
                                metaLabelIds, contactMeta.name, contactMeta.size.toLong()
                            ),
                            // Carried across: an upsert replaces the whole row, so a column not
                            // restated here would be silently dropped.
                            serverFn = existing?.serverFn,
                            serverHasN = existing?.serverHasN,
                            serverGiven = existing?.serverGiven,
                            serverFamily = existing?.serverFamily,
                        )
                    )
            return HeaderFilterStep.CONSUMED
                }
                // Undecidable from the header: fall through and fetch, the card phase decides.
            }
            if (!hasLocalRow) {
                val cachedVerdict = me.proton.sync.contacts.filter.FilterTag.verdictOf(existing?.syncTag)
                if (!filterStale && cachedVerdict == me.proton.sync.contacts.filter.RuleAction.EXCLUDE) {
                    // Settled on an earlier sync and nothing has changed since: no fetch, no
                    // decryption, no write.
                    filterSession.countCachedExclusion()
            return HeaderFilterStep.CONSUMED
                }
                if (headerVerdict is me.proton.sync.contacts.filter.FilterEvaluator.Verdict.Decided &&
                    headerVerdict.action == me.proton.sync.contacts.filter.RuleAction.EXCLUDE
                ) {
                    contactSyncDao.upsert(
                        me.proton.sync.db.entities.ContactSyncEntity(
                            accountName = parentAccountName,
                            protonContactId = contactMeta.id,
                            androidRawContactId = null,
                            lastModified = contactMeta.modifyTime,
                            syncTag = filterSession.tagFor(
                                me.proton.sync.contacts.filter.RuleAction.EXCLUDE,
                                metaLabelIds, contactMeta.name, contactMeta.size.toLong()
                            ),
                            // Carried across: an upsert replaces the whole row, so a column not
                            // restated here would be silently dropped.
                            serverFn = existing?.serverFn,
                            serverHasN = existing?.serverHasN,
                            serverGiven = existing?.serverGiven,
                            serverFamily = existing?.serverFamily,
                        )
                    )
                    filterSession.countAppliedExclusion()
            return HeaderFilterStep.CONSUMED
                }
            }
        }
        return if (filterStale) HeaderFilterStep.STALE else HeaderFilterStep.FRESH
    }

    /**
     * Route one header: hold, re-judge, unchanged, backfill, conflict or fetch.
     *
     * Deliberately not suspend, so the loop keeps the suspension points it had. The collections are the
     * caller's own and are added to here; passing copies would drop everything this decides, including the
     * conflict ids the later phases depend on.
     */
    private fun routeHeader(
        parentAccountName: String,
        provider: ContentProviderClient,
        contactMeta: ContactMeta,
        existing: me.proton.sync.db.entities.ContactSyncEntity?,
        dirtyRawContactIds: Set<Long>,
        downloadHolds: Map<String, ContactDownloadHolds.Hold>,
        filterStale: Boolean,
        nameModeReapply: Boolean,
        toFetch: MutableList<ContactMeta>,
        backfillOnly: MutableSet<String>,
        rejudgeNoWrite: MutableSet<String>,
        conflictUseRemote: MutableList<ContactMeta>,
        activeConflictIds: MutableSet<String>,
        tally: HeaderTally
    ) {
        if (ContactDownloadHolds.skips(
                downloadHolds[contactMeta.id], contactMeta.modifyTime,
                hasLocalRow = existing?.androidRawContactId != null,
                localEditPending = existing?.androidRawContactId?.let { it in dirtyRawContactIds } == true,
            )
        ) {
            // Proton's copy is newer than the phone's. An edit made while this sync runs would miss the
            // dirty snapshot above and go up unchecked, replacing it; held back, it meets the conflict
            // check next sync.
            if (downloadHolds[contactMeta.id]?.onPhone == true) activeConflictIds += contactMeta.id
            tally.skipped++
            return
        }

        // A mapping with no local row is a contact Proton still has and this phone does not.
        // It must be fetched whatever its modify time says, because "unchanged since the last
        // sync" is an answer about the server, and the thing that changed is here.
        //
        // Without this, switching contact filtering back off never returns the contacts it
        // excluded: their mappings survive with a null android id, nothing on Proton moves, so
        // every later sync skips them and they stay missing until a full resync. The same trap
        // catches any local row lost some other way.
        val missingLocally = existing != null && existing.androidRawContactId == null

        // A name-mode change only alters contacts Proton has no structured name for, so the
        // re-apply pass is scoped to those rather than rewriting the whole address book.
        // serverHasN is null on rows that predate the column; those are re-read once by the
        // backfill below, which decrypts but writes nothing to the phone.
        val nameModeStale = nameModeReapply && existing?.serverHasN != 1
        // Two generations of this bookkeeping, and both have to be able to fill themselves
        // in. The second clause is the one that is easy to miss: schema 10 added the given and
        // family columns, but every row already carried serverHasN from schema 9, so a trigger
        // that only asked "is serverHasN null" would never fire again and the new columns would
        // stay null for the life of the install, leaving preserveServerDisplayName permanently
        // inert on exactly the contacts it exists for.
        val needsServerNameBackfill = existing != null && (
            existing.serverHasN == null ||
                (existing.serverHasN == 1 &&
                    existing.serverGiven == null && existing.serverFamily == null)
            )
        // A stale verdict has to be re-decided, so it must not be short-circuited by the
        // unchanged check below. Without this a rules change would re-evaluate nothing,
        // because a contact whose modify time has not moved is otherwise skipped outright.
        //
        // The name backfill is deliberately NOT one of these conditions. It is bookkeeping, and
        // treating it as a reason to re-examine the contact sent every locally edited row into
        // the conflict branch below: after the upgrade every mapping has a null serverHasN, so
        // a contact that was merely dirty, with nothing changed on Proton, raised a conflict
        // notification saying it had been modified in both places, and uploadDirtyContacts then
        // refused to send it while the conflict was outstanding. The edit stopped travelling
        // and the notification came back every sync.
        // Re-judging is not a conflict. filterStale and nameModeStale mean "look at this
        // contact again", and routing it to the conflict branch told the user it had been
        // changed in both places when the server copy had not moved, then withheld its upload.
        val serverUnchanged = existing != null && existing.lastModified >= contactMeta.modifyTime
        if (serverUnchanged && !missingLocally && (filterStale || nameModeStale)) {
            // Re-judging needs the card, and taking this branch ahead of the conflict branch is
            // what stops a rules edit raising a false "changed in both places". But the card
            // phase writes what it fetches, and the server copy has NOT moved, so applying it
            // to a row holding an unsent local edit would overwrite that edit with older
            // content and call it a download. A rules edit touches every contact at once, so
            // this would have quietly reverted every pending change in the address book.
            // Judged, then, but not written: the upload still carries the edit to Proton.
            if (existing?.androidRawContactId != null &&
                existing.androidRawContactId in dirtyRawContactIds
            ) {
                rejudgeNoWrite.add(contactMeta.id)
            }
            toFetch.add(contactMeta)
        } else if (!filterStale && !missingLocally && !nameModeStale && serverUnchanged) {
            if (needsServerNameBackfill) {
                // Fetched only to learn what the card says. Decrypted and recorded, but
                // deliberately not written to the phone: rewriting every contact's rows once
                // per upgrade is how a provider that re-dirties sync-adapter writes turns a
                // bookkeeping pass into an upload of the whole address book.
                backfillOnly.add(contactMeta.id)
                toFetch.add(contactMeta)
            } else {
                tally.skipped++
            }
        } else if (existing?.androidRawContactId != null &&
            existing.androidRawContactId in dirtyRawContactIds) {
            // Conflict detected — check if user already made a decision
            val decision = ConflictResolutionReceiver.getDecision(
                context, parentAccountName, contactMeta.id
            )
            when (decision) {
                ConflictResolutionReceiver.DECISION_LOCAL -> {
                    // User chose local — skip download, upload will push local version
                    SyncLog.i(TAG, "Conflict resolved (local wins): ${contactMeta.id}")
                    ConflictResolutionReceiver.clearDecision(context, parentAccountName, contactMeta.id)
                }
                ConflictResolutionReceiver.DECISION_REMOTE -> {
                    // User chose remote — download server version, clear dirty flag
                    SyncLog.i(TAG, "Conflict resolved (remote wins): ${contactMeta.id}")
                    ConflictResolutionReceiver.clearDecision(context, parentAccountName, contactMeta.id)
                    conflictUseRemote.add(contactMeta)
                }
                ConflictResolutionReceiver.DECISION_SKIP -> {
                    // User chose skip — keep decision stored, don't re-notify
                    SyncLog.i(TAG, "Conflict skipped by user: ${contactMeta.id}")
                    activeConflictIds.add(contactMeta.id)
                    tally.conflicts++
                }
                else -> {
                    // No decision yet — show notification and skip for now
                    activeConflictIds.add(contactMeta.id)
                    tally.conflicts++
                    showConflictNotification(contactMeta, parentAccountName, existing.androidRawContactId, provider)
                }
            }
        } else {
            toFetch.add(contactMeta)
        }
    }

    /**
     * Decrypt and parse the cards of one fetched contact.
     *
     * @return null where the loop used to skip the contact: nothing decrypted, or no vCard in the result.
     */
    private fun decodeFetchedCard(
        parentAccountName: String,
        contactDetail: me.proton.sync.api.models.ContactDetail
    ): VCard? {
        // Decrypt contact cards
        val vCardString = contactDecryptor.decryptAndMergeCards(
            parentAccountName,
            contactDetail.cards
        )

        // Unescape vCard escaped commas in data URIs (PHOTO, LOGO)
        val fixedVCardString = vCardString.replace("base64\\,", "base64,")

        // Parse vCard
        val vCards = Ezvcard.parse(fixedVCardString).all()
        if (vCards.isEmpty()) return null

        return vCards.first()
    }

    /**
     * The branches that record what a card said without writing it to the phone: a backfill-only contact, a
     * contact a filter rule excludes, and one fetched only to be judged again while it carries an unsent
     * local edit.
     *
     * @return true when the contact is finished and the caller moves on to the next one.
     */
    private suspend fun recordWithoutWriting(
        parentAccountName: String,
        contactMeta: ContactMeta,
        vCard: VCard,
        existing: me.proton.sync.db.entities.ContactSyncEntity?,
        existingMappings: Map<String, me.proton.sync.db.entities.ContactSyncEntity>,
        filterSession: me.proton.sync.contacts.filter.ContactFilterSession?,
        backfillOnly: Set<String>,
        rejudgeNoWrite: Set<String>
    ): Boolean {
        if (contactMeta.id in backfillOnly) {
            existingMappings[contactMeta.id]?.let { row ->
                contactSyncDao.upsert(
                    row.copy(
                        serverFn = vCard.formattedName?.value,
                        serverHasN = if (hasStructuredName(vCard)) 1 else 0,
                        serverGiven = recordedNamePart(vCard, vCard.structuredName?.given),
                        serverFamily = recordedNamePart(vCard, vCard.structuredName?.family)
                    )
                )
            }
            return true
        }

        // Card phase: filter verdict with the decrypted card, the only place field-shape rules
        // such as "a display name and an email, with no first or last name" can be judged.
        // After the group resolution above so group names are available to the evaluation.
        val cardAction = filterSession?.cardVerdict(
            vCard = vCard,
            protonId = contactMeta.id,
            displayName = contactMeta.name,
            uid = contactMeta.uid,
            sizeBytes = contactMeta.size.toLong(),
            createTime = contactMeta.createTime,
            modifyTime = contactMeta.modifyTime,
            labelIds = contactMeta.labelIds ?: emptyList(),
        )

        // Same restriction as the header phase: a contact already on the phone is left
        // alone, so this can only ever decline to CREATE something. Removing an existing
        // contact is a separate, confirmed step.
        // Same reason as the header phase: a kept contact is still an excluded contact,
        // and the sweep needs to know that even though it is never asked about again.
        if (filterSession != null && filterSession.applies &&
            cardAction == me.proton.sync.contacts.filter.RuleAction.EXCLUDE &&
            existing?.androidRawContactId != null &&
            filterSession.isKept(contactMeta.id)
        ) {
            filterSession.recordStillExcluded(contactMeta.id)
        }
        if (filterSession != null && filterSession.applies &&
            cardAction == me.proton.sync.contacts.filter.RuleAction.EXCLUDE &&
            existing?.androidRawContactId != null &&
            !filterSession.isKept(contactMeta.id)
        ) {
            // Excluded, and already on the phone. Collected for confirmation; the existing
            // contact is left exactly as it is and is not overwritten by this download.
            filterSession.recordPendingRemoval(contactMeta.id, contactMeta.name)
            contactSyncDao.upsert(
                me.proton.sync.db.entities.ContactSyncEntity(
                    accountName = parentAccountName,
                    protonContactId = contactMeta.id,
                    androidRawContactId = existing.androidRawContactId,
                    // Held for a prompt, not downloaded, so the server's time would mark
                    // an edit as applied that never reached the phone. Same reasoning as
                    // the header-phase hold above.
                    lastModified = existing.lastModified,
                    syncTag = filterSession.tagFor(
                        me.proton.sync.contacts.filter.RuleAction.EXCLUDE,
                        contactMeta.labelIds ?: emptyList(),
                        contactMeta.name,
                        contactMeta.size.toLong(),
                    ),
                    serverFn = vCard.formattedName?.value,
                    serverHasN = if (hasStructuredName(vCard)) 1 else 0,
                    serverGiven = recordedNamePart(vCard, vCard.structuredName?.given),
                    serverFamily = recordedNamePart(vCard, vCard.structuredName?.family),
                )
            )
            return true
        }

        if (filterSession != null && filterSession.applies &&
            cardAction == me.proton.sync.contacts.filter.RuleAction.EXCLUDE &&
            existing?.androidRawContactId == null
        ) {
            contactSyncDao.upsert(
                me.proton.sync.db.entities.ContactSyncEntity(
                    accountName = parentAccountName,
                    protonContactId = contactMeta.id,
                    androidRawContactId = null,
                    lastModified = contactMeta.modifyTime,
                    syncTag = filterSession.tagFor(
                        me.proton.sync.contacts.filter.RuleAction.EXCLUDE,
                        contactMeta.labelIds ?: emptyList(),
                        contactMeta.name,
                        contactMeta.size.toLong(),
                    ),
                    serverFn = vCard.formattedName?.value,
                    serverHasN = if (hasStructuredName(vCard)) 1 else 0,
                    serverGiven = recordedNamePart(vCard, vCard.structuredName?.given),
                    serverFamily = recordedNamePart(vCard, vCard.structuredName?.family),
                )
            )
            filterSession.countAppliedExclusion()
            return true
        }

        if (contactMeta.id in rejudgeNoWrite) {
            // The filter verdict above has had its say, which is the whole reason this
            // contact was fetched. What remains is the write, and that is exactly what a
            // dirty row must not receive. The tag and the name columns are still recorded
            // so the contact counts as judged and is not fetched again next run.
            existingMappings[contactMeta.id]?.let { row ->
                contactSyncDao.upsert(
                    row.copy(
                        syncTag = filterSession?.tagFor(
                            cardAction ?: me.proton.sync.contacts.filter.RuleAction.SYNC,
                            contactMeta.labelIds ?: emptyList(),
                            contactMeta.name,
                            contactMeta.size.toLong(),
                        ) ?: row.syncTag,
                        serverFn = vCard.formattedName?.value,
                        serverHasN = if (hasStructuredName(vCard)) 1 else 0,
                        serverGiven = recordedNamePart(vCard, vCard.structuredName?.given),
                        serverFamily = recordedNamePart(vCard, vCard.structuredName?.family),
                    )
                )
            }
            SyncLog.d(TAG, "Re-judged ${contactMeta.id} without writing; it has an unsent local edit")
            return true
        }
        return false
    }

    /**
     * Write one decrypted contact to the phone, holding it instead when it is too large for the provider.
     *
     * [newlyHeld] and [activeConflictIds] are the caller's own collections, because the phases after the
     * download read what this adds to them.
     *
     * @return false when nothing was written and the caller moves on without counting the contact.
     */
    private suspend fun writeFetchedContact(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
        contactMeta: ContactMeta,
        vCard: VCard,
        existing: me.proton.sync.db.entities.ContactSyncEntity?,
        androidGroupIds: List<Long>,
        nameMode: ContactNameMode,
        filterSession: me.proton.sync.contacts.filter.ContactFilterSession?,
        downloadHolds: Map<String, ContactDownloadHolds.Hold>,
        newlyHeld: MutableList<String>,
        activeConflictIds: MutableSet<String>
    ): Boolean {
        val target = downloadTargetFor(provider, account, existing?.androidRawContactId)
        if (target == null) return false // downloadTargetFor logged why

        val outcome = ContactsMapper.writeContact(
            provider = provider,
            vCard = vCard,
            account = account,
            existingRawContactId = target.takeIf { it != NEW_ROW },
            androidGroupIds = androidGroupIds,
            nameMode = nameMode
        )
        if (outcome is ContactsMapper.WriteOutcome.TooLarge) {
            // Nothing written. When held, the phone's copy, if any, still counts as older than
            // Proton's, and its upload waits for the conflict check (see the header loop).
            if (handleTooLargeContact(parentAccountName, contactMeta, existing, target != NEW_ROW, outcome, filterSession, vCard)) {
                newlyHeld += contactMeta.id
                activeConflictIds += contactMeta.id
            }
            syncResult.stats.numSkippedEntries++
            return false
        }
        val rawContactId = (outcome as ContactsMapper.WriteOutcome.Written).rawContactId
        if (contactMeta.id in downloadHolds) {
            ContactDownloadHolds.release(
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context),
                parentAccountName, contactMeta.id
            )
        }

        // Write the full-resolution photo to the display-photo asset URI.
        // The batch above only wrote the small Photo.PHOTO thumbnail (which Android
        // hard-caps at ~96x96); without this call, a high-res photo would round-trip
        // as a postage stamp on the device. Cheap no-op when the vCard has no photo.
        ContactsMapper.writeDisplayPhotoFromVCard(provider, rawContactId, vCard, account)

        // Restore Samsung's profile_card portrait row if the vCard carries a
        // second PHOTO marked with X-SAMSUNG-PORTRAIT=1. No-op when the vCard
        // has no portrait — leaves any pre-existing local row untouched, which
        // also handles the migration case where a user upgraded after setting
        // a portrait offline (we won't accidentally erase a row that hasn't yet
        // been mirrored to the cloud).
        ContactsMapper.writeSamsungProfileCardFromVCard(provider, rawContactId, vCard, account)

        // Update sync mapping. The filter verdict rides along in sync_tag so the next sync
        // can tell "already judged, nothing changed" from "needs judging again", and so a
        // rules change invalidates every cached verdict exactly once.
        contactSyncDao.upsert(
            ContactSyncEntity(
                accountName = parentAccountName,
                protonContactId = contactMeta.id,
                androidRawContactId = rawContactId,
                lastModified = contactMeta.modifyTime,
                syncTag = filterSession?.takeIf { it.applies }?.tagFor(
                    me.proton.sync.contacts.filter.RuleAction.SYNC,
                    contactMeta.labelIds ?: emptyList(),
                    contactMeta.name,
                    contactMeta.size.toLong(),
                ),
                // What the card actually said, recorded before Android gets to complete it.
                // The upload consults these so it never sends back a name the provider
                // invented; see ContactNameMode.
                serverFn = vCard.formattedName?.value,
                serverHasN = if (hasStructuredName(vCard)) 1 else 0,
                serverGiven = recordedNamePart(vCard, vCard.structuredName?.given),
                serverFamily = recordedNamePart(vCard, vCard.structuredName?.family)
            )
        )

        // Clear dirty flag so we don't try to upload back what we just synced. Not on a row
        // the user deleted while this sync was fetching (the dirty snapshot is taken before
        // the fetch, which can take a minute): clearing it lost that deletion for good.
        SyncUtils.clearContactDirtyFlag(provider, account, rawContactId, TAG, onlyIfNotDeleted = true)
        return true
    }

    private suspend fun uploadDirtyContacts(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
        activeConflictIds: Set<String> = emptySet(),
        existingLabelNames: MutableSet<String> = mutableSetOf(),
        filterSession: me.proton.sync.contacts.filter.ContactFilterSession? = null,
        /** Read here rather than passed from the caller so a manual upload-only run agrees with it. */
        nameMode: ContactNameMode = ContactNameMode.of(context, parentAccountName)
    ) {
        // A forced Remote→Local run never gets here: performContactsSync calls
        // clearDirtyAfterForcedDownload instead.
        val dirtyContacts = snapshotDirtyContacts(account, provider) ?: return
        val orphanedDeletedIds = mutableListOf<Long>()

        val pendingCreates = mutableListOf<Pair<Long, List<ContactCard>>>()
        // New contacts Proton refused this run (raw id, reason for the user), for one notification.
        val refusedCreates = mutableListOf<Pair<Long, String>>()
        val tally = UploadTally()
        if (dirtyContacts.isNotEmpty()) {
            SyncLog.i(TAG, "Uploading ${dirtyContacts.size} dirty contacts to Proton")
        }
        for (dc in dirtyContacts) {
                val rawContactId = dc.rawContactId
                val deleted = dc.deleted

                // Find the Proton contact ID from our mapping
                val mapping = contactSyncDao.getByAndroidId(parentAccountName, rawContactId)

                if (deleted == 1 && mapping == null) {
                    // Orphaned deleted contact — batch cleanup below
                    orphanedDeletedIds.add(rawContactId)
                    continue
                }

                if (!passesConflictGate(account, parentAccountName, provider, rawContactId, mapping, activeConflictIds)) {
                    continue
                }

                try {
                    if (deleted == 1 && mapping != null) {
                        uploadLocalDeletion(account, parentAccountName, provider, syncResult, rawContactId, mapping)
                    } else {
                        uploadModifiedContact(
                            account, parentAccountName, provider, syncResult, rawContactId, mapping, nameMode,
                            filterSession, existingLabelNames, pendingCreates, refusedCreates, tally,
                            dirtyContacts.size
                        )
                    }
                } catch (e: ServerCardUnavailable) {
                    // Proton's copy could not be read, so the edit waits rather than going up without the
                    // KEY, X-PM-* and UID only that copy has. Counted like a refused update: a passing
                    // failure is an I/O error, which the sync reports and retries; anything else is skipped.
                    SyncLog.w(TAG, "Holding contact $rawContactId for a later sync: ${e.message}")
                    if (e.transient) syncResult.stats.numIoExceptions++ else syncResult.stats.numSkippedEntries++
                } catch (e: ContactDecryptor.EncryptionUnavailable) {
                    // Deliberately does NOT clear the dirty flag, unlike the general case below.
                    // The keys were unusable this run; the edit is still good and belongs on Proton,
                    // so it waits for a sync that can encrypt it rather than being dropped.
                    SyncLog.w(TAG, "Holding contact $rawContactId for a later sync: ${e.message} (${e.cause?.javaClass?.simpleName})")
                    syncResult.stats.numSkippedEntries++
                } catch (e: IllegalArgumentException) {
                    // A card that cannot be SERIALISED is not a card that should be thrown away.
                    // This lands here when even a repaired vCard is refused, and clearing the dirty
                    // flag would discard an edit the user made and never retry it. Held instead,
                    // exactly like the unusable-keys case above.
                    SyncLog.w(TAG, "Holding contact $rawContactId: its vCard cannot be written (${e.message})")
                    if (mapping == null && deleted == 0) {
                        // A new contact held this way was retried every sync and never mentioned. Count it
                        // like a refusal, so the user hears about it and the retries stop after three.
                        recordCreateRefusal(
                            provider, account, parentAccountName, rawContactId, "vCard cannot be written",
                            context.getString(me.proton.sync.R.string.notification_contacts_rejected_local_reason),
                            refusedCreates
                        )
                    }
                    syncResult.stats.numSkippedEntries++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Latent today, because runUninterruptible's scope is never cancelled, but the generic catch
                    // below CLEARS THE DIRTY FLAG for a mapped contact, so a cancellation arriving here would
                    // throw the user's edit away silently. flushBatchCreate already rethrows for the same reason.
                    throw e
                } catch (e: Exception) {
                    if (deleted == 1) {
                        // A deletion that could not be sent (a dropped connection or a timeout throws
                        // out of the API call) stays pending and is retried next sync, as the calendar
                        // does. Clearing the flag here lost it: the contact was gone from the phone,
                        // still on Proton, and the tombstone was invisible to every later upload.
                        SyncLog.e(TAG, "Failed to send the deletion of contact $rawContactId; retrying next sync", e)
                    } else if (mapping == null) {
                        // A contact created on the phone. Clearing its flag here handed it to the orphan
                        // sweep, which deleted the only copy on the next sync.
                        if (e is android.os.RemoteException || e is java.io.IOException) {
                            // The contacts provider or the connection, not the contact: no strike.
                            SyncLog.w(TAG, "New contact $rawContactId could not be prepared (${e.javaClass.simpleName}); it stays pending for the next sync")
                        } else {
                            SyncLog.e(TAG, "Failed to prepare new contact $rawContactId for Proton", e)
                            recordCreateRefusal(
                                provider, account, parentAccountName, rawContactId, e.javaClass.simpleName,
                                context.getString(me.proton.sync.R.string.notification_contacts_rejected_local_reason),
                                refusedCreates
                            )
                        }
                    } else {
                        SyncLog.e(TAG, "Failed to process dirty contact $rawContactId, clearing dirty flag", e)
                        SyncUtils.clearContactDirtyFlag(provider, account, rawContactId, TAG, onlyIfNotDeleted = true)
                    }
                    syncResult.stats.numSkippedEntries++
                }
            }

        // Flush remaining batch creates
        if (pendingCreates.isNotEmpty()) {
            flushBatchCreate(pendingCreates.toList(), parentAccountName, provider, account, syncResult, refusedCreates)
            pendingCreates.clear()
        }
        notifyRefusedCreates(parentAccountName, provider, refusedCreates)
        if (tally.processedUpdates > 0 && tally.processedUpdates % 50 != 0) {
            SyncLog.i(TAG, "Uploaded ${tally.processedUpdates} contact updates total")
        }

        deleteUnmappedTombstones(account, provider, orphanedDeletedIds)
    }

    /** One dirty raw contact, as read for upload. */
    private data class DirtyContact(val rawContactId: Long, val deleted: Int)

    /**
     * The dirty raw contacts of this account, read into a list before anything writes.
     *
     * @return null when the provider gives no cursor, which means the caller has nothing to do.
     */
    private fun snapshotDirtyContacts(
        account: Account,
        provider: ContentProviderClient
    ): List<DirtyContact>? {
        SyncLog.d(TAG, "Checking dirty contacts for account=${account.name} type=${account.type}")
        val cursor = provider.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.DELETED
            ),
            "${ContactsContract.RawContacts.DIRTY} = 1 AND " +
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                // A download that failed part way and could not be removed: never Proton's to receive.
                "(${ContactsContract.RawContacts.SYNC4} IS NULL OR ${ContactsContract.RawContacts.SYNC4} != ?)",
            arrayOf(account.name, account.type, ContactsMapper.PARTIAL_WRITE_MARKER),
            null
        ) ?: return null

        SyncLog.d(TAG, "Found ${cursor.count} dirty contacts")

        // Snapshot dirty contacts so cursor isn't invalidated by clearDirtyFlag updates
        val dirtyContacts = mutableListOf<DirtyContact>()
        try {
            while (cursor.moveToNext()) {
                dirtyContacts.add(DirtyContact(cursor.getLong(0), cursor.getInt(1)))
            }
        } finally {
            cursor.close()
        }
        return dirtyContacts
    }

    /** Tell the user about new contacts Proton would not take, or withdraw the notice when none are stuck. */
    private fun notifyRefusedCreates(
        parentAccountName: String,
        provider: ContentProviderClient,
        refusedCreates: List<Pair<Long, String>>
    ) {
        // Every refusal, not only the third: a contact that cannot reach Proton exists nowhere else, and
        // the user should know from the first failed attempt, with the name and the reason. The list is
        // everything still stuck, so a new refusal cannot replace the notice about an earlier one; it
        // is posted only when something failed in this run, and withdrawn once nothing is stuck.
        val stillStuck = ContactUploadRefusals.tracked(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context), parentAccountName
        )
        if (refusedCreates.isNotEmpty() && stillStuck.isNotEmpty()) {
            SyncNotificationHelper.notifyContactsRejected(
                context, parentAccountName,
                stillStuck.map { (rawId, reason) -> displayNameOf(provider, rawId) to reason }
            )
        } else if (stillStuck.isEmpty()) {
            SyncNotificationHelper.cancelContactsRejected(context, parentAccountName)
        }
    }

    /** Remove the local tombstones of contacts Proton never knew, which nothing else would clean up. */
    private fun deleteUnmappedTombstones(
        account: Account,
        provider: ContentProviderClient,
        orphanedDeletedIds: List<Long>
    ) {
        // Batch-delete orphaned deleted contacts (no server mapping, already deleted locally)
        if (orphanedDeletedIds.isNotEmpty()) {
            SyncLog.i(TAG, "Cleaning up ${orphanedDeletedIds.size} orphaned deleted contacts")
            for (id in orphanedDeletedIds) {
                try {
                    provider.delete(
                        SyncUtils.asCallerSyncAdapter(
                            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id),
                            account
                        ),
                        null, null
                    )
                } catch (e: Exception) {
                    // Ignore individual cleanup failures
                }
            }
        }
    }

    /**
     * Batch-create up to 10 contacts in one API call (the batch size Proton's web client uses,
     * WebClients ADD_CONTACTS_MAX_SIZE).
     *
     * A contact that does not reach Proton keeps its dirty flag, because it exists only on this
     * phone and the orphan sweep deletes unmapped contacts that are not dirty. What happens instead
     * depends on why it failed (see [ContactUploadRefusals]): no connection, a timeout, a server
     * error, rate limiting or an expired session leave the whole batch pending with no strike; a
     * batch Proton refuses outright is retried one contact at a time, so one bad contact cannot hold
     * back the other nine; a contact Proton refuses counts a strike, and after three the app stops.
     * Never throws except for cancellation.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun flushBatchCreate(
        creates: List<Pair<Long, List<ContactCard>>>,
        parentAccountName: String,
        provider: ContentProviderClient,
        account: Account,
        syncResult: SyncResult,
        refused: MutableList<Pair<Long, String>>
    ) {
        if (creates.isEmpty()) return
        SyncLog.i(TAG, "Batch-creating ${creates.size} contacts on Proton")

        val bodies = creates.map { (_, cards) -> CreateContactBody(cards = cards) }
        val response = try {
            apiClient.createContacts(parentAccountName, CreateContactRequest(contacts = bodies))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not reach Proton to create ${creates.size} contact(s) (${e.javaClass.simpleName}); they stay pending for the next sync")
            syncResult.stats.numIoExceptions++
            return
        }

        if (!response.isSuccessful) {
            val code = response.code()
            val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
            val protonCode = me.proton.sync.api.protonErrorCode(errorBody)
            val detail = "HTTP $code" + (protonCode?.let { ", Proton code $it" } ?: "")
            if (ContactUploadRefusals.isTransientHttp(code)) {
                SyncLog.w(TAG, "Batch contact create failed ($detail); ${creates.size} contact(s) stay pending for the next sync")
                syncResult.stats.numIoExceptions++
                return
            }
            if (creates.size > 1) {
                SyncLog.w(TAG, "Batch contact create refused ($detail); retrying the ${creates.size} contacts one at a time")
                for (single in creates) {
                    flushBatchCreate(listOf(single), parentAccountName, provider, account, syncResult, refused)
                }
                return
            }
            val protonError = protonErrorText(errorBody)
            recordCreateRefusal(
                provider, account, parentAccountName, creates[0].first, detail,
                protonError?.let { "$it ($detail)" } ?: detail, refused
            )
            syncResult.stats.numSkippedEntries++
            return
        }

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val body = response.body()
        val responsesList = body?.get("Responses") as? List<Map<*, *>> ?: emptyList()
        for ((index, pair) in creates.withIndex()) {
            val (rawContactId, _) = pair
            val resp = responsesList.getOrNull(index)
            val innerResp = resp?.get("Response") as? Map<*, *>
            val innerCode = (innerResp?.get("Code") as? Number)?.toInt()
            if (innerCode == 1000 || innerCode == 1001) {
                val contact = innerResp?.get("Contact") as? Map<*, *>
                val protonContactId = contact?.get("ID") as? String
                if (protonContactId != null) {
                    contactSyncDao.upsert(
                        me.proton.sync.db.entities.ContactSyncEntity(
                            accountName = parentAccountName,
                            protonContactId = protonContactId,
                            androidRawContactId = rawContactId,
                            lastModified = System.currentTimeMillis() / 1000
                        )
                    )
                } else {
                    // Created, but the reply carries no id to map it by. The next sync links this row to
                    // Proton's copy by the uid stored in SYNC1 (adoptPendingCreates); keeping it dirty
                    // instead would create a second copy on Proton.
                    SyncLog.w(TAG, "Proton created contact $rawContactId but returned no id; the next sync links it to Proton's copy")
                }
                ContactUploadRefusals.forget(prefs, parentAccountName, rawContactId)
                SyncUtils.clearContactDirtyFlag(provider, account, rawContactId, TAG, onlyIfNotDeleted = true)
                syncResult.stats.numInserts++
            } else {
                // A refusal, or no verdict at all for this contact (a short or unreadable reply). The
                // second counts as a strike too: retried freely, a reply format this code no longer
                // understands would create a new copy on Proton at every sync.
                val reason = innerCode?.let { "Proton code $it" } ?: "no answer in Proton's reply"
                val protonError = (innerResp?.get("Error") as? String)?.takeIf { it.isNotBlank() }
                recordCreateRefusal(
                    provider, account, parentAccountName, rawContactId, reason,
                    protonError?.let { "$it ($reason)" } ?: reason, refused
                )
                syncResult.stats.numSkippedEntries++
            }
        }
        SyncLog.i(TAG, "Batch create completed: ${creates.size} contacts processed")
    }

    /**
     * Count a refusal of a contact created on the phone and, on the third, stop retrying it.
     * Giving up clears the dirty flag and records the contact in [ContactUploadRefusals], which is
     * what keeps the orphan sweep from deleting it. The contact stays on the phone.
     *
     * @param reason codes only, for the log.
     * @param userReason Proton's own message where it sent one, for the notification only: it can
     *   quote what the contact holds, so it never goes to the log.
     * @param refused every refusal of this run, (raw id, [userReason]), for the notification.
     */
    private fun recordCreateRefusal(
        provider: ContentProviderClient,
        account: Account,
        parentAccountName: String,
        rawContactId: Long,
        reason: String,
        userReason: String,
        refused: MutableList<Pair<Long, String>>
    ) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val attempt = ContactUploadRefusals.recordStrike(prefs, parentAccountName, rawContactId, userReason)
        if (attempt >= ContactUploadRefusals.MAX_ATTEMPTS) {
            // Protect first, synchronously, then clear the flag: the other order, or a protection still
            // waiting to be written, leaves a clean, unmapped, unprotected row, which the sweep deletes.
            if (ContactUploadRefusals.markRefused(prefs, parentAccountName, rawContactId)) {
                SyncUtils.clearContactDirtyFlag(provider, account, rawContactId, TAG, onlyIfNotDeleted = true)
                SyncLog.e(TAG, "Proton refused new contact $rawContactId ($reason), attempt $attempt of ${ContactUploadRefusals.MAX_ATTEMPTS}: giving up; it stays on this phone only")
            } else {
                SyncLog.w(TAG, "New contact $rawContactId refused ($reason) but its protection could not be saved; it stays pending")
            }
        } else {
            SyncLog.w(TAG, "Proton refused new contact $rawContactId ($reason), attempt $attempt of ${ContactUploadRefusals.MAX_ATTEMPTS}: it stays pending and is retried next sync")
        }
        refused += rawContactId to userReason
    }

    /**
     * The contacts created on the phone that the app gave up creating on Proton, after dropping any
     * that no longer exist. If the phone's contacts cannot be read, nothing is dropped.
     */
    private fun refusedCreateIds(account: Account, parentAccountName: String, provider: ContentProviderClient): Set<Long> {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val tracked = ContactUploadRefusals.refusedIds(prefs, parentAccountName)
        val live = try {
            provider.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                    "${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(account.name, account.type),
                null
            )?.use { c -> HashSet<Long>().also { ids -> while (c.moveToNext()) ids.add(c.getLong(0)) } }
        } catch (e: Exception) {
            null
        }
        if (live != null) ContactUploadRefusals.retain(prefs, parentAccountName) { it in live }
        return if (live != null) tracked.filterTo(HashSet()) { it in live } else tracked
    }

    /** The "Error" message of a Proton JSON error body, for the user-facing notification only. */
    private fun protonErrorText(body: String?): String? = try {
        body?.let { org.json.JSONObject(it).optString("Error") }?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /**
     * The uid a contact created on the phone uploads with, the same on every attempt: read from the raw
     * contact's SYNC1 column, or made and stored there on the first attempt. Null if it cannot be
     * stored, in which case the caller uses a one-off uid (no loss, only no protection against a
     * duplicate after a lost reply). This app writes nothing else to SYNC1.
     */
    private fun pendingCreateUid(provider: ContentProviderClient, account: Account, rawContactId: Long): String? = try {
        val itemUri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId)
        val stored = provider.query(itemUri, arrayOf(ContactsContract.RawContacts.SYNC1), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        stored?.takeIf { it.isNotBlank() } ?: newContactUid().takeIf { uid ->
            provider.update(
                SyncUtils.asCallerSyncAdapter(itemUri, account),
                ContentValues().apply { put(ContactsContract.RawContacts.SYNC1, uid) },
                null, null
            ) > 0
        }
    } catch (e: Exception) {
        SyncLog.w(TAG, "Could not keep a stable uid for new contact $rawContactId: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Link contacts created on the phone to the copy Proton already has, when a create reached Proton
     * but its reply did not reach the phone (a timeout, a dropped connection, a gateway error). Such a
     * contact stays pending and unmapped, so without this the download inserted Proton's copy as a
     * second contact and the retry created a third on Proton. Matched by the stable uid the upload
     * stores in SYNC1; runs before the mappings are read. A copy untouched since its create is recorded
     * as current, so the upload sends the phone's version as an update; a copy changed on Proton since
     * then is recorded at its create time, so the download sees that change instead of hiding it.
     */
    private suspend fun adoptPendingCreates(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        headers: List<ContactMeta>
    ) {
        try {
            val pending = HashMap<String, Long>()
            provider.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SYNC1),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                    // Live rows, and rows deleted on the phone whose deletion is still pending: linked,
                    // the upload sends that deletion to Proton; unlinked, Proton's copy came back.
                    "(${ContactsContract.RawContacts.DELETED} = 0 OR ${ContactsContract.RawContacts.DIRTY} = 1) AND " +
                    "${ContactsContract.RawContacts.SYNC1} IS NOT NULL",
                arrayOf(account.name, account.type),
                null
            )?.use { c -> while (c.moveToNext()) c.getString(1)?.let { uid -> pending[uid] = c.getLong(0) } }
            if (pending.isEmpty()) return
            // A few seconds between Proton's create and modify times still count as the create itself.
            val createTolerance = 5L
            val mappings = contactSyncDao.getAllByAccount(parentAccountName)
            val mappedRaw = mappings.mapNotNullTo(HashSet()) { it.androidRawContactId }
            val mappedProton = mappings.mapTo(HashSet()) { it.protonContactId }
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            var adopted = 0
            for (header in headers) {
                if (header.id in mappedProton) continue
                val rawId = pending[header.uid] ?: continue
                if (rawId in mappedRaw) continue
                contactSyncDao.upsert(
                    ContactSyncEntity(
                        accountName = parentAccountName,
                        protonContactId = header.id,
                        androidRawContactId = rawId,
                        // The create time, not the modify time, when the copy on Proton was changed after
                        // it was created (say, on the web while the reply was lost). With the modify time
                        // that edit looked already applied: no download, no conflict, and the phone's
                        // version then overwrote it on upload. With the create time a clean row downloads
                        // the edit and a row with pending changes raises the usual conflict prompt.
                        lastModified = if (header.modifyTime - header.createTime <= createTolerance) header.modifyTime
                            else header.createTime
                    )
                )
                mappedRaw.add(rawId)
                ContactUploadRefusals.forget(prefs, parentAccountName, rawId)
                adopted++
            }
            if (adopted > 0) {
                SyncLog.i(TAG, "Linked $adopted contact(s) created on this phone to the copy Proton already had; a lost reply had left them pending")
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not check pending contacts against Proton: ${e.javaClass.simpleName}")
        }
    }

    /**
     * A downloaded card [ContactsMapper.writeContact] could not store.
     *
     * When Proton's copy has not changed since the phone's was written, the fetch was only a re-judge (the
     * contact filter rules or the name setting changed): the phone already has the current version, so the
     * judgement is recorded as the no-write re-judge does and nothing is held. Holding it would call the
     * phone's copy outdated and freeze the stale verdict until the contact changed.
     *
     * Otherwise the contact is held (see [ContactDownloadHolds]) and true returned. A mapping naming a row
     * that is gone, or not this account's, is unlinked first, so the hold ("not on this phone") matches it
     * next sync instead of the contact being fetched and reported again on every sync.
     */
    private suspend fun handleTooLargeContact(
        parentAccountName: String,
        contactMeta: ContactMeta,
        existing: ContactSyncEntity?,
        onPhone: Boolean,
        outcome: ContactsMapper.WriteOutcome.TooLarge,
        filterSession: me.proton.sync.contacts.filter.ContactFilterSession?,
        vCard: VCard,
    ): Boolean {
        val sizes = "${outcome.totalBytes / 1024} KB, largest field ${outcome.largestRowBytes / 1024} KB"
        if (onPhone && existing != null && existing.lastModified >= contactMeta.modifyTime) {
            contactSyncDao.upsert(
                existing.copy(
                    syncTag = filterSession?.takeIf { it.applies }?.tagFor(
                        me.proton.sync.contacts.filter.RuleAction.SYNC,
                        contactMeta.labelIds ?: emptyList(),
                        contactMeta.name,
                        contactMeta.size.toLong(),
                    ) ?: existing.syncTag,
                    serverFn = vCard.formattedName?.value,
                    serverHasN = if (hasStructuredName(vCard)) 1 else 0,
                    serverGiven = recordedNamePart(vCard, vCard.structuredName?.given),
                    serverFamily = recordedNamePart(vCard, vCard.structuredName?.family),
                )
            )
            SyncLog.d(TAG, "Contact ${contactMeta.id} is unchanged on Proton but too large to rewrite ($sizes); the phone keeps it as it is")
            return false
        }
        if (!onPhone && existing?.androidRawContactId != null) {
            contactSyncDao.upsert(existing.copy(androidRawContactId = null))
        }
        ContactDownloadHolds.hold(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context),
            parentAccountName,
            ContactDownloadHolds.Hold(contactMeta.id, contactMeta.modifyTime, onPhone)
        )
        SyncLog.w(
            TAG,
            "Contact ${contactMeta.id} is too large for this phone's contacts storage ($sizes): " +
                (if (onPhone) "the phone keeps its older version" else "not written") +
                "; skipped until it changes on Proton"
        )
        return true
    }

    /**
     * Remove new contacts a download left half-written (see [ContactsMapper.PARTIAL_WRITE_MARKER]): the
     * write failed and so did removing it. Hard-deleted as the sync adapter, so nothing goes to Proton; the
     * contact itself comes down again in this sync, since it never got a mapping.
     */
    private fun purgePartialWrites(account: Account, provider: ContentProviderClient) {
        try {
            val removed = provider.delete(
                SyncUtils.asCallerSyncAdapter(ContactsContract.RawContacts.CONTENT_URI, account),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                    "${ContactsContract.RawContacts.SYNC4} = ?",
                arrayOf(account.name, account.type, ContactsMapper.PARTIAL_WRITE_MARKER)
            )
            if (removed > 0) SyncLog.i(TAG, "Removed $removed half-written contact(s) left by an earlier download")
        } catch (e: Exception) {
            SyncLog.w(TAG, "Could not remove half-written contacts: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Forget holds for contacts gone from Proton, then tell the user about the rest: a notification
     * naming every contact still too large, posted when this sync held a new one and brought up to date
     * while still shown when the list changed, withdrawn once none is left. Names come from Proton's
     * contact list and appear in the notification only.
     */
    private fun reportTooLargeContacts(
        parentAccountName: String,
        allContacts: List<ContactMeta>,
        newlyHeld: Collection<String>,
        heldAtStart: Set<String>,
    ) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val metaById = allContacts.associateBy { it.id }
        ContactDownloadHolds.retain(prefs, parentAccountName) { it in metaById }
        val holds = ContactDownloadHolds.all(prefs, parentAccountName)
        if (holds.isEmpty()) {
            SyncNotificationHelper.cancelContactsTooLarge(context, parentAccountName)
            return
        }
        if (newlyHeld.isEmpty() &&
            (holds.keys == heldAtStart || !SyncNotificationHelper.isContactsTooLargeShown(context, parentAccountName))
        ) {
            return
        }
        SyncNotificationHelper.notifyContactsTooLarge(
            context, parentAccountName,
            holds.values.map { hold ->
                metaById[hold.protonId]?.name to context.getString(
                    if (hold.onPhone) me.proton.sync.R.string.notification_contacts_too_large_outdated
                    else me.proton.sync.R.string.notification_contacts_too_large_missing
                )
            }
        )
    }

    /** A raw contact's display name for the user-facing notification only; never logged. */
    private fun displayNameOf(provider: ContentProviderClient, rawContactId: Long): String? = try {
        provider.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
            null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /**
     * Copies back the properties Proton keeps and this app has no home for on Android.
     *
     * An upload replaces the contact's whole card set with one rebuilt from provider rows, and the
     * provider has nowhere to put a pinned PGP key or a per-contact encryption preference. So
     * changing a phone number on the handset silently stripped `KEY` and every `X-PM-*` from the
     * card, and Proton Mail stopped encrypting to that person. Nothing said so, on either side.
     *
     * Fetched per contact rather than cached: this runs only for contacts with a local edit, and a
     * stale copy of somebody's encryption settings is worse than an extra request. The same fetch
     * gives the card the UID Proton stores for the contact ([uploadUidFor]).
     */
    private suspend fun preserveServerOnlyProperties(
        vCard: VCard,
        accountName: String,
        protonContactId: String,
    ) {
        val (storedUid, serverCard) = try {
            val response = apiClient.getContact(accountName, protonContactId)
            // A refused or empty reply is no more skippable than an exception below: returning here
            // uploaded the card without KEY and X-PM-* after all, and now it would lose the UID too.
            val detail = response.body()?.contact ?: throw ServerCardUnavailable(
                "Proton's copy of contact $protonContactId could not be read (HTTP ${response.code()}${response.protonErrorSuffix()})",
                transient = ContactUploadRefusals.isTransientHttp(response.code())
            )
            val merged = contactDecryptor.decryptAndMergeCards(accountName, detail.cards)
            val card = Ezvcard.parse(merged.replace(ESCAPED_BASE64, "base64,")).first()
                ?: throw ServerCardUnavailable("Proton's copy of contact $protonContactId has no readable card", transient = false)
            detail.uid to card
        } catch (e: ServerCardUnavailable) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not skippable. Carrying on would upload a card with no KEY and no X-PM-*, which is
            // exactly the loss this function exists to prevent, just triggered by a dropped
            // connection instead of by design. The contact waits for a sync that can read it.
            throw ContactDecryptor.EncryptionUnavailable(e)
        }
        vCard.uid = ezvcard.property.Uid(uploadUidFor(storedUid, serverCard.uid?.value))
        // These properties are bound to one address by their vCard group: Proton stores
        // "item1.EMAIL" alongside "item1.X-PM-ENCRYPT", and the group is the only thing tying the
        // setting to the address. addExtendedProperty(name, value) makes an ungrouped property, so
        // copying them that way detached every per-address preference from its address, which is
        // the loss this function was added to prevent. The group is carried across, and the local
        // Email carrying the same address is given the same group so the pairing survives the
        // renumbering that ContactDecryptor.addItemGroups would otherwise do by position.
        val groupByAddress = serverCard.emails
            .filter { !it.group.isNullOrEmpty() && !it.value.isNullOrBlank() }
            .associate { it.value.trim().lowercase() to it.group }
        for (email in vCard.emails) {
            val group = groupByAddress[email.value?.trim()?.lowercase()] ?: continue
            email.group = group
        }
        val liveGroups = vCard.emails.mapNotNull { it.group }.toSet()

        for (key in serverCard.keys) {
            // An orphaned pin is worse than none: it would claim to encrypt to an address the
            // contact no longer has, or with renumbering, to somebody else's.
            if (key.group.isNullOrEmpty() || key.group in liveGroups) vCard.addKey(key)
            else SyncLog.w(TAG, "Dropping a pinned key whose address is gone for $protonContactId")
        }
        for (property in serverCard.extendedProperties) {
            val name = property.propertyName?.uppercase().orEmpty()
            // X-PM-* are Proton's own per-address settings. Everything else here is a custom field
            // some other client wrote, which this app has no column for and therefore cannot have
            // read off the phone; the three X- names the upload builder writes itself are excluded
            // so the server copy cannot overwrite what the phone just said.
            val ours = name.startsWith("X-PM-") ||
                name.startsWith("X-PHONETIC-") || name == "X-ABDATE" || name == "X-SIP"
            if (!ours && name.isNotEmpty()) {
                vCard.addExtendedProperty(property.propertyName, property.value).group = property.group
                continue
            }
            if (!name.startsWith("X-PM-")) continue
            if (!property.group.isNullOrEmpty() && property.group !in liveGroups) {
                SyncLog.w(TAG, "Dropping $name whose address is gone for $protonContactId")
                continue
            }
            val copy = vCard.addExtendedProperty(property.propertyName, property.value)
            copy.group = property.group
        }

        // Standard properties this app has no column for. The upload rebuilds the card from Android
        // rows, so anything it cannot represent simply was not there to write, and the upload
        // replaced the whole card: an IMPP handle, a GENDER, a LANG, a TZ entered in Proton's web
        // client was destroyed by an unrelated phone-side edit, silently and on the first sync
        // after it. Carried by exclusion rather than by a list of names, so a property nobody has
        // thought of yet survives too. The managed set below is what the upload builder writes;
        // re-adding any of those would resurrect the very rows the user just deleted.
        val managed = setOf(
            ezvcard.property.FormattedName::class.java,
            ezvcard.property.StructuredName::class.java,
            ezvcard.property.Email::class.java,
            ezvcard.property.Telephone::class.java,
            ezvcard.property.Address::class.java,
            ezvcard.property.Url::class.java,
            ezvcard.property.Note::class.java,
            ezvcard.property.Title::class.java,
            ezvcard.property.Role::class.java,
            ezvcard.property.Photo::class.java,
            ezvcard.property.Organization::class.java,
            ezvcard.property.Nickname::class.java,
            ezvcard.property.Birthday::class.java,
            ezvcard.property.Anniversary::class.java,
            ezvcard.property.Categories::class.java,
            ezvcard.property.Related::class.java,
            ezvcard.property.Uid::class.java,
            ezvcard.property.Key::class.java,
            ezvcard.property.Kind::class.java,
            ezvcard.property.ProductId::class.java,
            ezvcard.property.Revision::class.java,
            ezvcard.property.RawProperty::class.java,
        )
        var carried = 0
        for (property in serverCard.properties) {
            if (property.javaClass in managed) continue
            vCard.addProperty(property)
            carried++
        }
        if (carried > 0) {
            SyncLog.i(TAG, "Carried $carried unmapped server properties for $protonContactId")
        }
    }

    /** Proton's copy of a contact could not be read, so its upload waits; [transient] ones the sync retries. */
    private class ServerCardUnavailable(message: String, val transient: Boolean) : Exception(message)

    /**
     * Keep Proton's display name when the phone's is only Android's shortening of it.
     *
     * [stripInventedName] protects contacts Proton has NO structured name for. This protects the
     * opposite population, and the loss is quieter. Android derives a contact's display name from
     * the given and family names alone, so everything else in Proton's FN, a title, a middle name,
     * a parenthetical, a second surname, is dropped on the way to the phone and stored nowhere on
     * it: "Dr. Max Erik Mustermann" arrives as given=Max, family=Mustermann and displays as "Max
     * Mustermann". The upload takes the FN straight from that display name, so the first local edit of
     * any kind, to a phone number, to an email, replaces Proton's full name with the short one and
     * nothing puts it back. Contacts in real address books had already diverged this way. For
     * example (the names here are invented), "Ana Lopez Garcia" would show as "Ana Garcia" and
     * "Otto Normalverbraucher" as "Normalverbraucher".
     *
     * Restoring on a name mismatch alone would revert real renames, so the test is narrower: the
     * given and family still match what Proton had, no prefix, middle name or suffix has been
     * typed, and the local display name is EXACTLY the reassembly of given and family. That is the
     * signature of Android's own derivation and nothing else. A user who renames the contact
     * changes given or family; one who adds a title fills the prefix; one who types a custom
     * display name breaks the reassembly. All three still travel.
     *
     * Does nothing until the columns are known, which is every row until the backfill has run.
     */
    private fun preserveServerDisplayName(vCard: VCard, mapping: ContactSyncEntity?) {
        if (mapping == null) return
        val n = vCard.structuredName
        val restore = serverDisplayNameToRestore(
            serverHasN = mapping.serverHasN,
            serverFn = mapping.serverFn,
            serverGiven = mapping.serverGiven,
            serverFamily = mapping.serverFamily,
            localFn = vCard.formattedName?.value,
            given = n?.given,
            family = n?.family,
            extraParts = if (n == null) emptyList() else n.prefixes + n.suffixes + n.additionalNames,
        ) ?: return
        SyncLog.d(TAG, "Keeping Proton's display name for ${mapping.protonContactId}; the phone only holds Android's shortening")
        vCard.setFormattedName(restore)
    }

    /**
     * Drop a structured name Android invented rather than the user typing it.
     *
     * The contacts provider completes every name it stores: hand it a display name alone and it
     * splits a given and family out of it, so a Proton contact called `app@example.com` comes back
     * with given=`app@example`, family=`com`. Reading that straight back on upload wrote an `N` to
     * Proton that nobody had entered, and contacts on real accounts had already acquired one.
     *
     * The test is not a guess about what the split looks like, which would be fragile: it is what
     * the card said last time we read it. If Proton had no `N` and the display name has not moved
     * since, anything structured on the phone can only have come from the provider. A name the user
     * actually types changes the display name too, because the provider recomposes it, so a real
     * edit still travels.
     *
     * Does nothing when the columns are null, which is every row predating them until the backfill
     * has run. Not knowing is a reason to leave the card alone, not to rewrite it.
     */
    private fun stripInventedName(vCard: VCard, mapping: ContactSyncEntity?) {
        if (mapping?.serverHasN != 0) return
        val serverFn = mapping.serverFn?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val localFn = vCard.formattedName?.value?.trim() ?: return
        if (!hasStructuredName(vCard)) return

        // Two shapes count as "the user has not touched this name".
        //
        // The display name still matching the card is the easy one. The other is the reason this
        // needed a second look: once the provider has split a name it also rebuilds the display name
        // out of the pieces, so "app@example.com" becomes "app@example com" locally. Comparing only
        // for equality therefore skipped exactly the contacts this exists to protect, and their
        // mangled name went to Proton along with the invented surname on the next local edit.
        val n = vCard.structuredName
        val rebuilt = listOfNotNull(n?.given, n?.family)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        // Compared against the SERVER's name, not the local one. Comparing the reassembly to the
        // local display name matched almost everything: the provider composes a display name as
        // "given family", so any ordinary two-part name reassembles to exactly itself. Typing a
        // real first and last name onto one of Proton's nameless contacts therefore looked like
        // the provider's own split, and the edit was deleted and the old name put back.
        //
        // Both sides are folded to letters and digits separated by single spaces, which is what
        // the provider's split does to punctuation: "app@example.com" becomes given "app@example"
        // and family "com", and both forms fold to "app example com". A name somebody typed does
        // not fold to the old one, so it travels.
        // The fold keeps case (foldPreservingCase): the provider never changes a letter's case, so
        // a user correcting "jane doe" to "Jane Doe" does not fold onto the old name and the
        // correction travels. A case-blind fold judged it untouched, deleted the correction and
        // put the lower-case version back on every sync. Its sibling serverDisplayNameToRestore
        // was fixed first and this one was not, which left that bug live on the other half of the
        // contacts.
        if (!isAndroidsOwnDerivation(localFn, serverFn, rebuilt)) return

        SyncLog.d(TAG, "Not uploading a structured name Android derived for ${mapping.protonContactId}")
        vCard.structuredName = null
        // Put back the display name the card actually had, so a name the provider reassembled with
        // a space where the dot was does not overwrite the real one on Proton.
        if (localFn != serverFn) {
            SyncLog.d(TAG, "Restoring the display name Android rewrote for ${mapping.protonContactId}")
            vCard.setFormattedName(serverFn)
        }
    }


    /**
     * Whether the card carries a real structured name, as opposed to none at all.
     *
     * Blank-aware on purpose: `buildVCardFromAndroid` adds empty strings for the middle name,
     * prefix and suffix unconditionally, so a null check would call almost every uploaded card
     * named and defeat the whole point of recording this.
     */
    private fun hasStructuredName(vCard: VCard): Boolean {
        val n = vCard.structuredName ?: return false
        val parts = listOfNotNull(n.given, n.family) +
            n.additionalNames.orEmpty() + n.prefixes.orEmpty() + n.suffixes.orEmpty()
        return parts.any { it.isNotBlank() }
    }

    /**
     * Build a VCard from Android's ContactsContract for a given raw contact.
     * Reads all supported field types to prevent data loss on upload.
     */
    private fun buildVCardFromAndroid(
        provider: ContentProviderClient,
        rawContactId: Long,
        account: Account,
        nameMode: ContactNameMode = ContactNameMode.ANDROID
    ): VCard? {
        val vCard = VCard()

        val cursor = provider.query(
            ContactsContract.Data.CONTENT_URI,
            null,
            "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId.toString()),
            null
        ) ?: return null

        try {
            while (cursor.moveToNext()) {
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE))
                when (mimeType) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        val sn = StructuredName()
                        sn.given = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME))
                        sn.family = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME))
                        sn.additionalNames.add(cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME)) ?: "")
                        sn.prefixes.add(cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.PREFIX)) ?: "")
                        sn.suffixes.add(cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.SUFFIX)) ?: "")
                        vCard.structuredName = sn
                        val displayName = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME))
                        if (displayName != null) {
                            vCard.setFormattedName(displayName)
                        }
                        // Phonetic names
                        val phoneticGiven = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME))
                        val phoneticMiddle = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.PHONETIC_MIDDLE_NAME))
                        val phoneticFamily = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME))
                        if (phoneticGiven != null) vCard.addExtendedProperty("X-PHONETIC-FIRST-NAME", phoneticGiven)
                        if (phoneticMiddle != null) vCard.addExtendedProperty("X-PHONETIC-MIDDLE-NAME", phoneticMiddle)
                        if (phoneticFamily != null) vCard.addExtendedProperty("X-PHONETIC-LAST-NAME", phoneticFamily)
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        val email = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS))
                        if (email != null && email.contains("@") && email.contains(".")) {
                            // v1.4.27: read TYPE column and translate to vCard EmailType.
                            // Without this every uploaded address came back labelled
                            // "Other" on the round-trip because Proton stored it
                            // type-less.
                            val typeInt = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.TYPE))
                            val emailProp = ezvcard.property.Email(email)
                            when (typeInt) {
                                ContactsContract.CommonDataKinds.Email.TYPE_HOME ->
                                    emailProp.types.add(ezvcard.parameter.EmailType.HOME)
                                ContactsContract.CommonDataKinds.Email.TYPE_WORK ->
                                    emailProp.types.add(ezvcard.parameter.EmailType.WORK)
                                ContactsContract.CommonDataKinds.Email.TYPE_MOBILE ->
                                    emailProp.types.add(ezvcard.parameter.EmailType.get("CELL"))
                                ContactsContract.CommonDataKinds.Email.TYPE_OTHER ->
                                    emailProp.types.add(ezvcard.parameter.EmailType.get("OTHER"))
                                ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM -> {
                                    val label = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.LABEL))
                                    if (!label.isNullOrEmpty()) emailProp.types.add(ezvcard.parameter.EmailType.get(label))
                                }
                                // else: no TYPE_* known — leave untyped, downstream defaults to OTHER
                            }
                            vCard.addEmail(emailProp)
                        }
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val phone = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                        if (phone != null) {
                            // v1.4.27: read TYPE column and translate to vCard TelephoneType.
                            val typeInt = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE))
                            val phoneProp = ezvcard.property.Telephone(phone)
                            when (typeInt) {
                                ContactsContract.CommonDataKinds.Phone.TYPE_HOME ->
                                    phoneProp.types.add(ezvcard.parameter.TelephoneType.HOME)
                                ContactsContract.CommonDataKinds.Phone.TYPE_WORK ->
                                    phoneProp.types.add(ezvcard.parameter.TelephoneType.WORK)
                                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE ->
                                    phoneProp.types.add(ezvcard.parameter.TelephoneType.CELL)
                                ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> {
                                    phoneProp.types.add(ezvcard.parameter.TelephoneType.HOME)
                                    phoneProp.types.add(ezvcard.parameter.TelephoneType.FAX)
                                }
                                ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> {
                                    phoneProp.types.add(ezvcard.parameter.TelephoneType.WORK)
                                    phoneProp.types.add(ezvcard.parameter.TelephoneType.FAX)
                                }
                                ContactsContract.CommonDataKinds.Phone.TYPE_PAGER ->
                                    phoneProp.types.add(ezvcard.parameter.TelephoneType.PAGER)
                                ContactsContract.CommonDataKinds.Phone.TYPE_MAIN ->
                                    phoneProp.types.add(ezvcard.parameter.TelephoneType.get("MAIN"))
                                ContactsContract.CommonDataKinds.Phone.TYPE_OTHER ->
                                    phoneProp.types.add(ezvcard.parameter.TelephoneType.get("OTHER"))
                                ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> {
                                    val label = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL))
                                    if (!label.isNullOrEmpty()) phoneProp.types.add(ezvcard.parameter.TelephoneType.get(label))
                                }
                                // else: leave untyped
                            }
                            vCard.addTelephoneNumber(phoneProp)
                        }
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        val note = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Note.NOTE))
                        if (note != null) vCard.addNote(note)
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val addr = ezvcard.property.Address()
                        addr.streetAddress = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.STREET))
                        addr.locality = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.CITY))
                        addr.region = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.REGION))
                        addr.postalCode = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE))
                        addr.country = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY))
                        // v1.4.27: read TYPE column and translate to vCard AddressType.
                        val typeInt = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.TYPE))
                        when (typeInt) {
                            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME ->
                                addr.types.add(ezvcard.parameter.AddressType.HOME)
                            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK ->
                                addr.types.add(ezvcard.parameter.AddressType.WORK)
                            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_OTHER ->
                                addr.types.add(ezvcard.parameter.AddressType.get("OTHER"))
                            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_CUSTOM -> {
                                val label = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.LABEL))
                                if (!label.isNullOrEmpty()) addr.types.add(ezvcard.parameter.AddressType.get(label))
                            }
                            // else: leave untyped
                        }
                        vCard.addAddress(addr)
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        val company = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Organization.COMPANY))
                        val dept = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Organization.DEPARTMENT))
                        if (company != null || dept != null) {
                            val org = ezvcard.property.Organization()
                            if (company != null) org.values.add(company)
                            if (dept != null) org.values.add(dept)
                            vCard.organization = org
                        }
                        val title = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Organization.TITLE))
                        if (title != null) vCard.addTitle(title)
                        val jobDesc = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Organization.JOB_DESCRIPTION))
                        if (jobDesc != null) vCard.addRole(jobDesc)
                    }
                    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE -> {
                        val url = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Website.URL))
                        if (url != null) vCard.addUrl(url)
                    }
                    ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> {
                        val nick = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Nickname.NAME))
                        if (nick != null) {
                            vCard.nickname = ezvcard.property.Nickname().apply { values.add(nick) }
                        }
                    }
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        val date = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Event.START_DATE))
                        val type = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Event.TYPE))
                        if (date != null) {
                            when (type) {
                                ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY -> {
                                    vCard.birthday = ezvcard.property.Birthday(date)
                                }
                                ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY -> {
                                    vCard.anniversary = ezvcard.property.Anniversary(date)
                                }
                                else -> {
                                    // Custom/other events — store as X-ABDATE
                                    val label = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Event.LABEL))
                                    vCard.addExtendedProperty("X-ABDATE", date).apply {
                                        if (label != null) setParameter("X-ABLABEL", label)
                                    }
                                }
                            }
                        }
                    }
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE -> {
                        // Prefer the full-resolution display photo over the auto-generated thumbnail.
                        // Photo.PHOTO is hard-capped at ~96x96 by Android's ContactsProvider; the
                        // un-scaled photo lives separately at DisplayPhoto.CONTENT_URI/<PHOTO_FILE_ID>.
                        // Reading PHOTO directly shrank a high-res phone photo to 96x96 after sync.
                        val photoFileIdCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Photo.PHOTO_FILE_ID)
                        val photoFileId = if (photoFileIdCol >= 0 && !cursor.isNull(photoFileIdCol)) cursor.getLong(photoFileIdCol) else 0L
                        var photoBytes: ByteArray? = null
                        if (photoFileId > 0) {
                            try {
                                val displayPhotoUri = ContentUris.withAppendedId(
                                    ContactsContract.DisplayPhoto.CONTENT_URI, photoFileId
                                )
                                provider.openAssetFile(displayPhotoUri, "r")?.use { afd ->
                                    afd.createInputStream().use { input ->
                                        photoBytes = input.readBytes()
                                    }
                                }
                                SyncLog.d(TAG, "Read display photo for raw contact $rawContactId: ${photoBytes?.size ?: 0} bytes (PHOTO_FILE_ID=$photoFileId)")
                            } catch (e: Exception) {
                                SyncLog.w(TAG, "Display photo read failed for $rawContactId, falling back to thumbnail: ${e.message}")
                            }
                        }
                        if (photoBytes == null || photoBytes!!.isEmpty()) {
                            // Fall back to the thumbnail blob — this happens when the contact only
                            // has a small photo to begin with (e.g. came from a sync that didn't
                            // populate display photo, or was set via an older API path).
                            photoBytes = cursor.getBlob(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Photo.PHOTO))
                        }
                        if (photoBytes != null && photoBytes!!.isNotEmpty()) {
                            // Resize to MAX_CONTACT_PHOTO_SIZE max (see SyncConstants for rationale)
                            val resizedBytes = try {
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes!!.size)
                                if (bmp != null && (bmp.width > SyncConstants.MAX_CONTACT_PHOTO_SIZE || bmp.height > SyncConstants.MAX_CONTACT_PHOTO_SIZE)) {
                                    val scale = SyncConstants.MAX_CONTACT_PHOTO_SIZE.toFloat() / maxOf(bmp.width, bmp.height)
                                    val scaled = android.graphics.Bitmap.createScaledBitmap(
                                        bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
                                    )
                                    val out = java.io.ByteArrayOutputStream()
                                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                                    if (scaled != bmp) scaled.recycle()
                                    bmp.recycle()
                                    out.toByteArray()
                                } else {
                                    bmp?.recycle()
                                    photoBytes!!
                                }
                            } catch (_: Exception) { photoBytes!! }
                            val photo = ezvcard.property.Photo(resizedBytes, ezvcard.parameter.ImageType.JPEG)
                            vCard.addPhoto(photo)
                        }
                    }
                    ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE -> {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Relation.NAME))
                        val type = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Relation.TYPE))
                        if (name != null) {
                            val relType = when (type) {
                                ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE -> "spouse"
                                ContactsContract.CommonDataKinds.Relation.TYPE_CHILD -> "child"
                                ContactsContract.CommonDataKinds.Relation.TYPE_PARENT -> "parent"
                                ContactsContract.CommonDataKinds.Relation.TYPE_RELATIVE,
                                ContactsContract.CommonDataKinds.Relation.TYPE_BROTHER,
                                ContactsContract.CommonDataKinds.Relation.TYPE_SISTER -> "sibling"
                                ContactsContract.CommonDataKinds.Relation.TYPE_FRIEND -> "friend"
                                ContactsContract.CommonDataKinds.Relation.TYPE_ASSISTANT,
                                ContactsContract.CommonDataKinds.Relation.TYPE_MANAGER -> "colleague"
                                else -> "contact"
                            }
                            val related = ezvcard.property.Related()
                            related.text = name
                            related.types.add(ezvcard.parameter.RelatedType.get(relType))
                            vCard.addRelated(related)
                        }
                    }
                    @Suppress("DEPRECATION")
                    ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE -> {
                        @Suppress("DEPRECATION")
                        val sip = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS))
                        if (sip != null) {
                            vCard.addExtendedProperty("X-SIP", sip)
                        }
                    }
                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE -> {
                        val groupRowId = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID))
                        val groupTitle = resolveGroupTitle(provider, groupRowId)
                        if (groupTitle != null && groupTitle.lowercase() !in FORBIDDEN_GROUP_NAMES) {
                            val cat = vCard.categories
                            if (cat != null) {
                                cat.values.add(groupTitle)
                            } else {
                                vCard.categories = ezvcard.property.Categories().apply { values.add(groupTitle) }
                            }
                        }
                    }
                }
            }
        } finally {
            cursor.close()
        }

        // Samsung-specific: profile_card portrait → second PHOTO with X-SAMSUNG-PORTRAIT=1.
        // Naturally scoped (only fires when the row exists, i.e. Samsung Contacts user
        // who actually set a portrait); other clients either ignore the second PHOTO or
        // see it as a duplicate avatar. That is an accepted trade-off.
        readSamsungPortraitForUpload(provider, rawContactId)?.let { portraitBytes ->
            val portraitPhoto = ezvcard.property.Photo(portraitBytes, ezvcard.parameter.ImageType.JPEG)
            portraitPhoto.parameters.put(ContactsMapper.PORTRAIT_PARAM_NAME, ContactsMapper.PORTRAIT_PARAM_VALUE)
            vCard.addPhoto(portraitPhoto)
            SyncLog.i(TAG, "Added Samsung portrait as second PHOTO for raw contact $rawContactId: ${portraitBytes.size} bytes")
        }

        // Nickname mode borrows the nickname row to hold the display name, and writes no
        // structured name at all so the provider has nothing to split. Read straight back, that
        // card has no FN, so the fallback below would reach past it to the email address and rename
        // the contact on Proton to their own address, with their real name left behind as a
        // NICKNAME nobody entered. The mapper only borrows the slot when the card had no nickname
        // of its own, so a missing structured name here is a reliable sign that it did.
        if (nameMode == ContactNameMode.NICKNAME && vCard.structuredName == null) {
            val borrowed = vCard.nickname?.values?.firstOrNull()?.takeIf { it.isNotBlank() }
            if (borrowed != null && vCard.formattedName?.value.isNullOrBlank()) {
                vCard.setFormattedName(borrowed)
                vCard.nickname = null
            }
        }

        // FN fallback: ensure FN is always present (Proton requires it)
        if (vCard.formattedName == null || vCard.formattedName.value.isNullOrBlank()) {
            val fn = when {
                vCard.structuredName != null -> {
                    listOfNotNull(vCard.structuredName.given, vCard.structuredName.family)
                        .filter { it.isNotBlank() }.joinToString(" ")
                }
                vCard.emails.isNotEmpty() -> vCard.emails.first().value
                else -> null
            }
            vCard.setFormattedName(if (fn.isNullOrBlank()) "Unknown" else fn)
        }

        // Enforce the name length limits of Proton's web client (WebClients
        // CONTACT_NAME_MAX_LENGTH and CONTACT_FIRST_LAST_NAME_MAX_LENGTH)
        val fnValue = vCard.formattedName?.value
        if (fnValue != null && fnValue.length > 190) {
            vCard.setFormattedName(fnValue.take(SyncConstants.MAX_CONTACT_FN_LENGTH))
        }
        val sn = vCard.structuredName
        if (sn != null) {
            if ((sn.given ?: "").length > SyncConstants.MAX_CONTACT_NAME_PART_LENGTH) sn.given = sn.given?.take(SyncConstants.MAX_CONTACT_NAME_PART_LENGTH)
            if ((sn.family ?: "").length > SyncConstants.MAX_CONTACT_NAME_PART_LENGTH) sn.family = sn.family?.take(SyncConstants.MAX_CONTACT_NAME_PART_LENGTH)
        }

        // Normalize birthday/anniversary dates to yyyyMMdd format
        vCard.birthday?.let { bday ->
            val text = bday.text
            if (text != null) {
                val normalized = text.replace("-", "").replace("/", "").take(8)
                if (normalized.length == 8 && normalized.all { it.isDigit() }) {
                    vCard.birthday = ezvcard.property.Birthday(normalized)
                }
            }
        }
        vCard.anniversary?.let { ann ->
            val text = ann.text
            if (text != null) {
                val normalized = text.replace("-", "").replace("/", "").take(8)
                if (normalized.length == 8 && normalized.all { it.isDigit() }) {
                    vCard.anniversary = ezvcard.property.Anniversary(normalized)
                }
            }
        }

        // Add PREF parameter to first email, phone, address
        if (vCard.emails.isNotEmpty()) {
            for ((i, email) in vCard.emails.withIndex()) {
                email.pref = i + 1
            }
        }
        if (vCard.telephoneNumbers.isNotEmpty()) {
            for ((i, tel) in vCard.telephoneNumbers.withIndex()) {
                tel.pref = i + 1
            }
        }
        if (vCard.addresses.isNotEmpty()) {
            for ((i, addr) in vCard.addresses.withIndex()) {
                addr.pref = i + 1
            }
        }

        // Return contact even if only has phone or notes (FN fallback ensures FN exists)
        return if (vCard.formattedName != null) {
            vCard
        } else {
            null
        }
    }

    /**
     * Read Samsung's portrait/profile-card photo for a raw contact, if one exists.
     *
     * Returns JPEG-encoded bytes resized to [SyncConstants.MAX_CONTACT_PHOTO_SIZE]
     * (matching the policy used for the primary photo), or null if the contact has
     * no `vnd.sec.cursor.item/profile_card` row.
     *
     * Strategy: prefer the full-resolution photo at the file ID stored in `data2`
     * (read via the standard `DisplayPhoto.CONTENT_URI` — Samsung uses Android's
     * shared photo asset store), fall back to the small `data15` BLOB if the file
     * ID lookup fails.
     */
    private fun readSamsungPortraitForUpload(
        provider: ContentProviderClient,
        rawContactId: Long
    ): ByteArray? {
        val cursor = provider.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DATA2, ContactsContract.Data.DATA15),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), ContactsMapper.SAMSUNG_PROFILE_CARD_MIMETYPE),
            null
        ) ?: return null

        var rawBytes: ByteArray? = null
        try {
            if (!cursor.moveToFirst()) return null
            val portraitFileId = if (!cursor.isNull(0)) cursor.getLong(0) else 0L
            if (portraitFileId > 0) {
                try {
                    val displayPhotoUri = ContentUris.withAppendedId(
                        ContactsContract.DisplayPhoto.CONTENT_URI, portraitFileId
                    )
                    provider.openAssetFile(displayPhotoUri, "r")?.use { afd ->
                        afd.createInputStream().use { input ->
                            rawBytes = input.readBytes()
                        }
                    }
                    SyncLog.d(TAG, "Read Samsung portrait for raw contact $rawContactId: ${rawBytes?.size ?: 0} bytes (file ID=$portraitFileId)")
                } catch (e: Exception) {
                    SyncLog.w(TAG, "Samsung portrait file-ID read failed for $rawContactId: ${e.message}")
                }
            }
            if (rawBytes == null || rawBytes!!.isEmpty()) {
                rawBytes = cursor.getBlob(1)
            }
        } finally {
            cursor.close()
        }

        val bytes = rawBytes ?: return null
        if (bytes.isEmpty()) return null

        return try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null && (bmp.width > SyncConstants.MAX_CONTACT_PHOTO_SIZE || bmp.height > SyncConstants.MAX_CONTACT_PHOTO_SIZE)) {
                val scale = SyncConstants.MAX_CONTACT_PHOTO_SIZE.toFloat() / maxOf(bmp.width, bmp.height)
                val scaled = android.graphics.Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
                )
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                if (scaled != bmp) scaled.recycle()
                bmp.recycle()
                out.toByteArray()
            } else {
                bmp?.recycle()
                bytes
            }
        } catch (_: Exception) { bytes }
    }

    /**
     * Extract base64-encoded photo data from data URI PHOTO lines in a vCard string.
     * Handles Proton's escaped comma format: PHOTO;PREF=1:data:image/jpeg;base64\,/9j/...
     */
    private fun extractDataUriPhotos(vCardString: String, propertyName: String): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        val lines = vCardString.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val upperLine = line.uppercase()
            if (upperLine.startsWith("$propertyName;") || upperLine.startsWith("$propertyName:")) {
                // Collect continuation lines (folded lines start with space/tab)
                val fullLine = StringBuilder(line)
                while (i + 1 < lines.size && lines[i + 1].startsWith(" ")) {
                    i++
                    fullLine.append(lines[i].substring(1)) // remove leading space
                }
                val fullValue = fullLine.toString()

                // Find base64 data after "base64\," or "base64,"
                val base64Marker = "base64"
                val markerIdx = fullValue.indexOf(base64Marker, ignoreCase = true)
                if (markerIdx >= 0) {
                    var dataStart = markerIdx + base64Marker.length
                    // Skip past \, or , delimiter
                    while (dataStart < fullValue.length && (fullValue[dataStart] == '\\' || fullValue[dataStart] == ',')) {
                        dataStart++
                    }
                    val base64Data = fullValue.substring(dataStart).trim()
                    if (base64Data.isNotEmpty()) {
                        try {
                            val decoded = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                            result.add(decoded)
                        } catch (e: Exception) {
                            SyncLog.w(TAG, "Failed to decode $propertyName base64: ${e.message}")
                        }
                    }
                }
            }
            i++
        }
        return result
    }

    /**
     * Remove all lines for a given property (including folded continuation lines).
     */
    private fun removePropertyLines(vCardString: String, propertyName: String): String {
        val result = StringBuilder()
        val lines = vCardString.lines()
        var i = 0
        var skipping = false
        while (i < lines.size) {
            val line = lines[i]
            val upperLine = line.uppercase()
            if (upperLine.startsWith("$propertyName;") || upperLine.startsWith("$propertyName:")) {
                skipping = true
            } else if (skipping && line.startsWith(" ")) {
                // continuation line of skipped property
            } else {
                skipping = false
                result.appendLine(line)
            }
            i++
        }
        return result.toString().trimEnd()
    }


    /**
     * Put locally-held contacts back in the upload phase's path after a rules change.
     *
     * These were created on the phone and held back by an upload rule, so they carry no mapping and
     * no dirty flag. Nothing would otherwise reconsider them, and a rule the user relaxed would
     * never take effect for them.
     *
     * Returns the ids it actually marked, which is narrower than the input when the provider refuses
     * an update. A caller that RELEASES protection must release only these: a row that could not be
     * marked is still unmapped and still clean, which is the shape the orphan sweep hard-deletes.
     */
    private fun markKeptLocalDirty(
        account: Account,
        provider: ContentProviderClient,
        rawContactIds: Set<Long>
    ): Set<Long> {
        if (rawContactIds.isEmpty()) return emptySet()
        val marked = mutableSetOf<Long>()
        val values = ContentValues().apply { put(ContactsContract.RawContacts.DIRTY, 1) }
        var updated = 0
        for (id in rawContactIds) {
            try {
                val rows = provider.update(
                    SyncUtils.asCallerSyncAdapter(
                        ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id),
                        account
                    ),
                    values, null, null
                )
                updated += rows
                if (rows > 0) marked.add(id)
            } catch (e: Exception) {
                SyncLog.w(TAG, "Could not re-open locally-held contact $id: ${e.message}")
            }
        }
        SyncLog.i(TAG, "Rules changed: re-opened $updated locally-held contact(s) for re-evaluation")
        return marked
    }

    /**
     * Proton label ids resolved two ways, plus whether the fetch actually worked.
     *
     * [names] exists because contact filter rules are written against group *names* while the
     * contact header carries only ids. [fetched] matters more: this function swallows its own
     * exceptions and returns empty maps on failure, which downstream reads as "this contact is in
     * no groups". A filter must be able to tell that apart from a genuinely ungrouped contact, or
     * one failed request could propose an entire address book for removal.
     */
    private data class ContactLabels(
        val groupRowIds: Map<String, Long>,
        val names: Map<String, String>,
        val fetched: Boolean,
    )

    /**
     * Fetch Proton contact labels and create/update matching Android contact groups.
     * Returns a map of Proton label ID → Android group row ID.
     */
    private suspend fun syncContactLabels(
        accountName: String,
        account: Account,
        provider: ContentProviderClient
    ): ContactLabels {
        val labelMap = mutableMapOf<String, Long>()
        val nameMap = mutableMapOf<String, String>()
        try {
            val response = apiClient.getContactLabels(accountName)
            if (!response.isSuccessful) {
                SyncLog.w(TAG, "Failed to fetch contact labels: HTTP ${response.code()}${response.protonErrorSuffix()}")
                return ContactLabels(labelMap, nameMap, fetched = false)
            }
            val labels = response.body()?.labels
                ?: return ContactLabels(labelMap, nameMap, fetched = false)
            SyncLog.d(TAG, "Fetched ${labels.size} contact labels")

            for (label in labels) {
                nameMap[label.id] = label.name
                // Check if Android group already exists for this label
                val groupId = findOrCreateGroup(provider, account, label.name)
                if (groupId != null) {
                    labelMap[label.id] = groupId
                }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Error syncing contact labels: ${e.message}")
            return ContactLabels(labelMap, nameMap, fetched = false)
        }
        return ContactLabels(labelMap, nameMap, fetched = true)
    }

    /**
     * Detect groups deleted locally but still present on Proton.
     * Deletes the Proton label and marks contacts dirty so CATEGORIES get re-uploaded.
     */
    private suspend fun markContactsDirtyForDeletedGroups(
        account: Account,
        parentAccountName: String,
        provider: ContentProviderClient,
        protonLabelNames: MutableSet<String>,
        protonLabels: List<ProtonContactLabel>
    ) {
        if (protonLabelNames.isEmpty()) return

        // Get all local group titles for this account. FAIL-CLOSED: a null/throwing read — or an
        // empty read while Proton still has labels — is indistinguishable from "the user deleted
        // every group", and acting on it would delete every Proton label (the calendar
        // reconcileDeletedEvents absence bug, applied to labels). So skip rather than mass-delete.
        val localGroupTitles = mutableSetOf<String>()
        val readOk = try {
            val cursor = provider.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups.TITLE),
                "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND ${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND ${ContactsContract.Groups.DELETED} = 0",
                arrayOf(account.name, account.type),
                null
            )
            if (cursor == null) false else cursor.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { localGroupTitles.add(it) }
                true
            }
        } catch (_: Exception) { false }

        if (!readOk) {
            SyncLog.w(TAG, "Group-deletion detection skipped: local group read failed/null - not deleting any Proton labels this cycle")
            return
        }
        if (localGroupTitles.isEmpty()) {
            SyncLog.w(TAG, "Group-deletion detection skipped: 0 local groups read but ${protonLabelNames.size} Proton label(s) exist - likely a glitch, not a real bulk delete")
            return
        }

        // Find Proton labels that no longer exist locally
        val deletedGroupNames = protonLabelNames - localGroupTitles
        if (deletedGroupNames.isEmpty()) return

        SyncLog.i(TAG, "Detected ${deletedGroupNames.size} locally deleted groups: $deletedGroupNames")

        // Delete the Proton labels so they don't get re-detected next sync
        for (name in deletedGroupNames) {
            val label = protonLabels.firstOrNull { it.name == name } ?: continue
            try {
                val resp = apiClient.deleteContactLabel(parentAccountName, label.id)
                if (resp.isSuccessful) {
                    SyncLog.i(TAG, "Deleted Proton label '$name' (${label.id})")
                    protonLabelNames.remove(name)
                } else {
                    SyncLog.w(TAG, "Failed to delete Proton label '$name': HTTP ${resp.code()}${resp.protonErrorSuffix()}")
                }
            } catch (e: Exception) {
                SyncLog.w(TAG, "Error deleting Proton label '$name': ${e.message}")
            }
        }

        // Mark ALL contacts as dirty so CATEGORIES get re-uploaded without deleted groups
        val values = ContentValues().apply { put(ContactsContract.RawContacts.DIRTY, 1) }
        try {
            val updated = provider.update(
                SyncUtils.asCallerSyncAdapter(ContactsContract.RawContacts.CONTENT_URI, account),
                values,
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(account.name, account.type)
            )
            SyncLog.i(TAG, "Marked $updated contacts as dirty for group deletion re-sync")
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to mark contacts dirty for group deletion: ${e.message}")
        }
    }

    private fun resolveGroupTitle(provider: ContentProviderClient, groupRowId: Long): String? {
        return try {
            provider.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups.TITLE),
                "${ContactsContract.Groups._ID} = ?",
                arrayOf(groupRowId.toString()),
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null }
    }

    /**
     * Find an existing Android contact group by title, or create one.
     */
    private fun findOrCreateGroup(
        provider: ContentProviderClient,
        account: Account,
        groupTitle: String
    ): Long? {
        // Skip forbidden group names
        if (groupTitle.lowercase() in FORBIDDEN_GROUP_NAMES) {
            SyncLog.w(TAG, "Skipping forbidden group name: $groupTitle")
            return null
        }
        // Search for existing group
        val cursor = provider.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID),
            "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND ${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND ${ContactsContract.Groups.TITLE} = ?",
            arrayOf(account.name, account.type, groupTitle),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }

        // Create new group
        val values = android.content.ContentValues().apply {
            put(ContactsContract.Groups.ACCOUNT_NAME, account.name)
            put(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Groups.TITLE, groupTitle)
            put(ContactsContract.Groups.GROUP_VISIBLE, 1)
            put(ContactsContract.Groups.SHOULD_SYNC, 1)
        }
        val groupUri = provider.insert(
            SyncUtils.asCallerSyncAdapter(ContactsContract.Groups.CONTENT_URI, account),
            values
        )
        val groupId = groupUri?.let { ContentUris.parseId(it) }
        if (groupId != null) {
            SyncLog.d(TAG, "Created Android group '$groupTitle' (id=$groupId)")
        }
        return groupId
    }

    private suspend fun fetchAllContacts(accountName: String): List<ContactMeta>? {
        val allContacts = mutableListOf<ContactMeta>()
        var page = 0

        while (true) {
            val response = apiClient.getContacts(accountName, page = page, pageSize = PAGE_SIZE)
            if (!response.isSuccessful) {
                val code = response.code()
                SyncLog.e(TAG, "Failed to fetch contacts page $page: HTTP $code${response.protonErrorSuffix()}")
                if (code == 401 || code == 403) {
                    throw Exception("Auth failed fetching contacts: HTTP $code")
                }
                return null
            }

            val body = response.body() ?: return null
            allContacts.addAll(body.contacts)

            if (body.contacts.size < PAGE_SIZE) break
            page++
        }

        return allContacts
    }

    private suspend fun fetchContactDetail(accountName: String, contactId: String): me.proton.sync.api.models.ContactDetail? {
        val response = apiClient.getContact(accountName, contactId)
        if (!response.isSuccessful) {
            SyncLog.e(TAG, "Failed to fetch contact detail $contactId: HTTP ${response.code()}${response.protonErrorSuffix()}")
            return null
        }
        return response.body()?.contact
    }

    private fun showConflictNotification(
        contactMeta: ContactMeta,
        accountName: String,
        rawContactId: Long,
        provider: ContentProviderClient
    ) {
        // Get contact display name from Android
        val contactName = try {
            val cursor = provider.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME),
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                null
            )
            cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
            ?: context.getString(me.proton.sync.R.string.notification_contact_conflict_unnamed)

        val notificationId = (NOTIFICATION_ID_CONFLICT_BASE + contactMeta.id.hashCode())

        fun makeActionIntent(action: String): PendingIntent {
            val intent = Intent(context, ConflictResolutionReceiver::class.java).apply {
                putExtra(ConflictResolutionReceiver.EXTRA_CONTACT_ID, contactMeta.id)
                putExtra(ConflictResolutionReceiver.EXTRA_ACCOUNT_NAME, accountName)
                putExtra(ConflictResolutionReceiver.EXTRA_ACTION, action)
                putExtra(ConflictResolutionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                // Unique action string to prevent PendingIntent reuse
                this.action = "me.proton.sync.CONFLICT_${action}_${contactMeta.id}"
            }
            return PendingIntent.getBroadcast(
                context, notificationId + action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Translated, and worded like the calendar's conflict prompt (SyncNotificationHelper), whose two action
        // labels it reuses: both ask the same question about the same kind of choice. These were hardcoded
        // English ("Use local" / "Use remote") before, so every non-English phone saw them untranslated.
        val body = context.getString(me.proton.sync.R.string.notification_contact_conflict_body, contactName)
        val notification = NotificationCompat.Builder(context, ProtonSyncApp.CHANNEL_CONFLICT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(me.proton.sync.R.string.notification_contact_conflict_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(
                0, context.getString(me.proton.sync.R.string.notification_conflict_keep_remote),
                makeActionIntent(ConflictResolutionReceiver.ACTION_USE_REMOTE)
            )
            .addAction(
                0, context.getString(me.proton.sync.R.string.notification_conflict_keep_local),
                makeActionIntent(ConflictResolutionReceiver.ACTION_USE_LOCAL)
            )
            .addAction(
                0, context.getString(me.proton.sync.R.string.btn_skip),
                makeActionIntent(ConflictResolutionReceiver.ACTION_SKIP)
            )
            .build()

        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.notify(notificationId, notification)
        } catch (e: SecurityException) {
            SyncLog.w(TAG, "Cannot post conflict notification: ${e.message}")
        }

        SyncLog.i(TAG, "Conflict notification shown for contact ${contactMeta.id}")
    }

}
