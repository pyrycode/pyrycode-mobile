# Pairing confirm gate

The **security checkpoint of QR and manual pairing** ([#343](../codebase/343.md)) — the mobile half of
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
3. **Confirm UI** — `ScannerScreen` renders `PairingConfirmContent` inside the shared
   [mobile modal](mobile-modal.md): the fingerprint verbatim (monospace,
   selectable/copyable, content-described) with an explicit comparison instruction,
   plus `Confirm pairing` and `Don't pair` footer actions.
4. **Confirm** → `ScannerViewModel` saves the record, then **waits for the host to answer** before
   reporting the pairing done (#1386). The modal stays up in its loading state;
   the channel list opens only once the shared [`verifySavedPairing`](paste-code-dialog.md#target-readiness-and-retry)
   step reports success. **This is the only `save` on the scan path**, across any number of retries.
5. **Decline / system Back** → `ScannerEvent.DeclinePairing` (before saving) or
   `ScannerEvent.CancelVerification` (during the wait or after a failure) → persists nothing new and
   leaves the scanner: Decline re-arms it (`ReadyToScan`), Cancel pops the route with the host already
   saved kept as-is.

```
Decoded(payload)
   │  [MainActivity LaunchedEffect: parsePairingPayload + serverKeyFingerprint]
   ├─ parse Failure ─────────► PairingFailed(PARSE_FAILED_MSG) ─► Error
   ├─ derive null ───────────► PairingFailed(PARSE_FAILED_MSG) ─► Error
   └─ Success + fp ──────────► PairingPrepared(fp, server) ─► AwaitingConfirm(fp, server)
                                                                 │
        ConfirmPairing ──► Verifying(fp, server) ──[persist: save, then verifySavedPairing]──┐
                                  │                                                           │
                       save fails: Error(SAVE_FAILED_MESSAGE)                                 │
                                                                                               │
              ┌────────────────────────────── outcome ───────────────────────────────────────┘
              ├─ Connected ──────────────────────────────────────────────────► Paired (route navigates)
              └─ Failure(message, retryable) ───────────────────────► VerificationFailed(fp, server, …)
                                                                             │
                                          RetryVerification (if retryable) ─┴─► Verifying(fp, server) (re-wait, no re-save)
                                          CancelVerification (from Verifying or VerificationFailed) ─► Cancelled (route pops; host stays saved)
        DeclinePairing (from AwaitingConfirm only) ──► ReadyToScan   (nothing persisted)
```

The [pair-with-code screen](paste-code-dialog.md) uses the same immutable
`AwaitingConfirm` value and `ScannerScreen` confirmation modal. Its own
`PairCodeViewModel` parses the trimmed draft, then handles Confirm through
`confirmPairingAndConnect`; it no longer sends a paste through the camera's
`QrDecoded`/`Decoded` events. Decline/Back restores the draft. After saving it
applies an optional local name and waits for the exact saved credentials' relay
and encrypted-session readiness before navigation. See
[manual pairing entry and return](navigation.md#manual-pairing-entry-and-return).

## How it works

### The fingerprint↔record binding (the critical correctness property)

The displayed `fingerprint` and the saved `server` are the **two fields of one immutable
`AwaitingConfirm` data object**, and `ScannerViewModel.onEvent(ConfirmPairing)` carries both fields
forward into `Verifying(fingerprint, server)` and then into `VerificationFailed`/`Paired` — the VM
never re-reads the state from outside itself, so Confirm always saves and waits on *that same* `server`
the shown fingerprint was derived from. There is **no path that shows fp(A) but saves B**; no TOCTOU
between display and persist. The developer must carry `server` forward through every later state and
**never re-parse or re-derive** — re-running the pipeline would reintroduce a shown-vs-saved gap. This
is the load-bearing review property of the slice.

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

### `PairingConfirmContent` — the shared confirmation modal

A `private`, **stateless** composable in [`ScannerScreen.kt`](scanner-screen.md), a peer of the
`Error`/`Denied` `when(state)` branches. Both QR and code routes present the existing
`MobileModal` shell with its dark rounded container, close control, scrolling content
and footer.

- **Title** — *"Pair"* in the modal header.
- **Compare copy** — *"Verify that this fingerprint matches the Static-key fp: line shown by pyry pair
  on your other device before you pair."* (`bodyMedium`, centered, `onPrimaryContainer`).
- **Fingerprint** — rendered **verbatim** (already the #342 colon-lowercase-hex form; never uppercased,
  regrouped, or stripped), `FontFamily.Monospace`, wrapped in a `SelectionContainer` (selectable +
  long-press copy), with `Modifier.semantics { contentDescription = "Server fingerprint $fingerprint" }`.
  The full-width `surfaceVariant` panel uses `MaterialTheme.shapes.small`, 12 dp horizontal and
  16 dp vertical padding, centered `titleLarge` text, and can wrap at compact width or larger text.

**The token never reaches this composable** — it receives only the public-key `fingerprint` string +
callbacks; the token-bearing `PairedServer` stays in `ScannerViewModel` (or `PairCodeViewModel`) state
for the save and the wait.

#### Confirm, the wait and a failure share one modal window (#1386)

`ScannerScreen` renders `AwaitingConfirm`, `Verifying` and `VerificationFailed` from **one** `when`
branch and derives the footer labels, `loading`, `error` and the submit/dismiss callbacks from the
current state inside it, rather than three separate branches each calling `PairingConfirmContent`
independently:

| State | Dismiss label → action | Submit | `loading` | `error` |
| --- | --- | --- | --- | --- |
| `AwaitingConfirm` | Don't pair → Decline | Confirm pairing → Confirm | no | — |
| `Verifying` | Cancel → Cancel the wait | Confirm pairing (disabled, spinner) | yes | — |
| `VerificationFailed`, retryable | Cancel → Cancel the wait | Retry → Retry | no | the step's `message` |
| `VerificationFailed`, not retryable | Cancel → Cancel the wait | *(none — null `submitLabel`)* | no | the step's `message` |

The dialog's own dismiss paths (close glyph, Back) route to the same callback as that row's labelled
dismiss action.

This single-branch shape is not cosmetic: a Compose `Dialog` keyed on separate `when` branches is a
**new window per branch**. An earlier revision rendered `AwaitingConfirm`/`Verifying`/`VerificationFailed`
from three branches; a verifier review found the modal was being torn down and recreated at each
transition — the window reopened and TalkBack focus reset on every state change, so the confirm modal
did not literally "stay up" through the wait as the ticket requires. No text- or button-presence
assertion catches this; `ScannerScreenTest` proves it by comparing the **semantics node id** of a node
inside the dialog across the transitions.

### Confirm, Retry and Cancel are events; the VM owns the wait (#1386)

`ScannerViewModel` stopped being a pure synchronous reducer with #1386: `ConfirmPairing` launches a
`viewModelScope` job that saves through `confirmPairingAndConnect` and then awaits the shared
[`verifySavedPairing`](paste-code-dialog.md#target-readiness-and-retry) step on
`RelayConnectionRegistry.pairingStatus` for the saved record — the same step [#1385](../../specs/architecture/1385-pairing-verification-rule.md)
extracted for `PairCodeViewModel`. Putting the wait in the VM (not a route-scope coroutine) is what lets
it survive rotation: a `rememberCoroutineScope` job in the route would be cancelled on a configuration
change and strand the modal in its loading state. The one held `Job` is cancelled by
`CancelVerification` and by `onCleared`; a result is applied only while the state is still `Verifying`
for that exact server, so a late resume can never navigate after Cancel. While `Verifying` or
`VerificationFailed`, every event other than `RetryVerification`/`CancelVerification` is ignored — this
closes a concurrency gap the security review flagged: the route's permission-check `LaunchedEffect(Unit)`
re-sends `PermissionGranted` on rotation, which under the old unconditional transition would have
replaced the modal with `ReadyToScan` while the wait kept running underneath.

This also closes the camera double-confirm gap this document used to flag as open (see "Camera
double-confirm" under Edge cases below): `ConfirmPairing` moves state to `Verifying` synchronously
before launching the save, so a second tap while saving is a no-op.

### System Back = Decline/Cancel while confirming or waiting

`BackHandler(enabled = state is AwaitingConfirm) { vm.onEvent(DeclinePairing) }` makes system Back
behave as Decline (return to scanner, persist nothing) while the fingerprint is still unconfirmed. A
second `BackHandler(enabled = state is Verifying || state is VerificationFailed) { vm.onEvent(CancelVerification) }`
covers the wait and a held failure: Back there stops waiting, keeps the saved host and pops the scanner
route rather than returning to `ReadyToScan`. Both are disabled otherwise, so Back pops normally.

## Security properties

- **Nothing persists before the human confirms.** Camera parsing prepares the
  gate in the route's `Decoded` effect; manual parsing prepares it in
  `PairCodeViewModel`. Neither writes. Both Confirm handlers call
  `confirmPairingAndConnect` with the record held in `AwaitingConfirm`.
  Parse/derive failures and Decline/Back from confirmation persist nothing.
  After Confirm, manual pairing can retain saved credentials despite a later
  name or connection failure; its feedback explicitly reports that partial success.
  Since [#1385](../../specs/architecture/1385-pairing-verification-rule.md), the post-save connection wait
  itself is a shared, Android-free step (`verifySavedPairing`) that classifies each saved host's status
  into waiting, success, or one of three static failures (unavailable/deadline, rejected, update-required)
  — see [pair-with-code § target readiness and retry](paste-code-dialog.md#target-readiness-and-retry).
  Retry re-verifies the already-saved record for a fresh wait; it never re-saves, so this gate's "Confirm
  is the only save" property holds across any number of retries.
- **No shown-vs-saved gap.** See the fingerprint↔record binding above — the display and the persist read
  the same immutable object.
- **Confirm-after-close is a no-op by construction.** `ScannerViewModel.onEvent` only acts on
  `ConfirmPairing` while the current state is `AwaitingConfirm`; once a decline/cancel has already
  moved state elsewhere, the event falls through to the ignored branch and confirm does nothing — a save
  cannot fire after the camera gate closed. The same synchronous-transition guard makes a second
  `ConfirmPairing` tap while already `Verifying` a no-op (see "Camera double-confirm" below).
  Manual Confirm is accepted only in Confirming and synchronously changes phase
  to Saving, so stale or repeated confirmation events cannot start another write.
- **No secret in confirmation content or diagnostics.** Tokens remain in the
  credential record and, for manual input, the editable draft; confirmation
  content receives only the public fingerprint. `PairedServer.toString()` redacts
  the record (so even a stray `Log("$state")` of
  `AwaitingConfirm`/`PairingPrepared` is byte-safe). The displayed fingerprint is a **public-key digest**
  (also printed by the desktop) — its display, content description, and copyability are the feature, not
  a leak. `serverKeyFingerprint` does no logging. Camera derive/save failures use
  fixed categories and exception class names; manual pairing logs only static
  lifecycle/failure codes and never exception details or draft fields.
- **The comparison is performed by the human eye** (phone vs. desktop), not by code — so constant-time
  comparison is N/A (there is no code-level compare of attacker input against a secret; that *is* the
  design).

## Edge cases and limitations

- **Design references.** The [older verification content](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2559)
  is adapted into the current [mobile modal shell](mobile-modal.md), with the explicit desktop
  comparison instruction retained. There is no dedicated mobile verification or keyboard-open
  confirmation frame. The [labelled 412 × 892 comparison](../../../app/src/androidTest/assets/pairing-1270/labelled-comparison-412x892.png)
  and [compact 360 × 640 capture](../../../app/src/androidTest/assets/pairing-1270/qr-confirm-360x640-dark-1.5x.png)
  use synthetic records and show the full fingerprint and actions at 1.5× text scale.
- **Camera double-confirm is now guarded (#1386).** `ConfirmPairing` moves state to
  `Verifying` synchronously before the save launches, so a second tap while `AwaitingConfirm` has already
  left saves once. This mirrors manual pairing's synchronous Saving guard, which also blocks editing and
  dismissal until credential/name persistence finishes.
- **Tapjacking / overlay is out of scope (named).** A malicious overlay that hides the fingerprint and
  synthesizes a Confirm tap would bypass the human verification. Not introduced by this slice (every
  existing tap target shares it), requires a separately-installed app holding `SYSTEM_ALERT_WINDOW`
  (heavily gated on this min-SDK-33 target). The correct mitigation — obscured-touch filtering at the
  Activity-window level — is a cross-cutting hardening, **not a one-composable bolt-on**; a
  security-hardening follow-up is recommended for it (and any future sensitive confirm).
- **Manual pairing shares the gate, with its own draft lifecycle.** The
  [full-screen form](paste-code-dialog.md) replaced the production paste-dialog
  entry. It reuses fingerprint derivation, immutable record binding and the
  confirmation content; declining returns to the form rather than re-arming a
  camera. The legacy store-free dialog remains in source but is not mounted.
- **TalkBack reads the raw colon-hex.** Whether TalkBack should spell the fingerprint group-by-group for
  easier audible verification remains open; the fingerprint is selectable/copyable today.

## Related

- [Scanner screen](scanner-screen.md) — hosts the `AwaitingConfirm` `when` branch + the rewired
  `Decoded` binding; owns the `ScannerViewModel` state machine this gate extends
- [Pairing payload parser](pairing-payload-parser.md) — produces the `PairingParseResult.Success` this
  gate sits behind; owns `serverKeyFingerprint`
- [Static-key fingerprint](static-key-fingerprint.md) — the #342 derivation (`staticKeyFingerprint`)
  this gate consumes; #343 is its first live consumer
- [Paired server store](paired-server-store.md) — the encrypted-at-rest persist target, gated behind
  Confirm
- [Pair-with-code § target readiness and retry](paste-code-dialog.md#target-readiness-and-retry) — the
  shared `verifySavedPairing` step both the manual and camera paths wait on after Confirm
- [Shared mobile modal](mobile-modal.md) — the nullable `submitLabel` (#1386) a non-retryable failure uses
  to drop Retry from the footer
- Ticket: [#343](../codebase/343.md) — implementation notes. Depends on #320 (parse) + #342 (derivation),
  both merged; split from #321. Server SSOT pyrycode#432 (the `Static-key fp:` line the human compares
  against).
- Ticket #1386, spec [`docs/specs/architecture/1386-scanner-pairing-verification.md`](../../specs/architecture/1386-scanner-pairing-verification.md)
  — routes the camera path through the shared `verifySavedPairing` step from
  [#1385](../../specs/architecture/1385-pairing-verification-rule.md), split from #1322. Rung-3 live coverage
  is deferred to #1394.
