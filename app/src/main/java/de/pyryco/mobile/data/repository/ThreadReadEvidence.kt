package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.ApiRetryPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskStartedPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskUpdatedPayloadDto
import de.pyryco.mobile.data.network.BannerPayloadDto
import de.pyryco.mobile.data.network.CompactingPayloadDto
import de.pyryco.mobile.data.network.ContextUsagePayloadDto
import de.pyryco.mobile.data.network.McpStatusPayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ModelAnnouncedPayloadDto
import de.pyryco.mobile.data.network.ModelListPayloadDto
import de.pyryco.mobile.data.network.ResettingPayloadDto
import de.pyryco.mobile.data.network.SessionFactsPayloadDto
import de.pyryco.mobile.data.network.SlashCommandListPayloadDto
import de.pyryco.mobile.data.network.StallPayloadDto
import de.pyryco.mobile.data.network.ThinkingProgressPayloadDto
import de.pyryco.mobile.data.network.TurnEndPayloadDto
import de.pyryco.mobile.data.network.TurnStatePayloadDto
import de.pyryco.mobile.data.network.toEvent
import de.pyryco.mobile.data.network.toFacts
import de.pyryco.mobile.data.network.toMenu
import de.pyryco.mobile.data.network.toReading
import de.pyryco.mobile.data.network.toReport
import de.pyryco.mobile.data.network.toStatus
import kotlinx.serialization.json.decodeFromJsonElement

/** Receipt claims bound to immutable content versions, never persisted as sight. */
@ConsistentCopyVisibility
data class ThreadReadEvidence internal constructor(
    internal val versions: Map<ThreadItem, Set<ULong>> = emptyMap(),
    internal val claims: Map<Any, Set<ULong>> = emptyMap(),
    // true deliberately nonvisual; false visible; null malformed/unsupported/unrepresented.
    internal val facts: Map<ULong, Boolean?> = emptyMap(),
    internal val unidentified: Set<Triple<String, String, kotlinx.serialization.json.JsonElement>> = emptySet(),
    internal val gaps: List<UnsignedHistoryGap> = emptyList(),
) {
    override fun toString(): String = "ThreadReadEvidence(<redacted>)"

    /** Presentation may settle streaming chrome without changing any represented content. */
    internal fun presentedAs(rows: List<ThreadItem>): ThreadReadEvidence {
        val presented =
            rows
                .mapNotNull { row ->
                    val ids =
                        versions[row] ?: if (row is ThreadItem.MessageItem && !row.message.isStreaming) {
                            versions.entries
                                .firstOrNull { (source, _) ->
                                    source is ThreadItem.MessageItem && source.message.copy(isStreaming = false) == row.message
                                }?.value
                        } else {
                            null
                        }
                    ids?.let { row to it }
                }.toMap()
        return copy(versions = presented)
    }

    internal fun checkpoint(
        presented: ThreadItem,
        confirmed: ULong,
    ): ULong? {
        if (unidentified.isNotEmpty()) return null
        var candidate = versions[presented]?.maxOrNull() ?: return null
        val received = facts.keys.filter { it > confirmed }.sorted()
        if (received.any { it <= candidate && facts[it] == null }) return null
        // A known received hole cannot be crossed by a newer row or a list latest-id hint.
        if (received.zipWithNext().any { (a, b) -> b <= candidate && b - a > 1u }) return null
        while (candidate < ULong.MAX_VALUE && facts[candidate + 1u] == true) candidate++
        if (gaps.any { confirmed < it.edge && candidate >= it.edge }) return null
        return candidate.takeIf { it > confirmed }
    }

    internal fun received(
        entries: List<HistoryEntry>,
        reduced: ReducedHistoryPage,
        actual: List<ThreadItem>,
    ): ThreadReadEvidence {
        val joined = claims.toMutableMap()
        (reduced.unsignedClaims.keys + reduced.readClaims.keys)
            .associateWith {
                reduced.unsignedClaims[it].orEmpty() +
                    reduced.readClaims[it].orEmpty()
            }.forEach { (key, ids) -> joined[key] = joined[key].orEmpty() + ids }
        val bound = versions.filterKeys { it in actual }.toMutableMap()
        for (row in actual) {
            val source =
                reduced.rows.firstOrNull {
                    it.mergeIdentity() == row.mergeIdentity() ||
                        (
                            row is ThreadItem.UnrecognizedMessage &&
                                it is ThreadItem.UnrecognizedMessage &&
                                row.copy(id = it.id, occurredAt = it.occurredAt) == it
                        )
                }
                    ?: continue
            if (!row.represents(source)) continue
            val segment = (row as? ThreadItem.MessageItem)?.message?.segment
            val keys =
                segment?.deltas.orEmpty().map { listOf("delta", segment?.turnId, it.seq) } +
                    listOf(row.mergeIdentity(), source.mergeIdentity())
            val ids = keys.flatMapTo(HashSet()) { joined[it].orEmpty() }
            if (ids.isNotEmpty()) bound[row] = ids
        }
        val receivedFacts = facts.toMutableMap()
        reduced.readFacts.forEach { (id, fact) ->
            // Re-delivery without a new row is not a new barrier or new presentation.
            if (id !in receivedFacts || receivedFacts[id] == null) receivedFacts[id] = fact
        }
        return copy(
            versions = bound,
            claims = joined,
            facts = receivedFacts,
            unidentified = unidentified - entries.map { Triple(it.type, it.timestamp.toString(), it.payload) }.toSet(),
        )
    }
}

