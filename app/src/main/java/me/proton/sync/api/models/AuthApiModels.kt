/**
 * Proton authentication API data classes.
 *
 * Models for the SRP-based authentication flow, token refresh, and
 * post-login user/key endpoints used to decrypt calendar data.
 */
package me.proton.sync.api.models

import com.google.gson.annotations.SerializedName

/**
 * Response from `POST auth/v4/info`. Provides SRP parameters needed to
 * compute the client proof for the given username.
 */
data class AuthInfoResponse(
    /** API status code; 1000 = success. */
    @SerializedName("Code") val code: Int,
    /** SRP modulus (base-64 encoded, signed by Proton). */
    @SerializedName("Modulus") val modulus: String,
    /** Server's SRP ephemeral value (base-64). */
    @SerializedName("ServerEphemeral") val serverEphemeral: String,
    /** SRP protocol version. */
    @SerializedName("Version") val version: Int,
    /** Base-64 bcrypt salt used to derive the SRP password. */
    @SerializedName("Salt") val salt: String,
    /** Opaque session ID that must be sent back with the auth request. */
    @SerializedName("SRPSession") val srpSession: String
)

/**
 * Response from `POST auth/v4`. Contains the session tokens and, when
 * two-factor authentication is enabled, a [twoFactor] descriptor.
 */
data class AuthResponse(
    /** API status code; 1000 = success. */
    @SerializedName("Code") val code: Int,
    /** Bearer token for subsequent API calls. Treat as secret. */
    @SerializedName("AccessToken") val accessToken: String,
    /** Long-lived token used to obtain a new access token. Treat as secret. */
    @SerializedName("RefreshToken") val refreshToken: String,
    @SerializedName("TokenType") val tokenType: String,
    /** Unique session identifier, sent as the `x-pm-uid` header. */
    @SerializedName("Uid") val uid: String,
    @SerializedName("Scope") val scope: String,
    /** SRP server proof; the client should verify this to confirm mutual authentication. */
    @SerializedName("ServerProof") val serverProof: String,
    /** 1 = single-password mode, 2 = two-password mode (legacy). */
    @SerializedName("PasswordMode") val passwordMode: Int,
    /** Base-64 salt for deriving the mailbox/key password; null in single-password mode. */
    @SerializedName("KeySalt") val keySalt: String?,
    /** Present when the account has 2FA enabled. */
    @SerializedName("2FA") val twoFactor: TwoFactorInfo?
)

/**
 * Two-factor authentication status returned inside [AuthResponse].
 */
data class TwoFactorInfo(
    /** Bitmask: 0 = disabled, 1 = TOTP enabled, 2 = FIDO2 enabled, 3 = both. */
    @SerializedName("Enabled") val enabled: Int,
    /** 1 if TOTP is enabled for this account, 0 otherwise. */
    @SerializedName("TOTP") val totp: Int,
    /**
     * Security-key detail. Diagnostic only: we do not perform a FIDO2 ceremony, but the
     * bitmask alone cannot tell a hardware key from a passkey, and both set the same bit.
     * Upstream `TwoFAInfo.FIDO2` (`go-proton-api/manager_auth_types.go:66-70`).
     */
    @SerializedName("FIDO2") val fido2: Fido2Info? = null
)

/**
 * The FIDO2 half of [TwoFactorInfo].
 *
 * Upstream also carries `AuthenticationOptions` (the WebAuthn challenge, typed `any`) which
 * we deliberately do not model: it is only needed to actually perform a ceremony, and
 * parsing an untyped blob on the sign-in path buys nothing.
 */
data class Fido2Info(
    @SerializedName("RegisteredKeys") val registeredKeys: List<RegisteredKey>? = null
)

/**
 * One registered security key (`go-proton-api/manager_auth_types.go:60-64`).
 *
 * [name] is what the account holder called the key in Proton's settings, which identifies
 * the authenticator far more reliably than guessing from WebAuthn transport hints.
 * `CredentialID` is deliberately not modelled: it identifies the credential and has no
 * diagnostic value.
 */
data class RegisteredKey(
    @SerializedName("Name") val name: String? = null,
    /** WebAuthn attestation statement format, e.g. `packed`, `apple`, `android-key`, `none`. */
    @SerializedName("AttestationFormat") val attestationFormat: String? = null
)

