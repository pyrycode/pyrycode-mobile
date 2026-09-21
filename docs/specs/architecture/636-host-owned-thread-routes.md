# Host-owned thread routes (#636)

## Files read

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — `PyryNavHost`, `Routes`: selected-host navigation currently drops host identity.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — `appModule`, `hostConversationModule`: destination construction and demo/relay selection.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` — `connectionFor`, `hostConnections`: exact retained owners, removal and selection.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionFactory.kt` — `RelayConnectionBundle`: reconnect-surviving coordinator and supervisor.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` — `repositoryFor`: immediate availability and demo identity.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` — scope-free reconnecting facade and unchanged error propagation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` — `hostNavigationEvents`, `createHostDiscussion`, `pickHostWorkspace`: captured asynchronous targets.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModel.kt` — `onHostRowTapped`, `requestHostPromotion`: exact targets and retained prompts.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` — constructor seams, `onModalOption`, `retry`: existing action behavior.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenViewModel.kt` — `onEvent`: per-destination snapshots, unchanged Retry.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt` — `Fixture`, `PeerTransport`: two-host real Noise test peers.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` — `tappedRelayRepositoryModule`: preserve parser-gap observation.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenNavigationTest.kt` — replace copied graph coverage with production routes.
- `docs/knowledge/features/dependency-injection.md` (Host identity and snapshots, Exact-host repository access, Demo binding), `navigation.md` (How it works), `thread-screen.md` (Wiring), `development-verification.md` (Compose evidence): ownership and proof requirements.
- Sibling `pyrycode/docs/protocol-mobile.md` (Identifiers, Application message types): authoritative unchanged wire contract.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot: a vertical dark chat surface with back/title/overflow header, alternating message cards, status strip and bottom composer. It uses Schemes/Surface, Primary and container roles with title-large/body-medium/body-small typography. This routing change reuses the existing M3 thread and literal surfaces; the header/composer adaptation remains #643.

## Context

One deliverable: destination ownership remains fixed when compatibility selection changes. Equal conversation and modal ids are host-local; never encode host identity into repository ids or wire payloads. #641 owns the list redesign and #637 the other destinations.

Size check: two production files, approximately 180 production + 480 test/helper + 85 plan lines (745 total), one internal factory type, seven route/binding consumer edits, four acceptance criteria, three routing rejection cases. No constructor signature cascade. Codegraph omitted private navigation/module callers; direct source inspection located them. Remote feature-branch overlap check found none.

## Design

- Add `serverId` to both route templates and URI-encode each argument independently. Shared internal builders accept `HostConversationTarget`; `SavedStateHandle` retains both fields. Keep ViewModels scoped inside their destination entries.
- Consume only `hostNavigationEvents` from both lists. Flat row taps capture the current relay selection or explicit demo id and call host-aware row commands. Flat create/picker and promotion adapters invoke the existing host commands, including their captured prompt/picker owner.
- Keep the flat list visuals; project host picker/promotion visibility into their existing UI state. No component changes.
- Add an internal `ThreadDestinationFactory` in `AppModule.kt`, selected alongside `HostConversationSource`. It resolves exact bundles, creates a `StableConversationRepository` from that bundle's coordinator stream, and supplies that same bundle's live events, modal, controls and supervisor to `ThreadViewModel`. Literal bindings use the same exact-host repository resolution.
- Demo resolves the existing fake singleton, connected fake connection state and inert default event/modal/control seams even with saved relay hosts. Unknown ids never resolve selection.
- Guard both destinations before resolving their ViewModels. Observe registry membership; distinguish saved hosts still initializing from absent hosts using the pairing collection. Unknown/removed hosts navigate to the list, clearing invalid destination entries. A transient disconnected bundle remains a valid destination.
- Provide an optional identity-default repository decorator in `hostConversationModule`; relay instrumentation supplies `TappingConversationRepository` so host-owned message reads still feed parser-gap evidence.

## State + concurrency model

No new coroutine owner. Destination `LaunchedEffect` jobs collect navigation and membership, cancelled when composition exits. Existing ViewModel scopes own state and actions. The factory holds no jobs; stable repository cold flows switch on the retained coordinator's repository stream. Background close retains the bundle, reconnect replaces only its concrete repository. Selection is consulted only when capturing a flat entry target.

## Error handling

Unknown/removed host: content-free route-rejected log and return to list. A saved owner not yet initialized waits rather than prematurely rejecting restoration. Disconnected/handshaking hosts retain the stable facade/coordinator's existing unavailable exceptions and ViewModel error handling. No fallback host. Existing timeout, cancellation, generic snapshot errors and modal error behavior remain unchanged.

## Testing strategy

- RED first: production Koin thread/literal resolutions with colliding conversation/modal ids must preserve A ownership while B is selected; verify frames and content independently using existing Noise fixtures.
- Prove exact send, stop, reset, modal answer/cancel, queue drop, literal Retry and representative repository actions; compare other-host outbound frames. Prove reconnect, independent B usability and demo isolation through actual bindings.
- Compose: use production `PyryNavHost` and route builders, both host event streams, separate destination ViewModels, back/reopen, saved-state restoration, thread overflow to literal, Retry and invalid-host return.
- Run scoped JVM class, focused managed-device navigation class with fresh XML counts, lint, assembleDebug, androidTest compilation and Spotless. Preserve existing live ping/reset tests; dispatcher owns full regression and `python3 scripts/android-test-gate.py live`. #673 owns the future two-host navigation/reconnect/reply scenario, which the current live gate cannot prove.

## Open questions

None. No wire or ViewModel contract changes are needed.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/navigation.md` (How it works), `docs/knowledge/features/thread-screen.md` (Wiring) and `docs/knowledge/features/dependency-injection.md`: host-qualified destinations, ownership across selection/reconnect, demo behavior and temporary flat-list compatibility.

