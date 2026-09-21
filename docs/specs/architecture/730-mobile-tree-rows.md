# 730 — Host, workspace and conversation tree rows

Four stateless row composables for the mobile channel tree: a section header, a host row, a
workspace row and a compact conversation row. This slice draws them. #731 assembles them into
`ChannelListScreen`; nothing consumes them yet.

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt` →
  `ConversationRow` — the existing flat row: the `ListItem` + merged-semantics + ellipsis idiom, and
  the `cd_conversation_row` formatted-content-description pattern this slice mirrors. Stays as it is.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLine.kt` →
  `RelayLinkStatus.toLegVisual`, `PyrycodeLinkStatus.toLegVisual`, `ConnectionLegVisual`,
  `ConnectionLegCategory.color` — the one connection-leg mapping. All `internal` in this package, so
  the host row reuses them directly. Also the dot idiom: an 8.dp `Box` filled with `CircleShape`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/HostWorkspaceGroup.kt` →
  `HostWorkspaceGroup` — #729's projection. `displayName` is already resolved and is display text
  only; the workspace row takes that text and resolves nothing.
- `app/src/main/java/de/pyryco/mobile/ui/workspace/` → `workspaceDisplayName`,
  `MAX_WORKSPACE_LABEL_CHARS` — the shared display rule, and the existing 128-UTF-16-unit clamp this
  slice reuses as the bound on every daemon-authored name it renders.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSnapshot` —
  where a host's `displayName` comes from, and that it is nullable. Choosing what a nameless host
  reads as is #731's call; this row takes a non-null `String`.
