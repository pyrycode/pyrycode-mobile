# Transient thread and reader error pills (#1747)

## Files read

- `docs/knowledge/features/thread-screen.md`: measured header and overlay placement.
- `docs/knowledge/features/thread-top-overlay.md`: persistent notice ordering and session-error ownership.
- `docs/knowledge/features/markdown-reader-screen.md`: reader chrome and local failure routes; measured bar plus 28dp is the notice anchor.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: error collectors, attachment outcomes and `dismissReasonText`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt`: persistent pill stack.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt`: `RefreshableMarkdownReader`, open and save outcomes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentActions.kt`: `AttachmentNotice` and static outcome strings.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentSendFailure.kt`: client-owned failure copy.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`: reusable Error visual.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreenTest.kt`: real local open/save/refresh actions and picker fake.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlayTest.kt`: stack geometry and retained actions.
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`: failure frame and reachable reader route.
- `app/src/androidTest/assets/design-1220/README.md`: full-device pixel evidence and comparison contract.

## Design source

Figma: [thread failure 685:4337](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=685-4337), [reader failure 696:5101](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=696-5101), [Error pill 347:6619](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6619), inspected through design context and screenshots on 2026-10-04.

The inert right-aligned Error pill uses bodySmall, errorContainer/error, 6dp corners, 8/4dp padding and the existing overlay shadow, without X. Both frames place it at 20dp side gutters, 28dp below the measured bar through its rule (97dp at baseline). Reuse `NoticePill`; leave the reader body and thread layout intact.

## Context

Thread and reader transient errors currently cover content/actions at the bottom. Move only failures to the existing visual language. Saved and dismissed-elsewhere notices retain their behavior; their unresolved design differences remain outside this ticket. No wire or daemon interaction changes.

Overlap: #1619, #1642, #1660 and #1668 touch shared files, but their changes are independent; keep additions local. The reader capture does not require #1619's pending method.

Sizing: forecast about 950 written lines including plan, tests and capture harness changes; four production files, one new state type, two optional consumer parameters with no required caller migration, five criteria, no new domain reject branches. One deliverable: replace presentation of existing transient failures.

## Design

Add `TransientErrorNoticeState` beside the screens, with a suspend `show(message)` contract and observable nullable current text. It serializes callers using a coroutine Mutex and retains each notice until its timeout, clearing only its own transient state. `rememberTransientErrorNoticeState` supplies the accessibility-adjusted Short timeout (4000ms, text=true, icons=false, controls=false), matching Material snackbar policy.

`ThreadScreen` remembers the state per conversation. Route new-session/archive/workspace/run-configuration failures, both attachment refusal counts, attachment send failures and markdown-open failures into it. Attachment NO_APP/OPEN_FAILED/SAVE_FAILED use it too; SAVED still uses the existing snackbar. Append an optional transient text to `ThreadTopOverlay` after session errors using the same 12dp stack gap. Existing persistent notice priority and actions stay intact.

`RefreshableMarkdownReader` shares the error state with `MarkdownReaderScreen` via an optional parameter, so a failed reread joins the same queue as failed open/save. The reader draws the transient pill as a sibling layer at measured bar height plus 28dp with 20dp gutters. Its SnackbarHost receives Saved only. Copy gains no failure path.

## State and concurrency model

All state stays on the UI dispatcher in composition. Collectors use their existing LaunchedEffect jobs; callback outcomes use rememberCoroutineScope. Mutex acquisition is cancellable and FIFO. Leaving composition cancels active and queued calls, and finally clears the active notice. Nothing is persisted. Conversation identity replaces the thread state and restarts its collectors. No new ViewModel, StateFlow, background job or injected dispatcher is needed.

## Error handling

Keep existing resources, plurals and enum-to-resource mapping; never display an exception, file path, URI or wire payload. New presentation adds no error classification and retains the existing structured lifecycle logging at the action boundary.

## Testing strategy

Write failing deterministic tests before implementation. Unit tests prove queue order, full per-item timeout and cancellation of active/waiting calls. Shared Compose tests prove every thread failure route, both refusal reasons, all attachment-send classifications, no click/dismiss semantics, top geometry, expiry, persistent coexistence and Saved/dismissed-elsewhere snackbar coexistence. Extend reader action coverage to assert failure-pill semantics and snackbar-only Saved, plus adjusted accessibility timeout and removal on screen exit. Run affected existing overlay, attachment, reader and layout classes.

