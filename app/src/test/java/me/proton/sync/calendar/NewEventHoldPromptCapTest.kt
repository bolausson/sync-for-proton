package me.proton.sync.calendar

import me.proton.sync.calendar.NewEventHoldRules.Entry
import me.proton.sync.calendar.NewEventHoldRules.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cap on the prompts about new events held in shared calendars (owner, 2026-11-21): at most five waiting series of
 * an account get a notification of their own, and one summary counts the rest. Android refuses every new notification
 * of an app that already has 50 up, so one prompt per series silenced the app's other prompts once about 50 series
 * waited. The rule is pure ([NewEventHoldRules.promptPlan]); its wiring is pinned in the app's own sources, with their
 * comments taken out, so a comment never satisfies a check. Made-up ids and packages only.
 */
class NewEventHoldPromptCapTest {

    private companion object {
        const val SHARED = 7L
        const val T0 = 1_800_000_000_000L
    }

    private fun entry(id: Long, heldAt: Long) = Entry(State.WAITING, id, SHARED, heldAt, "com.example.planner", "uid-$id")

    private fun ids(entries: List<Entry>) = entries.map { it.eventId }

    // ---- The rule ----

    @Test
    fun `five prompts at most, and one summary for the rest`() {
        assertEquals(5, NewEventHoldRules.MAX_PROMPTS)
        for (n in 0..5) {
            val waiting = (1..n).map { entry(it.toLong(), T0 + it) }
            val plan = NewEventHoldRules.promptPlan(waiting, alertId = null)
            assertEquals("$n waiting", ids(waiting), ids(plan.shown))
            assertTrue("$n waiting: no summary", plan.rest.isEmpty())
        }
        // The finding's case: a planner app writes 120 shifts into a shared calendar, held in one run.
        val bulk = (1..120).map { entry(1000L + it, T0) }
        val plan = NewEventHoldRules.promptPlan(bulk, alertId = 1001L)
        assertEquals(5, plan.shown.size)
        assertEquals(115, plan.rest.size)
        assertEquals(bulk.toSet(), (plan.shown + plan.rest).toSet())
    }

    @Test
    fun `the alerting series first, then the newest, and one run's series in the order of their rows`() {
        val earlier = (1..4).map { entry(it.toLong(), T0 + it) }
        val thisRun = (11..18).map { entry(it.toLong(), T0 + 100) }
        val waiting = earlier + thisRun
        // This run's series are the newest; among them the lowest row ids, the first the other app wrote.
        assertEquals(listOf(11L, 12L, 13L, 14L, 15L), ids(NewEventHoldRules.promptPlan(waiting, alertId = 11L).shown))
        // A refresh without an alert (the screen's, an answer's) keeps the same five up.
        assertEquals(listOf(11L, 12L, 13L, 14L, 15L), ids(NewEventHoldRules.promptPlan(waiting, alertId = null).shown))
        // An alerting series held long ago (sent back to waiting in this run) still gets its prompt.
        assertEquals(listOf(2L, 11L, 12L, 13L, 14L), ids(NewEventHoldRules.promptPlan(waiting, alertId = 2L).shown))
        // An alert id that names no waiting series changes nothing.
        assertEquals(listOf(11L, 12L, 13L, 14L, 15L), ids(NewEventHoldRules.promptPlan(waiting, alertId = 99L).shown))
        // Among earlier runs, the newest first; both lists keep the order they were given in.
        val plan = NewEventHoldRules.promptPlan(earlier, alertId = null, max = 3)
        assertEquals(listOf(2L, 3L, 4L), ids(plan.shown))
        assertEquals(listOf(1L), ids(plan.rest))
    }

