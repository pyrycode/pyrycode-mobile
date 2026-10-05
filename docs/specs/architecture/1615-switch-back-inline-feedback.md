# Switch-back offer geometry and inline failure (#1615)

## Files read

- `ModelRefusalRow.kt`: `ModelRefusalRowContent`, `SwitchBackAction`, `appendModel`; preserve the inert model-text boundary and row-local expansion.
- `ThreadViewModel.kt`: `onSwitchBack`, `sendSessionSettings`, `switchBackOffer`; shared pending-model ownership and identity-checked acknowledgement/failure.
- `ThreadViewModelRefusalOfferTest.kt`: `RecordingRepo` and retry coverage.
- `ThreadRunConfigModelSelectionTest.kt`: selection projection; `ThreadViewModelTest.kt`: ordinary write feedback and scope-cancellation coverage.
- `ModelRefusalSwitchBackTest.kt`: independent button and details actions.
- `ThreadDesignCaptureTest.kt`: `refusalStateFramesAt412By892`, `threadNoticeFramesAt412By892`; immediate failure evidence currently waits out a duplicate snackbar.
- `DesignCapture.kt`, `ViewportRule.kt`: real-system-bar screenshots and density/font-scale setup.
- `docs/knowledge/features/model-refusal-row.md`: an acknowledged switch-back also remembers the model; preserve that existing side effect and separate model spans.
- `docs/knowledge/features/thread-screen.md` and `development-verification-compose-evidence.md`: compare visible geometry separately from touch bounds; synthetic-bar runs do not prove pixels.
- Sibling `pyrycode/docs/protocol-mobile.md`: unchanged `set_session_settings` and model-refusal contract; protocol remains the source of truth.

## Design source

**Figma:** pending https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=646-4694; failed https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=646-4700; armed screen https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=646-4707.

Inspected context and screenshots on 2026-10-05. Bare bodyMedium title, labelMedium details, background-filled primary outline with the existing modalControl shape and medium-weight bodySmall label. The collapsed outline starts at y=56, is 32 px tall, and failed bodySmall text starts visually 8 px below it. Pending draws the entire button at 38% opacity. No assets change.

## Context

The switch-back action reserves Material's minimum layout space and adds duplicate shared error feedback over its own retry line. This is one deliverable: a correctly placed, immediately retryable offer. No decision record is needed.

## Design

Keep the existing row and offer types. Render a 32 dp outlined Surface with a 48 dp clickable target that overlaps the neighbouring padding rather than expanding the row. Give the action priority over the details target where its extension overlaps; disable the target while pending. Preserve token colours, shape, text styles and model-name spans. Use the border's two pixels in the button's measured padding rather than allowing the Surface border to disappear from height accounting.

Add a defaulted private `reportFailure` option to `sendSessionSettings`; only `onSwitchBack` disables the shared error channel. Both existing typed failure catches always run `revert`, then conditionally send the shared signal. Success, refresh, model recall and all other callers remain unchanged.

Concurrent overlaps: #1283 (historical row layout), #1727 (attachment functions), #1603/#1619 (other capture methods), #1747 (capture error expectations). Edits are local; none supplies a required dependency. Codegraph's shared-path impact result lists only the owner; repository search confirms four callers, with only switch-back changed.

## State and concurrency model

No new state, dispatcher or jobs. `viewModelScope` continues owning the write; cancellation is rethrown before IllegalStateException and neither reports nor marks a failure. `refusalOffer`/`pendingModel` and the existing StateFlow keep the current identity guards, reconnect rules and teardown lifetime. Retry clears `failed` before entering pending. Socket background closure stays owned by the lifecycle driver.

## Error handling

Relay refusal and IllegalStateException retain the same offer and clear pending, displaying the client-owned inline failure immediately. Ordinary model/effort edits still emit the one-shot shared signal. Decode/argument errors remain fail-loud. No exception message enters UI or logs.

## Testing strategy

Test first: extend the existing refusal ViewModel test to observe the error channel for both failure classes; assert retry clearing and ordinary model/effort feedback. Retain cancellation regression coverage and add switch-back cancellation coverage if absent. Extend shared Compose tests to measure the 32 dp visible outline separately from the 48 dp clickable target and route physical pointer taps at both extensions without toggling details; pending consumes neither action.

