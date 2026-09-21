# Host-owned discussion defaults (#712)

## Files read

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `MainActivity.onCreate`, `PyryNavHost`: startup pending surface and production host-action adapter.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `createHostDiscussion`, `sendHostDiscussion`, `pickHostWorkspace`: captured identity, fresh repository lookup and qualified navigation.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `migrateDefaultWorkspace`, `defaultWorkspace`: atomic one-time ownership and exact-id reads.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt` → `PairedServerCollectionStore.list`: full ordered saved-host snapshot, empty on unreadable storage.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `appModule`, `conversationRepositoryModule`: collection binding and production ViewModel/demo wiring already exist.
- `app/src/main/java/de/pyryco/mobile/di/ObservablePairedServerStore.kt` → `ObservablePairedServerStore`: collection delegation and pairing revision publication.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → default-creation suspension, picker, unavailable-host and production demo tests: extend these established seams.
- `app/src/test/java/de/pyryco/mobile/data/preferences/HostWorkspacePreferencesTest.kt` → migration/persistence matrix: reuse storage proof rather than duplicate it.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenNavigationTest.kt` → production navigation tests bypass startup; startup proof must launch `MainActivity` itself.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` → ordinary instrumentation uses demo bindings and supports isolated preference/store overrides before Activity launch.
- `docs/knowledge/features/navigation.md` § How it works; `channel-list-viewmodel.md` § One-shot navigation; `app-preferences.md` § What it does: initial destination is fixed, explicit picks bypass defaults, legacy ownership cannot be retargeted.
- `docs/knowledge/features/development-verification.md` and `docs/e2e-interactive-stream.md`: focused device execution and dispatcher-owned live acceptance.
- Upstream `pyrycode/docs/protocol-mobile.md` § Application message types (`create_conversation`) and § Security model: existing wire contract and authenticated host boundary.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

The context and screenshot show a dark vertical channel list with toolbar icons, host/workspace hierarchy, compact rows, status dots and blue selection backgrounds. It uses `Schemes/Surface`, `On Surface`, `On Surface Variant`, `Primary Container` and M3 small body/title and large label typography. This ticket preserves the supplied mobile theme and existing layout, including the neutral pending `Surface`; it only changes creation ownership, as the ticket requests.

## Context and size

#711 supplies storage semantics, but startup still reads only the latest host and the production create command reads the transitional global default. A second pairing can otherwise obscure the initial owner.

One deliverable: discussion creation uses its host's default, including upgrade startup. Forecast: approximately 410 written lines including tests and this plan, two production files, zero new exported types/composables, zero consumer signature updates, three acceptance criteria and one new failure branch. The #240 analogue added 212 lines across plan and implementation; the extra work here is startup integration proof. All six size limits hold. No ADR or dependency change is needed. The refreshed remote-branch overlap check found no overlap across 17 feature branches.

## Design

In `MainActivity.onCreate`, inject `PairedServerCollectionStore` for startup. The existing `produceState<Boolean?>` loads `list()` once, collects exact `entry.record.serverId` values into a set, then awaits `AppPreferences.migrateDefaultWorkspace`. Publish `entries.isNotEmpty()` only after a successful result. Loading, migration suspension and returned failure retain null, so neither pairing nor discussion creation is reachable. Keep the existing pairing-route `PairedServerStore` dependency intact.

Change only the preference read in `createHostDiscussion` to `defaultWorkspace(serverId).first()`. Preserve exact identity through `sendHostDiscussion`, fresh host repository resolution, success navigation and the explicit picker path. The explicit `demo` identity naturally uses its own key or scratch, never the legacy owner's key. Leave the legacy reducer and Settings migration to their own tickets.

## State + concurrency model

Startup stays a composition-owned `produceState` coroutine on Main; the store owns its IO dispatch. Key it by both store and preferences. Composition disposal cancels loading/migration; cancellation is not converted to success. Recomposition does not repeat the work. Activity restart rechecks the durable migration decision; #711's transaction prevents another transfer or owner change.

Creation remains in `viewModelScope` through `launchGuardedRepoCall`. Its cold preference flow is read once. Compatibility selection cannot mutate the captured argument; host availability/repository replacement is resolved after that suspension. Background connection closure continues to make the exact-host lookup unavailable, with no fallback. No new job, hot flow, mutex or ViewModel state is introduced.

## Error handling

The collection contract returns an empty snapshot for missing/unreadable storage. Migration returns `Result<Unit>` for IO failure: retain the pending surface and emit a static blocked code, without opening navigation. Restart can retry; this ticket adds no retry UI. Cancellation propagates. Existing guarded creation failures and unavailable-host rejection remain unchanged. Debug-only `RelayLog` records startup started/ready/blocked; never paths, server ids, credentials or exception messages.

## Testing strategy

- First add regressions and execute them against the old implementation. Instrumentation launches actual `MainActivity` with test-bound saved collection and real `AppPreferences` over a gated in-memory DataStore; no copied startup gate.
- Delay collection loading and then migration independently; neither pairing nor creation controls may appear until migration commits. Assert the sole exact initial id receives the legacy default before the channel list becomes reachable.
- Return an IO migration failure; assert no navigation, no persisted decision, and no automatic retry. Restart after recovery completes the migration.
- Launch with zero and multiple initial ids, then restart with one later host; the legacy path remains unowned. Restart a single-owner case with another host and assert ownership remains unchanged. #711 already proves real on-disk reopening and the detailed storage matrix.
- Extend the existing creation suspension test with distinct case-sensitive host defaults, both host destinations and scratch fallback; retain selection-switch/replacement and existing explicit-picker/unavailable-host coverage. Resolve the ViewModel through production Koin bindings for creation proof.
- Extend production-bound demo coverage with a paired host owning a legacy default, asserting demo scratch and explicit demo default.
- Run scoped `HostChannelListViewModelTest`, `HostWorkspacePreferencesTest` and existing `ChannelListViewModelTest`, focused managed-device `StartupWorkspaceMigrationTest`, Android test compilation, Spotless, lint and debug assembly. Inspect fresh executed counts and XML.
- #676 owns the two-live-host rung-3 scenario. No new harness scenario here; dispatcher retains its current full UI/scripted/live regression gates under `needs-real-claude`. Those runs are pending at builder handoff.

## Open questions

None. Failure intentionally keeps the existing pending surface; no recovery affordance is requested.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/navigation.md` (How it works) and `docs/knowledge/features/channel-list-viewmodel.md` with startup migration ordering, host-specific default resolution and demo behaviour. Builder does not edit these shared documents.

