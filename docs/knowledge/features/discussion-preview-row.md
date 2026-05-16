# DiscussionPreviewRow

Stateless row primitive (#69) rendering a single recent discussion inside the inline "Recent discussions" section of [`ChannelListScreen`](./channel-list-screen.md). Title (auto-fallback to `R.string.untitled_discussion`), 2-line body placeholder, and a single-line relative-time meta. Locked to Figma node `15:8` since #158 — the meta line is the bare relative timestamp; the pre-#158 `<workspace> · <relative-time>` shape (monospace span on the workspace fragment, "scratch" fallback for `DEFAULT_SCRATCH_CWD`) was walked back against the locked spec along with the `discussionPreviewWorkspaceLabel` helper and the `R.string.discussion_preview_workspace_scratch` resource. Sole call site is the section composable inside `ChannelListScreen.kt`; sibling of [`ConversationRow`](./conversation-row.md), which lost its workspace label in #154.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `DiscussionPreviewRow.kt`.

## What it does

Renders a single `Conversation` as a three-line `Column` with a top-level click target. Top to bottom:

- **Title.** `Text(displayName, style = titleMedium, color = onSurface, maxLines = 1, overflow = Ellipsis)`. `displayName` is `conversation.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.untitled_discussion)`.
- **Body.** `Text(stringResource(R.string.discussion_preview_placeholder_body), style = bodyMedium, color = onSurfaceVariant, maxLines = 2, overflow = Ellipsis)`. The placeholder body is intentional: the `Conversation` model carries no last-message field, and subscribing to `ConversationRepository.observeMessages(id)` per row would be prohibitive; #69 explicitly defers real previews to a follow-up.
- **Meta.** Single `Text(formatRelativeTime(conversation.lastUsedAt), style = labelSmall, color = onSurfaceVariant.copy(alpha = 0.70f), maxLines = 1, overflow = Ellipsis)` since #158 — bare relative timestamp, no workspace prefix, no separator, no monospace span. Time label comes from the shared `formatRelativeTime(...)` helper in `RelativeTime.kt` (same package).

The whole `Column` is `.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)`, with `verticalArrangement = Arrangement.spacedBy(2.dp)`. No leading avatar, no trailing affordance, no `ListItem` chrome — this is a tighter, content-only row.

## Shape

```kotlin
@Composable
fun DiscussionPreviewRow(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

The component is intentionally `Conversation`-shaped (not `String title + String body`) so the placeholder-body logic stays inside the row. When a follow-up wires real message previews, only the body `Text`'s source string changes; the call sites (currently a `forEach` inside `RecentDiscussionsSection`) are untouched.

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
- **Strings:** `R.string.untitled_discussion` ("Untitled discussion") and `R.string.discussion_preview_placeholder_body` ("Tap to resume — message preview coming soon."). Both added in #69. `R.string.discussion_preview_workspace_scratch` ("scratch") also landed in #69 but was deleted in #158 along with the workspace prefix that consumed it.

## Preview

Three `@Preview`s in `DiscussionPreviewRow.kt`, all `widthDp = 412`:

- `DiscussionPreviewRowScratchLightPreview` — `cwd = DEFAULT_SCRATCH_CWD`, `name = "What's the safest way…"`, `lastUsedAt = now - 12.minutes`. Light theme. Meta reads `12m ago` — exercises the minute-bucket time format.
- `DiscussionPreviewRowScratchDarkPreview` — same shape, `name = "Help me debug auth flow"`, `lastUsedAt = now - 2.hours`. Dark theme (`Configuration.UI_MODE_NIGHT_YES`). Meta reads `2h ago` — exercises the hour-bucket and dark-scheme `onSurface` / `onSurfaceVariant` contrast on the body and meta lines.
- `DiscussionPreviewRowNamedCwdPreview` — `cwd = "~/Workspace/Projects/some-repo"`, `name = "Quick regex for log parsing"`, `lastUsedAt = now - 5.hours`. Light theme. Meta reads `5h ago` — kept post-#158 as a free named-cwd seed for the row's surviving lines even though the cwd is no longer surfaced in the meta.

## Edge cases / limitations

- **Body is a single non-parameterised placeholder.** `R.string.discussion_preview_placeholder_body` ships with the literal "Tap to resume — message preview coming soon." for every row, regardless of conversation content. The follow-up that wires real previews replaces the `stringResource(...)` body source with whatever the data layer surfaces (likely a per-`Conversation` last-message projection on the VM emission); the row's other lines are untouched. This is the other Figma `15:8` divergence noted on #158 and tracked separately.
- **Meta is timestamp-only.** No workspace label, no separator, no monospace span (since #158). `Conversation.cwd` is still in the data model and is still used to seed two previews, but it is no longer surfaced anywhere in this row. The pre-#158 "always-render-a-label, fall back to scratch" rule is gone with the helper.
- **No avatar, no leading slot, no trailing slot.** The Figma `15:8` treatment for discussions is content-only — title / body / meta on a tighter vertical rhythm than `ConversationRow`'s `ListItem`. Don't reach for `ListItem` here even if a future affordance lands (long-press → "Save as channel…", swipe action); add the gesture decorations to this `Column` directly, the way [`DiscussionListScreen`](./discussion-list-screen.md)'s row does.
- **Relative-time strings are not localised.** `just now` / `Yesterday` / `Nm ago` etc. are Kotlin literals inside `formatRelativeTime`. Out of scope for #69; a localisation pass becomes ~6 `<plurals>` entries and a `Resources`-typed parameter.

## Related

- Ticket notes: [`../codebase/69.md`](../codebase/69.md) (introduction), [`../codebase/158.md`](../codebase/158.md) (meta-line walked back to timestamp-only against Figma `15:8`)
- Specs: `docs/specs/architecture/69-channel-list-recent-discussions-section.md`, `docs/specs/architecture/158-discussion-preview-row-meta-timestamp-only.md`
- Upstream: [data model](./data-model.md) (`Conversation`, `DEFAULT_SCRATCH_CWD` — still seeds previews here), [ConversationRow](./conversation-row.md) (`formatRelativeTime` originated here; lost its own workspace label in #154 — the close sibling-walked-back precedent for #158)
- Consumer: [ChannelListScreen](./channel-list-screen.md) — `RecentDiscussionsSection` private composable iterates the VM's top-3 `recentDiscussions` and calls `DiscussionPreviewRow` for each
- Downstream / follow-ups: real message-content previews (one-line body-source swap once the data layer surfaces a per-`Conversation` last-message projection — the other Figma `15:8` divergence called out on #158), relative-time localisation, accessibility audit on the row hit area
