package de.pyryco.mobile.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A live thread reading, including own user echoes that restored rows must not resurrect. */
data class ThreadSnapshot(
    val rows: List<ThreadItem>,
    val suppressedUserMessageIds: Set<String> = emptySet(),
)

/**
 * The cache reader's narrow companion to [ConversationRepository.observeMessages]. Rows and
 * suppression travel together, so a restore cannot draw a removed queued echo between subscriptions.
 * Suppression is connection-local, keyed by locally minted ids, and never persisted.
 */
interface ThreadSnapshotSource {
    fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot>
}

/** Repositories without queued-echo suppression retain their existing list-only read behavior. */
internal fun ConversationRepository.threadSnapshots(conversationId: String): Flow<ThreadSnapshot> =
    if (this is ThreadSnapshotSource) observeThreadSnapshot(conversationId) else observeMessages(conversationId).map { ThreadSnapshot(it) }
