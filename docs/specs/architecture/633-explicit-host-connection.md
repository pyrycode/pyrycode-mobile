# Explicit-host relay connection construction

## Context and scope

#633 extracts one independently testable construction contract: a relay connection
bundle authenticates to its supplied immutable `PairedServer` for its lifetime.
#634 owns discovery, replacement and selection. Existing single-connection
algorithms and the temporary latest-saved app behavior stay intact.

Sizing: about 650–700 written lines (150 production, 430 tests/helpers, 100 plan),
2 production files, 2 new exported types, 3 moved construction sites, 4 acceptance
criteria, no new state-machine reject branches. This fits the refiner's estimate
and the #351 analogue (518 implementation/test additions plus 215 plan lines).
Codegraph's `appModule` impact omits consumers; direct source search confirms only
the three constructions in `appModule` move, with existing constructor APIs kept.
Remote feature branches were fetched and checked: no overlapping target files.

## Files read

- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: `appModule`, `conversationRepositoryModule` — current composition and concrete aliases.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt`: `PairedServer`, `PairedServerStore` — immutable credentials and compatibility read seam.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionFactory.kt`: `create`, `reloadDeviceStaticKey` — authentication inputs and private-buffer wiping.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt`: `connect`, `runLoop`, `close`, `retry` — fresh transport per dial and resumable close.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt`: `NoiseSessionPump`, `close` — per-connection scope, re-key and session cleanup.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt`: `onConnection`, `teardownActive`, `close`, `replayCursor` — repository/pump ownership and reconnect lifetime.
- `app/src/main/java/de/pyryco/mobile/data/network/ReplayCursor.kt`: `record`, `reset`, `latest` — per-owner atomic cursor.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt`: `d` — debug-only, content-free lifecycle logging.
- `app/src/main/java/de/pyryco/mobile/lifecycle/LifecycleConnectionDriver.kt`: `onStart`, `onStop` — background close must remain resumable.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSessionPumpTest.kt`: `TestResponder`, `FakeRelayTransport` — real Noise responder proof seam.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSessionFactoryTest.kt`: `create_forwardsLastEventIdSupplierIntoHello` — decrypt hello to assert actual cursor bytes.
- `app/src/test/java/de/pyryco/mobile/di/ConversationRepositoryBindingTest.kt`: `withSelector` — isolated Koin selector proof.
- `gradle/libs.versions.toml` — existing coroutine/JUnit/Koin dependencies suffice.
- `docs/knowledge/features/noise-ik-session.md`, Factory wiring and Threading & key hygiene — preserve short private-key RAM lifetime.
- `docs/knowledge/features/relay-repository-coordinator.md`, Scope ownership, Configuration and Reconnect-spanning replay cursor — pump scope requires explicit close.
- `docs/knowledge/features/paired-server-store.md`, Wiring & usage — compatibility selects latest save.
- `docs/knowledge/features/dependency-injection.md`, How it works — fake selector changes UI repository, not connection establishment.
- `docs/knowledge/features/lifecycle-connection-driver.md`, Wiring and Guarantees — keep lifecycle controller alias.
- `docs/knowledge/features/development-verification.md`, Test scheduling and JVM logging — drain channel cascades with `runCurrent`; capture `RelayLog.sink` in JVM tests.
- `docs/e2e-interactive-stream.md` — existing rung-4 reconnect coverage; no new operator flow.
- Sibling `pyrycode/docs/protocol-mobile.md`, Pairing flow, Phone → relay → binary, `hello`, Security model — authoritative wire contract, unchanged here.

## Design

Add `di/RelayConnectionFactory.kt` with `RelayConnectionFactory` and
`RelayConnectionBundle`. The factory takes the existing device store, transport
factory, client info, push-token supplier and injectable worker/IO dispatchers.
`create(record: PairedServer)` wraps the record in a private read-only
`PairedServerStore` view; `createCompatibility(store: PairedServerStore)` retains
the dynamic legacy read. Both enter the same construction path.

Each bundle owns a supervisor, session factory and coordinator. The session
factory's cursor lambda closes over that bundle's coordinator, with no Koin read.
Construction completes before the coordinator starts observing transports; the
lambda is invoked only when a later handshake builds hello. Each connection still
gets a fresh real `NoiseSessionPump`, session and repository. No extra cursor,
registry, repository facade or persisted state is introduced.