/** A page fragment must be fully represented; a held newer delta is not granted a page's claim. */
private fun ThreadItem.represents(source: ThreadItem): Boolean {
    if (this is ThreadItem.MessageItem && source is ThreadItem.MessageItem) {
        val expected = source.message
        val held = message
        val segment = expected.segment
        if (segment != null) {
            return held.segment?.turnId == segment.turnId &&
                segment.deltas.all { it in held.segment.deltas } &&
                historyRowProofs(listOf(source)).all { (key, proof) -> historyRowProofs(listOf(this))[key] == proof } &&
                (expected.isStreaming || !held.isStreaming)
        }
        return held.copy(timestamp = expected.timestamp, sessionId = expected.sessionId) == expected
    }
    if (this is ThreadItem.UnrecognizedMessage &&
        source is ThreadItem.UnrecognizedMessage
    ) {
        return copy(id = source.id, occurredAt = source.occurredAt) == source
    }
    if (this is ThreadItem.StoppedTurn && source is ThreadItem.StoppedTurn) {
        return copy(occurredAt = source.occurredAt) == source
    }
    return this == source
}

/** Only explicitly understood transient entries may extend a visible checkpoint. */
internal fun understoodNonvisualEntry(
    entry: HistoryEntry,
    interactive: Boolean,
): Boolean? {
    if (!interactive) return null
    return try {
        when (entry.type) {
            "background_task_started" ->
                MobileJson
                    .decodeFromJsonElement<BackgroundTaskStartedPayloadDto>(entry.payload)
                    .let {
                        it.taskId.isNotEmpty()
                    }.takeIf { it }
            "background_task_updated" ->
                MobileJson
                    .decodeFromJsonElement<BackgroundTaskUpdatedPayloadDto>(entry.payload)
                    .let {
                        it.taskId.isNotEmpty()
                    }.takeIf { it }
            "model_list" -> MobileJson.decodeFromJsonElement<ModelListPayloadDto>(entry.payload).toMenu().let { true }
            "slash_command_list" -> MobileJson.decodeFromJsonElement<SlashCommandListPayloadDto>(entry.payload).toMenu().let { true }
            "mcp_status" -> MobileJson.decodeFromJsonElement<McpStatusPayloadDto>(entry.payload).toReport()?.let { true }
            "context_usage" -> MobileJson.decodeFromJsonElement<ContextUsagePayloadDto>(entry.payload).toReading()?.let { true }
            "model_announced" -> MobileJson.decodeFromJsonElement<ModelAnnouncedPayloadDto>(entry.payload).toReading()?.let { true }
            "session_facts" -> MobileJson.decodeFromJsonElement<SessionFactsPayloadDto>(entry.payload).toFacts().let { true }
            "turn_state" -> MobileJson.decodeFromJsonElement<TurnStatePayloadDto>(entry.payload).toEvent()?.let { true }
            "turn_end" -> MobileJson.decodeFromJsonElement<TurnEndPayloadDto>(entry.payload).toEvent().let { true }
            "api_retry" -> MobileJson.decodeFromJsonElement<ApiRetryPayloadDto>(entry.payload).let { true }
            "stall" -> MobileJson.decodeFromJsonElement<StallPayloadDto>(entry.payload).let { true }
            "thinking_progress" -> MobileJson.decodeFromJsonElement<ThinkingProgressPayloadDto>(entry.payload).let { true }
            "resetting" ->
                MobileJson
                    .decodeFromJsonElement<ResettingPayloadDto>(entry.payload)
                    .let {
                        !it.active || it.toStatus() != null
                    }.takeIf { it }
            "compacting" -> MobileJson.decodeFromJsonElement<CompactingPayloadDto>(entry.payload).let { true }
            "banner" -> MobileJson.decodeFromJsonElement<BannerPayloadDto>(entry.payload).let { it.level == "info" }.takeIf { it }
            else -> null
        }
    } catch (error: IllegalArgumentException) {
        null
    }
}

internal fun ThreadItem.isReadContent(): Boolean =
    this !is ThreadItem.BackgroundTaskLifecycle && (this !is ThreadItem.Banner || level != BannerLevel.Info)

/** History parsing canonicalizes valid instants; malformed timestamps must remain unresolved. */
internal fun normalizeReadTimestamp(timestamp: String): String =
    try {
        kotlinx.datetime.Instant
            .parse(timestamp)
            .toString()
    } catch (error: IllegalArgumentException) {
        timestamp
    }
