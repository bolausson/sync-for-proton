package me.proton.sync.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "calendar_sync",
    primaryKeys = ["account_name", "proton_calendar_id"],
    indices = [
        Index(value = ["account_name"]),
        Index(value = ["account_name", "android_calendar_id"])
    ]
)
data class CalendarSyncEntity(
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "proton_calendar_id") val protonCalendarId: String,
    @ColumnInfo(name = "android_calendar_id") val androidCalendarId: Long? = null,
    @ColumnInfo(name = "display_name") val displayName: String = "",
    @ColumnInfo(name = "color") val color: Int = 0,
    @ColumnInfo(name = "last_model_event_id", defaultValue = "") val lastModelEventId: String? = null
)
