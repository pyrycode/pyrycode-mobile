package de.pyryco.mobile.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * One host's thread verdict, taken only from an authenticated `hello_ack` on a current connection (#2175).
 * [connection] identifies that connection, so work fenced to one verdict cannot act on its replacement.
 */
sealed interface ThreadSupport {
    /** No authenticated connection currently answers; held facts may be read but establish no support. */
    data object Unverified : ThreadSupport

    class Supported(
        val connection: Any,
    ) : ThreadSupport {
        override fun toString(): String = "Supported"
    }

    /** The host's daemon does not provide threads. Sticky until a later acknowledgement replaces it. */
    class Missing(
        val connection: Any,
    ) : ThreadSupport {
        override fun toString(): String = "Missing"
    }
}

/**
 * The production thread route of one exact host (#2175). Decorators forward it, as they forward
 * [ConversationRepository.runConfigurationReadings]; a repository without it is the legacy route.
 */
abstract class NegotiatedThread {
    abstract val host: String
    abstract val support: StateFlow<ThreadSupport>
    internal abstract val store: AuthoritativeThreadStore

    /** Observation only: never opens a query. */
    internal abstract fun observe(conversationId: String): Flow<AuthoritativeThreadSnapshot>

    /** Transient readings belong only to the supported connection captured by the caller. */
    internal open fun progress(
        conversationId: String,
        connection: Any,
    ): Flow<AuthoritativeLiveProgress> = flowOf(AuthoritativeLiveProgress())

    /** Opens synchronization on the connection named by the current [ThreadSupport.Supported] verdict only. */
    abstract suspend fun open(conversationId: String): Boolean

    abstract fun older(conversationId: String): Boolean

    abstract fun cancel(conversationId: String)
}
