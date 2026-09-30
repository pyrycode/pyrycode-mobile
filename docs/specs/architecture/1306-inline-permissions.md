# Inline conversation permissions (#1306)

## Files read

Production paths are relative to `app/src/main/java/de/pyryco/mobile/`.

- `ui/conversations/thread/ThreadPermissionModal.kt` → `PermissionModalOverlay`, `PermissionContext`, `AlwaysAllowOffer`, `ModalOptionButton`: the controls to reuse unchanged inside the stream.
- `ui/components/MobileModal.kt` → `MobileGateModal`: the dialog-window `FLAG_SECURE` and obscured-touch hardening that no longer covers the permission once it leaves the dialog. `CreateChatModal` still uses the gate, so it stays.
- `ui/conversations/thread/QuestionBatchModal.kt` → `QuestionPromptProtection`, `QuestionProtectionOwners`: the #1305 activity-window/decor/view protection with shared ownership, reused as-is for the permission.
- `ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`: empty-thread branch, `promptRowCount` in the oldest-history predicate, streaming pin, the #1305 prompt-reveal effect, reverse-layout `LazyColumn`, and the `when (modalState)` overlay slot.
- `ui/conversations/thread/ThreadViewModel.kt` → `currentModal`, `armedModalOption`/`armedOptionId`, `acceptedAlwaysAllow`/`alwaysAllowAccepted`, `onModalOption`, `onModalCancel`, `onAlwaysAllowChanged`, `grantsAlwaysAllow`, `scopedModal`, `AcceptedAlwaysAllow`: arm and grant state that is VM-scoped today and dies on Back.
- `ui/conversations/thread/QuestionDraftStore.kt` → `QuestionDraftStore.bind`: the app-scoped host-binding precedent for a draft that outlives the destination.
- `di/AppModule.kt` → `ThreadDestinationFactory.thread`, the thread `viewModel { }` block: where the question store is bound per host and passed to the VM.
- `MainActivity.kt` → the `CONVERSATION_THREAD` destination and `openThread`: a thread can be pushed on top of another thread, so the lower VM survives while its screen is disposed.
- `data/model/ModalUiState.kt` → `ModalUiState.Open.offersAlwaysAllow`, `scopedTo`: request identity is `modalId`; the offer is the rules list (the wire carries no separate session id).
- `app/src/sharedTest/.../thread/ThreadScreenModalTest.kt`, `ThreadInlineQuestionTest.kt`: screen tests to adapt / mirror.
- `app/src/androidTest/.../thread/ThreadPermissionCaptureTest.kt`, `QuestionBatchModalTest.inline_prompt_protects_capture_rejects_obscured_touches_and_restores_window_policy`: device capture and obscured-touch pattern.
- `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`, `promptDialog`, `inPromptDialog`, `awaitPromptDialog`, `awaitNoPromptDialog`: live selectors that key on the dialog's Cancel.
- `docs/knowledge/features/permission-modal-overlay.md` § Security, `modal-answer-flow.md`, `development-verification.md` § Where a screen test goes.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=639-2242 (states `639-2451`, `639-2666`, `639-2882`, `639-3099`, `639-3308`, `640-2437`, `640-2838`).

The Figma MCP server was unauthenticated in this session, so the nodes could not be fetched. The layout follows the ticket text and the sibling #1305 inline-question family it was designed alongside (`app/src/androidTest/assets/question-1305/question-normal.png`): a `titleMedium` heading carrying the server title, a bordered card (`background` fill, 1dp `primaryContainer` border, `modalControl` shape, 16dp padding) holding the verbatim prompt, decision context, the tertiary session-grant checkbox with its rules and the full-width option buttons (filled safe default, outlined non-default, tonal armed with its "Tap again to confirm" marker), then an outlined Cancel centred below the card. The existing chat gutters, top bar, Back and composer footer stay. The deviation risk from not reading the nodes is stated in the PR for the verifier's fidelity check.

## Context

`PermissionModalOverlay` draws the pending permission in a blocking dialog: the reader cannot scroll history, go Back or switch chats without answering. #1305 moved questions into the stream; this ticket does the same for permission and trust requests, reusing the controls, the grant offer and the reply handlers, and moves the grant draft to process lifetime so Back does not lose it. No wire change. The documentation stage may want an ADR-sized note on "prompt drafts are app-scoped, arms are destination-scoped" now that both prompt kinds follow it.

Overlapping in-flight branches: #1328 (`ThreadScreen`, `ThreadViewModel`), #1323 (`MainActivity`), #1330 (`AppModule`) touch unrelated lines; no real dependency.

## Design

