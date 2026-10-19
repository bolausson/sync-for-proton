package me.proton.sync.crypto

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Installs the bundled full BouncyCastle provider as "BC" at highest priority.
 *
 * Two reasons this must run before anything touches JCA:
 *  - Android's built-in "BC" is a stripped fork (missing XDH/X25519) and PGP needs
 *    the full one.
 *  - Android 16 removes the platform BC provider for apps targeting SDK 36, and with
 *    it the default KeyStore type "BKS" (KeyStore.getDefaultType()). Any TLS
 *    trust-manager init that resolves the default keystore (every OkHttp client)
 *    then throws "KeyStoreException: BKS not found" unless the bundled provider is
 *    already installed. Observed on the first Paddle license validate at app start,
 *    which fires from ProtonSyncApp.onCreate BEFORE Hilt ever constructs KeyManager
 *    (Android 16, SM-S918B, v1.6.22 bring-up) — so the install cannot live only in
 *    KeyManager's init.
 *
 * Idempotent: skips when the registered "BC" is already the bundled class (the
 * platform's stripped fork lives under com.android.org.bouncycastle, a different
 * class, so the type check distinguishes them).
 */
object BouncyCastleSetup {
    @JvmStatic
    fun ensureInstalled() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) is BouncyCastleProvider) return
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
