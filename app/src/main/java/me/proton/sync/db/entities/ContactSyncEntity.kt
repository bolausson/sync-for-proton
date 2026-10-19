package me.proton.sync.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "contact_sync",
    primaryKeys = ["account_name", "proton_contact_id"],
    indices = [
        Index(value = ["account_name"]),
        Index(value = ["account_name", "android_raw_contact_id"])
    ]
)
data class ContactSyncEntity(
    @ColumnInfo(name = "account_name") val accountName: String,
    @ColumnInfo(name = "proton_contact_id") val protonContactId: String,
    @ColumnInfo(name = "android_raw_contact_id") val androidRawContactId: Long? = null,
    @ColumnInfo(name = "last_modified") val lastModified: Long = 0,
    @ColumnInfo(name = "sync_tag") val syncTag: String? = null,

    /**
     * The display name (vCard FN) this contact had on Proton at the last download.
     *
     * Kept so the upload can tell a name the user typed from one Android invented. The provider
     * completes every structured name it stores, so a local given/family proves nothing on its own.
     */
    @ColumnInfo(name = "server_fn") val serverFn: String? = null,

    /**
     * Whether Proton's card carried a structured name (vCard N): 1 yes, 0 no, null not yet known.
     *
     * Null on every row that predates this column. Both readers treat null as "leave it alone",
     * which is the safe direction, and a backfill fills it in without touching the phone.
     */
    @ColumnInfo(name = "server_has_n") val serverHasN: Int? = null,

    /**
     * The given and family names Proton's card carried at the last download.
     *
     * [serverHasN] answers "was there a structured name at all", which is enough to stop Android
     * inventing one but not enough to protect a contact that legitimately has one. Android's
     * display name is DERIVED from given and family alone, so a Proton FN carrying anything else,
     * a title, a middle name, a parenthetical, comes back to the phone shortened: "Dr. Max
     * Erik Mustermann" is stored as given=Max, family=Mustermann and displays as "Max Mustermann", with
     * the title and middle name held nowhere on the device. The upload then sends that display
     * name as the FN and Proton's full name is gone.
     *
     * Holding the values, not just a flag, is what separates the two cases: if the local given and
     * family still match these, the user never touched the name and the shortening is Android's,
     * so Proton's FN is kept. If they differ, it is a real edit and it travels.
     *
     * Null on rows predating the column and on contacts with no structured name; both readers
     * treat null as "not known, change nothing".
     */
    @ColumnInfo(name = "server_given") val serverGiven: String? = null,
    @ColumnInfo(name = "server_family") val serverFamily: String? = null
)