## Security review

**Verdict:** PASS

- Trust boundaries: resolve route `serverId` with exact `connectionFor`; never use display names or selection as fallback. Colliding modal ids require the same captured coordinator for display and answers.
- Tokens/credentials: no new access, storage or logging; factory holds existing owners, not pairing secrets.
- Storage: save only route identifiers in navigation state; literal snapshot text stays in its existing ViewModel, never in saved state or paths.
- Android surface: no exported components, intents or deep links added. Encode path arguments independently so reserved characters cannot change route structure.
- Cryptography/network: existing Noise, transport, frame bounds and retry policy unchanged; no new decoder or text rendering path.
- Errors/logs: static lifecycle/rejection codes only; no route values, prompts, snapshot text or secrets.
- Concurrency: immutable destination identity and retained coordinator prevent selection redirection; cold repository flows follow reconnect. Compose guards wait for initial saved-host hydration and react to removal.
- Threat alignment: hostile relay/frame handling and Keystore wrapping remain in existing transport/storage boundaries. This change addresses UI cross-host leakage; visual redesign and two-host live evidence remain #643/#673 respectively.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

## Revisions

- 2026-09-21: `openThread` suppresses only an identical current target. Do not use `launchSingleTop` for the parameterized thread route: different host arguments can otherwise retain the same destination ViewModel. The production-graph regression sends A/A/B in one burst and checks distinct A/B owners and a single A entry.
- 2026-09-21 (PR #710 verifier rework): ownership includes `WorkspacePicker`'s descendant repository injection. Provide `LocalWorkspacePickerRepository` around the production thread and flat-list screens, resolved through the factory's reconnecting exact-host facade using the route or captured picker owner. The picker keeps its existing settings/preview fallback, error UI and cancellation; no screen signature cascade or visual change. Production-route device tests use two Noise peers with distinct recents and record folder creation/change-workspace frames across a selection change. Read `WorkspacePicker`, `WorkspacePickerInternal` and `RelayConnectionRegistry.retry` in addition to the original reading list; Codegraph omitted their callers, so source inspection checked the three picker consumers.
- 2026-09-21 (PR #710 verifier rework): exact-host Retry moves into `RelayConnectionRegistry.retryHost(serverId, expectedBundle)`. Hold the registry lifecycle/removal monitor through foreground/disposed and bundle-identity validation and the supervisor's nonblocking retry, following the existing compatibility Retry pattern. Production DI regressions schedule Retry on both sides of removal/replacement/background close and check that retired or background owners cannot dial and B stays untouched. This repair extends the production scope to four files; it addresses the existing ownership deliverable.
- Security review amendment — PASS after both repairs: a nested composable's Koin lookup is a separate cross-host trust boundary, so the route/picker owner must cover reads and folder creation as well as the ViewModel callback. Retry's check-and-use must be serialized with removal/replacement and background close; a prior identity check alone cannot authorize a later dial. Existing protocol, credentials, storage, rendering, errors and content-free logging remain unchanged. The documentation handoff also includes these picker and lifecycle ownership boundaries.
