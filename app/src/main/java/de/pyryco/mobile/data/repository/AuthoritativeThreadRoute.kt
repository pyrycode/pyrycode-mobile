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
) {
    private val pendingGenerations = mutableMapOf<String, Long>()

    @Synchronized
    fun accept(envelope: Envelope) {
        decoder.decode(envelope).forEach(::applyOutcome)
    }

    suspend fun runExpiry() {
        while (currentCoroutineContext().isActive) {
            delay(1000)
            expire()
        }
    }

    @Synchronized
    private fun expire() {
        decoder.expire().forEach(::applyOutcome)
    }

    @Synchronized
    fun abandon() {
        decoder.abandon().forEach(::applyOutcome)
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

    private fun applyOutcome(outcome: ThreadDecodeOutcome<ThreadUpdateDto>) {
        when (outcome) {
            is ThreadDecodeOutcome.Complete -> {
                val conversation = outcome.value.conversationId
                val generation = pendingGenerations.remove(conversation) ?: store.current(host, conversation).generation
                store.apply(host, outcome.value, generation)
            }
            is ThreadDecodeOutcome.Pending ->
                pendingGenerations.putIfAbsent(
                    outcome.conversationId,
                    store.current(host, outcome.conversationId).generation,
                )
            is ThreadDecodeOutcome.Repair -> {
                val conversation = outcome.conversationId
                if (conversation == null) {
                    store.requestHostRepair(host)
                } else {
                    pendingGenerations.remove(conversation)
                    store.requestRepair(host, conversation)
                }
            }
        }
    }
}
