# #967 — composer controls and command feedback survive a reconnect, live

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`
  - `interactiveTurn_pingPrompt_footerShowsContextUsage` (#946): the `Cxt: N%` wait this ticket reuses.
  - `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` (#850) and `setHostLink` / `cycleHostLink`: the
    cut-and-restore drive, whose readiness is the coordinator's repository, not a `ConnectionState`.
  - The #545 settings helpers `prepareChat`, `freshSettings`, `publishedMenu`, `usableRows`, `pickFooterOption`,
    `awaitFooter`, `restoreSettings`, `clearRememberedEffort`, and `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn`,
    whose `EffectiveEffort` → footer label mapping this ticket needs as well.
  - The #950 / #966 approval path: `runningToolPeer` (the main daemon's `--allow-remote-permissions` peer),
    `SecondClientPeer.allowOnce`, `answerChat`, `openChatRow`, `sendFromPhone`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `awaitFrame`, `recorded`, `allowOnce`:
  the frame recorder the background-task check reads the task's start and finish from.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `observeContextUsage`:
  the reading is cleared across connections and "stays absent until the conversation's next turn ends on the
  new connection". `observeSlashCommandMenu`, `observeSessionSettings` switch the same way.
- `docs/knowledge/features/thread-composer-footer.md` § Context usage segment (#946): **no ask since Rework 1**
  (pyrycode#2563 still open), so a fresh `Cxt:` reading needs a turn on the new connection. § Actions menu:
  live count label `"Background tasks (N)"`, `liveCount` excludes a finished task, Compact session sends
  `/compact`, a row the published menu proves absent is disabled.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `ComposerAction`,
  `footerMenu`, `permissionModeLabel`, `PermissionModeOption`; `ThreadUiState.kt` → `ThreadRunConfig.modelLabel`
  (`""` saved model shows `INHERITED_RUN_CONFIG_LABEL`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the overlay's `openMenu` is
  re-derived from state on every pass (the count label is live while open); `ThreadStatusArea`'s
  `isCompacting` arm.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/SlashCommandTypeAhead.kt` →
  `slashCommandTypeAheadRows`, `slashCommandOptions`, `completeSlashCommand`: the test derives the expected
  rows, labels and completion from these rather than restating the rules.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt` → panel sentences,
  `Finished` label; `app/src/main/java/de/pyryco/mobile/data/network/BackgroundTaskPayloads.kt` →
  `BackgroundTaskStartedPayloadDto`, `BackgroundTaskUpdatedPayloadDto` (`status != ""` is the finish).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt` →
  `compactionBoundaryLabel`: "Conversation compacted…", " by you" for `trigger: manual`.
- `../pyrycode/docs/protocol-mobile.md` § `compacting`, § `compaction_boundary` (manual trigger observed),
  § `slash_command_list` (connect-time snapshot), § `background_task_*`; daemon
  `docs/knowledge/features/e2e-realclaude-roster-after-finish-capture-test-go.md`: at claude 2.1.280 a finished
  background task produces a terminal `task_updated` and an empty roster **unprompted**, while idle.
- `scripts/e2e-emulator.sh` (LIVE `TEST_TARGET`), `scripts/android-test-gate.py` (`LIVE_MINIMUM`),
  `scripts/test_android_test_gate.py` → `test_live_floor_matches_the_curated_list` keeps the two equal.

No other feature branch touches these files.

## Design source

**Figma:** N/A — test-only ticket; no UI changes.

## Context

#679's composer tickets each left a live proof: the footer readings (#545/#650/#946), the Actions menu (#884,
#678), the slash type-ahead (#885) and compaction feedback (#874). This ticket proves they still work after the
phone's link is cut and restored, plus the live background-task count. Zero production files.

## Design

Three new always-on methods in `InteractiveStreamE2ETest`, each self-contained.

### 1. `interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive` — AC-1, two real turns

1. `clearRememberedEffort`, `prepareChat` (records the original for `restoreSettings`), open it, one ping; the
   footer shows `Cxt: N%`.
2. `setHostLink(down)`, `setHostLink(up)`. The footer shows `Cxt: n/a`: the old connection's reading was
   dropped, so any later percentage is from the new connection.
3. A fresh reading after the reconnect sets the expectations: saved model `""` → `INHERITED_RUN_CONFIG_LABEL`;
   effort from its `effectiveEffort` through the #545 mapping (an `Unavailable` after a turn fails); permission
   `PermissionModeOption.fromWire(mode)?.label ?: mode.inert()`, required non-empty. `awaitFooter` checks each
   control settles, not pending.
