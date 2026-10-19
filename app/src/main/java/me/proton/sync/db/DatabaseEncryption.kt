package me.proton.sync.db

import android.content.Context
import android.util.Base64
import me.proton.sync.SyncLog
import me.proton.sync.crypto.SecureStorage
import java.io.File
import java.security.SecureRandom

/**
 * Encrypts the sync database at rest, and moves an existing plaintext one across.
 *
 * The database is a sync ledger rather than a copy of the user's content, but "ledger" undersells
 * what it holds: every contact's display name in the clear, every calendar's name, and the exact
 * start time of every event, one row per event. No event titles, descriptions, locations or
 * attendees are stored, yet a thousand timestamps and their UIDs still describe a person's life in
 * some detail, and a UID alone can give the game away: an airline booking's UID can read
 * `1-1234567890@reservation.example.com`.
 *
 * Android's own protections were already doing most of the work. App-private files are unreadable
 * by other apps, `android:allowBackup="false"` blocks extraction over adb, and file-based
 * encryption covers the disk while the device is locked. What none of that covers is a rooted or
 * already-unlocked device, which is exactly the situation a privacy-focused user is most likely to
 * be worried about, so the file is now encrypted in its own right.
 *
 * The passphrase is 32 random bytes held through [SecureStorage], so at rest it is sealed by an
 * AES-256-GCM key inside the Android Keystore, hardware-backed where the device offers it. That
 * key is deliberately not bound to user authentication, so changing or removing the lockscreen
 * does not invalidate it and lock the user out of their own sync state.
 */
object DatabaseEncryption {

    private const val TAG = "DatabaseEncryption"
    const val DB_NAME = "proton_sync.db"
    private const val PREFS = "db_encryption"
    private const val KEY_PASSPHRASE = "db_passphrase"
    private const val TMP_NAME = "proton_sync_enc.tmp"

    /** Every SQLite file begins with this; an encrypted one does not, which is how we tell. */
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * Makes sure the database on disk is encrypted, converting a plaintext one if it finds it.
     *
     * @return the passphrase to open it with, or null if it must be opened unencrypted. Null is
     *   returned only when this could not deliver encryption safely AND the file on disk is still
     *   plaintext: the native library is missing, or a conversion failed. Refusing to open a
     *   perfectly good plaintext database would leave the user with an app that cannot sync at all,
     *   and would risk far more than it protects, because the mapping rows in here are what stop a
     *   resync deleting and recreating the entire address book.
     *
     *   When the file is ALREADY encrypted, null is never returned. Handing an encrypted file to
     *   plain SQLite does not degrade gracefully, it presents as corruption, and Room may then
     *   delete the very ledger this is trying to protect. Failing loudly is the lesser harm,
     *   because the underlying causes, a missing ABI or a Keystore that stopped answering, are
     *   recoverable once seen, and a deleted ledger is not.
     */
    fun prepare(context: Context): ByteArray? {
        val dbFile = context.getDatabasePath(DB_NAME)
        // Whether the file on disk is ALREADY encrypted has to be settled before anything else,
        // because it changes what a failure below is allowed to do. Returning null tells AppModule
        // to open with plain framework SQLite, which is right for a plaintext file and catastrophic
        // for an encrypted one: Room cannot read it, and its corruption handling can take the whole
        // sync ledger with it. Every early return under this line was reached before the file was
        // ever looked at, so an unreadable passphrase or a missing native library did exactly that.
        val alreadyEncrypted = dbFile.exists() && !isPlaintext(dbFile)

        if (!loadNativeLibrary()) {
            check(!alreadyEncrypted) {
                "The sync database is encrypted but SQLCipher is unavailable on this device, so it " +
                    "cannot be opened. Refusing to open it as plaintext, which would risk destroying it."
            }
            return null
        }
        val passphrase = try {
            getOrCreatePassphrase(context)
        } catch (e: Exception) {
            check(!alreadyEncrypted) {
                "The sync database is encrypted but its passphrase could not be read (${e.javaClass.simpleName}). " +
                    "Refusing to open it as plaintext, which would risk destroying it."
            }
            SyncLog.e(TAG, "Could not obtain a database passphrase; continuing unencrypted", e)
            return null
        }

        if (!dbFile.exists()) {
            SyncLog.i(TAG, "New database; it will be created encrypted")
            return passphrase
        }
        if (alreadyEncrypted) return passphrase

        SyncLog.i(TAG, "Found a plaintext sync database; converting it to an encrypted one")
        return if (convertInPlace(context, dbFile, passphrase)) {
            SyncLog.i(TAG, "Sync database is now encrypted at rest")
            passphrase
        } else {
            // Left exactly as it was. The next launch tries again, and in the meantime the app
            // keeps working on the plaintext file rather than presenting an empty database, which
            // the orphan sweep would read as "every local contact has lost its mapping".
            SyncLog.e(TAG, "Conversion failed; leaving the database unencrypted and retrying next launch")
            null
        }
    }

