# #270 — Channel List row accessibility: merged TalkBack semantics + idle-state announcement

Phase 5 accessibility. Closes the one code-observable a11y gap on the Channel List: channel/discussion rows are not exposed to TalkBack as single, meaningful, state-bearing labels. Additive, no public API changes, no visual change.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:28-78` — the channel row. `ListItem` with `headlineContent` (name + sleeping `Box` dot) and `trailingContent` (relative time). The dot at lines 61-71 has **no semantic equivalent** — this is the AC #2 gap. The `gestureModifier` (lines 40-45) is where the merged-semantics modifier attaches.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/DiscussionPreviewRow.kt:28-71` — the recent-discussion row. `Column` already `.clickable(role = Role.Button, …)` at line 43; three child `Text`s (name / optional message / time). Attach merged semantics to this `Column`'s modifier.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:269-296` — `SeeAllDiscussionsRow`, **the in-repo pattern to mirror exactly**: `.clickable(…).semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button }` with child `Text` underneath. Note `contentDescription` is computed from a `stringResource(...)` above the `Row`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RelativeTime.kt:30` — `internal fun formatRelativeTime(...)`. Returns "2m ago" / "Yesterday" / "2 days ago". Already called in both rows; the spec reuses the same call for the label. `internal` + same module ⇒ the `androidTest` can call it directly to build expected strings.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:13` — `val isSleeping: Boolean = false`. This flag drives both the visible dot and the idle announcement.
- `app/src/main/res/values/strings.xml:3-50` — existing `cd_*` convention (e.g. `cd_see_all_discussions` = `"See all %d recent discussions"`). Add the new strings here, alphabetically near the other `cd_*` entries.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt:24-94` — string-fetch helper (`InstrumentationRegistry...targetContext.getString(...)`), the `channel(...)` fixture, and the existing `channelList_rendersEachChannelName` / `channelRow_emitsRowTappedWithId` tests that **must stay green** after the merge (see Testing strategy).
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiterTest.kt:21-86` — the component-test idiom for this package (`createComposeRule`, `setContent { PyrycodeMobileTheme { Component(...) } }`, `onNode(...)` assertions). New row tests follow this shape.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Channel List frame — **content reference only; this ticket makes no visual change.** The frame confirms what each row renders and therefore what the spoken label must mirror: a channel row is `[avatar] name [idle dot when sleeping] · relative-time` (e.g. "leaky-faucet ● 3h ago"); a recent-discussion row is a stacked `name / message-preview / relative-time`. The idle dot (node `15:70`, an 8dp `Ellipse`) is purely visual today and carries no text — that silence is the gap this ticket closes.

## Context

These screens were built accessibility-aware (icon-only controls already carry `cd_*` descriptions). The residual gap, confirmed by codebase audit (2026-05-30), is on the **list rows**:

