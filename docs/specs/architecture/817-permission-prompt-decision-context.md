# #817 — show the permission prompt's decision context

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `ModalShownPayloadDto`, `ModalShownPayloadDto.toEvent()` — the `modal_shown` decode seam that gains the four optional fields.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` → `MobileJson` — `ignoreUnknownKeys = true`, `explicitNulls = false`; decides how an explicit `"reason": null` decodes.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → the `TYPE_MODAL_SHOWN` decode arm — single decode site; a thrown `SerializationException` drops the one envelope.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalEvent.kt` → `ModalEvent.Shown`, `ModalOption` — portable event type the context rides on.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalUiState.kt` → `ModalUiState.Open`, `reduce`, `scopedTo` — the fold that must carry the context into the open modal.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `PermissionModalOverlay`, `PermissionModalOverlayPreview` — the render site.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `IdentityRow`, `HostNameField` — the existing translation of the `533-2369` frame's label/value rows (`labelLarge` SemiBold label, `bodyMedium` value, merged semantics).
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileGateModal` — hardened container (`FLAG_SECURE`, `filterTouchesWhenObscured`) the prompt already draws in; unchanged.
- `app/src/test/.../data/repository/RemoteConversationRepositoryTest.kt` → `modalShown_decodesAllFieldsPreservingOptionOrder`, `modalShownEnvelope` — decode test idiom; existing positional `ModalEvent.Shown(...)` constructions must keep compiling (new parameter is trailing and defaulted).
- `app/src/test/.../data/model/ModalUiStateTest.kt` — fold test idiom.
- `app/src/androidTest/.../thread/ThreadScreenModalTest.kt` — screen test idiom.
- `docs/knowledge/features/permission-modal-overlay.md` § Security — the render obligations (plain `Text`, no `MarkdownText`, no `SelectionContainer`, no saved state) the new rows inherit.
- `../pyrycode/docs/protocol-mobile.md` § Modal (v2) → `modal_shown` — the four optional rows and the untrusted-display paragraph below the table. Not restated here.
- `pyrycode-desktop` `PermissionModal.tsx` — reason label: `classifier` / `rule` sentences, `Reason type: <raw>` fallback, `Reason` when no type; order reason → description → blocked path; non-string reason stringified.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The generic mobile-modal container: a full-height rounded column with a `titleLarge` header over an `inversePrimary` divider, a centred content area of label/value rows, and a centred footer. Its "Read only textfield" rows pair an `M3/label/large-emphasized` label (`labelLarge`, SemiBold) with an `M3/body/medium` value in `onPrimaryContainer`; its "Input large" puts the label above its content with an 8 dp gap. There is no dedicated frame for the context rows, so they reuse those styles in the "Input large" stacked shape (label above value), because reason and description are multi-line prose that a single-line ellipsised row would hide.

## Context

Daemon #2346 added `reason`, `reason_type`, `blocked_path` and `description` to `modal_shown`; mobile decodes none of them. This ticket decodes them, carries them through the fold, and renders them under the prompt. `default_to_no` stays out (the producer's `default_option_id` is already the deny option).