**Inline surface (`ThreadPermissionModal.kt`).** Replace `PermissionModalOverlay` with `internal fun LazyListScope.permissionRequestItems(open, armedOptionId, onOption: (modalId, optionId), onCancel: (modalId), alwaysAllowAccepted, onAlwaysAllowChanged)`. Under the list's reverse layout it emits, newest end first: `permission-cancel:<modalId>` (outlined Cancel, the existing `ModalCancelButton`), `permission-card:<modalId>` (the card), `permission-title:<modalId>` (heading). Every click lambda captures `open.modalId`. Server strings (title, prompt, context values, rules, option labels) render through plain `Text` bounded by one named constant; no `MarkdownText`, no `SelectionContainer`, no saved state. `PermissionContext`, `AlwaysAllowOffer`, `ModalOptionButton` and `dismissReasonText` are unchanged. Test tags: `permission-request-card`, `permission-request-title`, `permission-request-cancel`.

**Screen (`ThreadScreen.kt`).**
- `onModalOption: (modalId: String, optionId: String) -> Unit`, `onModalCancel: (modalId: String) -> Unit` — the screen reports which request a tap landed on.
- The `when (modalState)` slot keeps only the `Dismissed` snackbar; `Open` renders inside the list, before the question items.
- The empty-thread branch also yields to an open request; `promptRowCount` adds 3 for an open request; the streaming pin is off while any prompt is present; the #1305 reveal effect keys on both prompt identities (question generation, permission `modalId`).
- `QuestionPromptProtection()` mounts while a question **or** an open request is present, from one call site, so a question→permission hand-over keeps the same owner.

**Grant draft (`PermissionDraftStore.kt`, new, app-scoped).**
- `internal data class PermissionGrantDraft(modalId: String, rules: List<String>)` — replaces the VM's private `AcceptedAlwaysAllow`.
- `class PermissionDraftStore(dispatcher = Dispatchers.Main.immediate)`: `bind(serverId, owner, modals: StateFlow<ModalUiState>)`, `observe(serverId, conversationId): Flow<PermissionGrantDraft?>`, `current(serverId, conversationId)`, `set(serverId, conversationId, draft?)`, `dispose()`.
- `bind` is idempotent per owner; a new owner cancels the old collector and clears that host. The collector retires every entry of the host whose (conversation, `modalId`, rules) no longer equals the host's `Open` modal offering the grant, so replacement, resolution, cancellation and a changed offer drop the draft while the thread is away. An unbound store (tests, demo host) keeps entries; the VM's key match still hides a stale one.

**ViewModel (`ThreadViewModel.kt`).**
- New `permissionDraftStore: PermissionDraftStore? = null` parameter; a private store stands in when absent.
- `alwaysAllowAccepted` combines `currentModal` with `store.observe(serverId, conversationId)`; `grantsAlwaysAllow` reads `store.current`; `onAlwaysAllowChanged` and `onModalCancel` write through the store.
- `onModalOption(optionId, modalId: String? = null)` and `onModalCancel(modalId: String? = null)` return when a supplied `modalId` differs from `scopedModal()`'s. Existing callers without an id keep today's behaviour.
- `onConversationLeft()` clears the arm. The arm stays VM-scoped: Back destroys it; navigating on to another thread disposes this screen, which calls it.

**Wiring.** `AppModule`: `single { PermissionDraftStore() } onClose { dispose }`; `ThreadDestinationFactory.thread(..., permissionDrafts: PermissionDraftStore? = null)` binds it to `bundle.coordinator.currentModal` beside the question binding and passes it to the VM. `MainActivity`: two-argument lambdas for option/cancel and `DisposableEffect(vm) { onDispose { vm.onConversationLeft() } }`.

## State + concurrency model

Store mutations are `@Synchronized`; its `SupervisorJob` scope on Main owns one collector per host, cancelled by rebind or `dispose` (Koin `onClose`). VM work stays in `viewModelScope`. `StateFlow`s carry immutable snapshots; no lock spans a suspension. The draft is heap-only: no `SavedStateHandle`, `rememberSaveable`, disk. Background connection close does not touch the draft; a coordinator modal change (including one after reconnect) retires it when the request differs.

## Error handling

No new I/O. Send failures keep the existing `modalSendErrors` → fixed local snackbar path. New logs are static debug events only (`event=permission_grant_draft action=set|cleared|retired`), never an id, rule or label.

## Testing strategy

