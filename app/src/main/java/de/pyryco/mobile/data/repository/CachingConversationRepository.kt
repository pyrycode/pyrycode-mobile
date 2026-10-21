package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.AttachmentStore
import de.pyryco.mobile.data.cache.AuthoritativeRemovalRejected
import de.pyryco.mobile.data.cache.AuthoritativeUpsertCache
import de.pyryco.mobile.data.cache.AuthoritativeWindowCache
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.SavedAuthoritativeThread
import de.pyryco.mobile.data.cache.sameSavedStructure
import de.pyryco.mobile.data.cache.toSavedAuthoritativeThread
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

/** Keeps this host's authoritative thread facts durable; transient state remains delegated. */
class CachingConversationRepository(
    private val delegate: ConversationRepository,
    private val cache: ConversationCache,
    private val serverId: String,
    private val attachments: AttachmentStore? = null,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ConversationRepository by delegate {
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

    private val generations = AtomicLong()

    private data class CacheCandidate(
        val capture: Long,
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
        val authoritative: SavedAuthoritativeThread? = null,
        val storeGeneration: Long = 0,
        val authoritativeBaseline: SavedAuthoritativeThread? = null,
        val authoritativeOrderMoves: Map<Long, AuthoritativeOrderMove> = emptyMap(),
    )

    private inner class ThreadWriter(
        private val conversationId: String,
        private val scope: CoroutineScope,
        private val initialAuthoritative: SavedAuthoritativeThread? = null,
    ) {
        private val latest = AtomicReference<CacheCandidate?>()
        private val retry = AtomicBoolean()
        private val signals = Channel<Unit>(Channel.CONFLATED)
        private var timer: Job? = null
        private var completed: CacheCandidate? = null
        private val writer =
            scope.launch(processingDispatcher, start = CoroutineStart.UNDISPATCHED) {
                for (signal in signals) {
                    val candidate = latest.get() ?: continue
                    if (candidate.ready.isCompleted) persist(candidate)
                }
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
                    capture,
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
                    persistAuthoritative(conversationId, candidate, refreshReset)
                } ?: return
            if (result.isSuccess) {
                completed = candidate
            } else {
                retry.set(true)
                RelayLog.d { "event=thread_cache_write_failed" }
            }
        }

        suspend fun finish() {
            withContext(NonCancellable + processingDispatcher) {
                timer?.cancelAndJoin()
                writer.cancelAndJoin()
                signals.close()
                run {
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
                                generations.incrementAndGet(),
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
                latest.get()?.let { persist(it, refreshReset = true) }
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
                        generations.incrementAndGet(),
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
                selected.capture <= (persisted?.capture ?: 0)
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
                                    0,
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
                val writer = ThreadWriter(conversationId, this, restored)
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
                    writer.finish()
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
                            generations.incrementAndGet(),
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
                generations.incrementAndGet(),
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

    /** Retrieves bytes through this host's attachment store, falling back to its delegate. */
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
                persistedAuthoritative.remove(conversationId)
                cache.removeConversation(serverId, conversationId)
            }
        }.onFailure { RelayLog.d { "event=conversation_cache_remove_failed" } }
    }
}
