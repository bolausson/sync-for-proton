package me.proton.sync.api

/**
 * Which `/auth/v4` calls must go out WITHOUT a Bearer token. Pure, so it can be tested without
 * okhttp, a session or a device.
 *
 * It lives apart from [AuthInterceptor] because the rule is easy to get subtly wrong and the failure
 * is silent in both directions: attach a token where none belongs and login breaks; strip one where
 * it belongs and the call is a guaranteed 401 that looks like an expired session.
 *
 * Matching on the path alone did exactly that. `DELETE /auth/v4` revokes the session it is
 * authenticated with, and it ends with `/auth/v4` just as the login does, so the session-revoke
 * added in 1.10.77 would have been stripped of its token and failed every time, leaving the session
 * alive on Proton after the account had gone from the phone. The METHOD is what separates them.
 *
 * Everything else under `/auth/v4` is authenticated, as Proton's API expects: 2FA submission
 * carries Bearer and UID, and the refresh call builds its own request rather than coming through
 * here.
 */
internal fun isUnauthenticatedAuthCall(path: String, method: String): Boolean {
    // The SRP challenge: there is no session yet, by definition.
    if (path.contains("/auth/v4/info")) return true
    // The login itself. Only the POST: a DELETE to the same path is the sign-out.
    return path.endsWith("/auth/v4") && method.equals("POST", ignoreCase = true)
}
