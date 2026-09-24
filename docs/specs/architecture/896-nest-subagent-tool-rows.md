# #896 — Nest a subagent's tool rows under the agent call that spawned them

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` → `ToolCall.parentToolUseId` — the grouping hint; `""` is the main thread. A tool row's `Message.id` is its own `tool_use_id`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt` → `foldQueuedRows`, `ThreadRow.listKey` — the render-time fold beside which the new pure derivation sits; list keys stay untouched.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`'s `LazyColumn` `itemsIndexed` block — where `rows` is remembered on `state.items` and each `ThreadItem.MessageItem` renders through `MessageBubble`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → `MessageBubble`'s `Role.Tool` arm, `MessageContentGutter`, `MessageAreaRowSpacing` — the gutter is applied at this call site; the indent joins it there.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt` → `ToolCallRow`, `ToolCallRowContent` — the clickable, merge-descendants `Column` whose semantics the subagent description joins.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadFrameTest.kt` → how a shared screen test mounts `ThreadScreen` with a `ThreadUiState`.
- `docs/knowledge/features/tool-call-row.md` § tests — lesson: a lookup inside the row's merged `clickable` should use `useUnmergedTree = true` for bounds/display assertions.

In-flight overlap: #883 and #884 edit other blocks of `ThreadScreen.kt` and `strings.xml`; no shared block, so edits here stay additive and local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The `Message area` column carries a 20dp gutter each side and a 16dp gap between rows; the `Tool use` instances (`I533:1956;134:4939`, `I533:1956;134:4941`) have no subagent grouping. Nesting reuses the frame's 16dp `Message area` gap as the per-level leading indent, added to the gutter, and leaves each row's own styling (border, fill, header) exactly as #895 shipped it.

## Context

`parentToolUseId` (#810) names the `Agent`/`Task` call whose subagent made a tool call. The thread renders every tool row flat, so a fanned-out turn reads as one interleaved list. Per `../pyrycode/docs/protocol-mobile.md` § `tool_use` the field is a grouping hint, not a capability: an unmatched parent, and a cache-restored row (which carries `""`), render at top level. Nesting has no depth limit. Layout only — no model, repository or wire change.

## Design

**Pure derivation, beside the fold** (`ThreadRow.kt`):

```kotlin
internal fun toolNestingDepths(items: List<ThreadItem>): Map<String, Int>
```

- Candidates are `ThreadItem.MessageItem`s with `role == Role.Tool`, a non-null `toolCall`, and a non-empty `id`. Only these can be a parent *or* a nested child.
- A row's depth is 0 when its `parentToolUseId` is empty or names no candidate; otherwise 1 + its parent's depth. The map holds only rows with depth > 0.
- Parent matching is by the AC's definition ("a tool row loaded in the thread"), independent of list order.
- Memoised walk up the parent chain, O(tool rows). **Cycle rule** (a hostile or corrupt chain such as A→B→A, or a self-parent): the walk stops at the first row it reaches twice, and that row counts as top level; rows before it on the path count up from there. Deterministic for a given list, never loops.

**Screen** (`ThreadScreen`): `val toolDepths = remember(state.items) { toolNestingDepths(state.items) }`, then the `MessageItem` arm passes `toolNestingDepth = toolDepths[item.message.id] ?: 0` to `MessageBubble`. Keys, the queued fold, the above-delimiter alpha and every other arm are unchanged.

**`MessageBubble(message, modifier, toolNestingDepth: Int = 0)`**: only the `Role.Tool` arm reads it — start padding becomes `MessageContentGutter + ToolNestingIndent * depth`, end stays `MessageContentGutter`; it forwards the depth to `ToolCallRow`. `ToolNestingIndent` is the frame's `MessageAreaRowSpacing` (16dp). User and assistant arms ignore the parameter.

**`ToolCallRow(toolCall, modifier, subagentDepth: Int = 0)`**: when `subagentDepth > 0`, the clickable `Column` gains a `contentDescription` of `cd_tool_subagent_step` ("Subagent step, level %1$d"). It sits on the same merge-descendants node as the click action, so TalkBack reads it with the row rather than as a separate stop, and the level states the nesting without relying on indentation or colour. Depth 0 adds nothing.

A `@Preview` in `MessageBubble.kt` shows a parent, child and grandchild tool row, light and dark.

## State + concurrency model

None added. The derivation is a pure function remembered on `state.items`, recomputed on the main thread during composition in O(tool rows), like `foldQueuedRows`.

## Error handling

No new failure modes surface to the user. Malformed hints degrade to top level: empty id, unmatched id, cache-restored row, cycle. `parentToolUseId` is daemon-authored and is only compared as a map key — never rendered, logged or used as a key in the list.

## Testing strategy

- **Unit** (`app/src/test/.../thread/ToolNestingDepthsTest.kt`): main-thread row absent from the map; matched child = 1; grandchild = 2; unmatched parent absent; empty-id row ignored; parent id matching a *user* message does not nest; parent listed after child still nests; two-row cycle and self-parent terminate with the stated rule.
- **Compose** (`app/src/sharedTest/.../thread/ToolRowNestingTest.kt`, Robolectric, mounts `ThreadScreen`): a thread with a top-level `Agent` row, a matched child, a grandchild under an inner agent call, an unmatched-parent row and an assistant message. Asserts tool-name left edges (unmerged tree) step right by level (top < child < grandchild), the unmatched row's edge equals the top-level row's, each nested row carries its "Subagent step, level N" description, and the top-level and unmatched rows carry none.
- No rung-3 scenario: this is a layout change of rows that already stream; the live data path is unchanged.

## Open questions

- Very deep nesting narrows the row; Compose clamps padding to the constraints, so it cannot crash. No visual cap is added, since the ticket says nesting has no depth limit and no deep chain has been observed.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: `docs/knowledge/features/tool-call-row.md` (the `MessageBubble` call site now takes a depth) and `thread-screen-how-it-works-list-and-status-row.md` (the list's new derivation).
