package me.proton.sync.contacts.filter

import android.content.Context
import android.provider.ContactsContract
import me.proton.sync.ProtonSyncApp
import me.proton.sync.contacts.ContactsSyncAdapter
import me.proton.sync.db.daos.ContactSyncDao

/**
 * A contact read from the phone's own address book.
 *
 * The third and last [ContactView] implementation, and the only one that needs no network. It
 * exists for the dry run: the question the preview answers is "which contacts already on this
 * phone would these rules take away", and the only honest place to read that population is the
 * contacts provider itself.
 *
 * Most of what the app syncs is knowable here, so [values] answers an empty list rather than null
 * for a field a contact simply does not have. Three things are not knowable, and each answers null
 * so the evaluator reports undecided rather than guessing: Proton's card size and creation time,
 * which exist nowhere on the device, and the structured name.
 *
 * **The structured name is the interesting one.** Android's contacts provider does not store what
 * it was given; it derives the missing half of the name pair. Insert a contact with only a display
 * name and the provider splits it into given and family:
 *
 * ```
 * "John Quincy Doe" -> given=John, middle=Quincy, family=Doe
 * "app@example.com" -> given=app@example, family=com
 * "AB"              -> given=AB
 * ```
 *
 * None of those contacts has a structured name on Proton. Verified on a real account, where every
 * one of the auto-created contacts had a manufactured N. So a local row can never distinguish
 * "has a real name" from "had one invented for it", which is precisely the distinction the
 * auto-created rules rest on. Answering from the provider would tell the user that a rule removes
 * nothing and then watch the sync remove sixty contacts, which is worse than not answering.
 */
class LocalContactView(
    /** Null only in tests; the loader skips raw contacts that have no Proton mapping. */
    val protonId: String?,
    private val displayName: String?,
    private val modifyTime: Long?,
    private val groups: List<String>,
    private val fields: Map<RuleField, List<String>>,
) : ContactView {

    /** What the user sees in the removal list. Falls back through email and id, never blank. */
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: fields[RuleField.EMAIL]?.firstOrNull()
            ?: protonId
            ?: ""

    override fun values(field: RuleField): List<String>? = when (field) {
        RuleField.PROTON_ID -> listOfNotNull(protonId?.takeIf { it.isNotBlank() })
        RuleField.DISPLAY_NAME -> listOfNotNull(displayName?.takeIf { it.isNotBlank() })
        // The UID is in the contact's signed card, which the phone does not keep. SYNC1 holds the UID a
        // contact created here was first uploaded with, which an edit before 2.0.0 may have replaced.
        RuleField.UID -> null
        RuleField.GROUPS -> groups
        RuleField.SIZE_BYTES, RuleField.CREATE_TIME, RuleField.MODIFY_TIME,
        RuleField.GROUP_COUNT -> null
        // Derived by the provider, so a value here is not evidence of one on Proton. See the note
        // on the class: answering these would make the auto-created rules preview as no-ops.
        RuleField.STRUCTURED_NAME, RuleField.GIVEN_NAME, RuleField.FAMILY_NAME,
        RuleField.ADDITIONAL_NAMES, RuleField.NAME_PREFIX, RuleField.NAME_SUFFIX -> null
        // Invented too, just by our own download mapper rather than by Android. An untyped
        // EMAIL, TEL or ADR on a Proton card becomes TYPE_OTHER in the provider, which reads back
        // as the literal "other" -- so "has an email type" would be true of every auto-created
        // contact here and false of every one of them on the card. Android's int types are lossy
        // in the other direction as well: TYPE_FAX_HOME collapses the card's "fax" and "home" into
        // one value, and a single row cannot represent TYPE="voice,cell" at all.
        RuleField.EMAIL_TYPE, RuleField.PHONE_TYPE, RuleField.ADDRESS_TYPE -> null
        // Whether the card carries anything this app cannot map is by definition unanswerable from
        // the rows this app wrote. Returning "" said "knowably absent", turning the honest UNKNOWN
        // into a FALSE, so a rule meant to catch richer contacts quietly matched none of them.
        RuleField.OTHER_CONTENT -> null
        else -> fields[field].orEmpty()
    }

    override fun number(field: RuleField): Long? = when (field) {
        RuleField.GROUP_COUNT -> groups.size.toLong()
        // Proton's ModifyTime, as recorded by the last sync.
        RuleField.MODIFY_TIME -> modifyTime
        else -> null
    }

    /**
     * Always null, because the structured name is not knowable here.
     *
     * Every "only has these fields" rule turns on whether the name counts, and the provider hands
     * out a manufactured one to every contact, so any set computed from a local row would carry a
     * name that Proton does not have. That is not a near-miss: it flips the flagship rule from
     * matching sixty contacts to matching none, silently and in the dangerous direction.
     *
     * Null makes the evaluator return NeedsCard, the preview counts those contacts as undecided
     * and says so, and the sync settles them from the real card, still asking before it removes
     * anything. An honest "cannot tell from here" is the only answer this class can give.
     */
    override fun populated(): Set<RuleField>? = null

    /**
     * Always null: Android group rows are matched to Proton labels by title, never by id.
     *
     * Returning null rather than an empty list matters. Empty would read as "this contact is in
     * none of your groups" and quietly falsify every group rule; null sends the evaluator down
     * its name-matching fallback, which is what the upload path uses too.
     */
    override fun groupIds(): List<String>? = null
}

