# #1199 Memory search presentation

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `MemorySearchReport`, `MemorySearchProvider` — session report and provider state contract.
- `app/src/main/java/de/pyryco/mobile/data/network/SessionSettingsPayloads.kt` → `readMemorySearch` — malformed or omitted reports become unknown.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig` — current-session reading held by the screen state.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`, `toChannelInfoUiModel` — one state source feeding three presentation surfaces.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoSheet.kt` → `ChannelInfoUiModel`, `MemoryRow` — Figma memory row and install control.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt` → `SessionBoundaryDelimiterContent` — reason/time rule and reset explanation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt` → `ThreadTopAppBar` — overflow pass-through.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt` → `ThreadOverflowMenu` — channel install item.
- `docs/knowledge/features/channel-info-sheet.md`, `docs/knowledge/features/session-boundary-delimiter.md`, `docs/knowledge/features/thread-overflow-menu.md` — existing layout and test conventions.
- `docs/knowledge/features/development-verification.md` — shared Compose test placement.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-48 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Channel info is a Material 3 bottom sheet with a Memory section and a right-aligned value beside the install control in the absent example. Retain the sheet's label/value rhythm, typography and theme roles for provider names and status; retain the thread's centered session rule and existing M3 overflow menu, changing only state-dependent copy and controls.

## Context

The current session's `MemorySearchReport` is already in `ThreadUiState.runConfig` after #1198. The three UI surfaces currently infer absence from missing display data or always offer installation. Search access describes retrieval from stored knowledge; it does not capture conversations or restore the agent's old context. Branch #1193 also edits `ThreadScreen`, in a separate model-selection block; keep changes to the screen's memory call sites and mapper local.

## Design

- Replace `ChannelInfoUiModel.memoryPlugins` with the current `MemorySearchReport`, passed through by `toChannelInfoUiModel`. `MemoryRow` shows each provider's daemon display name as plain bounded text and a status reflecting installed, enabled and effective availability. Only an installed, enabled, available provider gets “Memory search available”; disabled remains visible as “Disabled”. Explicit aggregate `Absent` shows “None” and Install. An unknown or omitted report shows “Status unknown”; other empty non-absent reports show “Memory search unavailable”. No other status shows Install.
- Pass the same report from `ThreadScreen` to `SessionBoundaryDelimiter` and through `ThreadTopAppBar` to `ThreadOverflowMenu`. Their install affordances appear only for explicit aggregate `Absent`, with the channel condition retained in overflow. The boundary always keeps its reason/time and the agent's context-reset explanation. Its absent-only copy says a plugin can search stored knowledge, without promising captured conversations or remembered messages.
- Keep `MEMORY_PLUGIN_DOCS_URL` as the destination. No repository, protocol, settings or navigation change. Passing the report as a normal composable parameter makes replacement reports and conversation changes recompose all three surfaces from current state.

## State and concurrency model

The report remains in `ThreadUiState.runConfig` and follows the existing ViewModel collection and #1198 clearing behavior. These composables are stateless; they create no job or flow. The UI never persists a report across conversations or sessions.

## Error handling

`readMemorySearch` already maps omitted or malformed wire data to `Unknown`. Presentation treats `Unknown` and `Unavailable` as non-installable. Provider names remain inert text and cannot become links or log values. Install click retains the existing URI handler behavior.

## Testing strategy

- Focused shared Compose tests for the Channel info sheet, boundary and overflow cover available, disabled, absent, unknown/omitted and status replacement. A screen-level test supplies a changed `ThreadUiState` to verify current conversation data reaches the sheet, boundary and overflow.
- Update assertions that assumed unconditional install copy. The existing JVM mapper test checks pass-through of the current report; focused `testDebugUnitTest --tests` runs the touched classes. Run lint, assembleDebug and Spotless as the builder gate.
- This changes presentation only and adds no new operator action or daemon flow, so it does not add an interactive-stream e2e scenario.

## Open questions

- None. The daemon's `displayName` is the provider name; installed/enabled/effective availability remain distinct.

## Documentation handoff

Pending documentation stage: update `CLAUDE.md` § Conversations model so the boundary install affordance is conditional on confirmed absence. Update `docs/knowledge/features/thread-screen.md`, `docs/knowledge/features/channel-info-sheet.md`, `docs/knowledge/features/thread-overflow-menu.md`, and `docs/knowledge/features/session-boundary-delimiter.md` to describe installed, disabled and unknown presentation plus search of stored knowledge versus knowledge capture.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The report crosses from the daemon through `readMemorySearch` into `MemorySearchReport`; `MemoryRow` renders only a bounded provider `displayName` as Compose `Text`. It is never a URL, attribute, filename or log value. Cap displayed characters and use one line with ellipsis to limit maliciously long names.
- [Tokens] No token is read, created, stored or rendered by these surfaces.
- [File and storage] No new file or storage operation occurs.
- [Android attack surface] No intent, exported component or WebView is added. The fixed `MEMORY_PLUGIN_DOCS_URL` stays the only opened URI.
- [Cryptography] The existing encrypted session and decoder are unchanged.
- [Network and I/O] The screen consumes the already decoded report and does not initiate I/O except the existing explicit install-link click.
- [Errors and logs] Unknown reports show a fixed local status; no provider content or payload is logged or included in errors.
- [Concurrency] Pure state-driven rendering cannot retain a previous conversation's report; no new job or cancellation path exists.
- [Threat model] Only explicit aggregate absence may invite an installation; unknown/unavailable or installed states cannot be mistaken for absence. This avoids a misleading link driven by malformed or missing daemon data.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-28
