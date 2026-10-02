# Pair with code

Manual pairing uses the full-screen `PairCodeScreen` at `pair_code`. The scanner's
paste actions open it from the viewport, permission-denied and error surfaces.
It accepts an optional local host name and a pairing code, asks the user to compare
the server fingerprint, then waits for that saved host's encrypted connection.
See [navigation](navigation.md#manual-pairing-entry-and-return) for caller return
and success routing.

The implementation lives in `ui/onboarding/PairCodeScreen.kt` and
`PairCodeViewModel.kt`. This document keeps its original path for existing links.
`PasteCodeDialog.kt` and its callback-contract tests remain as legacy components;
`PyryNavHost` no longer mounts the dialog.

## Form and rendering

`PairCodeScreen(state, onEvent, modifier)` is stateless. Its filled Host name and
Pairing code fields have independent clear controls: clearing one leaves the other
unchanged. Pair submits; persistence/connection failures change that action to
Retry, except a held `PairCodeState.failure` that is not retryable
(`Rejected`, \#1385), which disables the fields and keeps the button reading Pair, disabled.
Cancel and the toolbar back arrow share the Android Back event.
The footer opens `https://github.com/pyrycode/pyrycode-mobile`.

While Saving or Connecting, the Pair button draws a 20 dp indeterminate
`CircularProgressIndicator` (2 dp stroke, `colors.primary`, 8 dp end padding) before its
label, mirroring the modal's `State=Loading` button; the container, label colour and
enablement are unchanged (#1464, `663:2887`/`663:2963`). `PairCodeField` draws its
trailing clear `IconButton` only when `enabled` — a read-only field (both fields while
Saving/Connecting or holding a verification failure, and Host name in re-pair mode) shows
no clear icon, while an editable field keeps it even with an inline error
(`INVALID_CODE_ERROR`); without the icon the decoration box takes the 16 dp end padding
itself so the text keeps the frame's right inset. The decision that the code path's
wait and failures stay on this form rather than moving into the shared modal — only
`PairCodePhase.Confirming` uses that modal — was made explicit in #1464; see the Related
section's #1464 spec for the Figma frames this form was compared against.

The 412×892 mobile design uses theme colors and typography, an atmospheric glow,
the supplied 24 dp back-arrow asset and bottom-aligned 56 dp actions. Since #1462
the glow is the shared `Modifier.onboardingGlow()` from
[Welcome](welcome-screen.md#how-it-works) applied to the full-screen outer
`Column`, right after its `surface` background and before `systemBarsPadding()` —
the same Figma `6:32` transform scaled to the window, covering the header too.
Before #1462 this screen drew its own `drawBehind` radial gradient scaled
`scaleY = 1.5f` behind the body only, which left dark bands down both side edges
between the header and the Pair button; a vertically scaled ellipse does not
reach a full-width frame's corners the way the shared affine transform does.
`ScannerScreen`'s `Confirming` phase replaces this column entirely and is
unaffected. Each filled
field keeps its label at the top even when empty, with the draft below it and a
separate trailing clear control. A controlled `BasicTextField` puts label and text
inside one full-height editable surface; placing only the text line there made
most of the 56 dp well inert to taps. The field uses the theme's extra-small
shape, and invalid or wrong-host code feedback appears directly below the code
field with error semantics and a polite live region. Other failures stay near
Pair/Retry. Light and dark previews use the same layout and tokens.

The [412×892 Figma/emulator comparison](../../../app/src/androidTest/assets/pair-code-1269/comparison-412x892.png)
and [compact, enlarged-text and visible-IME captures](../../../app/src/androidTest/assets/pair-code-1269/)
record the route against the mobile frame. The older form reference has a
different field order and desktop card; the mobile frame supplies this route's
geometry. Figma has no keyboard frame, so that state follows the existing product
controls and behavior. The target-host, error, saving and connecting states are now
compared against the Pair Code States frames (`663:2887`–`663:3331`, #1464; see
[Testing](#testing) and `app/src/androidTest/assets/design-1220/onboarding/index.md`).

The form applies `imePadding()` and scrolls at smaller heights. The scrolled
column combines a viewport minimum height with `height(IntrinsicSize.Min)` before
its weighted field container distributes spare space. Without the intrinsic
minimum, the second field collapsed when the keyboard reduced the viewport;
adding scrolling alone did not keep both fields reachable.

The outer `Column` applies `systemBarsPadding()` before `imePadding()` (#1141),
matching [`ScannerViewport`](scanner-screen.md#scannerviewport--the-locked-viewport-body):
the `surface` background precedes inset padding, while the header sits below the
status bar and Cancel/the footer sit above the navigation bar. In production,
the activity applies and consumes its Scaffold padding at `PyryNavHost`, so the
screen's `systemBarsPadding()` receives no remaining system-bar space to add.
When hosted alone, that modifier reserves and consumes the bars itself.
`imePadding()` then adds only keyboard height beyond the bottom inset already
consumed by the activity or the screen. See
[navigation § Insets](navigation.md#configuration) for once-only ownership.
The toolbar Back `IconButton` is explicitly sized `Modifier.size(48.dp)`.
The pair route starts this target at the consumed status inset; the scanner
retains its separate 18 dp header offset. The
`Confirming` phase returns `ScannerScreen` before this `Column` is composed,
so it does not gain a second system-bar inset.

## Draft and fingerprint gate

The destination-scoped Koin `PairCodeViewModel` owns both draft strings in memory;
neither is written to `SavedStateHandle` or saved instance state. Configuration
changes retain the ViewModel, but process death or popping the destination loses
the draft. A newly opened destination starts empty.

Pair parses `code.trim()` with the existing
[`parsePairingPayload`](pairing-payload-parser.md), then derives
`serverKeyFingerprint`. Blank/invalid input produces the fixed inline
`Invalid pairing code` message and retains both draft fields verbatim. A valid
code creates `ScannerUiState.AwaitingConfirm(fingerprint, server)` without writing
anything. The screen reuses `ScannerScreen`'s existing
[fingerprint-confirmation surface](pairing-confirm-gate.md).

Confirm saves the exact record held beside the displayed fingerprint; it does
not parse the draft again. Decline or Android Back while confirming returns to
the unchanged draft without a write. Unlike the camera path, manual pairing owns
its orchestration in `PairCodeViewModel`; it does not feed `ScannerEvent.QrDecoded`.

## Persistence and local names

Confirm calls `confirmPairingAndConnect`: save credentials, start the registry's
controller, then report persistence success. A failed credential save never
starts a new connection. A successful save is followed by `setDisplayName` only
when `name.trim()` is nonblank. Credentials and names are separate transactions;
the registry can begin connecting before the name write finishes.

The [collection's identity contract](paired-server-store.md#the-contract) applies:

- Only the exact, case-sensitive `serverId` from the code is added or updated.
  Equal names or relay URLs never merge different ids.
- A nonblank trimmed name replaces that host's local metadata. It is not part of
  the pairing payload or wire protocol.
- A blank name skips the name write: a new host stays unnamed and an existing
  host keeps its name. Clearing the form field does not clear a saved name.
- Other hosts retain their credentials, names and connection bundles.

## Re-pairing a target host (#842)

The channel list's rejected-pairing plug control (see
[reconnect control](channel-list-screen-tree-and-controls.md#host-row-reconnect-control-840)) opens this
same screen scoped to one host via `Routes.pairCode(serverId)` → `pair_code?serverId=…`. `PairCodeViewModel`
takes an optional `target: String? = null`, sourced from `SavedStateHandle` in `AppModule`; a blank or
absent argument (the unrouted add-host entry, and every existing `Routes.PAIR_CODE` navigation) leaves
`target` null and the flow behaves exactly as above.

In target mode:

- `PairCodeState.targetName` is set to `target` immediately, then updated in the background to that host's
  stored display name if `store.loadById(target)` returns one — a store failure or missing record leaves
  the `serverId` label. The Host name field renders this name read-only; `PairCodeEvent.Name` is ignored.
- `PairCodeEvent.Pair` compares the parsed code's `serverId` to `target` with exact, case-sensitive `==`
  before the fingerprint gate opens. A mismatch fails with `WRONG_HOST_ERROR` ("This code is for a
  different host") and saves nothing. This reuses the same field-error slot `INVALID_CODE_ERROR` already
  occupied; `PairCodeField`'s `error` parameter changed from a `Boolean` to the error string itself so the
  screen can key both messages off the same slot instead of duplicating the field.
- `persist` skips `store.setDisplayName` entirely, so a same-id save keeps the stored name (the store
  already preserves it across a same-id save; see [paired-server store](paired-server-store.md)). Every
  other step — `confirmPairingAndConnect`, the connection wait, and its existing `PairingRejected` early
  exit below — is unchanged, so pasting a code identical to the rejected one still fails without waiting
  out the 30 s deadline, and a newly rejected credential is read the same way.

Cancel, a failed save or a second rejection in target mode leave every other saved host's record and
connection untouched: the only write in target mode is `store.save` of a record whose `serverId` equals
the target, which `RelayConnectionRegistry.reconcile` diffs per host.

## Target readiness and retry

Saving and calling `connect()` do not prove readiness. The ViewModel observes
`RelayConnectionRegistry.pairingStatus(confirmedRecord)`, which emits null until
reconciliation owns a bundle with the complete matching credentials. After
re-pairing, a connection using the old token/key cannot satisfy the wait. Neither
compatibility selection nor another host's healthy connection is consulted.

Since #1385, the wait and its outcomes are a shared, Android-free step,
`verifySavedPairing` in `ui/onboarding/PairingVerification.kt`, mirroring
desktop's `createPairingVerification` (`pairingReducer` /
`pairingState.ts`). It takes the saved `PairedServer` and the status-observing
flow and knows nothing about this screen. `PairCodeViewModel.verify`
calls it and holds the result in `PairCodeState.saved` (the record, kept across
a failure for Retry to wait on again) and `PairCodeState.failure` (the held
`PairingVerification.Failure`, if any — `error` carries its message). Since
[#1386](scanner-screen.md#state-model--scannerviewmodel), `ScannerViewModel`
calls the same step from its `Verifying`/`VerificationFailed` states, flattening
the failure into `message`/`retryable` fields instead (its public sealed state
cannot hold the `internal PairingVerification.Failure` directly).

The rule, applied to each status within a 30-second deadline
(`PAIRING_VERIFICATION_DEADLINE_MS`):

| Status | Outcome |
| --- | --- |
| relay `Connected` and pyrycode `Connected` | Succeeds; a bare relay socket is insufficient. |
| `null`, `Idle`, `Connecting`, `Reconnecting`, `Offline` | Keeps waiting — a relay blip no longer ends the wait (#1385; previously `Offline` failed it immediately). |
| `DaemonAbsent` | Fails, retryable: *"The host is temporarily unavailable. The pairing is saved. Retry to wait again, or Cancel."* |
| `PairingRejected` (#841) | Fails, **not** retryable: *"Pairing rejected. The saved host is retained. Cancel, then pair manually with a fresh code."* |
| `UpdateRequired` (#1008) | Fails, retryable: *"Pairing saved. This app is too old for this host. Update the app, then retry."* — the host's minimum version is never read, rendered or logged. |
| 30 s elapses, or the status flow completes without a decision | Fails, retryable, with the same unavailable text as `DaemonAbsent`. |

A just-saved pairing the host refuses (a `4401`/`4426` close on its very first
dial) reports `DaemonAbsent` and so fails immediately with the unavailable text
instead of waiting out the full 30 s. A just-saved pairing to a host that rejects
this app build (a `4412` close) fails just as immediately with the distinct
update-required text.

**Retry waits again; it never re-parses, re-confirms or saves.** While a
`PairCodeState.failure` is held, `PairCodeEvent.Pair` only re-verifies the same
`saved` record for a fresh 30 s deadline (`verify(saved, retry = true)`) if the
failure is retryable; a non-retryable failure (`Rejected`) ignores Pair entirely.
`Name`/`Code` events are also ignored while a failure is held — the draft fields
freeze, mirroring desktop hiding the inputs after the save. This is why
`PairCodeState` carries `saved` and `failure` rather than deriving a Retry target
from the draft.

On a retry, an absence already held over from the previous wait is not itself a
new failure: `verifySavedPairing`'s own `ignoreAbsence` flag (mirroring desktop's
`ignoredAbsence`) suppresses a `DaemonAbsent` seen before any other status in the
new wait, so a stale fake status flow that simply replays its last value cannot
immediately re-fail a fresh retry. The production registry's `pairingStatus`
separately treats an initial `DaemonAbsent`/`Offline` as stale on resubscription
and redials, emitting `null`; either guard alone would have been enough in
production, but a `MutableStateFlow`-backed test fake only replays its held
value, so the step needed its own guard to behave correctly under test. Each
wait (initial or Retry) collects `observe(saved)` afresh, so the deadline is a
fresh 30 s and the registry's own redial-on-resubscribe applies every time too.
Retry retains the registry's foreground and exact-bundle lifecycle checks.

## Failure and cancellation

Every failure returns to editing with both drafts retained and credential-free
feedback. The distinction between the writes matters:

| Failure | Saved state and next action |
| --- | --- |
| Invalid code or fingerprint derivation | No write; correct the code and Pair again (unchanged — re-parses on Retry). |
| Credential save | Existing collection unchanged; retry after storage recovers (unchanged — re-parses on Retry). |
| Name write after credential save | Pairing retained, prior name unchanged; feedback says the pairing was saved but the name was not (unchanged — re-parses on Retry). |
| Target unavailable, or the 30 s deadline (#1385) | Pairing and any successful name write retained; feedback says `The host is temporarily unavailable. The pairing is saved. Retry to wait again, or Cancel.`; Retry waits again on the saved record. |
| Target rejected the pairing (#841, #1385) | Pairing retained; feedback says `Pairing rejected. The saved host is retained. Cancel, then pair manually with a fresh code.`; Pair is disabled and not labelled Retry — only Cancel leaves. |
| Target too old for this app build (#1008) | Pairing and any successful name write retained; feedback says `Pairing saved. This app is too old for this host. Update the app, then retry.`; Retry waits again on the saved record. |

Only the first three rows re-parse the draft and cross the fingerprint gate
again on Retry — they set no `PairCodeState.failure`. The last three are
connection-verification failures (#1385): Retry re-verifies the already-saved
record for a fresh 30 s without parsing, confirming or saving again. See
[target readiness and retry](#target-readiness-and-retry). Cancel after partial
success makes no further changes and does not roll back the saved pairing.

The ViewModel enters Saving synchronously before launching persistence, rejecting
duplicate confirmation, field edits and Back while credential/name writes run.
Pair is accepted only while Editing. Connecting disables editing but allows
Cancel/Back: it cancels the wait and enters terminal Cancelled before the route
pops, so later readiness cannot navigate to the list. Registry connections remain
application/lifecycle owned; cancelling the screen's wait does not unpair or close
other hosts.

## Security properties

The parser establishes credential shape; the human fingerprint comparison
establishes trust before saving. Only the public fingerprint reaches the shared
confirmation content. Draft/state string output is redacted or does not include
field values. Debug-only `RelayLog` events contain static lifecycle/failure codes;
UI failures omit exception details, pairing codes, tokens, ids and names.

Draft secrecy does not protect against clipboard access, third-party keyboards,
screenshots or overlays. The existing
[confirmation gate's overlay limitation](pairing-confirm-gate.md#edge-cases-and-limitations)
still applies; this screen adds no such hardening contract.

## Testing

New `PairCodeScreenFormStatesTest` (Robolectric, `app/src/sharedTest`, #1464) covers the busy
ring and read-only clear icons: Saving and Connecting each show an indeterminate
`ProgressBarRangeInfo` node alongside their busy label, Editing shows neither; Saving,
Connecting and a held verification failure show no clear icon on either field, and re-pair
mode shows none on Host name while keeping "Clear pairing code"; a drafting field holding
`INVALID_CODE_ERROR` keeps both clear icons. `targetModeNamesTheHostReadOnlyAndShowsWrongHostOnTheCode`
(below) now asserts "Clear host name" with `assertDoesNotExist` rather than disabled, matching
the read-only field no longer drawing the icon at all.

New `PairingVerificationTest` (`ui/onboarding/`, no Android, `runTest` with virtual time) covers the
shared `verifySavedPairing` step directly over scripted `ConnectionStatus` flows (#1385): `Offline` then
relay+pyrycode `Connected` succeeds, as do `null`/`Idle`/`Connecting`/`Reconnecting` before `Connected`;
relay `Connected` with pyrycode not yet connected keeps waiting; `DaemonAbsent`, `PairingRejected` and
`UpdateRequired` produce their respective `Failure` with the right `retryable` flag and text; holding
`Offline` keeps waiting at 29 999 ms and fails as `Deadline` at 30 000 ms; and `retry = true` over a held
`DaemonAbsent` keeps waiting, while a fresh `DaemonAbsent` after some other status still fails it.

`PairCodeViewModelTest` covers immutable confirmation binding, decline/draft return,
duplicate confirmation, the persistence edit/Back lock, storage/name failures,
retained retry, blank-name preservation, deadline and cancellation, and (#841) a
`PairingRejected` status after save ending the wait immediately with the now-updated rejected-pairing
feedback rather than after the 30 s deadline.
(#1008) adds the `UpdateRequired` sibling, `updateRequiredEndsTheConnectionWaitImmediately`: a terminal
`UpdateRequired("1.4.0")` status after save ends the wait immediately too, with the distinct "Update the
app" feedback, and asserts the daemon-authored minimum is **not** echoed into the copy — the same
"immediately, not after the deadline" shape as #841's guard. Its case-sensitive peer fixture keeps `b` intact when pairing `B` with the same name.
(#842) target mode adds: a wrong-host code (including a case variant) refused before
confirmation with zero saves; replacing only the target host's record while a peer host
is left equal and its stored name is kept (`setDisplayName` never called); cancel and a
failed save leaving the store unchanged; and a rejection while connecting failing well
before the 30 s deadline.
(#1385) adds: an `Offline` status during the wait does not end it, and a following `Connected` still
completes it; after a `DaemonAbsent` failure, Pair re-enters Connecting without a new confirmation, saves
exactly once in total, is not ended by the held `DaemonAbsent` replaying from the test's
`MutableStateFlow` fixture, runs a fresh 30 s deadline from the Retry tap, and completes on `Connected`
with the save count still at one; after `PairingRejected`, Pair and Code events change nothing and Back
still ends in Cancelled. These updates also carry the new unavailable/rejected copy into
`failuresRetainDraftAndRetrySameHostWithoutLosingName`, `deadlineAndCancellationCannotNavigateLater`,
`rejectedPairingEndsTheConnectionWaitImmediately` and `targetModeRejectedWhileConnectingFailsBeforeTheDeadline`.

New `PairCodeScreenVerificationTest` (Robolectric, `app/src/sharedTest`, #1385): with a held `Rejected`
failure the button reads Pair, is disabled, and no node reads Retry, while Cancel still sends Back; with
a held `Unavailable` failure, Retry is enabled and sends Pair.

`RelayConnectionFactoryTest.pairingStatusWaitsForExactCredentialsAndKeepsConnectedPeer`
uses real Noise peers to pair/re-pair B while A stays connected, with equal names
and a shared relay. It holds B's handshake to distinguish socket from encrypted
readiness, checks credential replacement, and retries an already-unavailable B.
An already-connected A alone cannot establish success for B.

`PairCodeScreenTest` covers independent clears, validation and confirmation
callbacks, failure/Retry/Cancel, light/dark frames, and actual production-route
return via Cancel, toolbar and Android Back. It also checks empty labels above
the text line and reachability of inline errors and actions at 360×640 with
enlarged text. Its real-IME test selects the test keyboard before Activity
launch and uses the app's edge-to-edge/Scaffold shape. It taps the upper field
surface, asserts a full-height focus target and visible IME insets, then scrolls
to both fields, clear controls, Pair, Retry and Cancel and checks action bounds
above the keyboard. Focus or text input alone can pass with no keyboard; see
[Compose evidence](development-verification-compose-evidence.md#compose-evidence). A screenshot
of the Compose root also omits the keyboard window; the visible-IME captures use
`UiAutomation.takeScreenshot()`.

`PairCodeScreenGlowTest` (Robolectric, `app/src/sharedTest`, `@GraphicsMode(NATIVE)`,
dark theme, #1462) draws the root view to a bitmap in first-pair, re-pair
(`targetName`) and invalid-code states: both side edges at 30% height and the
header's right edge are bluer than plain surface (no dark bands, glow reaches
the header), and side-edge pixels in the header band and mid-body are
pixel-identical to `WelcomeScreen` at the same window size — proving the shared
modifier, not a separate reimplementation, produced the match.

`PairCodeScreenInsetsTest` (Robolectric, `app/src/sharedTest`) puts the host
activity edge to edge and applies fixed nonzero status and navigation insets
with `ViewCompat.dispatchApplyWindowInsets`, since Robolectric reports none on
its own. It asserts the pair Back target starts at the status inset and Cancel
and the footer clear the navigation inset. Reading `WindowInsets` in the test
content keeps Compose's inset listener attached; otherwise a fresh inset
request can replace the fixed values with zero and make the test pass
vacuously. The fixture has no production outer `Scaffold`, so the
[activity inset regressions](navigation.md#testing) also check the production
route's once-only inset ownership. The pair and scanner headers keep their
separate offsets.
Two (#842) additions: `targetedRouteNamesItsHostAndBackReturnsWithoutSaving` navigates through
`Routes.PAIR_CODE_ROUTE` with a `serverId` argument via a real `NavHostController`, asserts the
disabled Host name field shows the target's name, and Back returns to `Routes.WELCOME` without a
save — proving the `SavedStateHandle` → `target` wiring through `PyryNavHost` itself, not just the
ViewModel in isolation. `targetModeNamesTheHostReadOnlyAndShowsWrongHostOnTheCode` asserts the disabled
Host name field and that its clear control does not exist (#1464), and that `WRONG_HOST_ERROR` renders under the code field
while the action button still reads "Pair" (a field error, not the "Retry" case).

`ChannelListScreenTest.hostRowPlugControl_onARejectedPairing_opensRePairingForItsOwnHost` (#842) asserts a
`PairingRejected` host's plug control emits `TreeHostRePairTapped(serverId)` while a sibling `Offline`
host's plug still emits `TreeHostReconnectTapped(serverId)` — the existing
`hostRowReconnectControl_targetsItsOwnHost_andLeavesEveryHostsRowsDrawn` (#840) test is unchanged and
keeps proving the retry event for its own (non-rejected) fixtures.

Existing `InteractiveStreamE2ETest` regressions and the
[live gate](../../e2e-interactive-stream.md#pre-ship-gate) remain unchanged.
[#1085](https://github.com/pyrycode/pyrycode-mobile/issues/1085)'s
`interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched` now proves named
B pairing through this screen (by code, via `pairHostByCode`), followed by rename and
unpair from the second host's Edit host modal, while host A keeps its label, its
conversations and its own connection instance. It pairs by code, not by scanning a
real QR, so real camera QR capture through this flow stays unproven live; that
remains [#676](https://github.com/pyrycode/pyrycode-mobile/issues/676)'s scope.

## Related

- [Pair-with-code design and revisions](../../specs/architecture/639-pair-with-code.md);
  [system-bar insets and the 48 dp Back target](../../specs/architecture/1141-pair-code-system-bar-insets.md)
  (#1141); [full-screen Figma glow](../../specs/architecture/1462-pair-code-full-screen-glow.md) (#1462);
  [busy ring and read-only clear icons](../../specs/architecture/1464-pair-code-form-states.md) (#1464)
- [Navigation](navigation.md) § [Insets](navigation.md#configuration), [scanner](scanner-screen.md) and
  [paired-server collection](paired-server-store.md)
- [Legacy dialog history](../codebase/501.md): store-free callback contract retained
  by `PasteCodeDialogTest`; it does not test the production full-screen flow.
- [Relay link status](relay-link-status.md) § `PairingRejected` and
  [Relay reconnect supervisor](relay-reconnect-supervisor.md) § Halt on a rejected pairing — the source
  of the `PairingRejected` status this screen's terminal predicate now checks (#841, spec:
  `docs/specs/architecture/841-rejected-pairing-relay-state.md`).
- [Relay link status](relay-link-status.md) § `UpdateRequired` — the source of the `UpdateRequired`
  status this screen's terminal predicate also checks, and its own distinct failure copy (#1008, spec:
  `docs/specs/architecture/1008-update-required-halt.md`).
- [Channel list tree and controls](channel-list-screen-tree-and-controls.md#host-row-reconnect-control-840)
  § the plug control's `PairingRejected` branch, the caller into target mode (#842, spec:
  `docs/specs/architecture/842-repair-rejected-host-from-tree-row.md`).
- The shared `verifySavedPairing` wait/failure/retry rule (#1385, spec:
  `docs/specs/architecture/1385-pairing-verification-rule.md`), mirroring pyrycode-desktop's
  `createPairingVerification`/`pairingReducer`. The QR scanner path's adoption of the same step is a
  sibling ticket split from #1322.
