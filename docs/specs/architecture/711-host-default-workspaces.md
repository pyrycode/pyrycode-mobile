# Host-specific default workspaces (#711)

## Context and sizing

The unqualified workspace path can be reused for the wrong server. This slice adds
the preference contract; #712 owns startup migration and creation consumers, and
#713/#714 own Settings. There is one deliverable: durable host-isolated defaults
with a one-time compatibility transition. No domain, pairing or wire change.

Estimate: roughly 450 written lines (110 production, 270 tests/helpers, 70 plan),
one production file, no new exported types, no required consumer updates, three
acceptance criteria, and two new reject/error paths (already migrated, IO failure).
This remains S against the refiner's ~360-line estimate. The #231 analogue added
39 preference, 77 test and 107 plan lines; this migration needs more state proofs.
Fetched origin and checked all 17 remote feature branches: no overlap in the
planned production/test files. Rechecked these six boundaries against this plan.

## Files read

- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` — `AppPreferences`, `defaultWorkspace`, `setDefaultWorkspace`: existing DataStore and compatibility surface.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt` — `pushToken_survivesProcessDeath`: cancel and join a store before reopening its actual file.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` — `RelayLog.d`, `RelayLog.w`, `RelayLog.sink`: debug-gated content-free logging and JVM test seam.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` — `defaultWorkspace`, `onSelectDefaultWorkspace`: legacy reactive read/write must keep compiling.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` — `createHostDiscussion`, `onEvent`: legacy reads stay until #712.
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — existing DataStore, JUnit and coroutine-test dependencies suffice.
- `docs/knowledge/INDEX.md`, `CLAUDE.md` — topic map and repository conventions.
- `docs/knowledge/features/app-preferences.md` — default workspace description and Adding a preference: preserve scratch sentinel and single shared store.
- `docs/knowledge/features/development-verification.md` — JVM logging and source checks: capture/restore the log sink, and account for incomplete graph caller results.

CodeGraph context located the preference surface; callees/callers returned no
useful results for `setDefaultWorkspace`, so source reads verified the consumers.

## Design source

N/A — preference storage only; no UI is changed or migration invoked in this ticket.

## Design

Add `defaultWorkspace(serverId: String): Flow<String>`,
`setDefaultWorkspace(serverId: String, cwd: String): Result<Unit>`,
`removeDefaultWorkspace(serverId: String): Result<Unit>` and
`migrateDefaultWorkspace(initialServerIds: Set<String>): Result<Unit>` (mutations suspend).
The host key uses a fixed namespace plus the exact server id, without trimming,
case folding, labels or relay URLs. Missing host keys always resolve to scratch.

A completion boolean and optional legacy-owner id persist alongside the host keys.
The first migration transaction chooses the snapshot's single id, if any, even
without a legacy path. It copies the old path only when that owner's key is absent,
records completion/ownership and removes the unqualified key in the same edit.
Every subsequent migration call leaves those decisions and host values untouched.

The existing property and one-argument setter keep their signatures. Before the
marker they use the old key; after it they resolve the owner key in the same
DataStore snapshot/transaction. No owner means scratch reads and no-op writes.
Removal never removes ownership or allows the old path to return. All unrelated
preferences, constructor and consumers stay unchanged; no new ViewModel/state UI.

## State, concurrency and errors

DataStore serializes edits, including concurrent migrations and writes; each
migration publishes one atomic snapshot. Flows derive directly from DataStore;
there is no extra cache, scope, job or dispatcher. Callers own collection and
cancellation. The migration caller supplies its initial saved-host identity set.
New mutations return IO failures as `Result.failure`; cancellation propagates.
Existing legacy APIs and read flows retain their error behavior. New operations
emit static lifecycle/error codes through `RelayLog`; no id, path or exception
message is logged. Existing consumers gain no new error/UI handling in this slice.

## Testing strategy

Add `HostWorkspacePreferencesTest` using real temporary DataStores and `runTest`:
- Exact-id isolation, reactive updates/removal and scratch fallback before/after migration; all app-wide settings survive mutation and reopening.
- Present/absent legacy path crossed with absent/custom/scratch owner values; owner/transfer survive reopening, repeat calls and later hosts.
- Zero/multiple initial hosts remain permanently ownerless; legacy writes are no-ops while explicit host access works.
- Legacy reads/writes alias the current owner, including removals; collectors see the transition atomically.
- Concurrent calls retain one decision; an injected failed transaction commits neither transfer nor decision and can be retried; cancellation is not swallowed.

Run RED first, then both preference test classes, Spotless, lint and assembleDebug.
No Compose or live-Claude scenario: this is an unconsumed data-layer API slice.
The dispatcher owns full regression gates; integration tickets own live flow proof.

## Documentation handoff

Pending for the documentation stage: The documentation stage updates
`docs/knowledge/features/app-preferences.md`, under the default workspace
description, with identity keys, scratch fallback, one-time migration and
transitional legacy behaviour.

## Open questions

None.