Device-only capture is necessary for real system bars and saved pixels. Update `rowAndNoticeFramesAt412By892` to capture the pill and wait via the test clock instead of snackbar dismissal. Update the switch-back capture's wait the same way. Add a reachable reader error capture through the production reader Refresh action. Run these selected methods on full pixel8Api35 with requireRealSystemBars, retain PNGs, comparisons, inset/viewport metadata and fresh XML counts. Keep unrelated retained captures unchanged. No real-Claude scenario is required: this only presents existing local flows.

## Open Questions

None. Reader error evidence may use failed Refresh (the existing client-owned open-failed text) as the reachable failure variant of 696:5101, whose fixture depicts Save failed; record that copy difference without claiming the excluded Saved variant is fixed.

## Documentation handoff

Pending for the documentation stage:

- `app/src/androidTest/assets/design-1220/thread/index.md`, Failure notice verdict: update from fresh failure evidence; update the reader-error entry under Reachable states (#1539) if present, otherwise add it.
- Preserve unresolved non-error dismissal/Saved discrepancies and #1619's scope conflict.
- `docs/knowledge/features/thread-top-overlay.md` and `thread-screen.md`: fold in transient placement, FIFO lifetime and cancellation.
- `docs/knowledge/features/markdown-reader-screen.md`: fold in error overlay placement and lifetime, retaining Saved behavior.

## Revisions

### 2026-10-04 — preserve Material accessibility flags

Inspection of the installed Material 3 1.4.0 `SnackbarHostKt.toMillis` confirms Short is 4000ms and always passes icons=true, text=true, controls=hasAction to the accessibility manager. Match those flags (controls=false here), rather than deriving icons=false from the new pill's appearance, to retain the previous adjusted timeout exactly.

### 2026-10-04 — transient pill height

The full-device capture showed a 22dp short Error pill: shared typography trims the line box, while the Figma frames use a 24dp pill. Give only `TransientErrorPill` a 24dp minimum visible height, retaining its bodySmall text, 8/4dp padding and growth for wrapped or enlarged text. Persistent pills remain unchanged. Pin the floor with a native text-measurement test and refresh both requested captures.

### 2026-10-04 — verifier rework: fixture isolation and visible Offline gap

The verifier found the reader's blocked-directory fixture replaced a production shared-storage path that persists between device methods. Override only that fixture's `LocalContext.noBackupFilesDir` with a `TemporaryFolder` root; guaranteed rule cleanup leaves production shared-note storage untouched. Verify the entire `MarkdownReaderScreenTest` class on the managed device, including failed open, no-viewer open and leaving-reader cancellation.

Offline's 48dp Retry box reserved extra invisible height before a following notice. A local `Layout` measures the visible Offline pill, its existing 144×48dp top-aligned clickable target and a following-errors column separately. Place the following column at the measured visible height plus 12dp; enclose the full target in the parent so bottom-edge hits work. Draw the target above Offline but below following errors so an inert error cannot activate Retry where their surfaces overlap. Preserve usage-dismiss separation, persistent ordering and transient expiry. Add native coexistence geometry and real pointer coverage of both the inert error and the exposed bottom edge of Retry. Existing failure and reader-error captures have no Offline notice and remain unchanged.

The separated Retry target owns the existing client-owned accessibility label and button action. Hide its decorative visible pill from accessibility to avoid duplicate announcements; geometry tests measure the visible text surface separately from the labelled 48dp target. The coexistence test requires the labelled Retry node to remain actionable.

### 2026-10-04 — out-of-scope device memory failure

The full device class run passed the three verifier-named storage/cancellation methods, then Android killed the app at about 1.3GB RSS during the pre-existing `copiesOfANoteAtTheReadersBound_areBounded` test. Filed #1759 for the reader-bound rendering allocation issue; mark that test `@Ignore` with the bug link per the builder handoff rule and retain the failed device evidence. Do not change reader rendering or clipboard contracts in this notice ticket. Rerun the entire reader class after the ignore, reporting its one skipped method explicitly.

### 2026-10-04 — out-of-scope native pairing touch overlap

The subsequent device run passed the full reader class and the new Offline/transient geometry and pointer test, but the existing `theUsagePill_sitsAboveThePairingPill_whichStartsRePair` assertion found a 3.5px native touch-bound overlap. The pairing branch and its 36dp touch configuration are unchanged by this ticket. Filed #1760, retained its failed XML and linked the existing test's ignore to the bug, rather than altering persistent pairing/usage behavior here. Final class runs report both known skips explicitly.
