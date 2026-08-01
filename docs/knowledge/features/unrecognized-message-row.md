# Unrecognized message row — `ThreadItem.UnrecognizedMessage` / `UnrecognizedMessageRow`

The thread's visible answer to a gap in the app's own parser ([#608](../codebase/608.md), split from
#585): a claude message the interactive daemon's stream-json reader could not understand renders as a
collapsed one-line pill that expands in place on tap to show the offending JSON verbatim, rather than
vanishing silently. The daemon used to discard unmapped kinds into a debug log the production build never
prints; it now sorts known-ignored kinds from genuinely unknown ones and forwards the latter as their own
`unrecognized_message` wire frame.

This slice builds **only the row and its type** — the `ThreadItem` subtype plus the composable that draws
it. Nothing produces the row yet; [#609](../codebase/609.md) decodes the wire frame, stamps `id` and
`occurredAt`, and folds it into the thread stream.

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `UnrecognizedMessageRow.kt`.
Type: `de.pyryco.mobile.data.repository.ConversationRepository.kt` (`ThreadItem.UnrecognizedMessage`,
`UnrecognizedSite`). Sibling of [`ToolCallRow`](./tool-call-row.md),
[`SessionBoundaryDelimiter`](./session-boundary-delimiter.md).

## The thread-row type

```kotlin
sealed interface ThreadItem {
    data class UnrecognizedMessage(
        val id: String,
        val site: UnrecognizedSite,
        val messageType: String,
        val raw: String,
        val truncated: Boolean,
        val occurredAt: Instant,
    ) : ThreadItem
}

enum class UnrecognizedSite { LineType, AssistantBlock, UserBlock, Undecodable }
```

- **`id` is client-owned, not a wire field.** The frame carries neither a message id nor a `turn_id`
  (opening a turn would wedge the conversation), so the row supplies its own identity. Two alternatives
  were rejected in the architecture spec: keying on list position breaks because
  `ThreadViewModel.render()` appends and drops a synthetic streaming `MessageItem`, shifting positions
  under an unrelated row; keying on the payload (`occurredAt + site + messageType + raw.hashCode()`)
  collides on two identical frames stamped in the same instant — exactly the repeat case the row has to
  keep distinct — and retains up to 16 KiB inside a key held for the list's lifetime. Uniqueness is
  documented as a producer invariant (asserted in tests, not enforced at construction), the same idiom
  `SessionBoundary` already uses for `workspaceCwd`. #609 stamps it; the v2 envelope's monotonic frame id
  is the expected source.
- **`messageType` is `""`, not `null`, when `site == Undecodable`** — nothing decoded, so no type was
  ever read, and modelling it as an empty string keeps the future decode arm total.
- **`raw` is a `String`, never nested JSON.** The daemon caps it at 16 KiB and truncates mid-blob, so a
  truncated cut is no longer valid JSON; it also scrubs invalid UTF-8 after cutting, so the string always
  arrives well-formed even on a mid-rune cut. Never parsed, trimmed, or reformatted — it renders verbatim.
- **`occurredAt` is load-bearing beyond ordering.** The wire carries no timestamp, but
  `ThreadItem.timestamp()` needs one, and `toChannelInfoUiModel` (`ThreadScreen.kt:601`) reads
  `items.firstOrNull()?.timestamp()` for the channel-info **"created"** label — an unrecognized row
  landing first in a thread supplies that label.
- **`UnrecognizedSite` is a closed, four-value enum** decoded from the wire string by #609. Its closure is
  what lets `siteLabel()` (below) stay exhaustive with no `else`.

## The row composable

```kotlin
@Composable
fun UnrecognizedMessageRow(item: ThreadItem.UnrecognizedMessage, modifier: Modifier = Modifier)
```

Structure clones [`ToolCallRow`](./tool-call-row.md): a stateful entry point owning a
`rememberSaveable` expand toggle, delegating to a stateless `UnrecognizedMessageRowContent(item,
expanded, onToggle, modifier)` so previews and tests can drive both states without gesture injection.
Only the `Boolean` toggle ever reaches saved-instance state — no payload field is written there.

**Collapsed** — a `Surface` (`RoundedCornerShape(12.dp)`, `colorScheme.surfaceContainer` fill,
1dp `colorScheme.outlineVariant` border, full-bleed `clickable`) holding one `Row`: a leading 18dp
`Icons.Outlined.WarningAmber` tinted `colorScheme.tertiary` (`contentDescription = null` — the adjacent
text carries the meaning), then a single annotated `Text` at `bodySmall`, `maxLines = 1` +
`TextOverflow.Ellipsis`. The annotated string appends, in order: the fixed client-owned label
(`onSurfaceVariant`), then — **only when `messageType` is non-empty** — a ` · ` separator and the type in
`FontFamily.Monospace` + `tertiary`, then another ` · ` and the site label (`onSurfaceVariant`). The
empty-`messageType` case (the `Undecodable` site) omits the middle span and its leading separator
entirely, rather than rendering an empty literal or a dangling `· ·`.

**Expanded** — appends a `Column` below the header: `raw` as a plain `Text` (`bodySmall` +
`FontFamily.Monospace`, `colorScheme.onSurface`, no `maxLines`, normal wrapping — verbatim, never
parsed/trimmed/reformatted), and, only when `truncated`, a second `Text` at `labelSmall` /
`onSurfaceVariant` carrying a client-owned truncation note.

`siteLabel(site: UnrecognizedSite): String` is a `@Composable` `when` expression with **no `else`** —
each arm returns a `stringResource`, so a future fifth site fails to compile rather than falling through
to a blank slot. This is the load-bearing half of the row's encoding posture: it keeps the daemon's
`site` value *selecting* client copy and never *becoming* rendered text, leaving `raw` and `messageType`
as the only daemon-supplied strings that reach the render.

### Design source and the `ToolCallRow` palette divergence

Figma `16-8` (Conversation Thread Screen) does not draw this row — the third design-owed thread-row gap
after [#406](../codebase/406.md) (thinking indicator) and [#388](../codebase/388.md) (tool row
running/failed states). The drawn sibling to match is the collapsed tool row `16-28`, and its tokens are
bound as M3 scheme roles, not literals:

| Figma token | Compose role |
|---|---|
| `Schemes/Surface Container` | `colorScheme.surfaceContainer` |
| `Schemes/Outline Variant` | `colorScheme.outlineVariant` |
| `Schemes/Tertiary` | `colorScheme.tertiary` |
| `Schemes/On Surface Variant` | `colorScheme.onSurfaceVariant` |
| `M3/body/small` | `typography.bodySmall` |

`ToolCallRow.kt:92` itself uses `surfaceContainerHigh` with no border — a drift from its own frame. This
row matches Figma `16-28` directly rather than propagating that drift; the two rows intentionally carry
different palettes and declare independent private dimension constants (`UnrecognizedRowVerticalSpacing`
etc.) so they stay separately tunable. The warning-glyph tint (`tertiary`, not `error`) is itself
design-owed — chosen because this reports a gap in the app's own parser, not a claude failure or a tool
error, so `error` would overstate it and `onSurfaceVariant` would erase it as a signal.

## Security posture

`raw` and `messageType` are the most untrusted strings the thread holds — unbounded, model-adjacent JSON
the daemon could not interpret, arriving over the network. The precedent is the permission modal's
render-time obligations (`ThreadScreen.kt:432-442`), not `LiteralScreenSurface`'s terminal-grid rule
bundle:

- **Inert output-encoding.** Every daemon string reaches a plain `Text` as a literal — never
  `MarkdownText` (which `MessageBubble` routes through) and no `SelectionContainer` (would open a
  clipboard exfiltration path).
- **No payload in persisted state** — only the expand toggle reaches `rememberSaveable`.
- **No logging** — nothing on this path logs `raw`, `messageType`, or `site`; preview fixtures are
  hand-written literals, never captured live payloads.
- **The type span's `Monospace` + `tertiary` styling is a security property, not decoration.** Because
  `messageType` renders adjacent to client-owned copy in one annotated string, a crafted value could try
  to impersonate the fixed label or the site text. Three things contain it and none may be dropped: the
  type is the *only* span in that styling (the client's spans are proportional + `onSurfaceVariant`), it
  always renders *between* the fixed label and the site label rather than replacing either, and the
  caller's `maxLines = 1` + ellipsis clips any embedded newline so a payload can't manufacture extra
  visual rows.
- **Explicitly out of scope: `FLAG_SECURE` and `softWrap = false`.** Both are `LiteralScreenSurface`
  precedents that don't transfer — `FLAG_SECURE` is per-window, and a thread row has no window of its
  own (applying it would harden the whole host Activity as a side effect of one diagnostic row);
  `softWrap = false` is that surface's terminal-grid requirement, not this row's — it wraps like its
  neighbours.
- **Layout cost of a pathological payload was reviewed and accepted, not defended against.** No
  `maxLines` cap on the expanded body means a 16 KiB payload of newlines lays out ~16,000 lines in one
  `LazyColumn` item. Not a MUST-FIX: capping it would violate the verbatim-render requirement, expansion
  is user-initiated per row, and this row is strictly more bounded (16 KiB, daemon-side) than
  `MessageBubble`/`ToolCallRow`'s expanded output, which render daemon-supplied text with no cap at all.

Full architect security review: [`608-unrecognized-message-row.md`](../../specs/architecture/608-unrecognized-message-row.md)
§ Security review (verdict **PASS**).

## `ThreadScreen` wiring

All three of `ThreadScreen`'s exhaustive `when`s over `ThreadItem` gained a third arm:

- **`LazyColumn` key** — `"unrecognized:${item.id}"`.
- **Render** — `UnrecognizedMessageRow(item = item)`, placed *inside* the existing `rowAlpha`-driven
  `Box` (`ThreadScreen.kt:296`), so above-delimiter dimming applies with zero new code.
- **`ThreadItem.timestamp()`** — `occurredAt`.

Every other `ThreadItem` reference in the tree (`ThreadViewModel.kt`, other `ThreadScreen.kt` sites) is
an `is`-check inside `any`/`filter`/`filterIsInstance`/`lastOrNull`, which a new sealed subtype cannot
break — see [Thread screen](./thread-screen.md).

A fourth exhaustive `when` over `ThreadItem` exists outside production code, in
`RemoteConversationRepositoryTest.threadShape()` — a test-fixture helper that also needed the third arm
to keep compiling. See [#608](../codebase/608.md) Lessons learned.

## Testing

Instrumented-only (Compose rendering, no JVM-testable logic):
`app/src/androidTest/.../components/UnrecognizedMessageRowTest.kt`, cloned from `ToolCallRowTest`'s
harness. 8 scenarios: collapsed default, expand/collapse round-trip, byte-verbatim short-body rendering,
truncation note presence/absence, the empty-`messageType` collapsed line (asserted on absence of a
dangling separator, not just label presence), and toggle survival across a `StateRestorationTester`
config-change simulation. Run with `./gradlew connectedAndroidTest`;
`compileDebugAndroidTestKotlin` first, since instrumented sources are not compiled by `test`/`lint`/
`assembleDebug`.

AC 1's interleaving/ordering/no-collapse-on-repeat properties are **not** testable here — nothing in
this slice can produce two rows to interleave. That end-to-end assertion belongs to
[#609](../codebase/609.md), the first slice that can emit them.

## Related

- Ticket notes: [`../codebase/608.md`](../codebase/608.md)
- Spec: [`docs/specs/architecture/608-unrecognized-message-row.md`](../../specs/architecture/608-unrecognized-message-row.md)
- Wire SSOT: `pyrycode/docs/protocol-mobile.md` § `unrecognized_message` (sibling checkout).
- Desktop sibling: `pyrycode-desktop`'s `ConversationScreen.tsx` `UnrecognizedRow` (shipped `8c0d013`) —
  this row's copy is taken verbatim from it so the two clients agree.
- Structural precedent: [`ToolCallRow`](./tool-call-row.md) (collapsed-pill + in-place-expand shape,
  palette intentionally diverges).
- Consumer: [`Thread screen`](./thread-screen.md) — `LazyColumn` key, render arm, `timestamp()`.
- Downstream: [#609](../codebase/609.md) — decodes the `unrecognized_message` wire frame, stamps `id` /
  `occurredAt`, folds it into `RemoteConversationRepository`'s thread stream, and owns the end-to-end
  interleaving/ordering coverage this slice couldn't write.
- Prior design-owed thread-row gaps: [#406](../codebase/406.md) (thinking indicator),
  [#388](../codebase/388.md) (tool row running/failed states).
