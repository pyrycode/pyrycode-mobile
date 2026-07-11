# Spec — rung-3 real-claude e2e for change-workspace round-trip (#562)

**Ticket:** [#562](https://github.com/pyrycode/pyrycode-mobile/issues/562) — `test(e2e): rung-3 real-claude scenario for change-workspace round-trip`
**Size:** S · **Security-sensitive:** no (test-only; drives the already-secured #560 wire through shipped UI)
**Split from:** #533 (change-workspace wire). Sibling e2es: #541 (new-session), #554 (delete), #551 (archive/restore), #566 (create-workspace-folder).

## Context

Org policy 2026-07-08: an operator-facing happy-path flow ships with a rung-3 e2e that runs in the LIVE pre-ship gate. This is the change-workspace follow-up #533's DoD note asked PO to file. It adds **one** `@Test` to `InteractiveStreamE2ETest` — a line-for-line clone of the existing conversation-scoped rung-3 scenarios — plus a `TEST_TARGET` list entry and a docs scenario note. **Zero production code changes.**

**Why it is buildable now.** PR #572 flipped `RemoteConversationRepository.mutationsSupported` to `true` in relay mode, so the overflow "Change workspace…" item (which is `mutationsSupported`-gated but **not** promotion-gated — see `ThreadOverflowMenu.kt:49,64`) is reachable on a plain **discussion** live — the same real overflow the operator uses. Earlier revisions parked this on a catch-22 (chip `!hasMessages`-gated *and* overflow `mutationsSupported`-gated); PR #572 broke the second half, and the overflow path drives the wire independently of the chip's message gate.

**Why it is not session-blocked (unlike #545).** `change_workspace` is **conversation-scoped** — the wire is `{conversation_id, cwd}` (a line-for-line mirror of `rename`), performs **no** session transition, and carries none of the `currentSessionId == ""` blocker that re-parks the settings e2e (#545). It is in the clean-buildable camp with the already-shipped conversation-scoped e2es (#541 / #554 / #551).

## Design source

N/A — test-only ticket. It drives already-shipped UI (the overflow "Change workspace…", the Workspace Picker, the `CreateFolderDialog`, and the `WorkspaceChip` — all landed in #560 / #561 / #564 / PR #572). No new visual surface is authored, so no Figma anchor is required; the visual-fidelity check is intentionally out of scope, consistent with the test-only siblings #541 / #554 / #551 which shipped without a Design source section.

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` — **the clone template.** Read in full. Most load-bearing:
  - `:307-377` `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` — the create-folder sub-flow (picker → "Create new folder…" → dialog → type unique name → Create) and the chip assertion on `folderName`. **This scenario's create-folder half is identical**; the only differences are the *entry* (thread overflow "Change workspace…" instead of FAB long-press) and that there is **no** navigation to a new discussion — we stay on the same thread and its chip re-labels.
  - `:514-602` `interactiveTurn_deleteConversation_removesFromListAndClosesThread` (#554) — the overflow-driving spine (open `CD_MORE_ACTIONS` → wait for item → tap) and the "twin of #554" KDoc style to mirror.
  - `:796-908` the `companion object` — reuse `CD_NEW_DISCUSSION`, `CD_SEND_MESSAGE`, `CD_MORE_ACTIONS`, `CREATE_FOLDER_ROW`, `CREATE_BUTTON`, and the timeout constants; add only the two new constants below.
  - `:787-794` `awaitConnected()` — reuse verbatim.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt:49-70` — the "Change workspace…" item lives inside `if (mutationsSupported)` and is **not** promotion-gated → reachable on a plain discussion.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:488-499` (`onWorkspacePicked` → `sendChangeWorkspace`), `:695-705` (`sendChangeWorkspace` → `repository.changeWorkspace`), `:934-938` (`Conversation.workspaceLabel()` = `cwd.substringAfterLast('/')` basename) — confirms the picked path becomes the conversation's `cwd` and the chip re-labels to its basename.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:201-204` (chip visibility gate `!state.isPromoted && !state.hasMessages`), `:317-321` (`WorkspacePicker(onPicked = onWorkspacePicked)`) — the chip is the durable assertion surface only while there are no messages.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePicker.kt:62-92` — `onCreate` sets `showCreateDialog = false` **synchronously** (before the suspend), then `onPicked(repository.createWorkspaceFolder(name))`. Grounds the transient-match reasoning in § Gotchas.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspaceChip.kt:17-34` — renders `"Workspace: $workspaceLabel (change)"`; the unique folder name lands here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerSheet.kt:64-76,169` — the `"Recent"` header and `"Create new folder under pyry-workspace…"` row strings (== `RECENT_SECTION` / `CREATE_FOLDER_ROW`).
- `app/src/main/res/values/strings.xml:67` — `thread_overflow_change_workspace = "Change workspace…"` — the trailing char is a real `…` (U+2026); match the substring `"Change workspace"` to sidestep it (mirrors `CREATE_FOLDER_ROW` substring matching).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:137` (`suspend fun changeWorkspace`), `:182` (`suspend fun createWorkspaceFolder(name): String`), `:164` (`recentWorkspaces`) — the two round-trips the flow chains.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:386-397` — confirms `onWorkspacePicked = vm::onWorkspacePicked` is wired live (shipped #561).
- `scripts/e2e-emulator.sh:454-486` — the LIVE `TEST_TARGET` curation (line 466), the curation comments, and the PASS message to update (§ Script delta).
- `docs/e2e-interactive-stream.md` — sections to extend: the ladder (`:25-44`), "What rung 3 is made of" (`:117-183`), Pre-ship gate (`:250-275`), Live mode (`:283-334`), Follow-ups (`:702-736`) (§ Docs delta).

## Design

One always-on `@Test` in `InteractiveStreamE2ETest`, riding the #421 LIVE harness (real app on a headless emulator → host `pyry` daemon → LIVE production relay over `wss://`). It spends **zero** real claude turns — `change_workspace` and `create_workspace_folder` are daemon round-trips, not claude turns — so the LIVE gate goes from a **quintet (5 methods) to a sextet (6 methods) at still 3 real claude turns**.

### Flow

Create discussion → give it a runtime-unique **new** workspace via the real overflow "Change workspace…" → Workspace Picker → "Create new folder…" (the target-path source per the ticket) → assert the conversation's recorded workspace durably flips to the new path, read off the `WorkspaceChip`.

### Recommended step sequence (developer writes the body in the file's idiom)

1. `waitUntil(LIST_TIMEOUT_MS)` for the `CD_NEW_DISCUSSION` FAB (channel-list marker), then `awaitConnected()` — the picker's create + change round-trip to the daemon, so the session must be Open first.
2. Tap `CD_NEW_DISCUSSION` → `waitUntil(THREAD_TIMEOUT_MS)` for the `CD_SEND_MESSAGE` send button (thread-arrival marker). A plain discussion suffices — "Change workspace…" is `mutationsSupported`-gated, reachable on it.
3. Compute `val newWorkspace = WORKSPACE_FOLDER_PREFIX + System.currentTimeMillis()`. **Absence guard (the before-state):** `onAllNodesWithText(newWorkspace, substring = true).assertCountEquals(0)` — the unique name is not on screen yet, so its later appearance in the chip is attributable to the change. Deterministic; no claude turn.
4. Drive the **real** overflow: tap `CD_MORE_ACTIONS` → `waitUntil` for the `"Change workspace"` substring item → tap it. The picker sheet opens (`pendingWorkspacePicker = true`).
5. `waitUntil` for `CREATE_FOLDER_ROW` (substring) → tap it → `waitUntil` for the editable field (`hasSetTextAction()`) → `performTextInput(newWorkspace)` → tap `CREATE_BUTTON`.
6. **After-state:** `waitUntil(THREAD_TIMEOUT_MS) { onAllNodesWithText(newWorkspace, substring = true).fetchSemanticsNodes().isNotEmpty() }`, then `onAllNodesWithText(newWorkspace, substring = true).onFirst().assertIsDisplayed()`. The single wait covers **both** sequential daemon round-trips (create → change); on success the chip reads `"Workspace: <newWorkspace> (change)"`.

The before (step 3, absent) → after (step 6, present-in-chip) is a genuine inversion on the same surface, and the unique suffix keeps repeated LIVE-gate runs green (no collision with folders left by prior runs, no pre-existing on-screen match).

### New constants (add to the shared `companion object`)

- `WORKSPACE_FOLDER_PREFIX = "e2e562-"` — runtime-unique target-folder prefix; distinct from #566's `"e2e566-"` (the shared companion forbids redeclaration). Clean single path element (lowercase alphanumerics + dash — the daemon rejects empty / absolute / separator-bearing / `..` names).
- `CHANGE_WORKSPACE_ITEM = "Change workspace"` — the overflow item, matched as a **substring** (the production string is `"Change workspace…"` with a real U+2026 ellipsis).

Reuse `CREATE_FOLDER_ROW`, `CREATE_BUTTON`, `CD_NEW_DISCUSSION`, `CD_SEND_MESSAGE`, `CD_MORE_ACTIONS`, and the timeout constants unchanged.

### Method name

Propose `interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace`. The developer may finalize the name, but it **must** match byte-for-byte the `class#method` string added to the script's `TEST_TARGET` list (§ Script delta) — a mismatch silently drops the method from the gate.

## State + concurrency model

None — this is an instrumented UI test, not a ViewModel change. It observes production state via Compose semantics (`waitUntil` / `onAllNodes…`) and reuses `awaitConnected()` (a `runBlocking { withTimeout { source.observe().first { Connected } } }` on the injected `ConnectionStateSource`). No new coroutine, StateFlow, or dispatcher is introduced.

## Error handling / assertion discipline

Semi-deterministic by nature (real daemon over the live relay) → every assertion is **tolerant**: substring + `ignoreCase` where relevant, generous timeouts, presence/absence — never a delta-count or timing assertion (the ladder's "never on timing" rule). The `create_workspace_folder` and `change_workspace` failure paths already surface generic messages / one-shot error signals in production (#561 `changeWorkspaceErrors`, #564 `CREATE_FOLDER_ERROR_MESSAGE`); this happy-path test does not exercise them.

**No negative control** (deliberate divergence from the ping / tool-use siblings, matching #541 / #554 / #551). Those assert on real-claude **output** substrings and ship an `@Ignore`d control to prove the matcher is selective. Here the asserted token is a runtime-unique folder name that cannot pre-exist on screen, and the step-3 absence guard already proves selectivity — there is nothing for a negative control to disprove.

## Gotchas (the load-bearing correctness notes)

- **Two sequential daemon round-trips, one wait.** The Create tap chains `createWorkspaceFolder` (returns the canonical path) → `onWorkspacePicked` → `sendChangeWorkspace` → `changeWorkspace` → `conversation_updated` → the projection re-emits with the new `cwd` → the chip re-labels. Step 6's single `waitUntil` must span **both**; use a generous timeout (`THREAD_TIMEOUT_MS` is enough for two daemon round-trips — bump only if the live relay proves slow on first operator run; rung 3 permits timeout tuning).
- **The unique name's only post-Create on-screen home is the chip.** `onCreate` sets `showCreateDialog = false` **synchronously before** the suspend (`WorkspacePicker.kt:71-74`), so the dialog's text field (which held `newWorkspace`) is gone the instant Create is tapped; the picker sheet then closes on `onPicked`; and the just-created folder is **not** yet in the picker's "Recent" (a folder becomes recent only once used). So there is no transient false match — `onFirst().assertIsDisplayed()` unambiguously lands on the chip. (No need to pre-wait for the picker to dismiss.)
- **Assert the recorded cwd, not a session id.** `change_workspace` is conversation-scoped and performs **no** session transition; the assertion targets the `WorkspaceChip` (recorded `cwd` basename), so it does not reintroduce the session-scoped dependency that parks #545.
- **The chip is the assertion surface only because no message is sent.** The `WorkspaceChip` is gated `!isPromoted && !hasMessages` (`ThreadScreen.kt:201`). This scenario spends no claude turn (no ping), so `hasMessages` stays false and the chip stays mounted throughout — the reason the chip, not a session artifact, is the durable post-condition.
- **Ellipsis in the overflow string.** `thread_overflow_change_workspace = "Change workspace…"` ends in a real U+2026; match the substring `"Change workspace"` (mirrors how `CREATE_FOLDER_ROW` drops the trailing ellipsis).

## Script delta — `scripts/e2e-emulator.sh`

1. **Functional:** append the new method to the LIVE `TEST_TARGET` comma-separated list at **line 466** (after the archive-restore entry): `,${TEST_CLASS}#interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace` (use the finalized method name).
2. **In-file consistency (mirror what #551 did for quartet→quintet):** update the comments in the same file that enumerate the curated set and its count so the file does not contradict itself — code-review flags a stale "quintet" beside a 6-method list. Sites: header block (`:9-12`), Usage line (`:56`), the curation comment (`:454-458`), the LIVE-branch comment (`:461-466`), and the LIVE PASS message (`:483`). In each: `quintet → sextet`, `five → six` / `5 methods → 6 methods`, and add `change-workspace` to the method enumeration. **Preserve the "still 3 (real claude) turns" invariant** everywhere — change-workspace adds a method, not a turn (daemon round-trip).

The `README` §Pre-ship gate count is **out of scope** — it is the separately-owed doc-sync ticket noted in #551's DoD (quartet→quintet already stale there); do not touch it here.

## Docs delta — `docs/e2e-interactive-stream.md`

Mirror the #554 / #551 deltas: add a change-workspace scenario and bump the LIVE-set count/enumeration. Sites:

- **The ladder** (`:25-44`): add a one-clause change-workspace description alongside the delete/archive clauses; `quintet → sextet`.
- **What rung 3 is made of** (`:117-183`): add a detailed change-workspace paragraph in the same shape as the #554/#551 paragraphs (drive the real overflow → picker → create folder → chip re-labels; conversation-scoped, zero claude turns, always-on because the chip re-label is a durable artifact).
- **Pre-ship gate** (`:262-263`): add `change-workspace, #562` to the enumerated methods; `five curated → six curated`.
- **Live mode (rung 3, live relay)** (`:283-334`): add the change-workspace method to the curated list; `quintet → sextet`, `five curated methods → six curated methods`; keep `three real claude turns`.
- **Follow-ups to ticket** (`:702-736`): add a change-workspace entry; `quintet → sextet` (`taking the gate from a quintet to a sextet at still 3 turns`).

## Testing strategy

The deliverable **is** the test. It is instrumented (`androidTest`) and runs **only** in the LIVE gate (`LIVE=1 bash scripts/e2e-emulator.sh`), not the deterministic emulator gate and not the default whole-class rung-3 run's cost accounting differently — it is added to the curated `TEST_TARGET` list, so it executes when the LIVE gate runs. Local compile check before commit: `./gradlew compileDebugAndroidTestKotlin` (the mandatory `test`/`lint`/`assembleDebug` gates do **not** compile `androidTest` — see the project lesson on that). No `./gradlew test` unit coverage applies (no production code changed).

## Open questions

- **Live-relay latency for two chained round-trips.** `THREAD_TIMEOUT_MS` (30 s) should comfortably cover create→change over `wss://`; if the first operator run shows it tight, bump the step-6 wait timeout (rung 3 permits timeout/selector tuning on first run — the harness KDocs establish this norm). Not a design risk, a tuning knob.
- **Folder accumulation under the operator's real `~/pyry-workspace`.** Each LIVE run creates one new folder (the #566 pattern; accepted there and by this ticket). The unique suffix prevents collisions; periodic cleanup of `~/pyry-workspace/e2e5*` is an operator chore, not a test concern.
