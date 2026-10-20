package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads of captured settings states and explicitly unsupported legacy backfill probes. */
internal class SuitePolledReads(
    source: List<SuiteStep>,
    private val resolve: (JsonElement) -> JsonElement,
    private val nextId: (String) -> Long,
    settings: Boolean,
    refusedBackfill: Boolean,
    private val cancelClearedSettings: Boolean = false,
    private val queryTypes: Set<String> = emptySet(),
) {
    private data class Key(
        val owner: String,
        val type: String,
        val payload: JsonElement,
    )

    private val snapshots = mutableMapOf<Pair<String, Long>, Key>()
    private val sessions = mutableMapOf<Key, Set<JsonElement>>()
    private val snapshotVersions = mutableMapOf<Pair<String, Long>, Int>()
    private val versions = mutableMapOf<Key, Int>()
    private val current = mutableMapOf<Key, Envelope>()
    private val pending = mutableMapOf<Key, MutableList<Envelope>>()
    private val known = mutableSetOf<Key>()
    private val clearedStates = mutableMapOf<Pair<String, Long>, Key>()
    private val clearedPending = mutableSetOf<Key>()
    private var reads = 0
    val steps: List<SuiteStep>

    init {
        val replies =
            source
                .filter { it.direction == "daemon" && it.message?.inReplyTo != null }
                .groupBy { it.owner to it.message!!.inReplyTo!! }
        val removed = mutableSetOf<Pair<String, Long>>()
        val settingsRequests = mutableMapOf<Pair<String, Long>, Key>()
        source
            .filter {
                val type = it.message?.type
                val enabled = settings && type == "request_session_settings" || refusedBackfill && type == "backfill_since"
                it.direction == "client" && it.owner.startsWith("phone/") && enabled
            }.groupBy { Key(it.owner, it.message!!.type, it.message.payload) }
            .forEach { (key, requests) ->
                val settingsShape = (key.payload as? JsonObject)?.keys == setOf("conversation_id")
                val responses = requests.map { replies[key.owner to it.message!!.id].orEmpty() }
                val cancelled =
                    requests.map { request ->
                        key.type == "request_session_settings" &&
                            replies[key.owner to request.message!!.id].isNullOrEmpty() &&
                            source
                                .drop(source.indexOf(request) + 1)
                                .firstOrNull {
                                    it.owner == key.owner && it.direction in setOf("client", "close", "down", "inbound_end")
                                }?.direction in setOf("close", "down", "inbound_end")
                    }
                val eligible =
                    responses.any { it.isNotEmpty() } &&
                        requests.all { it.accepted } &&
                        responses.withIndex().all { (index, rows) ->
                            if (rows.isEmpty() && cancelled[index]) {
                                true
                            } else if (rows.size != 1) {
                                false
                            } else {
                                val frame = rows.single().message!!
                                frame.historyEntryId == null &&
                                    frame.eventId == null &&
                                    when (key.type) {
                                        "request_session_settings" ->
                                            settingsShape && frame.type == "session_settings"
                                        else ->
                                            frame.type == "error" &&
                                                (frame.payload as? JsonObject)?.get("code") == JsonPrimitive("protocol.unknown_type")
                                    }
                            }
                        }
                if (eligible) {
                    known += key
                    requests.forEach { removed += key.owner to it.message!!.id }
                    if (key.type == "backfill_since") {
                        current[key] = responses.first().single().message!!
                    } else {
                        responses.filter { it.isNotEmpty() }.forEach { snapshots[key.owner to it.single().message!!.id] = key }
                        sessions[key] =
                            responses.mapNotNull { (it.singleOrNull()?.message?.payload as? JsonObject)?.get("session_id") }.toSet()
                        requests.forEach { settingsRequests[key.owner to it.message!!.id] = key }
                    }
                }
            }
        if (settings && cancelClearedSettings) registerClearedEnds(source, replies, removed)
        val sourceVersions = mutableMapOf<Key, Int>()
        val requestVersions = mutableMapOf<Pair<String, Long>, Int>()
        source.forEach { step ->
            val frame = step.message ?: return@forEach
            if (step.direction == "client") {
                writeKeys(step.owner, frame, rebind = false).forEach { key ->
                    sourceVersions[key] = (sourceVersions[key] ?: 0) + 1
                }
                settingsRequests[step.owner to frame.id]?.let { key ->
                    requestVersions[step.owner to frame.id] = sourceVersions[key] ?: 0
                }
            } else if (step.direction == "daemon" && step.owner to frame.id in snapshots) {
                snapshotVersions[step.owner to frame.id] = checkNotNull(requestVersions[step.owner to frame.inReplyTo])
            }
        }
        steps =
            source.mapNotNull { step ->
                val frame = step.message
                when {
                    frame == null -> step
                    step.direction == "client" && step.owner to frame.id in removed -> null
                    step.direction == "daemon" && step.owner to frame.inReplyTo in removed -> {
                        if (step.owner to frame.id in snapshots) step.copy(direction = "settings_snapshot") else null
                    }
                    else -> step
                }
            }
    }

    private fun key(
        owner: String,
        frame: Envelope,
    ) = known.singleOrNull {
        it.owner == owner && it.type == frame.type && resolve(it.payload) == frame.payload
    }

    fun accepts(
        owner: String,
        frame: Envelope,
    ) = frame.inReplyTo == null && key(owner, frame) != null

    fun send(
        owner: String,
        frame: Envelope,
    ): List<SuiteStep> {
        check(++reads <= 50000) { "Recorded polling bound exceeded" }
        check(frame.inReplyTo == null)
        val key = checkNotNull(key(owner, frame)) { "Recorded polling request changed" }
        val snapshot = current[key]
        return if (snapshot == null) {
            pending.getOrPut(key) { mutableListOf() } += frame
            emptyList()
        } else {
            listOf(reply(key, snapshot, frame))
        }
    }

    private fun reply(
        key: Key,
        snapshot: Envelope,
        request: Envelope,
    ) = SuiteStep(
        "daemon",
        key.owner,
        snapshot.copy(id = nextId(key.owner), inReplyTo = request.id, payload = resolve(snapshot.payload)),
        detail = JsonObject(mapOf("replayResponse" to JsonPrimitive(true))),
    )

    fun delivered(step: SuiteStep): List<SuiteStep> {
        val snapshot = checkNotNull(step.message)
        val key = checkNotNull(snapshots[step.owner to snapshot.id])
        val version = checkNotNull(snapshotVersions[step.owner to snapshot.id])
        val active = versions[key] ?: 0
        check(version <= active) { "Recorded settings snapshot preceded its write" }
        if (version < active) return emptyList()
        current[key] = snapshot
        return pending.remove(key).orEmpty().map { reply(key, snapshot, it) }
    }

    private fun writeKeys(
        owner: String,
        frame: Envelope,
        rebind: Boolean,
    ): List<Key> {
        if (frame.type != "set_session_settings") return emptyList()
        val session = (frame.payload as? JsonObject)?.get("session_id") ?: return emptyList()
        return sessions
            .filter { (key, values) ->
                key.owner == owner && values.any { (if (rebind) resolve(it) else it) == session }
            }.keys
            .toList()
    }

    fun command(
        owner: String,
        frame: Envelope,
    ) {
        writeKeys(owner, frame, rebind = true).forEach { key ->
            versions[key] = (versions[key] ?: 0) + 1
            current.remove(key)
        }
    }

    private fun registerClearedEnds(
        source: List<SuiteStep>,
        replies: Map<Pair<String, Long>, List<SuiteStep>>,
        removed: MutableSet<Pair<String, Long>>,
    ) {
        source.forEachIndexed { index, step ->
            val frame = step.message ?: return@forEachIndexed
            val conversation = frame.conversationId as? JsonPrimitive ?: return@forEachIndexed
            if (!step.owner.startsWith("phone/") ||
                step.direction != "daemon" ||
                frame.type != "session_settings" ||
                frame.sessionStateCleared != JsonPrimitive(true) ||
                !conversation.isString ||
                conversation.content.isBlank() ||
                frame.inReplyTo != null ||
                (frame.payload as? JsonObject)?.isEmpty() != true
            ) {
                return@forEachIndexed
            }
            val end =
                (index + 1 until source.size).firstOrNull {
                    source[it].owner == step.owner && source[it].direction in setOf("close", "down", "inbound_end", "transport_down")
                } ?: return@forEachIndexed
            val later = source.subList(index + 1, end).filter { it.owner == step.owner }
            if (later.any { it.direction == "client" && it.message!!.type !in queryTypes } ||
                later.any {
                    it.direction == "daemon" &&
                        it.message?.type == "session_settings" &&
                        it.message.sessionStateCleared != JsonPrimitive(true)
                }
            ) {
                return@forEachIndexed
            }
            val key = Key(step.owner, "request_session_settings", JsonObject(mapOf("conversation_id" to conversation)))
            val requests = later.filter { it.direction == "client" && it.message?.type == key.type && it.message.payload == key.payload }
            if (requests.any { !it.accepted || replies[it.owner to it.message!!.id].orEmpty().isNotEmpty() }) return@forEachIndexed
            clearedStates[step.owner to frame.id] = key
            requests.forEach { removed += it.owner to it.message!!.id }
        }
    }

    /** A clear permits waiting, never a fabricated empty settings answer. */
    fun observeCleared(
        owner: String,
        frame: Envelope,
    ) {
        val key = clearedStates[owner to frame.id] ?: return
        if (frame.type != "session_settings" ||
            frame.sessionStateCleared != JsonPrimitive(true) ||
            frame.conversationId != (resolve(key.payload) as JsonObject)["conversation_id"]
        ) {
            return
        }
        known += key
        current.remove(key)
        clearedPending += key
    }

    fun connectionEnded(owner: String) {
        val ending = clearedPending.filter { it.owner == owner }
        ending.forEach { key ->
            pending.remove(key)
            current.remove(key)
            known.remove(key)
        }
        clearedPending.removeAll(ending.toSet())
    }

    fun assertComplete() {
        check(pending.isEmpty()) { "Recorded settings reads have no delivered snapshot" }
    }
}
