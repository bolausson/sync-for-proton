package me.proton.sync.calendar

import kotlinx.coroutines.withTimeoutOrNull
import me.proton.sync.api.ProtonApiError
import me.proton.sync.api.models.CalendarInfo
import me.proton.sync.api.models.CalendarMember
import me.proton.sync.api.models.CalendarUserSettingsResponse
import me.proton.sync.calendar.PrimaryCalendarChooser.Decision
import me.proton.sync.calendar.PrimaryCalendarChooser.Reason
import me.proton.sync.calendar.PrimaryCalendarChooser.Settings
import me.proton.sync.db.entities.CalendarSyncEntity
import retrofit2.Response
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLHandshakeException

/**
 * What the account card's Default calendar row offers, and how it reads Proton's answers to a change. Pure, with no
 * Android references, so it is tested on the JVM; DefaultCalendarService fetches the inputs and sends the change.
 *
 * The row offers exactly the primary-calendar chooser's candidates ([PrimaryCalendarChooser.candidates], reached
 * through [PrimaryCalendarChooser.choose]): Type 0, owned through our own member, active by its Flags, in Proton's
 * order. That is the set Proton web and Proton's Android app allow as a default. It never filters on its own: the
 * calendars it leaves out are only counted, with [PrimaryCalendarChooser.whyPassedOver].
 */
internal object DefaultCalendarChoices {

    /** The sync's name for a personal calendar Proton gives none (CalendarSyncAdapter.prepareCalendarJob). */
    const val MY_CALENDAR = "My Calendar"

    /** One calendar the row offers. [androidCalId] is its mapping's row when the list was loaded, for the log only. */
    data class Choice(val protonCalendarId: String, val name: String, val androidCalId: Long?)

    /** Why one of the account's calendars is not offered. */
    enum class LeftOut { SHARED, SUBSCRIBED_OR_HOLIDAY, INACTIVE, NOT_OURS }

    /** What the calendar list alone says, before Proton's settings are read. */
    sealed interface Shape {
        /** [candidates] can be offered; [leftOut] counts the account's other calendars by why they are not. */
        data class Candidates(val candidates: List<CalendarInfo>, val leftOut: Map<LeftOut, Int>) : Shape

        /** No calendar of the account can be the default: nothing is offered, and settings/calendar is not read. */
        data class NoOwnCalendar(val leftOut: Map<LeftOut, Int>) : Shape

        /**
         * Which calendars are ours cannot be told: the address list was not loaded, or not fully. [noAddressLoaded]:
         * no address of the account loaded at all. An account that lists a personal calendar has an address, so the
         * address read failed (the key unlock goes on without it) or none of the address keys unlocked, and another
         * try may work. False: addresses loaded, and a personal calendar still lists members on none of them.
         */
        data class Unknown(val detail: String, val noAddressLoaded: Boolean) : Shape
    }

    /**
     * The list as the row shows it: [choices] in Proton's order, [checkedIndex] the calendar Proton uses as the default
     * now ([decision]), [storedId] Proton's DefaultCalendarID as stored (null when it is not set), [leftOut] the counts of
     * [Shape.Candidates], and [liveProtonIds] every calendar Proton listed (PrimaryCalendarRows.locate counts the other
     * calendars that share a row among these).
     */
    data class Offer(
        val choices: List<Choice>,
        val checkedIndex: Int,
        val storedId: String?,
        val decision: Decision.Known,
        val leftOut: Map<LeftOut, Int>,
        val liveProtonIds: Set<String>,
    ) {
        /** The calendar Proton uses as the default now. */
        val checked: Choice get() = choices[checkedIndex]
    }

    /** How a change of the default ended. [Uncertain] exists only between the PUT and its check ([finishSave]). */
    sealed interface SaveOutcome {
        /** Proton has the new default. */
        data object Saved : SaveOutcome

