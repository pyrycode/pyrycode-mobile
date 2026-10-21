package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ThreadDecodeOutcome
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.ThreadQueryDecoder
import de.pyryco.mobile.data.network.ThreadQueryReply
import de.pyryco.mobile.data.network.ThreadQueryRequest
import de.pyryco.mobile.data.network.ThreadUpdateDto
import de.pyryco.mobile.data.network.threadUnsigned
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
    cancelledClearedSettingsReads: Boolean = false,
) {
    private var optionalModelReplies = emptyMap<Int, Int>()
    private val optionalTailReplies = mutableMapOf<Int, List<Int>>()
    private val deliveredThreadReplies = mutableMapOf<Int, Envelope>()

    private class DeliveredQueryReply(
        val terminal: ThreadQueryReply.Success,
        val items: List<ThreadItemDto>,
    )

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
    private val threadQueryRequests =
        steps
            .filter { it.direction == "client" && it.message?.type in setOf("thread_catch_up", "thread_page") }
            .map { it.owner to it.message!!.id }
            .toSet()

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
            polledReads =
                SuitePolledReads(
                    steps,
                    ::mapped,
                    ::nextDaemonId,
                    recordedSettingsReads,
                    refusedBackfillProbes,
                    cancelledClearedSettingsReads,
                    queryTypes,
                )
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
    ) = requiredRequests[owner].orEmpty().all { it >= index || it in consumed || it in optionalTailReplies }

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

    /** A read may race a pure test observation; its reply still waits in drain until that observation. */
    private fun threadQueryMayRaceCheckpoint(
        index: Int,
        boundary: Int,
        checkpoint: Int,
    ): Boolean {
        val step = steps[index]
        if (boundary != checkpoint ||
            step.direction != "client" ||
            !step.accepted ||
            step.owner to step.message!!.id !in threadQueryRequests
        ) {
            return false
        }
        return (checkpoint + 1 until index).all { before ->
            val earlier = steps[before]
            earlier.direction == "client" &&
                (earlier.message!!.type in queryTypes || earlier.owner == "control" && earlier.message.type == "test-control:checkpoint") ||
                earlier.direction == "daemon" &&
                earlier.owner != step.owner
        }
    }

    private fun eligible(): List<Int> {
        val boundary = commandBoundary()
        val checkpoint = checkpointBoundary()
        return steps.indices.filter { index ->
            val step = steps[index]
            val connected = lifecycleBefore[index]?.let { it in consumed } ?: true
            when {
                index in consumed || !connected -> false
                index > checkpoint && !threadQueryMayRaceCheckpoint(index, boundary, checkpoint) -> false
                step.direction == "connect" -> true
                step.direction == "close" -> precedingRequestsComplete(index, step.owner)
                step.direction == "client" -> step.message!!.type in queryTypes || index == boundary
                else -> false
            }
        }
    }

    private fun catchUp(index: Int): ThreadQueryRequest.CatchUp? {
        return try {
            val step = steps[index]
            val frame = step.message ?: return null
            val payload = mapped(frame.payload) as? JsonObject ?: return null
            if (step.direction != "client" ||
                !step.accepted ||
                frame.type != "thread_catch_up" ||
                payload.keys != setOf("conversation_id", "epoch", "version")
            ) {
                return null
            }
            val conversation = (payload["conversation_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val epoch = (payload["epoch"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (conversation.isEmpty()) return null
            ThreadQueryRequest.CatchUp(conversation, epoch, payload.threadUnsigned("version"))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun replyIndices(query: Int): List<Int> =
        steps.indices.filter {
            it > query &&
                steps[it].direction == "daemon" &&
                steps[it].owner == steps[query].owner &&
                steps[it].message?.inReplyTo == steps[query].message!!.id
        }

    /** Reuse the wire decoder over actual delivered replies, without a second store or certificate authority. */
    private fun decodedQuery(
        query: Int,
        frames: List<Envelope>,
    ): DeliveredQueryReply? {
        if (frames.isEmpty() ||
            frames.any { it.type != "thread_items" } ||
            (frames.last().payload as? JsonObject)?.get("final") != JsonPrimitive(true)
        ) {
            return null
        }
        val request = catchUp(query) ?: return null
        val step = steps[query]
        val envelope = checkNotNull(step.message)
        val decoder = ThreadQueryDecoder(step.owner, step.owner, envelope.id, request, nowMillis = { 0 })
        val items = mutableListOf<ThreadItemDto>()
        var terminal: ThreadQueryReply.Success? = null
        for ((index, frame) in frames.withIndex()) {
            for (outcome in decoder.decode(frame.copy(inReplyTo = envelope.id))) {
                when (outcome) {
                    is ThreadDecodeOutcome.Pending ->
                        if ((frame.payload as? JsonObject)?.get("continuation") !is JsonObject) return null
                    is ThreadDecodeOutcome.Repair -> return null
                    is ThreadDecodeOutcome.Complete ->
                        when (val reply = outcome.value) {
                            is ThreadQueryReply.Batch -> items += reply.items
                            is ThreadQueryReply.Success -> {
                                if (index != frames.lastIndex) return null
                                terminal = reply
                            }
                            else -> return null
                        }
                }
            }
        }
        if (items.map { it.id }.distinct().size != items.size) return null
        return terminal?.let { DeliveredQueryReply(it, items) }
    }

    private fun mappedFrame(frame: Envelope): Envelope =
        MobileJson.decodeFromJsonElement(Envelope.serializer(), mapped(MobileJson.encodeToJsonElement(Envelope.serializer(), frame)))

    /** This chooses omission only after the real later terminal boundary is attempted. */
    private fun prepareTails(
        boundary: Int,
        owner: String? = null,
    ) {
        for (query in 0 until boundary) {
            val step = steps[query]
            if (query in consumed ||
                query in optionalTailReplies ||
                !step.owner.startsWith("phone/") ||
                owner != null &&
                step.owner != owner ||
                lifecycleBefore[query]?.let { it !in consumed } == true
            ) {
                continue
            }
            val request = catchUp(query) ?: continue
            if (request.version == 0uL || request.epoch.isEmpty()) continue
            val replies = replyIndices(query)
            if (replies.isEmpty() || replies.any { it >= boundary }) continue
            val candidate = decodedQuery(query, replies.map { mappedFrame(checkNotNull(steps[it].message)) }) ?: continue
            if (candidate.terminal.checkpoint.epoch != request.epoch) continue
            if (candidate.terminal.checkpoint.version != request.version) continue
            val prior =
                (0 until query).lastOrNull { previous ->
                    if (previous !in consumed ||
                        steps[previous].owner != step.owner ||
                        lifecycleBefore[previous] != lifecycleBefore[query]
                    ) {
                        return@lastOrNull false
                    }
                    val previousRequest = catchUp(previous) ?: return@lastOrNull false
                    if (previousRequest.conversationId != request.conversationId) return@lastOrNull false
                    val actualReplies = replyIndices(previous)
                    if (actualReplies.isEmpty()) return@lastOrNull false
                    if (actualReplies.any { it >= query || it !in deliveredThreadReplies }) return@lastOrNull false
                    val priorFrames = actualReplies.map { checkNotNull(deliveredThreadReplies[it]) }
                    val delivered = decodedQuery(previous, priorFrames) ?: return@lastOrNull false
                    if (delivered.terminal.checkpoint.epoch != request.epoch) return@lastOrNull false
                    if (delivered.terminal.checkpoint.version != request.version) return@lastOrNull false
                    if (candidate.items.any { item -> delivered.items.none { it.raw == item.raw } }) return@lastOrNull false
                    // No intervening source change, command, external input, reset, or canonical mutation may be borrowed.
                    (actualReplies.last() + 1..replies.last()).all { index ->
                        index == query ||
                            index in replies ||
                            steps[index].let { between ->
                                between.direction == "daemon" &&
                                    (between.owner != step.owner || between.message?.type?.startsWith("thread_") != true)
                            }
                    }
                } ?: continue
            check(prior < query)
            optionalTailReplies[query] = replies
        }
    }

    @Synchronized fun prepareClose(owner: String): List<SuiteStep> {
        failure?.let { fail(it) }
        val close =
            steps.indices.firstOrNull { it !in consumed && steps[it].owner == owner && steps[it].direction == "close" }
                ?: fail("No recorded close for connection $owner")
        prepareTails(close, owner)
        return drain()
    }

    private fun testEndBoundary(actual: Envelope): Int {
        val boundary = commandBoundary()
        failure?.let { fail(it) }
        val expected = steps.getOrNull(boundary) ?: fail("Recorded test-end boundary missing")
        val message = expected.message ?: fail("Recorded test-end boundary missing")
        if (actual.type != "test-control:test-end" ||
            expected.owner != "control" ||
            message.type != actual.type ||
            mapped(message.payload) != actual.payload
        ) {
            fail("Recorded test-end boundary mismatch")
        }
        return boundary
    }

    @Synchronized fun prepareTestEnd(actual: Envelope): List<SuiteStep> {
        prepareTails(testEndBoundary(actual))
        return drain()
    }

    @Synchronized fun testEndReady(actual: Envelope): Boolean = precedingWorkReleased(testEndBoundary(actual), allowDeferredReads = true)

    /** A foreground fence must not wait for later cleanup controls or registry disposal. */
    @Synchronized fun phoneClosesBeforeTeardown(): List<String> {
        val cleanup =
            steps.indices.firstOrNull {
                it !in consumed && steps[it].owner == "control" && steps[it].message?.type == "test-control:test-end"
            } ?: steps.size
        return steps
            .take(minOf(cleanup, checkpointBoundary()))
            .filter { it.direction == "close" && it.owner.startsWith("phone/") }
            .map { it.owner }
            .distinct()
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
        if (actual.type in
            setOf("thread_catch_up", "thread_page")
        ) {
            lastThreadQueryAttempt = describeThreadQueryAttempt(owner, actual, candidate)
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
        optionalTailReplies[candidate]?.forEach { released.remove(it) }
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

    private val phoneDeviceBindings = mutableMapOf<String, String>()

    /** Bind only a captured phone send's own canonical echo to this replay's real phone key. */
    @Synchronized fun bindPhoneDevice(
        owner: String,
        actualDeviceId: String,
    ) {
        check(owner == "phone" || owner.startsWith("phone/")) { "Expected a phone connection" }
        check(actualDeviceId.isNotBlank()) { "Missing replay phone identity" }

        fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        val sends =
            steps
                .filter {
                    family(it.owner) == family(owner) && it.direction == "client" && it.accepted && it.message?.type == "send_message"
                }.mapNotNull {
                    val payload = it.message!!.payload as? JsonObject ?: return@mapNotNull null
                    val conversation = payload.string("conversation_id") ?: return@mapNotNull null
                    val message = payload.string("message_id") ?: return@mapNotNull null
                    conversation to message
                }.toSet()
        val devices =
            steps
                .filter {
                    family(it.owner) == family(owner) &&
                        it.direction == "daemon" &&
                        it.message?.type in setOf("thread_items", "thread_item_added")
                }.flatMap { step ->
                    val payload = step.message!!.payload as? JsonObject ?: return@flatMap emptyList()
                    val conversation = payload.string("conversation_id") ?: return@flatMap emptyList()
                    val items = (payload["items"] as? JsonArray)?.toList() ?: listOfNotNull(payload["item"])
                    items.mapNotNull { value ->
                        val item = value as? JsonObject ?: return@mapNotNull null
                        if (item.string("kind") != "user_message") return@mapNotNull null
                        val content = item["content"] as? JsonObject ?: return@mapNotNull null
                        if (conversation to content.string("message_id") !in sends) return@mapNotNull null
                        content.string("device_id")?.takeIf { it.isNotBlank() }
                    }
                }.toSet()
        check(devices.size <= 1) { "Ambiguous recorded phone identity" }
        val captured = devices.singleOrNull() ?: return
        val previous = phoneDeviceBindings[captured]
        check(previous == null || previous == actualDeviceId) { "Replay phone identity changed" }
        phoneDeviceBindings[captured] = actualDeviceId
        identities[captured] = actualDeviceId
    }

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
        if (direction == "close") {
            cancelUnsentReadNotifications(candidate, owner)
            polledReads?.connectionEnded(owner)
        }
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

    /** Metadata remains fenced itself; only a separately requested thread reply may cross it. */
    private fun matchedIndependentMetadata(index: Int): Boolean {
        val step = steps[index]
        val response = step.message ?: return false
        val reply = response.inReplyTo
        if (reply == null) {
            // A clear is request-independent on the wire. Borrow only its immediately paired,
            // actually requested response, with the same explicit conversation and source.
            if (response.type !in setOf("context_usage", "model_list") ||
                response.sessionStateCleared != JsonPrimitive(true) ||
                response.payload != JsonObject(emptyMap())
            ) {
                return false
            }
            val following = steps.getOrNull(index + 1) ?: return false
            val paired = following.message ?: return false
            val conversation = (response.conversationId as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (conversation.isNullOrEmpty() ||
                following.direction != "daemon" ||
                following.owner != step.owner ||
                paired.type != response.type ||
                paired.sessionStateCleared == JsonPrimitive(true) ||
                paired.conversationId != response.conversationId
            ) {
                return false
            }
            return matchedRequestedMetadata(index + 1, paired, conversation)
        }
        return matchedRequestedMetadata(index, response)
    }

    private fun matchedRequestedMetadata(
        index: Int,
        response: Envelope,
        requiredConversation: String? = null,
    ): Boolean {
        val step = steps[index]
        val reply = response.inReplyTo ?: return false
        if (step.owner to reply !in requests) return false
        val request =
            steps
                .take(index)
                .firstOrNull {
                    it.owner == step.owner && it.direction == "client" && it.accepted && it.message?.id == reply
                }?.message ?: return false
        val expectedType =
            when (request.type) {
                "request_context_usage" -> "context_usage"
                "request_model_list" -> "model_list"
                "mcp_status_request" -> "mcp_status"
                else -> null
            }
        if (response.type == expectedType) {
            val conversation =
                ((request.payload as? JsonObject)?.get("conversation_id") as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
            val supplied =
                ((response.payload as? JsonObject)?.get("conversation_id") as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
            val envelope = (response.conversationId as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (conversation.isNullOrEmpty() ||
                supplied != conversation ||
                envelope != null &&
                envelope != conversation ||
                requiredConversation != null &&
                requiredConversation != conversation
            ) {
                return false
            }
            return true
        }
        if (requiredConversation != null) return false
        return when (request.type) {
            "register_push_token" -> response.type == "ack"
            "mcp_status_request", "request_context_usage" -> response.type == "error"
            "list_conversations" -> response.type == "conversations"
            else -> false
        }
    }

    /** Explicit attribution must agree across the request, envelope and catalog row. */
    private fun frameConversation(
        frame: Envelope,
        correlated: String? = null,
    ): String? {
        val payload = frame.payload as? JsonObject
        val identities =
            listOfNotNull(
                correlated?.let(::JsonPrimitive),
                payload?.get("conversation_id"),
                frame.conversationId,
                if (frame.type == "conversation_updated") payload?.get("id") else null,
            )
        val names =
            identities.map {
                (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.takeIf(String::isNotEmpty) ?: return null
            }
        return names.distinct().singleOrNull()
    }

    /** A delivered certificate permits live progress to trigger, but never spend, a later catch-up. */
    private fun liveProgressHasDeliveredBase(
        index: Int,
        query: Int,
    ): Boolean {
        val step = steps[index]
        val frame = step.message?.let(::mappedFrame) ?: return false
        if (frame.inReplyTo != null || step.direction != "daemon") return false
        val update = ThreadUpdateDto.decode(frame.type, frame.payload as? JsonObject ?: return false) ?: return false
        val request = catchUp(query) ?: return false
        if (request.version == 0uL || request.epoch.isEmpty() ||
            update.conversationId != request.conversationId || update.epoch != request.epoch ||
            update.version.toULong() <= request.version || frameConversation(frame) != update.conversationId
        ) {
            return false
        }
        return (0 until query).reversed().any { prior ->
            if (prior !in consumed || steps[prior].owner != step.owner ||
                lifecycleBefore[prior] != lifecycleBefore[index]
            ) {
                return@any false
            }
            val previous = catchUp(prior) ?: return@any false
            if (previous.conversationId != request.conversationId) return@any false
            val replies = replyIndices(prior)
            if (replies.isEmpty() || replies.any { it >= query || it !in deliveredThreadReplies }) return@any false
            val certificate = decodedQuery(prior, replies.map { checkNotNull(deliveredThreadReplies[it]) }) ?: return@any false
            certificate.terminal.checkpoint.epoch == request.epoch &&
                certificate.terminal.checkpoint.version == request.version &&
                (replies.last() + 1 until index).none { between ->
                    val event = steps[between]
                    if (event.owner != step.owner || event.direction != "daemon" ||
                        event.message?.type?.startsWith("thread_") != true
                    ) {
                        return@none false
                    }
                    val original = checkNotNull(event.message)
                    val correlated = original.inReplyTo?.let { reply ->
                        val request = steps.firstOrNull {
                            it.direction == "client" && it.owner == event.owner && it.message?.id == reply
                        } ?: return@none true
                        (request.message?.payload as? JsonObject)?.get("conversation_id") ?: return@none true
                    }
                    if (correlated != null && correlated != (original.payload as? JsonObject)?.get("conversation_id")) return@none true
                    val message = mappedFrame(original)
                    val attribution = frameConversation(message) ?: return@none true
                    if (attribution != request.conversationId) return@none false
                    val progress = ThreadUpdateDto.decode(message.type, message.payload as? JsonObject ?: return@none true)
                    between !in released || progress == null || progress.epoch != request.epoch
                }
        }
    }

    /** Independent replies and live facts retain their own conversation's captured query and source order. */
    private fun independentConversationDelivery(index: Int): Boolean {
        val step = steps[index]
        val frame = step.message ?: return false
        if (step.direction != "daemon") return false
        val reply = frame.inReplyTo
        if (reply != null && (step.owner to reply !in threadQueryRequests || step.owner to reply !in requests)) return false

        fun requestConversation(
            owner: String,
            id: Long,
        ): String? =
            steps
                .firstOrNull {
                    it.owner == owner && it.direction == "client" && it.message?.id == id
                }?.message
                ?.payload
                ?.let { (it as? JsonObject)?.get("conversation_id") as? JsonPrimitive }
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotEmpty() }
        val correlated = reply?.let { requestConversation(step.owner, it) ?: return false }
        val conversation = frameConversation(frame, correlated) ?: return false
        if (reply == null &&
            (0 until index).none { before ->
                val earlier = steps[before]
                before in consumed &&
                    earlier.owner == step.owner &&
                    earlier.direction == "client" &&
                    earlier.accepted &&
                    lifecycleBefore[before] == lifecycleBefore[index] &&
                    earlier.owner to earlier.message!!.id in threadQueryRequests &&
                    requestConversation(earlier.owner, earlier.message.id) == conversation
            }
        ) {
            return false
        }
        return (0 until index).all { before ->
            if (before in released) return@all true
            val earlier = steps[before]
            if (earlier.direction == "external") return@all false
            if (earlier.owner != step.owner) return@all true
            val message = earlier.message
            when (earlier.direction) {
                "client" ->
                    before in consumed ||
                        message?.let {
                            earlier.owner to it.id in threadQueryRequests &&
                                requestConversation(earlier.owner, it.id)?.let { other ->
                                    other != conversation || liveProgressHasDeliveredBase(index, before)
                                } == true
                        } == true
                "daemon" -> {
                    // Summary metadata does not establish a canonical thread base. Its own delivery stays fenced.
                    if (message?.type == "conversations" || matchedIndependentMetadata(before)) return@all true
                    val correlated = message?.inReplyTo?.let { requestConversation(earlier.owner, it) }
                    message?.let { frameConversation(it, correlated) }?.let { it != conversation } == true
                }
                else -> false
            }
        }
    }

    private fun drain(): List<SuiteStep> {
        val result = mutableListOf<SuiteStep>()
        val blockedOwners = mutableSetOf<String>()
        val blockedReplies = mutableSetOf<String>()
        for (index in 0 until commandBoundary()) {
            if (index in released) continue
            val step = steps[index]
            if (step.direction in setOf("client", "connect", "close")) {
                if (index in consumed || index in optionalModelReplies || index in optionalTailReplies) {
                    released += index
                } else if (step.direction == "client" && step.owner to step.message!!.id in threadQueryRequests) {
                    // Preserve the captured query start before any later live frame, even one preceding its reply.
                    blockedReplies += step.owner
                }
                continue
            }
            if (lifecycleBefore[index]?.let { it !in consumed } == true) continue
            if (optionalTailReplies.any { (query, replies) -> query !in consumed && index in replies }) {
                released += index
                continue
            }
            // This response is already optional because an identical automatic catalog was captured.
            // Honor that omission before it can fence another conversation's actual thread request.
            if (index in optionalModelReplies.values && optionalModelReplies.entries.single { it.value == index }.key !in consumed) {
                released += index
                continue
            }
            if (step.owner in blockedReplies && !independentConversationDelivery(index)) continue
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
            if (step.direction == "daemon" && frame!!.inReplyTo?.let { step.owner to it !in requests } == true) {
                // An authoritative query must establish its captured base before later live updates.
                // Ordinary background reads and other connections can still progress independently.
                if (step.owner to frame.inReplyTo in threadQueryRequests) blockedReplies += step.owner
                continue
            }
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
        val delivered =
            steps.indices.singleOrNull { index ->
                val step = steps[index]
                val recorded = step.message ?: return@singleOrNull false
                step.direction == "daemon" &&
                    step.owner == owner &&
                    recorded.id == frame.id &&
                    recorded.type == frame.type &&
                    recorded.inReplyTo?.let { expected ->
                        owner to expected in threadQueryRequests && requests[owner to expected] == frame.inReplyTo
                    } == true
            }
        if (delivered != null) deliveredThreadReplies[delivered] = frame
        readReceipts?.delivered(owner, frame)
        polledReads?.observeCleared(owner, frame)
    }

    @Synchronized fun connectionEnded(owner: String) {
        polledReads?.connectionEnded(owner)
    }

    private var lastThreadQueryAttempt = "none"

    private fun queryNumber(
        payload: JsonElement,
        field: String,
    ): String =
        ((payload as? JsonObject)?.get(field) as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.content
            ?.toULongOrNull()
            ?.toString() ?: "none"

    private fun describeThreadQueryAttempt(
        owner: String,
        actual: Envelope,
        candidate: Int?,
    ): String {
        val attempts =
            eligible().filter { steps[it].message?.type in setOf("thread_catch_up", "thread_page") }.take(8).joinToString(";") { index ->
                val expected = steps[index]
                val fields = mapped(expected.message!!.payload) as? JsonObject
                val supplied = actual.payload as? JsonObject
                "index=$index,ownerMatches=${expected.owner == owner},typeMatches=${expected.message.type == actual.type}," +
                    "conversationMatches=${fields?.get("conversation_id") == supplied?.get("conversation_id")}," +
                    "epochMatches=${fields?.get("epoch") == supplied?.get("epoch")}," +
                    "checkpointMatches=${fields?.get("version") == supplied?.get("version")}"
            }
        return "type=${actual.type},request=${actual.id},checkpoint=${queryNumber(actual.payload, "version")}," +
            "beforeOrder=${queryNumber(actual.payload, "before_order")},candidate=$candidate,candidates=[$attempts]"
    }

    @Synchronized fun progress(): String {
        val index = commandBoundary()
        val next = steps.getOrNull(index)
        val type = next?.message?.type?.takeIf { it.matches(Regex("[A-Za-z0-9_:-]{1,100}")) } ?: "none"
        val pending =
            steps.indices.filter { it !in released }.take(8).joinToString {
                "${steps[it].owner}:${steps[it].message?.type ?: steps[it].direction}"
            }
        val queries =
            steps.indices
                .filter { i ->
                    i !in released && steps[i].direction == "client" && steps[i].message?.type in setOf("thread_catch_up", "thread_page")
                }.take(8)
                .joinToString(";") { i ->
                    val step = steps[i]
                    val message = checkNotNull(step.message)
                    "index=$i,type=${message.type},request=${message.id},checkpoint=${queryNumber(message.payload, "version")}," +
                        "consumed=${i in consumed},mapped=${step.owner to message.id in requests}"
                }
        // The first failure is sticky, and the app absorbs a refused send, so a later stall can hide it.
        return "Recorded suite progress: matched=$matched remaining=${steps.size - released.size} " +
            "next=${next?.owner}:$type index=$index pending=$pending reads=${readReceipts?.progress()} " +
            "query=$lastThreadQueryAttempt query-pending=[$queries] first-failure=$failure"
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
