package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-only question picks. Repository epochs retire drafts even when a reconnect rebuilds an equal batch. */
class QuestionDraftStore(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val states = MutableStateFlow<Map<Pair<String, String>, QuestionModalState>>(emptyMap())
    private val bindings = mutableMapOf<String, Pair<Any, Job>>()
    private var nextGeneration = 0L

    @Synchronized
    fun bind(
        serverId: String,
        owner: Any,
        repositories: Flow<ConversationRepository?>,
    ) {
        if (bindings[serverId]?.first === owner) return
        bindings.remove(serverId)?.second?.cancel()
        clearHost(serverId)
        val job =
            scope.launch {
                repositories.collectLatest { repository ->
                    clearHost(serverId)
                    (repository as? RemoteConversationRepository)?.questionBatches?.collect { reconcileHost(serverId, it) }
                }
            }
        bindings[serverId] = owner to job
    }

    fun observe(
        serverId: String,
        conversationId: String,
    ): Flow<QuestionModalState?> = states.map { it[serverId to conversationId] }.distinctUntilChanged()

    @Synchronized
    fun current(
        serverId: String,
        conversationId: String,
    ): QuestionModalState? = states.value[serverId to conversationId]

    @Synchronized
    fun reconcileHost(
        serverId: String,
        batches: List<QuestionBatch>,
    ) {
        val next = states.value.filterKeys { it.first != serverId }.toMutableMap()
        batches.distinctBy { it.conversationId }.forEach { batch ->
            val key = serverId to batch.conversationId
            val old = states.value[key]
            next[key] = if (old?.batch == batch) old else QuestionModalState(batch, generation = ++nextGeneration)
        }
        if (next != states.value) {
            RelayLog.d { "event=question_drafts_reconciled" }
            states.value = next
        }
    }

    @Synchronized
    fun update(
        serverId: String,
        conversationId: String,
        generation: Long,
        edit: (QuestionModalState) -> QuestionModalState,
    ) {
        val key = serverId to conversationId
        val held = states.value[key] ?: return
        if (held.generation != generation) return
        states.value = states.value + (key to edit(held))
    }

    @Synchronized
    fun clearHost(serverId: String) {
        states.value = states.value.filterKeys { it.first != serverId }
    }

    @Synchronized
    fun dispose() {
        scope.cancel()
        bindings.clear()
        states.value = emptyMap()
    }
}
