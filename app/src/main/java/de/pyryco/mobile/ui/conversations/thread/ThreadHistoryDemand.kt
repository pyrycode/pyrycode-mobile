package de.pyryco.mobile.ui.conversations.thread

/**
 * How many pages one screen-open may walk before the client stops asking (#777).
 *
 * The **load-bearing** bound against a deliberately adversarial peer. The [HistoryWalkStop.NotAdvancing]
 * guard below is only an honest-bug guard: a daemon alternating between two cursor values defeats it
 * while still answering `atStart = false` forever. This cap does not care what the daemon says — it is
 * counted client-side, so the worst case is a fixed number of sequential round trips and then a terminal
 * stop.
 *
 * At the fake's page size that is 2000 rows of scroll-back, past any plausible reader. The count is per
 * walk (so per [ThreadViewModel], so per screen-open): leaving and re-entering a thread starts a fresh
 * walk from the newest page. That reset costs a deliberate human gesture, and a per-screen walk is the
 * right scope for a per-screen cap — a process-scoped counter would leak walk state across conversations
 * for no attacker-relevant gain.
 */
internal const val MAX_HISTORY_PAGES = 100

/**
 * Why a history walk stopped, or `null` while it is still walking (#777).
 *
 * An enum rather than a `Boolean` so the retryable split [#778] adds can reopen exactly one member:
 * [Failed] is the recoverable one, and [AtStart] / [NotAdvancing] / [PageCap] are terminal. A
 * `stopped: Boolean` would have designed that split shut.
 */
internal enum class HistoryWalkStop {
    /** The daemon reported the start of the log — the only termination signal the wire has. */
    AtStart,

    /** The daemon answered `atStart = false` with a cursor that cannot be walked from. */
    NotAdvancing,

    /** [MAX_HISTORY_PAGES] reached. */
    PageCap,

    /** The ask failed. Recoverable in principle — #778 owns the retry. */
    Failed,
}

/**
 * One conversation's backward history walk, as a value (#777) — cursor, termination, in-flight and the
 * stop reason in one place, with the ask / settle / fail transitions testable without a ViewModel.
 *
 * It holds **no** [de.pyryco.mobile.data.repository.HistoryPage] and this file does not import one, so no
 * daemon-authored [de.pyryco.mobile.data.repository.HistoryEntry] can structurally reach the walk's
 * state. [settled] takes the page's two scalars instead; that narrowing is deliberate, not incidental.
 *
 * @param cursor The position to ask with next — the daemon's own opaque value, echoed **verbatim** and
 *   never parsed, rebuilt, logged, or used as a path, URL, filename or cache key. Empty means "start at
 *   the newest", which is the normal opening value of a walk and not a missing one.
 * @param pagesLoaded How many pages have settled, counting toward [MAX_HISTORY_PAGES].
 * @param inFlight A request is outstanding. Exactly one may be, per conversation — see [canAsk].
 * @param stoppedBy Why the walk ended, or `null` while it is still walking.
 */
internal data class ThreadHistoryDemand(
    val cursor: String = "",
    val pagesLoaded: Int = 0,
    val inFlight: Boolean = false,
    val stoppedBy: HistoryWalkStop? = null,
) {
    /**
     * Whether an ask may be issued now. The one place the "one outstanding request per conversation, and
     * an ask arriving during a request is **dropped** rather than queued" rule lives — there is no queue
     * anywhere in this walk, by design.
     */
    val canAsk: Boolean get() = !inFlight && stoppedBy == null

    /**
     * Claim the outstanding-request slot. The cursor is unchanged, so the caller reads the returned
     * value's [cursor] as the one to ask with.
     */
    fun asking(): ThreadHistoryDemand = copy(inFlight = true)

    /**
     * Fold the answer to the outstanding ask, stopping the walk when any of the three rules fires.
     *
     * Order matters: [atStart] is checked **first**, because the wire leaves [pageCursor] empty whenever
     * it is true, and an empty cursor read first would misreport the normal end of a log as
     * [HistoryWalkStop.NotAdvancing].
     *
     * A short or empty page is **not** a stop: the entries count says nothing about the end of the log
     * (only [atStart] does), which is why this function never sees an entry count at all.
     */
    fun settled(
        pageCursor: String,
        atStart: Boolean,
    ): ThreadHistoryDemand {
        val loaded = pagesLoaded + 1
        return ThreadHistoryDemand(
            cursor = pageCursor,
            pagesLoaded = loaded,
            inFlight = false,
            stoppedBy =
                when {
                    atStart -> HistoryWalkStop.AtStart
                    pageCursor.isEmpty() || pageCursor == cursor -> HistoryWalkStop.NotAdvancing
                    loaded >= MAX_HISTORY_PAGES -> HistoryWalkStop.PageCap
                    else -> null
                },
        )
    }

    /**
     * Fold a failed ask: clear the in-flight state so the loading affordance can never be left stuck,
     * keep [cursor] and [pagesLoaded] exactly as they were so every loaded row and the walk's position
     * survive, and stop asking. This slice ships no retry — #778 owns recovery, and reopening
     * [HistoryWalkStop.Failed] is how it will do it.
     */
    fun failed(): ThreadHistoryDemand = copy(inFlight = false, stoppedBy = HistoryWalkStop.Failed)
}
