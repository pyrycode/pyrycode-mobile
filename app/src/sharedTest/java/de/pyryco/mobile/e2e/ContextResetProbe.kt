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
        private var legacyTransition = false
        private var owner: Any? = null
        private var reset: CompletableDeferred<Boolean>? = null
        private var candidate: Pair<Envelope, ContextUsage>? = null
        private var request: Pair<Long, CompletableDeferred<Boolean>>? = null
        private var failure: IllegalStateException? = null

        /** The scenario supplies only its independently admitted, rendered replacement boundary. */
        @Synchronized fun replaced(replacement: ThreadItem.SessionBoundary) {
            if (reset == null && boundary == null) return fail("reset boundary lacks an observed action")
            bindReplacement(replacement)
        }

        private fun bindReplacement(replacement: ThreadItem.SessionBoundary) {
            val previous = boundary
            if (previous != null &&
                (
                    previous.previousSessionId != replacement.previousSessionId ||
                        previous.newSessionId != replacement.newSessionId
                )
            ) {
                return fail("reset boundary changed sessions")
            }
            boundary = replacement
            completeIfReady()
        }

        private fun completeIfReady() {
            val replaced = boundary ?: return
            val (frame, usage) = candidate ?: return
            if (frame.sessionProvenance.raw?.let { it != JsonPrimitive(replaced.newSessionId) } == true) {
                return fail("reset context reply has a different producer")
            }
            val predatesReplacement = usage.asOf?.let { it < replaced.occurredAt } == true
            if (predatesReplacement) return fail("reset context reply predates replacement")
            reply.complete(usage)
        }

        @Synchronized fun assertValid() {
            failure?.let { throw it }
        }

        @Synchronized private fun fail(message: String) {
            val error = failure ?: IllegalStateException(message).also { failure = it }
            reply.completeExceptionally(error)
        }

        @Synchronized fun before(
            connection: Any,
            frame: Envelope,
        ) {
            if (frame.type != "session_transition" || frame.field("conversation_id") != conversation) return
            if (legacyTransition) return fail("reset witness saw another replacement")
            if (owner != null && owner !== connection) return fail("reset context connection changed")
            legacyTransition = true
            owner = connection
            val transition = MobileJson.decodeFromJsonElement<SessionTransitionPayloadDto>(frame.payload)
            transition.toBoundary()?.let(::bindReplacement)
        }

        @Synchronized fun sending(
            connection: Any,
            frame: Envelope,
        ): CompletableDeferred<Boolean>? {
            if (frame.field("conversation_id") != conversation) return null
            if (frame.type == "new_session") {
                if (reset != null || boundary != null) {
                    fail("reset witness saw another action")
                    return null
                }
                owner = connection
                return CompletableDeferred<Boolean>().also { reset = it }
            }
            if (boundary == null && reset == null) return null
            if (owner !== connection) {
                fail("reset context connection changed")
                return null
            }
            if (frame.type == "send_message") {
                fail("post-reset context required another phone message")
                return null
            }
            if (frame.type != "request_context_usage") return null
            if (request != null) {
                fail("reset sent more than one additional context request")
                return null
            }
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
            val resetReceipt = synchronized(this) { reset }
            if (resetReceipt?.await() == false) return fail("reset action was refused")
            if (!receipt.second.await()) return fail("reset context request was refused")
            if (frame.type != "context_usage") return fail("reset context request did not produce a reading")
            if (frame.field("conversation_id") != conversation) return fail("reset context reply changed conversation")
            val usage = checkNotNull(MobileJson.decodeFromJsonElement<ContextUsagePayloadDto>(frame.payload).toReading())
            if (repository.observeContextUsage(conversation).first() != usage) {
                return fail("reset reply was not the exact admitted repository reading")
            }
            synchronized(this) {
                candidate = frame to usage
                completeIfReady()
            }
        }
    }
}

private fun Envelope.field(name: String): String? = ((payload as? JsonObject)?.get(name) as? JsonPrimitive)?.content
