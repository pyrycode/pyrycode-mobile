package de.pyryco.mobile.di

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.model.Conversation

/**
 * A [ConversationCache] that stores nothing and restores nothing, for instrumented tests that build a
 * relay-mode Koin container from `appModule` and are not about the cache (#796).
 *
 * Two reasons an instrumented container overrides the real binding rather than supplying
 * `androidContext()` and taking it:
 *
 * 1. **Hermeticity.** The real binding is rooted at `Context.noBackupFilesDir`, which is one
 *    directory shared by every test in the run and by every run on a reused device. `HostConversationSource`
 *    writes each host's list as it arrives and seeds a snapshot from that document when no live
 *    repository exists — which is exactly what these tests exercise — so a live host in one test
 *    would leave rows that a later disconnected host with the same server id draws for free. That is
 *    an order-dependent pass, not a proof.
 * 2. **The missing `androidContext()` is load-bearing.** These containers deliberately have none, so
 *    resolving any Android-bound definition fails loudly instead of quietly reaching real DataStore
 *    or Keystore state. That failure is what surfaced this dependency in the first place; keeping it
 *    keeps the next one visible too.
 *
 * [ConversationCacheBindingInstrumentedTest] owns the real binding's proof and is the only
 * instrumented test that resolves it.
 */
internal object InertConversationCache : ConversationCache {
    override suspend fun readConversations(serverId: String): List<Conversation> = emptyList()

    override suspend fun writeConversations(
        serverId: String,
        conversations: List<Conversation>,
    ) = Result.success(Unit)

    override suspend fun removeHost(serverId: String) = Result.success(Unit)

    override suspend fun removeConversation(
        serverId: String,
        conversationId: String,
    ) = Result.success(Unit)
}
