# ConversationRow

Shared list row for both Phase 0 conversation surfaces — the Channel list (#18) and the Discussions drilldown (later). First UI primitive consuming `Conversation` + `Message` from #2.

## What it does

Renders one conversation as a single Material 3 `ListItem` with three slots:

- **Headline** — a `Row` containing the conversation name + zero or more decorations. The name is `Conversation.name` with a deterministic placeholder fallback. The first decoration is the workspace label (#19) — a condensed form of `Conversation.cwd` for non-default-scratch conversations; #20 will layer a sleeping-session indicator onto the same `Row`.
- **Supporting** — the `Message.content` preview, whitespace-normalized, capped at 2 lines with ellipsis. Slot is `null` (not an empty lambda) when `lastMessage == null`, which collapses `ListItem` to its 1-line height — no manual layout branching needed.
- **Trailing** — relative timestamp derived from `Conversation.lastUsedAt`.

Tapping anywhere on the row invokes `onClick` exactly once via `Modifier.clickable`.

## Public surface

```kotlin
@Composable
fun ConversationRow(
    conversation: Conversation,
    lastMessage: Message?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Stateless and pure: no `remember`, no `ViewModel`, no I/O. All parameters are Compose-stable; lambda stability is the caller's responsibility (#18 will `remember`-wrap per-id).

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

### Preview normalization

`previewText(content)` collapses any whitespace run (including embedded newlines) to a single space and trims. Without this, a message starting with blank lines or a markdown heading burns its 2-line budget on whitespace before any text renders. `maxLines = 2` + `TextOverflow.Ellipsis` are the M3 idiom for "1–2 lines with ellipsis".

### Relative time bucketing

Inline `private` helper `formatRelativeTime(instant, now, timeZone)` — default `now = Clock.System.now()` and `timeZone = TimeZone.currentSystemDefault()` so previews and any future test can inject deterministic values without exposing the parameter publicly. First-match wins:

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

`ListItem` resolves slot styling to M3 defaults: `headlineContent` → `titleMedium`-equivalent on `onSurface`, `supportingContent` → `bodyMedium` on `onSurfaceVariant`, `trailingContent` → `labelSmall` on `onSurfaceVariant`. The conversation name itself passes no overrides — the M3 defaults are the contract. The only explicit `style` / `color` overrides in the row are on decorations whose hierarchy is *intentionally* below the headline (the workspace label uses `labelMedium` / `onSurfaceVariant`); without overriding, decorations would inherit the headline's `titleMedium` and read as a second name rather than annotation.

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
- Strings are English literals. Localization is **out of scope** for Phase 0 — no `stringResource(...)`.

## Out of scope (deliberate)

- Sleeping-session indicator — #20 (will layer onto the same headline `Row` seam as the workspace label).
- Role-based preview decoration (e.g. `"You: …"` for `Role.User`) — not in AC; future ticket if needed.
- Extracting `formatRelativeTime` / `previewText` / `condenseWorkspace` to a shared module — all three are `private` to this file. If a later ticket (e.g. thread message timestamps, a workspace picker) needs one, that ticket extracts.
- Tooltip / long-press to surface the full `cwd` path — explicit non-goal in #19's "Technical Notes".
- Aligning `FakeConversationRepository.createDiscussion(workspace = null)` to write `DefaultScratchCwd` instead of `""` — works correctly today via the blank-fallback at the UI; deferred to a follow-up if filed.
- `ComposeTestRule` tests — `androidx-compose-ui-test-junit4` is in the catalog but has zero consumers; introducing it here would expand scope beyond the AC.

## Related

- Specs: `docs/specs/architecture/17-conversation-row-composable.md`, `docs/specs/architecture/19-conversation-row-workspace-label.md`
- Ticket notes: `docs/knowledge/codebase/17.md`, `docs/knowledge/codebase/19.md`
- Upstream: #2 ([`Conversation` + `Message`](data-model.md)), [ADR 0001](../decisions/0001-kotlinx-datetime-for-data-layer.md) (`kotlinx-datetime`)
- Downstream: #18 (Channel list — first consumer), Discussions drilldown (later), #20 (sleeping-session indicator — layers onto the headline `Row` seam established here)
