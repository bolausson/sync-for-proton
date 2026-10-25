package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guards for the scheduling rules that need Android to run, so they are checked in the source, as
 * ActiveStatusLineTest and BackupSettingsRepairTest do. The working directory of a unit test is the module's.
 *
 * Each of these broke at least once: an hourly periodic sync armed outside the shared writer brought "Manual
 * only" back four times, the flow emitted before the store so the observer acted while the gate still read
 * the old state, and a restore requested syncs the gate then dropped.
 */
class PeriodicSyncWriterTest {

    private fun source(path: String) = File("src/$path").readText().replace("\r\n", "\n")

    private fun kotlinSources(): List<File> =
        listOf("main", "direct", "play", "debug")
            .map { File("src/$it/java") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test
    fun `only SyncUtils arms a periodic sync`() {
        val writers = kotlinSources().filter { Regex("addPeriodicSync\\(").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("every periodic sync goes through SyncUtils.applyPeriodicSync, which knows the rule", listOf("SyncUtils.kt"), writers)
    }

    @Test
    fun `nothing arms the default hour directly`() {
        val users = kotlinSources().filter { it.readText().contains("DEFAULT_INITIAL_PERIODIC_SYNC_SECONDS") }.map { it.name }.toSet()
        assertTrue(
            "the default interval is only read as the default of an unset schedule: $users",
            setOf("SyncConstants.kt", "SyncUtils.kt", "SyncScheduling.kt").containsAll(users),
        )
    }

    @Test
    fun `both flavours store the state before the flow emits it`() {
        for (flavour in listOf("direct", "play")) {
            val update = source("$flavour/java/me/proton/sync/billing/SubscriptionManager.kt")
                .substringAfter("private fun updateState(").substringBefore("\n    }\n")
            val store = update.indexOf("persistState(newState)")
            assertTrue("$flavour: updateState persists", store >= 0)
            assertTrue(
                "$flavour: the gate reads the store, so it must be written before the observer hears of the change",
                store < update.indexOf("_state.value = newState"),
            )
        }
    }

    @Test
    fun `the observer reads the cached state before the backend can change it`() {
        val app = source("main/java/me/proton/sync/ProtonSyncApp.kt")
        val onCreate = app.substringAfter("override fun onCreate() {").substringBefore("\n    }\n")
            .lines().joinToString("\n") { it.substringBefore("//") }
        val observe = onCreate.indexOf("observeSubscriptionForScheduling()")
        assertTrue("onCreate starts the observer", observe >= 0)
        assertTrue("the observer starts before connect()", observe < onCreate.indexOf("subscriptionManager.connect()"))
        val body = app.substringAfter("private fun observeSubscriptionForScheduling() {").substringBefore("\n    }\n")
        val read = body.indexOf("isSyncAllowed()")
        assertTrue("the cached verdict is read on the calling thread, before the collector launches", read in 0 until body.indexOf(".launch"))
    }

    @Test
    fun `a restore asks for its first syncs only while the gate is open`() {
        val apply = source("main/java/me/proton/sync/backup/ImportManager.kt")
            .substringAfter("suspend fun apply(").substringBefore("\n    }\n")
        val gate = apply.indexOf("SubscriptionGate.isSyncAllowedCached(context)")
        assertTrue("the import checks the gate", gate >= 0)
        assertTrue("before it requests the syncs the gate would drop", gate < apply.indexOf("requestImmediateSync("))
    }
}