/**
 * Request body for `POST auth/v4/2fa` to submit a TOTP code.
 */
data class TwoFactorRequest(
    @SerializedName("TwoFactorCode") val twoFactorCode: String
)

/**
 * Request body for `POST auth/v4/refresh` to exchange a refresh token
 * for a new access/refresh token pair.
 */
data class RefreshTokenRequest(
    /** The current refresh token. Treat as secret. */
    @SerializedName("RefreshToken") val refreshToken: String,
    /** Session UID the refresh token belongs to. */
    @SerializedName("UID") val uid: String,
    @SerializedName("ResponseType") val responseType: String = "token",
    @SerializedName("GrantType") val grantType: String = "refresh_token",
    @SerializedName("RedirectURI") val redirectUri: String = "https://protonmail.ch"
)

/**
 * Response from `POST auth/v4/refresh`. Same token structure as [AuthResponse]
 * but without SRP or 2FA fields.
 */
data class RefreshTokenResponse(
    @SerializedName("Code") val code: Int,
    /** New bearer access token. Treat as secret. */
    @SerializedName("AccessToken") val accessToken: String,
    /** New refresh token (the old one is now invalid). Treat as secret. */
    @SerializedName("RefreshToken") val refreshToken: String,
    @SerializedName("TokenType") val tokenType: String,
    @SerializedName("Uid") val uid: String,
    @SerializedName("Scope") val scope: String
)

/**
 * Request body for `POST auth/v4`. Contains the SRP client proof computed
 * from the parameters in [AuthInfoResponse].
 */
data class SrpAuthRequest(
    @SerializedName("Username") val username: String,
    /** Base-64 SRP client ephemeral value. */
    @SerializedName("ClientEphemeral") val clientEphemeral: String,
    /** Base-64 SRP client proof. */
    @SerializedName("ClientProof") val clientProof: String,
    /** Session ID from [AuthInfoResponse.srpSession]. */
    @SerializedName("SRPSession") val srpSession: String
)

// User/Keys models for fetching the user's encrypted private key after login

/** Response from `GET users`. */
data class UserResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("User") val user: UserInfo
)

/** Core user profile and associated encryption keys. */
data class UserInfo(
    @SerializedName("ID") val id: String,
    @SerializedName("Name") val name: String?,
    @SerializedName("Email") val email: String?,
    @SerializedName("DisplayName") val displayName: String?,
    /** User-level encryption keys; used to decrypt address keys. */
    @SerializedName("Keys") val keys: List<UserKey>?
)

/**
 * An OpenPGP private key belonging to a user or address.
 */
data class UserKey(
    @SerializedName("ID") val id: String,
    /** Armored OpenPGP private key (encrypted with the mailbox password). */
    @SerializedName("PrivateKey") val privateKey: String,
    /** 1 = active, 0 = inactive/disabled. */
    @SerializedName("Active") val active: Int,
    /** 1 = primary key for this user/address; null or 0 = secondary. */
    @SerializedName("Primary") val primary: Int?,
    /** Encrypted key token for organization keys; null for personal keys. */
    @SerializedName("Token") val token: String?,
    /** Transient: set programmatically to associate this key with an address email. */
    @Transient var addressEmail: String? = null
)

/** Response from `GET addresses`. */
data class AddressesResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("Addresses") val addresses: List<AddressInfo>
)

/** A Proton email address and its associated encryption keys. */
data class AddressInfo(
    @SerializedName("ID") val id: String,
    @SerializedName("Email") val email: String,
    /** Address-level encryption keys; used to decrypt per-address data (e.g. calendar events). */
    @SerializedName("Keys") val keys: List<UserKey>?
)

/** Response from `GET core/v4/keys/salts`. */
data class KeySaltsResponse(
    @SerializedName("Code") val code: Int,
    @SerializedName("KeySalts") val keySalts: List<KeySaltInfo>
)

/** Maps a key ID to its bcrypt salt, used for deriving the key passphrase. */
data class KeySaltInfo(
    @SerializedName("ID") val id: String,
    /** Base-64 bcrypt salt; null if the key uses the legacy flat passphrase. */
    @SerializedName("KeySalt") val keySalt: String?
)
