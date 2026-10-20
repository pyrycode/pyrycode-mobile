package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
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
) {
    private val pendingGenerations = mutableMapOf<String, Long>()

    fun accept(envelope: Envelope) {
        val applied = mutableListOf<Triple<String, Long, Long>>()
        val repairs =
            synchronized(this) {
                applyOutcomes(decoder.decode(envelope)) { conversation, id, generation -> applied += Triple(conversation, id, generation) }
            }
        if (repairs.isNotEmpty()) onRepair(repairs)
        // Query reset takes query -> route; never call back into queries while holding this monitor.
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
    ): Map<String, Long> =
        buildMap {
            outcomes.forEach { outcome ->
                if (applyOutcome(outcome, applied)) {
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
    ): Boolean =
        when (outcome) {
            is ThreadDecodeOutcome.Complete -> {
                val conversation = outcome.value.conversationId
                val generation = pendingGenerations.remove(conversation) ?: store.current(host, conversation).generation
                val result = store.apply(host, outcome.value, generation)
                if (result == AuthoritativeThreadOutcome.Applied) applied(conversation, outcome.value.itemId, generation)
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
