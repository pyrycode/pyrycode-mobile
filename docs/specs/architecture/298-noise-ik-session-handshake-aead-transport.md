# Architecture: Noise_IK session — handshake + AEAD transport (initiator) (#298)

## Files to read first

- `app/src/main/java/com/southernstorm/noise/protocol/HandshakeState.java:147-219,486-554,760-931,958-1180` — constructor (suite-string parse, `NoSuchAlgorithmException`), `start()` (mixes the **empty** prologue at :513-516 — never call `setPrologue`), `writeMessage` (early-data is the trailing `payload` arg), `readMessage` (recovers early-data; `BadPaddingException` on MAC failure), `split()` (responder swaps; **initiator does not** — :1146-1147). The load-bearing call sequence.
- `app/src/main/java/com/southernstorm/noise/protocol/CipherStatePair.java:52-63` — `getSender()` / `getReceiver()`. The session assigns **sender = encrypt-outbound, receiver = decrypt-inbound** (the spike's "one high-risk line").
- `app/src/main/java/com/southernstorm/noise/protocol/CipherState.java:112,141` — `encryptWithAd(ad, pt, ptOff, ct, ctOff, len)` / `decryptWithAd(...)`. Pass **`ad = null`** on every transport op; ct length = pt + `getMACLength()` (16).
- `app/src/main/java/com/southernstorm/noise/protocol/DHState.java:80,99` — `setPublicKey(rs,0)` (remote static), `setPrivateKey(localPriv,0)` (local static). Both take raw 32-byte buffers.
- `app/src/main/java/de/pyryco/mobile/data/crypto/DeviceStaticKeyStore.kt:12-26` — `suspend fun loadOrCreate(serverId): DeviceStaticKeyPair`; `DeviceStaticKeyPair.privateKey` is the raw 32-byte local `s` to feed `setPrivateKey`. Throws `DeviceStaticKeyException` ("needs re-pair").
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt:18-51` — `suspend fun load(): PairedServer?`; `PairedServer.serverStaticPublicKey` (base64-std String → remote `rs`), `.token` (hello secret), `.serverId`. `load()` returns `null` when unpaired/corrupt.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:37-73` — `Envelope`, `HelloClientPayload` (the `noise_init` early-data; `toString()` already redacts `token`), `HelloAckPayload` (`connId`).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-61` — `MobileJson` (the only `Json` to use), `base64StdDecode`, and `decodeServerStaticPubkey`'s 32-byte-guard pattern (the factory mirrors it for the stored key).
- `app/src/main/java/de/pyryco/mobile/data/model/Session.kt:1-10` — confirms the data-layer datetime idiom is **`kotlinx.datetime.Instant`** (ADR 0001), not `java.time`. Use `kotlinx.datetime.Clock.System.now().toString()` for the envelope `ts`.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:26-43` — Koin module; the `single { … } bind …` idiom and the existing crypto-store bindings to slot next to.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSuiteSmokeTest.kt:20-33` — the existing JVM Noise test: `HandshakeState` runs in `test/` (pure-Java suite, no device). This ticket's test follows it, not the `androidTest/` Keystore tests.
- Vault doc **"Phase 4 — Noise Client Spike Findings"** (`second-brain`, `2026-05-02-pyrycode-mobile/phase-4-noise-client-spike-findings.md`) — § "noise-java IK initiator call sequence" and § "Proven wire contract (byte-accurate)". The byte oracle. (codegraph won't surface it — a markdown vault note.)
- `docs/specs/architecture/291-device-x25519-static-keypair-keystore.md` and `docs/specs/architecture/273-mobile-protocol-v2-wire-models-codec.md` — the two producers this ticket consumes; § Security review posture (no-secrets-in-logs) carries forward here.

## Context

Phase 4 chain — the core encrypted transport, de-risked end-to-end by the 2026-05-29 proving spike (a `noise-java` initiator interoperated with the Go `flynn/noise` responder against the live relay, zero byte-level debugging). This ticket wraps the vendored `noise-java` for `Noise_IK_25519_ChaChaPoly_BLAKE2s` as the **initiator** (the phone): drive the handshake, recover `conn_id`, split into transport ciphers, and expose AEAD encrypt/decrypt over byte arrays.

It consumes two already-landed producers: the device X25519 static keypair (#291, the local `s`) and the `PairedServer` record (#294, the remote `rs` + token). The wire models/codec (#273) frame the `hello` / `hello_ack` early-data.

Scope boundary (from the ticket): **byte-array in / byte-array out.** This ticket does **not** own the WebSocket, the `InnerFrameV2` outer framing + base64 of each frame, the `RemoteConversationRepository`, the application message set (all #276/#278), or the 1-hour rekey loop (#299). It produces/consumes **raw Noise frame bytes** and the established `conn_id`; #276 wraps those bytes in `InnerFrameV2(type, base64StdEncode(bytes))` and pumps them over the WS.

This is a data-layer transport primitive — **not UI-visible**, so there is correctly no `## Figma` / `## Design source` section (the ticket body has none; this is not a PO gap — same shape as #291).

## Design

### Placement & portability

New code lands in `de.pyryco.mobile.data.network` (`app/src/main/java/de/pyryco/mobile/data/network/`) — the transport package, sibling to the wire codec (#273) it sits on and the WS client (#276) that will hold it. (`data/crypto` is for *at-rest key custody*; this is *in-flight transport crypto*.) The existing `NoiseSuiteSmokeTest` already lives here, confirming the team's mental placement.

**The session and factory stay fully portable — no `android.*` imports.** They operate on byte arrays + `String`s, lean on the pure-Java vendored `noise-java`, the portable store interfaces, and `MobileJson` + `kotlinx.datetime`. The only Android-bound values — the device name and client version in the `hello` — are resolved at the Koin module (which is Android-bound) and injected as plain strings via `NoiseClientInfo`. This keeps the CMP walk-back trigger satisfied (`data/` portable).

### Types (2 new production files)

**`NoiseIkSession.kt`** — the session plus its two small companions:

```kotlin
// The Android-resolved hello identity, injected so the session stays android-free.
data class NoiseClientInfo(val deviceName: String, val clientVersion: String)

// Any handshake / transport / setup failure. Message names the CATEGORY only —
// never key material, plaintext, token, or raw bytes (see Error handling).
class NoiseSessionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class NoiseIkSession(
    localStaticPrivateKey: ByteArray,   // raw 32-byte device s   (#291)
    remoteStaticPublicKey: ByteArray,   // raw 32-byte server rs   (#294)
    token: String,                      // hello secret            (#294)
    clientInfo: NoiseClientInfo,
) {
    fun writeInit(): ByteArray          // raw noise_init frame bytes (hello as early-data)
    fun readResp(resp: ByteArray): String   // recover hello_ack; returns conn_id; → established
    val connId: String                  // established conn_id (throws if not yet established)
    fun encrypt(plaintext: ByteArray): ByteArray   // → ciphertext (plaintext + 16)
    fun decrypt(ciphertext: ByteArray): ByteArray  // → plaintext  (ciphertext − 16)
    fun close()                         // wipe transport ciphers; idempotent
}
```

**`NoiseSessionFactory.kt`** — resolves the crypto inputs and constructs a ready-to-handshake session:

```kotlin
class NoiseSessionFactory(
    private val deviceStaticKeyStore: DeviceStaticKeyStore,
    private val pairedServerStore: PairedServerStore,
    private val clientInfo: NoiseClientInfo,
) {
    suspend fun create(): NoiseIkSession   // throws NoiseSessionException if setup fails
}
```

Exported types: `NoiseIkSession`, `NoiseSessionFactory`, `NoiseSessionException`, `NoiseClientInfo` = **4**. (No `NoiseSession` interface is introduced — there is no consumer faking it yet; #276 extracts one when it needs to. Evidence-based: don't ship a speculative seam.)

### Session lifecycle (the state machine)

```
NEW ──writeInit()──▶ AWAITING_RESP ──readResp()──▶ ESTABLISHED ──close()──▶ CLOSED
                                                      ▲   │
                                              encrypt/decrypt (stay ESTABLISHED)
```

- **Constructor** builds the `HandshakeState("Noise_IK_25519_ChaChaPoly_BLAKE2s", INITIATOR)`, `localKeyPair.setPrivateKey(localStaticPrivateKey, 0)`, `remotePublicKey.setPublicKey(remoteStaticPublicKey, 0)`, then `start()` — **never `setPrologue`** (empty prologue is load-bearing; `start()` mixes the empty prologue at `HandshakeState.java:513-516`). Doing setup here (not lazily) means the raw private key is copied into the DH state immediately, shrinking its RAM window (see Error handling → key hygiene). `require` both input keys are 32 bytes; wrap `NoSuchAlgorithmException` (defensive — vendored suite is present) as `NoiseSessionException`.
- **`writeInit()`** (requires NEW): build the `hello` envelope (below), `handshake.writeMessage(buf, 0, helloBytes, 0, helloBytes.size)` into a buffer sized `helloBytes.size + 96` (IK msg1 overhead = 32-byte `e` + 48-byte encrypted `s` + 16-byte payload MAC), return `buf.copyOf(n)`. → AWAITING_RESP.
- **`readResp(resp)`** (requires AWAITING_RESP): `handshake.readMessage(resp, 0, resp.size, ackBuf, 0)` into a buffer sized `resp.size`; `check(handshake.action == SPLIT)`; `val pair = handshake.split()`; assign `sender = pair.sender` (encrypt-out), `receiver = pair.receiver` (decrypt-in); then `handshake.destroy()` (wipes the device-key copy + handshake secrets — the split cipher pair is independent). Parse the recovered `hello_ack` (below) → `connId`. → ESTABLISHED. Returns `connId`. A MAC failure here (`BadPaddingException` — wrong `rs`, wrong suite, tampered msg2) wraps as `NoiseSessionException` (maps to server close `4426`).
- **`encrypt(pt)`** (requires ESTABLISHED): `sender.encryptWithAd(null, pt, 0, ct, 0, pt.size)` into `ct = ByteArray(pt.size + 16)`; return it. **`ad = null`** always.
- **`decrypt(ct)`** (requires ESTABLISHED): guard `ct.size >= 16` else `NoiseSessionException`; `receiver.decryptWithAd(null, ct, 0, pt, 0, ct.size)` into `pt = ByteArray(ct.size)`; return `pt.copyOf(ptLen)`. A `BadPaddingException` (tampered/crossed frame) wraps as `NoiseSessionException`.
- **`close()`**: destroy the `CipherStatePair` (and the `HandshakeState` if still held, e.g. closed before `readResp`); idempotent; subsequent ops throw `IllegalStateException`.

**Wrong-order calls (`writeInit` twice, `encrypt` before established, etc.) throw `IllegalStateException`** — these are caller bugs, distinct from `NoiseSessionException` (runtime protocol/crypto failures). Reconnect is **not** resumable: #276 discards a failed/closed session and calls `factory.create()` for a fresh handshake (Noise ephemerals are per-handshake).

### `hello` construction and `hello_ack` recovery

- **hello** (early-data in `noise_init`): `HelloClientPayload(deviceName = clientInfo.deviceName, clientVersion = clientInfo.clientVersion, token = token)` (`role` + `protocolVersions` default to `"client"` / `["v2"]`), wrapped as `Envelope(id = 1L, type = "hello", ts = Clock.System.now().toString(), payload = MobileJson.encodeToJsonElement(hello))`, then `MobileJson.encodeToString(envelope).toByteArray(Charsets.UTF_8)`. **Always `MobileJson`**, never a fresh `Json {}` (defaulted-field omission is wire-breaking — see #273).
- **hello_ack** (early-data in `noise_resp`): `MobileJson.decodeFromString<Envelope>(String(ackBuf, 0, ackLen, Charsets.UTF_8))`; require `type == "hello_ack"`; `MobileJson.decodeFromJsonElement<HelloAckPayload>(envelope.payload)`; `connId = it.connId`. Any decode/shape failure (`SerializationException`, wrong type, missing `conn_id`) → `NoiseSessionException` ("malformed hello_ack" — no payload bytes in the message).

The `Envelope.payload_encrypted` open seam from #273 is **resolved as: not here.** This ticket never constructs an application `noise_msg` envelope (its transport surface is raw byte arrays); `payload_encrypted` belongs with the application message set (#278) that builds those envelopes. Adding the field here would be speculative and untested.

### Factory `create()`

`withContext(Dispatchers.IO)`:
1. `val ps = pairedServerStore.load() ?: throw NoiseSessionException("not paired")`.
2. `val rs = base64StdDecode(ps.serverStaticPublicKey)`; `require(rs.size == 32)` else `NoiseSessionException("invalid server static key")` (no bytes echoed) — defense-in-depth mirroring `decodeServerStaticPubkey`; a wrong-length `rs` would corrupt the handshake.
3. `val kp = deviceStaticKeyStore.loadOrCreate(ps.serverId)` — may generate on first connect; `DeviceStaticKeyException` wraps as `NoiseSessionException` (cause preserved).
4. `NoiseIkSession(kp.privateKey, rs, ps.token, clientInfo)`, then (defense-in-depth) zero `kp.privateKey` — the constructor has already copied it into the DH state.

`create()`'s single failure type is `NoiseSessionException` (cause chain preserved) — one thing for #276 to catch to surface "re-pair / retry". It does **not** open a socket; that's #276.

### DI wiring (`AppModule.kt`)

Two additions (Android-bound values resolved here so the session stays portable):

```kotlin
single { NoiseClientInfo(deviceName = Build.MODEL, clientVersion = BuildConfig.VERSION_NAME) }
single { NoiseSessionFactory(get(), get(), get()) }   // DeviceStaticKeyStore, PairedServerStore, NoiseClientInfo
```

`get()`s resolve the already-registered crypto stores. No consumer cascade — #276 doesn't exist yet (edit fan-out = 1 file).

## State + concurrency model

- Not a ViewModel — no `StateFlow`/`UiState`/`Event`. Plain synchronous byte ops on a session object + one `suspend` factory method.
- **Dispatcher.** `factory.create()` wraps the store I/O (`load`, `loadOrCreate` — Keystore/DataStore) in `withContext(Dispatchers.IO)`. The session's own `writeInit`/`readResp`/`encrypt`/`decrypt` are synchronous, CPU-bound, fast; the caller (#276) invokes them on its own message-pump dispatcher.
- **Thread-safety + the nonce-reuse safety net.** The `sender` and `receiver` cipher states each hold a **monotonic nonce counter**; two concurrent calls on the *same* direction could read-then-increment racily → **nonce reuse**, which for ChaCha20-Poly1305 is silent and catastrophic (keystream reuse → confidentiality loss). The expected usage is a single WS message pump (one outbound, one inbound at a time), so concurrency *shouldn't* happen — but a doc-only contract is a single stochastic layer guarding a catastrophic, silent failure. Per the belt-and-suspenders principle, pair it with a **deterministic** net: guard the body of `encrypt` and of `decrypt` with the session's intrinsic lock (`@Synchronized`, non-suspend, ~free on the uncontended single-pump path). A contract violation then degrades to harmless serialization instead of nonce reuse. `encrypt` and `decrypt` use different cipher objects, so this never deadlocks and outbound/inbound stay independent. Still document the single-pump expectation in KDoc — the lock is the floor, not a license to fan out.
- **Shutdown / cancellation.** No long-lived jobs. `factory.create()` is cancellation-cooperative (the suspend store calls are). The session holds no coroutine; #276 owns the lifecycle and calls `close()` on disconnect/screen-exit to wipe key material. A handshake abandoned mid-flight (cancelled before `readResp`) leaks nothing durable — `close()` (or GC + `HandshakeState` being `Destroyable`) wipes the in-RAM device-key copy.

## Error handling

| Failure | Source | Surfaced as |
|---|---|---|
| Not paired | `pairedServerStore.load()` → `null` | `NoiseSessionException("not paired")` |
| Stored server key not 32 bytes / bad base64 | `base64StdDecode` / `require` | `NoiseSessionException("invalid server static key")` |
| Device key undecryptable (needs re-pair) | `DeviceStaticKeyException` | `NoiseSessionException` (cause preserved) |
| Suite/algorithm missing | `NoSuchAlgorithmException` | `NoiseSessionException` (defensive) |
| Handshake MAC failure (wrong `rs`/suite/tampered msg2) | `readMessage` → `BadPaddingException` | `NoiseSessionException` (server close `4426`) |
| Malformed `hello_ack` (bad JSON / wrong type / no `conn_id`) | decode | `NoiseSessionException("malformed hello_ack")` |
| Transport MAC failure (tampered/crossed/AD-mismatched frame) | `decryptWithAd` → `BadPaddingException` | `NoiseSessionException` |
| Wrong call order (`encrypt` pre-establish, double `writeInit`, use-after-`close`) | state guard | `IllegalStateException` (caller bug) |

**Key hygiene (carries #291's "no key material in logs" forward).** No `Log`/`Timber` anywhere in the session or factory; no key material, plaintext, token, raw frame, or `conn_id`-adjacent secret in any `NoiseSessionException` message (category strings only). `HelloClientPayload.toString()` already redacts the token. **`NoiseIkSession` must stay a plain `class`, never a `data class`** — it retains `token` as a field until `writeInit`, and a `data class`'s generated `toString()` would print it to any crash frame / stray `Log.d("$session")`. A plain class's identity `toString()` (`NoiseIkSession@hash`) leaks nothing; `NoiseClientInfo` carries no secret so its `data class` `toString` is fine. Key lifecycle: raw private key → copied into the DH state in the constructor → factory zeros its input buffer → `readResp` calls `handshake.destroy()` post-`split()` (wipes the device-key copy + handshake secrets) → only the `CipherStatePair` (transport keys) survives → `close()` destroys it. This bounds the device-static-key RAM window to the handshake, not the whole session.

## Testing strategy

JVM unit test (`app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt`, `./gradlew test`) — `noise-java` is pure-Java and the session takes raw bytes (no `AndroidKeyStore`), so the whole lifecycle runs without a device, like `NoiseSuiteSmokeTest`. The factory's store I/O is not unit-tested here (the stores have their own `androidTest`); the factory is thin wiring (a light JVM test with faked `DeviceStaticKeyStore`/`PairedServerStore` is optional — note as such).

**In-test responder harness** (the Go server's mirror): a helper builds `HandshakeState(PROTO, RESPONDER)`, `localKeyPair.generateKeyPair()`, exposes its public key as the session's `remoteStaticPublicKey`, `start()`s, reads msg1, and writes a `hello_ack` envelope (known `conn_id`) as msg2 early-data, then `split()`s (noise-java swaps for the responder, so `responderPair.receiver` decrypts the initiator's outbound). A second helper mints a raw 32-byte device private key via `Noise.createDH("25519").generateKeyPair()` → `getPrivateKey`.

Scenarios (developer writes bodies in the project idiom — bullet = input → expectation):

- **Full handshake + conn_id (AC #1).** Drive `writeInit` → responder `readMessage` → responder `hello_ack(conn_id="conn-xyz")` → `readResp`. Assert returned `connId == "conn-xyz"` and `session.connId == "conn-xyz"`.
- **Transport round-trip both ways (AC #2).** `ct = session.encrypt(pt)`; `responderPair.receiver.decryptWithAd(null, ct…)` equals `pt`. `rct = responderPair.sender.encryptWithAd(null, reply…)`; `session.decrypt(rct)` equals `reply`. (This round-trip *is* the positive proof of the correct `sender`/`receiver` assignment — a crossed session would MAC-fail here.)
- **Ciphertext length (AC #2).** `session.encrypt(pt).size == pt.size + 16` for a few `pt` sizes including empty.
- **Crossing fails (AC #3).** `responderPair.sender.decryptWithAd(null, session.encrypt(pt)…)` raises `BadPaddingException` — the initiator's outbound pairs with the responder's *receiver*, not sender.
- **Non-empty AD fails (AC #3).** `responderPair.receiver.decryptWithAd(byteArrayOf(1,2,3), session.encrypt(pt)…)` raises `BadPaddingException` — proves empty/null AD is load-bearing.
- **Non-empty prologue fails (AC #3).** A raw initiator `HandshakeState` with `setPrologue(nonEmpty)` (responder empty) → responder `readMessage(msg1)` raises `BadPaddingException` — proves the session's no-prologue choice is load-bearing. (Library-level guard justifying the contract; the session itself never sets a prologue.)
- **Wrong server static key → clean failure.** Construct the session with an `rs` ≠ the responder's actual static; `readResp` raises `NoiseSessionException` (not a raw library exception).
- **Malformed hello_ack → clean failure.** Responder sends an envelope with `type != "hello_ack"` (or missing `conn_id`); `readResp` raises `NoiseSessionException`.
- **State guards.** `encrypt`/`decrypt` before `readResp`, and `writeInit` twice, raise `IllegalStateException`; ops after `close()` raise `IllegalStateException`.

## Open questions

- **`device_name` source.** Spec uses `Build.MODEL` (e.g. "Pixel 7") for the Phase-4 scaffold; a user-settable device name is a later settings concern. `client_version` = `BuildConfig.VERSION_NAME`. Both injected via `NoiseClientInfo`; no behavioural dependency on their exact values (server stores them for display).
- **Plaintext frame-size cap (65519 B).** The spike notes a per-frame plaintext cap. Per #273 this is the WS layer's (#276) responsibility, enforced before bytes reach/leave this session; not double-enforced here. A defensive `require(plaintext.size <= 65519)` in `encrypt` is a cheap option if #276 prefers it upstream — deferred to #276 (evidence-based: no observed failure).
- **Rekey (#299).** The 1-hour `rekey_request` loop will operate on the established `CipherStatePair`; the session deliberately exposes no rekey op yet. #299 adds it on this seam.
- **`NoiseSession` interface.** Deferred to #276, which will want to fake the session for its WS-client tests. One-line extract when a consumer exists.

## Sizing

S — confirmed (PO sized S; not overriding to XS — genuine handshake state-machine + a non-trivial responder-mirror test). Production source files: `NoiseIkSession.kt` (new), `NoiseSessionFactory.kt` (new), `AppModule.kt` (2 lines) = **3** (< 5). New exported types: `NoiseIkSession`, `NoiseSessionFactory`, `NoiseSessionException`, `NoiseClientInfo` = **4** (≤ 5). Total written ≈ 60 (types + companions) + ~130 (session impl) + ~30 (factory) + ~3 (DI) + ~180 (test incl. responder harness) ≈ **400** (≤ ~600). Edit fan-out: one DI call site, no rename, greenfield consumer (#276 doesn't exist) = **1** (≤ 10). Error/reject branches: 8 (table above), < 10. No new dependency (noise-java vendored #272/ADR-0004; kotlinx-serialization #272; kotlinx-datetime ADR-0001; coroutines present) → no `libs.versions.toml` / `build.gradle.kts` / `settings.gradle.kts` edit. Within every red line; ships as one ticket.

## Security review

**Verdict:** PASS

The asset is the phone's encrypted transport to the daemon: the device static private key (`s`, phone identity), the server static key (`rs`, trust anchor), the bearer token (in the `hello`), and the confidentiality + integrity of all application traffic. An adversary's goals: impersonate the phone, MITM the untrusted relay, weaken/downgrade the handshake, leak the token, or read plaintext. Two SHOULD-FIX findings were folded into the spec before this verdict (the nonce-reuse safety net and the `data class` token-leak guard); the checklist was then re-walked from the top.

**Findings:**

- **[Trust boundaries]** The one untrusted→trusted crossing is `readResp(resp)`: attacker-controllable msg2 bytes from a possibly-hostile relay. It is **explicit and single-function**. The recovered `hello_ack` (and its `conn_id`) is trusted *only because* `readMessage` succeeds, which in Noise_IK requires the responder to hold the server static private key (the `es`/`se` DH) — a MITM without `rs`'s private half cannot forge a valid msg2 (MAC fails → `NoiseSessionException` → server close `4426`). The recovered JSON stays untrusted until `MobileJson` decode + `type == "hello_ack"` + `conn_id` presence checks; malformed → `NoiseSessionException`. The stored `rs` is re-validated to exactly 32 bytes before `setPublicKey` (mirrors #273's `decodeServerStaticPubkey`). No finding.
- **[Tokens / secrets]** SHOULD FIX **folded in.** The token is never generated/stored here (#294/#277 own that); it is read from `PairedServer.token`, placed inside the *encrypted* `hello` early-data (never a plaintext header — correct v2), and never logged. Surfaced footgun: `NoiseIkSession` retains `token` as a field until `writeInit`, so it **must stay a plain `class`** — a `data class`'s auto-`toString` would leak it to crash frames (folded into § Key hygiene). `HelloClientPayload.toString()` already redacts. Revocation = server-side close `4401`, handled by #276. No residual finding.
- **[File / storage]** N/A by design — this ticket performs **no** file/storage I/O. It reads through the `DeviceStaticKeyStore`/`PairedServerStore` interfaces, which own their app-private DataStore + Keystore custody (reviewed in #291/#294). No path concatenation (`serverId` is a store-internal map key, never a filesystem path here), no TOCTOU, no new at-rest artifact, no backup surface added.
- **[Inter-process / Android surface]** N/A by design — the session and factory are portable, `android.*`-free injected singletons. No exported `Activity`/`Service`/`Receiver`, no `<intent-filter>`, no deep link, no `PendingIntent`, no `ContentProvider`, no `WebView`. The only Android reads (`Build.MODEL`, `BuildConfig.VERSION_NAME`) happen at the Koin module, are not attacker-controlled, and carry no secret.
- **[Cryptographic primitives]** SHOULD FIX **folded in.** Standard, spike-proven `Noise_IK_25519_ChaChaPoly_BLAKE2s` via the vendored, reviewed `noise-java` (#272/ADR-0004); no hand-rolled crypto. All randomness (ephemeral keypair, device key) is the library's / #291's `SecureRandom` — no `kotlin.random.Random` on any security path. The suite string is hardcoded → **no negotiation, no downgrade surface**. The transport's catastrophic+silent failure mode — **nonce reuse under concurrent same-direction ops** — is now backstopped by a deterministic `@Synchronized` guard (§ State + concurrency), not just the single-pump doc contract. Empty prologue and `ad = null` are enforced and asserted by the AC-#3 negative tests. No secret-equality path exists (the `type` compare is against a public constant; the token is validated server-side), so no constant-time-compare obligation. No finding.
- **[Network & I/O]** N/A here, with a named owner. This ticket opens no socket; TLS, cert pinning, timeouts, and the **inbound WS frame-size cap** are #276's (per #273's explicit layering). Residual: the session allocates buffers proportional to its input (`ackBuf = resp.size`, `pt = ct.size`), so an uncapped oversized inbound frame is a memory-pressure vector — **OUT OF SCOPE, owner #276** (it caps inbound frames before bytes reach this session). A defensive outbound plaintext cap (65519 B) is likewise deferred to #276 (§ Open questions). Replay is inherently resisted by the monotonic transport nonce (we never call `setNonce` or reset a `CipherState`).
- **[Error messages / logs / telemetry]** No `Log`/`Timber` in the session or factory; `NoiseSessionException` messages are category-only (no key bytes, plaintext, token, or frame); wrapped causes (`BadPaddingException`, `DeviceStaticKeyException`) carry no secrets (#291 guarantees the latter). No telemetry/analytics added. Nothing leaks to Logcat in release (no logging at all). No finding.
- **[Concurrency]** `factory.create()` launches no coroutine (runs in the caller's scope via `withContext(IO)`, cancellation-cooperative); the session owns no scope. The sole shared-mutable-state hazard is the per-direction cipher nonce, now guarded deterministically (above). No `StateFlow` (not a ViewModel) → no check-then-mutate flow hazard. `close()` wipes ciphers; a mid-handshake cancel leaves nothing durable. No finding.
- **[Threat model alignment]** Aligned with `protocol-mobile.md` § Security model / ADR 024: this is exactly the initiator leg that lets the relay stay **untrusted and content-blind**. Relay-operator MITM is blocked (responder authenticated via the QR-pinned `rs`; phone authenticated via the #291 `s`); downgrade is blocked (hardcoded suite, fixed `protocol_versions`). Mobile-specific threats (screenshot/overlay/accessibility/keyboard-logging/deep-link) are **N/A** — headless data-layer code, no UI, no input fields, no deep links; the token is not entered here. Out of scope, named: WS transport security (#276), token lifecycle (#294/#277), rekey (#299), application-layer message ordering (#278).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31
