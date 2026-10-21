package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.AttachmentStore
import de.pyryco.mobile.data.cache.AuthoritativeRemovalRejected
import de.pyryco.mobile.data.cache.AuthoritativeUpsertCache
import de.pyryco.mobile.data.cache.AuthoritativeWindowCache
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.OwnedLegacyCache
import de.pyryco.mobile.data.cache.OwnedLegacyCoverageCache
import de.pyryco.mobile.data.cache.OwnedLegacyRow
import de.pyryco.mobile.data.cache.SavedAuthoritativeThread
import de.pyryco.mobile.data.cache.applyOwnedRows
import de.pyryco.mobile.data.cache.cacheableThreadRows
import de.pyryco.mobile.data.cache.legacyRowKey
import de.pyryco.mobile.data.cache.sameSavedStructure
import de.pyryco.mobile.data.cache.settledThreadRows
import de.pyryco.mobile.data.cache.threadRowsWereTrimmed
import de.pyryco.mobile.data.cache.toSavedAuthoritativeThread
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
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
    private val authoritativeRemote = (delegate as? RemoteConversationRepository)?.takeIf { it.authoritativeStoreFor(serverId) != null }

    // #2175: production reaches the host's shared store through the forwarded route, never another host's.
    private val hostThread = delegate.negotiatedThread?.takeIf { it.host == serverId }
    internal val authoritativeThreads =
        hostThread?.store ?: authoritativeRemote?.authoritativeStoreFor(serverId) ?: AuthoritativeThreadStore()

    /** The host route with this cache's restore-before-query observation and opening (#2175). */
    override val negotiatedThread: NegotiatedThread? =
        hostThread?.let { route ->
            object : NegotiatedThread() {
                override val host: String = route.host
                override val support: StateFlow<ThreadSupport> get() = route.support
                override val store: AuthoritativeThreadStore get() = authoritativeThreads

                override fun observe(conversationId: String): Flow<AuthoritativeThreadSnapshot> = observeAuthoritativeThread(conversationId)

                override fun progress(
                    conversationId: String,
                    connection: Any,
                ): Flow<AuthoritativeLiveProgress> = route.progress(conversationId, connection)

                override suspend fun open(conversationId: String): Boolean = openAuthoritativeThread(conversationId)

                override suspend fun acquire(
                    conversationId: String,
                    connection: Any,
                ): java.io.Closeable? {
                    restoreAuthoritative(conversationId)
                    if (conversationId in deleted) return null
                    return route.acquire(conversationId, connection)
                }

                override fun older(conversationId: String): Boolean =
                    conversationId !in deleted && (hydrateOlderAuthoritative(conversationId) || route.older(conversationId))

                override fun cancel(conversationId: String) = route.cancel(conversationId)
            }
        }
    private val persistedAuthoritative = ConcurrentHashMap<String, CacheCandidate>()
    private val authoritativeOpeningLocks = ConcurrentHashMap<String, Mutex>()
    private val authoritativeOpenings = ConcurrentHashMap<String, SavedAuthoritativeThread>()
    private val pendingAuthoritativeOpenings = ConcurrentHashMap<String, de.pyryco.mobile.data.cache.AuthoritativeThreadWindow>()
    private val authoritativeLifetimes = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val authoritativeReaders = ConcurrentHashMap<String, CoroutineScope>()
    private val authoritativeHydrations = ConcurrentHashMap<CoroutineScope, Job>()
    private val authoritativeHydrated = ConcurrentHashMap.newKeySet<String>()

    private fun authoritativeCurrent(conversationId: String): Boolean =
        authoritativeLifetimes[conversationId]?.let {
            (cache as? AuthoritativeWindowCache)?.authoritativeLifetime(serverId, conversationId) == it
        } != false

    /** The compatibility read belongs to this observation and is triggered only by reader demand. */
    private fun hydrateOlderAuthoritative(conversationId: String): Boolean {
        if (cache !is AuthoritativeWindowCache ||
            conversationId in authoritativeHydrated ||
            authoritativeThreads.current(serverId, conversationId).generation != 0L ||
            !authoritativeCurrent(conversationId)
        ) {
            return false
        }
        val reader = authoritativeReaders[conversationId]?.takeIf { it.isActive } ?: return false
        authoritativeHydrations.compute(reader) { _, held ->
            held?.takeIf { it.isActive } ?: reader.launch {
                val restored =
                    try {
                        restoreAuthoritative(conversationId, full = true)
                    } catch (_: java.io.IOException) {
                        null
                    } catch (_: SecurityException) {
                        null
                    }
                if (restored != null) {
                    authoritativeHydrated += conversationId
                    RelayLog.d { "event=authoritative_restore code=reader_hydrated" }
                } else if (isActive && conversationId !in deleted && authoritativeCurrent(conversationId)) {
                    // The asynchronous local attempt owns this demand only when it actually applies.
                    hostThread?.older(conversationId)
                    RelayLog.d { "event=authoritative_restore code=reader_forwarded" }
                }
            }
        }
        return true
    }

    private data class DrawnThread(
        val rows: List<ThreadItem>,
        val generation: Long,
    )

    private val generations = AtomicLong()
    private val drawnThreads = ConcurrentHashMap<String, DrawnThread>()

    // Updated under historyWrites by both row writers; successful captures fence older candidates.
    private data class PersistedThread(
        val coverageGeneration: Long,
        val coverageRevision: Long,
        val cacheable: List<ThreadItem>,
        val generation: Long = coverageGeneration,
    )

    private val persistedThreads = ConcurrentHashMap<String, PersistedThread>()
    private val observedPositions = ConcurrentHashMap<String, HistoryPosition>()

    private data class AcceptedLegacyRow(
        val capture: Long,
        val order: Int,
        val change: OwnedLegacyRow?,
    )

    private val acceptedRows = ConcurrentHashMap<String, ConcurrentHashMap<String, AcceptedLegacyRow>>()

    private fun retireAcceptedRows(
        conversationId: String,
        through: Long,
    ) {
        acceptedRows.computeIfPresent(conversationId) { _, intents ->
            intents.forEach { (key, intent) ->
                if (intent.capture <= through) intents.remove(key, intent)
            }
            intents.takeIf { it.isNotEmpty() }
        }
    }

    private data class CacheCandidate(
        val drawn: DrawnThread,
        val cacheable: List<ThreadItem>,
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
        val authoritative: SavedAuthoritativeThread? = null,
        val storeGeneration: Long = 0,
        val authoritativeBaseline: SavedAuthoritativeThread? = null,
        val authoritativeOrderMoves: Map<Long, AuthoritativeOrderMove> = emptyMap(),
        val owned: Set<String> = emptySet(),
        val rowGenerations: Map<String, Long> = emptyMap(),
        val removedGenerations: Map<String, Long> = emptyMap(),
    )

    private inner class ThreadWriter(
        private val conversationId: String,
        private val scope: CoroutineScope,
        restored: List<ThreadItem>,
        private val collectionCoverageRevision: Long,
        private val initialAuthoritative: SavedAuthoritativeThread? = null,
    ) {
        private val latest = AtomicReference<CacheCandidate?>()
        private val retry = AtomicBoolean()
        private val signals = Channel<Unit>(Channel.CONFLATED)
        private var timer: Job? = null
        private val initial = restored
        private val owned by lazy { restored.mapTo(HashSet()) { it.legacyRowKey() } }
        private val seen by lazy { restored.associateByTo(HashMap()) { it.legacyRowKey() } }
        private val rowGenerations = HashMap<String, Long>()
        private val removedGenerations = HashMap<String, Long>()
        private var previousRows = restored
        private var completed: CacheCandidate? = null
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
            if (cache is OwnedLegacyCache) {
                val present = drawn.rows.mapTo(HashSet()) { it.legacyRowKey() }
                owned += present
                // Losing an unsettled row or excluding it at a disconnect never authorizes removal.
                cacheableThreadRows(previousRows).forEach { row ->
                    val key = row.legacyRowKey()
                    if (key !in present) {
                        removedGenerations[key] = drawn.generation
                        seen.remove(key)
                    }
                }
                previousRows = drawn.rows
                cacheable.forEach { row ->
                    val key = row.legacyRowKey()
                    if (seen[key] != row) rowGenerations[key] = drawn.generation
                    seen[key] = row
                    removedGenerations.remove(key)
                }
            }
            val previous = latest.get()
            val changed = cacheable != (previous?.cacheable ?: initial)
            val persisted = persistedThreads[conversationId]
            // Completion order identifies overlapping saves; capture order rejects stale snapshots.
            val superseded =
                persisted != null &&
                    persisted.coverageRevision > collectionCoverageRevision &&
                    (previous?.drawn?.generation ?: 0) <= persisted.coverageGeneration &&
                    drawn.generation > persisted.coverageGeneration &&
                    cacheable != persisted.cacheable
            if (superseded && cache is OwnedLegacyCache) {
                val present = drawn.rows.mapTo(HashSet()) { it.legacyRowKey() }
                persisted?.cacheable.orEmpty().forEach { row ->
                    val key = row.legacyRowKey()
                    if (key !in present) {
                        owned += key
                        removedGenerations[key] = drawn.generation
                    }
                }
            }
            if (!changed && !superseded && removedGenerations == previous?.removedGenerations.orEmpty() && !retry.getAndSet(false)) return
            retry.set(false)
            val candidate =
                CacheCandidate(
                    drawn,
                    cacheable,
                    owned = if (cache is OwnedLegacyCache) owned.toSet() else emptySet(),
                    rowGenerations = rowGenerations.toMap(),
                    removedGenerations = removedGenerations.toMap(),
                )
            if (cache is OwnedLegacyCoverageCache) {
                val keys = cacheable.map { it.legacyRowKey() }
                acceptedRows.compute(conversationId) { _, held ->
                    val intents = held ?: ConcurrentHashMap()
                    // Acceptance can race success publication; use the current fence inside the same map operation as pruning.
                    val fence = persistedThreads[conversationId]?.generation ?: 0
                    cacheable.forEachIndexed { index, row ->
                        val capture = rowGenerations[keys[index]] ?: return@forEachIndexed
                        if (capture <= fence) return@forEachIndexed
                        val intent =
                            AcceptedLegacyRow(capture, index, OwnedLegacyRow(row, keys.getOrNull(index - 1), keys.getOrNull(index + 1)))
                        intents.compute(keys[index]) { _, old -> if (old != null && old.capture >= capture) old else intent }
                    }
                    removedGenerations.forEach { (key, capture) ->
                        if (capture > fence) {
                            val intent = AcceptedLegacyRow(capture, Int.MAX_VALUE, null)
                            intents.compute(key) { _, old -> if (old != null && old.capture >= capture) old else intent }
                        }
                    }
                    intents.takeIf { it.isNotEmpty() }
                }
            }
            schedule(candidate)
        }

        fun accept(
            snapshot: AuthoritativeThreadSnapshot,
            capture: Long,
        ) {
            if (snapshot.epoch == null) return
            val saved = snapshot.toSavedAuthoritativeThread()
            val previous = latest.get()
            if (saved == (previous?.authoritative ?: initialAuthoritative) &&
                snapshot.generation == (previous?.storeGeneration ?: 0) &&
                snapshot.orderMoves == previous?.authoritativeOrderMoves.orEmpty() &&
                !retry.getAndSet(false)
            ) {
                return
            }
            retry.set(false)
            val candidate =
                CacheCandidate(
                    DrawnThread(emptyList(), capture),
                    emptyList(),
                    authoritative = saved,
                    storeGeneration = snapshot.generation,
                    authoritativeBaseline = initialAuthoritative,
                    authoritativeOrderMoves = snapshot.orderMoves,
                )
            schedule(candidate, immediate = snapshot.generation > (previous?.storeGeneration ?: 0))
        }

        private fun schedule(
            candidate: CacheCandidate,
            immediate: Boolean = false,
        ) {
            latest.set(candidate)
            timer?.cancel()
            // Signal readiness separately so a long write cannot queue obsolete intermediate rows.
            timer =
                scope.launch {
                    if (!immediate) delay(100)
                    candidate.ready.complete(Unit)
                    signals.trySend(Unit)
                }
        }

        private suspend fun persist(
            candidate: CacheCandidate,
            refreshReset: Boolean = false,
        ) {
            if (candidate === completed && !refreshReset) return
            val result =
                historyWrites.withLock {
                    if (candidate.authoritative != null) return@withLock persistAuthoritative(conversationId, candidate, refreshReset)
                    val persisted = persistedThreads[conversationId]
                    if (conversationId in deleted ||
                        candidate.drawn.generation <= (persisted?.generation ?: 0)
                    ) {
                        return@withLock null
                    }
                    val baseline = persisted?.cacheable ?: initial
                    // Untrimmed drawn rows let the cache invalidate cursor/stop claims on retention loss.
                    // Finish mutation and bookkeeping together: cancellation can otherwise hide a committed write.
                    withContext(NonCancellable) {
                        val ownedCache = cache as? OwnedLegacyCache
                        val previous = baseline.associateBy { it.legacyRowKey() }
                        val removed =
                            candidate.removedGenerations
                                .filter { (key, capture) ->
                                    key in candidate.owned &&
                                        capture > (persisted?.generation ?: 0)
                                }.keys
                        val keys = candidate.cacheable.map { it.legacyRowKey() }
                        val nextHeld = arrayOfNulls<String>(keys.size)
                        var next: String? = null
                        for (index in keys.indices.reversed()) {
                            nextHeld[index] = next
                            if (keys[index] in previous) next = keys[index]
                        }
                        val changes =
                            candidate.cacheable.mapIndexedNotNull { index, row ->
                                if (row == previous[keys[index]] ||
                                    (ownedCache != null && (candidate.rowGenerations[keys[index]] ?: 0) <= (persisted?.generation ?: 0))
                                ) {
                                    null
                                } else {
                                    OwnedLegacyRow(row, keys.getOrNull(index - 1), nextHeld[index])
                                }
                            }
                        val result =
                            when {
                                ownedCache != null && (changes.isNotEmpty() || removed.isNotEmpty()) ->
                                    ownedCache.mutateLegacyRows(serverId, conversationId, changes, removed)
                                ownedCache != null || candidate.cacheable == baseline -> Result.success(Unit)
                                else -> cache.writeThread(serverId, conversationId, candidate.drawn.rows)
                            }
                        if (result.isSuccess) {
                            val successful = if (ownedCache == null) candidate.cacheable else applyOwnedRows(baseline, changes, removed)
                            persistedThreads[conversationId] =
                                PersistedThread(
                                    persisted?.coverageGeneration ?: 0,
                                    persisted?.coverageRevision ?: 0,
                                    successful,
                                    candidate.drawn.generation,
                                )
                            retireAcceptedRows(conversationId, candidate.drawn.generation)
                        }
                        result
                    }
                } ?: return
            if (result.isSuccess) {
                completed = candidate
            } else {
                retry.set(true)
                RelayLog.d { "event=thread_cache_write_failed" }
            }
        }

        suspend fun finish(authoritative: Boolean = false) {
            withContext(NonCancellable + processingDispatcher) {
                timer?.cancelAndJoin()
                writer.cancelAndJoin()
                signals.close()
                if (authoritative) {
                    // Delivery can hold an earlier snapshot while the remote retires its generation.
                    val snapshot = authoritativeThreads.current(serverId, conversationId)
                    if (snapshot.epoch != null &&
                        (
                            snapshot.generation > (latest.get()?.storeGeneration ?: 0) ||
                                snapshot.retirements !=
                                latest
                                    .get()
                                    ?.authoritative
                                    ?.retirements
                                    .orEmpty()
                        )
                    ) {
                        latest.set(
                            CacheCandidate(
                                DrawnThread(emptyList(), generations.incrementAndGet()),
                                emptyList(),
                                authoritative =
                                    retirementSafeSnapshot(
                                        snapshot,
                                        latest.get()?.authoritative ?: initialAuthoritative,
                                    ).toSavedAuthoritativeThread(),
                                storeGeneration = snapshot.generation,
                                authoritativeOrderMoves =
                                    if (snapshot.generation == 0L && snapshot.openingRows != null) {
                                        latest.get()?.authoritativeOrderMoves.orEmpty()
                                    } else {
                                        snapshot.orderMoves
                                    },
                            ),
                        )
                    }
                }
                latest.get()?.let { persist(it, refreshReset = authoritative) }
            }
        }
    }

    /** Called only under historyWrites; started mutations finish with their successful baseline. */
    private suspend fun persistAuthoritative(
        conversationId: String,
        candidate: CacheCandidate,
        refreshReset: Boolean = false,
    ): Result<Unit>? {
        var selected = candidate
        while (true) {
            val current = authoritativeThreads.current(serverId, conversationId)
            // Retirement can arrive while cleanup waits for the lock or a final mutation.
            if (current.epoch != null &&
                (
                    (refreshReset && current.generation > selected.storeGeneration) ||
                        (current.generation == selected.storeGeneration && current.retirements != selected.authoritative?.retirements)
                )
            ) {
                selected =
                    CacheCandidate(
                        DrawnThread(emptyList(), generations.incrementAndGet()),
                        emptyList(),
                        authoritative = retirementSafeSnapshot(current, selected.authoritative).toSavedAuthoritativeThread(),
                        storeGeneration = current.generation,
                        authoritativeOrderMoves =
                            if (current.generation == 0L && current.openingRows != null) {
                                selected.authoritativeOrderMoves
                            } else {
                                current.orderMoves
                            },
                    )
            }
            val saved = selected.authoritative ?: return null
            val persisted = persistedAuthoritative[conversationId]
            if (conversationId in deleted ||
                !authoritativeCurrent(conversationId) ||
                selected.storeGeneration != current.generation ||
                selected.drawn.generation <= (persisted?.drawn?.generation ?: 0)
            ) {
                return null
            }
            val result =
                withContext(NonCancellable) {
                    val result =
                        if (saved == persisted?.authoritative &&
                            selected.storeGeneration == persisted?.storeGeneration &&
                            selected.authoritativeOrderMoves == persisted.authoritativeOrderMoves
                        ) {
                            Result.success(Unit)
                        } else {
                            val owned = cache as? AuthoritativeUpsertCache
                            val reset = selected.storeGeneration > (persisted?.storeGeneration ?: 0)
                            if (owned == null) {
                                if (reset) {
                                    cache.resetAuthoritativeThread(serverId, conversationId, saved)
                                } else {
                                    cache.writeAuthoritativeThread(serverId, conversationId, saved)
                                }
                            } else {
                                val baseline =
                                    (persisted?.authoritative ?: selected.authoritativeBaseline)
                                        ?.takeIf { !reset && it.epoch == saved.epoch }
                                val previous = baseline?.items?.associateBy { it["id"] }.orEmpty()
                                val changed = saved.items.filter { it != previous[it["id"]] }
                                val successfulMoves =
                                    persisted
                                        ?.takeIf { it.storeGeneration == selected.storeGeneration }
                                        ?.authoritativeOrderMoves
                                        .orEmpty()
                                val moves =
                                    saved.items
                                        .mapNotNull { raw ->
                                            val id = raw.getValue("id").toString().toLong()
                                            val move = selected.authoritativeOrderMoves[id]
                                            move?.takeIf { it.capture > (successfulMoves[id]?.capture ?: 0) }?.let { id to it.before }
                                        }.toMap()
                                owned.upsertAuthoritativeThread(
                                    serverId,
                                    conversationId,
                                    saved.copy(
                                        items = changed,
                                        retirements =
                                            saved.retirements.filterKeys {
                                                it !in
                                                    baseline?.retirements.orEmpty()
                                            },
                                    ),
                                    reset,
                                    baseline?.copy(items = emptyList()),
                                    moves,
                                )
                            }
                        }
                    (result.exceptionOrNull() as? AuthoritativeRemovalRejected)?.let { rejected ->
                        authoritativeThreads.rejectRemoval(
                            serverId,
                            conversationId,
                            selected.storeGeneration,
                            saved.incarnation,
                            saved.retirements.getValue(rejected.held.id),
                            rejected.held,
                        )
                    }
                    if (result.isSuccess) {
                        persistedAuthoritative[conversationId] = selected
                        authoritativeThreads.commitRemovals(
                            serverId,
                            conversationId,
                            selected.storeGeneration,
                            saved.incarnation,
                            saved.retirements,
                        )
                    }
                    result
                }
            if (result.isFailure ||
                !refreshReset ||
                authoritativeThreads.current(serverId, conversationId).let {
                    it.generation <= selected.storeGeneration && it.retirements == selected.authoritative?.retirements
                }
            ) {
                return result
            }
        }
    }

    /** Retirement may flush without delivery; it cannot admit unrelated unvalidated live payloads. */
    private fun retirementSafeSnapshot(
        current: AuthoritativeThreadSnapshot,
        accepted: SavedAuthoritativeThread?,
    ): AuthoritativeThreadSnapshot {
        if (current.generation > 0) return current
        if (accepted == null) {
            return if (hostThread != null && cache is AuthoritativeWindowCache) {
                current.copy(items = emptyList(), checkpoint = 0, coverage = null)
            } else {
                current
            }
        }
        if (current.openingRows == null) return current
        val validated = accepted.toSnapshot()
        return validated.copy(
            items = validated.items.filterNot { it.id in current.retirements },
            retirements = current.retirements,
            completedVersion = maxOf(validated.completedVersion, current.completedVersion),
            checkpoint = 0,
            coverage = null,
        )
    }

    private suspend fun restoreAuthoritative(
        conversationId: String,
        full: Boolean = false,
    ): SavedAuthoritativeThread? {
        val indexed = (cache as? AuthoritativeWindowCache)?.takeIf { hostThread != null }
        if (indexed == null) {
            val restored = applyAuthoritativeRestore(conversationId) { cache.readAuthoritativeThread(serverId, conversationId) }
            return (restored as? AuthoritativeRestore.Validated)?.saved
        }
        return authoritativeOpeningLocks.computeIfAbsent(conversationId) { Mutex() }.withLock {
            if (!authoritativeCurrent(conversationId)) return@withLock null
            if (!full) authoritativeOpenings[conversationId]?.let { return@withLock it }
            authoritativeLifetimes.putIfAbsent(conversationId, indexed.authoritativeLifetime(serverId, conversationId))
            var openingRows: Set<Pair<Long, Boolean>>? = null
            val restored =
                applyAuthoritativeRestore(conversationId, extendBaseline = full, windowRows = { openingRows }) {
                    if (full) {
                        cache.readAuthoritativeThread(serverId, conversationId)
                    } else {
                        (
                            pendingAuthoritativeOpenings[conversationId] ?: indexed
                                .newestAuthoritativeWindow(serverId, conversationId, 200)
                        )?.takeIf { it.serverId == serverId && it.conversationId == conversationId }
                            ?.also {
                                pendingAuthoritativeOpenings[conversationId] = it
                                openingRows = it.rows
                            }?.saved
                    }
                }
            val saved = (restored as? AuthoritativeRestore.Validated)?.saved
            if (saved != null && !full) authoritativeOpenings[conversationId] = saved
            if (saved != null || restored == AuthoritativeRestore.Stale) pendingAuthoritativeOpenings.remove(conversationId)
            saved
        }
    }

    private sealed interface AuthoritativeRestore {
        data class Validated(
            val saved: SavedAuthoritativeThread,
            val snapshot: AuthoritativeThreadSnapshot,
        ) : AuthoritativeRestore

        data object Unavailable : AuthoritativeRestore

        data object Stale : AuthoritativeRestore
    }

    private suspend fun applyAuthoritativeRestore(
        conversationId: String,
        extendBaseline: Boolean = false,
        preserveOpeningRows: Boolean = false,
        windowRows: () -> Set<Pair<Long, Boolean>>? = { null },
        read: suspend () -> SavedAuthoritativeThread?,
    ): AuthoritativeRestore {
        if (conversationId in deleted) return AuthoritativeRestore.Stale
        val generation = authoritativeThreads.current(serverId, conversationId).generation
        val initial = read() ?: return AuthoritativeRestore.Unavailable
        return withContext(processingDispatcher) {
            var saved = initial
            val indexed = (cache as? AuthoritativeWindowCache)?.takeIf { hostThread != null }
            var applied: AuthoritativeThreadSnapshot? = null
            do {
                val resolved = resolveAuthoritativeRestore(conversationId, saved, generation, indexed)
                if (resolved !is AuthoritativeRestore.Validated) return@withContext resolved
                saved = resolved.saved
                val captured = resolved.snapshot
                historyWrites.withLock {
                    val restoredSnapshot =
                        if (conversationId in deleted || !authoritativeCurrent(conversationId)) {
                            null
                        } else {
                            synchronized(authoritativeThreads) {
                                val outcome =
                                    authoritativeThreads.restore(
                                        serverId,
                                        conversationId,
                                        saved.toSnapshot(),
                                        generation,
                                        captured.takeIf { indexed != null },
                                        if (preserveOpeningRows) captured.openingRows else windowRows(),
                                    )
                                val restored = authoritativeThreads.current(serverId, conversationId)
                                restored.takeIf { outcome == AuthoritativeThreadOutcome.Applied }
                            }
                        }
                    if (restoredSnapshot != null) {
                        applied = restoredSnapshot
                        // The committed restore is a baseline, never a successful live capture or merged live state.
                        persistedAuthoritative.compute(conversationId) { _, previous ->
                            if (previous == null) {
                                CacheCandidate(
                                    DrawnThread(emptyList(), 0),
                                    emptyList(),
                                    authoritative = saved,
                                    storeGeneration = generation,
                                )
                            } else {
                                val baseline = previous.authoritative
                                if (extendBaseline &&
                                    previous.storeGeneration == generation &&
                                    baseline?.epoch == saved.epoch &&
                                    baseline.incarnation == saved.incarnation
                                ) {
                                    val facts = baseline.items.associateByTo(LinkedHashMap()) { it.getValue("id") }
                                    saved.items.forEach { raw ->
                                        val id = raw.getValue("id")
                                        val held = facts[id]
                                        val revision = raw.getValue("rev").toString().toLong()
                                        val heldRevision = held?.getValue("rev")?.toString()?.toLong() ?: -1L
                                        if (id.toString().toLong() !in baseline.retirements &&
                                            revision > heldRevision
                                        ) {
                                            facts[id] = raw
                                        }
                                    }
                                    val ordered =
                                        facts.values
                                            .filterNot { it.getValue("id").toString().toLong() in saved.retirements }
                                            .sortedBy { raw ->
                                                raw["order"]?.toString()?.toLongOrNull()?.takeIf { it > 0 } ?: Long.MAX_VALUE
                                            }
                                    previous.copy(
                                        authoritative =
                                            baseline.copy(
                                                items = ordered,
                                                retirements = baseline.retirements + saved.retirements,
                                                appliedVersion = maxOf(baseline.appliedVersion, saved.appliedVersion),
                                                checkpoint = baseline.checkpoint.takeIf { saved.retirements.isEmpty() } ?: 0,
                                                coverage = baseline.coverage.takeIf { saved.retirements.isEmpty() },
                                            ),
                                    )
                                } else {
                                    previous
                                }
                            }
                        }
                    }
                }
            } while (applied == null &&
                indexed != null &&
                conversationId !in deleted &&
                authoritativeCurrent(conversationId) &&
                authoritativeThreads.current(serverId, conversationId).let { it.generation == generation && it != captured }
            )
            applied?.let { AuthoritativeRestore.Validated(saved, it) } ?: AuthoritativeRestore.Stale
        }
    }

    private suspend fun resolveAuthoritativeRestore(
        conversationId: String,
        initial: SavedAuthoritativeThread,
        generation: Long,
        indexed: AuthoritativeWindowCache?,
    ): AuthoritativeRestore {
        var saved = initial
        while (true) {
            val captured = authoritativeThreads.current(serverId, conversationId)
            if (indexed == null) return AuthoritativeRestore.Validated(saved, captured)
            if (conversationId in deleted ||
                !authoritativeCurrent(conversationId) ||
                captured.generation != generation ||
                generation != 0L
            ) {
                return AuthoritativeRestore.Stale
            }
            val winners = saved.toSnapshot().items.associateByTo(mutableMapOf()) { it.id }
            val changed = captured.items.filter { item -> winners[item.id]?.raw != item.raw }.mapTo(mutableSetOf()) { it.id }
            val structuralChanges =
                captured.items.filter { item ->
                    val committed = winners[item.id]
                    committed == null || item.rev > committed.rev && !item.sameSavedStructure(committed)
                }
            captured.items.forEach { item -> if ((winners[item.id]?.rev ?: -1) <= item.rev) winners[item.id] = item }
            val parents = winners.values.mapNotNull { it.parent?.takeIf { id -> id > 0 && id !in winners } }.toSet()
            val evidence = captured.items.mapTo(mutableSetOf()) { it.id }
            val placementRoots = winners.values.filter { it.kind == "agent" && (it.order ?: 0) <= 0 }.mapTo(mutableSetOf()) { it.id }
            if (parents.isNotEmpty() || evidence.isNotEmpty()) {
                val closure =
                    try {
                        historyWrites.withLock {
                            indexed.authoritativeParentClosure(
                                serverId,
                                conversationId,
                                saved.epoch,
                                parents + changed,
                                evidence,
                                placementRoots,
                                structuralChanges,
                            )
                        }
                    } catch (_: java.io.IOException) {
                        null
                    } catch (_: SecurityException) {
                        null
                    } ?: return AuthoritativeRestore.Unavailable
                if (closure.epoch != saved.epoch ||
                    closure.incarnation != saved.incarnation ||
                    !authoritativeCurrent(conversationId)
                ) {
                    return AuthoritativeRestore.Stale
                }
                val facts = saved.toSnapshot().items.associateByTo(LinkedHashMap()) { it.id }
                closure.toSnapshot().items.forEach { item -> if ((facts[item.id]?.rev ?: -1) < item.rev) facts[item.id] = item }
                val fences = saved.retirements + closure.retirements
                if (parents.any { it !in facts && it !in fences }) return AuthoritativeRestore.Unavailable
                val ordered =
                    facts.values
                        .filterNot { it.id in fences }
                        .sortedBy { it.order?.takeIf { order -> order > 0 } ?: Long.MAX_VALUE }
                        .map { it.raw }
                saved =
                    saved.copy(
                        items = ordered,
                        retirements = fences,
                        appliedVersion = maxOf(saved.appliedVersion, closure.appliedVersion),
                        checkpoint = if (fences.isEmpty()) saved.checkpoint else 0,
                        coverage = saved.coverage.takeIf { fences.isEmpty() },
                    )
            }
            if (authoritativeThreads.current(serverId, conversationId) == captured) return AuthoritativeRestore.Validated(saved, captured)
        }
    }

    /** Supplied cache seam; #1988 selects the production route using current negotiation. */
    internal fun observeAuthoritativeThread(conversationId: String): Flow<AuthoritativeThreadSnapshot> =
        flow {
            val restored = restoreAuthoritative(conversationId)
            coroutineScope {
                val reader = this
                authoritativeReaders[conversationId] = reader
                val writer = ThreadWriter(conversationId, this, emptyList(), 0, restored)
                var observedGeneration = 0L
                var observedRetirements: Map<Long, Long> = restored?.retirements.orEmpty()
                try {
                    authoritativeThreads.observe(serverId, conversationId).collect { observed ->
                        var snapshot = observed
                        var openingRetryMs = 100L
                        while (pendingAuthoritativeOpenings.containsKey(conversationId)) {
                            if (conversationId in deleted || !authoritativeCurrent(conversationId)) return@collect
                            restoreAuthoritative(conversationId)
                            if (!pendingAuthoritativeOpenings.containsKey(conversationId)) {
                                snapshot = authoritativeThreads.current(serverId, conversationId)
                                break
                            }
                            RelayLog.d { "event=authoritative_restore code=opening_supplement_unavailable" }
                            delay(openingRetryMs)
                            openingRetryMs = (openingRetryMs * 2).coerceAtMost(1000L)
                        }
                        val baseline = persistedAuthoritative[conversationId]?.authoritative
                        if (snapshot.openingRows != null && snapshot.generation == 0L && baseline != null) {
                            val needsClosure =
                                withContext(processingDispatcher) {
                                    val committed = baseline.toSnapshot().items.associateBy { it.id }
                                    val heldIds = snapshot.items.mapTo(mutableSetOf()) { it.id }
                                    snapshot.retirements != observedRetirements ||
                                        snapshot.items.any { item ->
                                            val savedItem = committed[item.id]
                                            savedItem == null ||
                                                item.parent?.let { it > 0 && it !in heldIds } == true ||
                                                item.rev > savedItem.rev &&
                                                (
                                                    !item.sameSavedStructure(savedItem)
                                                )
                                        }
                                }
                            if (needsClosure) {
                                var retryMs = 100L
                                while (true) {
                                    when (
                                        val result =
                                            applyAuthoritativeRestore(
                                                conversationId,
                                                extendBaseline = true,
                                                preserveOpeningRows = true,
                                            ) { baseline }
                                    ) {
                                        is AuthoritativeRestore.Validated -> {
                                            snapshot = result.snapshot
                                            break
                                        }
                                        AuthoritativeRestore.Stale -> return@collect
                                        AuthoritativeRestore.Unavailable -> {
                                            if (!authoritativeCurrent(conversationId) ||
                                                conversationId in deleted ||
                                                authoritativeThreads.current(serverId, conversationId).generation != observed.generation
                                            ) {
                                                return@collect
                                            }
                                            RelayLog.d { "event=authoritative_restore code=supplement_unavailable" }
                                            delay(retryMs)
                                            retryMs = (retryMs * 2).coerceAtMost(1000L)
                                        }
                                    }
                                }
                            }
                        }
                        val capture = generations.incrementAndGet()
                        val reset = snapshot.generation > observedGeneration || snapshot.retirements != observedRetirements
                        if (reset) {
                            // Accept retirement before delivery can suspend or cancel; the writer owns its flush.
                            withContext(NonCancellable + processingDispatcher) { writer.accept(snapshot, capture) }
                        }
                        observedGeneration = snapshot.generation
                        observedRetirements = snapshot.retirements
                        emit(if (conversationId in deleted) AuthoritativeThreadSnapshot() else snapshot)
                        if (!reset) withContext(processingDispatcher) { writer.accept(snapshot, capture) }
                    }
                } finally {
                    authoritativeReaders.remove(conversationId, reader)
                    authoritativeHydrations.remove(reader)?.cancel()
                    writer.finish(authoritative = true)
                }
            }
        }

    internal suspend fun openAuthoritativeThread(conversationId: String): Boolean {
        restoreAuthoritative(conversationId)
        if (conversationId in deleted) return false
        return hostThread?.open(conversationId) ?: (authoritativeRemote?.openAuthoritativeThread(conversationId) == true)
    }

    /** Explicit owned intent; success acknowledges committed retirement, never mere admission. */
    internal suspend fun removeAuthoritativeFact(target: AuthoritativeThreadStore.Removal): Result<Unit> =
        withContext(processingDispatcher) {
            historyWrites.withLock {
                if (target.host != serverId || target.conversation in deleted) {
                    return@withLock Result.failure(IllegalArgumentException("authoritative removal rejected"))
                }
                authoritativeThreads.remove(target)
                val snapshot = authoritativeThreads.current(serverId, target.conversation)
                if (snapshot.generation != target.generation ||
                    snapshot.incarnation != target.incarnation ||
                    target.id !in snapshot.retirements ||
                    target.owner !== authoritativeThreads
                ) {
                    return@withLock Result.failure(IllegalArgumentException("authoritative removal rejected"))
                }
                withContext(NonCancellable) {
                    val candidate =
                        CacheCandidate(
                            DrawnThread(emptyList(), generations.incrementAndGet()),
                            emptyList(),
                            authoritative =
                                retirementSafeSnapshot(
                                    snapshot,
                                    persistedAuthoritative[target.conversation]?.authoritative,
                                ).toSavedAuthoritativeThread(),
                            storeGeneration = snapshot.generation,
                            authoritativeOrderMoves =
                                if (snapshot.generation == 0L && snapshot.openingRows != null) {
                                    persistedAuthoritative[target.conversation]?.authoritativeOrderMoves.orEmpty()
                                } else {
                                    snapshot.orderMoves
                                },
                        )
                    persistAuthoritative(target.conversation, candidate, refreshReset = true)
                        ?: Result.failure(IllegalStateException("authoritative removal not persisted"))
                }
            }
        }.onFailure { RelayLog.d { "event=authoritative_cache_remove_failed" } }

    /** Reset acknowledgement includes durable invalidation; failure leaves the last successful record intact. */
    internal suspend fun resetAuthoritativeThread(
        conversationId: String,
        epoch: String,
    ): Result<Unit> {
        if (conversationId in deleted) return Result.success(Unit)
        if (authoritativeRemote == null || !authoritativeRemote.resetAuthoritativeThread(conversationId, epoch)) {
            if (authoritativeThreads.reset(serverId, conversationId, epoch) != AuthoritativeThreadOutcome.Applied) {
                return Result.failure(IllegalArgumentException("invalid authoritative reset"))
            }
        }
        val snapshot = authoritativeThreads.current(serverId, conversationId)
        val candidate =
            CacheCandidate(
                DrawnThread(emptyList(), generations.incrementAndGet()),
                emptyList(),
                authoritative = snapshot.toSavedAuthoritativeThread(),
                storeGeneration = snapshot.generation,
                authoritativeOrderMoves = snapshot.orderMoves,
            )
        return withContext(NonCancellable) {
            historyWrites.withLock {
                persistAuthoritative(conversationId, candidate)
                    ?: if ((persistedAuthoritative[conversationId]?.storeGeneration ?: 0) >= snapshot.generation) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException("authoritative reset not persisted"))
                    }
            }
        }.onFailure { RelayLog.d { "event=authoritative_cache_reset_failed" } }
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
            // A save captured before entry can still complete while restoration suspends.
            val collectionCoverageRevision = persistedThreads[conversationId]?.coverageRevision ?: 0
            var base = cache.readThread(serverId, conversationId)
            if (cache is OwnedLegacyCoverageCache) persistedThreads.putIfAbsent(conversationId, PersistedThread(0, 0, base))
            var baseOrder = emptyMap<Any, ULong>()
            var restoredOrder = false
            var lastOrder = emptyMap<Any, ULong>()
            var lastDrawn = base
            var firstSnapshot = true
            coroutineScope {
                val writer = ThreadWriter(conversationId, this, base, collectionCoverageRevision)
                try {
                    delegate.threadSnapshots(conversationId).collect { snapshot ->
                        val generation = generations.incrementAndGet()
                        if (!restoredOrder && snapshot.rows.isNotEmpty()) {
                            val savedPosition = cache.readHistoryPosition(serverId, conversationId)
                            savedPosition?.let { observedPositions.putIfAbsent(conversationId, it) }
                            baseOrder =
                                withContext(processingDispatcher) {
                                    base.receivedUnsignedHistoryOrder(savedPosition?.coverage?.unsignedPositions().orEmpty())
                                }
                            restoredOrder = true
                        }
                        val drawn =
                            withContext(processingDispatcher) {
                                val live = snapshot.rows
                                if (firstSnapshot) {
                                    firstSnapshot = false
                                    if (live.isEmpty() && snapshot.suppressedUserMessageIds.isEmpty()) {
                                        // Cache reads already guarantee settled rows and unique renderer keys.
                                        lastOrder = snapshot.unsignedHistoryOrder
                                        RelayLog.d { "event=thread_cache_initial_rows_published rows=${base.size}" }
                                        return@withContext base
                                    }
                                }
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
        saved?.let { observedPositions.putIfAbsent(conversationId, it) }
        val restored = cache.readThread(serverId, conversationId)
        if (cache is OwnedLegacyCoverageCache) persistedThreads.putIfAbsent(conversationId, PersistedThread(0, 0, restored))
        val empty = restored.isEmpty()
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
                    withContext(NonCancellable) {
                        cache
                            .writeHistoryPosition(serverId, conversationId, position)
                            // Keep a sentinel so a delayed metadata read cannot reseed the cleared baseline.
                            .onSuccess { observedPositions[conversationId] = HistoryPosition("", false) }
                            .onFailure { RelayLog.d { "event=history_position_write_failed" } }
                    }
                }
            }
            return
        }
        val (snapshot, historyGeneration) =
            delegate
                .threadSnapshots(conversationId)
                .map { it to generations.incrementAndGet() }
                .first()
        historyWrites.withLock {
            if (conversationId in deleted) return@withLock
            val drawn = drawnThreads[conversationId]
            val persisted = persistedThreads[conversationId]
            val owned = cache as? OwnedLegacyCoverageCache
            if (historyGeneration <= (persisted?.generation ?: 0)) return@withLock
            val baselineRows =
                if (owned == null) {
                    drawn?.rows ?: cache.readThread(serverId, conversationId)
                } else {
                    persisted?.cacheable ?: drawn?.rows ?: cache.readThread(serverId, conversationId)
                }
            val pending = acceptedRows[conversationId].orEmpty().filterValues { it.capture > (persisted?.generation ?: 0) }
            val pendingOrder =
                pending.values
                    .sortedWith(
                        compareBy<AcceptedLegacyRow> { it.capture }.thenBy { it.order },
                    )
            val pendingChanges = pendingOrder.mapNotNull { it.change }
            val pendingRemoved = pending.filterValues { it.change == null }.keys
            val base = if (pending.isEmpty()) baselineRows else applyOwnedRows(baselineRows, pendingChanges, pendingRemoved, trim = false)
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
            val merged = snapshot.rows.mergeUnsignedCachedRows(restored, order, rendererOwners = base)
            val newer = pending.filterValues { it.capture > historyGeneration }
            val rows =
                if (newer.isEmpty()) {
                    merged
                } else {
                    applyOwnedRows(
                        merged,
                        pendingOrder.filter { it.capture > historyGeneration }.mapNotNull { it.change },
                        newer.filterValues { it.change == null }.keys,
                        trim = false,
                    )
                }
            val satisfied =
                if (owned == null) {
                    maxOf(historyGeneration, drawn?.generation ?: 0)
                } else {
                    maxOf(historyGeneration, pendingOrder.maxOfOrNull { it.capture } ?: 0)
                }
            val cacheable = withContext(processingDispatcher) { cacheableThreadRows(rows) }
            val baseline = if (owned == null) emptyMap() else baselineRows.associateBy { it.legacyRowKey() }
            val removed = if (owned == null) emptySet() else baseline.keys - rows.mapTo(HashSet()) { it.legacyRowKey() }
            val keys = if (owned == null) emptyList() else cacheable.map { it.legacyRowKey() }
            val nextHeld = arrayOfNulls<String>(keys.size)
            var next: String? = null
            for (index in keys.indices.reversed()) {
                nextHeld[index] = next
                if (keys[index] in baseline) next = keys[index]
            }
            val changes =
                if (owned == null) {
                    emptyList()
                } else {
                    cacheable.mapIndexedNotNull { index, row ->
                        if (baseline[row.legacyRowKey()] == row) {
                            null
                        } else {
                            OwnedLegacyRow(
                                row,
                                cacheable.getOrNull(index - 1)?.legacyRowKey(),
                                nextHeld[index],
                            )
                        }
                    }
                }
            val result =
                withContext(NonCancellable) {
                    val result =
                        if (owned == null) {
                            cache.writeThread(serverId, conversationId, rows)
                        } else {
                            owned.mutateLegacyRows(serverId, conversationId, changes, removed)
                        }
                    if (result.isSuccess) {
                        persistedThreads[conversationId] =
                            PersistedThread(
                                satisfied,
                                (persistedThreads[conversationId]?.coverageRevision ?: 0) + 1,
                                if (owned == null) cacheable else applyOwnedRows(baseline.values.toList(), changes, removed),
                            )
                        retireAcceptedRows(conversationId, satisfied)
                    }
                    result
                }
            if (result.isFailure) {
                RelayLog.d { "event=history_rows_write_failed" }
                return@withLock
            }
            if (conversationId in deleted) return@withLock
            if (owned != null) {
                owned
                    .writeOwnedLegacyCoverage(serverId, conversationId, position, observedPositions[conversationId], cacheable)
                    .onSuccess {
                        observedPositions[conversationId] =
                            withContext(processingDispatcher) {
                                rememberObservedCoverage(position, observedPositions[conversationId], cacheable)
                            }
                    }.onFailure { RelayLog.d { "event=history_position_write_failed" } }
                return@withLock
            }
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

    /** Receipt memory keeps omitted facts, even when committed content has conservatively invalidated them. */
    private fun rememberObservedCoverage(
        position: HistoryPosition,
        previous: HistoryPosition?,
        rows: List<ThreadItem>,
    ): HistoryPosition {
        val next = position.coverage ?: return position
        val old = previous?.coverage ?: return position
        val direct = rows.flatMapTo(HashSet()) { it.historyKeys() }
        val owned = direct + old.legacyKeys.filterValues { it in direct }.keys + next.legacyKeys.filterValues { it in direct }.keys

        fun <V> merge(
            prior: Map<String, V>,
            current: Map<String, V>,
        ): Map<String, V> = prior.filterKeys { it !in owned } + current.filterKeys { it in owned }
        val spans = ArrayList<UnsignedHistorySpan>()
        for (span in (old.unsignedSpans + next.unsignedSpans).sortedBy { it.first }) {
            val last = spans.lastOrNull()
            if (last != null && (span.first <= last.last || span.first - 1u == last.last)) {
                spans[spans.lastIndex] = UnsignedHistorySpan(last.first, maxOf(last.last, span.last))
            } else {
                spans += span
            }
        }
        val entries = owned.flatMapTo(HashSet()) { old.unsignedRowEntries[it].orEmpty() + next.unsignedRowEntries[it].orEmpty() }
        return position.copy(
            coverage =
                next.copy(
                    unsignedSpans = spans,
                    unsignedRowOrder = merge(old.unsignedRowOrder, next.unsignedRowOrder),
                    unsignedRowEntries = merge(old.unsignedRowEntries, next.unsignedRowEntries),
                    proofs = merge(old.proofs, next.proofs),
                    legacyKeys = merge(old.legacyKeys, next.legacyKeys),
                    legacyOffsets = merge(old.legacyOffsets, next.legacyOffsets),
                    deltaLengths = merge(old.deltaLengths, next.deltaLengths),
                    deltaHashes = merge(old.deltaHashes, next.deltaHashes),
                    deltaText = merge(old.deltaText, next.deltaText),
                    unsignedCursors = old.unsignedCursors.filterKeys { it !in entries } + next.unsignedCursors,
                    unsignedWalks = old.unsignedWalks.filterKeys { it !in entries } + next.unsignedWalks,
                ),
        )
    }

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
                observedPositions.remove(conversationId)
                acceptedRows.remove(conversationId)
                persistedAuthoritative.remove(conversationId)
                cache.removeConversation(serverId, conversationId)
            }
        }.onFailure { RelayLog.d { "event=conversation_cache_remove_failed" } }
    }
}
