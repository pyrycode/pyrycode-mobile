# 1198 — Memory search reading in thread state

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/SessionSettingsPayloads.kt` → `SessionSettingsPayloadDto`, `toSessionSettings`: the single reply decode boundary.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `SessionSettings`, `observeSessionSettings`: the portable reading contract.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionSettingsCommands.kt` → `observeSessionSettings`, `sessionSettingsRead`: refresh cancellation and null on a replacement read.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `observeSessionSettings`: host replacement re-subscribes and emits null.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig`: the surface carrying the current reading.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `runConfig`, `forLiveSession`: the sole settings subscription and session mismatch guard.
- `app/src/test/java/de/pyryco/mobile/data/network/SessionSettingsPayloadsTest.kt` → fixture decoding pattern and original-field assertions.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelAppliedEffortTest.kt` → flow-based session replacement test pattern.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer-application-payloads.md` § session settings → optional payload guidance.
- `docs/knowledge/features/conversation-repository.md` and `docs/knowledge/features/thread-screen.md` → owning knowledge topics; documentation stage updates them.
- `docs/knowledge/features/development-verification.md` → fixture and focused verification conventions.
- `../../pyrycode/docs/protocol-mobile.md` § `session_settings` → `memory_search`: wire source of truth.
- `../../pyrycode/internal/protocol/testdata/session_settings*.json` → five shared daemon fixtures.

## Context

The daemon now reports search access for the selected conversation's agent and workspace. Mobile needs to retain that report without interpreting it as knowledge capture or inferring absence from a missing report. The dependent UI can later read the current thread's state. `feature/1193` also touches `ThreadUiState` and `ThreadViewModel`, but changes model selection; this ticket adds a local field and mapping without depending on its new behavior.

## Design

- Add portable `MemorySearchAvailability` and `MemorySearchProvider` / `MemorySearchReport` domain values beside `SessionSettings`. `Unknown` is the default report, with no providers. Only an explicit decoded aggregate `Absent` means confirmed absence. Keep installed and enabled independently from availability.
- Add an optional raw `memory_search` member to `SessionSettingsPayloadDto`, then decode it in `toSessionSettings` through two internal serializable report/provider DTOs. A missing, malformed, incomplete, null, or future-valued report maps to `Unknown` without failing the seven original settings or their other optional fields. A valid `unknown` report can retain an empty provider list. No capture claim enters these types.
- Carry the report in `SessionSettings` and `ThreadRunConfig` through `runConfig`. A null settings reading yields `Unknown`. Extend `forLiveSession` to replace the report with `Unknown` while a new session's settings are pending. Keep the one existing settings subscription, revision refresh, and host/reconnect null emission.
- Do not add a UI rendering path. A later UI ticket must bound and render daemon display names as inert text; no report content is logged or used as a URL, path, or key.

## State and concurrency model

`SessionSettingsCommands.observeSessionSettings` stays cold; `flatMapLatest` cancels superseded reads and its null emission clears state on subscription/host replacement. `ThreadViewModel` collects that one stream within `viewModelScope`; conversation selection creates its own ViewModel and `forLiveSession` masks a mismatched session synchronously with the conversation row. No new job, flow, dispatcher, or persistence is introduced.

## Error handling

The optional report alone degrades to `Unknown` at `toSessionSettings`; malformed required settings still fail the entire read as before. The decoder does not include raw values in authored errors or logs. The existing repository converts failed reads to null, which also maps to `Unknown` in the thread.

## Testing strategy

- Add JVM decoder tests using verbatim copies of the five daemon contract fixtures under `app/src/test/resources`; assert each aggregate state, provider fields, and original settings. Mutate a fixture for missing, wrong-typed, null, and future status values and verify the other settings survive with an unknown report.
- Add a focused `ThreadViewModel` JVM test that feeds decoded fixture readings through the repository flow, changes conversations, clears on a pending replacement read, masks a session mismatch, and accepts the replacement session's report. Existing effort/settings lifecycle tests cover the same shared subscription and reconnect null path.
- Run these focused classes, lint, and assembleDebug. No screen or device test is needed because this ticket adds no visible UI or operator action; no real-Claude scenario is needed.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/mobile-protocol-v2-wire-layer-application-payloads.md` § session settings, `docs/knowledge/features/conversation-repository.md` § session settings reading, and `docs/knowledge/features/thread-screen-how-it-works-state.md` § run configuration. Record the optional report, unknown versus explicit absent, and conversation/host/session lifecycle without duplicating `../../pyrycode/docs/protocol-mobile.md` § `session_settings` → `memory_search`.

## Open questions

- None. The upstream contract and existing null/mismatch lifecycle define the behavior.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] `toSessionSettings` is the sole untrusted JSON boundary. The optional raw member is validated before creating typed domain values; malformed or future statuses yield `Unknown`. No report text is rendered by this ticket. A later UI consumer must bound and render display names as inert text.
- [Tokens, storage, Android, cryptography] No token, file, component, intent, key, nonce, or persistence path changes. The existing Noise transport still authenticates the daemon frame.
- [Network and I/O] No socket, timeout, URL, or request behavior changes. The existing bounded frame path and settings subscription remain in force.
- [Errors and logs] Raw report values and decoder exceptions are not logged. `SessionSettingsCommands.sessionSettingsRead` already drops failed-read exceptions; the optional report decoder also does not expose its exception text.
- [Concurrency] No new job or shared mutable state. The existing `flatMapLatest` cancellation, null reset, and `forLiveSession` mismatch mask prevent a superseded report reaching the current thread.
- [Threat model] An on-path relay cannot read the Noise payload; a delayed reply is handled by the existing request cancellation. Hostile daemon report structure degrades to unknown. UI-side display and length checks belong to the dependent UI ticket because no display exists here.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-28
