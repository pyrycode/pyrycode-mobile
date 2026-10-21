package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSupport
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.fixtureBackgroundRows
import de.pyryco.mobile.ui.conversations.thread.fixtureQueuedRows
import de.pyryco.mobile.ui.conversations.thread.foldToolRuns
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class ThreadEvidenceKind { Legacy, Authoritative }

/** A repository-only legacy caller cannot report its host; never invent that attribution. */
internal data class ThreadEvidenceOwner(
    val host: String?,
    val conversation: String,
    val connection: Any,
    val kind: ThreadEvidenceKind,
    val epoch: String? = null,
    val generation: Long? = null,
) {
    fun matches(current: ThreadEvidenceOwner): Boolean =
        connection === current.connection &&
            host == current.host &&
            conversation == current.conversation &&
            kind == current.kind &&
            epoch == current.epoch &&
            generation == current.generation

    override fun toString(): String = "ThreadEvidenceOwner(redacted)"
}

/** Rendered block/run identities accompany original messages and facts, which retain raw parent attribution. */
internal class ThreadEvidenceRecord(
    val message: Message,
    val fullText: String,
    val session: String?,
    val turn: String?,
    val agentId: String?,
    val toolRunId: String?,
    val fact: ThreadItemDto? = null,
) {
    override fun toString(): String = "ThreadEvidenceRecord(redacted)"
}

internal class ThreadEvidence(
    val owner: ThreadEvidenceOwner,
    val records: List<ThreadEvidenceRecord>,
    val admitted: Boolean = true,
    val sourceSnapshot: AuthoritativeThreadSnapshot? = null,
) {
    /** Ordinary loaded history remains usable for selection; it does not acquire delta evidence. */
    fun loadedMainReply(): Message? =
        if (!admitted) {
            null
        } else {
            records
                .lastOrNull {
                    it.message.role == Role.Assistant &&
                        it.agentId == null &&
                        when (owner.kind) {
                            ThreadEvidenceKind.Legacy -> it.message.parentToolUseId.isEmpty()
                            ThreadEvidenceKind.Authoritative -> (it.fact?.parent ?: 0) == 0L
                        } &&
                        !it.message.isStreaming &&
                        it.message.content.isNotBlank() &&
                        it.fullText.isNotBlank() &&
                        (it.fact == null || it.fact.status in setOf("done", "finished") && !it.fact.active)
                }?.message
        }

    /**
     * Call with current ownership when retaining evidence across a source or document replacement.
     * Legacy families use raw parent attribution; authoritative families use projected Agent block IDs.
     */
    fun settledReply(
        current: ThreadEvidenceOwner,
        session: String,
        turn: String,
        agentId: String = "",
    ): List<ThreadEvidenceRecord>? {
        if (!admitted || !owner.matches(current)) return null
        val family =
            records.filter {
                it.message.role == Role.Assistant &&
                    it.session == session &&
                    it.turn == turn &&
                    when (owner.kind) {
                        ThreadEvidenceKind.Legacy -> it.message.parentToolUseId == agentId
                        ThreadEvidenceKind.Authoritative -> it.fact?.kind == "assistant_message" && it.agentId.orEmpty() == agentId
                    }
            }
        if (family.isEmpty()) return null
        val settled =
            when (owner.kind) {
                ThreadEvidenceKind.Legacy -> holdsSettledAssistantReply(family.map { ThreadItem.MessageItem(it.message) }, turn, agentId)
                ThreadEvidenceKind.Authoritative ->
                    family.all {
                        val item = it.fact
                        item != null &&
                            ((item.parent ?: 0) == 0L || it.agentId != null) &&
                            !item.active &&
                            item.status in setOf("done", "finished") &&
                            it.fullText.isNotBlank()
                    }
            }
        return family.takeIf { settled }
    }

    fun userEchoCount(text: String): Int =
        if (!admitted) {
            0
        } else {
            records.count {
                it.message.role == Role.User &&
                    it.fullText == text &&
                    (it.fact == null || it.fact.status == "delivered")
            }
        }

    override fun toString(): String = "ThreadEvidence(redacted)"
}

