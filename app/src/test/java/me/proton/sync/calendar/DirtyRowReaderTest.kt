package me.proton.sync.calendar

import android.database.Cursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The dirty-row read ([DirtyRowReader]) by column name and type. Until 2.2.0 it read _ID, CALENDAR_ID and DELETED by
 * position, which held only while every projection started with those three; the hold of new events in shared
 * calendars reads four more columns, and the retry without MUTATORS has one column fewer. A cursor is stood in for by
 * the column names and one value per column, typed as SQLite would report it.
 */
class DirtyRowReaderTest {

    /** A one-row cursor: [values] by column name, in that order. getString on anything but text throws, as a BLOB does. */
    private fun read(values: List<Pair<String, Any?>>): DirtyRow {
        val names = values.map { it.first }.toTypedArray()
        val cells = values.map { it.second }
        fun type(i: Int) = when (cells[i]) {
            null -> Cursor.FIELD_TYPE_NULL
            is String -> Cursor.FIELD_TYPE_STRING
            is Long, is Int -> Cursor.FIELD_TYPE_INTEGER
            is Double -> Cursor.FIELD_TYPE_FLOAT
            else -> Cursor.FIELD_TYPE_BLOB
        }
        fun text(i: Int): String? = when (val v = cells[i]) {
            null -> null
            is String -> v
            is ByteArray -> throw IllegalStateException("Unable to convert BLOB to string")
            else -> v.toString()
        }
        fun number(i: Int): Long = when (val v = cells[i]) {
            null -> 0L
            is Number -> v.toLong()
            else -> (v as? String)?.toLongOrNull() ?: 0L
        }
        return DirtyRowReader.read(DirtyRowReader.columns(names), ::type, ::text, ::number)
    }

    private val full = listOf(
        "_id" to 41L,
        "calendar_id" to 7L,
        "deleted" to 0L,
        "_sync_id" to null,
        "original_id" to null,
        "original_sync_id" to null,
        "uid2445" to "booking-41@example.test",
        "mutators" to "com.example.booking",
    )

    @Test
    fun `columns are found by name in any order`() {
        val expected = DirtyRow(41, 7, 0, "com.example.booking", true, null, null, null, "booking-41@example.test")
        assertEquals(expected, read(full))
        assertEquals(expected, read(full.reversed()))
        assertEquals(expected, read(full.shuffled(java.util.Random(4))))
    }

    @Test
    fun `without MUTATORS the row says so and every other value is right`() {
        val row = read(full.filter { it.first != "mutators" })
        assertFalse(row.mutatorsReadable)
        assertNull(row.mutators)
        assertEquals(41L, row.androidEventId)
        assertEquals(7L, row.androidCalId)
        assertEquals(0, row.deleted)
        assertEquals("booking-41@example.test", row.uid)
        // The retry's projection: the query's own columns without MUTATORS, in the query's order.
        val retry = listOf("_id" to 42L, "calendar_id" to 8L, "deleted" to 1L, "_sync_id" to "proton-42", "original_id" to 40L,
            "original_sync_id" to "proton-40", "uid2445" to "u42@example.test")
        assertEquals(DirtyRow(42, 8, 1, null, false, "proton-42", 40L, "proton-40", "u42@example.test"), read(retry))
    }

    @Test
    fun `a NULL ORIGINAL_ID is null, never 0`() {
        assertNull(read(full).originalId)
        assertEquals(40L, read(full.map { if (it.first == "original_id") it.first to 40L else it }).originalId)
    }

    @Test
    fun `a UID that looks like a number shifts nothing`() {
        val row = read(full.map { if (it.first == "uid2445") it.first to "12345" else it })
        assertEquals("12345", row.uid)
        assertEquals(41L, row.androidEventId)
        assertEquals("com.example.booking", row.mutators)
    }

    @Test
    fun `values of the wrong type are read as absent`() {
        val odd = full.map {
            when (it.first) {
                "mutators" -> it.first to byteArrayOf(1, 2)
                "uid2445" -> it.first to 99L
                "_sync_id" -> it.first to 3.5
                "original_id" -> it.first to "40"
                else -> it
            }
        }
        val row = read(odd)
        assertNull(row.mutators)
        assertTrue(row.mutatorsReadable)
        assertNull(row.uid)
        assertNull(row.syncId)
        assertNull(row.originalId)
        assertEquals(41L, row.androidEventId)
    }

    @Test
    fun `a projection without a required column is refused`() {
        try {
            DirtyRowReader.columns(arrayOf("calendar_id", "deleted", "mutators"))
            fail("no _id")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
