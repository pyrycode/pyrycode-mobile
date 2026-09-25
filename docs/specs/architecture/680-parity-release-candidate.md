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
| Continuity | #849 (#858) | `interactiveTurn_peerQueue_staysConsistentAcrossClients` | none; the drop step checks the queued row is gone, not its text (#859) |
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

LAYOUT_SECTION

FEATURE_SECTION

## 5. APK and gaps

**APK.** `./gradlew assembleDebug`, at `bf3749d8` with a clean tree and no `-PuseRelayRepository` property, built `app/build/outputs/apk/debug/app-debug.apk`. The generated `BuildConfig` has `USE_RELAY_REPOSITORY = true` and `GIT_SHA = "bf3749d8"`, the value the Settings version row shows.

- SHA-256: `4fc9f38a46fe9665a6c92735ab6bea0f20a4d8d9ec5aaf1394b32115917d2688`
- Size: 102334052 bytes

GAP_SECTION
