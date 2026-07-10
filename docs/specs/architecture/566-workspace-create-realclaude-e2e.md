# 566 — Rung-3 real-claude e2e: create a workspace folder and use it as a live session's workspace

**Ticket:** [#566](https://github.com/pyrycode/pyrycode-mobile/issues/566) · **Size:** S · **Not** `security-sensitive`

## Design source

N/A — test-only ticket. It exercises already-shipped, already-reviewed UI (#564 create-folder wire, #565 recents wire) and adds **no production surface**, so there is nothing to design and the visual-fidelity check is intentionally skipped (ticket body: "Not UI-visible (test-only, exercises existing UI) → no Figma").

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt:62-97` — the shipped `interactiveTurn_pingPrompt_streamsPingReplyIntoThread` scenario. **This is the template.** Clone its structure (list-marker wait → `awaitConnected()` → create discussion → type prompt → send → tolerant substring assertion) and its helpers (`awaitConnected`, `pingNodeCount`, companion constants `CD_NEW_DISCUSSION`, `CD_SEND_MESSAGE`, `PING`, `PING_PROMPT`, the timeout constants). Read the class KDoc (lines 26-47) too — it states the tolerance discipline every assertion must follow.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt:288-329` — the `companion object`. Your new constants (folder-name prefix, picker strings) go here alongside the existing ones; reuse `REPLY_TIMEOUT_MS`, `THREAD_TIMEOUT_MS`, `LIST_TIMEOUT_MS`, `PING`, `PING_PROMPT` verbatim.
- `scripts/e2e-emulator.sh:451-478` — the test-run block. Line 457-458 is the **LIVE branch** that today hardcodes `${TEST_CLASS}#interactiveTurn_pingPrompt_streamsPingReplyIntoThread` (a single method). The one edit here is to make LIVE run *two* methods (comma-separated `class#method` list). Lines 452-454 (comment) and 475 (PASS log) also mention "the ping method"/"'ping' rendered" — update the prose so it is not stale. `TEST_CLASS` is set at line 85.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerSheet.kt:69-76,151-174` — the sheet's production strings you select against: `SectionHeader("Recent")` (only rendered when `recent.isNotEmpty()`), `SectionHeader("Other")`, and the create row text `"Create new folder under pyry-workspace…"` (line 169). No test tags — text selectors only.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialog.kt:59-102` — the create dialog. Title `"Create workspace"`, the single `OutlinedTextField` (the only `hasSetTextAction()` node once the dialog is open), confirm button `"Create"`, dismiss `"Cancel"`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:113-122,200-215` — the FAB. It is one node carrying `contentDescription = "New discussion"` **and** a `combinedClickable(onLongClick = …)`. A **long-press** (not a tap) opens the picker (`ChannelListEvent.LongPressFab`). In a Compose test that is `performTouchInput { longClick() }` on the `hasContentDescription(CD_NEW_DISCUSSION)` node.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:136-144` — `LongPressFab` → picker visible; `WorkspacePicked(path)` → `createDiscussion(workspace = path)` → navigate `ToThread`. This is *why* creating a folder lands you in a fresh discussion whose `cwd` is the created folder.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspaceChip.kt:23-25` — the thread's workspace affordance. Renders literally `"Workspace: $workspaceLabel (change)"`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:201-210` — **critical gating:** the chip renders only when `!state.isPromoted && !state.hasMessages`. Once the thread has any message the chip is gone. This is the load-bearing constraint that dictates AC-3's re-open path (see § Design).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:934-939` — `Conversation.workspaceLabel()` = `cwd.substringAfterLast('/')` (the folder's basename, unless scratch → `"scratch"`). This is why a unique folder name shows up verbatim in the chip.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt:42` — the thread's back nav icon carries `contentDescription = cd_back` (`"Back"`, `res/values/strings.xml:9`). AC-3's return-to-list step selects this.
- `app/src/main/java/de/pyryco/mobile/data/network/CreateWorkspaceFolderPayloads.kt` (whole file) + `RemoteConversationRepository.kt:1745-1855` — `createWorkspaceFolder(name)` sends `{parent: "~/pyry-workspace", name: name.trim()}`, awaits `workspace_folder_created{path}`, and returns the daemon's **canonical realpath**. The daemon rejects a `name` that is not a clean single path element (empty / absolute / contains a separator / `..`). Constrains the collision-resistant name you may generate.
- `docs/e2e-interactive-stream.md:166-205` (§ Pre-ship gate) — how the LIVE gate is documented; the KDoc of the new test should stay consistent with it. **Read-only** (shared doc, documentation phase owns it) — do not edit; the new test's own KDoc carries the scenario's documentation.

Relevant memory/lessons: **androidTest is not compiled by the mandatory gates** — `./gradlew test`, `lint`, `assembleDebug` all skip `androidTest`. Verify the new test compiles with `./gradlew compileDebugAndroidTestKotlin`. `onNode` / `onAllNodes` / `performTouchInput` / `longClick` are Compose-test members/functions — mind the imports (`androidx.compose.ui.test.*`).

## Context

Org policy (2026-07-08): an operator-facing happy-path flow ships with a real-claude e2e that runs in the pre-ship gate. The create-workspace-folder flow shipped in #564 and its recents companion in #565; this ticket is the deferred rung-3 e2e that keeps that happy path from regressing unnoticed before a release. It rides the shipped #421/#481/#482/#527 harness — a `@Test` in `InteractiveStreamE2ETest`, run by `scripts/e2e-emulator.sh`, executed against a live relay + real `pyry` by `scripts/e2e-preship-gate.sh` (`LIVE=1`).

**Reachability is confirmed positive** (ticket body + PO re-verification, 2026-07-10): the create affordance is **not** behind the `mutationsSupported` gate that makes the #537 rename family unbuildable. The FAB long-press → Workspace Picker → "Create new folder…" path is ungated on the live build, so this scenario **is** buildable and ships a real, mergeable slice.

## Design

Two files change; **neither is a production source file** (one androidTest, one shell script). No new types, no production surface.

### A. The new `@Test` — `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace`

A single non-`@Ignore` test method added to `InteractiveStreamE2ETest`, cloning the ping scenario's shape and tolerance discipline. It walks the real create-folder flow end to end against real claude, in this order:

1. **Land on the channel list.** Wait `LIST_TIMEOUT_MS` for `hasContentDescription(CD_NEW_DISCUSSION)` (the FAB marks the list). Then `awaitConnected()` — the picker's create round-trips to the daemon, so the relay session must be Open first (same reason the ping test waits before creating).
2. **Open the Workspace Picker.** `performTouchInput { longClick() }` on the FAB node (`hasContentDescription(CD_NEW_DISCUSSION)`). A *tap* would create a scratch discussion; a *long-press* opens the picker (`combinedClickable.onLongClick` → `LongPressFab`). Wait for the picker's create row: `onAllNodesWithText("Create new folder under pyry-workspace…")` non-empty.
3. **Create the folder.** Click the create row → the `CreateFolderDialog` opens. Type a **collision-resistant** folder name into the dialog's only `hasSetTextAction()` node, then click `"Create"` (see § Folder naming). On success the returned realpath flows to `onPicked` → `WorkspacePicked` → `createDiscussion(workspace = path)` → navigation into the new discussion's thread.
4. **Assert we landed in the thread with the created folder as its workspace (AC-1).** Wait `THREAD_TIMEOUT_MS` for the send button (`hasContentDescription(CD_SEND_MESSAGE)`, the thread marker). Then assert the workspace chip reflects the folder: `onAllNodesWithText(folderName, substring = true)` is non-empty and `onFirst().assertIsDisplayed()`. `workspaceLabel()` = the folder's basename, and the chip renders `"Workspace: <basename> (change)"`, so the unique folder name is present verbatim. (The chip is still visible here — no messages yet.)
5. **Send the constrained ping in the new workspace and assert the reply renders (AC-2).** Reuse the ping test's tail verbatim: type `PING_PROMPT` into `hasSetTextAction()`, click send, snapshot `pingNodeCount()` as `baseline`, wait `REPLY_TIMEOUT_MS` for `pingNodeCount() > baseline`, then `onAllNodesWithText(PING, substring = true, ignoreCase = true).onFirst().assertIsDisplayed()`. This proves the created folder is usable as a live session's workspace against a real claude turn.
6. **Re-open the picker and assert the folder is in "Recent" (AC-3).** The thread now has messages, so the workspace chip is **gone** (`!hasMessages` gate). Re-open the picker from the **channel list**, not the thread:
   - Click the thread back nav icon (`hasContentDescription("Back")`) → wait for the FAB (`CD_NEW_DISCUSSION`) to reappear (list marker).
   - `performTouchInput { longClick() }` on the FAB again → wait for the sheet.
   - Assert the "Recent" section is present (`onAllNodesWithText("Recent")` non-empty) **and** the created folder appears in it: `onAllNodesWithText(folderName, substring = true)` non-empty → `onFirst().assertIsDisplayed()`. (The recents list re-fetches on every picker open — it is a cold one-shot flow, #565 — so the folder used by step 3/5 is present.)

Every wait is tolerant (existing generous timeouts, substring/case-insensitive, never a delta-count or timing assertion), matching the ladder's rung-3 discipline. The load-bearing signal in each assertion is a **durable** on-screen artifact (the chip text, a rendered "ping" node, the recents row) — never a transient like the thinking spinner.

**Why AC-3 re-opens from the list, not the thread (the one non-obvious call).** The `WorkspaceChip` — the thread's only in-place picker entry — is gated on `!state.hasMessages` (`ThreadScreen.kt:201`). AC-2 sends a message, so by the time AC-3 runs the chip has unmounted and cannot re-open the picker. The channel-list FAB long-press is the same ungated entry point AC-1 already uses, so it needs no new selector beyond the `"Back"` nav icon. Sequencing AC-3 *after* the ping also means the folder has unambiguously been used by an active session, so its presence in the daemon's recents is not sensitive to exactly when the daemon records a workspace as "recent."

**KDoc.** Give the method a KDoc in the style of the ping/tool-use siblings: what it exercises (the #564 create + #565 recents wires end to end against real claude), the reachability note (ungated, unlike #537), and the AC-3 gating rationale above. Keep it consistent with `docs/e2e-interactive-stream.md` § Pre-ship gate.

### B. Extend the LIVE branch of `scripts/e2e-emulator.sh`

The LIVE branch scopes the run to a single method; a new `@Test` is **not** auto-discovered there (it *is* in the default whole-class rung-3 run — see § Consequences). The `class` instrumentation-runner argument accepts a comma-separated list of `fqcn#method` entries, so extend LIVE to run both curated methods. Contract (the shape of the edit, not a paste):

```sh
# scripts/e2e-emulator.sh, LIVE branch (currently line 457-458)
elif [ -n "${LIVE}" ]; then
  # LIVE curates its real-claude turns: ping + create-workspace-folder (2 turns). The class' #481
  # tool-use test is still excluded from LIVE for cost (it runs in the default whole-class rung-3).
  TEST_TARGET="${TEST_CLASS}#interactiveTurn_pingPrompt_streamsPingReplyIntoThread,${TEST_CLASS}#interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace"
```

Also refresh the two stale prose spots so they do not claim "ping only": the comment at lines 452-454 and the LIVE PASS log at line 475 (e.g. "…sent the prompts, and both the ping reply and the created-workspace flow rendered"). Do not touch the `DETERMINISTIC` or default branches, the runner-arg block (465-470), or any relay/pairing setup.

### Folder naming (collision-resistance)

`WORKSPACE_FOLDER_PARENT` is `~/pyry-workspace` on the operator's **real** `$HOME` (LIVE isolates the pyry instance *name*, not `$HOME` — #527), and the gate does not clean between runs, so a fixed name would collide/accumulate on the second run. Generate a unique name at test runtime and hold it in a local `val` reused across the chip assertion (step 4) and the recents assertion (step 6):

- Shape: a **clean single path element** — lowercase alphanumerics + dashes, no separator, not `..`, non-empty (the daemon rejects anything else). E.g. `"e2e566-" + System.currentTimeMillis()` (optionally a short random suffix for extra safety). `System.currentTimeMillis()` / `UUID.randomUUID()` are fine in device-test code.
- Uniqueness matters for the assertions too: because the name is unique, a substring match on it (in the chip and in the recents row) cannot be satisfied by unrelated UI text, so it is a genuine presence check — the same discipline the ping/tool tests use for their tokens.

## State + concurrency model

None introduced. The test drives the real production ViewModels/repository through the shipped Koin graph (paired + relay-backed `E2eTestApplication`, swapped in by `E2eInstrumentationRunner` from the instrumentation args the script passes). `awaitConnected()` (copied from the ping test) blocks on the `ConnectionStateSource` `StateFlow` until `Connected`. Recents is a cold one-shot flow re-collected on each picker open (#565), which is what makes the AC-3 re-open observe the freshly-used folder without any test-side state management.

## Error handling

The test asserts only the happy path; failure surfaces as a `waitUntil` timeout → test FAIL, which is the exact red signal the gate exists to produce (a down/stale relay, a broken create wire, or a recents regression all surface this way). All timeouts are the existing generous constants (`REPLY_TIMEOUT_MS = 90_000L` for the real-claude turn; `THREAD_TIMEOUT_MS`/`LIST_TIMEOUT_MS = 30_000L` for UI transitions). No new error-handling code; no negative control is needed (unlike the ping/tool substring matchers, the folder-name token is a runtime-unique string that cannot pre-exist on screen, and the "Recent"/chip assertions are structural).

## Testing strategy

This ticket **is** the test. Verification for the developer:

- **Compiles:** `./gradlew compileDebugAndroidTestKotlin` (the mandatory gates — `test`/`lint`/`assembleDebug` — do **not** compile `androidTest`; this is the only way to catch a bad selector import or signature before a device run).
- **Runs green in the gate (AC-4):** `bash scripts/e2e-preship-gate.sh` (i.e. `LIVE=1 scripts/e2e-emulator.sh`) against the live relay + real claude, which now executes both curated methods. This is an operator-run, real-stack, real-cost check — it is the acceptance evidence, not something the developer's turn budget must reproduce headlessly. The developer's deliverable is the compiling test + the script extension; the green LIVE run is the operator's pre-ship confirmation.
- **Script sanity:** `bash -n scripts/e2e-emulator.sh` (syntax) and eyeball that the comma-separated `class` arg is a single unbroken shell token.
- No unit/instrumented `runTest`/`ComposeTestRule` fakes are added — the whole point of rung 3 is the real stack.

## Consequences (accepted)

- The new method is a normal non-`@Ignore` `@Test`, so the **default whole-class rung-3 run** (`bash scripts/e2e-emulator.sh`, local relay) picks it up automatically and goes from 2 real-claude turns (ping + #481 tool-use; the think-prompt is `@Ignore`) to **3**. This is the natural, intended consequence of adding a scenario to the class and mirrors how #481's tool-use test already behaves (in the default run, excluded from LIVE). The **LIVE gate** goes from 1 curated turn to **2** (ping + workspace-create), which the 2026-07-08 org policy explicitly accepts.
- Created folders accumulate under the operator's real `~/pyry-workspace` across gate runs (no cleanup). Acceptable per ticket; the collision-resistant name keeps each run green.

## Open questions

- **Recents timing** — the design sequences AC-3 after the ping turn so the folder is unambiguously a used workspace before we assert it in "Recent." If the daemon records a workspace as "recent" at `createDiscussion` time (step 3) rather than at first session activity, AC-3 would also pass earlier; either way the after-ping ordering is safe. No action unless the LIVE run shows the recents row missing — then confirm daemon recents semantics against pyrycode#888.
- **Back-nav content-description uniqueness** — `"Back"` is used by several screens' top bars, but only one `"Back"` node is present on the thread screen at AC-3 time, so `onNode(hasContentDescription("Back"))` is unambiguous there. If a future change adds a second "Back" affordance to the thread, switch to `onAllNodes(...).onFirst()`.
- **Long-press flake** — `performTouchInput { longClick() }` on a FAB is occasionally sensitive to the default long-press duration on a slow emulator; if the picker fails to open, the developer may pass an explicit longer duration to `longClick(durationMillis = …)`. Tune on first operator run (the ladder permits rung-3 gesture tuning).