Run focused refusal/ordinary-settings ViewModel and shared refusal classes, lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck. Device-only reason: real screenshot pixels and real system bars. Run both named ThreadDesignCaptureTest methods on full pixel8Api35 with requireRealSystemBars=true; replace failure snackbar expectation with immediate absence, and retain nonblank PNGs, sidecars, aligned row comparisons and executed/failed/skipped XML. Run existing device ModelRefusalRowTest and deterministic scripted refusal. Existing rung-3 model round-trip and rung-4 switch-back coverage meet the ticket's live requirements; no new real-Claude run is required.

## Open Questions

None. Verify exact pixel gaps and touch overlap against tests/captures during implementation.

## Documentation handoff

Pending for the documentation stage: update `app/src/androidTest/assets/design-1220/thread/index.md`, armed/pending/failed switch-back verdicts and measurements, including the immediate failure capture and inline-only feedback decision. Use this builder/verifier's retained PNGs, comparisons and XML; do not run a new documentation-stage capture.

## Security review

**Verdict:** PASS

- [Trust boundaries] Preserve `appendModel`/`refusalModelDisplay`: daemon strings remain bounded inert separate spans; no parsing or executable sink is added.
- [Tokens] No credential handling changes; `onSwitchBack` forwards the existing offered model only after its connected/writable guards.
- [Files/storage] No runtime file writes added. Existing acknowledged-model preference write in `rememberModel` remains unchanged. Capture assets contain deterministic fixtures only.
- [Android attack surface] No components, intents, providers or WebViews added; the touch extension only invokes the existing guarded action.
- [Cryptography] No Noise or key-store changes; existing encrypted transport remains authoritative.
- [Network/I/O] Shared repository request and timeout behavior remain unchanged; this change affects feedback only, never admission or retry loops.
- [Errors/logs] SHOULD FIX: restrict suppression to switch-back and prove both ordinary callers still signal. Keep static failure logs and generic inline resource; never log models, banners, error messages, tokens or decrypted payloads.
- [Concurrency] SHOULD FIX: prove CancellationException remains cancellation, without revert or shared feedback. Keep one pending model write and identity-checked offer updates.
- [Threat model] Relay delay/drop remains handled by the existing request path; no plaintext exposure added. Token theft remains the existing Keystore boundary. Hostile daemon model text stays at the existing render boundary. Screenshot/accessibility exposure is unchanged and capture fixtures contain no user content.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-05

Sizing: approximately 350 written lines across plan, two production files and existing test files; zero new exported declarations, four existing shared-path callers, three acceptance criteria and two unchanged error branches. Within all ticket boundaries.

## Revisions

- 2026-10-05: The action uses an explicit 48 dp clickable Box whose layout reports the outline's 32 dp and centres the input node across surrounding padding. Its non-clickable Surface carries the visible outline; zIndex gives the upper extension priority over details. Fixed visible height accounts for the outline independently of font glyph bounds. Shared geometry tests pin 412 dp and use Robolectric native fonts, following the verification topic, after legacy font metrics measured an 83 px title-to-outline offset. No change to the offer or error contract.

- 2026-10-05: Simplified the touch implementation before commit: use Foundation clickable's existing 48 dp input expansion on a 32 dp non-clickable Material Surface, with the details block's bottom padding moved outside its click boundary. This removes custom layout/priority handling while reserving the same visible spacing. Pointer tests prove both extensions; `touchBoundsInRoot` proves target size independently of visible bounds. Shared row tests use native font measurement at density 1; the full device captures establish the 412×892 screen contract.

- 2026-10-05: Native-font measurement exposed Compose's default first/last-line trimming: the offered title/details occupied 18/14 dp instead of Figma's 20/16 dp, putting the outline at y=52. Follow existing `PairingHeader`/`BackgroundTaskPanel` practice with centred, untrimmed line boxes for the offered title/details and the failed line. Offer-free rows retain their current text layout. The offer's outline now has a stable y=56 from the row top without adding arbitrary gap compensation.
