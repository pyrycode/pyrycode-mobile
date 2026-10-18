package de.pyryco.mobile.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

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
