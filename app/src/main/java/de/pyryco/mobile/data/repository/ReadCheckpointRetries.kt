package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Application ownership: connection sources are host/pairing-specific, never selected-host flows. */
class ReadCheckpointRetries(
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val hosts = ConcurrentHashMap<StateFlow<ConversationRepository?>, Host>()

    internal fun qualify(
        source: StateFlow<ConversationRepository?>,
        conversation: String,
        checkpoint: ULong,
        owner: ConversationRepository? = null,
        isQualified: (() -> Boolean)? = null,
    ) {
        if (owner != null && (source.value !== owner || isQualified?.invoke() != true)) return
        hosts.computeIfAbsent(source) { Host(it) }.qualify(conversation, checkpoint, owner, isQualified)
    }

    fun dispose() = scope.cancel()

    private class Pending(
        val checkpoint: ULong,
        val owner: ConversationRepository?,
        val isQualified: (() -> Boolean)?,
    )

    private inner class Host(
        private val source: StateFlow<ConversationRepository?>,
    ) {
        private val pending = MutableStateFlow<Map<String, Pending>>(emptyMap())
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val observed = ConcurrentHashMap.newKeySet<String>()
        private val confirmed = ConcurrentHashMap<String, ULong>()

        fun qualify(
            conversation: String,
            checkpoint: ULong,
            owner: ConversationRepository?,
            isQualified: (() -> Boolean)?,
        ) {
            if (owner == null && checkpoint <= (confirmed[conversation] ?: 0uL)) return
            pending.update { work ->
                val previous = work[conversation]
                if (owner == null && previous != null && (previous.owner != null || previous.checkpoint >= checkpoint)) {
                    work
                } else {
                    work + (conversation to Pending(checkpoint, owner, isQualified))
                }
            }
            if (!observed.add(conversation)) return
            scope.launch {
                source.collectLatest { repository ->
                    pending.update { work -> work.filterValues { it.owner == null || it.owner === source.value } }
                    if (repository == null || source.value !== repository) return@collectLatest
                    var attempted: Pending? = null
                    // Facts can arrive after connection publication; support is never inferred from cache.
                    combine(repository.observeReadMarks(conversation), pending) { facts, work -> facts to work[conversation] }
                        .collect { (facts, target) ->
                            if (source.value !== repository) return@collect
                            val stored = facts?.readUpTo ?: return@collect
                            confirm(conversation, stored)
                            if (target == null || target.checkpoint <= stored) return@collect
                            locks.computeIfAbsent(conversation) { Mutex() }.withLock {
                                val highest = pending.value[conversation] ?: return@withLock
                                if (source.value !== repository || highest === attempted ||
                                    (highest.owner != null && (highest.owner !== repository || highest.isQualified?.invoke() != true))
                                ) {
                                    return@withLock
                                }
                                attempted = highest
                                RelayLog.d { "event=read_checkpoint outcome=attempt" }
                                repository
                                    .markConversationRead(conversation, highest.checkpoint)
                                    .onSuccess {
                                        if (source.value === repository) confirm(conversation, it)
                                        RelayLog.d { "event=read_checkpoint outcome=confirmed" }
                                    }.onFailure { RelayLog.w { "event=read_checkpoint outcome=pending" } }
                            }
                        }
                }
            }
        }

        private fun confirm(
            conversation: String,
            stored: ULong,
        ) {
            confirmed.merge(conversation, stored, ::maxOf)
            pending.update { work -> if ((work[conversation]?.checkpoint ?: ULong.MAX_VALUE) <= stored) work - conversation else work }
        }
    }
}
