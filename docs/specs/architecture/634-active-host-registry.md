# Active paired-host registry (#634)

## Files read

- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionFactory.kt` → `RelayConnectionFactory.create`, `RelayConnectionBundle.close`: immutable host ownership and permanent versus resumable teardown.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `appModule`, `conversationRepositoryModule`: eight compatibility composition sites and the fake selector.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt` → `PairedServerCollectionStore`, `PairedServerEntry`: exact ids, oldest-save-first ordering and metadata separation.
- `app/src/main/java/de/pyryco/mobile/data/crypto/KeystorePairedServerStore.kt` → `mutate`, `list`: committed mutations, atomic encrypted collection and graceful read failure.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt` → `connect`, `close`, `retry`: independent nonblocking loops and resumable cancellation.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `currentRepository`, `currentModal`, `connectionStatus`, `replayCursor`: host-local projections and retained cursor.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `switchToLive`: existing stable repository facade.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt` → `Fixture`, `PeerTransport`: deterministic real Noise peers and DI overrides.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt` → `severLink`, `restoreLink`: concrete aliases must resolve the retained selected host.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` → `onCreate`: pairing writes use the production store binding.
- `docs/knowledge/features/lifecycle-connection-driver.md` § Wiring and Guarantees: process edges own Android close-on-background policy.
- `docs/knowledge/features/paired-server-store.md` § Wiring & usage: saves currently have no collection notification seam.
- `docs/knowledge/features/relay-repository-coordinator.md` § Configuration and Reconnect-spanning replay cursor: backgrounding must not dispose a bundle.
- `docs/knowledge/features/dependency-injection.md`, `noise-ik-session.md` § Factory wiring: compatibility aliases and host-bound session construction.
- `docs/knowledge/features/development-verification.md`, `docs/e2e-interactive-stream.md`: focused JVM and deterministic reconnect evidence requirements.
- `../pyrycode-desktop/src/main/connectionRegistry.ts` → `createConnectionRegistry`: serialized reconciliation and latest-save selection reference (read from the canonical sibling checkout).
- `../pyrycode/docs/protocol-mobile.md` § Pairing flow, Phone → relay → binary, Interactive events: authoritative wire and daemon-wide `event_id` contract (canonical sibling checkout).
- `gradle/libs.versions.toml`: existing coroutines, Koin and JUnit suffice.

## Context and size

One deliverable: connection lifetime follows every retained pairing independently of the temporary selected-host UI. Host-aware screens/routing/settings remain #635–#637; targeted push lifetime remains #361. No new wire or visual contract and no ADR is needed.

Refiner estimate: 780 lines / three production files; #633 added 702 implementation/test/plan lines. This plan budgets about 260 production, 420 test/helper and 95 plan lines (775 total), three production files, two exported types, eight existing composition sites, four acceptance criteria and no new network rejection state machine. Codegraph's impact/caller results omitted Koin lookups; direct source search verified the eight composition sites plus unchanged test-only alias consumers. The remote feature-branch overlap check found none.

## Design

Add `ObservablePairedServerStore` in `di/`: a `PairedServerCollectionStore` decorator with a revision `StateFlow`. Delegate reads and mutations to the existing encrypted store. Advance the revision only after successful save/remove/name mutations. Both legacy and collection DI interfaces resolve this one decorator; existing store and screen constructors remain unchanged.

Add `RelayConnectionRegistry` in `di/`, implementing `RelayConnectionController` and `ConnectionStateSource`. It observes revisions (including the initial value), reads the collection serially, and reconciles an exact `serverId` map of record/bundle pairs. Unchanged records reuse their bundle regardless of metadata/order. Removed or credential-changed entries are permanently closed before replacements are created/dialled. Empty collections have no stand-in connection. Each new bundle comes only from `RelayConnectionFactory.create(record)`.

`connectionFor(serverId): RelayConnectionBundle?` returns only an exact held owner; its coordinator exposes repository, live events, modal and combined relay/pyrycode legs. Unknown/removed ids return null. A hot selected-bundle flow follows the final saved entry, matching `load()`. Compatibility repository/status/modal projections switch over that single selection; events and banner observation are cold switched flows. Modal answer/cancel, interrupt and retry resolve selection at call entry. No event or cursor is merged across hosts.

`appModule` eagerly owns the registry and disposes it on Koin close. The lifecycle controller, banner source, stable repository, Settings status and Thread event/modal/action bindings resolve it. Concrete bundle/supervisor/coordinator/session-factory aliases become factory resolutions of the currently selected retained bundle for the existing reconnect harness; resolving one without a selection refuses. They never create an extra connection and are not the stable application-consumer API.

## State + concurrency model