        /**
         * Proton answered with an error, or kept another default, so the change was not made. [suffix] is what the log
         * adds after the HTTP status: ProtonApiError.describe's text, or why a 2xx counts as a refusal.
         */
        data class Refused(val http: Int, val code: Int?, val suffix: String = "") : SaveOutcome

        /** The session needs a new sign-in. */
        data object Auth : SaveOutcome

        /**
         * No usable answer. [mayHaveArrived] false: nothing reached Proton, or Proton turned the request away before
         * reading it (408, 429), so the change was not made. True: the PUT went out, its answer was lost, and Proton,
         * asked once more, did not confirm it (it could not be asked, or it still had another default, which a slow PUT
         * may still replace), so Proton may have the change after all.
         */
        data class Unreachable(val detail: String, val mayHaveArrived: Boolean = false) : SaveOutcome

        /**
         * The PUT went out and its answer may have been lost, or did not confirm the change. [refusal] is set for a 2xx
         * that names no change: Proton keeping another default when asked once more then counts as that refusal.
         */
        data class Uncertain(val detail: String, val refusal: Refused? = null) : SaveOutcome
    }

    /**
     * The shape of the list from the full `GET calendar/v1` list and the account's lowercase addresses, as the chooser
     * itself reads them: no Type-0 calendar at all, or no candidate, is [Shape.NoOwnCalendar]; a list whose personal
     * calendars hold no member of ours, or one that lists members on none of the loaded addresses, is [Shape.Unknown]
     * (the chooser's NO_OWN_MEMBER and PARTIAL_ADDRESSES), because a list drawn from part of the addresses could leave
     * out an own calendar. An empty [userAddresses] always gives [Shape.Unknown] with [Shape.Unknown.noAddressLoaded]
     * when the list holds a personal calendar.
     */
    fun shape(calendars: List<CalendarInfo>, userAddresses: Set<String>): Shape {
        if (calendars.none { it.type == PrimaryCalendarChooser.TYPE_PERSONAL }) {
            return Shape.NoOwnCalendar(leftOut(calendars, emptyList(), userAddresses))
        }
        // The chooser's own answer without a stored default: its candidates, or why it cannot tell.
        val result = PrimaryCalendarChooser.choose(calendars, userAddresses, Settings.Loaded(null))
        val decision = result.decision
        if (decision is Decision.Unknown) return Shape.Unknown(decision.detail, noAddressLoaded = userAddresses.isEmpty())
        val leftOut = leftOut(calendars, result.candidates, userAddresses)
        return if (result.candidates.isEmpty()) Shape.NoOwnCalendar(leftOut) else Shape.Candidates(result.candidates, leftOut)
    }

    /**
     * The list for [shape] with Proton's [settings]: the candidates in their order, each named by [nameOf] with its
     * mapping from [mappings] (by Proton id), and the calendar [PrimaryCalendarChooser.choose] decides on checked. Null
     * only when the chooser does not decide, which [shape] rules out for the same [calendars] and [userAddresses].
     */
    fun offer(
        shape: Shape.Candidates,
        calendars: List<CalendarInfo>,
        userAddresses: Set<String>,
        settings: Settings.Loaded,
        mappings: Map<String, CalendarSyncEntity>,
        accountName: String,
    ): Offer? {
        val decision = PrimaryCalendarChooser.choose(calendars, userAddresses, settings).decision as? Decision.Known
            ?: return null
        val choices = shape.candidates.map { calendar ->
            val mapping = mappings[calendar.id]
            Choice(
                protonCalendarId = calendar.id,
                name = nameOf(calendar, ownMember(calendar.members, userAddresses), mapping?.displayName, accountName),
                androidCalId = mapping?.androidCalendarId,
            )
        }
        val checkedIndex = choices.indexOfFirst { it.protonCalendarId == decision.protonCalendarId }
        if (checkedIndex < 0) return null
        return Offer(
            choices = choices,
            checkedIndex = checkedIndex,
            storedId = settings.defaultCalendarId?.takeIf { it.isNotEmpty() },
            decision = decision,
            leftOut = shape.leftOut,
            liveProtonIds = calendars.mapTo(HashSet()) { it.id },
        )
    }

