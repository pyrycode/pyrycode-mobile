# Pairing confirm gate

The **security checkpoint of the QR-pairing flow** ([#343](../codebase/343.md)) — the mobile half of
the QR trust-on-first-use MITM control. Structural QR validity (proven by the
[Pairing payload parser](pairing-payload-parser.md), #320) shows the scanned payload is *well-formed*,
**not** that it came from the user's own server: an attacker's well-formed QR persists identically to a
legitimate one. This gate interposes a **human confirmation between the successful parse and the
persist** — it renders the server's [static-key fingerprint](static-key-fingerprint.md) (#342) and
requires an explicit confirm before [`PairedServerStore.save`](paired-server-store.md) commits. The
human compares the fingerprint on the phone against the `Static-key fp:` line the desktop's `pyry pair`
prints under the QR (pyrycode#432); a mismatch means a wrong-server / MITM, and the user declines.

## What it does

After a successful scan + parse, the [Scanner screen](scanner-screen.md) no longer persists
immediately. Instead:

1. **Derive** — the `MainActivity` `Decoded` `LaunchedEffect` calls
   `serverKeyFingerprint(result.server.serverStaticPublicKey)` to get the colon-lowercase-hex
   fingerprint.
2. **Park** — it fires `ScannerEvent.PairingPrepared(fingerprint, server)`, moving the VM to a new
   `ScannerUiState.AwaitingConfirm(fingerprint, server)` state. **Nothing is persisted.**
3. **Confirm UI** — `ScannerScreen` renders the full-screen `PairingConfirmContent`: the fingerprint
   verbatim (monospace, selectable/copyable, content-described) with copy telling the user to compare it
   against their other device, plus Confirm and Decline buttons.
4. **Confirm** → the route-scope `confirmPairAndNavigate(state.server)` saves and navigates to the
   channel list. **This is the only `save` on the scan path.**
5. **Decline / system Back** → `ScannerEvent.DeclinePairing` → `ReadyToScan` — persists nothing and
   re-arms the scanner.

```
Decoded(payload)
   │  [MainActivity LaunchedEffect: parsePairingPayload + serverKeyFingerprint]
   ├─ parse Failure ─────────► PairingFailed(PARSE_FAILED_MSG) ─► Error
   ├─ derive null ───────────► PairingFailed(PARSE_FAILED_MSG) ─► Error
   └─ Success + fp ──────────► PairingPrepared(fp, server) ─► AwaitingConfirm(fp, server)
                                                                 │
        Confirm button ──[confirmPairAndNavigate]──► save(server); navigate(CHANNEL_LIST)
                                  └─ save fails ──► PairingFailed(SAVE_FAILED_MSG) ─► Error
        Decline / system Back ──DeclinePairing──► ReadyToScan   (nothing persisted)
```

## How it works

### The fingerprint↔record binding (the critical correctness property)

The displayed `fingerprint` and the saved `server` are the **two fields of one immutable
`AwaitingConfirm` data object**. The fingerprint was derived from *that* `server`'s key, and Confirm
saves *that same* `server` — `(state as? AwaitingConfirm)?.let { confirmPairAndNavigate(it.server) }`.
There is **no path that shows fp(A) but saves B**; no TOCTOU between display and persist. The developer
must read `state.server` at confirm time and **never re-parse or re-derive** — re-running the pipeline
would reintroduce a shown-vs-saved gap. This is the load-bearing review property of the slice.

### `serverKeyFingerprint` — decode → re-validate → derive

The derivation seam lives in [`PairingPayloadParser.kt`](pairing-payload-parser.md) (not `data/crypto`,
because it needs `base64StdDecode`, and `data/network → data/crypto` already holds):

```kotlin
fun serverKeyFingerprint(staticKeyBase64: String): String? {
    val bytes = try { base64StdDecode(staticKeyBase64) }
                catch (_: IllegalArgumentException) { return null }
    if (bytes.size != SERVER_STATIC_KEY_SIZE) return null   // 32
    return staticKeyFingerprint(bytes)                       // #342
}
```

It **mirrors `NoiseSessionFactory.create()`'s decode-then-revalidate** exactly: decode base64-std,
re-check exactly 32 bytes, derive — pure, synchronous, no I/O, **no logging, never throws**, never
echoes key bytes. The re-validate-to-`null` (rather than calling `staticKeyFingerprint` directly) keeps
that function's `require(size == 32)` **structurally unreachable** — an uncaught throw inside the
`LaunchedEffect` coroutine would crash the pairing flow. For a freshly-parsed `Success` the `null`
branch is unreachable (the parser already proved base64-std-of-32-bytes), so this is **deterministic
belt-and-suspenders** over #320's guarantee — different fabric (code, not a second stochastic check) —
that routes a hypothetical bad stored key to the same Error path as a parse failure.

### `PairingConfirmContent` — the full-screen confirm surface

A `private`, **stateless** composable in [`ScannerScreen.kt`](scanner-screen.md), a peer of the
`Error`/`Denied` `when(state)` branches (full-screen `Surface` + centered `Column`, mirroring
`ScannerErrorContent`). Chosen full-screen over a dialog/sheet because a security checkpoint should be
prominent, not reflexively dismissed, and it extends the screen's existing state-driven `when`.

- **Title** — *"Confirm the server fingerprint"* (`headlineSmall`).
- **Compare copy** — *"Check this matches the Static-key fp: line that pyry pair shows on your other
  device before you pair."* (`bodyMedium`, `onSurfaceVariant`).
- **Fingerprint** — rendered **verbatim** (already the #342 colon-lowercase-hex form; never uppercased,
  regrouped, or stripped), `FontFamily.Monospace`, wrapped in a `SelectionContainer` (selectable +
  long-press copy), with `Modifier.semantics { contentDescription = "Server fingerprint $fingerprint" }`.
- **Confirm** — `Button`, `fillMaxWidth().heightIn(min = 48.dp)`, label *"Confirm pairing"*.
- **Decline** — `OutlinedButton`, `fillMaxWidth().heightIn(min = 48.dp)`, label *"Don't pair"*.

**The token never reaches this composable** — it receives only the public-key `fingerprint` string +
two callbacks; the token-bearing `PairedServer` stays in `MainActivity` (read only for the save).

### Confirm is a callback, Decline is an event

The persist is a `suspend save` + navigation the `ScannerViewModel` cannot own (its contract: no
`viewModelScope`, no Android types). So **Confirm is deliberately not a `ScannerEvent`** — it is a
route-scope lambda (`confirmPairAndNavigate`, mirroring `stubPairAndNavigate`) wired like
`onPasteCode`/`onNavigateBack`. Only **Decline** is an event (`DeclinePairing → ReadyToScan`, a pure
state transition). This preserves #320's "orchestration lives in the composable, the VM stays a pure
reducer" decision rather than reintroducing a scope into the VM.

### System Back = Decline while confirming

`BackHandler(enabled = state is AwaitingConfirm) { vm.onEvent(DeclinePairing) }` makes system Back
behave as Decline (return to scanner, persist nothing) instead of popping the whole scanner route back
to Welcome. Disabled otherwise, so Back pops normally.

## Security properties

- **Nothing persists before the human confirms.** The store is touched in exactly two places:
  `confirmPairAndNavigate` (gated behind the Confirm button) and the out-of-scope paste stub
  (`stubPairAndNavigate`). The rewired `Decoded` effect touches the store **never**. Parse failures,
  derive failures, Decline, and Back all persist nothing — #320's never-throw / nothing-persisted-on-
  failure boundary stays intact.
- **No shown-vs-saved gap.** See the fingerprint↔record binding above — the display and the persist read
  the same immutable object.
- **Confirm-after-close is a no-op by construction.** `onConfirmPairing` reads the *current* collected
  state via `as? AwaitingConfirm`; if a back/decline already moved state to `ReadyToScan`, the cast is
  `null` and confirm does nothing — a save cannot fire after the gate closed.
- **No secret leak.** The token lives only inside the `PairedServer` in VM state and never reaches the
  rendered surface; `PairedServer.toString()` redacts it (so even a stray `Log("$state")` of
  `AwaitingConfirm`/`PairingPrepared` is byte-safe). The displayed fingerprint is a **public-key digest**
  (also printed by the desktop) — its display, content description, and copyability are the feature, not
  a leak. `serverKeyFingerprint` does no logging; derive/save failures log only fixed category strings +
  `e.javaClass.simpleName`.
- **The comparison is performed by the human eye** (phone vs. desktop), not by code — so constant-time
  comparison is N/A (there is no code-level compare of attacker input against a secret; that *is* the
  design).

## Edge cases and limitations

- **Design-later — no Figma.** The locked file `g2HIq2UyPhslEoHRokQmHG` has no confirm-pairing surface
  (re-verified 2026-06-01, 599 nodes). `PairingConfirmContent` is a clean, **deliberately
  non-decorative** Material 3 surface; a visual-fidelity retrofit is owed as a follow-up when the view is
  drawn, and the spec's design source re-anchors to the new `node-id` then. The security behavior
  (AC #1–#4) is visual-independent, so it does not wait on the pixels.
- **Double-confirm is benign and not guarded.** A fast double-tap fires two idempotent same-record
  `save`s (last-writer-wins overwrite) + two `navigate`s (`launchSingleTop` + `popUpTo` dedupe). A
  `Saving` state purely to prevent an unobserved, harmless double-write would be over-engineering —
  omitted (Evidence-Based Fix Selection). Same posture as the existing `stubPairAndNavigate`.
- **Tapjacking / overlay is out of scope (named).** A malicious overlay that hides the fingerprint and
  synthesizes a Confirm tap would bypass the human verification. Not introduced by this slice (every
  existing tap target shares it), requires a separately-installed app holding `SYSTEM_ALERT_WINDOW`
  (heavily gated on this min-SDK-33 target). The correct mitigation — obscured-touch filtering at the
  Activity-window level — is a cross-cutting hardening, **not a one-composable bolt-on**; a
  security-hardening follow-up is recommended for it (and any future sensitive confirm).
- **The paste path remains ungated.** `onPasteCode = stubPairAndNavigate` still persists the stub with no
  confirm gate; per #320 the real paste-input UI doesn't exist. A future real-paste ticket routes paste
  through `parsePairingPayload → serverKeyFingerprint →` this same gate.
- **TalkBack reads the raw colon-hex.** Whether TalkBack should spell the fingerprint group-by-group for
  easier audible verification is a design-time polish item for the owed Figma retrofit, not a blocker —
  the fingerprint is selectable/copyable today.

## Related

- [Scanner screen](scanner-screen.md) — hosts the `AwaitingConfirm` `when` branch + the rewired
  `Decoded` binding; owns the `ScannerViewModel` state machine this gate extends
- [Pairing payload parser](pairing-payload-parser.md) — produces the `PairingParseResult.Success` this
  gate sits behind; owns `serverKeyFingerprint`
- [Static-key fingerprint](static-key-fingerprint.md) — the #342 derivation (`staticKeyFingerprint`)
  this gate consumes; #343 is its first live consumer
- [Paired server store](paired-server-store.md) — the encrypted-at-rest persist target, gated behind
  Confirm
- Ticket: [#343](../codebase/343.md) — implementation notes. Depends on #320 (parse) + #342 (derivation),
  both merged; split from #321. Server SSOT pyrycode#432 (the `Static-key fp:` line the human compares
  against).
