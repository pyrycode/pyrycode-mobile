# Spec — parse scanned QR payload → `PairedServer` + persist (replaces #295 stub) (#320)

**Size:** S (architect-confirmed; PO sized S).

- **Production files:** 4 (1 new, 3 modified) — under the ≥5 split gate.
  - new `data/network/PairingPayloadParser.kt`
  - modify `data/network/MobileWireCodec.kt` (add the outer base64url-no-pad decoder)
  - modify `ui/onboarding/ScannerViewModel.kt` (add one `ScannerEvent` + one `when` branch)
  - modify `MainActivity.kt` (rewire the `Decoded → stubPairAndNavigate` binding)
- **New exported top-level symbols:** 3 (`decodeBase64UrlNoPad`, `parsePairingPayload`, `PairingParseResult`) — under ≤5. Plus one member added to the existing `ScannerEvent` sealed interface (not a new top-level type).
- **Edit fan-out:** none. `QrPayload` and `PairedServer` are unchanged; no signature change cascades. The new `ScannerEvent.PairingFailed` forces one new branch in the VM's `when (event)` (the compiler flags it). No consumer cascade.

**Status:** ready for development.

**Depends on:** nothing un-landed. Consumes `QrPayload` + `MobileJson` + `decodeServerStaticPubkey` (#273, on `main`), `PairedServer` + `PairedServerStore` + `PairedServerStoreException` (#294, on `main`, Koin-bound), and the `ScannerUiState.Error` / `Decoded` surface (#326/#333/#334, on `main`).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2

Locked scanner viewport ("Pair with pyrycode" — corner reticle + sweep line over the camera box, a `scrim`-backed `pyry pair` hint card, and a bottom `TextButton` "Trouble scanning? Paste the pairing code instead"). **This slice adds no new visual surface:** a successful scan reuses this viewport unchanged and then navigates to the channel list (existing transition); a parse/validation failure reuses the existing in-screen `ScannerErrorContent` (message + paste affordance, built by #326 — which has *no dedicated Figma node*; it is the deliberate minimal recovery surface). The Figma anchor exists only to keep us from inventing a new error affordance — do not add a dialog, snackbar, or "rescan" button.

## Files to read first

The developer's turn-1 data load. Page these in deliberately; do not grep for them.

- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:75-92` — `QrPayload` (`server`, `relay`, `token`, `@SerialName("server_static_pubkey") serverStaticPubkey`). **Do NOT re-model it.** Note its redacting `toString()` already masks `token` (the AC4 deterministic net).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` (whole file, 62 lines) — `MobileJson` (the configured `Json`; `ignoreUnknownKeys = true` is why extra fields are tolerated), `base64StdDecode` (inner-key alphabet — **do NOT use it for the outer wrapper**), and `decodeServerStaticPubkey(qr)` (base64-**std** + exactly-32-byte check; throws `IllegalArgumentException` with a byte-safe message). This is where you add the new `decodeBase64UrlNoPad`.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt:19-58` — `PairedServerStore` interface (`suspend fun save(record)` throws `PairedServerStoreException`; `load()` never throws), the `PairedServer` data class (note its `toString()` shows only `serverId`), and `PairedServerStoreException`. The mapping target.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerViewModel.kt` (whole file, 75 lines) — `ScannerUiState` (`Decoded(payload)`, `Error(message)`; both `Decoded` and `QrDecoded` redact `payload` in `toString()`), `ScannerEvent` (`CameraError(message) → Error(message)` is the existing pattern you mirror), and the pure-synchronous `onEvent` `when`. Add `PairingFailed(message)` here.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:141-219` + `:368-396` — the `Routes.SCANNER` composable: `stubPairAndNavigate`, the `LaunchedEffect(state) { if (Decoded) stubPairAndNavigate() }` binding you replace, `onPasteCode = stubPairAndNavigate` (leave this — paste is out of scope), and the `STUB_PAIRED_SERVER` const (leave it; the paste path still uses it). `koinInject<PairedServerStore>()` is already wired at `:143`.
- `app/src/main/java/de/pyryco/mobile/data/network/OkHttpRelayTransport.kt:84-110` — `buildRequest()`: it scheme-converts `wss://→https://` / `ws://→http://` then appends `/v1/client` to `relayUrl`. **This is why `relayUrl` is an origin, not a full path**, and pins the relay-validation contract below. Read before deciding relay strictness.
- `app/src/test/java/de/pyryco/mobile/data/network/MobileWireCodecTest.kt:1-40` — JUnit4 idiom (`org.junit.Assert.*`, `assertThrows`, fixture-string style). Mirror it for the codec + parser tests.
- `app/src/test/java/de/pyryco/mobile/ui/onboarding/ScannerViewModelTest.kt` (whole file) — the `cameraError_movesToErrorCarryingMessage` test is the template for the new `PairingFailed → Error` case.
- Memory: the two-alphabet trap — outer wrapper is base64**url**-no-pad (Go `base64.RawURLEncoding`, pyrycode #211/#432); inner `server_static_pubkey` is base64-**std**-with-padding. Reject **trailing data after the JSON object**, but **tolerate unknown/extra fields** (both peers do).

## Context

Phase 4 / pairing. A live QR scan decodes to `ScannerUiState.Decoded(payload)` (#333 decode pipeline, #334 camera binding). Today `MainActivity`'s `LaunchedEffect(state) { if (Decoded) stubPairAndNavigate() }` **ignores the payload** and persists a throwaway `STUB_PAIRED_SERVER`. This slice closes the loop: parse + validate the decoded string into a real `PairedServer` and persist it, replacing the stub write **at the `Decoded` binding only**.

In scope: the outer QR-string transport-wrapper decode (base64url-no-pad — #277's named concern, split into #320), the `QrPayload → PairedServer` mapping, the persist, and failure routing into the existing `Error` surface.

Out of scope (do not build): the fingerprint / safety-number confirm gate (**#321**), the handshake (**#302 + #309**), and the "Paste the pairing code" text-entry path (no paste-input UI exists; the paste button keeps running the stub — a real paste path is a separate ticket). The decoded-JSON schema is **not** redefined — `QrPayload` is the contract.

## Design

### Component placement

The wrapper-decode + map is pure Kotlin, `data/`-portable (no `android.*`; `java.util.Base64` / `java.net.URI` only — the same JVM-stdlib bar the codec already uses). It consumes `QrPayload`/`MobileJson`/`decodeServerStaticPubkey` (`data/network`) and produces `PairedServer` (`data/crypto`) — and `data/network` already depends on `data/crypto` (`OkHttpRelayTransport` holds a `PairedServer`), so the home is **`data/network/PairingPayloadParser.kt`**. Putting it in `data/crypto` would invert the layer dependency; don't.

The base64url decoder is a transport-encoding primitive and belongs next to `base64StdDecode` in `MobileWireCodec.kt` — co-locating both alphabet decoders in one file makes the two-alphabet trap visible to the next reader.

### Outer base64url-no-pad decoder (in `MobileWireCodec.kt`)

```kotlin
/** Base64-decode [data] with the URL-safe alphabet, no padding (Go `base64.RawURLEncoding`).
 *  The OUTER QR-string wrapper only — NOT for `server_static_pubkey` (that is base64-std; use
 *  [base64StdDecode]). Throws [IllegalArgumentException] on a non-base64url character. */
fun decodeBase64UrlNoPad(data: String): ByteArray
```

- Implement with `Base64.getUrlDecoder().decode(data)`. The URL-safe decoder rejects the standard-alphabet `+`/`/` (the trap), which is the load-bearing rejection. It tolerates optional `=` padding — acceptable: real `RawURLEncoding` output is unpadded so it round-trips, and we have no observed failure mode from padding-lenience. Do **not** hand-roll a stricter no-pad check.

### `parsePairingPayload` + result type (in `PairingPayloadParser.kt`)

```kotlin
sealed interface PairingParseResult {
    data class Success(val server: PairedServer) : PairingParseResult
    /** [reason] is a fixed byte-safe category label for logging only — never a field VALUE,
     *  never the user-facing copy (the UI maps all failures to one fixed string). */
    data class Failure(val reason: String) : PairingParseResult
}

/** Decode the scanned outer wrapper → [QrPayload] → validate → [PairedServer]. Pure, synchronous,
 *  no I/O, no persist. Any malformed input yields [PairingParseResult.Failure]; never throws. */
fun parsePairingPayload(scanned: String): PairingParseResult
```

Pipeline (first failure wins; each `Failure.reason` is a fixed constant, listed in Error handling):

1. `decodeBase64UrlNoPad(scanned)` → bytes. Catch `IllegalArgumentException` → `Failure("bad-encoding")`.
2. `String(bytes, Charsets.UTF_8)` → json (this constructor substitutes, never throws; malformed UTF-8 falls through to a JSON-parse failure).
3. `MobileJson.decodeFromString<QrPayload>(json)`. Catch `kotlinx.serialization.SerializationException` → `Failure("malformed-json")`. This single catch covers non-JSON, **trailing bytes after the top-level object** (kotlinx requires EOF after the value — unlike Go's `json.Unmarshal`, so no double-decode idiom is needed; the explicit test below proves it), **and absent required fields** (`MissingFieldException ⊂ SerializationException`).
4. Empty-string field check: `server`, `relay`, `token` each `isNotBlank()` → else `Failure("missing-field")`. (Absent fields already fail at step 3; this catches present-but-`""`.)
5. Relay validation — `Failure("invalid-relay")` unless **all** hold: `java.net.URI(relay)` parses (catch `URISyntaxException`), `uri.scheme?.lowercase()` ∈ `{"ws","wss"}`, and `uri.host` is non-empty. See "relay strictness" below.
6. `decodeServerStaticPubkey(qr)` (validates base64-std + exactly 32 bytes). Catch `IllegalArgumentException` → `Failure("invalid-server-key")`. Discard the returned bytes — `PairedServer` stores the base64 **string**; this call is validation-only.
7. `Success(PairedServer(serverId = qr.server, token = qr.token, relayUrl = qr.relay, serverStaticPublicKey = qr.serverStaticPubkey))`.

**Field mapping (pin exactly):** `QrPayload.server → PairedServer.serverId`; `.token → .token`; `.relay → .relayUrl` (verbatim — the transport appends `/v1/client`); `.serverStaticPubkey → .serverStaticPublicKey` (the base64-std string, unchanged).

### Relay strictness (the decision #320 asked the architect to make)

`OkHttpRelayTransport.buildRequest()` treats `relayUrl` as an **origin** (`wss://host[:port]`) and appends `/v1/client`. pyrycode #211 emits `relay` as a trust-on-first-use origin and does **not** validate it server-side. Decided contract for #320: **parseable `java.net.URI` + scheme ∈ {ws, wss} + non-empty host.** Rationale:

- This rejects the garbage AC2 names (non-URL, `http://…`, missing host) without inventing path/port constraints the protocol doesn't specify.
- Validating with `java.net.URI` (not `okhttp3.HttpUrl`) keeps the parser free of the OkHttp dependency and `data/`-portable, matching the codec's existing `java.util.Base64` usage. `android.net.Uri` is forbidden here (it's `android.*`).
- Do **not** reject a present path/query/port — staying close to the Go emitter's opaque-origin treatment avoids false rejections of legitimately-shaped relays. (Path-shape nuance flagged in Open Questions.)

### MVI wiring — where the parse runs

The VM stays a **pure synchronous state machine** (its existing contract: no `viewModelScope`, no Android types). The persist (`suspend save`) + navigation already live in the `MainActivity` `Routes.SCANNER` composable, so the orchestration stays there too — matching the existing `stubPairAndNavigate` structure rather than splitting parse-into-VM / persist-into-composable.

Replace the `Decoded` `LaunchedEffect` body (keep `stubPairAndNavigate`, `STUB_PAIRED_SERVER`, and `onPasteCode = stubPairAndNavigate` untouched for the out-of-scope paste path):

- `if (state is Decoded)` → `when (parsePairingPayload(state.payload))`:
  - `Success` → `try { pairedServerStore.save(server); navController.navigate(CHANNEL_LIST){ popUpTo(SCANNER){inclusive=true}; launchSingleTop=true } } catch (PairedServerStoreException) { Log.w(TAG, "…${e.javaClass.simpleName}"); vm.onEvent(ScannerEvent.PairingFailed(SAVE_FAILED_MSG)) }`
  - `Failure` → `Log.w(TAG, "pairing parse failed: ${result.reason}"); vm.onEvent(ScannerEvent.PairingFailed(PARSE_FAILED_MSG))`

The parse is microsecond CPU work on a small string — run it inline on the `LaunchedEffect`'s Main coroutine; no `withContext(Dispatchers.Default)` warranted.

Re-fire safety (unchanged invariant): on `Success`, `popUpTo(SCANNER){inclusive=true}` pops the destination off the back stack so the effect can't re-fire; the analyzer emits one payload per scan (`ScannerEvent.QrDecoded` contract). On failure, state flips `Decoded → Error`; the `LaunchedEffect(state)` re-runs but `state is Decoded` is now false → no-op. No loop.

Two user-facing message consts live in `MainActivity` (UI layer owns copy; the parser only emits byte-safe category labels):
- `PARSE_FAILED_MSG` — e.g. *"That QR code isn't a valid pyrycode pairing code. Scan the code from `pyry pair`."*
- `SAVE_FAILED_MSG` — e.g. *"Couldn't save the pairing. Please try again."*

### `ScannerViewModel` change

Add to the `ScannerEvent` sealed interface and the `onEvent` `when` — mirror the existing `CameraError`:

```kotlin
data class PairingFailed(val message: String) : ScannerEvent
// in onEvent: is ScannerEvent.PairingFailed -> ScannerUiState.Error(event.message)
```

`message` is an app constant (not a secret), so no redacting `toString()` is needed — but it must never carry a payload byte (the call site passes a const, never `result.reason` and never a field value).

## State + concurrency model

- No new `StateFlow`, no new `viewModelScope` job. The VM remains the single `StateFlow<ScannerUiState>` source of truth; the only state additions are the new `Error` transition driven by `PairingFailed`.
- `parsePairingPayload` is pure and synchronous (no flows, no suspension, no shared state) — trivially thread-safe.
- The only async edge is the existing `pairedServerStore.save(...)` suspend call inside the composable's `LaunchedEffect` (lifecycle-scoped; cancelled if the scanner leaves composition). On screen exit mid-save the `LaunchedEffect` coroutine cancels; nothing is half-written (the store overwrites atomically — its contract). On success the `popUpTo` removes the scanner before the next frame, so no double-save.

## Error handling

Failure modes and routing (every reject path persists **nothing** — the store is only touched on `Success`):

| Stage | Failure | `Failure.reason` (byte-safe const) | User sees |
|---|---|---|---|
| Outer decode | not base64url (e.g. `+`/`/`) | `bad-encoding` | `PARSE_FAILED_MSG` via `Error` |
| JSON parse | non-JSON, trailing data after object, absent required field | `malformed-json` | `PARSE_FAILED_MSG` |
| Field check | present-but-empty `server`/`relay`/`token` | `missing-field` | `PARSE_FAILED_MSG` |
| Relay | unparseable / non-ws(s) scheme / no host | `invalid-relay` | `PARSE_FAILED_MSG` |
| Pubkey | non-base64-std or ≠ 32 bytes | `invalid-server-key` | `PARSE_FAILED_MSG` |
| Persist | `PairedServerStoreException` (Keystore/IO) | (n/a — caught in composable) | `SAVE_FAILED_MSG` via `Error` |

**No-leak rule (AC4):** `Failure.reason` is always a fixed category constant — never a field value, never the scanned bytes. The deterministic safety nets are already in place: `QrPayload.toString()` masks `token`, `PairedServer.toString()` shows only `serverId`, and `decodeServerStaticPubkey`'s message names only a category + byte-count. The parser must not interpolate `qr.token` (or raw decoded key bytes) into any `reason` or log line; the `MainActivity` log lines emit only `e.javaClass.simpleName` and the fixed `reason`. A test asserts `reason` never contains the token/pubkey.

**Recovery surface:** failures land on the existing full-screen `ScannerErrorContent` (message + "Paste the pairing code instead"). Note this surface has no "rescan" affordance and the camera is torn down in non-`ReadyToScan` states — so the only forward path from `Error` is the (stubbed, out-of-scope) paste button. This matches the existing `CameraError → Error` behavior (#326); it is **not** a regression introduced here. Flagged in Open Questions.

## Testing strategy

All unit (`./gradlew test`), JUnit4 with `org.junit.Assert.*` (+ `assertThrows`), mirroring `MobileWireCodecTest`. No device, no `runTest` — everything here is pure synchronous. A small helper builds a valid scanned string: `Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())` over a known-good JSON whose `server_static_pubkey` is `base64StdEncode(ByteArray(32))`.

**`PairingPayloadParserTest.kt`** (new) — scenarios (inputs → expected):
- Valid wrapper → `Success`; assert the exact field mapping (`serverId==server`, `token==token`, `relayUrl==relay`, `serverStaticPublicKey==pubkey-string`).
- Round-trip pin: encode a `QrPayload` via `MobileJson` + base64url-no-pad, decode through `parsePairingPayload`, recover the same field values.
- Reject — outer not base64url (`"!!!"`, and a base64-**std** string containing `+`/`/`) → `Failure` (proves the std-alphabet trap is rejected).
- Reject — valid base64url of non-JSON (`"not json"`) → `Failure`.
- Reject — valid base64url of JSON **with trailing bytes** after the object (`{…}` + `garbage`, and `{…}{}`) → `Failure` (pins the kotlinx EOF-after-value behavior the AC requires).
- Reject — absent `server` (field omitted); absent `relay`; absent `token` → `Failure` each.
- Reject — present-but-empty `"server":""`; `"relay":""`; `"token":""` → `Failure` each.
- Reject — malformed relay: `"::::"` (unparseable), `"http://x"` / `"ftp://x"` (wrong scheme), `"wss://"` (no host) → `Failure` each.
- Reject — pubkey wrong length (base64-std of 31 bytes) → `Failure`; pubkey non-base64 (`"!!!"`) → `Failure`.
- **Tolerate extra fields** — valid JSON with an unknown extra key still → `Success` (proves `ignoreUnknownKeys`; the AC's "reject trailing-data, not extra-fields" line).
- **No-leak** — for a payload carrying a distinctive `token`/`pubkey` value that fails validation, assert `Failure.reason` contains neither substring.

**`MobileWireCodecTest.kt`** (add cases): `decodeBase64UrlNoPad` decodes a known url-safe-no-pad vector to expected bytes; round-trips `getUrlEncoder().withoutPadding()` output; `assertThrows<IllegalArgumentException>` on a string containing `+`/`/`.

**`ScannerViewModelTest.kt`** (add one case): `vm.onEvent(ScannerEvent.PairingFailed("boom"))` → `state == ScannerUiState.Error("boom")`.

`MainActivity`'s composable wiring (parse→save→navigate / failure→Error) is integration-shaped and validated by the parser + VM unit coverage plus manual smoke; no new instrumented test is required for this slice (consistent with how the existing `stubPairAndNavigate` binding is covered).

## Open questions

- **Relay path shape (low risk).** This slice accepts a `relay` with a path/port. If `pyry pair` ever emits a relay carrying a path (e.g. `wss://host/v1/client`), the transport's `trimEnd('/') + "/v1/client"` would double the suffix. #211 confirms an origin-only emitter today, so this is currently moot — but if the emitter contract changes, relay normalization belongs in the transport (it owns the dial URL), not here. Resolve only if observed.
- **Recovery from `Error` (deferred, not a regression).** From a parse-failure `Error` there is no in-screen rescan affordance and the camera is gone (only the out-of-scope paste button remains). Identical to the existing `CameraError → Error` behavior (#326). If product wants "tap to rescan from Error," that's a follow-up ticket touching `ScannerErrorContent` + a `ScannerEvent` to re-arm `ReadyToScan`.

## Security review

**Verdict:** PASS

Adversarial re-read of the spec above, assuming it has holes. The worst case for this slice is a **hostile QR** the user is tricked into scanning: it can supply an attacker-chosen `serverId`, `token`, `relayUrl`, and `serverStaticPublicKey`.

**Findings:**

- **[Trust boundaries] No findings.** The untrusted→trusted boundary is a single named function, `parsePairingPayload(scanned)`. Input is untrusted scanned bytes; output is either a structurally-validated `PairedServer` (Success) or a typed `Failure` — never a half-validated record. Downstream (`MainActivity`) holds only the `Success.server` or routes to `Error`. The boundary is not scattered. Validation re-checks every field (base64url wrapper, JSON shape + EOF, non-empty `server`/`relay`/`token`, ws(s) relay with host, 32-byte base64-std pubkey) before a `PairedServer` exists.

- **[Threat model alignment — pairing MITM] OUT OF SCOPE → #321 (named).** Structural validation proves the QR is *well-formed*, **not** that it came from the user's own server. An attacker's well-formed QR parses and persists just as a legitimate one does. The control against this is the fingerprint / safety-number confirm gate, **explicitly ticket #321** (scope guard in the body). This is **not a MUST-FIX for #320** because: (a) #320 replaces an *already-unguarded* stub persist (`stubPairAndNavigate` saves `STUB_PAIRED_SERVER` with no gate today) — it is not a new exposure; (b) the persisted record is **not yet trust-bearing in a live flow** — the UI runs against `FakeConversationRepository`, and the handshake that would actually dial the attacker's relay/key is gated behind #302/#309, not wired to the UI; (c) the design is **gate-friendly** — parse (pure) is isolated from persist (composable), so #321 can interpose a fingerprint-confirm step between `Success` and `pairedServerStore.save(...)` with no restructuring. Named dependency; owner #321.

- **[Tokens, secrets, credentials] SHOULD FIX — addressed in spec.** The `token` is the device-pairing secret (server-minted; the phone only receives it). Storage is the existing Keystore-wrapped `KeystorePairedServerStore` (#294, encrypts the whole `PairedServer` blob), unchanged here. Three concrete leak defenses are specified: (1) `parsePairingPayload` performs **no logging at all** — in particular it must never `Log` the raw `scanned` string nor the intermediate decoded `json` String (which carries the plaintext token before parsing); the caller logs only `e.javaClass.simpleName` + the fixed category `reason`; (2) `Failure.reason` is a fixed category constant, never a field value; (3) the deterministic nets already on `main` — `QrPayload.toString()` masks `token`, `PairedServer.toString()` shows only `serverId`, `ScannerUiState.Decoded`/`QrDecoded.toString()` redact `payload`. A unit test asserts `reason` contains neither the token nor the pubkey. Token rotation/revocation/expiry are protocol/server concerns (re-pair overwrites last-writer-wins) — OUT OF SCOPE, named.

- **[File / storage operations] No findings.** No QR field is ever used to build a filesystem path — all four fields are stored as opaque strings inside the encrypted `PairedServer` blob, so there is no path-traversal vector from the untrusted payload. Encryption-at-rest, atomic overwrite, and `allowBackup` exclusion are `KeystorePairedServerStore`'s concern (#294), unchanged by this slice. A partial write on mid-save process death yields an undecryptable blob, which `load()` returns as `null` → re-pair (the store's documented contract) — no garbage record surfaces.

- **[Inter-process / Android attack surface] No findings.** This slice adds no exported component, `intent-filter`, deep link, `PendingIntent`, `ContentProvider`, or `WebView`. The payload's only entry point is the camera analyzer (#333/#334) — there is no programmatic intent path by which a third-party app can inject a payload. The only "attacker" is one who controls a physical/displayed QR the user scans (covered by the pairing-MITM finding).

- **[Cryptographic primitives] No findings for this slice.** No RNG, no comparison of attacker input against a secret (the relay/scheme checks are not secret comparisons), no hand-rolled crypto. base64 is transport encoding, not a security primitive. The X25519 pubkey is validated for **length only** (32 bytes, via the existing `decodeServerStaticPubkey`) and is **not used** here — curve-point/identity-key validity belongs to the Noise_IK layer that consumes it (#275/#302/#309). OUT OF SCOPE for key-validity-beyond-length, named, consistent with the existing decoder's contract.

- **[Network & I/O] No findings; one named decision.** `parsePairingPayload` performs no I/O. Relay validation accepts both `ws://` and `wss://`: confidentiality is provided by the **Noise_IK layer over the socket**, not by relay TLS, and `OkHttpRelayTransport` deliberately supports both schemes (it scheme-converts `ws→http`/`wss→https`). TLS-only enforcement, if ever wanted, is a transport-policy decision, not a pairing-parse one — named, deferred. Timeouts/TLS-version/frame-caps are `OkHttpRelayTransport`'s concern (#306), not touched here.

- **[Error messages, logs, telemetry] No findings beyond the Tokens finding.** User-facing copy is two fixed generic strings; no field value is interpolated. No analytics/telemetry are added. No verbose logging of the payload, the decoded JSON, or any field value.

- **[Concurrency] No findings.** The parser is pure and synchronous (no scope, no shared state, no suspension) — trivially thread-safe. The only async edge is the existing lifecycle-scoped `LaunchedEffect` save, which cancels on screen exit; no new coroutine scope, mutex, or long-lived background work. `popUpTo(inclusive)` + one-payload-per-scan prevents double-save/re-fire. No check-then-mutate race is introduced.

- **[Mobile-specific surfaces] No findings.** The scanner never renders the decoded payload on screen (the `Decoded` state shows the unchanged viewport), so there is no on-screen secret for an overlay/accessibility eavesdropper to capture. QR-screenshot/cloud-backup token leakage (protocol-mobile.md security model) is the user's/`pyry pair`'s exposure surface; #320 adds no *second* exposure (no raw-payload persistence, masked `toString`, no logging). The out-of-scope paste path's clipboard/third-party-keyboard exposure belongs to the future real-paste ticket — named.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-01
