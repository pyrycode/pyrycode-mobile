package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
    private val literalProducerIdentities: Boolean = false,
    private val redundantModelQueries: Boolean = false,
    retainedPhoneHistory: Boolean = false,
    recordedSettingsReads: Boolean = false,
    refusedBackfillProbes: Boolean = false,
    private val requireRecordedReadProgress: Boolean = true,
) {
    private var optionalModelReplies = emptyMap<Int, Int>()
    private val redundantModels = mutableSetOf<Pair<String, String>>()
    private val consumed = mutableSetOf<Int>()
    private val requests = mutableMapOf<Pair<String, Long>, Long>()
    private val actualIds = mutableMapOf<String, MutableSet<Long>>()
    private val identities = mutableMapOf<String, String>()
    private val released = mutableSetOf<Int>()
    private var lifecycleBefore = emptyList<Int?>()
    private var requiredRequests = emptyMap<String, List<Int>>()
    private var matched = 0
    private var failure: String? = null
    private var readReceipts: SuiteReadReceipts? = null
    private var polledReads: SuitePolledReads? = null
    private val daemonIds = mutableMapOf<String, Long>()

    private fun nextDaemonId(owner: String): Long {
        val next = daemonIds[owner] ?: 1L
        check(next < Long.MAX_VALUE) { "Recorded response ID exhausted" }
        daemonIds[owner] = next + 1
        return next
    }

    private val queryTypes =
        setOf(
            "list_conversations",
            "request_history",
            "request_attachment",
            "request_model_list",
            "request_context_usage",
            "request_session_settings",
            "request_system_prompt",
            "mcp_status_request",
            "thread_catch_up",
            "thread_page",
            "backfill_since",
            "register_push_token",
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
                require(
                    message != null &&
                        (message.payload is JsonObject || message.payload == JsonNull) &&
                        message.type.isNotBlank() &&
                        message.ts.isNotBlank(),
                )
                require(message.type !in setOf("hello", "hello_ack", "rekey_request", "multipart", "fragment"))
                require(message.id >= 0 && ids.getOrPut(step.owner to step.direction) { mutableSetOf() }.add(message.id))
                message.inReplyTo?.let {
                    require(step.direction == "daemon" && it in ids[step.owner to "client"].orEmpty())
                }
                if (step.direction == "daemon") {
                    require(message.id < Long.MAX_VALUE)
                    daemonIds.merge(step.owner, message.id + 1, ::maxOf)
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
            readReceipts = SuiteReadReceipts(steps, queryTypes, ::mapped, retainedPhoneHistory, ::nextDaemonId, requireRecordedReadProgress)
            steps = readReceipts!!.steps
        }
        if (recordedSettingsReads || refusedBackfillProbes) {
            polledReads = SuitePolledReads(steps, ::mapped, ::nextDaemonId, recordedSettingsReads, refusedBackfillProbes)
            steps = polledReads!!.steps
        }
        if (redundantModelQueries) {
            optionalModelReplies =
                steps.indices
                    .mapNotNull { index ->
                        val query = steps[index]
                        val frame = query.message
                        if (query.direction != "client" ||
                            !query.owner.startsWith("phone/") ||
                            frame?.type != "request_model_list" ||
                            !query.accepted ||
                            (frame.payload as? JsonObject)?.keys != setOf("conversation_id") ||
                            steps.count {
                                it.owner == query.owner &&
                                    it.direction == "client" &&
                                    it.message?.type == frame.type &&
                                    it.message.payload == frame.payload
                            } != 1
                        ) {
                            return@mapNotNull null
                        }
                        val start =
                            (0 until index)
                                .lastOrNull {
                                    steps[it].direction == "client" && steps[it].message!!.type !in queryTypes
                                }?.plus(1) ?: 0
                        val end =
                            (index + 1 until steps.size).firstOrNull {
                                steps[it].direction == "client" && steps[it].message!!.type !in queryTypes
                            } ?: steps.size
                        val response =
                            (index + 1 until end).singleOrNull {
                                val candidate = steps[it]
                                candidate.owner == query.owner && candidate.message?.inReplyTo == frame.id
                            }
                                ?: return@mapNotNull null
                        val reply = steps[response].message!!
                        if (reply.type != "model_list" ||
                            reply.historyEntryId != null ||
                            reply.eventId != null ||
                            (reply.payload as? JsonObject)?.get("conversation_id") != frame.payload.jsonObject["conversation_id"]
                        ) {
                            return@mapNotNull null
                        }
                        val independent =
                            (start until end).any {
                                val other = steps[it]
                                other.owner == query.owner &&
                                    other.direction == "daemon" &&
                                    other.message?.type == "model_list" &&
                                    other.message.inReplyTo == null &&
                                    other.message.payload == reply.payload
                            }
                        if (independent) index to response else null
                    }.toMap()
        }
        val lifecycle = mutableMapOf<String, Int>()
        lifecycleBefore =
            steps.mapIndexed { index, step ->
                val previous = lifecycle[family(step.owner)]
                if (step.direction in setOf("connect", "close")) lifecycle[family(step.owner)] = index
                previous
            }
        requiredRequests =
            steps.indices.filter { steps[it].direction == "client" && it !in optionalModelReplies }.groupBy { steps[it].owner }
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
                        identities.getOrPut(child.content) {
                            if (literalProducerIdentities) child.content else UUID.randomUUID().toString()
                        }
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

    private fun family(owner: String) = owner.substringBeforeLast('/', owner)

    private fun precedingRequestsComplete(
        index: Int,
        owner: String,
    ) = requiredRequests[owner].orEmpty().all { it >= index || it in consumed }

    private fun commandBoundary(): Int =
        steps.indices.firstOrNull {
            it !in consumed && steps[it].direction == "client" && steps[it].message!!.type !in queryTypes
        } ?: steps.size

    private fun checkpointBoundary(): Int =
        steps.indices.firstOrNull {
            it !in consumed && steps[it].owner == "control" && steps[it].message?.type == "test-control:checkpoint"
        } ?: steps.size

    private fun precedingWorkReleased(
        before: Int,
        allowDeferredReads: Boolean,
    ) = (0 until before).all {
        it in released || allowDeferredReads && readReceipts?.permits(steps[it]) == false
    }

    /** Observations fence ordinary wire work. The declared read model governs deferred notifications. */
    @Synchronized fun checkpointReady(name: String): Boolean {
        failure?.let { fail(it) }
        val boundary = checkpointBoundary()
        val expected = steps.getOrNull(boundary)?.message?.payload as? JsonObject
        if (expected != JsonObject(mapOf("body" to JsonPrimitive(name)))) {
            fail("Recorded suite test checkpoint mismatch")
        }
        return precedingWorkReleased(boundary, name != "scenario-complete" || !requireRecordedReadProgress)
    }

    /** Negative assertions observe every earlier released message before their short replay wait. */
    @Synchronized fun negativeObservationReady(name: String): Boolean {
        failure?.let { fail(it) }
        val boundary =
            steps.indices.firstOrNull {
                it !in consumed && steps[it].owner == "control" && steps[it].message?.type == "test-control:negative-absence"
            } ?: fail("Recorded negative observation is missing")
        if (steps[boundary].message!!.payload != JsonObject(mapOf("body" to JsonPrimitive(name)))) {
            fail("Recorded negative observation mismatch")
        }
        return precedingWorkReleased(boundary, allowDeferredReads = true)
    }

    private fun eligible(): List<Int> {
        val boundary = commandBoundary()
        val checkpoint = checkpointBoundary()
        return steps.indices.filter { index ->
            val step = steps[index]
            val connected = lifecycleBefore[index]?.let { it in consumed } ?: true
            when {
                index in consumed || !connected || index > checkpoint -> false
                step.direction == "connect" -> true
                step.direction == "close" -> precedingRequestsComplete(index, step.owner)
                step.direction == "client" -> step.message!!.type in queryTypes || index == boundary
                else -> false
            }
        }
    }

    @Synchronized fun initial(): List<SuiteStep> = drain()

    @Synchronized fun closeReady(owner: String): Boolean {
        failure?.let { fail(it) }
        val close =
            steps.indices.firstOrNull { it !in consumed && steps[it].owner == owner && steps[it].direction == "close" }
                ?: fail("No recorded close for connection $owner")
        return close in eligible() && precedingWorkReleased(close, allowDeferredReads = true)
    }

    @Synchronized fun hasEligibleRefusal(owner: String) =
        eligible().any {
            steps[it].direction == "client" && steps[it].owner == owner && !steps[it].accepted
        }

    @Synchronized fun send(
        owner: String,
        actual: Envelope,
    ): SuiteOutcome {
        failure?.let { fail(it) }
        if (owner == "control" && actual.type == "test-control:checkpoint") {
            val name =
                (actual.payload as? JsonObject)
                    ?.get("body")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            if (!checkpointReady(name)) fail("Recorded suite test checkpoint prefix incomplete")
        }
        if (actual.id in actualIds[owner].orEmpty()) fail("Recorded suite duplicate request ID")
        if (polledReads?.accepts(owner, actual) == true) {
            val connection = steps.indices.lastOrNull { steps[it].owner == owner && steps[it].direction == "connect" }
            if (connection != null && connection !in consumed) fail("Recorded polling connection not open")
            val response = polledReads!!.send(owner, actual)
            actualIds.getOrPut(owner) { mutableSetOf() } += actual.id
            return SuiteOutcome(response + drain(), true)
        }
        if (readReceipts?.accepts(owner, actual) == true) {
            return try {
                val response = readReceipts!!.send(owner, actual)
                actualIds.getOrPut(owner) { mutableSetOf() } += actual.id
                SuiteOutcome(listOf(response) + drain(), true)
            } catch (error: Exception) {
                val detail = error.message?.takeIf { it.startsWith("Read receipt checkpoint rejected:") }.orEmpty()
                fail("Recorded read receipt rejected $detail")
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
            }
        if (candidate == null) {
            if (acceptRedundantModelQuery(owner, actual)) {
                actualIds.getOrPut(owner) { mutableSetOf() } += actual.id
                return SuiteOutcome(drain(), true)
            }
            fail(mismatch)
        }
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
        if (actual.type == "request_model_list") {
            (actual.payload as? JsonObject)?.get("conversation_id")?.jsonPrimitive?.contentOrNull?.let {
                redundantModels += owner to it
            }
        }
        actualIds.getOrPut(owner) { mutableSetOf() } += actual.id
        requests[owner to expected.id] = actual.id
        optionalModelReplies[candidate]?.let { released.remove(it) }
        consumed += candidate
        matched++
        readReceipts?.requested(owner, actual)
        if (actual.type !in queryTypes) {
            readReceipts?.command()
            polledReads?.command(owner, actual)
        }
        return SuiteOutcome(drain(), steps[candidate].accepted)
    }

    @Synchronized fun request(
        owner: String,
        actual: Envelope,
    ): List<SuiteStep> = send(owner, actual).deliveries

    @Synchronized fun arguments(values: JsonObject): JsonObject = mapped(values).jsonObject

    private val uploadBindings = mutableSetOf<String>()

    /** The production model projection permits one ask racing an unsolicited reconnect catalog. */
    private fun acceptRedundantModelQuery(
        owner: String,
        actual: Envelope,
    ): Boolean {
        if (!redundantModelQueries ||
            !owner.startsWith("phone/") ||
            actual.type != "request_model_list" ||
            actual.inReplyTo != null
        ) {
            return false
        }
        val payload = actual.payload as? JsonObject ?: return false
        if (payload.keys != setOf("conversation_id")) return false
        val conversation = payload["conversation_id"]?.jsonPrimitive?.contentOrNull ?: return false
        if (eligible().none {
                steps[it].owner == owner && steps[it].direction == "client" && steps[it].message?.type in queryTypes
            }
        ) {
            return false
        }
        val boundary = commandBoundary()
        val observed =
            steps.take(boundary).any {
                it.owner == owner &&
                    it.direction == "daemon" &&
                    it.message?.type == "model_list" &&
                    it.message.inReplyTo == null &&
                    (mapped(it.message.payload) as? JsonObject)?.get("conversation_id") == payload["conversation_id"]
            }
        return observed && redundantModels.add(owner to conversation)
    }

    @Synchronized fun lifecycle(
        direction: String,
        owner: String,
    ): List<SuiteStep> {
        failure?.let { fail(it) }
        val candidate =
            eligible().firstOrNull { steps[it].direction == direction && steps[it].owner == owner }
                ?: fail(
                    "Recorded suite lifecycle mismatch at step ${matched + 1}: $owner:$direction, expected " +
                        eligible().joinToString { "${steps[it].owner}:${steps[it].message?.type ?: steps[it].direction}" },
                )
        check(direction in setOf("connect", "close")) { "Invalid lifecycle request" }
        consumed += candidate
        if (direction == "close") cancelUnsentReadNotifications(candidate, owner)
        matched++
        return drain()
    }

    private fun cancelUnsentReadNotifications(
        before: Int,
        owner: String,
    ) {
        for (index in 0 until before) {
            if (steps[index].owner == owner && readReceipts?.mayCancel(steps[index]) == true) released += index
        }
    }

    private fun drain(): List<SuiteStep> {
        val result = mutableListOf<SuiteStep>()
        val blockedOwners = mutableSetOf<String>()
        for (index in 0 until commandBoundary()) {
            if (index in released) continue
            val step = steps[index]
            if (step.direction in setOf("client", "connect", "close")) {
                if (index in consumed || index in optionalModelReplies) released += index
                continue
            }
            if (lifecycleBefore[index]?.let { it !in consumed } == true) continue
            // Android inputs affect the whole graph. They cannot cross a captured restart or unfinished request.
            if (step.direction == "external" && !precedingWorkReleased(index, allowDeferredReads = true)) continue
            if (step.direction in setOf("inbound_end", "down", "transport_down") && !precedingRequestsComplete(index, step.owner)) continue
            // An automatic read update must not hold the producer traffic that makes that read possible.
            // Keep connection termination behind it, while independent messages retain their source order.
            if (step.owner in blockedOwners && step.direction !in setOf("daemon", "settings_snapshot")) continue
            if (readReceipts?.permits(step) == false) {
                if (readReceipts?.mayCancel(step) != true) blockedOwners += step.owner
                continue
            }
            val frame = step.message
            if (index in optionalModelReplies.values && optionalModelReplies.entries.single { it.value == index }.key !in consumed) {
                released += index
                continue
            }
            if (step.direction == "daemon" && frame!!.inReplyTo?.let { step.owner to it !in requests } == true) continue
            if (step.direction in setOf("inbound_end", "down", "transport_down")) cancelUnsentReadNotifications(index, step.owner)
            if (step.direction in setOf("daemon", "settings_snapshot")) {
                val encoded = mapped(MobileJson.encodeToJsonElement(Envelope.serializer(), frame!!)).jsonObject.toMutableMap()
                if (step.direction == "settings_snapshot") {
                    encoded.remove("in_reply_to")
                } else {
                    frame.inReplyTo?.let { encoded["in_reply_to"] = JsonPrimitive(checkNotNull(requests[step.owner to it])) }
                }
                result += step.copy(message = MobileJson.decodeFromJsonElement(Envelope.serializer(), JsonObject(encoded)))
            } else {
                result += step
            }
            released += index
        }
        return result
    }

    @Synchronized fun deliveredSnapshot(step: SuiteStep): List<SuiteStep> = checkNotNull(polledReads).delivered(step)

    @Synchronized fun delivered(
        owner: String,
        frame: Envelope,
    ) {
        readReceipts?.delivered(owner, frame)
    }

    @Synchronized fun progress(): String {
        val index = commandBoundary()
        val next = steps.getOrNull(index)
        val type = next?.message?.type?.takeIf { it.matches(Regex("[A-Za-z0-9_:-]{1,100}")) } ?: "none"
        val pending =
            steps.indices.filter { it !in released }.take(8).joinToString {
                "${steps[it].owner}:${steps[it].message?.type ?: steps[it].direction}"
            }
        return "Recorded suite progress: matched=$matched remaining=${steps.size - released.size} " +
            "next=${next?.owner}:$type index=$index pending=$pending reads=${readReceipts?.progress()}"
    }

    @Synchronized fun assertComplete() {
        failure?.let { fail(it) }
        check(released.size == steps.size) { progress() }
        readReceipts?.assertComplete()
        polledReads?.assertComplete()
    }
}

internal data class SuiteOutcome(
    val deliveries: List<SuiteStep>,
    val accepted: Boolean,
)
