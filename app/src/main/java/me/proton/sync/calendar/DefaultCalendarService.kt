package me.proton.sync.calendar

import android.accounts.Account
import android.content.Context
import androidx.preference.PreferenceManager
import kotlinx.coroutines.withTimeoutOrNull
import me.proton.sync.LogNames
import me.proton.sync.SyncLog
import me.proton.sync.SyncPreferences
import me.proton.sync.SyncScheduling
import me.proton.sync.SyncUtils
import me.proton.sync.account.AccountSessionManager
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.models.CalendarInfo
import me.proton.sync.api.models.UpdateCalendarUserSettingsRequest
import me.proton.sync.api.protonErrorSuffix
import me.proton.sync.calendar.DefaultCalendarChoices.SaveOutcome
import me.proton.sync.crypto.KeyManager
import me.proton.sync.db.daos.CalendarSyncDao

/**
 * The account card's Default calendar row without its dialogs (DefaultCalendarPicker shows those): [load] reads the
 * account's calendars and Proton's default, offers the calendars that can be the default and brings the phone in line
 * with Proton; [save] changes the default on Proton (PUT settings/calendar) and then on the phone. Callers run both on
 * Dispatchers.IO, since unlocking the keys blocks. Logs under SyncSettings for the load and UserAction for a change;
 * Proton ids appear as their first 8 characters.
 */
