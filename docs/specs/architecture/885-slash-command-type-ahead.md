# #885 — Slash-command type-ahead in the composer

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `SlashCommandMenu`, `SlashCommandMenuRow`, `observeSlashCommandMenu` — the per-conversation menu (#882) and its security note: every string is workspace-authored, bounded but unsanitized, inert text only, never logged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `absentActions`, `absentComposerActions`, `state`, `inert` — #884 observes the menu but only a verdict reaches the screen; `inert()` is the existing control-character strip + length bound for daemon text.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadUiState.absentActions` — where the new field sits.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt` → `ThreadInputBar`, `FieldLeadingInset` — the composer field the suggestions anchor to.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`'s `openControl` / `footerAnchors` / `layerOrigin` and the `OptionsOverlay` mount — the overlay layer, anchor translation and conversation-keyed `remember` idiom to mirror.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/OptionsOverlay.kt` → `OptionsOverlay`, `OptionsOverlayOption`, `OptionsColumn` — the shared overlay: scrim takes the outside tap, `BackHandler` dismisses, keyboard focus is kept, rows are one ellipsized line.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterTest.kt` — the Robolectric `ThreadScreen` harness the new screen test copies.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelComposerActionsTest.kt` — the VM test that seeds `FakeConversationRepository.setSlashCommandMenu`.
- `docs/knowledge/features/options-overlay.md` — why the overlay is a same-window layer, not a `Popup` (focus and keyboard stay with the composer while the user keeps typing).
- desktop `slashCommandTypeAhead.ts` / `ComposerSlashCommandTypeAhead.tsx` — the reference rules: `^/(\S*)$`, prefix → contains → unknown-alias buckets, `toLowerCase` not locale, completion adds a space only for a non-empty hint, dismissal is keyed to the text it was made for, row identity is the index, never the name.

In-flight overlap: #883 edits `ThreadScreen.kt` (removes the stall banner) and #932 edits `ThreadViewModel.kt` (attachments). Neither is a dependency; my edits there are additive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

No suggestion frame is drawn. The ticket says to reuse the `Options overlay` component (`533:1958`, already built as `OptionsOverlay`, `surfaceContainerLowest` surface, 6dp corners, `primary` `bodySmall` row labels) anchored above the `Input area`'s input field (`Input large`, 6dp-cornered `surfaceContainerHigh` field with 16dp leading text inset). The one addition is an optional second line per row: the description, `bodySmall` in `onSurfaceVariant`, at most two lines.

## Context

The daemon publishes each conversation's slash-command menu (#882). #884 uses it only to grey out Actions-menu rows. This ticket turns it into a composer type-ahead following desktop #939/#940. The difference from desktop is that the description is shown, as the ticket requires.

## Design

### Pure rules — new file `ui/conversations/thread/SlashCommandTypeAhead.kt`

- `internal fun slashCommandTypeAheadRows(text: String, commands: List<SlashCommandMenuRow>?): List<SlashCommandMenuRow>`. Returns an empty list when the suggestions must stay closed. The text must be `/` followed by characters that are not `Char.isWhitespace`. The whole text is checked, so a slash that is not first, a space, a tab or a newline all close it. The needle is the fragment `.lowercase()`, which uses `Locale.ROOT` in Kotlin. Each row is ranked over `name` and then its `aliases`: prefix, contained or none, and a prefix hit anywhere wins. The result is prefix rows, then contained rows, then rows ranked none whose `truncatedFields` names `aliases`, each group in daemon order. The description is never searched. A lone `/` matches every row by prefix. A `null` or empty list gives an empty result.
- `internal fun completeSlashCommand(row: SlashCommandMenuRow): String`. Returns `"/" + name`, plus `" "` exactly when `argumentHint` is not empty. The emptiness test is literal. The name is used verbatim because its destination is the plain-text field.
- `internal fun slashCommandOptions(rows: List<SlashCommandMenuRow>): List<OptionsOverlayOption>`. The `value` is the row's index and never the name, because names are not unique identifiers. The `label` is `"/" + name.inert()`, followed by `" " + argumentHint.inert()` when the hint is not empty. The `detail` is the description, from `slashCommandDetail(description)` below. It is `null` when nothing printable is left.
- `internal fun slashCommandDetail(raw: String): String?`. Line breaks and tabs become spaces, other ISO control characters are dropped, and the result is capped at `MAX_SLASH_DETAIL_CHARS` (240). It returns `null` when the result is blank. The display is also capped at two lines with an ellipsis.

### Host composable, in the same file

`@Composable internal fun SlashCommandTypeAhead(text, commands, anchor: Rect?, imeVisible: Boolean, onComplete: (String) -> Unit, resetKey: Any?)`

- `dismissedFor: String?` is held in `remember(resetKey)`, never saveable. The suggestions are open when `rows.isNotEmpty() && anchor != null && dismissedFor != text`. A `LaunchedEffect(text)` clears `dismissedFor` once the text differs, so any edit re-opens them. This follows desktop's `slashCommandTypeAheadStateFor`.
- Picking row `i` sets `dismissedFor = completed` and calls `onComplete(completed)`. A command with no hint leaves text that still matches, and this keeps it from re-opening over its own completion. The index is range-guarded.
- `onDismiss`, from the scrim tap or `BackHandler` inside `OptionsOverlay`, sets `dismissedFor = text`.
- `LaunchedEffect(imeVisible) { if (!imeVisible) dismissedFor = currentText }` closes the suggestions when the keyboard hides. On first composition with the keyboard already hidden, this only suppresses an already-present `/…` draft until the next edit. It is harmless for hardware keyboards and in Robolectric, because the next keystroke changes the text.
- Draws `OptionsOverlay(options = slashCommandOptions(rows.take(MAX_SLASH_TYPEAHEAD_ROWS)), selectedValue = "", notListed = rows.size - shown, anchor, actions = true)`. `MAX_SLASH_TYPEAHEAD_ROWS` is 100. The overlay's column is not lazy, and nothing on the client bounds the menu's row count. The cap keeps a hostile or buggy menu from composing thousands of rows. The overlay's existing "not listed" caption reports the remainder, so a capped list never reads as complete. A real menu, which measured 51 entries, fits.

### `OptionsOverlay` — optional secondary line

`OptionsOverlayOption` gains `val detail: String? = null`. When it is not null, the row becomes a `Column` that holds the label, which is one ellipsized line as before, and the detail in `bodySmall` / `onSurfaceVariant` with `maxLines = 2` and an ellipsis. The clickable or selectable modifier moves to that `Column`. Rows without a detail render exactly as before.

### `ThreadInputBar` — anchor reporting

New parameter: `onAnchorChanged: (Rect) -> Unit = {}`. It reports the field's `boundsInWindow()` with `left` moved in by `FieldLeadingInset`, so the overlay's row text lines up with the typed text. The footer's `onAnchorChanged` does the same.

### `ThreadScreen` — mount

- `var inputAnchor by remember { mutableStateOf<Rect?>(null) }` is filled from `ThreadInputBar(onAnchorChanged = …)`.
- `val imeVisible = WindowInsets.isImeVisible` (`@OptIn(ExperimentalLayoutApi::class)`).
- `SlashCommandTypeAhead` is drawn in the same `Box` layer as the footer overlay: `text = draft`, `commands = state.slashCommands`, `anchor = inputAnchor?.translate(-layerOrigin)`. The anchor is `null` while a footer menu is open, so two overlays never stack. It also takes `onComplete = onDraftChange` and `resetKey = state.conversationId`.
- A completion goes through `onDraftChange`, the normal draft path. Nothing is sent. Sending stays with `onSendMessage(draft)`.

### ViewModel / state

- `ThreadUiState.slashCommands: List<SlashCommandMenuRow>? = null` means that no menu has been received. The #884 comment "only the verdict reaches the screen" is rewritten, because the rows now reach the screen, for rendering as inert text only.
- In `ThreadViewModel`, the `absentActions` flow becomes `slashCommandMenu: Flow<SlashCommandMenu?>`, which is `observeSlashCommandMenu(conversationId).onStart { emit(null) }.distinctUntilChanged()`. The final `state` combine sets both `slashCommands = menu?.rows` and `absentActions = absentComposerActions(menu)`, so one observation feeds both.

## State + concurrency model

No new coroutines in the VM, just one combine arm fewer (the same count). The UI state is `dismissedFor` in a `remember` keyed on the conversation id. There are two `LaunchedEffect`s keyed on the text and on `imeVisible`, and both end with composition.

## Error handling

There is no failure path. An absent menu, an empty menu or no match all close the suggestions. Out-of-range pick indices are ignored. Nothing is logged: the strings must never reach a log, and the feature has no network edge.

## Testing strategy

- **Unit tests, `app/src/test/.../thread/SlashCommandTypeAheadTest.kt`:**
  - Opening and closing: a lone `/` returns the whole list in order. `a/b`, `/cl ear`, `/clear\n`, `/\t`, and `""` return nothing, as do a null and an empty list. A fragment nothing matches returns nothing.
  - Ranking: prefix rows come before contained rows, in daemon order within each group. An alias prefix beats a name that only contains the fragment. Matching ignores case. The description is never searched. A row with cut aliases and no visible match trails the rest, but a row with empty aliases and no cut is dropped.
  - Completion: a hinted command gets a trailing space and an unhinted one does not. A hint of `" "` counts as non-empty. A row matched through an alias completes to its canonical name.
  - Options: the value is the index. The label is `/name` or `/name hint`, with control characters stripped. The detail has newlines collapsed and is capped at 240 characters, or is null when blank.
- **VM test**, added to `ThreadViewModelComposerActionsTest`: a seeded menu reaches `state.slashCommands`, and an unseeded conversation leaves it `null`.
- **Compose screen test, `app/src/sharedTest/.../thread/SlashCommandTypeAheadScreenTest.kt`** (Robolectric, `ThreadScreen` with a stateful `draft`):
  - `/cl` shows the matching rows. `x/cl` and an absent menu show no overlay.
  - Picking a hinted row sets the draft to `/model `. The overlay closes, `onSendMessage` is not called, and a typed argument then sends through the send button.
  - A scrim tap and Back dismiss the overlay and leave the draft unchanged. The next edit re-opens it.
  - Hiding the keyboard is tested on the internal `SlashCommandTypeAhead`: flip `imeVisible` true → false, and the overlay closes with the text unchanged.
  - A multi-line description of 1,500 characters renders with a bounded row height, under two lines.
- No device-only test. #679 holds the live proof, as the ticket says. There is no rung-3 or rung-4 scenario here, because the flow is phone-local text completion, and the send it leads to is the existing, already-covered send path.

## Documentation handoff

The ticket names no documentation requirement. The documentation stage may fold the type-ahead into `docs/knowledge/features/options-overlay.md`, which gains the detail line, and `thread-input-bar.md`. Status: pending for the documentation stage.

## Open questions

1. `WindowInsets.isImeVisible` under Robolectric is expected to stay false throughout. If it reports true, or flips, the screen test's pick path will show it. The fallback is that the IME test stays on the internal composable only.
2. The overlay's 240dp max width may crowd long descriptions. The two-line cap bounds the height regardless. A width change is left to a future drawing.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The workspace-authored `name`, `argumentHint`, `description` and `aliases` reach the screen as raw `SlashCommandMenuRow`s in `ThreadUiState.slashCommands`. Only `slashCommandOptions` turns them into display text. Labels go through `inert()`, which drops ISO control characters and bounds the text to 128 characters. The description goes through `slashCommandDetail`, which collapses line breaks, drops control characters, caps the text at 240 characters and shows at most two lines. Both are drawn by `Text` in `OptionsOverlay` only. They are never used in markdown, a WebView, a URL, a `testTag` or a key.
- [Trust boundaries] No findings on the completion. `completeSlashCommand` inserts the name verbatim into the composer, as desktop does, because the name has to be the command claude recognises. It reaches a plain-text field the user can edit and does not send. The user sees the exact characters in the composer before any send, and that send is the ordinary user-initiated message path. A name that contains whitespace produces text that closes the suggestions and cannot re-trigger them.
- [Trust boundaries] SHOULD FIX, folded into the design: nothing on the client bounds the menu's row count, and the overlay's `Column` is not lazy. `MAX_SLASH_TYPEAHEAD_ROWS` = 100 caps the rendered rows, and the remainder is reported through the existing "not listed" caption.
- [Trust boundaries] OUT OF SCOPE: Unicode bidi and format characters (category Cf) are not ISO control characters, so they survive `inert()`. They can reorder a row's own glyphs but cannot draw outside that row. The inserted text carries the same characters, so what the user sees and what the composer holds agree. This posture is shared with every `inert()` label in the codebase and is not specific to this ticket.
- [Row identity] No findings. The overlay value is the row's index, not the name, so duplicate or hostile names cannot collide in a key or in a lookup. `onSelect` range-guards the index.
- [Tokens, storage, IPC, crypto, network] Not applicable. This ticket adds no token, no storage, no intent, no crypto and no network I/O. The menu arrives through #882's already-reviewed decode path.
- [Logs] No findings. The type-ahead has no log call. The published strings must never be logged, and the feature has no content-free event worth recording, because it is local text completion.
- [Concurrency] No findings. The only new state is a composition-scoped `remember` keyed on the conversation id, which resets when the conversation changes, so no suggestion state leaks across conversations, and two `LaunchedEffect`s that end with composition. The ViewModel replaces one flow with another under the same `state` combine.
- [Threat model] Hostile daemon or workspace: these strings are bounded and rendered as inert text, as above. UI leakage: the strings are visible only in this app's own window, like thread content. A third-party keyboard sees the completed `/name` because it now sits in the composer, as it would if the user had typed it.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

### 2026-09-24: the composer places the cursor at the end after an outside change

The pick test found this. `ThreadInputBar` took a `String` and passed it to `BasicTextField(String)`, which keeps the old cursor offset when the text changes from outside. After picking `/model ` from `/mo`, an argument typed next would land inside the name, giving `/moopusdel `. `ThreadInputBar` now holds its own `TextFieldValue`. When `text` differs from the field's own value, the field adopts it with the cursor at the end. This covers a completion, and also the clear after a send. One exception: `text` still equal to the draft as it stood before the field's latest edit (`textAtLastEdit`) is the asynchronous draft round trip lagging behind, not an outside change, so the field keeps its own value and cursor. A `LaunchedEffect(text)` forgets `textAtLastEdit` once the draft catches up, so a send that clears back to that same text is still adopted. `ThreadInputBar`'s signature is unchanged apart from `onAnchorChanged`. Resolves no open question. The file count is unchanged, because `ThreadInputBar.kt` was already in scope.

### Open questions resolved

1. `WindowInsets.isImeVisible` stays false under Robolectric. The screen tests open, pick and dismiss normally, because the effect only fires on first composition, when the draft is empty. The keyboard-hide test drives the internal `SlashCommandTypeAhead`.
2. The width is unchanged at 240dp. The long-description test shows the row stays bounded.
