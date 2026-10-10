package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_MULTI_AGENT
import de.pyryco.mobile.data.network.ConversationResponseDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.ErrorPayload
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Client-owned failure. No daemon text or exception cause crosses this boundary. */
class SwitchAgentFailure(
    val category: Category,
    val retryable: Boolean = false,
) : Exception("Agent switch failed") {
    enum class Category {
        ConversationNotFound,
        Malformed,
        Unsupported,
        ModelListUnavailable,
        BinaryBusy,

        // The old binding remains usable, but outgoing wrap-up may already have run.
        BinaryOffline,
        Unavailable,
        Failed,
    }
}

/** Recorded fake arguments are inspectable by tests, but diagnostics contain no request values. */
data class SwitchAgentCall(
    val conversationId: String,
    val agent: ConversationAgent,
    val model: String,
    val effort: String?,
) {
    override fun toString(): String = "SwitchAgentCall"
}

/** One connection's uncorrelated confirmations and correlated refusals. No timeout or retry. */
internal class SwitchAgentCommands(
    private val send: (Envelope) -> Boolean,
    private val negotiatedCapabilities: () -> Set<String>,
    private val nextRequestId: () -> Long,
) {
    private class Pending(
        val conversationId: String,
        val agent: String,
        val result: CompletableDeferred<Result<Unit>> = CompletableDeferred(),
    )

    private val pending = mutableMapOf<Long, Pending>()
    private var ended = false

    suspend fun switchAgent(
        conversationId: String,
        agent: ConversationAgent,
        model: String,
        effort: String?,
    ): Result<Unit> {
        currentCoroutineContext().ensureActive()
        val wireAgent = agent.name.lowercase()
        val request =
            Envelope(
                id = nextRequestId(),
                type = "switch_agent",
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(Payload(conversationId, wireAgent, model, effort)),
            )
        val entry = Pending(conversationId, wireAgent)
        try {
            synchronized(this) {
                if (ended) return failure(SwitchAgentFailure.Category.Unavailable)
                val capabilities = negotiatedCapabilities()
                if (CAPABILITY_INTERACTIVE !in capabilities || CAPABILITY_MULTI_AGENT !in capabilities) {
                    return failure(SwitchAgentFailure.Category.Unsupported)
                }
                // Sending is non-suspending; end cannot sweep before this registration is visible.
                pending[request.id] = entry
                val sent =
                    try {
                        send(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        false
                    }
                if (!sent) {
                    settle(request.id, failure(SwitchAgentFailure.Category.Unavailable))
                } else {
                    RelayLog.d { "event=switch_agent outcome=sent" }
                }
            }
            return entry.result.await()
        } catch (cancelled: CancellationException) {
            RelayLog.d { "event=switch_agent outcome=cancelled" }
            throw cancelled
        } finally {
            synchronized(this) { pending.remove(request.id) }
        }
    }

    /** Called only after this valid row has been folded into the conversation list. */
    @Synchronized
    fun confirm(
        record: ConversationResponseDto,
        payload: JsonElement,
    ) {
        val objectPayload = payload as? JsonObject ?: return
        val id = (objectPayload["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val agent = (objectPayload["agent"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        if (record.id != id || record.agent != agent) return
        val matching = pending.filterValues { it.conversationId == id && it.agent == agent }.keys.toList()
        matching.forEach { requestId ->
            RelayLog.d { "event=switch_agent outcome=confirmed" }
            settle(requestId, Result.success(Unit))
        }
    }

    @Synchronized
    fun refuse(
        requestId: Long,
        payload: JsonElement,
    ) {
        if (requestId !in pending) return
        val decoded =
            try {
                val fields = payload as? JsonObject
                val code = fields?.get("code") as? JsonPrimitive
                val message = fields?.get("message") as? JsonPrimitive
                val retryable = fields?.get("retryable") as? JsonPrimitive
                if (
                    code?.isString != true ||
                    message?.isString != true ||
                    retryable?.isString != false ||
                    retryable.booleanOrNull == null
                ) {
                    null
                } else {
                    MobileJson.decodeFromJsonElement<ErrorPayload>(payload)
                }
            } catch (error: IllegalArgumentException) {
                null
            }
        val category =
            when (decoded?.code) {
                "conversation.not_found" -> SwitchAgentFailure.Category.ConversationNotFound
                "protocol.malformed" -> SwitchAgentFailure.Category.Malformed
                "protocol.unsupported" -> SwitchAgentFailure.Category.Unsupported
                "model_list.unavailable" -> SwitchAgentFailure.Category.ModelListUnavailable
                "server.binary_busy" -> SwitchAgentFailure.Category.BinaryBusy
                "server.binary_offline" -> SwitchAgentFailure.Category.BinaryOffline
                else -> SwitchAgentFailure.Category.Failed
            }
        settle(requestId, failure(category, decoded?.retryable ?: false))
    }

    @Synchronized
    fun end() {
        ended = true
        val requests = pending.keys.toList()
        requests.forEach { settle(it, failure(SwitchAgentFailure.Category.Unavailable)) }
    }

    private fun settle(
        requestId: Long,
        result: Result<Unit>,
    ) {
        pending.remove(requestId)?.result?.complete(result)
    }

    private fun failure(
        category: SwitchAgentFailure.Category,
        retryable: Boolean = false,
    ): Result<Unit> {
        val outcome =
            when (category) {
                SwitchAgentFailure.Category.ConversationNotFound -> "not_found"
                SwitchAgentFailure.Category.Malformed -> "malformed"
                SwitchAgentFailure.Category.Unsupported -> "unsupported"
                SwitchAgentFailure.Category.ModelListUnavailable -> "model_list_unavailable"
                SwitchAgentFailure.Category.BinaryBusy -> "binary_busy"
                SwitchAgentFailure.Category.BinaryOffline -> "binary_offline"
                SwitchAgentFailure.Category.Unavailable -> "unavailable"
                SwitchAgentFailure.Category.Failed -> "failed"
            }
        RelayLog.w { "event=switch_agent outcome=$outcome" }
        return Result.failure(SwitchAgentFailure(category, retryable))
    }

    @Serializable
    private data class Payload(
        @SerialName("conversation_id") val conversationId: String,
        val agent: String,
        val model: String,
        val effort: String? = null,
    ) {
        override fun toString(): String = "SwitchAgentPayload"
    }
}
