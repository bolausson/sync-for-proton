package me.proton.sync.calendar

import org.junit.Assume.assumeTrue
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Some tests let the test JVM's own time-zone data play a phone with outdated rules: Vancouver at -08 in
 * December 2026, which IANA 2026d moved to a permanent -07 (the online rules of the tzurl fixtures). On a
 * JVM whose data already has that change, the phone and the online rules agree, so those tests could no
 * longer tell which rules the code used. They are skipped there instead of failing. JetBrains JDK 21.0.10
 * has 2025b; a JDK with 2026e data fails exactly the seven tests that call [assume].
 */
internal object StalePhoneJvm {

    /** Whether the JVM's data still has Vancouver at -08 in December 2026, as every release before 2026d did. */
    val outdated: Boolean = ZoneId.of("America/Vancouver").rules
        .getOffset(Instant.parse("2026-12-01T18:00:00Z")) == ZoneOffset.ofHours(-8)

    /** Skips the calling test unless the JVM's time-zone data can play the outdated phone. */
    fun assume() {
        assumeTrue(
            "needs a JVM whose time-zone data predates 2026d (Vancouver at -08 in December 2026) to play the outdated phone",
            outdated
        )
    }
}