1. A channel/discussion row is a composite of multiple text nodes. TalkBack should announce each row as **one** focusable, merged label rather than disconnected fragments. The repo convention for this (`SeeAllDiscussionsRow`) is an explicit `.semantics(mergeDescendants = true) { contentDescription = … }` — the rows do not yet apply it.
2. The channel idle ("sleeping") state is conveyed **only** by a dim `Box` dot with no semantics, so a screen-reader user cannot distinguish an idle channel from an active one (AC #2).

Both rows are leaf composables consumed only by `ChannelListScreen`; the change is internal and additive (no signature change, no consumer cascade).

## Design

Two component edits + three string resources. No new types, no new composables, no ViewModel/state changes.

### New string resources (`res/values/strings.xml`)

Positional-arg format strings (locale-safe; no runtime concatenation):

| name | value | renders as |
|------|-------|-----------|
| `cd_conversation_row` | `"%1$s, %2$s"` | "leaky-faucet, 3h ago" |
| `cd_conversation_row_idle` | `"%1$s, idle, %2$s"` | "leaky-faucet, idle, 3h ago" |
| `cd_discussion_preview_row` | `"%1$s, %2$s"` | "Help me debug auth flow, 2h ago" |

Spoken idle term is **"idle"** (clearer than "sleeping" for audio; matches the user-story's primary word). Args are `(displayName, relativeTime)`.

### `ConversationRow.kt`

1. Hoist the relative-time string to a single `val` at the top so the label and the visible `trailingContent` share one value:
   - `val relativeTime = formatRelativeTime(conversation.lastUsedAt)` — reuse in `trailingContent` (replace the inline call at line 75).
2. Compute the merged content description from `displayName` + `relativeTime`, branching on `conversation.isSleeping`:
   - sleeping → `stringResource(R.string.cd_conversation_row_idle, displayName, relativeTime)`
   - otherwise → `stringResource(R.string.cd_conversation_row, displayName, relativeTime)`
3. Append the merged-semantics modifier to `gestureModifier` (the `Modifier` passed to `ListItem`), mirroring `SeeAllDiscussionsRow`:

```kotlin
// contract sketch — attaches to the existing gestureModifier
.semantics(mergeDescendants = true) {
    contentDescription = rowDescription
    role = Role.Button
}
```

Leave `headlineContent` / `trailingContent` / the dot untouched — `mergeDescendants` collapses the children into one node and `contentDescription` becomes the authoritative announcement, while the child `Text`s still contribute their `text` property (so existing `hasText`-based finders keep matching — see Testing strategy).

### `DiscussionPreviewRow.kt`

1. `val relativeTime = formatRelativeTime(conversation.lastUsedAt)` at the top; reuse in the existing time `Text` (line 64).
2. `val rowDescription = stringResource(R.string.cd_discussion_preview_row, displayName, relativeTime)`.
3. Append `.semantics(mergeDescendants = true) { contentDescription = rowDescription }` to the `Column`'s modifier chain (after the existing `.clickable(role = Role.Button, …)`, before/after `.padding` is fine — match `SeeAllDiscussionsRow` ordering: semantics before padding). `role` already comes from `clickable`, so it need not be repeated in the semantics block.

**Message preview is intentionally excluded from the spoken label** (AC #3 makes it optional: "may be included"). Rationale: the preview is long, variable, and frequently truncated/markdown-y — name + time is the deterministic, testable signal that lets a user tell rows apart. The preview `Text` remains visible and still merges into the node's `text`; only the *announced* `contentDescription` is name + time.

**Idle state is not added to discussion rows** — `DiscussionPreviewRow` renders no sleeping dot, so there is no visible state to mirror (parity with the screen). Scope stays on channel rows per AC #2.

## State + concurrency model

N/A. Both are stateless composables receiving `(conversation, …, onClick)`; no `ViewModel`, `StateFlow`, coroutine, or lifecycle surface is touched. The semantics value is derived purely from the passed `Conversation`.

## Error handling

N/A. No I/O, network, parse, or permission paths. `displayName` already null-safes the name; `relativeTime` is total over any `Instant`.

## Testing strategy

Instrumented (`androidTest`, `./gradlew connectedAndroidTest`) only — TalkBack semantics and measured touch-target height are device/compose-runtime concerns, not unit-testable. Two new component test files following `SessionBoundaryDelimiterTest`'s idiom; expected labels built from the same `stringResource` + `formatRelativeTime` the production code uses (no hardcoded time strings).

**`app/src/androidTest/.../components/ConversationRowTest.kt`** (new):
- *Active channel exposes one merged label of name + time* — render `ConversationRow` with a known name and fixed `lastUsedAt`; assert a node exists with `contentDescription == getString(cd_conversation_row, name, formatRelativeTime(lastUsedAt))`; assert exactly one such node (`onAllNodes(hasContentDescription(expected)).assertCountEquals(1)`) to prove the row is a single focusable node, not fragments.
- *Idle channel announces the idle state* — same fixture with `isSleeping = true`; assert `contentDescription == getString(cd_conversation_row_idle, name, time)` exists, and the non-idle label does **not**.
- *Channel row meets the 48dp touch target* — select the merged node by content description; `assertHeightIsAtLeast(48.dp)`.

**`app/src/androidTest/.../components/DiscussionPreviewRowTest.kt`** (new):
- *Discussion row exposes one merged label of name + time* — render with a name + a `lastMessage`; assert single node with `contentDescription == getString(cd_discussion_preview_row, name, time)`.
- *No-message discussion still announces name + time* — `lastMessage = null`; same label present.
- *Discussion row meets the 48dp touch target* — `assertHeightIsAtLeast(48.dp)`.

**Regression guard (do not modify, just re-run):** `ChannelListScreenTest.channelList_rendersEachChannelName` and `channelRow_emitsRowTappedWithId` use `onNode(hasText("alpha"))` / `.performClick()`. After the merge, the child name `Text` still contributes `text` to the merged node and the node remains clickable, so both finders resolve to the single merged row node and these tests stay green. If either breaks, the merge was applied wrong (e.g. `clearAndSetSemantics` instead of `semantics` — do **not** use `clearAndSetSemantics`, it would drop the child `text` and break `hasText`).

## Open questions

- None blocking. (Spoken idle term "idle" vs "sleeping" resolved in favour of "idle"; preview-in-label resolved to excluded. Both are reversible string-only tweaks if a later on-device TalkBack pass disagrees.)

## Acceptance criteria mapping

- AC #1 (channel row = single merged name+time label) → `ConversationRow` merge + `cd_conversation_row`; first `ConversationRowTest`.
- AC #2 (idle announced) → `isSleeping` branch + `cd_conversation_row_idle`; idle `ConversationRowTest`.
- AC #3 (discussion row = single merged label) → `DiscussionPreviewRow` merge + `cd_discussion_preview_row`; first two `DiscussionPreviewRowTest`s.
- AC #4 (instrumented merged-label + 48dp tests) → both new test files; touch-target assertions in each.

## Size

**S** — 2 production files modified (`ConversationRow.kt`, `DiscussionPreviewRow.kt`), 3 string additions, 2 new `androidTest` files; ~300 LOC total. No new exported types/composables, no reject branches, 0 consumer call sites (additive, no signature change), 4 AC. No red line tripped.
