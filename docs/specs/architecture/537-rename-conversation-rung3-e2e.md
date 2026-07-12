# #537 — rung-3 real-claude e2e: rename a conversation → new title durable on top bar + list

**Ticket:** [#537](https://github.com/pyrycode/pyrycode-mobile/issues/537) · **Size:** S · **Not** `security-sensitive`

The **last** of the shipped rung-3 e2e family (#541 / #551 / #554 / #562). Zero production code: one new
always-on `@Test` in `InteractiveStreamE2ETest.kt`, **one** new companion constant, one edit to the
`scripts/e2e-emulator.sh` LIVE `class#method` list (sextet → **septet**), and a scenario entry in
`docs/e2e-interactive-stream.md`. Rename is the **lowest-risk** member: the four shipped siblings already
drive the real `RenameDialog` as a *seeding* step — this ticket promotes it from seeding to the **subject**
of its own scenario, so only the assertion target changes.

## Design source

N/A — test-only ticket. It exercises already-shipped, already-reviewed UI (the #530 `rename` wire, the
overflow-menu "Rename" item, `RenameDialog`) and adds **no production surface**, so there is nothing to
design and the visual-fidelity check is intentionally skipped. The ticket body carries no `## Figma`
section, which is correct here (not a PO gap): this is a test of existing UI, not new UI work. (Same shape
#541 / #554 / #562 established.)

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` — **the clone target.**
  - `interactiveTurn_deleteConversation_removesFromListAndClosesThread` (lines 515–602) — **closest
    structural template.** Its steps 3–6 are *exactly* this scenario's spine (create discussion → open
    overflow → tap "Rename" → `hasSetTextAction() and isFocused()` → `performTextReplacement(uniqueName)` →
    Save → Back → assert the unique name on a recents row). #537 keeps that spine and **drops** steps 7–9
    (the Channel-info → Delete → confirm tail); the rename *is* the subject, not a seed for a later action.
  - `interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace` (lines 842–896) — the **absence-guard →
    inversion** idiom (step 3 `assertCountEquals(0)` on the unique name before the change, step 6 presence
    after) and the *in-thread* assertion surface (it stays in the thread and reads the chip; #537 stays in
    the thread and reads the **top bar** the same way).
  - The `private companion object` (lines 915–1042) — reuse verbatim: `CD_NEW_DISCUSSION`, `CD_SEND_MESSAGE`,
    `CD_BACK`, `CD_MORE_ACTIONS`, `RENAME_ITEM` (line 1000), `RENAME_SAVE` (line 1001), `awaitConnected()`,
    `LIST_TIMEOUT_MS`, `CONNECT_TIMEOUT_MS`, `THREAD_TIMEOUT_MS`. **Only one new constant is needed**
    (`RENAME_NAME_PREFIX`, see § Companion constant). No `PING`/`pingNodeCount` — this scenario spends **no**
    claude turn (see § Cost).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt:57–63` — the **"Rename"**
  item (`thread_overflow_rename`, gated on `mutationsSupported` → present in relay mode after PR #572) fires
  `ThreadEvent.Rename`. The menu closes (`onDismiss()`) **before** the event fires, so only `RenameDialog`
  is up afterward — no menu-behind-dialog collision (contrast #554's sheet-behind-dialog).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:830–836` — `Rename →
  pendingRenameDialog = true` (830); `RenameSubmit → repository.rename(conversationId, name)` inside
  `launchGuardedRepoCall` (831–836). **Load-bearing divergence from delete/archive: there is NO PopBack.**
  The dialog dismisses and the thread stays open → the top bar re-labels in place (see § Design).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:169,322–326` — the top bar
  `title = state.displayName` (line 169) is the **in-thread** assertion surface; `RenameDialog(initialName =
  state.displayName, onSubmit = { onOverflowEvent(ThreadEvent.RenameSubmit(it)) })` is gated on
  `state.showRenameDialog` (322–326).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RenameDialog.kt:31–49,59–68,80–98` — the
  field is **pre-filled with `initialName` and fully selected** (`TextRange(0, len)`, line 43) and
  **auto-focuses** (`focusRequester.requestFocus()`, line 67). This is why the developer targets the field
  with `isFocused()` and **replaces** rather than appends (see § Gotcha). Save button `rename_dialog_save` =
  "Save".
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` (recents rendering) — a
  renamed discussion appears in `RecentDiscussionsSection` as a `DiscussionPreviewRow` whose **name** is a
  `Text` node (`DiscussionPreviewRow.kt:53`), matched by `onAllNodesWithText(uniqueName, substring = true)`.
  Context only, not edited. (The #554 spec documents the top-3 recents cap is never a risk: a just-created
  discussion sorts #1 by `lastUsedAt`, `RemoteConversationRepository.observeConversations` line ~981.)
- `scripts/e2e-emulator.sh:454–487` — the LIVE curation block. Line **468** is the comma-separated
  `class#method` `TEST_TARGET` (a **sextet** today) to append the 7th method to; lines 9–10, 57, 456–458,
  464–466, 485 carry the "sextet / 3 turns" prose to refresh (see § Harness change).
- `docs/e2e-interactive-stream.md` — **read-only while writing the test** (the developer edits it as a
  deliverable): lines 7/48–49 (ladder), 108/137 (What rung 3 is made of), 282/307 (pre-ship gate), 315–317
  (Live mode), 736/765–776 (Follow-ups). Mirror how #562 (the last-merged sibling) was wired in.
- Memory lesson `androidtest-not-compiled-by-mandatory-gates` — `./gradlew test` / `lint` / `assembleDebug`
  do **not** compile `androidTest`. The build gate for this ticket is `./gradlew compileDebugAndroidTestKotlin`.
- Memory lesson `compose-test-and-member-and-rename-field-disambig` — `and` is a `SemanticsMatcher` **member**
  (no import); the RenameDialog-over-thread two-field disambiguation is `hasSetTextAction() and isFocused()`.

## Context

Org policy (2026-07-08): every operator-facing happy-path flow ships with a real-claude e2e that runs in the
pre-ship gate. This is the **rename** scenario, the #530 follow-up filed separately so the wire stayed size S.
Both prerequisites have long landed: **#530** shipped the `rename` data path (`RenameConversationPayloadDto`
+ `TYPE_RENAME_CONVERSATION`, folding `conversation_updated` into local state), and PR #572 flipped
`RemoteConversationRepository.mutationsSupported = true` (`:1457`), so the "Rename" overflow item is reachable
on the live relay path. Rename is **conversation-scoped** — the wire is `{conversation_id, name}`, a
line-for-line mirror of `change_workspace` (#560) and `delete` (#532), with **no session transition** — so it
carries **none** of the session-scoped `currentSessionId == ""` blocker that re-parks the settings e2e (#545).
It is in the clean-buildable camp with the four shipped siblings, all living in `InteractiveStreamE2ETest`.
This is a **test-only** ticket.

## The scenario

Create a scratch discussion, drive the **real** rename affordance to a runtime-unique new title, submit, and
assert the new title appears **durably** on **two surfaces** after the round-trip: the **thread top bar**
(in-thread, reached immediately after submit) and the **conversation list** (reached after popping back). The
target title's absence is asserted **before** the rename (a deterministic before-state), so each presence
assertion is a genuine before → after inversion on the same surface, same unique token.

## Design

### Entry point: the overflow "Rename" item (not the Channel Info sheet)

The ticket permits either the overflow "Rename" or the Channel Info sheet's "Rename" `ActionCell`
(`ChannelInfoSheet.kt:300`). **Drive the overflow item.** It is the maximally de-risked path — all four
shipped siblings drive exactly this item as their seeding step — and it avoids two needless costs of the sheet
path: an extra navigation hop (overflow → "Channel info" → sheet → "Rename") and a redundant "Rename" literal
that collides with the overflow item's `R.string.thread_overflow_rename` text. Because the overflow menu
closes (`onDismiss()`) before firing `ThreadEvent.Rename`, only `RenameDialog` is on screen when the dialog
opens — there is **no** menu-behind-dialog artifact.

### Assert the recorded conversation name — never a session id, never a transient

Rename is conversation-scoped and performs **no session transition**, so the durable post-condition is the
recorded conversation **name**. The assertion targets:

1. **Thread top bar** (`ThreadScreen.kt:169`, `title = state.displayName`) — reached **immediately after
   submit**, in-thread. After `RenameSubmit → repository.rename → conversation_updated` folds into thread
   state, `state.displayName` re-labels in place. **There is no PopBack** (unlike delete/archive), so the
   thread stays open and the top bar is the first durable surface. This is the same in-thread read #562 does
   against the chip — #537 reads the top bar instead.
2. **Conversation list** (`DiscussionPreviewRow` name `Text`) — reached after tapping `CD_BACK`. The
   `conversation_updated` fold also upserts the list projection, so the recents row shows the new name.

Neither surface is a session id or a transient. This is the #545 lesson applied: assert the recorded name on
durable surfaces, do **not** reintroduce a session-scoped dependency.

### The `@Test` — `interactiveTurn_renameConversation_relabelsTopBarAndListRow`

One always-on (not `@Ignore`d) method. Steps as tolerant-assertion scenario bullets — **not** code to paste:

1. **Land on the channel list** — `waitUntil(LIST_TIMEOUT_MS)` for `CD_NEW_DISCUSSION` present.
2. **`awaitConnected()`** — the relay session must be Open before the rename round-trip to the daemon.
3. **Create a discussion** — a regular tap on `CD_NEW_DISCUSSION` → navigate into the thread;
   `waitUntil(THREAD_TIMEOUT_MS)` for `CD_SEND_MESSAGE`. A plain discussion suffices — "Rename" is
   `mutationsSupported`-gated (`true` after PR #572), reachable on it.
4. **Absence guard (deterministic before-state)** — `onAllNodesWithText(uniqueName, substring =
   true).assertCountEquals(0)`. The runtime-unique target title is nowhere on screen yet (the top bar shows
   the server auto-name), so its later appearance is attributable to the rename round-trip. (Same guard as
   #562 step 3.)
5. **Rename to the unique name** — open the overflow (`CD_MORE_ACTIONS`), `waitUntil` for `RENAME_ITEM`, tap
   it; `waitUntil` for `hasSetTextAction() and isFocused()` (the dialog's field, disambiguated from the
   thread composer — see § Gotcha), `performTextReplacement(uniqueName)` on the pre-filled + selected field,
   tap `RENAME_SAVE`.
6. **Top-bar assertion (AC-2, surface #1 — in-thread)** — `waitUntil(THREAD_TIMEOUT_MS)` for
   `onAllNodesWithText(uniqueName, substring = true)` non-empty (this wait spans the rename round-trip:
   `conversation_updated → state.displayName`), then `onFirst().assertIsDisplayed()`. The dialog has already
   left composition by the time the round-trip lands (see § Gotcha, false-match note), so the match is the top
   bar.
7. **List assertion (AC-2, surface #2 — after popping back)** — tap `CD_BACK`; `waitUntil(LIST_TIMEOUT_MS)`
   for `CD_NEW_DISCUSSION` (the list marker → the thread has popped back); then `waitUntil` for
   `onAllNodesWithText(uniqueName, substring = true)` non-empty and `onFirst().assertIsDisplayed()` — the
   renamed discussion's recents row.

**Always-on, not `@Ignore`d.** Both post-conditions are **durable** structural facts (the conversation's
recorded name on two surfaces) — no transient like #482's spinner — so the test belongs in the always-on
gate, matching #481's tool-name row, #541's delimiter, and #554's / #562's inversions.

**No negative control needed.** The assertions key on a runtime-unique token whose absence is asserted first
(step 4) and whose presence is then observed on two surfaces; there is no match-everything to disprove (same
reasoning #482 documents).

### Gotcha — the one thing that will bite a naive clone

**The RenameDialog-over-thread two-field disambiguation.** `RenameDialog` opens **over** the thread, whose
composer (`ThreadInputBar` `BasicTextField`) is also editable, so `onNode(hasSetTextAction())` alone throws
"more than one node". `RenameDialog` **auto-focuses** its field (`focusRequester.requestFocus()`,
`RenameDialog.kt:67`) and the composer never requests focus, so **`hasSetTextAction() and isFocused()`**
selects the dialog's field. Wrap in `waitUntil` so focus has landed. The field is **pre-filled with the
server auto-name and fully selected** (`TextRange(0, len)`, line 43) → use **`performTextReplacement(uniqueName)`**,
not `performTextInput` (whose insert-vs-replace-selection behaviour could leave the auto-name concatenated
with the suffix). `and` is a `SemanticsMatcher` **member** — do **not** `import androidx.compose.ui.test.and`
(it fails to resolve; costs a compile cycle). This is verbatim the selector the four siblings already use for
their rename seed — reuse it.

**False-match note (top-bar assertion).** After Save is tapped, `RenameSubmit` sets `pendingRenameDialog =
false` synchronously → `showRenameDialog` flips false → the dialog (whose field held `uniqueName`) leaves
composition. The top bar still shows the **old** auto-name until the round-trip lands. So the two never hold
`uniqueName` simultaneously: when the step-6 `waitUntil` first sees `uniqueName`, the dialog is already gone
and the match is the top bar. (#554 step 4 already used exactly this "top bar shows uniqueName" wait as its
intermediate round-trip confirmation.)

### Companion constant

Add exactly **one** new constant (mirror the sibling naming — #554 `CONVERSATION_NAME_PREFIX = "e2e554-"`,
#551 `ARCHIVE_NAME_PREFIX = "e2e551-"`, #562 `WORKSPACE_FOLDER_PREFIX = "e2e562-"`):

```kotlin
// #537 rename-conversation scenario. Runtime-unique rename target: "e2e537-" + System.currentTimeMillis().
// Distinct prefix (the shared companion forbids redeclaration; each scenario owns its own). Unique so a
// substring match cannot pre-exist on screen — the absence guard (step 4) and its inversions on the top bar
// (step 6) and the list row (step 7) are all genuine; also keeps repeated LIVE gate runs green (no collision
// with titles left by prior runs) and does not collide as a substring with top-bar / list chrome.
const val RENAME_NAME_PREFIX = "e2e537-"
```

`RENAME_ITEM` ("Rename"), `RENAME_SAVE` ("Save"), `CD_NEW_DISCUSSION`, `CD_SEND_MESSAGE`, `CD_BACK`,
`CD_MORE_ACTIONS`, and the timeout constants already exist — reuse them.

### Cost — zero claude turns

Create-discussion and rename are **daemon round-trips, not claude turns**, and the durable identity is the
typed name, which needs no live session content to be identifiable, so this scenario spends **no** claude
turn — there is no `PING_PROMPT`. It still rides the real rung-3 stack (real relay, real daemon) and catches
a broken `rename` wire against the production relay, exactly the pre-ship failure class the gate exists for.
The LIVE gate therefore goes from a **sextet (6 methods)** to a **septet (7 methods)** at **still 3 turns** —
rename adds a method, not a turn (the same "spends none" note #554 / #562 make).

## State + concurrency model

None new. The test drives the shipped MVI surface through Compose/Espresso synchronization (`waitUntil` +
`assertIsDisplayed` / `assertCountEquals`) and reads the relay connection via the existing `awaitConnected()`
`ConnectionStateSource` helper. No coroutine, dispatcher, or `StateFlow` is introduced. The rename path
(`RenameSubmit → repository.rename → conversation_updated` fold → `state.displayName` + list projection
re-emit) is the canonical shipped #530 flow — the e2e proves it against a real daemon, exercising nothing the
Fake synthesizes.

## Error handling

Test-side only: every wait is `waitUntil(<generous timeout>)`, which throws `ComposeTimeoutException` on a
genuinely stuck flow — the correct red signal. `repository.rename` runs inside `launchGuardedRepoCall`, which
swallows a failed wire silently; the failure then surfaces as the step-6 top-bar wait timing out (the top bar
never re-labels), which is the intended red. No new production error paths. Real-claude variance is not a
factor (no claude turn); the daemon round-trip is fast, so `THREAD_TIMEOUT_MS` / `LIST_TIMEOUT_MS = 30_000L`
are ample. All assertions are tolerant (substring, presence/absence, generous timeouts) per the
`docs/e2e-interactive-stream.md` Constraints.

## Testing strategy

Instrumented (`androidTest`), run by `scripts/e2e-emulator.sh` on a headless emulator against a host `pyry`
+ relay (rung 3), and by the `LIVE=1` pre-ship gate (`scripts/e2e-preship-gate.sh`) against the production
relay. **Not** covered by `./gradlew test` (unit). Developer verification:

- **Compiles:** `./gradlew compileDebugAndroidTestKotlin` — the only gate that compiles `androidTest`
  (`test`/`lint`/`assembleDebug` skip it), so the only way to catch a bad selector import before a device run.
  Matchers used (`hasSetTextAction`, `isFocused`, `performTextReplacement`, `onNodeWithText`,
  `onAllNodesWithText`, `assertCountEquals`, `assertIsDisplayed`) are already imported by the sibling
  scenarios; `and` is a member (no import). Let the compile gate resolve the final import set.
- **Script sanity:** `bash -n scripts/e2e-emulator.sh`, and eyeball that the comma-separated `class` arg
  (now 7 `class#method` tokens) is a single unbroken shell token.
- **Green in the gate (AC-4):** `bash scripts/e2e-preship-gate.sh` — an operator-run, real-stack check that
  now executes seven curated methods. That green LIVE run is the operator's pre-ship confirmation, not
  something the developer's turn budget must reproduce headlessly.

## `scripts/e2e-emulator.sh` change (LIVE sextet → septet)

Append the new method to the LIVE `TEST_TARGET` comma-separated `class#method` list (line **468**), so the
gate runs a **septet**: ping + create-workspace-folder + new-session + delete + archive-restore +
change-workspace + **rename**. Contract (shape, not a paste — append to the existing single-line value):

```sh
# scripts/e2e-emulator.sh, LIVE branch (line 468) — append the 7th method:
…,${TEST_CLASS}#interactiveTurn_renameConversation_relabelsTopBarAndListRow"
```

The #481 tool-use test stays LIVE-excluded for cost — this is **additive**. Refresh the "sextet / 3 turns"
prose to "**septet** / 7 methods, **still 3 turns** (rename spends none — create/rename are daemon
round-trips)": header lines 9–10 and 57, run-block comment lines 456–458 and 464–466, and the PASS log line
485. Grep to find every spot so none drifts:

```bash
grep -n 'sextet\|6 methods\|change-workspace = 6\|still 3 turns' scripts/e2e-emulator.sh docs/e2e-interactive-stream.md
```

## Doc note (`docs/e2e-interactive-stream.md`)

Mirror how #562 (the last-merged sibling) was wired in: add a rename scenario to the rung-3 scenario list
(§ "What rung 3 is made of", ~line 108, near the #530-rename-wire mention at ~137), the ladder entry
(~7 / 48–49), the Live-mode "What it runs" (~315–317), the pre-ship gate section (~282 / 307), and add a
Follow-ups entry (~765–776) mirroring the "change-workspace — **shipped (#562)**" line ("rename —
**shipped (#537)**, driven end to end … taking the gate from a sextet to a septet at still 3 turns"). Key
facts to state: the load-bearing signals are the two **durable** post-conditions (the unique title on the
**thread top bar** in-thread and on the **conversation-list** recents row — both genuine inversions of the
step-4 absence guard); always-on (durable, unlike the `@Ignore`d spinner); rename is **conversation-scoped**
with **no session transition**, so the assertion is the recorded **name**, never a session id; and total
real-claude cost is **zero** turns, so the LIVE gate stays at 3 turns across 7 curated methods.

## Open questions / first-run assumptions (grounded, unverified end to end)

- **`rename` propagates to `state.displayName` before step 6.** The rename reply (`conversation_updated`)
  folds into thread state → the top bar re-labels. The step-6 `waitUntil` covers the round-trip; if the top
  bar still shows the auto-name, that is a rename-wire finding, not a reason to weaken the test.
- **`rename` propagates to the list row before step 7.** The same fold upserts the list projection →
  `observeConversations` re-emits with the new name. The step-7 `waitUntil` covers it.
- **`CD_BACK` returns to the channel list.** The thread was entered from the list (step 3), so `PopBack`
  returns there. Confirm on first run the list — not some other back-stack entry — is what renders (step 7
  keys on `CD_NEW_DISCUSSION`, the list marker).
- **The dialog leaves composition before the round-trip lands (top-bar false-match).** See § Gotcha. If a
  first run shows a transient double-match on `uniqueName`, add a `waitUntil` that the dialog field is gone
  (`hasSetTextAction() and isFocused()` count == 0) before the top-bar `assertIsDisplayed` — rung 3 permits
  selector tuning. The invariant: the assertion targets the top bar, never the dismissing field.

## Acceptance criteria (developer deliverable)

- [ ] New always-on `@Test` in `InteractiveStreamE2ETest` (rung 3) creates a scratch discussion, drives the
      real overflow **"Rename"** affordance → `RenameDialog` to a runtime-unique new title
      (`hasSetTextAction() and isFocused()` + `performTextReplacement`), submits, and completes the
      `rename_conversation` daemon round-trip.
- [ ] It asserts the new title **durably** on **two** surfaces after the round-trip: the **thread top bar**
      (in-thread, immediately after submit) and the **conversation list** (after popping back). The recorded
      **name** — never a session id, never a transient.
- [ ] The target title is **runtime-unique** (`RENAME_NAME_PREFIX + System.currentTimeMillis()`), its
      **absence asserted before** the rename (`assertCountEquals(0)`), so each presence assertion is a genuine
      before → after inversion and repeated LIVE-gate runs stay green.
- [ ] The scenario is registered in the LIVE rung-3 pre-ship gate lane: a test method, an entry appended to
      the comma-separated `class#method` `TEST_TARGET` in `scripts/e2e-emulator.sh` (**sextet → septet**), and
      a scenario note in `docs/e2e-interactive-stream.md`; the LIVE gate stays at **3 real claude turns**
      (rename spends none).
- [ ] Assertions are tolerant (generous timeouts, substring / presence-absence), matching the rung-3
      real-claude ladder.
- [ ] `./gradlew compileDebugAndroidTestKotlin` is green (androidTest is not compiled by the mandatory gates).
