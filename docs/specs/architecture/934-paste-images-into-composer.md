# #934 — Paste clipboard images into the composer as attachments

## Files read

- `ui/conversations/thread/ThreadInputBar.kt` → `ThreadInputBar` — uses the `TextFieldValue` overload of `BasicTextField`, plus the #885 cursor rule (`fieldValue` / `textAtLastEdit`). The migration has to keep that rule.
- `ui/conversations/thread/AttachmentPicker.kt` → `PickedAttachment`, `rememberAttachmentPicker`, `describePickedAttachment` — the #933 pick path. A paste reuses its description step and its sink.
- `ui/conversations/thread/AttachmentReader.kt` → `isForeignContentUri` — the content-URI trust boundary, applied to a pasted URI before the resolver is asked anything.
- `ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` parameter `onAttachmentsPicked`, the `ThreadInputBar` call in the bottom bar — a paste flows into the same `onAttachmentsPicked`, which `MainActivity` already binds to `ThreadViewModel.addPickedAttachments`. So the size and count refusals and the snackbar come for free, and `MainActivity` does not change.
- `ui/conversations/thread/ComposerAttachmentStrip.kt` → `ATTACHMENT_STRIP_TEST_TAG`; each tile's merged content description is the display name. The device test asserts on these.
- `app/src/sharedTest/.../SlashCommandTypeAheadScreenTest.kt`, `ThreadFrameTest.kt` — pin #885's completion cursor and the Send/Stop button. Both must stay green across the field migration.
- `app/src/sharedTest/.../ComposerAttachmentStripTest.kt` — the Robolectric `ThreadScreen` harness the new screen tests mirror.
- `docs/knowledge/features/thread-input-bar.md`, `attachment-upload.md` — the draft-binding rule (#789) and the send-time bounded read (#932).
- Compose foundation 1.10.4, which is what BOM `2026.02.01` resolves to. Inspecting the bytecode shows that only the `TextFieldState` text field reads `ReceiveContentConfiguration` (`TextFieldDecoratorModifierNode`, `TextFieldSelectionState`). The legacy `value`/`onValueChange` field ignores `Modifier.contentReceiver`. The IME commit path (`StatelessInputConnection`) calls `InputContentInfoCompat.requestPermission()` itself. `contentReceiver` is `@ExperimentalFoundationApi`.

No in-flight `feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

There is no new visual. A pasted image shows up as the same `Attachment area` tile (390:7136) that #933 built: a 45 × 60 tile cropped to fill, with 6dp corners and the circle-x remove icon at its top-trailing corner. The paste reuses `ComposerAttachmentStrip` unchanged.

## Context

#933 added the picker and the strip. Pasting an image does nothing yet: the legacy text field accepts text only from the clipboard and advertises no content MIME types to the keyboard. This ticket routes a pasted image into the existing add path. The paste can come from the clipboard's Paste or from the keyboard's image insert (`commitContent`). No ADR needed.

## Design

### Field migration — `ThreadInputBar`

`BasicTextField(value, onValueChange)` becomes `BasicTextField(state: TextFieldState, …)`, because `contentReceiver` needs it. The public signature (`text`, `onTextChange`, …) stays, so the draft stays bound to `ComposerDraftStore` through `ThreadScreen`'s `draft` / `onDraftChange` exactly as it is.

- `val fieldState = rememberTextFieldState(text)` (cursor at end).
- **Outward (user edits → draft):** an `InputTransformation` calls `onTextChange(newText)` synchronously for every user edit that changes the text, and records `textAtLastEdit = text` first. This covers typing, pasted text and IME edits. A programmatic `setTextAndPlaceCursorAtEnd` does not run input transformations, so an outside change never echoes back.
- **Inward (outside change → field):** `LaunchedEffect(text)`. If `text` differs from the field and is not `textAtLastEdit`, the draft changed from outside (a completion, a cleared send): `fieldState.setTextAndPlaceCursorAtEnd(text)`. When `text` equals the field, clear `textAtLastEdit`. This is #885's rule moved from a derived value to an effect.
- Keep the design's metrics and behaviour: `lineLimits = MultiLine(maxHeightInLines = 5)`, `keyboardOptions` with `ImeAction.Send`, `onKeyboardAction = { onSend() }`, the same `decorator` with the placeholder, the same text style and cursor brush.
- New parameter `onImagesReceived: ((List<Uri>) -> Unit)? = null`. When it is non-null, the field gets `Modifier.contentReceiver`. Its listener consumes each clip item `isPastedImageItem` accepts, collects those URIs, and passes the batch to `onImagesReceived` when it is not empty. It returns the remainder, so text items still paste as text.

### Paste classification — `AttachmentPicker.kt`

- `internal fun isPastedImageItem(item: ClipData.Item, description: ClipDescription, ownPackage: String): Boolean` — true only when the item has a URI, `isForeignContentUri` accepts it (so the scheme is `content:` and the provider is not this app's own), and the clip's description declares an `image/*` type. This is synchronous and makes no binder call, so it is safe inside `onReceive`.
- `internal fun describePastedImage(resolver: ContentResolver, uri: Uri, ownPackage: String): PickedAttachment?` — `describePickedAttachment`, kept only when the provider's own `getType` is `image/*`. The pasting app's clip description is a claim. The provider's type is the check.
- `@Composable fun rememberPastedImageReceiver(onPicked: (List<PickedAttachment>) -> Unit): (List<Uri>) -> Unit` — describes the URIs on `Dispatchers.IO` in a `rememberCoroutineScope`, logs counts only, and calls `onPicked` once with the survivors in clip order. This mirrors `rememberAttachmentPicker`.

### Screen — `ThreadScreen`

`val onImagesReceived = rememberPastedImageReceiver(onAttachmentsPicked)` is passed to `ThreadInputBar`. No new `ThreadScreen` parameter.

## State + concurrency model

- `onReceive` runs on the main thread. It only classifies and hands the URIs over. The provider queries run on IO in the composition's scope, so a paste resolving after the screen has left is dropped with it, the same as a pick.
- The field's `TextFieldState` is the composition's. The draft remains the source of truth across navigation (#789): a fresh composition seeds the field from `text`.

## Error handling

- Non-image or non-`content:` item, or this app's own provider → not consumed. The text field then handles the remainder as it normally would.
- Provider type is not `image/*`, or the query fails with no type → dropped, logged by static code.
- Too large or too many → the #933 refusal snackbar, through `addPickedAttachments`.
- The grant expires before send (a clipboard or keyboard grant can outlive the paste only so long) → the #932 send-time read fails as `Unreadable`, and the send reports failure and keeps the draft. Bytes are read only at send time, by design.

Logging: `RelayLog.d` static codes only — `event=composer_attachment_paste count=<n>` and `event=composer_attachment_paste outcome=refused_type`. Never a URI, name or type.

## Testing strategy

Unit/Robolectric (`app/src/test`, run with Robolectric for `ClipData`):
- `AttachmentPasteTest`: `isPastedImageItem` accepts a foreign `content:` URI under an `image/png` description. It refuses a text-only item, a `file:` URI, this app's own authority, and a `content:` URI under an `application/pdf` description. `describePastedImage` keeps an `image/jpeg`-typed URI and drops a `text/plain`-typed one, using a Robolectric-registered provider.

Compose (`app/src/sharedTest`, Robolectric) — `ComposerPasteTest`:
- The existing `SlashCommandTypeAheadScreenTest` and `ThreadFrameTest` suites stay green. They cover the field migration: completion cursor at end, typed text reaching the draft, and the Send/Stop rule.
- A text paste (`SemanticsActions.PasteText` with plain text on the clipboard) inserts text into the draft and adds no attachment.

Device (`app/src/androidTest/.../ComposerImagePasteDeviceTest.kt`), per AC#2: the test inserts a small PNG through `MediaStore` (a real foreign `content://media/...` URI with a real provider type). It puts `ClipData.newUri` on the real clipboard, renders `ThreadScreen` over a `ThreadViewModel` (fake repository, real `ComposerDraftStore`), and triggers the field's `PasteText` action. Then it asserts one strip tile whose content description is the file's display name. Cleanup deletes the MediaStore row. Device-only because the test needs the real clipboard service and a real `MediaStore` provider with its grant and `getType`. Robolectric's clipboard and resolver are shadows, so they cannot prove the platform path the AC names.

No rung-3 scenario: this ticket adds a way to add an attachment locally and does not talk to the daemon. The live attachment exchange belongs to #674.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/thread-input-bar.md` should record the `TextFieldState` migration, the `InputTransformation` / `LaunchedEffect` draft binding, and the image paste receiver. `thread-screen.md` § Composer pending attachments should record that a paste joins the pick path.

## Open questions

- Whether `SemanticsActions.PasteText` on a `TextFieldState` field routes through `contentReceiver` in tests the way a user Paste does. The bytecode shows `TextFieldSelectionState` reading `ReceiveContentConfiguration`, which suggests it does. If it does not, the device test drives the listener through the text toolbar instead.

## Security review

**Verdict:** PASS

The adversary is another app: whatever put the clip on the clipboard, or whichever keyboard committed content, controls the clip description, the URI and, through its provider, the name, size, type and bytes.

**Findings:**

- [Trust boundaries] No findings. There are two gates, both named. `isPastedImageItem` refuses any URI `isForeignContentUri` refuses (`file://`, `android.resource://`, this app's own provider, including behind a `userId@` prefix) before anything is queried. So a clip that points at this app's private provider can never be attached and later uploaded to the daemon. `describePastedImage` then re-checks the type against the provider's `getType`. The clip description's `image/*` claim alone admits nothing.
- [Trust boundaries] No findings. The display name and type go through `ComposerDraftStore`'s `clampProviderText` as for a pick. The size pre-screen is advisory, and the #932 send-time `readBounded` enforces the 8 MB limit on the real bytes.
- [Trust boundaries] SHOULD FIX, apply in Phase B: a pasted item the listener does not consume falls back to the text field, which pastes the item's text coercion. The listener must consume only items it will actually hand on. Nothing is silently eaten. A refused URI item falls through as text, which is the platform's normal behaviour for a URI clip.
- [Tokens] Not applicable: no token, key or credential is read, created or stored.
- [File / storage] No findings. URIs are never turned into paths and nothing is written. `takePersistableUriPermission` is not called. Drafts stay heap-only.
- [Android surface] No findings. No component, intent filter or permission is added. `EditorInfo.contentMimeTypes` is set by Compose for the focused field only. The keyboard's `commitContent` grant is requested by Compose's `StatelessInputConnection` and is scoped to that URI.
- [Crypto] Not applicable.
- [Network & I/O] No findings. The upload path is unchanged. A hostile provider that stalls `query` or `getType` stalls an IO coroutine owned by the composition, never the main thread.
- [Logs] No findings. Static codes and counts only. `PickedAttachment.toString` is already redacted.
- [Concurrency] No findings. The receiver's coroutines belong to the composition. `addPickedAttachments` runs on main, as for a pick. The field's user edits and the draft's outside changes are reconciled by one rule, and a programmatic set cannot re-enter `onTextChange`.
- [Threat model] OUT OF SCOPE: a clipboard-reading attack (another app reading this app's clipboard) is unaffected, because this ticket only reads from the clipboard, on a user's Paste. Clipboard access prompts are the platform's.
- [Threat model] OUT OF SCOPE: platform decoding of a hostile image happens in `ContentResolver.loadThumbnail`, which #933 already uses. No new decoder.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
