# #545 — live proof of the model and effort settings round trips

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → the rung-3 class the new methods join; `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` (the in-process restart to mirror), `interactiveTurn_peerStartedTurn_continuesOnPhone` (finding a chat row by a run-unique name), `hostConversationIds` / `assertHostHoldsConversation` (reaching a host's own repository through `RelayConnectionRegistry.connectionFor`), `sendFromPhone`, `awaitChannelList`, `awaitConnected`, `openRow`, the companion's string constants.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` → `rebuildGraph`: the restart hands the running `DataStore` to the new graph, so `AppPreferences` reads the same `app_prefs`.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `rememberedEffort` / `setRememberedEffort`; the only production change adds its clear beside them.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/EffortRecall.kt` → `EffortRecall.decide`: recall fires only when the remembered level is among `config.effortChoices`, i.e. the row the **saved model** names.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig.selectedEffort`, `effortNote`, `effortLabel` (placeholder `"Effort"`), `modelLabel`, `selectedChoice` (matches `savedModel` against row `value`; `""` matches nothing).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onModelSelected`, `onEffortSelected`, `sendSessionSettings` (remembers only an acked effort write, then `refreshSessionSettings`); nothing re-reads settings on `turn_end`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `ThreadComposerFooter` / `FooterButton`: each control is one merged node with an `onClickLabel` (`thread_footer_change_model`, `thread_footer_change_effort`), its label as text, and a state description that is `thread_footer_pending` while a write is outstanding, else the effort note.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/OptionsOverlay.kt` → `OptionsColumn`: each choice is a merged `Role.RadioButton` node carrying its label.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `createDiscussion`, `createChannel`, `rename`, `setSessionSettings`, `observeSessionSettings`, `observeModelMenu`, `SessionSettings`, `EffectiveEffort`, `ModelMenuRow`.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionSettingsCommands.kt` → `observeSessionSettings`: every collection sends a new `request_session_settings` (the "fresh reply").
- `app/src/main/java/de/pyryco/mobile/data/repository/ModelMenuProjection.kt` → `observe` asks `request_model_list` on start, so a conversation created after connect still gets its menu.
- `scripts/e2e-emulator.sh` → the `LIVE` curated `TEST_TARGET` list and its comments; `scripts/android-test-gate.py` → `LIVE_MINIMUM`.
- `docs/knowledge/features/thread-composer-footer.md` § Applied effort (#889), `thread-composer-footer-effort-recall.md` → the display ranking and recall rules the assertions encode.
- `../pyrycode/docs/protocol-mobile.md` § Session settings, `model_list`, "Asking for a model list on demand" → a dormant bound session accepts `model`/`effort` writes; `create_conversation` binds a session before replying.

## Design source

N/A — test-only ticket: live e2e scenarios plus one non-visual preferences method. No UI changes.

## Context

Four shipped features (#590 settings read, #889 applied effort, #686 remembered effort, #946 context footer) have no live proof of the settings round trip. This adds four rung-3 methods to `InteractiveStreamE2ETest`, four real turns in total, and joins them to the `LIVE` gate.

**Fixture choice: the test prepares its conversations through the host's own repository, not `conversations.json`.** The ticket suggests seeding from `scripts/e2e-emulator.sh`. A seeded registry row has no bound session (the #847 seed is `omitempty`-unbound), and a model/effort write needs one: the dormant-write path the protocol describes needs a persisted session entry the seed would also have to forge. `create_conversation` mints and binds that session on the daemon itself. Each method calls `createDiscussion` / `createChannel` / `rename` / `setSessionSettings` on `RelayConnectionRegistry.connectionFor(serverId)`'s repository. This is the daemon's own verb, not the phone's creation forms (#676's surface), and it keeps each method self-contained regardless of the curated list's order. The script change is only the curated list and its counts.

**A gap found while planning. It is out of scope and filed separately.** `ThreadRunConfig.selectedChoice` matches the saved model against published row values. A conversation with no model override has `savedModel == ""`, so it matches no row. Its effort control has no levels, and `EffortRecall.decide` reports it as `unpublished`. Desktop resolves `""` to the inherited-default row (`effortRowFor`, desktop #1168). Mobile's #686 port did not. So on a truly fresh chat, the one with no model chosen, recall never fires. The criterion only requires an empty saved **effort**, so criterion 3's fresh chat and channel are prepared with an explicit saved model and an empty effort. The bug goes to its own Inbox ticket, linked from the PR.

**Also noted, not a defect this ticket fixes.** The open thread does not re-read its settings when a turn ends: the triggers are subscription, `session_transition`, and a settled write. The applied value from a first turn therefore reaches the footer only on the next reading. The scenarios take the "settled footer" reading by leaving and reopening the thread, which opens a new subscription.

## Design

### Production: `AppPreferences.clearRememberedEffort(): Result<Unit>`

This mirrors `setRememberedEffort`. It removes the `remembered_effort` key and logs `event=remembered_effort_cleared outcome=success|io_failure` by static code only. No other caller. `RememberedEffortStore` is unchanged.

### Test methods (all in `InteractiveStreamE2ETest`)

Names follow the class's `interactiveTurn_*` convention:

1. **`interactiveTurn_modelChange_roundTripsAndStaysPerConversation`** (0 turns).
   - Clear the remembered level.
   - Create chats X and Y and rename them to run-unique names. Read X's published menu.
   - Pick at runtime, from usable rows (no `value` or `effort_levels` in `truncatedFields`):
     - `rowA`, the first row with effort levels;
     - `rowB`, a second row;
     - `target`, a third row distinct from both. Fail with a clear message if the menu has fewer than three usable rows.
   - Prepare X = (`rowA.value`, `rowA`'s first level) and Y = (`rowB.value`, a `rowB` level different from X's, else `""`). Fresh replies confirm both.
   - Open X from its chat row, wait for the footer model label to read `rowA.displayName` and settle, then pick `target.displayName` in the overlay.
   - Wait until the model label reads the target's name and is not `Applying`.
   - Fresh reply X: `model == target.value`. Fresh reply Y: `model` and `effort` are unchanged.
   - Back, reopen X: the settled footer still shows `target.displayName`.
2. **`interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn`** (1 turn), criterion 2(a).
   - Clear the remembered level and assert it is absent. Create a chat and assert the fresh reply's `effort == ""`.
   - Open the chat, send `PING_PROMPT`, and await the reply.
   - The next fresh reply's `effectiveEffort` must not be `Unavailable`.
   - Back, then reopen. The settled effort button must show exactly:
     - `Applied(v)` with `v` non-empty → label `v`, no state description;
     - `Applied("")` → label `"Effort"`, note `thread_effort_note_default_unavailable`;
     - `NotReported` → label `"Effort"`, note `thread_effort_note_not_reported`.
   - No level is hard-coded.
3. **`interactiveTurn_chosenEffort_appliesFromTheFirstTurn`** (1 turn), criterion 2(b).
   - Clear the remembered level. Create and open a chat.
   - Through the footer, pick the first usable row with effort levels (not the current model), wait for it to settle, then pick that row's first level and wait until the effort label reads it, not `Applying`. The ack has then landed, because pending clears only on the refreshed reading.
   - Fresh reply: `effort == level`, and `effectiveEffort !is Applied`. The footer shows the level.
   - Send `PING_PROMPT` and await the reply. Fresh reply: `effectiveEffort == Applied(level)`.
   - Back, reopen: the label is the level and there is no state description, so it shows the applied value, not the saved fallback.
4. **`interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel`** (2 turns).
   - Clear the remembered level. Pick row `R`, the first usable row with at least two levels. Let `L = R`'s first level and `L2 = R`'s last level.
   - Create priming chat P with saved model `R` and open it. Tap `L` in the footer, then wait until it settles. Assert `AppPreferences.rememberedEffort == L`.
   - Create fresh chat F, then channel H via `createChannel(name, F.cwd)`, both with saved model `R` and effort `""`, confirmed by fresh replies. Create chat E with (`R`, `L2`).
   - Restart the way #847 does: destroy the activity, `rebuildGraph`, launch `MainActivity`. Then `awaitChannelList`, `awaitConnected`, and remembered is still `L`.
   - For F, then H, from its row:
     1. Before the first message, the settled effort label is `L`, and a fresh reply's `effort == L`.
     2. Send `PING_PROMPT` and await the reply.
     3. Fresh reply `effectiveEffort == Applied(L)`, exactly.
     4. Back.
   - Open E: the settled label is `L2`, a fresh reply's `effort == L2`, and the label is still `L2` and settled after that reply.

### Shared helpers (private, in the class)

- `hostRepository(): ConversationRepository` — the paired host's current repository, read from the **current** `GlobalContext` so it survives `rebuildGraph`.
- `freshSettings(conversationId): SessionSettings` — one new collection of `observeSessionSettings`, the first non-null reading, under `THREAD_TIMEOUT_MS`.
- `publishedMenu(conversationId): ModelMenu` and `usableRows(menu)`.
- `prepareChat(prefix): Conversation` — `createDiscussion` + `rename` to `prefix + timestamp`.
- `writeSettings(conversationId, model, effort)` — writes to the fresh reply's own `sessionId`.
- Footer: `footerControl(clickLabel)` matches the merged node whose `OnClick` label is `clickLabel`. `footerLabel(...)`, `footerState(...)` and `awaitFooter(clickLabel, label, state)` wait for the exact label and state description, where settled means not `Applying`. `pickFooterOption(clickLabel, optionLabel)` opens the overlay and taps the `Role.RadioButton` node carrying `optionLabel`.
- `openChatRow(name)`: scroll to the `TREE_CHAT_ROW_TEST_TAG` row, tap it, and wait for the thread. `openRow(name)` (channels) exists. `leaveThread()` goes back and waits for the list.
- `restoreSettings(originals)` — `finally`: for each conversation the method changed, write back the `model` / `effort` of its first reading with `set_session_settings` on the same conversation. Each write runs in `runCatching` so a restore failure never masks the scenario's own failure. The block then clears the remembered level.

### Gate wiring

- `scripts/e2e-emulator.sh`: append the four methods to the `LIVE` `TEST_TARGET` list. Update the counts in its comments to 19 methods and 14 turns, and update the PASS line.
- `scripts/android-test-gate.py`: `LIVE_MINIMUM` goes from 15 to 19.

## State + concurrency model

Test-side only. Repository calls run in `runBlocking { withTimeout(...) }` from the instrumentation thread, as the existing helpers do. Every settings read is a new cold collection that ends after its first non-null value, so no job outlives a helper. The restart helper closes the relaunched `ActivityScenario` in `finally`.

## Error handling

A missing precondition fails with a message naming it: fewer than three usable model rows, no row with two or more levels, or a fresh conversation with a non-empty saved effort. Restore failures are swallowed so the scenario's own assertion stays the reported failure. The remembered level is always cleared in `finally`.

## Testing strategy

- **JVM:** `AppPreferencesTest` gains `clearRememberedEffort_removesTheLevel_andReadsAbsent` (TDD, red first).
- **Live (rung 3):** the four methods above. The dispatcher's post-verifier `python3 scripts/android-test-gate.py live` runs them. They need real Claude, so I compile them (`compileDebugAndroidTestKotlin`) but do not run them.
- **Deterministic cases → existing JVM tests (criterion 4, recorded in the PR, nothing new added):**
  - explicit-null vs omitted `effective_effort` → `SessionSettingsPayloadsTest.effectiveEffort_omittedKey_decodesAsUnavailable`, `effectiveEffort_explicitNull_decodesAsNotReported` and `effectiveEffort_threeStates_areAllDistinct`; on screen, `ThreadViewModelAppliedEffortTest.anOlderDaemonOmittingTheKey_showsTheSavedChoiceAndExplainsIt`.
  - saved and applied disagree → `SessionSettingsPayloadsTest.effort_and_effectiveEffort_disagreeing_areRetainedIndependently`, `ThreadViewModelAppliedEffortTest.appliedValueWins_overADisagreeingSavedChoice`.
  - invalid / unpublished remembered level → `ThreadViewModelEffortRecallTest.aRememberedLevelTheSelectedRowDoesNotPublish_isNotSent`.
  - rejected write not repeated → `ThreadViewModelEffortRecallTest.aRejectedRecall_revertsAndSignals_remembersNothing_andIsNotRetried`.
  - passive reading never remembered → `ThreadViewModelEffortRecallTest.passiveReadings_neverChangeTheRememberedLevel`.
  - host and conversation isolation → `ThreadViewModelEffortRecallTest.twoConversations_onlyTheUnsetOneIsWritten_atItsOwnSession`, `ThreadViewModelAppliedEffortTest.aHostSwitchOrReconnect_showsNoStaleAppliedValue`, `StableConversationRepositoryTest.observeSessionSettings_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch`.
  - remembered level stays local → `AppPreferencesTest.rememberedEffort_andTheSettingsDefaultEffort_areIndependent`, `ThreadViewModelAppliedEffortTest.aReadingAlone_sendsNoWrite_noRefresh_andNoMessage`, `ThreadViewModelEffortRecallTest.aSuccessfulTap_survivesAnAppRestart`.

## Documentation handoff (pending — documentation stage)

- `docs/e2e-interactive-stream.md`: list the four new live methods and the new real-turn count (14).
- `docs/knowledge/features/thread-composer-footer.md`, "Live coverage" bullet (§ Related): name the new methods instead of pointing at #545.
- `docs/knowledge/features/thread-composer-footer-effort-recall.md`, "No rung-3 scenario" sentence: name `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel`.
- `docs/knowledge/features/app-preferences.md` § Remembered effort key: mention `clearRememberedEffort`.

## Open questions

- Can `createChannel` take the scratch `cwd` of a created chat? If the daemon refuses it, use `createWorkspaceFolder` with a run-unique name instead, as #566 does. Record the choice under Revisions.
- Is `effective_effort` present on the **first** fresh reply after the ping reply renders? The criterion says "the next fresh reply". The scenario keeps that literally, and any flake goes in the PR's Lessons.

## Revisions

- **2026-09-24, Phase B.** The `""`-model gap is filed as [#972](https://github.com/pyrycode/pyrycode-mobile/issues/972) (Inbox), and the recall scenario's KDoc links it. No design change.
- **Open questions, status.** Both need a live run and stay open for the post-verifier live gate. The first is whether `createChannel` accepts a created chat's scratch `cwd`. The second is whether the first fresh reply after the ping carries `effective_effort`. Builders cannot run real Claude.
