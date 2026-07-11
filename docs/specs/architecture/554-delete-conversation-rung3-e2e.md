# #554 — rung-3 real-claude e2e: delete a conversation → gone from list, thread closed

**Ticket:** [#554](https://github.com/pyrycode/pyrycode-mobile/issues/554) · **Size:** S · **Not** `security-sensitive`

Clone of #541 / #566 (the rung-3 e2e siblings). Zero production code: one new always-on `@Test` in
`InteractiveStreamE2ETest.kt`, ~7 companion constants, one edit to the `scripts/e2e-emulator.sh` LIVE
`class#method` list (trio → quartet), and a scenario entry in `docs/e2e-interactive-stream.md`.

## Design source

N/A — test-only ticket. It exercises already-shipped, already-reviewed UI (the #532 `delete` wire, the
`ChannelInfoSheet` Actions block, the `DeleteConfirmationDialog`, the overflow menu, `RenameDialog`) and
adds **no production surface**, so there is nothing to design and the visual-fidelity check is
intentionally skipped. The ticket body carries no `## Figma` section, which is correct here (not a PO gap):
this is a test of existing UI, not new UI work. (Same shape #541 / #566 established.)

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` — **the clone target.**
  - `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` (lines 404–456) — closest structural
    template: overflow-menu driven (`CD_MORE_ACTIONS` → tap item), a deterministic absence/presence guard,
    tolerant asserts. Read its KDoc (374–403) for the token-selectivity discipline.
  - `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` (lines 302–372) — the **back-to-list
    + re-enter + recents-row** navigation idiom (steps 355–371: `CD_BACK` → wait `CD_NEW_DISCUSSION` →
    long-press → assert a unique name in a merged recents row via `onAllNodesWithText(name, substring=true)`).
    This scenario reuses the same "return to the list, assert a unique name on a `DiscussionPreviewRow`" move.
  - The `companion object` (lines 475–540) — reuse verbatim: `CD_NEW_DISCUSSION`, `CD_SEND_MESSAGE`,
    `CD_BACK`, `CD_MORE_ACTIONS`, `awaitConnected()`, `LIST_TIMEOUT_MS`, `CONNECT_TIMEOUT_MS`,
    `THREAD_TIMEOUT_MS`. (No `PING`/`pingNodeCount` — this scenario spends **no** claude turn; see § Cost.)
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt` (whole, 96 lines) —
  the two menu items this scenario drives: **"Rename"** (`thread_overflow_rename`, line 58, gated on
  `mutationsSupported` → present in relay mode after PR #572) fires `ThreadEvent.Rename`; **"Channel info"**
  (`thread_overflow_channel_info`, line 80, **ungated** — always present) fires `ThreadEvent.ChannelInfo`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoSheet.kt:106,118–131,282–330`
  — the Actions block is gated on `mutationsSupported` (line 118); the **"Delete" `ActionCell`** (line 308)
  uses a **hardcoded literal `"Delete"`** (NOT a `stringResource`) → `onDelete`. Sheet title (line 106) is
  the conversation name.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:322–326,360–384,549–569` —
  the wiring: `RenameDialog(initialName = state.displayName)` (322–326); `ChannelInfoSheet(onDelete = {
  onOverflowEvent(ThreadEvent.Delete) })` (360–376); `if (state.deleteConfirmVisible) DeleteConfirmationDialog(
  onConfirm = DeleteConfirm, onDismiss = DeleteDismiss)` (378–384); and `DeleteConfirmationDialog` itself
  (549–569): title `delete_dialog_title` = **"Delete conversation?"**, confirm `delete_dialog_confirm` =
  **"Delete"**, cancel `delete_dialog_cancel` = **"Cancel"**. Top bar title (line 169) = `state.displayName`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:811–857` — the overflow
  event handling. **Load-bearing for the gotcha:** `ThreadEvent.Delete → pendingDeleteConfirm = true`
  (line 820) — it does **not** clear `pendingChannelInfo`, so the sheet stays composed **behind** the confirm
  dialog. `DeleteConfirm` (821–828) → `repository.delete(...)` then `ThreadNavigation.PopBack`. `Rename →
  pendingRenameDialog = true` (830); `RenameSubmit → repository.rename(...)` (831–836).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RenameDialog.kt:31–49,80–107` — the field
  is **pre-filled with `initialName` and fully selected** (`TextRange(0, len)`, line 43). The single
  `OutlinedTextField` is the only `hasSetTextAction()` node while the dialog is open; Save button
  `rename_dialog_save` = "Save". The pre-fill is why the developer must **replace**, not append (see § Gotchas).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:151–171,238–267` — how the
  list renders: **channels** as `ConversationRow` (uncapped `LazyColumn` items, 154–160), **discussions** as
  `DiscussionPreviewRow` inside `RecentDiscussionsSection` (161–169, 238–267). Both render the conversation
  **name** as `Text` (`ConversationRow.kt:74`, `DiscussionPreviewRow.kt:53`). The recents section renders in
  both `Loaded` and `Empty` list states.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:62–110,156` — recents
  projection: `discussions.take(RECENT_DISCUSSIONS_LIMIT)` where the limit is **3** (line 156).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:959–981` — context
  only: `observeConversations` filters then `.sortedByDescending { it.lastUsedAt }` (line 981). **This is
  why a freshly-created discussion is always #1 → always inside the visible top-3.** Also `delete`
  (1529–1545) → `removeConversation` clears all projections so `observeConversations` re-emits without it;
  `rename` (~1555–1565) upserts `conversation_updated` → the list re-emits with the new name. Not edited.
- `scripts/e2e-emulator.sh:454–479` — the LIVE curation block. Line 462 is the comma-separated
  `class#method` `TEST_TARGET` (a trio today) to append to; lines 8–11, 55, 454, 459–460, 479 carry the
  "trio / 3 turns" prose to refresh.
- `docs/e2e-interactive-stream.md` — **read-only while writing this test** (the developer edits it as a
  deliverable): lines 22–36 (ladder), 92–130 (What rung 3 is made of), 194–271 (pre-ship gate / LIVE mode).
  Mirror how #541 / #566 were wired in.
- Memory lesson `androidtest-not-compiled-by-mandatory-gates` — `./gradlew test` / `lint` / `assembleDebug`
  do **not** compile `androidTest`. The build-gate for this ticket is `./gradlew compileDebugAndroidTestKotlin`.

## Context

Org policy (2026-07-08): every operator-facing happy-path flow ships with a real-claude e2e that runs in
the pre-ship gate. This is the delete scenario, the #532 follow-up filed separately so the wire stayed
size S. Both prerequisites have landed: **#532** shipped the `delete` data path (PR #555), and PR #572
flipped `RemoteConversationRepository.mutationsSupported = true`, so the Delete affordance (which lives in
the `mutationsSupported`-gated Actions block of the Channel Info sheet) is reachable on the live relay path.
Delete is conversation-scoped (keyed by `conversation_id`, replies `conversation_deleted`), so it is in the
clean-buildable camp with #541 / #566 — it does **not** carry the orthogonal blockers that re-park #545
(live `currentSessionId=""`) or #562 (workspace-chip `!hasMessages` catch-22). Sibling #541 already shipped
through the pipeline (PR #573) as proof the family is buildable. This is a **test-only** ticket.

## The scenario

Seed a conversation with a **runtime-unique, list-visible name** (via Rename), confirm it is present on the
list, then drive the real Delete flow from the thread and confirm **both** durable post-conditions: the name
is gone from the list, and the thread has popped back. The absence is a genuine inversion of the presence
check — same surface (the channel list), same unique token.

## Design

### Durable identity: **Rename** to a runtime-unique name (not promote)

The ticket offers two ways to give the scratch conversation a test-controlled, list-visible identity:
promote-to-channel via "Save as channel", or rename it. **Rename is chosen** because it is the simpler,
side-effect-free path with equally robust visibility:

- **Simpler to drive.** `RenameDialog` is a single text field + Save. `SaveAsChannelDialog` adds a
  DEDICATED/SCRATCH workspace radio; the default (DEDICATED) creates a `pyry-workspace/channels/<slug>/`
  folder that **accumulates across gate runs** (the #566 folder-accumulation problem), and avoiding it means
  an extra radio tap to select SCRATCH. Rename touches only the name — no filesystem, no accumulation.
- **Visibility is guaranteed, not capped-out.** A renamed discussion stays a discussion and appears in the
  channel list's `RecentDiscussionsSection`. That section shows only the top `RECENT_DISCUSSIONS_LIMIT = 3`,
  but `observeConversations(Discussions)` is `sortedByDescending { lastUsedAt }`, and the seeded discussion
  was **just created** → it is #1 → always inside the visible three. No test creates a discussion after it,
  so nothing displaces it. The top-3 cap is therefore never a risk for *this* item.
- **The "ungated Save as channel" advantage is moot here.** The ticket notes "Save as channel" is ungated,
  but the Delete affordance under test is itself gated on `mutationsSupported` — so the whole scenario
  requires the gate `true` regardless of how the identity is seeded. Rename being gated costs nothing extra.

The unique name is a runtime string — `CONVERSATION_NAME_PREFIX + System.currentTimeMillis()`, e.g.
`"e2e554-1720800000000"` — so it cannot pre-exist on screen and cannot collide with discussions/channels
accumulated by prior gate runs. This is the #566 unique-`folderName` / #481 token-omission discipline applied
to an **absence** assertion: because the token is unique, `assertCountEquals(0)` on it after delete is a
genuine inversion, never a match-everything.

### The `@Test` — `interactiveTurn_deleteConversation_removesFromListAndClosesThread`

One always-on (not `@Ignore`d) method, cloning the #541 / #566 shape. Steps as tolerant-assertion scenario
bullets — **not** code to paste:

1. **Land on the channel list** — `waitUntil(LIST_TIMEOUT_MS)` for `CD_NEW_DISCUSSION` present.
2. **`awaitConnected()`** — the relay session must be Open before the rename/delete round-trip to the daemon.
3. **Create a discussion** — a **regular tap** on `CD_NEW_DISCUSSION` → navigate into the thread;
   `waitUntil(THREAD_TIMEOUT_MS)` for `CD_SEND_MESSAGE`. A plain discussion suffices — both "Rename" (gated
   on `mutationsSupported = true`) and "Channel info" (ungated) are reachable on it.
4. **Rename to the unique name** — open the overflow (`CD_MORE_ACTIONS`), `waitUntil` for `RENAME_ITEM`, tap
   it; `waitUntil` for the dialog's `hasSetTextAction()` field, `performTextReplacement(uniqueName)` (see
   § Gotchas — the field is pre-filled + selected, so **replace**, not `performTextInput`), tap `RENAME_SAVE`.
   Optional intermediate confirmation the round-trip landed: `waitUntil` for `uniqueName` (substring) in the
   thread top bar.
5. **Back to the list → presence check (AC-3)** — tap `CD_BACK`; `waitUntil(LIST_TIMEOUT_MS)` for
   `CD_NEW_DISCUSSION`; then `waitUntil` for `onAllNodesWithText(uniqueName, substring=true)` non-empty and
   `onFirst().assertIsDisplayed()`. This is the genuine presence observation on the surface where absence is
   later asserted.
6. **Re-enter the thread** — `onAllNodesWithText(uniqueName, substring=true).onFirst().performClick()` (the
   merged `DiscussionPreviewRow` carries the name as text and is clickable — #566 asserts the recents row the
   same way); `waitUntil(THREAD_TIMEOUT_MS)` for `CD_SEND_MESSAGE`. (This tap is itself a second presence
   observation — it can only succeed if the name is on the list.)
7. **Open Channel info → Delete** — open the overflow (`CD_MORE_ACTIONS`), `waitUntil` for
   `CHANNEL_INFO_ITEM`, tap it; `waitUntil` for the sheet's `DELETE_ACTION` (unique while only the sheet is
   open), tap it → the "Delete conversation?" dialog opens **over** the still-composed sheet.
8. **Confirm the delete** — `waitUntil(THREAD_TIMEOUT_MS)` for `DELETE_DIALOG_TITLE` ("Delete conversation?",
   the unique dialog anchor). Then tap the **dialog's** confirm button with the disambiguating matcher (see
   § Gotchas): `onNode(hasText(DELETE_ACTION) and hasAnySibling(hasText(DELETE_DIALOG_CANCEL))).performClick()`.
9. **Absence + thread-closed (AC-2)** — after `DeleteConfirm` → `repository.delete` → `PopBack`:
   `waitUntil(LIST_TIMEOUT_MS)` for `CD_NEW_DISCUSSION` (the list marker → **the thread has popped back**),
   then `onAllNodesWithText(uniqueName, substring=true).assertCountEquals(0)` (**gone from the list**). Both
   durable post-conditions, tolerant, never on a delta count or timing.

**Always-on, not `@Ignore`d.** The post-conditions are **durable** structural facts (a conversation is
either in the list or not; the thread either popped or not) — no transient like #482's spinner — so the test
belongs in the always-on gate, matching #481's durable tool-name row and #541's delimiter.

**No negative control needed.** The absence assertion already keys on a runtime-unique token whose presence
is observed twice (step 5 assert + step 6 tap) before deletion; there is no match-everything to disprove
(same reasoning #482's spinner test documents).

### Gotchas — the two things that will bite a naive clone

**1. The "Delete" collision (the one real gotcha).** The sheet's Delete button (`ChannelInfoSheet` line 308,
`ActionCell(label = "Delete")`, a literal) and the confirm dialog's button (`delete_dialog_confirm` = "Delete")
are **both the string "Delete"**, and `ThreadEvent.Delete` leaves `pendingChannelInfo` **true** — so the sheet
stays composed behind the dialog and **both "Delete" nodes are on screen at confirm time**. A bare
`onNodeWithText("Delete")` throws (2 matches). Disambiguate the confirm by a compound matcher only the dialog's
button satisfies — its sibling is the dialog's "Cancel", which the sheet has no equivalent of (the sheet's
dismiss is a Close *icon*):

```kotlin
// tap the sheet's Delete (unique — only the sheet is up yet):
composeTestRule.onNodeWithText(DELETE_ACTION).performClick()
// …dialog opens over the sheet; wait for its unique title, then tap the CONFIRM Delete:
composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
    composeTestRule.onAllNodesWithText(DELETE_DIALOG_TITLE).fetchSemanticsNodes().isNotEmpty()
}
composeTestRule.onNode(hasText(DELETE_ACTION) and hasAnySibling(hasText(DELETE_DIALOG_CANCEL))).performClick()
```

`DELETE_DIALOG_TITLE` ("Delete conversation?") is the unique wait anchor; `DELETE_DIALOG_CANCEL` ("Cancel")
uniquely disambiguates the confirm button. If the button-row tree differs on first compile/run, an equivalent
unambiguous anchor is fine (rung 3 permits selector tuning) — the invariant is: **never** rely on `onFirst()`
across the two identical "Delete" nodes (z-order is not guaranteed).

**2. The pre-filled rename field.** `RenameDialog` pre-fills the field with the current name and selects it all
(`RenameDialog.kt:43`). Use `performTextReplacement(uniqueName)` to guarantee the field holds exactly the unique
name — do **not** use `performTextInput` (whose insert-vs-replace-selection behaviour is ambiguous and could
leave the auto-name concatenated with the suffix). Substring assertions on the unique part would still pass, but
a clean name keeps the top-bar / delete-dialog-body / list-row text tidy and the intent obvious.

### Companion constants to add

```kotlin
const val RENAME_ITEM = "Rename"                        // R.string.thread_overflow_rename — overflow item
const val RENAME_SAVE = "Save"                          // R.string.rename_dialog_save
const val CHANNEL_INFO_ITEM = "Channel info"           // R.string.thread_overflow_channel_info — overflow item (ungated)
const val DELETE_ACTION = "Delete"                      // sheet ActionCell literal AND delete_dialog_confirm — COLLIDES (see Gotchas)
const val DELETE_DIALOG_TITLE = "Delete conversation?" // R.string.delete_dialog_title — unique dialog anchor
const val DELETE_DIALOG_CANCEL = "Cancel"              // R.string.delete_dialog_cancel — disambiguates the confirm button
const val CONVERSATION_NAME_PREFIX = "e2e554-"         // runtime-unique rename target (+ System.currentTimeMillis())
```

Keep the `// Keep in sync with res/values/strings.xml` comment convention already used in the companion, and
flag `DELETE_ACTION` inline as the colliding literal. `CD_NEW_DISCUSSION`, `CD_SEND_MESSAGE`, `CD_BACK`,
`CD_MORE_ACTIONS`, and the timeout constants already exist — reuse them.

### Cost — zero claude turns (deliberate divergence from #541 / #566)

Create-discussion, rename, and delete are **daemon round-trips, not claude turns**. The durable identity comes
from the typed name, which does **not** need live session content to be identifiable, so per the ticket's cost
guidance ("spend a claude turn only if the chosen durable identity needs live session content") this scenario
spends **no** claude turn — there is no `PING_PROMPT`. It still rides the real rung-3 stack (real relay, real
daemon) and belongs in the LIVE gate: it catches a broken `delete` / `rename` wire against the production relay,
which is exactly the pre-ship failure class the gate exists for. Consequently the LIVE gate goes from a **trio
(3 methods / 3 turns)** to a **quartet (4 methods / still 3 turns)** — delete adds a method, not a turn (the
same "spends none" note #541 makes for `/clear`).

## State + concurrency model

None new. The test drives the shipped MVI surface through Compose/Espresso synchronization (`waitUntil` +
`assertIsDisplayed` / `assertCountEquals`) and reads the relay connection via the existing `awaitConnected()`
`ConnectionStateSource` helper. No coroutine, dispatcher, or `StateFlow` is introduced. The delete path
(`DeleteConfirm → repository.delete → removeConversation → observeConversations re-emit`, plus `PopBack`) is
the canonical shipped #532 flow — the e2e proves it against a real daemon, exercising nothing the Fake
synthesizes.

## Error handling

Test-side only: every wait is `waitUntil(<generous timeout>)`, which throws `ComposeTimeoutException` on a
genuinely stuck flow — the correct red signal (a down/stale relay, a broken delete/rename wire, or a list
re-projection regression all surface this way). No new production error paths. Real-claude variance is not a
factor here (no claude turn); the daemon round-trips are fast, so `THREAD_TIMEOUT_MS`/`LIST_TIMEOUT_MS = 30_000L`
are ample. All assertions are tolerant (substring, presence/absence, generous timeouts) per the
`docs/e2e-interactive-stream.md` Constraints.

## Testing strategy

Instrumented (`androidTest`), run by `scripts/e2e-emulator.sh` on a headless emulator against a host `pyry`
+ relay (rung 3), and by the `LIVE=1` pre-ship gate (`scripts/e2e-preship-gate.sh`) against the production
relay. **Not** covered by `./gradlew test` (unit). Developer verification:

- **Compiles:** `./gradlew compileDebugAndroidTestKotlin` — the only gate that compiles `androidTest`
  (`test`/`lint`/`assembleDebug` skip it), so the only way to catch a bad selector import before a device run.
  New matchers needed: `hasText`, `hasAnySibling`, `and`, `performTextReplacement`, `onNodeWithText`
  (`androidx.compose.ui.test.*`); `assertCountEquals` is already imported. Let the compile gate resolve the
  final import set.
- **Script sanity:** `bash -n scripts/e2e-emulator.sh`, and eyeball that the comma-separated `class` arg is a
  single unbroken shell token.
- **Green in the gate (AC-4):** `bash scripts/e2e-preship-gate.sh` — an operator-run, real-stack check that
  now executes four curated methods. That green LIVE run is the operator's pre-ship confirmation, not
  something the developer's turn budget must reproduce headlessly.

## `scripts/e2e-emulator.sh` change (LIVE trio → quartet)

Append the new method to the LIVE `TEST_TARGET` comma-separated `class#method` list (line 462), so the gate
runs a **quartet**: ping + create-workspace-folder + new-session + **delete**. Contract (shape, not a paste):

```sh
# scripts/e2e-emulator.sh, LIVE branch (line ~462) — append the 4th method:
TEST_TARGET="${TEST_CLASS}#interactiveTurn_pingPrompt_streamsPingReplyIntoThread,\
${TEST_CLASS}#interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace,\
${TEST_CLASS}#interactiveTurn_newSession_rendersSessionBoundaryDelimiter,\
${TEST_CLASS}#interactiveTurn_deleteConversation_removesFromListAndClosesThread"
```

The #481 tool-use test stays LIVE-excluded for cost — this is **additive**. Refresh the "trio / 3 turns"
prose to "quartet / 4 scenarios, **still 3 turns** (delete spends none — create/rename/delete are daemon
round-trips)": header lines 8–11, usage line 55, run-block comment lines 454 and 459–460, and the PASS log
line 479. Grep to find every spot so none drifts:

```bash
grep -n 'curated trio\|3 turns\|three real\|trio\|new-session delimiter\|create-workspace-folder + new-session' \
  scripts/e2e-emulator.sh docs/e2e-interactive-stream.md
```

## Doc note (`docs/e2e-interactive-stream.md`)

Mirror how #541 / #566 were wired in: add a delete scenario to the rung-3 scenario list (§ "What rung 3 is
made of", ~lines 92–130), the ladder entry (~22–36), the LIVE-mode "What it runs" (~237–243) and the pre-ship
gate section (~194–218), and update the Cost lines and Coverage follow-ups (~638–663) from a trio to a
quartet. Key facts to state: the load-bearing signals are the two **durable** post-conditions (unique name
**absent** from the list — a genuine inversion of the presence check — and the thread **popped back**);
always-on (durable, unlike the `@Ignore`d spinner); the identity is a **rename** to a runtime-unique name;
and total real-claude cost is **zero** turns (create/rename/delete are daemon round-trips), so the LIVE gate
stays at 3 turns across 4 curated methods.

## Open questions / first-run assumptions (grounded, unverified end to end)

- **The daemon honours `delete_conversation` and the list re-projects without it.** This is the #532 /
  pyrycode#822 contract (`removeConversation` clears all projections), but unverified end to end against a
  real daemon. If delete does not converge the list, step 9's `assertCountEquals(0)` fails — that is the drift
  Layer 3 exists to surface. Do **not** weaken the assertion; record the finding.
- **`rename` propagates to the list row before step 5.** The rename reply (`conversation_updated`) upserts →
  `observeConversations` re-emits with the new name. The step-5 `waitUntil` covers the round-trip; if the
  recents row still shows the auto-name, that is a rename-wire finding, not a reason to weaken the test.
- **`PopBack` after `DeleteConfirm` returns to the channel list.** The thread was entered from the list (step
  6), so `PopBack` returns there. Confirm on first run the list — not some other back-stack entry — is what
  renders (step 9 keys on `CD_NEW_DISCUSSION`, the list marker).
- **The confirm-button disambiguation matcher resolves to exactly one node.** `hasText(DELETE_ACTION) and
  hasAnySibling(hasText(DELETE_DIALOG_CANCEL))` assumes the `AlertDialog`'s confirm/dismiss buttons are
  siblings (Material3 `AlertDialogFlowRow`). Confirm on first compile/run; if the tree differs, pick another
  unambiguous anchor rooted at `DELETE_DIALOG_TITLE`.

## Acceptance criteria (developer deliverable)

- [ ] New always-on `@Test` in `InteractiveStreamE2ETest` (rung 3) seeds a conversation with a
      runtime-unique, list-visible name (via **Rename**), confirms it **present** on the channel list, then
      drives the real Delete flow: overflow → "Channel info" → "Delete" → "Delete conversation?" → confirm.
- [ ] After confirmation it asserts **both** durable post-conditions: the unique name is **absent** from the
      list (`assertCountEquals(0)`, a genuine inversion of the step-5 presence check), and the **thread has
      popped back** (`CD_NEW_DISCUSSION` list marker present). Never a transient or a timing/delta count.
- [ ] The confirm tap disambiguates the sheet's "Delete" `ActionCell` from the dialog's "Delete" confirm
      button (both on screen — the sheet stays composed behind the dialog); the rename uses
      `performTextReplacement` on the pre-filled field.
- [ ] The scenario is added to the LIVE curated `class#method` list in `scripts/e2e-emulator.sh` (trio →
      quartet) and documented in `docs/e2e-interactive-stream.md`, mirroring how #541 / #566 were wired in;
      the LIVE gate stays at 3 real claude turns (delete spends none).
- [ ] `./gradlew compileDebugAndroidTestKotlin` is green (androidTest is not compiled by the mandatory gates).
