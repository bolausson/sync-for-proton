package me.proton.sync.contacts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.parameter.ImageType
import ezvcard.property.FormattedName
import ezvcard.property.Photo
import ezvcard.property.StructuredName
import ezvcard.property.Uid
import android.accounts.Account
import kotlinx.coroutines.delay
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncLog
import me.proton.sync.SyncUtils
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.models.CreateContactBody
import me.proton.sync.api.models.CreateContactRequest
import me.proton.sync.api.models.DeleteContactsRequest
import me.proton.sync.api.models.UpdateContactRequest
import me.proton.sync.crypto.ContactDecryptor
import me.proton.sync.crypto.KeyManager
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Empirical probe for Proton's contact-card size limit.
 *
 * Proton can't see inside the encrypted vCard, so the real cap is on the
 * encrypted blob's bytes — not on photo dimensions. This probe iterates a
 * ladder of (dimensions, JPEG quality) pairs starting at the largest practical
 * size and stepping down until something is accepted, recording the encrypted
 * blob size at every step.
 *
 * Strictly debug-only:
 * - Creates one contact named ZZ_Photo_Calibration_<timestamp>
 * - Updates it about ten times with progressively smaller photos
 * - Reads each accepted upload back and verifies a round-trip
 * - Deletes the contact in a `finally` block, no matter what
 *
 * Output goes to [SyncLog]; the caller gets a one-line [Result] summary.
 */