    /** True when the file still carries SQLite's plaintext header. */
    private fun isPlaintext(dbFile: File): Boolean = try {
        val head = ByteArray(SQLITE_MAGIC.size)
        dbFile.inputStream().use { it.read(head) }
        head.contentEquals(SQLITE_MAGIC)
    } catch (e: Exception) {
        SyncLog.w(TAG, "Could not read the database header: ${e.message}")
        false
    }

    /**
     * Copies every table into a new encrypted file, then puts it in place of the old one.
     *
     * Copying the schema and rows does not carry the schema version (`PRAGMA user_version`), and
     * Room reads that first: left at zero it would take a fully migrated database for a brand new
     * one, run every migration from scratch and fail on the first `ALTER TABLE` for a column that
     * is already there. So it is carried over explicitly.
     */
    private fun convertInPlace(context: Context, dbFile: File, passphrase: ByteArray): Boolean {
        val tmp = File(dbFile.parentFile, TMP_NAME)
        listOf(tmp, File(tmp.absolutePath + "-wal"), File(tmp.absolutePath + "-shm"))
            .forEach { if (it.exists()) it.delete() }

        var plain: net.zetetic.database.sqlcipher.SQLiteDatabase? = null
        var enc: net.zetetic.database.sqlcipher.SQLiteDatabase? = null
        try {
            // The overload that takes no key issues no PRAGMA key, which is what lets SQLCipher
            // read a file that is still plain SQLite.
            plain = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null as net.zetetic.database.sqlcipher.SQLiteDatabase.CursorFactory?,
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE,
            )
            val version = plain.version
            val expected = tableCounts(plain)

            enc = net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                tmp.absolutePath, passphrase.copyOf(), null, null,
            )

            // Deliberately NOT sqlcipher_export via ATTACH. SQLCipher hands each rawExecSQL a
            // connection from a pool, and an attached schema belongs to the one connection that
            // attached it, so the ATTACH was silently lost and the export wrote nothing at all:
            // no file, no error. Copying explicitly is a few more lines and every step of it can
            // be checked.
            val schema = mutableListOf<Pair<String, String>>()
            plain.rawQuery(
                "SELECT type, sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'",
                null,
            ).use { c -> while (c.moveToNext()) schema.add(c.getString(0) to c.getString(1)) }
            // Tables before the indices and triggers that reference them.
            for ((type, sql) in schema.sortedBy { if (it.first == "table") 0 else 1 }) {
                enc.execSQL(sql)
            }

            enc.beginTransaction()
            try {
                for (table in expected.keys) copyTable(plain, enc, table)
                enc.setTransactionSuccessful()
            } finally {
                enc.endTransaction()
            }
            enc.version = version
            val actual = tableCounts(enc)
            enc.close()
            enc = null
            plain.close()
            plain = null

            val mismatch = expected.filter { (name, n) -> actual[name] != n }
            if (mismatch.isNotEmpty()) {
                SyncLog.e(TAG, "Encrypted copy is incomplete ($mismatch); keeping the original")
                tmp.delete()
                return false
            }
            if (!stampAndVerify(tmp, passphrase, version, expected)) {
                tmp.delete()
                return false
            }

