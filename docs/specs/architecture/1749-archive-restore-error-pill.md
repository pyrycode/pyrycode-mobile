# Archive restore failure Error pill (#1749)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt`: `ArchivedDiscussionsScreen` sequentially collects restore effects and owns the success snackbar and header.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt`: `ArchivedDiscussionsEffect` is a buffered, payload-free failure or named success; repository error classification stays unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`: `NoticePill` supplies the shared Error colours, body-small label, corners, padding and shadow.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`: failed-create overlay supplies the placement and accessibility timeout precedent.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsLayoutTest.kt`: existing Archive geometry and interactions remain covered.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/CreateChatFailureNoticeTest.kt`: native Robolectric rendering and controlled clock pattern for transient notices.
- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt`: `archiveChannels` and failing repository override support a narrow real-activity capture.
- `app/src/androidTest/assets/design-1220/README.md`: capture with real bars on the full API 35 image, retain metadata and comparison artifacts.
- `docs/knowledge/features/archived-discussions-screen.md`: keep the owning host, tabs and row geometry unchanged.
- `docs/knowledge/features/channel-list-screen.md`: polite live-region semantics preserve announcements; shared pill line-box discrepancy is already tracked by #1757.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=685-4337

Read the frame and Error component `347:6619` on 2026-10-04. The reference shows a right-aligned body-small Error pill without X, with errorContainer/error tokens, 6dp corners, 8dp horizontal/4dp vertical padding and an overlay shadow. #1604 authorises reuse on Archive within 20dp side gutters, 28dp below its measured header; the thread content is not an Archive reference. Archive has no existing sibling overlay notice to stack beneath. Reuse the shared component, including the inherited line-box difference tracked by #1757.

## Context

Restore failure currently appears in a bottom snackbar, contrary to #1604. Only failure presentation changes. #1748 is merged and closed; no in-flight branch overlaps the planned production or capture file. No decision record is needed.

## Design

Keep `ArchivedDiscussionsScreen`'s public signature, ViewModel and effect types intact. Store a composition-local transient failure identity. Collect effects sequentially: success continues to await the existing snackbar; failure mounts an inert Error `NoticePill`, awaits the Short lifetime, then clears it. Distinct identities give repeated identical failures distinct notice nodes and announcements. Add the overlay as a sibling above the unchanged body, positioned with Scaffold's measured top padding. Give it polite live-region semantics, a stable test tag, no click or dismissal action.

## State and concurrency model

The existing `LaunchedEffect` remains the single sequential collector. Failure uses coroutine delay for Material Short's 4000ms, adjusted by `LocalAccessibilityManager` using icons/text/controls flags true/true/false. Clearing occurs in `finally`; leaving composition cancels the collector and timer. A frame boundary after clearing allows queued identical effects to remain individually observable. No new ViewModel job, dispatcher, hot flow or repository state is needed.

## Error handling

`RestoreFailed` retains the exact local `restore_failed` resource, with no daemon text. Persistent load errors and all repository outcomes are unchanged. A cancelled collector does not create a notice on a later screen instance.

## Testing strategy

Add native Robolectric screen tests under `app/src/sharedTest` using a buffered effect channel and controlled Compose clock. Trigger restore buttons and emit deterministic failed/successful effects; prove unchanged row/tab/host bounds, header-relative position and gutters, inert pill, polite semantics, no error snackbar, unchanged success snackbar, expiry, accessibility timeout flags, sequential identical failures and queued success, and screen-exit cancellation. Run existing Archive layout/navigation and ViewModel tests.

Extend only `ListDesignCaptureTest` with a failed-unarchive repository and one 412x892 Archive capture through real `MainActivity`. Device-only reason: hardware pixels and real system bars. Run its new method on the full API 35 image with real bars required; retain PNG, viewport/inset metadata, JUnit executed/failed/skipped counts and comparison against the retained `685:4337` reference. This deterministic presentation slice needs no real-Claude or scripted stream scenario. Run focused tests, lint, assembleDebug, compileDebugAndroidTestKotlin, formatting and forced spotlessCheck.

## Documentation handoff

Pending for the documentation stage:

- `app/src/androidTest/assets/design-1220/list/index.md`: add the restore-failure entry and verdict, citing Error-pill/placement reuse from `685:4337`, actual test counts, metadata and inherited #1757 discrepancy.
- `docs/knowledge/features/archived-discussions-screen.md`: update restore failure presentation, timer, accessibility and sequential-effect coverage.
- `docs/knowledge/features/channel-list-screen.md`: fold the Archive notice into the owning Archive/channel-list topic links.

## Open Questions

None. Forecast: about 400 written lines including plan, tests and capture; no new exported types or changed consumer call sites, three acceptance criteria and no added error-classification branches. The sizing limits hold.
