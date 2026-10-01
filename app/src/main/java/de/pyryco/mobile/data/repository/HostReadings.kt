package de.pyryco.mobile.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * The five per-conversation readings a host pushes and the phone never asks for again (#1317): the announced
 * model, session facts, context usage, usage limit and slash-command menu. They are held for the life of a
 * host's pairing rather than one connection, as on desktop, so a return to the foreground does not blank them
 * until the next turn ends.
 *
 * [RelayRepositoryCoordinator] owns one per host and threads it into each connection's
 * [RemoteConversationRepository], which still decodes, replaces and clears through these projections exactly
 * as before. The thread reads them through [StableConversationRepository], so they stay readable while the
 * host is disconnected. [close] is the pairing-scoped drop: the coordinator calls it when unpair or re-pair
 * closes the bundle, and every read then reports nothing. Defaulted per repository so constructions without a
 * coordinator keep connection-scoped readings.
 *
 * Emits no logs: every reading carries claude-authored text.
 */
class HostReadings(
    now: () -> Instant = Clock.System::now,
) {
    internal val announcedModel = AnnouncedModelProjection()
    internal val sessionFacts = SessionFactsProjection()
    internal val contextUsage = ContextUsageProjection()
    internal val usageLimit = UsageLimitProjection(now)
    internal val slashCommandMenu = SlashCommandMenuProjection()

    private val open = MutableStateFlow(true)

    fun observeAnnouncedModel(conversationId: String): Flow<AnnouncedModel?> = whileOpen { announcedModel.observe(conversationId) }

    fun observeSessionFacts(conversationId: String): Flow<SessionFacts?> = whileOpen { sessionFacts.observe(conversationId) }

    fun observeContextUsage(conversationId: String): Flow<ContextUsage?> = whileOpen { contextUsage.observe(conversationId) }

    fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?> = whileOpen { usageLimit.observe(conversationId) }

    fun observeSlashCommandMenu(conversationId: String): Flow<SlashCommandMenu?> = whileOpen { slashCommandMenu.observe(conversationId) }

    /** The pairing ended: every read reports nothing from now on. Idempotent. */
    fun close() {
        open.value = false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> whileOpen(read: () -> Flow<T?>): Flow<T?> = open.flatMapLatest { if (it) read() else flowOf(null) }
}
