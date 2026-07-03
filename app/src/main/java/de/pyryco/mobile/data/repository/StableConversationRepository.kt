package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * The stable [ConversationRepository] reference ViewModels hold across relay connection churn (#352).
 *
 * #351's [RelayRepositoryCoordinator] builds a fresh connection-scoped [RemoteConversationRepository]
 * per live relay connection and publishes the current one — or `null` between connections — on
 * `currentRepository`. ViewModels resolve a single repository at construction and hold it for their
 * lifetime, so a connection-scoped repository captured directly would be a dead reference the moment
 * its connection dropped. This facade is the stable indirection: one process-lifetime singleton whose
 * object identity never changes (AC #1), delegating every call to whichever connection-scoped
 * repository is currently live and switching transparently as connections come and go.
 *
 * **Holds no scope and launches no coroutine.** Cold reads are collected on the consumer's scope;
 * one-shots run on the caller's coroutine. The only state is the injected [currentRepository], owned
 * and written by the coordinator — this facade only **reads** it (`.value` for one-shots, the flow
 * for cold reads), so there is no lock, no `MutableStateFlow`, and no TOCTOU here.
 *
 * **Cold reads** [observeConversations]/[observeMessages]/[observeLastMessage]/[recentWorkspaces]
 * switch over [currentRepository] with [flatMapLatest]: a new value cancels the prior inner flow and
 * subscribes the new one, so the previous connection's projection is dropped the instant the value
 * changes — combined with #351 giving each connection a distinct repository instance, a reader can
 * never observe a previous connection's data after a reconnect (AC #2). While no connection is live
 * each cold read emits its defined empty projection (`emptyList()` / `null`) and resumes on the next
 * connection (AC #3).
 *
 * **One-shots** snapshot the live repository at call entry and delegate to it; with no connection live
 * they throw [IllegalStateException] — the same type [RemoteConversationRepository] throws on a
 * not-`Open` pump, so a caller catches one type whether the connection was absent at call time
 * (here) or dropped mid-flight (the delegate). Delegation is behaviour-neutral: a wired mutation, a
 * throwing stub, or a wired error all propagate verbatim (AC #4) — the facade adds nothing.
 *
 * Emits **no logs**, consistent with the coordinator/pump/supervisor posture: it moves only object
 * references and already-decoded domain values, and must not log repo contents or the not-connected
 * event.
 */
class StableConversationRepository(
    private val currentRepository: StateFlow<ConversationRepository?>,
) : ConversationRepository {
    /**
     * Switch a cold read over [currentRepository]: delegate to the live repository's [select] flow, or
     * emit [whenAbsent] once while no connection is live. [flatMapLatest] cancels the prior inner flow
     * on every change, so a reconnect drops the previous connection's projection (AC #2) and a `null`
     * falls back to the empty projection, resuming when the next connection arrives (AC #3).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> switchToLive(
        whenAbsent: T,
        select: (ConversationRepository) -> Flow<T>,
    ): Flow<T> = currentRepository.flatMapLatest { repo -> repo?.let(select) ?: flowOf(whenAbsent) }

    /** The currently-live repository snapshot, or [IllegalStateException] if no connection is live. */
    private val live: ConversationRepository
        get() = currentRepository.value ?: throw IllegalStateException(NOT_CONNECTED)

    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
        switchToLive(emptyList()) { it.observeConversations(filter) }

    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
        switchToLive(emptyList()) { it.observeMessages(conversationId) }

    override fun observeLastMessage(conversationId: String): Flow<Message?> =
        switchToLive<Message?>(null) { it.observeLastMessage(conversationId) }

    override fun observeStall(conversationId: String): Flow<Boolean> = switchToLive(false) { it.observeStall(conversationId) }

    override fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> =
        switchToLive(emptyList()) { it.observeQueue(conversationId) }

    /**
     * Delegates the capability to the live repository's value, reporting `false` when no connection is
     * live (fail-safe-deny — the safe answer for a gating consumer is "hide the actions"). This is the
     * plain-`Boolean` analog of [observeStall]'s `switchToLive(false)`: a getter that re-reads
     * [currentRepository] `.value` on every access, like [live], so a connection landing after facade
     * construction is reflected — never a construction-time snapshot.
     */
    override val mutationsSupported: Boolean
        get() = currentRepository.value?.mutationsSupported ?: false

    override fun recentWorkspaces(): Flow<List<String>> = switchToLive(emptyList()) { it.recentWorkspaces() }

    override suspend fun createDiscussion(workspace: String?): Conversation = live.createDiscussion(workspace)

    override suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String?,
    ): Conversation = live.promote(conversationId, name, workspace)

    override suspend fun archive(conversationId: String): Unit = live.archive(conversationId)

    override suspend fun unarchive(conversationId: String): Unit = live.unarchive(conversationId)

    override suspend fun delete(conversationId: String): Unit = live.delete(conversationId)

    override suspend fun rename(
        conversationId: String,
        name: String,
    ): Conversation = live.rename(conversationId, name)

    override suspend fun startNewSession(
        conversationId: String,
        workspace: String?,
    ): Session = live.startNewSession(conversationId, workspace)

    override suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session = live.changeWorkspace(conversationId, workspace)

    override suspend fun sendMessage(
        conversationId: String,
        text: String,
    ): Message = live.sendMessage(conversationId, text)

    override suspend fun createWorkspaceFolder(name: String): String = live.createWorkspaceFolder(name)

    override suspend fun requestScreenSnapshot(conversationId: String): String = live.requestScreenSnapshot(conversationId)

    override suspend fun dropQueuedMessage(
        conversationId: String,
        queuedMessageId: Long,
    ): Unit = live.dropQueuedMessage(conversationId, queuedMessageId)

    private companion object {
        const val NOT_CONNECTED = "No live relay connection"
    }
}