- **Unit, `PermissionDraftStoreTest`** (new): isolation by server and conversation; bound collector retires on replacement, dismissal, a changed offer, and an owner rebind; the matching request keeps it.
- **Unit, `ThreadViewModelTest`**: a shared store preserves the grant across VM recreation (Back and reopen) and the recreated VM carries it on the allow; `onConversationLeft` clears the arm so allowing needs two fresh taps; a stale `modalId` on option, cancel and grant is ignored after replacement; existing #451/#818 cases stay green.
- **Shared screen, `ThreadScreenModalTest`** (adapted): no dialog; the request renders in an empty thread; Back invokes `onBack` without answering or cancelling; array order, default, armed marker, grant toggle, context and two-tap flow now in the stream (lambdas carry the `modalId`); compact 320×700 at 150% keeps every decision reachable, wrapping, unclipped; arrival and grant toggles keep a history reader anchored and add no history demand.
- **Device, `ThreadPermissionCaptureTest`** (adapted): static-fixture captures of normal, checked, armed (412×892) and compact (320×700, 1.5×) from the activity view; `FLAG_SECURE` on the activity window and decor/view obscured-touch filters while the request is present, an obscured `MotionEvent` on an option and on the grant row does not act while an unobscured tap does, and the prior window policy returns when the request is removed. Device-only reason: window flags and real `MotionEvent` dispatch. Evidence under `app/src/androidTest/assets/permission-1306/`.
- **Rung 3**: adapt `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` — inline selectors, tick the grant and arm Allow in A, leave for B (no A prompt), return to A (grant checked, arm cleared), two new taps allow; phone answer, A's session grant and peer resolution in B stay proven. The dispatcher's post-verifier live run is the proof.
- Focused unit/shared classes, `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin`, spotless; one focused device run of the capture class.

## Open questions

- Exact card spacing against the unfetched Figma nodes: resolved against the #1305 family and recorded as a deviation risk in the PR.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/permission-modal-overlay.md` and the thread topics (`thread-screen.md`, `thread-screen-how-it-works-list-and-status-row.md`) describe inline placement, the process-lifetime grant draft and its retirement, the leaving-chat arm reset and the moved hardening; `modal-answer-flow.md` notes the `modalId` guard on option/cancel. Record the named live result. Keep #1220 in Inbox.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Daemon text enters Compose only through `permissionRequestItems`, rendered as plain `Text` with a named length bound; option ids and `modalId` are compared, never displayed or interpreted. The fail-safe-deny highlight is still `option.id == open.defaultOptionId`.
- [Tokens] No findings. The grant draft is a UI intent, not a credential; the grant is only a boolean on an allow answer, computed by `grantsAlwaysAllow` against the current request.
- [File / storage] No findings. `PermissionDraftStore` is heap-only; nothing enters `SavedStateHandle`, `rememberSaveable`, DataStore or disk. Lazy keys carry `modalId` only, never request text. Capture evidence uses static fixture text.
- [Android surface] SHOULD FIX (landed in Phase B, verifier checks): the dialog's own-window `FLAG_SECURE` and `filterTouchesWhenObscured` no longer cover the request, so `QuestionPromptProtection` must be mounted for every open request — including one scrolled offscreen — and restore the prior policy on removal and on leaving. Its shared-owner counting keeps a question/permission overlap protected. No exported component, deep link or WebView.
- [Crypto] No findings. No change to Noise, keys or transport.
- [Network & I/O] No findings. Existing `answerModal`/`cancelModal` sends; no new frame, URL or timeout.
- [Errors / logs] No findings. Send failure stays the payload-free `Unit` event and a fixed local snackbar; dismissal stays the mapped local reason. New debug logs are static action codes.
- [Concurrency] MUST FIX addressed in design: a tap composed for a request that has since been replaced would, with today's option-id-only callback, answer the replacement (a one-tap default) or cancel it. Option and cancel callbacks carry the rendered `modalId` and the VM rejects a mismatch against `scopedModal()`'s synchronous read; the grant toggle already did. The store's collector is owned by a `SupervisorJob` scope cancelled on rebind/dispose; mutations are synchronized and never suspend under the lock.
- [Threat model] Hostile daemon text stays inert and cannot move the default highlight; screen capture and overlay tapjacking are handled by the activity protection above; the second-confirm gate still guards any allow, and leaving the chat now clears a half-made allow. OUT OF SCOPE: accessibility-service reading of on-screen text (unchanged platform trade-off, documented in `permission-modal-overlay.md`).

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01

## Revisions

- 2026-10-01: `InteractiveStreamE2ETest.awaitReadPrompt` scoped the Read prompt by "an ancestor holding Cancel". Inline, that ancestor is the message list, which would match the phone's own message naming the same file. It now scopes by the request card, as the adapted `promptDialog` / `inPromptDialog` helpers do; the Stop-control wait that looks for no Cancel text needed no change.
- 2026-10-01: The shared screen test first wrapped content in `DeviceConfigurationOverride.ForcedSize(412×892)`. On Robolectric's 320dp-wide screen that rescales density, so the 20dp checkbox measured 16dp. Robolectric's configured Pixel 2 height already fits the request, so the helper uses the default screen; the compact case keeps `ForcedSize(320×700)`, which does not exceed it.
- Final size: seven production Kotlin files (one new), three new declarations, five AC, no new send or error branch. About 1,150 written lines including tests, plan and text evidence. Boundaries hold.
