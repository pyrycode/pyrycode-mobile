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
    private val delivered = mutableMapOf<Pair<String, String>, MutableSet<ULong>>()
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
        source.forEach { step ->
            val frame = step.message ?: return@forEach
            if (step.direction == "daemon") nextIds.merge(step.owner, frame.id + 1, ::maxOf)
            if (step.direction != "client") return@forEach
            if (frame.type == "mark_conversation_read") {
                val response = replies[step.owner to frame.id]?.message ?: return@forEach
                if (response.type != "conversation_updated" || response.eventId != null || response.historyEntryId != null) return@forEach
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

    fun delivered(
        owner: String,
        frame: Envelope,
    ) {
        val checkpoint = frame.historyEntryId ?: return
        val conversation =
            frame.payload.jsonObject["conversation_id"]
                ?.jsonPrimitive
                ?.content ?: return
        delivered.getOrPut(owner to conversation) { mutableSetOf() } += checkpoint
    }

    fun accepts(
        owner: String,
        frame: Envelope,
    ): Boolean {
        if (frame.type != "mark_conversation_read") return false
        val payload = frame.payload.jsonObject
        val conversation = payload["conversation_id"]?.jsonPrimitive?.content ?: return false
        return groups.keys.any {
            it.owner == owner &&
                resolve(JsonPrimitive(it.conversation)).jsonPrimitive.content == conversation &&
                it.action == action
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
            groups.keys.single {
                it.owner == owner &&
                    resolve(JsonPrimitive(it.conversation)).jsonPrimitive.content == conversation &&
                    it.action == action
            }
        val group = groups.getValue(key)
        check(target > group.lastRequested && target <= group.maxRequested && target in delivered[owner to conversation].orEmpty()) {
            "Read receipt is duplicate, regressive or acknowledges undelivered history"
        }
        group.lastRequested = target
        group.confirmed = minOf(target, group.required)
        val original = group.snapshot
        val response = resolve(original.payload).jsonObject
        val id = nextIds.getValue(owner)
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
                                    .parseToJsonElement(group.confirmed.toString())
                        ),
                    ),
            ),
        )
    }

    fun assertComplete() {
        check(groups.values.all { it.confirmed == it.required }) { "Recorded final read progress is incomplete" }
    }
}
