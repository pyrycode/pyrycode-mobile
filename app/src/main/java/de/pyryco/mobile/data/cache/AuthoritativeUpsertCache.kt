package de.pyryco.mobile.data.cache

/** Optional owned-write capability; full-file compatibility continues using the replacement API. */
internal interface AuthoritativeUpsertCache {
    /**
     * [saved] contains changed identities and store-authored metadata, never an exhaustive family.
     * [reset] retires the previous runtime generation even when its epoch is reused.
     * [certify] distinguishes new certification from a repeated certificate invalidated by retention.
     */
    suspend fun upsertAuthoritativeThread(
        serverId: String,
        conversationId: String,
        saved: SavedAuthoritativeThread,
        reset: Boolean,
        certify: Boolean,
    ): Result<Unit>
}
