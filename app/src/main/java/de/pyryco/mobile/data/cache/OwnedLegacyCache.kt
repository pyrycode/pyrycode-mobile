package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem

/** Capability used only by a writer that observed the identities it changes. */
internal interface OwnedLegacyCache {
    suspend fun mutateLegacyRows(
        host: String,
        conversation: String,
        upserts: List<OwnedLegacyRow>,
        removed: Set<String>,
    ): Result<Unit>
}

/** Received evidence changes are separate from the successful row baseline. */
internal interface OwnedLegacyCoverageCache : OwnedLegacyCache {
    suspend fun writeOwnedLegacyCoverage(
        host: String,
        conversation: String,
        position: HistoryPosition,
        previous: HistoryPosition?,
        observed: List<ThreadItem>,
    ): Result<Unit>
}

internal class OwnedLegacyRow(
    val row: ThreadItem,
    val after: String?,
    val before: String?,
) {
    override fun toString(): String = "OwnedLegacyRow(redacted)"
}

/** Canonical renderer identities, independent of content, timestamps used for placement and unsigned history ids. */
internal fun ThreadItem.legacyRowKey(): String =
    when (this) {
        is ThreadItem.MessageItem -> "message:${message.id}"
        is ThreadItem.SessionBoundary ->
            "boundary:${previousSessionId.length}:$previousSessionId${newSessionId.length}:$newSessionId@$occurredAt"
        is ThreadItem.Banner -> "banner:$occurredAt"
        is ThreadItem.CompactionBoundary -> "compaction:$occurredAt"
        is ThreadItem.ModelRefusal -> "refusal:${fallbackModel != null}:$occurredAt"
        is ThreadItem.StoppedTurn -> "stopped:$turnId"
        is ThreadItem.UnrecognizedMessage -> "unrecognized:$id"
        is ThreadItem.BackgroundTaskLifecycle -> "background:${terminal != null}:$taskId"
    }

internal const val LEGACY_ORDINAL_STEP = 4_294_967_296L

/** Apply the successful mutation to the observed baseline, retaining unowned and unsettled content. */
internal fun applyOwnedRows(
    baseline: List<ThreadItem>,
    changes: List<OwnedLegacyRow>,
    removed: Set<String>,
    trim: Boolean = true,
): List<ThreadItem> {
    val positions = baseline.withIndex().associate { it.value.legacyRowKey() to it.index }
    val replacements = changes.associate { it.row.legacyRowKey() to it.row }
    val slots = HashMap<Int, MutableList<ThreadItem>>()
    val newSlots = HashMap<String, Int>()
    for (change in changes) {
        val key = change.row.legacyRowKey()
        if (key in positions) continue
        val slot = positions[change.after]?.plus(1) ?: newSlots[change.after] ?: positions[change.before] ?: baseline.size
        slots.getOrPut(slot) { ArrayList() }.add(change.row)
        newSlots[key] = slot
    }
    val applied =
        buildList {
            for (index in 0..baseline.size) {
                slots[index]?.let { addAll(it) }
                if (index < baseline.size) {
                    val row = baseline[index]
                    val key = row.legacyRowKey()
                    if (key !in removed) add(replacements[key] ?: row)
                }
            }
        }
    return if (trim) cacheableThreadRows(applied) else applied
}
