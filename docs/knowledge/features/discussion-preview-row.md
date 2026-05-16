# DiscussionPreviewRow

Stateless row primitive (#69) rendering a single recent discussion inside the inline "Recent discussions" section of [`ChannelListScreen`](./channel-list-screen.md). Title (auto-fallback to `R.string.untitled_discussion`), 2-line body preview of the discussion's most-recent `Message` (since #162 — conditionally emitted, omitted entirely when no message exists), and a single-line relative-time meta. Locked to Figma node `15:8` since #158 — the meta line is the bare relative timestamp; the pre-#158 `<workspace> · <relative-time>` shape (monospace span on the workspace fragment, "scratch" fallback for `DEFAULT_SCRATCH_CWD`) was walked back against the locked spec along with the `discussionPreviewWorkspaceLabel` helper and the `R.string.discussion_preview_workspace_scratch` resource. Sole call site is the section composable inside `ChannelListScreen.kt`; sibling of [`ConversationRow`](./conversation-row.md), which lost its workspace label in #154.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `DiscussionPreviewRow.kt`.

## What it does

Renders a single `Conversation` as a three-line `Column` with a top-level click target. Top to bottom:

- **Title.** `Text(displayName, style = titleMedium, color = onSurface, maxLines = 1, overflow = Ellipsis)`. `displayName` is `conversation.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.untitled_discussion)`.
- **Body (conditional, since #162).** When `lastMessage != null`: `Text(lastMessage.content, style = bodyMedium, color = onSurfaceVariant, maxLines = 2, overflow = Ellipsis)`. When `lastMessage == null`: the slot is omitted entirely — no `Text`, no `Spacer`, no placeholder copy. The `Arrangement.spacedBy(2.dp)` collapses naturally because Compose only inserts the 2 dp spacer between sibling slots that are actually emitted, so an omitted body leaves a single 2 dp gap between title and meta (not a doubled gap, not a preserved blank line). The pre-#162 unconditional `R.string.discussion_preview_placeholder_body` ("Tap to resume — message preview coming soon.") was retired in #162 along with the resource itself; the data path landed in #161 as `ChannelListUiState.recentDiscussionLastMessages: Map<String, Message>`, and the call site (`RecentDiscussionsSection`) looks up `lastMessages[conversation.id]` to feed this parameter.
- **Meta.** Single `Text(formatRelativeTime(conversation.lastUsedAt), style = labelSmall, color = onSurfaceVariant.copy(alpha = 0.70f), maxLines = 1, overflow = Ellipsis)` since #158 — bare relative timestamp, no workspace prefix, no separator, no monospace span. Time label comes from the shared `formatRelativeTime(...)` helper in `RelativeTime.kt` (same package).

The whole `Column` is `.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)`, with `verticalArrangement = Arrangement.spacedBy(2.dp)`. No leading avatar, no trailing affordance, no `ListItem` chrome — this is a tighter, content-only row.

## Shape

```kotlin
@Composable
fun DiscussionPreviewRow(
    conversation: Conversation,
    lastMessage: Message?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`lastMessage` sits immediately after `conversation` — they form a logical pair (the conversation and its most-recent message). **No default value** on `lastMessage`: this is a private-package composable with exactly one production caller plus its own previews; a default would let future callers accidentally render an empty body, and named-argument call sites are clearer without one. The component stays `Conversation`-shaped (not `String title + String body`) so the title fallback and the relative-time meta logic stay inside the row; the body's source-of-truth is the data-layer `Message`, supplied per-row by the call site.

## How it works

### Meta line is the bare timestamp (since #158)

The third `Text`'s argument is a direct `formatRelativeTime(conversation.lastUsedAt)` call — no `AnnotatedString`, no `SpanStyle`, no `Row` of sibling `Text`s. Both walked-back precedents — the workspace prefix from #69 and the helper that produced it — were removed in #158 to match Figma node `15:8` exactly. If a future ticket reintroduces a workspace label (or any mixed-font fragment) on this row against an updated Figma node, the `SpanStyle`-inside-one-`Text` pattern is the right shape (it keeps the `labelSmall` baseline coherent in a way a `Row(Arrangement.spacedBy(...))` of sibling `Text`s would not); don't reach for a sibling-`Row` rewrite. The `discussionPreviewWorkspaceLabel` helper is gone — if a label rule comes back, write it inline at the call site first; only extract a helper if a second caller materialises.

### Time bucketing — shared helper

`formatRelativeTime(conversation.lastUsedAt)` resolves to the internal top-level function in [`RelativeTime.kt`](#shared-relativetime-helper) (same package). Output strings (`"just now"`, `"3m ago"`, `"4h ago"`, `"Yesterday"`, `"3d ago"`, abbreviated dates) are Kotlin literals inside the helper; localisation of those strings is a deliberate future-ticket scope per #69's open-questions.

### A11y semantics

`clickable(role = Role.Button, ...)` is the only semantics override. TalkBack reads the merged subtree of title + body + meta plus the role announcement — the natural reading order for the three lines reads cleanly without an explicit `contentDescription`. No `Modifier.semantics(mergeDescendants = true)` because there's only one click target on the `Column` and the children are pure-text leaves.

## Shared `RelativeTime` helper

Lives at `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RelativeTime.kt`. Internal, top-level:

```kotlin
internal fun formatRelativeTime(
    instant: Instant,
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String
```

Buckets:

- `< 0` or `< 1.minutes` → `"just now"`
- `< 1.hours` → `"${m}m ago"`
- `< 24.hours` → `"${h}h ago"`
- `< 48.hours` → `"Yesterday"`
- `< 7.days` → `"${d}d ago"`
- same year → abbreviated `MMM dd` via `kotlinx.datetime.LocalDate.Format { monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); dayOfMonth() }`
- cross year → `MMM dd, yyyy`

The two `LocalDate.Format` builders are file-`private` vals at the top of `RelativeTime.kt`. Extraction history: the function originated as `private` inside `ConversationRow.kt` (since #17); #69 moved it out to a peer file at `internal` visibility because `DiscussionPreviewRow` needs the same buckets. Don't duplicate, don't re-`private` it back — both call sites are in the same `ui/conversations/components/` package and the project rule is: when a second sibling needs a `private` helper, move it to an `internal` peer file rather than copying or up-leveling visibility on the original definition.

## Configuration

- **No new dependencies.** `kotlinx.datetime` is already on the classpath since the data layer landed.
- **Strings:** `R.string.untitled_discussion` ("Untitled discussion"). The `R.string.discussion_preview_placeholder_body` ("Tap to resume — message preview coming soon.") resource from #69 was deleted in #162 once the body slot started reading `lastMessage.content` directly. `R.string.discussion_preview_workspace_scratch` ("scratch") also landed in #69 but was deleted in #158 along with the workspace prefix that consumed it.

## Preview

Three `@Preview`s in `DiscussionPreviewRow.kt`, all `widthDp = 412`. Reshaped in #162 to exercise both the non-null and null `lastMessage` branches:

- `DiscussionPreviewRowScratchLightPreview` — `cwd = DEFAULT_SCRATCH_CWD`, `name = "What's the safest way…"`, `lastUsedAt = now - 12.minutes`. Light theme. `lastMessage` is a non-null `Message(role = Assistant, content = "I'd start by checking if your nullable timestamp comparison is using the right operator. In TypeScript…", timestamp = lastUsedAt)` — the Figma 15:8 verbatim copy. Meta reads `12m ago`; body wraps to 2 lines and ellipsises — exercises both the minute-bucket time format and the populated-body case.
- `DiscussionPreviewRowScratchDarkPreview` — same shape, `name = "Help me debug auth flow"`, `lastUsedAt = now - 2.hours`. Dark theme (`Configuration.UI_MODE_NIGHT_YES`). Non-null `lastMessage` with a second multi-sentence string ("The token refresh is happening but the new token isn't being stored. Let me trace through the request flow…"). Meta reads `2h ago` — exercises the hour-bucket plus dark-scheme `onSurface` / `onSurfaceVariant` contrast on the body and meta lines.
- `DiscussionPreviewRowNoMessageLightPreview` (#162 — renamed from the pre-#162 `DiscussionPreviewRowNamedCwdPreview` since the "named cwd" framing is obsolete after #158) — `cwd = DEFAULT_SCRATCH_CWD`, `name = "Quick regex for log parsing"`, `lastUsedAt = now - 5.hours`, `lastMessage = null`. Light theme. The row collapses to title above meta with a single 2 dp gap — visually verifies the conditional-emission contract.

No dark + null preview — color slots don't change between null and non-null states, so the dark/null permutation adds nothing the light/null preview doesn't already verify.

## Edge cases / limitations

- **Body collapses entirely when `lastMessage == null`** (since #162). No placeholder text, no `Spacer`, no preserved blank line — title sits directly above the meta line with the single 2 dp gap from the outer `Column`'s `Arrangement.spacedBy(2.dp)`. This is the contract the data layer relies on: `ChannelListUiState.recentDiscussionLastMessages` from #161 keeps absent-key = "no last message" semantics (the map only holds non-null entries), and the call site's `lastMessages[conversation.id]` lookup feeds that `null` straight through.
- **Body source is the raw `Message.content` string.** No truncation, no markdown stripping, no role prefix — the row trusts `maxLines = 2` + `TextOverflow.Ellipsis` to handle long content. Phase 4's real wire data may surface code-fence or attachment-bearing messages where 2 lines of raw markdown looks awful; revisit when that's observed.
- **Meta is timestamp-only.** No workspace label, no separator, no monospace span (since #158). `Conversation.cwd` is still in the data model but is no longer surfaced anywhere in this row. The pre-#158 "always-render-a-label, fall back to scratch" rule is gone with the helper.
- **No avatar, no leading slot, no trailing slot.** The Figma `15:8` treatment for discussions is content-only — title / body / meta on a tighter vertical rhythm than `ConversationRow`'s `ListItem`. Don't reach for `ListItem` here even if a future affordance lands (long-press → "Save as channel…", swipe action); add the gesture decorations to this `Column` directly, the way [`DiscussionListScreen`](./discussion-list-screen.md)'s row does.
- **Relative-time strings are not localised.** `just now` / `Yesterday` / `Nm ago` etc. are Kotlin literals inside `formatRelativeTime`. Out of scope for #69; a localisation pass becomes ~6 `<plurals>` entries and a `Resources`-typed parameter.

## Related

- Ticket notes: [`../codebase/69.md`](../codebase/69.md) (introduction), [`../codebase/158.md`](../codebase/158.md) (meta-line walked back to timestamp-only against Figma `15:8`), [`../codebase/161.md`](../codebase/161.md) (data path — `ChannelListUiState.recentDiscussionLastMessages` + `ConversationRepository.observeLastMessage` + seed-discussion-a history), [`../codebase/162.md`](../codebase/162.md) (this consumer slice — signature gains `lastMessage`, body is conditional, placeholder string retired)
- Specs: `docs/specs/architecture/69-channel-list-recent-discussions-section.md`, `docs/specs/architecture/158-discussion-preview-row-meta-timestamp-only.md`, `docs/specs/architecture/161-recent-discussion-last-message-uistate.md`, `docs/specs/architecture/162-channel-list-discussion-preview-row-last-message.md`
- Upstream: [data model](./data-model.md) (`Conversation`, `Message`, `DEFAULT_SCRATCH_CWD` — still seeds previews here), [ConversationRow](./conversation-row.md) (`formatRelativeTime` originated here; lost its own workspace label in #154 — the close sibling-walked-back precedent for #158), [Conversation repository](./conversation-repository.md) (`observeLastMessage` projection feeds the VM's per-row last-message map)
- Consumer: [ChannelListScreen](./channel-list-screen.md) — `RecentDiscussionsSection` private composable iterates the VM's top-3 `recentDiscussions` and calls `DiscussionPreviewRow` for each, passing `lastMessages[conversation.id]` per row
- Downstream / follow-ups: relative-time localisation, accessibility audit on the row hit area, Phase 4 wire-data content shapes (long/code-fence/attachment messages — may need richer body handling once observed)
