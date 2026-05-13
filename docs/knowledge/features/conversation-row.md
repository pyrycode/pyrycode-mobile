# ConversationRow

Shared list row for both Phase 0 conversation surfaces — the Channel list (#18) and the Discussions drilldown (later). First UI primitive consuming `Conversation` + `Message` from #2.

## What it does

Renders one conversation as a single Material 3 `ListItem` with four slots:

- **Leading** — a 40dp [`ConversationAvatar`](./conversation-avatar.md) bubble (#68) with deterministic hash-selected `*-container` fill and 2-letter lowercase initials derived from the conversation name (falling back to the id). The avatar reads the `Conversation` directly; the row passes nothing else.
- **Headline** — a `Row` containing the conversation name + zero or more decorations. The name is `Conversation.name` with a deterministic placeholder fallback. Two decorations layer onto the `Row`: the workspace label (#19, trailing edge) — a condensed form of `Conversation.cwd` for non-default-scratch conversations — and the sleeping-session indicator (#20, leading edge) — an 8.dp dot when `Conversation.isSleeping` is true.
- **Supporting** — the `Message.content` preview, whitespace-normalized, capped at 2 lines with ellipsis. Slot is `null` (not an empty lambda) when `lastMessage == null`, which collapses `ListItem` to its 1-line height — no manual layout branching needed.
- **Trailing** — relative timestamp derived from `Conversation.lastUsedAt`.

Tapping anywhere on the row invokes `onClick` exactly once. If the optional `onLongClick` is supplied, the modifier chain switches from `Modifier.clickable(onClick)` to `Modifier.combinedClickable(onClick, onLongClick)` (under `@OptIn(ExperimentalFoundationApi::class)`) so long-press is also detected.

## Public surface

```kotlin
@Composable
fun ConversationRow(
    conversation: Conversation,
    lastMessage: Message?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
)
```

Stateless and pure: no `remember`, no `ViewModel`, no I/O. All parameters are Compose-stable; lambda stability is the caller's responsibility (#18 will `remember`-wrap per-id).

The `onLongClick` parameter (added in #25) is the project-wide convention for adding a gesture surface to a shared row primitive without fanning a screen-specific concept into the component. The lambda is generic — `ConversationRow` is told only "here is something to call on long press"; the meaning (promote / rename / archive / etc.) is supplied by the caller. A screen-level pointer-input wrapper does **not** work as an alternative: `ConversationRow`'s inner `ListItem` applies its own `Modifier.clickable`, which consumes pointer events before any outer wrapper sees them. See [`../codebase/25.md`](../codebase/25.md) for the full rationale. Defaults to `null` — every pre-#25 call site continues to compile unchanged.

## How it works

### Auto-name fallback

```kotlin
val displayName = conversation.name?.takeIf { it.isNotBlank() }
    ?: if (conversation.isPromoted) "Untitled channel" else "Untitled discussion"
```

Branches on `isPromoted` so the placeholder matches the CLAUDE.md vocabulary (channels = persistent, discussions = throwaway). Depends only on stable fields — does **not** read `lastUsedAt` (which mutates) or `id` (UUID-shaped strings render badly). `cwd` is read separately by the workspace decoration (next section).

### Workspace label decoration (#19)

The headline slot is a `Row { name; if (label != null) Text(label) }`. `condenseWorkspace(cwd)` returns the label, or `null` to suppress the decoration entirely:

- `cwd == DefaultScratchCwd` (`"~/.pyrycode/scratch"`, the sentinel) → `null` (no label, headline reads as a bare name)
- Else strip trailing slashes, take `substringAfterLast('/')`; if that's blank, fall back to the trimmed whole; if *that's* still blank, return `null`.

| `cwd` | Label | Notes |
|---|---|---|
| `"~/.pyrycode/scratch"` | _(no label)_ | Default scratch — the noisy majority |
| `"~/Workspace/Projects/KitchenClaw/"` | `"KitchenClaw"` | Trailing slash stripped |
| `"~/Workspace/Projects/KitchenClaw"` | `"KitchenClaw"` | Same without trailing slash |
| `"~/Workspace"` | `"Workspace"` | Single segment |
| `"~"` | `"~"` | No `/` — `substringAfterLast` returns the whole; reads as "home" |
| `""` | _(no label)_ | Blank cascade — covers `createDiscussion(workspace = null)` |
| `"/"` | _(no label)_ | Trims to blank — edge case, defined for completeness |
| `"/usr/local/bin"` | `"bin"` | Absolute paths follow the same rule |

The label is a plain `Text` with `style = MaterialTheme.typography.labelMedium` and `color = MaterialTheme.colorScheme.onSurfaceVariant` — explicitly **not** an `AssistChip`, `SuggestionChip`, or `Surface` pill, because those have a 32.dp min-height that would visibly raise the row's intrinsic height and break the "no layout shift versus other rows" contract. `labelMedium` sits one tier above the trailing timestamp's M3-default `labelSmall` — heavier enough to read as an annotation tied to the name, lighter enough to obviously sit below the headline.

Name layout in the `Row`:

- `Modifier.weight(1f, fill = false)` — yields remaining space to a label that fits, but doesn't expand a short name to push the label to the edge.
- `maxLines = 1` + `TextOverflow.Ellipsis` — name ellipsizes first when long names crowd the label; pathological long single-segment labels ellipsize too.
- `Arrangement.spacedBy(8.dp)` between `Row` children — applies only when both render; absent labels contribute nothing.

The sentinel constant `DefaultScratchCwd` lives at the top level of `data/model/Conversation.kt` — single source of truth, imported by `ConversationRow`, `FakeConversationRepository` seeds, and the repository test that asserts on the scratch literal.

### Sleeping-session indicator (#20)

When `conversation.isSleeping == true`, an 8.dp `Box` filled with `MaterialTheme.colorScheme.outline` and `CircleShape` renders as the **first** child of the headline `Row`, opposite the trailing workspace label. When `isSleeping == false`, the `Box` is absent and `Arrangement.spacedBy(8.dp)` contributes no leading gap — the active row's headline reads exactly as it did pre-#20.

- The decoration is a `Box`-and-`background(shape)` primitive, **not** an `Icon`. `material-icons-core` (the always-on set) has no "sleeping" glyph; the closest is `Icons.Outlined.Schedule`, which reads as "duration" rather than "asleep". The sleeping-themed glyphs (`Bedtime`, `NightsStay`) live in `material-icons-extended`, which is **not** on the classpath and is deliberately not added — the dependency would bloat the APK by several MB of icon bytecode for one glyph.
- `colorScheme.outline` is the M3 role for inactive borders/dividers — colour-scheme-aware, automatically adjusts for dark mode. Not `error` (alarming), not `surfaceVariant` (invisible), not `primary` (call-to-action).
- The row stays fully tappable when sleeping; **no `Modifier.alpha`** is applied to the `ListItem`. The dot alone is the entire signal. Pairing it with row-wide opacity would push the supporting-content preview into a "looks disabled" register and is rejected.

The `Conversation.isSleeping` field is derived at projection in `FakeConversationRepository.observeConversations` (`current?.endedAt != null` on the conversation's current session). Phase 4's real backend will parse the field directly from the conversations endpoint response — the UI contract (`conversation.isSleeping: Boolean`) survives the migration; the Phase 1 derivation is throwaway. See [`data-model.md`](data-model.md) for the field shape and [`conversation-repository.md`](conversation-repository.md) for the projection.

### Preview normalization

`previewText(content)` collapses any whitespace run (including embedded newlines) to a single space and trims. Without this, a message starting with blank lines or a markdown heading burns its 2-line budget on whitespace before any text renders. `maxLines = 2` + `TextOverflow.Ellipsis` are the M3 idiom for "1–2 lines with ellipsis".

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

The displayed string is **frozen at composition time** — it does not tick forward as wall-clock time advances. A self-updating "live" timestamp via `produceState` + `delay` is an explicit non-goal; if a later ticket needs it, it goes in #18 or a dedicated decoration, not here.

Uses `kotlinx.datetime` end-to-end (per [ADR 0001](../decisions/0001-kotlinx-datetime-for-data-layer.md)) — `Clock.System`, `Instant`, `LocalDate.Format` DSL, `MonthNames.ENGLISH_ABBREVIATED`. No `java.time.*`, no `SimpleDateFormat`, no `Locale`.

### Typography & color

`ListItem` resolves slot styling to M3 defaults: `headlineContent` → `titleMedium`-equivalent on `onSurface`, `supportingContent` → `bodyMedium` on `onSurfaceVariant`, `trailingContent` → `labelSmall` on `onSurfaceVariant`. The conversation name itself passes no overrides — the M3 defaults are the contract. The only explicit `style` / `color` overrides in the row are on decorations whose hierarchy is *intentionally* below the headline (the workspace label uses `labelMedium` / `onSurfaceVariant`, the sleeping dot is filled with `colorScheme.outline`); without overriding, decorations would inherit the headline's `titleMedium` and read as a second name rather than annotation.

## Configuration / usage

Not yet wired into any screen. #18 (Channel list) is the first consumer:

```kotlin
LazyColumn { items(channels, key = { it.id }) { channel ->
    ConversationRow(
        conversation = channel,
        lastMessage = previewsByConversation[channel.id],
        onClick = remember(channel.id) {
            { navController.navigate("conversation_thread/${channel.id}") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}}
```

`modifier` is part of the public surface specifically because list consumers need it — `Modifier.fillMaxWidth()`, `Modifier.animateItemPlacement()`, etc. Don't strip it.

## Edge cases / limitations

- `name = null` / `""` / whitespace → `"Untitled channel"` / `"Untitled discussion"`.
- `lastMessage = null` → supporting slot collapses, row shrinks to ~56dp; no placeholder text, no reserved vertical space.
- `lastMessage.content` with embedded newlines or leading whitespace → normalized by `previewText`.
- `lastUsedAt` in the future (clock skew, bad seed data) → `"just now"`.
- Very long `displayName` → ellipsizes at 1 line via the explicit `maxLines = 1` on the name `Text` inside the headline `Row` (decoration-aware: `Modifier.weight(1f, fill = false)` yields to a fitting label first).
- `cwd = ""` (current `createDiscussion(workspace = null)` output) → no label, via `condenseWorkspace`'s blank-fallback chain. Behaves identically to `cwd == DefaultScratchCwd` at the UI; aligning the writer with the sentinel is a deferred follow-up.
- `isSleeping = true` → leading 8.dp dot in the headline; row stays tappable, supporting-content preview stays at full opacity. `isSleeping = false` (default) → no dot, headline reads exactly as the #17/#19 baseline.
- Strings are English literals. Localization is **out of scope** for Phase 0 — no `stringResource(...)`.

## Out of scope (deliberate)

- Real session-eviction loop / wake-up affordance — #20 surfaces a sleeping flag; flipping it on idle and the re-attach UX are Phase 4 backend territory.
- Surfacing the eviction `BoundaryReason` (Clear / IdleEvict / WorkspaceChange) in the row — explicit non-goal in #20's "Technical Notes".
- Tooltip / long-press to explain "sleeping" — accessibility-affordances polish, follow-up ticket if filed.
- Role-based preview decoration (e.g. `"You: …"` for `Role.User`) — not in AC; future ticket if needed.
- Extracting `previewText` / `condenseWorkspace` to a shared module — both remain `private` to this file. `formatRelativeTime` was extracted in #69 (peer file `RelativeTime.kt`, `internal`); the same move applies to either of the others when a second caller appears. `condenseWorkspace` was *not* shared with `DiscussionPreviewRow` in #69 — the two have intentionally different fallback rules, and the spec forbids a shared helper.
- Tooltip / long-press to surface the full `cwd` path — explicit non-goal in #19's "Technical Notes".
- Aligning `FakeConversationRepository.createDiscussion(workspace = null)` to write `DefaultScratchCwd` instead of `""` — works correctly today via the blank-fallback at the UI; deferred to a follow-up if filed.
- `ComposeTestRule` tests — `androidx-compose-ui-test-junit4` is in the catalog but has zero consumers; introducing it here would expand scope beyond the AC.

## Related

- Specs: `docs/specs/architecture/17-conversation-row-composable.md`, `docs/specs/architecture/19-conversation-row-workspace-label.md`, `docs/specs/architecture/20-conversation-row-sleeping-indicator.md`, `docs/specs/architecture/68-channel-list-figma-polish.md` (adds the leading avatar slot)
- Ticket notes: `docs/knowledge/codebase/17.md`, `docs/knowledge/codebase/19.md`, `docs/knowledge/codebase/20.md`, `docs/knowledge/codebase/68.md`
- Leading avatar primitive: [`ConversationAvatar`](./conversation-avatar.md) (#68)
- Upstream: #2 ([`Conversation` + `Message`](data-model.md)), [ADR 0001](../decisions/0001-kotlinx-datetime-for-data-layer.md) (`kotlinx-datetime`)
- Downstream: #46 ([Channel list](channel-list-screen.md) — first consumer), [Discussions drilldown](discussion-list-screen.md) (#24 — calls with `Modifier.alpha(0.65f)`; #25 — passes `onLongClick` to open the "Save as channel…" menu)
