# #818 — Don't ask again this session on permission prompts

## Files read

- `../pyrycode/docs/protocol-mobile.md` § Modal (v2) → the `always_allow` rows under `modal_shown` and `modal_answer`, plus the offer-availability paragraphs. This is the wire contract. The plan cites it and does not restate it.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `ModalShownPayloadDto`, `ModalShownPayloadDto.toEvent`, `toModalContext`. These show #817's tolerant `JsonElement` posture for display-only fields, which the new decode follows.
- `app/src/main/java/de/pyryco/mobile/data/network/ModalOutboundPayloads.kt` → `ModalAnswerPayloadDto`, the encode side.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` → `MobileJson` sets `encodeDefaults = true` and `explicitNulls = false`, so a `null` field is omitted from the encoded frame.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalEvent.kt` → `ModalEvent.Shown`, `ModalContext`.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalUiState.kt` → `ModalUiState.Open`, `reduce`, `scopedTo`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `answerModal` and `answerToken`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `answerModal` passthrough.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.thread` builds the `answerModal` lambda.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `answerModal` has no production caller. It stays untouched because the new argument is defaulted.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `armedModalOption`, `armedOptionId`, `onModalOption`, `onModalCancel`, `scopedModal`, `sendAnswer`, `ArmedModalOption`. The acceptance state follows the arm pattern.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` parameters, `PermissionModalOverlay`, `PermissionContext`, `ModalContextRow`, `PermissionModalOverlayPreview`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the thread route collects `vm.armedOptionId` and binds `vm::onModalOption`. The new flag and toggle are wired the same way.
- `pyrycode-desktop` `PermissionModal.tsx` and `modalResolution.ts` → `confirmPrompt`. The desktop sends the grant only for `allow_once` / `allow_always` and only when the prompt matches the accepted offer.
- Tests: `ThreadViewModelTest` (`makeVm`, `vmWithModalSendPath`, `ModalSendRecorder`), `ThreadViewModelQuestionTest` (an `answerModal` lambda), `RemoteConversationRepositoryTest` (`decodeModalContext`, `answerModal_*`, `startAnswerModal`), `ModalUiStateTest`, `ThreadScreenModalTest` (`setContent`, `openModal`).
- The builder comment on #818 from the blocked run → design notes. This plan adopts them.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

This is the generic mobile modal container: a `Schemes/on-primary-fixed` panel with a title-large header and separator, and a Content column with 12 dp gaps. That column holds label-large-emphasized labels in `on-primary-container` above body-medium values, then outlined and filled 6 dp-radius buttons. The offer has no frame of its own. It uses the container's "Input large" stacking: a label row made of an M3 `Checkbox` and the label-large SemiBold "Don't ask again this session for:" text, then the rules as body-medium lines 8 dp below. It sits between the context rows and the options.

## Context

Daemon #2364 added an always-present `always_allow` object to `modal_shown` and an optional `always_allow` boolean to `modal_answer`. The phone reads neither, so a phone user answers every repeat of a permission prompt. This ticket decodes the offer, shows it on permission prompts, keeps the accepted state scoped to the prompt that showed it, and sends the flag on an allow answer.

**Sizing overage (deliberate).** This ticket touches 10 production `.kt` files, which is 2 over the 8-file ceiling. The estimate missed `MainActivity.kt`, which must forward the new flag and toggle. The other limits hold: about 650 lines of written work, 0 new exported types (only new members on existing types), 4 `answerModal` call sites, 3 acceptance criteria and no state machine. A split fails the floor rule, because a decode-only slice and a send-only slice would each have exactly one consumer inside the family. The floor wins, so this builds as one ticket.

No new ADR is needed.

## Design

### Decode (`data/network`)

- `ModalShownPayloadDto` gains `@SerialName("always_allow") val alwaysAllow: JsonElement? = null`. It is tolerant like #817's context fields: a missing or malformed value never drops the prompt.
- A private mapper, `JsonElement?.toAlwaysAllowRules(): List<String>`, returns the offered rules or the empty list. It returns rules only when all of the following hold:
  - the value is a `JsonObject`;
  - `offered` is a JSON boolean `true`. The quoted string `"true"` is rejected;
  - `rules` is a `JsonArray` of 1 to 16 elements;
  - every element is a JSON string, non-empty, and at most 1024 UTF-8 bytes (`encodeToByteArray().size`).

  Any violation rejects the whole list, so the phone never keeps a prefix. This matches the daemon's no-truncation rule. The client applies the daemon's own bounds and adds no new cap.
- `toEvent` copies the result into `ModalEvent.Shown.alwaysAllowRules`.

### Models (`data/model`)

- `ModalEvent.Shown` and `ModalUiState.Open` gain `val alwaysAllowRules: List<String> = emptyList()`. An empty list means no offer is available. `reduce` copies the field.
- `ModalUiState.Open` gains the derived `val offersAlwaysAllow: Boolean`, which is `modalClass == "permission" && alwaysAllowRules.isNotEmpty()`. The UI and the ViewModel both gate on this one property.

### Outbound (`data/network`, `data/repository`, `di`)

- `ModalAnswerPayloadDto` gains `@SerialName("always_allow") val alwaysAllow: Boolean? = null`. `null` is omitted on the wire, so existing answer bytes do not change.
- `RemoteConversationRepository.answerModal(modalId, optionId, alwaysAllow: Boolean = false)` sends `alwaysAllow = true` when the argument is set and omits the field otherwise. It never sends `false`, which the contract treats the same as absent. `answerToken` does not change.
- `RelayRepositoryCoordinator.answerModal(modalId, optionId, alwaysAllow: Boolean = false)` passes the argument through.
- The `answerModal` lambda in `AppModule`'s `ThreadDestinationFactory.thread` becomes `{ modal, option, grant -> …coordinator.answerModal(modal, option, grant) }`.
- `RelayConnectionRegistry.answerModal` and `SecondClientPeer` stay untouched because of the default argument.

### ViewModel (`ThreadViewModel`)

- The constructor's `answerModal` becomes `suspend (modalId: String, optionId: String, alwaysAllow: Boolean) -> Unit`.
- New private state: `acceptedAlwaysAllow = MutableStateFlow<AcceptedAlwaysAllow?>(null)`, where `AcceptedAlwaysAllow` is a private data class holding `modalId` and `rules`. It follows the `ArmedModalOption` pattern.
- New public `val alwaysAllowAccepted: StateFlow<Boolean>` combines `currentModal` with that state. It is `true` only while the scoped modal is `Open`, `offersAlwaysAllow` holds, and the key equals `(modalId, alwaysAllowRules)`. The flow is started `Eagerly`, like `armedOptionId`. Because of the key, a new, replaced, re-offered-with-different-rules or resolved prompt reads as unaccepted by construction (AC 3).
- New `fun onAlwaysAllowChanged(modalId: String, accepted: Boolean)`. It reads `scopedModal()`. If that is an `Open` modal that offers the grant **and** whose `modalId` equals the argument, it sets or clears the key. Otherwise it does nothing. The id is a guard, never a target: it only stops a tap on a stale frame from accepting a prompt that replaced it (see the security review). It never sends and never touches `armedModalOption`, so accepting the offer is not the second-tap confirmation.
- `onModalOption` computes the flag when it decides to send: the answered option is `allow_once` or `allow_always`, `open.offersAlwaysAllow` holds, and `acceptedAlwaysAllow.value` equals the open prompt's key. The flag is passed through `sendAnswer(modalId, optionId, alwaysAllow)`. Arming does not send, so it does not consume the acceptance.
- `onModalCancel` clears `acceptedAlwaysAllow`. Cancel never carries the flag.
- A send attempt does not clear the acceptance. The prompt stays `Open` until the daemon resolves it, so a user retrying after a failed send still sees their checkbox and sends the same intent. Resolution hides the checkbox through the projection.
- **Reject with the offer accepted** sends the flag unset, matching the desktop's `confirmPrompt`. The contract makes `true` a no-op on a deny, so sending it would carry no information. The ticket's AC 2 wording about answering with the offer accepted applies to allow answers.

### UI (`ThreadScreen`, `MainActivity`, `strings.xml`)

- `ThreadScreen` gains `alwaysAllowAccepted: Boolean = false` and `onAlwaysAllowChanged: (modalId: String, accepted: Boolean) -> Unit = { _, _ -> }`. The overlay passes the rendered `open.modalId`. Both are forwarded to `PermissionModalOverlay`.
- `PermissionModalOverlay` renders a new private `AlwaysAllowOffer(rules, accepted, onChanged)` only when `open.offersAlwaysAllow`. It sits after `PermissionContext` and before the options.
  - The label row is `Modifier.toggleable(value = accepted, role = Role.Checkbox, onValueChange = onChanged)`. It contains a `Checkbox(checked = accepted, onCheckedChange = null)` and the local string `modal_always_allow_label` ("Don't ask again this session for:") in `labelLarge` SemiBold, with a minimum height of 48 dp.
  - Each rule renders as its own plain body-medium `Text`, in wire order. There is no markdown, `SelectionContainer` or saved state, and nothing is logged. The rules live inside `MobileGateModal`'s `FLAG_SECURE` dialog like the rest of the prompt.
- `MainActivity` collects `vm.alwaysAllowAccepted` and binds `onAlwaysAllowChanged = vm::onAlwaysAllowChanged`.
- `PermissionModalOverlayPreview` gains sample rules with the offer accepted.

## State + concurrency model

- No new coroutines. `alwaysAllowAccepted` is a `combine(...).stateIn(viewModelScope, Eagerly, false)` sibling of `armedOptionId` and is cancelled with the ViewModel.
- The send path is the existing `sendAnswer` launch. It captures the flag value before launching, so a later toggle cannot change an in-flight answer.
- The input guard reads `hostModal` synchronously through `scopedModal()`, as `onModalOption` already does, so a prompt from another conversation can never be toggled or granted from this thread.

## Error handling

- Decode: any shape violation in `always_allow` means no offer is available. The prompt still surfaces, and nothing is thrown or logged.
- Send: unchanged. The two documented throws surface as the one-shot `modalSendErrors`.

## Testing strategy

Unit tests (RED first):

- `RemoteConversationRepositoryTest`, beside `decodeModalContext`, with a helper that decodes `alwaysAllowRules`:
  - offered with rules → the rules in order;
  - `{offered:false, rules:[]}` → empty;
  - an absent object → empty;
  - offered with an empty list → empty;
  - `"offered":"true"` (a string) → empty;
  - 17 rules → empty;
  - a 1025-byte rule (multi-byte characters, so the count is in bytes, not chars) → empty, while a 1024-byte rule passes;
  - an empty-string rule or a non-string element → empty, and no prefix is kept;
  - `offered:false` with rules present → empty;
  - a malformed value (a string or an array) → empty, and the modal still surfaces.
- `RemoteConversationRepositoryTest` `answerModal`:
  - with `alwaysAllow = true` the payload keys include `always_allow` set to `true`;
  - the default call's payload is still exactly the three original keys. The existing `answerModal_sendsModalAnswerMatchingWireContract` already pins that, so only a `false` case is added, which must also omit the field.
- `ModalUiStateTest`:
  - `reduce` copies `alwaysAllowRules`;
  - `offersAlwaysAllow` is true only for class `permission` with rules, and false for `trust` with rules and for `permission` without rules.
- `ThreadViewModelTest`. The recorder captures `Triple(modalId, optionId, alwaysAllow)`. Cases:
  - accept, then allow (default tap path with an allow-default fixture, and the armed double tap) → `true`;
  - allow without accepting → `false`;
  - accept, then reject (the default) → `false`;
  - accept, then cancel → the cancel is sent and a new identical `Open` prompt with another id reads unaccepted;
  - toggle on a prompt that offers nothing → stays `false`, and the answer sends `false`;
  - accepting does not arm or send anything;
  - a replaced prompt (new `modalId`) reads unaccepted, and answering it sends `false`;
  - the same `modalId` re-sent with different rules reads unaccepted;
  - dismissal → `alwaysAllowAccepted` is false;
  - accept then un-accept → `false`;
  - a toggle that carries a stale `modalId` is ignored.
- `ThreadViewModelQuestionTest`: only the lambda arity changes.

Compose UI test (`ThreadScreenModalTest`), run on the managed device for the class:

- a permission prompt with rules shows the label and each rule, placed between the context and the options;
- no offer appears for a `trust` prompt with rules or for a permission prompt without rules;
- tapping the row calls `onAlwaysAllowChanged("m1", true)` and does not call `onModalOption`;
- `alwaysAllowAccepted = true` renders the checkbox as checked.

E2E: the ticket assigns live verification to #679 (the rung-3 follow-up). This ticket lands no scenario.

## Documentation handoff

Pending for the documentation stage. Fold the always-allow offer and grant into `docs/knowledge/features/modal-answer-flow.md`, `features/permission-modal-overlay.md` and `features/current-modal-state.md`, which are the owning docs the ticket names.

## Open questions

- Should a successful send clear the acceptance? Resolved: no. See the ViewModel section.
- Should rules have a render cap beyond the daemon's 16 × 1024 bytes? Resolved: no. The decode already rejects anything over those bounds.

## Security review

**Verdict:** PASS, after one revision. The first pass found a MUST FIX: the toggle carried no prompt identity, so a tap on the last frame of a prompt that had just been replaced would accept the replacing prompt, which the user had not seen. `onAlwaysAllowChanged` now carries the rendered `modalId` as a guard.

**Findings:**

- [Trust boundaries] No findings. The boundary is the single `toAlwaysAllowRules` mapper. It accepts a whole rule list within the daemon's bounds (1 to 16 JSON strings, each 1 to 1024 UTF-8 bytes) or nothing. Everything downstream holds a display-only `List<String>`. The phone sends a boolean only, never rule bytes or a destination. The daemon grants only its own retained rules, only on an authorized allow, and only once per modal, so a hostile or buggy client value cannot widen a grant.
- [Trust boundaries] No findings. Acceptance is keyed on `(modalId, rules)`, so if the rules on the open prompt change the acceptance lapses. The user never grants a rule set other than the one they saw.
- [Trust boundaries] OUT OF SCOPE. Rule strings render verbatim, so bidi-override or control characters could make a rule look different from its bytes. The prompt, option labels and #817 context already share this exposure. Sanitizing modal text is a cross-cutting change for the whole overlay. It is flagged in the PR body as a follow-up candidate and is not filed from this ticket.
- [Tokens] No findings. `answer_token` still derives from `(modalId, optionId)`. The flag is not a credential, and a replayed answer cannot grant twice because consuming the modal is the daemon's one-shot boundary.
- [File / storage] No findings. Nothing persists. Acceptance lives in ViewModel memory, and neither `rememberSaveable` nor `SavedStateHandle` holds it or the rules.
- [Android attack surface] No findings. The checkbox row sits inside `MobileGateModal`'s window, so it inherits `FLAG_SECURE` and `filterTouchesWhenObscured`. That blocks a screen-overlay tapjack on the checkbox, the same way it does for the options.
- [Crypto] N/A. No primitives are touched.
- [Network & I/O] No findings. The inbound size is bounded by the relay frame cap and by the decode bounds above. The outbound frame gains one boolean at most.
- [Logs] No findings. The modal path's never-log contract applies. The new mapper, ViewModel state and composable log nothing, and rules never reach a log, an error message or a snackbar.
- [Concurrency] Revised, as described in the verdict. With the guard, `onAlwaysAllowChanged` only acts on the scoped open modal whose id matches the rendered one. `onModalOption` reads the acceptance synchronously against `scopedModal()` and captures the flag before launching the send, so a later toggle cannot alter an in-flight answer.
- [Threat model] No findings. A hostile relay is content-blind. A hostile daemon already decides grants, and it can offer a grant but cannot make the phone assent without an explicit checkbox tap followed by an allow answer. The arm-then-confirm gate is unchanged, and the checkbox tap never confirms an arm.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
