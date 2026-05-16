# 162 — wire lastMessage into DiscussionPreviewRow body slot

## Context

`DiscussionPreviewRow` currently renders `R.string.discussion_preview_placeholder_body` ("Tap to resume — message preview coming soon.") in its body slot. Figma 15:8 shows the row's body line as the first ~2 lines of the discussion's most-recent message. The data path landed in #161: `ChannelListUiState.{Empty, Loaded}` now expose `recentDiscussionLastMessages: Map<String, Message>`, keyed by conversation id and defaulting to `emptyMap()`. This ticket consumes that surface.

Channel rows are explicitly out of scope. `ConversationRow` does not render a body preview today; whether to give channels their own preview slot is deferred.

Split from #159.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Each recent-discussion row is a Column with three stacked Texts at 2 dp vertical gap: title (`title-medium`, `on-surface`), body — first ~2 lines of the most-recent message text, ellipsised (`body-medium`, `on-surface-variant`), meta — relative timestamp (`label-small`, `on-surface-variant` at 0.70 alpha). Rows without a most-recent message (e.g. a freshly-created discussion) collapse the body Text entirely; the title sits directly above the meta line — no blank line, no placeholder copy.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/DiscussionPreviewRow.kt:1-132` — the entire file is in scope. Note the three-Text Column and the hard-coded body `Text` at line 51–57.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:181-208` — the sole production call site of `DiscussionPreviewRow` lives inside `RecentDiscussionsSection`. The section's parameter list grows by one (`lastMessages: Map<String, Message>`) and the `forEach` body grows by one lookup.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:115-156` — the two `Empty` / `Loaded` branches that call `RecentDiscussionsSection` need to pass `state.recentDiscussionLastMessages` through.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:305-409` — the 6 screen-level `@Preview`s that construct `Empty(...)` / `Loaded(...)`. The new field has a default of `emptyMap()` (per #161) so previews need no changes unless you want to demo populated previews — see "What does NOT change" below.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:24-43` — `ChannelListUiState.{Empty, Loaded}.recentDiscussionLastMessages: Map<String, Message>` with default `emptyMap()`. The map only contains entries for ids in the current recent slice and never carries null values (#161 filters them out).
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` — `data class Message(id, sessionId, role, content, timestamp, isStreaming)`. The body slot consumes `message.content` only.
- `app/src/main/res/values/strings.xml:14` — the `discussion_preview_placeholder_body` resource to delete.
- `docs/specs/architecture/161-recent-discussion-last-message-uistate.md` — predecessor spec; explains the map-with-default shape choice and why entries are non-null. Useful background.
- `docs/specs/architecture/158-discussion-preview-row-meta-timestamp-only.md` — the prior `DiscussionPreviewRow` Figma 15:8 trim. Same patterns apply (Compose styling, no leading avatar, three-Text Column).

## Design

### `DiscussionPreviewRow` signature change

Add one parameter:

```kotlin
fun DiscussionPreviewRow(
    conversation: Conversation,
    lastMessage: Message?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Place `lastMessage` immediately after `conversation` — they form a logical pair (the conversation and its last message), and the existing `onClick` + `modifier` tail-arg convention stays consistent with the rest of the components package. **No default value.** This is a private-package composable with exactly one production caller plus its own previews; a default would let future callers accidentally render an empty body, and named-argument call sites are clearer without one. The previews update explicitly (see below).

### Body-slot rendering

Replace the unconditional middle `Text` with a conditional emission. Contract:

- When `lastMessage == null`: emit no body `Text` at all. The outer Column's `verticalArrangement = Arrangement.spacedBy(2.dp)` collapses the gap automatically — Compose only inserts the 2 dp spacer between sibling slots that are emitted, so removing the middle slot leaves a single 2 dp gap between title and meta, not a doubled gap.
- When `lastMessage != null`: emit one `Text` with:
  - `text = lastMessage.content`
  - `style = MaterialTheme.typography.bodyMedium`
  - `color = MaterialTheme.colorScheme.onSurfaceVariant`
  - `maxLines = 2`
  - `overflow = TextOverflow.Ellipsis`

These are the same style slots as today's placeholder `Text` — only the source of `text` changes (from `stringResource(...)` to `lastMessage.content`) and the wrapping `if (lastMessage != null) { ... }` guard is new.

The `Text(text = formatRelativeTime(conversation.lastUsedAt), ...)` meta line at the bottom of the Column is unchanged. Title `Text` is unchanged.

### `RecentDiscussionsSection` signature change

`ChannelListScreen.kt`'s private `RecentDiscussionsSection` grows one parameter so it can forward the map down to each row:

```kotlin
@Composable
private fun RecentDiscussionsSection(
    discussions: List<Conversation>,
    lastMessages: Map<String, Message>,
    totalCount: Int,
    onSeeAllClick: () -> Unit,
    onRowClick: (String) -> Unit,
)
```

Inside the `forEach`, pass `lastMessages[conversation.id]` (returns `Message?` from `Map<String, Message>.get`) into the new `DiscussionPreviewRow` parameter.

Both `Empty` and `Loaded` branches pass `lastMessages = state.recentDiscussionLastMessages`. The branches already destructure `state.recentDiscussions` and `state.recentDiscussionsCount`; this is a one-line addition per call site.

### Deletion: `discussion_preview_placeholder_body`

Delete line 14 of `app/src/main/res/values/strings.xml`:

```xml
<string name="discussion_preview_placeholder_body">Tap to resume — message preview coming soon.</string>
```

After step 1 there are zero Kotlin references to `R.string.discussion_preview_placeholder_body` (verified with `grep` — the only consumer is line 52 of `DiscussionPreviewRow.kt`, replaced by the `lastMessage.content` path). Leaving the resource would be dead-string lint debt. The `stringResource(...)` import in `DiscussionPreviewRow.kt` is still used by the `displayName` fallback at line 34 (`R.string.untitled_discussion`) — keep that import.

### Preview updates

`DiscussionPreviewRow.kt` currently has three previews (lines 68–131). The AC require previews exercising both the non-null and null `lastMessage` cases. Concrete plan:

- Keep `DiscussionPreviewRowScratchLightPreview` (line 68) and `DiscussionPreviewRowScratchDarkPreview` (line 88). Update each to pass a non-null `lastMessage` — construct an inline `Message(...)` with a multi-sentence `content` long enough to exercise the 2-line ellipsis. Use the `Message` model fields verbatim (`id`, `sessionId`, `role = Role.Assistant`, `content`, `timestamp = conversation.lastUsedAt`, `isStreaming = false`); pick any stable string ids. Suggested content for the light preview matches Figma 15:8 verbatim ("I'd start by checking if your nullable timestamp comparison is using the right operator. In TypeScript…").
- Rename `DiscussionPreviewRowNamedCwdPreview` (line 113) to `DiscussionPreviewRowNoMessageLightPreview` (the "named cwd" framing is obsolete after #158 — cwd is no longer surfaced in this row, see the #158 spec). Pass `lastMessage = null`. This is the AC-required null case.
- The dark preview's `lastMessage` argument exists to verify the dark theme still applies correct colors — keep `lastMessage` non-null there.

This gives three previews covering: light + populated body, dark + populated body, light + collapsed body. No need for a dark + null preview — color slots don't change between null and non-null states, so the dark/null permutation adds nothing the light/null preview doesn't already verify.

### What does NOT change

- **`ChannelListScreen.kt` screen-level previews (6 of them, lines 305–409).** They construct `Empty(...)` and `Loaded(...)` and rely on the new field's default of `emptyMap()` (added in #161 — see that spec's "Why default value `emptyMap()`"). Leaving the defaults means the previews render with collapsed body slots (no last message). Don't add inline maps here — preview noise without preview value, and the `DiscussionPreviewRow` previews already exercise the populated case directly. If a developer wants a richer screen-level visual, that's an aesthetic call but not an AC.
- **`ChannelListViewModel.kt`** — no changes. The map is already plumbed in #161 with the correct semantics (non-null entries only, bounded by the recent slice).
- **`ChannelListScreenTest.kt`** (instrumented) — no changes. `grep` confirms it has zero references to `DiscussionPreviewRow`, the placeholder string, or `lastMessage`; it asserts on surrounding screen behavior, not row body text.
- **`ConversationRow`** — out of scope per ticket body.
- **`Message` model, `ConversationRepository` interface, fakes** — already complete from #161.
- **The order of the three Texts inside `DiscussionPreviewRow`** — title, body, meta is preserved. Only the body slot becomes conditional.

## State + concurrency model

N/A — pure presentation change inside a stateless `@Composable`. No coroutines, dispatchers, or flow plumbing touched. The map flows down through composables as a plain `Map<String, Message>` and is read via the standard `Map.get` operator — no `remember`, no `derivedStateOf`, no `key()` needed: the map is recomposition-stable as long as the `UiState` it lives in is stable (the `data class` `Loaded(...)` is, by virtue of all its fields being stable Kotlin types).

## Error handling

N/A — no new failure modes. `lastMessage.content` is a non-nullable `String` on a non-null `Message`; the null check happens once at the row level. The repository / VM error pathway from #161 is unchanged.

## Testing strategy

Run `./gradlew test` (covers Compose previews compiling) and `./gradlew lint` (catches any orphaned string resource).

**No new unit/instrumented tests required.** Rationale:
- The AC's preview requirements are satisfied by the `@Preview` updates above — those are visual-fidelity proofs run in Android Studio's Compose preview pane, not test functions.
- VM-layer correctness (the map carrying the right entries) is owned by #161's tests.
- Screen-flow correctness is covered by the existing `ChannelListScreenTest`, which passes through unchanged.
- Adding a Compose UI test that asserts "body Text is absent when `lastMessage` is null" would test the framework's `verticalArrangement.spacedBy` behavior and Compose's conditional emission rather than this ticket's contract; not worth the maintenance cost for a 2-file UI tweak.

**Manual verification (developer):**

- Run all three `DiscussionPreviewRow` previews and confirm:
  - Populated previews render two lines of message content ellipsised at the end of the second line; title above, timestamp below.
  - The null-lastMessage preview shows title immediately followed by timestamp, with a single ~2 dp gap between them (not double-gapped).
- Run the screen-level `ChannelListScreenLoadedWithDiscussionsPreview` and confirm that with default `emptyMap()`, the three recent-discussion rows render in the collapsed (no body) shape — visual confirmation that the section degrades gracefully when the VM hasn't yet emitted last messages.
- Run `./gradlew assembleDebug installDebug` on a connected emulator and verify the seeded `seed-discussion-a` (from #161) shows its last message text in the row's body slot, while the empty-message discussions (`seed-discussion-b`, `seed-discussion-archived`) render collapsed.

## Open questions

None. AC map to:
1. `DiscussionPreviewRow` signature gains `lastMessage: Message?`; body slot becomes conditional → AC #1, #2.
2. `RecentDiscussionsSection` forwards `state.recentDiscussionLastMessages[conversation.id]` → AC #3.
3. Delete `discussion_preview_placeholder_body` from `strings.xml` → AC #4.
4. Update + rename previews to cover both cases → AC #5.
