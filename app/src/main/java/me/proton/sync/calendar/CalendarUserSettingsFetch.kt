package me.proton.sync.calendar

import kotlinx.coroutines.withTimeoutOrNull
import me.proton.sync.api.ProtonApiClient
import me.proton.sync.api.protonErrorSuffix

/**
 * `GET settings/calendar` as the primary-calendar choice reads it ([PrimaryCalendarChooser.Settings]): for the
 * calendar sync (CalendarSyncAdapter.fetchCalendarUserSettings, which answers from the debug fixture first) and for
 * the account card's Default calendar row (DefaultCalendarService), which always asks Proton. Read-only, and it never
 * throws, so a caller costs one request at most.
 */
internal object CalendarUserSettingsFetch {

    /**
     * The most the read may take, a token refresh included: one request's timeout (the client's read timeout is 30 s
     * too). Past it the calendar sync goes on without the answer and leaves the primary flag as it is. Also the cap of
     * each of the Default calendar row's own requests (the calendar list, this read, the change). Not of the key unlock
     * the row's load runs first: SyncUtils.ensureKeysUnlocked blocks on the account's unlock lock and on its address
     * read, which only the client's own timeouts end and a cancellation cannot stop, so the row's Cancel frees the row
     * at once instead of waiting for it (DefaultCalendarPicker).
     */
    const val TIMEOUT_MS = 30_000L

    /** The Default calendar row's one check after its change got an answer that may have been lost. */
    const val VERIFY_TIMEOUT_MS = 15_000L

    /**
     * Loaded with DefaultCalendarID (which may be null), or Failed with the reason: HTTP status and Code, Code other
     * than 1000, no settings object, a parse or network error, or no answer within [timeoutMs]. Every Failed built from
     * an HTTP answer carries its status, so a caller can tell a 401 from the rest.
     */
    suspend fun fetch(
        apiClient: ProtonApiClient,
        accountName: String,
        timeoutMs: Long = TIMEOUT_MS
    ): PrimaryCalendarChooser.Settings = try {
        withTimeoutOrNull(timeoutMs) {
            val response = apiClient.getCalendarUserSettings(accountName)
            val http = response.code()
            val body = response.body()
            val settings = body?.settings
            when {
                !response.isSuccessful ->
                    PrimaryCalendarChooser.Settings.Failed("HTTP $http${response.protonErrorSuffix()}", http)
                body == null -> PrimaryCalendarChooser.Settings.Failed("HTTP $http, empty body", http)
                body.code != 1000 -> PrimaryCalendarChooser.Settings.Failed("HTTP $http, Code ${body.code}", http)
                settings == null -> PrimaryCalendarChooser.Settings.Failed("HTTP $http, no CalendarUserSettings", http)
                else -> PrimaryCalendarChooser.Settings.Loaded(settings.defaultCalendarId)
            }
        } ?: PrimaryCalendarChooser.Settings.Failed("no answer within ${timeoutMs / 1000} s")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        PrimaryCalendarChooser.Settings.Failed(e.javaClass.simpleName)
    }
}
