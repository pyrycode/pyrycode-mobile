package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Test-only application messages. Transport keys and handshake frames are never part of a fixture. */
internal class ExchangeRecording private constructor(
    val document: JsonObject,
) {
    val wire =
        document.getValue("wire").jsonArray.map { entry ->
            entry.jsonObject
                .getValue("direction")
                .jsonPrimitive.content to
                MobileJson.decodeFromJsonElement(Envelope.serializer(), entry.jsonObject.getValue("message"))
        }
    val capabilities =
        document
            .getValue("capabilities")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
    val scenario get() = document.getValue("scenario").jsonObject

    companion object {
        fun parse(text: String): ExchangeRecording {
            fun invalid(): Nothing = throw IllegalArgumentException("Invalid application exchange recording")
            if (text.toByteArray().size > 2_000_000) invalid()
            try {
                val doc = MobileJson.parseToJsonElement(text).jsonObject
                require(doc["format"]?.jsonPrimitive?.content == "pyrycode.mobile-exchange" && doc["version"]?.jsonPrimitive?.int == 1)
                val source = doc.getValue("source").jsonObject
                for (key in listOf("mobileRevision", "daemonRevision")) {
                    require(
                        source[key]?.jsonPrimitive?.content?.matches(Regex("[a-f0-9]{40}")) == true,
                    )
                }
                require(source["daemonBinarySha256"]?.jsonPrimitive?.content?.matches(Regex("[a-f0-9]{64}")) == true)
                require(source["claudeVersion"]?.jsonPrimitive?.content?.isNotBlank() == true)
                java.time.Instant.parse(source.getValue("capturedAt").jsonPrimitive.content)
                require(doc.getValue("capabilities").jsonArray.any { it.jsonPrimitive.content == "interactive" })
                for (key in listOf(
                    "prompt",
                    "expectedReply",
                    "allowLabel",
                )) {
                    require(
                        doc
                            .getValue("scenario")
                            .jsonObject
                            .getValue(key)
                            .jsonPrimitive.content
                            .isNotBlank(),
                    )
                }
                requireSafe(doc)
                val recording = ExchangeRecording(doc)
                require(recording.wire.size in 1..5000)
                val ids = mutableMapOf("client" to mutableSetOf<Long>(), "daemon" to mutableSetOf<Long>())
                recording.wire.forEach { (direction, frame) ->
                    require(direction in ids && ids.getValue(direction).add(frame.id) && frame.id >= 0)
                    require(frame.type !in forbidden && frame.type.isNotBlank() && frame.ts.isNotBlank() && frame.payload is JsonObject)
                    frame.inReplyTo?.let { require(direction == "daemon" && it in ids.getValue("client")) }
                }
                return recording
            } catch (_: Exception) {
                invalid()
            }
        }
    }
}

private val forbidden = setOf("hello", "hello_ack", "rekey_request", "multipart", "fragment")
private val sensitiveKeys = Regex("^(token|access_?token|refresh_?token|private_?key|secret|authorization)$", RegexOption.IGNORE_CASE)
private val credential = Regex("sk-ant-|gh[pousr]_|-----BEGIN .*PRIVATE KEY-----")

internal fun requireSafe(value: JsonElement) {
    when (value) {
        is JsonObject ->
            value.forEach { (key, child) ->
                require(!sensitiveKeys.matches(key))
                requireSafe(child)
            }
        is JsonArray -> value.forEach(::requireSafe)
        is JsonPrimitive -> require(!credential.containsMatchIn(value.content))
    }
}

/** Commands are causal boundaries. Named read-only queries can reorder only before the next command. */
internal class RecordedExchange(
    private val recording: ExchangeRecording,
) {
    private val queries =
        setOf(
            "list_conversations",
            "request_history",
            "thread_catch_up",
            "thread_page",
            "request_session_settings",
            "request_context_usage",
            "request_model_list",
            "request_system_prompt",
            "mcp_status_request",
        )
    private val identityKeys =
        setOf(
            "conversation_id",
            "current_session_id",
            "session_id",
            "modal_id",
            "message_id",
            "device_id",
            "tool_use_id",
            "parent_tool_use_id",
            "turn_id",
            "task_id",
            "parent",
            "epoch",
        )
    private val identities = mutableMapOf<String, String>()
    private val requestIds = mutableMapOf<Long, Long>()
    private val actualIds = mutableSetOf<Long>()
    private val consumed = mutableSetOf<Int>()
    private var index = 0
    private var matched = 0
    private var failure: String? = null

    init {
        fun collect(
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
        recording.wire.forEach { (_, frame) ->
            collect(
                frame.payload,
                root =
                    frame.type in setOf("conversation_created", "conversation_updated"),
            )
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

    @Synchronized fun initial(): List<Envelope> = drain()

    @Synchronized fun reply(actual: Envelope): List<Envelope> {
        failure?.let { fail(it) }
        if (actual.id in actualIds) fail("Recorded exchange duplicate request ID")
        val eligible = mutableListOf<Int>()
        for (i in index until recording.wire.size) {
            val (direction, frame) = recording.wire[i]
            if (direction != "client" || i in consumed) continue
            if (frame.type !in queries) {
                if (eligible.isEmpty()) eligible += i
                break
            }
            eligible += i
        }
        val mismatch = "Recorded exchange request mismatch at step ${matched + 1}"
        val candidate =
            eligible.firstOrNull { i ->
                val expected = recording.wire[i].second
                expected.type == actual.type && (actual.type !in queries || mapped(expected.payload) == actual.payload)
            } ?: fail(mismatch)
        val expected = recording.wire[candidate].second
        val bindings =
            when (actual.type) {
                "send_message" -> listOf("message_id")
                "modal_answer" -> listOf("answer_token")
                else -> emptyList()
            }
        for (key in bindings) {
            val from = (expected.payload as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull
            val to = (actual.payload as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull
            if (!from.isNullOrEmpty() && !to.isNullOrEmpty()) identities[from] = to
        }
        if (mapped(expected.payload) != actual.payload || actual.inReplyTo != null) fail(mismatch)
        actualIds += actual.id
        requestIds[expected.id] = actual.id
        consumed += candidate
        matched++
        return drain()
    }

    private fun drain(): List<Envelope> {
        val result = mutableListOf<Envelope>()
        while (index < recording.wire.size) {
            val (direction, frame) = recording.wire[index]
            if (direction == "client") {
                if (index !in consumed) break
            } else {
                val encoded = MobileJson.encodeToJsonElement(Envelope.serializer(), frame)
                val remapped = mapped(encoded).jsonObject.toMutableMap()
                frame.inReplyTo?.let { remapped["in_reply_to"] = JsonPrimitive(checkNotNull(requestIds[it])) }
                result += MobileJson.decodeFromJsonElement(Envelope.serializer(), JsonObject(remapped))
            }
            index++
        }
        return result
    }

    @Synchronized fun assertComplete() {
        failure?.let { fail(it) }
        check(index == recording.wire.size) { "Recorded exchange incomplete" }
    }
}
