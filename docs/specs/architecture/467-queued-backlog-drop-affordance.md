# Spec — #467 feat(ui): drop affordance on the queued backlog

Phase 3 of the queued-message backlog (epic pyrycode#597). Adds the **user-facing drop action** on top of
the data path that already exists:

- **#460** decodes the daemon's `queue_state` broadcast into `ConversationRepository.observeQueue(id): Flow<List<QueuedMessage>>`.
- **#461** renders that backlog as ordered de-emphasized rows in the thread (`QueuedBacklog` composable, hoisted on `ThreadUiState.queuedMessages`).
- **#466** (merged) added the data-layer send `ConversationRepository.dropQueuedMessage(conversationId, queuedMessageId: Long)` — dispatches the `dequeue_message` wire frame, resolves against request↔ack correlation, reachable through the `StableConversationRepository` facade.

This slice wires a per-row drop affordance → a thread-ViewModel event → that already-built repository method. The
list update is **free**: it rides the existing `observeQueue` render path with **no optimistic mutation** — the
dropped row disappears only when the daemon broadcasts the next `queue_state`.

> **Blocker cleared.** The ticket was natively `blockedBy #458` (interrupt-send) to serialize edits to
> `ThreadViewModel.kt` + `ThreadViewModelTest.kt`. #458 is merged (PR #469, commit `90670be` is an ancestor of
> this branch's HEAD); this spec is designed against the merged ViewModel. The §1.5 branch-overlap re-check
> found **no** in-flight branch touching any of this slice's files.

---

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:473-505` — `onInterrupt()` / `sendInterrupt()`: the **exact structural twin** for a fire-and-forget swallow-on-failure send (CancellationException rethrow first, then swallow `RelayErrorException` + `IllegalStateException`, no state mutation). Mirror it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:379-384` — `sendMessage()`: how a VM action reaches the **injected `repository`** directly (drop is a facade method, *not* an injected lambda like interrupt/modal — no new constructor param).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:200-254` — the `threadContent` / `state` combine: `queuedMessages` is a pure projection of `observeQueue`; the drop does **not** touch it.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:163-187` — `dropQueuedMessage` contract: `Long` id (the pyrycode#720 `uint64` trap), throws `RelayErrorException` (server error) / `IllegalStateException` (not connected) / `IllegalArgumentException` (unknown id); **no projection side effect**.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:222-233` — `QueuedMessage(id: Long, text, timestamp)`: `id` is the verbatim value to pass back to `dropQueuedMessage`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/QueuedBacklog.kt` — the #461 component to extend: `QueuedBacklog(queued, modifier)` + private `QueuedMessageRow(text, modifier)`; note `QUEUED_ALPHA`, the merged-descendants semantics on the Column (line 86), and the two previews (light/dark) that call `QueuedBacklog`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:92-115` — the stateless screen's defaulted-callback param block; `:266` — the single `QueuedBacklog(queued = state.queuedMessages, …)` call site. New `onDropQueued` param goes here (defaulted, so the 5 internal `ThreadScreen(` previews stay untouched).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:360-379` — the live `ThreadScreen(...)` wiring; `onModalOption = vm::onModalOption` (line 371) is the precedent for the one-line `onDropQueued = vm::onDropQueued` addition.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:2170-2185` — `QueueControllableRepo` (delegates to `FakeConversationRepository`, overrides `observeQueue` with a `MutableStateFlow`): extend it to also record/throw `dropQueuedMessage`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:586-676` — `onInterrupt` send/swallow/cancellation tests: the AC#4 / AC#2 test shapes to mirror (note the `Thread.setDefaultUncaughtExceptionHandler` capture — a swallow test that asserts on call-count alone false-greens).
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/QueuedBacklogTest.kt` — the #461 Compose test to extend for the affordance-tap; note the `useUnmergedTree = true` idiom for reading inside the merged-descendants Column.
- `app/src/main/res/values/strings.xml:58-59` — existing `thread_queued_backlog_label` / `cd_thread_queued_backlog`; add `cd_thread_queued_drop` here.

---

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

**Design-owed — N/A for a literal visual transcription.** The Conversation Thread frame (`16-8`) draws the
message thread, tool rows, session delimiter and input bar, but **no queued-backlog section and no drop
affordance** (confirmed by reading the node + screenshot). The drop affordance is not yet drawn; per the
ticket, the visual was owed "at or before architect stage" and has not landed. The backlog render (#461)
already shipped a **Material 3 default** treatment in the absence of a drawn design (de-emphasized user-side
bubble + leading `Schedule` glyph), exactly as `ThinkingIndicator` / `StallPromotionBanner` did. This slice
follows the same posture: the drop affordance is a **trailing `IconButton` with `Icons.Outlined.Close`**, tinted
`onSurfaceVariant`, on each row — the conventional Material "remove from a list" affordance — until the visual
lands. No new color/typography tokens. Code-review's visual-fidelity check is intentionally a Material-default
check here, not a pixel-match against `16-8`.

---

## Design

### Data flow

```
QueuedBacklog row  ──onDrop(id: Long)──▶  ThreadScreen.onDropQueued  ──(MainActivity vm::onDropQueued)──▶
    ThreadViewModel.onDropQueued(id)  ──viewModelScope.launch──▶  repository.dropQueuedMessage(conversationId, id)
                                                                          │
                          (no local state mutation; fire-and-forget)     ▼
   next queue_state broadcast ──▶ observeQueue ──▶ ThreadUiState.queuedMessages ──▶ row disappears
```

The drop is a one-way trigger. The disappearance is a **separate, server-driven** event that rides the
already-shipped #460/#461 render path — there is no coupling between the two in the VM.

### 1. `QueuedBacklog.kt` — per-row affordance

- `QueuedBacklog` gains one param: `onDrop: (Long) -> Unit` (after `queued`, before/around `modifier`).
- `QueuedMessageRow` gains `id: Long` and `onDrop: (Long) -> Unit`; the `queued.forEach { entry -> QueuedMessageRow(id = entry.id, text = entry.text, onDrop = onDrop) }` call passes `entry.id` (Long).
- The row renders a **trailing** `IconButton(onClick = { onDrop(id) })` with `Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_thread_queued_drop), tint = onSurfaceVariant)`. Placement: after the bubble, at the trailing (End) edge of the existing `Row(horizontalArrangement = Arrangement.End)`.
- The two `@Preview` composables pass `onDrop = {}`.

**Semantics / a11y note (load-bearing for the test).** The Column sets `semantics(mergeDescendants = true)`
for the section's group description. A clickable `IconButton` forms its **own** semantics node and is *not*
absorbed by the ancestor merge, so it stays individually addressable in the **unmerged** tree by
`cd_thread_queued_drop` — the same `useUnmergedTree = true` access the existing `QueuedBacklogTest` uses for
per-row text. The decorative leading `Schedule` glyph keeps `contentDescription = null`. The row's
`.alpha(QUEUED_ALPHA)` dims the whole row; the drop button stays interactive while dimmed (acceptable; see Open
questions if affordance contrast reads too weak).

### 2. `ThreadViewModel.kt` — event handler

Add one public handler mirroring `onInterrupt`/`sendInterrupt` (§ Error handling for the catch contract). Drop
is reachable as a **facade interface method** on the already-injected `repository`, so — unlike
`interrupt`/`answerModal`/`cancelModal` — there is **no new constructor parameter**:

```kotlin
/** Drop queued message [queuedMessageId] from this conversation's backlog (#467) — fire the #466
 *  dequeue send via the facade. No optimistic removal: the row leaves only on the next queue_state
 *  (observeQueue); this holds no state to roll back. Failure is inert (AC #4). */
