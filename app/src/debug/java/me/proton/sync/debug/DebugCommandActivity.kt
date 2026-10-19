package me.proton.sync.debug

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import me.proton.sync.DebugCommandGate
import me.proton.sync.ProtonSyncApp
import me.proton.sync.SyncLog
import me.proton.sync.settings.SyncSettingsActivity
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Debug builds only: the adb entry point for debug commands (`am start` with a debug_command extra).
 *
 * Declared in the debug manifest with android.permission.DUMP, so only the shell (adb) can start it.
 * User actions are handed to SyncSettingsActivity with a one-time token, which refuses any command
 * without one. Test fixtures the shell cannot set up itself run here directly. No UI of its own.
 */
class DebugCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            if (intent.getStringExtra("debug_command") == "photo_probe") {
                writeNoisePhoto(intent.getLongExtra("raw_id", -1L), intent.getIntExtra("px", 1600))
            } else {
                startActivity(
                    Intent(this, SyncSettingsActivity::class.java).apply {
                        intent.extras?.let { putExtras(it) }
                        putExtra("debug_token", DebugCommandGate.issue())
                        // Into the app's own task (this activity has none), reusing a running settings screen.
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
        }
        finish()
    }

    /**
     * Fixture: a [px] x [px] noise image as the display photo of one of this app's raw contacts. Noise
     * does not compress, so it gives the largest photo the upload will send, the case that tests how
     * big photos travel. The shell's `content write` cannot do this (the provider serves display photos
     * as asset files only). Written without the sync-adapter flag, so the contact is marked for upload
     * as a user's photo change would be.
     */
    private fun writeNoisePhoto(rawId: Long, px: Int) {
        val tag = "DebugCommand"
        val app = applicationContext
        Thread {
            try {
                val type = app.contentResolver.query(
                    ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
                    arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE), null, null, null
                )?.use { if (it.moveToFirst()) it.getString(0) else null }
                if (type != ProtonSyncApp.ACCOUNT_TYPE_ADDRESS_BOOK) {
                    SyncLog.w(tag, "photo_probe ignored: raw contact $rawId is not one of this app's contacts")
                    return@Thread
                }
                val random = Random(rawId)
                val pixels = IntArray(px * px) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
                val bitmap = Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                bitmap.recycle()
                val uri = Uri.withAppendedPath(
                    ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
                    ContactsContract.RawContacts.DisplayPhoto.CONTENT_DIRECTORY
                )
                app.contentResolver.openAssetFileDescriptor(uri, "rw")?.use { afd ->
                    afd.createOutputStream().use { it.write(bytes) }
                }
                SyncLog.i(tag, "photo_probe: wrote a ${px}x$px noise photo (${bytes.size} bytes) to raw contact $rawId")
            } catch (e: Exception) {
                SyncLog.w(tag, "photo_probe failed for raw contact $rawId: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }
}
