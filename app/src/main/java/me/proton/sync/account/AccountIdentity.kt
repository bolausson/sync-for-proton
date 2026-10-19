package me.proton.sync.account

/**
 * Which Proton sign-in an account on this phone belongs to, as far as the sign-in screen can tell.
 * Pure, so LoginActivity's guards can be tested on the JVM.
 *
 * Why the guards exist. Requests are routed to an account by its bare NAME (the `@Tag accountName` on
 * every ProtonApiClient call; X-Proton-Account-Name up to 1.11.4), and two kinds of name share that
 * namespace: the display names of the accounts already on the phone, and, while a sign-in is in
 * progress, the typed username its temporary session is stored under (SrpAuthHandler.login). Nothing
 * stopped the two from meeting. AccountSessionManager.storeSession writes onto any AccountManager row of
 * that name, so signing in as "work" while another Proton identity's account was called "work" replaced
 * that account's tokens, UID and key salt (and KeyManager its key ring), and from then on it synced the
 * other identity's data. Picking an existing display name at the alias prompt did the same through
 * AccountProvisioner.provision.
 */
internal object AccountIdentity {

    /**
     * The form two sign-in names are compared in. "alice" and "alice@proton.me" are the same
     * identifier as far as this app is concerned: the alias dialog appends the domain when the user
     * typed a bare username, so the stored proton_username and what they type at a later sign-in
     * legitimately differ that way.
     */
    fun canonical(identifier: String): String =
        identifier.trim().lowercase().removeSuffix("@proton.me").removeSuffix("@pm.me")

    fun sameSignIn(a: String, b: String): Boolean = canonical(a) == canonical(b)

    /**
     * Whether [name] is already the name of an account on this phone that was added with a sign-in
     * name other than [signIn].
     *
     * [storedUsernames] maps each account name on the phone to the proton_username it was added
     * with, or to null for an account from before that was recorded. Such an account cannot be told
     * apart, so it never counts as taken: that keeps the behaviour it always had. Account names are
     * compared exactly, as AccountManager and the session map compare them.
     */
    fun takenByOtherSignIn(storedUsernames: Map<String, String?>, name: String, signIn: String): Boolean {
        val stored = storedUsernames[name] ?: return false
        return !sameSignIn(stored, signIn)
    }
}