fun onDropQueued(queuedMessageId: Long) { /* viewModelScope.launch { try { repository.dropQueuedMessage(conversationId, queuedMessageId) } … } */ }
```

- Uses the VM's own `conversationId` (the private field, line 131) — **never** a caller-supplied conversation id (the screen passes only the row's `Long` queued-message id).
- **No mutation of `state.queuedMessages`** (AC#3). The VM's only effect is the wire send.

### 3. `ThreadScreen.kt` — pass-through

- Add `onDropQueued: (Long) -> Unit = {}` to the param block (defaulted → the 5 internal previews + the existing `QueuedBacklogTest` `setContent` stay compiling unchanged).
- At the call site (`:266`): `QueuedBacklog(queued = state.queuedMessages, onDrop = onDropQueued, modifier = Modifier.fillMaxWidth())`.

### 4. `MainActivity.kt` — live wiring

- One line in the `ThreadScreen(...)` block (~line 372): `onDropQueued = vm::onDropQueued,`. Without it the live affordance defaults to a no-op (AC#2 requires the production path to reach the VM).

### 5. `strings.xml`

- Add `<string name="cd_thread_queued_drop">Drop this queued message</string>` (content description for the per-row affordance; wording is the developer's to match repo a11y copy).

---

## State + concurrency model

- No new `StateFlow`. `queuedMessages` stays a pure projection of `observeQueue` inside the existing five-arm
  `state` combine — untouched by this slice.
- The drop runs on `viewModelScope.launch` (Main-immediate via the repo's own dispatching, same as `sendMessage`
  / `sendInterrupt`); it is a `suspend` one-shot that returns `Unit` on success.
- **Shutdown:** on screen exit `viewModelScope` cancels; an in-flight drop send throws `CancellationException`,
  which **must** be rethrown ahead of the typed catches (see Error handling). No retained job, no leak.
- **No optimistic state.** There is deliberately nothing to cancel-and-roll-back: the backlog is server-owned.

---

## Error handling

Mirror `sendInterrupt` **exactly** (`ThreadViewModel.kt:493-505`):

| Throw | Source | Handling |
|---|---|---|
| `CancellationException` | `viewModelScope` teardown mid-send | **Rethrow first** — on the JVM `kotlinx.coroutines.CancellationException` extends `IllegalStateException`, so a bare `catch (IllegalStateException)` would swallow structured cancellation. This catch arm **must precede** the typed ones. |
| `RelayErrorException` | server `error` reply (e.g. stale/already-drained id, `conversation.not_found` surfaced generically) | Swallow — inert (AC#4). |
| `IllegalStateException` | not-connected / pre-Open session | Swallow — inert (AC#4: "no live connection … leaves the backlog unchanged and does not crash"). |

- **No error channel, no snackbar, no log.** Unlike the modal send (#451, which surfaces `modalSendErrors`), a
  failed drop is a **silent no-op** — AC#4 asks only that it not crash and not mutate the backlog. Any future
  user-visible failure surface is out of scope.
- `IllegalArgumentException` (unknown conversation id) is **not** caught: the VM always passes its own valid
  `conversationId`, so it cannot occur on the real path — same posture as `sendInterrupt` (which also does not
  catch it). Do not add a catch for a failure mode that cannot be reached.

---

## Testing strategy

Two layers, mirroring the #451/#452 split (behaviour ⇒ VM unit test; affordance ⇒ Compose screen test). The
ACs map cleanly across them.

### A. `QueuedBacklogTest.kt` (androidTest / Compose) — the affordance (AC#1, AC#2 UI half, AC#5 "activate" half)

- **Each row exposes a drop affordance.** Render a 2-row queue; assert two nodes with `cd_thread_queued_drop`
  exist (`onAllNodesWithContentDescription(dropCd, useUnmergedTree = true).assertCountEquals(2)`).
- **Activating row N's affordance fires `onDrop` with row N's id.** Pass an `onDrop` recorder; `performClick()`
  the affordance at index 1; assert the recorder captured the **second** row's `Long` id (not the first) —
  proves per-row id wiring, not just "a button exists." Use `useUnmergedTree = true` (the clickable is its own
  semantics node inside the merged Column).
- Keep the existing #461 tests green (the new `onDrop` param is required on `QueuedBacklog` but the
  `ThreadScreen`-level `onDropQueued` is defaulted, so `setThreadScreen` needs no change).

### B. `ThreadViewModelTest.kt` (unit / `runTest`) — routing, no-optimistic-removal, swallow (AC#2 VM half, AC#3, AC#4, AC#5 "disappears" half)

Extend `QueueControllableRepo` (`:2175`) to also record `dropQueuedMessage(conversationId, queuedMessageId)`
calls and optionally throw a supplied exception after recording — the analog of the `InterruptRecorder`
(`:1976`), but on the repo double since drop is a facade method.

- **Routes verbatim (AC#2):** `vm.onDropQueued(42L)` ⇒ the recorder captured exactly one
  `(conversationId, 42L)` call with the VM's own conversation id and the id passed through unchanged.
- **No optimistic removal (AC#3 + AC#5 "disappears"):** seed the controllable `queue` flow with two rows;
  collect `state.queuedMessages`; call `onDropQueued(firstId)`; assert `state.queuedMessages` is **still both
  rows** (the VM did not mutate it). Then push a new `queue_state` (the one-row list without `firstId`) onto the
  flow; assert `state.queuedMessages` now drops to that row — the disappearance is the broadcast's effect, not
  the drop's.
- **Failure is inert (AC#4):** configure the double to throw `IllegalStateException` (not connected) and
  `RelayErrorException` (server error) after recording; `onDropQueued(...)` makes the attempt, the throw never
  escapes, the screen does not crash, and `state.queuedMessages` is unchanged. **Mirror
  `onInterrupt_whenSendFailsInert_isSwallowedWithoutCrashing` (`:608`)** — capture with
  `Thread.setDefaultUncaughtExceptionHandler` and assert `uncaught.isEmpty()`; asserting only on call-count
  false-greens (the throw would propagate to the test's uncaught handler).
- **Cancellation propagates (structured-cancellation guard):** mirror
  `onInterrupt_scopeCancellationMidSend_propagatesCancellationInert` (`:650`) — a drop that suspends mid-send,
  then `store.clear()` cancels `viewModelScope`; assert no spurious crash and the `CancellationException` is not
  swallowed (guards the catch-ordering).

**AC#5 coverage note.** AC#5 ("a screen test … activating the affordance triggers the drop, and the row
disappears when the updated `queue_state` arrives") is satisfied across both layers: the *activate-triggers-drop*
half is test A (the Compose screen test), the *row-disappears-on-next-queue_state* half is test B's
no-optimistic-removal test. The disappearance is a pure projection of `observeQueue` (already shipped in
#461) — a single full-real-VM androidTest would re-exercise that projection with heavier machinery for no added
coverage. This is the same behaviour-VM / affordance-screen split #451/#452 used.

Gates: `./gradlew test` (unit, layer B), `./gradlew lint spotlessCheck assembleDebug`, and
`./gradlew compileDebugAndroidTestKotlin` to catch layer-A instrumented-test breaks without a device
(androidTest is **not** compiled by the mandatory gates — see project memory). `connectedAndroidTest` requires
a device.

---

## Open questions

- **Drop-button contrast under `QUEUED_ALPHA` (0.6).** The whole row is dimmed; the drop button inherits it. If
  the design (when `16-8` lands) wants the affordance at full opacity for clearer tap affordance, lift the
  button out of the `.alpha(QUEUED_ALPHA)` scope. Default for this slice: keep it inside the dimmed row
  (simplest, reads as "secondary action on a not-yet-sent row"). Revisit when the visual lands.
- **Confirm-on-drop?** This slice drops immediately on tap (no confirm dialog) — consistent with the
  "no optimistic removal, server is source of truth" model and the ticket's single-affordance framing. If
  product later wants an undo/confirm, that is a separate slice; do not add it here.
- **Icon choice.** `Icons.Outlined.Close` (×) is the Material "remove from list" convention; `Icons.Outlined.Delete`
  (trash) is the alternative. Defaulting to `Close` — a queued message is *un-queued*, not *deleted*. Trivially
  swappable when the visual lands.
