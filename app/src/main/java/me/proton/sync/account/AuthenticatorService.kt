package me.proton.sync.account

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AuthenticatorService : Service() {

    @Inject lateinit var sessionManager: AccountSessionManager

    private lateinit var authenticator: ProtonAccountAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = ProtonAccountAuthenticator(this, sessionManager)
    }

    override fun onBind(intent: Intent?): IBinder {
        return authenticator.iBinder
    }
}
