package de.pyryco.mobile.ui.conversations.thread

/**
 * What the thread's single oldest-end slot shows — one slot, five states, never two rows.
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
