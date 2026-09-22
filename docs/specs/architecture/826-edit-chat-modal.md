# #826 — the mobile Edit chat modal

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal` — the shell this modal draws on: its `submissionEnabled && !loading` OK gate, its dismissal funnel, its error live region.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `EditHostModal`, `HostNameField`, `UnpairAction`, `boundedText`, `FIELD_FILL_ALPHA` — the sibling this modal mirrors: identity-keyed name buffer, the filled label-above field, the outlined in-content action at a 48 dp floor, the content-free debug log.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt` → `EditHostModalTest` — the model for the focused Compose test.
- `app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt` → `MAX_WORKSPACE_LABEL_CHARS` — the bound applied to the daemon-written pre-filled name.
- `app/src/main/res/values/strings.xml` → the `edit_host_*` block — where the three new strings sit.
- `docs/knowledge/features/mobile-modal.md` § Callers — key the edit buffer on the edited thing's identity, not its value; a dark-only frame hides a scheme-inverting fill token; `maxLines` is not a clamp.
- pyrycode-desktop `src/renderer/src/screens/channels/EditChatDialog.tsx` → `EditChatDialogView`, `EDIT_CHAT_COPY` — the source behaviour: sentence-case "Edit chat", "Channel name:" field label, "Archive chat"; OK disabled on `blank || !available`, Archive on `!available` alone; no archive confirmation.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2320 (Edit Chat content), adapted to the mobile shell per https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369.

