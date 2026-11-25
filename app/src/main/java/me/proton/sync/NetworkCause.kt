package me.proton.sync

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.StreamResetException

/**
 * Why a request to Proton got no answer, when the network is why. A sync writes it after the text it records for a
 * failure ([describe]: SyncUtils.recordSwallowedFailure and savePostSyncResult), and the failure notification and the
 * account card's failure dialog read it back first ([carriedBy], in SyncNotificationHelper.friendlyMessageRes), so the
 * reason they show is the network's. Pure, so it is tested without a device.
 *
 * The recorded text used to lose the cause. A calendar whose keys could not be fetched while the phone had no network
 * recorded only that it "could not be opened", and the notification said the sync met an unexpected error (seen on a
 * test phone on 2026-11-22). The texts were classified by their words alone, and an exception's message seldom names
 * its class: "Connection reset", "Read timed out" and "Failed to connect to ..." matched none of them either.
 *
 * Only these exceptions count, never an answer from Proton: a key fetch that Proton refused (an HTTP status) has no
 * network cause, and neither has a failure on the phone (a key that does not open, a row that cannot be written).
 */
internal enum class NetworkCause(
    /** How [describe] writes it and [carriedBy] reads it back. */
    val label: String,
) {
    /** No address for the host, no route to it, a connection refused, or no network for the app: nothing reached Proton. */
    NO_CONNECTION("no connection"),

    /** A connection or an answer that took too long. */
    TIMEOUT("timeout"),

    /** A connection that broke off: reset, a broken pipe, a stream that ended before the answer did. */
    INTERRUPTED("connection interrupted"),

    /** A secure connection that could not be set up, which a captive portal or a filtering proxy also causes. */
    SECURE_CONNECTION("secure connection failed");

    companion object {
        /** What [describe] puts between a failure's text and its cause. */
        private const val MARK = " (network: "

        /** How far down an exception's causes [of] looks. */
        private const val MAX_CAUSES = 8

        /** [error]'s network cause: that of the first exception in it and its causes that has one, outermost first. */
        fun of(error: Throwable?): NetworkCause? = find(error)?.first

        /**
         * [message], the failure a sync records, with [error]'s network cause after it
         * (`... (network: no connection, UnknownHostException)`), or [message] unchanged when [error] has none or
         * [message] carries one already. A null or blank [message] gives way to the exception's class. The cause always
         * goes last, where [carriedBy] reads it, so a calendar's name quoted in [message] can neither hide it nor stand in
         * for it.
         */
        fun describe(message: String?, error: Throwable?): String? {
            val found = find(error) ?: return message
            if (message != null && carriedBy(message) != null) return message
            val name = found.second.javaClass.simpleName
            return (message?.takeIf { it.isNotBlank() } ?: name) + MARK + found.first.label + ", " + name + ")"
        }

        /** The cause [describe] wrote at the end of [raw], or null when it carries none. */
        fun carriedBy(raw: String): NetworkCause? {
            val at = raw.lastIndexOf(MARK)
            if (at < 0 || !raw.endsWith(")")) return null
            val rest = raw.substring(at + MARK.length)
            return entries.firstOrNull { rest.startsWith(it.label + ", ") }
        }

        /** The first exception of [error] and its causes that has a network cause, with that cause. */
        private fun find(error: Throwable?): Pair<NetworkCause, Throwable>? {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var current = error
            var depth = 0
            while (current != null && depth <= MAX_CAUSES && seen.add(current)) {
                val cause = ownCause(current)
                if (cause != null) return cause to current
                current = current.cause
                depth++
            }
            return null
        }

        /** [t]'s network cause, without looking at its causes. */
        private fun ownCause(t: Throwable): NetworkCause? = when (t) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is PortUnreachableException ->
                NO_CONNECTION
            is SocketTimeoutException -> TIMEOUT
            // Android's own words when the app may not open a socket at all ("socket failed: EPERM (Operation not
            // permitted)", a network blocked for it) or the phone has no network ("Network is unreachable").
            is SocketException -> if (noNetwork(t.message)) NO_CONNECTION else INTERRUPTED
            // A connection reset under TLS reads "Read error: ssl=...: I/O error during system call, Connection reset by
            // peer": the connection broke off, the secure setup was fine.
            is SSLException -> if (brokeOff(t.message)) INTERRUPTED else SECURE_CONNECTION
            is CertificateException -> SECURE_CONNECTION
            // OkHttp's call timeout says "timeout"; a thread interrupted for any other reason is not the network.
            is InterruptedIOException -> if (t.message.equals("timeout", ignoreCase = true)) TIMEOUT else null
            // OkHttp: an HTTP/2 stream or connection that was reset or shut down. By type, never by name: R8 renames both
            // classes in a release build (a name check matched them in debug builds only).
            is StreamResetException, is ConnectionShutdownException -> INTERRUPTED
            // OkHttp: the server closed the connection before its answer ("unexpected end of stream on ...").
            is IOException -> if (t.message?.startsWith("unexpected end of stream") == true) INTERRUPTED else null
            else -> null
        }

        private fun noNetwork(message: String?): Boolean =
            message != null && (message.startsWith("socket failed:") || message.contains("Network is unreachable", ignoreCase = true))

        private fun brokeOff(message: String?): Boolean =
            message != null && (message.contains("connection reset", ignoreCase = true) || message.contains("broken pipe", ignoreCase = true))
    }
}
