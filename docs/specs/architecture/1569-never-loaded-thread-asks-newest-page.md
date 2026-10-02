# #1569 — A never-loaded thread asks for its newest history page once

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `historySeed`,
  `hostAvailable`, `onDemandOlderHistory`, `launchHistoryAsk`, `claimHistorySlot` and the `init` block's
  #1352 comment. The only production file this ticket changes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryDemand.kt`: `canAsk`,
  `asking`, the pristine default value. Read only.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `readHistoryPosition`
  defaults to `null`, `requestHistory` defaults to an `IllegalStateException`. Read only.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`: the #777/#1352
  and #1354 history tests and the `HistoryRepo` double.

Overlapping in-flight branches #1497 and #1561 edit `ThreadViewModel.kt` outside the history walk; edits
here stay additive.

## Design source

https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8. No visual change: the loaded rows and
the oldest-end slot render exactly as they already do after a pull.

## Change

`historySeed` today restores a saved position and otherwise does nothing. It now also records whether a
position existed. A new collector in `init` waits for `historySeed`, and when no position was saved it
waits for the first `hostAvailable == true` and then asks once through `claimHistorySlot`, with a claim
rule that only admits the pristine walk (`ThreadHistoryDemand()`: no page loaded, nothing in flight, no
stop). The ask goes through `launchHistoryAsk`, so the page's position is saved and a failure lands in
the existing Retry/DeadEnd/Offline tail. Because the claim needs the pristine walk, a reader's pull that
got there first makes the opening ask a no-op instead of asking a second, older page, and the collector
takes only the first available edge, so no reconnect or arriving page asks. A thread with a saved
position never reaches the collector's ask, keeping #1352's pull-only rule. The `init` comment and the
`onDemandOlderHistory` KDoc are updated to state the exception.

## Testing strategy

In `ThreadViewModelTest`:

- `history_openingAThread_asksNothing` is replaced by a test that a never-loaded thread on a connected
  host asks `""` once at open, its rows render, and the page's position is saved.
- New: a never-loaded thread opened offline asks nothing until the host becomes available, then asks
  once; a later drop and return asks nothing more.
- `history_withASavedPosition_theFirstPullAsksWithTheSavedCursorAndOpeningAsksNothing` stays as is.
- Existing #777/#1352 tests that count asks from a pristine walk are adjusted for the opening ask, either
  by counting it or by giving the double a saved non-terminal position where the test is about the pull
  rule rather than the open.

Focused run: `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.ui.conversations.thread.*"`.
The rung-3 live scenario is #1571, out of scope here.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md` § "The oldest-end history
  demand (#777)": opening no longer always asks nothing; a never-loaded thread asks the newest page once.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md` §
  "Resuming from the saved position (#1354)": record the never-loaded exception and why (a dormant
  channel created on another client opened empty, #1569).
