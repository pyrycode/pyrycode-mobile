# Pairing payload parser

The **untrusted→trusted boundary of the pairing flow** (`data/network/PairingPayloadParser.kt`,
[#320](../codebase/320.md)): the single pure function that turns a scanned QR string into a persisted
[`PairedServer`](paired-server-store.md). Everything upstream (the [QR code analyzer](qr-code-analyzer.md)
→ [`ScannerUiState.Decoded`](scanner-screen.md)) is untrusted external input; everything downstream
(the encrypted store, eventually the `Noise_IK` handshake) trusts the validated record. This function
is the one place that crossing happens.

## What it does

`parsePairingPayload(scanned: String): PairingParseResult` decodes the **outer QR-string transport
wrapper**, deserializes the decoded JSON into [`QrPayload`](mobile-protocol-v2-wire-layer.md),
validates every field, and maps it to a `PairedServer`. It is **pure, synchronous, does no I/O, does
no persist, and never throws** — any malformed input yields a typed `Failure` rather than an
exception. The persist and navigation happen at the call site (`MainActivity`), not here.

```kotlin
sealed interface PairingParseResult {
    data class Success(val server: PairedServer) : PairingParseResult
    data class Failure(val reason: String) : PairingParseResult   // byte-safe category label
}

fun parsePairingPayload(scanned: String): PairingParseResult
```

`PairingParseResult` lives in its own file (`PairingParseResult.kt`) — ktlint's `standard:filename`
forbids a public top-level type sharing a file with the top-level functions in
`PairingPayloadParser.kt`.

## How it works

**The two-alphabet trap.** There are two different base64 alphabets in one pairing payload, and using
the wrong decoder for either is a silent bug:

- The **outer** scanned wrapper is base64**url**, no padding (Go `base64.RawURLEncoding`) — decoded by
  the new `decodeBase64UrlNoPad` (`Base64.getUrlDecoder()`), which **rejects** the standard alphabet's
  `+`/`/` (the load-bearing rejection).
- The **inner** `server_static_pubkey` field is base64-**std**, with padding — decoded by the existing
  `decodeServerStaticPubkey` (`Base64.getDecoder()` + exactly-32-byte check).

Both decoders are co-located in [`MobileWireCodec.kt`](mobile-protocol-v2-wire-layer.md) so the trap
stays visible. Using `base64StdDecode` / `decodeServerStaticPubkey` on the outer wrapper would be
wrong.

**Pipeline (first failure wins).** Each step's failure is a fixed `Failure.reason` category constant:

| Step | Operation | Failure → `reason` |
|---|---|---|
| 1 | `decodeBase64UrlNoPad(scanned)` (catch `IllegalArgumentException`) | `bad-encoding` |
| 2 | `String(bytes, Charsets.UTF_8)` (substitutes, never throws) | — |
| 3 | `MobileJson.decodeFromString<QrPayload>(json)` (catch `SerializationException`) | `malformed-json` |
| 4 | `server`/`relay`/`token` each `isNotBlank()` | `missing-field` |
| 5 | relay validation (see below) | `invalid-relay` |
| 6 | `decodeServerStaticPubkey(qr)` (catch `IllegalArgumentException`) | `invalid-server-key` |
| 7 | `Success(PairedServer(...))` | — |

Step 3's single catch covers **three** acceptance criteria at once: non-JSON, **trailing bytes after
the top-level object** (kotlinx requires EOF after the value — unlike Go's `json.Unmarshal`, so no
double-decode idiom is needed), and **absent required fields** (`MissingFieldException` is a
`SerializationException`). Step 4 only needs to catch present-but-`""` fields. Unknown/extra JSON keys
are **tolerated** (`MobileJson` sets `ignoreUnknownKeys`) — the rule is *reject trailing data, tolerate
extra fields*, and both peers do the same.

**Field mapping** (`QrPayload` → `PairedServer`), pinned exactly:

| `QrPayload` (wire, snake_case) | `PairedServer` |
|---|---|
| `server` | `serverId` |
| `token` | `token` |
| `relay` | `relayUrl` (verbatim) |
| `server_static_pubkey` → `serverStaticPubkey` | `serverStaticPublicKey` (the base64-std string, unchanged) |

Step 6 is **validation-only**: it decodes the pubkey to confirm base64-std + exactly 32 bytes, then
discards the bytes — `PairedServer` stores the base64 **string**, and curve-point/identity validity
belongs to the `Noise_IK` layer (#302/#309) that later consumes it.

**Relay validation — a trust-on-first-use origin.** `OkHttpRelayTransport.buildRequest()` treats
`relayUrl` as an **origin** (`wss://host[:port]`) and appends `/v1/client` itself — so `relay` is an
origin, not a full path. The decided contract (#320 asked the architect to choose): accept iff
`java.net.URI(relay)` parses, `scheme.lowercase() ∈ {ws, wss}`, and `host` is non-empty. Validated
with `java.net.URI` — **not** `android.net.Uri` (forbidden in `data/`) and **not** OkHttp `HttpUrl`
(would pull the OkHttp dep into the parser) — keeping the function `data/`-portable. A present
path/port is **not** rejected, staying close to the Go emitter's opaque-origin treatment to avoid
false rejections. Both `ws://` and `wss://` are accepted: confidentiality comes from the `Noise_IK`
layer over the socket, not relay TLS.

## Configuration / usage

The only call site is the scanner's `Decoded` binding in `MainActivity`'s `Routes.SCANNER` composable
(rewired from the #334 stub-pair `LaunchedEffect`):

```kotlin
LaunchedEffect(state) {
    val decoded = state as? ScannerUiState.Decoded ?: return@LaunchedEffect
    when (val result = parsePairingPayload(decoded.payload)) {
        is PairingParseResult.Success ->
            try {
                pairedServerStore.save(result.server)
                navController.navigate(Routes.CHANNEL_LIST) {
                    popUpTo(Routes.SCANNER) { inclusive = true }; launchSingleTop = true
                }
            } catch (e: PairedServerStoreException) {
                Log.w(TAG, "paired-server save failed: ${e.javaClass.simpleName}")
                vm.onEvent(ScannerEvent.PairingFailed(SAVE_FAILED_MSG))
            }
        is PairingParseResult.Failure -> {
            Log.w(TAG, "pairing parse failed: ${result.reason}")
            vm.onEvent(ScannerEvent.PairingFailed(PARSE_FAILED_MSG))
        }
    }
}
```

The parse is microsecond CPU work on a small string, so it runs inline on the `LaunchedEffect`'s Main
coroutine. The new `ScannerEvent.PairingFailed(message)` routes both parse and persist failures into
the existing `ScannerUiState.Error` surface (mirrors `CameraError → Error`); the two user-facing
strings (`PARSE_FAILED_MSG`, `SAVE_FAILED_MSG`) are UI-owned constants in `MainActivity`. On `Success`
the `popUpTo(SCANNER){inclusive}` pops the scanner so the keyed effect can't re-fire; on failure the
state flips `Decoded → Error`, the effect re-runs but `state is Decoded` is now false → no-op. No loop,
no double-save.

## No-leak discipline

The `token` is the device-pairing secret and `server_static_pubkey` is sensitive — neither may reach a
log or the screen:

- `parsePairingPayload` does **no logging at all** — both `scanned` and the intermediate decoded
  `json` string carry the plaintext token.
- `Failure.reason` is always a fixed category constant, **never** a field value or the scanned bytes.
  A test (`failureReason_neverContainsTokenOrPubkey`) asserts this.
- The caller logs only `e.javaClass.simpleName` (persist) + the fixed `reason` (parse).
- Deterministic nets remain: `QrPayload.toString()` masks `token`, `PairedServer.toString()` shows
  only `serverId`, and `ScannerUiState.Decoded`/`QrDecoded.toString()` redact the payload.

## Edge cases and limitations

- **Structural validity ≠ authenticity.** A *well-formed hostile QR* parses and persists exactly like
  a legitimate one — `parsePairingPayload` proves the QR is well-formed, not that it came from the
  user's own server. The control against a pairing MITM is the fingerprint / safety-number confirm
  gate, **explicitly #321**, which will interpose between `Success` and
  `pairedServerStore.save(...)` (the pure-parse / composable-persist split makes that a clean
  insertion, no restructuring). #320 is not a new exposure: it replaces an already-unguarded stub
  persist, and the persisted record isn't trust-bearing in a live flow yet (the UI runs against
  `FakeConversationRepository`; the dialling handshake is #302/#309).
- **Recovery from `Error` has no in-screen rescan.** A parse/persist failure lands on
  `ScannerErrorContent` (message + the out-of-scope "Paste the pairing code instead" button); the
  camera is torn down in non-`ReadyToScan` states. This matches the existing `CameraError → Error`
  behavior — **not** a regression. "Tap to rescan from Error" would be a follow-up touching
  `ScannerErrorContent`.
- **Relay path could double the suffix (currently moot).** If `pyry pair` ever emitted a relay
  carrying a path (e.g. `wss://host/v1/client`), the transport's `trimEnd('/') + "/v1/client"` would
  double it. pyrycode #211 confirms an origin-only emitter today; if that contract changes, relay
  normalization belongs in the transport (which owns the dial URL), not in this parser.
- **The paste path still stubs.** The "Paste the pairing code instead" button runs the #295
  `STUB_PAIRED_SERVER` persist — a real paste-input flow is a separate ticket. Only the `Decoded`
  (scanned) binding is wired to `parsePairingPayload`.

## Related

- [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md) — `QrPayload`, `MobileJson`,
  `base64StdDecode`/`decodeServerStaticPubkey`, and the new `decodeBase64UrlNoPad`
- [Paired server store](paired-server-store.md) — the encrypted-at-rest mapping target
- [Scanner screen](scanner-screen.md) — produces the `Decoded(payload)` this function consumes
- [QR code analyzer](qr-code-analyzer.md) — the decode core that surfaces the scanned string
- [ADR 0006 — Keystore wrap-at-rest](../decisions/0006-keystore-wrap-at-rest-device-static-key.md) —
  how the resulting `PairedServer` is encrypted at rest
- Ticket: [#320](../codebase/320.md) — implementation notes. Split from #277; server analog pyrycode
  #211 (`internal/pair.Decode`) + #432.
