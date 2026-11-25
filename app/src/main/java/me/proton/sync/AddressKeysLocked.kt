package me.proton.sync

import me.proton.sync.calendar.AddressKeysMissing

/**
 * What a SUCCESSFUL read of an account's addresses left it with (SyncUtils.doEnsureKeysUnlocked), and when that is a
 * state only signing in again heals. Pure, so it is tested without a device.
 *
 * Since 2.2.0 a calendar with keys is skipped while no address key of the account is loaded (calendar.AddressKeysMissing).
 * When the read of the addresses failed (an HTTP status or a request that threw), that is a passing state: the next
 * sync reads them again, and the skip names the status or the network cause. When the read succeeded and still no key
 * loaded, nothing passes: every sync skipped the calendars and said "unexpected error" (owner, 2026-11-23). The usual
 * cause is a password changed elsewhere: the stored mailbox password still opens the stored user key, but no longer the
 * account's address keys. That state leads to the sign-in-again flow (SyncUtils.runSyncPreflight, KEYS_LOCKED), and
 * only that state ([State.SIGN_IN_AGAIN]): the read succeeded in this run, Proton marks at least one address key active,
 * this app can read at least one of those, and none of them unlocks with the stored mailbox password, so no address key
 * is loaded at all.
 *
 * Two other states keep the calendar skip, with a reason of their own, because signing in again would read the same
 * keys and change nothing: no address key is active at all ([State.NO_ACTIVE_KEY]), or none of the active ones can be
 * read by this app ([State.UNREADABLE], KeyManager.canParseKey). An account with any address key loaded is fine
 * ([State.LOADED]), whatever happened to its other keys.
 */
internal object AddressKeysLocked {

    /** The four outcomes of a read of the addresses that succeeded. */
    enum class State {
        /** At least one address key is loaded: the account is fine. */
        LOADED,

        /** Proton marks none of the account's address keys active: signing in again would not help. */
        NO_ACTIVE_KEY,

        /** Some are active, but this app cannot read any of them: a new sign-in reads the same keys. */
        UNREADABLE,

        /** Active keys this app can read, and the stored mailbox password opens none of them: the user signs in again. */
        SIGN_IN_AGAIN,
    }

    /** A read of the addresses that succeeded: its [state], and how many address keys Proton marks active ([activeKeys]). */
    class Read(val state: State, val activeKeys: Int)

    /**
     * The state a successful read left the account in: [activeKeys] address keys Proton marks active, [readableActiveKeys]
     * of them this app can read, and whether any address key is loaded after the unlock ([anyAddressKeyLoaded]).
     */
    fun state(activeKeys: Int, readableActiveKeys: Int, anyAddressKeyLoaded: Boolean): State = when {
        anyAddressKeyLoaded -> State.LOADED
        activeKeys <= 0 -> State.NO_ACTIVE_KEY
        readableActiveKeys <= 0 -> State.UNREADABLE
        else -> State.SIGN_IN_AGAIN
    }

    /**
     * Whether the sync leads the user to sign in again: this run's read of the addresses ([read], null when it failed or
     * none was made) found the account locked out of its address keys, and still none is loaded
     * ([anyAddressKeyLoaded]). A read that failed never leads there, nor does an account with a key loaded.
     */
    fun signsInAgain(read: Read?, anyAddressKeyLoaded: Boolean): Boolean =
        read?.state == State.SIGN_IN_AGAIN && !anyAddressKeyLoaded

    /** What the sign-in-again flow logs (SyncUtils.KeysNotUnlockedException): the account and the count, no key. */
    fun message(accountName: String, activeKeys: Int): String =
        "The addresses of $accountName were read, but the stored mailbox password opens none of their " +
            "$activeKeys active key(s); the account must sign in again"

    /**
     * The reason a calendar skipped for want of an address key (AddressKeysMissing) records when the read succeeded
     * ([read]), saying why no key loaded; null when the read failed (its status or cause is recorded instead) or a key is
     * loaded. Named by the start of the calendar's id, as AddressKeysMissing.reason names it.
     */
    fun reason(calendarId: String, read: Read?): String? {
        val why = when (read?.state) {
            State.NO_ACTIVE_KEY -> "the account has no active address key"
            State.UNREADABLE -> "this app cannot read any of the account's ${read.activeKeys} active address key(s)"
            State.SIGN_IN_AGAIN -> "the stored mailbox password opens none of the account's ${read.activeKeys} active address key(s)"
            State.LOADED, null -> return null
        }
        return AddressKeysMissing.reason(calendarId, null) + ": " + why
    }
}
