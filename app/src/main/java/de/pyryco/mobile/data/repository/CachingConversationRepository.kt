package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.AttachmentStore
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.cacheableThreadRows
import de.pyryco.mobile.data.cache.settledThreadRows
import de.pyryco.mobile.data.cache.threadRowsWereTrimmed
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ordinaryId
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
 * An empty live projection without suppressed echoes is a connection boundary — the stable facade emits `emptyList()` on every
 * disconnect and before a new connection's first page. There the base moves to the settled rows last
 * drawn ([settledThreadRows]), so losing reception keeps the thread on screen and never rewrites the
 * cache with the open-time snapshot, and the next connection's rows merge over everything drawn so far.
 *
 * If more than one page arrived while offline, a reconnect's newest page does not overlap the base's
 * tail: the base draws above a gap in arrival order. With no saved history position the reader's first
 * pull asks for the newest page, and the walk, whose pages land in the live projection, can fill it. Once
 * a position is saved (#1354) the walk continues from older than the cached rows and never returns to
 * the newest page, so the gap stays until that position is cleared.
 *
 * What is written is the thread as drawn, restored-plus-live, not the live projection alone: right
 * after a reconnect the live side holds only the newest page and would shrink the cache. It is written
 * only when its [cacheableThreadRows] differ from the last set written, so an `assistant_delta` stream
 * writes nothing until the turn settles. A collection-owned writer coalesces settled changes for
 * 100 ms without delaying subsequent snapshots, and flushes accepted rows during orderly cleanup.
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
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ConversationRepository by delegate,
    ThreadSnapshotSource {
    // Ids this destination deleted. The thread that issued the delete keeps collecting until its PopBack,
    // and a write from that collector after the removal would put the rows straight back.
    private val deleted = ConcurrentHashMap.newKeySet<String>()
    private val historyWrites = Mutex()

    private data class DrawnThread(
        val rows: List<ThreadItem>,
        val generation: Long,
    )

    private val generations = AtomicLong()
    private val drawnThreads = ConcurrentHashMap<String, DrawnThread>()

    // Updated under historyWrites by both row writers; coverage alone supersedes older candidates.
    private data class PersistedThread(
        val coverageGeneration: Long,
        val cacheable: List<ThreadItem>,
    )

    private val persistedThreads = ConcurrentHashMap<String, PersistedThread>()

    private data class CacheCandidate(
        val drawn: DrawnThread,
        val cacheable: List<ThreadItem>,
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private inner class ThreadWriter(
        private val conversationId: String,
        private val scope: CoroutineScope,
        restored: List<ThreadItem>,
    ) {
        private val latest = AtomicReference<CacheCandidate?>()
        private val retry = AtomicBoolean()
        private val signals = Channel<Unit>(Channel.CONFLATED)
        private var timer: Job? = null
        private val initial = restored
        private val writer =
            scope.launch(processingDispatcher, start = CoroutineStart.UNDISPATCHED) {
                for (signal in signals) {
                    val candidate = latest.get() ?: continue
                    if (candidate.ready.isCompleted) persist(candidate)
                }
            }

        // Called sequentially on the processing dispatcher; disk work never holds this path.
        fun accept(drawn: DrawnThread) {
            val cacheable = cacheableThreadRows(drawn.rows)
            val previous = latest.get()
            val changed = cacheable != (previous?.cacheable ?: initial)
            val persisted = persistedThreads[conversationId]
            // A coverage save can change disk rows without the observer ever seeing its snapshot.
            val superseded =
                persisted != null &&
                    (previous?.drawn?.generation ?: 0) <= persisted.coverageGeneration &&
                    drawn.generation > persisted.coverageGeneration &&
                    cacheable != persisted.cacheable
            if (!changed && !superseded && !retry.getAndSet(false)) return
            retry.set(false)
            val candidate = CacheCandidate(drawn, cacheable)
            latest.set(candidate)
            timer?.cancel()
            // Signal readiness separately so a long write cannot queue obsolete intermediate rows.
            timer =
                scope.launch {
                    delay(100)
                    candidate.ready.complete(Unit)
                    signals.trySend(Unit)
                }
        }

        private suspend fun persist(candidate: CacheCandidate) {
            val result =
                historyWrites.withLock {
                    val persisted = persistedThreads[conversationId]
                    if (conversationId in deleted ||
                        candidate.drawn.generation <= (persisted?.coverageGeneration ?: 0)
                    ) {
                        return@withLock null
                    }
                    val baseline = persisted?.cacheable ?: initial
                    if (candidate.cacheable == baseline) return@withLock Result.success(Unit)
                    // Untrimmed drawn rows let the cache invalidate cursor/stop claims on retention loss.
                    val result = cache.writeThread(serverId, conversationId, candidate.drawn.rows)
                    if (result.isSuccess) {
                        persistedThreads[conversationId] = PersistedThread(persisted?.coverageGeneration ?: 0, candidate.cacheable)
                    }
                    result
                } ?: return
            if (result.isFailure) {
                retry.set(true)
                RelayLog.d { "event=thread_cache_write_failed" }
            }
        }

        suspend fun finish() {
            withContext(NonCancellable + processingDispatcher) {
                timer?.cancelAndJoin()
                writer.cancelAndJoin()
                signals.close()
                latest.get()?.let { persist(it) }
            }
        }
    }

    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
        flow {
            var previous: List<ThreadItem>? = null
            observeThreadSnapshot(conversationId).collect { snapshot ->
                if (withContext(processingDispatcher) { snapshot.rows != previous }) {
                    previous = snapshot.rows
                    emit(snapshot.rows)
                }
            }
        }

    override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> =
        flow {
            var base = cache.readThread(serverId, conversationId)
            val savedPosition = cache.readHistoryPosition(serverId, conversationId)
            var baseOrder =
                withContext(processingDispatcher) {
                    base.receivedUnsignedHistoryOrder(savedPosition?.coverage?.unsignedPositions().orEmpty())
                }
            var lastOrder = emptyMap<Any, ULong>()
            var lastDrawn = base
            coroutineScope {
                val writer = ThreadWriter(conversationId, this, base)
                try {
                    delegate.threadSnapshots(conversationId).collect { snapshot ->
                        val generation = generations.incrementAndGet()
                        val drawn =
                            withContext(processingDispatcher) {
                                val live = snapshot.rows
                                // Awaiting delivery can hide the only live row; that is not a connection boundary.
                                if (live.isEmpty() && snapshot.suppressedUserMessageIds.isEmpty()) {
                                    base = settledThreadRows(lastDrawn)
                                    baseOrder = baseOrder + lastOrder
                                }
                                val restored =
                                    if (snapshot.suppressedUserMessageIds.isEmpty()) {
                                        base
                                    } else {
                                        base.filterNot {
                                            it is ThreadItem.MessageItem &&
                                                it.message.role == Role.User &&
                                                it.message.ordinaryId in snapshot.suppressedUserMessageIds
                                        }
                                    }
                                val drawn =
                                    live.mergeUnsignedCachedRows(
                                        restored,
                                        baseOrder + snapshot.unsignedHistoryOrder,
                                        rendererOwners = lastDrawn,
                                    )
                                lastDrawn = drawn
                                lastOrder = snapshot.unsignedHistoryOrder
                                drawn
                            }
                        val published = DrawnThread(drawn, generation)
                        drawnThreads[conversationId] = published
                        emit(snapshot.copy(rows = drawn))
                        withContext(processingDispatcher) { writer.accept(published) }
                    }
                } finally {
                    writer.finish()
                }
            }
        }

    /** This thread's saved history position (#1354), under this wrapper's own [serverId]. */
    override suspend fun readHistoryPosition(conversationId: String): HistoryPosition? {
        val saved = cache.readHistoryPosition(serverId, conversationId)
        val empty = cache.readThread(serverId, conversationId).isEmpty()
        // Omitted unsigned content remains evidence even when cache policy retains no rows.
        if (empty &&
            saved?.coverage?.unsignedIncomplete != true &&
            saved
                ?.coverage
                ?.unsignedSpans
                .orEmpty()
                .isEmpty()
        ) {
            return null
        }
        if (saved?.coverage != null) return saved.copy(atStart = saved.atStart && !saved.coverage.unsignedIncomplete)
        return (saved ?: HistoryPosition("", false)).copy(coverage = HistoryCoverage(unknown = true))
    }

    /**
     * Saves [position] beside the thread's cached rows (#1354). Skipped for a conversation this destination
     * deleted, so a page settling after the delete cannot put the document back. A failed write is logged
     * and not surfaced: the next open re-fetches one page.
     */
    override suspend fun writeHistoryPosition(
        conversationId: String,
        position: HistoryPosition?,
    ) {
        if (position?.coverage == null) {
            historyWrites.withLock {
                if (conversationId !in deleted) {
                    cache
                        .writeHistoryPosition(serverId, conversationId, position)
                        .onFailure { RelayLog.d { "event=history_position_write_failed" } }
                }
            }
            return
        }
        val snapshot = delegate.threadSnapshots(conversationId).first()
        val historyGeneration = generations.incrementAndGet()
        historyWrites.withLock {
            if (conversationId in deleted) return@withLock
            val drawn = drawnThreads[conversationId]
            val base = drawn?.rows ?: cache.readThread(serverId, conversationId)
            if (conversationId in deleted) return@withLock
            val restored =
                base.filterNot {
                    it is ThreadItem.MessageItem &&
                        it.message.role == Role.User &&
                        it.message.ordinaryId in snapshot.suppressedUserMessageIds
                }
            val order =
                (snapshot.rows + restored).receivedUnsignedHistoryOrder(position.coverage.unsignedPositions()) +
                    snapshot.unsignedHistoryOrder
            val rows = snapshot.rows.mergeUnsignedCachedRows(restored, order, rendererOwners = base)
            val cacheable = withContext(processingDispatcher) { cacheableThreadRows(rows) }
            if (cache.writeThread(serverId, conversationId, rows).isFailure) {
                RelayLog.d { "event=history_rows_write_failed" }
                return@withLock
            }
            persistedThreads[conversationId] = PersistedThread(maxOf(historyGeneration, drawn?.generation ?: 0), cacheable)
            if (conversationId in deleted) return@withLock
            val trimmed = threadRowsWereTrimmed(rows)
            val coverage = position.coverage.boundTo(rows)
            cache
                .writeHistoryPosition(
                    serverId,
                    conversationId,
                    position.copy(
                        cursor = if (trimmed) "" else position.cursor,
                        atStart = !trimmed && !coverage.unsignedIncomplete && position.atStart,
                        coverage = coverage,
                    ),
                ).onFailure { RelayLog.d { "event=history_position_write_failed" } }
        }
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
        withContext(NonCancellable) {
            historyWrites.withLock {
                drawnThreads.remove(conversationId)
                persistedThreads.remove(conversationId)
                cache.removeConversation(serverId, conversationId)
            }
        }.onFailure { RelayLog.d { "event=conversation_cache_remove_failed" } }
    }
}
