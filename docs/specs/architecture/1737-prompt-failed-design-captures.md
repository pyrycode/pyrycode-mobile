## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt`: `walk`, failure-notice overrides and modal focus/IME helpers establish assembled routes and cleanup.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignInputs.kt`: `module` supplies the thread repository separately from `HostConversationSource`.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignCapture.kt`: `capture` requires nonblank hardware pixels and real bars when requested.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`: `CreateChannelModalBinding` maps confirmed creation to locked name and prompt-failure copy.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `SaveAsChannelDialog` binding maps confirmed promotion to locked name and prompt-failure copy.
- `app/src/main/java/de/pyryco/mobile/ui/components/ChannelFormFields.kt`: field semantics, 0.38 disabled text alpha and shared geometry.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt`: final-content error and enabled action presentation.
- `docs/knowledge/features/channel-list-screen.md` and `save-as-channel-dialog.md`: host-specific creation and two-leg promotion contracts.
- `docs/knowledge/features/development-verification-compose-evidence.md`: separate dialog insets, keyboard sequencing, temporary fixture cleanup and current Figma exports.
- `app/src/androidTest/assets/design-1220/README.md` and `list/1862-evidence.txt`: evidence format and real-bar coordinate accounting.

## Design source

Figma: Create channel / Prompt failed `784:7095` and Save as channel / Prompt failed `784:7134`, section `784:7094`, file `g2HIq2UyPhslEoHRokQmHG`. Both read with design context and screenshots on 2026-10-08. The shared full-height modal has 28 dp gutters, 20 dp shell gaps, 6 dp field/button radii, a 28 dp circle-xmark asset and a final-content error. Name is `Release notes`, disabled at 0.38 opacity; the retained prompt is `Summarise each merged pull request in one plain sentence for the release notes.`; OK stays enabled.

Tokens: shell Schemes/On Primary Fixed (#001D34), title/labels On Primary Container (#CFE4FF), field On Background (#E0E2E8), field fill On Primary (#003355) at 41%, Primary (#9DCBFC), Error (#FFB4AB), separator Inverse Primary (#32628D) at 60%. Roboto titleLarge 22/28/400, labelLarge 14/20/600, bodyMedium 14/20/400 and emphasized bodyLarge 16/24/500.

## Context

#1592 decided reuse for the other list-side states and drew these two new frames. #1588 and #1862 are closed and their changes are on the starting main. This ticket audits only these frames; production fixes belong in focused follow-ups. No decision record is needed. No other remote feature branch overlaps the planned test files at planning time.

## Design

Add one default-viewport device method to `ListDesignCaptureTest`, covering creation and promotion sequentially through existing UI routes. Inject a list-source repository that delegates creation to the fake but rejects the subsequent nonblank prompt write. Add a test-only prompt-write failure switch to the existing `DesignInputs` repository wrapper so the thread's actual consumer rejects its write too. Use a temporary chat for promotion and remove both newly created conversations in `finally`; restore the prior host source and failure switch and dispose the temporary source.

Assert the exact resource error, both retained field values, disabled name, editable prompt, confirmed promoted conversation and enabled OK before each capture. Open/observe the dialog IME, close it with physical Back before submission, then await and assert focused-window hidden/zero IME before and after failure capture. Retain only two default 412x892 PNG/metadata pairs, fresh Figma exports, comparisons, XML and a measurement report in `design-1220/list`; no compact capture or unrelated evidence changes.

## State and concurrency model

No production jobs or state change. The existing capture rules install and restore Koin definitions, theme, display and IME. The failure switch is volatile because the thread's existing coroutine reads it; reset it in `finally`. Temporary source disposal cancels its collectors. Fixture mutations run through the fake repository and are removed after the Activity closes.

## Error handling

Only the test-only system-prompt write throws a static deterministic exception; creation/promotion continue through the real fake implementation. Production reducers classify this into their existing prompt-failure states. Test failures still restore overrides and delete temporary fixtures.

## Testing strategy

Device-only reason: fresh nonblank hardware screenshots, real bars and separate dialog focus/IME state cannot be established by Robolectric. First run the new method with only the list override; expect Save as channel's error assertion to fail because the thread wrapper remains unwired. Then wire its failure and rerun the method on full `pixel8Api35` with `requireRealSystemBars=true`; inspect fresh XML and executed/failed/skipped counts. Run lint, assembleDebug, compileDebugAndroidTestKotlin, Spotless and the final whole unit/shared suite plus pre-verify after merging main. No real-Claude flow is added; these deterministic fake-backed audit states do not change operator behavior.

Measure native-scale geometry/padding/spacing/borders/radii with 2 dp tolerance, exact role tokens/type styles/assets, and name alpha 0.38 ±0.01. Account for real status/navigation insets separately from centered content. Search issues and link every out-of-tolerance finding without fixing production.

## Open Questions

None; the comparison verdict is measured after capture and recorded in the retained evidence.

## Documentation handoff

Pending documentation stage:

- `app/src/androidTest/assets/design-1220/list/index.md`, **List states**: add both frame verdicts, provenance, counts, measurements/comparison paths and mismatch links using this ticket's retained evidence.
- `app/src/androidTest/assets/design-1220/README.md`, inventory and associated gap prose: update only #1592's decisions, splitting rows with different statuses. Saving in Edit channel/chat, Create channel, Save as channel and Edit host: no separate frame, Modal `489:1942` and Loading/Primary Button `654:2975`, Pairing Connecting `654:4882`. Inline failures `archive_failed`, `edit_channel_save_failed`, `edit_chat_save_failed`, `create_channel_failed`, `save_as_channel_failed`, `edit_host_save_failed`, `edit_host_unpair_failed`: no separate frame, Modal end-of-content error from Pairing Verification failed retry `654:4932`. Host unavailable/disabled OK: no separate frame, Create channel Empty `671:5558`. Every reuse row must include `(decision on #1592)` and drawing node/component.
- Same README: prompt failures use `784:7095` / `784:7134`, measured `audited, match` or `audited, mismatch` and #1737/audit entry rather than #1504. Create-chat/Archive restore failures reuse Error `685:4337` and Pill `347:6619`, captured by #1604, with no recapture. `Restored <name>` is outside reference, default Material confirmation snackbar. Archive loading/error reuse centered empty text `673:3577` / `673:3621`, without tabs. Correct decided-state gap prose without re-auditing other rows or implying app-wide parity.
