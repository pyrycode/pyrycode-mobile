package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.cacheableThreadRows
import de.pyryco.mobile.data.cache.cachedThreadRowProof
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.security.MessageDigest

@Serializable
data class HistorySpan(
    val first: Long,
    val last: Long,
)

@Serializable
data class HistoryGap(
    val anchor: Long,
    val edge: Long,
)

/** Received durable ids only. Row identities locate content; they never establish a span. */
@Serializable
data class HistoryCoverage(
    val spans: List<HistorySpan> = emptyList(),
    val gaps: List<HistoryGap> = emptyList(),
    val cursors: Map<Long, String> = emptyMap(),
    val walks: Map<Long, String> = emptyMap(),
    val unknown: Boolean = false,
    val newestCursor: String? = null,
    val rowOrder: Map<String, Long> = emptyMap(),
    val rowEntries: Map<String, Set<Long>> = emptyMap(),
    val proofs: Map<String, String> = emptyMap(),
    val legacyKeys: Map<String, String> = emptyMap(),
    val legacyOffsets: Map<String, Int> = emptyMap(),
    val deltaLengths: Map<String, Int> = emptyMap(),
    val deltaHashes: Map<String, String> = emptyMap(),
    @Transient val deltaText: Map<String, String> = emptyMap(),
) {
    val highWater: Long get() = spans.maxOfOrNull { it.last } ?: 0
    val unknownEdge: Long? get() = if (unknown) spans.minOfOrNull { it.first } else null

    fun cursorFor(anchor: Long): String {
        walks[anchor]?.let { return it }
        val edge = if (anchor == 0L) unknownEdge else gaps.firstOrNull { it.anchor == anchor }?.edge
        return edge?.let { boundary ->
            cursors.keys
                .filter { it >= boundary }
                .minOrNull()
                ?.let(cursors::get)
        }
            ?: ""
    }

    fun refused(anchor: Long): HistoryCoverage {
        val refused = cursorFor(anchor)
        val usable = newestCursor?.takeUnless { it == refused }
        return copy(cursors = cursors.filterValues { it != refused }, walks = walks + (anchor to usable.orEmpty()), newestCursor = usable)
    }

    fun received(
        page: HistoryPage,
        newest: Boolean = false,
        target: Long? = null,
    ): HistoryCoverage {
        val received =
            page.entries
                .map { it.id }
                .filter { it > 0 }
                .distinct()
                .sorted()
        val merged = normalize(spans + received.map { HistorySpan(it, it) })
        val holes = merged.zipWithNext { older, newer -> HistoryGap(older.last, newer.first) }
        val nextGaps =
            holes.map { hole ->
                val olderSpan = merged.first { it.last == hole.anchor }
                val previous = gaps.firstOrNull { it.anchor in olderSpan.first..olderSpan.last && it.edge >= hole.edge }
                hole.copy(anchor = previous?.anchor ?: hole.anchor)
            }
        val reduced = reduceOrderedHistoryPage(page.entries, interactive = true)
        val retainedKeys =
            reduced.rows
                .filterNot { it is ThreadItem.UnrecognizedMessage || it is ThreadItem.BackgroundTaskLifecycle }
                .flatMap { it.historyKeys() }
                .toSet()
        val order =
            reduced.order.entries
                .associate { historyIdentity(it.key) to it.value }
                .filterKeys { it in retainedKeys }
        val claims = reduced.claims.mapKeys { historyIdentity(it.key) }.filterKeys { it in retainedKeys }
        val texts = HashMap<String, String>()
        val aliases = HashMap<String, String>()
        reduced.rows.filterIsInstance<ThreadItem.MessageItem>().forEach { row ->
            val segment = row.message.segment ?: return@forEach
            var offset = 0
            segment.deltas.forEach { delta ->
                val end =
                    (offset.toLong() + delta.length)
                        .coerceIn(
                            offset.toLong(),
                            row.message.content.length
                                .toLong(),
                        ).toInt()
                val key = historyIdentity(listOf("delta", segment.turnId, delta.seq))
                texts[key] = row.message.content.substring(offset, end)
                aliases[key] = historyIdentity(listOf("message", segment.turnId))
                offset = end
            }
        }
        return copy(
            spans = merged,
            gaps = nextGaps,
            cursors = received.firstOrNull()?.let { cursors + (it to page.cursor) } ?: cursors,
            walks = if (target != null) walks + (target to page.cursor) else walks,
            unknown = unknown && !page.atStart,
            newestCursor = if (newest) page.cursor else newestCursor,
            rowOrder = order + rowOrder,
            rowEntries =
                (rowEntries.keys + order.keys).associateWith { key ->
                    rowEntries[key].orEmpty() + claims[key].orEmpty()
                },
            legacyKeys = aliases + legacyKeys,
            deltaText = texts + deltaText,
            deltaLengths = texts.mapValues { it.value.length } + deltaLengths,
            deltaHashes = texts.mapValues { historyHash(it.value) } + deltaHashes,
        )
    }

    /** Bind claims to the exact logical message/delta content retained by cache policy. */
    fun boundTo(rows: List<ThreadItem>): HistoryCoverage {
        val kept = cacheableThreadRows(rows)
        val available = historyRowProofs(kept).toMutableMap()
        val offsets = legacyOffsets.toMutableMap()
        val legacy =
            kept
                .filterIsInstance<ThreadItem.MessageItem>()
                .filter { it.message.segment == null }
                .associateBy { historyIdentity(it.mergeIdentity()) }
        legacyKeys.entries.groupBy { it.value }.forEach { (alias, keys) ->
            val message = legacy[alias]?.message ?: return@forEach
            val proof = available[alias] ?: return@forEach
            var offset = 0
            val ordered = keys.sortedBy { rowOrder[it.key] ?: Long.MAX_VALUE }
            ordered.forEachIndexed { index, (key, _) ->
                if (key in available) return@forEachIndexed
                val text = deltaText[key]
                if (text == null) {
                    if (proofs[key] == proof) {
                        available[key] = proof
                        offset = maxOf(offset, (offsets[key] ?: 0) + (deltaLengths[key] ?: 0))
                    }
                } else {
                    val next =
                        ordered.drop(index + 1).firstNotNullOfOrNull { entry ->
                            offsets[entry.key]?.takeIf { proofs[entry.key] == proof }
                        } ?: message.content.length
                    val at = message.content.indexOf(text, offset)
                    if (at >= 0 && at + text.length <= next) {
                        available[key] = proof
                        offsets[key] = at
                        offset = at + text.length
                    }
                }
            }
        }
        val missing = rowOrder.keys - available.keys
        return withoutRows(missing).copy(
            proofs = available.filterKeys { it in rowOrder && it !in missing },
            legacyOffsets =
                offsets - missing,
        )
    }

    /** A stale writer or trim cannot retain coverage for content it removed. */
    fun retainedBy(rows: List<ThreadItem>): HistoryCoverage {
        val available = historyRowProofs(cacheableThreadRows(rows)).toMutableMap()
        legacyKeys.forEach { (key, alias) ->
            if (key !in available && available[alias] == proofs[key]) available[alias]?.let { available[key] = it }
        }
        val missing =
            proofs
                .filter { (key, proof) ->
                    available[key] != proof && (deltaHashes[key] == null || available[key] != deltaHashes[key])
                }.keys
        return withoutRows(missing).copy(proofs = proofs.mapValues { (key, proof) -> available[key] ?: proof } - missing)
    }

    private fun withoutRows(missing: Set<String>): HistoryCoverage {
        if (missing.isEmpty()) return this
        val removed = missing.flatMap { rowEntries[it]?.takeIf { ids -> ids.isNotEmpty() } ?: listOfNotNull(rowOrder[it]) }.toSet()
        val kept =
            spans.flatMap { span ->
                var start = span.first
                buildList {
                    removed.filter { it in span.first..span.last }.sorted().forEach { id ->
                        if (start < id) add(HistorySpan(start, id - 1))
                        start = id + 1
                    }
                    if (start <= span.last) add(HistorySpan(start, span.last))
                }
            }
        return copy(
            spans = kept,
            gaps = kept.zipWithNext { a, b -> HistoryGap(a.last, b.first) },
            unknown = true,
            rowOrder = rowOrder - missing,
            rowEntries = rowEntries - missing,
            proofs = proofs - missing,
            legacyKeys = legacyKeys - missing,
            legacyOffsets = legacyOffsets - missing,
            deltaLengths = deltaLengths - missing,
            deltaHashes = deltaHashes - missing,
            deltaText = deltaText - missing,
        )
    }

    internal fun positions(): Map<String, Long> =
        rowOrder.toMutableMap().apply {
            legacyKeys.forEach { (key, alias) -> rowOrder[key]?.let { put(alias, maxOf(get(alias) ?: 0, it)) } }
        }

    /** Separate display fragments at verified holes; repository rows and cache identities stay held. */
    internal fun displayRows(rows: List<ThreadItem>): List<ThreadItem> {
        if (gaps.isEmpty()) return rows
        val reserved = rows.filterIsInstance<ThreadItem.MessageItem>().mapTo(HashSet()) { it.message.id }
        return rows.flatMap { row ->
            val message = (row as? ThreadItem.MessageItem)?.message ?: return@flatMap listOf(row)
            val segment = message.segment ?: return@flatMap listOf(row)
            val boundaries =
                segment.deltas.indices.drop(1).filter { index ->
                    val before = rowOrder[historyIdentity(listOf("delta", segment.turnId, segment.deltas[index - 1].seq))]
                    val after = rowOrder[historyIdentity(listOf("delta", segment.turnId, segment.deltas[index].seq))]
                    before != null && after != null && gaps.any { before <= it.anchor && after >= it.edge }
                }
            if (boundaries.isEmpty()) return@flatMap listOf(row)
            var offset = 0
            (listOf(0) + boundaries + segment.deltas.size).zipWithNext { first, last ->
                val deltas = segment.deltas.subList(first, last)
                val end =
                    (offset.toLong() + deltas.sumOf { it.length.toLong() })
                        .coerceIn(
                            offset.toLong(),
                            message.content.length.toLong(),
                        ).toInt()
                val text = message.content.substring(offset, end)
                offset = end
                var key = if (first == 0) message.id else segmentKey(segment.turnId, deltas.first().seq)
                if (first != 0) {
                    val natural = key
                    var suffix = 0
                    while (!reserved.add(key)) {
                        suffix++
                        key = "$natural~$suffix"
                    }
                }
                ThreadItem.MessageItem(message.copy(id = key, content = text, segment = segment.copy(deltas = deltas)))
            }
        }
    }

    override fun toString(): String = "HistoryCoverage(spans=${spans.size}, gaps=${gaps.size}, unknown=$unknown)"
}

