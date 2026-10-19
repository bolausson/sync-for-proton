package me.proton.sync.backup

import android.content.SharedPreferences
import me.proton.sync.contacts.FakePrefs

/**
 * [FakePrefs] read the way Android's SharedPreferencesImpl reads: every getter casts the stored value, so a
 * value of another type throws ClassCastException instead of quietly reading as the default. Counts the
 * editors it hands out, so a test can tell that nothing was written at all.
 */
internal class StrictPrefs(val fake: FakePrefs = FakePrefs()) : SharedPreferences by fake {
    var editsOpened = 0
        private set

    override fun edit(): SharedPreferences.Editor = fake.edit().also { editsOpened++ }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = fake.values[key]?.let { it as Boolean } ?: defValue
    override fun getString(key: String, defValue: String?): String? = fake.values[key]?.let { it as String } ?: defValue
    override fun getInt(key: String, defValue: Int): Int = fake.values[key]?.let { it as Int } ?: defValue
    override fun getLong(key: String, defValue: Long): Long = fake.values[key]?.let { it as Long } ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = fake.values[key]?.let { it as Float } ?: defValue
}
