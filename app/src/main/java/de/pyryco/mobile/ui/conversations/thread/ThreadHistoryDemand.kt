package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.ThreadItem

/** Exact unsigned demand anchor with its logical history key and prepared display target. */
data class ThreadHistoryMarker(
    val beforeRow: String,
    val unsignedAnchor: ULong,
    /** Prepared display-only target; compatibility markers resolve through [beforeRow]. */
    val displayRow: ThreadItem? = null,
) {
    constructor(anchor: Long, beforeRow: String) : this(
        beforeRow,
        anchor.also { require(it >= 0) { "invalid signed history anchor" } }.toULong(),
    )

    /** Lower-range compatibility only; upper ids must be read through [unsignedAnchor]. */
    val anchor: Long
        get() {
            check(unsignedAnchor <= Long.MAX_VALUE.toULong()) { "unsigned history anchor requires unsigned consumer" }
            return unsignedAnchor.toLong()
        }
}

/**
 * What the thread's single oldest-end slot shows (#778) — one slot, five states, never two rows.
 *
 * The walk's *termination* reasons deliberately do not reach the screen, only its failures: the screen
 * asks, the ViewModel decides whether the ask is honoured, and a second copy of that decision in Compose
 * would be a second place to get it wrong.
 */
enum class ThreadHistoryTail {
    /** Nothing at the oldest end: walking, or ended normally. */
    None,

    /** A page is in flight. */
    Loading,

    /** The page failed retryably — the reader can ask for it again. */
    Retry,

    /** The page failed permanently — visible, with nothing to press. */
    DeadEnd,

    /** The host is not connected and the walk has not reached the start of history (#1352). */
    Offline,
}
