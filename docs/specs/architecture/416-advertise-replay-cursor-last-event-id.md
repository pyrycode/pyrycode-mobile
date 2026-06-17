# 416 — Advertise the live replay cursor as `last_event_id` on reconnect

**Ticket:** pyrycode-mobile#416 · **Size:** S · **Security-sensitive:** yes (server-replay trust boundary)
**Split from:** #413 · **Sibling (blocked on this):** #417 (resync reset + surface-gap)

## Design source

N/A — pure data-layer wiring (the `hello` handshake payload). No UI surface; no Figma node.

## Context

A mid-turn socket drop loses the interactive structured-stream tail (the daemon kept producing
`turn_state` / `assistant_delta` / `tool_use` / `tool_result` / `turn_end` events while the phone was
gone). The daemon holds those events in a per-conversation in-memory ring (pyrycode#646) keyed by a
durable, strictly-increasing `event_id`. To replay the missed tail it needs the phone to say where it
left off.

The **record** half already shipped: #412 added `ReplayCursor` — a process-scoped, reconnect-spanning,
strictly-advancing, positive-guarded high-water mark of the latest observed `Envelope.eventId`. It
lives on `RelayRepositoryCoordinator` as `internal val replayCursor` and is folded on the single
inbound path by `RemoteConversationRepository.recordReplayCursor`.

This slice is the **advertise** half: on every reconnect the phone reads `replayCursor.latest` at
`hello`-build time and sends it as `last_event_id` in the `hello` payload. The daemon replays the
conversation's ring events with id `> last_event_id` on the same single inbound path **before** the
live stream resumes. The phone names no conversation — `last_event_id` is its only obligation; the
daemon resolves the conversation server-side.

**Out of scope (explicitly deferred):** reacting to the daemon's `resync` marker (#417, blocked on
this), and full reload via `backfill_since` (no daemon-side message-history store exists yet). This
slice closes the wire-advertise; it does not attempt to perfectly reconstruct an in-flight turn whose
*pre-cursor* deltas the fresh per-connection repo never saw — that gap is owned downstream.

## Server precondition — security gate (re-confirmed at architect time)

The advertise was held in Inbox until the server stopped trusting `last_event_id` unboundedly. An
unfixed server set its per-conn dedup watermark (`replayThrough`) straight from the untrusted
`last_event_id` even when the ring was caught up — silently suppressing **all** live frames after a
`/clear`+reconnect (rotated id-space below a stale cursor) or a hostile `2^64-1` cursor.

