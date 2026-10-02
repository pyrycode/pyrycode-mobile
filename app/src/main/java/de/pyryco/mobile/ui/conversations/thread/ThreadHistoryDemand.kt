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
 * walk (so per [ThreadViewModel], so per screen-open): re-entering a thread continues from its saved
 * position (#1354) with a fresh count, because only the position is saved. Every page costs a deliberate
 * human gesture since #1352, and a per-screen walk is the right scope for a per-screen cap — a
 * process-scoped counter would leak walk state across conversations for no attacker-relevant gain.
 */
internal const val MAX_HISTORY_PAGES = 100

/**
 * Why a history walk stopped, or `null` while it is still walking (#777, split by #778).
 *
 * An enum rather than a `Boolean` so the retryable split could reopen exactly one member, which is what
 * #778 did: [RetryableFailure] is the one Retry recovers. [AtStart] / [NotAdvancing] / [PageCap] are
 * terminal; since #1352 both failures let a fresh gesture ask again. A `stopped: Boolean` would have
 * designed that split shut.
 *
 * Only the two failure members reach the screen (see [ThreadHistoryDemand.tail]). Reaching the start of a
 * log is not a failure and shows nothing.
 */
internal enum class HistoryWalkStop {
    /** The daemon reported the start of the log — the only termination signal the wire has. */
    AtStart,

    /** The daemon answered `atStart = false` with a cursor that cannot be walked from. */
    NotAdvancing,

    /** [MAX_HISTORY_PAGES] reached. */
    PageCap,

    /**
     * The ask failed with a retryable code. `history.unavailable` is the contract's **only** retryable
     * member; a retry resumes from the same cursor and loads the page that failed (#778).
     */
    RetryableFailure,

    /**
     * The ask failed permanently — the non-retryable `history.*` codes, an unknown conversation id, a
     * closed session, a malformed page. Visible to the reader with nothing to press; a fresh gesture
     * asks again from the same cursor (#1352).
     */
    PermanentFailure,
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
 *   the newest", which is the normal opening value of a walk and not a missing one. It survives a
 *   reconnect, because it names a position in the daemon's append-only log rather than in a connection
 *   (#1352).
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
     *
     * Every ask is a user gesture (#1352), so a failed walk may be asked again: the failure stays visible
     * until then, and the reader stops retrying by not asking. Only the terminal stops refuse.
     */
    val canAsk: Boolean
        get() =
            !inFlight &&
                (
                    stoppedBy == null ||
                        stoppedBy == HistoryWalkStop.RetryableFailure ||
                        stoppedBy == HistoryWalkStop.PermanentFailure
                )

    /** Whether the reader may retry the page that failed (#778) — the whole of the retry affordance's gate. */
    val canRetry: Boolean get() = !inFlight && stoppedBy == HistoryWalkStop.RetryableFailure

    /**
     * Claim the outstanding-request slot, clearing a failure stop. The cursor and [pagesLoaded] are
     * unchanged, so the caller reads the returned value's [cursor] as the one to ask with, and an ask
     * after a failure — a gesture or the Retry press (#778) — loads the page that failed.
     */
    fun asking(): ThreadHistoryDemand = copy(inFlight = true, stoppedBy = null)

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
        return copy(
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
     * survive, and record the failure for the oldest-end slot.
     *
     * @param retryable The contract's own split, taken verbatim from
     *   [de.pyryco.mobile.data.network.RelayErrorException.retryable] — `history.unavailable` is its only
     *   retryable member. A hostile daemon can set it on any failure; the worst that buys it is a retry
     *   row that never succeeds, at one human-gated round trip per press and no budget spent.
     */
    fun failed(retryable: Boolean): ThreadHistoryDemand =
        copy(
            inFlight = false,
            stoppedBy = if (retryable) HistoryWalkStop.RetryableFailure else HistoryWalkStop.PermanentFailure,
        )

    /**
     * Resume from the position saved when the thread was last open (#1354): ask next with [cursor], and stop
     * as [HistoryWalkStop.AtStart] when the saved walk had reached the start of history, so a pull asks
     * nothing and the offline notice stays hidden. Takes the saved position's two scalars, for the same
     * reason [settled] does. [pagesLoaded] is carried, so restoring never resets the [MAX_HISTORY_PAGES]
     * budget.
     */
    fun restored(
        cursor: String,
        atStart: Boolean,
    ): ThreadHistoryDemand = copy(cursor = cursor, stoppedBy = if (atStart) HistoryWalkStop.AtStart else stoppedBy)

    /**
     * Fold a refused cursor (#1352): the daemon answered `history.invalid_cursor`, so the next ask starts
     * again from the newest page. Nothing asks by itself — the slot is released, and the next qualifying
     * gesture carries the empty cursor. [pagesLoaded] is carried, so a daemon refusing every cursor
     * cannot buy a fresh budget.
     */
    fun cursorRefused(): ThreadHistoryDemand = copy(cursor = "", inFlight = false, stoppedBy = null)

    /**
     * What the thread's oldest-end slot shows for this state (#778). While the host is not [connected]
     * the slot says older messages need a connection (#1352), unless this walk has reached the start of
     * history, as desktop's `olderSaved` notice does.
     */
    fun tail(connected: Boolean): ThreadHistoryTail =
        when {
            !connected -> if (stoppedBy == HistoryWalkStop.AtStart) ThreadHistoryTail.None else ThreadHistoryTail.Offline
            inFlight -> ThreadHistoryTail.Loading
            stoppedBy == HistoryWalkStop.RetryableFailure -> ThreadHistoryTail.Retry
            stoppedBy == HistoryWalkStop.PermanentFailure -> ThreadHistoryTail.DeadEnd
            else -> ThreadHistoryTail.None
        }
}
