package me.proton.sync.contacts

import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay

/**
 * How a birthday or anniversary is spelled on the phone (Event.START_DATE).
 *
 * Android leaves that column free text, and the apps that read it have settled on the extended ISO forms that the
 * AOSP contacts editor, DAVx5 and Google's sync write: "yyyy-MM-dd", or "--MM-dd" without a year. Several calendar
 * and birthday apps drop the basic forms ("19900215", "--0215"), and "--0215" fails almost everywhere. Up to 2.2.0
 * this app wrote "19900215" back for every full date it had uploaded itself (the upload stores a full date as text
 * without dashes, and the download passed text through), so from 2.2.1 the phone gets the extended spelling.
 *
 * Proton keeps what it has: the upload still strips the dashes (ContactsSyncAdapter.buildVCardFromAndroid), so a
 * full date goes up as the very text Proton already stores.
 */
internal object ContactDates {

    private val COMPACT = Regex("([0-9]{4})([0-9]{2})([0-9]{2})")
    private val BASIC_YEARLESS = Regex("--([0-9]{2})([0-9]{2})")
    private val EXTENDED = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")
    private val EXTENDED_YEARLESS = Regex("--([0-9]{2})-([0-9]{2})")

    /**
     * The basic spelling of an extended date ("1990-02-15" becomes "19900215", "--02-15" becomes "--0215"), or null.
     * Only for the contact filter (FilterEvaluator): a rule written against the spelling the phone had before 2.2.1
     * keeps matching. Never written anywhere.
     */
    fun toBasic(text: String): String? {
        EXTENDED.matchEntire(text)?.let { m ->
            val (year, month, day) = m.destructured
            return "$year$month$day"
        }
        EXTENDED_YEARLESS.matchEntire(text)?.let { m ->
            val (month, day) = m.destructured
            return "--$month$day"
        }
        return null
    }

    /**
     * The extended spelling of [text] when it is a basic-form date naming a real day: exactly eight ASCII digits
     * ("19900215" becomes "1990-02-15"), or "--" followed by four ("--0215" becomes "--02-15"; 29 February is a real
     * day without a year). Null for anything else, text already in the extended form included, so the function
     * applied to its own result gives null and the rewrite pass (ContactDateRewrite) finds nothing left to do.
     *
     * ASCII digits only, on purpose: Char.isDigit also accepts Arabic or Persian digits, which no reader takes as a
     * date, and such text is left as it is. So is a date that does not exist ("19900230", "--1315"), a date-time, and
     * any text with spaces around it.
     */
    fun toExtended(text: String): String? {
        COMPACT.matchEntire(text)?.let { m ->
            val (year, month, day) = m.destructured
            return if (isRealDate(year.toInt(), month.toInt(), day.toInt())) "$year-$month-$day" else null
        }
        BASIC_YEARLESS.matchEntire(text)?.let { m ->
            val (month, day) = m.destructured
            return if (isRealMonthDay(month.toInt(), day.toInt())) "--$month-$day" else null
        }
        return null
    }

    private fun isRealDate(year: Int, month: Int, day: Int): Boolean =
        try {
            LocalDate.of(year, month, day)
            true
        } catch (e: DateTimeException) {
            false
        }

    private fun isRealMonthDay(month: Int, day: Int): Boolean =
        try {
            MonthDay.of(month, day)
            true
        } catch (e: DateTimeException) {
            false
        }
}
