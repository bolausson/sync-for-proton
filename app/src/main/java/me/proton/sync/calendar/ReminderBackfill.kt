package me.proton.sync.calendar

/**
 * Whether an unchanged event is missing its reminder rows and may be given them, going by the snapshot
 * of the Reminders table a run takes once per calendar ([withReminders]). Pure, so it can be tested
 * without a ContentProviderClient; the caller checks that there are reminders to give at all.
 *
 * Null is a snapshot that could not be taken, and then no event is backfilled. The failed read used to
 * come back as an empty set, which reads as "no event has reminders": the backfill then rewrote the
 * reminder rows of every unchanged event without unsent edits that Proton gives reminders, on every
 * calendar the run fetched. Skipping costs nothing that lasts: the next run that fetches the calendar
 * goes through every event again and backfills the ones still missing their rows.
 *
 * Never for an event with unsent edits ([locallyDirty]): "no reminder rows" is also what "the user just
 * deleted every reminder" looks like, and the download runs before the upload (see finishUnchangedEvent).
 */
internal fun needsReminderBackfill(localId: Long, withReminders: Set<Long>?, locallyDirty: Set<Long>): Boolean =
    withReminders != null && localId !in withReminders && localId !in locallyDirty
