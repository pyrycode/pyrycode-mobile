package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/** #2114: content-free snapshots, deliberately independent of the wait's result. Null means unknown. */
internal data class LaterReplyPeer(
    val baselineFrames: Int,
    val frames: Int,
    val deliveredMessages: List<String>,
    val exactTurns: List<String>,
    val completedDifferentTurns: List<String>,
    val turns: List<String>,
    val queueContainsPrompt: Boolean?,
    val link: String = "unknown",
    val messageTurnJoin: String = "unknown",
    val baselineTurns: List<String> = emptyList(),
    val deliveredHostTimes: List<String> = emptyList(),
)

internal data class LaterReplyRepository(
    val baselineRows: Int?,
    val rows: Int?,
    val exactRows: List<String>?,
    val completedRows: List<String>?,
    val turns: List<String>?,
    val submittedMessages: List<String>?,
    val queuedMessages: List<String>? = null,
    val phase: String = "unknown",
    val baselineExactRows: List<String>? = null,
)

internal data class LaterReplyComposition(
    val exactNodes: Int?,
    val listNodes: Int?,
    val displayedNodes: Int?,
    val rowIndices: Map<String, Int>?,
)

internal data class LaterReplyObservation(
    val stage: String,
    val phoneEpochMs: Long,
    val phoneStartNs: Long,
    val phoneEndNs: Long,
    val peer: LaterReplyPeer?,
    val repository: LaterReplyRepository?,
    val composition: LaterReplyComposition?,
)

internal class LaterReplyProbe(
    private val prompt: String,
    private val answer: String,
    baselineFrames: List<Envelope>?,
    baselineRows: List<ThreadItem>?,
) {
    private val baselineCount = baselineFrames?.size
    private val oldTurns = baselineFrames?.mapNotNull { it.field("turn_id") }?.toSet()
    private val oldRows = baselineRows?.filterIsInstance<ThreadItem.MessageItem>()?.map { it.message.id }?.toSet()
    private val rowCount = baselineRows?.size
    private val baselineExactRows =
        baselineRows
            ?.filterIsInstance<ThreadItem.MessageItem>()
            ?.map { it.message }
            ?.filter { it.role == Role.Assistant && it.parentToolUseId.isEmpty() && it.content == answer }
            ?.map { laterReplyHash(it.id) }

    fun peer(frames: List<Envelope>): LaterReplyPeer {
        check(baselineCount != null && oldTurns != null) // Unknown baseline must not admit stale evidence.
        check(frames.size <= 8192)
        val later = frames.drop(baselineCount)
        check(
            later.filter { it.type == "assistant_delta" }.all {
                it.field("parent_tool_use_id") != null && !it.field("turn_id").isNullOrEmpty() && it.field("text") != null
            },
        )
        val deltas =
            later
                .filter { it.type == "assistant_delta" && it.field("parent_tool_use_id") == "" }
                .filter { !it.field("turn_id").isNullOrEmpty() && it.field("turn_id") !in oldTurns }
                .groupBy { requireNotNull(it.field("turn_id")) }
        val exact = mutableListOf<String>()
        val different = mutableListOf<String>()
        val facts =
            deltas.map { (turn, entries) ->
                val unique = entries.distinctBy { it.field("seq") }
                check(unique.all { it.field("seq")?.toIntOrNull() != null })
                val sorted = unique.sortedBy { it.field("seq")?.toIntOrNull() }
                // Missing/conflicting sequences cannot establish an exact answer.
                val contiguous = sorted.map { it.field("seq")?.toIntOrNull() } == (0 until sorted.size).toList()
                val consistent =
                    entries.groupBy { it.field("seq") }.values.all { group ->
                        group.map { it.field("text") }.distinct().size == 1
                    }
                check(sorted.sumOf { it.field("text").orEmpty().length } <= 65536)
                val text = sorted.joinToString("") { it.field("text").orEmpty() }
                val matches = contiguous && consistent && text == answer
                val ends =
                    later.filter {
                        it.type == "turn_end" && it.field("turn_id") == turn && it.field("parent_tool_use_id").isNullOrEmpty()
                    }
                val hash = laterReplyHash(turn)
                if (matches) {
                    exact += hash
                } else if (ends.isNotEmpty() && contiguous && consistent) {
                    different += hash
                }
                "turn=$hash exact=$matches completed=${ends.isNotEmpty()} length=${text.length} " +
                    "contiguous=$contiguous consistent=$consistent " +
                    "host_delta_ts=${sorted.map { safeTimestamp(it.ts) }} host_end_ts=${ends.map { safeTimestamp(it.ts) }}"
            }
        val delivered =
            later
                .filter { it.type == "message" && it.field("role") == "user" && it.field("text") == prompt }
                .mapNotNull { it.field("message_id")?.let(::laterReplyHash) }
        val queue = frames.lastOrNull { it.type == "queue_state" }?.payload as? JsonObject
        val queued = queue?.get("queued") as? JsonArray
        return LaterReplyPeer(
            baselineCount,
            frames.size,
            delivered,
            exact,
            different,
            facts,
            queued?.any { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull == prompt },
            baselineTurns = oldTurns.map(::laterReplyHash),
            deliveredHostTimes =
                later
                    .filter { it.type == "message" && it.field("role") == "user" && it.field("text") == prompt }
                    .map { safeTimestamp(it.ts) },
        )
    }

    fun repository(rows: List<ThreadItem>?): LaterReplyRepository {
        if (rows == null) return LaterReplyRepository(rowCount, null, null, null, null, null, baselineExactRows = baselineExactRows)
        val main =
            rows
                .filterIsInstance<ThreadItem.MessageItem>()
                .map { it.message }
                .filter { it.id !in oldRows.orEmpty() && it.parentToolUseId.isEmpty() }
        val exact = if (oldRows == null) emptyList() else main.filter { it.role == Role.Assistant && it.content == answer }
        return LaterReplyRepository(
            rowCount,
            rows.size,
            if (oldRows == null) null else exact.map { laterReplyHash(it.id) },
            if (oldRows == null) null else exact.filter { !it.isStreaming }.map { laterReplyHash(it.id) },
            if (oldRows == null) null else exact.mapNotNull { it.segment?.turnId?.let(::laterReplyHash) },
            if (oldRows == null) null else main.filter { it.role == Role.User && it.content == prompt }.map { laterReplyHash(it.id) },
            baselineExactRows = baselineExactRows,
        )
    }
}