**Re-verified present on pyrycode `main` @ `0386940` (#663 merged) at architect time:**

- **Clamp:** `internal/relay/v2session.go:1493` — `s.replayThrough = min(afterID, newest)`. The
  aged-out / gap branch (`:1481`) leaves `replayThrough` untouched and emits a `resync` marker
  instead.
- **Accessor:** `internal/eventring/ring.go:198` — `NewestID(convID) uint64` (the newest event is
  never evicted, so this is the highest retained id).
- **Dedup guard bounded by the clamp:** `v2session.go:1962` drops `env.EventID <= s.replayThrough`;
  because `replayThrough` is clamped to `newest`, it can never over-suppress a live frame.
- **Delivery regression tests:** `TestV2Session_Reconnect_OutOfRangeLastEventID_LiveStreamDelivered`
  and `TestV2Session_Reconnect_ClearRotation_LiveStreamDelivered`
  (`internal/relay/v2session_replay_test.go:454, :512`).

> Note: pyrycode `docs/knowledge/codebase/647.md` § "Known issue" still reads "unresolved MUST FIX" —
> that is **stale**; the fix landed under the separate #663 ticket (`codebase/663.md`, "Blocks
> pyrycode-mobile#416") and 647.md was never back-updated. Current state is the 663 doc.

Precondition met → design and ship.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:46-89` — `Envelope` (see the
  `eventId: Long? = null` + `@SerialName("event_id")` omitempty doc at `:38-54`) and
  `HelloClientPayload:76-89`. `Envelope.eventId` is the **exact precedent** to copy for the new
  `HelloClientPayload.lastEventId` field (nullable-defaulted, `explicitNulls=false` omit-on-encode).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — `MobileJson` config.
  Confirm `explicitNulls = false` is present (it is) — this is what omits a null `last_event_id`
  (AC#2). No codec change.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:62-101, 116-130, 297-313` —
  the constructor (`clientInfo` is the injected identity), `writeInit()`, and `buildHello()`. The
  cursor read lands inside `buildHello()`; the supplier is a new constructor param.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionFactory.kt:19-53` — `create()` builds
  the session from a static `clientInfo`. Add a defaulted supplier param the factory forwards to the
  session.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:78-99` — the
  `internal val replayCursor` seam and its `:89-98` KDoc, which states the field is `internal`
  **specifically so #413 can read `ReplayCursor.latest` to advertise the resume point**. This slice
  is that reader. Do **not** relocate or re-introduce the cursor.
- `app/src/main/java/de/pyryco/mobile/data/network/ReplayCursor.kt:22-39` — `latest: Long?` (point
  read; `null` = nothing observed) and the positive/strictly-advancing `record` guard.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:52-53, 78-88` — the `NoiseSessionFactory`
  single and the coordinator single. The supplier is wired here (see Design § wiring).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:217-218,
  346-362` — `onInbound` calls `recordReplayCursor` first; the single inbound path + `appendMessages`
  message_id dedup (`:475-491`) are the existing no-double-apply substrate (AC#3). Unchanged here.
- `app/src/test/java/de/pyryco/mobile/data/network/MobileWireCodecTest.kt:71-96, 157-191` —
  `envelope_withNullEventId_omitsOnEncode` (`:91-96`) is the template for the AC#2 omit test;
  `:157-171` shows `HelloClientPayload` serialization assertions.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt:25, 43-54` — the
  `TestResponder` decrypts `writeInit()` and recovers the `hello` JSON; `:43-54` asserts decoded
  `HelloClientPayload` fields. This is the harness for the AC#1 live-read test.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSessionFactoryTest.kt:24-138` — fakes +
  `factory(...)` helper (`:90`) + happy-path `create()` (`:66-88`); extend for the factory-forwards
  test.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:2257-2280`
  — `replayCursor_advancesOnInteractiveFrames_ignoresOutOfOrder` (real `ReplayCursor`,
  `turnStateEnvelope(..., eventId=)`, `runCurrent()`); the AC#3 test extends this harness.
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt:596-624` —
  `replayCursor_survivesReconnect` (#412): the cursor outlives the per-connection repo rebuild.
  Context for why the advertised value carries across a reconnect.

## Design

Thread a **live read** of the existing cursor into the `hello`-build. The cursor is *consumed*, never
moved or duplicated (#412 owns it). The read is deferred via a `() -> Long?` supplier so it reflects
the cursor as of the moment `writeInit()` builds the `hello` (AC#1: dynamic per reconnect, survives
the per-connection session rebuild, never a construction-time snapshot, never baked into the static
client identity).

### Data flow (downward; the cursor read is supplied at the composition root)

```
RelayRepositoryCoordinator.replayCursor (internal val, process-scoped)   ← #412, unchanged
        │  read lazily as { coordinator.replayCursor.latest }
AppModule  ── lastEventId supplier ──▶ NoiseSessionFactory (single)
        │  factory.create() forwards the supplier
NoiseIkSession(lastEventId)  ── buildHello() invokes lastEventId() ──▶ HelloClientPayload.lastEventId
        │  explicitNulls=false omits a null value
hello envelope on the wire: last_event_id present (value) or absent (null)
```

The supplier reads the `internal val replayCursor` seam `RelayRepositoryCoordinator` exposes — the
seam #412's KDoc was written for. We do **not** change `createPump`'s signature or re-thread through
the coordinator/pump (that would touch 6 production files and disturb the guarded `onConnection`
cancellation-atomicity invariants for no benefit); the factory holding a `() -> Long?` keeps the
network layer's code free of any repository dependency, with the only cross-layer edge confined to the
composition root.

### Contract changes (4 production files)

1. **`MobileWireModels.kt` — `HelloClientPayload`.** Add one field, modelled byte-for-byte like
   `Envelope.eventId`:
   - `@SerialName("last_event_id") val lastEventId: Long? = null`
   - Add `lastEventId` to the overridden `toString()` (non-secret, alongside `capabilities`).
   - KDoc: mirrors `Envelope.eventId` — `explicitNulls=false` omits it when null so a fresh `hello`
     stays byte-identical to today (AC#2); a positive value rides as `omitempty *uint64` per the
     server contract. The cursor's record-time positive guard means `latest` is always a positive
     `Long` or null, so no wraparound/negative reaches the wire.

2. **`NoiseIkSession.kt`.** Add a defaulted constructor param
   `lastEventId: () -> Long? = { null }` (after `clientInfo`). In `buildHello()`, set
   `lastEventId = lastEventId()` on the `HelloClientPayload`. The invocation site is inside
   `buildHello()` (called from `writeInit()`), so the read happens at handshake-build, not at session
   construction — this is the AC#1 "live read" guarantee. KDoc the param: a live supplier, invoked
   once per session at `writeInit()`; default `{ null }` keeps the field absent.

3. **`NoiseSessionFactory.kt`.** Add a defaulted constructor param
   `lastEventId: () -> Long? = { null }` (after `ioDispatcher`). Forward it into the
   `NoiseIkSession(...)` construction in `create()`. `reloadDeviceStaticKey()` (re-key) builds no
   `hello` and is untouched. The default keeps existing positional `NoiseSessionFactory(get(), get(),
   get())` constructions and all factory tests compiling unchanged.

4. **`AppModule.kt`.** Wire the supplier into the factory single:
   `single { NoiseSessionFactory(get(), get(), get(), lastEventId = { get<RelayRepositoryCoordinator>().replayCursor.latest }) }`
   - `replayCursor` is `internal` and `AppModule` is in the same module → accessible.
   - **No DI cycle:** the supplier is a lambda invoked only at `hello`-build (per connection, after
     the coordinator is constructed and started). Constructing the factory only *stores* the lambda;
     it never resolves the coordinator eagerly. Even though the coordinator single resolves the
     factory eagerly at its own construction (`AppModule.kt:79`), the lambda is not invoked there. By
     the time it fires, `get<RelayRepositoryCoordinator>()` returns the already-cached singleton.
   - Leave a comment recording this ordering rationale and the no-cycle reasoning.

No new files, no new exported types, no interface changes, no `createPump`/coordinator/pump changes.

## State + concurrency model

- **No new state, no new coroutines.** The supplier is a pure deferred read of
  `ReplayCursor.latest`, which is a `MutableStateFlow.value` read — non-suspending, thread-safe,
  lock-free (the cursor is folded via a TOCTOU-free `update` on the inbound collector; the reader and
  writer never race, per #412's design).
- **When the read fires:** exactly once per session, on the drive coroutine inside
  `NoiseSessionPump.drive()` → `session.writeInit()` → `buildHello()`. The cursor write fires on the
  *previous* connection's inbound collector; the read fires on the *next* connection's handshake. They
  are on different connections (the cursor is the only thing shared, by design, to bridge them).
- **Dispatcher:** unchanged. The session is built on the factory's `ioDispatcher`; the supplier read
  adds no blocking work.

## Error handling

- **No new failure modes.** `ReplayCursor.latest` cannot throw; it returns `Long?`. A `null`
  (fresh process, nothing observed) omits the field (AC#2) — the omit is correct, not an error.
- **Trust-boundary posture (this is an advertise, server-consumed):** the phone advertises only the
  high-water mark it itself recorded, never a value it received unvalidated from the server, and never
  a conversation id. The server-side clamp (re-verified above) bounds the blast radius of any
  malformed/stale cursor; combined with the phone advertising the *accurate* recorded cursor, the
  caught-up-watermark suppression cannot trigger.
- **Logging:** `last_event_id` is non-secret (an event ordinal), but keep it out of any new log line
  — `HelloClientPayload.toString()` already redacts the token, and the no-secrets-in-logs posture
  (#291/#273) means the `hello` is never logged wholesale anyway.

## Testing strategy

All unit (`./gradlew testDebugUnitTest`), pure-JVM. Map each AC to the lowest level that proves it.

**AC#2 — fresh connection omits `last_event_id` (wire-level, pure).** In `MobileWireCodecTest`,
mirroring `envelope_withNullEventId_omitsOnEncode`:
- `HelloClientPayload(deviceName=…, clientVersion=…, token=…, lastEventId = 42)` →
  `encodeToString` contains `"last_event_id":42`.
- `HelloClientPayload(…)` with `lastEventId` defaulted (null) → encoded string does **not** contain
  `last_event_id` (asserts omitted, never `0`/`null`).

**AC#1 — reconnect advertises the recorded cursor, read live at `hello`-build (session-level).** In
`NoiseIkSessionTest`, using the `TestResponder` to decrypt `writeInit()` and decode the `hello`:
- Build a real `ReplayCursor`; `record(5)`; construct the session with `lastEventId = { cursor.latest }`;
  then `record(8)` **after** construction but **before** `writeInit()`; decrypt → decoded
  `HelloClientPayload.lastEventId == 8`. This single scenario proves both "the recorded cursor is
  advertised" and "live read at build, not a construction snapshot" (AC#1's two clauses) using the
  real cursor — no mock.
- Supplier `{ null }` (or a fresh `ReplayCursor`) → the recovered raw `hello` JSON string omits
  `last_event_id` (AC#2 at the session level too).

**Factory forwards the supplier (factory-level).** In `NoiseSessionFactoryTest`, extend the
`factory(...)` helper with a defaulted `lastEventId` and add one happy-path test: a factory built with
`lastEventId = { 9L }` produces a session whose `writeInit()` `hello` (decoded via the responder
helper, or asserted through the session) carries `9` — proving the threading factory→session.

**AC#3 — replayed events are not double-applied relative to the live stream (repository-level).** In
`RemoteConversationRepositoryTest`, extending the `replayCursor_advances…` harness (real
`ReplayCursor`, fake pump, `runCurrent()`), drive the single inbound path with a replay-then-live
sequence and assert single application:
- Pre-advance the shared cursor (as a prior connection would) to N. Push, in order on one fresh repo,
  replayed `message` envelopes with `event_id` `N+1`, `N+2` (distinct `message_id`s) followed by a
  live `message` with `event_id` `N+3`; assert the conversation thread contains each message exactly
  once and the cursor advanced monotonically to `N+3`.
- Push a `message` whose `message_id` repeats one already applied (the defensive overlap case); assert
  it folds **in place** (one row, last-write-wins) via the existing `appendMessages` dedup — no second
  row. This demonstrates the single-path consumption is duplication-safe; no new production code backs
  this AC (the dedup + cursor are #313/#412 substrate — the advertise composes with them).

Describe these as scenarios; write the assertions in the project's existing test idiom. Reuse
`turnStateEnvelope` / the message-envelope builders already in those test files; do not introduce new
fixtures.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No MUST FIX. One new boundary: the **outbound** advertise of `last_event_id`
  (phone → daemon). The phone advertises **only** `replayCursor.latest`, a value it recorded from its
  own authenticated inbound stream through #412's positive-guarded, strictly-advancing fold
  (`ReplayCursor.kt:35-38`) — never an unvalidated server value, never a conversation id (the daemon
  resolves the conversation). The historical *server-side suppression* risk (a stale/hostile/oversized
  cursor making the daemon drop live frames after `/clear`+reconnect or via `2^64-1`) is the precise
  reason this ticket was Inbox-gated; it is closed by the `min(afterID, newest)` clamp + bounded dedup
  guard, **re-verified present on pyrycode `main` @ `0386940`** (§ Server precondition). No new inbound
  parse — replayed events arrive on the existing validated `onInbound` boundary, unchanged.
- **[Tokens / secrets]** N/A. `last_event_id` is a non-secret event ordinal, not a credential. The
  `hello` token path is untouched (still sealed in Noise early-data, still redacted in
  `HelloClientPayload.toString()`).
- **[File / storage]** N/A. The cursor is in-RAM only (#412: never persisted; a fresh process reads
  `null`). No file write, no path from user/network input into a filesystem path, no at-rest data.
- **[IPC / Android surface]** N/A. No Activity/Service/Receiver/deep-link/Intent/WebView; pure
  `data/network` + `di` wiring.
- **[Cryptographic primitives]** N/A. No new crypto, RNG, or key handling. The advertise rides the
  existing `Noise_IK` `hello` early-data; the supplier read sits inside the already-established
  `buildHello()`/`writeInit()` path.
- **[Network & I/O]** No findings. One optional bounded field (a single `Long`, ≤ ~25 bytes) added to
  an existing frame — no new frame type, no size-cap / timeout / TLS change. No unbounded growth.
- **[Error messages / logs]** No findings. `last_event_id` (non-secret) is not logged: the `hello` is
  never logged wholesale (#291/#273 no-secrets posture) and `toString()` keeps the token redacted.
  Developer must add no new `Log`/`Timber` line printing the `hello`.
- **[Concurrency]** No MUST FIX. The advertise side is a single non-suspending, lock-free
  `MutableStateFlow.value` read (TOCTOU-free per #412's `update` fold) — no new coroutine, scope, or
  mutex, no check-then-mutate. Benign edge: a late `record` from a torn-down previous connection's
  collector can only *advance* the cursor, so a new `hello` could read a value missing that last
  event → the server replays at most one extra already-seen event → the **fresh** per-connection repo
  applies it exactly once (no double-apply, no suppression). The design tolerates this by construction.
- **[Threat-model alignment]** Advertises per the wire SSOT (`protocol-mobile.md` § "Replay cursor
  (`event_id`)"). The server suppression threat is the addressed gate (above). No mobile-specific
  surface (screenshot/deep-link/overlay) applies to a wire ordinal. **OUT OF SCOPE:** in-flight-turn
  pre-cursor gap reconstruction → #417 (resync) + deferred `backfill_since` full reload.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-17

## Open questions

- None blocking. The in-flight-turn pre-cursor gap (a fresh per-connection repo never re-receives
  events `≤ cursor`) is intentionally out of scope — closed later by the `resync` reaction (#417) and
  the deferred full-reload (`backfill_since`). This slice's contract ends at the wire-advertise.
