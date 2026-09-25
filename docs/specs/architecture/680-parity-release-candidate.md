# Mobile parity release candidate (#680)

## Files read

- `scripts/e2e-emulator.sh` → the `LIVE` branch that builds `TEST_TARGET`: the curated real-Claude list the live gate runs.
- `scripts/android-test-gate.py` → `LIVE_MINIMUM`, `main`, `combine_reports`: the live gate's executed-test floor and its count checks.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → the `interactiveTurn_*` methods: the five suites' live scenarios.
- `app/build.gradle.kts` → `GitShaValueSource`, `USE_RELAY_REPOSITORY`: the Settings version row shows `git rev-parse --short HEAD` at build time; the real-data binding is the default.
- `docs/specs/architecture/528-live-mobile-baseline.md`: the previous revision-linked live result and the record's shape.
- `docs/knowledge/features/development-verification.md` § "Emulator and real evidence": what screenshots and records may contain.
- Suite tickets #847–#850, #965–#967, #674 (#1016, #1017), #676 (#1085–#1090), #684: the methods each suite added to the live list.

## Design source

**Figma:** the five frames supplied on 2026-09-19.

- Scanner https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2
- Pair with code https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2147
- Channel list https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8
- Conversation thread https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8
- Mobile modal shell https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

All five are dark 412×892 frames. The scanner is a "Pairing" top bar over a dark camera well with four corner brackets and a scan line, a `pyry pair` hint card and a "paste the pairing code" text link. Pair with code is the same top bar over a radial blue glow, with Host name and Pairing code filled fields, a full-width Pair button, Cancel and an open-source footer. The channel list has settings and archive icons, then per-host sections with a server icon, workspace folders and conversation rows with status dots, the selected row highlighted with a pencil. The thread has a back arrow, title and overflow menu, assistant and user bubbles with timestamps and copy icons, a "Thinking…" line, attachment chips, a composer and a footer of Actions, permission, model, effort and context readings. The modal shell is a full-screen "Edit host" sheet with a close button, identity and relay rows, a Host name field, Unpair host, and Cancel and OK buttons.

## Context

This ticket is the final check of the parity batch authorised on 2026-09-19. It adds no product code. It holds the verification record for one candidate commit, plus any live-list additions the five suites left out. A defect found here is filed as its own issue and listed as a gap; it is not fixed in this PR.

## Candidate definition

The ticket names the PR head as the candidate, but the record cannot contain the SHA of the commit that holds it, and the Settings version row shows the SHA the APK was built at. The candidate is therefore this plan's commit: the first commit on `feature/680`. Every later commit on the branch changes only this file, so the PR head's product tree (`app/`, `scripts/`, `gradle/`, build scripts) is byte-identical to the candidate's. The record states the `git diff --stat` that shows it. The dispatcher's gates on the PR head therefore run the candidate's product.

## Design

The record has four sections and a gap list, filled in below after this commit:

1. **Candidate**: the commit, its main parent, and the mobile and daemon revisions the gate scripts print.
2. **Gates**: `./gradlew assembleDebug test lint` once at the candidate, with its exit status and test count; the dispatcher's `ui`, `scripted-all` and `live` gates, pending until the dispatcher runs them; each suite's live methods by name, and any case a suite proves only deterministically.
3. **Layout**: emulator screenshots of the candidate compared in words with the five frames, then the keyboard, back, rotation and small-screen checks. Screenshots are not committed.
4. **Feature checks**: the passing test or observation that proves each of the seven items.
5. **APK and gaps**: the build command, commit and SHA-256 of the real-data debug APK, and one issue per gap, including push-notification status.

The live list already carries every live method from the five suites (44 methods, `LIVE_MINIMUM` 44), so no script change is planned. If the record finds a missing method, it is added to `TEST_TARGET` and `LIVE_MINIMUM` is raised with it.

## Testing strategy

No new tests. The proof is the gate run at the candidate plus emulator observations. The layout checks use an emulator on this machine. The channel list and thread need a paired host with conversations; the builder does not run a daemon with real Claude, so those two frames are compared on the same commit's demo binding (`-PuseRelayRepository=false`), which renders the same composables over seeded data. The scanner, pair-with-code and Settings version row are observed on the real-data APK itself.

## Documentation handoff

Pending for the documentation stage, after the live gate passes:

- `docs/e2e-interactive-stream.md` § Verification status: name this candidate as the current live baseline, with the date, mobile and daemon revisions and each executed method's outcome, as #528 did.
- `README.md` § Status: one line saying the parity candidate was verified at this commit, linking this record's gap list, and stating push-notification status per the gap list below.

## Open questions

- Whether the emulator on this machine can host the layout checks inside the run budget. Resolved in the record below.

## Revisions

### 2026-09-25: the candidate moves to `bf3749d8`

