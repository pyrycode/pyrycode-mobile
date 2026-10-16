package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** One application or lifecycle event. Each owner names a client, host and connection generation. */
internal data class SuiteStep(
    val direction: String,
    val owner: String,
    val message: Envelope? = null,
    val capabilities: Set<String> = emptySet(),
    val delayMs: Long = 0,
    val accepted: Boolean = true,
    val detail: JsonObject = JsonObject(emptyMap()),
)

/** A single causal tape spans every client, so a peer action can release a phone notification. */
internal class RecordedSuiteTape(
    private var steps: List<SuiteStep>,
    declaredIdentities: Set<String> = emptySet(),
    coalescedReadReceipts: Boolean = false,
) {
    private val consumed = mutableSetOf<Int>()
    private val requests = mutableMapOf<Pair<String, Long>, Long>()
    private val actualIds = mutableMapOf<String, MutableSet<Long>>()
    private val identities = mutableMapOf<String, String>()
    private var cursor = 0
    private var matched = 0
    private var failure: String? = null
    private var readReceipts: SuiteReadReceipts? = null
    private val queryTypes =
        setOf(
            "list_conversations",
            "request_history",
            "request_model_list",
            "request_context_usage",
            "request_session_settings",
            "request_system_prompt",
            "mcp_status_request",
            "thread_catch_up",
            "thread_page",
            "backfill_since",
        )
    private val identityKeys =
        setOf(
            "conversation_id",
            "session_id",
            "current_session_id",
            "modal_id",
            "message_id",
            "device_id",
            "tool_use_id",
            "parent_tool_use_id",
            "turn_id",
            "task_id",
            "parent",
            "epoch",
            "attachment_id",
            "question_batch_id",
            "question_id",
        )

    init {
        require(steps.size in 1..50000) { "Invalid suite recording bound" }
        declaredIdentities.forEach { identities[it] = UUID.randomUUID().toString() }
        val ids = mutableMapOf<Pair<String, String>, MutableSet<Long>>()
        var bytes = 0
        steps.forEach { step ->
            requireSafe(step.detail)
            require(step.delayMs in 0..3600000) { "Invalid suite event timing" }
            require(step.owner.matches(Regex("[A-Za-z0-9_./:-]{1,160}"))) { "Invalid suite owner" }
            require(
                step.direction in
                    setOf("client", "daemon", "connect", "open", "down", "close", "transport_down", "minimum", "inbound_end", "external"),
            ) { "Invalid suite event" }
            if (step.direction == "external") {
                require(step.owner == "android" && step.detail.keys == setOf("event"))
                require(
                    step.detail
                        .getValue("event")
                        .jsonPrimitive.content in setOf("push-token", "push-wake"),
                )
            }
            val message = step.message
            if (step.direction in setOf("client", "daemon")) {
                require(message != null && message.payload is JsonObject && message.type.isNotBlank() && message.ts.isNotBlank())
                require(message.type !in setOf("hello", "hello_ack", "rekey_request", "multipart", "fragment"))
                require(message.id >= 0 && ids.getOrPut(step.owner to step.direction) { mutableSetOf() }.add(message.id))
                message.inReplyTo?.let {
                    require(step.direction == "daemon" && it in ids[step.owner to "client"].orEmpty())
                }
                requireSafeSuiteFrame(step.direction, message)
                bytes +=
                    message.payload
                        .toString()
                        .toByteArray()
                        .size
                require(bytes <= 20_000_000) { "Invalid suite recording bound" }
                collect(message.payload, root = message.type in setOf("conversation_created", "conversation_updated"))
            } else {
                require(message == null) { "Invalid suite lifecycle event" }
            }
        }
        if (coalescedReadReceipts) {
            readReceipts = SuiteReadReceipts(steps, queryTypes, ::mapped)
            steps = readReceipts!!.steps
        }
    }

    private fun collect(
        value: JsonElement,
        parent: String = "",
        root: Boolean = false,
    ) {
        when (value) {
            is JsonObject ->
                value.forEach { (key, child) ->
                    if (child is JsonPrimitive &&
                        child.isString &&
                        child.content.isNotEmpty() &&
                        (key in identityKeys || key == "id" && (root || parent in setOf("conversations", "items", "messages", "item")))
                    ) {
                        identities.getOrPut(child.content) { UUID.randomUUID().toString() }
                    }
                    collect(child, key)
                }
            is JsonArray -> value.forEach { collect(it, parent) }
            else -> Unit
        }
    }

    private fun mapped(value: JsonElement): JsonElement =
        when (value) {
            is JsonPrimitive -> if (value.isString) identities[value.content]?.let(::JsonPrimitive) ?: value else value
            is JsonArray -> JsonArray(value.map(::mapped))
            is JsonObject -> JsonObject(value.mapValues { mapped(it.value) })
        }

    private fun fail(reason: String): Nothing {
        failure = failure ?: reason
        throw IllegalStateException(failure)
    }

    private fun eligible(): List<Int> {
        val result = mutableListOf<Int>()
        for (i in cursor until steps.size) {
            val step = steps[i]
            if (i in consumed || step.direction !in setOf("client", "connect", "close")) continue
            if (step.direction != "client" || step.message!!.type !in queryTypes) {
                if (result.isEmpty()) result += i
                break
            }
            result += i
        }
        return result
    }

    @Synchronized fun initial(): List<SuiteStep> = drain()

    @Synchronized fun send(
        owner: String,
        actual: Envelope,
    ): SuiteOutcome {
        failure?.let { fail(it) }
        if (actual.id in actualIds[owner].orEmpty()) fail("Recorded suite duplicate request ID")
        if (readReceipts?.accepts(owner, actual) == true) {
            return try {
                val response = readReceipts!!.send(owner, actual)
                actualIds.getOrPut(owner) { mutableSetOf() } += actual.id
                SuiteOutcome(listOf(response) + drain(), true)
            } catch (_: Exception) {
                fail("Recorded read receipt rejected")
            }
        }
        val expectedKinds = eligible().joinToString { "${steps[it].owner}:${steps[it].message?.type ?: steps[it].direction}" }
        val mismatch = "Recorded suite request mismatch at step ${matched + 1}: $owner:${actual.type}, expected $expectedKinds"
        val candidate =
            eligible().firstOrNull { i ->
                val expected = steps[i]
                expected.owner == owner &&
                    expected.direction == "client" &&
                    expected.message!!.type == actual.type &&
                    (actual.type !in queryTypes || mapped(expected.message.payload) == actual.payload)
            } ?: fail(mismatch)
        val expected = steps[candidate].message!!
        val bindings =
            when (actual.type) {
                "send_message" -> listOf("message_id")
                "modal_answer", "question_answer", "question_refuse" -> listOf("answer_token")
                "attachment_chunk" -> listOf("attachment_id")
                else -> emptyList()
            }
        for (key in bindings) {
            val from =
                expected.payload.jsonObject[key]
                    ?.jsonPrimitive
                    ?.contentOrNull
            val to =
                actual.payload.jsonObject[key]
                    ?.jsonPrimitive
                    ?.contentOrNull
            if (!from.isNullOrEmpty() && !to.isNullOrEmpty()) {
                val previous = identities[from]
                // Generated upload identities may be bound once; later chunks must agree.
                if (actual.type == "attachment_chunk" && from in uploadBindings && previous != to) fail(mismatch)
                identities[from] = to
                if (actual.type == "attachment_chunk") uploadBindings += from
            }
        }
        if (mapped(expected.payload) != actual.payload || actual.inReplyTo != null) {
            val checkpoint =
                if (actual.type == "mark_conversation_read") {
                    val value =
                        actual.payload.jsonObject["up_to"]
                            ?.jsonPrimitive
                            ?.content
                            ?.toULongOrNull()
                    "; observed checkpoint=$value"
                } else {
                    ""
                }
            fail(mismatch + checkpoint)
        }
        actualIds.getOrPut(owner) { mutableSetOf() } += actual.id
        requests[owner to expected.id] = actual.id
        consumed += candidate
        matched++
        readReceipts?.requested(owner, actual)
        if (actual.type !in queryTypes) readReceipts?.command()
        return SuiteOutcome(drain(), steps[candidate].accepted)
    }

    @Synchronized fun request(
        owner: String,
        actual: Envelope,
    ): List<SuiteStep> = send(owner, actual).deliveries

    @Synchronized fun arguments(values: JsonObject): JsonObject = mapped(values).jsonObject

    private val uploadBindings = mutableSetOf<String>()

    @Synchronized fun lifecycle(
        direction: String,
        owner: String,
    ): List<SuiteStep> {
        failure?.let { fail(it) }
        val candidate =
            eligible().firstOrNull { steps[it].direction == direction && steps[it].owner == owner }
                ?: fail("Recorded suite lifecycle mismatch at step ${matched + 1}")
        check(direction in setOf("connect", "close")) { "Invalid lifecycle request" }
        consumed += candidate
        matched++
        return drain()
    }

    private fun drain(): List<SuiteStep> {
        val result = mutableListOf<SuiteStep>()
        while (cursor < steps.size) {
            val step = steps[cursor]
            if (readReceipts?.permits(step) == false) break
            if (step.direction in setOf("client", "connect", "close")) {
                if (cursor !in consumed) break
            } else if (step.direction == "daemon") {
                val frame = step.message!!
                val encoded = mapped(MobileJson.encodeToJsonElement(Envelope.serializer(), frame)).jsonObject.toMutableMap()
                frame.inReplyTo?.let { encoded["in_reply_to"] = JsonPrimitive(checkNotNull(requests[step.owner to it])) }
                result += step.copy(message = MobileJson.decodeFromJsonElement(Envelope.serializer(), JsonObject(encoded)))
            } else {
                result += step
            }
            cursor++
        }
        return result
    }

    @Synchronized fun delivered(
        owner: String,
        frame: Envelope,
    ) {
        readReceipts?.delivered(owner, frame)
    }

    @Synchronized fun assertComplete() {
        failure?.let { fail(it) }
        check(cursor == steps.size) { "Recorded suite incomplete" }
        readReceipts?.assertComplete()
    }
}

internal data class SuiteOutcome(
    val deliveries: List<SuiteStep>,
    val accepted: Boolean,
)
