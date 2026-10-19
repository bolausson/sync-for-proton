package me.proton.sync.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.proton.sync.db.daos.CalendarEventSyncDao
import me.proton.sync.db.daos.CalendarSyncDao
import me.proton.sync.db.daos.ContactSyncDao
import me.proton.sync.db.entities.CalendarEventSyncEntity
import me.proton.sync.db.entities.CalendarSyncEntity
import me.proton.sync.db.entities.ContactSyncEntity

@Database(
    entities = [
        ContactSyncEntity::class,
        CalendarSyncEntity::class,
        CalendarEventSyncEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun contactSyncDao(): ContactSyncDao
    abstract fun calendarSyncDao(): CalendarSyncDao
    abstract fun calendarEventSyncDao(): CalendarEventSyncDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calendar_sync ADD COLUMN last_model_event_id TEXT DEFAULT ''")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calendar_event_sync ADD COLUMN sequence INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Add compound indices for queries that filter on (account_name, android_*_id). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_contact_sync_account_name_android_raw_contact_id ON contact_sync (account_name, android_raw_contact_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_calendar_sync_account_name_android_calendar_id ON calendar_sync (account_name, android_calendar_id)")
            }
        }

        /**
         * Cache server fields on calendar_event_sync so uploadModifiedEvent can skip the
         * per-event GET /calendar/events/{id} for events the user owns. All columns are
         * nullable; existing rows get null and fall back to the GET on first upload.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calendar_event_sync ADD COLUMN cached_shared_key_packet TEXT")
                db.execSQL("ALTER TABLE calendar_event_sync ADD COLUMN cached_dtstart_line TEXT")
                db.execSQL("ALTER TABLE calendar_event_sync ADD COLUMN cached_dtend_line TEXT")
                db.execSQL("ALTER TABLE calendar_event_sync ADD COLUMN cached_rrule TEXT")
                db.execSQL("ALTER TABLE calendar_event_sync ADD COLUMN cached_uid TEXT")
                db.execSQL("ALTER TABLE calendar_event_sync ADD COLUMN cached_is_organizer INTEGER")
            }
        }

        /**
         * Wipe cached_shared_key_packet so the next upload of any event re-fetches the
         * server's actual PKESK. v1.3.83…v1.3.109 cached the wrong PKESK on create when
         * an event had Proton attendees: we computed a calendar-key-only PKESK first,
         * then re-wrapped to a fresh session key for the attendees, but cached the
         * pre-rewrap one. The update path then encrypted SEIPDs with a session key the
         * server never stored, breaking decryption in the Proton WebUI.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE calendar_event_sync SET cached_shared_key_packet = NULL")
            }
        }

        /**
         * Persist the override marker cached_recurrence_id on calendar_event_sync. Nullable
         * TEXT: null for recurring masters and standalone events, the RECURRENCE-ID value for
         * single-occurrence overrides. The master-selection DB fallback in syncCalendarEvents
         * requires cached_recurrence_id IS NULL so a RANGE=THISANDFUTURE override (which carries
         * both an RRULE and a RECURRENCE-ID) is no longer mis-selected as the master. Existing
         * rows get null and are re-marked on their next upsert.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calendar_event_sync ADD COLUMN cached_recurrence_id TEXT")
            }
        }

        /**
         * Baseline fingerprint of the event's SHARED fields as of the last successful sync.
         *
         * Needed to tell "the user edited the title" from "the user added a reminder", because
         * Android sets `Events.DIRTY = 1` on the parent row for both. Comparing the current local
         * row against this baseline answers "did the LOCAL side change a shared field", which
         * comparing local against remote cannot: when the remote changed, the two differ either
         * way. Without it, a reminder added locally looks identical to a retitled event and every
         * personal-part change would raise a spurious conflict prompt.
         *
         * Null on existing rows, which the conflict check treats as "no baseline, assume conflict"
         * — the safe direction. Rows get a baseline the first time they are written after upgrade.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calendar_event_sync ADD COLUMN shared_fingerprint TEXT")
            }
        }

        /**
         * Remember what a contact's name looked like on Proton.
         *
         * Android's contacts provider does not store the name it is given, it completes it: hand it
         * a display name and it splits one out of it, so "app@example.com" comes back as
         * given=app@example, family=com. Reading that back on upload wrote a structured name to
         * Proton that nobody had typed, and contacts on real accounts had already acquired one.
         *
         * Both columns are null on existing rows, which every reader treats as "not known, change
         * nothing". They are filled in by a read-only backfill that decrypts but does not write.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contact_sync ADD COLUMN server_fn TEXT")
                db.execSQL("ALTER TABLE contact_sync ADD COLUMN server_has_n INTEGER")
            }
        }

        /**
         * Records the given and family names Proton holds, not merely whether it holds any.
         *
         * server_has_n only guards contacts Proton has NO structured name for. The ones it does
         * have a name for were losing the rest of their display name: Android derives the display
         * name from given and family alone, so "Dr. Max Erik Mustermann" shows as "Max Mustermann"
         * with the title and middle name stored nowhere on the device, and the upload then sends
         * that back as the FN. On real address books such contacts had already diverged
         * this way and would have been truncated on Proton at their next local edit.
         *
         * Comparing against the stored values tells an Android-derived shortening from a name the
         * user actually changed. Null on existing rows until the backfill fills them in.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contact_sync ADD COLUMN server_given TEXT")
                db.execSQL("ALTER TABLE contact_sync ADD COLUMN server_family TEXT")
            }
        }

        /**
         * Record an event's time columns as the last sync left them (calendar_event_sync.cached_times), so the
         * upload can tell a time the user left alone from one they changed, field by field. An untouched time
         * goes back to Proton as Proton's own text: reformatting the stored instant moved events whenever it had
         * been computed with other zone rules than the ones at hand (the online rules failing to load, the
         * switch turned off, a tz data update). Null on existing rows until their next sync write, which every
         * reader treats as "not known".
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE calendar_event_sync ADD COLUMN cached_times TEXT")
            }
        }
    }
}