    /**
     * The one name of a calendar for the list, a pick's record and the sync's record: the first non-blank of the
     * mapping's display name without the `<accountName> - ` prefix the sync adds when there are several accounts
     * (what calendar apps show), the calendar's Name, its Description, our own member's Name, and [MY_CALENDAR].
     */
    fun nameOf(calendar: CalendarInfo?, own: CalendarMember?, mappingDisplayName: String?, accountName: String): String =
        listOf(mappingDisplayName?.removePrefix("$accountName - "), calendar?.name, calendar?.description, own?.name)
            .firstOrNull { !it.isNullOrBlank() } ?: MY_CALENDAR

    /** Whether choosing [chosenId] has to be sent: Proton stores another id, or none ([storedId] null). */
    fun needsPut(chosenId: String, storedId: String?): Boolean = chosenId != storedId

    /**
     * Whether a settings read that [failed] needs a new sign-in rather than another try: Proton answered 401 (after the
     * client's own token refresh). Only a Failed built from an HTTP answer carries the status (CalendarUserSettingsFetch).
     */
    fun needsSignIn(failed: Settings.Failed): Boolean = failed.httpStatus == 401

    /**
     * The index of the one choice [part] names, for the debug verb: the only name equal to it, ignoring case, else the
     * only name that contains it. Null for a blank part, and when it names none or several.
     */
    fun matchByNamePart(choices: List<Choice>, part: String?): Int? {
        val wanted = part?.trim().orEmpty()
        if (wanted.isEmpty()) return null
        val exact = choices.indices.filter { choices[it].name.equals(wanted, ignoreCase = true) }
        val partial = choices.indices.filter { choices[it].name.contains(wanted, ignoreCase = true) }
        return exact.singleOrNull() ?: partial.singleOrNull()
    }

    /**
     * Proton's answer to the PUT: [http] its status, [protonCode] the body's Code, [echoPresent] whether a 2xx body held
     * a settings object, [echoedId] its DefaultCalendarID. Saved for a 2xx with Code 1000 whose echo, when there is one,
     * names [chosenId]. A 2xx with Code 1000 whose echo names another calendar or none, or a 2xx without a Code (no
     * body to read), is Uncertain: Proton's own clients ignore the echo, so the one check after it decides, and Proton
     * keeping another default then counts as a refusal. A 2xx with another Code, and any other 4xx (403 included), is
     * Refused; 401 needs a new sign-in; 408 and 429 are Unreachable (Proton turned the request away); a 5xx is
     * Uncertain, since Proton may have applied the change before it failed.
     */
    fun classifySave(http: Int, protonCode: Int?, echoPresent: Boolean, echoedId: String?, chosenId: String): SaveOutcome =
        when {
            http in 200..299 -> when {
                protonCode == 1000 && (!echoPresent || echoedId == chosenId) -> SaveOutcome.Saved
                protonCode == 1000 || protonCode == null -> {
                    val why = if (protonCode == null) " without a Code"
                        else " (Code 1000) naming ${if (echoedId == null) "no default" else "another default"}"
                    SaveOutcome.Uncertain(
                        "HTTP $http$why",
                        refusal = SaveOutcome.Refused(http, protonCode, "$why; Proton still has another default"),
                    )
                }
                else -> SaveOutcome.Refused(http, protonCode)
            }
            http == 401 -> SaveOutcome.Auth
            http == 408 || http == 429 -> SaveOutcome.Unreachable("HTTP $http")
            http in 500..599 -> SaveOutcome.Uncertain("HTTP $http")
            else -> SaveOutcome.Refused(http, protonCode)
        }

