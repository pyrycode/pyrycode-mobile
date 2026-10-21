package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.network.ThreadItemDto

/** Definitive admission refusal after rollback; payload and identity never enter exception prose. */
internal class AuthoritativeRemovalRejected(
    val held: ThreadItemDto,
) : Exception("authoritative removal superseded")

/** A partial opening has no paging exhaustion or daemon-continuation meaning. */
internal class AuthoritativeThreadWindow(
    val serverId: String,
    val conversationId: String,
    val saved: SavedAuthoritativeThread,
    val rows: Set<Pair<Long, Boolean>> = emptySet(),
    val cursor: AuthoritativeDisplayCursor? = null,
) {
    override fun toString(): String = "AuthoritativeThreadWindow(redacted)"
}

/** A local display boundary, including which contribution of a fact was admitted. */
internal class AuthoritativeDisplayCursor(
    val host: String,
    val conversation: String,
    val epoch: String,
    val incarnation: String,
) {
    override fun toString(): String = "AuthoritativeDisplayCursor(redacted)"
}

/** Opening projections remain distinct from full compatibility reads and daemon continuations. */
internal interface AuthoritativeWindowCache {
    fun authoritativeLifetime(
        host: String,
        conversation: String,
    ): Pair<Long, Long>

    suspend fun newestAuthoritativeWindow(
        serverId: String,
        conversationId: String,
        size: Int,
    ): AuthoritativeThreadWindow?

    suspend fun beforeAuthoritativeWindow(
        serverId: String,
        conversationId: String,
        cursor: AuthoritativeDisplayCursor,
        admitted: Set<Pair<Long, Boolean>>,
        size: Int,
    ): AuthoritativeThreadWindow?

    /** [placementRoots] names winning unordered agents; durable revisions may still be ordered. */
    suspend fun authoritativeParentClosure(
        serverId: String,
        conversationId: String,
        epoch: String,
        parents: Set<Long>,
        evidence: Set<Long>,
        placementRoots: Set<Long> = emptySet(),
        structuralChanges: List<ThreadItemDto> = emptyList(),
    ): SavedAuthoritativeThread?
}

/** Optional owned-write capability; full-file compatibility continues using the replacement API. */
internal interface AuthoritativeUpsertCache {
    /**
     * [saved] contains changed identities and store-authored metadata, never an exhaustive family.
     * [reset] retires the previous runtime generation even when its epoch is reused.
     * [baseline] is the prior certificate whose durable prerequisites the candidate inherits.
     * [orderMoves] independently carries accepted placement blocks in candidate order, including unchanged
     * payloads: true joins before a tie, false after. Only the serialized owner writer supplies these.
     * Explicit [SavedAuthoritativeThread.retirements] are durable identity fences; omission is never removal.
     * [SavedAuthoritativeThread.incarnation] survives restart and changes only on explicit reset.
     */
    suspend fun upsertAuthoritativeThread(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
        reset: Boolean,
        baseline: SavedAuthoritativeThread?,
        orderMoves: Map<Long, Boolean>,
    ): Result<Unit>
}