## Security review

**Verdict:** PASS

- **Trust boundaries:** `MainActivity.onCreate` takes identities only from `PairedServerCollectionStore.list`, preserving exact case/spacing. The navigation gate prevents pairing from changing the initial ownership set while migration is pending. `createHostDiscussion` binds the preference and repository to that same explicit action id.
- **Tokens/secrets:** no token generation or persistence changes; paired credentials retain the existing Keystore-backed store. Tests use synthetic records and never live credentials.
- **File/storage:** workspace strings remain opaque preference values and existing daemon arguments, never local filenames. #711's single DataStore transaction commits transfer and decision atomically; no new backup or filesystem surface.
- **Android attack surface:** no new exported component, intent handling, deep link, provider, pending intent or WebView. `MainActivity` still gates all graph composition.
- **Cryptography:** no changes to Noise, keys, counters, comparison or randomness; existing authenticated host bundles remain authoritative.
- **Network/IO:** no wire changes. `sendHostDiscussion` uses `repositoryFor(serverId)` at send time and rejects unavailable targets; it cannot borrow the selected host's connection. Existing frame, transport, timeout and reconnect policies apply.
- **Logs/errors:** only static startup event/outcome codes; no sensitive identifiers, path values or exception details. Migration failure leaves UI closed. `RelayLog` already gates release output.
- **Concurrency:** startup awaits the snapshot and successful transaction before exposing actions. Preference suspension retains the action argument; cancellation and background-close behavior remain inherited from composition/ViewModel and repository ownership.
- **Threat model:** relay compromise, credential-at-rest theft, hostile frame parsing and screenshot/accessibility risks retain their existing protocol/storage/UI boundaries; this change introduces no new exposure to those surfaces. Cross-host default leakage and legacy retargeting are addressed here. Live two-host proof is explicitly owned by #676.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21