            // The write-ahead log belongs to the file being replaced. Leaving it would let SQLite
            // replay plaintext pages over the new file, and it is also a copy of the very data
            // this is meant to stop leaving on disk.
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()
            if (!dbFile.delete()) {
                SyncLog.e(TAG, "Could not remove the plaintext database; keeping it")
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(dbFile)) {
                SyncLog.e(TAG, "Could not move the encrypted database into place")
                return false
            }
            SyncLog.i(TAG, "Converted $actual to an encrypted database at schema $version")
            return true
        } catch (e: Exception) {
            SyncLog.e(TAG, "Converting the database to an encrypted one failed", e)
            try { enc?.close() } catch (_: Exception) {}
            try { plain?.close() } catch (_: Exception) {}
            tmp.delete()
            return false
        }
    }

    /** Copies one table row by row, carrying each value at whatever type SQLite reports. */
    private fun copyTable(
        from: net.zetetic.database.sqlcipher.SQLiteDatabase,
        to: net.zetetic.database.sqlcipher.SQLiteDatabase,
        table: String,
    ) {
        from.rawQuery("SELECT * FROM `$table`", null).use { c ->
            while (c.moveToNext()) {
                val values = android.content.ContentValues()
                for (i in 0 until c.columnCount) {
                    val name = c.getColumnName(i)
                    when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> values.putNull(name)
                        android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(name, c.getLong(i))
                        android.database.Cursor.FIELD_TYPE_FLOAT -> values.put(name, c.getDouble(i))
                        android.database.Cursor.FIELD_TYPE_BLOB -> values.put(name, c.getBlob(i))
                        else -> values.put(name, c.getString(i))
                    }
                }
                to.insertOrThrow(table, null, values)
            }
        }
    }

    /** Row counts per table, so the copy can be proved complete before the original is removed. */
    private fun tableCounts(db: net.zetetic.database.sqlcipher.SQLiteDatabase): Map<String, Long> {
        val counts = LinkedHashMap<String, Long>()
        val names = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null
        ).use { c -> while (c.moveToNext()) names.add(c.getString(0)) }
        for (name in names) {
            db.rawQuery("SELECT COUNT(*) FROM `$name`", null).use { c ->
                if (c.moveToFirst()) counts[name] = c.getLong(0)
            }
        }
        return counts
    }

    /**
     * Writes the schema version onto the new file and checks it holds everything the old one did.
     *
     * The copy carries tables and rows but not `PRAGMA user_version`, and Room reads that
     * before anything else: left at zero it would take a fully migrated database for a new one,
     * run every migration from the beginning and fail on the first `ALTER TABLE` for a column that
     * already exists.
     */
    private fun stampAndVerify(
        tmp: File,
        passphrase: ByteArray,
        version: Int,
        expected: Map<String, Long>,
    ): Boolean {
        var enc: net.zetetic.database.sqlcipher.SQLiteDatabase? = null
        return try {
            enc = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                tmp.absolutePath, passphrase.copyOf(), null,
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null,
            )
            enc.version = version
            val actual = tableCounts(enc)
            val mismatch = expected.filter { (name, n) -> actual[name] != n }
            if (mismatch.isNotEmpty()) {
                SyncLog.e(TAG, "Encrypted copy is incomplete ($mismatch); keeping the original")
                return false
            }
            SyncLog.i(TAG, "Encrypted copy verified at schema $version: $actual")
            true
        } catch (e: Exception) {
            SyncLog.e(TAG, "Could not read back the encrypted copy; keeping the original", e)
            false
        } finally {
            try { enc?.close() } catch (_: Exception) {}
        }
    }

    /**
     * The stored passphrase, created on first use.
     *
     * Base64 of 32 random bytes, so it is 256 bits of entropy in an alphabet that never needs
     * escaping. [SecureStorage] seals it with the Keystore; if the Keystore is unavailable it
     * falls back to Base64 obfuscation and says so, which is weaker but still better than the
     * plaintext database this replaces.
     */
    private fun getOrCreatePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PASSPHRASE, null)?.let { stored ->
            // decryptStrict, never decrypt. The lenient reader answers a Keystore failure by
            // handing back the raw ciphertext mangled through UTF-8, with no exception and no log.
            // For most values that is a bad guess; for this one it is fatal, because convertInPlace
            // would encrypt with the guess, verify with the same guess so every check passes, and
            // then delete the only readable copy. A passphrase that cannot be read back is a
            // reason to leave the database alone.
            val recovered = SecureStorage.decryptStrict(stored)
            if (recovered.isEmpty()) {
                throw IllegalStateException("Stored database passphrase could not be decrypted")
            }
            return recovered.toByteArray(Charsets.US_ASCII)
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val generated = Base64.encodeToString(raw, Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString(KEY_PASSPHRASE, SecureStorage.encrypt(generated)).commit()
        SyncLog.i(TAG, "Generated a database passphrase")
        return generated.toByteArray(Charsets.US_ASCII)
    }

    private fun loadNativeLibrary(): Boolean = try {
        System.loadLibrary("sqlcipher")
        true
    } catch (e: Throwable) {
        // An ABI this build does not carry. Syncing unencrypted is a real loss of protection, but
        // it is a loss the user can still recover from; refusing to open the database is not.
        SyncLog.e(TAG, "SQLCipher native library unavailable; the database stays unencrypted", e)
        false
    }
}