/**
 * Runs a rule set against the contacts currently on this phone.
 *
 * Reads the whole account in three queries rather than one per contact: a few hundred contacts
 * with a query each is a visible stall, and a dry run is only useful if the user is still willing
 * to wait for it.
 */
object ContactFilterPreview {

    /**
     * [kept], [removed] and [undecided] partition [total]. Undecided is deliberately not folded
     * into kept: a contact the phone cannot judge has not been judged, and counting it as safe is
     * how a dry run ends up reassuring somebody about a rule it never evaluated.
     */
    data class Result(
        val total: Int,
        val kept: Int,
        /** Names of the contacts these rules would take off the phone, sorted for display. */
        val removed: List<String>,
        /** Contacts no local answer could settle; see the note on [LocalContactView]. */
        val undecided: Int,
    ) {
        /** How many contacts the rules could actually be applied to. */
        val checked: Int get() = total - undecided
    }

    suspend fun run(
        context: Context,
        dao: ContactSyncDao,
        accountName: String,
        ruleSet: FilterRuleSet,
    ): Result {
        val views = load(context, dao, accountName)
        // Contacts the user already rescued at a removal prompt; the sync will not ask again.
        val rescued = me.proton.sync.RemoteDeletionGuard.keptIds(
            context, me.proton.sync.RemoteDeletionGuard.TYPE_CONTACTS_FILTER, accountName
        )
        val evaluator = FilterEvaluator(ruleSet)
        var kept = 0
        var undecided = 0
        val removed = mutableListOf<String>()
        for (view in views) {
            when (val verdict = evaluator.evaluate(view, RuleDirection.DOWNLOAD)) {
                is FilterEvaluator.Verdict.Decided ->
                    if (verdict.action == RuleAction.EXCLUDE && view.protonId !in rescued) {
                        removed += view.label
                    } else {
                        kept++
                    }
                FilterEvaluator.Verdict.NeedsCard -> undecided++
            }
        }
        return Result(views.size, kept, removed.sortedWith(String.CASE_INSENSITIVE_ORDER), undecided)
    }

    private suspend fun load(
        context: Context,
        dao: ContactSyncDao,
        accountName: String,
    ): List<LocalContactView> {
        val resolver = context.contentResolver

        val protonIdByRawId = HashMap<Long, String>()
        val modifyByRawId = HashMap<Long, Long>()
        for (row in dao.getAllByAccount(accountName)) {
            val rawId = row.androidRawContactId ?: continue
            protonIdByRawId[rawId] = row.protonContactId
            modifyByRawId[rawId] = row.lastModified
        }

        val names = LinkedHashMap<Long, String?>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                "${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(accountName, ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK),
            null,
        )?.use { c ->
            while (c.moveToNext()) names[c.getLong(0)] = c.getString(1)
        }
        if (names.isEmpty()) return emptyList()

        val fields = HashMap<Long, MutableMap<RuleField, MutableList<String>>>()
        val groups = HashMap<Long, MutableList<String>>()
        readDataRows(context, accountName, readGroupTitles(context, accountName), fields, groups)

