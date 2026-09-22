# #657 — Code block chrome and per-block copy

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` → `CodeBlock`, the `CODE_FENCE` / `CODE_BLOCK` arms of `MarkdownBlock`, the `CodeBlock*` constants — the only production file that changes shape.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRow.kt` → `CopyTextControl` (imported, not edited), `META_CONTENT_ALPHA` and its comment — #644's answer to `Schemes/Inverse Primary` for the glyph tint.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt` → `ExpandedSection` — the second `CodeBlock` caller; stays untouched because the copy control is opt-in.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → the finalized-assistant comment relying on `CodeBlock`'s own `fillMaxWidth()` — must stay true.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt`, `Theme.kt` → `background`, `primaryContainer`, `onPrimaryContainer`, `secondaryContainer` in both schemes.
- `app/src/androidTest/.../MarkdownTextTest.kt`, `app/src/test/.../MarkdownTextParsingTest.kt`, `app/src/androidTest/.../ToolCallRowTest.kt` — the rung-2 and parser test patterns #681 established.
- `docs/knowledge/features/markdown-text.md` § "Code blocks" — the language label sits outside the `horizontalScroll` chain so it stays anchored; the copy control follows the same rule.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=134-4809

The `Code` instance inside the assistant container is a column in a 6dp rounded box with a 1dp border (`Schemes/Primary Container`) and a `Schemes/Background` fill. Its `Header` row holds the language in `M3/label/medium-emphasized` (labelMedium, semibold) in `Schemes/On Background`, padded 16h / 8v. Below it is a 1dp `Schemes/On Primary Container` divider. The `Content` body is padded 16h / 12v and holds monospace code and an 11×12 `copy-solid-full` glyph anchored bottom-right. Width stays the bubble's.

## Context

`CodeBlock` today is a flat `surfaceContainer` tile with the language floated over the code's top-right corner and no copy control. This ticket gives it the design's bordered header/body treatment and mounts #644's `CopyTextControl` in the body. `ToolCallRow` takes the chrome for free and gets no copy control. No ADR is warranted.

**The copied string must be the block's source. The current join is wrong, confirmed by an AST dump on this tree.** Blank lines inside a fence arrive as consecutive `EOL` tokens with no `CODE_FENCE_CONTENT` between them. `joinToString("\n")` over the content tokens alone therefore collapses `a\n\n\nb` to `a\nb`, both on screen and on the clipboard. Fences nested in a list or a blockquote carry their container prefix (`"  "`, `"> "`) as separate `WHITE_SPACE` children. Indented blocks carry the 4-space indent marker inside each `CODE_LINE` token. An unterminated fence, the streaming case, simply has no `CODE_FENCE_END`.

## Design

### Source extraction (pure helpers, `internal` for the unit tests)

- `internal fun fencedCodeText(fence: ASTNode, source: String): String`: walks the fence's children after the opening line's terminating `EOL`. It appends `CODE_FENCE_CONTENT` text to the current line, and each `EOL` closes that line. It ignores `WHITE_SPACE`, the container prefix, and stops at `CODE_FENCE_END`. If the input ends without a closing `EOL`, the last line is kept too. It returns the lines joined with `"\n"`. Interior and trailing blank lines are kept, so the copied text keeps every blank line the source has, and relative indentation survives because it lives inside the content token.
- `internal fun indentedCodeText(block: ASTNode, source: String): String`: uses the same line walk over `CODE_LINE` / `EOL`, then drops the CommonMark indent marker from each line (up to four leading spaces, or one leading tab). A deeper indent keeps its excess.
- The `CODE_FENCE` and `CODE_BLOCK` arms call these in place of their inline joins. The language lookup is unchanged.

**Deliberate visible changes, both following from "exact source":** a fence's interior blank lines now render instead of collapsing, and an indented block no longer renders its 4-space marker as leading whitespace. Rendered and copied text stay one string.

### `CodeBlock` signature

`internal fun CodeBlock(content: String, language: String?, copyable: Boolean = false)`

The copy control is opt-in. The two markdown arms pass `copyable = true`, and `ToolCallRow`'s call compiles unchanged with no control. That meets AC4 without editing `ToolCallRow.kt`.

### Structure

`Surface(fillMaxWidth, RoundedCornerShape(6dp), color = background, border = BorderStroke(1dp, primaryContainer))` › `Column`:

1. **Header**, only when `language` is non-blank: a full-width `Text(language)` in `labelMedium.copy(fontWeight = SemiBold)`, colour `onBackground`, one line with an ellipsis, padded 16h / 8v. The 1dp `onPrimaryContainer` divider is drawn with `drawBehind` along the header's bottom edge. **The divider belongs to the header, not the body** (desktop's decision), so a languageless block has neither a bar nor a stranded rule. It is tagged `CODE_BLOCK_HEADER_TAG` for the absence assertion.
2. **Body**: a `Row(verticalAlignment = Bottom)`:
   - `Box(Modifier.weight(1f).horizontalScroll(..))` › `Text(annotated, softWrap = false, monospace bodyMedium, padding(start 16, end 16 or 8, vertical 12))`. Only this viewport scrolls. The end padding is 8dp when a copy control follows.
   - When `copyable`: `CopyTextControl(text = content, contentDescription = cd_thread_copy_code)` padded so the glyph sits 16dp from the right edge and about 12dp from the bottom (the 6dp touch padding is counted). **It is a sibling outside the scroll viewport**, a fixed right-hand rail rather than an overlay. It can never sit on the code at any scroll offset, and it cannot move while the code scrolls. The `Row`'s height is at least the control's, which gives an empty fence room for the glyph (desktop's minimum body height, for free).
   - The glyph tint comes from `LocalContentColor.current.copy(alpha = CODE_COPY_ALPHA)` (0.80, the same value and reasoning as #644's `META_CONTENT_ALPHA`), not from `inversePrimary`. `LocalContentColor` is `onBackground` inside this `Surface`. `inversePrimary` is `#9DCBFC` on the light `#F8F9FF` fill, which is illegible. #644 rejected it for the same reason.

The code text style is unchanged (`bodyMedium` monospace). The design's 12sp mono is not among the listed transfer items, and the renderer's type scale stays put.

### Constants

The constants that change are listed. `CodeBlockCornerRadius` goes 8 → 6dp. A new `CodeBlockBorderWidth` and `CodeBlockDividerWidth` are 1dp. `CodeBlockHeaderPadding` is 16h / 8v and `CodeBlockBodyPadding` is 16h / 12v. `CodeBlockCopyRailGap` is 8dp. `CodeBlockLabelVerticalPadding` and the old padding pair are deleted.

### Fill in both schemes

`background` against the bubble's `secondaryContainer`: light is `#F8F9FF` on `#D6E4F7`, and dark is `#101418` on `#3A4857`. In both, the block reads as its own surface, a step off the bubble (a step darker in dark, and lighter in light). The border follows the tokens the design names. In dark it resolves to the darker blue (`#134A74`), and the divider to the brighter one (`#CFE4FF`), as drawn.

### String

`cd_thread_copy_code` → "Copy this code block", placed after `cd_thread_copy_message`.

## State + concurrency model

There is none new. The only state is one `rememberScrollState()` per block (unchanged). The clipboard write happens in `CopyTextControl`'s click handler.

## Error handling

The extraction helpers are total over any AST. An unknown child type is ignored and never throws. `CopyTextControl` already caps clipboard length and logs nothing.

## Testing strategy

**Unit (`MarkdownTextParsingTest`, real parser):**
- fenced: interior blank lines kept (`"a\n\n\nb"`); a trailing blank line before the closing fence kept; code indentation kept; the info string excluded; a bare fence returns its content; an empty fence returns `""`; an unterminated (streaming) fence returns its partial content.
- fenced inside a list and inside a blockquote: the container prefix is dropped, the code's own relative indent kept, and the blank line kept.
- indented: the 4-space marker dropped, a deeper indent's excess kept, and an interior blank line kept.

**Rung 2 (`MarkdownTextTest`, real parsed markdown on the managed device):**
- a fence with an info string: the header tag exists once and shows the language; the copy control exists.
- an indented block and a bare fence: the header tag does not exist, and the copy control still exists.
- copy exactness: two blocks in one message, one with interior blank lines and indentation. Each control, reached with the all-nodes matcher, puts only its own block's exact source on a fake `ClipboardManager` provided via `LocalClipboardManager`.
- a long line in a width-capped container: the block does not exceed the container. After a left swipe on the code, the code text has moved while the header label and the copy control bounds are unchanged. The code's clipped bounds end at or before the control's left edge (not on top of the code).

**Rung 2 (`ToolCallRowTest`):** an expanded tool call with code-ish output renders no `cd_thread_copy_code` node.

The existing `MarkdownTextTest` cases cover "nothing else moves". No rung-3 or rung-4 scenario is needed: this is a renderer change with no daemon interaction, and #680 owns the live comparison.

## Documentation handoff

**Pending for the documentation stage:**
- `docs/knowledge/features/markdown-text.md` § "Code blocks". It needs the new structure: header plus divider on the header, a body row with the scroll viewport and the copy rail, the opt-in `copyable` parameter, and the source-extraction helpers with the blank-line fix. The language-label placement note, the file-private constants list and the "flat, no outline border" edge-case bullet all go stale.
- `docs/knowledge/features/tool-call-row.md`, where it describes reusing `CodeBlock`. The chrome is inherited, and the copy control is absent by default.

## Open questions

- Does the managed device's compose-test `swipeLeft` scroll a `horizontalScroll` child inside a `Row` reliably? If not, drive the scroll through `performScrollToIndex`-free means (`performTouchInput { swipeLeft() }` on the code text).
- Overlap: `origin/feature/803` (#803, In Code Review) also edits `strings.xml`, adding lines about 18 lines above this ticket's insertion point. This is a non-adjacent append. Verify with `git merge-tree` before opening the PR, not by blocking.
