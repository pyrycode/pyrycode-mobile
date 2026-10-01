# #1312 — keep the status snowflake in place and turn it while the agent works

## Files read

- `ui/conversations/thread/ThreadScreen.kt` — `ThreadStatusArea` (the band, two layouts split on `taskCount`), `statusArm`, `StatusReading`. The band is redrawn here.
- `ui/conversations/components/ThinkingIndicator.kt` — `ThinkingIndicator` owns the `ic_thread_thinking` `Image` (tag `thinking_glyph`) and its opacity pulse. The glyph moves out and becomes `ThreadStatusGlyph` in this file.
- `ui/conversations/components/ApiRetryIndicator.kt`, `CompactingIndicator.kt`, `ResettingIndicator.kt` — each draws `ThreadStatusSpinner()` beside its label with 16dp horizontal padding.
- `ui/conversations/components/ThreadStatusSpinner.kt` — the arc spinner; deleted.
- `ui/conversations/components/ConnectionStatusIndicator.kt`, `TurnOutcomeIndicator.kt` — arms with their own 16dp horizontal padding, which must go once the band owns the leading glyph.
- `sharedTest/.../components/ThreadActivityIndicatorVisualTest.kt` — glyph geometry, 38dp text offset, pulse and arc tests.
- `androidTest/.../e2e/InteractiveStreamE2ETest.kt` — `interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy` counts a reading by `STATUS_GLYPH_TEST_TAG`.
- `docs/knowledge/features/development-verification.md` "Where a screen test goes" — a Compose test that must not run on a device may live in plain `app/src/test`.

Overlaps: #1314, #1329, #1341, #1342, #1346, #1359 touch `ThreadScreen.kt` and several branches touch `InteractiveStreamE2ETest.kt`; none edits the status-area block, so edits stay local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

`Status area` (`I533:1957;111:3525`): a 24dp band, the 14 × 16 `ic_thread_thinking` snowflake at its leading edge on the 20dp composer gutter, an 8dp gap, then the `bodySmall` label in `colorScheme.primary`. Figma draws the glyph static; the always-present rule and the 1.6 s linear rotation come from desktop (`ComposerStatusArea`, `isStatusIconTurning`, `.composer-status__icon--spinning`) by the owner's decision of 2026-09-30.

## Context

The snowflake today belongs to one arm, pulses and never turns; three other arms draw a different arc, and when nothing is live the band contributes no node, so the input field jumps. Desktop draws one glyph in a row that is always mounted and turns it only while the turn runs or the local send is pending.

## Design

`ThreadStatusArea` becomes one always-composed `Row`: `fillMaxWidth`, horizontal padding `ComposerGutter` (20dp), `heightIn(min = 24.dp)`, `spacedBy(8.dp)`, vertically centred. Children in a fixed order:

1. The glyph slot: the question glyph when `waitingForAnswers` (unchanged reading), otherwise `ThreadStatusGlyph(turning = isBusy || localSendPending)`. The slot is the first child in every state, so arm changes and the task pill never recompose it into a new node and the rotation never restarts on a label change.
2. The reading, `Modifier.weight(1f)`: the waiting text, or `StatusReading(arm)` as today. `StatusArm.None` still emits nothing; the band stays.
3. The task pill when `taskCount > 0` (unchanged pill code), no longer a separate layout.

`ThreadStatusGlyph(turning: Boolean, modifier)`, internal, in `ThinkingIndicator.kt`: the `ic_thread_thinking` image at 14 × 16 with tag `thinking_glyph`. When `turning` and `ValueAnimator.areAnimatorsEnabled()` it runs a `rememberInfiniteTransition` from 0° to 360° over 1600 ms, `LinearEasing`, restart repeat, and applies it with `Modifier.rotate`; otherwise the angle is 0°. The tag and an internal semantics key `StatusGlyphRotation` (the current angle) sit before `size`/`rotate`, so the node's bounds stay the 14 × 16 slot and tests can read the angle.

The arms keep only their text or pill: the `Image` and pulse leave `ThinkingIndicator`, the `ThreadStatusSpinner()` call leaves the three spinner arms, and every arm drops its 16dp horizontal padding (they keep the 4dp vertical padding that gives the 24dp band). `ThreadStatusSpinner.kt` is deleted. Text positions on screen are unchanged: 20 + 14 + 8 = 42dp, as before (4 + 16 + 14 + 8).

## State and concurrency model

No new state outside composition. The rotation is a composition-scoped infinite transition inside `ThreadStatusGlyph`; it is disposed when `turning` drops (angle snaps to 0°, desktop removes the class the same way) and restarted from 0° on the next rising edge. Reading the angle recomposes only the glyph's scope each frame, as the opacity pulse did for the whole `ThinkingIndicator`.

## Error handling

None: no I/O. Animator scale 0 is the only branch, and it means "still".

## Testing strategy