`appModule` registers the reusable factory and one eager compatibility bundle.
Existing concrete supervisor/session-factory/coordinator resolutions are aliases
of its members. Controller/state interfaces and fake/relay selector stay intact.
Koin bundle disposal calls bundle close; ordinary lifecycle close still calls the
supervisor alone, permitting foreground reconnect with the same cursor.

## State + concurrency model

The factory adds no scope. The existing supervisor owns retries; coordinator owns
its hot projections and per-connection repository scopes; each pump owns its scope.
Injected worker dispatcher reaches all three, with IO separately injected into
`NoiseSessionFactory`. Bundle close stops the supervisor and closes the coordinator,
which cancels repository collectors and explicitly closes the pump, preserving
session/key wiping. Close is idempotent. Disposed bundles are not reused; temporary
disconnect/retry remains through the supervisor. Neither action touches another bundle.

## Error handling

Keep transport/Noise error categories, retry/backoff and UI projections unchanged.
The fixed store view rejects mutation as an internal invariant violation and never
exposes credentials in its exception. Record validation remains in the existing
transport and Noise boundaries. Emit static `RelayLog` creation/disposal events;
no record fields, token, host, cursor or exception content are logged.

## Testing strategy

Add focused JVM construction tests first and observe RED before production edits.
Use real Noise responders over channel-backed fake transports to prove:

- A/B sharing a relay URL dial their exact records, decrypt distinct hello tokens,
  pin distinct keys, and use server-id-specific device keys initially and on re-key
  after a shared-store change; caller-owned private buffers are wiped.
- Inbound events record distinct cursors; resetting A does not reset B; reconnects
  advertise the owning cursor and omit `last_event_id` when empty.
- A drop/retry creates fresh encrypted connections; disposal closes channels and
  collectors; B retains its repository, status and cursor and still processes events.
- Compatibility is idle when unpaired, connects after save, selects the next saved
  host on a later dial, and survives background close/foreground reconnect.
- Isolated Koin resolution retains concrete aliases and both selector choices.

Run the focused JVM class and existing selector tests, Spotless, lint and
`assembleDebug`. Run `python3 scripts/android-test-gate.py scripted reconnect`
with the installed SDK/JDK and isolated sibling test binaries, inspect fresh XML
and record executed count. Dispatcher owns the full UI/scripted suite. No new UI,
Figma surface, protocol behavior or rung-3 scenario is introduced.

## Open questions

None. The deliberate dynamic compatibility entry is removed/migrated by #634,
not frozen at startup in this ticket.

## Documentation handoff

Pending for the documentation stage: update
`docs/knowledge/features/noise-ik-session.md` under Factory wiring and
`docs/knowledge/features/relay-repository-coordinator.md` under Configuration and
Reconnect-spanning replay cursor to describe bundle ownership and the temporary
single-host compatibility path.

## Security review

**Verdict:** PASS

- **Trust boundaries:** `create(record)` accepts an already paired record, not raw QR input. Its fixed read view feeds both dial and Noise identity; latest-save mutation cannot retarget it. Existing `NoiseSessionFactory.create` validation and authenticated pump decoding remain the boundaries.
- **Tokens:** no token generation, comparison or persistence added. The fixed immutable record is retained deliberately; replacement/revocation selection belongs to #634. Existing Keystore-wrapped stores and private-buffer wiping remain intact; tests verify both hosts after store changes.
- **File/storage:** no new path, write, backup or disk cache; replay remains memory-only. Existing storage custody is unchanged.
- **Android surface:** no manifest, intent, provider, push payload or rendering changes. Push-token supplier remains the existing one-shot registration input.
- **Crypto:** retain vendored Noise IK, fresh session per transport and existing re-key key reload/wipe. Tests use separate responder keys and bidirectional encrypted traffic; no shared ciphers or nonce reset.
- **Network/I/O:** preserve transport URL/header validation, frame caps, TLS/timeouts and supervisor backoff. No new network endpoint or certificate policy. A hostile relay still cannot authenticate as either pinned responder.
- **Logs/errors:** only static bundle lifecycle events through the debug gate; no credentials, decrypted frames, URL, host or cursor. Existing category errors remain unchanged.
- **Concurrency:** initialize all members before starting collectors; local lambda never resolves app-global state. Close reaches both supervisor and coordinator/pump scopes. Existing algorithms are preserved; no additional locks around crypto or new cross-owner flows.
- **Threat model:** this change addresses accidental cross-host authentication/replay mixing. Malformed daemon frames and relay denial remain covered by existing boundaries. Disk theft and UI leakage gain no new surface; credential replacement and selection are explicitly deferred to #634.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-20
