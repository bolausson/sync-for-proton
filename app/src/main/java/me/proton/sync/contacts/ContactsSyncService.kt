package me.proton.sync.contacts

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.crypto.ContactDecryptor
import me.proton.sync.crypto.KeyManager
import me.proton.sync.db.daos.ContactSyncDao
import javax.inject.Inject

@AndroidEntryPoint
class ContactsSyncService : Service() {

    @Inject lateinit var apiClient: ProtonApiClient
    @Inject lateinit var sessionManager: AccountSessionManager
    @Inject lateinit var contactDecryptor: ContactDecryptor
    @Inject lateinit var contactSyncDao: ContactSyncDao
    @Inject lateinit var keyManager: KeyManager

    private var syncAdapter: ContactsSyncAdapter? = null
    private val lock = Object()

    override fun onCreate() {
        super.onCreate()
        synchronized(lock) {
            if (syncAdapter == null) {
                syncAdapter = ContactsSyncAdapter(
                    context = applicationContext,
                    autoInitialize = true,
                    apiClient = apiClient,
                    sessionManager = sessionManager,
                    contactDecryptor = contactDecryptor,
                    contactSyncDao = contactSyncDao,
                    keyManager = keyManager
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return syncAdapter!!.syncAdapterBinder
    }
}
