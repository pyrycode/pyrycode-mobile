# Thread connection and notice states (#1283)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`, `ThreadStatusArea`, `StatusReading` — owns the composer status band, list, and overlay mount.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt` → `ThreadTopOverlay` — existing usage and pairing pills, with a place for Offline Retry.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionBanner.kt` → `ConnectionBanner` — current connection labels and Retry callback being relocated.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/BannerNoticeRow.kt` → `BannerNoticeRow`, `bannerDisplayText` — current attributed, control-stripped session notices.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ModelRefusalRow.kt` → `ModelRefusalRow`, `refusalTitle`, `attributedBanner` — existing explanation toggle and safe text rendering.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryRows.kt` → `HistoryErrorRow` — retains its own error affordance after the connection banner goes away.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` → `ThinkingIndicator` — shared composer status reading spacing.
- `app/src/main/res/values/strings.xml` — client-owned labels for connection, warning, and refusal details.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedConnectionBannerTest.kt` → `ScriptedConnectionBannerTest` — existing connection transition and retry screen tests.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenRePairTest.kt` → `ThreadScreenRePairTest` — rejected pairing takes precedence over network retry.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadAgentAttributionTest.kt` → `ThreadAgentAttributionTest` — thread-level agent attribution and refusal toggle.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/BannerNoticeRowTest.kt` → `BannerNoticeRowTest` — notice formatting and inert text coverage.
- `docs/knowledge/features/thread-screen.md`, `docs/knowledge/features/model-refusal-row.md`, `docs/knowledge/features/banner-notice-row.md`, `docs/knowledge/features/development-verification.md` — existing behavior, trust boundary, and shared screen test guidance.

## Design source

- [Connecting thread](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-1740) and [Reconnecting thread](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4657): a muted body-small reading at the 20 dp composer gutter above attachments; the message area remains unchanged.
- [Offline thread](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910): a compact error-toned Retry pill at the top right of the message area, using the existing overlay family.
- [Session warning thread](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-5466) and [thread notification component](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1576): muted M3 body text on the message gutter, with no bubble, border, or icon. Warnings start with client-owned “Warning · ” copy.
- [Refusal in the stream](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1577): a bare title, optional agent-attributed explanation, and a Show details / Hide details label. The whole gutter-width row toggles when an explanation exists.

## Context

The existing connection banner occupies message-list height, and the banner and refusal components use older visual treatments. The supplied design places connection state next to composition or in the top overlay and treats session information as text in the stream. This is a UI presentation change; wire decoding, row identity, and the repository contract remain as they are.

The earlier implementation was opened on `feature/1283-notice-placement` while the dispatcher tested `feature/1283`. This plan is committed on the assigned branch before the implementation is brought over. No other in-flight feature branch changes the planned files.

## Design

- Replace `ConnectionBanner` with `ConnectionStatusIndicator(state: ConnectionState, modifier: Modifier)` for Connecting and Reconnecting in `ThreadStatusArea`. Connection state outranks turn readings while the link is unavailable; Offline emits no composer reading. Preserve task-count pill behavior beside the reading.
- Extend `ThreadTopOverlay` with the current connection state and Retry callback. Offline shows the existing error `NoticePill`; `showRePair` has priority because retry cannot repair rejected pairing. Preserve the usage-limit pill and its dismissal behavior.
- Keep `ThreadItem.Banner` and `ThreadItem.ModelRefusal` data intact. `BannerNoticeRow` draws one full-gutter `Text` with separate client-owned warning and attribution spans, sanitized agent text, and the existing truncation mark. `ModelRefusalRow` draws bare text and puts its conditional toggle on the whole gutter-width row. Its title and attributed explanation retain their existing sanitization and separate spans.
- Add localized copy in `strings.xml`; retain M3 typography and color roles. Remove references to the deleted connection banner in `ThreadHistoryRows` and match status reading padding to its siblings.

## State and concurrency model

No new flow or job is introduced. `ThreadScreen` consumes its existing `connectionState` and uses its existing `onRetry` callback, which reaches `ThreadViewModel.retry` and the lifecycle-checked connection driver. `ModelRefusalRow` continues to save only its local expanded Boolean under the list row key. Leaving the screen cancels the existing ViewModel and composition work as before.

## Error handling

Offline Retry uses the existing callback and network error handling. Pairing rejection remains a distinct Re-pair action. Agent-authored notice text is stripped at the render boundary and displayed only as plain Compose `Text`; it is not logged, linked, parsed as markup, or persisted by these components. Empty refusal explanations have no toggle.

## Testing strategy

- Update the shared Robolectric connection screen tests to prove composer placement for Connecting/Reconnecting, overlay placement and callback for Offline, recovery, and Re-pair priority.
- Update shared notice and thread attribution tests to prove warning prefix, inert plain text, collapsed and expanded refusal copy, and the whole-row tap target. Run affected existing overlay tests and focused screen tests on the changed branch.
- Run `testDebugUnitTest` for affected classes, `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin` for any touched device tests, and forced `spotlessCheck`. The dispatcher owns the full gate and real-Claude execution.
- A focused rung-3 follow-up will cover live Offline Retry and refusal interaction where a real model can produce that event; retain `needs-real-claude` for dispatcher acceptance. No deterministic twin is available from the current scripted daemon because it has no refusal emitter.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/thread-screen.md` and its status/overlay sections for Connecting and Reconnecting in the composer row and Offline Retry versus Re-pair priority; `docs/knowledge/features/banner-notice-row.md` for bare session notices, the client-owned warning prefix, and attributed plain text; `docs/knowledge/features/model-refusal-row.md` for collapsed and expanded copy, gutter wrapping, and the whole-row details target. Carry the six Figma references above into the owning feature guidance.

## Open questions

- Resolved in the revision below.

## Revisions

2026-09-29, implementation check: `setHostLink` in the existing live harness intentionally closes the supervisor to `Idle`, whose derived connection reading is hidden. It cannot hold the `Offline` failure state needed to exercise the Retry pill. Keep that live proof in a focused follow-up. The existing device-only `ModelRefusalRowTest` now adds pointer and wrapping assertions because the full-width tap target is a device interaction; run its affected class on the managed API 33 emulator. The shared notice wrapping test uses Robolectric native graphics for reliable line measurement.
