package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.network.ConversationsPayload
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.toConversations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.atomic.AtomicLong

/**
 * Mobile Protocol v2 conversation-list read path (#312). Drives a `list_conversations` request
 * and projects inbound `conversations` snapshots — decoded and mapped by the #316
 * [ConversationsPayload] layer — into the filtered, sorted domain [Conversation] list the UI reads.
 *
 * Consumes the Noise session pump (#309) over the portable [SessionPump] surface: inbound
 * `Flow<Envelope>` + non-throwing [SessionPump.send]. It does **not** reach below the pump to the
 * frame transport or the Noise session, and it does **not** re-implement wire↔domain mapping — the
 * #316 mapper is the single decode-and-validate boundary, and this slice runs **behind** the
 * already-authenticated Noise channel.
 *
 * `pump.inbound` is hot and **single-consumer**, so the repository runs exactly one long-lived
 * collector (launched in [init] on the connection-scoped [scope]) that demultiplexes by
 * [Envelope.type] into a single [projection] `StateFlow`. [observeConversations] is a cold flow that
 * fans out from that projection, so N concurrent collectors share the one inbound consumer.
 *
 * Every interface method other than [observeConversations] is a not-yet-implemented stub that the
 * sibling slices replace in this same class: thread reads (#313), last-message (#329), mutations
 * (#314), and the methods with no documented v2 wire message (`archive` / `unarchive` / `rename` /
 * `startNewSession` / `changeWorkspace`). [delete], [recentWorkspaces], and [createWorkspaceFolder]
 * keep their interface defaults — intentionally outside this slice's surface.
 */
class RemoteConversationRepository(
    private val pump: SessionPump,
    scope: CoroutineScope,
) : ConversationRepository {
    /**
     * The demuxed list projection: `null` until the first `conversations` snapshot loads, then the
     * latest full-list snapshot. A single source of state; [observeConversations] derives every cold
     * read from it. `StateFlow` conflation means a value-equal snapshot (e.g. a redundant reply to a
     * second collector's request) does not re-emit.
     */
    private val projection = MutableStateFlow<List<Conversation>?>(null)

    private val requestId = AtomicLong(0)

    init {
        // The single consumer of the hot, single-consumer inbound stream. Cancelled by its owner
        // (#279/#302) when the connection ends; the pump completing `inbound` on teardown also ends it.
        scope.launch {
            pump.inbound.collect { envelope -> onInbound(envelope) }
        }
    }

    private fun onInbound(envelope: Envelope) {
        when (envelope.type) {
            TYPE_CONVERSATIONS -> {
                // The reply to our request AND any unsolicited change push arrive as a full-list
                // `conversations` snapshot, so re-emission needs no in_reply_to correlation (AC #3).
                // Decode is the single failure surface: a malformed payload (missing required field
                // → SerializationException, bad timestamp → IllegalArgumentException; the former is a
                // subtype of the latter) is dropped so the single inbound consumer survives, rather
                // than letting an uncaught throw freeze every future update for the connection.
                val decoded =
                    try {
                        MobileJson.decodeFromJsonElement<ConversationsPayload>(envelope.payload)
                    } catch (e: IllegalArgumentException) {
                        return
                    }
                projection.value = decoded.toConversations()
            }
            // Any other type is a no-op here: single-row conversation deltas (#318 → #314), `messages`
            // (#313), and last-message (#329) extend this `when` in their own slices.
            else -> Unit
        }
    }

    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
        flow {
            // Request on every subscription: redundant requests are absorbed by StateFlow conflation,
            // and re-subscribing (e.g. on lifecycle resume) naturally re-issues. A pre-Open send returns
            // false and is dropped — the coordinator (#302) wires this against an Open pump.
            pump.send(listConversationsRequest())
            emitAll(projection.filterNotNull().map { project(it, filter) })
        }

    /** Apply the [ConversationFilter] then order most-recently-used first — mirrors the fake exactly. */
    private fun project(
        conversations: List<Conversation>,
        filter: ConversationFilter,
    ): List<Conversation> =
        conversations
            .filter { conversation ->
                when (filter) {
                    ConversationFilter.All -> true
                    ConversationFilter.Channels -> conversation.isPromoted && !conversation.archived
                    ConversationFilter.Discussions -> !conversation.isPromoted && !conversation.archived
                    ConversationFilter.Archived -> conversation.archived
                }
            }.sortedByDescending { it.lastUsedAt }

    private fun listConversationsRequest(): Envelope =
        Envelope(
            id = requestId.incrementAndGet(),
            type = TYPE_LIST_CONVERSATIONS,
            ts = Clock.System.now().toString(),
            payload = JsonObject(emptyMap()),
        )

    // ---- Stubs: each later slice replaces the methods it owns -----------------------------------

    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
        throw UnsupportedOperationException("observeMessages: thread read path not yet wired (#313)")

    override fun observeLastMessage(conversationId: String): Flow<Message?> =
        throw UnsupportedOperationException("observeLastMessage: last-message via message read path (#329)")

    override suspend fun createDiscussion(workspace: String?): Conversation =
        throw UnsupportedOperationException("createDiscussion: mutation path not yet wired (#314)")

    override suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String?,
    ): Conversation = throw UnsupportedOperationException("promote: mutation path not yet wired (#314)")

    override suspend fun sendMessage(
        conversationId: String,
        text: String,
    ): Message = throw UnsupportedOperationException("sendMessage: mutation path not yet wired (#314)")

    override suspend fun archive(conversationId: String): Unit =
        throw UnsupportedOperationException("archive: no v2 wire message defined (follow-up specs the wire contract)")

    override suspend fun unarchive(conversationId: String): Unit =
        throw UnsupportedOperationException("unarchive: no v2 wire message defined (follow-up specs the wire contract)")

    override suspend fun rename(
        conversationId: String,
        name: String,
    ): Conversation = throw UnsupportedOperationException("rename: no v2 wire message defined (follow-up specs the wire contract)")

    override suspend fun startNewSession(
        conversationId: String,
        workspace: String?,
    ): Session = throw UnsupportedOperationException("startNewSession: no v2 wire message defined (follow-up specs the wire contract)")

    override suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session = throw UnsupportedOperationException("changeWorkspace: no v2 wire message defined (follow-up specs the wire contract)")

    private companion object {
        /** Request: list the conversations (payload `{}` per protocol). */
        const val TYPE_LIST_CONVERSATIONS = "list_conversations"

        /** Response/push: a full-list `{conversations:[…]}` snapshot — also unsolicited on change. */
        const val TYPE_CONVERSATIONS = "conversations"
    }
}