internal class DefaultCalendarService(
    private val apiClient: ProtonApiClient,
    private val sessionManager: AccountSessionManager,
    private val keyManager: KeyManager,
    private val calendarSyncDao: CalendarSyncDao,
    /**
     * Runs after [bringInLine] wrote the phone's primary rows (a change Proton confirmed, or the load bringing the phone
     * in line): on the caller's background thread, after PrimaryCalendarPicks.applyOutsideSync has released its lock.
     * The prompts about new events held in shared calendars name the default calendar on their Move button
     * (NewEventHolds.refreshOutsideSync). Must not throw.
     */
    private val afterPrimaryRowsMoved: suspend (Context, Account) -> Unit = { _, _ -> },
) {

    /**
     * The debug verb's answers that stand in for Proton (DefaultCalendarPicker.Debug). None sends a change: OFFLINE,
     * AUTH, INCOMPLETE and NONE_OWN send nothing at all (INCOMPLETE is a load whose calendars could not all be told
     * apart as the account's own, [Kind.INCOMPLETE]); OFFLINE_SAVE and REFUSE only the load's reads; TIMEOUT_SAVE adds
     * the one check that follows an answer taken as lost.
     */
    enum class Fixture { NONE, OFFLINE, OFFLINE_SAVE, TIMEOUT_SAVE, REFUSE, AUTH, INCOMPLETE, NONE_OWN }

    /**
     * Why a load failed: Proton gave no usable answer, or no address of the account loaded, also after one more read
     * (the address read fails without failing the key unlock, so the calendar list may come while the addresses did
     * not), and another try may work; the account must sign in again; or Proton answered and addresses loaded, but
     * which calendars are the account's own could not be told for all of them (a personal calendar lists members on
     * none of the addresses whose keys loaded, also after one more read), so no list is offered rather than a partial
     * one.
     */
    enum class Kind { UNREACHABLE, AUTH, INCOMPLETE }

    sealed interface Load {
        /** The list to show. */
        data class Ready(val offer: DefaultCalendarChoices.Offer) : Load

        /** No calendar of the account can be the default. */
        data object NoOwnCalendar : Load

        data class Failed(val kind: Kind, val detail: String) : Load
    }

    /** The calendar list, or why the load ends without it. */
    private sealed interface Listed {
        class Ok(val calendars: List<CalendarInfo>) : Listed
        class Failed(val load: Load.Failed) : Listed
    }

    /**
     * Reads what the list offers: the keys first, so our own member can be found by address, then `GET calendar/v1`,
     * then `GET settings/calendar`, only when a calendar can be the default. Sends no change to Proton. When the
     * phone's record or its rows differ from where Proton's default puts them, the phone is brought in line at once
     * ([bringInLineIfNeeded]), by the rules of a pick ([PrimaryCalendarPicks.planned]). Never throws but for a
     * cancellation.
     */
    suspend fun load(context: Context, account: Account, fixture: Fixture = Fixture.NONE): Load = try {
        loadChecked(context, account, fixture)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(account.name, Kind.UNREACHABLE, e.javaClass.simpleName)
    }

    private suspend fun loadChecked(context: Context, account: Account, fixture: Fixture): Load {
        val name = account.name
        when (fixture) {
            Fixture.OFFLINE -> return failed(name, Kind.UNREACHABLE, "fixture offline")
            Fixture.AUTH -> return failed(name, Kind.AUTH, "fixture auth")
            Fixture.INCOMPLETE -> return failed(name, Kind.INCOMPLETE, "fixture incomplete")
            Fixture.NONE_OWN -> {
                SyncLog.i(TAG, "Default calendar for '$name': fixture none-own, so no calendar is offered and nothing is asked")
                return Load.NoOwnCalendar
            }
            else -> Unit
        }
        unlock(account)?.let { return it }
        val calendars = when (val listed = listCalendars(account)) {
            is Listed.Ok -> listed.calendars
            is Listed.Failed -> return listed.load
        }
        var addresses = keyManager.getAddressEmails(name)
        var shapeRead = DefaultCalendarChoices.shape(calendars, addresses)
        if (shapeRead is DefaultCalendarChoices.Shape.Unknown) {
            // Our own member is found by address: read the addresses once more, as the sync does on the same evidence.
            SyncUtils.invalidateKeyCache(name)
            unlock(account)?.let { return it }
            addresses = keyManager.getAddressEmails(name)
            shapeRead = DefaultCalendarChoices.shape(calendars, addresses)
        }
        val shape = shapeRead
        val candidates = when (shape) {
            // No address at all is a failed address read (or no address key unlocked), not calendars that cannot be
            // told apart: it fails as unreachable, so the toast asks for another try.
            is DefaultCalendarChoices.Shape.Unknown -> return if (shape.noAddressLoaded) {
                failed(
                    name, Kind.UNREACHABLE,
                    "no address of the account loaded, also after a second read (the address read failed, or none of " +
                        "its keys unlocked)"
                )
            } else {
                failed(name, Kind.INCOMPLETE, "no member of ours found (${shape.detail})")
            }
            is DefaultCalendarChoices.Shape.NoOwnCalendar -> {
                SyncLog.i(
                    TAG,
                    "Default calendar for '$name': no active calendar of its own can be the default, so nothing is " +
                        "offered and settings/calendar is not read; left out: ${DefaultCalendarChoices.leftOutText(shape.leftOut)}"
                )
                return Load.NoOwnCalendar
            }
            is DefaultCalendarChoices.Shape.Candidates -> shape
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (me.proton.sync.BuildConfig.DEBUG &&
            prefs.getString(SyncPreferences.debugDefaultCalendarFixtureKey(name), null) != null
        ) {
            SyncLog.w(TAG, "default-calendar-fixture is set for '$name'; the picker reads Proton")
        }
        val settings = when (val read = CalendarUserSettingsFetch.fetch(apiClient, name)) {
            is PrimaryCalendarChooser.Settings.Loaded -> read
            is PrimaryCalendarChooser.Settings.Failed -> return failed(
                name,
                if (DefaultCalendarChoices.needsSignIn(read)) Kind.AUTH else Kind.UNREACHABLE,
                "settings/calendar: ${read.detail}"
            )
        }

        val mappings = try {
            calendarSyncDao.getAllByAccount(name).associateBy { it.protonCalendarId }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncLog.w(TAG, "Default calendar for '$name': this account's calendar mappings could not be read (${e.javaClass.simpleName}); the phone is not brought in line")
            null
        }
        val offer = DefaultCalendarChoices.offer(candidates, calendars, addresses, settings, mappings.orEmpty(), name)
            ?: return failed(name, Kind.UNREACHABLE, "the calendars could not be decided on")
        // Calendar names only with Verbose logging, else the calendars' ids (LogNames).
        SyncLog.i(
            TAG,
            "Default calendar for '$name': Proton stores ${idText(offer.storedId)}, uses ${shown(offer.checked)} " +
                "(${offer.decision.reason.text}); offers ${offer.choices.size}: " +
                offer.choices.withIndex().joinToString { (i, c) ->
                    shown(c) + " (" + (c.androidCalId?.let { "row $it" } ?: "not on this phone") +
                        (if (i == offer.checkedIndex) ", checked" else "") + ")"
                } + "; left out: ${DefaultCalendarChoices.leftOutText(offer.leftOut)}"
        )
        if (mappings != null) bringInLineIfNeeded(context, account, offer, mappings.values.map { it.protonCalendarId to it.androidCalendarId })
        return Load.Ready(offer)
    }

    /**
     * After a fresh read: when the phone's record names another calendar than Proton uses, or the rows as stored (three
     * provider reads) are not where a bring-in-line would put them, such as two rows that store 1 or a record that
     * names another row, the phone takes Proton's default now ([PrimaryCalendarPicks.needsBringInLine]). Sends nothing.
     */
    private suspend fun bringInLineIfNeeded(
        context: Context,
        account: Account,
        offer: DefaultCalendarChoices.Offer,
        mappings: List<Pair<String, Long?>>,
    ) {
        val name = account.name
        val checked = offer.checked
        val record = PrimaryCalendarMemory.decode(
            PreferenceManager.getDefaultSharedPreferences(context).getString(SyncPreferences.primaryCalendarLastKey(name), null)
        )
        val chosen = PrimaryCalendarRows.locate(checked.protonCalendarId, mappings, offer.liveProtonIds)
        val wouldDo = PrimaryCalendarPicks.wouldApply(context, account, chosen)
        if (!PrimaryCalendarPicks.needsBringInLine(record, checked.protonCalendarId, wouldDo)) return
        val applied = bringInLine(context, account, checked, offer.decision.reason.name, offer.liveProtonIds)
        // The record keeps the calendar's id only; a name only with Verbose logging (LogNames).
        val why = when {
            record?.protonCalendarId == checked.protonCalendarId ->
                "the phone's calendar rows were not where Proton's default ${shown(checked)} puts them"
            record == null -> "the phone had nothing, Proton uses ${shown(checked)}"
            record.protonCalendarId == null -> "the phone had none, Proton uses ${shown(checked)}"
            else -> "the phone had ${LogNames.calendar(record.name, idText(record.protonCalendarId))}, Proton uses ${shown(checked)}"
        }
        SyncLog.i(TAG, "Default calendar for '$name': $why; brought in line (${PrimaryCalendarPicks.describe(applied)})")
    }

    /**
     * Makes [choice] the account's default on Proton (PUT settings/calendar, at most [CalendarUserSettingsFetch.TIMEOUT_MS]),
     * then on the phone at once ([bringInLine], reason PICKED_HERE), with no sync. An answer that may have been lost
     * after the PUT went out is checked with one GET ([DefaultCalendarChoices.finishSave]). When Proton confirms a
     * calendar that has no row on this phone yet, the calendar sync that brings it down is asked for
     * (SyncScheduling.requestSettingsSync: an ordinary request, so none while Android's automatic sync is off; the
     * phone keeps its current main calendar until that sync marks the new one). Nothing changes on the phone on any
     * other outcome; one whose PUT went out unconfirmed is logged as not confirmed rather than as not changed, since
     * Proton may have it. [offer] is the list the choice came from. Never throws but for a cancellation.
     */
    suspend fun save(
        context: Context,
        account: Account,
        offer: DefaultCalendarChoices.Offer,
        choice: DefaultCalendarChoices.Choice,
        fixture: Fixture = Fixture.NONE,
    ): SaveOutcome {
        val name = account.name
        val previous = offer.checked
        val first = when (fixture) {
            Fixture.OFFLINE_SAVE -> SaveOutcome.Unreachable("fixture offline-save")
            Fixture.REFUSE -> SaveOutcome.Refused(422, 2001)
            Fixture.TIMEOUT_SAVE -> SaveOutcome.Uncertain("fixture timeout-save")
            else -> put(name, choice.protonCalendarId)
        }
        var applied: PrimaryCalendarPicks.Applied? = null
        val outcome = DefaultCalendarChoices.finishSave(
            first = first,
            chosenId = choice.protonCalendarId,
            verify = { CalendarUserSettingsFetch.fetch(apiClient, name, CalendarUserSettingsFetch.VERIFY_TIMEOUT_MS) },
            applyLocally = {
                applied = bringInLine(context, account, choice, PrimaryCalendarChooser.Reason.PICKED_HERE.name, offer.liveProtonIds)
            },
            onApplyFailed = { e ->
                SyncLog.w(
                    USER_ACTION,
                    "Default calendar for '$name': Proton has ${shown(choice)}, but the phone was not updated " +
                        "(${e.javaClass.simpleName}); the next calendar sync sets it"
                )
            },
        )
        val fixtureNote = if (fixture == Fixture.NONE) "" else " (fixture ${fixture.name.lowercase().replace('_', '-')})"
        val done = applied
        // Calendar names only with Verbose logging, else the calendars' ids (LogNames).
        if (outcome is SaveOutcome.Saved) {
            SyncLog.i(
                USER_ACTION,
                "Default calendar for '$name': ${shown(choice)} accepted by Proton (was ${shown(previous)}); phone: " +
                    (done?.let { PrimaryCalendarPicks.describe(it) } ?: "not updated") + fixtureNote
            )
            if (done != null && done.noRow in NOT_FOLLOWED) {
                SyncLog.w(
                    USER_ACTION,
                    "Default calendar for '$name': Proton has ${shown(choice)}, but this phone's calendar rows were not " +
                        "changed (${PrimaryCalendarPicks.describe(done)})"
                )
            }
            if (done?.noRow == PrimaryCalendarPicks.NoRow.NOT_ON_PHONE) {
                // Its calendar reaches the phone with the next calendar sync, which marks it primary.
                SyncScheduling.requestSettingsSync(context, account, "default calendar")
            }
        } else if (outcome is SaveOutcome.Unreachable && outcome.mayHaveArrived) {
            // The PUT went out and nothing confirmed it: "not changed" could be false. The phone stays as it was.
            SyncLog.w(
                USER_ACTION,
                "Default calendar for '$name': change to ${shown(choice)} not confirmed (${outcome.detail}); Proton may " +
                    "have it, and the next opening of the list or calendar sync shows what it has$fixtureNote"
            )
        } else {
            SyncLog.w(USER_ACTION, "Default calendar for '$name' not changed: ${failureText(outcome)}$fixtureNote")
        }
        return outcome
    }

    /**
     * The phone made to follow [choice]: where its calendar sits is read fresh, so a row created since the list was
     * loaded is found, and [PrimaryCalendarPicks.applyOutsideSync] writes the rows and the record. [liveProtonIds] are
     * the calendars Proton listed with the list. Only after a fresh read of Proton: the load's, or a change Proton has
     * just confirmed. Never throws but for a cancellation.
     */
    private suspend fun bringInLine(
        context: Context,
        account: Account,
        choice: DefaultCalendarChoices.Choice,
        reason: String,
        liveProtonIds: Set<String>,
    ): PrimaryCalendarPicks.Applied {
        val mappings = try {
            calendarSyncDao.getAllByAccount(account.name).map { it.protonCalendarId to it.androidCalendarId }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncLog.w(TAG, "Default calendar for '${account.name}': this account's calendar mappings could not be read (${e.javaClass.simpleName})")
            null
        }
        val chosen = PrimaryCalendarRows.locate(choice.protonCalendarId, mappings, liveProtonIds)
        val applied = PrimaryCalendarPicks.applyOutsideSync(context, account, choice.protonCalendarId, choice.name, reason, chosen)
        // Outside the picks' lock, which applyOutsideSync has released: only when a row's flag changed, since Move's
        // target is the row that stores 1.
        if (applied.writes.isNotEmpty()) afterPrimaryRowsMoved(context, account)
        return applied
    }

    /** The PUT, at most [CalendarUserSettingsFetch.TIMEOUT_MS], and what its answer means ([DefaultCalendarChoices.send]). */
    private suspend fun put(accountName: String, chosenId: String): SaveOutcome =
        DefaultCalendarChoices.send(CalendarUserSettingsFetch.TIMEOUT_MS, chosenId) {
            apiClient.updateCalendarUserSettings(accountName, UpdateCalendarUserSettingsRequest(chosenId))
        }

    /** `GET calendar/v1`: a 401 needs a new sign-in; no answer, an exception, any other error or no list is Unreachable. */
    private suspend fun listCalendars(account: Account): Listed {
        val name = account.name
        val response = try {
            withTimeoutOrNull(CalendarUserSettingsFetch.TIMEOUT_MS) { apiClient.getCalendars(name) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Listed.Failed(failed(name, Kind.UNREACHABLE, "calendar list: ${e.javaClass.simpleName}"))
        }
        if (response == null) {
            return Listed.Failed(
                failed(name, Kind.UNREACHABLE, "calendar list: no answer within ${CalendarUserSettingsFetch.TIMEOUT_MS / 1000} s")
            )
        }
        val http = response.code()
        val calendars = response.body()?.calendars
        return when {
            http == 401 -> Listed.Failed(failed(name, Kind.AUTH, "calendar list: HTTP 401${response.protonErrorSuffix()}"))
            !response.isSuccessful || calendars == null ->
                Listed.Failed(failed(name, Kind.UNREACHABLE, "calendar list: HTTP $http${response.protonErrorSuffix()}"))
            else -> Listed.Ok(calendars)
        }
    }

    /** The keys unlocked, so our own member is found by address; null when they are, else why the load ends. */
    private fun unlock(account: Account): Load.Failed? = try {
        SyncUtils.ensureKeysUnlocked(account, sessionManager.getSession(account), keyManager, apiClient, TAG)
        null
    } catch (e: SyncUtils.KeysNotUnlockedException) {
        failed(account.name, Kind.AUTH, "the keys could not be unlocked")
    } catch (e: Exception) {
        failed(account.name, Kind.UNREACHABLE, "the keys could not be unlocked (${e.javaClass.simpleName})")
    }

    private fun failed(accountName: String, kind: Kind, detail: String): Load.Failed {
        SyncLog.w(
            TAG,
            "Default calendar for '$accountName': not loaded, $detail" + when (kind) {
                Kind.AUTH -> "; the account must sign in again"
                Kind.INCOMPLETE -> "; no list is offered rather than one without some of the account's calendars"
                Kind.UNREACHABLE -> ""
            }
        )
        return Load.Failed(kind, detail)
    }

    private fun failureText(outcome: SaveOutcome): String = when (outcome) {
        is SaveOutcome.Refused -> "refused (HTTP ${outcome.http}" +
            outcome.suffix.ifEmpty { outcome.code?.let { " (Code $it)" }.orEmpty() } + ")"
        SaveOutcome.Auth -> "the session needs a new sign-in (HTTP 401)"
        is SaveOutcome.Unreachable -> "no usable answer from Proton (${outcome.detail})"
        is SaveOutcome.Uncertain -> "no answer (${outcome.detail})"
        SaveOutcome.Saved -> "saved"
    }

    private fun idText(id: String?): String = id?.let { it.take(8) + "…" } ?: "not set"

    /** [choice] for a log line: its name only while Verbose logging is on, else its Proton id cut as [idText] cuts it. */
    private fun shown(choice: DefaultCalendarChoices.Choice): String = LogNames.calendar(choice.name, idText(choice.protonCalendarId))

    private companion object {
        const val TAG = "SyncSettings"
        const val USER_ACTION = "UserAction"

        /** A confirmed change the phone's rows could not follow, which a W line reports. */
        val NOT_FOLLOWED = setOf(
            PrimaryCalendarPicks.NoRow.WRITE_FAILED, PrimaryCalendarPicks.NoRow.NO_PERMISSION,
            PrimaryCalendarPicks.NoRow.MAPPINGS_UNREADABLE,
        )
    }
}
