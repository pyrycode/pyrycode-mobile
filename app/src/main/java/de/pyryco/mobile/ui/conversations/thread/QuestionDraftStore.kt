package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.QuestionAnswer
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private class Binding(
        val owner: Any,
        val job: Job,
        val liveRepository: () -> ConversationRepository?,
        val submit: (suspend (RemoteConversationRepository, QuestionBatch, List<QuestionAnswer>?) -> Unit)?,
    )

    private val bindings = mutableMapOf<String, Binding>()
    private val sources = mutableMapOf<String, RemoteConversationRepository>()
    private var nextGeneration = 0L

    @Synchronized
    fun bind(
        serverId: String,
        owner: Any,
        repositories: StateFlow<ConversationRepository?>,
        liveRepository: () -> ConversationRepository? = { repositories.value },
        submit: (suspend (RemoteConversationRepository, QuestionBatch, List<QuestionAnswer>?) -> Unit)? = null,
    ) {
        if (bindings[serverId]?.owner === owner) return
        bindings.remove(serverId)?.job?.cancel()
        clearHost(serverId)
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                repositories.collectLatest { repository ->
                    reconcileSource(serverId, owner, repository, emptyList())
                    (repository as? RemoteConversationRepository)?.questionBatches?.collect {
                        reconcileSource(serverId, owner, repository, it)
                    }
                }
            }
        bindings[serverId] = Binding(owner, job, liveRepository, submit)
        job.start()
    }

    @Synchronized
    private fun reconcileSource(
        serverId: String,
        owner: Any,
        repository: ConversationRepository?,
        batches: List<QuestionBatch>,
    ) {
        val binding = bindings[serverId] ?: return
        if (binding.owner !== owner) return
        if (binding.liveRepository() !== repository) {
            clearHost(serverId)
            return
        }
        if (sources[serverId] !== repository) clearHost(serverId)
        if (repository is RemoteConversationRepository) sources[serverId] = repository
        reconcileHost(serverId, batches)
    }

    fun observe(
        serverId: String,
        conversationId: String,
    ): Flow<QuestionModalState?> = states.map { it[serverId to conversationId] }.distinctUntilChanged()

    @Synchronized
    fun current(
        serverId: String,
        conversationId: String,
    ): QuestionModalState? {
        val key = serverId to conversationId
        val held = states.value[key] ?: return null
        val binding = bindings[serverId]
        if (binding != null) {
            val source = sources[serverId]
            if (source == null || binding.liveRepository() !== source) {
                clearHost(serverId)
                RelayLog.d { "event=question_draft_retired reason=source" }
                return null
            }
            if (source.questionBatches.value.firstOrNull { it.conversationId == conversationId } !== held.batch) {
                states.value = states.value - key
                RelayLog.d { "event=question_draft_retired reason=request" }
                return null
            }
        }
        return held
    }

    /** Send through the captured source. The bound handler repeats validation at the authoritative boundary. */
    internal suspend fun submit(
        serverId: String,
        conversationId: String,
        generation: Long,
        answers: List<QuestionAnswer>?,
        fallback: suspend (String) -> Unit,
    ): Boolean {
        val (held, source, send) =
            synchronized(this) {
                val held = current(serverId, conversationId)?.takeIf { it.generation == generation } ?: return false
                Triple(held, sources[serverId], bindings[serverId]?.submit)
            }
        if (send != null && source != null) send(source, held.batch, answers) else fallback(held.batch.questionBatchId)
        return true
    }

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
        val held = current(serverId, conversationId) ?: return
        if (held.generation != generation) return
        states.value = states.value + (key to edit(held))
    }

    @Synchronized
    fun clearHost(serverId: String) {
        sources.remove(serverId)
        states.value = states.value.filterKeys { it.first != serverId }
    }

    @Synchronized
    fun dispose() {
        scope.cancel()
        bindings.clear()
        sources.clear()
        states.value = emptyMap()
    }
}
