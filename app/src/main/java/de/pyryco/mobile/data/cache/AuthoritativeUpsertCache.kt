package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.network.ThreadItemDto

/** Definitive admission refusal after rollback; payload and identity never enter exception prose. */
internal class AuthoritativeRemovalRejected(
    val held: ThreadItemDto,
) : Exception("authoritative removal superseded")

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
