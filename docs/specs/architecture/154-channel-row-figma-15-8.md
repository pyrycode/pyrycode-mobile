# Spec — #154 Channel row matches Figma 15:8 (drop workspace label, sleep dot after name)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt` (whole file, 166 lines) — the only composable being edited. Read it end-to-end before touching anything; the change is small enough that the file is its own context.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationAvatar.kt:1-30` — confirms the avatar already matches Figma; no change here. Read just to confirm scope.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:140-148` — single production call site; the `lastMessage = null` line to drop.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreen.kt:195-205,310-326` — three call sites (1 production list rendering + 2 inside `@Preview` composables); same one-line drop at each.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt:92-100` — single call site; same one-line drop.

No test files reference `ConversationRow`, `condenseWorkspace`, `previewText`, or `previewMessage` (verified by grep across `app/src/test/` and `app/src/androidTest/`); no tests need updating.

## Context

`ConversationRow` is the shared row composable rendered for channels (`ChannelListScreen`), discussions (`DiscussionListScreen`), and archived discussions (`ArchivedDiscussionsScreen`). Three earlier specs (#17 introduced the composable; #19 added the inline workspace label; #20 added the leading sleep dot) accreted features that Figma 15:8 — the canonical channel-list spec — does not include. This ticket walks those back and reorders the sleep dot to match the design.

The change uniformly affects every screen that renders `ConversationRow`. That is the intent per the ticket body.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

A channel row is a single-line list item with three slots — circular monogram avatar (40dp, already correct), name (`titleMedium`, `onSurface`), and trailing timestamp (`bodySmall`, `onSurfaceVariant` at ~75% opacity). When the conversation is sleeping, an 8dp `outline`-colored dot sits between the name and the timestamp with an 8dp gap; the dot is absent for awake conversations. No workspace label, no message preview line.

## Design

### Single composable, three deletions and one reorder

All edits land in `ConversationRow.kt`. The function signature, the `ListItem` skeleton, the gesture wrapper, and the `displayName` resolution stay exactly as they are. Four targeted changes:

1. **Drop the `lastMessage: Message?` parameter.** It is consumed only by `supportingContent`, which is being removed. All five production call sites already pass `lastMessage = null` (verified by grep).
2. **Remove the `supportingContent = …` block** from the `ListItem` call.
3. **Inside `headlineContent`, reorder the children of the existing `Row`:**
   - First child: the name `Text` (unchanged).
   - Second child (conditional): the 8dp `Box` sleep dot, rendered only when `conversation.isSleeping` — moved from before the name to after it. The dot's modifier and background stay identical; only its position in the `Row` moves.
   - The workspace-label block (the `val workspaceLabel = condenseWorkspace(...)` lookup and the conditional `Text`) is deleted entirely.

   The `Row` keeps `verticalAlignment = Alignment.CenterVertically` and `horizontalArrangement = Arrangement.spacedBy(8.dp)`; the 8dp gap matches Figma node 15:68's inner-frame spacing.
4. **Delete the now-unused helpers and previews-only fixture:**
   - `private fun previewText(...)` (line 103) — was used only inside `supportingContent`.
   - `private fun condenseWorkspace(...)` (lines 105–110) — was used only inside the removed workspace-label block.
   - `private fun previewMessage(...)` (lines 157–165) — was used only by the with-message preview.
   - The `Message` and `Role` imports (data-model imports for `previewMessage`) and the `DEFAULT_SCRATCH_CWD` import (used only by `condenseWorkspace`) all become unused — remove them. `kotlin.time.Duration.Companion.hours` and `kotlinx.datetime.Clock` stay (still used by `previewConversation`).

### Preview composables

The two `@Preview` composables in the file (`ConversationRowWithMessagePreview`, `ConversationRowWithoutMessagePreview`) must continue to build. Since `lastMessage` is gone:

- **`ConversationRowWithMessagePreview`** loses its semantic purpose ("with message" no longer differs from "without message"). Rename it to **`ConversationRowPromotedPreview`** (or similar) and drop the `lastMessage = …` argument. Keep the `name = "pyrycode-mobile", isPromoted = true` configuration; it covers the "named channel, awake" state.
- **`ConversationRowWithoutMessagePreview`** keeps its existing config (`name = null, isPromoted = false, isSleeping = true`) which covers "untitled, sleeping" — same as AC bullet 5. Rename to **`ConversationRowUntitledSleepingPreview`** for consistency with the new naming. Drop the `lastMessage = null` argument.

The two preview states together cover the matrix the ticket's AC #5 asks for (with-name promoted, untitled-with-sleeping-dot).

### Call-site updates

Five production call sites lose their `lastMessage = null` line; nothing else changes at any call site.

| File | Line | Change |
|---|---|---|
| `ChannelListScreen.kt` | 144 | drop `lastMessage = null,` |
| `DiscussionListScreen.kt` | 199 | drop `lastMessage = null,` |
| `DiscussionListScreen.kt` | 316 | drop `lastMessage = null,` (inside a `@Preview`) |
| `DiscussionListScreen.kt` | 321 | drop `lastMessage = null,` (inside a `@Preview`) |
| `ArchivedDiscussionsScreen.kt` | 96 | drop `lastMessage = null,` |

No imports need removing at the call sites — none of them import `Message` solely for the `ConversationRow` call (`DiscussionListScreen.kt` and `ArchivedDiscussionsScreen.kt` should be checked; if `Message` becomes unused after the edit, drop the import too).

## State + concurrency model

N/A — purely a stateless composable contract change. No `viewModelScope`, no `StateFlow`, no dispatcher choices in scope.

## Error handling

N/A — no I/O, no parsing, no failure modes introduced or removed.

## Testing strategy

- **No new tests required.** No existing unit or instrumented test references the symbols being changed (verified via grep across `app/src/test/` and `app/src/androidTest/`).
- **Verification path:**
  - `./gradlew assembleDebug` must succeed.
  - `./gradlew lint` must show no new warnings related to the change (specifically: unused imports, unused parameters).
  - Visual check via Android Studio preview pane on the two `@Preview` composables in `ConversationRow.kt`. Both states must render: (a) named promoted channel, single-line, no workspace label, no preview text; (b) untitled sleeping discussion, single-line, sleep dot appearing AFTER the placeholder name and BEFORE the timestamp.

## Open questions

None. The ticket body fully specifies the desired end state; the Figma node confirms it; the call-site mechanics are deterministic.
