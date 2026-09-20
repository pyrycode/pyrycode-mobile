# Host-scoped conversation snapshots

## Context

#704 adds one reusable list source; list models (#705/#706), thread routing (#636)
and tree rendering (#641) remain separate. The selected-host compatibility facade
continues to clear disconnected reads. Host identity is attached only at the DI
aggregation boundary; domain and wire records remain host-local.

## Files read

- `di/RelayConnectionRegistry.kt` → `reconcile`, `connectionFor`, `dispose`: retained bundle ownership and exact identity.
- `di/RelayConnectionFactory.kt` → `RelayConnectionBundle`: permanent disposal versus resumable supervisor close.
- `di/ObservablePairedServerStore.kt` → `revision`: serialized reconciliation after saved metadata changes.
- `di/AppModule.kt` → `appModule`, `conversationRepositoryModule`: singleton ownership and build selector.
- `data/repository/RelayRepositoryCoordinator.kt` → `currentRepository`, `connectionStatus`: Open-gated repository and independent link states.
- `data/repository/RemoteConversationRepository.kt` → `observeConversations`: waits for first real list and supplies repository order.
- `data/model/Conversation.kt` → `Conversation`: preserve identifiers, paths and objects without normalization.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt` → `Fixture`, `appModuleTracksLatestSurvivorWithoutReplacingStableConsumers`: real Noise fixture and DI compatibility proof.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` → `tappedRelayRepositoryModule`: keep parser wrapper and add the shared source selector.
- `docs/knowledge/features/dependency-injection.md` § How it works: selected aliases are retained owners, not new connections.
- `docs/knowledge/features/stable-conversation-repository.md` § Cold reads: disconnected fallback cannot be used as a cache input.
- `docs/knowledge/features/relay-repository-coordinator.md` § Configuration: metadata-only edits retain bundle identity.
- `docs/knowledge/features/development-verification.md` § JVM logging and formatting: inject the log sink in JVM fixtures.
- `gradle/libs.versions.toml`: existing coroutines, Koin and JUnit suffice.
- Sibling `pyrycode/docs/protocol-mobile.md` § Identifiers and Application message types: authoritative wire contract; no wire changes.

Production paths above are relative to `app/src/main/java/de/pyryco/mobile/`.

## Design

Add `HostConversationSnapshot` (exact `serverId`, nullable local `displayName`,
`ConnectionStatus`, `channels`, `chats`) and `HostConversationSource` in `di/`.
The source exposes `snapshots: StateFlow<List<HostConversationSnapshot>>` and
`repositoryFor(serverId): ConversationRepository?`. No pairing record is exposed.

The registry publishes internal connection descriptors after its existing revision
reconciliation. Each descriptor holds only presentation identity and the retained
coordinator's repository/status streams. Repository stream identity identifies the
bundle generation. Removed or replaced generations cancel their collectors and
drop their rows; name-only changes retain streams and cached rows.

The source collects `ConversationFilter.All` once per live repository, partitions
active rows by promotion without sorting or rewriting them, and replaces that
host's two lists atomically. Null repositories and silence leave lists untouched.
Status collection runs independently of list collection, including silent hosts.
Updates must match both the currently registered generation and current repository.

Exact lookup uses `RelayConnectionRegistry.connectionFor`, never `selected`, and
rejects absent transports and the coordinator's null pre-Open repository. Lookup
is a current availability snapshot, not a promise that a later operation succeeds.

`hostConversationModule(useRelay)` binds one source, reused by the ordinary selector
and the tapped instrumentation selector. It starts collection on first DI resolution
and owns it until Koin close, independently of screen subscribers. Demo mode uses
only the existing fake singleton, stable id `demo`, local name `Demo`, and connected
demo states. Unknown demo ids never fall through to saved relay hosts.

## State + concurrency model

The source owns a `SupervisorJob` with an injectable Default dispatcher. Registry
reconciliation, per-host status and switched repository-list jobs are eagerly
collected within that scope once constructed. Source mutation and disposal share
one non-suspending lock. No locks are held across suspensions. Disposal cancels all
jobs and clears the descriptor/cache references. Registry disposal publishes no
hosts. Background close retains bundles and snapshots; resume switches repositories.

## Error handling

Existing transport and coordinator streams supply classified connection failures.
No new I/O, parser, retry policy or UI error surface is added. Cancellation propagates.
Unexpected list-flow exceptions are isolated to that collection and logged with a
static category, retaining prior rows until a new repository arrives. Debug logs
contain event codes and counts only, never ids, names, paths, rows or credentials.

## Testing strategy

- JVM controlled flows: empty startup, silent host independence, case-sensitive ids,
  duplicate conversation ids/paths, filter/order preservation and explicit empty replacement.
- JVM lifetime: zero screen subscribers, disconnect/reconnect silence, retired-repo
  late emission, generation replacement, rename, removal and disposal collector counts.
- Existing real Noise registry fixture: saved metadata, connection lookup availability,
  per-host list routing and both selector modes preserve compatibility singleton identity.
- Scoped JVM tests RED then GREEN; Spotless, lint, debug assembly and instrumentation
  compilation. No UI behavior changes or new emulator/live scenario; full regression
  and existing device gates remain dispatcher-owned.

## Open questions

None. Demo identity is deliberately explicit and scoped to fake mode.

## Scope check

One deliverable, 3 production files, approximately 600 written lines including tests
and this plan, 3 new types, no changed constructor contracts, 2 selector integration
sites, 3 acceptance criteria, fewer than 10 lifecycle/error branches. #634's two
commits added 647 lines and removed 45. No in-flight feature branch overlaps found.

## Documentation handoff

Pending for the documentation stage: update
`docs/knowledge/features/dependency-injection.md` under **How it works** to describe
host identity, snapshot lifetime, exact-host access and demo binding. Preserve the
distinction from the selected-host compatibility facade.

## Security review

**Verdict:** PASS

- Trust boundaries: snapshots carry parsed host-local `Conversation` objects under exact saved host identity; paths/names are data, never routing aliases. Rendering remains #705/#706/#641.
- Tokens: internal descriptors and public snapshots exclude `PairedServer`, token and key fields. Credential rotation replaces stream identity and drops that generation's cache.
- Storage: memory only; no file access, backup changes, path interpretation or persistent plaintext cache.
- Android surface: no new activity, service, intent, provider, push handler or WebView.
- Cryptography: unchanged bundle/pump construction and key teardown; the source never consumes encrypted or decrypted frames directly.
- Network/I/O: existing authenticated repository streams are the sole input; one list subscriber per source host, no new transport or retry loop. A silent host cannot block other hosts.
- Logs: only static lifecycle/error codes and row counts through debug-only `RelayLog`; no exception text or presentation values.
- Concurrency: late updates check current descriptor generation plus repository identity; cancellation alone is insufficient at a reconnect/removal edge. Disposal clears state under the same mutation lock.
- Threat alignment: relay delay/drop retains offline rows without granting availability. Exact lookup cannot redirect to the compatibility host. Existing transport/crypto owners retain malformed-frame and credential-at-rest defenses; UI exposure is owned by #705/#706/#641.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

## Revisions

### 2026-09-21 — coherent availability after verifier review

- PR #707 reproduced `repositoryFor` returning the retired repository at the new
  transport edge, before the coordinator's `stateIn` projection caught up. The
  original Design and Security review assumed that cache proved current readiness;
  this revision supersedes that assumption.
- Add internal `RelayRepositoryCoordinator.liveRepository(): ConversationRepository?`.
  Under the coordinator's teardown lock, read one active connection and require
  an active owner, identity with the supervisor's current transport, and that
  connection's pump to be Open. `HostConversationSource.relay` uses this read after
  exact `connectionFor` lookup; compatibility streams keep their existing behavior.
- This necessary repair extends production scope beyond DI to the coordinator,
  which alone owns the coherent connection. No new state, jobs, I/O or wire change.
  Four production files overall, one added internal method consumer, three ACs,
  and about 880 written lines including the original plan/tests and this repair.
  The line ceiling is exceeded; the floor rule keeps this repair with its sole
  consumer because the accessor is not an independently verifiable deliverable.
  The refreshed feature-branch overlap check found none.
- Regression: `repositoryForRejectsRetiredRepositoryAtReconnectTransportEdge`
  checks null synchronously on replacement transport arrival, null while its
  handshake is held, then the new repository after that same handshake completes.
  Run its RED/GREEN proof, the affected DI classes, formatting, lint and assembly.

### Security review update — PASS

- Trust/concurrency: `liveRepository` checks transport identity and the same
  connection's actual pump state under the coordinator lock, independent of cached
  status/repository flows. A later disconnect can invalidate a returned reference,
  as documented; an already replaced transport cannot authorize the old reference.
- Credentials, storage, Android surfaces, cryptography, network and logs: no new
  exposure or behavior. Existing bundle retirement, key teardown, in-memory cache,
  single inbound consumer and content-free logs remain as reviewed above.
- Reviewer: builder, re-applied `builder/security-review.md` on 2026-09-21.