- `app/src/main/java/de/pyryco/mobile/data/model/ConnectionStatus.kt` → `ConnectionStatus`,
  `RelayLinkStatus`, `PyrycodeLinkStatus` — the two legs the host row shows separately.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` →
  `ChannelsSectionHeader` — the existing private section header. Its tokens (`labelLarge`,
  `onSurfaceVariant` at 0.85 alpha) already match the Figma `Sidebar header`; the new shared header
  lifts exactly those tokens and takes its title as a parameter.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` — the codebase's existing
  chevron choice (`Icons.AutoMirrored.Filled.KeyboardArrowRight`), so the fold control matches.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt`, `Color.kt`, `Theme.kt` — `AppTypography` is
  stock M3 `Typography()`, so every Figma `M3/*` style maps to a `MaterialTheme.typography` slot; the
  dark scheme's literal hexes let the Figma's raw values be read back as token roles.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/ConversationRowTest.kt` →
  `ConversationRowTest` — the device-test shape to match, including its 48.dp touch-target assertion.
- `docs/knowledge/features/channel-list-screen.md` — the owning topic; the documentation stage folds
  this slice in (see **Documentation handoff**).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Node `15-8` hosts the mobile Sidebar adaptation `133-259`. Its `Channels` frame stacks four
components at rising indentation: a `Sidebar header` label (`M3/label/large`,
`Schemes/on-surface-variant` at 85% opacity) at the container edge; a `Host` row (server glyph at
indent 0, `M3/title/small` name at 24, a fold chevron after the name, and two 5px status dots pinned
to the trailing edge — the inner one named *Host connection status dot*, the outer one *Relay
connection status dot*); a `Workspace` row (open-folder glyph at indent 12, `M3/title/small` name at
34, fold chevron after the name); and a `Channel` row (a 5px ring-only *Status dot* at indent 16,
`M3/body/small` name at 30, 6px corner radius on the row fill). Both `Channels` sections instance the
same `Channel` component, so one conversation row serves channels and chats.

### Translation decisions, and where the render deliberately departs

The supplied drawing is a desktop sidebar rendered dark-only. Four departures, each deliberate:

1. **Row height.** The drawing's rows are 28/28/24dp. The three tappable rows render at
   `heightIn(min = 48.dp)` instead — the Android touch-target minimum, already asserted for the flat
   row by `ConversationRowTest`'s `channelRow_meetsMinimumTouchTargetHeight`. The visual hierarchy
   the heights carried on desktop is carried here by indentation, leading glyph and type scale
   (`titleSmall` for host/workspace, `bodySmall` for conversations), all of which are preserved. The
   section header is not tappable in this slice and stays compact.
2. **Selected fill.** The drawing highlights two conversation rows: the brighter one
   (`Schemes/primary-container`) is hover with the #665 pencil, the plainer one
   (`Schemes/on-primary`, `#003355`) is selection. The phone has no hover, so only the plain
   treatment is drawn. Taken literally, `onPrimary` is `#FFFFFF` in the light scheme and would
   vanish, failing AC#5. The row instead fills with `primaryContainer` at `SelectedFillAlpha`,
   composited over whatever surface the list draws — which in the dark scheme lands within a shade of
   the drawn `#003355`, in the light scheme gives a visible soft-blue highlight, keeps the drawing's
   own `onSurface` text token legible against both, and leaves full-opacity `primaryContainer` free
   as the brighter tier #665 will need.
3. **Glyphs.** The drawing uses FontAwesome solids (`server-solid-full`, `folder-open-solid-full`,
   thin chevrons). The project ships no icon drawables for these and depends on
   `material-icons-extended`, so each maps to its Material equivalent: `Icons.Filled.Dns`,
   `Icons.Filled.FolderOpen`, and `Icons.AutoMirrored.Filled.KeyboardArrowRight` /
   `Icons.Filled.KeyboardArrowDown` for the fold pair. No new drawable assets.
4. **Status dots.** The drawing's dots are 5px circles: ring-only (stroke `#9DCBFC`, which is the
   dark scheme's `primary`) on the conversation row, filled green on the host row. They render at the
   package's existing 8.dp dot size — 5px is below legibility on a phone — as a `primary`-bordered
   ring and as filled circles whose colour comes from `ConnectionLegCategory.color`.

## Context

The channel list has only `ConversationRow` and `DiscussionPreviewRow`, both flat. The tree the
supplied design draws needs four new row shapes at four indentations. This slice adds the
composables and nothing else: no view-model change, no screen change, no consumer. Splitting drawing
from assembly keeps the visual-fidelity review (this slice) separate from the projection-to-rows
wiring (#731).

No ADR is warranted — this adds presentation components inside an established package and introduces
no new architectural choice.

## Design

One new file, `ui/conversations/components/ConversationTreeRows.kt`, holding the four public
composables plus the private helpers they share. `ConversationRow` and `DiscussionPreviewRow` are
untouched; `DiscussionListScreen` still uses the former.

### Contracts

```kotlin
@Composable fun TreeSectionHeader(title: String, modifier: Modifier = Modifier)

@Composable fun TreeHostRow(
    hostName: String,
    connectionStatus: ConnectionStatus,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
)

@Composable fun TreeWorkspaceRow(
    workspaceName: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
)

@Composable fun TreeConversationRow(
    conversationName: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Every parameter is either display text the caller resolved, a flag the caller owns, or a callback.
No row takes a `serverId`, a `cwd`, a `Conversation` or a `HostWorkspaceGroup`: a row decides nothing
about which host or workspace it belongs to and resolves no name of its own. The host row's
`connectionStatus` is the already-parsed `ConnectionStatus` the supervisor produces — the row parses
no wire data.

### Shared private helpers

- `TreeRowIndent` — the four start indents as private `dp` constants (0 / 0 / 12 / 16), mirroring the
  Figma's absolute positions within its `Channels` container. The horizontal screen gutter is #731's
  to add on the container; each row carries only its own tree indent.
- `boundedRowText(raw: String): String` — clamps a daemon-authored name to
  `MAX_WORKSPACE_LABEL_CHARS` before it reaches either a text node or a formatted content
  description. See **Security review** finding 1.
- `FoldChevron(expanded, contentDescription)` — the fold indicator; `KeyboardArrowRight` when
  collapsed, `KeyboardArrowDown` when expanded, so the two states differ by glyph and not by colour
  or rotation alone.
- `LegDot(visual: ConnectionLegVisual)` — an 8.dp circle filled with `visual.category.color()`,
  carrying `clearAndSetSemantics { contentDescription = visual.contentDescription }`. Both dots come
  from the existing `toLegVisual` mapping; this slice derives no second mapping.
- `TreeRowStatusDot()` — the conversation row's leading slot, drawn only in the idle ring treatment.
  It takes no state parameter; #668 introduces that state and the precedence behind it.

### Layout and truncation

Each row is a `fillMaxWidth` `Row` at `heightIn(min = 48.dp)`, start-padded by its indent and
end-padded by 8.dp, laid out as: leading glyph → name + fold chevron group → trailing content. AC#2
is met structurally: the name-plus-chevron group takes `Modifier.weight(1f)` so it absorbs all
leftover width, and the name inside it takes `Modifier.weight(1f, fill = false)` with `maxLines = 1`
and `TextOverflow.Ellipsis`. A long name therefore ellipsizes and cannot push the chevron or the
trailing indicator pair past the row's trailing edge.

The host row's trailing pair renders in the drawing's order — the pyrycode leg's dot inboard, the
relay leg's dot outboard — spaced 6.dp apart.

### Accessibility

- The three tappable rows are `Modifier.clickable(...)`. `clickable` merges descendants, so the row
  announces as one node while each child's description survives into the merged text; the device
  test reads the individual dots through `useUnmergedTree = true`.
- Neither the host nor the workspace row sets an overriding `contentDescription` on the row. Doing so
  would replace the leg dots' descriptions and break AC#4. The fold action is named through
  `clickable`'s `onClickLabel`, and the chevron carries the same string as its own
  `Icon(contentDescription = …)`, so the control has an accessible name in both the merged and the
  unmerged tree. Nothing depends on a pointer hovering.
- Two new `cd_*` strings, beside the existing entries in `strings.xml`:
  `cd_tree_row_expand` (`"Expand %1$s"`) and `cd_tree_row_collapse` (`"Collapse %1$s"`), shared by
  the host and workspace rows.
- The conversation row carries `semantics { selected = … }` and `Role.Button` rather than a
  bespoke string, so selection is announced by the platform.
- The section header carries `semantics { heading() }`.

### Out of scope, by ticket

The host row's edit control (#642), the section header's and host row's add controls (#732, with
#664 behind the latter), the conversation row's edit content (#665), the disconnected-host repair
control (#675), live indicator accuracy and the conversation row's unread/activity state (#668).
None are drawn, and no placeholder space is reserved for them — a trailing slot held empty would
read as a rendering fault today. Each of those tickets adds its own trailing content to the row it
owns.

## State + concurrency model

None. All four composables are stateless and side-effect free: no `remember` of mutable state, no
`LaunchedEffect`, no `DisposableEffect`, no coroutine, no flow collection, no `Context` access. Every
parameter is stable (`String`, `Boolean`, the `ConnectionStatus` data class over sealed leg types,
and lambdas the caller hoists), so recomposition is driven only by a real input change.

## Error handling

No failure modes: nothing here does I/O, parses, or can throw. The one defensive measure is the
length clamp on daemon-authored names, which is unconditional rather than a failure path. A blank
name is the caller's problem — #731 decides what a nameless host reads as.

## Testing strategy

Device test only; there is no pure logic to unit-test, and the connection-leg mapping these rows
reuse is already covered by its own tests. One new file,
`app/src/androidTest/.../ui/conversations/components/ConversationTreeRowsTest.kt`, matching
`ConversationRowTest`'s shape (`createComposeRule`, `PyrycodeMobileTheme`, string resources read
through `InstrumentationRegistry`):

- The section header renders its title as a heading.
- A host row with a 300-character name keeps its trailing indicator pair displayed and within a
  fixed-width container — AC#2's "not pushed past the trailing edge", proven positionally rather
  than by inspecting the ellipsis.
- The same, for a conversation row's name against its row bounds.
- A collapsed host row announces the expand label and an expanded one announces the collapse label;
  tapping the row invokes `onToggleExpanded` — AC#3, touch only.
- A host row exposes two distinct leg descriptions from `toLegVisual`, one per leg, read through
  the unmerged tree — AC#4.
- The workspace row's fold control does the same as the host row's.
- A selected conversation row asserts selected and an unselected one asserts not-selected; tapping
  invokes `onClick`.
- Each of the three tappable rows is at least 48.dp tall.

AC#1 and AC#5's light/dark fidelity are proven by `@Preview`s rather than assertions — a Compose test
cannot read a colour. One preview composable renders the whole tree (header, collapsed host,
expanded host, workspace, and conversation rows both plain and selected), instanced twice for light
and dark, and compared against the Figma screenshot before the PR opens.

This slice ships no operator-facing flow — the rows are unreachable until #731 assembles them — so no
rung-3 real-claude scenario is due here. #731 owns that.

## Open questions

1. Does `Icons.Filled.Dns` exist in the pinned `material-icons-extended`? If not, fall back to
   `Icons.Filled.Storage`. Resolved at first compile; recorded under `## Revisions` if it changes.
2. Does the drawing's trailing dot order (pyrycode inboard, relay outboard) survive review, given the
   phone→relay→server chain reads the other way? Following the Figma layer names as drawn; the pair's
   meaning and live accuracy are #668's, and the content descriptions name each leg regardless of
   order.

## Documentation handoff

Pending, for the documentation stage. Fold the new row components into
`docs/knowledge/features/channel-list-screen.md`, in a section describing the tree's rows, recording
that each row is stateless, that daemon-authored text reaches text nodes and formatted content
descriptions only, and that the host row's indicator pair reuses the existing connection-leg mapping
rather than repeating it.

That file is 37993 bytes at `11bc809` against the 50000-byte cap `scripts/docs-guard.sh` enforces,
and #731 and #732 fold into it too. If the section will not fit, split a new topic document out the
way #729's documentation stage split `channel-list-viewmodel-projection.md` off
`channel-list-viewmodel.md`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] **MUST FIX — addressed by this revision.** This slice is a new render path for
  daemon-authored text: a host's name, a workspace's display name and a conversation's name all cross
  from the wire into Compose here. Only one of the three arrives bounded —
  `workspaceDisplayName` clamps to `MAX_WORKSPACE_LABEL_CHARS`, and the protocol is explicit that its
  own 128-byte limit is a size limit and not a safety property. `HostConversationSnapshot.displayName`
  and `Conversation.name` reach these rows unclamped, and `maxLines = 1` bounds only what is *painted*:
  Compose still measures the whole string, and the fold control's formatted content description would
  hand the whole string to TalkBack. A hostile or buggy daemon supplying a multi-megabyte name is an
  ANR on the list screen. The design now clamps every name through `boundedRowText` at the top of
  each composable, and uses the clamped value for both the text node and the formatted description —
  the same bound the workspace rule already applies, so a protocol-conformant name is never touched.
- [Trust boundaries] No further findings — the boundary is explicit and singular. Each row takes
  display text only: no `serverId`, no `cwd`, no `Conversation`. Nothing here keys a list, builds a
  path, a filename, a URL or a cache key from a name, and nothing renders one as markup. The
  `ConnectionStatus` the host row takes is a parsed sealed type produced by the supervisor, not
  relay-authored content.
- [Tokens, secrets, credentials] Not applicable by design — no row takes a credential, a token, a
  key or a server identity. The rows are pure presentation over text the caller already resolved.
- [File / storage operations] Not applicable — no file, `DataStore`, preference or cache access on
  any path in this slice.
- [Inter-process / Android attack surface] Not applicable — no `Activity`, `Service`, receiver,
  intent filter, deep link or `PendingIntent`. Named explicitly because it is the tempting shortcut:
  daemon-authored names reach `Text` only. No `WebView`, no `AndroidView`, no `AnnotatedString`
  built from a name, and no `MarkdownText` — that component exists in this same package and routing
  a name through it would be a markup-injection surface.
- [Cryptographic primitives] Not applicable — no randomness, hashing, key handling or comparison.
- [Network & I/O] Not applicable — the composables perform no I/O and open no connection. They
  render a `ConnectionStatus` someone else produced.
- [Error messages, logs, telemetry] Design decision: this file emits **zero** log calls. `RelayLog`
  is used freely elsewhere in the neighbouring packages, and a per-row lifecycle log would put
  daemon-authored host, workspace and conversation names into Logcat, readable over ADB. There is no
  lifecycle event worth logging in a stateless row, so the rule costs nothing.
- [Concurrency] Not applicable in the coroutine sense — nothing is launched, so nothing needs a
  cancellation path. One recomposition observation: `RelayLinkStatus.Reconnecting` carries a
  `secondsRemaining` that ticks, so a host row bound to it recomposes each second even though
  `toLegVisual` maps every `Reconnecting` to one constant visual. The row is cheap and this slice has
  no consumer; if it matters once #731 holds many host rows, that is a `derivedStateOf` on the
  projection side, not a change here.
- [Threat model alignment] The hostile-daemon-frame threat is the live one and is addressed above:
  oversized text is clamped, and every name renders as text. The malicious-relay threat does not
  reach this slice — the relay is content-blind and cannot author a name inside the Noise session.
  UI-side leakage (screenshots, accessibility-service eavesdropping, overlays) is real for a screen
  showing conversation names, but it is an app-wide window-flag decision, not a row's; out of scope
  here and unowned by any ticket this slice depends on.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21