    @Test
    fun `the prompts and the summary follow the count as series are answered or held`() {
        var waiting = (1..7).map { entry(it.toLong(), T0 + it) }
        var plan = NewEventHoldRules.promptPlan(waiting, alertId = null)
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L), ids(plan.shown))
        assertEquals(listOf(1L, 2L), ids(plan.rest))
        // The newest is answered on its prompt: the next one takes its place, the summary counts one.
        waiting = waiting.filter { it.eventId != 7L }
        plan = NewEventHoldRules.promptPlan(waiting, alertId = null)
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), ids(plan.shown))
        assertEquals(listOf(1L), ids(plan.rest))
        // The oldest is answered in the summary's walk: five left, five prompts, and the summary goes.
        waiting = waiting.filter { it.eventId != 1L }
        plan = NewEventHoldRules.promptPlan(waiting, alertId = null)
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), ids(plan.shown))
        assertTrue(plan.rest.isEmpty())
        // A new series held while five wait gets its prompt, alerting; the oldest moves to the summary.
        plan = NewEventHoldRules.promptPlan(waiting + entry(20L, T0 + 50), alertId = 20L)
        assertEquals(listOf(3L, 4L, 5L, 6L, 20L), ids(plan.shown))
        assertEquals(listOf(2L), ids(plan.rest))
    }

    @Test
    fun `no series is lost or counted twice, whatever the limit`() {
        val waiting = (1..12).map { entry(it.toLong(), T0 + (it % 4)) }
        for (max in listOf(-1, 0, 1, 5, 12, 40)) {
            val plan = NewEventHoldRules.promptPlan(waiting, alertId = 6L, max = max)
            assertEquals("max $max", max.coerceIn(0, waiting.size), plan.shown.size)
            assertEquals("max $max", waiting.size, plan.shown.size + plan.rest.size)
            assertTrue("max $max", plan.shown.none { it in plan.rest })
            assertEquals("max $max", waiting.filter { it in plan.shown }, plan.shown)
            assertEquals("max $max", waiting.filter { it in plan.rest }, plan.rest)
            if (max >= 1) assertTrue("max $max: the alerting series has its prompt", plan.shown.any { it.eventId == 6L })
        }
    }

    // ---- The wiring, in the app's sources ----

    private fun source(path: String) = File("src/main/$path").readText().replace("\r\n", "\n")

    /** [text] without KDoc, block comment lines and line comments. */
    private fun code(text: String): String =
        text.lines().filterNot { it.trim().let { t -> t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") } }
            .joinToString("\n") { it.substringBefore(" // ") }

    private fun squash(text: String) = text.replace(Regex("\\s+"), " ")

    /** The text of the member function that starts with [signature], up to its closing brace. */
    private fun body(src: String, signature: String): String {
        assertTrue("$signature not found", src.contains(signature))
        return src.substringAfter(signature).substringBefore("\n    }\n")
    }

    private val holds by lazy { code(source("java/me/proton/sync/calendar/NewEventHolds.kt")) }

    @Test
    fun `a refresh takes down what is past the limit before it posts, one refresh at a time, and the summary first`() {
        val refresh = squash(body(holds, "fun refreshPrompts("))
        assertTrue(refresh, refresh.contains("synchronized(promptLock) { postPrompts(context, provider, account, mappedCalendarIds, alertId) }"))
        val post = squash(body(holds, "private fun postPrompts("))
        val read = post.indexOf("val onScreen = SyncNotificationHelper.activeNotificationIds(context)")
        val plan = post.indexOf("val plan = NewEventHoldRules.promptPlan(prompts.map { it.entry }, alertId)")
        val down = post.indexOf("for (e in waiting) if (e !in chosen) takeDownPrompt(context, account, e, onScreen)")
        val skip = post.indexOf("if (p.entry !in chosen) continue")
        val posted = post.indexOf("SyncNotificationHelper.showNewEventHoldPrompt(")
        val summary = post.indexOf("showOrCancelSummary(context, account, plan.rest)")
        // The summary before the prompts: Android drops an app's updates of notifications already up while it posts more
        // than about five a second, and a refresh posts its prompts again, so the count goes first in each refresh.
        assertTrue(post, read >= 0 && plan > read && down > plan && summary > down && skip > summary && posted > skip)
        assertEquals(post, 1, Regex(Regex.escape("showOrCancelSummary(")).findAll(post).count())
        assertTrue(post, post.contains("if (waiting.isEmpty()) { SyncNotificationHelper.cancelNewEventHoldSummary(context, account.name) return }"))
        // A look at the screen that fails takes every prompt that may be up down, never none.
        val takeDown = squash(body(holds, "private fun takeDownPrompt("))
        assertTrue(takeDown, takeDown.contains("if (onScreen == null || SyncNotificationHelper.newEventHoldNotificationId(account.name, entry.eventId) in onScreen) {"))
        val show = squash(body(holds, "private fun showOrCancelSummary("))
        assertTrue(show, show.contains("if (rest.isEmpty()) { SyncNotificationHelper.cancelNewEventHoldSummary(context, account.name) return }"))
        assertTrue(show, show.contains("SyncNotificationHelper.showNewEventHoldSummary(context, account.name, rest.size, rest.maxOf { it.heldAtMillis })"))
        // A sweep that leaves nothing waiting takes the summary down: an empty store asks for no refresh after it.
        val sweep = squash(body(holds, "fun sweep("))
        assertTrue(sweep, sweep.contains("if (left.waiting == 0) SyncNotificationHelper.cancelNewEventHoldSummary(context, account.name)"))
    }

    @Test
    fun `a refresh checks Move only for the series that get a prompt of their own`() {
        // Move's check reads the rows of each series (one query per series with a UID). With 120 series waiting, a refresh
        // posts five prompts: only their series are read, by the very plan the refresh then posts by.
        val post = squash(body(holds, "private fun postPrompts("))
        val drawn = post.indexOf(
            "val prompts = prompts(context, provider, account, mappedCalendarIds, waiting) { drawn -> NewEventHoldRules.promptPlan(drawn, alertId).shown }"
        )
        val plan = post.indexOf("val plan = NewEventHoldRules.promptPlan(prompts.map { it.entry }, alertId)")
        assertTrue(post, drawn >= 0 && plan > drawn)
        assertEquals(post, 2, Regex(Regex.escape("NewEventHoldRules.promptPlan(")).findAll(post).count())
        val prompts = squash(body(holds, "fun prompts("))
        assertTrue(prompts, prompts.contains("return drawn.map { e ->"))
        assertTrue(prompts, prompts.contains("exceptionCalendars(provider, account, checked)"))
        assertEquals(prompts, 1, Regex(Regex.escape("exceptionCalendars(")).findAll(prompts).count())
        // The dialog keeps the check for the one entry it shows.
        val dialog = squash(code(source("java/me/proton/sync/settings/NewEventHoldDialog.kt")))
        assertTrue(dialog, dialog.contains("NewEventHolds.prompts(context, client, account, mappedCalendarIds, listOf(entry))?.firstOrNull()"))
    }

    @Test
    fun `a prompt the last refresh posted comes down past the limit, also before Android lists it`() {
        // Android adds a posted notification to the list it gives a moment after notify() returns, so the next refresh
        // counts the prompts the last one posted as on screen too; a look that fails still takes every one down.
        val post = squash(body(holds, "private fun postPrompts("))
        assertTrue(post, post.contains("val onScreen = SyncNotificationHelper.activeNotificationIds(context)?.let { it + postedPromptIds[account.name].orEmpty() }"))
        val read = post.indexOf("val onScreen = ")
        val settled = post.indexOf("for (e in all) if (e.state != State.WAITING) takeDownPrompt(context, account, e, onScreen)")
        val loop = post.indexOf("for (p in prompts) {")
        val shown = post.indexOf(
            "if (SyncNotificationHelper.showNewEventHoldPrompt(context, account.name, p, alert = p.entry.eventId == alertId)) { " +
                "shown++ posted += SyncNotificationHelper.newEventHoldNotificationId(account.name, p.entry.eventId) }"
        )
        val stored = post.indexOf("postedPromptIds[account.name] = posted")
        assertTrue(post, read >= 0 && settled > read && loop > settled && shown > loop && stored > shown)
        // Kept in this object, and only the locked refresh reads or writes it.
        val uses = holds.lines().filter { it.contains("postedPromptIds") }
        assertEquals(uses.joinToString("\n"), 3, uses.size)
        assertTrue(uses[0], uses[0].contains("private val postedPromptIds = HashMap<String, Set<Int>>()"))
        assertEquals(post, 2, Regex(Regex.escape("postedPromptIds")).findAll(post).count())
    }

    @Test
    fun `only the locked refresh posts a prompt or the summary, so nothing else can pass the limit`() {
        val files = listOf("main", "direct", "play", "debug").map { File("src/$it/java") }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("the app's sources are where the test runs (${files.size} files)", files.size > 100)
        for (call in listOf("showNewEventHoldPrompt(", "showNewEventHoldSummary(")) {
            val callers = files.filter { f -> code(f.readText()).lines().any { it.contains(call) && !it.contains("fun $call") } }
            assertEquals(call, listOf("NewEventHolds.kt"), callers.map { it.name })
            assertEquals(call, 1, Regex(Regex.escape("SyncNotificationHelper.$call")).findAll(holds).count())
        }
        // postPrompts runs only under the lock, and the summary is posted only from it.
        assertEquals(1, holds.lines().count { it.contains("postPrompts(") && !it.contains("fun postPrompts(") })
        assertEquals(1, holds.lines().count { it.contains("showOrCancelSummary(") && !it.contains("fun showOrCancelSummary(") })
        assertTrue(squash(body(holds, "private fun postPrompts(")).contains("showOrCancelSummary(context, account, plan.rest)"))
    }

    @Test
    fun `the summary has no buttons, never alerts, opens the walk, and a locked screen shows its title only`() {
        val helper = source("java/me/proton/sync/SyncNotificationHelper.kt")
        val summary = squash(code(body(helper, "internal fun showNewEventHoldSummary(")))
        assertFalse(summary, summary.contains("addAction("))
        assertFalse(summary, summary.contains("Action.Builder("))
        assertTrue(summary, summary.contains(".setSilent(true)"))
        assertTrue(summary, summary.contains(".setOnlyAlertOnce(true)"))
        assertTrue(summary, summary.contains("NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)"))
        val intents = Regex("""PendingIntent\.get(Broadcast|Activity|Service|ForegroundService)\(""").findAll(summary).count()
        assertEquals(summary, 1, intents)
        assertTrue(summary, summary.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(summary, summary.contains("putExtra(me.proton.sync.calendar.NewEventHolds.EXTRA_WALK, true)"))
        assertFalse(summary, summary.contains("EXTRA_EVENT"))
        assertTrue(summary, summary.contains(".setVisibility(NotificationCompat.VISIBILITY_PRIVATE)"))
        assertTrue(summary, summary.contains(".setPublicVersion(publicVersion)"))
        val publicVersion = summary.substringAfter("val publicVersion = ").substringBefore(".build()")
        assertFalse(publicVersion, publicVersion.contains("setContentText") || publicVersion.contains("body"))
        assertTrue(summary, summary.contains("Plurals.text(context, R.plurals.notification_new_event_hold_more, count, count)"))
        // Its own notification id, apart from every prompt's.
        assertTrue(squash(code(helper)).contains("fun newEventHoldSummaryId(accountName: String): Int = (\"new_event_hold_more_\$accountName\").hashCode()"))
    }

    @Test
    fun `the summary's tap opens the card's walk, which a full re-sync's preparation refuses as the row does`() {
        val activity = source("java/me/proton/sync/settings/SyncSettingsActivity.kt")
        val handle = squash(code(body(activity, "private fun handleNewEventHoldIntent(")))
        val removed = handle.indexOf("intent.removeExtra(me.proton.sync.calendar.NewEventHolds.EXTRA_WALK)")
        val walk = handle.indexOf("if (walk && !restored && !fromHistory) { openNewEventHoldWalk(name, fromCreate) return }")
        assertTrue(handle, removed >= 0 && walk > removed)
        val open = squash(code(body(activity, "private fun openNewEventHoldWalk(")))
        val refused = open.indexOf("if (account.name in fullResyncInFlightAccounts) {")
        val shown = open.indexOf("newEventHoldDialog.show(account, walk = NewEventHoldDialog.Walk.WAITING) { refreshNewEventHolds(account) }")
        assertTrue(open, refused >= 0 && shown > refused)
        assertTrue(open, open.contains("getString(R.string.toast_resync_in_flight)"))
        assertTrue(open, open.contains("if (fromCreate) openedForNewEventHold = true"))
        assertFalse(open, open.contains("press"))
        // The very call the card's waiting row makes.
        val call = "newEventHoldDialog.show(account, walk = NewEventHoldDialog.Walk.WAITING) { refreshNewEventHolds(account) }"
        assertEquals(2, Regex(Regex.escape(call)).findAll(squash(code(activity))).count())
    }

    @Test
    fun `an account renamed or removed takes its summary down with its prompts`() {
        val app = squash(code(source("java/me/proton/sync/ProtonSyncApp.kt")))
        assertEquals(2, Regex(Regex.escape("SyncNotificationHelper.cancelNewEventHoldSummary(")).findAll(app).count())
        assertTrue(app.contains("SyncNotificationHelper.cancelNewEventHoldSummary(this, oldParent.name)"))
        assertTrue(app.contains("SyncNotificationHelper.cancelNewEventHoldSummary(this, name)"))
    }
}
