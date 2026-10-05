# SaveAsChannelDialog

Stateless form ([#957](https://github.com/pyrycode/pyrycode-mobile/issues/957)) that promotes a
discussion ([`Conversation`](data-model.md) with `isPromoted == false`) to a channel **in place**: a
channel name and an optional system prompt, drawn inside the shared [`MobileModal`](mobile-modal.md)
shell. It replaces the earlier `AlertDialog` with a binary workspace-location radio (`WorkspaceChoice`,
a dedicated-folder-vs-scratch choice and a slug helper) — desktop withdrew that choice
(pyrycode-desktop#1436) because no workspace entity exists beyond a conversation's exact `cwd`, and the
phone follows: `promote(id, name, workspace = null)` always keeps the conversation's own `cwd`. There is
no dedicated channel folder on the phone any more, and `WorkspaceChoice`, `resolveWorkspace` and the
slug helper are deleted.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialog.kt`).
Its form fields live in a separate, reusable file:
`de.pyryco.mobile.ui.components.ChannelFormFields` (`ui/components/ChannelFormFields.kt`). Hosted by
[`ThreadScreen`](thread-screen.md); the sole entry point is the [`ThreadOverflowMenu`](thread-overflow-menu.md)'s
discussion-only **Save as channel…** item.

Figma: shell at [`533:2369`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369)
(`MobileModal`), content at [`487:2355`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2355)
(desktop's Save as channel instance — no location choice).

## Shape

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

- **Presentation only.** It does no I/O and no logging beyond the shell's own events; every write and
  every retry decision lives on [`ThreadViewModel`](#threadviewmodel-the-two-write-state-machine).
- **`conversationId`** keys both typed-value buffers (`remember(conversationId)`), the same posture
  [`EditChatModal`](mobile-modal-callers.md#callers) uses for its name buffer. Neither buffer is keyed on
  `initialName`, `loading` or `error`, so a failed write leaves the operator's typed values in place for
  OK to retry.
- **`initialName`** seeds the name field, clamped surrogate-safe to `MAX_WORKSPACE_LABEL_CHARS`
  (`ui/workspace/WorkspaceDisplayName.kt`) before layout — the daemon sets no length limit on a
  conversation's name, and OK sends the field back unedited, so the clamp must not end on half a
  surrogate pair. The field opens fully selected, so the first keystroke replaces it outright.
- **`onSubmit(name, systemPrompt)`** fires only from OK, with the name trimmed and the prompt sent
  **verbatim** — the dialog is the trim authority for the name; the prompt is never trimmed anywhere.
  OK is enabled only when `name.text.isNotBlank() && SystemPromptLimit.fits(systemPrompt)`
  (`ConversationRepository.kt`'s `SystemPromptLimit`, 8192 UTF-8 bytes).
- **`onDismissRequest`** covers Cancel, the shell's Close glyph and Back — all from `MobileModal`, none
  of which submit.
- **`nameEditable`**, default `true`: the caller sets this `false` once the promote leg of a submit has
  been confirmed (`SaveAsChannelDialogState.promoted`), because the name is already stored and a retry
  writes only the prompt. The prompt field stays editable regardless.
- **`loading`** and **`error`** pass straight through to `MobileModal`: loading disables OK and keeps the
  shell's other dismissal routes live; a non-null `error` renders below the form with error semantics and
  a polite live region, while the typed values stay exactly as entered.
- The prompt buffer **always opens empty** — `remember(conversationId) { "" }` — because this flow never
  reads the conversation's stored prompt. A blank prompt on submit is the caller's cue to write nothing,
  so any prompt the chat already stores is left untouched.

## `ChannelFormFields`

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

Pulled into its own file, `ui/components/ChannelFormFields.kt`, rather than nested in
`SaveAsChannelDialog.kt`, because Create, Edit and Save as channel share the same controls — stateless,
the caller owns both values and every callback, no I/O.

- **Name field** — single-line, tagged `CHANNEL_NAME_FIELD_TAG`, `ImeAction.Next` (no submit-on-Done:
  the prompt below it is multi-line, so OK is the single submit route, matching desktop's form). It
  requests focus once in its own `LaunchedEffect(Unit)`. That effect **must** live inside the form's own
  composition, not in a parent composed before the modal call — `MobileModal`'s dialog window composes
  its content in its own sub-composition, so a `FocusRequester` attached from outside it is never bound
  when the request fires (#589; the same fix `RenameDialog` needed for the same reason). Fires cleanly
  either way (`requestFocus()` throws nothing when unbound), which is what made the original defect
  invisible until it was actually measured.
- **Prompt field** — multi-line, `minLines = 4`, tagged `CHANNEL_PROMPT_FIELD_TAG`, no `maxLines`: the
  modal's content column already scrolls, so a long prompt just grows the well. When
  `!SystemPromptLimit.fits(systemPrompt)` it exposes an error semantic and shows the static
  `channel_form_prompt_too_long` message in the error color; an optional caller note occupies that
  supporting slot when the prompt fits. The caller (the dialog) independently disables OK for the
  same reason, so the limit is enforced twice, once for the visible cue and once for the actual gate.
  `MAX_BYTES` is measured in UTF-8 bytes, not UTF-16 code units, so a limit test needs multi-byte
  characters to actually exercise the boundary (`SaveAsChannelDialogTest` uses `"é".repeat(...)`, two
  bytes each).
- **Field geometry** follows the current Figma input (`347:6446`) and all three form instances:
  `labelLarge` SemiBold labels in `onPrimaryContainer`, 8dp above filled wells and 12dp between field
  blocks. A `BasicTextField` inside an explicit 6dp rounded well avoids Material `TextField`'s extra
  minimum height and content insets. The name well is at least 52dp high, with 16dp top, bottom and
  left insets and 56dp reserved on the right; the prompt well is at least 112dp high, with 16dp on
  every side. Both use `bodyMedium` text and the shared modal field fill/text tokens, without a border
  or indicator. These are minimum heights: text and font scaling can grow the wells. The field nodes
  carry their label as a `contentDescription`; disabled fields remain readable without edit actions.
  Figma specified only the default dark state, so focus, disabled and over-limit appearances follow
  the theme and accessibility contract.
- **Visual evidence** — [412 × 892 emulator beside the native-scale Figma Create crop](../../../app/src/androidTest/assets/channel-fields-1233/viewport-comparison.png)
  and the [aligned field overlay](../../../app/src/androidTest/assets/channel-fields-1233/fields-overlay.png)
  compare actual API 33 pixels with the 2026-09-28 design. The [raw capture](../../../app/src/androidTest/assets/channel-fields-1233/emulator-create.png)
  and [Figma render](../../../app/src/androidTest/assets/channel-fields-1233/figma-create.png) are retained
  alongside them. The wider source form and phone modal place the outer shell differently; the field
  measurements stay at native scale, and the prompt wraps further at phone width.

## `ThreadViewModel`: the two-write state machine

`ThreadEvent.SaveAsChannel` opens the dialog seeded from the conversation's own name, not a generated
suggestion:

```kotlin
initialName = state.value.conversationName?.takeIf(String::isNotBlank) ?: "New channel"
```

`ThreadUiState.conversationName` (`conv?.name`, distinct from `displayName`'s fallback-applied value) was
added for exactly this seed. `ThreadEvent.SaveAsChannelDismiss` clears the dialog state and sends nothing.

`ThreadEvent.SaveAsChannelSubmit(name, systemPrompt)` calls a private `submitSaveAsChannel`, which runs
as **one, or two, sequential daemon writes** depending on whether a prompt was typed:

1. Ignore the submit outright — no state change, no log beyond `save_as_channel_rejected` — when the
   dialog is already `null`, already `saving` (the in-flight guard), the trimmed name is empty, or the
   prompt is over the byte limit. Everything here runs on `Main.immediate`, so this guard and the publish
   below are atomic with respect to taps; there is no window for a double-tap to start two writes.
2. Publish `dialog.copy(saving = true, failure = null)` as `pending`.
3. **If the promote has not already been confirmed** (`!dialog.promoted`), call
   `repository.promote(conversationId, trimmed, workspace = null)`. On failure, `compareAndSet(pending,
   pending.copy(saving = false, failure = Promote))` and stop — the modal reopens with the typed values
   still in its own buffers and OK will promote again.
4. **A blank prompt writes nothing.** `compareAndSet(pending, null)` closes the modal — the promote (or
   the already-confirmed promote from a prior attempt) is the only write this submit needed.
5. **A non-blank prompt** is written only after the promote is confirmed: publish
   `pending.copy(promoted = true)`, then call `repository.setSystemPrompt(conversationId, systemPrompt)`.
   On failure, `compareAndSet(promoted, promoted.copy(saving = false, failure = SystemPrompt))` — the
   modal reopens with `nameEditable = false` (the name is already stored) and OK retries **only the
   prompt write**, never a second promote. On success, `compareAndSet(promoted, null)` closes the modal.

Every terminal transition is a `compareAndSet` against the exact state object published right before it,
never a blind `.value = `. That is what makes a result landing after `SaveAsChannelDismiss` inert: the
dismiss already set `pendingSaveAsChannelDialog.value = null`, so the async result's `compareAndSet`
against its own stale `pending`/`promoted` reference simply fails and changes nothing — a late success or
failure can never resurrect a modal the operator already closed. The write chain itself is **not**
cancelled by a dismissal, since the operator already pressed OK before dismissing; only the modal's
visibility is affected.

The catch clause covers `Exception` and rethrows `CancellationException` — the same shape
`ChannelEditorController.submit` uses — and never logs an exception message, the name, the prompt or
the conversation id. Every log is a static `RelayLog.d` event name:
`save_as_channel_opened` / `_rejected` / `_promote_started` / `_promote_failed` / `_prompt_failed` /
`_saved` / `_dismissed`.

`resolveWorkspace`, `toChannelSlug` and the old `AUTO_SUGGESTED_CHANNEL_NAME` constant are deleted; the
`"New channel"` literal that used to seed every open now only applies when the conversation has no name
of its own, as `DEFAULT_CHANNEL_NAME` in the open handler.

### `ThreadUiState.kt` additions

```kotlin
data class SaveAsChannelDialogState(
    val initialName: String,
    val saving: Boolean = false,
    val promoted: Boolean = false,
    val failure: SaveAsChannelFailure? = null,
)

enum class SaveAsChannelFailure { Promote, SystemPrompt }
```

No typed value lives on this state — the name and prompt stay in the dialog's own composable buffers —
so a failure can only ever publish a flag, never a daemon message or the operator's text, and neither
reaches a logged state.

`ThreadEvent.SaveAsChannelSubmit` overrides `toString()` to redact the prompt
(`"SaveAsChannelSubmit(name=$name, systemPrompt=<redacted>)"`), following the
`SystemPromptEditorState.Loaded` precedent — the generated `data class` `toString` would otherwise print
whatever the operator typed, including a pasted credential, into any crash trace or logged event. The
prompt buffer inside the dialog composable uses `remember`, not `rememberSaveable`, for the same reason:
it stays out of the saved-instance-state Bundle, at the cost of losing an unsent, in-progress prompt on
activity recreation.

## `ThreadScreen.kt` host wiring

```kotlin
state.saveAsChannelDialog?.let { dialogState ->
    SaveAsChannelDialog(
        conversationId = state.conversationId,
        initialName = dialogState.initialName,
        onSubmit = { name, systemPrompt ->
            onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = name, systemPrompt = systemPrompt))
        },
        onDismissRequest = { onOverflowEvent(ThreadEvent.SaveAsChannelDismiss) },
        nameEditable = !dialogState.promoted,
        loading = dialogState.saving,
        error = when (dialogState.failure) {
            SaveAsChannelFailure.Promote -> stringResource(R.string.save_as_channel_failed)
            SaveAsChannelFailure.SystemPrompt -> stringResource(R.string.save_as_channel_prompt_failed)
            null -> null
        },
    )
}
```

The thread stays open behind the modal the whole time — nothing navigates, and there is no `PopBack` on
submit, dismiss or either failure. Both error strings are generic ("Couldn't save the chat as a channel.
Try again." / "The channel was saved, but its system prompt wasn't. Try again.") for the same reason
`EditChatModal`'s save error is: the shell announces the error text aloud, so no daemon-supplied message
should ever reach it.

## Strings

`save_as_channel_dialog_title` ("Save as channel") is the only string this dialog keeps from the old
`AlertDialog`. Removed: `save_as_channel_dialog_field_label`, `_workspace_dedicated`, `_workspace_scratch`,
`_save`, `_cancel` — the location choice and the dialog's own Save/Cancel labels no longer exist; OK and
Cancel come from `MobileModal`'s fixed footer. Added, in the `channel_form_*` namespace shared with the
Create channel modal: `channel_form_name_label` ("Channel name:"), `channel_form_prompt_label`
("Channel system prompt:"), `channel_form_prompt_too_long`, plus the two failure strings above.
`save_as_channel_action` ("Save as channel…", with the ellipsis) is the unrelated
[`ThreadOverflowMenu`](thread-overflow-menu.md) item string and is untouched.

## Tests

`ChannelFormFieldsTest` checks the 52dp/112dp wells, 8dp/12dp gaps, accessible names, Next focus,
verbatim prompt edits, error semantics, disabled fields and separation at 240dp width with 1.5× text.
Those layout bounds do not prove rendered pixels: `ChannelFormFieldsCaptureTest#createFormAt412By892`
provides the API 33 capture used in the visual comparison above. Scaling the wider Figma render down
would also shrink its well and conceal a size mismatch, so the comparison uses a native-scale crop.

**`SaveAsChannelDialogTest`** (`app/src/sharedTest/.../components/SaveAsChannelDialogTest.kt`, Robolectric)
covers: the title, both labels and the footer render, with no "Keep in scratch" remnant; the name prefills
and the prompt opens empty; the name field is the one focused, unique, editable field once the modal
composes (the exact `hasSetTextAction() and isFocused()` predicate the live scenario waits on); OK
disabled for a blank or whitespace name; the prompt's UTF-8 byte limit, exactly at and one byte over,
using two-byte characters to actually hit the boundary; OK submits the trimmed name and the verbatim
(untrimmed) prompt; an empty prompt submits as an empty string, not skipped client-side; Cancel and Close
send nothing; a long daemon-authored name is clamped without splitting a surrogate pair; an error renders
while the typed values (including an already-typed prompt) survive it; and `nameEditable = false` disables
the name field while leaving the prompt field and OK enabled.

**`ThreadViewModelTest`** replaces the four old `onOverflowEvent_saveAsChannel*` tests with coverage on a
small `SaveAsChannelRepo` double serving one conversation (`promoteCalls`, `setSystemPromptCalls`,
injectable `promoteFailures` / `promptFailures` counts and a `promoteGate` for exercising the in-flight and
late-result cases): the dialog opens seeded with the conversation's own name, or `"New channel"` when it
has none; a submit promotes in place (`workspace == null`) with the trimmed name and closes when the
prompt is blank, with no `setSystemPrompt` call; a non-blank prompt is written verbatim after the promote,
in call order; a failed promote keeps the modal open with `failure = Promote` and a retry promotes again;
a failed prompt write keeps it open with `promoted = true, failure = SystemPrompt`, and a retry issues
**exactly one** promote call across both attempts, writing only the prompt again; a submit while another
is in flight is ignored; a result landing after `SaveAsChannelDismiss` does not reopen the modal; Cancel
and both invalid-submit shapes (blank name, over-limit prompt) send nothing; and `SaveAsChannelSubmit`'s
`toString` redacts the prompt.

**Rung 3 (`interactiveTurn_saveAsChannel_promotesToChannelTier`, #581):** rewritten for the modal — no
"Keep in scratch" step, since there is no location choice to steer away from a real folder any more. It
replaces the focused, pre-filled name field, types a short prompt into the tagged prompt field, and taps
OK; it then waits for the modal's exact title to leave composition (proof both writes were confirmed —
the modal now stays open, holding the name, until they are) before reading the unique name off the thread
top bar, which prevents the modal's own field from matching first. See
[Real-claude e2e coverage](../../e2e-interactive-stream.md) for the full scenario writeup and its
in-place-promote rationale. `KEEP_IN_SCRATCH_OPTION` and `SAVE_AS_CHANNEL_SAVE` are gone from the test's
constants; `SAVE_AS_CHANNEL_OK` (`"OK"`) and `SAVE_AS_CHANNEL_TITLE` (`"Save as channel"`, matched exactly
against the ellipsis-bearing menu item) replace them, alongside `SAVE_AS_CHANNEL_PROMPT`.

## Related

- Shell contract: [Shared mobile modal](mobile-modal.md) — this is one of its direct `MobileModal`
  callers (§ Callers).
- Nearest analogue: [`EditChatModal`](mobile-modal-callers.md#callers) (#827) — the identity-keyed
  buffer and the surrogate-safe clamp of a daemon-authored name are lifted from it.
- [`ThreadOverflowMenu`](thread-overflow-menu.md) — the sole entry point.
- [`ConversationRepository.promote`](conversation-repository.md) / `setSystemPrompt` /
  `SystemPromptLimit` — the two writes this flow drives, and the byte limit both the field and the VM
  check.
- [`SystemPromptEditor`](#not-used-systempprompteditor) — considered and rejected; see below.
- The discussion drilldown's own promotion confirmation (`DiscussionListScreen`'s `AlertDialog`,
  `DiscussionListViewModel.requestHostPromotion`) is a separate surface, out of this ticket's scope, and
  unchanged.
- Figma: shell [`533:2369`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369),
  content [`487:2355`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2355).

### Not used: `SystemPromptEditor`

[`SystemPromptEditor`](system-prompt-editor.md) reads the conversation's stored prompt on construction
and refuses to save until that read lands — the right shape for editing an existing prompt, and the wrong
one here: this flow's whole point is that the prompt field never shows what is already stored, so a blank
submit reliably means "leave it alone." `SaveAsChannelDialog` uses `SystemPromptLimit` directly instead of
routing through that editor.

### No dedicated channel folder on the phone

Before #957, promoting to `WorkspaceChoice.DEDICATED` created (or referenced) a real
`pyry-workspace/channels/<slug>` directory, distinct from the conversation's existing `cwd`. Desktop
withdrew that concept (pyrycode-desktop#1436): there is no "workspace" entity beyond a conversation's
exact `cwd`, so a channel promoted into a new subfolder just showed up as an unrelated new workspace
group rather than living under the chat's own workspace. The phone now matches: `promote` is always
called with `workspace = null`, which keeps the conversation's id, history and `cwd` exactly as they
were — promoting only flips `isPromoted` and (optionally) renames it. There is currently no phone
affordance for moving a chat's files to a different folder as part of promotion; that would need a new,
separate flow if desktop ever reintroduces one.
