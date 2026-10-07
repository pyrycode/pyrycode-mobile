package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnsignedHistoryGap
import de.pyryco.mobile.data.repository.UnsignedHistorySpan
import de.pyryco.mobile.data.repository.historyKeys
import de.pyryco.mobile.data.repository.historyRowProofs
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

/** 36000 received durable entries, separated into 18000 spans, restored through the disk codec. */
internal fun fragmentedHistoryFixture(indices: List<Int>): Pair<HistoryCoverage, List<ThreadItem>> {
    val rows =
        indices.map { index ->
            ThreadItem.MessageItem(
                Message("fragmented-$index", "s", Role.User, "Row $index.", Instant.fromEpochSeconds(index.toLong()), false),
            )
        }
    val spans =
        (0 until 18000).map { index ->
            val first = index.toULong() * 4u + 1u
            UnsignedHistorySpan(first, first + 1u)
        }
    val coverage =
        HistoryCoverage(
            unsignedSpans = spans,
            unsignedGaps = spans.zipWithNext { older, newer -> UnsignedHistoryGap(older.last, newer.first) },
            unsignedWalks = mapOf(spans[8999].last to "selected-gap", spans[9000].last to "internal-gap"),
            unsignedUnknown = true,
            unsignedRowOrder = rows.zip(indices).associate { (row, index) -> row.historyKeys().first() to spans[index].first },
        )
    val bound =
        coverage.copy(
            unsignedRowEntries = coverage.unsignedRowOrder.mapValues { setOf(it.value) },
            proofs = historyRowProofs(rows),
        )
    return Json.decodeFromString<HistoryCoverage>(Json.encodeToString(bound)).validated(rows) to rows
}
