# #694 — Reply-specific LIVE ping assertions

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` — `pingNodeCount` and the ping, create-workspace-folder and new-session tests currently depend on substring-count growth.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/QueuedBacklogTest.kt` — `stateWith` and the hoisted-state regression provide the fixture pattern.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` — `ThreadScreen` puts messages in a `LazyColumn`, with title and queued backlog outside it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` — `AssistantMessage` renders finalized content through `MarkdownText`; sent user prompts remain full text.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` — `MarkdownBlock` exposes paragraph text through Compose semantics.
- `docs/knowledge/features/queued-backlog-section.md` — backlog descendants merge; unmerged semantics allow independent text matching.
- `docs/knowledge/features/thread-screen.md` and `docs/knowledge/features/development-verification.md` — screen structure, routine UI package exclusion, and device-evidence boundaries.
- `docs/e2e-interactive-stream.md`, `scripts/e2e-emulator.sh`, `scripts/android-test-gate.py` — rung vocabulary, unchanged LIVE selections and dispatcher execution ownership.

## Change

Replace `pingNodeCount` with a shared androidTest-only assertion in `ui/conversations/thread/PingReplyAssertions.kt`. Match case-insensitive, exact `ping` text under a scrollable ancestor in the unmerged tree. In these fresh discussions the only sent input is the full `PING_PROMPT`; exact matching excludes it, and list scoping excludes even a title or queued entry equal to `ping`. This is a constrained-prompt assertion, not a generic role detector. Wait for the matching node to be displayed within the existing `REPLY_TIMEOUT_MS`, then assert it is displayed. Share the prompt constant with the regression. Preserve scenario names, LIVE selection and workspace/session postconditions. No production change, new state, concurrency or error behavior is needed.

## Testing strategy

Add `PingReplyTest` beside `QueuedBacklogTest`, outside the excluded e2e package. Render the real `ThreadScreen` with the full user prompt, a title equal to `ping`, and queued text equal to `ping`. Assert no reply match; remove the queue and assert no match; restore it, then atomically replace it with a finalized assistant reply. Assert the shared LIVE helper succeeds and total substring matches have not increased. This exercises both exact-text and list-scope discrimination.

Author the negative/transition regression before the matcher implementation. Device RED/GREEN execution belongs to the dispatcher; compilation is not execution evidence. Run Spotless, lint, assembleDebug and compileDebugAndroidTestKotlin locally. No JVM test class changes. Dispatcher runs the routine UI gate, then post-verifier `python3 scripts/android-test-gate.py live`, requiring executed passing results for `interactiveTurn_pingPrompt_streamsPingReplyIntoThread`, `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` and `interactiveTurn_newSession_rendersSessionBoundaryDelimiter`. Keep `needs-real-claude`; #588 artifact revalidation remains separate.

## Scope check

One deliverable: reliable LIVE ping assertion with its regression. Approximately 200 written lines including this plan, 0 production files, 0 exported production declarations, 3 helper consumers, 3 acceptance criteria and 0 state-machine reject branches. The #461 analogue added 166 test lines. No in-flight feature branch overlaps the three planned test files after fetching origin. Codegraph found the entry points but no callers/callees for `pingNodeCount`; source inspection confirmed the three consumers.

## Documentation handoff

No documentation-only acceptance criteria or required reference-document edits in this ticket. Pending documentation-stage recording, if needed: `docs/knowledge/features/development-verification.md`, section “Compose evidence”, capture why substring-count growth is invalid when queued prompt text disappears. Do not claim daemon history proves phone rendering.

## Open questions

None. No Figma source is required for test-only work with no UI changes.
