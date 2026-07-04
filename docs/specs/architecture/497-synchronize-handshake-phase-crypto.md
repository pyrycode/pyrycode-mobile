# #497 — Synchronize handshake-phase crypto methods against `close()`

**Size:** XS · **Security-sensitive:** yes · **Manifests only with `USE_RELAY_REPOSITORY` on.**

Add `@Synchronized` to the two handshake-phase entry points of `NoiseIkSession` — `writeInit` and `readResp` — so they lock on the same instance monitor (`this`) already used by `close`/`encrypt`/`decrypt`/`writeRekeyInit`/`readRekeyResp`. This closes a use-after-wipe window where a teardown `close()` firing mid-handshake could destroy the `handshake` (device static-key copy + handshake secrets) out from under an in-flight handshake step. Plus a one-line KDoc **Threading** edit so the enumerated synchronized set matches the code.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:52-57` — the class KDoc **Threading** paragraph; the sentence enumerating the `@Synchronized` set. **Edit target** (AC #2): must grow to include `writeInit`/`readResp`.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:125-134` — `writeInit`. **Edit target** (AC #1): prepend `@Synchronized`. Note it calls the private `buildHello()` (non-synchronized) — no nested lock.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:142-174` — `readResp`. **Edit target** (AC #1): prepend `@Synchronized`. Note the `finally { hs.destroy(); handshake = null }` — the handshake wipe this method itself performs, and the wipe that `close()` performs concurrently, are what the lock now serializes.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:186-289` — the five methods already carrying `@Synchronized` (`writeRekeyInit:186`, `readRekeyResp:217`, `encrypt:249`, `decrypt:258`, `close:277`). The pattern to join; confirm the annotation form (`@Synchronized` on its own line above `fun`, **no import** — `kotlin.jvm.*` is a default import). `close:277-289` is the concurrent wiper (`handshake?.destroy(); handshake = null`).
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt` — the existing JUnit4 suite (handshake round-trip, re-key, AEAD encrypt/decrypt, close idempotence) that must stay green (AC #3). Imports are `org.junit.Test` / `org.junit.Assert.*`. The new structural test lands here as one more `@Test` (no new file — keeps the single-class-per-file ktlint rule satisfied).
- `docs/knowledge/features/noise-ik-session.md` § *Threading & key hygiene* (read-only) — the deterministic-backstop rationale and the bounded device-static-key RAM window (#298) this change preserves. **Do not edit** — the feature doc is documentation-phase territory; the developer's worktree mutates only `NoiseIkSession.kt` and `NoiseIkSessionTest.kt`.

## Context

`NoiseIkSession` commits to its instance monitor as a **deterministic backstop** against the catastrophic, silent nonce-reuse a same-direction race would cause under ChaCha20-Poly1305 — the established-phase and lifecycle methods (`encrypt`, `decrypt`, `close`, `writeRekeyInit`, `readRekeyResp`) are all `@Synchronized`. The two **handshake-phase** entry points were left un-synchronized.

`close()` wipes and nulls `handshake` (and the cipher pair). It can run concurrently with an in-flight `writeInit`/`readResp` — e.g. a transport `Down` teardown landing mid-handshake while the pump is still driving the IK exchange. The concurrent handshake path dereferences `handshake` after `close()` has destroyed it → a **use-after-wipe** of the device static-key copy and handshake secrets.

The single-pump contract means this "should not occur" in normal operation, but the class deliberately treats the lock as the floor rather than trusting the contract. Extending the floor to the handshake pair closes the gap and makes the KDoc's enumerated set match the code. Same class of defensive crypto-lifecycle hardening as the sibling 2026-07-03 Cross-Repo Code Review findings — #495 (re-key watchdog) and #496 (close-then-connect race). Synchronization posture origin: #298, extended by #303.

## Design

Two annotation additions, one doc edit — one production file.

```kotlin
@Synchronized
fun writeInit(): ByteArray { … }          // NoiseIkSession.kt:125 — body unchanged

@Synchronized
fun readResp(resp: ByteArray): String { … } // :142 — body unchanged
```

`@Synchronized` on an instance method locks on `this` — the **same** monitor the other five methods already acquire. No new lock object is introduced, so there is no lock-ordering question. No method body changes; the annotation adds no observable single-threaded behaviour.

**KDoc edit (AC #2).** Revise the **Threading** paragraph (`:52-57`) so the enumerated synchronized set includes `[writeInit]`/`[readResp]`, and add a clause stating that a mid-handshake `close()` therefore cannot wipe `handshake` out from under an in-flight handshake step. Required semantic content (developer words it to match the paragraph's voice):

> …`[writeInit]`/`[readResp]`/`[encrypt]`/`[decrypt]`/`[close]` are `@Synchronized` — as are `[writeRekeyInit]` and `[readRekeyResp]` — so a teardown `[close]` firing mid-handshake cannot destroy the `handshake` out from under an in-flight `[writeInit]`/`[readResp]`, and the re-key CipherState swap cannot interleave with an in-flight same-direction AEAD op.

## State + concurrency model

The load-bearing reasoning — why the added lock is safe, verified against the code:

- **No deadlock / no self-re-entrancy hazard.** `writeInit` calls only the private `buildHello()`; `readResp` calls only the private `parseHelloAck()`. Neither private helper is `@Synchronized`, and neither method calls another `@Synchronized` method on `this`. A held monitor is reentrant anyway, so even nested acquisition would be safe — but there is none. Single monitor, no lock ordering to reason about (AC restated in the ticket's Technical Notes).
- **No blocking I/O under the lock.** `writeInit`/`readResp` are pure synchronous in-memory crypto over bounded buffers (`hs.writeMessage` / `hs.readMessage` on ≤128 KiB inputs — the inbound cap is enforced upstream at the WS transport, #306). The monitor is never held across a network call or a suspension point, so the lock cannot stall the pump on a slow/hostile relay.
- **Mutual exclusion achieved.** After the change, `close()` and an in-flight `writeInit`/`readResp` are mutually exclusive on `this`. `close()` either runs fully before the handshake step reads `handshake` (the step then sees `handshake == null` and throws the existing `IllegalStateException("session is closed")` — a clean caller-bug signal, not a wipe-corrupted read), or it waits for the bounded handshake step to complete and then wipes. Either ordering is safe; the interleaved-mid-body ordering is the one now excluded.
- **RAM-window tradeoff (considered, acceptable).** `close()` now waits for an in-flight handshake step before wiping, extending the device-static-key RAM window by at most one bounded, non-blocking crypto op (microseconds). This is the correct behaviour — you cannot safely wipe key material *during* a step that is reading it — and the extension is negligible against #298's whole-handshake window bound.
- **Cancellation.** These are plain synchronous JVM methods with no coroutine suspension point mid-body. Cancelling the calling coroutine cannot interrupt a `synchronized` method mid-execution (a held monitor is not released by `Thread.interrupt`, and these methods don't poll interruption); the monitor is always released on normal or exceptional method exit. This is also why a deterministic close-vs-handshake race *test* is not practical here — unlike #496's coroutine-`finally` seam, there is no injectable suspension point to gate. See Testing strategy.

## Error handling

Unchanged. `writeInit`/`readResp` keep their existing failure surfaces:

- Wrong-order calls → `IllegalStateException` (caller bug), including the `handshake == null` "session is closed" throw when `close()` won the race.
- Handshake MAC failure / malformed `noise_resp` / malformed `hello_ack` → `NoiseSessionException` (category-only message; no key material, plaintext, token, or raw bytes — the no-secrets-in-logs posture is preserved; **no new log calls are added**).
- `readResp`'s existing fail-closed transition to `State.CLOSED` on any throw, and its `finally` handshake wipe, are unchanged.

## Testing strategy

Unit-only (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.network.NoiseIkSessionTest"`). No instrumented test — pure-JVM `noise-java`, byte-array in/out. No new test file.

