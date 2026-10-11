package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.ThreadItem

/** Opt-in capture of the app's own asks and rows, with no extra history subscription. */
internal object DurableHistoryProbe {
    private var selected: String? = null
    private val requests = mutableListOf<Boolean>()
    private var pages = 0
    private val receipts = mutableListOf<List<ULong>>()
    private var observed = emptyList<ThreadItem>()

    @Synchronized fun begin(conversationId: String) {
        selected = conversationId
        requests.clear()
        pages = 0
        receipts.clear()
        observed = emptyList()
    }

    @Synchronized fun end() {
        selected = null
        requests.clear()
        pages = 0
        receipts.clear()
        observed = emptyList()
    }

    @Synchronized fun asked(
        conversationId: String,
        newest: Boolean,
    ) {
        if (selected == conversationId) requests += newest
    }

    @Synchronized fun received(
        conversationId: String,
        page: HistoryPage,
    ) {
        if (selected == conversationId) {
            pages++
            receipts += page.entries.map { it.unsignedId }.sorted()
        }
    }

    @Synchronized fun record(
        conversationId: String,
        rows: List<ThreadItem>,
    ) {
        if (selected == conversationId) observed = rows
    }

    @Synchronized fun asks(): List<Boolean> = requests.toList()

    @Synchronized fun completed(): Int = pages

    @Synchronized fun pageReceipts(): List<List<ULong>> = receipts.toList()

    @Synchronized fun rows(): List<ThreadItem> = observed.toList()
}
