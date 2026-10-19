package me.proton.sync.contacts

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.*
import android.provider.ContactsContract.RawContacts
import android.util.Base64
import me.proton.sync.SyncLog
import me.proton.sync.SyncUtils
import ezvcard.VCard
import ezvcard.parameter.EmailType
import ezvcard.parameter.TelephoneType

/**
 * Maps between ez-vcard VCard objects and Android ContactsContract operations.
 */
object ContactsMapper {

    private const val TAG = "ContactsMapper"

    /** Samsung's vendor-specific MIMETYPE for the portrait/profile-card data row. */
    const val SAMSUNG_PROFILE_CARD_MIMETYPE = "vnd.sec.cursor.item/profile_card"

    /** vCard PHOTO parameter used to flag the Samsung portrait when round-tripping via Proton. */
    const val PORTRAIT_PARAM_NAME = "X-SAMSUNG-PORTRAIT"
    const val PORTRAIT_PARAM_VALUE = "1"

    /**
     * Mimetypes we own end-to-end via vCard (read on upload, written on download).
     *
     * The update path in [mapToAndroidOps] only deletes rows whose mimetype is in this
     * set, so vendor-specific extensions we don't manage — Samsung's profile_card,
     * Huawei skin photos, OnePlus tags, etc. — survive the wholesale rewrite that
     * happens on every Remote→Local sync. Keep this in sync with the mimetypes
     * actually emitted by [addDataOps] and consumed by buildVCardFromAndroid.
     */
    private val MANAGED_MIMETYPES = arrayOf(
        StructuredName.CONTENT_ITEM_TYPE,
        Email.CONTENT_ITEM_TYPE,
        Phone.CONTENT_ITEM_TYPE,
        StructuredPostal.CONTENT_ITEM_TYPE,
        Organization.CONTENT_ITEM_TYPE,
        Note.CONTENT_ITEM_TYPE,
        Photo.CONTENT_ITEM_TYPE,
        Event.CONTENT_ITEM_TYPE,
        Nickname.CONTENT_ITEM_TYPE,
        Website.CONTENT_ITEM_TYPE,
        Relation.CONTENT_ITEM_TYPE,
        @Suppress("DEPRECATION") SipAddress.CONTENT_ITEM_TYPE,
        GroupMembership.CONTENT_ITEM_TYPE
    )

    private fun isPortraitPhoto(photo: ezvcard.property.Photo): Boolean =
        photo.parameters.first(PORTRAIT_PARAM_NAME) == PORTRAIT_PARAM_VALUE

    /** Pick the contact's main avatar photo (non-portrait), falling back to whatever's first. */
    private fun primaryPhoto(vCard: VCard): ezvcard.property.Photo? =
        vCard.photos.firstOrNull { !isPortraitPhoto(it) } ?: vCard.photos.firstOrNull()

    /** Above this a photo is not put into the batch at full size; see [photoForBatch]. */
    private const val BATCH_PHOTO_MAX_BYTES = 128_000

    /**
     * Above this Samsung's portrait is scaled before its insert: one call carrying one row, so it can use
     * more than a batch, while staying clear of the ~1 MB Binder limit.
     */
    private const val PORTRAIT_MAX_BYTES = 500_000

    /** Longest side of the copy that goes into the batch instead. */
    private const val BATCH_PHOTO_DIM = 512

    /**
     * RawContacts.SYNC4 of a new contact while [writeContact] fills it in several calls; the last call
     * clears it. A row still carrying it is a half-written contact: the upload never sends it, and the
     * next sync removes it.
     */
    const val PARTIAL_WRITE_MARKER = "sfp-partial-download"

    sealed class WriteOutcome {
        /** Stored in [calls] provider calls. */
        data class Written(val rawContactId: Long, val calls: Int) : WriteOutcome()

        /** Not written at all; sizes in parcel bytes. */
        data class TooLarge(val totalBytes: Int, val largestRowBytes: Int) : WriteOutcome()
    }

