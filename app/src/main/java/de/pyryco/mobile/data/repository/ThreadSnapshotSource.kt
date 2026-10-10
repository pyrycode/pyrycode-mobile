package de.pyryco.mobile.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A live thread reading, including own user echoes that restored rows must not resurrect. */
data class ThreadSnapshot(
    val rows: List<ThreadItem>,
    val suppressedUserMessageIds: Set<String> = emptySet(),
    /** Received durable positions from the same projection generation; live ids never enter this map. */
    val historyOrder: Map<Any, Long> = emptyMap(),
    /** Authoritative unsigned positions, scoped by the source host and observed conversation. */
    val unsignedHistoryOrder: Map<Any, ULong> = historyOrder.filterValues { it > 0 }.mapValues { it.value.toULong() },
    val readEvidence: ThreadReadEvidence = ThreadReadEvidence(),
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

/** Compatibility cannot represent an upper-range position under a different signed value. */
internal fun Map<Any, ULong>.signedHistoryOrder(): Map<Any, Long> =
    mapNotNull { (key, id) -> id.takeIf { it > 0u && it <= Long.MAX_VALUE.toULong() }?.let { key to it.toLong() } }.toMap()
