package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.LegacyThreadPage
import de.pyryco.mobile.data.cache.MigratingConversationCache
import de.pyryco.mobile.data.cache.legacyRowKey
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Partial saved evidence is never a serializable full-cache history position. */
class SavedLegacyHistory internal constructor(
    internal val coverage: HistoryCoverage,
    internal val unknownEdge: ULong?,
    internal val hasOlder: Boolean,
) {
    internal fun retainedBy(rows: List<ThreadItem>): SavedLegacyHistory =
        SavedLegacyHistory(coverage.retainedBy(rows), unknownEdge, hasOlder)

    internal fun withNetwork(network: HistoryCoverage): HistoryCoverage = joinEvidence(coverage, network)

    internal fun cursorFor(
        anchor: ULong,
        network: HistoryCoverage,
    ): String {
        val joined = withNetwork(network)
        if (anchor != 0uL) return joined.cursorForUnsigned(anchor)
        joined.unsignedWalks[anchor]?.let { return it }
        val edge = unknownEdge ?: joined.unsignedUnknownEdge ?: return ""
        return joined.unsignedCursors.keys
            .filter { it >= edge }
            .minOrNull()
            ?.let(joined.unsignedCursors::get)
            .orEmpty()
    }

    internal fun plus(page: LegacyThreadPage): SavedLegacyHistory =
        SavedLegacyHistory(
            joinEvidence(coverage, page.evidence?.projected ?: HistoryCoverage()).copy(
                unsignedUnknown = coverage.unsignedUnknown || page.evidence?.unsignedUnknown == true,
            ),
            unknownEdge ?: page.evidence?.savedUnknownEdge,
            page.continuation != null,
        )

    override fun toString(): String = "SavedLegacyHistory(redacted)"
}

/** Union only actual received intervals and actual gaps; an omitted interval never becomes a new gap. */
private fun joinEvidence(
    saved: HistoryCoverage,
    newer: HistoryCoverage,
): HistoryCoverage {
    val spans = ArrayList<UnsignedHistorySpan>()
    for (span in (saved.unsignedSpans + newer.unsignedSpans).sortedBy { it.first }) {
        val last = spans.lastOrNull()
        if (last != null && (span.first <= last.last || span.first - 1u == last.last)) {
            spans[spans.lastIndex] = UnsignedHistorySpan(last.first, maxOf(last.last, span.last))
        } else {
            spans += span
        }
    }

    fun containing(id: ULong): Int {
        val found = spans.binarySearch { it.first.compareTo(id) }
        val index = if (found >= 0) found else -found - 2
        return index.takeIf { it >= 0 && id <= spans[it].last } ?: -1
    }
    val gaps = LinkedHashMap<ULong, UnsignedHistoryGap>()
    for (gap in newer.unsignedGaps + saved.unsignedGaps) {
        val first = containing(gap.anchor)
        val last = containing(gap.edge)
        if (first < 0 || last <= first) continue
        // A receipt inside a known hole splits it; only joining its actual endpoints closes it.
        for (index in first until last) {
            val edge = spans[index + 1].first
            gaps.putIfAbsent(edge, UnsignedHistoryGap(if (index == first) gap.anchor else spans[index].last, edge))
        }
    }
    return newer.copy(
        unsignedSpans = spans,
        unsignedGaps = gaps.values.sortedBy { it.edge },
        unsignedRowOrder = saved.unsignedRowOrder + newer.unsignedRowOrder,
        unsignedRowEntries =
            (saved.unsignedRowEntries.keys + newer.unsignedRowEntries.keys).associateWith {
                saved.unsignedRowEntries[it].orEmpty() + newer.unsignedRowEntries[it].orEmpty()
            },
        proofs = saved.proofs + newer.proofs,
        legacyKeys = saved.legacyKeys + newer.legacyKeys,
        legacyOffsets = saved.legacyOffsets + newer.legacyOffsets,
        deltaLengths = saved.deltaLengths + newer.deltaLengths,
        deltaHashes = saved.deltaHashes + newer.deltaHashes,
        deltaText = saved.deltaText + newer.deltaText,
        unsignedCursors = saved.unsignedCursors + newer.unsignedCursors,
        unsignedWalks = saved.unsignedWalks + newer.unsignedWalks,
    )
}

/** Caller-owned disk work; live collection never acquires this window's read mutex. */
internal class LegacyLocalWindow(
    private val cache: MigratingConversationCache,
    private val host: String,
    private val conversation: String,
    private val epoch: Pair<Long, Long>,
) {
    internal class Reading(
        val rows: List<ThreadItem>,
        val history: SavedLegacyHistory,
        val token: LegacyThreadPage.Continuation?,
        val position: HistoryPosition?,
        val removed: Set<String> = emptySet(),
    )

    val current: Boolean get() = cache.legacyEpoch(host, conversation) == epoch
    val readings = MutableStateFlow<Reading?>(null)
    val published = CompletableDeferred<Boolean>()
    private val reads = Mutex()

    suspend fun initial(): Reading =
        reads.withLock {
            readings.value?.let { return@withLock it }
            val page = cache.newestLegacyPage(host, conversation, 200).takeIf { current }
            val rows = page?.rows.orEmpty()
            val evidence = page?.evidence
            val history =
                SavedLegacyHistory(
                    evidence?.projected ?: HistoryCoverage(),
                    evidence?.savedUnknownEdge,
                    page?.continuation != null,
                )
            val position =
                if (rows.isEmpty() && evidence == null) {
                    null
                } else {
                    HistoryPosition(
                        page?.savedCursor.orEmpty(),
                        page?.savedAtStart == true && evidence?.unsignedIncomplete != true,
                        // Scalar seed only. Saved claims stay out of the network walk and owned coverage writes.
                        HistoryCoverage(
                            unknown = evidence?.unsignedUnknown ?: rows.isNotEmpty(),
                            newestCursor = evidence?.newestCursor,
                            unsignedIncomplete = evidence?.unsignedIncomplete == true,
                        ),
                    )
                }
            RelayLog.d { "event=legacy_local_open rows=${rows.size}" }
            Reading(rows, history, page?.continuation, position).also { readings.value = it }
        }

    suspend fun older(held: List<ThreadItem>): Boolean {
        if (!current) return true
        if (!reads.tryLock()) return true
        try {
            val previous = readings.value ?: return false
            val token = previous.token ?: return false
            val page =
                cache.beforeLegacyPage(host, conversation, token, 200) ?: run {
                    RelayLog.d { "event=legacy_local_continue outcome=reanchor" }
                    val owned = previous.rows.mapTo(LinkedHashSet()) { it.legacyRowKey() }
                    val anchors = held.map { it.legacyRowKey() }.filter { it in owned }
                    cache.beforeHeldLegacyPage(host, conversation, anchors + (owned - anchors.toSet()), 200)
                }
            if (!current) return true
            if (page == null) return false
            val history = previous.history.plus(page)
            val order = (previous.rows + page.rows).receivedUnsignedHistoryOrder(history.coverage.unsignedPositions())
            val rows =
                previous.rows
                    .filterNot { it.legacyRowKey() in page.missingHeld }
                    .mergeUnsignedCachedRows(page.rows, order, rendererOwners = previous.rows)
            readings.value =
                Reading(
                    rows,
                    history.retainedBy(rows),
                    page.continuation,
                    previous.position,
                    previous.removed + page.missingHeld,
                )
            RelayLog.d { "event=legacy_local_continue outcome=published rows=${page.rows.size}" }
            return true
        } finally {
            reads.unlock()
        }
    }
}
