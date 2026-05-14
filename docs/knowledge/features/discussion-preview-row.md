# DiscussionPreviewRow

Stateless row primitive (#69) rendering a single recent discussion inside the inline "Recent discussions" section of [`ChannelListScreen`](./channel-list-screen.md). Title (auto-fallback to `R.string.untitled_discussion`), 2-line body placeholder, and a `<workspace> · <relative-time>` meta line with a monospace span on the workspace fragment. Sole call site is the section composable inside `ChannelListScreen.kt` for now; sibling of [`ConversationRow`](./conversation-row.md) but with deliberately different rendering rules.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `DiscussionPreviewRow.kt`.

## What it does

Renders a single `Conversation` as a three-line `Column` with a top-level click target. Top to bottom:

- **Title.** `Text(displayName, style = titleMedium, color = onSurface, maxLines = 1, overflow = Ellipsis)`. `displayName` is `conversation.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.untitled_discussion)`.
- **Body.** `Text(stringResource(R.string.discussion_preview_placeholder_body), style = bodyMedium, color = onSurfaceVariant, maxLines = 2, overflow = Ellipsis)`. The placeholder body is intentional: the `Conversation` model carries no last-message field, and subscribing to `ConversationRepository.observeMessages(id)` per row would be prohibitive; #69 explicitly defers real previews to a follow-up.
- **Meta.** Single `Text` rendering an `AnnotatedString` of the shape `<workspace> · <relative-time>`. `style = labelSmall`, `color = onSurfaceVariant.copy(alpha = 0.70f)`, `maxLines = 1`. The workspace fragment is wrapped in `SpanStyle(fontFamily = FontFamily.Monospace)`; the dot-separator and timestamp inherit the label-small style. Time label comes from the shared `formatRelativeTime(...)` helper in `RelativeTime.kt` (same package).

Workspace label is computed by the file-private `discussionPreviewWorkspaceLabel(cwd, scratchLabel)`:

- `cwd == DEFAULT_SCRATCH_CWD` → `R.string.discussion_preview_workspace_scratch` (`"scratch"`).
- Else → `cwd.trimEnd('/').substringAfterLast('/').ifBlank { scratchLabel }`.

The whole `Column` is `.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)`, with `verticalArrangement = Arrangement.spacedBy(2.dp)`. No leading avatar, no trailing affordance, no `ListItem` chrome — this is a tighter, content-only row.

## Shape

```kotlin
@Composable
fun DiscussionPreviewRow(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)

private fun discussionPreviewWorkspaceLabel(cwd: String, scratchLabel: String): String
```

The component is intentionally `Conversation`-shaped (not `String title + String body`) so the placeholder-body logic stays inside the row. When a follow-up wires real message previews, only the body `Text`'s source string changes; the call sites (currently a `forEach` inside `RecentDiscussionsSection`) are untouched.

## How it works

### Workspace-label rule diverges from `ConversationRow`

`ConversationRow.condenseWorkspace(cwd)` returns `String?` — `null` for `DEFAULT_SCRATCH_CWD`, so the caller omits the workspace chip entirely. `DiscussionPreviewRow` *always* renders a meta line and always shows a workspace label; collapsing to `"scratch"` is the contract. The rules are similar enough to look extractable and different enough that the spec explicitly forbids sharing — two near-twin helpers each one screen away from their caller beats one helper with a behaviour flag. See [`./conversation-row.md`](./conversation-row.md) for `condenseWorkspace`.

### Single `AnnotatedString` for the mixed-font meta line

The meta line builds one `AnnotatedString` via `buildAnnotatedString { withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(workspaceLabel) }; append(" · "); append(timeLabel) }` and hands it to a single `Text` carrying the inherited `labelSmall` style. Splitting into three sibling `Text` composables inside a `Row(Arrangement.spacedBy(...))` would baseline-misalign at the kerning the dot-separator wants; the monospace span on a single line keeps the baseline coherent. Pattern for any single-line "mixed font/colour/weight run inside an otherwise-themed `Text`": reach for `SpanStyle` over a `Row` of sibling `Text`s.

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

- **No new dependencies.** `kotlinx.datetime` is already on the classpath since the data layer landed. `FontFamily.Monospace` is built into Compose.
- **Strings:** `R.string.untitled_discussion` ("Untitled discussion"), `R.string.discussion_preview_placeholder_body` ("Tap to resume — message preview coming soon."), `R.string.discussion_preview_workspace_scratch` ("scratch"). All added in #69.

## Preview

Three `@Preview`s in `DiscussionPreviewRow.kt`, all `widthDp = 412`:

- `DiscussionPreviewRowScratchLightPreview` — `cwd = DEFAULT_SCRATCH_CWD`, `name = "What's the safest way…"`, `lastUsedAt = now - 12.minutes`. Light theme. Exercises the `"scratch"` workspace fallback and the `"12m ago"` minute-bucket time format.
- `DiscussionPreviewRowScratchDarkPreview` — same shape, `name = "Help me debug auth flow"`, `lastUsedAt = now - 2.hours`. Dark theme (`Configuration.UI_MODE_NIGHT_YES`). Exercises the `"2h ago"` hour-bucket and dark-scheme `onSurface` / `onSurfaceVariant` contrast on the body and meta lines.
- `DiscussionPreviewRowNamedCwdPreview` — `cwd = "~/Workspace/Projects/some-repo"`, `name = "Quick regex for log parsing"`, `lastUsedAt = now - 5.hours`. Light theme. Exercises the non-default-cwd `substringAfterLast('/')` branch — meta line reads `some-repo · 5h ago`.

## Edge cases / limitations

- **Body is a single non-parameterised placeholder.** `R.string.discussion_preview_placeholder_body` ships with the literal "Tap to resume — message preview coming soon." for every row, regardless of conversation content. The follow-up that wires real previews replaces the `stringResource(...)` body source with whatever the data layer surfaces (likely a per-`Conversation` last-message projection on the VM emission); the row's other lines are untouched.
- **Workspace label is computed inline, not via `condenseWorkspace`.** Intentional — `ConversationRow.condenseWorkspace` returns `null` for `DEFAULT_SCRATCH_CWD` (the row omits the workspace chip), whereas `DiscussionPreviewRow` always renders a meta line and falls back to `"scratch"`. Two helpers, two contracts; do not consolidate.
- **No avatar, no leading slot, no trailing slot.** The Figma `15:8` treatment for discussions is content-only — title / body / meta on a tighter vertical rhythm than `ConversationRow`'s `ListItem`. Don't reach for `ListItem` here even if a future affordance lands (long-press → "Save as channel…", swipe action); add the gesture decorations to this `Column` directly, the way [`DiscussionListScreen`](./discussion-list-screen.md)'s row does.
- **Relative-time strings are not localised.** `just now` / `Yesterday` / `Nm ago` etc. are Kotlin literals inside `formatRelativeTime`. Out of scope for #69; a localisation pass becomes ~6 `<plurals>` entries and a `Resources`-typed parameter.

## Related

- Ticket notes: [`../codebase/69.md`](../codebase/69.md)
- Spec: `docs/specs/architecture/69-channel-list-recent-discussions-section.md`
- Upstream: [data model](./data-model.md) (`Conversation`, `DEFAULT_SCRATCH_CWD`), [ConversationRow](./conversation-row.md) (`formatRelativeTime` originated here; `condenseWorkspace` rule is the contrast point)
- Consumer: [ChannelListScreen](./channel-list-screen.md) — `RecentDiscussionsSection` private composable iterates the VM's top-3 `recentDiscussions` and calls `DiscussionPreviewRow` for each
- Downstream / follow-ups: real message-content previews (one-line body-source swap once the data layer surfaces a per-`Conversation` last-message projection), relative-time localisation, accessibility audit on the row hit area