internal fun laterReplyHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

private fun safeTimestamp(value: String): String = runCatching { Instant.parse(value).toString() }.getOrDefault("unknown")

private fun Envelope.field(name: String): String? = ((payload as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull

/** Capture every layer separately: failure of one read never erases another layer's evidence. */
internal fun recordLaterReply(
    stage: String,
    peer: () -> LaterReplyPeer?,
    repository: () -> LaterReplyRepository?,
    composition: () -> LaterReplyComposition?,
    retain: (LaterReplyObservation) -> Unit,
) {
    require(stage in setOf("baseline", "after_send", "success", "timeout"))
    val epoch = System.currentTimeMillis()
    val start = System.nanoTime()
    val p = runCatching(peer).getOrNull()
    val r = runCatching(repository).getOrNull()
    val c = runCatching(composition).getOrNull()
    retain(LaterReplyObservation(stage, epoch, start, System.nanoTime(), p, r, c))
}

/** Does not retry, scroll, reinterpret the wait, or replace its throwable. */
internal fun observeLaterReply(
    send: () -> Unit,
    wait: () -> Unit,
    capture: (String) -> Unit,
) {
    runCatching { capture("baseline") }
    try {
        send()
        runCatching { capture("after_send") }
        wait()
    } catch (original: Throwable) {
        runCatching { capture("timeout") }
        throw original
    }
    runCatching { capture("success") }
}

/** Preserve the original failure while attempting every scenario-owned cleanup operation. */
internal fun cleanupLaterReply(
    original: Throwable?,
    operations: List<() -> Unit>,
    failed: () -> Unit = {},
) {
    var firstFailure: Throwable? = null
    operations.forEach { operation ->
        try {
            operation()
        } catch (failure: Throwable) {
            if (firstFailure == null) firstFailure = failure
            runCatching(failed)
        }
    }
    if (original == null) firstFailure?.let { throw it }
}
