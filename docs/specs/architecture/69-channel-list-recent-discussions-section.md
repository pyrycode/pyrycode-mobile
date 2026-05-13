# Spec — Channel List: inline Recent discussions section + See all link (#69)

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Below the channels list sits a thin `outlineVariant` divider at 60% alpha (Figma `15:85`), an `onSurfaceVariant`-at-85%-alpha "Recent discussions" header in M3 label-large (Figma `15:87`), then up to three preview cards (Figma `15:88` / `15:92` / `15:96`) — each a vertical 2-dp-gap column with a title-medium title, a body-medium 2-line ellipsised body, and a label-small meta line of the shape `<workspace> · <relative-time>` (`onSurfaceVariant` at 70% alpha; the workspace fragment is monospace `Roboto Mono` 11sp, the dot-separator and timestamp are `Roboto` 11sp). The trailing affordance (Figma `15:100`–`15:103`) is a left-aligned clickable row pairing an M3 label-large "See all discussions (N)" label tinted `colorScheme.primary` with a 18dp `arrow_forward` icon.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:1-271` — the screen being edited. Public surface `ChannelListScreen(state, onEvent, modifier)` and the `ChannelListEvent` sealed interface (incl. `RecentDiscussionsTapped`) are frozen. The `Loaded` and `Empty` branches change to render the new section in place of `RecentDiscussionsPill`. All six existing Previews are reworked (loaded/empty × light/dark and the two `+ pill` variants — see § Previews).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:1-84` — `ChannelListUiState.Loaded` and `Empty` gain a `recentDiscussions: List<Conversation>` field; the `combine` body changes to derive that list from the existing `ConversationFilter.Discussions` flow. `ChannelListNavigation`, `navigationEvents`, and the `onEvent` body are unchanged; `RecentDiscussionsTapped` stays a no-op.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:32-47` — confirms `observeConversations(ConversationFilter.Discussions)` already emits `.sortedByDescending { it.lastUsedAt }`. The ViewModel may rely on that ordering and `.take(3)` without re-sorting; the spec depends on this contract holding.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-44` — the `ConversationRepository` interface and `ConversationFilter` enum the VM consumes. No surface changes here; just confirming the contract for the test stubs you'll need to extend.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:107-150` — contains the existing `private fun formatRelativeTime(...)` and `private fun condenseWorkspace(...)`. § Shared utilities below requires you to extract `formatRelativeTime` into a new internal top-level helper in the components package so the new preview row can call it without duplicating logic. `condenseWorkspace` stays put — the preview row needs a slightly different rule (see § Workspace label rule).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RecentDiscussionsPill.kt` (all 55 lines) — the file you will DELETE. Verify the file is removed at the end; no other call sites exist (the strings it consumes are also deleted — see § Strings).
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:5-20` — `Conversation` data class and the `DefaultScratchCwd` sentinel. Drives the workspace-label rule in § Workspace label rule.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt:1-309` — the existing VM test file. Five tests assert `Loaded(..., recentDiscussionsCount = N)` or `Empty(recentDiscussionsCount = N)` literally; all need updating to include the new `recentDiscussions = ...` field. § Tests below enumerates the surgical updates and the new top-3-derivation test to add.
- `app/src/main/res/values/strings.xml:1-16` — current copy. § Strings below specifies the three additions and the two deletions.
- `docs/specs/architecture/68-channel-list-figma-polish.md` — the immediately-preceding slice that established the `ChannelsSectionHeader` pattern, the avatar bubble, and the M3-token discipline this spec inherits. Read its § Design and § Constraints sections; the styling rules carry forward verbatim.
- `gradle/libs.versions.toml` and `app/build.gradle.kts` — no new dependencies. `androidx-compose-material-icons-extended` is not on the classpath, but `Icons.AutoMirrored.Filled.ArrowForward` lives in `androidx-compose-material-icons-core` (already present, used by the existing FAB `Icons.Default.Add`). No catalog edits.

## Context

#46 shipped `RecentDiscussionsPill` as a single capsule rendered at the bottom of the channels column, surfacing only the count and routing to `DiscussionListScreen`. The canonical Figma `15:8` replaces that minimal affordance with an inline section directly below the channels: divider, header, up to three preview cards (title + 2-line body + workspace · time meta), and a "See all discussions (N) →" link that retains the existing navigation event. The pill is removed entirely.

