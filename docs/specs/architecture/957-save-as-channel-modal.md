# #957 — Save a chat as a channel from its thread, in the mobile modal shell

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialog.kt` → `SaveAsChannelDialog`, `WorkspaceRadios`: the `AlertDialog` this ticket replaces; its focus comment (#589) is why focus is requested inside the modal content.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal`: the shell. Fixed "Cancel"/"OK" footer, `submissionEnabled`, `loading`, `error`. Cancel stays enabled while loading.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChatModal.kt` → `EditChatModal`, `ChatNameField`: the nearest analogue (#827). It provides the label-over-filled-well field style, `FIELD_FILL_ALPHA`, and the surrogate-safe clamp of a daemon-authored name to `MAX_WORKSPACE_LABEL_CHARS`. The buffer is keyed on `conversationId`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `submitChatName`, `ChatEditorState`: the saving/failed flag shape and the `compareAndSet(pending, …)` terminal transitions this ticket mirrors.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onOverflowEvent`, `transientDialogs`, `state`, `resolveWorkspace`, `toChannelSlug`, `AUTO_SUGGESTED_CHANNEL_NAME`, `Conversation.displayName`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadEvent.SaveAsChannelSubmit`, `WorkspaceChoice`, `SaveAsChannelDialogState`, `ThreadUiState`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the `state.saveAsChannelDialog?.let` call site.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` → `launchGuardedRepoCall`: this flow stops using it because a failure must now surface.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `promote` (`workspace = null` keeps `cwd`), `setSystemPrompt` (verbatim; throws `IllegalArgumentException` over the limit or for an unknown conversation), `SystemPromptLimit.fits`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SystemPromptEditor.kt` → `SystemPromptEditor`: reads the stored prompt on construction. **Not used**, because this flow never reads the stored prompt.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_saveAsChannel_promotesToChannelTier` plus the `SAVE_AS_CHANNEL_ITEM` / `KEEP_IN_SCRATCH_OPTION` / `SAVE_AS_CHANNEL_SAVE` constants.
- `app/src/test/.../ThreadViewModelTest.kt` → the four `onOverflowEvent_saveAsChannel*` tests, `guardedRepoCalls_whenRepositoryThrowsEachHandledType_areSwallowedWithoutCrashing`, `RecordingRepo`.
- `app/src/sharedTest/.../SaveAsChannelDialogTest.kt`: rewritten.
- `../pyrycode/docs/protocol-mobile.md` § `set_system_prompt`: the daemon supports the write, so the live scenario can exercise it.

