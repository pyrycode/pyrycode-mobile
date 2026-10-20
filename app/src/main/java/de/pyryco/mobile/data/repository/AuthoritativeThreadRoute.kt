package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.TYPE_THREAD_ITEM_ADDED
import de.pyryco.mobile.data.network.ThreadDecodeOutcome
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.ThreadUpdateDecoder
import de.pyryco.mobile.data.network.ThreadUpdateDto
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Single connection's codec lifecycle; never translates authoritative facts into raw events. */
internal class AuthoritativeThreadRoute(
    private val host: String,
    private val store: AuthoritativeThreadStore,
    private val decoder: ThreadUpdateDecoder = ThreadUpdateDecoder(host, "connection"),
    private val onRepair: (Map<String, Long>) -> Unit = {},
    private val onApplied: (String, Long, Long) -> Unit = { _, _, _ -> },
    private val onFreshAdded: (String, String, Long, ThreadItemDto) -> Unit = { _, _, _, _ -> },
) {
    private val pendingGenerations = mutableMapOf<String, Long>()

    fun accept(envelope: Envelope) {
        val applied = mutableListOf<Triple<String, Long, Long>>()
        val fresh = mutableListOf<ThreadUpdateDto>()
        val repairs =
            synchronized(this) {
                applyOutcomes(
                    decoder.decode(envelope),
                    applied = { conversation, id, generation -> applied += Triple(conversation, id, generation) },
                    fresh = { fresh += it },
                )
            }
        if (repairs.isNotEmpty()) onRepair(repairs)
        // Query reset takes query -> route; never call back into queries while holding this monitor.
        // Effects run before completion-triggered queries can replace their source snapshot.
        fresh.forEach { update ->
            val generation = applied.first { it.first == update.conversationId && it.second == update.itemId }.third
            onFreshAdded(update.conversationId, update.epoch, generation, requireNotNull(update.item))
        }
        applied.forEach { (conversation, id, generation) -> onApplied(conversation, id, generation) }
    }

    suspend fun runExpiry() {
        while (currentCoroutineContext().isActive) {
            delay(1000)
            expire()
        }
    }

    private fun expire() {
        val expired =
            synchronized(this) {
                applyOutcomes(decoder.expire())
            }
        if (expired.isNotEmpty()) onRepair(expired)
    }

    @Synchronized
    fun abandon() {
        decoder.abandon().forEach { applyOutcome(it) }
        pendingGenerations.clear()
    }

    @Synchronized
    fun reset(
        conversation: String,
        epoch: String,
    ): AuthoritativeThreadOutcome {
        decoder.discard(conversation)
        pendingGenerations.remove(conversation)
        return store.reset(host, conversation, epoch)
    }

    @Synchronized
    fun establishComplete(
        conversation: String,
        epoch: String,
        version: Long,
        items: List<ThreadItemDto>,
        generation: Long,
    ): AuthoritativeThreadOutcome {
        val outcome = store.establishComplete(host, conversation, epoch, version, items, generation)
        if (outcome == AuthoritativeThreadOutcome.Applied) {
            decoder.discard(conversation)
            pendingGenerations.remove(conversation)
        }
        return outcome
    }

    private fun applyOutcomes(
        outcomes: List<ThreadDecodeOutcome<ThreadUpdateDto>>,
        applied: (String, Long, Long) -> Unit = { _, _, _ -> },
        fresh: (ThreadUpdateDto) -> Unit = {},
    ): Map<String, Long> =
        buildMap {
            outcomes.forEach { outcome ->
                if (applyOutcome(outcome, applied, fresh)) {
                    val conversation =
                        when (outcome) {
                            is ThreadDecodeOutcome.Complete -> outcome.value.conversationId
                            is ThreadDecodeOutcome.Pending -> outcome.conversationId
                            is ThreadDecodeOutcome.Repair -> outcome.conversationId
                        }
                    putAll(store.repairGenerations(host, conversation))
                }
            }
        }

    private fun applyOutcome(
        outcome: ThreadDecodeOutcome<ThreadUpdateDto>,
        applied: (String, Long, Long) -> Unit = { _, _, _ -> },
        fresh: (ThreadUpdateDto) -> Unit = {},
    ): Boolean =
        when (outcome) {
            is ThreadDecodeOutcome.Complete -> {
                val conversation = outcome.value.conversationId
                val generation = pendingGenerations.remove(conversation) ?: store.current(host, conversation).generation
                val update = outcome.value
                val (result, firstAdded) =
                    synchronized(store) {
                        val absent = store.current(host, conversation).items.none { it.id == update.itemId }
                        store.apply(host, update, generation) to (absent && update.type == TYPE_THREAD_ITEM_ADDED)
                    }
                if (result == AuthoritativeThreadOutcome.Applied) {
                    applied(conversation, update.itemId, generation)
                    if (firstAdded) fresh(update)
                }
                result in setOf(AuthoritativeThreadOutcome.RepairNeeded, AuthoritativeThreadOutcome.ResetNeeded)
            }
            is ThreadDecodeOutcome.Pending -> {
                pendingGenerations.putIfAbsent(
                    outcome.conversationId,
                    store.current(host, outcome.conversationId).generation,
                )
                false
            }
            is ThreadDecodeOutcome.Repair -> {
                val conversation = outcome.conversationId
                if (conversation == null) {
                    store.requestHostRepair(host)
                } else {
                    pendingGenerations.remove(conversation)
                    store.requestRepair(host, conversation)
                }
                true
            }
        }
}
