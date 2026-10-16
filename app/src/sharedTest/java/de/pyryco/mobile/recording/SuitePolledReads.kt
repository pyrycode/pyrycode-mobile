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
) {
    private data class Key(
        val owner: String,
        val type: String,
        val payload: JsonElement,
    )

    private val snapshots = mutableMapOf<Pair<String, Long>, Key>()
    private val current = mutableMapOf<Key, Envelope>()
    private val pending = mutableMapOf<Key, MutableList<Envelope>>()
    private val known = mutableSetOf<Key>()
    private var reads = 0
    val steps: List<SuiteStep>

    init {
        val replies =
            source
                .filter { it.direction == "daemon" && it.message?.inReplyTo != null }
                .groupBy { it.owner to it.message!!.inReplyTo!! }
        val removed = mutableSetOf<Pair<String, Long>>()
        source
            .filter {
                val type = it.message?.type
                val enabled = settings && type == "request_session_settings" || refusedBackfill && type == "backfill_since"
                it.direction == "client" && it.owner.startsWith("phone/") && enabled
            }.groupBy { Key(it.owner, it.message!!.type, it.message.payload) }
            .forEach { (key, requests) ->
                val settingsShape = (key.payload as? JsonObject)?.keys == setOf("conversation_id")
                val responses = requests.map { replies[key.owner to it.message!!.id].orEmpty() }
                val eligible =
                    requests.all { it.accepted } &&
                        responses.all { rows ->
                            if (rows.size != 1) {
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
                        responses.forEach { snapshots[key.owner to it.single().message!!.id] = key }
                    }
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
        current[key] = snapshot
        return pending.remove(key).orEmpty().map { reply(key, snapshot, it) }
    }

    fun assertComplete() {
        check(pending.isEmpty()) { "Recorded settings reads have no delivered snapshot" }
    }
}