The registry owns a `SupervisorJob` scope with injected worker dispatcher (Default in production). One revision collector serializes suspending snapshot reads. A short synchronized, non-suspending critical section owns the map, foreground flag, selection and disposal flag. `connect()` marks foreground and starts each held supervisor; newly reconciled hosts start only if that flag remains true. `close()` clears it and closes every supervisor resumably. Rechecking under the lock after each read prevents background/disposal races from reopening sockets. Supervisor starts launch independently, so one unavailable host cannot hold the others.

Registry disposal is permanent and idempotent: clear selection/map, dispose every bundle, cancel the collector and projection scope. Ordinary background retains bundle coordinators, modal accumulators and cursors; fresh supervisor connections create fresh Noise sessions. No new ViewModel state, composition effects or UI-local jobs.

## Error handling

Persistence errors propagate unchanged from the encrypted store and do not publish successful mutations. Reads retain the existing graceful-empty contract for unreadable storage. Cancellation propagates. Network, handshake, pending-request and backoff errors remain host-local in the existing bundle. Compatibility actions with no host throw the existing not-connected invariant error; status is idle/down and repository/events/modal are empty/hidden. New debug-only `RelayLog` events contain static lifecycle names and counts, never ids, credentials, names, payloads or exception text.

## Testing strategy

- Extend the existing real-Noise peer fixture, avoiding a duplicate harness. RED first: new registry/store/DI tests fail before production implementation.
- Prove initial empty/paired collections, same-relay A/B, immediate saves/removals, unchanged/name-only retention, latest-save selection and credential replacement ordering.
- Prove independent failure/retry, overlapping conversation/modal ids, B's retained repository/modal/cursor and completion of a pending request while A reconnects or is removed.
- Prove foreground/background idempotency, retained cursors with fresh sessions, delayed collection read after background, and permanent disposal cancelling collectors.
- Verify stable DI consumers through unpaired startup, first pairing, selection changes, last removal and both fake/relay selectors. Verify failed persistence produces no revision.
- Run focused JVM classes, Spotless, lint and assembleDebug; run `python3 scripts/android-test-gate.py scripted reconnect` with isolated sibling test binaries and inspect fresh executed XML. No new Compose test or real-Claude scenario: this is ownership-only work. Full UI/scripted regression remains the dispatcher gate.

## Open questions

None. Concrete aliases intentionally require a selected host; stable app consumers use the registry and remain resolvable while unpaired.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/lifecycle-connection-driver.md` under Wiring and Guarantees, `paired-server-store.md` under Wiring & usage, and `relay-repository-coordinator.md` under Configuration to describe collection reconciliation, per-host lifetime and latest-saved compatibility selection. Align the temporary-single-host descriptions in `dependency-injection.md` and `noise-ik-session.md` (Factory wiring) with the new ownership. These shared documents are not edited by the builder.

## Security review

**Verdict:** PASS

- **Trust boundaries:** exact case-sensitive `serverId` map keys select immutable `PairedServer` bundles; no relay URL, conversation id or modal id chooses a host. Unknown ids refuse; no compatibility fallback. Existing parser/authentication boundaries are unchanged.
- **Tokens:** the decorator keeps Keystore AES-GCM persistence; record equality is local change detection, not attacker-facing token authentication. Credential replacement disposes old sessions before new dialing. No token generation, validation, revocation protocol or secret logs are added.
- **Storage:** no new disk data, paths or backup surfaces; delegate atomic encrypted mutations and publish only their successful completion.
- **Android attack surface:** no exported component, intent, URI or WebView change. Targeted push ownership is OUT OF SCOPE under #361.
- **Cryptography:** reuse factory-owned fresh Noise_IK sessions and device-key handling; retain only replay position across resumable close, never reuse a closed pump or nonce state.
- **Network/I/O:** transport validation, frame limits, TLS and capped backoff remain in existing network constructors. Start hosts independently and close all loops on background. No new IO protocol or pinning choice.
- **Logs:** static lifecycle events/counts only through debug-gated `RelayLog`; no server ids, credential fields, local names, daemon text or caught error messages.
- **Concurrency:** serialize reads, synchronize mutations/lifecycle and recheck activity after suspension; cancel all registry collectors permanently on disposal. Deterministic tests cover delayed reads and duplicate signals.
- **Threat alignment:** hostile relay delays/failures stay within that host; daemon-wide replay ids remain within its coordinator. Disk theft protections and hostile-frame bounds are inherited unchanged. Host-aware UI routing/leakage work remains #635–#637; this slice adds no rendering.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

## Revisions

- 2026-09-21: A deterministic selection-edge assertion failed when `stateIn` still exposed B's repository immediately after selection changed to A. Compatibility state now reads `.value` through the selected bundle and switches collected flows without an independent cache. This keeps one-shot repository delegation and modal actions on the same selection, also making disposal immediately read empty state. The projection has no owned job; its collection is cancelled with its consumer. The registry retains only its collection-read job. No network or screen contract changes.
