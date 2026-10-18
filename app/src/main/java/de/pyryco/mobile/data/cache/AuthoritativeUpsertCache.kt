package de.pyryco.mobile.data.cache

/** Optional owned-write capability; full-file compatibility continues using the replacement API. */
internal interface AuthoritativeUpsertCache {
    /**
     * [saved] contains changed identities and store-authored metadata, never an exhaustive family.
     * [reset] retires the previous runtime generation even when its epoch is reused.
     * [baseline] is the prior certificate whose durable prerequisites the candidate inherits.
     * [orderMoves] carries accepted moves since that baseline: true joins before a tie, false after.
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
