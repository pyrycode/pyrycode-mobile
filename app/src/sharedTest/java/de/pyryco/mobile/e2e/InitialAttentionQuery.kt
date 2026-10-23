package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.ThreadSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import java.io.Closeable

/** Fresh offscreen conversations must finish their attention query before the scenario acquires them. */
internal suspend fun awaitInitialAttentionQuery(snapshots: Flow<AuthoritativeThreadSnapshot>) {
    snapshots.first {
        !it.epoch.isNullOrEmpty() && it.checkpoint > 0 && it.coverage != null && !it.repairNeeded && !it.resetNeeded
    }
}

/** Only the armed scenario delays acquisition; destination setup and all provider admission stay real. */
internal class ThreadAcquisitionGate private constructor(
    private val host: String,
    private val conversation: String,
    private val connection: Any,
    private val generation: Long,
    private val timeoutMillis: Long,
) : Closeable {
    private val acquired = CompletableDeferred<Unit>()
    private val closed = MutableStateFlow(false)

    val finished get() = acquired.isCompleted

    suspend fun awaitAcquired() = acquired.await()

    override fun close() {
        closed.value = true
        synchronized(Companion) {
            if (active === this) active = null
        }
        acquired.cancel()
    }

    companion object {
        private var active: ThreadAcquisitionGate? = null

        @Synchronized fun arm(
            thread: NegotiatedThread,
            conversation: String,
            timeoutMillis: Long,
        ): ThreadAcquisitionGate {
            check(active == null) { "thread acquisition already armed" }
            val connection = checkNotNull((thread.support.value as? ThreadSupport.Supported)?.connection)
            return ThreadAcquisitionGate(
                thread.host,
                conversation,
                connection,
                thread.store.current(thread.host, conversation).generation,
                timeoutMillis,
            ).also { active = it }
        }

        fun negotiated(delegate: NegotiatedThread): NegotiatedThread =
            object : NegotiatedThread() {
                override val host = delegate.host
                override val support = delegate.support
                override val store = delegate.store

                override fun localHistory(conversationId: String) = delegate.localHistory(conversationId)

                override fun observe(conversationId: String) = delegate.observe(conversationId)

                override fun progress(
                    conversationId: String,
                    connection: Any,
                ) = delegate.progress(conversationId, connection)

                override suspend fun open(conversationId: String) = delegate.open(conversationId)

                override fun older(conversationId: String) = delegate.older(conversationId)

                override fun cancel(conversationId: String) = delegate.cancel(conversationId)

                override suspend fun acquire(
                    conversationId: String,
                    connection: Any,
                ): Closeable? {
                    val gate =
                        synchronized(Companion) {
                            active?.takeIf { it.host == host && it.conversation == conversationId && it.connection === connection }
                        }
                    if (gate == null) return delegate.acquire(conversationId, connection)
                    try {
                        val ready =
                            withTimeout(gate.timeoutMillis) {
                                coroutineScope {
                                    val observation =
                                        async(start = CoroutineStart.UNDISPATCHED) {
                                            val receipt = ThreadQueryReadiness.awaitCurrent(host, conversationId)
                                            awaitInitialAttentionQuery(
                                                store.observe(host, conversationId).filter { it.generation == gate.generation },
                                            )
                                            receipt.isCurrent()
                                        }
                                    val retired =
                                        async(start = CoroutineStart.UNDISPATCHED) {
                                            combine(support, gate.closed) { verdict, closed ->
                                                closed || (verdict as? ThreadSupport.Supported)?.connection !== connection
                                            }.first { it }
                                        }
                                    try {
                                        select<Boolean> {
                                            observation.onAwait { it }
                                            retired.onAwait { false }
                                        }
                                    } finally {
                                        observation.cancel()
                                        retired.cancel()
                                    }
                                }
                            }
                        if (!ready ||
                            synchronized(Companion) { active !== gate } ||
                            (support.value as? ThreadSupport.Supported)?.connection !== connection ||
                            store.current(host, conversationId).generation != gate.generation
                        ) {
                            gate.acquired.cancel()
                            return null
                        }
                        val lease = delegate.acquire(conversationId, connection)
                        if (lease == null) gate.acquired.cancel() else gate.acquired.complete(Unit)
                        return lease
                    } catch (failure: Exception) {
                        gate.acquired.completeExceptionally(failure)
                        throw failure
                    }
                }
            }
    }
}