Two architectural constraints govern the design:

1. **Message-content previews are aspirational, not real.** The `Conversation` data class carries no last-message field, and `ConversationRepository.observeMessages(conversationId)` is a per-conversation paginated stream that's prohibitively expensive to subscribe to per row in a list. The ticket explicitly defers real previews to a follow-up; this spec ships a deterministic, localised placeholder body string for every preview row. When real message access lands, only `DiscussionPreviewRow` and its caller in `ChannelListScreen` change.
2. **Sorting is upstream.** `FakeConversationRepository.observeConversations` already emits the discussions list sorted by `lastUsedAt` desc. The VM does `.take(3)` against that ordering; no re-sort is needed and no new contract is added to the repository.

## Design

### File scope

- **Modify:** `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`
- **Modify:** `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt`
- **Modify:** `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt` (only to move `formatRelativeTime` out — see § Shared utilities)
- **Modify:** `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt`
- **Modify:** `app/src/main/res/values/strings.xml`
- **Create:** `app/src/main/java/de/pyryco/mobile/ui/conversations/components/DiscussionPreviewRow.kt`
- **Create:** `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RelativeTime.kt`
- **Delete:** `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RecentDiscussionsPill.kt`

No changes to `Conversation`, `ConversationRepository`, `FakeConversationRepository`, `ConversationAvatar`, `MainActivity`, `PyryApp`, `Theme.kt`, `Color.kt`, `Type.kt`, `DiscussionListScreen`, navigation wiring, or DI modules.

### Public surface

`ChannelListEvent`, `ChannelListNavigation`, the `ChannelListScreen(state, onEvent, modifier)` signature, and `ChannelListViewModel`'s public API are all frozen. The only exported additions are:

```kotlin
// In ChannelListUiState (the sealed interface in ChannelListViewModel.kt)
data class Empty(
    val recentDiscussions: List<Conversation>,
    val recentDiscussionsCount: Int,
) : ChannelListUiState

data class Loaded(
    val channels: List<Conversation>,
    val recentDiscussions: List<Conversation>,
    val recentDiscussionsCount: Int,
) : ChannelListUiState
```

`recentDiscussions` is the up-to-three most-recent discussion conversations (sorted upstream, capped via `.take(3)`); `recentDiscussionsCount` is the total count (uncapped) — the same semantics as today, used by the See-all label.

