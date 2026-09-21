# Host-scoped channel list state and creation

## Context

Ticket #705 consumes the shared source from #704 without migrating the screen (#641)
or route consumer (#636). One additive ViewModel contract lets those consumers keep
host-local conversations distinct. No wire or domain serialization changes, new
dependencies, or ADR are needed. Protocol authority remains the sibling
`pyrycode/docs/protocol-mobile.md`, sections Identifiers and Application message types.

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `ChannelListViewModel`, `ChannelListNavigation` — preserve flat projection and navigation.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSnapshot`, `repositoryFor` — ordered cached rows versus live availability.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `appModule`, `hostConversationModule` — shared source and fake singleton selection.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `PyryNavHost` — existing exhaustive bare-id navigation consumer.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` → `launchGuardedRepoCall` — typed quiet failures and cancellation propagation.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `defaultWorkspace` — preserve the scratch fallback verbatim.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` → `RelayLog` — content-free debug logging and JVM sink seam.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt` → `makeVm` — existing compatibility proof.
- `app/src/test/java/de/pyryco/mobile/di/HostConversationSourceTest.kt` → `HostConversationSourceTest` — source lifecycle and controlled host fixtures.
- `app/src/test/java/de/pyryco/mobile/di/ConversationRepositoryBindingTest.kt` → `ConversationRepositoryBindingTest` — isolated Koin selector tests.
- `docs/knowledge/features/channel-list-viewmodel.md` → State projection, Per-row observeLastMessage, One-shot navigation, onEvent reducer — preview fan-out, order, guard and scratch semantics.
- `docs/knowledge/features/dependency-injection.md` → Host identity and snapshots, Exact-host repository access — never infer availability from rows or indicators.
- `docs/knowledge/features/development-verification.md` → Establish the change surface, JVM logging — index misses consumers; use fresh scoped JVM evidence.

## Design source

N/A — additive ViewModel/DI contract only; screen layout and event producers remain #641.

## Design

Require the shared `HostConversationSource` as the third constructor dependency.
Keep `state`, `onEvent(ChannelListEvent)` and `navigationEvents` on the compatibility
repository, unchanged. Do not flatten hosts or send host actions into bare-id navigation.

Add three presentation types in the ViewModel file: `HostChannelListState` (ordered
host entries and nullable `workspacePickerServerId`), `HostChannelListEntry` (the
unchanged `HostConversationSnapshot`, recent-three chats, full count, and host-local
preview map), and `HostConversationTarget` (exact server/conversation pair).
Expose `hostState` and a separate buffered `hostNavigationEvents` flow of targets.
Nesting rows and their preview maps under their source snapshot preserves the pair.

Project source snapshots without sorting or copying domain records. Resolve each
host's preview repository through `repositoryFor`; seed each preview with absence
so a silent or missing message never blocks rows. A missing repository gives an
empty preview map while preserving cached rows. Cancel obsolete preview streams
when source snapshots change; a preview failure affects only that preview.

Expose explicit member actions: `onHostRowTapped(target)`,
`createHostDiscussion(serverId)`, `openHostWorkspacePicker(serverId)`,
`pickHostWorkspace(workspace)`, and `dismissHostWorkspacePicker()`.
Default creation captures its argument before reading preferences, then resolves
that exact live repository immediately before sending. Picker opening stores the
host; completion captures and synchronously clears it before launching, bypasses
preferences, and uses live lookup. Completion without a target and dismissal send
nothing. Successful creation sends one target on the host-only navigation channel.

## State + concurrency model

`hostState` uses `stateIn(viewModelScope, WhileSubscribed(5000), empty state)`;
pending picker state is a VM-owned `MutableStateFlow`. All actions and projections
run on the existing Main-bound scope; repositories/DataStore own I/O dispatch.
Clearing the VM cancels actions and preview collectors. Background connection close
leaves source-owned rows intact and live lookup unavailable. No new cache owner.

## Error handling

Unavailable targets are quiet no-ops with a static debug rejection event.
Creation retains `launchGuardedRepoCall`'s three typed catches; log static action
failure events without exception text and rethrow into that guard. Cancellation
propagates and never produces navigation. Preview exceptions produce absence and
a static debug event; cancellation is rethrown. Never log ids, paths or message text.

## Testing strategy

- JVM projection tests: colliding host-local ids/paths, exact metadata/status/order, full and recent chat slices, isolated previews, silent host/message, cached rows and empty hosts.
- Action tests: exact row targets, preference suspension plus compatibility selection change and repository replacement, picker capture/override/clear/dismiss, unavailable lookup, all guarded failures, cancellation and exactly-once host navigation without legacy emissions.
- DI tests resolve the actual `appModule` definition with JVM dependencies; fake selector exposes only demo and creation uses its singleton. Retain existing compatibility tests and add selected-host state/action isolation proof.
- RED new focused tests before production changes; then scoped `testDebugUnitTest`, `spotlessApply`, `lint`, and `assembleDebug`. No androidTest change or new live control; #676/#673 own the downstream live scenarios. Dispatcher owns full regression gates.

## Sizing and overlap

Estimate approximately 620 written lines: 175 production, 355 tests/helpers, 90 plan.
Two production files, three exported types, two existing constructor consumers,
three acceptance criteria, and at most seven distinct rejection/error branches.
CodeGraph context was useful but impact/callers missed external constructors;
direct search confirmed only `appModule` and the existing JVM helper. The #161
analogue totals 217 inserted/28 deleted scoped lines. All six limits hold.
Fetched origin and checked all 17 feature branches: no touched-file overlaps.

## Open questions

None. Preview absence is allowed; cached conversation rows remain source-owned.

## Documentation handoff

Pending for the documentation stage: updates `docs/knowledge/features/channel-list-viewmodel.md`
under State projection, One-shot navigation and Wiring to describe host-qualified
state, explicit creation targets and the temporary flat-screen compatibility boundary.

## Security review

**Verdict:** PASS

- [Trust boundaries] Host snapshots supply exact identity; `repositoryFor` alone authorizes current availability. Separate navigation prevents host erasure. Colliding-id tests exercise the boundary.
- [Tokens] No credentials enter the new presentation types or logs; existing stores and token lifecycle are unchanged.
- [File/storage] No new persistence or local path use. Workspace values pass verbatim to the captured host's repository, never to local files.
- [Android surface] No intents, providers, WebViews, push changes or exported components are added.
- [Cryptography] No primitive, handshake, key or nonce changes; existing Noise transport remains authoritative.
- [Network/I/O] No new wire messages or clients. Existing repository transport limits and timeouts remain; lookup after preference suspension avoids stale routing.
- [Errors/logging] Only static debug lifecycle/failure codes; no exception messages, host names, paths or decrypted preview content. UI error handling is unchanged.
- [Concurrency] VM scope owns actions, captures targets before suspension, clears picker before sending, cancels superseded preview flows and propagates cancellation. Send-time disconnect still flows through the existing guard.
- [Threat alignment] Delayed/absent host replies and previews cannot block other rows; host-local data never shares a cross-host id map. Existing crypto/storage layers retain relay and disk defenses. New rendering and route handling remain explicitly out of scope under #641/#636 (live proof #676/#673); no new daemon-text rendering occurs here.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21
