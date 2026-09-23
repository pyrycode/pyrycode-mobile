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
Retry. Cancel and the toolbar back arrow share the Android Back event.
The footer opens `https://github.com/pyrycode/pyrycode-mobile`.

The 412×892 design uses theme colors and typography, an atmospheric glow and
bottom-aligned 56 dp actions. Native M3 empty labels remain centered until focus
or input, an explicit adaptation from the supplied frame's floated empty labels.
Light and dark previews use the same layout and tokens.

The form applies `imePadding()` and scrolls at smaller heights. The scrolled
column combines a viewport minimum height with `height(IntrinsicSize.Min)` before
its weighted field container distributes spare space. Without the intrinsic
minimum, the second field collapsed when the keyboard reduced the viewport;
adding scrolling alone did not keep both fields reachable.

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

## Target readiness and retry

Saving and calling `connect()` do not prove readiness. The ViewModel observes
`RelayConnectionRegistry.pairingStatus(confirmedRecord)`, which emits null until
reconciliation owns a bundle with the complete matching credentials. After
re-pairing, a connection using the old token/key cannot satisfy the wait. Neither
compatibility selection nor another host's healthy connection is consulted.

Success requires both `RelayLinkStatus.Connected` and
`PyrycodeLinkStatus.Connected` for that record. A bare relay socket is insufficient.
The connection wait has a 30-second deadline; a reported `DaemonAbsent`,
`PairingRejected` (#841) or `Offline` ends it earlier. A just-saved pairing the
host refuses (a `4401`/`4426` close on its very first dial) therefore fails
immediately with the same "Pairing saved. Host unavailable." feedback below,
instead of waiting out the full 30 s — no new copy for this case.

When a matching bundle is already unavailable, `pairingStatus` subscribes to its
coordinator before requesting `retryHost(serverId, expectedBundle)` and suppresses
only the initial stale unavailable status. Reading the terminal status immediately
after requesting Retry reused the previous attempt's failure and rejected the new
attempt before it could connect. Later unavailable emissions still end the wait.
Retry retains the registry's foreground and exact-bundle lifecycle checks.

## Failure and cancellation

Every failure returns to editing with both drafts retained and credential-free
feedback. The distinction between the writes matters:

| Failure | Saved state and next action |
| --- | --- |
| Invalid code or fingerprint derivation | No write; correct the code and Pair again. |
| Credential save | Existing collection unchanged; retry after storage recovers. |
| Name write after credential save | Pairing retained, prior name unchanged; feedback says the pairing was saved but the name was not. |
| Target unavailable or deadline | Pairing and any successful name write retained; feedback says `Pairing saved. Host unavailable. Retry or cancel.` |

Retry returns through validation and the fingerprint gate. With the unchanged
code, it upserts the same id without another entry. Cancel after partial success
makes no further changes and does not roll back the saved pairing.

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

`PairCodeViewModelTest` covers immutable confirmation binding, decline/draft return,
duplicate confirmation, the persistence edit/Back lock, storage/name failures,
retained retry, blank-name preservation, deadline and cancellation, and (#841) a
`PairingRejected` status after save ending the wait immediately with the existing
"Pairing saved. Host unavailable." feedback rather than after the 30 s deadline.
Its case-sensitive peer fixture keeps `b` intact when pairing `B` with the same name.

`RelayConnectionFactoryTest.pairingStatusWaitsForExactCredentialsAndKeepsConnectedPeer`
uses real Noise peers to pair/re-pair B while A stays connected, with equal names
and a shared relay. It holds B's handshake to distinguish socket from encrypted
readiness, checks credential replacement, and retries an already-unavailable B.
An already-connected A alone cannot establish success for B.

`PairCodeScreenTest` covers independent clears, validation and confirmation
callbacks, failure/Retry/Cancel, light/dark frames, and actual production-route
return via Cancel, toolbar and Android Back. Its 360×640 test selects the real
test IME before Activity launch and uses the app's edge-to-edge/Scaffold shape.
It asserts visible IME insets, scrolls to both fields and clear controls, and
checks action bounds above the keyboard. Focus or text input alone can pass with
no keyboard; see [Compose evidence](development-verification.md#compose-evidence).

Existing `InteractiveStreamE2ETest` regressions and the
[live gate](../../e2e-interactive-stream.md#pre-ship-gate) remain unchanged. They do
not prove named B pairing through this screen followed by rename/unpair while A
remains intact. That real-daemon/live-relay scenario belongs to
[#676](https://github.com/pyrycode/pyrycode-mobile/issues/676).

## Related

- [Pair-with-code design and revisions](../../specs/architecture/639-pair-with-code.md)
- [Navigation](navigation.md), [scanner](scanner-screen.md) and
  [paired-server collection](paired-server-store.md)
- [Legacy dialog history](../codebase/501.md): store-free callback contract retained
  by `PasteCodeDialogTest`; it does not test the production full-screen flow.
- [Relay link status](relay-link-status.md) § `PairingRejected` and
  [Relay reconnect supervisor](relay-reconnect-supervisor.md) § Halt on a rejected pairing — the source
  of the `PairingRejected` status this screen's terminal predicate now checks (#841, spec:
  `docs/specs/architecture/841-rejected-pairing-relay-state.md`).