The new components are package-internal-shaped (top-level `@Composable fun` is necessarily public in Kotlin, but they're consumed only from `ChannelListScreen` for now):

```kotlin
// DiscussionPreviewRow.kt
@Composable
fun DiscussionPreviewRow(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)

// RelativeTime.kt
internal fun formatRelativeTime(
    instant: Instant,
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String
```

`DiscussionPreviewRow` takes a `Conversation` (not a string title + string body) so the placeholder-body logic stays inside the row component — when a follow-up wires real message previews, the row's body source changes without disturbing call sites.

### ViewModel derivation

The `combine` body in `ChannelListViewModel.state` changes shape: instead of mapping the discussions flow to `.size`, it captures both the top-3 list and the total count from the same upstream emission:

- Source flows: `repository.observeConversations(ConversationFilter.Channels)` and `repository.observeConversations(ConversationFilter.Discussions)`.
- For the discussions side, compute `recent = discussions.take(3)` and `count = discussions.size` in the same `combine` lambda — one upstream subscription, two derived values, no `distinctUntilChanged` games.
- Emit `Empty(recent, count)` when `channels.isEmpty()`, `Loaded(channels, recent, count)` otherwise.
- `.catch` / `.stateIn` behaviour unchanged.

Behavioural invariant: `recentDiscussions.size <= 3`, `recentDiscussions.size <= recentDiscussionsCount`, and when `recentDiscussionsCount == 0` both fields are empty/zero (so the consuming UI's "section is empty" predicate is `recentDiscussions.isEmpty()`).

### Section composable in `ChannelListScreen`

Add a private composable `RecentDiscussionsSection` inside `ChannelListScreen.kt` that renders the full inline block. Signature:

```kotlin
@Composable
private fun RecentDiscussionsSection(
    discussions: List<Conversation>,
    totalCount: Int,
    onSeeAllClick: () -> Unit,
    onRowClick: (String) -> Unit,
)
```

Behaviour contract:

- If `discussions.isEmpty()`, the composable returns without emitting anything (no divider, no header, no link — the "no orphan header" AC).
- Otherwise it emits a `Column(Modifier.fillMaxWidth())` with, in order:
  1. A 1dp-thick horizontal divider (`HorizontalDivider`), `outlineVariant` at 60% alpha (matches Figma `15:85`), wrapped in `Modifier.padding(horizontal = 16.dp, top = 12.dp)`. Use `MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f)`.
  2. The "Recent discussions" section header: a `Text` with `style = MaterialTheme.typography.labelLarge`, `color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)`, padding `start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp`. Mirrors the existing `ChannelsSectionHeader` pattern in the same file (line 146).
  3. `discussions.forEach { conversation -> DiscussionPreviewRow(conversation, onClick = { onRowClick(conversation.id) }) }` — no `LazyColumn`; the section is part of the outer `Column` and the row count is bounded at 3.
  4. The See-all affordance: a `Row` with `Modifier.fillMaxWidth().clickable(...).padding(horizontal = 16.dp, top = 4.dp, bottom = 8.dp)`, containing a `Text` (the label) and an `Icon(Icons.AutoMirrored.Filled.ArrowForward, ..., modifier = Modifier.size(18.dp))`. The label uses `MaterialTheme.typography.labelLarge` and `MaterialTheme.colorScheme.primary`; the icon `tint = MaterialTheme.colorScheme.primary`. The row has `horizontalArrangement = Arrangement.spacedBy(6.dp)` and `verticalAlignment = Alignment.CenterVertically`.

The `clickable` on the See-all row uses `role = Role.Button` and a `Modifier.semantics(mergeDescendants = true) { contentDescription = … }` wrapping the label text — see § Accessibility.

The call from the `Loaded` branch becomes:

```kotlin
is ChannelListUiState.Loaded -> Column(bodyModifier.fillMaxSize()) {
    ChannelsSectionHeader()
    LazyColumn(modifier = Modifier.weight(1f)) {
        items(items = state.channels, key = { it.id }) { channel ->
            ConversationRow(
                conversation = channel,
                lastMessage = null,
                onClick = { onEvent(ChannelListEvent.RowTapped(channel.id)) },
            )
        }
        item(key = "recent-discussions-section") {
            RecentDiscussionsSection(
                discussions = state.recentDiscussions,
                totalCount = state.recentDiscussionsCount,
                onSeeAllClick = { onEvent(ChannelListEvent.RecentDiscussionsTapped) },
                onRowClick = { onEvent(ChannelListEvent.RowTapped(it)) },
            )
        }
    }
}
```

Note the placement change: the recent-discussions section moves **inside** the `LazyColumn` as a single trailing `item` so it scrolls together with the channels list, matching the Figma's single-scroll-region layout. The `LazyColumn`'s `Modifier.weight(1f)` claim of the remaining vertical space stays the same.

For the `Empty` branch the section moves above the centred empty-state text inside the `Column`, since there is no `LazyColumn` to host it. Shape:

```kotlin
is ChannelListUiState.Empty -> Column(bodyModifier.fillMaxSize()) {
    RecentDiscussionsSection(
        discussions = state.recentDiscussions,
        totalCount = state.recentDiscussionsCount,
        onSeeAllClick = { onEvent(ChannelListEvent.RecentDiscussionsTapped) },
        onRowClick = { onEvent(ChannelListEvent.RowTapped(it)) },
    )
    Box(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentAlignment = Alignment.Center,
    ) { Text(stringResource(R.string.channel_list_empty)) }
}
```

When `state.recentDiscussions.isEmpty()` the section is a no-op and only the centred empty text shows — the existing #46 behaviour is preserved.

### `DiscussionPreviewRow`

A vertical `Column` matching Figma `15:88` / `15:92` / `15:96`. Public composable:

```kotlin
@Composable
fun DiscussionPreviewRow(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Layout (top-to-bottom):

- Outer `Column` with `Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)`, `verticalArrangement = Arrangement.spacedBy(2.dp)`.
- **Title.** `Text(displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)`. `displayName` follows `ConversationRow`'s rule: `conversation.name?.takeIf { it.isNotBlank() } ?: "Untitled discussion"` — but as a stringResource, not a literal. Add `R.string.untitled_discussion` (§ Strings).
- **Body.** `Text(stringResource(R.string.discussion_preview_placeholder_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)`. Single non-parameterised placeholder string; deliberately bland — when real previews land it's a one-line swap.
- **Meta.** A single `Text` rendering `"$workspaceLabel · $timeLabel"` with `style = MaterialTheme.typography.labelSmall`, `color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)`, `maxLines = 1`. The Figma uses `Roboto Mono` for the workspace fragment (a typographic flourish): emit it as an `AnnotatedString` with a `SpanStyle(fontFamily = FontFamily.Monospace)` applied only to `workspaceLabel`. The separator and time use the inherited label-small style.

#### Workspace label rule

The preview row's meta always shows a workspace label — including for default-scratch conversations, which `condenseWorkspace` currently returns `null` for. Compute inline (do NOT modify the existing `condenseWorkspace`):

- If `conversation.cwd == DefaultScratchCwd` → label is `"scratch"` (matches Figma's `scratch ·` fragment).
- Otherwise → `conversation.cwd.trimEnd('/').substringAfterLast('/').ifBlank { "scratch" }` — same trailing-segment extraction, with `"scratch"` as the empty-string fallback so the meta line never has a leading `" · "`.

Localisation: the literal `"scratch"` is presentation copy, surface it via `R.string.discussion_preview_workspace_scratch` (§ Strings) so future translators can adjust without touching code.

#### Time label

`formatRelativeTime(conversation.lastUsedAt)` — the extracted shared helper (§ Shared utilities). Output strings (`"just now"`, `"3m ago"`, `"Yesterday"`, etc.) are produced inside that helper; they remain Kotlin string literals there (not localised) to match the existing `ConversationRow` trailing timestamp. Localising the relative-time strings is out of scope — a follow-up if/when the app gets translations.

### Shared utilities — `RelativeTime.kt`

Extract `formatRelativeTime` from `ConversationRow.kt` into a new file in the same package, internal visibility:

```kotlin
// File: app/src/main/java/de/pyryco/mobile/ui/conversations/components/RelativeTime.kt
package de.pyryco.mobile.ui.conversations.components

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
// + the LocalDate.Format builders, MonthNames, kotlin.time.Duration extensions

internal fun formatRelativeTime(
    instant: Instant,
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String { /* verbatim move of the existing body */ }
```

Then in `ConversationRow.kt`: delete the `private fun formatRelativeTime`, delete the now-unused `private val sameYearFormat` / `private val crossYearFormat` (they move with the function), delete the now-unused imports. The single call site (`Text(text = formatRelativeTime(conversation.lastUsedAt))` at line 102) now resolves to the same-package internal helper — no import change needed.

This is the only change to `ConversationRow.kt`. Do not touch `condenseWorkspace`, the `ListItem` body, the previews, or anything else in the file.

### Strings

Edits to `app/src/main/res/values/strings.xml`:

**Delete:**
- `recent_discussions_pill_label` — only consumed by `RecentDiscussionsPill`, which is being deleted.
- `cd_recent_discussions_pill` — same.

**Add (4 new strings):**
- `recent_discussions_section_header` — `Recent discussions` — used by `RecentDiscussionsSection`'s header.
- `see_all_discussions_label` — parametrised, `See all discussions (%d)` — used by the See-all `Text` (the trailing arrow is a separate `Icon`, not part of this string; no Unicode arrow glyph in the string).
- `cd_see_all_discussions` — parametrised, `See all %d recent discussions` — content description for the See-all clickable row.
- `untitled_discussion` — `Untitled discussion` — fallback title in `DiscussionPreviewRow` when `conversation.name` is null/blank.
- `discussion_preview_placeholder_body` — `Tap to resume — message preview coming soon.` — body placeholder.
- `discussion_preview_workspace_scratch` — `scratch` — workspace label for `DefaultScratchCwd` conversations.

Net string count: −2, +6, so +4 net.

### Accessibility

- **See-all row.** Wrap in `Modifier.semantics(mergeDescendants = true) { contentDescription = stringResource(R.string.cd_see_all_discussions, totalCount); role = Role.Button }`. The `Text` and `Icon` then carry no individual `contentDescription` (`null` on the `Icon` is correct since the row's merged description names the action).
- **Preview rows.** The `clickable` modifier with `role = Role.Button` is sufficient; the merged semantics of the title + body + meta text reads naturally for TalkBack ("title, body, workspace · time, button"). No explicit `contentDescription` override.
- **Section header.** Plain `Text`, no semantic role — it's decorative/landmark copy.
- **Divider.** Decorative; no `contentDescription`. (M3's `HorizontalDivider` already excludes itself from semantics.)

### State + concurrency model

Unchanged. `ChannelListViewModel.state` remains a `StateFlow<ChannelListUiState>` with `SharingStarted.WhileSubscribed(5_000)`; the new `recentDiscussions` field is plain data on the existing emission. No new `viewModelScope` jobs, no new dispatcher choices, no new flows. The `onEvent`-side flow is unchanged — `RecentDiscussionsTapped` continues to be a no-op (the no-op-on-RecentDiscussionsTapped test asserts this and must continue to pass).

### Error handling

Unchanged. The `.catch { emit(Error(message)) }` covers both source flows; if either throws, the screen renders the Error branch and the Recent discussions section never renders. No new failure modes introduced — `DiscussionPreviewRow` cannot fail (pure rendering); the See-all click is identical to today's pill click.

### Testing strategy

**Unit (`./gradlew test`).** All assertions on `ChannelListUiState.Loaded(...)` and `ChannelListUiState.Empty(...)` are positional, so adding the `recentDiscussions` field forces a surgical update to every existing test that constructs those states. Required changes in `ChannelListViewModelTest.kt`:

- `loaded_whenSourceEmitsNonEmpty` — assertion becomes `Loaded(listOf(sampleChannel), recentDiscussions = emptyList(), recentDiscussionsCount = 0)`.
- `empty_whenSourceEmitsEmptyList` — `Empty(recentDiscussions = emptyList(), recentDiscussionsCount = 0)`.
- `loaded_carriesDiscussionsCount` — the test emits three discussions, so `recentDiscussions = listOf(d1, d2, d3)`, `recentDiscussionsCount = 3`. Choose `d1`/`d2`/`d3` with distinct `lastUsedAt` values to keep the assertion stable against any future re-sort.
- `empty_carriesDiscussionsCount` — `recentDiscussions = listOf(d1, d2)`, `recentDiscussionsCount = 2`.
- `discussionsCount_updatesReactively` — final assertion changes to `recentDiscussions = listOf(d1, d2, d3)` (the first three of the five emitted), `recentDiscussionsCount = 5`.
- `loadingPersists_untilBothFlowsEmit` — final assertion gains `recentDiscussions = emptyList()`.

**New unit tests** to add to `ChannelListViewModelTest.kt`:

- `recentDiscussions_isCappedAtThree` — emit 4 discussions with distinct, descending `lastUsedAt`; assert `recentDiscussions.size == 3` and the IDs match the top-3 by `lastUsedAt` desc (matches what the upstream sort emits first). Verifies the `.take(3)` slicing.
- `recentDiscussions_orderingFollowsUpstream` — emit 3 discussions in a specific order via `MutableSharedFlow`; assert `recentDiscussions` is identity-equal to the emitted list (the VM does not re-sort, only slices).

**Component-level (`./gradlew test`, Compose Preview).** No new instrumentation tests required for Phase 1 — the Preview surface (§ Previews) is the visual contract. The implementation should pass Android Lint and pass `./gradlew test` without instrumentation flakes.

### Previews

`ChannelListScreen.kt`: replace the existing six Previews with the following six (same coverage, updated state shapes):

- `ChannelListScreenLoadedPreview` (light) — channels + `recentDiscussions = emptyList()`, `recentDiscussionsCount = 0`. Section absent — no orphan header. Mirrors today's empty-pill preview.
- `ChannelListScreenLoadedWithDiscussionsPreview` (light) — channels + 3 sample discussions (varied `lastUsedAt` so the meta line shows `12m ago` / `2h ago` / `Yesterday`), `recentDiscussionsCount = 8`. Renamed from `…WithPillPreview`.
- `ChannelListScreenEmptyPreview` (light) — `recentDiscussions = emptyList()`, `recentDiscussionsCount = 0`. Centred "Tap + to start a conversation".
- `ChannelListScreenEmptyWithDiscussionsPreview` (light) — `recentDiscussions = listOf(...)` (1–3 items), `recentDiscussionsCount = 5`. Section + centred empty text both visible. Renamed from `…WithPillPreview`.
- `ChannelListScreenLoadedDarkPreview` (dark, `Configuration.UI_MODE_NIGHT_YES`) — same data as the light loaded-with-discussions preview but in dark theme. This is the canonical "matches Figma 15:8" preview.
- `ChannelListScreenEmptyDarkPreview` (dark) — `recentDiscussions = emptyList()`, `recentDiscussionsCount = 0`.

`DiscussionPreviewRow.kt`: ships with its own previews — one light and one dark — driven by a sample `Conversation` with `cwd = DefaultScratchCwd`, `name = "What's the safest way…"`, `lastUsedAt = Clock.System.now() - 12.minutes`. Two more named-conversation samples (`cwd = "~/Workspace/Projects/some-repo"`) in a separate preview to exercise the non-default-cwd workspace-label branch.

`RecentDiscussionsPill.kt`: file deleted; its single preview is deleted with it.

`ConversationRow.kt`: existing previews unchanged (the `formatRelativeTime` extraction is behaviour-preserving).

### Recomposition correctness

`DiscussionPreviewRow` takes a `Conversation` (stable — all fields are primitives or stable types) and a `() -> Unit` lambda. The lambda passed from `ChannelListScreen` is constructed inside the `LazyColumn` `item` block as `{ onRowClick(conversation.id) }`; that closes over a stable `String`. No instability concerns. The `LazyColumn` already keys channels by `it.id`; the recent-discussions `item` uses a constant key (`"recent-discussions-section"`) so the section as a whole is one stable child.

The `DiscussionPreviewRow` itself does not need a `key` — it's emitted in a non-lazy `Column` inside the section, bounded at 3 items, no reorder churn on typical state transitions.

## Constraints

- **Zero hardcoded literals** in colour, typography, or shape — everything via `MaterialTheme.colorScheme.*`, `MaterialTheme.typography.*`, `MaterialTheme.shapes.*`. The AC's reading mirrors #68: no `Color(0x…)` outside the palette, no `TextStyle(...)` outside `MaterialTheme.typography`. Dimension literals (`16.dp`, `12.dp`, `2.dp`, `4.dp`, `18.dp`, `6.dp`) are the normal Compose vocabulary and match the precedent specs (#57, #60, #68).
- **No new dependencies.** `Icons.AutoMirrored.Filled.ArrowForward` is in `material-icons-core` (already on the classpath via the existing `Icons.Default.Settings` / `Icons.Default.Add` usage). Do NOT add `material-icons-extended`.
- **No public API changes beyond `ChannelListUiState`.** `ChannelListEvent`, navigation events, repository interface, and `ConversationRow`'s signature are frozen.
- **Pill removal is total.** Delete `RecentDiscussionsPill.kt`, delete its two strings, delete the `import` in `ChannelListScreen.kt`, delete every call site. After the change, `grep -rn 'RecentDiscussionsPill\|recent_discussions_pill\|cd_recent_discussions' app/src/` must return zero matches.
- **No data-layer changes.** `Conversation` is unchanged, `ConversationRepository` is unchanged, `FakeConversationRepository` is unchanged. The "real previews" follow-up will revisit the data layer; this ticket does not.
- **`formatRelativeTime` is the only refactor.** Do not extract `condenseWorkspace`, do not generalise the workspace-label rule, do not introduce a shared `MetaLine` composable for ConversationRow/DiscussionPreviewRow's meta text. Each component renders its own meta — the rules differ enough (ConversationRow shows trailing time only; DiscussionPreviewRow shows workspace · time with a monospace span) that premature abstraction would only obscure both call sites.

## Open questions

- **Localisation of relative-time strings.** Currently `"just now"`, `"Yesterday"`, `"3m ago"` etc. are Kotlin literals inside `formatRelativeTime`. The extraction preserves that. If a localisation pass lands later, this becomes ~6 new `plurals` entries and a `Resources`-typed parameter. Out of scope here; flag a follow-up if/when translation work begins.
- **Placeholder body copy.** "Tap to resume — message preview coming soon." is my pick. The PO may have a preferred string — fine to swap during code review, the resource id is the contract. If swapped, the body still must be one short sentence that ellipses naturally at 2 lines.
- **See-all row click target.** The section currently emits the See-all click only on the small label-and-arrow row. The Figma's left padding (16dp from edge to label) is generous and the row is full-width clickable in this spec, which gives a wide hit area. If accessibility audit later wants the click target shaped tighter (e.g. only around the label + arrow), wrap the row contents in a `Box` and apply `clickable` to that inner `Box` instead — single-line change.
