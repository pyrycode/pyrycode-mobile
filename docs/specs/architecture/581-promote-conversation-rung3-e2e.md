# #581 — rung-3 real-stack e2e: save-as-channel promote → channel name durable on top bar + promoted tier on the list

**Ticket:** [#581](https://github.com/pyrycode/pyrycode-mobile/issues/581) · **Size:** S · **Not** `security-sensitive`

The **eighth** member of the shipped rung-3 mutation-e2e family (#541 / #551 / #554 / #562 / #566 / #537), and
the **backfill** one: save-as-channel shipped in #348, *before* the real-stack definition-of-done rule
(pyrycode-mobile-agents#9), so it is the one daemon-action flow with no rung-3 coverage — and it silently
answered `unsupported` on the real wire until pyrycode/pyrycode#949 registered the daemon handler. Zero
production code: one new always-on `@Test` in `InteractiveStreamE2ETest.kt`, six companion constants, one edit
to the `scripts/e2e-emulator.sh` LIVE `class#method` list (septet → **octet**), and the matching scenario /
count updates in `docs/e2e-interactive-stream.md`.

Structurally this is the **#537 rename scenario with three divergences** (§ Design): the dialog carries a
**radio group** that must be moved off its `DEDICATED` default, the tier flip gives a **second in-thread
signal** rename does not have, and the list-side tier assertion needs a **drilldown** because the main list
cannot discriminate the two tiers with a tolerant matcher (§ The list surface cannot discriminate tiers).

## Design source

N/A — test-only ticket, matching the ticket body's own `## Figma` N/A. It exercises already-shipped,
already-reviewed UI (the #348 `promote` wire, the overflow "Save as channel…" item, `SaveAsChannelDialog`, the
channel list's two tiers) and adds **no production surface**, so the visual-fidelity check is intentionally
skipped. Same shape #541 / #554 / #562 / #537 established.

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` — **the clone target.**
  - `interactiveTurn_renameConversation_relabelsTopBarAndListRow` (**KDoc 900–945, body 946–1009**) — **the
    structural template.** Steps 1–3 (list marker → `awaitConnected()` → create discussion → thread marker),
    step 4 (absence guard), step 5 (overflow → dialog → `hasSetTextAction() and isFocused()` →
    `performTextReplacement` → Save), step 6 (in-thread top-bar wait), step 7 (Back → list marker → presence)
    are **all reused verbatim in shape**; only the menu item, the dialog interaction, and the tier assertions
    change. Read its KDoc too — the "no top-bar false match" and field-disambiguation notes transfer whole.
  - `interactiveTurn_archiveRestore_roundTripsListMembership` (**661–777**) — the **tier-membership idiom**:
    prove tier by *navigating to the tier-specific screen and asserting presence/absence* (its steps 9–13 do
    this against the Archived screen). #581's step 8 is the same move against the Discussions screen. Note its
    step-8 comment: *"Tolerant: presence/absence, generous timeout — **never a delta count**"* — this rules out
    reading the `See all discussions (N)` counter as a tier signal.
  - `interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace` (**780–840 KDoc, 841–896 body**) — the
    absence-guard → inversion idiom, and the **`WorkspaceChip` gating fact** its KDoc documents (`!isPromoted
    && !hasMessages`) that #581 turns into its in-thread tier signal (§ Design).
  - The `private companion object` (**1028–1164**) — reuse verbatim: `CD_NEW_DISCUSSION`, `CD_SEND_MESSAGE`,
    `CD_BACK`, `CD_MORE_ACTIONS`, `LIST_TIMEOUT_MS`, `CONNECT_TIMEOUT_MS`, `THREAD_TIMEOUT_MS`,
    `awaitConnected()`. Note the per-scenario prefix convention (`FOLDER_NAME_PREFIX = "e2e566-"` :1085,
    `WORKSPACE_FOLDER_PREFIX = "e2e562-"` :1100, `CONVERSATION_NAME_PREFIX = "e2e554-"` :1124,
    `ARCHIVE_NAME_PREFIX = "e2e551-"` :1147, `RENAME_NAME_PREFIX = "e2e537-"` :1156). **No `PING`/`PING_PROMPT`
    — this scenario spends no claude turn** (§ Cost).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt:40–48` — the **"Save as
  channel…"** item, gated `if (!isPromoted)` — **not** `mutationsSupported` (that block starts at :49). So it
  is reachable on a fresh discussion regardless of the capability flag, and it **disappears** once the promote
  lands. `ThreadOverflowMenuTest.kt:235` asserts the ordering `["dismiss", "event:SaveAsChannel"]` — the menu
  closes **before** the event fires, so only the dialog is on screen afterward (no menu-behind-dialog
  collision, same as #537, unlike #554's sheet-behind-dialog).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:838–850, 895, 899–903` — the
  whole promote drive. `SaveAsChannel → pendingSaveAsChannelDialog = SaveAsChannelDialogState(initialName =
  AUTO_SUGGESTED_CHANNEL_NAME)` (838–840); `AUTO_SUGGESTED_CHANNEL_NAME = "New channel"` (:895) — **the
  pre-filled name is a constant, not `state.displayName`** (divergence from `RenameDialog`).
  `SaveAsChannelSubmit` clears the dialog **synchronously at :842, before** the suspend, then
  `launchGuardedRepoCall { repository.promote(conversationId, name, resolveWorkspace(name, workspace)) }`
  (843–849). `resolveWorkspace` (899–903): `DEDICATED → "pyry-workspace/channels/<slug>"`, **`SCRATCH →
  null`**. **Load-bearing divergence from delete/archive: there is NO PopBack** — the thread stays open.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:233, 242–244` — **the single
  most load-bearing read in this spec.** Thread state is derived from
  `repository.observeConversations(ConversationFilter.All)` (:233), with `displayName = conv?.displayName()`
  (:242), `isPromoted = conv?.isPromoted ?: false` (:243), `hasMessages = …` (:244). The promote reply's
  confirmed upsert into that projection therefore drives the top-bar name **and** the tier flip — and it is
  the **same projection** the channel list's two tiers are filtered from, which is what makes the in-thread
  `isPromoted` read a faithful tier assertion (§ The list surface cannot discriminate tiers).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:169, 177, 201–205, 330–339` —
  the top bar `title = state.displayName` (:169) is in-thread assertion surface #1; `isPromoted =
  state.isPromoted` feeds the overflow (:177); **`if (!state.isPromoted && !state.hasMessages) {
  WorkspaceChip(…) }`** (:201–205) is in-thread assertion surface #2 (§ Design); `SaveAsChannelDialog(initialName
  = dialogState.initialName, onSubmit = { name, workspace -> … })` is gated on the nullable
  `state.saveAsChannelDialog` (:330–339).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialog.kt:43–61, 71–79, 94–118,
  121–128` — the dialog contract. **`initialWorkspace = WorkspaceChoice.DEDICATED` (:56) is the default** —
  the scenario must actively move it (§ Gotcha 2). Field is pre-filled and **fully selected** (`TextRange(0,
  initialName.length)`, :54) and **auto-focuses** (`focusRequester.requestFocus()`, :77–79) → the #537
  selector applies verbatim (§ Gotcha 1). `WorkspaceRadios` rows are `Modifier.selectable(role =
  Role.RadioButton)` (:151–155 dedicated, :185–189 scratch).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspaceChip.kt:25` — label is a **literal**,
  `"Workspace: $workspaceLabel (change)"` (no string resource), so `"Workspace:"` is the matchable token.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:151–171, 229–236, 239–267,
  269–298` — the two tiers. `Loaded` renders `ChannelsSectionHeader()` then a `LazyColumn` of
  `ConversationRow`s over `state.channels`, then a **single** `item(key = "recent-discussions-section")`
  holding `RecentDiscussionsSection` (`DiscussionPreviewRow`s + `SeeAllDiscussionsRow`). Note
  `RecentDiscussionsSection` **returns early when `discussions.isEmpty()`** (:246) — the basis of the one
  first-run assumption (§ Open questions). `SeeAllDiscussionsRow` (:269–298) is the drilldown affordance.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:47–52` **and**
  `DiscussionPreviewRow.kt:42` — read these two side by side: **both** set a merged
  `contentDescription` from a format string, and `strings.xml:15` `cd_conversation_row` = `"%1$s, %2$s"` is
  **byte-identical** to `strings.xml:17` `cd_discussion_preview_row` = `"%1$s, %2$s"`. This is why the main
  list cannot discriminate tiers by content description (§ The list surface cannot discriminate tiers).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModel.kt:53` — the drilldown
  screen is fed by `repository.observeConversations(ConversationFilter.Discussions)`, i.e. the
  **Discussions-tier-only** projection. This is what makes step 8's absence assertion a real tier read.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreen.kt:71–113` — the drilldown
  screen: a `TopAppBar` with a **`cd_back` nav icon and no FAB**, `DiscussionRow`s over `state.discussions`.
  Its title is `R.string.discussion_list_title` — which `strings.xml:7` defines as **"Recent discussions"**,
  the *same literal* as `recent_discussions_section_header` (`strings.xml:12`). See § Gotcha 3.
- `app/src/main/res/values/strings.xml:7, 11–20, 76–81` — every literal this scenario matches on:
  `save_as_channel_action` = **"Save as channel…"** (:11, real `…` U+2026), `save_as_channel_dialog_title` =
  "Save as channel" (:76, **no** ellipsis), `save_as_channel_dialog_workspace_scratch` = **"Keep in scratch"**
  (:79), `save_as_channel_dialog_save` = "Save" (:80), `see_all_discussions_label` = "See all discussions
  (%d)" (:13), `channels_section_header` = "Channels" (:20).
- `scripts/e2e-emulator.sh:454–471, 482–488` — the LIVE curation block. Line **468** is the comma-separated
  `class#method` `TEST_TARGET` (a **septet** today) to append the 8th method to; lines 455–460 and 464–467
  carry the "septet / 7 methods / still 3 turns" prose, line **485** is the PASS message (§ Harness change).
- `docs/e2e-interactive-stream.md` — **read-only while writing the test** (the developer edits it as a
  deliverable): lines 46–54 (ladder + curated septet), 138–162 (What rung 3 is made of), 192–249 (per-scenario
  notes + always-on rationale), 327–340 (turn accounting), 350–369 + 403 (Live mode / what it runs), 799–818
  (Follow-ups). Mirror how #537 — the last-merged sibling — was wired in.
- Memory lesson `androidtest-not-compiled-by-mandatory-gates` — `./gradlew test` / `lint` / `assembleDebug`
  do **not** compile `androidTest`. The build gate for this ticket is `./gradlew compileDebugAndroidTestKotlin`.
- Memory lesson `compose-test-and-member-and-rename-field-disambig` — `and` is a `SemanticsMatcher` **member**
  (no import); the dialog-over-thread two-field disambiguation is `hasSetTextAction() and isFocused()`.

## Context

Save-as-channel is the **one** operator-facing daemon-action flow that shipped (#348) before the real-stack
definition-of-done rule (pyrycode-mobile-agents#9), so it is the only member of the mutation family with no
rung-3 coverage. That gap was not theoretical: the daemon never registered the `promote_conversation` handler,
so the verb answered `unsupported` over the real wire and **the promote never happened** — while the mobile
suite stayed green, because the mobile e2e fake daemon answers anything. pyrycode/pyrycode#949 landed the
handler (closed 2026-07-14) with daemon-side wire coverage, and pyrycode-desktop#430 (closed 2026-07-15)
asserted the round-trip from the desktop UI. This ticket is the remaining **mobile client half**.

Promote is **conversation-scoped** — the reply is folded into the conversation projection with **no session
transition** — so it carries none of the session-scoped `currentSessionId == ""` blocker that re-parks the
settings e2e (#545). It is in the clean-buildable camp with the seven shipped siblings, all living in
`InteractiveStreamE2ETest`. This is a **test-only** ticket.

**Wire shape (the body's one correction).** Promote is a **request/reply** verb whose reply is the **bare
conversation object**, folded by a confirmed upsert — it is **not** a `conversation_updated` broadcast (that is
*rename's* shape, from #530). The spec therefore asserts on **rendered UI**, never on a named wire message.
With `WorkspaceChoice.SCRATCH` the request's workspace is `null`, and the conversation keeps its existing
scratch `cwd`.

## The scenario

Create a scratch discussion, drive the **real** overflow "Save as channel…" affordance to a runtime-unique
channel name **keeping the conversation in scratch**, submit, and assert the promote round-trip landed on
**three** durable surfaces: the **thread top bar** re-labels in place (no pop-back), the **`WorkspaceChip`
unmounts** (the `isPromoted` tier flip, in-thread), and after backing out the name is **present on the main
list** while **absent from the Discussions-tier drilldown** (promoted tier, not a recent discussion). The
unique name's absence is asserted **before** the submit, so every presence assertion is a genuine before →
after inversion on the same token.

## Design

### Entry point: the thread overflow "Save as channel…" (not the discussion-list row overflow)

Two entry points converge on `repository.promote(conversationId, name, workspace)`:

| Entry | Dialog | Name |
|---|---|---|
| **Thread overflow "Save as channel…"** | `SaveAsChannelDialog` (name field + workspace radios) | **operator-typed** |
| `DiscussionListScreen` row overflow | `PromotionConfirmationDialog` (plain confirm) | auto-derived, `workspace = null` |

**Drive the thread overflow.** It is the entry the whole mutation-e2e family uses, and it is the only one that
yields an **operator-typed, runtime-unique assertable token**; the row-overflow path's auto-derived name gives
nothing unique to key assertions on. It is gated `!isPromoted` — **not** `mutationsSupported` — so a fresh
discussion reaches it regardless of the capability flag (a reachability difference worth noting against #537,
whose "Rename" item *is* `mutationsSupported`-gated and only became reachable after PR #572).

### Three in-thread / list surfaces, all durable, all reading the same projection

`ThreadViewModel:233` derives thread state from `observeConversations(ConversationFilter.All)`, so a single
confirmed upsert of the promote reply drives all of:

1. **Thread top bar** (`ThreadScreen.kt:169`, `title = state.displayName`) — reached **immediately after
   submit**, in-thread. There is **no PopBack** on promote (unlike delete/archive), so the thread stays open
   and the top bar re-labels in place. Proves the round-trip landed *and* the name was recorded.
2. **`WorkspaceChip` unmount** (`ThreadScreen.kt:201`, `if (!state.isPromoted && !state.hasMessages)`) — the
   **tier flip**, in-thread, free. The chip is mounted before the promote (a discussion) and unmounts after
   (`isPromoted` true). Because this scenario **sends no message**, `hasMessages` stays false throughout, so
   the chip's disappearance is attributable **solely** to `isPromoted` — the same gating fact #562's KDoc
   already relies on, read in the opposite direction. This is the strongest available read of the flag the
   list's tier split is itself computed from.
3. **Main list + Discussions drilldown** (after `CD_BACK`) — presence on the main list, **absence** from the
   `ConversationFilter.Discussions` projection (`DiscussionListViewModel:53`). Present-on-the-list ∧
   not-a-discussion ⇒ presented in the **promoted (channel) tier**. See below for why this needs a drilldown.

None of the three is a session id or a transient — the #545 lesson applied.

### The list surface cannot discriminate tiers (why step 8 is a drilldown)

The obvious assertion — "the unique name renders on a channel row, not a recents row" — has **no tolerant
encoding on the main list**, and a developer will waste turns discovering that. Three independent reasons:

- **Identical content descriptions.** `ConversationRow` and `DiscussionPreviewRow` both set a merged
  `contentDescription` from a format string, and the two strings are byte-identical: `cd_conversation_row` =
  `"%1$s, %2$s"` (`strings.xml:15`) and `cd_discussion_preview_row` = `"%1$s, %2$s"` (`strings.xml:17`). Both
  render `"<name>, <relativeTime>"`. A CD matcher cannot tell them apart.
- **No test tags, and the section wrapper carries no semantics.** `RecentDiscussionsSection`'s `Column` has no
  semantics modifier, so it is not a `SemanticsNode`; its children are hoisted toward the enclosing
  `LazyColumn`. Any `hasAnyAncestor` / `hasAnySibling` scoping would be a bet on merge behaviour that cannot
  be verified without a device run — exactly the kind of guess that produces a false green.
- **Counter reads are out of idiom.** The `See all discussions (N)` counter would decrement on promote, but
  #551's step 8 explicitly forbids this shape: *"never a delta count."*

So the tier read goes where the tiers are unambiguous: the **Discussions-only screen**. Absence there is a
direct read of the `Discussions` projection — the same filter the recents section renders. Combined with
presence on the main list, it is exactly AC-1's *"in the promoted (channel) tier rather than as a recent
discussion"*, expressed entirely in tolerant presence/absence matchers.

**Rejected alternative — bounds comparison.** Asserting the row's `getBoundsInRoot().top` is above the
"Recent discussions" header would encode tier positionally, but it depends on that header existing (same
first-run dependency as the drilldown, § Open questions) *and* introduces layout math no other scenario in
this suite uses. The drilldown is the established idiom (#551 steps 9–13); prefer it.

### The `@Test` — `interactiveTurn_saveAsChannel_promotesToChannelTier`

One always-on (not `@Ignore`d) method. Steps as tolerant-assertion scenario bullets — **not** code to paste;
steps 1–3 and 7 are shape-identical to #537's:

1. **Land on the channel list** — `waitUntil(LIST_TIMEOUT_MS)` for `CD_NEW_DISCUSSION` present.
2. **`awaitConnected()`** — the relay session must be Open before the promote round-trip to the daemon.
3. **Create a discussion** — tap `CD_NEW_DISCUSSION` → navigate into the thread; `waitUntil(THREAD_TIMEOUT_MS)`
   for `CD_SEND_MESSAGE`. A plain discussion suffices — "Save as channel…" is `!isPromoted`-gated, and a fresh
   discussion is unpromoted by construction.
4. **Absence guard + tier before-state (deterministic, no claude turn)** — compute `uniqueName =
   PROMOTE_NAME_PREFIX + System.currentTimeMillis()`; assert `onAllNodesWithText(uniqueName, substring =
   true).assertCountEquals(0)` (AC-2: nowhere on screen yet — the top bar shows the server auto-name and the
   dialog's pre-fill is the constant `"New channel"`, so a later appearance is attributable to the
   round-trip). Then assert the `WorkspaceChip` **is** present (`onAllNodesWithText(WORKSPACE_CHIP_PREFIX,
   substring = true)` non-empty) — the before-state of the step-6 tier-flip inversion.
5. **Promote to the unique name, keeping scratch** — tap `CD_MORE_ACTIONS`; `waitUntil` for
   `SAVE_AS_CHANNEL_ITEM`, tap it (exact match — see § Gotcha 3); `waitUntil` for `hasSetTextAction() and
   isFocused()` (the dialog's field, disambiguated from the thread composer — § Gotcha 1);
   `performTextReplacement(uniqueName)` on the pre-filled + selected field; **tap
   `KEEP_IN_SCRATCH_OPTION`** (§ Gotcha 2 — mandatory, the dialog defaults to `DEDICATED`); tap
   `SAVE_AS_CHANNEL_SAVE`.
6. **In-thread assertions (AC-1 first half, surfaces #1 + #2)** — `waitUntil(THREAD_TIMEOUT_MS)` for
   `onAllNodesWithText(uniqueName, substring = true)` non-empty (this wait spans the promote round-trip), then
   `onFirst().assertIsDisplayed()` — the top bar re-labelled **in place**, no pop-back. Then
   `waitUntil(THREAD_TIMEOUT_MS)` for the `WorkspaceChip` token count to reach 0 — the tier flip. (Two waits,
   not one assertion after one wait: name and flag land in the same upsert, but waiting on each independently
   keeps the failure attributable.)
7. **Main-list presence (AC-1 second half, surface #3a)** — tap `CD_BACK`; `waitUntil(LIST_TIMEOUT_MS)` for
   `CD_NEW_DISCUSSION` (the list marker → the thread popped back); `waitUntil(LIST_TIMEOUT_MS)` for
   `onAllNodesWithText(uniqueName, substring = true)` non-empty; `onFirst().assertIsDisplayed()`.
8. **Discussions-tier absence (AC-1 second half, surface #3b — the tier read)** — `waitUntil(LIST_TIMEOUT_MS)`
   for `SEE_ALL_DISCUSSIONS` present, tap it; `waitUntil(LIST_TIMEOUT_MS)` for the **FAB to be gone**
   (`onAllNodes(hasContentDescription(CD_NEW_DISCUSSION)).fetchSemanticsNodes().isEmpty()`) — the arrival
   marker for `DiscussionListScreen`, which has a Back nav icon and **no FAB** (§ Gotcha 3 explains why the
   screen *title* must not be used as the marker). Then `onAllNodesWithText(uniqueName, substring =
   true).assertCountEquals(0)` — the promoted conversation is **not** in the `Discussions` projection.

**Always-on, not `@Ignore`d.** All three post-conditions are **durable** structural facts (the recorded name
and the recorded `isPromoted` flag) — no transient like #482's spinner — so the scenario belongs in the
always-on gate, matching #481's tool-name row, #541's delimiter, and #554's / #562's / #537's inversions.

**No negative control needed.** Assertions key on a runtime-unique token whose absence is asserted first
(step 4) and whose presence is then observed on the surfaces that must show it and whose absence is asserted
on the surface that must not; there is no match-everything to disprove (same reasoning #482 documents).

### Gotchas — the things that will bite a naive clone

**1. The dialog-over-thread two-field disambiguation (identical to #537 — reuse verbatim).**
`SaveAsChannelDialog` opens **over** the thread, whose composer (`ThreadInputBar` `BasicTextField`) is also
editable, so `onNode(hasSetTextAction())` alone throws "more than one node". The dialog **auto-focuses** its
field (`focusRequester.requestFocus()`, `SaveAsChannelDialog.kt:77–79`) and the composer never requests focus,
so **`hasSetTextAction() and isFocused()`** selects the dialog's field; wrap in `waitUntil` so focus has
landed. The field is **pre-filled with `"New channel"` and fully selected** (`TextRange(0,
initialName.length)`, :54) → use **`performTextReplacement(uniqueName)`**, not `performTextInput` (whose
insert-vs-replace-selection behaviour could leave `"New channel"` concatenated with the unique name). `and` is
a `SemanticsMatcher` **member** — do **not** `import androidx.compose.ui.test.and` (it fails to resolve; costs
a compile cycle).

**2. The workspace radio defaults to `DEDICATED` — the scratch option must be tapped (no #537 analogue).**
`SaveAsChannelDialog.kt:56` passes `initialWorkspace = WorkspaceChoice.DEDICATED`, and
`ThreadViewModel.resolveWorkspace` (:899–903) maps `DEDICATED → "pyry-workspace/channels/<slug>"` versus
`SCRATCH → null`. Submitting without touching the radios therefore **creates a real directory on the
operator's machine** on every gate run — the filesystem side-effect the ticket explicitly wants avoided (the
dedicated-folder branch is already covered by the #566 create-workspace-folder scenario). So step 5 **must**
tap "Keep in scratch" before Save. `Modifier.selectable(role = Role.RadioButton)` merges its descendants, so
`onNodeWithText(KEEP_IN_SCRATCH_OPTION).performClick()` resolves to the selectable `Row` and fires
`onSelectedChange(SCRATCH)` — no need to target the `RadioButton` (which is `onClick = null` by design).

**3. Three literal collisions to match exactly, not by substring.**
- `save_as_channel_action` = **"Save as channel…"** (with U+2026) but `save_as_channel_dialog_title` = **"Save
  as channel"** (no ellipsis). A `substring = true` search for "Save as channel" matches both. Use **exact**
  `onAllNodesWithText(SAVE_AS_CHANNEL_ITEM)` for the menu item, and copy the real `…` character into the
  constant.
- `discussion_list_title` (`strings.xml:7`) is **"Recent discussions"** — the *same literal* as
  `recent_discussions_section_header` (`strings.xml:12`), which is already on the **main list**. Using the
  drilldown screen's title as step 8's arrival marker would pass **instantly, before navigating**, and the
  absence assertion would then run against the main list and pass for the wrong reason — a **false green**.
  Use the **FAB's disappearance** as the arrival marker instead (`DiscussionListScreen` has no FAB).
- `save_as_channel_dialog_save` = "Save", the same literal as the existing `RENAME_SAVE`. Declare a separate
  `SAVE_AS_CHANNEL_SAVE` constant (§ Companion constants) rather than reusing `RENAME_SAVE` — no `RenameDialog`
  is involved here, and exact matching keeps it unambiguous against the dialog title.

**4. No top-bar false match (same mechanism as #537).** `SaveAsChannelSubmit` clears
`pendingSaveAsChannelDialog` **synchronously at `ThreadViewModel:842`, before** the `launchGuardedRepoCall`
suspend, so the dialog (whose field held `uniqueName`) leaves composition the instant Save is tapped, while the
top bar still shows the old auto-name until the round-trip lands. The two never hold `uniqueName`
simultaneously, so step 6's first match is the top bar.

### Companion constants

Six new `const val`s in the existing `private companion object`, following the sibling naming convention:

```kotlin
// #581 save-as-channel (promote) scenario. Exact-match literals: the menu item carries a U+2026 ellipsis the
// dialog title does not, so substring matching would conflate them. KEEP_IN_SCRATCH_OPTION is mandatory —
// the dialog defaults to DEDICATED, which would create a real folder on the operator's machine.
const val SAVE_AS_CHANNEL_ITEM = "Save as channel…"
const val KEEP_IN_SCRATCH_OPTION = "Keep in scratch"
const val SAVE_AS_CHANNEL_SAVE = "Save"          // same literal as RENAME_SAVE, different dialog
const val WORKSPACE_CHIP_PREFIX = "Workspace:"   // WorkspaceChip label is a literal, not a string resource
const val SEE_ALL_DISCUSSIONS = "See all discussions"
const val PROMOTE_NAME_PREFIX = "e2e581-"        // runtime-unique: prefix + System.currentTimeMillis()
```

`PROMOTE_NAME_PREFIX` is distinct per the sibling convention (the shared companion forbids redeclaration, and
a unique token keeps repeated LIVE gate runs green — no collision with channels left by prior runs — while not
colliding as a substring with top-bar or list chrome). `CD_NEW_DISCUSSION`, `CD_SEND_MESSAGE`, `CD_BACK`,
`CD_MORE_ACTIONS`, `awaitConnected()`, and the timeout constants already exist — reuse them.

### Cost — zero claude turns

Create-discussion and promote are **daemon round-trips, not claude turns** (promote is a pure registry op
daemon-side), and the durable identity is the typed name, which needs no live session content to be
identifiable, so this scenario spends **no** claude turn — there is no `PING_PROMPT`. It still rides the real
rung-3 stack (real relay, real daemon) and catches exactly the pyrycode#949 failure class — a verb that
answers `unsupported` on the production wire — which is the whole reason the gate exists. The LIVE gate
therefore goes from a **septet (7 methods)** to an **octet (8 methods)** at **still 3 turns** (AC-3): promote
adds a method, not a turn, joining delete, archive-restore, change-workspace, and rename as a zero-turn member.

## State + concurrency model

None new. The test drives the shipped MVI surface through Compose/Espresso synchronization (`waitUntil` +
`assertIsDisplayed` / `assertCountEquals`) and reads the relay connection via the existing `awaitConnected()`
`ConnectionStateSource` helper. No coroutine, dispatcher, or `StateFlow` is introduced. The promote path
(`SaveAsChannelSubmit → repository.promote → request/reply → confirmed upsert → observeConversations(All)
re-emit → state.displayName + state.isPromoted + both list projections`) is the canonical shipped #348 flow —
the e2e proves it against a real daemon, exercising nothing the Fake synthesizes.

## Error handling

Test-side only: every wait is `waitUntil(<generous timeout>)`, which throws `ComposeTimeoutException` on a
genuinely stuck flow — the correct red signal. `repository.promote` runs inside `launchGuardedRepoCall`
(`ThreadViewModel:843`), which **swallows a failed wire silently**; the failure then surfaces as the step-6
top-bar wait timing out (the top bar never re-labels), which is the intended red — and is precisely what an
`unsupported` reply from a pre-#949 daemon produces (AC-5). No new production error paths. Real-claude
variance is not a factor (no claude turn); the daemon round-trip is fast, so `THREAD_TIMEOUT_MS` /
`LIST_TIMEOUT_MS = 30_000L` are ample. All assertions are tolerant (substring, presence/absence, generous
timeouts) per the `docs/e2e-interactive-stream.md` Constraints.

## Testing strategy

Instrumented (`androidTest`), run by `scripts/e2e-emulator.sh` on a headless emulator against a host `pyry` +
relay (rung 3), and by the `LIVE=1` pre-ship gate (`scripts/e2e-preship-gate.sh`, #529) against the production
relay. **Not** covered by `./gradlew test` (unit). Developer verification:

- **Compiles:** `./gradlew compileDebugAndroidTestKotlin` — the only gate that compiles `androidTest`
  (`test` / `lint` / `assembleDebug` skip it), so the only way to catch a bad selector or import before a
  device run. Every matcher used (`hasSetTextAction`, `isFocused`, `performTextReplacement`, `onNodeWithText`,
  `onAllNodesWithText`, `hasContentDescription`, `assertCountEquals`, `assertIsDisplayed`, `onFirst`) is
  already imported by the sibling scenarios; `and` is a member (no import). Let the compile gate resolve the
  final import set.
- **Script sanity:** `bash -n scripts/e2e-emulator.sh`, and eyeball that the comma-separated `class` arg (now
  **8** `class#method` tokens) is a single unbroken shell token.
- **Green in the gate (AC-3, AC-5):** `bash scripts/e2e-preship-gate.sh` — an operator-run, real-stack check
  that now executes eight curated methods against a daemon built at or after pyrycode/pyrycode#949. That green
  LIVE run is the operator's pre-ship confirmation, not something the developer's turn budget must reproduce
  headlessly. **The scenario is expected RED on any older daemon binary — that is the regression it exists to
  catch**, and a red there is a finding about the daemon, never a reason to weaken the test.

## `scripts/e2e-emulator.sh` change (LIVE septet → octet)

Append the new method to the LIVE `TEST_TARGET` comma-separated `class#method` list (line **468**), so the gate
runs an **octet**: ping + create-workspace-folder + new-session + delete + archive-restore + change-workspace +
rename + **save-as-channel**. Contract (shape, not a paste — append to the existing single-line value):

```sh
# scripts/e2e-emulator.sh, LIVE branch (line 468) — append the 8th method:
…,${TEST_CLASS}#interactiveTurn_saveAsChannel_promotesToChannelTier"
```

The #481 tool-use test stays LIVE-excluded for cost — this is **additive**. Refresh the "septet / 7 methods /
still 3 turns" prose to "**octet** / 8 methods, **still 3 turns** (promote spends none — create/promote are
daemon round-trips)": the run-block comment (lines 455–460), the LIVE-branch comment (464–467), and the PASS
log line (**485**, append the promote clause to its list of rendered flows). Grep to find every spot so none
drifts (AC-4):

```bash
grep -rn 'septet\|7 methods\|still 3 turns\|sextet' scripts/e2e-emulator.sh docs/e2e-interactive-stream.md
```

## Doc note (`docs/e2e-interactive-stream.md`)

Mirror how #537 — the last-merged sibling — was wired in (AC-4). Update: the ladder / curated-set line
(~46–54, **septet → octet**, still 3 turns), § "What rung 3 is made of" (~138–162, add the scenario next to the
rename entry), the per-scenario notes + always-on rationale block (~192–249), the turn accounting (~327–340),
§ Live mode "What it runs" (~350–369 and ~403), and a Follow-ups entry (~799–818) mirroring the "rename —
**shipped (#537)**" line: "save-as-channel — **shipped (#581)**, … taking the gate from a septet to an octet at
still 3 turns."

Key facts to state: the load-bearing signals are **three durable** post-conditions (the unique channel name on
the **thread top bar** in-thread, the **`WorkspaceChip` unmount** as the `isPromoted` tier flip, and
**presence on the main list ∧ absence from the Discussions drilldown**); always-on (durable, unlike the
`@Ignore`d spinner); promote is **conversation-scoped** with **no session transition**, so the assertion is the
recorded **name and promotion flag**, never a session id; the reply is a **bare conversation object folded by a
confirmed upsert**, **not** a `conversation_updated` broadcast; the scenario **keeps the conversation in
scratch** so the gate leaves no folder behind; total real-claude cost is **zero** turns, so the LIVE gate stays
at 3 turns across 8 curated methods; and this is the **backfill** of the one pre-pyrycode-mobile-agents#9 flow,
red against any daemon older than pyrycode/pyrycode#949.

Also worth a line in the doc's rationale: **why the tier assertion needs the drilldown** (identical row content
descriptions, no section semantics) — it is the non-obvious fact a future sibling scenario will want.

## Open questions / first-run assumptions (grounded, unverified end to end)

- **The Discussions drilldown affordance exists at step 8 (the one real assumption).**
  `RecentDiscussionsSection` returns early when `discussions.isEmpty()` (`ChannelListScreen.kt:246`), so
  `SEE_ALL_DISCUSSIONS` is absent iff the operator's daemon has **zero** discussions after the subject is
  promoted. In practice it will not: every sibling scenario leaves its discussion behind (ping, new-session,
  create-workspace-folder, change-workspace, rename all end with one; archive-restore restores its own), and
  they persist on the operator's real daemon across gate runs. A truly fresh daemon running this method first
  would red at step 8's `waitUntil` — a **loud, diagnosable false red**, not a false green. Deliberately **not**
  defended against (no bystander-discussion seeding): the failure mode has not been observed, and the repo's
  evidence-based-fix rule says defer. If it ever fires, the remedy is one extra `CD_NEW_DISCUSSION` tap + Back
  before step 3 to seed a bystander discussion.
- **The promote reply reaches `state.displayName` and `state.isPromoted` before step 6 completes.** Both are
  derived from `observeConversations(All)` (`ThreadViewModel:242–243`), which the confirmed upsert re-emits.
  Step 6's two `waitUntil`s cover the round-trip; if the top bar never re-labels or the chip never unmounts,
  that is a promote-wire finding (very possibly the pyrycode#949 regression this test exists to catch), not a
  reason to weaken the test.
- **`CD_BACK` returns to the channel list.** The thread was entered from the list (step 3), so `PopBack`
  returns there. Confirm on first run that the list — not some other back-stack entry — is what renders (step 7
  keys on `CD_NEW_DISCUSSION`, the list marker).
- **Tapping "Keep in scratch" registers before Save.** The radio row is a merged `selectable`, so the tap
  should land on it; if a first run shows the promote still moved the conversation to a dedicated folder (the
  `WorkspaceChip` is gone by then, so check the daemon's `~/pyry-workspace/channels/`), assert
  `assertIsSelected()` on the scratch row between the tap and Save — rung 3 permits selector tuning.

## Acceptance criteria (developer deliverable)

- [ ] New always-on `@Test` in `InteractiveStreamE2ETest` (rung 3) creates a scratch discussion, drives the
      real overflow **"Save as channel…"** affordance → `SaveAsChannelDialog` to a runtime-unique channel name
      (`hasSetTextAction() and isFocused()` + `performTextReplacement`), **selects "Keep in scratch"**, submits,
      and completes the `promote_conversation` daemon round-trip.
- [ ] It asserts the round-trip **durably**: the **thread top bar** re-labels to that name **in place** (the
      flow does not pop back), the **`WorkspaceChip` unmounts** (the `isPromoted` tier flip, in-thread), and
      after tapping Back the name is **present on the main list** and **absent from the Discussions drilldown**
      — i.e. presented in the promoted (channel) tier rather than as a recent discussion.
- [ ] The target name is **runtime-unique** (`PROMOTE_NAME_PREFIX + System.currentTimeMillis()`), its
      **absence asserted before** the submit (`assertCountEquals(0)`), so each presence assertion is a genuine
      before → after inversion and repeated LIVE-gate runs stay green.
- [ ] The scenario is registered in the LIVE rung-3 pre-ship gate lane (the #529 gate command): a test method,
      an entry appended to the comma-separated `class#method` `TEST_TARGET` in `scripts/e2e-emulator.sh`
      (**septet → octet**), the script's header/run-block comments and PASS message refreshed, and the matching
      scenario notes and counts updated in `docs/e2e-interactive-stream.md`; the LIVE gate stays at **3 real
      claude turns** (promote spends none).
- [ ] Assertions are tolerant (generous timeouts, substring / presence-absence, never a delta count), matching
      the rung-3 real-claude ladder.
- [ ] `./gradlew compileDebugAndroidTestKotlin` is green (androidTest is not compiled by the mandatory gates).
