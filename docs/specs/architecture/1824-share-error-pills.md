# Share failures in top Error pills

## Files read

- `MainActivity.kt`: `MainActivity.onCreate` collects share notices into a root snackbar; `PyryNavHost` transfers before navigating and conceals the Direct Share picker.
- `ui/conversations/share/ShareIntake.kt`: `ShareIntakeViewModel.accept` and `select` emit resource/count pairs without content; capture ownership remains here.
- `ui/conversations/share/SharePickerHeader.kt`: `SharePickerHeader` supplies the picker header and preview.
- `ui/conversations/list/ChannelListScreen.kt`: `ChannelListScreen` places create-chat errors below measured Scaffold header padding.
- `ui/conversations/thread/TransientErrorNotice.kt`: `TransientErrorNoticeState.enqueue` preserves FIFO and occurrence identity; `TransientErrorPill` supplies inert polite semantics.
- `ui/conversations/thread/ThreadTopOverlay.kt`: `followingErrors` draws after persistent notices and above Offline Retry's expanded target.
- `ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen` already positions the overlay below its measured header.
- `ShareIntakeTest`, `ShareActivityTest`, `SharePickerTest`, `ThreadTransientErrorTest`: ownership, navigation, timing and accessibility fixtures.
- `docs/knowledge/features/navigation.md`: Incoming shares requires synchronous transfer before navigation and preserved drafts.
- `docs/knowledge/features/thread-top-overlay.md`: transient failures use full accessibility-adjusted Short lifetime and occurrence keys, without content reflow.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=685-4337 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6619

Read both design contexts and screenshots. The frame shows a right-aligned red pill below the divider, over the messages; the component shows the Error variant. Reuse `TransientErrorPill` with X hidden, bodySmall, errorContainer/error, 6dp corners, 8dp/4dp padding and its shadow. Each screen retains its layout, 20dp gutters, 28dp clearance below its measured header and 12dp gaps after existing notices.

## Context

Share failures currently reach a root snackbar. This single presentation deliverable replaces that route without changing capture, draft merging, destination resolution or ownership. #1750 owns the later source-routing guard integration; do not import its branch or classifications. No decision record is needed.

Sizing: approximately 500 written lines including tests and plan, five production files, two internal composables and one composition-local value, no signature changes or consumer migrations, three acceptance criteria, no new reject branches. Rechecked against this plan: within all limits. The estimate's two production files undercounts the two screen hosts needed for placement. `feature/1283-notice-placement` overlaps `ThreadTopOverlay.kt`; its changes are additive placement work, not a prerequisite.

## Design

Add a `ShareErrorNoticeHost` in the share UI package. The activity mounts this host once around its content instead of collecting into a snackbar. The host formats the same resource/count pairs and provides its `TransientErrorNoticeState` through a nullable `LocalNavigationErrorNotice` with a null default. This is UI presentation state, scoped above navigation; standalone screens retain their existing behavior.

Add `NavigationErrorPill` alongside the transient helper to render the provided state's message keyed by occurrence. Append it below `ThreadTopOverlay`'s local-error/recovery content, including the Offline layout's following-error region. In `ChannelListScreen`, put create-chat and navigation errors in one right-aligned overlay column below Scaffold's measured header padding. The picker uses this same column. The concealed Direct Share surface also draws the navigation error while target resolution is pending. No screen signature changes, dependency changes or snackbar classifications.

## State and concurrency model

The host remembers its state across picker-to-thread navigation. Its `LaunchedEffect(intake, notices)` collects the existing hot channel flow on Main and immediately enqueues each notice in a child coroutine. The existing mutex gives every occurrence its full 4-second accessibility-adjusted lifetime. Host disposal cancels active and queued children. No I/O, socket changes, new ViewModel state or saved state; connection-background behavior and capture dispatcher remain unchanged.

## Error handling

Only existing client-owned resource ids, plural counts and the formatted app size limit enter pills. Capture failures and intake/selection refusals share the same presentation queue; no exception, URI, filename or share text enters this route. Persistent screen notices remain after transient expiry.

## Testing strategy

Write failing deterministic `ShareErrorNoticeTest` coverage under `app/src/sharedTest`, driving the actual production host collector and `PyryNavHost` with an injected capture function. Cover capture unreadable/size formatting, repeated occurrences, intake count refusal, selection count and size refusal across picker-to-thread navigation, Direct Share, inert polite semantics, no snackbar ancestor, expiry, accessibility extension and measured placement without row reflow. Retain existing `ShareIntakeTest`, `ShareActivityTest`, `SharePickerTest`, `TransientErrorNoticeStateTest`, `ThreadTransientErrorTest`, `ThreadTopOverlayTest` and relevant channel-list layout tests. No device-only test changes or new live scenario: the ticket explicitly assigns these local outcomes deterministic coverage and names the existing successful-share rung-3 scenario. Run required focused, lint/build/compile, formatting and final whole-unit/pre-verify checks; record XML counts.

## Open Questions

None.

## Security review

**Verdict:** PASS

- [Trust boundaries] `ShareIntakeViewModel` emits local resource/count pairs; the host must preserve this boundary and never render a capture exception, URI or filename in a notice.
- [Tokens] No credential generation, storage or transport changes; the host retains only fixed client-owned copy in memory.
- [Files and storage] Capture, private owned copies and cleanup stay in the existing intake/draft code; no path construction, writes or backup changes are introduced.
- [Android attack surface] Exported activity intent parsing stays in `SharePayload.from`; no new component, link, provider or intent acceptance path.
- [Cryptography] No crypto or Noise changes; presentation does not access keys or nonces.
- [Network and I/O] The collector receives already-classified local outcomes; no socket, relay URL, TLS, timeout or upload policy changes.
- [Errors, logs and telemetry] Existing intake classification logs and transient shown/cleared logs suffice. Never log share text, filename, URI, bytes, credentials or exception details.
- [Concurrency] The host owns collector and queued children; navigation does not cancel selection failures before rendering, and disposal cancels all notices. Existing ViewModel capture cancellation and ownership tests must remain green.
- [Threat model] Against protocol-mobile.md Security model/ADR 025: relay flooding and hostile daemon frames do not feed this local resource route; token theft mitigation remains existing Keystore storage. UI-side notice leakage is limited to generic client copy/counts, including accessibility announcements; no shared content or token is added to overlays.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-07

## Revisions

- 2026-10-07: also render the navigation notice on the startup surface while paired-host storage is loading. The old root snackbar could announce failures before the navigation graph composed; keeping a pill on that surface preserves active-screen visibility without changing startup or share capture.