A column of the shell's header ("Edit Chat" in `titleLarge`, the close glyph, the 60% `inversePrimary` divider), then content: a `labelLarge` semibold "Channel name:" label 8 dp above a filled, underline-free, 6 dp-cornered name field (`bodyMedium`), then 8 dp further down an outlined `primary`-bordered "Archive chat" button (`bodyLarge` medium), then the shell's centred Cancel/OK footer. Every piece already exists in `MobileModal` or as a pattern in `EditHostModal`; no new assets (the close glyph is the shell's `ic_modal_close`). Deliberate adaptations, all inherited from `EditHostModal`: the frame's `on-primary` 41% field fill becomes `onPrimaryContainer` at 12% so it survives the light scheme; the title renders sentence case "Edit chat", as desktop and the AC do; the archive action grows to the shell's 48 dp touch floor.

## Context

Desktop's `EditChatDialogView` renames or archives a chat. This slice is its phone presentation only: a stateless composable that #827 draws from a Chats row and wires to rename, and #828 wires to archive. Nothing draws it yet, so the focused Compose test is the proof.

File overlap: `strings.xml` is also touched by open branches `feature/657` and `feature/803`, in the thread string block; this ticket appends after the `edit_host_*` block, far from both hunks, so the merge is clean and no blocker is set.

## Design

One new file `ui/components/EditChatModal.kt`:

```kotlin
internal const val EDIT_CHAT_NAME_FIELD_TAG: String = "edit-chat-name"

@Composable
internal fun EditChatModal(
    conversationId: String,
    initialName: String,
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,          // the trimmed name, once per activation
    onArchiveRequested: () -> Unit,      // one request per activation
    modifier: Modifier = Modifier,
    hostAvailable: Boolean = true,
    loading: Boolean = false,            // a write is in flight
    error: String? = null,
)
```

- The pre-filled name is clamped with `take(MAX_WORKSPACE_LABEL_CHARS)` before it seeds anything.
- The field buffer is a `remember(conversationId) { mutableStateOf(TextFieldValue(bounded, cursor at end)) }` — keyed on the raw conversation id, never on `initialName`, so an incoming list update (a new `initialName`) cannot erase typed text, and `loading`/`error`/`hostAvailable` are not keys.
- OK: passes `submissionEnabled = hostAvailable && field.text.isNotBlank()` to the shell, which adds `!loading`. The submit lambda reports `field.text.trim()`. The IME Done action runs the same guard (`submissionEnabled && !loading`) before submitting.
- Archive: an `OutlinedButton` with `enabled = hostAvailable && !loading`, independent of the field's content. Its click logs `archive_requested` (content-free, debug-gated) and calls `onArchiveRequested`.
- Neither action closes the modal; the caller removes it from composition. Dismissal is the shell's.
- Private `ChatNameField` and `ArchiveAction` composables follow `HostNameField` and `UnpairAction` in shape. They are duplicated rather than shared, because extracting them would edit `EditHostModal` outside this ticket's scope; the duplication is about 50 lines.
- Strings: `edit_chat_title` "Edit chat", `edit_chat_name_label` "Channel name:", `edit_chat_archive` "Archive chat".
- Light and dark `@Preview`s at 412 × 892 dp.

## State + concurrency model

No coroutines, no jobs, no flows. The only state is the `remember`ed field buffer, which lives in the dialog's composition and dies with it.

## Error handling

The caller supplies `error`; the shell renders it in its live region. Nothing in the modal clears it or the field. Callers must keep `error` generic (it is announced aloud).

## Testing strategy

`app/src/androidTest/.../ui/components/EditChatModalTest.kt`, modelled on `EditHostModalTest`, with mutable `hostAvailable`/`loading`/`error`/`initialName` states driving one composed modal:

- Renders the title, the "Channel name:" label, the pre-filled name in the tagged field, "Archive chat", Close, Cancel and OK.
- Typing a name with surrounding spaces and pressing OK reports the trimmed name exactly once, reports no archive and no dismissal, and the modal stays; Archive then reports one request and still no dismissal.
- A blank and a whitespace-only field disable OK (a click reports nothing) while Archive stays enabled and reports.
- Host unavailable disables both OK and Archive; loading disables both.
- An error appears (with a live region) while the typed name is kept; flipping loading, error, availability and the caller's `initialName` leaves the typed name intact.
- An oversized pre-filled name appears only clamped to `MAX_WORKSPACE_LABEL_CHARS`.

No unit test: there is no logic outside composition. Not an operator-facing flow yet (nothing draws it), so no rung-3 scenario; #827/#828 own that.

## Open questions

- None blocking. The field label copies desktop's and the frame's "Channel name:" even for an unpromoted chat, matching desktop.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/mobile-modal.md` § Callers should name `EditChatModal` as a third caller of the shell (no ticket AC requires it; the ticket body names this section as the pattern source).

## Security review

**Verdict:** PASS (one SHOULD FIX, handled in this rework).

**Findings:**

- [Trust boundaries] SHOULD FIX, fixed. The only untrusted input is the daemon-written `initialName`. It crosses into the UI in one place: the seed of the `remember(conversationId)` buffer inside `EditChatModal`. That seed clamps with `take(MAX_WORKSPACE_LABEL_CHARS)`. A clamp that lands inside a surrogate pair leaves a lone high surrogate. OK sends the field back unedited, so the rename would carry a malformed tail. The seed now drops a trailing high surrogate after the clamp, and `theClampNeverSplitsASurrogatePair` proves it. The name renders only as the `TextField` value. It never reaches a `contentDescription`, a URL, a filename, a cache key, markup or a log. A clamped name shows in the field as it will be sent, so OK sends only what the operator sees.
- [Trust boundaries] OUT OF SCOPE. `EditHostModal`'s `boundedText` and `workspaceDisplayName` share the same surrogate-splitting `take`. They are outside this ticket's diff and are filed as #851.
- [Tokens, secrets] No findings. The modal touches no token, key or credential. A chat name is operator content, not a secret.
- [File / storage] No findings. The modal stores nothing. The buffer lives in composition and dies with the dialog, so nothing reaches disk or backup.
- [Android attack surface] No findings. The modal adds no activity, intent filter, pending intent, push path, provider or WebView.
- [Cryptographic primitives] No findings. The modal uses no randomness or cryptography.
- [Network & I/O] No findings. The modal opens no connection. The trimmed name it reports goes to `rename_conversation` through #827's caller. The daemon rejects a blank name as `protocol.malformed`, and OK is disabled on `isNotBlank()` to match. Where Kotlin's and the daemon's whitespace sets differ, the worst case is a rejected write that the caller surfaces as a generic error. The daemon sets no length limit on typed input. That input is the operator's own, and the transport's frame cap bounds the send. Every display site clamps the name again.
- [Error messages, logs] No findings. `logEditChatEvent` logs only the static event name `archive_requested`, behind `BuildConfig.DEBUG`. It logs no name and no conversation id. The caller supplies `error`, and the shell announces it through its live region. Callers (#827, #828) must map failures to generic string resources and must never include the daemon's message or the chat name. The KDoc on `EditChatModal` states this.
- [Concurrency] No findings. The modal launches no coroutine and holds no shared state. The one guard, `submissionEnabled && !loading`, is evaluated inside the click and IME handlers. A double tap during a write is therefore blocked once the caller raises `loading`. Callers own the in-flight de-duplication before that point.
- [Threat model] Hostile daemon frame: bounded by the clamp and text-only render above. Accessibility-service or keyboard eavesdropping on a chat name is out of scope, because the name is not a secret. Relay threats do not apply, because the modal opens no connection.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

## Revisions

- 2026-09-23 (verifier rework, PR #835): the plan lacked the `## Security review` required by the `security-sensitive` label. Added it above. Its one SHOULD FIX changes the seed contract. The pre-filled name is clamped to `MAX_WORKSPACE_LABEL_CHARS`, then a trailing high surrogate is dropped, so the clamp never splits a pair. The Testing strategy gains `theClampNeverSplitsASurrogatePair`. It asserts that the field shows the name without the split pair and that OK reports that exact name.
