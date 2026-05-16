# ConversationRow

Shared list row for every Phase 0 conversation surface — the Channel list (#46/#68), the Discussions drilldown (#24), and the Archived Discussions screen (#94). Stateless `ListItem`-based primitive consuming `Conversation`. Locked to Figma node `15:8` since #154 — the single-line "avatar + name + sleep-dot + timestamp" shape with no per-row decorations.

## What it does

Renders one conversation as a single Material 3 `ListItem` with three slots:

- **Leading** — a 40dp [`ConversationAvatar`](./conversation-avatar.md) bubble (#68) with deterministic hash-selected `*-container` fill and 2-letter lowercase initials derived from the conversation name (falling back to the id). The avatar reads the `Conversation` directly; the row passes nothing else.
- **Headline** — a `Row` containing the conversation name and, when `conversation.isSleeping == true`, an 8.dp dot rendered *after* the name. The name is `Conversation.name` with a deterministic placeholder fallback. No workspace label, no other decorations.
- **Trailing** — relative timestamp derived from `Conversation.lastUsedAt`.

There is no supporting-content slot. Rows are single-line; `ListItem` collapses to its ~56dp one-line intrinsic height. Tapping anywhere on the row invokes `onClick` exactly once. If the optional `onLongClick` is supplied, the modifier chain switches from `Modifier.clickable(onClick)` to `Modifier.combinedClickable(onClick, onLongClick)` (under `@OptIn(ExperimentalFoundationApi::class)`) so long-press is also detected.

## Public surface

```kotlin
@Composable
fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
)
```

Stateless and pure: no `remember`, no `ViewModel`, no I/O. All parameters are Compose-stable; lambda stability is the caller's responsibility (call sites `remember`-wrap per-id where they care). The `lastMessage: Message?` parameter that existed #17 → #154 was removed in #154 — no production call site ever passed a non-null value, and the supporting-content slot it fed was deleted along with it.

The `onLongClick` parameter (added in #25) is the project-wide convention for adding a gesture surface to a shared row primitive without fanning a screen-specific concept into the component. The lambda is generic — `ConversationRow` is told only "here is something to call on long press"; the meaning (promote / rename / restore / etc.) is supplied by the caller. A screen-level pointer-input wrapper does **not** work as an alternative: `ConversationRow`'s inner `ListItem` applies its own `Modifier.clickable`, which consumes pointer events before any outer wrapper sees them. See [`../codebase/25.md`](../codebase/25.md) for the full rationale. Defaults to `null` — every pre-#25 call site continues to compile unchanged.

## How it works

### Auto-name fallback

```kotlin
val displayName = conversation.name?.takeIf { it.isNotBlank() }
    ?: if (conversation.isPromoted) "Untitled channel" else "Untitled discussion"
```

Branches on `isPromoted` so the placeholder matches the CLAUDE.md vocabulary (channels = persistent, discussions = throwaway). Depends only on stable fields — does **not** read `lastUsedAt` (which mutates) or `id` (UUID-shaped strings render badly).

### Sleeping-session indicator (#20 → repositioned in #154)

When `conversation.isSleeping == true`, an 8.dp `Box` filled with `MaterialTheme.colorScheme.outline` and `CircleShape` renders as the **second** child of the headline `Row`, *after* the name and before the trailing timestamp slot. When `isSleeping == false`, the `Box` is absent and `Arrangement.spacedBy(8.dp)` contributes no extra gap — the awake row's headline reads as a bare name. Headline `Row` layout:

```kotlin
Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    Text(
        text = displayName,
        modifier = Modifier.weight(1f, fill = false),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (conversation.isSleeping) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = MaterialTheme.colorScheme.outline, shape = CircleShape),
        )
    }
}
```

The 8.dp spacing between name and dot matches Figma node `15:68`'s inner-frame spacing. `Modifier.weight(1f, fill = false)` on the name yields remaining space to a fitting dot but does not stretch a short name to push the dot to the edge; `maxLines = 1` + `TextOverflow.Ellipsis` ellipsize when names are long. The dot's modifier chain (no `padding`, fixed `size(8.dp)`) is intentional — adding padding would consume part of the dot's render area and shrink it visually below the design's 8dp.

- The decoration is a `Box`-and-`background(shape)` primitive, **not** an `Icon`. `material-icons-core` (the always-on set) has no "sleeping" glyph; the closest is `Icons.Outlined.Schedule`, which reads as "duration" rather than "asleep". The sleeping-themed glyphs (`Bedtime`, `NightsStay`) live in `material-icons-extended`, which is **not** on the classpath and is deliberately not added — the dependency would bloat the APK by several MB of icon bytecode for one glyph.
- `colorScheme.outline` is the M3 role for inactive borders/dividers — colour-scheme-aware, automatically adjusts for dark mode. Not `error` (alarming), not `surfaceVariant` (invisible), not `primary` (call-to-action).
- The row stays fully tappable when sleeping; **no `Modifier.alpha`** is applied to the `ListItem`. The dot alone is the entire signal. Per-screen de-emphasis (e.g. `DiscussionListScreen`'s 0.65 alpha for the secondary tier) is layered *outside* the row at the call site.

The `Conversation.isSleeping` field is derived at projection in `FakeConversationRepository.observeConversations` (`current?.endedAt != null` on the conversation's current session). Phase 4's real backend will parse the field directly from the conversations endpoint response — the UI contract (`conversation.isSleeping: Boolean`) survives the migration; the Phase 1 derivation is throwaway. See [`data-model.md`](data-model.md) for the field shape and [`conversation-repository.md`](conversation-repository.md) for the projection.

### Relative time bucketing

Since #69 `formatRelativeTime(instant, now, timeZone)` lives at `internal` visibility in a peer file `ui/conversations/components/RelativeTime.kt` (same package) — extracted from `ConversationRow.kt` so the new [`DiscussionPreviewRow`](./discussion-preview-row.md) can call it without duplication. Default `now = Clock.System.now()` and `timeZone = TimeZone.currentSystemDefault()` so previews and any future test can inject deterministic values without exposing the parameter publicly. The two `LocalDate.Format` builders (`sameYearFormat`, `crossYearFormat`) moved with it; `ConversationRow.kt` no longer owns either. First-match wins:

| Condition | Output |
|---|---|
| `(now - instant).isNegative()` or `< 1 minute` | `"just now"` |
| `< 1 hour` | `"{N}m ago"` |
| `< 24 hours` | `"{N}h ago"` |
| `< 48 hours` | `"Yesterday"` |
| `< 7 days` | `"{N}d ago"` |
| same calendar year (in `timeZone`) | `"May 5"` |
| otherwise | `"May 5, 2025"` |

"Yesterday" uses a 48-hour **duration** threshold, not a calendar-day comparison. Calendar-day "yesterday" flips at midnight and produces "Yesterday" 31 seconds after a timestamp 30 seconds before midnight — Slack and Discord both use the duration shape for the same reason.

The displayed string is **frozen at composition time** — it does not tick forward as wall-clock time advances. A self-updating "live" timestamp via `produceState` + `delay` is an explicit non-goal; if a later ticket needs it, it goes in a dedicated decoration, not here.

Uses `kotlinx.datetime` end-to-end (per [ADR 0001](../decisions/0001-kotlinx-datetime-for-data-layer.md)) — `Clock.System`, `Instant`, `LocalDate.Format` DSL, `MonthNames.ENGLISH_ABBREVIATED`. No `java.time.*`, no `SimpleDateFormat`, no `Locale`.

### Typography & color

`ListItem` resolves slot styling to M3 defaults: `headlineContent` → `titleMedium`-equivalent on `onSurface`, `trailingContent` → `labelSmall` on `onSurfaceVariant`. The conversation name itself passes no overrides — the M3 defaults are the contract. The only explicit color override is on the sleeping dot's fill (`colorScheme.outline`), which is the role-aware muted-grey the headline `titleMedium` should *not* inherit.

## Configuration / usage

Three production call sites; each passes `Modifier.fillMaxWidth()` at the `modifier` parameter so the row stretches to fill its `LazyColumn` container. Per-screen treatment (de-emphasis alpha, long-press gestures) layers at the call site, not inside the row:

```kotlin
// ChannelListScreen — channels at full opacity, tap-to-open
ConversationRow(
    conversation = channel,
    onClick = { onEvent(ChannelListEvent.RowTapped(channel.id)) },
    modifier = Modifier.fillMaxWidth(),
)

// DiscussionListScreen — secondary-tier alpha + long-press menu
ConversationRow(
    conversation = discussion,
    onClick = onTap,
    modifier = Modifier.alpha(0.65f),
    onLongClick = { menuExpanded = true },
)

// ArchivedDiscussionsScreen — same alpha + long-press "Restore" menu
ConversationRow(
    conversation = discussion,
    onClick = {},
    modifier = Modifier.alpha(0.65f),
    onLongClick = { menuExpanded = true },
)
```

`modifier` is part of the public surface specifically because list consumers need it — `Modifier.fillMaxWidth()`, `Modifier.alpha(0.65f)`, `Modifier.animateItemPlacement()`, etc. Don't strip it.

## Edge cases / limitations

- `name = null` / `""` / whitespace → `"Untitled channel"` / `"Untitled discussion"`.
- `lastUsedAt` in the future (clock skew, bad seed data) → `"just now"`.
- Very long `displayName` → ellipsizes at 1 line via the explicit `maxLines = 1` on the name `Text` inside the headline `Row` (sleep-dot-aware: `Modifier.weight(1f, fill = false)` yields to a fitting dot first).
- `isSleeping = true` → trailing 8.dp dot in the headline (between name and timestamp); row stays tappable, no opacity change. `isSleeping = false` (default) → no dot, headline reads as a bare name.
- Strings are English literals. Localization is **out of scope** for Phase 0 — no `stringResource(...)`.

## Out of scope (deliberate)

- Per-row workspace label, supporting-content message preview, leading sleep dot — all walked back in #154 against Figma node `15:8`. Don't re-introduce as parameters or "feature flag"-style decorations; the row's job is the single-line spec.
- Real session-eviction loop / wake-up affordance — #20 surfaces a sleeping flag; flipping it on idle and the re-attach UX are Phase 4 backend territory.
- Surfacing the eviction `BoundaryReason` (Clear / IdleEvict / WorkspaceChange) in the row — explicit non-goal in #20's "Technical Notes".
- Tooltip / long-press to explain "sleeping" — accessibility-affordances polish, follow-up ticket if filed.
- Role-based name decoration (e.g. `"You: …"`) — `ConversationRow` shows the conversation name only.
- `ComposeTestRule` tests — `androidx-compose-ui-test-junit4` is in the catalog but the row itself has no instrumented tests; its visual contract is exercised through the screens that render it (`ChannelListScreenTest` since #99, `DiscussionListScreenTest` since #78).

## Previews

Two `@Preview` composables in the file, both `widthDp = 412`, both renamed in #154 to match the surviving state matrix:

- `ConversationRowPromotedPreview` — light theme; `previewConversation(name = "pyrycode-mobile", isPromoted = true)`. Covers the named-channel-awake state.
- `ConversationRowUntitledSleepingPreview` — dark theme (`Configuration.UI_MODE_NIGHT_YES`); `previewConversation(name = null, isPromoted = false, isSleeping = true)`. Covers the untitled-discussion-with-sleeping-dot state. Renders the placeholder `"Untitled discussion"` followed by the 8.dp `outline`-coloured dot, with the trailing timestamp at the end of the row.

The two together cover the matrix AC #5 of #154 asks for. Adding a third "named awake" preview is redundant — `ConversationRowPromotedPreview` already covers it.

## Related

- Specs: `docs/specs/architecture/17-conversation-row-composable.md`, `docs/specs/architecture/68-channel-list-figma-polish.md` (adds the leading avatar slot), `docs/specs/architecture/154-channel-row-figma-15-8.md` (locks the row to Figma `15:8`). Predecessor specs that #154 walked back: `docs/specs/architecture/19-conversation-row-workspace-label.md`, `docs/specs/architecture/20-conversation-row-sleeping-indicator.md`.
- Ticket notes: [`../codebase/17.md`](../codebase/17.md), [`../codebase/25.md`](../codebase/25.md) (optional `onLongClick`), [`../codebase/68.md`](../codebase/68.md) (leading avatar), [`../codebase/154.md`](../codebase/154.md) (current shape). Walked-back: [`../codebase/19.md`](../codebase/19.md), [`../codebase/20.md`](../codebase/20.md).
- Leading avatar primitive: [`ConversationAvatar`](./conversation-avatar.md) (#68)
- Upstream: #2 ([`Conversation`](data-model.md)), [ADR 0001](../decisions/0001-kotlinx-datetime-for-data-layer.md) (`kotlinx-datetime`)
- Consumers: [`ChannelListScreen`](channel-list-screen.md) (#46/#68), [`DiscussionListScreen`](discussion-list-screen.md) (#24 — wraps with `Modifier.alpha(0.65f)`; #25 — passes `onLongClick` for "Save as channel"; #78 — same gesture seam fed by the promotion-dialog state machine), [`ArchivedDiscussionsScreen`](archived-discussions-screen.md) (#94 — same alpha + `onLongClick` for "Restore")
