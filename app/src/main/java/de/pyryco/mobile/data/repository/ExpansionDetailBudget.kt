package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.ThreadQueryRequest

/** Retention identity is independent of any request cursor or consumer. Never log this key. */
internal data class ExpansionDetailKey(
    val host: String,
    val conversation: String,
    val epoch: String,
    val generation: Long,
    val incarnation: String,
    val item: Pair<Long, Long>,
    val children: Boolean,
) {
    constructor(host: String, request: ThreadQueryRequest.Expansion, generation: Long, incarnation: String) : this(
        host,
        request.conversationId,
        request.epoch,
        generation,
        incarnation,
        request.itemId.toLong() to request.expectedRev.toLong(),
        request is ThreadQueryRequest.Children,
    )

    fun owns(snapshot: AuthoritativeThreadSnapshot): Boolean =
        epoch == snapshot.epoch && generation == snapshot.generation && incarnation == snapshot.incarnation && !snapshot.resetNeeded

    fun replaces(other: ExpansionDetailKey): Boolean =
        host == other.host && conversation == other.conversation && epoch == other.epoch &&
            generation == other.generation && incarnation == other.incarnation && children == other.children &&
            item.first == other.item.first

    override fun toString() = "ExpansionDetailKey(redacted)"
}

/** Called only under the store monitor. Costs describe retained JSON, not rendered or source facts. */
internal class ExpansionDetailBudget {
    private class Entry(
        val bytes: Long,
        val summaries: Int,
        var used: Long,
    )

    private val entries = mutableMapOf<ExpansionDetailKey, Entry>()
    private var clock = 0L

    fun touch(key: ExpansionDetailKey) {
        entries[key]?.let { it.used = ++clock }
    }

    /** A failed plan changes neither recency nor availability, including the previous page/cursor. */
    fun victims(
        key: ExpansionDetailKey,
        bytes: Long,
        summaries: Int,
        protected: Set<ExpansionDetailKey>,
    ): List<ExpansionDetailKey>? {
        val retained = entries.filterKeys { it.host == key.host && !key.replaces(it) }
        var totalBytes = retained.values.sumOf { it.bytes } + bytes
        var totalSummaries = retained.values.sumOf { it.summaries } + summaries

        fun fits() = totalBytes <= MAX_BYTES && totalSummaries <= MAX_SUMMARIES
        val victims = mutableListOf<ExpansionDetailKey>()
        if (fits()) return victims
        for ((candidate, entry) in retained.entries.sortedBy { it.value.used }) {
            if (candidate in protected) continue
            victims += candidate
            totalBytes -= entry.bytes
            totalSummaries -= entry.summaries
            if (fits()) return victims
        }
        return null
    }

    fun record(
        key: ExpansionDetailKey,
        bytes: Long,
        summaries: Int,
    ) {
        entries.keys.removeAll { key.replaces(it) }
        entries[key] = Entry(bytes, summaries, ++clock)
    }

    fun reconcile(
        host: String,
        conversation: String,
        snapshot: AuthoritativeThreadSnapshot,
    ) {
        entries.keys.removeAll { key ->
            key.host == host &&
                key.conversation == conversation &&
                (!key.owns(snapshot) || key.item !in if (key.children) snapshot.childPages else snapshot.bodyContents)
        }
    }

    private companion object {
        const val MAX_BYTES = 16L * 1024 * 1024
        const val MAX_SUMMARIES = 1000
    }
}
