# Spec #522 — OkHttpRelayTransport frame-rejection tests (binary + oversize)

**Ticket:** https://github.com/pyrycode/pyrycode-mobile/issues/522 · **Size:** XS · **Test-only** (no production change) · Split from #500.

## Files to read first

- `app/src/test/java/de/pyryco/mobile/data/network/OkHttpRelayTransportTest.kt:200-223` — `inbound_malformedJsonTearsDownWithDown`, the **direct template**. Copy its shape: enqueue a `MockResponse().withWebSocketUpgrade(...)` whose `onOpen` drives the bad frame in, then await `transport.events.first { it is TransportEvent.Down }`.
- `app/src/test/java/de/pyryco/mobile/data/network/OkHttpRelayTransportTest.kt:294-320` — `ClosingServerListener` (base for every server listener — echoes the client-initiated close so `server.shutdown()` doesn't hang) and `RecordingServerListener`. Your two new server listeners **must** extend `ClosingServerListener`, exactly as the template's anonymous object does. `failLocally` closes the socket client-side; without the echo, `tearDown()`'s `server.shutdown()` blocks on its drain timeout.
- `app/src/test/java/de/pyryco/mobile/data/network/OkHttpRelayTransportTest.kt:259-292` — `newTransport(...)` helper + `TIMEOUT_MS`/`runBlocking { withTimeout(...) { ... } }` idiom. Reuse verbatim; do not introduce `runTest` (real OkHttp reader threads feed the channels — see the class KDoc at :30-36).
- `app/src/main/java/de/pyryco/mobile/data/network/OkHttpRelayTransport.kt:176-186` — `onMessage(webSocket, bytes: ByteString)` → `failLocally(WS_UNSUPPORTED_DATA, …)`. The binary-reject branch under test.
- `app/src/main/java/de/pyryco/mobile/data/network/OkHttpRelayTransport.kt:149-163` — `onMessage(webSocket, text)`: the **size check at :153 runs before** the JSON decode at :164. So any text of length > `MAX_INBOUND_FRAME_CHARS` trips the size branch regardless of whether it is valid JSON.
- `app/src/main/java/de/pyryco/mobile/data/network/OkHttpRelayTransport.kt:117-139, 222` — `terminate()` (closes both `eventsChannel` and `inboundChannel` idempotently), `failLocally()`, and `const val MAX_INBOUND_FRAME_CHARS = 131_072`. Reference the constant via `OkHttpRelayTransport.MAX_INBOUND_FRAME_CHARS`; it is a public `companion object` member.

## Context

`OkHttpRelayTransport` already rejects two classes of malformed inbound frame from the untrusted relay — a **binary** WS frame (`onMessage(…, bytes)`) and an **oversize text** frame (length `> MAX_INBOUND_FRAME_CHARS`). Both funnel through `failLocally`, which closes the socket and surfaces a terminal `TransportEvent.Down`. Neither path has a test; the 2026-07-03 cross-repo review flagged the gap. This ticket adds the two missing tests to the existing `OkHttpRelayTransportTest.kt`. Production behaviour does not change.

## Design

Two new `@Test` methods in `OkHttpRelayTransportTest`, each a near-clone of `inbound_malformedJsonTearsDownWithDown`, differing only in the frame the server drives in. Add both under the existing `// ---- Design: malformed inbound … ` region (after line 223).

**Observable contract to assert (per AC #1/#2), for both tests:**
1. A terminal `TransportEvent.Down` appears on `events` — await `transport.events.first { it is TransportEvent.Down }` within `withTimeout(TIMEOUT_MS)` (proves teardown ran). Do **not** assert on the close code or the specific `cause` type (AC technical note: assert the outcome, not the mechanism).
2. Nothing is delivered on `inbound`. `terminate()` closes `inboundChannel`, so after teardown `transport.inbound.toList()` completes with an **empty** list — assert `isEmpty()` inside `withTimeout(TIMEOUT_MS)`. Collect `events.first { Down }` first (it establishes that `terminate()` ran); the subsequent `inbound.toList()` then returns `[]` promptly rather than blocking. `inbound` is a single-channel `receiveAsFlow()` — collect it **once** per test.

### Test 1 — binary frame rejected

- Server listener extends `ClosingServerListener`; in `onOpen` it sends a binary frame: `webSocket.send(<ByteString>)` (e.g. a short UTF-8 payload). This routes to the transport's `onMessage(…, bytes)` overload → `failLocally(WS_UNSUPPORTED_DATA, …)`.
- Assert the observable contract above.
- **Import:** `okio.ByteString` is used by the production file but **not** yet imported in the test file — add `import okio.ByteString`. Construct the payload with the Okio idiom already available (e.g. `"x".encodeUtf8()` via `okio.ByteString.Companion.encodeUtf8`, or `ByteString.of(...)`); a one-byte payload is sufficient — content is irrelevant, only the frame *type* matters.

### Test 2 — oversize text frame rejected

- Server listener extends `ClosingServerListener`; in `onOpen` it sends a text frame of length **strictly greater than** the cap: `"a".repeat(OkHttpRelayTransport.MAX_INBOUND_FRAME_CHARS + 1)` (= 131_073 chars). This trips the size guard at `OkHttpRelayTransport.kt:153` **before** any JSON parse, so the non-JSON content is intentional and correct — it exercises the size branch, not the malformed-JSON branch already covered at :200.
- **Boundary (AC #2):** the check is `>`, not `>=`. A frame of exactly `MAX_INBOUND_FRAME_CHARS` (131_072) is *accepted* and must not be used. Anchor the size to `MAX_INBOUND_FRAME_CHARS + 1` rather than a hard-coded literal, so the test tracks the constant if it ever changes.
- Assert the observable contract above.

## State + concurrency model

No production concurrency change. Tests bridge the real OkHttp reader/dispatcher threads (which feed the two `Channel`-backed flows) to the test thread with `runBlocking { withTimeout(TIMEOUT_MS) { … } }`, exactly as every existing test in this class does. `TIMEOUT_MS = 5_000L`. Do not use `runTest`/virtual time — it cannot advance real network I/O (see class KDoc, :30-36).

## Error handling

N/A — these tests *assert* the transport's existing error handling (protocol-violation teardown). Both reject branches already close the socket and emit a single `Down` via `terminate()`'s CAS; the tests observe that outcome and confirm no frame leaks to `inbound`.

## Testing strategy

- **Unit only** (`./gradlew test`; single-class run: `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.network.OkHttpRelayTransportTest"`). No instrumented/androidTest — this class is plain JVM against in-process `MockWebServer`.
- Two new `@Test` scenarios, as specified above. No new helpers required beyond the two ad-hoc server listeners (anonymous `ClosingServerListener` subclasses, matching the template at :204).
- Both tests must pass under `./gradlew test` (AC #3). Run `./gradlew spotlessApply` before committing (ktlint/spotless are gate-enforced).

## Open questions

None. The behaviour under test is shipped and unambiguous; the template test fixes the idiom.
