package me.proton.sync.calendar

/**
 * Whether the phone's own tz data looks stuck: the outdated-data notice and the hint under "Update time zones
 * online" use this, nothing else does.
 *
 * Outdated means the data's year is at least two behind max(the phone's clock year, [FLOOR_YEAR]). IANA has
 * published at least two releases every year since 2021, and a phone that still receives updates (Google
 * Play system updates carry the tz data from Android 10 on) gets them within months, so data two calendar
 * years old means at least a full year of missed releases. The floor is the year this shipped, so a phone
 * whose clock is set in the past still counts; it makes 2024 data outdated now and 2025 data from 1 January
 * 2027. 2021a (the Note 10+'s files) is outdated, 2026c (the S23) is not.
 *
 * An unknown or unreadable version is never outdated: no notice is better than a wrong one that asks the user
 * to send data to a third party.
 */
internal object TzDataFreshness {
    const val FLOOR_YEAR = 2026
    private val VERSION = Regex("^(\\d{4})([a-z]{1,2})$")

    /** The year of a tz release such as "2026c", or null when [version] is not one. */
    fun year(version: String?): Int? =
        version?.trim()?.let { VERSION.matchEntire(it) }?.groupValues?.get(1)?.toInt()

    fun isOutdated(version: String?, clockYear: Int): Boolean {
        val dataYear = year(version) ?: return false
        return maxOf(clockYear, FLOOR_YEAR) - dataYear >= 2
    }
}
