package de.pyryco.mobile.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * The five per-conversation readings a host pushes and the phone never asks for again (#1317): the announced
 * model, session facts, context usage, usage limit and slash-command menu. They are held for the life of a
 * host's pairing rather than one connection, as on desktop, so a return to the foreground does not blank them
 * until the next turn ends.
 *
 * Since #1320 it also holds two readings the phone asks for: the model menu of each conversation and the last
 * successful run-settings reply. The asks themselves stay with each connection. A held settings reading is
 * only ever read back invalidated, through [observeHeldSessionSettings], so a permission mode from an earlier
 * connection never looks confirmed.
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

    /** `conversationId -> the model menu last heard for it` (#1320), written by each connection's [ModelMenuProjection]. */
    internal val modelMenus = MutableStateFlow<Map<String, ModelMenu>>(emptyMap())

    /** `conversationId -> the last successful settings reply` (#1320), written by each connection's [SessionSettingsCommands]. */
    private val sessionSettings = MutableStateFlow<Map<String, SessionSettings>>(emptyMap())

    private val open = MutableStateFlow(true)

    fun observeAnnouncedModel(conversationId: String): Flow<AnnouncedModel?> = whileOpen { announcedModel.observe(conversationId) }

    fun observeSessionFacts(conversationId: String): Flow<SessionFacts?> = whileOpen { sessionFacts.observe(conversationId) }

    fun observeContextUsage(conversationId: String): Flow<ContextUsage?> = whileOpen { contextUsage.observe(conversationId) }

    fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?> = whileOpen { usageLimit.observe(conversationId) }

    fun observeSlashCommandMenu(conversationId: String): Flow<SlashCommandMenu?> = whileOpen { slashCommandMenu.observe(conversationId) }

    fun observeModelMenu(conversationId: String): Flow<ModelMenu?> =
        whileOpen { modelMenus.map { it[conversationId] }.distinctUntilChanged() }

    /**
     * The held settings reading of [conversationId] (#1320) as desktop's `invalidate()` leaves it on a `connected`
     * edge: the model, effort and session kept, the permission mode not yet known and memory search
     * [MemorySearchReport.Unknown], until the live connection's own reply replaces it. `null` when none is held.
     */
    fun observeHeldSessionSettings(conversationId: String): Flow<SessionSettings?> =
        whileOpen {
            sessionSettings
                .map { held -> held[conversationId]?.copy(permissionMode = "", memorySearch = MemorySearchReport.Unknown) }
                .distinctUntilChanged()
        }

    /** Replace [conversationId]'s held settings reading with a successful reply (#1320). */
    internal fun holdSessionSettings(
        conversationId: String,
        settings: SessionSettings,
    ) {
        sessionSettings.update { it + (conversationId to settings) }
    }

    /** The pairing ended: every read reports nothing from now on. Idempotent. */
    fun close() {
        open.value = false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> whileOpen(read: () -> Flow<T?>): Flow<T?> = open.flatMapLatest { if (it) read() else flowOf(null) }
}
