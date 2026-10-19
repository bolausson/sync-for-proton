package me.proton.sync.api

import okhttp3.Request

/*
 * Which app account a Proton call is for, carried as an OkHttp request TAG.
 *
 * Retrofit's `@Tag accountName: String` (every account-scoped endpoint in [ProtonApiClient]) stores the
 * value under its declared type, String, and OkHttp copies tags along with newBuilder(), so the
 * interceptor, its 429 retries and the authenticator's 401 retry all see the same value. A tag never
 * leaves the phone. Nothing else in the app tags requests with a String; keep it that way.
 *
 * Replaces the X-Proton-Account-Name header used up to 1.11.4, which sent the account's display name to
 * Proton and made OkHttp throw on any non-ASCII name.
 */

/** The account this request is for, or null when the call names none (sign-in, refresh). */
internal fun Request.protonAccountName(): String? = tag(String::class.java)

/** Name the account this request is for. A tag only: nothing is added to the request that is sent. */
internal fun Request.Builder.protonAccountName(name: String): Request.Builder = tag(String::class.java, name)
