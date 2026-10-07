package de.pyryco.mobile.data.repository

internal data class HistoryDisplayProjection(
    val rows: List<ThreadItem>,
    val markers: List<HistoryDisplayMarker>,
)

internal data class HistoryDisplayMarker(
    val anchor: ULong,
    val beforeRow: String,
    val displayRow: ThreadItem,
)

private data class PreparedHistoryRow(
    val item: ThreadItem,
    val keys: List<String>,
)

/** Display eligibility never modifies the received coverage or its independent cursor walks. */
internal fun HistoryCoverage.projectDisplay(
    rows: List<ThreadItem>,
    keysFor: ((ThreadItem) -> List<String>)? = null,
    positionFor: (String) -> ULong? = unsignedPositions()::get,
    checkActive: () -> Unit = {},
): HistoryDisplayProjection {
    if (rows.isEmpty()) return HistoryDisplayProjection(rows, emptyList())
    val prepared = prepareDisplayRows(rows, keysFor, positionFor, checkActive)
    val firstBySpan = HashMap<Int, PreparedHistoryRow>()
    var oldestPosition: ULong? = null
    for (row in prepared) {
        checkActive()
        for (key in row.keys) {
            checkActive()
            val position = positionFor(key) ?: continue
            oldestPosition = oldestPosition?.let { minOf(it, position) } ?: position
            val span = spanAt(position)
            if (span >= 0) firstBySpan.putIfAbsent(span, row)
        }
    }
    val markers = ArrayList<Pair<ULong, HistoryDisplayMarker>>()
    var oldestEdge: ULong? = unsignedUnknownEdge?.takeIf { it <= (oldestPosition ?: 0u) }
    var oldestAnchor = 0uL
    for (gap in unsignedGaps) {
        checkActive()
        if (gap.edge <= (oldestPosition ?: 0u) && (oldestEdge == null || gap.edge >= oldestEdge)) {
            oldestEdge = gap.edge
            oldestAnchor = gap.anchor
        } else if (gap.edge > (oldestPosition ?: 0u)) {
            val older = spanAt(gap.anchor)
            val newer = spanAt(gap.edge)
            val target = firstBySpan[newer]
            if (older >= 0 && newer == older + 1 && firstBySpan.containsKey(older) && target != null) {
                markers += gap.edge to HistoryDisplayMarker(gap.anchor, target.keys.first(), target.item)
            }
        }
    }
    if (oldestEdge !=
        null
    ) {
        markers += oldestEdge to HistoryDisplayMarker(oldestAnchor, prepared.first().keys.first(), prepared.first().item)
    }
    checkActive()
    return HistoryDisplayProjection(prepared.map { it.item }, markers.sortedBy { it.first }.map { it.second })
}

/** One binary search per position replaces scanning every received hole for every delta. */
private fun HistoryCoverage.spanAt(position: ULong): Int {
    val found = unsignedSpans.binarySearch { it.first.compareTo(position) }
    val index = if (found >= 0) found else -found - 2
    return index.takeIf { it >= 0 && position <= unsignedSpans[it].last } ?: -1
}

private fun HistoryCoverage.prepareDisplayRows(
    rows: List<ThreadItem>,
    keysFor: ((ThreadItem) -> List<String>)?,
    positionFor: (String) -> ULong?,
    checkActive: () -> Unit,
): List<PreparedHistoryRow> {
    val reserved = rows.filterIsInstance<ThreadItem.MessageItem>().mapTo(HashSet()) { it.message.id }
    return buildList {
        for (row in rows) {
            checkActive()
            val keys = keysFor?.invoke(row) ?: row.cancellableHistoryKeys(checkActive)
            val message = (row as? ThreadItem.MessageItem)?.message
            val segment = message?.segment
            if (segment == null || unsignedGaps.isEmpty()) {
                add(PreparedHistoryRow(row, keys))
                continue
            }
            val positions =
                keys.map {
                    checkActive()
                    positionFor(it)
                }
            val boundaries =
                segment.deltas.indices.drop(1).filter { index ->
                    checkActive()
                    val before = positions[index - 1]?.let(::spanAt) ?: -1
                    val after = positions[index]?.let(::spanAt) ?: -1
                    before >= 0 && after > before
                }
            if (boundaries.isEmpty()) {
                add(PreparedHistoryRow(row, keys))
                continue
            }
            var offset = 0
            for ((first, last) in (listOf(0) + boundaries + segment.deltas.size).zipWithNext()) {
                checkActive()
                val deltas = segment.deltas.subList(first, last)
                val end =
                    (offset.toLong() + deltas.sumOf { it.length.toLong() })
                        .coerceIn(offset.toLong(), message.content.length.toLong())
                        .toInt()
                val text = message.content.substring(offset, end)
                offset = end
                var id = if (first == 0) message.id else segmentKey(segment.turnId, deltas.first().seq)
                if (first != 0) {
                    val natural = id
                    var suffix = 0
                    while (!reserved.add(id)) {
                        checkActive()
                        suffix++
                        id = "$natural~$suffix"
                    }
                }
                add(
                    PreparedHistoryRow(
                        ThreadItem.MessageItem(
                            message.copy(
                                id = id,
                                content = text,
                                segment = segment.copy(deltas = deltas),
                            ),
                        ),
                        keys.subList(first, last),
                    ),
                )
            }
        }
    }
}

internal fun HistoryCoverage.fragmentDisplayRows(rows: List<ThreadItem>): List<ThreadItem> =
    if (unsignedGaps.isEmpty()) rows else prepareDisplayRows(rows, null, unsignedRowOrder::get, {}).map { it.item }

private fun ThreadItem.cancellableHistoryKeys(checkActive: () -> Unit): List<String> {
    val segment = (this as? ThreadItem.MessageItem)?.message?.segment
    return segment?.deltas?.map {
        checkActive()
        historyIdentity(listOf("delta", segment.turnId, it.seq))
    } ?: listOf(historyIdentity(mergeIdentity()))
}
