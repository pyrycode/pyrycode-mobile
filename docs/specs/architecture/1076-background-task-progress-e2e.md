# #1076 — rung 3: a running background task's progress shows on the panel

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`
  - `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` (#967) — the sibling this scenario mirrors: `runningToolPeer()`, `answerChat`, `allowPromptsUntil`, then `openActions()` / `openBackgroundTasks` / `inBackgroundPanel()` / `closeBackgroundTasks()`.
  - `allowPromptsUntil` — polls `SecondClientPeer.recorded` and allows each permission prompt once; its `done` predicate is re-run against a fresh snapshot every poll.
  - `WAIT_PROMPT` / `RUNNING_TOOL_PROMPT` comments — a `python3` command always raises a permission prompt, and a bare `sleep` of 25 s or more is refused.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `recorded` — a non-suspending snapshot, safe inside `composeTestRule.waitUntil`.
- `app/src/main/java/de/pyryco/mobile/data/network/BackgroundTaskPayloads.kt` → `BackgroundTaskStartedPayloadDto`, `BackgroundTaskProgressPayloadDto` — the production decoders the test uses.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt` → `TaskRow`, `TaskProgress`, `boundedText` — the card is one merged semantics node whose text list holds the activity line and the meta line as separate entries; daemon text is filtered of control characters and cut at `MAX_PANEL_TEXT_CHARS`, so a short prefix is what survives.
- `app/src/main/res/values/strings.xml` → `background_tasks_progress_tools` plural — the tools segment's own template, read at runtime rather than restated.
- `docs/e2e-interactive-stream.md` § the stop scenario — on the main test daemon the phone draws a permission prompt as a **modal dialog over the composer**. This decides the hold below.
- `docs/knowledge/features/remote-conversation-repository-live-stream-and-modals.md` § `backgroundTasks` — progress is replaced whole per frame and nulled on finish; `docs/knowledge/features/mobile-modal.md` § the #1044 progress block.
- pyrycode `docs/protocol-mobile.md` § `background_task_progress` and `internal/protocol/testdata/background_task_progress.json` — at most one frame per two `tool_uses`; the capture's subagent is `general-purpose`.
- `scripts/e2e-emulator.sh` LIVE list and `scripts/android-test-gate.py` `LIVE_MINIMUM` — #684's commit `d0a562f6` is the shape for joining the list.

## Design source

N/A — test-harness ticket exercising the already-anchored panel (Figma node 568-877); no new visuals.

## Context

#1044 draws the latest `background_task_progress` frame on a running card; only its unit and Robolectric tests prove it. This rung-3 scenario proves real claude plus the daemon actually deliver a progress frame for a real background subagent and that the panel draws it while the task runs. It is #1044's definition of done (PR #1073 verifier finding).

## Design

One new `@Test` on `InteractiveStreamE2ETest`:
`interactiveTurn_backgroundAgentProgress_showsOnRunningCard`.

**The hold — why not a permission prompt.** Every other held scenario holds on a `python3` permission prompt. Here that fails twice: the phone draws the prompt as a modal dialog over the composer, so the Actions footer is unreachable while it is outstanding, and a background subagent may auto-deny a tool that was not approved at launch, which would end the task at once. So the subagent holds itself open with work that needs no permission: `BACKGROUND_PROGRESS_PROMPT` asks claude to start a `general-purpose` subagent **in the background** whose instructions are a fixed run of Glob calls (`BACKGROUND_PROGRESS_GLOBS` patterns), **one call per message, never in parallel**. At a few seconds per subagent step that keeps the task running for roughly a minute after its first progress frame (which needs two tool calls), while the phone needs a few seconds to open the panel. The main agent then stops without commentary. This is the one timing dependency left, and it is loose.

