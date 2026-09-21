# Pair with code (#639)

## Files read

- `MainActivity.kt` → `PyryNavHost`, `Routes`: scanner paste entry and destination ownership.
- `ui/onboarding/PasteCodeDialog.kt` → `PasteCodeDialog`: existing trim/parser contract; leave the old component and regressions intact.
- `ui/onboarding/ScannerViewModel.kt`, `ScannerScreen.kt` → `ScannerUiState.AwaitingConfirm`, `ScannerScreen`: immutable fingerprint/record binding and reusable confirmation surface.
- `ui/onboarding/PairingConfirmation.kt` → `confirmPairingAndConnect`: save-before-connect ordering and narrow storage failure boundary.
- `data/network/PairingPayloadParser.kt` → `parsePairingPayload`, `serverKeyFingerprint`: validation and public fingerprint derivation.
- `data/crypto/PairedServerStore.kt`, `di/ObservablePairedServerStore.kt` → collection/name contract and successful-write notifications.
- `di/RelayConnectionRegistry.kt`, `RelayConnectionFactory.kt` → `reconcile`, `retryHost`, immutable per-record bundles.
- `data/model/ConnectionStatus.kt`, `RelayLinkStatus.kt`, `PyrycodeLinkStatus.kt`: encrypted readiness differs from socket readiness.
- `ui/onboarding/WelcomeScreen.kt` → `WelcomeScreen`: themed glow, CTA/footer style.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt` → `Fixture`: real Noise peers and retained-host regression seams.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalTest.kt` → real test IME setup before activity launch.
- `docs/knowledge/features/paired-server-store.md` § The contract, Wiring & usage; `pairing-confirm-gate.md` § Security properties: exact identity, encrypted custody, immutable confirmation.
- `docs/knowledge/features/paste-code-dialog.md`, `navigation.md`, `development-verification.md`: draft secrecy, route ownership and actual IME evidence.
- Sibling `pyrycode/docs/protocol-mobile.md` § Pairing flow: authoritative unchanged credential contract.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2147

The 412×892 frame has a back/title row and faint divider, a blue atmospheric glow behind two filled M3 fields separated by 64 dp, and bottom-aligned 56 dp Pair/Cancel actions plus a muted source footer. Use surface/primaryContainer/onPrimaryContainer/inversePrimary and M3 titleLarge/labelSmall tokens; each field has its own circular clear icon. The footer targets the mobile repository per the ticket; confirmation retains the existing scanner surface.

## Context and size

One deliverable: named manual pairing through confirmation and target readiness. Forecast ~790 written lines including tests and this plan, four production files, five top-level declarations, no changed public signatures or consumer cascade, five AC, and six classified rejection paths (parse, fingerprint, credentials, name, unavailable, deadline). This fits the refiner's ~780-line hypothesis within normal sketch precision. Refreshed all remote feature branches: no overlap in planned production/test files. Codegraph provided entry points but no helper callees; source reads filled that gap.

## Design

- Add `PairCodeViewModel` and its state/event types under `ui/onboarding/`. Draft fields remain in memory, never saved instance state. Hold the existing `ScannerUiState.AwaitingConfirm` value; Confirm saves that exact record without reparsing.
- Add stateless `PairCodeScreen`, reusing `ScannerScreen` for the confirmation state. Editing uses independent filled fields, accessible clear controls, inline static feedback, Pair/Retry and Cancel. A scrollable minimum-height content column preserves the frame's alignment at full size and makes every control reachable with IME on small screens. Add light/dark previews.
- Add `Routes.PAIR_CODE` and destination in `PyryNavHost`; scanner paste actions navigate there. Back/Cancel pop to caller. Success navigates to channel list and clears onboarding entries. Construct a destination-scoped VM with the observable collection and registry via the existing Compose ViewModel factory API.
- Add an internal registry flow for a supplied complete `PairedServer`: emit null until reconciliation owns exactly those credentials, then follow that bundle's coordinator status. On collection retry only an unavailable matching bundle, using `retryHost`; never use compatibility selection. Re-pairing replaces the observed bundle, not other hosts.
- Confirm calls unchanged `confirmPairingAndConnect`, then writes a nonblank trimmed local name. Blank names skip the write. Credentials and names are separate transactions: explicitly report retained pairing on name/connection failures. Retry repeats exact-id upsert behind confirmation; it never creates another id.