4. A second ping on the new connection; the footer's `Cxt:` becomes `N%` again. The post-turn push is the only
   source of the reading (#946 removed the ask), so this is the fresh reading. The turn runs **before** the model
   change, so it never runs on an arbitrary published model the account may not serve.
5. A published model other than the current and the inherited default is picked from the footer; the footer
   settles on it and a fresh reading's saved model equals its value.

The #545 effort mapping moves out of `interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn` into a
private helper `appliedEffortFooter(applied: EffectiveEffort): Pair<String, String?>` that both call.

### 2. `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` — AC-2, two real turns

1. A named chat on the main host (`answerChat`), open it, one ping (spawns claude, which publishes its
   commands). `cycleHostLink`.
2. The menu the host publishes for the chat after the reconnect (`observeSlashCommandMenu`, non-empty). Typing
   `/` shows the first rows' labels from `slashCommandOptions(slashCommandTypeAheadRows("/", rows))`. Tapping
   the first puts exactly `completeSlashCommand(row)` in the composer (its `EditableText`). The composer is then
   cleared.
3. Actions → **Compact session** (must be enabled: the menu does not prove `/compact` absent). The status area
   shows the compacting indicator (`cd_thread_compacting`), then the thread shows a divider starting
   "Conversation compacted" and ending " by you" (manual trigger), and the indicator goes.

### 3. `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` — AC-3, one real turn

1. The `runningToolPeer` opens; a named chat on the main host; the phone sends a prompt asking for Bash with
   `run_in_background` on `sleep 40`.
2. A helper `allowPromptsUntil(peer, conversationId, timeoutMs, done: (Envelope) -> Boolean): Envelope` polls
   the peer's recorded frames, allows each new permission prompt once through the peer (the #950 path), and
   returns the first frame `done` accepts. First use: the task's `background_task_started`.
3. Actions shows `Background tasks (N)`, `N >= 1`. Tapping it opens the panel, which lists the task's
   `task_type` from that frame and neither "no report" nor "no tasks" sentence. Close.
4. The helper again: that task's `background_task_updated` with a non-empty `status`. Actions shows
   `Background tasks (0)`, and the panel labels the task `Finished`.

The count and panel are asserted as the user sees them; the peer only supplies timing and the task identity.

### Curated list

The three methods join the LIVE `TEST_TARGET` in `scripts/e2e-emulator.sh` as one appended assignment with a
count comment (27 methods, 29 turns). `LIVE_MINIMUM += 3` in `scripts/android-test-gate.py`.

## State + concurrency model

Test-only. Every wait is bounded (`waitUntil` or `withTimeout`); peer reads are `runBlocking` inside the test
thread, as in the existing methods. The peer is closed in `finally`; settings are restored in `finally`.

## Error handling

Each wait fails with an `AssertionError` naming what was not seen (counts, labels, booleans), never claude's or
the command's text. A precondition the live stack does not meet (no second usable model, `/compact` greyed out,
an empty published menu, a blank `task_type`) fails with its own message, never a skip.

## Testing strategy

- The three methods are rung-3 live scenarios; the dispatcher runs them in `python3 scripts/android-test-gate.py live`
  after verifier (`needs-real-claude`). The builder cannot run real claude.
- Locally: `./gradlew compileDebugAndroidTestKotlin`, `lint`, `assembleDebug`, and
  `python3 -m unittest scripts/test_android_test_gate.py` for the floor.
- Deterministic-only in this family: banner notices (`BannerNoticeRowTest`) and model refusals
  (`ModelRefusalRowTest`); real claude does not trigger either on demand. The PR names them. No rung-4 twin:
  the scripted fixtures do not carry `compacting`, `slash_command_list` or `background_task_*` frames, and
  #946 records that `context_usage` over `fakeclaude` is not established.

## Open questions

1. Real claude may refuse a background `sleep` or run it in the foreground. The phone's count reads any
   `background_task_started`, so the assertions hold either way; a refusal fails step 2 by timeout. If the live
   gate shows that, the fallback is the ticket's deterministic route, recorded in `## Revisions`.
2. A manual `/compact` on a two-message conversation may finish too fast for the indicator. The rising edge
   precedes a model call, so it should show for seconds. Resolved by the live run.
3. Whether a finished task makes claude start a turn on its own. Not asserted; the PR counts one turn.

## Documentation handoff

Pending for the documentation stage: `docs/e2e-interactive-stream.md` § Live mode — add the three methods to the
curated list and update the method count (27) and the real-claude turn count (29).

## Revisions

- **2026-09-24, during Phase B.** The background command is `python3 -c "import time; time.sleep(40)"`, not
  `sleep 40`. The harness's `WAIT_PROMPT` records that claude's Bash tool refuses a bare `sleep` of 25 s or more,
  and a `python3` command is never auto-allowed on the main daemon, so the permission path in
  `allowPromptsUntil` is the one the run exercises. The assertions are unchanged.
- **2026-09-24, rework 1 (live gate FAIL).** The live gate ran all 27 methods. AC-1 and AC-2 passed, and so did AC-3's
  start and count. `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` failed at Design § 3 step 4's
  `Finished` wait, after `Background tasks (0)` had passed. claude sends an empty `background_task_roster` after a
  finish (the daemon note in Files read), and `BackgroundTaskProjection.applyRoster` drops a task a roster omits,
  so the panel then says "No background tasks". Step 4 now accepts either reading, `Finished` on the task or the
  no-tasks sentence, and still rejects "no report". The count check is unchanged. `allowPromptsUntil` also takes
  the answered modal ids from its caller now, so the finish wait does not answer the start's prompt a second time
  (the verifier's NIT). The same run also failed `interactiveTurn_deleteConversation_removesFromListAndClosesThread`.
  That method ran before any of this ticket's methods, and this branch changes neither it nor production code.
  The PR records it as a failure that did not come from this branch.
