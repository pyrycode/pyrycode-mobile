# Screen-local Default confirmation pills (#1851)

## Files read

- `docs/knowledge/INDEX.md`, `features/thread-screen.md`, `features/thread-top-overlay.md`, `features/notice-pill.md` and `features/markdown-reader-screen.md`: owning surfaces, existing token mapping and overlay order.
- `docs/knowledge/features/development-verification-gates.md`, `development-verification-compose-evidence.md` and `docs/e2e-interactive-stream.md`: shared tests, real-bar capture and live evidence ownership.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/TransientErrorNotice.kt`: `TransientErrorNoticeState.show`, `enqueue` and accessibility timeout; reuse the FIFO and occurrence implementation without changing error consumers.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: attachment callback, modal-id effect and `ThreadTopOverlay` call.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt`: `dismissReasonText` preserves all source mappings.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt`: persistent/error order and Offline's visible-height layout.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt`: save callback and measured-header overlay.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`: inert Default treatment with untrimmed bodySmall line box.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/TransientErrorNoticeStateTest.kt`: virtual-time queue and cancellation checks.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadTransientErrorTest.kt`, `ThreadScreenAttachmentLoadTest.kt` and `MarkdownReaderScreenTest.kt`: real producers, picker results and existing snackbar assertions.
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`, `DesignCapture.kt` and `e2e/InteractiveStreamE2ETest.kt`: assembled app capture and existing peer save scenario.

## Design source

Figma: [Default thread notice 696:5065](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=696-5065) and [reader overlay 696:5101](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=696-5101), inspected with design context and screenshots on 2026-10-07.

The inert Default pill uses `primaryContainer` / `onPrimaryContainer` (static dark #134A74 / #CFE4FF), bodySmall (12sp/16sp, 0.4sp tracking), 6dp corners, 8/4dp padding and the existing overlay shadow. The screenshots place the surface at the right 20dp gutter, 28dp below the measured bar through its rule, with 24dp single-line height. Reader frame 696:5101 depicts Error, so use only its overlay placement with Default colors and existing File saved copy, as the ticket specifies.

## Context

Move the three remaining thread/reader confirmation routes from bottom snackbars into Default pills. This is one presentation deliverable with four acceptance criteria, no domain or wire change and no new error branches. Forecast 900 written lines including tests, captures and plan; four production files, two new internal helper functions, one optional overlay contract extension and no required bulk caller migration. Codegraph caller results were incomplete; repository searches confirm no caller supplies the reader's snackbar parameter.

Overlap: #1866 adds suggested-reply inputs to `ThreadScreen` and its composer call; those edits are independent of these local notice additions.

## Design

Reuse `TransientErrorNoticeState` as the queue implementation in a separate remembered confirmation instance. A confirmation factory shares the existing accessibility timeout calculation, while a static lifecycle event distinguishes confirmation logs from error logs. Add an inert `TransientConfirmationPill` that delegates to `NoticePill(isError = false)` and keys rendering by occurrence. Error types, callers and timing stay unchanged.

Thread Saved and `dismissReasonText` feed this same confirmation queue. Enqueue directly from callback/effect into a composition-owned scope so an effect-key change cannot cancel an earlier dismissal or reorder it with Saved. Preserve `LaunchedEffect(modalState.modalId)` and the existing reopening behavior. Remove the thread snackbar host and reader snackbar parameter/host; no caller supplies that parameter.

Append optional confirmation text/occurrence to `ThreadTopOverlay`. Render it after navigation errors inside the following-notice column, including the Offline layout and stopped-turn following-notice seam, so visible geometry retains 12dp gaps. Reader uses a right-aligned overlay column at its existing error position, errors above confirmation, without changing body reservations.

## State and concurrency model

All new state is UI-local Compose state on the UI dispatcher. Mutex FIFO and monotonically increasing occurrence identify equal-text notices independently; every item receives its own full adjusted 4,000ms delay starting when it becomes active. Errors have their existing independent queue and can appear while confirmations are active. The thread confirmation scope and state are keyed by conversation identity; the reader scope belongs to its composition. Screen exit cancels active and waiting jobs, with finally clearing the active message. No persistence, ViewModel jobs, reconnect or history behavior is added.

## Error handling

Preserve dismissal source mapping and attachment enum-to-resource mapping. Only existing client-owned text reaches the pill. Confirmation lifecycle logs contain static event/phase fields, never copy, file names or paths. Save failures remain errors in their existing queues.

## Testing strategy

Write the screen routing assertions red first. Unit probes drive the reused production queue with empty, single, repeated and differently ordered occurrences, per-occurrence timeouts, independent error queue, and owner cancellation between admissions. Shared tests drive every dismissal source, recomposition, modal-id transitions, reopening, equal text and full timeout, accessibility flags/extension, screen exit, Saved picker results and error coexistence. Measure inert semantics, top/bottom stack order, unchanged content bounds and Offline 12dp spacing. Run existing error, overlay, attachment, reader and layout coverage as well.

Device capture is necessary for saved hardware pixels and real system bars. Recapture `dismissalNoticeFrameAt412By892` and add reachable thread and reader Saved states through production actions with an intent stub supplying a private document destination. Retain only these three states, viewport/inset metadata, measured notice geometry, comparison verdicts and fresh XML under `app/src/androidTest/assets/confirmation-1851/`. Run the three selected methods on full pixel8Api35 with `requireRealSystemBars=true`; ATD metadata alone is not pixel proof.

Extend only `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` after its existing save helper returns: require File saved as an inert Default pill in the top overlay and no bottom snackbar. Shared deterministic picker/dismissal tests prove local presentation; no additional daemon turn or scripted state is needed. Dispatcher owns fresh full live-gate execution and counts; list the method in the PR's Live tests section.

Run focused JVM tests, lint, assembleDebug, androidTest compilation and forced Spotless. Commit/push, merge final main, then run the whole unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle` before opening the PR.

## Open Questions

None.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/thread-top-overlay.md` and `thread-screen.md`: confirmation placement below errors, independent FIFO lifetime and cancellation.
- `docs/knowledge/features/markdown-reader-screen.md`: replace Saved snackbar description with Default overlay behavior.
- `docs/knowledge/features/development-verification-gates.md`, Snackbar routing guard: remove the three migrated production routes from the documented remaining snackbar inventory.
- `app/src/androidTest/assets/design-1220/thread/index.md`, prompt-resolved-elsewhere and Saved states: fold in the three notice-only match verdicts from `confirmation-1851/`.
