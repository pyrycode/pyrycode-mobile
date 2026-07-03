package de.pyryco.mobile.ui.conversations

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository

/**
 * A [ConversationRepository] decorator (#490) that delegates every read to a seeded
 * [FakeConversationRepository] — so each view-model's `state` pipeline still assembles from real seed
 * data — and throws [failWith] from the seven mutation methods the nine guarded one-shot launch sites
 * call. Interface delegation (`by delegate`) forwards `observe*`; only the mutations throw, so a test can
 * assert `launchGuardedRepoCall` swallows each of the three relay failure types (RelayErrorException /
 * IllegalStateException / UnsupportedOperationException) without an uncaught throw escaping viewModelScope.
 */
class ThrowingConversationRepository(
    private val failWith: Throwable,
    private val delegate: FakeConversationRepository = FakeConversationRepository(),
) : ConversationRepository by delegate {
    override suspend fun sendMessage(
        conversationId: String,
        text: String,
    ): Message = throw failWith

    override suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session = throw failWith

    override suspend fun archive(conversationId: String): Unit = throw failWith

    override suspend fun delete(conversationId: String): Unit = throw failWith

    override suspend fun rename(
        conversationId: String,
        name: String,
    ): Conversation = throw failWith

    override suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String?,
    ): Conversation = throw failWith

    override suspend fun createDiscussion(workspace: String?): Conversation = throw failWith
}