In-flight overlaps (#685, #878, #883, #885, #905, #932) touch `strings.xml`, `ThreadScreen.kt`, `ThreadViewModel.kt`, `ThreadUiState.kt` and the e2e file in unrelated blocks. There is no dependency, so this ticket builds through them with additive edits.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369 (shell, already `MobileModal`). Content: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2355

The frame is titled "Save as channel" and has the shell's close glyph, a divider and a Cancel/OK footer. Its content is a column (12dp gap) of two "Input large" blocks, each with a `labelLarge` SemiBold label (`Channel name:` and `Channel system prompt:`) 8dp above a filled, underline-free well (radius 6 → `shapes.small`, `bodyMedium` text). The prompt well is a multi-line paragraph. No location choice. Colours follow `EditChatModal`'s mapping: an `onPrimaryContainer` fill at `FIELD_FILL_ALPHA` on the shell's `primaryContainer`.

## Context

Desktop withdrew the dedicated-folder choice (pyrycode-desktop #1436). The phone now promotes **in place** with `promote(id, name, workspace = null)`. `WorkspaceChoice`, `resolveWorkspace` and `toChannelSlug` are deleted. An optional system prompt is written after the promote is confirmed. The discussion drilldown's promotion `AlertDialog` is out of scope.

## Design

### `ui/components/ChannelFormFields.kt` (new): `ChannelFormFields`

```kotlin
@Composable
internal fun ChannelFormFields(
    name: TextFieldValue,
    onNameChange: (TextFieldValue) -> Unit,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    nameEnabled: Boolean = true,
)
```

- This is stateless: the caller owns both values. It is reused by the future Create channel modal.
- The name field is single-line with `ImeAction.Next`. On first composition it requests focus in its own `LaunchedEffect`, which runs inside the modal's dialog subcomposition, per `MobileModal`'s KDoc and #589. It is tagged `CHANNEL_NAME_FIELD_TAG`.
- The prompt field is multi-line with `minLines = 4`. The shell scrolls, so it has no max. It is tagged `CHANNEL_PROMPT_FIELD_TAG`. When `!SystemPromptLimit.fits(systemPrompt)` it shows `isError` and a static supporting text, `channel_form_prompt_too_long`.
- The field style is `EditChatModal`'s `ChatNameField` recipe, reimplemented privately. `EditChatModal` is not touched.

### `SaveAsChannelDialog.kt` (rewritten)

```kotlin
@Composable
fun SaveAsChannelDialog(
    conversationId: String,
    initialName: String,
    onSubmit: (name: String, systemPrompt: String) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    nameEditable: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
)
```

- The name buffer is `remember(conversationId)`. It is seeded with `initialName`, clamped surrogate-safe to `MAX_WORKSPACE_LABEL_CHARS` (the `EditChatModal` bound), and fully selected. The prompt buffer is `remember(conversationId) { "" }`, so it always opens empty.
- `submissionEnabled = name.text.isNotBlank() && SystemPromptLimit.fits(prompt)`. OK sends `onSubmit(name.text.trim(), prompt)`, with the prompt verbatim.
- This composable does no I/O and no logging beyond the shell's own events.

### `ThreadUiState.kt`

- `ThreadEvent.SaveAsChannelSubmit(name: String, systemPrompt: String)`, which replaces `workspace`.
- `WorkspaceChoice` is deleted.
- `SaveAsChannelDialogState(initialName: String, saving: Boolean = false, promoted: Boolean = false, failure: SaveAsChannelFailure? = null)`.
- `enum class SaveAsChannelFailure { Promote, SystemPrompt }`: flags only, so no daemon text can reach the shell's live region.
- `ThreadUiState.conversationName: String? = null`: the conversation's own name, before `displayName`'s fallback.

### `ThreadViewModel.kt`

- `SaveAsChannel` opens with `initialName = state.value.conversationName?.takeIf(String::isNotBlank) ?: "New channel"`.
- `SaveAsChannelDismiss` sets the dialog state to `null`. It sends nothing.
- `SaveAsChannelSubmit` calls a private `submitSaveAsChannel(name, systemPrompt)`:
  1. It ignores the submit when the dialog is `null` or `saving`, when the trimmed name is empty, or when the prompt is over the limit.
  2. `pending = dialog.copy(saving = true, failure = null)` is published.
  3. If `!dialog.promoted`, it calls `repository.promote(conversationId, trimmed, null)`. On failure it does `CAS(pending → saving=false, failure=Promote)` and stops.
  4. A blank prompt skips the write: `CAS(pending → null)`, done.
  5. Otherwise it publishes `CAS(pending → pending.copy(promoted = true))` and calls `repository.setSystemPrompt(conversationId, systemPrompt)`. On failure it does `CAS(→ saving=false, failure=SystemPrompt)`. On success it does `CAS(→ null)`.
- Retrying after a `promoted` state skips step 3, so no second promote is ever sent. The name field is disabled while `promoted` (`nameEditable = !promoted`), because the name is already stored.
- The catch covers `Exception`, rethrowing `CancellationException`, which is the `submitChatName` shape. It never logs a message, name or prompt. The logs are `RelayLog.d` static events: `save_as_channel_opened`, `_rejected`, `_promote_started`, `_promote_failed`, `_prompt_failed`, `_saved`, `_dismissed`.
- The `CAS` terminal transitions mean a result that lands after Cancel cannot resurrect the modal. The write chain itself continues after a dismissal, because the operator already pressed OK.
- `resolveWorkspace`, `toChannelSlug` and `AUTO_SUGGESTED_CHANNEL_NAME` are deleted, and the `"New channel"` literal moves into the open handler's constant.

### `ThreadScreen.kt`

The call site passes `conversationId`, `initialName`, `nameEditable = !promoted`, `loading = saving`, and `error` resolved from `failure` (`save_as_channel_failed` / `save_as_channel_prompt_failed`). The thread stays open: nothing navigates.

### `strings.xml`

- Remove: `save_as_channel_dialog_field_label`, `_workspace_dedicated`, `_workspace_scratch`, `_save`, `_cancel`.
- Add: `channel_form_name_label` ("Channel name:"), `channel_form_prompt_label` ("Channel system prompt:"), `channel_form_prompt_too_long`, `save_as_channel_failed`, `save_as_channel_prompt_failed`.
- `save_as_channel_dialog_title` stays.

## State + concurrency model

There is one `MutableStateFlow<SaveAsChannelDialogState?>`, already in `transientDialogs`. Writes run in one `viewModelScope.launch` and are cancelled with the VM. Everything runs on Main.immediate, so the `saving` guard and the publish are atomic with respect to taps.

## Error handling

A failed promote leaves the modal open, keeps the typed values in the composable buffers, shows a static error, and OK retries the promote. A failed prompt write after a confirmed promote leaves the modal open with its own static error, and OK retries only the write. An over-limit prompt disables OK, and the VM re-checks. Exceptions never reach UI state.

## Testing strategy

- **`SaveAsChannelDialogTest`** (sharedTest, Robolectric, rewritten):
  - renders the title, both labels and the footer;
  - prefills the name and leaves the prompt empty;
  - the name field is focused (the live predicate `hasSetTextAction() and isFocused()`, unique);
  - OK is disabled for a blank or whitespace name;
  - OK is disabled for an over-limit prompt and enabled at exactly `MAX_BYTES`;
  - OK submits the trimmed name and the verbatim prompt;
  - Cancel and Close submit nothing;
  - a long daemon name is clamped;
  - an error renders;
  - `nameEditable = false` disables the name field.
- **`ThreadViewModelTest`**: the four old save-as tests are replaced with tests on a small recording/failing repo double that serves one conversation:
  - opens with the chat's own name, or with "New channel" when it has none;
  - submit promotes in place (`workspace == null`) with the trimmed name and closes;
  - a blank prompt makes no `setSystemPrompt` call;
  - a non-blank prompt is written verbatim after the promote, in call order;
  - a promote failure keeps the dialog open with `failure = Promote`, and a retry promotes again;
  - a prompt failure keeps it open with `promoted = true, failure = SystemPrompt`, and a retry writes only the prompt (one promote in total);
  - dismiss sends nothing;
  - an over-limit prompt or a blank name sends nothing.

  The `SaveAsChannelSubmit` arm leaves the #490 guard test, whose comment is updated.
- **Rung 3**: `interactiveTurn_saveAsChannel_promotesToChannelTier` drives the modal. It replaces the name and types a short prompt into the prompt field (by tag), then taps OK. It waits for the modal to leave (the exact title text is gone), which now happens only after both writes are confirmed. The same in-thread and list assertions follow. The KDoc and constants are updated: `KEEP_IN_SCRATCH_OPTION` is removed and `SAVE_AS_CHANNEL_SAVE` becomes the `"OK"` label. The dispatcher runs the live suite. Locally this ticket only compiles it with `compileDebugAndroidTestKotlin`.

## Documentation handoff (pending, documentation stage)

- `docs/knowledge/features/save-as-channel-dialog.md`: rewrite it for the modal, the in-place promote and the prompt write. Drop the `WorkspaceRadios` and slug sections.
- `docs/knowledge/features/mobile-modal.md` § Callers: add Save as channel.
- Worth an ADR-style note in the overview: the phone has no dedicated channel folder, following pyrycode-desktop #1436.

## Open questions

- Is `ImeAction.Next` on the name field enough, with no submit-on-Done? Yes: the prompt is multi-line, so OK is the single submit route. This matches desktop's form.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The one daemon-authored value is the conversation name that seeds the name field. `SaveAsChannelDialog` clamps it, surrogate-safe, to `MAX_WORKSPACE_LABEL_CHARS` before layout, as `EditChatModal` does, and renders it only as `TextField` text. Both error strings are static resources chosen from a `SaveAsChannelFailure` flag, so no exception or server message reaches the shell's announced live region.
- [Tokens / secrets] SHOULD FIX. The operator's prompt may hold a pasted credential. `ThreadEvent.SaveAsChannelSubmit` is a `data class` whose generated `toString` would print it. Override `toString` to redact `systemPrompt`, following the `SystemPromptEditorState.Loaded` precedent. `SaveAsChannelDialogState` deliberately carries no prompt. The prompt buffer uses `remember`, not `rememberSaveable`, so it never enters the saved-instance-state Bundle. The trade-off is that the typed prompt is lost when the activity is recreated.
- [File / storage] No findings. The change removes a surface: `resolveWorkspace` / `toChannelSlug` no longer turn operator text into a daemon-side path, because `promote` is called with `workspace = null`. The phone persists nothing new.
- [Android IPC] Not applicable. There is no new component, intent, deep link or pending intent.
- [Crypto] Not applicable. The change travels over the existing Noise session through existing repository verbs.
- [Network & I/O] No findings. The prompt is checked with `SystemPromptLimit.fits` in the composable (OK is disabled) and again in `submitSaveAsChannel` before any send. `setSystemPrompt` also refuses over-limit input before framing.
- [Logs] No findings. The only logs are static `RelayLog.d` event names, and `RelayLog` is debug-gated. There is no name, prompt, conversation id or exception message.
- [Concurrency] No findings. The `saving` guard rejects double taps. The terminal transitions are `compareAndSet` against the published pending state, so a late result cannot resurrect a dismissed modal. `promoted` makes a retry skip the promote, so a confirmed promote is never repeated from the same modal. Known benign edge: if the operator dismisses during an unconfirmed promote and immediately reopens (the overflow still offers the action until `isPromoted` lands), they can send a second promote with the same effect. The daemon treats it as a rename of an already-promoted conversation.
- [Threat model] OUT OF SCOPE. Screenshot and IME leakage of the prompt field applies equally to the existing channel prompt surfaces (#824) and needs no new treatment here.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
