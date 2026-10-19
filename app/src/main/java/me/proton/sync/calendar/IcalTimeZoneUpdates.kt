package me.proton.sync.calendar

/**
 * ical4j's own time-zone updater: switched OFF for good, at app start, before anything can parse iCal.
 *
 * ical4j 3.2.14 resolves every TZID through its registry and hands the bundled definition to its
 * TimeZoneUpdater, which is ON unless told otherwise and fetches `https://www.tzurl.org/zoneinfo/<zone>` with
 * HttpURLConnection, so the platform's `http.agent` (Android version, phone model, build ID) went with it. Up
 * to 1.11.4 the app never switched it off; 1.11.5's first pass made it the "Update time zones online" switch.
 *
 * Since 1.11.5 no event time comes from ical4j at all (DownloadTimes computes them with java.time from the
 * raw text, and ical4j misread tzurl's current files anyway: America/Vancouver nine hours early from
 * 2026-11-01). The switch now chooses the RULE SOURCE ([TimeZoneRuleSource], [OnlineTimeZoneRules]), fetched
 * by the app's own client ([TzurlClient]) with a generic User-Agent. So nothing may ever turn ical4j's
 * updater on again: [disable] is the only thing this object does, and it only ever writes "false".
 *
 * What the library does, verified in the 3.2.14 bytecode (TimeZoneUpdater, TimeZoneLoader, Configurator):
 *  - The flag is read again on every update attempt, so [disable] governs every zone loaded after it.
 *  - ONLY the exact lowercase string "false" turns updates off. Any other value, "False" included, and no
 *    value at all, mean ON. So the property is never cleared, which would switch updates back on.
 *  - System properties are authoritative because no ical4j.properties exists on the classpath (it would be
 *    read first). Keep it that way.
 *  - The download has no timeout by default and runs under a process-wide lock on ical4j's zone cache, so
 *    both timeouts are set too, in case anything ever slipped past the flag.
 */
object IcalTimeZoneUpdates {

    /** ical4j's own key (TimeZoneUpdater.UPDATE_ENABLED). */
    const val PROPERTY = "net.fortuna.ical4j.timezone.update.enabled"

    /** ical4j's connect and read timeouts for the download, in milliseconds. Unset means none. */
    const val CONNECT_TIMEOUT_PROPERTY = "net.fortuna.ical4j.timezone.update.timeout.connect"
    const val READ_TIMEOUT_PROPERTY = "net.fortuna.ical4j.timezone.update.timeout.read"

    /** Short, should anything ever get past the flag. */
    const val TIMEOUT_MS = 10_000

    /** Switch ical4j's updater off for the whole process. Writes exactly "false", never anything else. */
    fun disable() {
        System.setProperty(CONNECT_TIMEOUT_PROPERTY, TIMEOUT_MS.toString())
        System.setProperty(READ_TIMEOUT_PROPERTY, TIMEOUT_MS.toString())
        System.setProperty(PROPERTY, "false")
    }
}
