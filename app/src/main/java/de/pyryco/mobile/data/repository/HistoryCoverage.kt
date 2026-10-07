package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.cacheableThreadRows
import de.pyryco.mobile.data.cache.cachedThreadRowProof
import de.pyryco.mobile.data.network.ReadMarkIdSerializer
import kotlinx.serialization.SerialName
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

@Serializable
data class UnsignedHistorySpan(
    @Serializable(with = ReadMarkIdSerializer::class) val first: ULong,
    @Serializable(with = ReadMarkIdSerializer::class) val last: ULong,
)

@Serializable
data class UnsignedHistoryGap(
    @Serializable(with = ReadMarkIdSerializer::class) val anchor: ULong,
    @Serializable(with = ReadMarkIdSerializer::class) val edge: ULong,
)

/** Received durable ids only. Signed properties expose only representable claims for older UI consumers. */
@Serializable
data class HistoryCoverage(
    @SerialName("gaps") val unsignedGaps: List<UnsignedHistoryGap> = emptyList(),
    @SerialName("cursors") val unsignedCursors: Map<ULong, String> = emptyMap(),
    @SerialName("walks") val unsignedWalks: Map<ULong, String> = emptyMap(),
    @SerialName("unknown") val unsignedUnknown: Boolean = false,
    val newestCursor: String? = null,
    @SerialName("rowOrder") val unsignedRowOrder: Map<
        String,
        @Serializable(with = ReadMarkIdSerializer::class)
        ULong,
    > = emptyMap(),
    @SerialName("rowEntries") val unsignedRowEntries: Map<
        String,
        Set<
            @Serializable(with = ReadMarkIdSerializer::class)
            ULong,
        >,
    > =
        emptyMap(),
    val proofs: Map<String, String> = emptyMap(),
    val legacyKeys: Map<String, String> = emptyMap(),
    val legacyOffsets: Map<String, Int> = emptyMap(),
    val deltaLengths: Map<String, Int> = emptyMap(),
    val deltaHashes: Map<String, String> = emptyMap(),
    @Transient val deltaText: Map<String, String> = emptyMap(),
    /** Sticky signed-view uncertainty; authoritative unsigned claims remain available independently. */
    @SerialName("unsignedIncomplete") val signedUncertainty: Boolean = false,
    @SerialName("spans") val unsignedSpans: List<UnsignedHistorySpan>,
) {
    constructor(
        spans: List<HistorySpan> = emptyList(),
        gaps: List<HistoryGap> = emptyList(),
        cursors: Map<Long, String> = emptyMap(),
        walks: Map<Long, String> = emptyMap(),
        unknown: Boolean = false,
        newestCursor: String? = null,
        rowOrder: Map<String, Long> = emptyMap(),
        rowEntries: Map<String, Set<Long>> = emptyMap(),
        proofs: Map<String, String> = emptyMap(),
        legacyKeys: Map<String, String> = emptyMap(),
        legacyOffsets: Map<String, Int> = emptyMap(),
        deltaLengths: Map<String, Int> = emptyMap(),
        deltaHashes: Map<String, String> = emptyMap(),
        deltaText: Map<String, String> = emptyMap(),
        unsignedIncomplete: Boolean = false,
    ) : this(
        unsignedSpans = spans.map { UnsignedHistorySpan(it.first.positiveHistoryId(), it.last.positiveHistoryId()) },
        unsignedGaps = gaps.map { UnsignedHistoryGap(it.anchor.positiveHistoryId(), it.edge.positiveHistoryId()) },
        unsignedCursors = cursors.mapKeys { it.key.positiveHistoryId() },
        unsignedWalks = walks.mapKeys { it.key.positiveHistoryId(allowZero = true) },
        unsignedUnknown = unknown,
        newestCursor = newestCursor,
        unsignedRowOrder = rowOrder.mapValues { it.value.positiveHistoryId() },
        unsignedRowEntries = rowEntries.mapValues { (_, ids) -> ids.map { it.positiveHistoryId() }.toSet() },
        proofs = proofs,
        legacyKeys = legacyKeys,
        legacyOffsets = legacyOffsets,
        deltaLengths = deltaLengths,
        deltaHashes = deltaHashes,
        deltaText = deltaText,
        signedUncertainty = unsignedIncomplete,
    )

    @Transient val spans: List<HistorySpan> =
        unsignedSpans.mapNotNull { span ->
            span.first.signedId()?.let { HistorySpan(it, minOf(span.last, Long.MAX_VALUE.toULong()).toLong()) }
        }

    @Transient val gaps: List<HistoryGap> =
        unsignedGaps.mapNotNull { gap ->
            gap.anchor.signedId()?.let { anchor -> gap.edge.signedId()?.let { HistoryGap(anchor, it) } }
        }

    @Transient val cursors: Map<Long, String> = unsignedCursors.mapNotNull { (id, value) -> id.signedId()?.let { it to value } }.toMap()

    @Transient val walks: Map<Long, String> =
        unsignedWalks
            .mapNotNull { (id, value) ->
                id.signedId(allowZero = true)?.let { it to value }
            }.toMap()

    @Transient val rowOrder: Map<String, Long> = unsignedRowOrder.mapNotNull { (key, id) -> id.signedId()?.let { key to it } }.toMap()

    @Transient val rowEntries: Map<String, Set<Long>> =
        unsignedRowEntries
            .mapValues { (_, ids) ->
                ids.mapNotNull { it.signedId() }.toSet()
            }.filterValues { it.isNotEmpty() }

    @Transient val unsignedHighWater: ULong = unsignedSpans.maxOfOrNull { it.last } ?: 0u

    @Transient val unsignedUnknownEdge: ULong? = if (unsignedUnknown) unsignedSpans.minOfOrNull { it.first } else null

    @Transient val unsignedIncomplete: Boolean = signedUncertainty || unsignedHighWater > Long.MAX_VALUE.toULong()

    @Transient val unknown: Boolean = unsignedUnknown || unsignedIncomplete

    @Transient val highWater: Long = spans.maxOfOrNull { it.last } ?: 0

    @Transient val unknownEdge: Long? = if (unknown) spans.minOfOrNull { it.first } else null

    fun cursorFor(anchor: Long): String {
        require(anchor >= 0) { "invalid history anchor" }
        walks[anchor]?.let { return it }
        val edge = if (anchor == 0L) unknownEdge else gaps.firstOrNull { it.anchor == anchor }?.edge
        return edge
            ?.let { boundary ->
                cursors.keys
                    .filter { it >= boundary }
                    .minOrNull()
                    ?.let(cursors::get)
            }.orEmpty()
    }

    fun refused(anchor: Long): HistoryCoverage = refusedCursor(anchor.positiveHistoryId(allowZero = true), cursorFor(anchor))

    fun received(
        page: HistoryPage,
        newest: Boolean = false,
        target: Long? = null,
    ): HistoryCoverage = receivedUnsigned(page, newest, target?.positiveHistoryId(allowZero = true))

    fun cursorForUnsigned(anchor: ULong): String {
        unsignedWalks[anchor]?.let { return it }
        val edge = if (anchor == 0uL) unsignedUnknownEdge else unsignedGaps.firstOrNull { it.anchor == anchor }?.edge
        return edge?.let { boundary ->
            unsignedCursors.keys
                .filter { it >= boundary }
                .minOrNull()
                ?.let(unsignedCursors::get)
        }
            ?: ""
    }

    fun refusedUnsigned(anchor: ULong): HistoryCoverage = refusedCursor(anchor, cursorForUnsigned(anchor))

    private fun refusedCursor(
        anchor: ULong,
        refused: String,
    ): HistoryCoverage {
        val usable = newestCursor?.takeUnless { it == refused }
        return copy(
            unsignedCursors = unsignedCursors.filterValues { it != refused },
            unsignedWalks =
                unsignedWalks + (anchor to usable.orEmpty()),
            newestCursor = usable,
        )
    }

    fun receivedUnsigned(
        page: HistoryPage,
        newest: Boolean = false,
        target: ULong? = null,
    ): HistoryCoverage {
        val received =
            page.entries
                .map { it.unsignedId }
                .filter { it > 0u }
                .distinct()
                .sorted()
        val merged = normalize(unsignedSpans + received.map { UnsignedHistorySpan(it, it) })
        val holes = merged.zipWithNext { older, newer -> UnsignedHistoryGap(older.last, newer.first) }
        val nextGaps =
            holes.map { hole ->
                val olderSpan = merged.first { it.last == hole.anchor }
                val previous = unsignedGaps.firstOrNull { it.anchor in olderSpan.first..olderSpan.last && it.edge >= hole.edge }
                hole.copy(anchor = previous?.anchor ?: hole.anchor)
            }
        val reduced = reduceOrderedHistoryPage(page.entries, interactive = true)
        val retainedKeys =
            reduced.rows
                .filterNot { it is ThreadItem.UnrecognizedMessage || it is ThreadItem.BackgroundTaskLifecycle }
                .flatMap { it.historyKeys() }
                .toSet()
        val order =
            reduced.unsignedOrder.entries
                .associate { historyIdentity(it.key) to it.value }
                .filterKeys { it in retainedKeys }
        val claims = reduced.unsignedClaims.mapKeys { historyIdentity(it.key) }.filterKeys { it in retainedKeys }
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
            unsignedSpans = merged,
            unsignedGaps = nextGaps,
            unsignedCursors = received.firstOrNull()?.let { unsignedCursors + (it to page.cursor) } ?: unsignedCursors,
            unsignedWalks = if (target != null) unsignedWalks + (target to page.cursor) else unsignedWalks,
            unsignedUnknown = unsignedUnknown && !page.atStart,
            signedUncertainty = unsignedIncomplete || received.any { it > Long.MAX_VALUE.toULong() },
            newestCursor = if (newest) page.cursor else newestCursor,
            unsignedRowOrder = order + unsignedRowOrder,
            unsignedRowEntries =
                (unsignedRowEntries.keys + order.keys).associateWith { key ->
                    unsignedRowEntries[key].orEmpty() + claims[key].orEmpty()
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
        val direct = historyRowProofs(kept)
        val available = direct.toMutableMap()
        val savedBindings = legacyBindingProofs(kept, direct)
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
            val ordered = keys.sortedBy { unsignedRowOrder[it.key] ?: ULong.MAX_VALUE }
            ordered.forEachIndexed { index, (key, _) ->
                if (key in available) return@forEachIndexed
                val text = deltaText[key]
                if (text == null) {
                    if (key in savedBindings) {
                        available[key] = proof
                        offset = offsets.getValue(key) + deltaLengths.getValue(key)
                    }
                } else {
                    val next =
                        ordered.drop(index + 1).firstNotNullOfOrNull { entry ->
                            offsets[entry.key]?.takeIf { entry.key in savedBindings }
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
        val verified = copy(proofs = available, legacyOffsets = offsets).legacyBindingProofs(kept, direct)
        (legacyKeys.keys - direct.keys - verified.keys).forEach(available::remove)
        val missing = unsignedRowOrder.keys - available.keys
        return withoutRows(missing).copy(
            proofs = available.filterKeys { it in unsignedRowOrder && it !in missing },
            legacyOffsets =
                offsets - missing,
        )
    }

    /** A stale writer or trim cannot retain coverage for content it removed. */
    fun retainedBy(rows: List<ThreadItem>): HistoryCoverage {
        val kept = cacheableThreadRows(rows)
        val direct = historyRowProofs(kept)
        val available = direct + legacyBindingProofs(kept, direct)
        val missing =
            proofs
                .filter { (key, proof) ->
                    available[key] != proof && (deltaHashes[key] == null || available[key] != deltaHashes[key])
                }.keys
        return withoutRows(missing).copy(proofs = proofs.mapValues { (key, proof) -> available[key] ?: proof } - missing)
    }

    /** A whole-row proof admits an alias only with its own ordered, retained fragment. */
    private fun legacyBindingProofs(
        rows: List<ThreadItem>,
        direct: Map<String, String>,
    ): Map<String, String> {
        val legacy =
            rows
                .filterIsInstance<ThreadItem.MessageItem>()
                .filter { it.message.segment == null }
                .associateBy { historyIdentity(it.mergeIdentity()) }
        return buildMap {
            legacyKeys.entries.groupBy { it.value }.forEach { (alias, keys) ->
                val content = legacy[alias]?.message?.content ?: return@forEach
                val proof = direct[alias] ?: return@forEach
                var end = 0L
                var previous: ULong? = null
                keys.sortedBy { unsignedRowOrder[it.key] ?: ULong.MAX_VALUE }.forEach binding@{ (key, _) ->
                    if (key in direct) return@binding
                    val order = unsignedRowOrder[key] ?: return@binding
                    val offset = legacyOffsets[key]?.toLong() ?: return@binding
                    val length = deltaLengths[key]?.toLong() ?: return@binding
                    val next = offset + length
                    if (proofs[key] != proof ||
                        offset < end ||
                        length < 0 ||
                        next > content.length ||
                        previous?.let { order <= it } == true ||
                        deltaHashes[key] != historyHash(content.substring(offset.toInt(), next.toInt()))
                    ) {
                        return@binding
                    }
                    put(key, proof)
                    end = next
                    previous = order
                }
            }
        }
    }

    private fun withoutRows(missing: Set<String>): HistoryCoverage {
        if (missing.isEmpty()) return this
        val removed =
            missing
                .flatMap {
                    unsignedRowEntries[it]?.takeIf { ids ->
                        ids.isNotEmpty()
                    } ?: listOfNotNull(unsignedRowOrder[it])
                }.toSet()
        val kept =
            unsignedSpans.flatMap { span ->
                var start: ULong? = span.first
                buildList {
                    removed.filter { it in span.first..span.last }.sorted().forEach { id ->
                        start?.let { first -> if (first < id) add(UnsignedHistorySpan(first, id - 1u)) }
                        start = if (id == ULong.MAX_VALUE) null else id + 1u
                    }
                    start?.let { if (it <= span.last) add(UnsignedHistorySpan(it, span.last)) }
                }
            }
        return copy(
            unsignedSpans = kept,
            unsignedGaps = kept.zipWithNext { a, b -> UnsignedHistoryGap(a.last, b.first) },
            unsignedUnknown = true,
            unsignedRowOrder = unsignedRowOrder - missing,
            unsignedRowEntries = unsignedRowEntries - missing,
            proofs = proofs - missing,
            legacyKeys = legacyKeys - missing,
            legacyOffsets = legacyOffsets - missing,
            deltaLengths = deltaLengths - missing,
            deltaHashes = deltaHashes - missing,
            deltaText = deltaText - missing,
        )
    }

    internal fun unsignedPositions(): Map<String, ULong> =
        unsignedRowOrder.toMutableMap().apply {
            legacyKeys.forEach { (key, alias) -> unsignedRowOrder[key]?.let { put(alias, maxOf(get(alias) ?: 0u, it)) } }
        }

    internal fun positions(): Map<String, Long> = unsignedPositions().mapNotNull { (key, id) -> id.signedId()?.let { key to it } }.toMap()

    /** Validate optional disk claims independently from the retained rows. */
    internal fun validated(rows: List<ThreadItem>? = null): HistoryCoverage {
        require(
            unsignedSpans.all { it.first > 0u && it.first <= it.last } && normalize(unsignedSpans) == unsignedSpans,
        ) { "invalid history spans" }
        val holes = unsignedSpans.zipWithNext()
        require(
            unsignedGaps.size == holes.size &&
                unsignedGaps.zip(holes).all { (gap, spans) ->
                    gap.anchor in spans.first.first..spans.first.last && gap.edge == spans.second.first
                },
        ) { "invalid history gaps" }

        fun covered(id: ULong): Boolean {
            val found = unsignedSpans.binarySearch { it.first.compareTo(id) }
            val index = if (found >= 0) found else -found - 2
            return index >= 0 && id <= unsignedSpans[index].last
        }
        require(
            unsignedRowOrder.all { (key, id) ->
                covered(id) && id in unsignedRowEntries[key].orEmpty()
            } &&
                unsignedRowEntries.all { (_, ids) ->
                    ids.isNotEmpty() && ids.all(::covered)
                },
        ) { "invalid history row claims" }
        require(unsignedRowOrder.keys == unsignedRowEntries.keys && proofs.keys == unsignedRowOrder.keys) { "unbound history claims" }
        require(
            unsignedCursors.keys.all {
                it > 0u
            } &&
                deltaLengths.values.all { it >= 0 } &&
                legacyOffsets.values.all { it >= 0 },
        ) { "invalid history anchors" }
        if (rows != null) {
            val direct = historyRowProofs(rows)
            val aliased = legacyKeys.keys - direct.keys
            require(legacyBindingProofs(rows, direct).keys == aliased) { "invalid history legacy bindings" }
        }
        return this
    }

    /** Separate display fragments at verified holes; repository rows and cache identities stay held. */
    internal fun displayRows(rows: List<ThreadItem>): List<ThreadItem> {
        if (unsignedGaps.isEmpty()) return rows
        val reserved = rows.filterIsInstance<ThreadItem.MessageItem>().mapTo(HashSet()) { it.message.id }
        return rows.flatMap { row ->
            val message = (row as? ThreadItem.MessageItem)?.message ?: return@flatMap listOf(row)
            val segment = message.segment ?: return@flatMap listOf(row)
            val boundaries =
                segment.deltas.indices.drop(1).filter { index ->
                    val before = unsignedRowOrder[historyIdentity(listOf("delta", segment.turnId, segment.deltas[index - 1].seq))]
                    val after = unsignedRowOrder[historyIdentity(listOf("delta", segment.turnId, segment.deltas[index].seq))]
                    before != null && after != null && unsignedGaps.any { before <= it.anchor && after >= it.edge }
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

    override fun toString(): String = "HistoryCoverage(spans=${unsignedSpans.size}, gaps=${unsignedGaps.size}, unknown=$unknown)"
}

private fun normalize(spans: List<UnsignedHistorySpan>): List<UnsignedHistorySpan> =
    buildList {
        for (span in spans.sortedBy { it.first }) {
            val last = lastOrNull()
            if (last != null && (span.first <= last.last || span.first > 0u && span.first - 1u == last.last)) {
                this[lastIndex] = UnsignedHistorySpan(last.first, maxOf(last.last, span.last))
            } else {
                add(span)
            }
        }
    }

private fun Long.positiveHistoryId(allowZero: Boolean = false): ULong {
    require(this > 0 || allowZero && this == 0L) { "invalid signed history identity" }
    return toULong()
}

private fun ULong.signedId(allowZero: Boolean = false): Long? =
    takeIf { (allowZero || it > 0u) && it <= Long.MAX_VALUE.toULong() }?.toLong()

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