private fun normalize(spans: List<HistorySpan>): List<HistorySpan> =
    buildList {
        for (span in spans.sortedBy { it.first }) {
            val last = lastOrNull()
            if (last != null && (span.first <= last.last || span.first - 1 == last.last)) {
                this[lastIndex] = HistorySpan(last.first, maxOf(last.last, span.last))
            } else {
                add(span)
            }
        }
    }

internal fun historyIdentity(identity: Any): String =
    historyHash(
        (identity as? List<*>)?.joinToString("") { value -> value.toString().let { "${it.length}:$it" } } ?: identity.toString(),
    )

internal fun ThreadItem.historyKeys(): List<String> {
    val segment = (this as? ThreadItem.MessageItem)?.message?.segment
    return segment?.deltas?.map { historyIdentity(listOf("delta", segment.turnId, it.seq)) }
        ?: listOf(historyIdentity(mergeIdentity()))
}

internal fun historyRowProofs(rows: List<ThreadItem>): Map<String, String> =
    buildMap {
        rows.forEach { row ->
            val message = (row as? ThreadItem.MessageItem)?.message
            val segment = message?.segment
            if (segment != null) {
                var offset = 0
                segment.deltas.forEach { delta ->
                    val end = (offset + delta.length).coerceIn(offset, message.content.length)
                    put(historyIdentity(listOf("delta", segment.turnId, delta.seq)), historyHash(message.content.substring(offset, end)))
                    offset = end
                }
            } else {
                put(historyIdentity(row.mergeIdentity()), cachedThreadRowProof(row))
            }
        }
    }

private fun historyHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
