# Architecture: Noise_IK session re-key mechanism — fresh handshake + atomic CipherState swap (initiator) (#303)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:1-248` — **the file this ticket modifies.** Extract: the `State` enum + `state`/`handshake`/`ciphers`/`sender`/`receiver` fields (:64-74); `readResp` (:118-149) — the re-key methods mirror its `readMessage → check(SPLIT) → split() → assign sender/receiver → destroy(handshake)` sequence, but **swap-and-retain instead of first-establish-and-fail-close**; the `@Synchronized` discipline on `encrypt`/`decrypt`/`close` (:152-190) — the swap extends this lock; `close()` (:180-190) — the new pending-rekey field must be wiped here too.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt:1-309` — **the test file this ticket extends.** Extract: the `TestResponder` harness (:258-286) — it must gain a re-key responder leg reusing its own static keypair; the `establish()` (:231-237) + `ackEnvelope()` (:239-252) helpers; the `newPrivateKey()`/`newPublicKey()`/`assertThrowsBadPadding` companion helpers (:288-308); the existing AC-test idioms (round-trip, crossing-fails, clean-failure-surface) to mirror.
- `app/src/main/java/com/southernstorm/noise/protocol/HandshakeState.java` — constructor (suite parse → `NoSuchAlgorithmException`), `start()` (mixes the **empty** prologue — never `setPrologue`, same as initial), `writeMessage` (re-key payload is **zero-length**), `readMessage` (`BadPaddingException` on MAC failure), `split()` at :1139 (**initiator does NOT swap**; responder mirror-swaps). The re-key builds a *fresh* `HandshakeState`; the call sequence is identical to the initial handshake.
- `app/src/main/java/com/southernstorm/noise/protocol/CipherStatePair.java:52-63,102` — `getSender()`/`getReceiver()` (assign new pair the same way `readResp` does) and `destroy()` (wipes both cipher states — this is the "old keys wiped on swap" call).
- `app/src/main/java/com/southernstorm/noise/protocol/CipherState.java:112,141` — `encryptWithAd(null,…)`/`decryptWithAd(null,…)`; transport stays valid throughout re-key, so these are unchanged.
- `docs/specs/architecture/298-noise-ik-session-handshake-aead-transport.md` — the parent spec. Extract § *State + concurrency model* (the nonce-reuse safety net the swap must respect) and § *Error handling → Key hygiene* (the **device-static-key RAM-window** posture this ticket must preserve — drives the §"Re-obtaining `s`" decision below).
- `docs/knowledge/features/noise-ik-session.md` — feature doc. Extract § *Edge cases → "No rekey op"* (the seam this ticket fills) and § *Threading & key hygiene* (the lifecycle invariant the re-key must not regress).
- Go mirror — `pyrycode/docs/specs/architecture/453-v2-rekey-responder-swap.md` (QMD `pyrycode-docs`). The responder's atomic CipherState swap + peer-static continuity check; the mobile initiator is the mirror image. (Key facts inlined in § Context below — read only if cross-repo access is available.)
- Authority — `pyrycode/docs/protocol-mobile.md` § Re-key (QMD `pyrycode-root`). The wire contract. (Inlined in § Context — the developer's dispatched context may lack cross-repo access; treat the inlined facts as load-bearing.)

## Context

Phase 4 chain. Builds directly on the established Noise_IK session shipped in #298 (`NoiseIkSession`). That session is one-shot today: it drives a single handshake, splits into transport ciphers, and exposes **no re-key surface** ("the session deliberately exposes no rekey surface yet" — `noise-ik-session.md` § Edge cases). This ticket adds the in-place re-key mechanism — a fresh `Noise_IK_25519_ChaChaPoly_BLAKE2s` handshake run on an already-open session, deriving fresh sender/receiver CipherStates that **atomically replace** the live pair, with the old pair **wiped** on swap.

**Scope: the session-layer mechanism only — byte-array(+key) in, byte-array out.** The *triggers* that invoke it (the 1-hour timer, the inbound `rekey_request` dispatch) live at the WS-message-pump layer and are a **separate ticket** that needs the relay WS client (#301, not yet built). This ticket is the mirror of the Go responder's atomic swap (`pyrycode` #453).

This is a data-layer transport primitive — **not UI-visible**, so there is correctly no `## Figma` / `## Design source` section (the ticket body has none; this is not a PO gap — same shape as #298).

### Wire contract — `protocol-mobile.md` § Re-key (inlined; authoritative)

Re-key is a **full Noise_IK handshake re-run, initiated by the phone** (IK requires the initiator to start). The relevant facts:

1. The phone sends a fresh `noise_init` frame. **The early-data payload of a re-key `noise_init` is empty** (zero-length plaintext, AEAD-sealed under the handshake-derived key per Noise_IK rules). The original handshake's `hello`/token early-data is **NOT re-sent** — the handshake itself is the signal (mirrors WireGuard/Tailscale re-key).
2. The binary responds with `noise_resp`, **also carrying empty early-data** (no `hello_ack`, no new `conn_id` — the `conn_id` is unchanged across re-key; confirmed by #453's responder `WriteResp(nil)`).
3. Both sides derive new `(k_send, k_recv)` CipherStates from the new handshake.
4. **Atomic switchover:** the first frame sent after the new handshake uses the new keys; **the old CipherStates are zeroised**. Any in-flight frame received under old keys after switchover fails AEAD MAC and the connection closes (acceptable — the switchover signal is the new handshake completing, not a wire marker).

### Peer-static continuity (AC #3) — how the initiator enforces it

On the responder (#453), continuity is an explicit `bytes.Equal(newResp.PeerStatic(), s.peerStatic)` check, because the responder learns the initiator's static only from the message. **On the initiator the check is structural**: Noise_IK pins the responder's static (`rs`) *up front*, and this ticket re-keys against the **same pinned `rs`** every time. A re-key whose `noise_resp` is produced by anyone *not* holding the pinned `rs`'s private half (a relay-operator MITM, or a server that rotated its static key) fails `readMessage` (`es`/`se` DH diverge → MAC failure). The required behaviour on that failure — **retain the existing CipherStates, no swap** — is the heart of AC #3, and is the key lifecycle difference from #298's `readResp` (which fail-closes).

## Design

### Re-obtaining the device static key `s` — the central decision

The re-key needs the device static private key `s` again, but #298 deliberately discards it after the first handshake to bound its RAM window (`readResp` destroys the handshake post-`split()`, wiping the device-key copy). The two options the ticket flags:

- **(A) Retain `s` on the session** for its whole lifetime → re-key needs no parameter. Simplest API, but **negates #298's explicit, security-reviewed RAM-window property**: `s` (the phone's *long-term identity* — strictly more valuable than the per-session transport keys, since leaking it enables future-handshake impersonation until re-pair) would sit in RAM for the entire multi-hour session.
- **(B) Re-load `s` per re-key, never retain it on the session** → preserves the RAM-window bound.

**Decision: (B).** The session does **not** retain `s`. `writeRekeyInit` **takes `s` as a parameter** (the caller re-loads it fresh and zeroes its buffer immediately after — the same `create()` discipline #298 already uses for the constructor). The only place `s` lives across the re-key is inside the *pending re-key `HandshakeState`*, which is destroyed on `readRekeyResp` (success or failure) and on `close()` — exactly the bounded window #298 fought for. Re-key fires hourly over a long session; option A would defeat that bound on every one of those hours.

Cost of (B) vs (A): one method parameter and one line of caller zero-after-use discipline. The security win (long-term identity stays out of RAM except for each ~100µs handshake) is worth it. This keeps the session byte-array(+ephemeral-key)-in/out — no store references, no `suspend`, no factory coupling added here. *Who* re-loads `s` (a future `NoiseSessionFactory` helper vs. the pump itself) is the pump ticket's concern; per #298's own evidence-based "no speculative seam" rule, this ticket adds no unused factory wiring (see § Open questions).

### The new session surface (no new files, no new exported types)

Two new methods on the existing `NoiseIkSession`, plus two new private fields. **No constructor signature change** (zero edit fan-out).

```kotlin
// NEW private fields:
//   private val pinnedRemoteStatic: ByteArray   // copyOf(remoteStaticPublicKey) — the continuity anchor (public; no wipe)
//   private var pendingRekey: HandshakeState? = null   // the in-flight re-key handshake; null ⟺ no re-key in flight

/** Begins a re-key: builds a fresh IK initiator handshake against the pinned rs using the
 *  caller-supplied (freshly re-loaded) device static [s], with EMPTY early-data, and returns
 *  the raw noise_init bytes. Transport stays valid on the CURRENT keys until [readRekeyResp].
 *  Caller MUST zero [s] after this returns — the session copies it into the handshake and does
 *  not retain it. Requires an established session with no re-key already in flight. */
fun writeRekeyInit(s: ByteArray): ByteArray

/** Completes a re-key: reads the noise_resp, derives the fresh CipherState pair, and ATOMICALLY
 *  swaps it for the live pair, wiping the old pair. On ANY handshake/crypto failure the live pair
 *  is RETAINED (transport continues on the current keys) and a NoiseSessionException is thrown.
 *  Requires a re-key in flight. */
fun readRekeyResp(resp: ByteArray)
```

`writeRekeyInit` returns the frame bytes the WS pump base64-wraps as `InnerFrameV2(type="noise_init", …)` (per protocol § Re-key step 1; same outer framing as the initial `writeInit`, owned by the pump ticket). `readRekeyResp` returns `Unit` — unlike `readResp`, **no `conn_id` is parsed** (it is unchanged across re-key, and the resp carries empty early-data).

### State model — no new enum value; transport never pauses

Re-key must **not** block transport: per protocol § Re-key step 4, frames continue on the *current* keys right up to the atomic switchover. So `state` stays `ESTABLISHED` throughout. "Re-key in flight" is encoded by `pendingRekey != null`, not by a state — avoiding a `REKEYING` state that would make `encrypt`/`decrypt` (which `check(state == ESTABLISHED)`) wrongly throw mid-re-key.

```
                         writeRekeyInit(s)            readRekeyResp(resp)
ESTABLISHED ──────────────────────────────▶ ESTABLISHED ─────────────────────────▶ ESTABLISHED
(pendingRekey=null)       (build fresh hs,   (pendingRekey≠null,   success: swap+wipe old → (pendingRekey=null, new keys)
                           empty early-data)  old keys live)        failure: retain old   → (pendingRekey=null, old keys)
```

Guards (caller-bug → `IllegalStateException`, distinct from runtime `NoiseSessionException`):

- `writeRekeyInit` requires `state == ESTABLISHED && pendingRekey == null` (else: not established, or re-key already in flight).
- `readRekeyResp` requires `state == ESTABLISHED && pendingRekey != null` (else: no re-key in flight).
- A **failed** re-key clears `pendingRekey` and stays `ESTABLISHED`, so the pump **may retry** `writeRekeyInit` (unlike the initial handshake, which is not resumable).

### `readRekeyResp` body — described, not pre-written (mirrors `readResp` with swap-and-retain)

The developer writes the imperative body in the project idiom; the contract:

1. `check` re-key is in flight (above) → else `IllegalStateException`.
2. `pendingRekey.readMessage(resp, …)` into a small buffer (size `resp.size`; early-data is empty, returned length 0 — discarded). Map `BadPaddingException` → `NoiseSessionException("noise_resp rekey verification failed")`, `ShortBufferException` → `NoiseSessionException("malformed noise_resp")`.
3. `check(pendingRekey.action == SPLIT)` → else `NoiseSessionException`.
4. `val newPair = pendingRekey.split()` — initiator does **not** swap: `newPair.sender` = encrypt-out, `newPair.receiver` = decrypt-in (same assignment as `readResp`).
5. **Atomic swap (the critical section):** capture `val oldPair = ciphers`; reassign `ciphers = newPair; sender = newPair.sender; receiver = newPair.receiver`; then `oldPair?.destroy()` (wipes the old transport keys — AC #2).
6. On any failure in 2–4: **do not touch `ciphers`/`sender`/`receiver`** — they keep pointing at the live pair (AC #3 retain). Re-throw as `NoiseSessionException`.
7. `finally`: `pendingRekey.destroy(); pendingRekey = null` — wipes the re-key handshake + its copy of `s` regardless of outcome (the bounded RAM window).

`writeRekeyInit` body: `require(s.size == 32)`; build `HandshakeState(PROTOCOL, INITIATOR)` (wrap `NoSuchAlgorithmException`), `setPrivateKey(s, 0)`, `setPublicKey(pinnedRemoteStatic, 0)`, `start()` (never `setPrologue` — empty prologue load-bearing, same as initial), `writeMessage(out, 0, EMPTY_BYTES, 0, 0)` into `out = ByteArray(IK_MSG1_OVERHEAD)` (= 96; zero-length payload), store `pendingRekey`, `return out.copyOf(n)`.

### `close()` change

Add `pendingRekey?.destroy(); pendingRekey = null` to the existing idempotent `close()` so an abandoned mid-re-key handshake (and its `s` copy) is wiped on teardown.

## State + concurrency model

- Not a ViewModel — no `StateFlow`/`UiState`/`Event`. Synchronous byte ops, as in #298.
- **The swap respects #298's nonce-reuse safety net.** Both new methods are **`@Synchronized`** (the session's intrinsic lock, the same one guarding `encrypt`/`decrypt`/`close`). This is AC #2's "atomic with respect to in-flight transport encrypt/decrypt": the pointer swap in step 5 cannot interleave with a concurrent same-direction AEAD op, so there is no window where one direction uses new keys and the other old, and no nonce-reuse window. The lock is held across the whole `readRekeyResp` (incl. `readMessage` + `split`, ~100µs) — acceptable: re-key is ~1/hour and the single-WS-pump contract means the lock is uncontended anyway. `encrypt`/`decrypt`/`writeRekeyInit`/`readRekeyResp` never call one another and the two ciphers are distinct objects, so no deadlock. The lock remains the deterministic floor under the stochastic single-pump contract (belt-and-suspenders; same posture as #298).
- **No new coroutine, no `suspend`.** `writeRekeyInit` takes `s` by parameter precisely so the session does no I/O; the caller (pump ticket) owns the suspend keystore re-load.
- **Shutdown.** A re-key abandoned between `writeRekeyInit` and `readRekeyResp` (process death, screen exit) leaks nothing durable: `close()` destroys `pendingRekey` (wiping `s`); a GC'd session's `Destroyable` handshake is wiped too.

## Error handling

| Failure | Source | Surfaced as | Live keys after |
|---|---|---|---|
| Re-key handshake MAC failure (wrong/rotated server `rs`, MITM, tampered re-key `noise_resp`) | `readMessage` → `BadPaddingException` | `NoiseSessionException` (pump maps to server close `4426`, as #298) | **RETAINED** (AC #3) |
| Truncated/garbage re-key `noise_resp` | `ShortBufferException` | `NoiseSessionException("malformed noise_resp")` | **RETAINED** |
| Handshake did not reach SPLIT | `check(action == SPLIT)` | `NoiseSessionException` | **RETAINED** |
| Suite/algorithm missing (defensive) | `NoSuchAlgorithmException` in `writeRekeyInit` | `NoiseSessionException("noise suite unavailable")` | unchanged (no swap attempted) |
| `s` not 32 bytes | `require` in `writeRekeyInit` | `IllegalArgumentException` (caller bug) | unchanged |
| Wrong call order (`readRekeyResp` with no re-key in flight; `writeRekeyInit` twice; either after `close`) | state/`pendingRekey` guard | `IllegalStateException` (caller bug) | unchanged |
| **Old-key frame after a successful swap** (AC #4) | `decrypt` → `BadPaddingException` on the **new** receiver | `NoiseSessionException` (**inherited**, no new code; pump closes the connection) | n/a |

**Fail-RETAIN is the correct fail-safe** (vs. #298's fail-close): a failed re-key has leaked no old-key material (the failure means the responder couldn't complete the new handshake), so tearing down a working session would be a needless availability hit. The pump may retry `writeRekeyInit`, or close if it suspects MITM. **Key hygiene carries #298 forward unchanged:** no `Log`/`Timber`; `NoiseSessionException` messages are category strings only; `s` is wiped per re-key via `pendingRekey.destroy()`; the old transport pair is wiped via `oldPair.destroy()` on swap.

## Testing strategy

JVM-only (`app/src/test/.../NoiseIkSessionTest.kt`, `./gradlew test`) — the vendored `noise-java` suite is pure Java and the mechanism is byte-array in/out, exactly like #298. No device/instrumented test.

**Extend `TestResponder`** with a re-key responder leg. It must (a) reuse its **own** static keypair so the pinned-`rs` continuity holds on the happy path, and (b) support a **different** static keypair for the continuity-fail path. Suggested shape (developer writes the body): retain the responder's static private key bytes at construction (via `getPrivateKey`), and add a helper that builds a *fresh* `HandshakeState(PROTO, RESPONDER)` with a chosen static key (default = own), reads the re-key `noise_init`, writes a `noise_resp` with **empty** early-data, `split()`s (responder mirror-swap), and returns the fresh responder `CipherStatePair`.

Scenarios (bullet = input → expectation; developer writes bodies in the file's idiom):

- **Re-key happy path + round-trip under new keys (AC #1, #2, #4-positive).** Establish; `init2 = session.writeRekeyInit(newPrivateKey())`; drive the responder re-key leg (same static) → `resp2`; `session.readRekeyResp(resp2)`. Then a transport round-trip both ways against the **new** responder pair succeeds (proves correct sender/receiver assignment on the new pair — a crossed swap MAC-fails here). Ciphertext length still `pt + 16`.
- **Old-key frame fails after swap (AC #4).** Keep the **pre-re-key** responder pair. After a successful re-key, a frame the old responder-sender sealed → `session.decrypt(...)` throws `NoiseSessionException` (the new receiver MAC-fails the stale frame).
- **Peer-static discontinuity retains old keys (AC #3).** Establish; `writeRekeyInit`; drive the responder re-key leg with a **different** static keypair → `resp2`; `session.readRekeyResp(resp2)` throws `NoiseSessionException`. **Then assert the old transport still works**: a round-trip against the *original* responder pair still succeeds (proves no swap, old keys retained). Then assert `pendingRekey` is cleared by re-trying `writeRekeyInit` successfully (re-key is retryable after failure).
- **Re-key noise_init carries empty early-data.** The responder's `readMessage` of `init2` recovers a **zero-length** payload (no `hello`, no token) — proves the original early-data is not re-sent.
- **Atomic-swap wipes old keys (AC #2).** After a successful re-key, the old `CipherStatePair` is destroyed — assert via the old-key-frame-fails test above (behavioural proof the old keys no longer decrypt); a direct `hasKey()`-after-`destroy` assertion is optional.
- **State guards (caller bugs → `IllegalStateException`).** `readRekeyResp` before any `writeRekeyInit`; `writeRekeyInit` twice without an intervening `readRekeyResp`; either after `close()`; `writeRekeyInit` before the session is established.

## Open questions

- **Where the pump re-loads `s`.** The future WS-pump/trigger ticket (1-hour timer + `rekey_request` dispatch; needs #301) re-loads `s` via `DeviceStaticKeyStore.loadOrCreate(serverId)` (serverId from `PairedServerStore`), passes it to `writeRekeyInit`, and zeroes the buffer — mirroring `NoiseSessionFactory.create()`. A `suspend fun NoiseSessionFactory.reloadDeviceStaticKey(): ByteArray` helper would DRY this, but has **no consumer until that ticket**, so per #298's evidence-based "no speculative seam" rule it is **not** added here.
- **`rekey_request` envelope.** The inbound `rekey_request` (binary-initiated nudge) and the 1-hour timer are the *triggers*, owned by the pump ticket. This ticket exposes only the mechanism they drive.
- **Outbound plaintext frame-size cap (65519 B) / inbound frame-size cap.** Unchanged from #298 — owned by the WS client (#276/#301), enforced before bytes reach this session. Not double-enforced on the re-key path.

## Sizing

**S — confirmed** (PO sized S; not overriding to XS — genuine crypto state-machine extension with a non-trivial re-key responder test leg and swap-and-retain semantics). Production source files: `NoiseIkSession.kt` (modified) = **1** (< 5). New exported types / public classes: **0** (two methods on the existing class; two private fields). Total written: ~70 production LOC (2 fields + `writeRekeyInit` ~15 + `readRekeyResp` ~30 + `close` +2 + KDoc ~20) + ~150 test LOC (TestResponder re-key leg + ~6 scenarios) ≈ **~220** (≤ ~600). Edit fan-out: constructor signature unchanged, two **new** methods with no existing callers (the test is the only consumer) = **0** (≤ 10). Error/reject branches in `readRekeyResp`: 3 (`BadPadding`, `ShortBuffer`, not-SPLIT) + 1 defensive in `writeRekeyInit` = **4** (< 10). No new dependency (noise-java vendored #272/ADR-0004; all crypto present). The factory and DI are **not** touched. Within every red line; ships as one ticket.

## Security review

**Verdict:** PASS

The asset is the phone's encrypted transport across a *re-key*: the device static private key `s` (re-obtained per re-key), the new and old transport CipherStates, and the confidentiality + integrity of traffic across the rotation. An adversary's re-key-specific goals: MITM the re-key to hijack an authenticated session, induce nonce reuse during the swap, widen the `s` RAM window, leave old keys readable after switchover, or force a re-key against a different/rotated server. The checklist was walked from the top against the final spec.

**Findings:**

- **[Trust boundaries]** The one untrusted→trusted crossing is `readRekeyResp(resp)` — attacker-controllable re-key `noise_resp` bytes from a possibly-hostile relay. It is **explicit and single-function**, mirroring #298's `readResp`. A valid re-key `noise_resp` is unforgeable without the pinned `rs`'s private half (IK `es`/`se` DH), so a relay-operator MITM cannot complete the re-key. **The fail-RETAIN semantics are the security-load-bearing choice**: a forged/failed re-key leaves the live (uncompromised) CipherStates in place and throws — it cannot down a working session into a re-handshake the attacker controls, nor leak old-key material. The old keys are wiped (`oldPair.destroy()`) only on a *successful* swap. No finding.
- **[Tokens / secrets]** SHOULD-FIX-class concern **resolved by design choice (B).** The device static key `s` is re-loaded per re-key and **never retained on the session** — only the pending re-key `HandshakeState` holds it, destroyed on `readRekeyResp`/`close`, so #298's bounded RAM window is preserved across every hourly re-key. Choosing option A (retain `s`) would have been a [Tokens/secrets] regression (long-term identity resident in RAM for the whole session); it was rejected. The caller-zeroes-`s`-after-`writeRekeyInit` contract (KDoc, § Design) mirrors `create()`'s existing discipline. The re-key `noise_init` carries **empty early-data** — the token is **not** re-sent (protocol § Re-key); the per-rekey identity gate is continuity of `s` (same keystore) + `rs` (pinned), matching #453. No token re-validation is needed or performed. No residual finding.
- **[File / storage]** N/A by design — this ticket performs no file/storage I/O. `s` arrives as a parameter; the keystore re-load is the pump ticket's concern, behind `DeviceStaticKeyStore` (#291, separately reviewed). No new at-rest artifact.
- **[Inter-process / Android surface]** N/A by design — the session stays portable and `android.*`-free; no exported component, deep link, `PendingIntent`, `ContentProvider`, or `WebView` is added. The re-key surface is two plain methods on an injected singleton.
- **[Cryptographic primitives]** No finding. The re-key is the same spike-proven `Noise_IK_25519_ChaChaPoly_BLAKE2s` via vendored `noise-java`; a *fresh* `HandshakeState` per re-key (Noise ephemerals are per-handshake), empty prologue and `ad = null` preserved. **Peer-static continuity (AC #3) is enforced cryptographically, not by a byte-compare**: the initiator re-keys against the *same pinned `rs`*, so a different/rotated server static simply fails the handshake — there is no attacker-controlled-value-vs-secret comparison on this path, hence no constant-time-compare obligation (the initiator mirror of #453's `bytes.Equal`, which #453 already justified as public-key-only). The new CipherStates' nonce counters reset to 0 (Noise guarantee), and old-key frames fail the new receiver's MAC — replay across the rotation is impossible.
- **[Network & I/O]** N/A here, owner named. No socket opened; re-key frame size caps are the WS client's (#276/#301), enforced before bytes reach this session — unchanged from #298. Re-key rate-limiting is the pump ticket's concern (the trigger cadence is defined there); the session mechanism imposes no loop. No finding.
- **[Error messages / logs / telemetry]** No finding. No `Log`/`Timber` added; `NoiseSessionException` messages on the re-key path are category strings only (no key bytes, no frame bytes); wrapped `BadPaddingException` carries no secret. No telemetry added.
- **[Concurrency]** No finding — this is the category the swap most stresses, and it is the strongest part of the design. The atomic swap and the wipe of the old pair execute inside the **same `@Synchronized` monitor** as `encrypt`/`decrypt`, so no mixed-key window and no nonce-reuse window can be observed by a concurrent same-direction op (AC #2). `state` stays `ESTABLISHED` throughout so transport never spuriously throws mid-re-key. No new coroutine/scope; no check-then-mutate on shared state outside the lock; a mid-re-key cancel wipes via `close()`.
- **[Threat model alignment]** Aligned with `protocol-mobile.md` § Re-key / § Security model and ADR 024. **Relay-operator MITM on re-key** is blocked (continuity via pinned `rs`; fail-RETAIN prevents session hijack) — the initiator mirror of #453's Threat #3 claim. **Downgrade** is blocked (hardcoded suite, fresh handshake, no negotiation). **Compromised-phone / leaked-Keystore-`s`** is unchanged from initial-handshake posture (a leaked `s` re-keys successfully because continuity passes — same key; not a new vector this ticket introduces). Mobile-specific threats (screenshot/overlay/accessibility/keyboard) are N/A — headless data-layer code, no UI, no input. Out of scope, named: re-key triggers + rate-limiting (pump ticket, needs #301), WS transport security (#276/#301), token lifecycle (#294/#277).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31
