package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.AttachmentStore
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.cacheableThreadRows
import de.pyryco.mobile.data.cache.settledThreadRows
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one host's threads readable while that host is unreachable (#797).
 *
 * Wraps the host's [StableConversationRepository], which emits `emptyList()` between connections and
 * holds nothing across process death. Only [observeMessages] is touched: every other member — stall,
 * queue, API retry, compaction, thinking, usage limit, modals, one-shots — is plain delegation, so
 * nothing restored can reopen a prompt or restart an indicator. That live state is not thread state.
 *
 * The drawn thread is `live.mergeCachedRows(restored)`: the history merge's join from the other side,
 * with the live projection as receiver and the cached rows as the older set. That is the one dedup —
 * one join key per row kind — so a restored row the daemon re-delivers is never drawn twice, and
 * merging into an empty live projection returns the restored rows verbatim, which is the disconnected
 * case with no branch of its own. A row only the cache holds, such as an attachment offer the daemon
 * never replays, stays beside the cached row above it (#983). The history walk is untouched: it reads only a page's
 * cursor and `atStart`, so restored rows cannot tell it the log has started.
 *
 * The restored set is read **once per collection**, so a later failed read cannot blank rows already
 * drawn. It is the merge base while live rows flow: a row the live projection deliberately removes (a
 * dropped queued send's echo) is not resurrected from an ever-growing union.
 *
 * An empty live projection is a connection boundary — the stable facade emits `emptyList()` on every
 * disconnect and before a new connection's first page. There the base moves to the settled rows last
 * drawn ([settledThreadRows]), so losing reception keeps the thread on screen and never rewrites the
 * cache with the open-time snapshot, and the next connection's rows merge over everything drawn so far.
 *
 * If more than one page arrived while offline, a reconnect's newest page does not overlap the base's
 * tail: the base draws above a gap in arrival order until the reader scrolls up and the history walk,
 * whose pages land in the live projection, fills it.
 *
 * What is written is the thread as drawn, restored-plus-live, not the live projection alone: right
 * after a reconnect the live side holds only the newest page and would shrink the cache. It is written
 * only when its [cacheableThreadRows] differ from the last set written, so an `assistant_delta` stream
 * writes nothing until the turn settles. Holds no scope and launches nothing; cancellation is the
 * collector's.
 *
 * The thread's saved history position (#1354) passes straight through to the cache under [serverId]: the
 * row writer above keeps it, and the thread screen reads it at open and writes it when an ask settles.
 *
 * A confirmed [delete] also removes the conversation's cached content (#798) — the host is this
 * wrapper's own [serverId], captured from the destination that issued the call, never a global
 * selection. Archive and unarchive stay plain delegation: they are not removals.
 *
 * [retrieveAttachment] keeps a fetched file for this same [serverId] (#899) through [attachments], the
 * app's one host-keyed store; the fetch itself runs on the delegate's live connection. With no store it is
 * plain delegation.
 *
 * Never logs a row, a conversation id or a server id.
 */
class CachingConversationRepository(
    private val delegate: ConversationRepository,
    private val cache: ConversationCache,
    private val serverId: String,
    private val attachments: AttachmentStore? = null,
) : ConversationRepository by delegate {
    // Ids this destination deleted. The thread that issued the delete keeps collecting until its PopBack,
    // and a write from that collector after the removal would put the rows straight back.
    private val deleted = ConcurrentHashMap.newKeySet<String>()

    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
        flow {
            var base = cache.readThread(serverId, conversationId)
            var lastWritten = base
            var lastDrawn = base
            delegate.observeMessages(conversationId).collect { live ->
                if (live.isEmpty()) base = settledThreadRows(lastDrawn)
                val drawn = live.mergeCachedRows(base)
                lastDrawn = drawn
                emit(drawn)
                val cacheable = cacheableThreadRows(drawn)
                if (cacheable != lastWritten && conversationId !in deleted) {
                    // A failed write leaves lastWritten behind, so the next change retries it.
                    if (cache.writeThread(serverId, conversationId, cacheable).isSuccess) {
                        lastWritten = cacheable
                    } else {
                        RelayLog.d { "event=thread_cache_write_failed" }
                    }
                }
            }
        }

    /** This thread's saved history position (#1354), under this wrapper's own [serverId]. */
    override suspend fun readHistoryPosition(conversationId: String): HistoryPosition? = cache.readHistoryPosition(serverId, conversationId)

    /**
     * Saves [position] beside the thread's cached rows (#1354). Skipped for a conversation this destination
     * deleted, so a page settling after the delete cannot put the document back. A failed write is logged
     * and not surfaced: the next open re-fetches one page.
     */
    override suspend fun writeHistoryPosition(
        conversationId: String,
        position: HistoryPosition?,
    ) {
        if (conversationId in deleted) return
        cache
            .writeHistoryPosition(serverId, conversationId, position)
            .onFailure { RelayLog.d { "event=history_position_write_failed" } }
    }

    override suspend fun retrieveAttachment(
        conversationId: String,
        attachmentId: String,
    ): AttachmentRetrievalResult =
        attachments?.retrieve(serverId, conversationId, attachmentId) { delegate.fetchAttachment(conversationId, attachmentId) }
            ?: delegate.retrieveAttachment(conversationId, attachmentId)

    /**
     * Deletes on the daemon first; only once that succeeded does the cached copy go. A refused delete
     * propagates with the cache untouched, since the conversation still exists. A failed cache removal
     * is logged and not surfaced — the conversation is gone, and reporting a failure would claim it is
     * not. `NonCancellable` so a screen cleared mid-removal cannot strand the content.
     */
    override suspend fun delete(conversationId: String) {
        delegate.delete(conversationId)
        deleted += conversationId
        withContext(NonCancellable) { cache.removeConversation(serverId, conversationId) }
            .onFailure { RelayLog.d { "event=conversation_cache_remove_failed" } }
    }
}