    /**
     * Proton's [response] to the PUT for [chosenId], read once: a 2xx by its body's Code and echo, an error by the Code
     * of its error body, which is read once (its cleaned text goes with a refusal, for the log).
     */
    fun classifyAnswer(response: Response<CalendarUserSettingsResponse>, chosenId: String): SaveOutcome {
        val http = response.code()
        if (response.isSuccessful) {
            val body = response.body()
            return classifySave(http, body?.code, body?.settings != null, body?.settings?.defaultCalendarId, chosenId)
        }
        // Read once: the Code decides, and the cleaned Error goes to the log.
        val text = try {
            response.errorBody()?.string()
        } catch (_: Exception) {
            null
        }
        return when (val outcome = classifySave(http, ProtonApiError.parse(text)?.first, false, null, chosenId)) {
            is SaveOutcome.Refused -> outcome.copy(suffix = ProtonApiError.describe(text))
            else -> outcome
        }
    }

    /**
     * The PUT for [chosenId] ([call]) and what its answer means: [classifyAnswer] for an answer,
     * [classifySaveFailure] for an exception, Uncertain when none came within [timeoutMs]. A CancellationException is
     * passed on.
     */
    suspend fun send(
        timeoutMs: Long,
        chosenId: String,
        call: suspend () -> Response<CalendarUserSettingsResponse>,
    ): SaveOutcome {
        val response = try {
            withTimeoutOrNull(timeoutMs) { call() }
                ?: return SaveOutcome.Uncertain("no answer within ${timeoutMs / 1000} s")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return classifySaveFailure(e)
        }
        return classifyAnswer(response, chosenId)
    }

    /**
     * The PUT threw instead of answering. Unreachable only when every attempt failed before anything was sent: no
     * address, no connection, no route or a failed TLS handshake, for the exception and for every exception it carries
     * as suppressed. The class alone proves nothing: OkHttp sends a request again after a failure on a connection that
     * was already open (retryOnConnectionFailure, on by default, and Retrofit's Gson body can be sent twice), and when
     * that new attempt cannot connect it throws the connect failure with the earlier failures attached as suppressed.
     * Anything else (a timeout, a reset stream, an unreadable 2xx body, or a connect failure after such an attempt) may
     * have come after Proton applied the change: Uncertain.
     */
    fun classifySaveFailure(e: Exception): SaveOutcome {
        val earlier = suppressedOf(e)
        val detail = e.javaClass.simpleName +
            if (earlier.isEmpty()) "" else " after " + earlier.map { it.javaClass.simpleName }.distinct().joinToString()
        val nothingSent = earlier.size < MAX_SUPPRESSED && beforeSending(e) && earlier.all(::beforeSending)
        return if (nothingSent) SaveOutcome.Unreachable(detail) else SaveOutcome.Uncertain(detail)
    }

    /** A failure that comes before a request is written: no address, no connection, no route, a failed TLS handshake. */
    private fun beforeSending(t: Throwable): Boolean =
        t is UnknownHostException || t is ConnectException || t is NoRouteToHostException || t is SSLHandshakeException

    /** More suppressed exceptions than any real call carries: past it, nothing is taken as proven. */
    private const val MAX_SUPPRESSED = 32

    /** How far down [e]'s causes [suppressedOf] looks. */
    private const val MAX_CAUSES = 8

