# Spec #521 — `RelayLog`: debug-gated, redacted relay diagnostic logging facility

**Ticket:** [#521](https://github.com/pyrycode/pyrycode-mobile/issues/521) · **Size:** S · **Security-sensitive:** yes · Split from #500.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/crypto/StaticKeyFingerprint.kt:1-38` — **the redaction idiom to mirror.** BLAKE2s full 256-bit digest → truncate to first N bytes → lowercase hex via `HexFormat`/`ByteArray.toHexString`. `redactConnId` copies this shape (different input, different byte width — see Design).
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:109-111` — `connId` is a **daemon-issued `String`** (from `hello_ack`), thrown-until-handshake. This is the value `redactConnId` consumes; note it is a plain String, not a key.
- `app/src/main/java/de/pyryco/mobile/ui/settings/AboutScreen.kt:26,61-62` — how `de.pyryco.mobile.BuildConfig` is imported and referenced. `RelayLog` imports `BuildConfig.DEBUG` the same way (module namespace confirmed `de.pyryco.mobile`, `app/build.gradle.kts:34`).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:9,54,147,156` — second `BuildConfig` usage; establishes the repo pattern of *compile-time BuildConfig flag as a defaulted value* (`useRelay: Boolean = BuildConfig.USE_RELAY_REPOSITORY`). `RelayLog.enabled` follows the same "default from BuildConfig, overridable seam" pattern.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:47`, `RelayConnectionSupervisor.kt:64`, `data/repository/RelayRepositoryCoordinator.kt:59` — the **"Emits no logs"** contract these four relay files keep. This ticket delivers a facility; it does **not** instrument these files. Read only to confirm the posture the facility must not silently violate (adoption is a future ticket).
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt:42` and `data/network/MobileWireModels.kt:68` — existing "a stray `Log.d($secret)` must not leak this" comments. `RelayLog`'s MUST-NOT-log KDoc is the same spirit, made into a first-class contract.
- `app/src/main/java/com/southernstorm/noise/crypto/Blake2sMessageDigest.java:59-70` — vendored digest. `Blake2sMessageDigest extends MessageDigest`; `.digest(bytes)` returns the fixed **32-byte** digest. API surface for `redactConnId`.
- `app/build.gradle.kts:87-104` (`testOptions`) — **no Robolectric, no `unitTests.returnDefaultValues`.** Consequence: real `android.util.Log.*` throws `"not mocked"` in plain JVM unit tests. This is *why* the design needs an injectable sink (below). Also: unit tests run the `testDebugUnitTest` variant → `BuildConfig.DEBUG == true` on the test classpath.
- `gradle/libs.versions.toml:13` — Kotlin `2.2.10`. `HexFormat` / `ByteArray.toHexString` are **stable** at this version; no `@OptIn(ExperimentalStdlibApi::class)` needed (confirmed: `StaticKeyFingerprint.kt` uses them with no opt-in).
- Lessons (vault, not code — codegraph won't surface these):
  - [[ktlint-filename-rule-single-class]] — file `RelayLog.kt` has exactly one top-level class-like type (`object RelayLog`); compliant. Keep `redactConnId` a *member* so no second top-level type appears.
  - [[data-layer-android-import-exception]] — `android.util.Log` in `data/` is allowed; the MUST-FIX trigger is `Context`-shaped only. This facility is the sanctioned platform-logging custodian.
  - [[blake2s-fingerprint-full-then-truncate]] — `redactConnId` is a *correlation token*, NOT the MITM static-key fingerprint; its width rationale is deliberately different (see Design → Redaction).

## Context

During the 2026-07-03 cross-repo review, ad-hoc `PYRYDBG` connection-diagnosis logs (`Log.i`/`Log.w`) on branch `wip/mobile-connection-diagnosis` nearly shipped. R8 strips `Log.d`/`Log.v` from release but **keeps `Log.i`/`Log.w`** — so those calls, which logged the relay host, live `connId`, close cause/reason, pump state, and negotiated capabilities, would have reached production logcat. They are confirmed absent from `main` today (grep `PYRYDBG app/` → no matches).

This ticket delivers a logging facility that is **safe by construction** so the next diagnosis session doesn't repeat the "add raw logs → diagnose → remember to strip" churn:

1. It **cannot reach release output** — gated on an explicit `BuildConfig.DEBUG` check, not on R8 log-level stripping (which the near-miss's `i`/`w` calls defeated).
2. It **cannot assemble a sensitive value in release** — messages are supplied by a lambda that is not invoked when the gate is off.
3. It **offers no affordance to write** the relay host, pairing token, or raw key material, and its one correlation-id helper redacts.

**Explicitly out of scope** (per body + #500 PO decision): permanently instrumenting `RelayConnectionSupervisor` / `RelayRepositoryCoordinator` / `NoiseSessionPump` / `OkHttpRelayTransport`. Those keep their "Emits no logs" contract. There is no observed need for standing diagnostic logging (Evidence-Based Fix Selection) — the facility ships ready-to-use, not pre-installed. A later diagnosis session or follow-up ticket adopts it.

## Design

### Placement

- **Production:** `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` — sits with the relay transport it will one day instrument.
- **Test:** `app/src/test/java/de/pyryco/mobile/data/network/RelayLogTest.kt`.

One new top-level type (`object RelayLog`). Zero consumer changes. No new dependency (reuses the already-vendored `Blake2sMessageDigest`; `BuildConfig` already generated for this module).

### `object RelayLog` — contract sketch

```kotlin
object RelayLog {
    private const val TAG = "RelayLog"          // stable logcat tag: `adb logcat -s RelayLog`; not derived from any value

    internal var enabled: Boolean = BuildConfig.DEBUG   // the gate; test seam (see State model)
    internal var sink: (Int, String, String) -> Unit =  // emission seam; default → android.util.Log
        { priority, tag, message -> android.util.Log.println(priority, tag, message) }

    fun d(message: () -> String)   // if (enabled) sink(Log.DEBUG, TAG, message())
    fun i(message: () -> String)   // if (enabled) sink(Log.INFO,  TAG, message())
    fun w(message: () -> String)   // if (enabled) sink(Log.WARN,  TAG, message())

    fun redactConnId(connId: String): String   // see Redaction below
}
```

Key properties (each maps to an AC):

- **Debug-only, explicit gate (AC1).** `enabled` defaults to the compile-time constant `BuildConfig.DEBUG`. In a release build that constant is `false`, so the branch is never taken; nothing in production flips it (no UI toggle, no prod setter). The guard is an explicit `if (enabled)` — it does **not** rely on R8 stripping a log level.
- **Lambda not invoked when disabled (AC2).** `message()` is only ever called inside `if (enabled)`. When the gate is off, the string is never built and no potentially-sensitive value is assembled. The methods are **non-inline** on purpose: a public `inline fun` would force the `internal` test seams to `@PublishedApi internal`; non-inline keeps the seams clean and still gives the full "string-not-built-when-disabled" guarantee (the lambda object is a cheap capture that's discarded unless invoked). Do **not** add `inline` (Simplicity First; the runtime gate, not inlining, is the load-bearing guarantee).
- **`d` / `i` / `w`.** The near-miss reached for `Log.i` and `Log.w`; offering `d`/`i`/`w` gives the next diagnosis the same vocabulary through the safe gate. The level only sets the logcat priority — safety is uniform (all three gate identically). Three trivial one-liners over one shared code path.
- **Redacted correlation id (AC3), and only that.** `redactConnId` is the single structured affordance. There is deliberately **no** `logHost(...)`, `logToken(...)`, or key-formatting method — the facility does not *offer* a way to write those. Free-form `{ }` messages remain the developer's responsibility; the KDoc carries the MUST-NOT-log contract (below). Statically preventing a developer from interpolating a secret into a lambda is not code-enforceable and is out of scope — code-review of the future adoption ticket is the belt to this suspenders (documented contract).

### Redaction — `redactConnId`

Mirror `StaticKeyFingerprint.kt`: full 256-bit BLAKE2s digest of the input, truncated, rendered lowercase hex.

```kotlin
// full digest of the UTF-8 bytes, first REDACT_BYTES rendered as lowercase hex (no separator)
Blake2sMessageDigest().digest(connId.toByteArray(Charsets.UTF_8)).toHexString(0, REDACT_BYTES)
```

- `REDACT_BYTES = 4` → an **8-char** lowercase-hex token (`^[0-9a-f]{8}$`).
- **Deterministic** — same `connId` → same token, so log lines from one connection correlate within a diagnosis session.
- **Structurally never equals the input** — a fixed-width hex digest of an arbitrary-length string can't equal a real `conn_id`. This satisfies the AC5 "not the full input" requirement for *all* inputs, including short or empty ones (no edge-case guard needed — the reason to prefer hashing over prefix-truncation).
- **Reveals zero bytes of the live `conn_id`** — strongest redaction posture, correct for a security-sensitive facility. The daemon-issued `conn_id` is a session-correlatable identifier we do not want scrapable from logcat.
- **Width rationale — read before flagging.** The 4-byte truncation here is a *correlation token*, **not** the MITM static-key fingerprint (`staticKeyFingerprint`, [[blake2s-fingerprint-full-then-truncate]], which is load-bearingly ≥8 bytes for brute-force resistance). That constraint is an *authentication* requirement — no adversary is forging a `conn_id` token here. A correlation token only needs enough width to avoid casual collisions across the handful of connections in one debug session; 2³² is ample. Do not "align with the fingerprint" and bump this to 8 bytes.

**Rejected alternative — prefix truncation** (`connId.take(8)`): reveals real bytes of the live id and needs a special case when `connId` is shorter than the window (else it returns the whole value, violating AC5). Hashing has neither problem. Truncation's only advantage — prefix-matching against a daemon-side raw `conn_id` log — is a nice-to-have a future adoption ticket can add deliberately if a diagnosis actually needs it.

### MUST-NOT-log contract (KDoc on `RelayLog`)

The object's KDoc states, as the facility's contract, that callers must never pass into a message lambda: the relay **host / URL**, the **pairing token**, **raw key material** (static/ephemeral keys, transcript), the **full `conn_id`** (use `redactConnId`), or **message payloads / bodies**. Loggable: event type, redacted conn-id, pump state name, close code, capability *names*. This mirrors the existing `PairedServerStore.kt:42` / `MobileWireModels.kt:68` "don't Log this secret" comments, promoted to the logger's own doc so it's visible at every future call site.

## State + concurrency model

Synchronous static helper — **no coroutines, no `Flow`, no scope, no dispatcher.** Nothing to cancel or shut down.

- `enabled` and `sink` are `object`-level mutable vars used purely as **test seams**. In production they are written once at class initialization (`enabled` from the `BuildConfig.DEBUG` constant; `sink` to its default) and **never reassigned**, so they are effectively final at runtime — safe publication via class init, no data race, no `@Volatile` needed (adding it would be cargo-cult; Simplicity First).
- Only tests mutate the seams, and unit tests are single-threaded, so no concurrency concern arises there either.
- `redactConnId` is pure and total.

## Error handling

No failure surface. `redactConnId` cannot throw for any `String` input (UTF-8 encoding and a fixed-size digest always succeed; empty string is valid). The log methods do nothing observable when disabled and delegate to the sink when enabled. There is no network, I/O, parse, or permission boundary in this facility, so there is no result type to thread and nothing for the UI to surface — by design, a diagnostic logger is invisible to the user.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.network.RelayLogTest"`; note bare `./gradlew test` is an aggregate that rejects `--tests` — [[gradle-single-test-class-task]]). No instrumented test. The **sink seam is what makes the enabled path testable on plain JVM** — the module has no Robolectric and no `returnDefaultValues`, so real `android.util.Log.*` throws; tests install a capturing lambda instead.

**Test hygiene (footgun):** `RelayLog` is an `object` with process-global mutable seams. Each test must restore them in `@After` — `enabled = BuildConfig.DEBUG`, `sink` back to a no-op/default — or state leaks across tests. Also note `BuildConfig.DEBUG == true` on the test classpath, so `enabled` defaults *true* in tests; the disabled-path test sets it `false` explicitly.

Scenarios (developer writes the bodies in the project idiom):

- **`redactConnId` produces a derived token, not the input (AC5).** For a representative `conn_id`: assert the result `!=` the input, matches `^[0-9a-f]{8}$`, and is deterministic (two calls equal). A different input yields a different token.
- **`redactConnId` on short/empty input still isn't the input.** Empty string and a 3-char id both yield an 8-char hex token `!=` input — proves the hash approach has no short-input hole (the reason it beats truncation).
- **Disabled gate does not invoke the lambda (AC2/AC4).** Set `enabled = false`; install a capturing sink; call `RelayLog.d { flag = true; "…" }`. Assert the message lambda's side effect never fired **and** the sink was never called. (Use a boolean/counter, not a thrown exception, so the assertion reads cleanly.)
- **Enabled gate invokes the lambda and forwards to the sink.** Set `enabled = true`; install a capturing sink; call `RelayLog.d { "hello ${RelayLog.redactConnId("abc123")}" }`. Assert the sink received `(Log.DEBUG, "RelayLog", "hello <token>")` where `<token>` is the redacted form (never `"abc123"`). This is the positive direction of the gate and proves no `android.util.Log` runtime is required.
- **Level mapping.** `i` and `w` forward `Log.INFO` / `Log.WARN` respectively (one small test, or fold priorities into the enabled test).

## Open questions

- **None blocking.** One deferred-by-design decision: whether a future adoption ticket wants raw-`conn_id` prefix correlation against daemon logs. If so, that ticket adds it deliberately; this facility ships hash-only.

## Design source

N/A — this is a non-UI platform-logging facility (`data/network/`); there is no visual surface. The ticket body has no `## Figma` section, which is correct here (nothing renders). Visual-fidelity checks intentionally skipped.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No inbound untrusted boundary — the only inputs are a daemon-issued `connId: String` (already inside the trusted process) and developer-authored message lambdas; the facility is a sink, not a parser. The load-bearing boundary is *outbound*: trusted process state → logcat (readable via ADB on a debug device, or `READ_LOGS` on a rooted device). It is explicit and single-point: the `if (enabled)` gate plus `redactConnId`. Downstream has no "am I holding trusted data?" question because nothing flows back out of the facility.
- **[Tokens/secrets]** No token is generated or stored here. AC3 is met structurally — the facility *offers* no host/token/key affordance (no `logHost`/`logToken`; the only structured helper redacts). **SHOULD FIX (adoption-time, not now):** a developer can still interpolate a secret into a free-form `d { "$token" }`. This is not statically preventable without over-engineering (Evidence-Based Fix Selection — no observed instance; the near-miss was a *release* leak, which the gate fully closes). Mitigated by the MUST-NOT-log KDoc contract + code-review of the future adoption ticket. Not exploitable as designed — the facility never itself assembles or writes a secret.
- **[File/storage]** N/A — no filesystem, persistence, or path handling.
- **[IPC / Android surface]** N/A — no exported component, intent-filter, deep link, content provider, WebView, or PendingIntent. The only cross-process exposure is logcat, addressed under Logs.
- **[Cryptographic primitives]** No finding — `redactConnId` reuses the vendored `Blake2sMessageDigest` (standard, already-vetted, not hand-rolled). It is a keyless one-way digest used for *correlation*, not authentication, so `SecureRandom` and constant-time comparison are not applicable. Preimage recovery of the `conn_id` from an 8-char token is infeasible; the deterministic same-id→same-token property is the intended (debug-only) correlation, not a leak. The 4-byte width is a correlation parameter, explicitly *not* the ≥8-byte MITM-fingerprint security parameter ([[blake2s-fingerprint-full-then-truncate]]).
- **[Network & I/O]** N/A — the facility adds no network surface; frame-size / timeout / TLS concerns belong to the transport (#522 and existing `OkHttpRelayTransport`), out of scope here.
- **[Logs / telemetry]** No finding on the core risk — release builds emit **nothing** (`enabled = BuildConfig.DEBUG` is `false` in release; the gate is an explicit build check, not R8 log-level stripping, which the `Log.i`/`Log.w` near-miss defeated). MUST-NOT-log fields (host, token, keys, full `conn_id`, payloads) are named in the `RelayLog` KDoc; MUST-log-safe fields (event type, redacted conn-id, pump state, close code, capability names) are enumerated. No crash reporter is in the emission path (sink → `android.util.Log` only). **OUT OF SCOPE:** if a crash reporter (Crashlytics/Sentry) is added later, it must honor the same gate — a future concern for whoever adds it, named here so it isn't forgotten.
- **[Concurrency]** No finding — no coroutine, scope, or shared mutable prod state. `enabled`/`sink` are write-once at class init in production (effectively final at runtime), mutated only by single-threaded tests; `android.util.Log.println` is thread-safe. No check-then-act / TOCTOU.
- **[Threat model alignment]** Mobile-specific threats (screenshot leakage, accessibility eavesdropping, overlay attacks, third-party keyboard logging) are N/A — no UI and no input surface. The applicable mobile threat is logcat readability, closed for release by the gate and narrowed in debug by redaction. The facility does not touch the wire protocol, so there is no `protocol-mobile.md` § Security-model threat to map beyond "don't log secrets," which is addressed.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-04
