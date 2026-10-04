# Create chat failure overlay (#1748)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`: `ChannelListScreen` owns the request-keyed snackbar; `ChannelListTopBar` owns header geometry.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt`: `createChat` publishes saving and failed states with monotonically increasing request IDs.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`: `NoticePill` already implements the Error tokens, label sizing and overlay shadow.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt`: existing failed-create, toolbar and tree interaction coverage.
- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt`: real-activity list capture harness.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignInputs.kt`: host-source override allows a failing repository for the capture.
- `app/src/androidTest/assets/design-1220/README.md`: real bars, viewport metadata and comparison contract.
- `docs/knowledge/features/channel-list-screen.md`: preserve fixed toolbar and tree geometry; lazy rows below the fold need scrolling before assertions.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=685-4337

Inspected the frame and Error component `347:6619` on 2026-10-04. The reference shows a compact right-aligned red notice over content with no X, body-small text, 6dp corners, 8/4dp padding and overlay shadow. Reuse `NoticePill` with `errorContainer`/`error`; #1604 authorises list placement 28dp below the measured header and at the 20dp right gutter. This is notice reuse, not a replacement list frame. There is no existing list notice to stack; future notice stacking would use 12dp gaps.

## Context

Replace only the Create chat failure snackbar. No daemon interaction, new action, dependency or decision record is needed. Branch #1665 overlaps the screen and capture/test files to add a toolbar menu; keep this additive in the Scaffold body so that merging requires no redesign.

## Design

Keep `HostChannelListState`, `CreateChatState` and event contracts unchanged. A composition-local Boolean controls the timed pill. A full-size Box in the Scaffold body draws the unchanged tree first and the pill last, aligned to the right within 20dp gutters. Scaffold's measured top padding anchors the overlay; the header's existing trailing tree gap remains untouched.

## State and concurrency model

Retain `LaunchedEffect(createChat?.requestId, createChat?.failed)`. Each failed request shows the pill, delays for the Short snackbar timeout (4000ms), then hides it. Use `LocalAccessibilityManager.calculateRecommendedTimeoutMillis` with the same icons/text/controls flags as Material's snackbar (true/true/false). Do not key the effect on presentation state or recomposition. A new request cancels the old timer and clears its notice; leaving composition cancels the effect. No new ViewModel job or flow.

## Error handling

Render only unchanged `create_chat_failed`, never exception text. Error pill has no click or dismissal callback. Existing repository failure classification and structured logging remain in the ViewModel.

## Testing strategy

Test first with deterministic shared screen coverage for actual `CreateChatState.failed`: placement relative to the measured header, text, no click/X/snackbar, unchanged tree geometry and usable toolbar; default expiry, accessibility timeout flags/adjustment, no replay after recomposition, fresh request and screen exit. Run existing list screen, colour and ViewModel tests.

Extend only `ListDesignCaptureTest` with a dedicated 412x892 failed-create method driving the real list plus through a failing repository. Device-only reason: hardware framebuffer and real system bars. Run the focused method on API 33 and full `pixel8Api35` with real bars required; retain PNG, viewport/inset sidecar, fresh XML counts and comparison against `685:4337`, judging only reused notice styling/relative placement. No real-Claude proof is required for deterministic presentation of an existing failure.

Run focused JVM tests, lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck.

## Open Questions

None.

## Documentation handoff

- Pending documentation stage: `app/src/androidTest/assets/design-1220/list/index.md`, Create chat failure entry and verdict, citing Error-pill/placement reuse from `685:4337` rather than claiming a new list frame.
- Pending documentation stage: `docs/knowledge/features/channel-list-screen.md`, describe the timed Create chat failure notice.

Sizing: one deliverable, approximately 400 written lines including plan/tests/evidence metadata; no new exported production type or consumer update, three acceptance criteria, no new error branch. Within all builder limits.

## Revisions

### 2026-10-04 — capture inspection

The real-bar capture places the notice at x=173..392, y=149..171: the right gutter is 20px and its top is 28px below the measured list header. The reused shared `NoticePill` trims its body-small line box, producing a 22px single-line background rather than Figma's 24px. This inherited shared-component difference is deferred to #1757; the current ticket preserves reuse, tokens, padding and actions. Record it in the documentation-stage verdict. No state or placement contract changed.

### 2026-10-04 — verifier accessibility repair and main merge

PR #1758's MUST FIX finding identified the SnackbarHost's polite announcement as a separate accessibility contract from its timeout adjustment. Add `LiveRegionMode.Polite` semantics at the Create chat `NoticePill` caller, retaining its inert behavior, request identity and timer. The regression test now requires the polite live region and independently checks that the sole failure message belongs to the pill and there is no snackbar dismissal action.

Merged main's #1665 toolbar menu around the existing Scaffold-body notice. Preserve the menu anchor, actions and overlay, and exercise Settings and Archive through that menu in the notice test. No notice geometry changes. Pending documentation stage: include polite announcement in the channel-list feature description.
