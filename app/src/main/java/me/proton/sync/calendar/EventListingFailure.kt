package me.proton.sync.calendar

/**
 * The reason a calendar whose event listing failed gives its run (CalendarSyncAdapter: fetchEventsFlat and
 * fetchEventsDense return null, and syncCalendarEvents counts the run as failed). The sync of that calendar stops there,
 * before anything of it is written, because a partial listing would take the events it missed for deleted on Proton.
 * Until 2.2.0 it stopped without counting a failure or recording a reason, so the run reported success although that
 * calendar downloaded nothing, and with Android's automatic sync off nothing healed it until the user tapped Sync.
 * Pure, so it is tested without a device.
 */
internal object EventListingFailure {

    /**
     * The text recorded for calendar [calendarId] (SyncUtils.recordSwallowedFailure), with Proton's HTTP status
     * [httpCode] where Proton answered: the status picks the text the failure shows (the server error, too many requests,
     * an expired session, a rejected request: SyncNotificationHelper.friendlyMessageRes). An exception's network cause is
     * added by the recording itself (NetworkCause.describe). The calendar is named by the start of its id, never by its
     * name: a name can hold the very words the text is read by, and the text also goes to the log.
     */
    fun reason(calendarId: String, httpCode: Int?): String =
        "The events of calendar '${calendarId.take(8)}' could not be listed, so none of them were downloaded" +
            (if (httpCode != null) ": HTTP $httpCode" else "")
}
