package me.proton.sync.account

data class AccountSession(
    val accountName: String,
    val accessToken: String,
    val refreshToken: String,
    val uid: String,
    val keySalt: String? = null,
    val encPrivateKey: String? = null,
    val mailboxPassword: String? = null,
    /**
     * Proton's password-mode flag from the `/auth/v4` response.
     *  - `1` = single-password mode: the SRP login password also unlocks the keys.
     *  - `2` = legacy two-password mode: a separate mailbox password is required to
     *    derive the key passphrase. Asking the user for it is the only way to unlock.
     *
     * Defaults to `1` so existing accounts (persisted before this field was tracked)
     * continue to work via the single-password path.
     */
    val passwordMode: Int = 1
)
