package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.ContextUsagePayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RequestContextUsagePayloadDto
import de.pyryco.mobile.data.network.toReading
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_REQUEST_CONTEXT_USAGE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * The context-usage reading for every conversation on one connection (#945): its state, its decoder, its read,
 * and the `request_context_usage` ask that keeps a watched reading fresh. The repository keeps the routing: its
 * `onInbound` arm calls [apply] only behind the negotiated `interactive` gate, and its `session_transition` arm
 * calls [onSessionTransition].
 *
 * [send], [negotiatedCapabilities] and [nextRequestId] are the repository's, the [ModelMenuProjection] wiring, so
 * the ask takes its envelope id from the one request-id counter.
 *
 * **A refusal needs no code here.** Both rejects (`conversation.not_found`, `context_usage.unavailable`) arrive on
 * the repository's `error` arm with an `in_reply_to` no waiter and no model-list ask holds, so they are no-ops:
 * nothing writes the reading, which stays absent, and nothing re-sends. The next turn-end frame fills it in.
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is connection-scoped:
 * a reconnect starts from nothing, and the facade's re-subscription asks again. Nothing here logs.
 */
internal class ContextUsageProjection(
    private val send: (Envelope) -> Boolean,
    private val negotiatedCapabilities: () -> Set<String>,
    private val nextRequestId: () -> Long,
) {
    /**
     * `conversationId -> the reading Claude last reported` (#945). Each frame **replaces** the entry, a push and a
     * reply alike. Two writers, both on the repository's single inbound collector so they never race: a frame
     * stores its reading, and the conversation's `session_transition` removes it.
     */
    private val readingByConversation = MutableStateFlow<Map<String, ContextUsage>>(emptyMap())

    /**
     * `conversationId -> how many collectors are watching its reading`. The 0→1 edge sends the subscription ask,
     * and a non-zero count is what "observed" means for the post-transition ask. Written from collectors'
     * coroutines and read from the inbound collector, hence the atomic [ConcurrentHashMap.compute]. A key is
     * removed at zero, so the map holds only ids somebody is watching right now.
     */
    private val observerCounts = ConcurrentHashMap<String, Int>()

    /**
     * Apply one `context_usage` envelope. Called only from the repository's `interactive`-gated arm. Routing is the
     * payload's own daemon-authored `conversation_id`, never `in_reply_to`, so an answer naming another
     * conversation lands under that one. A malformed payload, a negative `percentage` or an unreadable `as_of`
     * decodes to null and is dropped so the single inbound consumer survives, leaving any prior reading standing
     * and never writing a zero one. It folds no thread row, touches no stall and never reaches the session
     * settings' transcript-derived token counts. Drop silently — nothing here logs the payload.
     */
    fun apply(envelope: Envelope) {
        decodeContextUsage(envelope)?.let { (conversationId, reading) ->
            readingByConversation.update { it + (conversationId to reading) }
        }
    }

    /**
     * The conversation's session was replaced, so its reading describes a gone context: drop it, **then**, if
     * somebody is watching, ask for the new session's figure. The order keeps the old figure from standing beside
     * the new request.
     */
    fun onSessionTransition(conversationId: String) {
        readingByConversation.update { it - conversationId }
        if ((observerCounts[conversationId] ?: 0) > 0) ask(conversationId)
    }

    /**
     * The reading Claude last reported for [conversationId] (#945), `null` while there is none, as a cold
     * projection of [readingByConversation]. [distinctUntilChanged] keeps another conversation's frame from
     * re-emitting this flow, and a `StateFlow` gives every new collector the current reading at once.
     *
     * The first collector of a conversation asks for a fresh reading; a second concurrent one does not, and the
     * count drops again when a collector ends. The ask is non-suspending, so the first emission is not delayed.
     */
    fun observe(conversationId: String): Flow<ContextUsage?> =
        readingByConversation
            .map { it[conversationId] }
            .distinctUntilChanged()
            .onStart { if (observerCounts.compute(conversationId) { _, count -> (count ?: 0) + 1 } == 1) ask(conversationId) }
            .onCompletion { observerCounts.compute(conversationId) { _, count -> if (count == null || count <= 1) null else count - 1 } }

    /**
     * Send one `request_context_usage` naming [conversationId]. **Fire-and-forget, non-suspending and
     * non-throwing**, the `ModelMenuProjection` ask: the success is a `context_usage` handled by [apply] and a
     * refusal needs no handling, so there is nothing to await. No ledger and no retry: a send the transport
     * refused is not re-sent, and only a new subscription, a reconnect or a session transition asks again.
     *
     * Two guards return without sending: an empty id names nothing, and a connection without `interactive` is
     * answered with nothing at all. Never logs, on any branch.
     */
    private fun ask(conversationId: String) {
        if (conversationId.isEmpty()) return
        if (CAPABILITY_INTERACTIVE !in negotiatedCapabilities()) return
        val request =
            Envelope(
                id = nextRequestId(),
                type = TYPE_REQUEST_CONTEXT_USAGE,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(RequestContextUsagePayloadDto(conversationId = conversationId)),
            )
        try {
            send(request)
        } catch (e: Exception) {
            // A refused send is the same outcome as `false`: no reading, and nothing re-sends.
        }
    }

    /**
     * Decode one v2 `context_usage` envelope to its routing conversation id and reading, or **null** when it must
     * be dropped: a missing, `null` or wrong-typed scalar fails the structural decode, and a negative `percentage`
     * or a malformed `as_of` fails [toReading]. The caught throwable is discarded, since kotlinx-serialization can
     * quote the offending input in its message.
     */
    private fun decodeContextUsage(envelope: Envelope): Pair<String, ContextUsage>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<ContextUsagePayloadDto>(envelope.payload)
            dto.toReading()?.let { dto.conversationId to it }
        } catch (e: IllegalArgumentException) {
            null
        }
}
