package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_STOP_BACKGROUND_TASK
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.ErrorPayload
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/** Connection-local fire-and-forget stop sends and one-shot refusal correlation. No reply waiter. */
internal class BackgroundTaskStops(
    private val requests: RelayRequests,
    private val send: (Envelope) -> Boolean,
    private val negotiatedCapabilities: () -> Set<String>,
) {
    private val lock = Any()
    private var ended = false
    private var attempted = false
    private val pending = mutableMapOf<Long, Pair<String, String>>()
    private val refusals = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val supported: Boolean
        get() = synchronized(lock) { !ended && CAPABILITY_STOP_BACKGROUND_TASK in negotiatedCapabilities() }

    /** Subscribe before sending. Values are originating opaque task keys, never daemon text. */
    fun observeRefusals(conversationId: String): Flow<String> = refusals.filter { it.first == conversationId }.map { it.second }

    fun stop(
        conversationId: String,
        taskId: String,
    ): Result<Unit> {
        val request =
            Envelope(
                id = requests.nextRequestId(),
                type = CAPABILITY_STOP_BACKGROUND_TASK,
                ts = Clock.System.now().toString(),
                payload = JsonObject(mapOf("conversation_id" to JsonPrimitive(conversationId), "task_id" to JsonPrimitive(taskId))),
            )
        synchronized(lock) {
            attempted = true
            if (!supported) {
                RelayLog.d { "event=background_task_stop code=unsupported" }
                return Result.failure(IllegalStateException("Background task stop unsupported"))
            }
            val target = conversationId to taskId
            pending.entries.removeAll { it.value == target }
            pending[request.id] = target
        }
        val accepted =
            try {
                send(request)
            } catch (e: CancellationException) {
                retireRequest(request.id)
                RelayLog.d { "event=background_task_stop code=cancelled" }
                throw e
            } catch (_: Exception) {
                false
            }
        if (!accepted) {
            retireRequest(request.id)
            RelayLog.d { "event=background_task_stop code=send_failed" }
            return Result.failure(IllegalStateException("Background task stop not sent"))
        }
        RelayLog.d { "event=background_task_stop code=sent" }
        return Result.success(Unit)
    }

    fun applyRefusal(
        inReplyTo: Long,
        payload: JsonElement,
    ) {
        val objectPayload = payload as? JsonObject ?: return
        // kotlinx serialization can coerce primitives to strings. Require the wire field types first.
        if ((objectPayload["code"] as? JsonPrimitive)?.isString != true ||
            (objectPayload["message"] as? JsonPrimitive)?.isString != true
        ) {
            return
        }
        val retryable = objectPayload["retryable"] as? JsonPrimitive ?: return
        if (retryable.isString || retryable.booleanOrNull == null) return
        val error =
            try {
                MobileJson.decodeFromJsonElement<ErrorPayload>(payload)
            } catch (_: IllegalArgumentException) {
                return
            }
        if (error.code != "stop_background_task.refused") return
        synchronized(lock) {
            val target = pending.remove(inReplyTo) ?: return
            refusals.tryEmit(target)
            RelayLog.d { "event=background_task_stop code=refused" }
        }
    }

    fun taskFinished(
        conversationId: String,
        taskId: String,
    ) {
        synchronized(lock) { pending.entries.removeAll { it.value == conversationId to taskId } }
    }

    fun rosterReported(
        conversationId: String,
        taskIds: Set<String>,
    ) {
        synchronized(lock) { pending.entries.removeAll { it.value.first == conversationId && it.value.second !in taskIds } }
    }

    fun end() {
        synchronized(lock) {
            if (ended) return
            ended = true
            pending.clear()
            if (attempted) RelayLog.d { "event=background_task_stop code=connection_ended" }
        }
    }

    private fun retireRequest(id: Long) {
        synchronized(lock) { pending.remove(id) }
    }
}
