package me.proton.sync.crypto

/**
 * Calendar key `Flags`, from Proton's own `CalendarKeyFlags` enum
 * (WebClients `packages/shared/lib/interfaces/calendar/CalendarKey.ts`):
 *
 *   INACTIVE = 0, ACTIVE = 1, PRIMARY = 2
 *
 * A key that is both active and primary therefore carries 3. Proton's Android calendar spells this
 * out ("0: Inactive - 1: Active - 3: Active and primary") and picks the key it ENCRYPTS new events
 * with by `isActiveAndPrimary = flags == 3` (android-calendar 2.34.0, `EditCreateEventUseCase`).
 *
 * This app used to read bit value 1 as "primary". Bit value 1 means ACTIVE, so every active key
 * qualified, and because the primary slot is overwritten by each primary key it sees, the LAST
 * active key became the encryption target. On a calendar with a single key that is harmless, since
 * that key is 3 either way. After a key rotation, when a calendar carries more than one active key,
 * new events went out encrypted to whichever active key happened to be listed last. The same class
 * of mistake as reading bit 4 as WRITE in Member.Permissions: a bitmask decoded against the wrong
 * table.
 *
 * The flags never decided decryption: every calendar key that unlocks goes into the account-wide
 * ring whatever its flags (see [KeyManager.unlockCalendarKey]). Only the choice of encryption key
 * depends on them. A separate bookkeeping flaw could skip a key's unlock altogether, which did
 * affect decryption; that is fixed in [CalendarKeySelection].
 */
internal object CalendarKeyFlags {
    const val ACTIVE = 1
    const val PRIMARY = 2
}

/**
 * Whether a calendar key is the one new events must be encrypted to.
 *
 * Both bits are required, matching Proton's Android client (`flags == 3`) while still tolerating any
 * further bit Proton might add later. A key flagged primary but not active cannot occur in practice,
 * and is not treated as primary if it ever did.
 */
internal fun isPrimaryCalendarKey(flags: Int): Boolean =
    (flags and CalendarKeyFlags.ACTIVE) != 0 && (flags and CalendarKeyFlags.PRIMARY) != 0
