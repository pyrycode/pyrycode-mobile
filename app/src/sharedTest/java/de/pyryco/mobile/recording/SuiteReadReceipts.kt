package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Android may coalesce visibility acknowledgements while a previous acknowledgement is in flight.
 * These contain no Claude content. Their recorded snapshot shape and final progress stay mandatory.
 */
internal class SuiteReadReceipts(
    source: List<SuiteStep>,
    private val queries: Set<String>,
    private val resolve: (JsonElement) -> JsonElement,
    private val retainedPhoneHistory: Boolean = false,
    private val allocateId: ((String) -> Long)? = null,
    private val requireRecordedProgress: Boolean = true,
) {
    private data class Key(
        val owner: String,
        val conversation: String,
        val action: Int,
    )

    private data class Group(
        val required: ULong,
        val maxRequested: ULong,
        val snapshot: Envelope,
        var confirmed: ULong = 0u,
        var lastRequested: ULong = 0u,
    )

    private val groups = linkedMapOf<Key, Group>()
    private val metadata = mutableMapOf<Pair<String, String>, JsonObject>()
    private val shown = mutableMapOf<Pair<String, String>, ULong>()
    private val confirmed = mutableMapOf<Pair<String, String>, ULong>()
    private val lastRequests = mutableMapOf<Pair<String, String>, ULong>()
    private val guardedSnapshots = mutableListOf<Triple<String, JsonElement, ULong>>()

    private fun host(owner: String) = owner.split('/').getOrNull(1) ?: "default"

    private fun phone(owner: String) = owner == "phone" || owner.startsWith("phone/")

    private val historyRequests = mutableMapOf<Pair<String, Long>, String>()
    private val delivered = mutableMapOf<Pair<String, String>, MutableSet<ULong>>()
    private val phoneHistory = mutableMapOf<Pair<String, String>, MutableSet<ULong>>()
    private val normalizedRequests = mutableSetOf<Pair<String, Long>>()
    private val snapshots = mutableMapOf<String, MutableSet<JsonElement>>()
    private val nextIds = mutableMapOf<String, Long>()
    private var action = 0
    val steps: List<SuiteStep>

    init {
        val replies =
            source
                .filter { it.direction == "daemon" && it.message?.inReplyTo != null }
                .associateBy { it.owner to it.message!!.inReplyTo!! }
        var epoch = 0
        source.forEachIndexed { index, step ->
            val frame = step.message ?: return@forEachIndexed
            if (step.direction == "daemon") nextIds.merge(step.owner, frame.id + 1, ::maxOf)
            if (step.direction != "client") return@forEachIndexed
            if (frame.type == "mark_conversation_read" && phone(step.owner)) {
                val response = replies[step.owner to frame.id]?.message
                if (response == null ||
                    response.type != "conversation_updated" ||
                    response.eventId != null ||
                    response.historyEntryId != null
                ) {
                    val cancelled =
                        response == null &&
                            step.accepted &&
                            source.drop(index + 1).any {
                                it.owner == step.owner && it.direction in setOf("close", "down", "inbound_end")
                            }
                    if (cancelled) normalizedRequests += step.owner to frame.id else epoch++
                    return@forEachIndexed
                }
                val request = frame.payload.jsonObject
                require(request.keys == setOf("conversation_id", "up_to")) { "Unexpected read receipt shape" }
                val conversation = request.getValue("conversation_id").jsonPrimitive.content
                val target =
                    request
                        .getValue("up_to")
                        .jsonPrimitive.content
                        .toULong()
                require(
                    response.payload.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content == conversation,
                )
                val stored =
                    response.payload.jsonObject
                        .getValue("read_up_to")
                        .jsonPrimitive.content
                        .toULong()
                require(stored <= target) { "Recorded read confirmation exceeds its request" }
                val key = Key(step.owner, conversation, epoch)
                val previous = groups[key]
                require(previous == null || target > previous.maxRequested && stored >= previous.required) {
                    "Recorded read progress is not monotonic"
                }
                groups[key] = Group(stored, target, response)
                normalizedRequests += step.owner to frame.id
                snapshots.getOrPut(step.owner) { mutableSetOf() } += response.payload
                guardedSnapshots += Triple(host(step.owner), response.payload, stored)
            } else if (frame.type !in queries) {
                epoch++
            }
        }
        steps =
            source.filterNot { step ->
                val frame = step.message
                when {
                    frame == null -> false
                    step.direction == "client" -> step.owner to frame.id in normalizedRequests
                    step.direction != "daemon" -> false
                    frame.inReplyTo != null -> step.owner to frame.inReplyTo in normalizedRequests
                    else -> frame.type == "conversation_updated" && frame.payload in snapshots[step.owner].orEmpty()
                }
            }
    }

    fun command() {
        action++
    }

    fun requested(
        owner: String,
        frame: Envelope,
    ) {
        if (frame.type == "request_history") {
            val conversation =
                frame.payload.jsonObject
                    .getValue("conversation_id")
                    .jsonPrimitive.content
            historyRequests[owner to frame.id] = conversation
        }
    }

    fun delivered(
        owner: String,
        frame: Envelope,
    ) {
        val payload = frame.payload.jsonObject

        fun rememberHistory(
            conversation: String,
            id: ULong,
        ) {
            delivered.getOrPut(owner to conversation) { mutableSetOf() } += id
            if (phone(owner)) phoneHistory.getOrPut(host(owner) to conversation) { mutableSetOf() } += id
        }

        fun remember(row: JsonObject) {
            val id = row["id"]?.jsonPrimitive?.content ?: return
            val key = host(owner) to id
            metadata[key] = row
            row["read_up_to"]
                ?.jsonPrimitive
                ?.content
                ?.toULongOrNull()
                ?.let { confirmed.merge(key, it, ::maxOf) }
        }
        when (frame.type) {
            "conversation_created", "conversation_updated" -> remember(payload)
            "conversations" ->
                (payload["conversations"] as? kotlinx.serialization.json.JsonArray)
                    ?.forEach { remember(it.jsonObject) }
        }
        if (frame.type == "history_page") {
            val conversation = historyRequests.remove(owner to frame.inReplyTo) ?: return
            (payload["entries"] as? kotlinx.serialization.json.JsonArray)?.forEach { element ->
                val entry = element.jsonObject
                val id =
                    entry
                        .getValue("id")
                        .jsonPrimitive.content
                        .toULong()
                rememberHistory(conversation, id)
                val type = entry.getValue("type").jsonPrimitive.content
                val body = entry.getValue("payload")
                if (de.pyryco.mobile.data.repository
                        .contributesToUnreadWatermark(type, body)
                ) {
                    shown.merge(host(owner) to conversation, id, ::maxOf)
                }
            }
            return
        }
        val checkpoint = frame.historyEntryId ?: return
        val conversation = payload["conversation_id"]?.jsonPrimitive?.content ?: return
        rememberHistory(conversation, checkpoint)
        if (de.pyryco.mobile.data.repository
                .contributesToUnreadWatermark(frame.type, frame.payload)
        ) {
            shown.merge(host(owner) to conversation, checkpoint, ::maxOf)
        }
    }

    fun permits(step: SuiteStep): Boolean {
        val frame = step.message ?: return true
        if (step.direction != "daemon" || frame.type != "conversation_updated" || frame.inReplyTo != null) return true
        val guard = guardedSnapshots.firstOrNull { it.first == host(step.owner) && it.second == frame.payload } ?: return true
        val conversation =
            resolve(frame.payload)
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content
        return (confirmed[host(step.owner) to conversation] ?: 0u) >= guard.third
    }

    /** An unsent automatic read notification can disappear only when its connection terminates. */
    fun mayCancel(step: SuiteStep) = !requireRecordedProgress && !permits(step)

    fun accepts(
        owner: String,
        frame: Envelope,
    ): Boolean {
        if (!phone(owner) || frame.type != "mark_conversation_read") return false
        val conversation =
            frame.payload.jsonObject["conversation_id"]
                ?.jsonPrimitive
                ?.content ?: return false
        return metadata.containsKey(host(owner) to conversation) ||
            groups.keys.any {
                it.owner == owner && resolve(JsonPrimitive(it.conversation)).jsonPrimitive.content == conversation
            }
    }

    fun send(
        owner: String,
        frame: Envelope,
    ): SuiteStep {
        val payload = frame.payload.jsonObject
        require(payload.keys == setOf("conversation_id", "up_to") && frame.inReplyTo == null) { "Read receipt payload changed" }
        val conversation = payload.getValue("conversation_id").jsonPrimitive.content
        val target =
            payload
                .getValue("up_to")
                .jsonPrimitive.content
                .toULong()
        val key =
            groups.keys.singleOrNull {
                it.owner == owner && resolve(JsonPrimitive(it.conversation)).jsonPrimitive.content == conversation && it.action == action
            }
        val group = key?.let(groups::getValue)
        val last = lastRequests[owner to conversation] ?: 0u
        val wasDelivered =
            target in delivered[owner to conversation].orEmpty() ||
                retainedPhoneHistory &&
                target in phoneHistory[host(owner) to conversation].orEmpty()
        check(target > last && wasDelivered && (retainedPhoneHistory || group == null || target <= group.maxRequested)) {
            "Read receipt checkpoint rejected: target=$target previous=$last delivered=$wasDelivered " +
                "maximum=${group?.maxRequested} action=$action"
        }
        lastRequests[owner to conversation] = target
        val server = host(owner) to conversation
        val ceiling =
            if (retainedPhoneHistory) {
                group?.required?.let { maxOf(it, shown[server] ?: it) }
                    ?: checkNotNull(shown[server]) { "Read receipt has no delivered content" }
            } else {
                group?.required ?: checkNotNull(shown[server]) { "Read receipt has no delivered content" }
            }
        val stored = maxOf(confirmed[server] ?: 0u, minOf(target, ceiling))
        confirmed[server] = stored
        group?.confirmed = stored
        val original = group?.snapshot ?: Envelope(0, "conversation_updated", frame.ts, JsonObject(emptyMap()))
        val allowed =
            setOf("id", "is_promoted", "is_archived", "is_muted", "read_up_to", "name", "cwd", "workspace_label", "last_used_at", "agent")
        val snapshot = metadata[server]?.filterKeys { it in allowed }.orEmpty()
        val defaults =
            mapOf(
                "is_promoted" to JsonPrimitive(false),
                "is_archived" to JsonPrimitive(false),
                "is_muted" to JsonPrimitive(false),
            )
        val response = if (group != null) resolve(original.payload).jsonObject else JsonObject(defaults + snapshot)
        val id = allocateId?.invoke(owner) ?: nextIds[owner] ?: 1L
        nextIds[owner] = id + 1
        return SuiteStep(
            "daemon",
            owner,
            original.copy(
                id = id,
                inReplyTo = frame.id,
                payload =
                    JsonObject(
                        response + (
                            "read_up_to" to
                                de.pyryco.mobile.data.network.MobileJson
                                    .parseToJsonElement(stored.toString())
                        ),
                    ),
            ),
        )
    }

    fun progress() =
        groups
            .map { (key, group) ->
                val conversation = resolve(JsonPrimitive(key.conversation)).jsonPrimitive.content
                "${key.owner}:${key.action}:${confirmed[host(key.owner) to conversation] ?: 0u}/${group.required}"
            }.joinToString(",")

    fun isComplete() =
        !requireRecordedProgress ||
            groups.all { (key, group) ->
                val conversation = resolve(JsonPrimitive(key.conversation)).jsonPrimitive.content
                (confirmed[host(key.owner) to conversation] ?: 0u) >= group.required
            }

    fun assertComplete() {
        check(isComplete()) { "Recorded final read progress is incomplete" }
    }
}
