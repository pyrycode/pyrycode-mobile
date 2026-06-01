# Spec — fingerprint confirm gate before pairing persists (#343)

**Size:** S (PO sized S; architect-confirmed S).

- **Production files:** 4 (0 new, 4 modified) — under the ≥5 split gate.
  - modify `data/network/PairingPayloadParser.kt` — add one pure top-level fn `serverKeyFingerprint`.
  - modify `ui/onboarding/ScannerViewModel.kt` — add 1 state + 2 events + 2 reducer branches.
  - modify `ui/onboarding/ScannerScreen.kt` — add the `AwaitingConfirm` `when` branch + the `PairingConfirmContent` private composable + 2 callback params.
  - modify `MainActivity.kt` — rewire the `Decoded` `LaunchedEffect` Success branch (derive → enter confirm, **no save**), add a confirm-scope save lambda, a `BackHandler`, and pass the two new callbacks.
- **New exported top-level symbols:** 1 (`serverKeyFingerprint`). The new state/events are members of the existing sealed `ScannerUiState`/`ScannerEvent` (not new top-level types). `PairingConfirmContent` is `private`.
- **Edit fan-out:** 0 beyond the 4 edited files. `codegraph_impact ScannerUiState` → 2 symbols, both in `ScannerViewModel.kt`. The only exhaustive `when`s over the sealed types are `ScannerScreen.kt:60` (`when(state)`) and `ScannerViewModel.kt:74` (`when(event)`) — both files I edit. `MainActivity` consumes scanner state via `is`/`as?`, not exhaustive `when`, so the sealed additions force no MainActivity cascade.

**Status:** ready for development.

