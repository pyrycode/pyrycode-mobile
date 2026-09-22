package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.cacheableThreadRows
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Keeps one host's threads readable while that host is unreachable (#797).
 *
 * Wraps the host's [StableConversationRepository], which emits `emptyList()` between connections and
 * holds nothing across process death. Only [observeMessages] is touched: every other member — stall,
 * queue, API retry, compaction, thinking, usage limit, modals, one-shots — is plain delegation, so
 * nothing restored can reopen a prompt or restart an indicator. That live state is not thread state.
 *
 * The drawn thread is `live.mergeHistoryRows(restored)`: the restore is the history merge from the
 * other side, with the live projection as receiver and the cached rows as the older set. That is the
 * one dedup — one join key per row kind — so a restored row the daemon re-delivers is never drawn
 * twice, and merging into an empty live projection returns the restored rows verbatim, which is the
 * disconnected case with no branch of its own. The history walk is untouched: it reads only a page's
 * cursor and `atStart`, so restored rows cannot tell it the log has started.
 *
 * The restored set is read **once per collection** and kept for it. A later failed read therefore
 * cannot blank rows already drawn, and a row the live projection deliberately removes (a dropped
 * queued send's echo) is not resurrected from an ever-growing union.
 *
 * What is written is the thread as drawn, restored-plus-live, not the live projection alone: right
 * after a reconnect the live side holds only the newest page and would shrink the cache. It is written
 * only when its [cacheableThreadRows] differ from the last set written, so an `assistant_delta` stream
 * writes nothing until the turn settles. Holds no scope and launches nothing; cancellation is the
 * collector's.
 *
 * Never logs a row, a conversation id or a server id.
 */
class CachingConversationRepository(
    private val delegate: ConversationRepository,
    private val cache: ConversationCache,
    private val serverId: String,
) : ConversationRepository by delegate {
    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
        flow {
            val restored = cache.readThread(serverId, conversationId)
            var lastWritten = restored
            delegate.observeMessages(conversationId).collect { live ->
                val drawn = live.mergeHistoryRows(restored)
                emit(drawn)
                val cacheable = cacheableThreadRows(drawn)
                if (cacheable != lastWritten) {
                    // A failed write leaves lastWritten behind, so the next change retries it.
                    if (cache.writeThread(serverId, conversationId, cacheable).isSuccess) {
                        lastWritten = cacheable
                    } else {
                        RelayLog.d { "event=thread_cache_write_failed" }
                    }
                }
            }
        }
}
