package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.ErrorPayload
import de.pyryco.mobile.data.network.McpReconnectPayloadDto
import de.pyryco.mobile.data.network.McpStatusPayloadDto
import de.pyryco.mobile.data.network.McpStatusRequestPayloadDto
import de.pyryco.mobile.data.network.McpTogglePayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.toReport
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.ERROR_MCP_STATUS_UNAVAILABLE
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_MCP_RECONNECT
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_MCP_STATUS_REQUEST
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_MCP_TOGGLE
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * The MCP server reading of every conversation on one connection (#1343): the held report, the five request flags,
 * the three request verbs and their refusal correlation. The flag rules are desktop's `mcpStatusStore`. The
 * repository keeps the routing: its `onInbound` arm calls [apply] for an `mcp_status` only behind the negotiated
 * `interactive` gate, and its `error` arm hands every correlated refusal to [applyRefusal].
 *
 * [send] is the repository's pump send, [negotiatedCapabilities] its capability supplier and [nextRequestId] its
 * one request-id counter, so [asks] stays disjoint from every other ledger on the connection.
 *
 * One instance per repository, and a fresh repository per connection, so the reading is connection-scoped and
 * deliberately not in [HostReadings]: a reconnect starts from nothing and the surface asks again.
 *
 * **Nothing here logs.** Server names, statuses and errors are claude-authored; they are held for display only and
 * never read, compared or keyed on here. A refusal carries no server text, and nothing about it needs logging for
 * the flags to be observable.
 */
internal class McpStatusProjection(
    private val send: (Envelope) -> Boolean,
    private val negotiatedCapabilities: () -> Set<String>,
    private val nextRequestId: () -> Long,
) {
    /** Which request an envelope id was spent on. A toggle refusal can therefore never settle as a reconnect one. */
    private enum class Verb { Status, Reconnect, Toggle }

    /**
     * `conversationId -> its reading`. Written from the single inbound collector ([apply], [applyRefusal]) and from
     * callers' coroutines (the sends and the end-waits); every write is one [MutableStateFlow.update] over an
     * immutable map, so no writer loses another's change.
     */
    private val statusByConversation = MutableStateFlow<Map<String, McpStatus>>(emptyMap())

    /**
     * Request envelope id -> the verb and the conversation it named. Never the server name or the requested state.
     * Registered before the send and removed by the correlated `mcp_status` or `error`, or by a send that failed.
     * Not capped: each entry is one user action on this connection, and the map dies with it.
     */
    private val asks = ConcurrentHashMap<Long, Pair<Verb, String>>()

    /**
     * Apply one `mcp_status` envelope, pushed or correlated: replace the named conversation's report and clear all
     * five flags (desktop's `setMcpStatus`). Routing is the payload's own `conversation_id`, never the ask the
     * `in_reply_to` names; the ask is only consumed. A malformed payload changes nothing and is dropped so the single
     * inbound consumer survives.
     */
    fun apply(envelope: Envelope) {
        envelope.inReplyTo?.let(asks::remove)
        decodeMcpStatus(envelope)?.let { (conversationId, report) ->
            statusByConversation.update { it + (conversationId to McpStatus(report = report)) }
        }
    }

    /**
     * Settle an `error` correlated by [inReplyTo] against the ask it answers, if any. A status ask refused as
     * `mcp_status.unavailable` marks the conversation unavailable; its other codes set nothing. A reconnect or toggle
     * refusal, whatever its code, ends that verb's wait as refused: the daemon merges every cause on purpose, so the
     * code is not read. No refusal touches the held report. An id this projection never registered is a no-op.
     */
    fun applyRefusal(
        inReplyTo: Long,
        payload: JsonElement,
    ) {
        val (verb, conversationId) = asks.remove(inReplyTo) ?: return
        when (verb) {
            Verb.Status ->
                if (refusalCode(payload) == ERROR_MCP_STATUS_UNAVAILABLE) {
                    edit(conversationId) { it.copy(unavailable = true) }
                }
            Verb.Reconnect -> edit(conversationId) { it.copy(reconnecting = false, reconnectRefused = true) }
            Verb.Toggle -> edit(conversationId) { it.copy(toggling = false, toggleRefused = true) }
        }
    }

    /** Send one `mcp_status_request`. Sets no flag; see [ConversationRepository.requestMcpStatus]. */
    fun requestStatus(conversationId: String) {
        send(
            Verb.Status,
            conversationId,
            TYPE_MCP_STATUS_REQUEST,
            MobileJson.encodeToJsonElement(McpStatusRequestPayloadDto(conversationId)),
        )
    }

    /** Send one `mcp_reconnect`, setting the reconnect wait; see [ConversationRepository.reconnectMcpServer]. */
    fun reconnect(
        conversationId: String,
        serverName: String,
    ) {
        send(
            Verb.Reconnect,
            conversationId,
            TYPE_MCP_RECONNECT,
            MobileJson.encodeToJsonElement(McpReconnectPayloadDto(conversationId, serverName)),
        )
    }

    /** Send one `mcp_toggle`, setting the toggle wait; see [ConversationRepository.toggleMcpServer]. */
    fun toggle(
        conversationId: String,
        serverName: String,
        enabled: Boolean,
    ) {
        send(
            Verb.Toggle,
            conversationId,
            TYPE_MCP_TOGGLE,
            MobileJson.encodeToJsonElement(McpTogglePayloadDto(conversationId, serverName, enabled)),
        )
    }

    /** Clear only the reconnect wait (desktop's `endMcpReconnectWait`). */
    fun endReconnectWait(conversationId: String) {
        edit(conversationId) { it.copy(reconnecting = false) }
    }

    /** Clear only the toggle wait (desktop's `endMcpToggleWait`). */
    fun endToggleWait(conversationId: String) {
        edit(conversationId) { it.copy(toggling = false) }
    }

    /**
     * [conversationId]'s reading as a cold projection of [statusByConversation]: [McpStatus] with nothing in it until
     * a report or a flag lands. [distinctUntilChanged] keeps another conversation's change from re-emitting it.
     */
    fun observe(conversationId: String): Flow<McpStatus> =
        statusByConversation
            .map { it[conversationId] ?: McpStatus() }
            .distinctUntilChanged()

    /**
     * Fire-and-forget send of one request. Returns without sending or setting anything for an empty id or a
     * connection without `interactive`, which the daemon would leave inert. The ask and, for an actuation, the
     * waiting flag are set **before** the send, so a refusal arriving at once cannot be overwritten by a late begin.
     * A refused or throwing send removes the ask and clears the flag only if this call set it: nothing was sent,
     * so nothing stays set. The prior flag is read from the same atomic update that sets it. Never retries, never
     * throws, and never builds text from the payload.
     */
    private fun send(
        verb: Verb,
        conversationId: String,
        type: String,
        payload: JsonElement,
    ) {
        if (conversationId.isEmpty()) return
        if (CAPABILITY_INTERACTIVE !in negotiatedCapabilities()) return

        val request = Envelope(id = nextRequestId(), type = type, ts = Clock.System.now().toString(), payload = payload)
        asks[request.id] = verb to conversationId
        val setWait = beginWait(verb, conversationId)
        val sent =
            try {
                send(request)
            } catch (e: Exception) {
                false
            }
        if (!sent) {
            asks.remove(request.id)
            if (setWait) endWait(verb, conversationId)
        }
    }

    /** Set [verb]'s waiting flag, returning whether this call set it. A status ask has no wait. */
    private fun beginWait(
        verb: Verb,
        conversationId: String,
    ): Boolean {
        if (verb == Verb.Status) return false
        val reconnect = verb == Verb.Reconnect
        val before =
            statusByConversation.getAndUpdate { map ->
                map.edited(conversationId) { if (reconnect) it.copy(reconnecting = true) else it.copy(toggling = true) }
            }[conversationId] ?: McpStatus()
        return if (reconnect) !before.reconnecting else !before.toggling
    }

    private fun endWait(
        verb: Verb,
        conversationId: String,
    ) {
        when (verb) {
            Verb.Status -> Unit
            Verb.Reconnect -> endReconnectWait(conversationId)
            Verb.Toggle -> endToggleWait(conversationId)
        }
    }

    private fun edit(
        conversationId: String,
        change: (McpStatus) -> McpStatus,
    ) {
        statusByConversation.update { it.edited(conversationId, change) }
    }

    private fun Map<String, McpStatus>.edited(
        conversationId: String,
        change: (McpStatus) -> McpStatus,
    ): Map<String, McpStatus> = this + (conversationId to change(this[conversationId] ?: McpStatus()))

    /** The refusal's code, or null for a payload that will not decode. The throwable is discarded. */
    private fun refusalCode(payload: JsonElement): String? =
        try {
            MobileJson.decodeFromJsonElement<ErrorPayload>(payload).code
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Decode one `mcp_status` envelope to its routing conversation id and report, or **null** when it must be
     * dropped: a missing, `null` or wrong-typed key fails the structural decode, and a negative `dropped_servers`
     * fails [toReport]. The caught throwable is discarded, since kotlinx-serialization can quote the offending
     * input — claude-authored server text — in its message.
     */
    private fun decodeMcpStatus(envelope: Envelope): Pair<String, McpStatusReport>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<McpStatusPayloadDto>(envelope.payload)
            dto.toReport()?.let { dto.conversationId to it }
        } catch (e: IllegalArgumentException) {
            null
        }
}
