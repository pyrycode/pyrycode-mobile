package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.flow.StateFlow

/** Fresh-chat selection test: read a completed main reply from the current connection. */
internal suspend fun StateFlow<ConversationRepository?>.awaitFinishedSelectionReply(conversationId: String): Message =
    firstOnLive({ harnessThreadEvidence(it).observe(conversationId) }) { it.loadedMainReply() != null }
        .let { checkNotNull(it.loadedMainReply()) }
