package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.flow.StateFlow

/** Fresh-chat selection test: read a completed main reply from the current connection. */
internal suspend fun StateFlow<ConversationRepository?>.awaitFinishedSelectionReply(conversationId: String): Message =
    firstOnLive({ it.observeMessages(conversationId) }) { rows -> finishedSelectionReply(rows) != null }
        .let { checkNotNull(finishedSelectionReply(it)) }

private fun finishedSelectionReply(rows: List<ThreadItem>): Message? =
    rows.filterIsInstance<ThreadItem.MessageItem>().map { it.message }.lastOrNull {
        it.role == Role.Assistant && it.parentToolUseId.isEmpty() && !it.isStreaming && it.content.isNotBlank()
    }