**Steps.**
1. As the sibling: `runningToolPeer()`, `awaitChannelList()`, `awaitConnected()`, `answerChat(serverId, BACKGROUND_PROGRESS_NAME_PREFIX)`, open the peer, `openChatRow`, `sendFromPhone(BACKGROUND_PROGRESS_PROMPT)`.
2. `allowPromptsUntil(..., BACKGROUND_PROGRESS_TIMEOUT_MS, frame = "background_task_progress")` until a recorded progress frame whose `task_id` equals that of a recorded `background_task_started` frame. Any prompt on the way (none expected) is allowed as in the sibling. Decode both with the production DTOs; frames that fail to decode are skipped. Then `awaitNoPromptDialog` so nothing covers the footer.
3. `openActions()`, `openBackgroundTasks { it >= 1 }`.
4. `composeTestRule.waitUntil(THREAD_TIMEOUT_MS)` for a panel node that carries **both**:
   - an activity line: a text containing the prefix of a progress description recorded for that task — re-read from `peer.recorded` on every poll, since a later frame may replace an earlier one on the phone. The prefix is the description up to its first control character, at most `ACTIVITY_PREFIX_CHARS`, blank ones skipped;
   - a meta line: a text holding a tools segment, matched by a pattern built from the `background_tasks_progress_tools` plural's own templates (`getQuantityText` for one and other, `%1$d` → digits). Any count passes.
   Both are checked on **one** node within `inBackgroundPanel()` — the task card, a single merged semantics node whose text list holds each line — so the opening description alone can never satisfy the check: only the progress block draws the tools segment. On timeout the failure says whether any card showed a tools segment and gives the count of recorded progress frames, never frame text.
5. `closeBackgroundTasks()`, `peer.close()` in `finally`. The task is left to finish on its own; the next scenario uses its own chat.

**Constants** (companion): `BACKGROUND_PROGRESS_NAME_PREFIX = "e2e1076-progress-"` (no "ping", no other prefix), `BACKGROUND_PROGRESS_PROMPT`, `BACKGROUND_PROGRESS_TIMEOUT_MS` (generous, 180 s: subagent start plus two tool calls), `ACTIVITY_PREFIX_CHARS`.

**Private helpers**, if the body needs them: `progressFramesFor(peer, chatId, taskIds)` → decoded `BackgroundTaskProgressPayloadDto` list; `toolsSegmentPatterns()` → `List<Regex>`.

## State + concurrency model

Test-only. `runBlocking` inside `allowPromptsUntil` as the sibling; the panel wait is `composeTestRule.waitUntil` polling the peer's non-suspending snapshot. No new coroutine scope.

## Error handling

Each wait converts its timeout into an `AssertionError` naming the step, recorded-frame counts and the peer's link state, never payload text (daemon descriptions name host files).

## Testing strategy

