package me.proton.sync

import java.util.Collections
import java.util.UUID

/**
 * One-time tokens for debug commands (see SyncSettingsActivity.handleDebugCommand).
 *
 * SyncSettingsActivity is exported, because the system's account settings open it, so an extra on
 * its intent is not proof of where a command came from. The only way to obtain a token is the
 * debug-only DebugCommandActivity, which requires android.permission.DUMP: adb's shell holds it,
 * ordinary apps cannot. Tokens live in memory and are spent on use, so an intent that Android
 * redelivers later (a restored task, Recents, a new process) carries a token nobody accepts.
 */
object DebugCommandGate {
    private val issued = Collections.synchronizedSet(HashSet<String>())

    fun issue(): String = UUID.randomUUID().toString().also { issued.add(it) }

    fun consume(token: String?): Boolean = token != null && issued.remove(token)
}