**Depends on:** nothing un-landed. Consumes `parsePairingPayload` + `PairingParseResult` (#320, on `main`), `staticKeyFingerprint` (#342, on `main`), `base64StdDecode` (#273/#320, on `main`), `PairedServer` + `PairedServerStore` + `PairedServerStoreException` (#294, on `main`), and the `ScannerUiState`/`ScannerEvent`/`ScannerScreen` surface (#326/#333/#334/#320, on `main`).

## Design source

N/A — design-later per ticket body (re-verified 2026-06-01: the locked file `g2HIq2UyPhslEoHRokQmHG` has no confirm-pairing / fingerprint surface across all 599 nodes). The security behavior (AC #1–#4) is visual-independent and fully specified; build a clean, functional Material 3 confirm surface and **do not invent decorative styling that pre-empts the eventual design**. When the confirm-pairing view is drawn, a visual-fidelity retrofit is filed as a follow-up and this section is re-anchored to the new `node-id`. The design is still owed; this escape tracks the gap.

## Files to read first

The developer's turn-1 data load. Page these in deliberately; do not grep for them.

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:143-240` — the `Routes.SCANNER` composable. The `LaunchedEffect(state)` at `:198-217` (parse → save → navigate, the binding you rewire), `stubPairAndNavigate` at `:154-166` (**the template for the new confirm-save lambda** — same `scope.launch { try save/navigate catch → VM event }` shape), `onPasteCode = stubPairAndNavigate` at `:230` (**leave unchanged** — out-of-scope paste path), and the `PARSE_FAILED_MSG`/`SAVE_FAILED_MSG` consts at `:395-398`. `koinInject<PairedServerStore>()` and `rememberCoroutineScope()` are already wired at `:145-146`.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerViewModel.kt` (whole file, 82 lines) — the pure synchronous reducer. `ScannerUiState` (note `Decoded` redacts `payload` in `toString`), `ScannerEvent` (`PairingFailed(message)` carries a final UI string, never a payload byte — mirror this), and the exhaustive `when(event)` at `:74`. You add 1 state, 2 events, 2 branches here.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:51-84` — `ScannerScreen(state, onNavigateBack, onOpenSettings, onPasteCode, …)` and its `when(state)` dispatch; `:314-345` `ScannerErrorContent` is the **full-screen stateless-content template** to mirror for `PairingConfirmContent` (Surface + centered Column + M3 typography + buttons). You add a peer branch + a peer private composable + 2 params.
- `app/src/main/java/de/pyryco/mobile/data/network/PairingPayloadParser.kt` (whole file) — `parsePairingPayload(scanned): PairingParseResult`. You add the sibling `serverKeyFingerprint` top-level fn here (functions don't trip the ktlint single-public-class filename rule).
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionFactory.kt:30-40` — **the decode-then-re-validate-to-32-bytes pattern to mirror exactly**: `base64StdDecode` in a `try`, `IllegalArgumentException` → typed failure; `size != REMOTE_STATIC_KEY_SIZE` → typed failure; never echo the bytes. `REMOTE_STATIC_KEY_SIZE = 32` is at `:72`.
- `app/src/main/java/de/pyryco/mobile/data/crypto/StaticKeyFingerprint.kt:33` — `staticKeyFingerprint(staticKey: ByteArray): String` (#342). Takes **raw 32 bytes**, colon-lowercase-hex out. Its `require(size == 32)` throws — that's why `serverKeyFingerprint` must re-validate before calling it (keep the `require` structurally unreachable, never let it throw into a LaunchedEffect).
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt:19-58` — `PairedServerStore.save` (throws `PairedServerStoreException`), and `PairedServer` (4 `String` fields; `toString()` already redacts `token` → only `serverId`). This redaction is the deterministic net that makes the new state/event safe to hold the record without a custom `toString`.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:37-40` — `base64StdEncode` / `base64StdDecode` (the std alphabet; the URL-safe alphabet is a different fn — not used here).
- `app/src/test/java/de/pyryco/mobile/data/network/PairingPayloadParserTest.kt:1-69` — JUnit4 idiom (`org.junit.Assert.*`, the `json()`/`wrap()` fixture helpers, `base64StdEncode(ByteArray(32))` for a valid pubkey). Mirror for the `serverKeyFingerprint` cases.
- `app/src/test/java/de/pyryco/mobile/data/crypto/StaticKeyFingerprintTest.kt:19` — the pinned vector `base64StdEncode(ByteArray(32))` → `"32:0b:5e:a9:9e:65:3b:c2"`. Reuse this exact literal to pin the parse→derive seam end-to-end.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerScreenTest.kt` (whole file) — Compose-test idiom: `@RunWith(AndroidJUnit4::class)`, `createComposeRule()`, `onNode(hasText(…))`, `assertHasClickAction()`. Compose UI tests live in **`androidTest`** here, not `src/test`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/ConversationRowTest.kt:87` — the a11y assertion idiom for AC #5: `composeTestRule.onNode(hasContentDescription(expected)).assertHeightIsAtLeast(48.dp)`.

## Context

Phase 4 / pairing — the **security checkpoint**. After #320, a successful scan parses the QR into a real `PairedServer` and the `MainActivity` `Decoded` `LaunchedEffect` persists it **immediately** (`pairedServerStore.save` → navigate). Structural QR validity proves the payload is *well-formed*, not that it came from the user's own server: an attacker's well-formed QR persists identically to a legitimate one. This slice interposes a **confirmation gate between the successful parse and the persist** — render the server's fingerprint (the #342 derivation, byte-for-byte with the desktop `Static-key fp:` line under the QR), and require an explicit user confirm before `save(...)` commits. Decline / back persists nothing and returns to scanning.

**In scope:** the confirm gate — a new `AwaitingConfirm` VM state, the fingerprint derivation from the parsed `PairedServer`, the full-screen confirm surface, and the `Decoded`-binding rewire so the **only** `save` on the scan path is gated behind confirm.

**Out of scope (do not build):** the Noise handshake that actually dials the relay/key (#302/#309); the "Paste the pairing code" text-entry path (`onPasteCode = stubPairAndNavigate` remains the out-of-scope stub, ungated, exactly as #320 left it — a future real-paste ticket routes paste through parse→confirm too); re-deriving the fingerprint (#342 owns the algorithm; this slice consumes it).

## Design

### Flow (state machine)

```
ReadyToScan ──QrDecoded(payload)──► Decoded(payload)
                                       │  [MainActivity LaunchedEffect(state): parse + derive]
                 parse Failure ────────┤
                 derive null ──────────┤───► PairingFailed(PARSE_FAILED_MSG) ──► Error
                                       │
                 Success + fp ─────────┴───► PairingPrepared(fp, server) ──► AwaitingConfirm(fp, server)
                                                                                  │
                          Confirm (button) ──[confirm-scope save lambda]──► save(server); navigate(CHANNEL_LIST)
                                                       └─ save fails ──► PairingFailed(SAVE_FAILED_MSG) ──► Error
                          Decline (button) / system Back ──DeclinePairing──► ReadyToScan   (nothing persisted)
```

The gate sits between `Success` and `save`. **No `save` runs until the Confirm button fires** — the rewired `Decoded` effect no longer saves at all (AC #1, #2).

### `ScannerUiState` — add one state (`ScannerViewModel.kt`)

```kotlin
data class AwaitingConfirm(
    val fingerprint: String,
    val server: PairedServer,
) : ScannerUiState
```

- Single source of confirm state, hoisted to the VM (the composable stays `(state, onEvent)` — no local `var`). Carries `server` so the confirm path persists exactly the parsed record, and `fingerprint` so the surface renders without re-deriving.
- **No custom `toString` needed.** The only secret is `server.token`, and `PairedServer.toString()` already redacts it (`serverId` only); `fingerprint` is a public-key digest (displayed on screen and on the desktop). Default data-class `toString` is therefore byte-safe. (State here so code-review doesn't flag the missing redaction — it lives one level down in `PairedServer`.)

### `ScannerEvent` — add two events (`ScannerViewModel.kt`)

```kotlin
data class PairingPrepared(val fingerprint: String, val server: PairedServer) : ScannerEvent
data object DeclinePairing : ScannerEvent
```

- `PairingPrepared` is fired by the composable after a successful parse **and** successful derive — same "feed the VM the final value" pattern as `PairingFailed`. No custom `toString` (same rationale as `AwaitingConfirm`).
- `DeclinePairing` is fired by the Decline button and by system Back while confirming.
- **Confirm is deliberately NOT a `ScannerEvent`.** The persist is a `suspend save` + navigation that this VM cannot own (its contract: no `viewModelScope`, no Android types — see `:65-67`). Confirm is wired exactly like `onPasteCode`/`onNavigateBack`: a route-scope callback lambda mirroring `stubPairAndNavigate`. This matches #320's established "orchestration (suspend save + nav) lives in the composable" decision rather than reintroducing a scope into the VM.

### `onEvent` — add two reducer branches (`ScannerViewModel.kt`)

- `is ScannerEvent.PairingPrepared -> ScannerUiState.AwaitingConfirm(event.fingerprint, event.server)`
- `ScannerEvent.DeclinePairing -> ScannerUiState.ReadyToScan` — re-arms the scanner; persists nothing. `ReadyToScan` re-mounts `CameraPreview` (the route mounts it only in `ReadyToScan`, `MainActivity.kt:232`), so the user can scan again (AC #3). Unconditional `→ ReadyToScan` is correct: `DeclinePairing` is only ever wired from the confirm surface / the `AwaitingConfirm`-gated `BackHandler`.

### `serverKeyFingerprint` — new pure fn (`PairingPayloadParser.kt`)

```kotlin
/** Decode [staticKeyBase64] (base64-std) → re-validate exactly 32 bytes → derive the colon-hex
 *  fingerprint (#342). Returns null if the stored key can't be decoded to a 32-byte value.
 *  Pure, synchronous, no I/O, never throws. */
fun serverKeyFingerprint(staticKeyBase64: String): String?
```

- Body (developer writes it): `try base64StdDecode` (catch `IllegalArgumentException` → `null`); `if size != NoiseSessionFactory.REMOTE_STATIC_KEY_SIZE → null`; else `staticKeyFingerprint(bytes)`. **Mirror `NoiseSessionFactory.create():30-40` exactly** — same decode-then-revalidate, same never-echo-bytes discipline.
- **Why the re-validate + `null` instead of calling `staticKeyFingerprint` directly:** #320 already guarantees a freshly-parsed `Success.server.serverStaticPublicKey` is base64-std of exactly 32 bytes, so the `null` branch is structurally unreachable on this flow — but `staticKeyFingerprint`'s `require(size == 32)` *throws*, and an uncaught throw inside the `LaunchedEffect` coroutine crashes the pairing flow. Re-validating to a typed `null` (the #342 spec's explicitly-assigned downstream responsibility) keeps that `require` unreachable and converts the impossible-but-fatal case into the same "route to Error" path as a parse failure. This is deterministic belt-and-suspenders over the #320 guarantee (different fabric: code, not a second stochastic check).
- Lives in `data/network` (not `data/crypto`) because it needs `base64StdDecode` (data/network) and `data/network → data/crypto` already holds; pulling base64 into `data/crypto` would invert the layer and contradict #342's deliberate boundary.

### `ScannerScreen` — add a branch + a stateless content composable (`ScannerScreen.kt`)

Add two callback params to `ScannerScreen` (keep them after `onPasteCode`, before `modifier`):

```kotlin
onConfirmPairing: () -> Unit = {},
onDeclinePairing: () -> Unit = {},
```

Add to `when(state)`:

```kotlin
is ScannerUiState.AwaitingConfirm ->
    PairingConfirmContent(
        fingerprint = state.fingerprint,
        onConfirm = onConfirmPairing,
        onDecline = onDeclinePairing,
        modifier = modifier,
    )
```

`PairingConfirmContent(fingerprint: String, onConfirm: () -> Unit, onDecline: () -> Unit, modifier)` — private, stateless, full-screen. Mirror `ScannerErrorContent`'s shape (Surface `surface` color, `systemBarsPadding`, centered Column). Contract (developer writes the body; keep it clean and non-decorative per design-later):

- **Title** — short heading, e.g. *"Confirm the server fingerprint"* (`titleLarge`/`headlineSmall`).
- **Compare copy (AC #4)** — body text instructing the user to compare against the other device, e.g. *"Check this matches the `Static-key fp:` line shown by `pyry pair` on your other device before you pair."* (`bodyMedium`/`bodyLarge`, `onSurfaceVariant`).
- **Fingerprint (AC #4, #5)** — the `fingerprint` string rendered **verbatim** (do not uppercase, regroup, or strip colons — it is already the #342 colon-lowercase-hex form). `FontFamily.Monospace`, prominent style. Wrap in `androidx.compose.foundation.text.selection.SelectionContainer` (selectable + long-press copy). Attach a content description: `Modifier.semantics { contentDescription = "Server fingerprint $fingerprint" }`.
- **Confirm button (AC #2, #5)** — `Button(onClick = onConfirm)`, full-width, `Modifier.heightIn(min = 48.dp)`, accessible label via its text (e.g. *"Confirm pairing"*).
- **Decline button (AC #3, #5)** — `OutlinedButton`/`TextButton(onClick = onDecline)`, `Modifier.heightIn(min = 48.dp)`, text e.g. *"Don't pair"*. (M3 already enforces a 48dp minimum interactive size; the explicit `heightIn` makes the AC #5 target assertion deterministic.)

### `MainActivity` — rewire the binding (`MainActivity.kt`)

1. **Rewire the `Decoded` `LaunchedEffect(state)` Success branch** (`:198-217`). On `PairingParseResult.Success`: derive `serverKeyFingerprint(result.server.serverStaticPublicKey)`; if `null` → `Log.w(TAG, "fingerprint derive failed: bad-stored-key")` + `vm.onEvent(ScannerEvent.PairingFailed(PARSE_FAILED_MSG))`; else `vm.onEvent(ScannerEvent.PairingPrepared(fp, result.server))`. **Remove the `save` + navigate from this branch entirely** (it moves behind confirm). The `Failure` branch is unchanged. The derive is microsecond CPU work — runs inline on the effect's Main coroutine, like the parse.
2. **Add a confirm-scope save lambda**, mirroring `stubPairAndNavigate` (`:154-166`):

   ```kotlin
   val confirmPairAndNavigate: (PairedServer) -> Unit = { server ->
       scope.launch {
           try {
               pairedServerStore.save(server)
               navController.navigate(Routes.CHANNEL_LIST) {
                   popUpTo(Routes.SCANNER) { inclusive = true }; launchSingleTop = true
               }
           } catch (e: PairedServerStoreException) {
               Log.w(TAG, "paired-server save failed: ${e.javaClass.simpleName}")
               vm.onEvent(ScannerEvent.PairingFailed(SAVE_FAILED_MSG))
           }
       }
   }
   ```
3. **Pass the callbacks to `ScannerScreen`:**
   - `onConfirmPairing = { (state as? ScannerUiState.AwaitingConfirm)?.let { confirmPairAndNavigate(it.server) } }`
   - `onDeclinePairing = { vm.onEvent(ScannerEvent.DeclinePairing) }`
4. **Add a `BackHandler`** (`androidx.activity.compose.BackHandler`) in the scanner route: `BackHandler(enabled = state is ScannerUiState.AwaitingConfirm) { vm.onEvent(ScannerEvent.DeclinePairing) }`. This makes system Back behave as Decline while confirming (return to scanner, persist nothing) rather than popping the whole scanner route back to Welcome (AC #3). When not `AwaitingConfirm` the handler is disabled and Back pops normally.

The user-facing copy consts (`PARSE_FAILED_MSG`, `SAVE_FAILED_MSG`) are reused unchanged — a derive failure surfaces `PARSE_FAILED_MSG` (a key that won't decode means the QR was bad). No new copy const.

## State + concurrency model

- The VM remains the single `StateFlow<ScannerUiState>` source of truth and a **pure synchronous reducer** — no new `StateFlow`, no `viewModelScope`, no Android types. The only additions are the `AwaitingConfirm` state and its two transitions.
- `serverKeyFingerprint` is pure/synchronous (no flows, no suspension, no shared state) — trivially thread-safe; a fresh `Blake2sMessageDigest` is constructed per `staticKeyFingerprint` call (#342).
- The only async edge is the existing lifecycle-scoped `rememberCoroutineScope` save inside `confirmPairAndNavigate` (cancelled if the scanner leaves composition; the store overwrites atomically). On `Success`, `popUpTo(SCANNER){inclusive=true}` removes the scanner so no re-fire.
- **Double-confirm is benign and deliberately not guarded.** A fast double-tap fires two `save`s of the same record (idempotent last-writer-wins overwrite) + two `navigate`s (`launchSingleTop` + `popUpTo` dedupe). Per Evidence-Based Fix Selection, adding a `Saving` state purely to prevent an unobserved, harmless double-write would be over-engineering — omitted. (Same posture as the existing `stubPairAndNavigate` double-tap.)
- Rotation mid-save: the VM survives (holds `AwaitingConfirm`); a recreated composition could re-enter `confirmPairAndNavigate` only if the user re-taps — the in-flight save was already scope-cancelled. Same idempotent overwrite property; no half-write (store contract).

## Error handling

| Stage | Failure | Routing | User sees |
|---|---|---|---|
| Parse | any malformed QR | `parsePairingPayload` → `Failure` (unchanged from #320) | `PARSE_FAILED_MSG` → `Error` |
| Derive | stored key non-base64-std / ≠ 32 bytes (structurally unreachable for a `Success`) | `serverKeyFingerprint` → `null` → fixed-reason `Log.w` + `PairingFailed(PARSE_FAILED_MSG)` | `PARSE_FAILED_MSG` → `Error` |
| Confirm/save | `PairedServerStoreException` (Keystore/IO) | caught in `confirmPairAndNavigate` → `Log.w(e.javaClass.simpleName)` + `PairingFailed(SAVE_FAILED_MSG)` | `SAVE_FAILED_MSG` → `Error` |
| Decline / Back | user declines | `DeclinePairing` → `ReadyToScan` | scanner, ready to re-scan; **nothing persisted** |

**No-leak rule (inherited from #320/#342):** logs emit only fixed category strings + `e.javaClass.simpleName` — never the payload, the decoded key bytes, the token, or the fingerprint-derivation internals. `serverKeyFingerprint` does **no logging** and never interpolates key bytes into any value (mirrors `parsePairingPayload`). The displayed fingerprint is a public-key digest (the feature, not a leak). `AwaitingConfirm`/`PairingPrepared` rely on `PairedServer.toString()`'s token redaction.

**Nothing-persisted-on-failure boundary (AC #3) stays intact:** the store is touched in exactly two places — `stubPairAndNavigate` (out-of-scope paste path, unchanged) and `confirmPairAndNavigate` (gated behind the Confirm button). The rewired `Decoded` effect touches the store **never**. Parse failures, derive failures, decline, and back all persist nothing.

## Testing strategy

**Unit (`./gradlew test`)** — JUnit4, no device, no `runTest` (everything here is pure/synchronous):

- `ScannerViewModelTest.kt` (add):
  - `onEvent(PairingPrepared("32:0b:…", server))` → `state == AwaitingConfirm("32:0b:…", server)`.
  - From `AwaitingConfirm`, `onEvent(DeclinePairing)` → `state == ReadyToScan`.
- `PairingPayloadParserTest.kt` (add, reusing `base64StdEncode`/`json()`/`wrap()`):
  - Valid key — `serverKeyFingerprint(base64StdEncode(ByteArray(32)))` → **literal** `"32:0b:5e:a9:9e:65:3b:c2"` (pins parity with #342's pinned vector; hard-coded, never recomputed).
  - Wrong length — `serverKeyFingerprint(base64StdEncode(ByteArray(31)))` → `null`.
  - Non-base64 — `serverKeyFingerprint("!!!")` → `null`.
  - End-to-end seam — `parsePairingPayload(wrap(json()))` → `Success`, then `serverKeyFingerprint(success.server.serverStaticPublicKey)` → the same literal (pins parse→derive).

**Instrumented (`./gradlew connectedAndroidTest`)** — `ScannerScreenTest.kt` (add), Compose idiom (`createComposeRule`, `hasText`, `hasContentDescription`):

- `AwaitingConfirm("32:0b:5e:a9:9e:65:3b:c2", server)` renders the fingerprint string verbatim (assert the colon-hex text exists) and the compare copy.
- Confirm button has a click action and invokes `onConfirm` when clicked.
- Decline button has a click action and invokes `onDecline` when clicked.
- The fingerprint node exposes a content description (`onNode(hasContentDescription(…)).assertExists()`).
- Confirm and Decline each `assertHeightIsAtLeast(48.dp)` (AC #5 touch target — mirror `ConversationRowTest.kt:87`).

`MainActivity`'s composable wiring (parse→derive→`AwaitingConfirm`; confirm→save→navigate; decline/back→`ReadyToScan`) is integration-shaped — validated by the VM + parser unit coverage and the `ScannerScreen` instrumented tests plus manual smoke, consistent with how #320 covered its binding. No new full instrumented `MainActivity` test. (Selectability/copyability is structurally guaranteed by `SelectionContainer`; not separately asserted.)

## Open questions

- **Confirm surface shape (resolved: full-screen).** Chosen over a dialog/sheet because (a) it extends `ScannerScreen`'s existing state-driven `when` (Error/Denied are peer full-screen branches), (b) a security checkpoint should be prominent, not a reflexively-dismissed dialog, and (c) selectable fingerprint + large touch targets are simplest without fighting dialog defaults. The eventual Figma retrofit replaces this one branch.
- **TalkBack reading of colon-hex (low risk, deferred).** The content description reads the raw `fingerprint`; whether TalkBack should spell it group-by-group for easier audible verification is a design-time polish item for the owed Figma retrofit, not a blocker. The fingerprint is selectable/copyable today.
- **Paste path remains ungated (named, not a regression).** `onPasteCode = stubPairAndNavigate` still persists the stub with no confirm gate; per #320 the real paste-input UI doesn't exist. The future real-paste ticket routes paste through `parsePairingPayload` → `serverKeyFingerprint` → the same confirm gate.

## Security review

**Verdict:** PASS

Adversarial re-read of the spec above, assuming it has holes. This slice **is** the security control for the QR-TOFU pairing flow, so the worst cases are: (a) a pairing persists without the human confirming; (b) the human is shown a fingerprint that doesn't correspond to the record being saved (verifies serverA, pairs serverB); (c) the gate is bypassable via back/decline/race so a MITM record persists unverified.

**Findings:**

- **[Trust boundaries] No MUST-FIX — the load-bearing property is explicit and bound.** The new boundary is human confirmation: `AwaitingConfirm` (proposed, untrusted) → Confirm → `save` (persisted, trusted). It is enforced at a **single named site** — `confirmPairAndNavigate`, the only scan-path call to `pairedServerStore.save`, wired solely to the Confirm button. The rewired `Decoded` effect no longer saves (verified against the rewrite in Design §"MainActivity"). **Fingerprint↔record binding (the critical correctness property):** the displayed `fingerprint` and the saved `server` are the two fields of one immutable `AwaitingConfirm` data object; the fingerprint was derived from *that* `server`'s key, and confirm saves *that same* `server` (`(state as? AwaitingConfirm)?.server`). There is no path that shows fp(A) but saves B — no TOCTOU between display and persist. Worth code-review attention that the developer does not re-derive or re-parse at confirm time (must read `state.server`, never re-run the pipeline).

- **[Tokens, secrets, credentials] No MUST-FIX.** The bearer `token` lives only inside the `PairedServer` carried in VM state; **it never reaches the rendered surface** — `PairingConfirmContent` receives `fingerprint: String` + two callbacks, not the `server`, so the token-bearing record stays in `MainActivity` (read only for the save). The displayed fingerprint is a public-key digest (also printed by the desktop) — its display, content description, and copyability are the feature, not a leak. `serverKeyFingerprint` does no logging and never interpolates key bytes; derive/save failures log only fixed category strings + `e.javaClass.simpleName`. Deterministic nets carry over: `PairedServer.toString()` redacts `token`, so even a stray `Log.d("$state")` of `AwaitingConfirm`/`PairingPrepared` cannot leak it (SHOULD-note for code-review, already covered structurally). Token rotation/revocation/expiry remain server/protocol concerns (re-pair = last-writer-wins overwrite) — OUT OF SCOPE, named.

- **[File / storage operations] No findings.** No QR field is used to build a filesystem path. Persistence is the existing Keystore-wrapped `KeystorePairedServerStore` (#294, encrypted at rest, atomic overwrite, `allowBackup` excluded), unchanged. The only "TOCTOU-shaped" risk (shown-vs-saved) is the fingerprint↔record binding, closed under Trust boundaries.

- **[Inter-process / Android attack surface] No findings.** Adds no exported component, `intent-filter`, deep link, `PendingIntent`, `ContentProvider`, or `WebView`. The new `BackHandler` is an in-process Compose back callback (`enabled` only while `AwaitingConfirm`) — no IPC surface. The payload's only entry point remains the camera analyzer (#333/#334); no third-party app can inject a payload or reach the confirm state programmatically. Confirm/Decline are in-process callbacks on the non-attacker-reachable scanner route.

- **[Cryptographic primitives] No findings for this slice.** No new crypto: `staticKeyFingerprint` (#342, vendored BLAKE2s, full-256-then-truncate, load-bearing 8-byte width) is consumed unchanged; `base64StdDecode` is transport encoding. **The fingerprint comparison is performed by the human eye (phone vs. desktop), not by code** — so constant-time comparison is N/A (there is no code-level compare of attacker input against a secret; that is precisely the design). The 32-byte re-validate is a length check; X25519 curve-point/identity validity belongs to the Noise_IK layer (#302/#309) — OUT OF SCOPE, named, consistent with #320/#342. No RNG.

- **[Network & I/O] No findings.** The gate and `serverKeyFingerprint` perform zero network/I/O; the only write is the local Keystore store. Relay dial/TLS/timeouts/frame-caps belong to `OkHttpRelayTransport` (#306), not touched.

- **[Error messages, logs, telemetry] No findings beyond Tokens.** Two fixed user-facing strings, no field interpolation; no telemetry/analytics added; no verbose payload/key/token logging.

- **[Concurrency] No MUST-FIX.** No new coroutine scope (the save reuses the existing lifecycle-scoped `rememberCoroutineScope`, cancelled on screen exit). The VM reducer is synchronous. **Confirm-after-close race:** `onConfirmPairing` reads the *current* collected `state` via `as? AwaitingConfirm`; if a back/decline already moved state to `ReadyToScan`, the cast is `null` and confirm is a no-op — **a save cannot fire after the gate closed.** Double-confirm is benign (idempotent same-record overwrite + `launchSingleTop`/`popUpTo` nav dedupe); a `Saving` state to prevent it is deliberately omitted (Evidence-Based Fix Selection — unobserved, harmless). Mid-save process death is handled by the store contract (undecryptable blob → `load()` null → re-pair, #294).

- **[Threat model alignment — QR-TOFU MITM] Addressed (this is the mitigation).** `protocol-mobile.md` § Security model names the 64-bit fingerprint visual check as the MITM mitigation for QR trust-on-first-use; this slice is the mobile human-confirm half that closes #320's explicitly-named gap. **Mobile-specific threats:** (1) *Screenshot / overlay / accessibility-service capture of the confirm screen* — the only on-screen value is the public fingerprint (no secret rendered; token absent from the tree), so capture leaks nothing secret — **no finding.** (2) *Tapjacking / screen-overlay synthesizing a Confirm tap* — a malicious overlay that hides the fingerprint and drives a Confirm tap would bypass the human verification this feature exists to enforce. Classified **OUT OF SCOPE (named, with a recommended follow-up)**, not MUST-FIX, because: it is not introduced by this spec (every existing tap target shares it; #320's persist and the stub had no such protection), it requires a separately-installed app holding overlay/`SYSTEM_ALERT_WINDOW` capability (heavily gated on this min-SDK-33 target, with `HIDE_OVERLAY_WINDOWS` available), and the correct mitigation — obscured-touch filtering at the Activity-window level — is a cross-cutting hardening applicable to all sensitive confirms, not a one-composable bolt-on (bolting a window-flag change into this ticket risks an incomplete, wrongly-scoped fix). **Recommended follow-up:** a security-hardening ticket that enables obscured-touch filtering on the Activity window covering this gate and any future sensitive confirm surface. (3) *Third-party keyboard logging* — no text entry on this surface — N/A.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-01
