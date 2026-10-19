package me.proton.sync.calendar

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.crypto.CalendarDecryptor
import me.proton.sync.crypto.KeyManager
import me.proton.sync.db.daos.CalendarEventSyncDao
import me.proton.sync.db.daos.CalendarSyncDao
import javax.inject.Inject

@AndroidEntryPoint
class CalendarSyncService : Service() {

    @Inject lateinit var apiClient: ProtonApiClient
    @Inject lateinit var sessionManager: AccountSessionManager
    @Inject lateinit var calendarDecryptor: CalendarDecryptor
    @Inject lateinit var calendarSyncDao: CalendarSyncDao
    @Inject lateinit var calendarEventSyncDao: CalendarEventSyncDao
    @Inject lateinit var keyManager: KeyManager

    private var syncAdapter: CalendarSyncAdapter? = null
    private val lock = Object()

    override fun onCreate() {
        super.onCreate()
        synchronized(lock) {
            if (syncAdapter == null) {
                syncAdapter = CalendarSyncAdapter(
                    context = applicationContext,
                    autoInitialize = true,
                    apiClient = apiClient,
                    sessionManager = sessionManager,
                    calendarDecryptor = calendarDecryptor,
                    calendarSyncDao = calendarSyncDao,
                    calendarEventSyncDao = calendarEventSyncDao,
                    keyManager = keyManager
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return syncAdapter!!.syncAdapterBinder
    }
}