This is the test. Always-on: it joins the `LIVE=1` list in `scripts/e2e-emulator.sh` (with the turn-count comment, as #684) and `LIVE_MINIMUM += 1` in `scripts/android-test-gate.py`. One real-claude turn (the prompt; the subagent is part of it). Builder proof: `./gradlew compileDebugAndroidTestKotlin`, `spotlessApply`, `assembleDebug`, `lint`. The dispatcher's post-verifier live run (`needs-real-claude`) is the first execution; if it shows the task finishing before the panel reads, the fallback per AC-2 is `@Ignore` with the #481/#482-shaped KDoc and dropping the LIVE entry.

Overlaps: none — no in-flight feature branch touches these three files.

## Documentation handoff (pending — documentation stage)

`docs/e2e-interactive-stream.md`, beside the #967 background-task scenario: record `interactiveTurn_backgroundAgentProgress_showsOnRunningCard`, what it proves, its one-turn cost, and why it holds on a foreground subagent's run of Read calls on missing files rather than a permission prompt (the daemon's claude has no Glob tool; see Revisions). It lands **`@Ignore`d, blocked on pyrycode/pyrycode#2658** (see the second Revisions entry), so it is **not** added to the `LIVE=1` variant's ticket list and the method/turn counts stay as they are. Record the un-ignore steps with it. *(Revised 2026-09-25; the earlier text said always-on.)*

## Open questions

- Whether real claude honours "one Glob per message" inside a background subagent. If it batches, the task may finish before the panel reads; the live run decides, with `@Ignore` as the documented fallback.

## Revisions

### 2026-09-25 — live gate: the subagent had no Glob tool (rework after the real-claude gate)

**Finding.** The real-claude gate failed the scenario twice, once in the first run and once in the re-run. Both runs failed at step 2: `no background_task_progress arrived for a started task within 180000 ms`. The daemon's claude transcripts for both runs show why. The main agent did start the background `general-purpose` subagent. The subagent then replied that **the session has no Glob tool** and, since the prompt ruled out every other tool, made no tool calls at all. It finished about five seconds after launch, so the daemon's rate bound (two `tool_uses`) never let a frame through. It was not a timing flake. The finish also queued claude's task notification, which ran a **second** main-agent turn and broke AC-3's one-turn limit.

**New contract.**
- **Read, not Glob.** Read is in the daemon's base tool set. Inside the chat's working directory it needs no permission prompt. The subagent reads run-unique relative paths (`e2e1076-<n>.txt`) that do not exist, one per message. It is told that each missing file is expected and to keep going. A failed Read is still a tool call, and no host file content is read. The activity line then names a file the test chose, not a host path.
- **A foreground subagent, not a background one.** The one measured `system/task_progress` capture (pyrycode `internal/e2e/realclaude/testdata/parent_tool_use_v2.1.259.json`) comes from a foreground `general-purpose` subagent. It is still a `local_agent` task, which the daemon reports through `background_task_started` and `background_task_progress`, and neither the daemon nor `BackgroundTaskProjection` filters on `is_backgrounded`. The main turn stays open while the subagent works, and the Actions footer stays enabled during a turn (`footerControlEnabled`). One prompt, one turn: no task notification starts a second one.
- **A diagnosable timeout.** Step 2's failure now gives the counts of recorded `background_task_started` frames and joined progress frames, so the next red says whether claude started no task or whether the task never reported progress.

The steps, the panel assertion and the always-on `LIVE=1` membership are unchanged.

**Open question resolved.** "Whether real claude honours one call per message" could not be answered: the subagent made no calls. The Read rework reopens it, and the next live run decides it, with `@Ignore` still the documented fallback.

### 2026-09-25 (second): the scenario's own assertions pass, but the parser-gap sentinel fails. Landing it `@Ignore`d

**Finding.** The real-claude gate ran the Read-based rework at daemon `9f76e401` and failed the scenario in both the full run and the re-run, with the same cause each time. Every step of the body passed, and the re-run took 12.5 s. The progress frame arrived and the card drew both lines. Then the class's `UnrecognizedRowSentinel` (#586) failed the run: `1 unrecognized-message row … site=user_block message_type="text"`. The daemon's claude transcript shows where the row came from. A foreground subagent's first sidechain entry is a `user` message carrying the prompt that the main agent gave it. In the daemon, `(*Parser).emitUser` drops only text written by the harness, so this echo becomes `Unrecognized{user_block, text}`. Any foreground subagent would hit it, whatever our prompt says.

**Why not fix it here.** The gap is in the daemon's parser (pyrycode). The sentinel's own documentation says a red means the daemon's measured ignore-list needs re-taking. Waiving the sentinel for this method would hide a row that operators actually see, and moving the subagent to the background brings back the second turn. The fix belongs in the daemon, so it is filed there as **pyrycode/pyrycode#2658**, on the Pyrycode board in Inbox.

**New contract.**
- The method carries `@Ignore("blocked on pyrycode/pyrycode#2658 …")`. Its KDoc names the finding and the un-ignore steps: remove the annotation, restore the `LIVE=1` entry in `scripts/e2e-emulator.sh`, and raise `LIVE_MINIMUM` in `scripts/android-test-gate.py`.
- The `LIVE=1` entry and the `LIVE_MINIMUM` bump are removed, so both scripts match main again. This is the AC-2 shape: a documented manual case, never an always-on red.
- The body, the prompt and the panel assertion are unchanged. They are what the live run showed working.

**Open question resolved.** Real claude does take the subagent's Read calls one per message. The run observed the progress frame and the card before the task finished.
