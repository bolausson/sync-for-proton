package me.proton.sync.db.daos

import androidx.room.*
import me.proton.sync.db.entities.CalendarEventSyncEntity
import me.proton.sync.db.entities.CalendarSyncEntity
import me.proton.sync.db.entities.ContactSyncEntity

@Dao
interface ContactSyncDao {
    @Query("SELECT * FROM contact_sync WHERE account_name = :accountName")
    suspend fun getAllByAccount(accountName: String): List<ContactSyncEntity>

    @Query("SELECT * FROM contact_sync WHERE account_name = :accountName AND proton_contact_id = :protonId")
    suspend fun getByProtonId(accountName: String, protonId: String): ContactSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ContactSyncEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ContactSyncEntity>)

    @Query("SELECT * FROM contact_sync WHERE account_name = :accountName AND android_raw_contact_id = :androidId")
    suspend fun getByAndroidId(accountName: String, androidId: Long): ContactSyncEntity?

    @Query("DELETE FROM contact_sync WHERE account_name = :accountName AND proton_contact_id = :protonId")
    suspend fun deleteByProtonId(accountName: String, protonId: String)

    @Query("DELETE FROM contact_sync WHERE account_name = :accountName")
    suspend fun deleteAllByAccount(accountName: String)

    @Query("DELETE FROM contact_sync WHERE account_name NOT IN (:activeAccounts)")
    suspend fun deleteByAccountNotIn(activeAccounts: Set<String>)

    @Query("UPDATE contact_sync SET account_name = :newName WHERE account_name = :oldName")
    suspend fun renameAccount(oldName: String, newName: String): Int
}

@Dao
interface CalendarSyncDao {
    @Query("SELECT * FROM calendar_sync WHERE account_name = :accountName")
    suspend fun getAllByAccount(accountName: String): List<CalendarSyncEntity>

    @Query("SELECT * FROM calendar_sync WHERE account_name = :accountName AND proton_calendar_id = :protonId")
    suspend fun getByProtonId(accountName: String, protonId: String): CalendarSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CalendarSyncEntity)

    @Query("DELETE FROM calendar_sync WHERE account_name = :accountName AND proton_calendar_id = :protonId")
    suspend fun deleteByProtonId(accountName: String, protonId: String)

    @Query("DELETE FROM calendar_sync WHERE account_name = :accountName")
    suspend fun deleteAllByAccount(accountName: String)

    @Query("DELETE FROM calendar_sync WHERE account_name NOT IN (:activeAccounts)")
    suspend fun deleteByAccountNotIn(activeAccounts: Set<String>)

    @Query("UPDATE calendar_sync SET account_name = :newName WHERE account_name = :oldName")
    suspend fun renameAccount(oldName: String, newName: String): Int
}

@Dao
interface CalendarEventSyncDao {
    @Query("SELECT * FROM calendar_event_sync WHERE account_name = :accountName AND proton_calendar_id = :calendarId")
    suspend fun getAllByCalendar(accountName: String, calendarId: String): List<CalendarEventSyncEntity>