- **AC #3 — unchanged behaviour.** The existing suite (handshake round-trip, re-key happy path + atomic swap + peer-static continuity, AEAD round-trip + direction/AD/prologue guards, state guards, close idempotence) must stay green. The annotation is single-threaded-transparent.
- **Structural assertion (the honest bar per the ticket's Technical Notes).** Add one `@Test` to `NoiseIkSessionTest.kt` that reflectively asserts the synchronized set — the executable form of AC #2 ("the doc's enumerated set matches the code"):
  - For each of `writeInit` (no params), `readResp`(`ByteArray`), `encrypt`(`ByteArray`), `decrypt`(`ByteArray`), `close` (no params), `writeRekeyInit`(`ByteArray`), `readRekeyResp`(`ByteArray`): look up the method via `NoiseIkSession::class.java.getDeclaredMethod(name, …paramTypes)` (use `ByteArray::class.java` for the `[B` params) and assert `java.lang.reflect.Modifier.isSynchronized(method.modifiers)` is `true`.
  - Rationale it relies on: Kotlin `@Synchronized` sets the JVM `ACC_SYNCHRONIZED` method flag (it does **not** compile to a `monitorenter` block), so `Modifier.isSynchronized` observes it via reflection. This is deterministic — no threads, no timing, not flaky.
  - The two load-bearing new entries are `writeInit`/`readResp`; enumerating the full set locks the whole documented invariant against a future refactor silently dropping an annotation.
- **No flaky race test.** Explicitly do **not** write a two-thread close-vs-handshake timing test. Per Evidence-Based Fix Selection and the ticket's Technical Notes: no injectable mid-body seam exists (unlike #496), so any such test would be timing-dependent and false-green-prone. The guarantee is structural — the two methods join an already-tested five-method pattern in the same class.

## Design source

N/A — internal crypto-lifecycle change, no UI surface. The visual-fidelity check is intentionally moot.

## Open questions

None. The fix is fully determined by the ticket: two annotations, one doc line, one structural test. Anchors verified against `main` at spec time (`writeInit:125`, `readResp:142`, KDoc `:52-57`, existing `@Synchronized` set `:186/:217/:249/:258/:277`).

## Acceptance criteria

1. `writeInit` (`NoiseIkSession.kt:125`) and `readResp` (`:142`) carry `@Synchronized`, locking on the same instance monitor (`this`) as `close`/`encrypt`/`decrypt`/`writeRekeyInit`/`readRekeyResp`.
2. The class KDoc **Threading** paragraph (`:52-57`) enumerates `writeInit`/`readResp` in the synchronized set, so the doc matches the code.
3. A reflective structural test in `NoiseIkSessionTest.kt` asserts `Modifier.isSynchronized` for the full synchronized set (the two new methods plus the existing five).
4. Handshake behaviour is otherwise unchanged: the existing `NoiseIkSession` unit suite remains green — the annotation adds no observable single-threaded behaviour.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — `readResp` remains the sole untrusted→trusted crossing (network `noise_resp` → post-MAC `hello_ack`). The change adds a lock only; it gates on no attacker-controlled value, relocates no parse, and weakens no validation. `establishedConnId`/`negotiatedCaps`/`state` are still written only after a successful parse (`NoiseIkSession.kt:161-163`).
- [Tokens, secrets, credentials] No findings — strengthening. The change closes a use-after-wipe of the device static-key copy + handshake secrets held in `handshake` (`close():279-280` wipes it; the un-synchronized `readResp`/`writeInit` could deref it post-wipe). No new field holds the key/token, no new log or `toString` surface is introduced, and `NoiseIkSession` remains a plain class (not a `data class`). `pendingToken` handling is unchanged.
- [File / storage operations] N/A — the class performs no filesystem I/O; it is byte-array in / byte-array out.
- [Inter-process / Android attack surface] N/A — no Android components, Intents, or exported surface; the class is `android.*`-free by design.
- [Cryptographic primitives] No findings — strengthening. No RNG/KDF/primitive/nonce change. The added lock can only *reduce* interleaving; it cannot create a nonce- or key-reuse window. No deadlock: `writeInit`/`readResp` call only non-synchronized private helpers (`buildHello`/`parseHelloAck`), never another `@Synchronized` method on `this`; single reentrant monitor, no lock ordering.
- [Network & I/O] No findings — no timeout/TLS/frame-cap change. The monitor is held only across bounded synchronous in-memory crypto (`hs.writeMessage`/`hs.readMessage` over already-received buffers; the inbound size cap is enforced upstream at the WS transport #306). No blocking I/O runs under the lock, so a hostile relay cannot stall the pump while it holds the monitor.
- [Error messages, logs, telemetry] No findings — no new log calls; `NoiseSessionException` messages stay category-only (no key material, plaintext, token, or raw bytes). The wrong-order `handshake == null` path throws the existing category-only `IllegalStateException`.
- [Concurrency] No findings — this is the fix's category. `close()` and an in-flight `writeInit`/`readResp` become mutually exclusive on `this`; the mid-body interleaving that caused the use-after-wipe is now excluded. No deadlock (above); no liveness hazard (`close()` waits at most one bounded, non-blocking crypto op before wiping); the change removes rather than adds a check-then-use window. No coroutines/flows in this class, so scope/hot-vs-cold questions do not apply. Cancelling the calling coroutine cannot interrupt a synchronous `@Synchronized` method mid-body; the monitor is always released on exit.
- [Threat model alignment] No findings — the addressed threat is a mid-handshake teardown (transport `Down`) racing the pump's handshake driver, a local memory-safety / secret-hygiene defect (use-after-wipe of the device static key + handshake secrets), not a remotely-exploitable one: it requires the single-pump contract to already be violated internally ("should not occur"). The fix is defense-in-depth, consistent with #298's bounded device-static-key RAM window and the class's deterministic-backstop posture. Mobile-specific threats (screenshot leakage, accessibility eavesdropping, overlay attacks) are untouched by an internal crypto-lifecycle lock and out of scope for this ticket.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-04