@Singleton
class PhotoSizeCalibrator @Inject constructor(
    private val apiClient: ProtonApiClient,
    private val contactDecryptor: ContactDecryptor,
    private val sessionManager: AccountSessionManager,
    private val keyManager: KeyManager
) {
    companion object {
        private const val TAG = "PhotoSizeCalibrator"

        /**
         * Step-down ladder of (dimensions, JPEG quality) pairs.
         *
         * We start big enough to almost certainly trip Proton's blob limit,
         * then walk down. The two passes at 2048 (q=90 then q=75) give us a
         * crude byte-vs-quality data point at the same dimension.
         */
        private val LADDER = listOf(
            Step(2048, 90),
            Step(2048, 75),
            Step(1440, 85),
            Step(1024, 85),
            Step(720, 85),
            Step(512, 85),
            Step(360, 85),
            Step(256, 85),
            Step(180, 85),
            Step(128, 85)
        )

        private const val INTER_STEP_DELAY_MS = 2_000L
        private const val NAME_PREFIX = "ZZ_Photo_Calibration_"
    }

    data class Step(val dimensionPx: Int, val jpegQuality: Int)

    data class StepResult(
        val step: Step,
        val rawJpegBytes: Int,
        /** Total wire bytes sent (sum of all card data + signature lengths). */
        val encryptedWireBytes: Int,
        val accepted: Boolean,
        val httpCode: Int,
        val httpBody: String?,
        val roundTripVerified: Boolean
    )

    data class Result(
        val accountName: String,
        val sourcePixels: Int,
        val sourceBytes: Int,
        val steps: List<StepResult>
    ) {
        val firstAccepted: StepResult? = steps.firstOrNull { it.accepted }
        val lastRejected: StepResult? = steps.firstOrNull { !it.accepted }

        fun summaryLine(): String {
            val acc = firstAccepted
            val rej = lastRejected
            return buildString {
                if (acc != null) {
                    append("Max accepted: ${acc.step.dimensionPx}x${acc.step.dimensionPx} ")
                    append("q=${acc.step.jpegQuality} ")
                    append("(encrypted ${acc.encryptedWireBytes / 1024} KB, ")
                    append("raw JPEG ${acc.rawJpegBytes / 1024} KB)")
                } else {
                    append("Nothing accepted! All steps rejected by Proton.")
                }
                if (rej != null) {
                    append(" - first rejection: ${rej.step.dimensionPx}x${rej.step.dimensionPx} ")
                    append("q=${rej.step.jpegQuality} ")
                    append("(encrypted ${rej.encryptedWireBytes / 1024} KB, ")
                    append("HTTP ${rej.httpCode})")
                }
            }
        }
    }

    /**
     * Run the probe against [accountName]. The bitmap is generated procedurally
     * (a colorful gradient + noise) so we don't need to bundle a sample image
     * and the photo's compressibility is roughly typical of a real photograph.
     *
     * Caller must be on a background thread.
     */
    suspend fun calibrate(accountName: String): Result {
        SyncLog.i(TAG, "=== Photo size calibration starting for account=$accountName ===")

        // Make sure PGP keys are unlocked — encryptContactCards needs them.
        // After app restart they may not be in KeyManager's in-memory cache yet.
        try {
            val account = Account(accountName, ProtonSyncApp.ACCOUNT_TYPE)
            val session = sessionManager.getSession(account)
            SyncUtils.ensureKeysUnlocked(account, session, keyManager, apiClient, TAG)
        } catch (e: Exception) {
            SyncLog.e(TAG, "Could not unlock keys for calibration: ${e.message}", e)
            throw e
        }

        val sourceBitmap = generateSourceBitmap(LADDER.first().dimensionPx)
        val sourcePixels = sourceBitmap.width  // captured early — finally{} recycles the bitmap
        val sourceBytes = bitmapToJpeg(sourceBitmap, LADDER.first().jpegQuality).size
        SyncLog.i(TAG, "Source: ${sourcePixels}x${sourcePixels}, " +
                "first-step JPEG = ${sourceBytes / 1024} KB")

        val testContactName = NAME_PREFIX + System.currentTimeMillis()
        // One UID for the whole run: the ladder's updates keep it, as an upload of an edited contact does.
        val testContactUid = newContactUid()
        var protonContactId: String? = null
        val stepResults = mutableListOf<StepResult>()

        try {
            // Create the test contact with the smallest photo first so the
            // create request itself is guaranteed to succeed; we then UPDATE
            // it through the ladder from the top.
            val initialStep = LADDER.last()
            val initialBitmap = sourceBitmap.scaled(initialStep.dimensionPx)
            val initialJpeg = bitmapToJpeg(initialBitmap, initialStep.jpegQuality)
            initialBitmap.recyclesIfNotSame(sourceBitmap)

            val initialVCard = buildVCard(testContactName, initialJpeg, testContactUid)
            SyncLog.d(TAG, "Initial vCard (${initialVCard.length} chars):\n$initialVCard")
            val initialCards = contactDecryptor.encryptContactCards(accountName, initialVCard)
            SyncLog.d(TAG, "Initial cards: ${initialCards.size} card(s), " +
                    "types=${initialCards.map { it.type }}, " +
                    "data lens=${initialCards.map { it.data.length }}, " +
                    "sig lens=${initialCards.map { it.signature?.length ?: 0 }}")
            val createResponse = apiClient.createContacts(
                accountName,
                CreateContactRequest(contacts = listOf(CreateContactBody(cards = initialCards)))
            )
            if (!createResponse.isSuccessful) {
                val errBody = try { createResponse.errorBody()?.string()?.take(500) } catch (_: Exception) { null }
                SyncLog.e(TAG, "Initial create HTTP ${createResponse.code()} - body=$errBody")
                return Result(accountName, sourcePixels, sourceBytes, emptyList())
            }

            // Dump full body so we can see Proton's actual reply shape
            val rawBody = createResponse.body()
            SyncLog.d(TAG, "createContacts raw body keys=${rawBody?.keys}, full=${rawBody.toString().take(800)}")

            @Suppress("UNCHECKED_CAST")
            val responses = (rawBody?.get("Responses") as? List<Map<*, *>>).orEmpty()
            val first = responses.firstOrNull()
            val innerResp = first?.get("Response") as? Map<*, *>
            val innerCode = (innerResp?.get("Code") as? Number)?.toInt()
            val innerError = innerResp?.get("Error") as? String
            val outerCode = (rawBody?.get("Code") as? Number)?.toInt()
            SyncLog.d(TAG, "createContacts parsed: outerCode=$outerCode, " +
                    "innerCode=$innerCode, innerError=$innerError, " +
                    "responses.size=${responses.size}")

            val contact = innerResp?.get("Contact") as? Map<*, *>
            protonContactId = contact?.get("ID") as? String
            if (protonContactId == null) {
                SyncLog.e(TAG, "Initial create returned no contact ID. " +
                        "outerCode=$outerCode innerCode=$innerCode innerError=$innerError - aborting probe")
                return Result(accountName, sourcePixels, sourceBytes, emptyList())
            }
            SyncLog.i(TAG, "Created test contact ID=$protonContactId, walking ladder…")

            // Walk the ladder top-down. Note: we re-encode from the source on
            // each step to avoid compounding compression artefacts.
            for ((index, step) in LADDER.withIndex()) {
                if (index > 0) delay(INTER_STEP_DELAY_MS)
                stepResults += runStep(accountName, protonContactId, testContactUid, sourceBitmap, step)
            }
        } finally {
            // Clean up the test contact regardless of how we got here.
            if (protonContactId != null) {
                try {
                    val del = apiClient.deleteContacts(
                        accountName,
                        DeleteContactsRequest(ids = listOf(protonContactId))
                    )
                    SyncLog.i(TAG, "Deleted test contact: HTTP ${del.code()}")
                } catch (e: Exception) {
                    SyncLog.w(TAG, "Failed to delete test contact $protonContactId: ${e.message}")
                }
            }
            sourceBitmap.recycle()
        }

        val result = Result(accountName, sourcePixels, sourceBytes, stepResults)
        SyncLog.i(TAG, "=== ${result.summaryLine()} ===")
        return result
    }

    private suspend fun runStep(
        accountName: String,
        contactId: String,
        uid: String,
        source: Bitmap,
        step: Step
    ): StepResult {
        val resized = source.scaled(step.dimensionPx)
        val jpeg = bitmapToJpeg(resized, step.jpegQuality)
        resized.recyclesIfNotSame(source)

        val vCard = buildVCard(NAME_PREFIX + step.dimensionPx, jpeg, uid)
        val cards = contactDecryptor.encryptContactCards(accountName, vCard)
        val wireBytes = cards.sumOf {
            it.data.length + (it.signature?.length ?: 0)
        }

        SyncLog.i(
            TAG,
            "Step ${step.dimensionPx}x${step.dimensionPx} q=${step.jpegQuality}: " +
                    "raw JPEG ${jpeg.size / 1024} KB, " +
                    "encrypted wire ${wireBytes / 1024} KB → uploading…"
        )

        val response = try {
            apiClient.updateContact(
                accountName,
                contactId,
                UpdateContactRequest(cards = cards)
            )
        } catch (e: Exception) {
            SyncLog.e(TAG, "Step ${step.dimensionPx}x${step.dimensionPx}: network error: ${e.message}")
            return StepResult(
                step = step,
                rawJpegBytes = jpeg.size,
                encryptedWireBytes = wireBytes,
                accepted = false,
                httpCode = -1,
                httpBody = "exception: ${e.message}",
                roundTripVerified = false
            )
        }

        val accepted = response.isSuccessful
        val errorBody = if (!accepted) {
            try { response.errorBody()?.string()?.take(500) } catch (_: Exception) { null }
        } else null

        SyncLog.i(
            TAG,
            "Step ${step.dimensionPx}x${step.dimensionPx} q=${step.jpegQuality}: " +
                    "HTTP ${response.code()} - ${if (accepted) "ACCEPTED" else "REJECTED"}" +
                    (errorBody?.let { " body=$it" } ?: "")
        )

        // Round-trip verification on accepted uploads.
        var roundTripOk = false
        if (accepted) {
            try {
                val readBack = apiClient.getContact(accountName, contactId)
                if (readBack.isSuccessful) {
                    val cardsBack = readBack.body()?.contact?.cards.orEmpty()
                    val readBackBytes = cardsBack.sumOf {
                        it.data.length + (it.signature?.length ?: 0)
                    }
                    // We don't expect byte-for-byte match (PGP is randomized) but
                    // the magnitudes should be similar; if drastically smaller,
                    // Proton silently dropped something.
                    val ratio = if (wireBytes == 0) 0.0 else readBackBytes.toDouble() / wireBytes
                    roundTripOk = ratio in 0.5..1.5
                    SyncLog.i(
                        TAG,
                        "Round-trip ${step.dimensionPx}x${step.dimensionPx}: " +
                                "sent ${wireBytes / 1024} KB, got ${readBackBytes / 1024} KB " +
                                "(ratio=${"%.2f".format(ratio)}, ok=$roundTripOk)"
                    )
                } else {
                    SyncLog.w(TAG, "Round-trip read failed: HTTP ${readBack.code()}")
                }
            } catch (e: Exception) {
                SyncLog.w(TAG, "Round-trip read errored: ${e.message}")
            }
        }

        return StepResult(
            step = step,
            rawJpegBytes = jpeg.size,
            encryptedWireBytes = wireBytes,
            accepted = accepted,
            httpCode = response.code(),
            httpBody = errorBody,
            roundTripVerified = roundTripOk
        )
    }

    /**
     * Build a minimal vCard 4.0 with the given JPEG photo embedded.
     *
     * Matches the production [me.proton.sync.contacts.ContactsSyncAdapter]
     * conventions:
     *   - vCard version 4.0 (Proton's API rejects 3.0 with "Invalid vCard version")
     *   - the UID a new contact gets ([newContactUid]), the same [uid] for every step
     */
    private fun buildVCard(displayName: String, jpegBytes: ByteArray, uid: String): String {
        val card = VCard()
        card.version = ezvcard.VCardVersion.V4_0
        card.uid = Uid(uid)

        card.formattedName = FormattedName(displayName)
        val structured = StructuredName().apply {
            family = "Calibration"
            given = "Photo"
        }
        card.structuredName = structured
        card.addPhoto(Photo(jpegBytes, ImageType.JPEG))
        return Ezvcard.write(card).go()
    }

    /**
     * Generate a deterministic-looking RGB gradient with a noise overlay so
     * JPEG compression behaves roughly like a real photo. The same seed is
     * used so re-runs produce identical bitmaps.
     */
    private fun generateSourceBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Diagonal gradient — purple to teal
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                0xFF6D4AFF.toInt(), 0xFF2EC4B6.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        // Sprinkle of pseudo-random noise so JPEG can't compress to a few bytes.
        val rng = java.util.Random(0xC0FFEEL)
        val noisePaint = Paint().apply { isAntiAlias = false }
        val pixelStep = (size / 64).coerceAtLeast(1)
        var y = 0
        while (y < size) {
            var x = 0
            while (x < size) {
                noisePaint.color = android.graphics.Color.rgb(
                    rng.nextInt(256), rng.nextInt(256), rng.nextInt(256)
                )
                canvas.drawRect(
                    x.toFloat(), y.toFloat(),
                    (x + pixelStep).toFloat(), (y + pixelStep).toFloat(),
                    noisePaint
                )
                x += pixelStep * 4
            }
            y += pixelStep * 4
        }
        return bmp
    }

    private fun Bitmap.scaled(targetMax: Int): Bitmap {
        if (width == targetMax && height == targetMax) return this
        return Bitmap.createScaledBitmap(this, targetMax, targetMax, true)
    }

    private fun bitmapToJpeg(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun Bitmap.recyclesIfNotSame(other: Bitmap) {
        if (this !== other && !isRecycled) recycle()
    }
}