Two merges of main into `feature/680` landed after the plan commit (`b6d5fb95` and `bf3749d8`, the second made by the dispatcher before this run). They changed 71 files under `app/` and `scripts/`, so the plan commit `3366ea08` no longer carries the product the PR head runs. The candidate is now `bf3749d8`: its product tree is main `0abb4a0f` exactly, since that merge left no conflict and main has nothing `bf3749d8` lacks. Every commit after it on this branch changes only this file. `git diff --stat bf3749d8 <PR head> -- app scripts gradle build.gradle.kts settings.gradle.kts gradle.properties` is empty at the PR head. If a later dispatcher merge of main changes that tree, this record no longer describes the PR head, and the candidate has to be re-recorded.

The same merges brought #1107, which put a 45th method on the live list and raised `LIVE_MINIMUM` to 45, so the Design section's "44" is now 45. Every suite method is still on the list, so no script change is needed.

The machine this ticket runs on crashed twice during earlier builder runs of it, the second time while this record was being made. The layout emulator is therefore booted only when the dispatcher's device gates are idle, under the host-wide device hold from #1071, and never beside a second emulator.

# Verification record

## 1. Candidate

| | |
|---|---|
| Candidate commit | `bf3749d886eb30a7a33cacf88572026b25e2557f` (`bf3749d8`) |
| Main parent | `0abb4a0f5818b26ed2e1ecb019d0149ad4bdbb87` (PR #1130, #1119) |
| Product tree at the PR head | identical to the candidate's; only this file changes after it |
| Mobile revision the gate scripts print | the PR head (`scripts/e2e-emulator.sh` prints `git rev-parse HEAD`); its product tree is the candidate's |
| Daemon revision the gate scripts print | pending: printed by the dispatcher's `scripted-all` and `live` runs as `daemon revision:` |

## 2. Gates

**`./gradlew assembleDebug test lint` at the candidate.** Run once on 2026-09-25 at `bf3749d8` with a clean tree: exit 0, `BUILD SUCCESSFUL`. `testDebugUnitTest` wrote 215 result files holding 3037 tests: 0 failures, 0 errors, 0 skipped. That count includes the Robolectric screen tests under `app/src/sharedTest`. Lint reported 97 warnings and no errors.

**Dispatcher gates.** `ui`, `scripted-all` and `live` are pending. The dispatcher runs `ui` and `scripted-all` on the PR head before the verifier and `live` after it (the ticket carries `needs-real-claude`). The builder runs no real Claude. Pending is not a pass; the documentation stage records the outcome.

**The live list covers every suite.** `TEST_TARGET` in the `LIVE` branch of `scripts/e2e-emulator.sh` holds 45 methods and `LIVE_MINIMUM` in `scripts/android-test-gate.py` sums to 45. Every method below is on that list, so no script change was needed. The five methods of `InteractiveStreamE2ETest` that are not on it are outside the five suites: the #481 tool-use method (cost; it runs in the whole-class rung-3 run) and four manual `@Ignore` cases (two negative controls, the transient thinking spinner and the long-running tool's elapsed time).

| Suite | Ticket (PR) | Live method | Proven only deterministically |
|---|---|---|---|
| Continuity | #847 (#856) | `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` | none |
| Continuity | #848 (#857) | `interactiveTurn_peerStartedTurn_continuesOnPhone` | none |
| Continuity | #849 (#858) | `interactiveTurn_peerQueue_staysConsistentAcrossClients` | none |
| Continuity | #850 (#860) | `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` | none |
| Interaction | #965 (#976) | `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`; `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` (reset's wrapping-up phase) | the reset's restarting phase: `ScriptedResettingTest` |
| Interaction | #966 (#991) | `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`, `interactiveTurn_questionAnswer_reachesTheAskingConversation` | none |
| Interaction | #967 (#995) | `interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive`, `interactiveTurn_reconnect_slashCommandsAndCompactStillWork`, `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` | banner notices (`BannerNoticeRowTest`) and model refusals (`ModelRefusalRowTest`) |
| Management | #676: #1085 (#1091) | `interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched` | none |
| Management | #1086 (#1096) | `interactiveTurn_twoHostsDefaultsAndArchive_stayPerHost` | none |
| Management | #1087 (#1100) | `interactiveTurn_addRenameArchiveWorkspace_roundTripsThroughTheHost` | none |
| Management | #1088 (#1101) | `interactiveTurn_createEditArchiveChannel_readsPromptBack` | none |
| Management | #1089 (#1104) | `interactiveTurn_peerWorkspaceLabel_reachesEveryOpenSurfacePerHost` | none |
| Management | #1090 (#1106) | `interactiveTurn_attentionDot_followsARealTurn` | the Running dot state is never asserted live |
| Management (related) | #1021 (#1024) | `interactiveTurn_muteChannel_roundTripsThroughTheHost` | the rename, mute and prompt write order: `HostChannelListViewModelTest`; the checkbox UI: `ChannelListScreenTest` |
| Attachment | #674: #1016 (#1018) | `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`, `interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart` | none |
| Attachment | #1020 (#1047) | `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` | none |
| Attachment | #1017 (#1045) | `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes`, `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile`, `interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost` | none |
| Diagnostic download | #684 (#1097) | `interactiveTurn_logData_savesTheOwningHostsArchive` | none |

The rest of the list predates the batch or belongs to its settings follow-up and push work: ping, status-sheet model, footer context, workspace folder, delete, archive-restore, list archive entry, change workspace, rename, save as channel, model change, the three effort methods, permission-held tool, operator bypass, the two push methods (#955), the markdown note link (#1050) and background-agent progress (#1107).

## 3. Layout

**Setup.** One headless Pixel 8 emulator (API 35, Gboard, 1080×2400 at 420 dpi, so 411×914 dp beside the frames' 412×892), dark theme, booted only after the dispatcher's `ui` gate finished and held under the #1071 device hold for the whole session. No second emulator ran beside it. Screenshots were compared by eye with each frame and not committed.

- The scanner, pair-with-code and Settings version row are from the real-data APK (SHA-256 below).
- The channel list, thread and modal need a paired host with conversations, and the builder runs no real Claude. They are from the same commit's demo binding (`-PuseRelayRepository=false`), which renders the same composables over seeded data. It was paired through the app's own paste-a-code flow with a throwaway isolated test daemon and local relay (no Claude binary) built from the configured sibling sources, both stopped afterwards.

| Frame | Result |
|---|---|
| Scanner (13-2) | **Match.** Pairing top bar, dark camera well with four corner brackets and scan line, `pyry pair` hint card with the command in code style, "Trouble scanning? Paste the pairing code instead" link. The top bar sits about 31 dp lower than on pair-with-code, where the frames put both at the same height: #1136. |
| Pair with code (533-2147) | **Match.** Same top bar over the radial blue glow, filled Host name and Pairing code fields with clear icons, full-width Pair button, Cancel and the open-source footer. The frame's footer names `pyrycode-desktop`; the app correctly names `pyrycode-mobile`. |
| Channel list (15-8) | **Match in structure, mismatch in density.** Settings and archive icons, Channels and Chats sections, host rows with the server icon and two status dots, workspace folders, conversation rows with status dots, and the last-opened row highlighted. Rows are 48 dp against the frame's ~28 dp, and every row carries a pencil: #1136. |
| Conversation thread (16-8) | **Match in structure, mismatch in one colour.** Back arrow, title and overflow menu, assistant bubbles left and user bubbles right, each with timestamp and copy icon, the tool row, composer with send, and the footer of Actions, permission, model, effort and context readings plus attach. Assistant bubbles are grey, not the frame's navy: #1136. The frame's thinking line, attachment chips and banners need a running turn or attachments and were not on screen; the scripted and live gates cover them. |
| Mobile modal shell (533-2369) | **Match in structure, mismatch in tone.** Observed on Edit channel, which uses the same shell: title and round close button, labelled filled fields, an outlined action, Cancel and OK. The sheet is lighter blue than the frame's deep navy: #1136. Edit host itself would not open on the demo binding: the demo host is not in the paired-host store (`host_editor_open_rejected code=unknown_host`), a demo-only limit. |

**Keyboard.** Pair with code in portrait: focusing Pairing code raised Gboard, and the focused field, Pair and Cancel stayed above it. Edit channel in portrait: the focused name field, prompt field, Cancel and OK stayed above it. Edit channel in landscape: only the title row stayed visible and the focused field was hidden behind the keyboard: #1135.

**Back.** Pair with code → back hides the keyboard, then returns to the scanner, then to the welcome screen. Thread → channel list. Edit channel → back hides the keyboard, then closes the modal to the list. About → Settings → channel list.

**Rotation.** Thread: portrait → landscape → portrait kept the conversation, its title and the typed draft. Edit channel: the modal stayed open through both rotations, but the unsaved name edit reset to the stored name. That reset is by design: `EditChannelModal` keeps its typed values in `remember`, not `rememberSaveable`, so a pasted credential in the prompt never enters the saved-state Bundle. #1135 notes it for the product decision on the name field alone.

**Small screen.** `wm size 720x1280` at `wm density 320` gives 360×640 dp, smaller than the managed Pixel 2's 411×731 dp. The channel list scrolled to its last row ("Untitled discussion" in Chats) fully on screen. The thread opened at its last message, whose timestamp and copy icon sat above the composer, and it stayed pinned to the end while the demo reply streamed. The footer truncates its labels at that width ("Act…", "unkno…").

## 4. Feature checks

Every test named below ran in the candidate's `./gradlew test` run in section 2 with no failure or skip.

| Feature | Proof |
|---|---|
| Message and code-block copy | `MessageBubbleTest` (7 tests): `copy_putsOnlyThatMessagesTextOnTheClipboard`, `copy_fromTheUserBubble_putsOnlyTheUserText_onTheClipboard`. `MarkdownTextTest` (19): `each_copy_control_copies_only_its_own_block_source_exactly`. Emulator: every bubble shows its copy control. |
| Tables | `MarkdownTextTest`: `table_renders_its_header_and_body_cells`, `wide_table_scrolls_horizontally_without_widening_its_container`. `MarkdownTextParsingTest` (28): the pipe-table and column-alignment parses. |
| Static task-list marks | `MarkdownTextTest`: `task_list_marks_distinguish_checked_from_unchecked_and_are_inert`. `MarkdownTextParsingTest`: the check-box token tests. |
| Strikethrough | `MarkdownTextTest`: `double_tilde_renders_struck_and_de_emphasised`, `single_tilde_renders_struck_and_de_emphasised`, `home_relative_paths_keep_their_tildes_and_strike_nothing`. |
| Tool-row expansion | `ToolCallRowTest` (14): `a_row_stays_expanded_while_it_is_updated_in_place`, `output_is_hidden_while_running_and_revealed_on_resolution`. No test taps an expanded tool row closed; the emulator did: tapping the demo thread's Read row showed its output, and tapping the header again hid it. |
| Draft retention per chat | `ThreadViewModelTest` (197): `draft_seedsFromTheStore_soAReturnedToChatRestoresItsText`, `onDraftChange_writesThisChatsPairAndNoOther`. `ComposerDraftStoreTest` (23): `draft_roundTripsExactText`. Emulator: a draft typed in Pyrycode Mobile was absent from Joi Pilates and back in Pyrycode Mobile on return, after two rotations and on the small display. |
| No cross-host leakage with two paired hosts | Unit: `ComposerDraftStoreTest.sameConversationIdOnTwoHosts_holdsTwoIndependentDrafts`, `ThreadViewModelTest.draft_isIndependentPerHost_forTheSameConversationId`, `HostConversationSourceAttentionTest.twoHostsSharingAConversationIdKeepSeparateStateAndOpeningTouchesOneHost`, `FileConversationCacheThreadTest` (threads isolated per host), `SettingsViewModelTest.defaultWorkspaceLabel_cannotReadAnotherHostsLabelForTheSameCwd`, `HostChannelListViewModelTest.confirmingUnpairDropsThatHostsDraftsAndLeavesEveryOtherHostsAlone`. Live, pending the live gate: `interactiveTurn_twoHostsCollidingConversationId_stayPerHost`, `interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched`, `interactiveTurn_twoHostsDefaultsAndArchive_stayPerHost`, `interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost`. |

The Settings version row, observed on the demo APK built at the same commit, reads "Version 1.0.0 / build bf3749d8". The real-data APK cannot reach Settings without a paired host. Its `BuildConfig.GIT_SHA` is `bf3749d8`, and that string is in its `classes4.dex`. `AboutScreen` renders the row from `BuildConfig.GIT_SHA`.

## 5. APK and gaps

**APK.** `./gradlew assembleDebug`, at `bf3749d8` with a clean tree and no `-PuseRelayRepository` property, built `app/build/outputs/apk/debug/app-debug.apk`. The generated `BuildConfig` has `USE_RELAY_REPOSITORY = true` and `GIT_SHA = "bf3749d8"`, the value the Settings version row shows.

- SHA-256: `4fc9f38a46fe9665a6c92735ab6bea0f20a4d8d9ec5aaf1394b32115917d2688`
- Size: 102334052 bytes

### Gap list

| Gap | Issue |
|---|---|
| In landscape the keyboard hides Edit channel's focused field; whether the unsaved channel name should survive rotation is an open product decision | #1135 |
| Scanner top-bar offset, channel-list density, assistant bubble colour and modal sheet tone differ from the frames | #1136 |

**Push notifications.** Firebase setup #579, token registration #361 and alerts #685 have all shipped (closed). The live proof is `interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread` and `interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect` (#955), both on the live list. Background-notification parity holds for this candidate only once the live gate passes those two methods; until then it is not claimed.

**Not gaps, pending.** The dispatcher's `ui`, `scripted-all` and `live` results on the PR head. A failure there is filed as its own issue and added to this list.

### Open question resolved

The emulator hosted the layout checks inside the run budget once the dispatcher's `ui` gate had finished, one emulator at a time under the device hold. The session took about 20 minutes and no crash occurred.