- `sharedTest/.../thread/ThreadStatusBandTest.kt` (Robolectric, also runs on device): `ThreadScreen` driven through each state — idle, offline, thinking, working, running tool, api-retry, compaction, Reset session, stall, turn outcome, connecting, reconnecting, task-count pill — finds exactly one `thinking_glyph`; waiting-for-answers finds none and shows its text. A second test compares the input field's bounds idle vs busy.
- `test/.../thread/ThreadStatusGlyphRotationTest.kt` (plain `app/src/test`, JVM only, because it sets the animator scale through Robolectric's `ShadowValueAnimator`, which `sharedTest`'s device build cannot import): with a paused `mainClock`, the angle advances while busy and keeps advancing, without a reset, across Thinking → Working → api-retry; it stays 0° while idle and during an idle api-retry; with `localSendPending` alone it turns; with duration scale 0 it stays 0° while busy and the Thinking label still shows.
- `ThreadActivityIndicatorVisualTest`: the 38dp-offset and pulse tests move to the band (glyph slot 14 × 16 stable across frames while turning; label at 42dp in the screen), the arc test is deleted with the spinner. The 24dp height test stays.
- `InteractiveStreamE2ETest.interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy`: the turn's own readings are counted by label (`Thinking…` prefix, `Working…`, `Running ` prefix, the stall label) instead of the glyph tag. Listed under `## Live tests`. No new rung-3 scenario: this is a visual change to an existing flow whose live check is this method.
- Existing: `ThinkingIndicatorTest`, `AgentStatusLabelsTest`, `TaskCountPillTest`, `ThreadInlineQuestionTest`, the `Scripted*` status tests.

## Open Questions

- Whether Compose under a paused test clock drives `rememberInfiniteTransition` inside the `if (turning)` group from its first frame. If not, the test advances one frame before sampling.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/thinking-indicator.md`, sections "Shape" and "Working and stalled (#1311)": the band is always composed, one glyph drawn by the band rather than by an arm, and the rotation gate (`isBusy || localSendPending`, 1.6 s linear, still at animator scale 0).
- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md`, "The arm order (#1311)": the same.

## Revisions

**2026-10-01, during implementation.**

- The reading sits in an always-present `Box(Modifier.weight(1f))` instead of taking the weight itself. `StatusReading` emits nothing for `StatusArm.None`, which dropped the weight with it and pulled a lone task pill to the glyph (`TaskCountPillTest.pill_showsAlone_atTheBandsRightEnd`). New contract: glyph, weighted reading box, optional pill, in that order in every state.
- The text-only readings measure 22dp on their own now that the 16dp glyph no longer sets their height. The band's `heightIn(min = 24.dp)` owns the 24dp; `ThreadActivityIndicatorVisualTest.shortReadingsUseTheInputStatusBandHeight` now checks that each text reading fits the band, and the pill still measures exactly 24dp.
- Open question resolved: with a paused clock and no animation running, a state write from the test is applied only after `Snapshot.sendApplyNotifications()`, so the rotation test's `advance` sends it before moving the clock.
- `ShadowValueAnimator.setDurationScale` is protected; the rotation test calls the framework's hidden `ValueAnimator.setDurationScale` through Robolectric's `ReflectionHelpers`, which is still the value `ValueAnimator.areAnimatorsEnabled()` reads.
- `ThreadStatusBandTest` runs the per-state check with native graphics at Figma's 412dp reference width, where every reading is one line; a reading that wraps at a narrower width raises the band, as it did before. The absolute-dp checks (42dp label start, 14 × 16 slot) run at the default configuration because `ForcedSize` rescales density.
- `ThreadScreenModalTest`: the always-present band shortens Robolectric's message area by 32dp. `context_rows_render_between_the_prompt_and_the_options_in_desktop_order` scrolls each row into view before asserting it, and the offline test taps the always-allow toggle's lower part, because the scroll now leaves that row under the Offline retry pill at the message area's top.
- `TaskCountPillTest.zeroCount_showsNoPill_andThePillDoesNotMoveTheBand` is renamed and its assertion flipped: the band is always present now, so the test checks that showing the pill leaves the band in place.

**2026-10-01, rework after review.**

- The verifier's SHOULD FIX: the live test's turn-reading matcher searched the whole tree, so a streamed reply line opening with "Running …" could pass for a reading. The band's reading box now carries the tag `STATUS_READING_TEST_TAG`, and `interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy` counts a turn reading only beneath it. `ThreadStatusBandTest.turnReadings_sitInTheTaggedReadingBox` asserts the tag holds the reading.

**2026-10-01, rework after the live gate.**

- The full live suite ran 41 methods: 40 passed, including `interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy`. One failed: `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` times out in `openChatRow` when it reopens the asking chat. It fails the same way on `origin/main` alone, so it is outside this ticket. It is filed as #1445 and isolated with `@Ignore("blocked on #1445 …")`, in the style of the #1397 ignores. No production code changes.

**2026-10-01, rework after triage of the script tests.**

- The verifier's MUST FIX: the `@Ignore` on `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` left the method in the curated LIVE `TEST_TARGET` list in `scripts/e2e-emulator.sh`, which `scripts/test_android_test_gate.py` requires to match the runnable methods. The method leaves the list with a #1445 exclusion comment; `LIVE_MINIMUM` follows from the list.
- The PR's `## Live tests` now reads `all`, so the live gate runs the full suite after the ignore. That run is AC4's evidence and covers the band-driving methods the verifier named.
