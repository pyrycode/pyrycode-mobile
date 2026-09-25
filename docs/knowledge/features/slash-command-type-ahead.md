# Slash-command type-ahead

Composer suggestions that turn the conversation's published slash-command menu
([#882](https://github.com/pyrycode/pyrycode-mobile/issues/882), consumed by
[#884](https://github.com/pyrycode/pyrycode-mobile/issues/884) only to grey out
Actions-menu rows) into completions while the user types. Landed in
[#885](https://github.com/pyrycode/pyrycode-mobile/issues/885), following desktop's
`slashCommandTypeAhead.ts` / `ComposerSlashCommandTypeAhead.tsx`.

Package: `de.pyryco.mobile.ui.conversations.thread` (new file
`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/SlashCommandTypeAhead.kt`).
Renders through the shared [Options overlay](options-overlay.md), which gained an
optional `detail` line for this ticket. Figma: no dedicated frame — the ticket calls
for reusing the `Options overlay` component
([`533:1958`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958))
anchored above the `Input area`'s `Input large` field.

## What it does

While the whole [Thread input bar](thread-input-bar.md) draft is `/` followed by
non-whitespace characters, an `OptionsOverlay` opens above the composer listing the
conversation's published commands whose `name` or an `alias` contains the typed
fragment. Picking a row completes the draft to `/name`, with a trailing space only
when the command has a non-empty `argumentHint`, and does not send — the argument
stays editable and reaches the daemon through the ordinary send path. A slash
anywhere but the first character, a typed space or newline, an absent menu, or a
fragment nothing matches all leave the suggestions closed. A tap outside, Back, or
hiding the keyboard dismisses the overlay without changing the draft.

## Pure rules

Four `internal` functions carry the whole decision surface, independent of Compose:

- **`slashCommandTypeAheadRows(text: String, commands: List<SlashCommandMenuRow>?): List<SlashCommandMenuRow>`**
  — empty unless `text` is `/` followed by characters that are none of them
  `Char.isWhitespace`; a `null` or empty `commands` also yields empty. The needle is
  the fragment lower-cased with `Locale.ROOT` (Kotlin's plain `.lowercase()`), never
  the platform locale. Each row is bucketed by matching `name` then `aliases`:
  **prefix** (name or an alias starts with the fragment) sorts before **contains**
  (present but not at the start), which sorts before **cut-alias** — a row whose
  `truncatedFields` names `aliases` and which nothing visible matched, shown last so
  a hidden alias the daemon didn't fully report still surfaces. A row with no cut and
  no visible match is dropped entirely. Order inside each bucket is the daemon's own
  order. The description is never searched. A lone `/` is the empty-fragment case and
  matches every row by prefix.
- **`completeSlashCommand(row: SlashCommandMenuRow): String`** — `"/" + name`, plus a
  literal `" "` exactly when `argumentHint` is non-empty (a hint of a single space
  still counts as non-empty). The name is inserted verbatim, never re-derived from
  whichever alias matched — picking a row found through an alias still completes to
  the canonical name.
- **`slashCommandOptions(rows): List<OptionsOverlayOption>`** — `value` is the row's
  own **index**, never `name`; names are not unique and the overlay's row identity
  must not collide on a hostile or duplicate one. `label` is `"/" + name.inert()`,
  followed by `" " + argumentHint.inert()` when the hint is non-empty. `detail` is
  `slashCommandDetail(description)`, `null` when nothing printable survives.
- **`slashCommandDetail(raw: String): String?`** — line breaks and tabs become
  spaces, other ISO control characters are dropped, the result is capped at
  `MAX_SLASH_DETAIL_CHARS` (240) and returns `null` if what's left is blank. Display
  additionally caps at two lines with an ellipsis inside `OptionsOverlay` itself (see
  [Options overlay § Row modes](options-overlay.md#row-modes-radio-vs-button-884) for
  the row shape the `detail` line rides on).

## Host composable

`@Composable internal fun SlashCommandTypeAhead(text, commands, anchor: Rect?, imeVisible: Boolean, onComplete: (String) -> Unit, resetKey: Any?)`
holds one piece of state, `dismissedFor: String?`, in `remember(resetKey)` — never
`rememberSaveable`, and reset whenever `resetKey` (the conversation id) changes, so no
suggestion state leaks across conversations. The suggestions are open exactly when
`rows.isNotEmpty() && anchor != null && dismissedFor != text`:

- Picking row `i` sets `dismissedFor = completed` (the string `completeSlashCommand`
  returns) and calls `onComplete(completed)`. A command with no hint leaves a draft
  that would still match its own fragment; recording the completed text rather than
  clearing `dismissedFor` is what keeps the overlay from reopening over its own pick.
  The index is range-guarded against a stale row list.
- `onDismiss` — the scrim tap or `BackHandler` inside `OptionsOverlay` — sets
  `dismissedFor = text`, closing the overlay for the text it was shown for.
- `LaunchedEffect(text)` clears `dismissedFor` once the draft differs from whatever it
  was dismissed for, so any further edit reopens the suggestions.
- `LaunchedEffect(imeVisible) { if (!imeVisible) dismissedFor = currentText }` closes
  the suggestions when the software keyboard hides. Under Robolectric,
  `WindowInsets.isImeVisible` never reports `true`, so this effect only ever fires
  once on first composition with the draft empty — harmless, and the reason the
  keyboard-hide behavior is unit-tested against this composable directly rather than
  through `ThreadScreen` (see [Testing](#testing)).

Rows are capped to `MAX_SLASH_TYPEAHEAD_ROWS` (100) before reaching `OptionsOverlay`,
whose column is not lazy and has no bound of its own on a hostile or buggy menu's row
count; the remainder is reported through the overlay's existing "not listed" caption
so a capped list never reads as complete. A real menu measured 51 rows at ticket time
and fits without the cap engaging.

## `ThreadScreen` mount

`ThreadScreen` tracks the composer's live bounds — `var inputAnchor by remember { mutableStateOf<Rect?>(null) }`,
filled from [`ThreadInputBar`](thread-input-bar.md)'s `onAnchorChanged` — and
`WindowInsets.isImeVisible`, then draws `SlashCommandTypeAhead` in the same overlay
`Box` layer the footer's `OptionsOverlay` mount uses, passing
`anchor = inputAnchor?.takeIf { openMenu == null }?.translate(-layerOrigin)`. Gating
the anchor on the footer menu being closed is what keeps the two overlays from ever
stacking — see [Thread screen — overlays, retry and the app bar §
Slash-command type-ahead placement](thread-screen-how-it-works-overlays-and-app-bar.md#slash-command-type-ahead-placement-post-885)
for the exact wiring and its relationship to the footer's own anchor-tracking.
`onComplete` is `onDraftChange`, the same callback typing routes through, so a pick is
indistinguishable from the user having typed the completion themselves; nothing here
calls `onSendMessage`.

## `ThreadUiState` / `ThreadViewModel`

`ThreadUiState.slashCommands: List<SlashCommandMenuRow>? = null` — `null` means no
menu has been received yet, distinct from an empty list (a menu that really has no
commands). In `ThreadViewModel`, the flow that used to feed only
`absentComposerActions` is now named `slashCommandMenu: Flow<SlashCommandMenu?>`
(`observeSlashCommandMenu(conversationId).onStart { emit(null) }.distinctUntilChanged()`)
and feeds both `slashCommands = menu?.rows` and `absentActions =
absentComposerActions(menu, slashCommandsAccepted)` from the same `combine` arm — one
observation, two consumers. The second argument (#1111) is `uiState.runConfig.capabilities?.slashCommands
?: true`, unrelated to the menu itself — see [Thread composer footer § Actions
menu](thread-composer-footer-actions-menu.md#actions-menu-884) for the sibling consumer of the same menu.

## Trust boundary

Every `SlashCommandMenuRow` field (`name`, `aliases`, `argumentHint`, `description`)
is workspace-authored, bounded but unsanitized, per [Conversation repository](conversation-repository.md).
`slashCommandTypeAheadRows` and `completeSlashCommand` operate on the raw row and
never render anything themselves. Only `slashCommandOptions` turns a row into display
text, and only through `inert()` (label) and `slashCommandDetail()` (detail) — both
land in `OptionsOverlay`'s plain `Text` rendering only, never a content description, a
semantics key, a `testTag`, or a log line. `completeSlashCommand` inserts `name`
verbatim into the composer's plain-text field — the name has to be the literal string
the daemon recognises — but that text is user-editable and unsent until the ordinary,
already-reviewed send path fires. Row identity is the index, not the name, so a
hostile or duplicate name cannot collide in `onSelect`'s dispatch. See the ticket's
plan (`docs/specs/architecture/885-slash-command-type-ahead.md`, § Security review) for
the full self-reviewed pass, including the explicit out-of-scope note on Unicode
bidi/format characters surviving `inert()` — a posture shared with every `inert()`
label in the codebase, not specific to this feature.

## Testing

- **Unit**, `app/src/test/.../thread/SlashCommandTypeAheadTest.kt`: opening/closing
  (a lone `/` lists everything; `a/b`, `/cl ear`, `/clear\n`, `/\t`, `""`, a `null`
  menu and an empty menu all stay closed), ranking (prefix before contains before
  cut-alias, daemon order preserved within a bucket, an alias prefix beats a
  name-only contains, matching is case-insensitive, description never searched),
  completion (hint present → trailing space, hint absent → none, a hint of `" "`
  counts as present, an alias-matched pick still completes to the canonical name),
  and options (index-valued `value`, `inert()`-stripped label, the 240-char/collapsed
  detail, `null` when the detail is blank).
- **VM**, added to `ThreadViewModelComposerActionsTest`: a seeded menu reaches
  `state.slashCommands`; an unseeded conversation leaves it `null`.
- **Compose screen test**, `app/src/sharedTest/.../thread/SlashCommandTypeAheadScreenTest.kt`
  (Robolectric, `ThreadScreen` with a stateful draft): opening on `/cl`, staying closed
  for `x/cl` and an absent menu, picking a hinted row (draft becomes `/model `, overlay
  closes, `onSendMessage` not called, a typed argument then sends through the send
  button), outside-tap and Back dismissal leaving the draft unchanged and reopening on
  the next edit, and a 1,500-character multi-line description rendering bounded under
  two lines. Keyboard-hide dismissal is exercised on the internal `SlashCommandTypeAhead`
  composable directly (flip `imeVisible` true → false) rather than through
  `ThreadScreen`, per the Robolectric limitation noted under [Host composable](#host-composable).
- No device-only scenario: this is phone-local text completion that feeds the
  existing, already-covered send path. [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679)
  (open at ticket time, blocked by parent #655) is where the live real-claude proof for
  slash actions is assigned.

## Related

- [Thread input bar](thread-input-bar.md) — the composer the suggestions anchor to,
  including the `onAnchorChanged` reporting and the `TextFieldValue`
  cursor-placement fix a pick's outside change required.
- [Options overlay](options-overlay.md) — the shared popup this renders through,
  including the new `detail` secondary line.
- [Thread composer footer — Actions menu](thread-composer-footer-actions-menu.md#actions-menu-884) — the sibling
  consumer of the same [#882](https://github.com/pyrycode/pyrycode-mobile/issues/882)
  slash-command menu, reading it only for an absence verdict rather than for display
  text.
- [Thread screen — overlays, retry and the app bar](thread-screen-how-it-works-overlays-and-app-bar.md#slash-command-type-ahead-placement-post-885) —
  the `ThreadScreen` mount point.
- Spec: `docs/specs/architecture/885-slash-command-type-ahead.md`.
- Figma: [`533:1958`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958)
  (reused `Options overlay` component), parent frame
  [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8).
