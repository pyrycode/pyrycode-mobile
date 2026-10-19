package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ContextUsagePayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.SessionTransitionPayloadDto
import de.pyryco.mobile.data.network.toBoundary
import de.pyryco.mobile.data.network.toReading
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** Passive test-only witness of the reset request, its source and its exact admitted reply. */
internal object ContextResetProbe {
    @Volatile private var active: Reading? = null

    fun <T> observing(
        host: String,
        conversation: String,
        repository: ConversationRepository,
        block: (Reading) -> T,
    ): T {
        check(active == null)
        val reading = Reading(host, conversation, repository)
        active = reading
        return try {
            block(reading)
        } finally {
            active = null
        }
    }

    fun before(
        host: String,
        connection: Any,
        frame: Envelope,
    ) {
        active?.takeIf { it.host == host }?.before(connection, frame)
    }

    suspend fun delivered(
        host: String,
        connection: Any,
        frame: Envelope,
    ) {
        active?.takeIf { it.host == host }?.delivered(connection, frame)
    }

    fun sending(
        host: String,
        connection: Any,
        frame: Envelope,
    ): CompletableDeferred<Boolean>? = active?.takeIf { it.host == host }?.sending(connection, frame)

    internal class Reading(
        val host: String,
        private val conversation: String,
        private val repository: ConversationRepository,
    ) {
        val reply = CompletableDeferred<ContextUsage>()
        private var boundary: ThreadItem.SessionBoundary? = null
        private var owner: Any? = null
        private var request: Pair<Long, CompletableDeferred<Boolean>>? = null

        @Synchronized fun before(
            connection: Any,
            frame: Envelope,
        ) {
            if (frame.type != "session_transition" || frame.field("conversation_id") != conversation) return
            check(boundary == null) { "reset witness saw another replacement" }
            boundary = MobileJson.decodeFromJsonElement<SessionTransitionPayloadDto>(frame.payload).toBoundary()
            owner = connection
        }

        @Synchronized fun sending(
            connection: Any,
            frame: Envelope,
        ): CompletableDeferred<Boolean>? {
            if (boundary == null || frame.field("conversation_id") != conversation) return null
            check(owner === connection) { "reset context connection changed" }
            check(frame.type != "send_message") { "post-reset context required another phone message" }
            if (frame.type != "request_context_usage") return null
            check(request == null) { "reset sent more than one additional context request" }
            return CompletableDeferred<Boolean>().also { request = frame.id to it }
        }

        suspend fun delivered(
            connection: Any,
            frame: Envelope,
        ) {
            val receipt =
                synchronized(this) {
                    request?.takeIf { owner === connection && it.first == frame.inReplyTo }
                } ?: return
            check(receipt.second.await()) { "reset context request was refused" }
            check(frame.type == "context_usage") { "reset context request did not produce a reading" }
            check(frame.field("conversation_id") == conversation) { "reset context reply changed conversation" }
            val replaced = checkNotNull(boundary)
            check(frame.sessionProvenance.raw?.let { it == JsonPrimitive(replaced.newSessionId) } != false) {
                "reset context reply has a different producer"
            }
            val usage = checkNotNull(MobileJson.decodeFromJsonElement<ContextUsagePayloadDto>(frame.payload).toReading())
            check(usage.asOf?.let { it >= replaced.occurredAt } != false) { "reset context reply predates replacement" }
            check(
                repository.observeContextUsage(conversation).first() == usage,
            ) { "reset reply was not the exact admitted repository reading" }
            reply.complete(usage)
        }
    }
}

private fun Envelope.field(name: String): String? = ((payload as? JsonObject)?.get(name) as? JsonPrimitive)?.content