## State + concurrency model

One Main-owned state flow and `viewModelScope` operation. Mark Saving synchronously before launch so repeated taps and edit/back events cannot race persistence. After persistence/name writes, transition to Connecting and wait at most 30 seconds for the exact record's encrypted readiness; DaemonAbsent/Offline terminate earlier. Cancel during Connecting cancels the wait and enters terminal Cancelled before navigation, fencing later completion. Decline/Back during confirmation restores the unchanged draft. VM teardown cancels all work; registry connections remain application/lifecycle owned and background close stays authoritative.

## Error handling

Parser/fingerprint failures remain inline; storage exceptions become fixed credential-free text without exception details. Credential failure never calls connect. Name failure and unavailable/deadline failures explicitly state that pairing is saved. Draft stays editable after failures, with retry returning through fingerprint confirmation. Debug-only `RelayLog` records static lifecycle/error codes, never inputs, names, ids or throwable messages. No new wire, schema or dependency.

## Testing strategy

- JVM: invalid/trimmed input, confirmation binding and decline, duplicate taps, persistence edit/back lock, save/name failures, retained retry, deadline/early unavailable and cancelled-wait fence. Real registry/Noise fixture: pair/re-pair B with equal names/shared relay while connected A survives; stale B credentials and bare socket cannot satisfy readiness.
- Compose: clear independence, inline validation, confirmation/back and failure/retry/cancel, actual test IME with forced 360×640 viewport and reachable fields/actions. Capture 412×892 render for comparison against Figma.
- Run scoped JVM classes, affected managed-device class, lint, assembleDebug, androidTest compilation and Spotless. Full UI/scripted gates and existing live suite belong to dispatcher; #676 owns the future real two-host screen/rename/unpair scenario.

## Open questions

None. No scanner redesign or list entry changes (#640/#641).

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/paste-code-dialog.md` and `docs/knowledge/features/navigation.md` to describe the full-screen entry/return flow, optional-name rules, fingerprint gate, and retained-pairing behavior after a failed connection or name write.

## Security review

**Verdict:** PASS

- Trust boundaries: `parsePairingPayload` validates untrusted code; `AwaitingConfirm` binds fingerprint to the saved record. Confirmation alone authorizes persistence.
- Tokens/storage: reuse app-private Keystore-wrapped collection and atomic existing mutations; keep drafts in memory with redacted state/events. No token generation, expiry/revocation, paths, backup or schema changes.
- Android surface: only a fixed source-code HTTPS link; no exported component, deep link, provider, pending intent or WebView.
- Cryptography/network: unchanged Noise_IK factory and relay URL parser; matching complete records prevents stale-session success. Existing TLS/backoff remain; the screen adds a bounded wait without changing transport policy.
- Errors/telemetry: static feedback and debug-only structured event codes; no exception causes or draft interpolation. No analytics.
- Concurrency: synchronous stage guard before launching persistence, cancellation fence before leaving connection wait, immutable record matching after reconciliation. No global scope or additional locks.
- Threat model: malicious relay cannot fake encrypted readiness; disk token custody stays encrypted. Existing overlay/screenshot/third-party IME exposure remains the documented cross-cutting residual in `pairing-confirm-gate.md`/`paste-code-dialog.md`; this change adds no hardening contract. No new daemon text rendering.

**Reviewer:** builder (self-review per `builder/security-review.md`). **Date:** 2026-09-21.
