package de.pyryco.mobile.e2e

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A live-e2e client link that redials when it ends (#1036), the way the app's supervisor does. On the live
 * relay a fresh connection often ends within a second of its handshake (#1039); a client that dials once
 * then stays dead for the rest of its scenario. [dial] makes one attempt and returns a link only once it
 * has *settled* (answered a request), so a connection that dies straight after its handshake is retried
 * here rather than handed to a caller. Generic in the link type so the JVM test needs no relay.
 *
 * [awaitEnd] suspends until a link ends and returns a content-free label for [describe]; [release] closes
 * a link on [close]. [dial] cleans up an attempt that does not settle itself.
 */
class RedialingLink<L : Any>(
    private val scope: CoroutineScope,
    private val dial: suspend () -> L?,
    private val awaitEnd: suspend (L) -> String,
    private val release: (L) -> Unit,
) {
    private val mutableCurrent = MutableStateFlow<L?>(null)

    /** The settled live link, or null while none is (before [start], while redialing, after [close]). */
    val current: StateFlow<L?> = mutableCurrent.asStateFlow()

    private val lock = Any()

    @Volatile private var closed = false

    @Volatile private var links = 0

    @Volatile private var lastEnd: String? = null

    private var supervisor: Job? = null

    /** Dial until a link settles, publish it, and redial whenever the published link ends. The caller bounds it. */
    suspend fun start() {
        if (!publish(dialUntilSettled())) return
        supervisor = scope.launch { supervise() }
    }

    /**
     * Run [attempt] on the live link until it is answered. A request the link refused ([Attempt.NotSent])
     * never reached the wire, so it always runs again on the next link. One that went out and lost its link
     * before the reply ([Attempt.Ended]) runs again only when [resend] says a repeat is harmless; otherwise
     * it fails naming [what]. Bounded by the caller's timeout.
     */
    suspend fun <T> request(
        what: String,
        resend: Boolean,
        attempt: suspend (L) -> Attempt<T>,
    ): T {
        var used: L? = null
        while (true) {
            val live = current.filterNotNull().first { it !== used }
            when (val outcome = attempt(live)) {
                is Attempt.Answered -> return outcome.value
                Attempt.NotSent -> used = live
                Attempt.Ended ->
                    if (resend) used = live else throw AssertionError("$what: the link ended before the reply, and it is not resent")
            }
        }
    }

    /** Whether a session is open, which link it is and how often it was replaced; counts and labels only. */
    fun describe(): String =
        when {
            closed -> "session closed by the test"
            current.value != null -> "session open (link $links, replaced ${links - 1}×)"
            else -> "no open session: ${lastEnd ?: "none settled yet"}; redialing"
        }

    /** Stop redialing and release the live link. Idempotent. */
    fun close() {
        val live =
            synchronized(lock) {
                if (closed) return
                closed = true
                mutableCurrent.value.also { mutableCurrent.value = null }
            }
        supervisor?.cancel()
        live?.let(release)
    }

    private suspend fun supervise() {
        while (true) {
            val live = current.value ?: return
            val label = awaitEnd(live)
            synchronized(lock) {
                lastEnd = "link $links ended ($label)"
                if (mutableCurrent.value === live) mutableCurrent.value = null
            }
            if (closed) return
            // The app's supervisor also waits before its first redial; a relay that just dropped a link gets
            // the same breathing room here.
            delay(backoffMs(1))
            if (!publish(dialUntilSettled())) return
        }
    }

    private suspend fun dialUntilSettled(): L {
        var failures = 0
        while (true) {
            dial()?.let { return it }
            failures += 1
            delay(backoffMs(failures))
        }
    }

    /** Publish [link] unless [close] got there first, in which case release it. */
    private fun publish(link: L): Boolean {
        val published =
            synchronized(lock) {
                if (!closed) {
                    links += 1
                    mutableCurrent.value = link
                }
                !closed
            }
        if (!published) release(link)
        return published
    }

    /** One run of a [request] on one link. */
    sealed interface Attempt<out T> {
        /** The link answered with [value]. */
        data class Answered<out T>(
            val value: T,
        ) : Attempt<T>

        /** The link refused the frame, so nothing went out. */
        data object NotSent : Attempt<Nothing>

        /** The frame went out, and the link ended before the reply. */
        data object Ended : Attempt<Nothing>
    }

    private companion object {
        const val BACKOFF_BASE_MS = 1_000L
        const val BACKOFF_CAP_MS = 8_000L

        fun backoffMs(failures: Int): Long = minOf(BACKOFF_BASE_MS shl (failures - 1).coerceAtMost(3), BACKOFF_CAP_MS)
    }
}