    /**
     * Every exception [e] carries as suppressed, and the ones those carry, each once, breadth first; the walk stops
     * once [MAX_SUPPRESSED] are found. Its causes' suppressed ones count too: where kotlinx.coroutines recovers stack
     * traces (debug runs), a coroutine resumed with an exception receives a copy that keeps the original, and so its
     * suppressed ones, as its cause.
     */
    private fun suppressedOf(e: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>()).apply { add(e) }
        fun take(t: Throwable) {
            for (s in t.suppressed) if (seen.add(s)) out.add(s)
        }
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth++ < MAX_CAUSES) {
            take(cause)
            cause = cause.cause
        }
        var next = 0
        while (next < out.size && out.size < MAX_SUPPRESSED) take(out[next++])
        return out
    }

    /**
     * The end of a change: an Uncertain [first] is checked with one [verify]: Proton naming [chosenId] is Saved; another
     * default is the Uncertain's refusal when it has one, else Unreachable; no answer is Unreachable. Both Unreachable
     * outcomes may have arrived ([SaveOutcome.Unreachable.mayHaveArrived]). Then, for Saved, [applyLocally] runs once.
     * Its failure goes to [onApplyFailed] and the outcome stays Saved, since Proton has the change. A
     * CancellationException is passed on. Never returns Uncertain.
     */
    suspend fun finishSave(
        first: SaveOutcome,
        chosenId: String,
        verify: suspend () -> Settings,
        applyLocally: suspend () -> Unit,
        onApplyFailed: (Exception) -> Unit,
    ): SaveOutcome {
        val outcome = if (first is SaveOutcome.Uncertain) {
            val checked = try {
                verify()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Settings.Failed(e.javaClass.simpleName)
            }
            when (checked) {
                is Settings.Loaded ->
                    if (checked.defaultCalendarId == chosenId) SaveOutcome.Saved
                    else first.refusal
                        ?: SaveOutcome.Unreachable("${first.detail}; Proton still has another default", mayHaveArrived = true)
                is Settings.Failed ->
                    SaveOutcome.Unreachable("${first.detail}; Proton could not be asked again", mayHaveArrived = true)
            }
        } else {
            first
        }
        if (outcome is SaveOutcome.Saved) {
            try {
                applyLocally()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onApplyFailed(e)
            }
        }
        return outcome
    }

    /** [leftOut] for a log line. */
    fun leftOutText(leftOut: Map<LeftOut, Int>): String =
        "${leftOut[LeftOut.SHARED] ?: 0} shared, ${leftOut[LeftOut.SUBSCRIBED_OR_HOLIDAY] ?: 0} subscribed or holiday, " +
            "${leftOut[LeftOut.INACTIVE] ?: 0} inactive, ${leftOut[LeftOut.NOT_OURS] ?: 0} without a member of ours"

    /** Every calendar not among [candidates], counted by [PrimaryCalendarChooser.whyPassedOver]; every group present. */
    private fun leftOut(calendars: List<CalendarInfo>, candidates: List<CalendarInfo>, userAddresses: Set<String>): Map<LeftOut, Int> {
        val offered = candidates.mapTo(HashSet()) { it.id }
        val counts = LeftOut.entries.associateWithTo(LinkedHashMap()) { 0 }
        for (calendar in calendars) {
            if (calendar.id in offered) continue
            val why = leftOutOf(PrimaryCalendarChooser.whyPassedOver(calendar, userAddresses)) ?: continue
            counts[why] = (counts[why] ?: 0) + 1
        }
        return counts
    }

    private fun leftOutOf(reason: Reason): LeftOut? = when (reason) {
        Reason.DEFAULT_SHARED -> LeftOut.SHARED
        Reason.DEFAULT_SUBSCRIBED, Reason.DEFAULT_HOLIDAY, Reason.DEFAULT_OTHER_TYPE -> LeftOut.SUBSCRIBED_OR_HOLIDAY
        Reason.DEFAULT_DISABLED, Reason.DEFAULT_INACTIVE -> LeftOut.INACTIVE
        Reason.DEFAULT_NO_MEMBER -> LeftOut.NOT_OURS
        // Never what whyPassedOver says of a calendar in the list.
        Reason.PROTON_DEFAULT, Reason.DEFAULT_NOT_SET, Reason.DEFAULT_NOT_LISTED, Reason.NO_ACTIVE_OWN_CALENDAR,
        Reason.PICKED_HERE -> null
    }
}
