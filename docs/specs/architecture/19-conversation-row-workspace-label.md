# Spec — Workspace label decoration on `ConversationRow` (#19)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt` — the existing composable. This ticket extends its `headlineContent` slot and adds a private path-shortener helper. **Do not touch** `formatRelativeTime`, `previewText`, the `supportingContent` slot, or the `trailingContent` slot. Read the existing import block — the new imports listed below must be additive only.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — the new constant `DefaultScratchCwd` lives at the bottom of this file (top-level, same package as `Conversation`). This is where you add the source-of-truth literal.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:280-294` — the two `seedDiscussion(...)` calls currently hard-code `cwd = "~/.pyrycode/scratch"`. Both replace with the new `DefaultScratchCwd` constant.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:253` — the only test that asserts on the scratch literal directly. Update to reference the constant so the source-of-truth invariant holds at the test boundary too.
- `docs/specs/architecture/17-conversation-row-composable.md` — the parent spec. Imports, preview shape, and style discipline (no explicit `style =` / `color =` arguments except where a slot needs to override the M3 default) all carry forward. The "no chips, no surfaces, no extra widgets when a `Text` suffices" rule from #17's "Layout" section applies here too — see "Label rendering" below.

## Context

`ConversationRow` (#17) renders a single channel/discussion row with name, last-message preview, and relative timestamp. This ticket adds a second decoration to the headline area: a condensed workspace label for any conversation whose `cwd` is **not** the default scratch path. The default case (the majority — discussions all live in scratch; channels often start there) renders no label, keeping the list quiet.

`#20` (sleeping-session indicator) follows this ticket and will layer a third decoration onto the same headline slot. The composition chosen here — a `Row` inside `headlineContent` with the name flex-weighted and decorations sitting at their natural width — is the precedent that ticket extends. Keep the seam clean.

## Design

### 1. Source-of-truth constant — `DefaultScratchCwd`

Append to `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt`:

```kotlin
/**
 * Sentinel `cwd` for conversations with no bound workspace.
 * Conversations whose `cwd` equals this value render without a workspace label.
 */
const val DefaultScratchCwd: String = "~/.pyrycode/scratch"
```

Placement rationale:

- **`data/model/` is the right layer** — the constant is a domain-level marker about the `Conversation.cwd` field, consumed by both UI (`ConversationRow`) and data (`FakeConversationRepository` seeds, repository tests). Putting it in `data/repository/` would force UI to depend on the repository package for a value that has nothing to do with repository behavior. Putting it in `ui/` would force the data layer to depend on UI. `data/model/` is the layer both already depend on.
- **Co-located with `Conversation`, not in a new file** — it's one line. A `WorkspacePaths.kt` would be premature; if `#20` or a later ticket adds more workspace-related constants, that ticket extracts.
- **Top-level `const val`, not inside a companion** — `Conversation` is a plain `data class` with no companion today; introducing one for a single sentinel is over-engineering. Top-level `const val` is the idiomatic Kotlin choice.

### 2. Replace the scratch-path literals — call-site cleanup

Three call sites currently hard-code `"~/.pyrycode/scratch"`. All three switch to `DefaultScratchCwd`. The constant import is `de.pyryco.mobile.data.model.DefaultScratchCwd` in each consumer file.

- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:282` — `seedDiscussion(id = "seed-discussion-b", cwd = DefaultScratchCwd, …)`
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:289` — `seedDiscussion(id = "seed-discussion-a", cwd = DefaultScratchCwd, …)`
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:253` — `assertEquals(setOf(DefaultScratchCwd), discussions.map { it.cwd }.toSet())`

**Do not** chase down the literal in older specs under `docs/specs/architecture/6-*.md` or vault notes under `docs/knowledge/codebase/6.md` — those are historical artefacts and are out of scope. The "single source of truth" invariant in AC #1 is a code-level claim, not a documentation claim.

**Out of scope: changing what `createDiscussion(workspace: String?)` writes.** That function currently stores `cwd = workspace ?: ""` (empty string when no workspace is passed). The path-shortener below treats `""` as no-label via its blank-fallback (see §4), so this works correctly at the UI without a behavior change in the data layer. Aligning `createDiscussion`'s default with `DefaultScratchCwd` is a separate ticket if anyone files it; AC #1 only requires *a* single source of truth for the constant, not that every code path emit it.

### 3. Label rendering — `Text` inside a `Row` in `headlineContent`

Replace the existing `headlineContent = { Text(displayName) }` with:

```kotlin
headlineContent = {
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
        val workspaceLabel = condenseWorkspace(conversation.cwd)
        if (workspaceLabel != null) {
            Text(
                text = workspaceLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
},
```

Why a plain `Text`, not a chip / surface / pill:

- **The constraint is "must coexist with the trailing timestamp and 1–2 line preview without forcing a layout change to other rows."** A chip (`AssistChip`, `SuggestionChip`) has its own padding, border, and min-height (`32.dp` per M3 spec); inserting one into `headlineContent` visibly raises the headline row's intrinsic height, which `ListItem` propagates to the row's overall height. A `Text` with a typography style is height-flat — it lays out at the same line height as the headline `Text` next to it. The without-label row and the with-label row end up the same height.
- **Sets the precedent for `#20`.** A sleeping-session indicator is conceptually an icon (e.g. `Icons.Outlined.Bedtime` at `16.dp`), not a chip. If this ticket locked in chip-shaped decoration, `#20` would either copy the chip pattern (visually wrong for an icon) or break the pattern (visually inconsistent). A `Row` of natural-height children — `Text` here, `Icon` next — composes both decorations cleanly into the same headline.
- **Matches the existing typographic restraint.** `#17` deliberately did not pass `style =` to its `ListItem` slots; the M3 defaults are the contract there. The label *does* override style (`labelMedium`) and color (`onSurfaceVariant`) — that override is intentional and necessary: without it, the label would inherit the headline's `titleMedium` and read as a second name rather than a secondary annotation. This is the only place in this ticket where overrides are warranted.

Why `labelMedium` and not `labelSmall`:

- The trailing timestamp slot (`ListItem`'s `trailingContent`) already lands at `labelSmall` (M3 default). Pinning the workspace label to `labelSmall` too would make it visually compete with the timestamp for the eye's attention at the same hierarchy level. `labelMedium` is one tier louder than the timestamp — heavier enough to read as a meaningful annotation tied to the name, light enough to obviously sit below the headline.

Width / overflow behavior:

- **Name has `Modifier.weight(1f, fill = false)`** — takes all remaining space in the `Row` after the label has claimed its natural width, but doesn't force itself to that full width (so very short names don't push the label to the right edge needlessly). Ellipsizes at 1 line if the name is long enough to crowd the label.
- **Label has `maxLines = 1` + `TextOverflow.Ellipsis`** — short labels (the AC's example `KitchenClaw`) render in full; pathological long single-segment paths (`some-very-long-monorepo-package-name-foo-bar-baz`) ellipsize. The label keeps its natural width when it fits; the name yields first.
- **`Arrangement.spacedBy(8.dp)`** — applies a fixed 8.dp gap only when both children render. When the label is absent (default-scratch case), there's only one child and the spacedBy contributes nothing — the headline reads exactly as it did before #19.

### 4. Path-shortening helper — `condenseWorkspace(cwd: String): String?`

Inline as a `private` top-level function inside `ConversationRow.kt`, following the same "private at the same file" precedent set by `#17` for `formatRelativeTime` and `previewText`:

```kotlin
private fun condenseWorkspace(cwd: String): String? {
    if (cwd == DefaultScratchCwd) return null
    val trimmed = cwd.trimEnd('/')
    val segment = trimmed.substringAfterLast('/')
    return segment.ifBlank { trimmed.ifBlank { null } }
}
```

Behavior table (informative — not separate test cases, see §Testing strategy):

| Input | Output | Notes |
|---|---|---|
| `"~/.pyrycode/scratch"` | `null` | Default scratch → no label (AC #2). |
| `"~/Workspace/Projects/KitchenClaw/"` | `"KitchenClaw"` | Trailing slash stripped, last segment extracted (AC #4). |
| `"~/Workspace/Projects/KitchenClaw"` | `"KitchenClaw"` | Same as above without trailing slash. |
| `"~/Workspace"` | `"Workspace"` | Single-level depth — still resolves to last segment. |
| `"~"` | `"~"` | No `/` to split on; `substringAfterLast` returns the whole string. Read as "home" — a sensible fallback. |
| `"/"` | `null` | Trims to `""`, segment is `""`, both blanks → null. Caller renders no label. Edge case; not user-facing in seed data, but worth defining. |
| `""` | `null` | Same blank-cascade as above. Covers `createDiscussion(workspace = null)` which writes `cwd = ""`. |
| `"/usr/local/bin"` | `"bin"` | Absolute path — same rule. |

Rule (one sentence): **strip trailing slashes, take the last `/`-segment; if that's blank, fall back to the whole trimmed string; if *that's* still blank, return `null` so the row renders no label.**

Why private and inline:

- Used by exactly one composable (this row). #20 doesn't need it. A hypothetical future "workspace picker" or "thread header workspace badge" could need it — when that lands, that ticket extracts. YAGNI applies: extracting a 5-line function for a hypothetical reuser is the same scope-creep trap `#17` avoided with `formatRelativeTime`.
- Pure function, no Compose state, no dispatcher concerns — safe to call inside composition (it'll re-run on each recomposition, like `previewText` already does).

Why the `ifBlank` cascade rather than a custom regex:

- The AC says "last non-empty path segment, strip trailing slash" — `trimEnd('/')` + `substringAfterLast('/')` is the literal Kotlin idiom for that rule. Reaching for `Regex` would be a code smell for a string operation this simple.
- `String.ifBlank` is the standard Kotlin null-coalescing pattern for "use this if not blank, else fall back" — chains read top-to-bottom in priority order.

### 5. Imports — additive only

Add to the import block (alphabetised insertion into existing groups):

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.DefaultScratchCwd
```

Notes:

- `dp` is already used in the `@Preview(widthDp = 412)` annotation as a literal `Int`, not as a `Dp` value — so `import androidx.compose.ui.unit.dp` may not be present yet. It is needed now for `8.dp` in the `Arrangement.spacedBy(8.dp)` call.
- `MaterialTheme` was explicitly listed as optional in `#17`'s spec. It becomes required here because the label overrides `style` and `color` via `MaterialTheme.typography.labelMedium` and `MaterialTheme.colorScheme.onSurfaceVariant`.
- **Forbidden, same as `#17`:** `Color(0x...)` literals, `androidx.compose.material3.AssistChip` / `SuggestionChip` / `Surface` (per Layout-rendering discussion above), `androidx.compose.material.icons.*` (no icon in this row yet — #20 introduces one).

After writing, verify with `grep -E 'AssistChip|SuggestionChip|Surface\(|Color\(0x' app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt` — must return empty.

### 6. Preview adjustments (AC #5)

The `#17` preview pair currently uses `cwd = "/Users/dev/projects/pyrycode-mobile"` for both previews — which means, post-#19, both previews would suddenly show a workspace label and the without-label state would lose preview coverage. AC #5 anticipates this and explicitly offers the architect the choice: "the existing preview pair from #17 continues to cover the without-label state **OR the architect adjusts the preview set to cover both dimensions explicitly**."

This spec takes the second option: adjust the two existing previews so they explicitly cover both label dimensions. **No third preview added** — keeps the file lean and the AC at "at least one with-label preview" with one of each.

In `previewConversation(name: String?, isPromoted: Boolean)`, the `cwd` argument should vary by `isPromoted` so each preview models the canonical case for its tier:

```kotlin
private fun previewConversation(name: String?, isPromoted: Boolean): Conversation =
    Conversation(
        id = "preview-1",
        name = name,
        cwd = if (isPromoted) "~/Workspace/Projects/pyrycode-mobile" else DefaultScratchCwd,
        currentSessionId = "session-1",
        sessionHistory = emptyList(),
        isPromoted = isPromoted,
        lastUsedAt = Clock.System.now() - 2.hours,
    )
```

This means:

- **"With message — Light"** (`isPromoted = true`, `name = "pyrycode-mobile"`) — channel bound to a project folder → renders the `"pyrycode-mobile"` workspace label next to the name. **This is the AC #5 with-label preview.**
- **"Without message — Dark"** (`isPromoted = false`, `name = null`) — discussion in scratch → renders no label. Continues to cover the without-label dimension from `#17`.

The cwd path also changes from `"/Users/dev/projects/pyrycode-mobile"` to `"~/Workspace/Projects/pyrycode-mobile"` so the rendered label matches the seed-data convention (channels in `FakeConversationRepository` use `~/Workspace/...` tilde-prefixed paths). The shortened result is the same (`pyrycode-mobile`) either way; the change is purely about preview/seed visual consistency.

Do **not** rename the existing preview functions or change their `@Preview` annotations. The names — `ConversationRowWithMessagePreview` and `ConversationRowWithoutMessagePreview` — describe the *message* dimension, not the *label* dimension; that's the deliberate two-dimensions-in-two-previews compaction.

## State + concurrency model

None. `condenseWorkspace` is a pure function called during composition, same shape as `previewText` and `formatRelativeTime` from `#17`. No `remember`, no `LaunchedEffect`, no coroutines.

The `workspaceLabel` value can be computed inline inside the `headlineContent` lambda (a local `val` allocation per recomposition — trivially cheap for a string operation on a short path). It does **not** need to be wrapped in `remember(conversation.cwd) { condenseWorkspace(...) }` — premature optimisation for a 5-line string transform, and `Conversation` is a stable type so the row only recomposes when the input itself changes anyway.

Recomposition correctness: `Conversation.cwd` is a `String` (stable to Compose). No action needed.

## Error handling

N/A — no I/O, no parse paths, no failure modes. The path-shortener's edge cases (`""`, `"/"`, `"~"`, multi-segment paths) are handled inline by the blank-fallback chain documented in §4.

## Testing strategy

Same posture as `#17`: the AC's only build gate is `./gradlew assembleDebug` (AC #6). No `ConversationRowTest.kt`, no `WorkspaceCondenseTest.kt`.

`condenseWorkspace` is `private` — testing it would require widening visibility to `internal`, which exposes a private implementation detail for testing alone (the same architectural smell `#17` flagged for `formatRelativeTime`). If a later ticket extracts the helper for genuine reuse, that ticket adds the test.

The `FakeConversationRepositoryTest.kt:253` edit is **not** a new test — it's a one-line update to an existing assertion to reference `DefaultScratchCwd` instead of the literal `"~/.pyrycode/scratch"`. This keeps the source-of-truth invariant honest at the test boundary (if someone later changes the constant, the test follows automatically rather than silently asserting a stale literal).

## Open questions

- **Label spacing tone.** Spec picks `8.dp` between name and label, `labelMedium` typography, `onSurfaceVariant` color. If the developer notices in the IDE preview that the label visually crowds the name or reads too quiet, a one-step adjustment to `12.dp` / `labelLarge` / `onSurface` (any one of these, not all) is acceptable without re-running through architect. The AC pins the rule (show/hide, where, what text), not the exact pixel.
- **Tilde expansion.** The shortener treats `~/Foo` as a sequence with `~` as the first segment and `Foo` as the second — so `condenseWorkspace("~/Foo")` returns `"Foo"`, **not** `"~/Foo"`. This is intentional (the AC's worked example `~/Workspace/Projects/KitchenClaw/` → `KitchenClaw` confirms the shape) but worth flagging. A user with `cwd = "~/code"` sees `code` as their label, which reads sensibly. If anyone ever wants `~` rendered as a literal home indicator on the row, that's a future ticket.

## Out of scope (do not implement)

- Sleeping-session indicator (`#20`) — even though it shares the same `Row`-in-headline slot, that's a separate composition step done by `#20`.
- Changing `createDiscussion(workspace: String?)` to write `DefaultScratchCwd` instead of `""` — out of scope, see §2.
- Updating older specs / vault notes (`docs/specs/architecture/6-*.md`, `docs/knowledge/codebase/6.md`) to reference `DefaultScratchCwd` — historical artefacts, not part of this diff.
- Tooltip / long-press to surface the full `cwd` path — out of scope per the ticket's "Technical Notes".
- A `WorkspacePicker` / `WorkspaceChip` shared component — extract only when a second consumer needs it.
- Tests for `condenseWorkspace` or `ConversationRow` rendering via `ComposeTestRule` — explicit non-goal, per §Testing strategy and the precedent in `#17`.
- i18n of the workspace label — labels are user-supplied path segments; no localisation needed.
- Visual indication of the workspace's "kind" (git repo vs. arbitrary folder vs. tilde-anchored) — Phase 0 scaffolding doesn't have that signal yet.
