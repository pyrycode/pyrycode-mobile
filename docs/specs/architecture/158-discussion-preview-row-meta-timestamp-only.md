# 158 — discussion preview row meta: timestamp only

## Context

`DiscussionPreviewRow` (used in the "Recent discussions" section of `ChannelListScreen`) renders a 3-line stack: title, body placeholder, and a trailing meta line. The meta line currently composes `<workspace> · <time>` with the workspace span in a monospace font (`buildAnnotatedString` + `FontFamily.Monospace`). Figma 15:8 shows the relative timestamp alone on that line — no workspace prefix, no `·` separator, no monospace.

The body-slot placeholder (the second `Text`) is the other Figma 15:8 divergence; it requires a repository + ViewModel wiring change and is intentionally out of scope here (tracked separately). This spec covers only the meta line and the resources/helpers that lose their last consumer when it changes.

Split from #155 (itself split from #123).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Each discussion row is a vertical Column with three text rows and a 2 dp vertical gap. Top: title (`title-medium`, `on-surface`). Middle: body (`body-medium`, `on-surface-variant`). Bottom: meta — relative timestamp only (`label-small`, `on-surface-variant`, 0.70 alpha). No leading avatar. The existing typography + alpha + color slots on the meta `Text` already match Figma; the only delta is the text content (drop the workspace span + separator).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/DiscussionPreviewRow.kt:1-156` — current implementation; the single file modified by this ticket. Note the `buildAnnotatedString` meta and the private helper at line 83.
- `app/src/main/res/values/strings.xml:15` — `discussion_preview_workspace_scratch` resource. Loses its last consumer here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:195-215` — the only call site of `DiscussionPreviewRow`. Confirm the call site needs no change.
- `docs/specs/architecture/154-channel-row-figma-15-8.md` — sibling Figma 15:8 fidelity ticket (channel row, already merged); same pattern for "trim to match Figma exactly."

## Design

### Single file change

`app/src/main/java/de/pyryco/mobile/ui/conversations/components/DiscussionPreviewRow.kt`:

1. **Replace the meta `Text` content** — the third `Text` (currently `text = meta` from `buildAnnotatedString`) becomes `text = formatRelativeTime(conversation.lastUsedAt)`. Keep the existing `style = MaterialTheme.typography.labelSmall`, `color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)`, `maxLines = 1`, `overflow = TextOverflow.Ellipsis` exactly as-is — they already match Figma 15:8.

2. **Delete the now-unused locals and helper:**
   - The `scratchLabel`, `workspaceLabel`, `timeLabel`, and `meta` locals (lines 39–49) collapse to direct inlining of `formatRelativeTime(conversation.lastUsedAt)` in the third `Text`.
   - The private `discussionPreviewWorkspaceLabel` function (lines 83–90). Codegraph confirms zero external callers; the only reference is line 40 in the same file, removed by the inlining above.

3. **Remove imports that lose their last reference** in this file:
   - `androidx.compose.ui.text.SpanStyle`
   - `androidx.compose.ui.text.buildAnnotatedString`
   - `androidx.compose.ui.text.font.FontFamily`
   - `androidx.compose.ui.text.withStyle`
   - `androidx.compose.ui.res.stringResource` — **keep**, still used by the `displayName` fallback (line 38) and the body placeholder (line 67).
   - `de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD` — **keep**, still used by the three `@Preview` functions for `cwd = DEFAULT_SCRATCH_CWD`.

4. **Preview functions stay as-is.** The three `@Preview` composables don't change — they exercise the row at different timestamps and a named cwd, which now all render the same meta shape (just the timestamp). No new previews are needed; existing previews already cover the visible variants under the simplified meta.

### Resource removal

`app/src/main/res/values/strings.xml`:

- Delete line 15: `<string name="discussion_preview_workspace_scratch">scratch</string>`. Grep + codegraph confirm `R.string.discussion_preview_workspace_scratch` has no other consumers after step 1; leaving it in would be dead resource lint debt.

### What does NOT change

- `DEFAULT_SCRATCH_CWD` constant in `data/model/Conversation.kt` — still used by repository, screens, tests, previews; only the workspace-label *display* helper goes, not the constant.
- `Conversation.cwd` field — still part of the data model; just no longer surfaced in `DiscussionPreviewRow`.
- The body-slot placeholder `Text` (line 66–72) — intentionally out of scope per ticket body. The hardcoded `discussion_preview_placeholder_body` string stays until the repository + ViewModel wiring ticket lands.
- The lack of a leading avatar (AC #4) — current code is a `Column` with three `Text`s, no `Row`/`ConversationAvatar`. Already correct; ensure no avatar is introduced.

## State + concurrency model

N/A — pure presentation change inside a stateless `@Composable`. No coroutines, dispatchers, or flow plumbing touched.

## Error handling

N/A — no new failure modes. `formatRelativeTime` (existing helper) already handles the `Instant` it receives; no nullable inputs introduced.

## Testing strategy

- **No new tests required.** The ticket's AC are visual + dead-code-removal; existing `ChannelListScreenTest` covers the surrounding screen flow (no assertion exists on the workspace label string — verified by grep).
- **Manual verification:** run the `DiscussionPreviewRowScratchLightPreview`, `DiscussionPreviewRowScratchDarkPreview`, and `DiscussionPreviewRowNamedCwdPreview` previews in Android Studio's Compose preview and confirm:
  - Meta line shows only `12m ago` / `2h ago` / `5h ago` respectively — no `scratch · …`, no `some-repo · …`, no monospace span, no `·` separator.
  - Title and body styling unchanged.
  - No leading avatar.
- **Regression guard:** `./gradlew lint` should pass with one less resource. If lint configuration treats unused strings as errors, deleting the resource and its single Kotlin reference in the same commit closes the window where lint could complain.
- **Build:** `./gradlew assembleDebug test` — must remain green. No test changes needed.

## Open questions

None — all AC map to concrete edits in a single file plus one resource line, with grep- and codegraph-confirmed zero cascading impact.
