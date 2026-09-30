# Clarification question components (#1299)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/QuestionBatchModal.kt` → `QuestionBatchModal`, `QuestionBlock`, `ChoiceRow`: current stateless batch rendering, row semantics and Other entry.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/QuestionModalState.kt` → `QuestionSelection`, `QuestionModalEvent`: selection, draft and validation contract stays with the ViewModel.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileGateModal`, `MobileModalShell`: secure, scrolling shell and pinned footer remain its responsibility.
- `app/src/main/java/de/pyryco/mobile/ui/theme/ModalColors.kt` → `modalFieldContainer`, `modalFieldText`: existing dark editing well roles.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` → `AppTypography`: M3 type metrics used by the question components.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/QuestionBatchModalTest.kt` → interaction and IME coverage using the real `ThreadViewModel`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalCaptureTest.kt` → real emulator capture and viewport precedent.
- `docs/knowledge/features/question-batch-modal.md` § Rendering: text stays inert, row identity uses indices, draft lives in `QuestionSelection`.
- `docs/knowledge/features/mobile-modal.md` § Layout and theme: the shell owns safe insets, compact scrolling, footer and window security.
- `docs/knowledge/features/development-verification.md` § Compose evidence: use real device pixels for visual evidence and a real IME for keyboard reachability.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6697

Inspected on 2026-09-30: questionnaire `347:6697`, question labels `347:6861`, radio rows `347:6476`, checkbox rows `347:6771`, and shared mobile shell `533:2369`. The questionnaire has a 14 × 16 tertiary glyph and small uppercase header above each rounded, 1 dp bordered background card; question text uses emphasized body medium, choices use 20 dp tertiary radio or checkbox outlines with medium label text, and the Other well is inset under a label beside its control. Its 699 dp component example includes Previous and local actions; there is no full-screen 412 × 892 question reference. The shipped one-batch gate keeps its own Cancel and Continue footer.

## Context

The current question data and send flow already work. `QuestionBlock` lacks the reference glyph, `ChoiceRow` uses larger stock Material controls, and the Other field sits outside its choice row. This change aligns that content while retaining the secure gate. No ADR is needed.

## Design

- Keep `QuestionBatchModal(state, onEvent, modifier)` and `MobileGateModal` wiring unchanged. Render all questions in batch order in the existing shell scroll area.
- In `QuestionBlock`, put the exported Figma glyph in a 14 × 16 dp slot beside the uppercase 11 sp header. Keep header text wrapping. Keep the background card, 1 dp `primaryContainer` border, 6 dp corners and 16 dp insets. Use 16 dp after the question and 8 dp between choices.
- In `ChoiceRow`, preserve `selectableGroup`, `Role.RadioButton` and `Role.Checkbox` on whole rows. Draw 20 dp tertiary outlines: round with an inner dot for selected single choice, 4 dp corners with the supplied check vector for selected multiple choice. The row retains a practical touch height while the visible control matches Figma. Labels use M3 `labelMedium` with semibold first line and medium description; untrusted text has no line clamp or clickable interpretation.
- Compose Other as a choice row containing its semibold `labelLarge` label and `BasicTextField` in a 6 dp `modalFieldContainer` well, using `modalFieldText`, `bodySmall`, and `inversePrimary` placeholder. Keep the `question_other_<index>` tag, text change event, independent stored draft and selection semantics. The field remains focusable and scrollable with the keyboard.
- Add the exported header and checkbox selector as local vector drawables with theme tint at the call site. No new dependency or exported type.

## State and concurrency

No new state, flow or job. `QuestionSelection` remains ViewModel owned; edits still dispatch `QuestionModalEvent` through `onEvent`. The existing gate and ViewModel retain cancellation, send locking and dismissal behavior.

## Error handling

Existing validation disables Continue until all answers are valid. Existing send failure text and locked controls stay in the gate. This visual change introduces no new IO path or error result.

## Testing strategy

- Add focused device assertions for visual control bounds/colour and the Other field's interaction; keep the existing single, multiple, failed refusal and IME tests green. Include long server text and enlarged text/compact width reachability.
- Capture the actual 412 × 892 dark gate on an emulator, compare at logical 1 dp to 1 px with Figma component renders, and retain a labelled overlay or difference artifact plus the capture and node/date notes. Mark the absent full-screen question reference explicitly; compare component geometry rather than claim an exact full-screen match.
- Run the affected device test class, focused ViewModel question test, lint, `assembleDebug`, androidTest compilation and forced Spotless check. The dispatcher owns the full gate; no new real-Claude scenario is needed for this visual-only change to an existing operator flow.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/question-batch-modal.md` § Rendering with the component alignment and inspected Figma nodes/date. The issue has no separate Documentation handoff section or documentation-only acceptance criterion.

## Open questions

- Whether a 48 dp row touch floor produces excess vertical spacing at the 412 dp viewport. Resolve by inspecting the actual capture and preserving the touch target while matching visible control geometry.
- Whether Figma's vector selector needs a separate Android asset. Resolve by checking the exported path and actual rendered selected state.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] `QuestionBatchModal` receives daemon-authored strings through existing `Question` models. The plan keeps them in wrapping `Text` and inert `BasicTextField` placeholder/label slots; no text becomes a URL, key, log or markup. No new wire decoder or trust promotion is introduced.
- [Tokens, storage, crypto, network, Android attack surface] No findings for this visual change: no secret, persistence, network, intent, WebView, exported component or cryptographic code is added.
- [UI-side leakage] `MobileGateModal` remains the only window and retains `SecureOn`, obscured-touch filtering, and explicit Cancel. No close, Back or outside dismissal is added.
- [Errors and logs] Existing generic send failure remains. No content-bearing log or telemetry is added.
- [Concurrency] No new coroutine or mutable state. `QuestionSelection` and the existing `ThreadViewModel` send lock remain authoritative during edits and IME resize.
- [Threat model] Hostile daemon strings remain bounded by the existing decoded model and rendered as inert wrapping text. Relay flooding and token theft are outside this UI ticket and stay with their existing transport/storage owners.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-30

## Revisions

- 2026-09-30 (verifier rework): Use the existing `Shapes.modalControl` 6 dp token for the question card and Other well, and M3 `Shapes.extraSmall` for the checkbox's 4 dp corners. This resolves PR #1301's shape-system finding without changing geometry or adding a theme slot.
- 2026-09-30 (verifier rework): The Other `BasicTextField` itself needs a 48 dp minimum touch region; `ChoiceRow`'s 48 dp selection target does not enlarge the independently focusable field. Center the unchanged 32 dp minimum visible well inside the field's own 48 dp minimum layout, and prove field bounds plus a near-edge pointer tap on the device. This responds to the PR #1301 MUST FIX finding.
- 2026-09-30: The real API 35 emulator returned a blank SurfaceFlinger screenshot because `MobileGateModal` sets `FLAG_SECURE`. The capture test draws that same dialog view directly into a bitmap with static fixture text; the secure window remains unchanged. The retained 412 × 892 render and labelled 1 dp-to-1 px component overlay are the visual evidence.
- 2026-09-30: The visible 20 dp controls match the Figma components, while `ChoiceRow` retains the existing 48 dp row touch floor. The component example is wider and has no phone-sized question frame, so vertical row rhythm adapts to touch reachability. The exported Figma checkbox selector became a separate vector resource; no stock substitution is used.
- 2026-09-30: Security review precision: `QuestionShownPayloadDto.toBatch` preserves daemon text verbatim; the existing 131,072-character inbound frame cap in `OkHttpRelayTransport` bounds the aggregate frame before decoding. This visual ticket adds no second parser or text sink.
