package me.proton.sync.di

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.proton.sync.db.SyncDatabase
import me.proton.sync.db.daos.CalendarEventSyncDao
import me.proton.sync.db.daos.CalendarSyncDao
import me.proton.sync.db.daos.ContactSyncDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSyncDatabase(@ApplicationContext context: Context): SyncDatabase {
        // Converts an existing plaintext database on the way past, and hands back the passphrase.
        // Null means encryption could not be delivered this launch; see DatabaseEncryption for why
        // that is answered by carrying on rather than by refusing to open anything.
        val passphrase = me.proton.sync.db.DatabaseEncryption.prepare(context)
        val builder = Room.databaseBuilder(
            context,
            SyncDatabase::class.java,
            me.proton.sync.db.DatabaseEncryption.DB_NAME
        ).addMigrations(
            SyncDatabase.MIGRATION_1_2,
            SyncDatabase.MIGRATION_2_3,
            SyncDatabase.MIGRATION_3_4,
            SyncDatabase.MIGRATION_4_5,
            SyncDatabase.MIGRATION_5_6,
            SyncDatabase.MIGRATION_6_7,
            SyncDatabase.MIGRATION_7_8,
            SyncDatabase.MIGRATION_8_9,
            SyncDatabase.MIGRATION_9_10,
            SyncDatabase.MIGRATION_10_11
        )
        if (passphrase != null) {
            // SQLCipher clears the array it is handed, so the factory gets a copy and the value
            // held here stays usable if this provider is ever called again.
            builder.openHelperFactory(
                net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.copyOf())
            )
        }
        return builder.build()
    }

    @Provides
    fun provideContactSyncDao(db: SyncDatabase): ContactSyncDao = db.contactSyncDao()

    @Provides
    fun provideCalendarSyncDao(db: SyncDatabase): CalendarSyncDao = db.calendarSyncDao()

    @Provides
    fun provideCalendarEventSyncDao(db: SyncDatabase): CalendarEventSyncDao = db.calendarEventSyncDao()

    @Provides
    @Singleton
    fun provideAccountManager(@ApplicationContext context: Context): AccountManager {
        return AccountManager.get(context)
    }

    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver {
        return context.contentResolver
    }
}