    @Query("SELECT * FROM calendar_event_sync WHERE account_name = :accountName AND proton_event_id = :protonId")
    suspend fun getByProtonId(accountName: String, protonId: String): CalendarEventSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CalendarEventSyncEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CalendarEventSyncEntity>)

    @Query("SELECT * FROM calendar_event_sync WHERE account_name = :accountName AND android_event_id = :androidId")
    suspend fun getByAndroidId(accountName: String, androidId: Long): CalendarEventSyncEntity?

    @Query("DELETE FROM calendar_event_sync WHERE account_name = :accountName AND proton_event_id = :protonId")
    suspend fun deleteByProtonId(accountName: String, protonId: String)

    @Query("DELETE FROM calendar_event_sync WHERE account_name = :accountName AND proton_calendar_id = :calendarId")
    suspend fun deleteByCalendar(accountName: String, calendarId: String)

    /**
     * Re-key every event mapping of [oldCalId] onto [newCalId] (same account). Used when a Proton
     * calendar is deleted and re-created under the same name: the new calendar has a new protonId
     * over the reused Android calendar, so its events must follow to the new id — then the per-event
     * RemoteDeletionGuard sees them as "vanished from the (now empty) server calendar" and prompts,
     * instead of the whole Android calendar being silently wiped. Safe because proton_calendar_id is
     * not part of the primary key (account_name, proton_event_id). Returns the number of rows moved.
     */
    @Query("UPDATE calendar_event_sync SET proton_calendar_id = :newCalId WHERE account_name = :accountName AND proton_calendar_id = :oldCalId")
    suspend fun reassignCalendar(accountName: String, oldCalId: String, newCalId: String): Int

    @Query("UPDATE calendar_event_sync SET last_modified = 0 WHERE account_name = :accountName")
    suspend fun resetAllLastModified(accountName: String)

    /** Every mapping of the account that names a local row: what the re-time pass checks. */
    @Query("SELECT * FROM calendar_event_sync WHERE account_name = :accountName AND android_event_id IS NOT NULL")
    suspend fun getAllMappedByAccount(accountName: String): List<CalendarEventSyncEntity>

    /**
     * Set sync_tag on [protonIds] (at most a few hundred per call: SQLite's variable limit). The calendar side
     * uses it only for the re-time marker (EventRetimePlanner.RETIME_TAG); a download write clears it.
     */
    @Query("UPDATE calendar_event_sync SET sync_tag = :tag WHERE account_name = :accountName AND proton_event_id IN (:protonIds)")
    suspend fun setSyncTag(accountName: String, protonIds: List<String>, tag: String?): Int

    /** Re-baseline one mapping's shared fingerprint and time record, nothing else (the debug retime-fixture verb). */
    @Query("UPDATE calendar_event_sync SET shared_fingerprint = :fingerprint, cached_times = :cachedTimes WHERE account_name = :accountName AND proton_event_id = :protonId")
    suspend fun setBaseline(accountName: String, protonId: String, fingerprint: String?, cachedTimes: String?): Int

    @Query("SELECT COUNT(*) FROM calendar_event_sync WHERE account_name = :accountName AND sync_tag = :tag")
    suspend fun countWithSyncTag(accountName: String, tag: String): Int

    /** The local rows this app downloaded from Proton, so a removal can spare everything else. */
    @Query("SELECT android_event_id FROM calendar_event_sync WHERE account_name = :accountName AND android_event_id IS NOT NULL")
    suspend fun androidEventIdsByAccount(accountName: String): List<Long>

    /**
     * Where [protonIds] claim to live on this phone. `android_event_id IS NOT NULL` on purpose: a
     * mapping without one is not something a deletion could act on, which is the same definition both
     * hold sites and the approve pass use. The claim still has to be checked against the provider,
     * because a mapping outlives a row that was removed behind this app's back.
     */
    @Query(
        "SELECT proton_event_id, android_event_id FROM calendar_event_sync WHERE account_name = :accountName " +
            "AND proton_event_id IN (:protonIds) AND android_event_id IS NOT NULL"
    )
    suspend fun mappedRows(accountName: String, protonIds: List<String>): List<MappedRow>

    @Query("DELETE FROM calendar_event_sync WHERE account_name = :accountName")
    suspend fun deleteAllByAccount(accountName: String)

    @Query("DELETE FROM calendar_event_sync WHERE account_name NOT IN (:activeAccounts)")
    suspend fun deleteByAccountNotIn(activeAccounts: Set<String>)

    @Query("UPDATE calendar_event_sync SET account_name = :newName WHERE account_name = :oldName")
    suspend fun renameAccount(oldName: String, newName: String): Int
}

/** One event mapping's two ids, for checking a Proton id's claim against the provider. */
data class MappedRow(
    @ColumnInfo(name = "proton_event_id") val protonEventId: String,
    @ColumnInfo(name = "android_event_id") val androidEventId: Long
)