/** Observation only. Construction never opens a query or enables an application route. */
internal class ThreadEvidenceSource private constructor(
    val kind: ThreadEvidenceKind,
    val observe: (String) -> Flow<ThreadEvidence>,
) {
    companion object {
        fun legacy(
            host: String?,
            connection: Any,
            observe: (String) -> Flow<List<ThreadItem>>,
        ) = ThreadEvidenceSource(ThreadEvidenceKind.Legacy) { conversation ->
            observe(conversation).map { legacyThreadEvidence(host, conversation, connection, it) }
        }

        fun authoritative(
            host: String,
            connection: Any,
            observe: (String) -> Flow<AuthoritativeThreadSnapshot>,
        ) = ThreadEvidenceSource(ThreadEvidenceKind.Authoritative) { conversation ->
            observe(conversation).map { authoritativeThreadEvidence(host, conversation, connection, it) }
        }

        fun connection(repository: RemoteConversationRepository) =
            ThreadEvidenceSource(ThreadEvidenceKind.Authoritative) { conversation ->
                repository.observeAuthoritativeThread(conversation).map {
                    val evidence = authoritativeThreadEvidence(repository.authoritativeHost, conversation, repository, it)
                    if (repository.usesAuthoritativeLiveState) evidence else ThreadEvidence(evidence.owner, emptyList(), admitted = false)
                }
            }

        fun negotiated(route: NegotiatedThread) =
            ThreadEvidenceSource(ThreadEvidenceKind.Authoritative) { conversation ->
                combine(route.support, route.observe(conversation)) { support, snapshot ->
                    val connection =
                        when (support) {
                            is ThreadSupport.Supported -> support.connection
                            is ThreadSupport.Missing -> support.connection
                            ThreadSupport.Unverified -> route
                        }
                    val evidence = authoritativeThreadEvidence(route.host, conversation, connection, snapshot)
                    if (support is ThreadSupport.Missing) ThreadEvidence(evidence.owner, emptyList(), admitted = false) else evidence
                }
            }
    }
}

/**
 * The single negotiation seam (#2175), bound to the production selection: a host repository carrying the
 * negotiated route, or a live connection serving one, yields only authoritative evidence. Only the legacy
 * demo/fake route, which has neither, yields legacy evidence, so an authoritative run cannot pass on it.
 */
internal fun harnessThreadEvidence(repository: ConversationRepository): ThreadEvidenceSource {
    repository.negotiatedThread?.let { route ->
        return ThreadEvidenceSource.negotiated(route)
    }
    if (repository is RemoteConversationRepository && repository.authoritativeHost.isNotEmpty()) {
        return ThreadEvidenceSource.connection(repository)
    }
    error("authoritative thread source is required")
}

/** Keep ordinary reads on the captured connection; thread evidence follows its owning host. */
internal suspend fun harnessHostRepository(coordinator: RelayRepositoryCoordinator): ConversationRepository {
    val repository = coordinator.currentRepository.filterNotNull().first()
    val thread = coordinator.negotiatedThread ?: return repository
    return object : ConversationRepository by repository {
        override val negotiatedThread: NegotiatedThread = thread
    }
}

internal fun legacyThreadEvidence(
    host: String?,
    conversation: String,
    connection: Any,
    items: List<ThreadItem>,
): ThreadEvidence {
    val rows = fixtureBackgroundRows(fixtureQueuedRows(items, emptyList()), items, null)
    val delivered = rows.filterIsInstance<ThreadRow.Delivered>().associateBy { (it.item as? ThreadItem.MessageItem)?.message?.id }
    val runs = toolRunIds(foldToolRuns(rows, emptySet()))
    return ThreadEvidence(
        ThreadEvidenceOwner(host, conversation, connection, ThreadEvidenceKind.Legacy),
        items.filterIsInstance<ThreadItem.MessageItem>().map { row ->
            val message = row.message
            ThreadEvidenceRecord(
                message,
                message.content,
                message.sessionId,
                message.segment?.turnId,
                delivered[message.id]?.agentBlockId,
                runs[message.id],
            )
        },
    )
}

internal fun authoritativeThreadEvidence(
    host: String,
    conversation: String,
    connection: Any,
    snapshot: AuthoritativeThreadSnapshot,
): ThreadEvidence {
    val projection = projectAuthoritativeThread(host, conversation, snapshot)
    val facts =
        snapshot.items
            .groupBy { it.id }
            .mapValues { (_, versions) -> versions.maxBy { it.rev } }
            .mapKeys { (id, _) -> "${projection.scope}:$id" }
    val runs = toolRunIds(foldToolRuns(projection.rows, emptySet()))
    val records =
        projection.rows.filterIsInstance<ThreadRow.Delivered>().mapNotNull { row ->
            val message = (row.item as? ThreadItem.MessageItem)?.message ?: return@mapNotNull null
            val fact = facts[message.id] ?: return@mapNotNull null
            val text = ((fact.content as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: message.content
            ThreadEvidenceRecord(message, text, fact.session, fact.turn, row.agentBlockId, runs[message.id], fact)
        }
    return ThreadEvidence(
        ThreadEvidenceOwner(host, conversation, connection, ThreadEvidenceKind.Authoritative, snapshot.epoch, snapshot.generation),
        records,
        admitted = snapshot.epoch != null && !snapshot.repairNeeded && !snapshot.resetNeeded,
        sourceSnapshot = snapshot,
    )
}

private fun toolRunIds(rows: List<ThreadRow>): Map<String, String> =
    rows.filterIsInstance<ThreadRow.ToolRun>().flatMap { run -> run.tools.map { it.id to run.runId } }.toMap()