    /**
     * Write a downloaded card into [existingRawContactId], or as a new raw contact when that is null.
     *
     * One batch when it fits [ContactWriteBudget.CALL_BYTES], which is nearly always, atomic as before.
     *
     * An existing contact is never split: a failure part way would leave a half-updated contact on the
     * phone, and a later edit to it would send that to Proton in place of the complete version. Up to
     * [ContactWriteBudget.SINGLE_CALL_MAX_BYTES] it is still tried as one batch, which usually fits the
     * Binder buffer, as it always was before the budget existed. A TransactionTooLargeException never
     * reaches the provider, so nothing is applied, and it comes back as [WriteOutcome.TooLarge], as does
     * anything larger.
     *
     * A new contact over the budget is created empty with [PARTIAL_WRITE_MARKER] and filled in several
     * batches, the last of which clears the marker. If a batch fails, the contact is removed again (a
     * sync-adapter delete leaves no tombstone) and the failure propagates, or comes back as TooLarge when
     * the size was refused. Should the removal fail as well, the marker keeps the row from ever being
     * uploaded and has the next sync remove it. A single row too large for any call is TooLarge at once.
     */
    fun writeContact(
        provider: android.content.ContentProviderClient,
        vCard: VCard,
        account: Account,
        existingRawContactId: Long?,
        androidGroupIds: List<Long>,
        nameMode: ContactNameMode,
    ): WriteOutcome {
        val ops = mapToAndroidOps(vCard, account, existingRawContactId, androidGroupIds, nameMode)
        val sizes = ops.map { parcelBytes(it) }
        val total = sizes.sum()
        val largest = sizes.maxOrNull() ?: 0
        if (total <= ContactWriteBudget.CALL_BYTES) {
            val results = applyBatchRetrying(provider, ops)
            return WriteOutcome.Written(existingRawContactId ?: ContentUris.parseId(results[0].uri!!), 1)
        }
        if (largest > ContactWriteBudget.SINGLE_CALL_MAX_BYTES ||
            (existingRawContactId != null && total > ContactWriteBudget.SINGLE_CALL_MAX_BYTES)
        ) {
            return WriteOutcome.TooLarge(total, largest)
        }
        if (existingRawContactId != null) {
            return try {
                applyBatchRetrying(provider, ops)
                WriteOutcome.Written(existingRawContactId, 1)
            } catch (e: android.os.TransactionTooLargeException) {
                SyncLog.w(TAG, "Contacts provider refused a ${total / 1024} KB update of raw contact $existingRawContactId: ${e.message}")
                WriteOutcome.TooLarge(total, largest)
            }
        }

        val rawUri = provider.insert(
            SyncUtils.asCallerSyncAdapter(RawContacts.CONTENT_URI, account),
            android.content.ContentValues().apply {
                put(RawContacts.ACCOUNT_NAME, account.name)
                put(RawContacts.ACCOUNT_TYPE, account.type)
                put(RawContacts.SYNC4, PARTIAL_WRITE_MARKER)
            }
        ) ?: throw IllegalStateException("The contacts provider returned no row for a new contact")
        val rawContactId = ContentUris.parseId(rawUri)
        val rawContactUri = SyncUtils.asCallerSyncAdapter(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId), account
        )
        var callKb = emptyList<Int>()
        var call = 0
        try {
            // Rebuilt against the real id, since a back reference only reaches within its own batch. The
            // update form starts by deleting the managed rows, which finds nothing on a contact this new.
            val rowOps = mapToAndroidOps(vCard, account, rawContactId, androidGroupIds, nameMode)
            // Last, so the marker only goes once every row is in.
            rowOps.add(ContentProviderOperation.newUpdate(rawContactUri).withValue(RawContacts.SYNC4, null).build())
            val rowSizes = rowOps.map { parcelBytes(it) }
            val ranges = ContactWriteBudget.chunk(rowSizes)
            callKb = ranges.map { range -> range.sumOf { rowSizes[it] } / 1024 }
            for (range in ranges) {
                call++
                applyBatchRetrying(provider, ArrayList(rowOps.subList(range.first, range.last + 1)))
            }
            SyncLog.i(TAG, "Wrote large contact $rawContactId (${total / 1024} KB) in ${ranges.size + 1} calls")
            return WriteOutcome.Written(rawContactId, ranges.size + 1)
        } catch (e: Throwable) {
            try {
                provider.delete(rawContactUri, null, null)
            } catch (cleanup: Throwable) {
                SyncLog.w(TAG, "Could not remove the half-written contact $rawContactId: ${cleanup.javaClass.simpleName}; the next sync removes it")
            }
            if (e is android.os.TransactionTooLargeException) {
                SyncLog.w(TAG, "Contacts provider refused call $call of ${callKb.size} (KB per call: $callKb) for new contact $rawContactId: ${e.message}")
                return WriteOutcome.TooLarge(total, largest)
            }
            throw e
        }
    }

    /**
     * applyBatch, tried again after [ContactWriteBudget.RETRY_DELAYS_MS] when Binder refuses the size. A
     * refused call never reaches the provider, so nothing was applied and repeating it is safe. The
     * refusal depends on what else is in the buffer at that moment, not only on this call. Rethrows the
     * last refusal; an interrupt (a cancelled sync) stops the retries and is left set.
     */
    private fun applyBatchRetrying(
        provider: android.content.ContentProviderClient,
        ops: ArrayList<ContentProviderOperation>,
    ): Array<android.content.ContentProviderResult> {
        var attempt = 0
        while (true) {
            try {
                return provider.applyBatch(ops)
            } catch (e: android.os.TransactionTooLargeException) {
                if (attempt >= ContactWriteBudget.RETRY_DELAYS_MS.size) throw e
                SyncLog.d(TAG, "Contacts provider refused a call (${e.message}); trying again")
                try {
                    Thread.sleep(ContactWriteBudget.RETRY_DELAYS_MS[attempt])
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                attempt++
            }
        }
    }

    /** What an operation adds to the Binder transaction that carries it. */
    private fun parcelBytes(op: ContentProviderOperation): Int {
        val parcel = android.os.Parcel.obtain()
        return try {
            op.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    /**
     * The photo as it goes into the batch's Photo.PHOTO row: unchanged up to [BATCH_PHOTO_MAX_BYTES],
     * above that shrunk to [BATCH_PHOTO_DIM] px, because the full image would take much of the Binder
     * buffer or more than all of it, failing the whole contact. Nothing is lost: the provider keeps only
     * a small thumbnail from this row, and [writeDisplayPhotoFromVCard] streams the full image right
     * after the batch through a file descriptor, which has no such limit. Null when a large image cannot
     * be decoded; the row is then left out rather than sent at a size that fails everything else.
     */
    private fun photoForBatch(bytes: ByteArray): ByteArray? {
        if (bytes.size <= BATCH_PHOTO_MAX_BYTES) return bytes
        val shrunk = scaledJpeg(bytes, BATCH_PHOTO_DIM)
        SyncLog.d(TAG, "Photo of ${bytes.size} bytes goes into the batch as ${shrunk?.size ?: "nothing"} bytes; the full image follows as the display photo")
        return shrunk
    }

    /** [bytes] re-encoded as a JPEG no larger than [maxDim] px on its longest side, or null if undecodable. */
    private fun scaledJpeg(bytes: ByteArray, maxDim: Int, quality: Int = 85): ByteArray? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            // Decode at the largest power-of-two reduction still at least maxDim, so a huge image never
            // has to fit in memory at full size.
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            val decoded = android.graphics.BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            )
            if (decoded == null) {
                null
            } else {
                val scale = minOf(1f, maxDim.toFloat() / maxOf(decoded.width, decoded.height))
                val scaled = if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    decoded
                }
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
                out.toByteArray()
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * Create ContentProviderOperations to insert or update an Android contact from a vCard.
     */
    fun mapToAndroidOps(
        vCard: VCard,
        account: Account,
        existingRawContactId: Long?,
        androidGroupIds: List<Long> = emptyList(),
        nameMode: ContactNameMode = ContactNameMode.ANDROID
    ): ArrayList<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()
        val dataUri = SyncUtils.asCallerSyncAdapter(ContactsContract.Data.CONTENT_URI, account)

        if (existingRawContactId != null) {
            // Update: delete only the rows we actually manage (see MANAGED_MIMETYPES).
            // Anything else (Samsung's profile_card portrait, other vendor extensions)
            // is left untouched so it survives the rewrite.
            val placeholders = MANAGED_MIMETYPES.joinToString(",") { "?" }
            ops.add(
                ContentProviderOperation.newDelete(dataUri)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                                "${ContactsContract.Data.MIMETYPE} IN ($placeholders)",
                        arrayOf(existingRawContactId.toString(), *MANAGED_MIMETYPES)
                    )
                    .build()
            )
            addDataOps(ops, vCard, dataUri, rawContactId = existingRawContactId, androidGroupIds = androidGroupIds, nameMode = nameMode)
        } else {
            // Insert: create RawContact first, then data rows
            ops.add(
                ContentProviderOperation.newInsert(
                    SyncUtils.asCallerSyncAdapter(RawContacts.CONTENT_URI, account)
                )
                    .withValue(RawContacts.ACCOUNT_NAME, account.name)
                    .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                    .build()
            )
            addDataOps(ops, vCard, dataUri, backReference = 0, androidGroupIds = androidGroupIds, nameMode = nameMode)
        }

        return ops
    }

    private fun addDataOps(
        ops: ArrayList<ContentProviderOperation>,
        vCard: VCard,
        dataUri: Uri,
        rawContactId: Long? = null,
        backReference: Int? = null,
        androidGroupIds: List<Long> = emptyList(),
        nameMode: ContactNameMode = ContactNameMode.ANDROID
    ) {
        // Structured Name (including phonetic names from X-PHONETIC-* properties)
        val name = vCard.structuredName
        val phoneticFirst = vCard.getExtendedProperty("X-PHONETIC-FIRST-NAME")?.value
        val phoneticMiddle = vCard.getExtendedProperty("X-PHONETIC-MIDDLE-NAME")?.value
        val phoneticLast = vCard.getExtendedProperty("X-PHONETIC-LAST-NAME")?.value
        if (name != null) {
            val op = ContentProviderOperation.newInsert(dataUri)
                .withRawContactReference(rawContactId, backReference)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.GIVEN_NAME, name.given)
                .withValue(StructuredName.FAMILY_NAME, name.family)
                .withValue(StructuredName.MIDDLE_NAME, name.additionalNames?.joinToString(" "))
                .withValue(StructuredName.PREFIX, name.prefixes?.joinToString(" "))
                .withValue(StructuredName.SUFFIX, name.suffixes?.joinToString(" "))
            val fn = vCard.formattedName?.value
            if (fn != null) op.withValue(StructuredName.DISPLAY_NAME, fn)
            if (phoneticFirst != null) op.withValue(StructuredName.PHONETIC_GIVEN_NAME, phoneticFirst)
            if (phoneticMiddle != null) op.withValue(StructuredName.PHONETIC_MIDDLE_NAME, phoneticMiddle)
            if (phoneticLast != null) op.withValue(StructuredName.PHONETIC_FAMILY_NAME, phoneticLast)
            ops.add(op.build())
        } else if (vCard.formattedName != null) {
            // Proton has a display name and no structured name, which is every contact it
            // auto-creates from a mail exchange. Handing the provider a display name on its own
            // makes it invent the other half by splitting on the dot, and then rebuild the display
            // name from the pieces, so "app@example.com" ends up shown as "app@example com".
            // Filling one component deliberately leaves the provider nothing to invent.
            val display = vCard.formattedName.value
            val op = ContentProviderOperation.newInsert(dataUri)
                .withRawContactReference(rawContactId, backReference)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, display)
            // A contact that already has a nickname on Proton keeps it; borrowing that slot would
            // overwrite something the user actually entered. Such a contact falls back to putting
            // the display name in the first name, which still leaves the provider nothing to split.
            val nicknameTaken = vCard.nickname?.values?.isNotEmpty() == true
            val effectiveMode =
                if (nameMode == ContactNameMode.NICKNAME && nicknameTaken) ContactNameMode.GIVEN
                else nameMode
            when (effectiveMode) {
                ContactNameMode.GIVEN -> op.withValue(StructuredName.GIVEN_NAME, display)
                ContactNameMode.FAMILY -> op.withValue(StructuredName.FAMILY_NAME, display)
                // Handled below: the name row is skipped entirely so the provider has nothing to
                // split, and the nickname becomes the contact's display-name source.
                ContactNameMode.NICKNAME -> Unit
                ContactNameMode.ANDROID -> Unit
            }
            if (phoneticFirst != null) op.withValue(StructuredName.PHONETIC_GIVEN_NAME, phoneticFirst)
            if (phoneticMiddle != null) op.withValue(StructuredName.PHONETIC_MIDDLE_NAME, phoneticMiddle)
            if (phoneticLast != null) op.withValue(StructuredName.PHONETIC_FAMILY_NAME, phoneticLast)
            if (effectiveMode == ContactNameMode.NICKNAME) {
                ops.add(
                    ContentProviderOperation.newInsert(dataUri)
                        .withRawContactReference(rawContactId, backReference)
                        .withValue(ContactsContract.Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE)
                        .withValue(Nickname.NAME, display)
                        .build()
                )
                // Phonetic components would drag a structured name back in, so they only ride
                // along when there is one.
                if (phoneticFirst != null || phoneticMiddle != null || phoneticLast != null) {
                    ops.add(op.build())
                }
            } else {
                ops.add(op.build())
            }
        }

        // Emails
        for (email in vCard.emails) {
            // v1.4.27: also recognise CELL/MOBILE on email so the round-trip
            // preserves Android's TYPE_MOBILE label (Samsung Contacts exposes it).
            val type = when {
                email.types.contains(EmailType.HOME) -> Email.TYPE_HOME
                email.types.contains(EmailType.WORK) -> Email.TYPE_WORK
                email.types.any { it.value.equals("cell", ignoreCase = true) ||
                                  it.value.equals("mobile", ignoreCase = true) } -> Email.TYPE_MOBILE
                else -> Email.TYPE_OTHER
            }
            ops.add(
                ContentProviderOperation.newInsert(dataUri)
                    .withRawContactReference(rawContactId, backReference)
                    .withValue(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, email.value)
                    .withValue(Email.TYPE, type)
                    .build()
            )
        }

        // Phone numbers
        // v1.4.27: handle HOME+FAX and WORK+FAX combinations explicitly so the
        // round-trip preserves Android's separate TYPE_FAX_HOME / TYPE_FAX_WORK.
        for (phone in vCard.telephoneNumbers) {
            val hasHome = phone.types.contains(TelephoneType.HOME)
            val hasWork = phone.types.contains(TelephoneType.WORK)
            val hasFax = phone.types.contains(TelephoneType.FAX)
            val type = when {
                hasFax && hasHome -> Phone.TYPE_FAX_HOME
                hasFax && hasWork -> Phone.TYPE_FAX_WORK
                hasFax -> Phone.TYPE_FAX_WORK
                hasHome -> Phone.TYPE_HOME
                hasWork -> Phone.TYPE_WORK
                phone.types.contains(TelephoneType.CELL) -> Phone.TYPE_MOBILE
                phone.types.contains(TelephoneType.PAGER) -> Phone.TYPE_PAGER
                phone.types.any { it.value.equals("main", ignoreCase = true) } -> Phone.TYPE_MAIN
                phone.types.any { it.value.equals("other", ignoreCase = true) } -> Phone.TYPE_OTHER
                else -> Phone.TYPE_OTHER
            }
            ops.add(
                ContentProviderOperation.newInsert(dataUri)
                    .withRawContactReference(rawContactId, backReference)
                    .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, phone.text)
                    .withValue(Phone.TYPE, type)
                    .build()
            )
        }

        // Addresses
        for (addr in vCard.addresses) {
            val type = when {
                addr.types.any { it.value.equals("home", ignoreCase = true) } -> StructuredPostal.TYPE_HOME
                addr.types.any { it.value.equals("work", ignoreCase = true) } -> StructuredPostal.TYPE_WORK
                else -> StructuredPostal.TYPE_OTHER
            }
            ops.add(
                ContentProviderOperation.newInsert(dataUri)
                    .withRawContactReference(rawContactId, backReference)
                    .withValue(ContactsContract.Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(StructuredPostal.STREET, addr.streetAddress)
                    .withValue(StructuredPostal.CITY, addr.locality)
                    .withValue(StructuredPostal.REGION, addr.region)
                    .withValue(StructuredPostal.POSTCODE, addr.postalCode)
                    .withValue(StructuredPostal.COUNTRY, addr.country)
                    .withValue(StructuredPostal.TYPE, type)
                    .build()
            )
        }

        // Organization + Title + Role
        val org = vCard.organization
        val title = vCard.titles?.firstOrNull()
        val role = vCard.roles?.firstOrNull()
        if (org != null || title != null || role != null) {
            val op = ContentProviderOperation.newInsert(dataUri)
                .withRawContactReference(rawContactId, backReference)
                .withValue(ContactsContract.Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
            org?.let {
                op.withValue(Organization.COMPANY, it.values?.firstOrNull())
                if (it.values != null && it.values.size > 1) {
                    op.withValue(Organization.DEPARTMENT, it.values.drop(1).joinToString(";"))
                }
            }
            title?.let { op.withValue(Organization.TITLE, it.value) }
            role?.let { op.withValue(Organization.JOB_DESCRIPTION, it.value) }
            ops.add(op.build())
        }

        // Notes
        for (note in vCard.notes) {
            ops.add(
                ContentProviderOperation.newInsert(dataUri)
                    .withRawContactReference(rawContactId, backReference)
                    .withValue(ContactsContract.Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                    .withValue(Note.NOTE, note.value)
                    .build()
            )
        }

        // Photo - handle both binary data and data URI format
        for (photo in vCard.photos) {
            val rawUrl = photo.url?.toString()
            val portraitTag = if (isPortraitPhoto(photo)) " (portrait)" else ""
            // The URL's scheme and length only: a contact's photo link is the contact's own data.
            val urlShape = rawUrl?.let { "${it.substringBefore(':', "?")}:(${it.length} chars)" }
            SyncLog.d(TAG, "Photo$portraitTag: data=${photo.data?.size ?: "null"} bytes, url=${urlShape ?: "null"}")
        }
        val photo = primaryPhoto(vCard)
        if (photo != null) {
            val photoBytes = (photo.data ?: tryDecodePhotoUri(photo.url?.toString()))?.let { photoForBatch(it) }
            if (photoBytes != null) {
                ops.add(
                    ContentProviderOperation.newInsert(dataUri)
                        .withRawContactReference(rawContactId, backReference)
                        .withValue(ContactsContract.Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                        .withValue(Photo.PHOTO, photoBytes)
                        .build()
                )
            }
        }

        // Birthday
        val bday = vCard.birthday
        if (bday != null) {
            val dateStr = parseDateProperty(bday)
            if (dateStr != null) {
                ops.add(
                    ContentProviderOperation.newInsert(dataUri)
                        .withRawContactReference(rawContactId, backReference)
                        .withValue(ContactsContract.Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                        .withValue(Event.START_DATE, dateStr)
                        .withValue(Event.TYPE, Event.TYPE_BIRTHDAY)
                        .build()
                )
            }
        }

        // Anniversary
        val anniversary = vCard.anniversary
        if (anniversary != null) {
            val dateStr = parseDateProperty(anniversary)
            if (dateStr != null) {
                ops.add(
                    ContentProviderOperation.newInsert(dataUri)
                        .withRawContactReference(rawContactId, backReference)
                        .withValue(ContactsContract.Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                        .withValue(Event.START_DATE, dateStr)
                        .withValue(Event.TYPE, Event.TYPE_ANNIVERSARY)
                        .build()
                )
            }
        }

        // Nickname
        val nickname = vCard.nickname
        if (nickname != null && nickname.values?.isNotEmpty() == true) {
            ops.add(
                ContentProviderOperation.newInsert(dataUri)
                    .withRawContactReference(rawContactId, backReference)
                    .withValue(ContactsContract.Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE)
                    .withValue(Nickname.NAME, nickname.values.first())
                    .build()
            )
        }

        // Websites
        for (url in vCard.urls) {
            ops.add(
                ContentProviderOperation.newInsert(dataUri)
                    .withRawContactReference(rawContactId, backReference)
                    .withValue(ContactsContract.Data.MIMETYPE, Website.CONTENT_ITEM_TYPE)
                    .withValue(Website.URL, url.value)
                    .withValue(Website.TYPE, Website.TYPE_OTHER)
                    .build()
            )
        }

        // Relationships (RELATED property)
        for (related in vCard.relations) {
            val name = related.text ?: related.uri ?: continue
            val type = when (related.types?.firstOrNull()?.value?.lowercase()) {
                "spouse" -> Relation.TYPE_SPOUSE
                "child" -> Relation.TYPE_CHILD
                "parent" -> Relation.TYPE_PARENT
                "sibling" -> Relation.TYPE_RELATIVE
                "friend" -> Relation.TYPE_FRIEND
                "colleague" -> Relation.TYPE_ASSISTANT
                else -> Relation.TYPE_CUSTOM
            }
            val op = ContentProviderOperation.newInsert(dataUri)
                .withRawContactReference(rawContactId, backReference)
                .withValue(ContactsContract.Data.MIMETYPE, Relation.CONTENT_ITEM_TYPE)
                .withValue(Relation.NAME, name)
                .withValue(Relation.TYPE, type)
            if (type == Relation.TYPE_CUSTOM) {
                op.withValue(Relation.LABEL, related.types?.firstOrNull()?.value ?: "other")
            }
            ops.add(op.build())
        }

        // SIP address (X-SIP extended property)
        val sipProp = vCard.getExtendedProperty("X-SIP")
        if (sipProp != null) {
            @Suppress("DEPRECATION")
            ops.add(
                ContentProviderOperation.newInsert(dataUri)
                    .withRawContactReference(rawContactId, backReference)
                    .withValue(ContactsContract.Data.MIMETYPE, SipAddress.CONTENT_ITEM_TYPE)
                    .withValue(SipAddress.SIP_ADDRESS, sipProp.value)
                    .withValue(SipAddress.TYPE, SipAddress.TYPE_OTHER)
                    .build()
            )
        }

        // Custom date events (X-ABDATE extended property)
        for (prop in vCard.getExtendedProperties("X-ABDATE")) {
            val label = prop.getParameter("X-ABLABEL")
            ops.add(
                ContentProviderOperation.newInsert(dataUri)
                    .withRawContactReference(rawContactId, backReference)
                    .withValue(ContactsContract.Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                    .withValue(Event.START_DATE, prop.value)
                    .withValue(Event.TYPE, Event.TYPE_OTHER)
                    .withValue(Event.LABEL, label)
                    .build()
            )
        }

        // Group memberships
        for (groupId in androidGroupIds) {
            ops.add(
                ContentProviderOperation.newInsert(dataUri)
                    .withRawContactReference(rawContactId, backReference)
                    .withValue(ContactsContract.Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(GroupMembership.GROUP_ROW_ID, groupId)
                    .build()
            )
        }
    }

    /**
     * Map a vCard date property (BDAY/ANNIVERSARY) to Android's Event.START_DATE string.
     *
     * Handles all four wire forms seen on Proton contacts (v1.6.23):
     *  - text values (our own uploads: "--02-15" or "1990-02-15") — passed through verbatim;
     *  - real dates (BDAY:19900215) — formatted as yyyy-MM-dd;
     *  - Apple's omit-year convention (BDAY;X-APPLE-OMIT-YEAR=1604:16040215) — the sentinel
     *    year is stripped to Android's native year-less "--MM-dd" instead of leaking a
     *    literal year-1604 birthday;
     *  - RFC 6350 truncated dates (BDAY:--0215, written by iOS/DAVx5/...) — these land in
     *    ez-vcard's PartialDate slot (text and date both null) and were previously dropped
     *    silently.
     *
     * Internal rather than private because the contact filter has to read a birthday the same way
     * this writes one. `VCardContactView` used to do its own two-branch `text ?: date` read, so a
     * year-less BDAY looked absent to a rule and present to the row this mapper had just written
     * from the very same card, and the two disagreed about the same contact.
     */
    internal fun parseDateProperty(prop: ezvcard.property.DateOrTimeProperty?): String? {
        if (prop == null) return null
        prop.text?.let { return it }
        prop.date?.let { date ->
            return try {
                val localDate = java.time.LocalDate.from(date)
                val omitYear = prop.getParameter("X-APPLE-OMIT-YEAR")?.toIntOrNull()
                if (omitYear != null && omitYear == localDate.year) {
                    String.format("--%02d-%02d", localDate.monthValue, localDate.dayOfMonth)
                } else {
                    String.format("%04d-%02d-%02d", localDate.year, localDate.monthValue, localDate.dayOfMonth)
                }
            } catch (e: Exception) {
                null
            }
        }
        prop.partialDate?.let { partial ->
            val year = partial.year
            val month = partial.month
            val day = partial.date
            return when {
                year != null && month != null && day != null ->
                    String.format("%04d-%02d-%02d", year, month, day)
                month != null && day != null ->
                    String.format("--%02d-%02d", month, day)
                else -> null
            }
        }
        return null
    }

    /**
     * Try to decode a photo from a data URI (data:image/jpeg;base64,...).
     */
    private fun tryDecodePhotoUri(uri: String?): ByteArray? {
        if (uri == null) return null
        try {
            // Handle data URI format: data:image/jpeg;base64\,/9j/4A...
            val dataPrefix = "data:"
            if (uri.startsWith(dataPrefix)) {
                val base64Marker = "base64"
                val base64Idx = uri.indexOf(base64Marker, ignoreCase = true)
                if (base64Idx >= 0) {
                    // Skip past "base64" and any delimiter (comma, escaped comma)
                    var dataStart = base64Idx + base64Marker.length
                    while (dataStart < uri.length && (uri[dataStart] == ',' || uri[dataStart] == '\\')) {
                        dataStart++
                    }
                    val base64Data = uri.substring(dataStart).replace("\\s+".toRegex(), "")
                    return Base64.decode(base64Data, Base64.DEFAULT)
                }
            }
        } catch (e: Exception) {
            SyncLog.w(TAG, "Failed to decode photo URI: ${e.message}")
        }
        return null
    }

    private fun ContentProviderOperation.Builder.withRawContactReference(
        rawContactId: Long?,
        backReference: Int?
    ): ContentProviderOperation.Builder {
        return if (rawContactId != null) {
            withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
        } else {
            withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backReference!!)
        }
    }

    /**
     * Write the vCard's photo to the raw contact's display-photo asset URI.
     *
     * Why a separate call (and not just another batch op): Android's
     * `RawContacts.DisplayPhoto.CONTENT_DIRECTORY` is an *asset* URI, not a
     * regular row. It's written via `openAssetFile(...).createOutputStream()`
     * — there's no `ContentProviderOperation` form for it, so this can't
     * piggyback on the batch and has to run after the rawContactId is known.
     *
     * The thumbnail at `Photo.PHOTO` is still written via the regular batch
     * op in [mapToAndroidOps] for instant display (Photo.PHOTO is what the
     * dialer / calendar / lock-screen read for the small icon). Writing the
     * full-resolution display photo here ensures other apps that ask for
     * the high-res version (e.g. when a contact appears full-screen on
     * incoming call) get the original quality back.
     *
     * No-ops cleanly when the vCard has no photo, when the photo bytes are
     * empty, or when the asset write fails. Errors are logged at WARN level
     * so a single bad photo doesn't break the whole sync.
     */
    fun writeDisplayPhotoFromVCard(
        provider: android.content.ContentProviderClient,
        rawContactId: Long,
        vCard: VCard,
        account: Account
    ) {
        val photoProperty = primaryPhoto(vCard) ?: return
        val photoBytes = photoProperty.data ?: tryDecodePhotoUri(photoProperty.url?.toString()) ?: return
        if (photoBytes.isEmpty()) return

        try {
            // Tagged as the sync adapter, which is right for the write itself but does not stop the
            // provider marking the contact changed once it has stored the photo; see awaitPhotoStored.
            val rawContactUri = SyncUtils.asCallerSyncAdapter(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId),
                account
            )
            val displayPhotoUri = Uri.withAppendedPath(
                rawContactUri, ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY
            )
            val rowBefore = photoRowVersion(provider, rawContactId)
            val written = provider.openAssetFile(displayPhotoUri, "rw")?.use { afd ->
                afd.createOutputStream().use { out ->
                    out.write(photoBytes)
                }
                true
            } ?: false
            SyncLog.d(TAG, "Wrote display photo for raw contact $rawContactId: ${photoBytes.size} bytes")
            if (written) awaitPhotoStored(provider, rawContactId, rowBefore)
        } catch (e: Exception) {
            SyncLog.w(TAG, "Display photo write failed for raw contact $rawContactId: ${e.message}")
        }
    }

    /** How long [awaitPhotoStored] waits for the provider; measured at 60 to 80 ms on a Note 10+. */
    private const val PHOTO_STORE_TIMEOUT_MS = 2_000L

    /**
     * Returns once the provider has stored a display photo written through its asset file.
     *
     * The write returns as soon as the bytes are in the pipe. The provider stores the photo afterwards
     * on a background thread, and that step updates the photo row without the sync-adapter flag, so it
     * marks the contact as changed on the phone. Measured on a Note 10+ (Android 12): 60 to 80 ms after
     * the write, DIRTY went to 1 together with the new PHOTO_FILE_ID. The download clears DIRTY after
     * this call; before the wait, that clear came first and was undone, so the same sync uploaded the
     * contact it had just downloaded, with its photo re-encoded. The new size made contact filter rules
     * re-judge the contact, which downloaded it again: every photo contact went down and up on every
     * sync, losing a little photo quality each time.
     *
     * Waits for the photo row to change from [before]: a new row, or its DATA_VERSION bumped by the
     * provider's update. Not PHOTO_FILE_ID, which stays null for an image no larger than the thumbnail
     * size, so waiting for it stalled every small photo for the full timeout. On timeout (an image the
     * provider cannot decode never updates the row) the contact may upload once more, which is what
     * happened on every download before. An interrupt (a cancelled sync) ends the wait and leaves the
     * interrupt set for the sync loop to see.
     */
    private fun awaitPhotoStored(
        provider: android.content.ContentProviderClient,
        rawContactId: Long,
        before: Pair<Long, Long>?,
    ) {
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            while (android.os.SystemClock.elapsedRealtime() - start < PHOTO_STORE_TIMEOUT_MS) {
                val now = photoRowVersion(provider, rawContactId)
                if (now != null && now != before) {
                    SyncLog.d(TAG, "Display photo for raw contact $rawContactId stored after ${android.os.SystemClock.elapsedRealtime() - start} ms")
                    return
                }
                Thread.sleep(20)
            }
            SyncLog.d(TAG, "Display photo for raw contact $rawContactId not stored within $PHOTO_STORE_TIMEOUT_MS ms; the contact may upload once")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** The (_ID, DATA_VERSION) of a raw contact's photo row, or null without one. */
    private fun photoRowVersion(provider: android.content.ContentProviderClient, rawContactId: Long): Pair<Long, Long>? =
        provider.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA_VERSION),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), Photo.CONTENT_ITEM_TYPE),
            null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }

    /**
     * Restore Samsung's `vnd.sec.cursor.item/profile_card` row from a portrait PHOTO
     * carried in the vCard.
     *
     * Samsung Contacts has a "profile card" — a large portrait/landscape image
     * shown alongside the regular round avatar — stored as a vendor-specific data
     * row. We round-trip it via Proton by emitting a second `PHOTO` property
     * marked with `X-SAMSUNG-PORTRAIT=1`; on download, this writer recreates the
     * row with sane defaults.
     *
     * Best-effort: we synthesize `data1` JSON (full-image crop + centered square
     * crop) and a SHA-1 hash for `data14`, and write the JPEG bytes into `data15`.
     * Samsung's UI may render directly from `data15` even without the internal
     * `data2`/`data3` photo file IDs (which only its own writer can mint). If
     * Samsung ignores the row, the [MANAGED_MIMETYPES] allow-list delete still
     * keeps any pre-existing profile_card row untouched, so nothing regresses.
     */
    fun writeSamsungProfileCardFromVCard(
        provider: android.content.ContentProviderClient,
        rawContactId: Long,
        vCard: VCard,
        account: Account
    ) {
        val portraitPhoto = vCard.photos.firstOrNull { isPortraitPhoto(it) } ?: return
        val original = portraitPhoto.data ?: tryDecodePhotoUri(portraitPhoto.url?.toString()) ?: return
        if (original.isEmpty()) return
        // The insert below carries the whole image in DATA15, one Binder call with the same limit as a
        // batch. A portrait over the budget is stored at the size the upload caps portraits to anyway.
        val photoBytes = if (original.size <= PORTRAIT_MAX_BYTES) {
            original
        } else {
            scaledJpeg(original, me.proton.sync.SyncConstants.MAX_CONTACT_PHOTO_SIZE)
                ?.takeIf { it.size <= PORTRAIT_MAX_BYTES }
                ?: scaledJpeg(original, BATCH_PHOTO_DIM)?.takeIf { it.size <= PORTRAIT_MAX_BYTES }
                ?: run {
                    SyncLog.w(TAG, "Samsung profile_card for raw contact $rawContactId skipped: ${original.size} bytes and cannot be shrunk")
                    return
                }
        }

        try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size, opts)
            val w = opts.outWidth.coerceAtLeast(1)
            val h = opts.outHeight.coerceAtLeast(1)
            val sq = minOf(w, h)
            val sqLeft = (w - sq) / 2
            val sqTop = (h - sq) / 2
            val data1Json =
                """{"crop_rect":[0,0,$w,$h],"square_crop_rect":[$sqLeft,$sqTop,${sqLeft + sq},${sqTop + sq}],"name_color":"adaptive,-405132"}"""

            val hash = java.security.MessageDigest.getInstance("SHA-1").digest(photoBytes)
            val hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP)

            val dataUri = SyncUtils.asCallerSyncAdapter(ContactsContract.Data.CONTENT_URI, account)
            // Idempotent: replace any existing profile_card row for this contact.
            provider.delete(
                dataUri,
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(rawContactId.toString(), SAMSUNG_PROFILE_CARD_MIMETYPE)
            )
            val values = android.content.ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, SAMSUNG_PROFILE_CARD_MIMETYPE)
                put(ContactsContract.Data.DATA1, data1Json)
                put(ContactsContract.Data.DATA14, hashB64)
                put(ContactsContract.Data.DATA15, photoBytes)
            }
            provider.insert(dataUri, values)
            SyncLog.d(TAG, "Wrote Samsung profile_card for raw contact $rawContactId: ${photoBytes.size} bytes (${w}x$h)")
        } catch (e: Exception) {
            SyncLog.w(TAG, "Samsung profile_card write failed for raw contact $rawContactId: ${e.message}")
        }
    }

}