**Overlap note.** `origin/feature/812` (PR #887) also edits `InteractivePayloads.kt`, adding `ToolProgressPayloadDto` near the `tool_denied` DTO — several hundred lines from `ModalShownPayloadDto`. The hunks are disjoint, so this build proceeds rather than blocking; before opening the PR the builder runs `git merge-tree` against that branch and records a clean result in the PR body.

No ADR needed.

## Design

### Portable type (`ModalEvent.kt`)

```kotlin
data class ModalContext(
    val reason: String? = null,
    val reasonType: String? = null,
    val blockedPath: String? = null,
    val description: String? = null,
) {
    val isEmpty: Boolean // all four null
    companion object { val None: ModalContext }
}
```

`null` means absent. The protocol makes absent and empty equivalent, so the decode seam folds an empty string to `null`; consumers never see `""`. `ModalEvent.Shown` and `ModalUiState.Open` each gain a trailing `val context: ModalContext = ModalContext.None`; `reduce` copies it from `Shown` into `Open`. `Dismissed` carries none.

### Decode (`InteractivePayloads.kt`)

`ModalShownPayloadDto` gains four optional fields, all defaulted to `null`:

- `reason: JsonElement?` with a small custom serializer that delegates to `JsonElement.serializer()`, so an explicit JSON `null` decodes to `JsonNull` (present) rather than Kotlin `null` (absent). Without it the plain nullable property would make `"reason": null` vanish, which the ticket forbids.
- `reason_type`, `blocked_path`, `description`: decoded as `JsonElement?` too, not `String?`, so a wrong-typed display field can never drop the whole prompt. These fields are display-only, and a missing permission prompt would leave the user nothing to answer until the daemon's deny-on-timeout.

`toEvent()` maps them into `ModalContext` through one private helper:

- `reason`: a string primitive → its content; any other JSON value (`false`, `0`, `null`, object, array) → its compact JSON text (`JsonElement.toString()`), matching desktop's `JSON.stringify`.
- the three string fields: a string primitive → its content; any other JSON type → absent.
- then empty → `null`, then a surrogate-safe length clamp: `reason_type` at 128 chars (a category token), the other three at 2048 chars. The protocol states no bound for these fields, and the prompt's `Text` is inside a scroll area, so the clamp bounds layout and state size, not visibility of ordinary values.

Nothing is trimmed, parsed or sanitised beyond that. The mapper stays total and logs nothing.

### Render (`ThreadScreen.kt`)

`PermissionModalOverlay` renders, between the prompt `Text` and the option column, a private `PermissionContext(context)` **only when `!context.isEmpty`**, so a context-free modal renders exactly as today (no empty area, no extra spacing). `PermissionContext` is a `Column` (12 dp spacing, the frame's content gap) of up to three private `ModalContextRow(label, value: String?)` rows, in desktop order:

1. **Reason row** — present when `reason` or `reasonType` is non-null. Label from `reasonType`: `classifier` → `modal_context_reason_classifier`; `rule` → `modal_context_reason_rule`; any other non-null value → `modal_context_reason_type` formatted with the raw value (never dropped); `null` → `modal_context_reason`. Value = `reason`, or no value line when only the type arrived.
2. **Description row** — label `modal_context_description`, value `description`.
3. **Blocked path row** — label `modal_context_blocked_path`, value `blockedPath`.

`ModalContextRow` is a `Column` with 8 dp spacing and merged semantics: the label is `Text(labelLarge, SemiBold)` and the value is `Text(bodyMedium)`, both in `onPrimaryContainer` via the container's content colour. Every value is plain `Text(String)`, with no `MarkdownText`, no `AnnotatedString` link handling, no `SelectionContainer` and no `remember`/`rememberSaveable`. Labels are local string resources; the only server text is the value and the raw `reason_type` inside the fallback label.

Preview: `PermissionModalOverlayPreview` gains the context in its sample, so the preview shows the populated shape.

### Strings (`values/strings.xml`)

`modal_context_reason` ("Reason"), `modal_context_reason_classifier` ("The auto classifier could not approve this"), `modal_context_reason_rule` ("A permission rule asks"), `modal_context_reason_type` ("Reason type: %1$s"), `modal_context_description` ("Description"), `modal_context_blocked_path` ("Blocked path").

## State + concurrency model

No new jobs, flows or dispatchers. The context rides the existing `replay = 0` `modalEvents` → `ModalUiState` fold → `ThreadViewModel.currentModal` path as immutable data; a re-sent `modal_shown` (reconnect) replaces the open modal wholesale, context included (last-shown wins in `reduce`).

## Error handling

- A wrong-typed context field does not fail decode: `reason` stringifies, the other three become absent.
- A structurally malformed envelope still drops as today (required fields unchanged).
- No user-visible error path is added.

## Testing strategy

Unit (`./gradlew testDebugUnitTest`):

- `RemoteConversationRepositoryTest` (beside the #437 modal decode tests):
  - all four string fields decode into `ModalContext`.
  - non-string `reason` values `false`, `0`, `null`, an object and an array each decode as their JSON text.
  - absent fields and empty strings decode as `ModalContext.None`; the existing tests keep passing with the default.
  - an unknown `reason_type` is carried verbatim; a wrong-typed `reason_type` / `description` / `blocked_path` is absent and the modal still surfaces.
  - over-long values are clamped to their caps.
- `ModalUiStateTest`: `Shown` with a context folds into `Open` carrying it; a later `Shown` without context replaces it with `None`.

Compose UI (`ThreadScreenModalTest`, focused managed-device run):

- a populated context shows the reason label and value, the description and the blocked path with their labels.
- the `classifier` / `rule` sentences appear, and an unknown `reason_type` renders as `Reason type: <raw>`.
- a type-only reason renders the label alone; a non-string reason text (`false`) is displayed.
- a context-free modal shows none of the context labels.

No rung-3 scenario in this ticket: the ticket names #679 as the live verification.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/permission-modal-overlay.md` (the overlay's context rows and their security obligations) and `docs/knowledge/features/modal-events.md` (the four decoded optional fields, the stringify rule for `reason`, the lenient-type and clamp posture), per the ticket's Owning docs.

## Open questions

- Does the custom `reason` serializer actually receive the JSON `null` token on a nullable property with `MobileJson`'s config? Resolved by the non-string-reason unit test; if not, fall back to a sentinel-free alternative and record it under Revisions.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. One boundary: `ModalShownPayloadDto.toEvent()` is the only place the four claude-authored values enter the app, and it emits a typed `ModalContext` of bounded strings. The protocol labels every value untrusted display content, and the design treats it that way: nothing keys a behaviour on any field (the fail-safe-deny highlight still comes only from `default_option_id`, and option taps still forward verbatim). `blocked_path` is never used as a filesystem input.
- [Trust boundaries / rendering] SHOULD FIX, addressed in the design: the values render only through plain `Text(String)` in `ModalContextRow`, inside `MobileGateModal`, which keeps `FLAG_SECURE` and `filterTouchesWhenObscured`. There is no `MarkdownText`, no link annotation (`Text(String)` does not autolink), no `SelectionContainer`, and no clipboard path. The verifier should confirm there is no `SelectionContainer` or `AnnotatedString` in the diff.
- [Trust boundaries / size] No findings. The 2048/128-char clamps bound state and layout independent of the daemon, and the relay frame cap already bounds the envelope. The clamp is surrogate-safe, so it never leaves a lone high surrogate.
- [Trust boundaries / spoofing chrome] Accepted residual: a hostile `reason_type` can put arbitrary text after the local "Reason type:" prefix, and `reason` can read like instructions ("Safe, tap Allow"). The prefix and labels are local and visually distinct (SemiBold label vs body value), the values sit below the daemon's prompt and never replace the option labels or the deny highlight, and the second-confirm gate (#451) still guards any allow. This is the same residual the `prompt` field already carries.
- [Tokens] No findings — no tokens, keys or credentials touched.
- [File / storage] No findings — nothing written; the context lives only in the transient `StateFlow` fold. There is no `rememberSaveable`, `SavedStateHandle` or DataStore, so nothing survives process death.
- [Android surface] No findings — no intents, deep links, providers or WebView.
- [Crypto] No findings — not touched.
- [Network & I/O] No findings — decode-only change inside the existing Noise session; a wrong-typed optional field cannot drop the prompt (lenient `JsonElement` decode), and a malformed required field keeps today's drop-one-envelope behaviour.
- [Logs] No findings — the mapper, the fold (`reduce`, whose no-log contract is kept) and the composables log nothing; no context value reaches Logcat, a crash message or the snackbar.
- [Concurrency] No findings — no new coroutines or shared mutable state.
- [Threat model] Hostile daemon frame: decoded defensively and rendered as bounded text. UI leakage: screen capture is blocked by the gate's `FLAG_SECURE`. The accessibility tree still exposes the text, the same accepted platform residual documented in `permission-modal-overlay.md` § Security.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

## Revisions

### 2026-09-23 — `reason` decodes as a non-null `JsonElement`, not through a custom serializer

The Open question resolved against the plan. With a custom `KSerializer<JsonElement?>` on a nullable property, kotlinx still short-circuits the JSON `null` token to Kotlin `null` before the serializer runs, so `"reason": null` vanished (caught by `modalShown_nonStringReason_decodesAsItsJsonText`). The daemon can send it: `permbridge` carries `decision_reason` as a `json.RawMessage`, which keeps a literal `null` through `omitempty`. New contract: `ModalShownPayloadDto.reason` is a non-null `JsonElement` defaulting to `JsonPrimitive("")`. An explicit `null` decodes as `JsonNull` and renders as `"null"`. An absent key takes the empty string, which the wire and the mapper already treat as absent. No custom serializer. The other three fields stay `JsonElement? = null` as planned.