        // Only contacts that exist on Proton. The download loop iterates Proton's list, so it can
        // never exclude or remove a row that is not on it; a contact created here and not yet
        // uploaded is the upload filter's business, and that path deliberately keeps it. Judging
        // those with download rules made the preview name specific people it would never touch.
        return names.mapNotNull { (rawId, name) ->
            val protonId = protonIdByRawId[rawId] ?: return@mapNotNull null
            LocalContactView(
                protonId = protonId,
                displayName = name,
                modifyTime = modifyByRawId[rawId],
                groups = groups[rawId].orEmpty(),
                fields = fields[rawId].orEmpty().mapValues { it.value.toList() },
            )
        }
    }

    private fun readGroupTitles(context: Context, accountName: String): Map<Long, String> {
        val titles = HashMap<Long, String>()
        context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
            "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND " +
                "${ContactsContract.Groups.DELETED} = 0",
            arrayOf(accountName, ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK),
            null,
        )?.use { c ->
            while (c.moveToNext()) c.getString(1)?.let { titles[c.getLong(0)] = it }
        }
        return titles
    }

    /**
     * One pass over every data row in the account.
     *
     * The photo blob is deliberately outside the projection. A rule can only ask whether a contact
     * has a photo at all, and pulling a few hundred full-size images through a cursor to answer
     * that would make the dry run slower than the sync it previews.
     */
    private fun readDataRows(
        context: Context,
        accountName: String,
        groupTitles: Map<Long, String>,
        into: MutableMap<Long, MutableMap<RuleField, MutableList<String>>>,
        groups: MutableMap<Long, MutableList<String>>,
    ) {
        val projection = arrayOf(
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7,
            ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9,
            ContactsContract.Data.DATA10,
        )
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND " +
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
            arrayOf(accountName, ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val rawId = c.getLong(COL_RAW_ID)
                val mime = c.getString(COL_MIME) ?: continue
                val bucket = into.getOrPut(rawId) { HashMap() }
                fun put(field: RuleField, value: String?) {
                    val v = value?.trim().orEmpty()
                    if (v.isNotEmpty()) bucket.getOrPut(field) { mutableListOf() }.add(v)
                }
                when (mime) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        put(RuleField.FORMATTED_NAME, c.getString(D1))
                        put(RuleField.GIVEN_NAME, c.getString(D2))
                        put(RuleField.FAMILY_NAME, c.getString(D3))
                        put(RuleField.NAME_PREFIX, c.getString(D4))
                        put(RuleField.ADDITIONAL_NAMES, c.getString(D5))
                        put(RuleField.NAME_SUFFIX, c.getString(D6))
                        put(RuleField.PHONETIC_FIRST, c.getString(D7))
                        put(RuleField.PHONETIC_MIDDLE, c.getString(D8))
                        put(RuleField.PHONETIC_LAST, c.getString(D9))
                        // The synthetic whole-name field the "only has a name" rules test.
                        for (part in intArrayOf(D2, D3, D4, D5, D6)) {
                            put(RuleField.STRUCTURED_NAME, c.getString(part))
                        }
                    }
                    ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE ->
                        put(RuleField.NICKNAME, c.getString(D1))
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        put(RuleField.EMAIL, c.getString(D1))
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        put(RuleField.PHONE, c.getString(D1))
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        put(RuleField.ADDRESS_STREET, c.getString(D4))
                        put(RuleField.ADDRESS_LOCALITY, c.getString(D7))
                        put(RuleField.ADDRESS_REGION, c.getString(D8))
                        put(RuleField.ADDRESS_POSTAL_CODE, c.getString(D9))
                        put(RuleField.ADDRESS_COUNTRY, c.getString(D10))
                    }
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        // ORG is multi-valued on the card. The mapper puts the first component in
                        // COMPANY and joins the rest into DEPARTMENT, and the upload reads both
                        // back, so reading only COMPANY here would make "organisation contains
                        // Research" report nothing and then take those contacts off the phone.
                        put(RuleField.ORGANIZATION, c.getString(D1))
                        put(RuleField.ORGANIZATION, c.getString(D5))
                        put(RuleField.TITLE, c.getString(D4))
                        put(RuleField.ROLE, c.getString(D6))
                    }
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE ->
                        put(RuleField.NOTE, c.getString(D1))
                    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE ->
                        put(RuleField.URL, c.getString(D1))
                    ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE ->
                        put(RuleField.RELATED, c.getString(D1))
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        val field = when (c.getInt(D2)) {
                            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY -> RuleField.BIRTHDAY
                            ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY -> RuleField.ANNIVERSARY
                            else -> RuleField.CUSTOM_DATE
                        }
                        put(field, c.getString(D1))
                    }
                    @Suppress("DEPRECATION")
                    ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE ->
                        put(RuleField.SIP, c.getString(D1))
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE ->
                        bucket.getOrPut(RuleField.PHOTO) { mutableListOf() }.add("photo")
                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE -> {
                        val title = groupTitles[c.getLong(D1)]
                        if (title != null && title.lowercase() !in ContactsSyncAdapter.FORBIDDEN_GROUP_NAMES) {
                            groups.getOrPut(rawId) { mutableListOf() }.add(title)
                        }
                    }
                }
            }
        }
    }

    // Cursor positions, in the order of the projection above.
    private const val COL_RAW_ID = 0
    private const val COL_MIME = 1
    private const val D1 = 2
    private const val D2 = 3
    private const val D3 = 4
    private const val D4 = 5
    private const val D5 = 6
    private const val D6 = 7
    private const val D7 = 8
    private const val D8 = 9
    private const val D9 = 10
    private const val D10 = 11
}
