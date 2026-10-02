# ChannelInfoSheet

Stateless M3 modal bottom sheet (#217) that surfaces channel-level metadata and entry points for channel actions. Aligns with the dark Figma `20:48` reference inspected on 2026-09-29: a title row, an **About** section with a read-only **Folder** path, a **Session** section (#1346) showing the agent's reported version, permission mode and Claude's own cost estimate, a **Memory** section showing the current session's provider report, an **Actions** section, and a footer that long-press-copies the channel ID. The reference's Workspace row and Change workspace button conflict with the 2026-09-28 product decision and are omitted. The **thread-overflow** host landed in [#226](../codebase/226.md) (tap overflow → **Channel info** opens the populated sheet); the **Channel List long-press** entry point is still open. Memory search retrieves stored knowledge; provider detection does not mean the plugin captures this conversation or restores earlier context.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `ChannelInfoSheet.kt`. Second **sheet** in that package alongside [`WorkspacePickerSheet`](./workspace-picker-sheet.md).

## Shape

```kotlin
internal data class ChannelInfoUiModel(
    val conversationName: String,
    val workspacePath: String,
    val createdLabel: String,
    val lastActivityLabel: String,
    val sessionCount: Int,
    val messageCount: Int,
    val memorySearch: MemorySearchReport,
    val channelId: String,
    val agent: ConversationAgent = ConversationAgent.Claude,
    val sessionFacts: SessionFacts? = null,
    val sessionCostUsd: Double? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelInfoSheet(
    model: ChannelInfoUiModel,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onInstallMemoryPlugin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    mutationsSupported: Boolean = true,               // new in #508 — false in relay mode hides the whole Actions section
    systemPrompt: SystemPromptEditorState? = null,     // new in #1342 — null omits the System prompt section (preview/test seam)
    onSystemPromptChange: (String) -> Unit = {},
    onSystemPromptSave: () -> Unit = {},
    onSystemPromptClear: () -> Unit = {},
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
)
```

- **`mutationsSupported` ([#508](../codebase/508.md))** is defaulted `= true` and forwarded verbatim into the `ChannelInfoSheetContent` body (which also takes a trailing `mutationsSupported: Boolean = true` — its gate site). It sits **after** `modifier` on the shell (before the also-defaulted `sheetState`) to satisfy Lint `ComposeParameterOrder` (defaulted-after-`modifier`; see [[compose-parameter-order-lint-defaulted-after-modifier]]) — `ChannelInfoSheetContent` has no `modifier` param, so its trailing defaulted param is fine. The default is a preview/test seam only — production threads `state.mutationsSupported` from [`ThreadScreen`](thread-screen.md). **It is a param, deliberately not a `ChannelInfoUiModel` field** — the model is pure display content; a capability flag is a separate concern, and putting it on the model would drag `toChannelInfoUiModel()` + `ThreadScreenMapperTest` into scope for no benefit.
- **`systemPrompt` and its three callbacks (#1342)** sit after `mutationsSupported` for the same `ComposeParameterOrder` reason, and are independent of it — the System prompt section renders for every conversation, never gated on `mutationsSupported`, matching desktop. `systemPrompt: SystemPromptEditorState? = null` defaults to omitting the section entirely (a preview/test seam); production always passes a value. See [System prompt section](#system-prompt-section) below.

- **`internal`** — consumed only within the module; same posture as [`WorkspacePickerSheet`](./workspace-picker-sheet.md) / `ThemePickerDialog`.
- **`sheetState` defaulted but exposed** — host (follow-up ticket) needs to drive `sheetState.hide()` for animated-close before invoking `onDismiss`; defaulting keeps the preview / one-off uses one-line. `skipPartiallyExpanded = true` because the content height is bounded.
- **No formatting in the composable.** `createdLabel` / `lastActivityLabel` arrive pre-rendered from the caller — the [#226](../codebase/226.md) host computes them via the app-wide `formatRelativeTime` helper: last-activity from `Conversation.lastUsedAt`, created from the **earliest collected `ThreadItem`'s timestamp** (there is no persisted `createdAt` on `Conversation` — deferred to a schema ticket). Both render the em-dash `"—"` when their source is absent. Numeric counts are formatted as `Int.toString()` at the call site inside the row.
- **No nullable callbacks.** Rendered controls have wired callbacks. The Install button appears only for confirmed absence; `ThreadScreen` opens `MEMORY_PLUGIN_DOCS_URL` through `LocalUriHandler` when tapped.

A peer `internal` composable `ChannelInfoSheetContent(model, callbacks...)` carries the body; `ChannelInfoSheet` is the `ModalBottomSheet` shell that delegates into it. Same shell + `*Content` split as [`WorkspacePickerSheet`](./workspace-picker-sheet.md) — previews target the content because the modal scrim + animation machinery don't render in the IDE preview pane.

## What it does

Single scrollable `Column(fillMaxWidth)` inside the `ModalBottomSheet`:

1. **`TitleRow(title = model.conversationName, onClose = onDismiss)`** — `Row(fillMaxWidth.padding(start = 16, end = 4, top = 4, bottom = 12))` with weighted `Text(titleLarge, onSurface)`, capped at two lines with ellipsis, + trailing `IconButton(Icons.Filled.Close, contentDescription = "Close", tint = onSurfaceVariant)`.
2. **`SectionHeader("About")`** — `labelLarge` on `onSurfaceVariant`, `padding(start = 24, end = 16, top = 12, bottom = 4)`. Identical helper shape to `WorkspacePickerSheet.SectionHeader`; deliberately not extracted into a shared file in this ticket — touch only this file.
3. **Five `AboutRow`s**: read-only `Folder` (with `valueIsPath = true`), `Created`, `Last activity`, `Total sessions`, `Total messages`. The model retains the `workspacePath` field name as its folder-path source. See "Row contracts" below.
4. **`SessionSection(model)`** (#1346) — `SectionHeader("Session")` then two or three rows. See "Session section" below.
5. **`SectionHeader("Memory")`** then **`MemoryRow(model.memorySearch, onInstall)`**. See "Memory row" below.
6. **`SectionHeader("System prompt")`** then **`SystemPromptSection(systemPrompt, ...)`** (#1342) — only when `systemPrompt != null`. Sits here regardless of `mutationsSupported`: desktop shows this section for every conversation, and `set_system_prompt` is not one of the gated mutations. See "System prompt section" below.
7. **`SectionHeader("MCP servers")`** then **`McpServersSection(status, onReconnect, onToggle)`** (#1344) — only when `model.mcpServers != null`. Sits after System prompt and before Actions, desktop's position. See "MCP servers section" below.
8. **`SectionHeader("Actions")`** then **`ActionsGrid`** — equal-width `Rename` and `Archive` cells above full-width `Delete`. Each cell is a `FilledTonalButton` with one-line text. The section is hidden when `mutationsSupported` is false; About, Session, Memory, System prompt, MCP servers and the Channel-ID footer remain visible. No workspace action or Settings storage section appears.
9. **`Footer(channelId)`** — see "Footer + clipboard" below.
10. **`Spacer(height = 24.dp)`** — bottom inner padding above the system-inset that `ModalBottomSheet` already applies. The body scrolls vertically so actions and footer remain reachable with enlarged text.

With the Session (#1346), System prompt (#1342) and MCP servers (#1344) sections present, Actions and the footer sit below the fold on the Robolectric default viewport, and on-device. Any screen test or live test that taps Rename, Archive or Delete must `performScrollTo()` on that node first — a bare `performClick()` lands outside the sheet and nothing is recorded. `ThreadScreenChannelInfoTest` and `InteractiveStreamE2ETest#interactiveTurn_deleteConversation_removesFromListAndClosesThread` both needed this fix; the live method's miss showed up as a 30-second wait for a confirm dialog that never opened.

### Row contracts

| Helper | Layout | Visual |
|---|---|---|
| `AboutRow(label, value)` | `Row(fillMaxWidth.padding(horizontal = 16, vertical = 8))` with equal weighted label and value; the value has 8 dp start padding | Right value right-aligns, wraps to two lines, then ellipsizes; numeric values render as `Int.toString()` at the call site |
| `AboutRow(label, value, valueIsPath = true)` | Same row, with an intrinsic-width `Folder` label and a weighted value using the remaining width after 16 dp spacing | Right text is `bodyMedium.copy(FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)` on `onSurfaceVariant`, `maxLines = 1`, `overflow = TextOverflow.StartEllipsis`, `TextAlign.End` — start-ellipsis keeps the leaf visible while the wider slot avoids truncating a path that fits |

### Session section (#1346)

Mirrors desktop's `ChannelInfoSheetView` Session block, drawn after About with the sheet's own `SectionHeader`/`AboutRow`-shaped `SessionRow` (no frame in Figma `20:48` draws it — the ticket says so, and the section reuses existing geometry and theme tokens only). It always renders, even with `model.sessionFacts == null` — only the cost row is conditional on `model.sessionCostUsd != null`. That asymmetry is why the section pushes Actions down by a fixed amount regardless of whether facts have arrived yet; see "A test that clicks below About scrolls first" below.

- **Version row**, labelled `"Claude version"` or `"Codex version"` by `model.agent` (`ConversationAgent.Codex` vs. everything else).
- **`"Reported permission mode"` row.** This is `SessionFacts.permissionMode` — Claude's own claim about what mode it is running under. It reaches this row only; `ThreadViewModel`'s `runningModel` flow (see [Thread composer footer](thread-composer-footer.md)) deliberately never reads it, and it is carried on `ThreadUiState.reportedSessionFacts`, **not** `ThreadRunConfig` — the Status sheet's permission control and the composer footer read `runConfig.permissionMode` (the settings reading) and can never see this claim. A `ThreadViewModel` test (`reportedFacts_reachTheStateButNotThePermissionReading`) pins the separation.
- **`"Cost (Claude's estimate)"` row**, present only when `model.sessionCostUsd != null`, showing `formatSessionCost(cost)` → `"$0.42 est."` (`%.2f`, `Locale.ROOT` — desktop's `toFixed(2)`). The "est." wording and the parenthetical label are load-bearing: the figure is Claude's own unverified running-session total from `turn_end.cost_usd_total` (see [Live-session events § `TurnEnd`](live-session-events.md)), never summed, never the app's accounting.

Both reported strings go through `internal fun reportedSessionValue(raw: String?, flaggedTruncated: Boolean): ReportedSessionValue` before reaching `Text`: ISO-control and Unicode-format code points (bidi overrides included) become spaces, the result is trimmed, then cut at 256 code points via `offsetByCodePoints` so a cut never splits a surrogate pair. An all-control or empty result reads as `text = null` → `SessionRow` shows `"Not reported"`. `truncated` is `true` when this cut fired *or* when `SessionFacts.truncatedFields` names the field (`claude_code_version` / `permission_mode`, matching the protocol's `session_facts` table) — either one shows a `"Truncated"` `labelSmall` line under the value. The cost row builds its `ReportedSessionValue` directly (`truncated = false`) since a formatted dollar figure is never cut. Same inert-rendering shape as [`UsageLimitIndicator`](usage-limit-indicator.md)'s `usageLimitStatusLabel`, independently implemented here because the cut length and format-character rule differ — `CHANNEL_INFO_AGENT_VERSION_TAG` / `CHANNEL_INFO_SESSION_COST_TAG` `testTag`s are the device suites' handles onto the two values.

**A test that clicks below About in `ChannelInfoSheetContent` scrolls to it first.** At Robolectric's 320×731dp, the always-drawn Session header and its two "Not reported" rows push Actions' "Delete" off-viewport, and `performClick()` on an off-screen node misses. `ThreadScreenChannelInfoTest.tapping_delete_emits_delete` and step 7 of `InteractiveStreamE2ETest.interactiveTurn_deleteConversation_removesFromListAndClosesThread` both call `performScrollTo()` before the click now; the plan's `## Revisions` records this as the contract going forward. As of landing, the sheet's `Install`/`Rename`/`Archive` taps in `ThreadScreenChannelInfoTest` do **not** yet follow the contract — they still pass because nothing pushes them further down, but a later section inserted above them (desktop parity work tends to add one) would need the same `performScrollTo()` fix.

`CLAUDE_CODE_VERSION_FIELD` is a private const duplicated between this file and `ThreadViewModel.kt` (its `runningModel`'s own `reportedText` cut uses the identical field name) — left unshared on purpose, since sharing it would add a dependency from the view model on this file for one string literal.

### Memory row

Outer `Row(fillMaxWidth.padding(horizontal = 16, vertical = 8), Arrangement.SpaceBetween)` keeps the `Memory plugins` label and right-aligned value. It branches on the report rather than the length of a plugin list:

- **Confirmed absent, with no installed provider** — `None` and the `Install` text button. Below 360 dp width or at font scale 1.3 and above, `None` sits above the button in the right slot. The button opens the shared memory-plugin docs URL.
- **Providers reported** — one right-aligned name and status per provider. The daemon's display name is inert text, capped at 120 characters and two lines with ellipsis; a blank name becomes `Unnamed provider`. Status may wrap to two lines. `Not installed` and `Disabled` take precedence over effective availability. Only installed, enabled and available renders `Memory search available`; installed and unavailable renders `Memory search unavailable`, and a remaining unknown state renders `Status unknown`. A listed provider never gets an Install control.
- **No providers, without confirmed absence** — `Status unknown` for unknown or omitted reports; otherwise `Memory search unavailable`. Neither state says `None` or offers Install. An omitted report defaults to `MemorySearchReport.Unknown` upstream.

### System prompt section

Private `SystemPromptSection(state: SystemPromptEditorState, onChange, onSave, onClear)` (#1342), desktop's
`SystemPromptSectionView`, mounted by `ThreadViewModel` constructing a
[`SystemPromptEditor`](system-prompt-editor.md) each time the sheet's `ThreadEvent.ChannelInfo`
opens it, and dropping it to `null` on every path that closes the sheet (dismiss, Archive, Delete) — so
every open gets a fresh read, never a stale one from a previous conversation or a previous open of the
same one. The three states:

- **`Loading`** — `SYSTEM_PROMPT_LOADING` ("Reading the stored prompt from the daemon"), `bodySmall` on
  `onSurfaceVariant`. No field, no buttons.
- **`Unavailable`** — a failed read: `SYSTEM_PROMPT_UNAVAILABLE` ("Couldn't read the stored system
  prompt."). No field, no buttons, no retry — the sheet has to be closed and reopened for another attempt.
- **`Loaded`** — in order: the hint line (`SYSTEM_PROMPT_HINT`), a `BasicTextField` (`testTag`
  `CHANNEL_INFO_PROMPT_FIELD_TAG`, `contentDescription` "System prompt for this channel") on a filled well
  shaped like Edit channel's prompt field — `MaterialTheme.shapes.modalControl` corners, `PROMPT_MIN_LINES`
  (4) and `PromptWellHeight` (112 dp) shared from `ui/components/ChannelFormFields.kt` so the two wells
  cannot drift apart, but on the sheet's own `surfaceContainerHighest` rather than the modal's
  `modalFieldContainer` token, because that token is keyed to the modal surface, not this sheet's; the
  byte count ("N / 8192 bytes", `error` colour over the limit); the over-limit line when over
  `SystemPromptLimit.MAX_BYTES`, and the field itself then carries `error(...)` semantics so TalkBack
  announces the over-limit state, the same as Edit channel's prompt field; the differs line when
  `appliedStatus == SessionPromptStatus.Differs`; the write line (`writeLine()`: "Saving" while `saving`,
  the Saved line once `saved`, else a `SystemPromptRefusal.line()` when `saveFailed`); then two equal-width
  `FilledTonalButton` cells, Save and Clear, `enabled = canSave` / `canClear` respectively.

All copy is desktop's `SystemPromptSection.tsx` verbatim, held in private `const val`s (not inline
literals, unlike the rest of this file) because the write line and the field's over-limit semantics both
read the same strings. `SystemPromptRefusal.line()` is desktop's `WRITE_REJECTED`: `Malformed` → "Not
saved: the daemon refused the request."; `NotFound` → "Not saved: the daemon has no record of this
channel."; `Unclassified` → "Not saved: the daemon refused the write." See
[System prompt editor](system-prompt-editor.md) for `canSave`/`canClear`, the refusal classification and
the write lifecycle; see [ChannelListViewModel § Wiring](channel-list-viewmodel.md#wiring) for the
companion Edit channel clear rule.

### MCP servers section

Private `McpServersSection(status: McpStatus, onReconnect, onToggle)` (#1344, new file
`McpServersSection.kt`), desktop's `McpServersSectionView`. The section shows only while the thread's
session reports the `mcp_servers` capability as other than `false` — `SessionCapabilitiesDto.mcpServers`
(`@SerialName("mcp_servers")`) defaults to `true`, as `slash_commands` does, so a daemon that never sends
the key still gets the section. `ThreadRunConfig.mcpServersSupported` is the one reading both
`ThreadViewModel` and `toChannelInfoUiModel` use; Codex sessions report `false` and get no section and no
request. `ChannelInfoUiModel.mcpServers` is `null` to hide the section — `ThreadScreen.toChannelInfoUiModel`
sets it to the current `McpStatus` only when `mcpServersSupported`, else `null`.

**Asking on open.** `ConversationRepository`'s MCP reading (#1343) starts empty on every new connection, so
the section has to ask for it. Every real opening of the sheet — `ThreadEvent.ChannelInfo`, inside the
`pendingChannelInfo.compareAndSet(false, true)` guard that also mounts the System prompt editor and rereads
settings — calls `repository.requestMcpStatus(conversationId)`, but only when `mcpServersSupported`; a
second `ChannelInfo` event while the sheet is already open asks nothing. `ThreadViewModel.mcpStatusReading`
is one more arm on the `state` combine chain, `observeMcpStatus(conversationId).onStart { emit(McpStatus()) }`
collected under the same `WhileSubscribed(5_000)` lifecycle as every other reading.

**Asking again on open and on every reconnect ([#1345](https://github.com/pyrycode/pyrycode-mobile/issues/1345)).**
The Channel info ask above only fires while the sheet is open. #1345's [Top overlay MCP-failure
pill](thread-top-overlay.md#the-failed-mcp-server-pill-1345) needs a current reading even when the sheet has
never been opened, and desktop keeps its last report across a reconnect while mobile's per-connection reading
starts empty — so `ThreadViewModel` runs a second, independent ask: a `viewModelScope` collector over
`repositoryAvailable.distinctUntilChanged()` calls `repository.requestMcpStatus(conversationId)` on every
`true`, gated on the same `mcpServersSupported` reading the Channel info branch uses. The first `true` is the
thread opening; every later one is a reconnect. Only the reconnect case logs
(`event=mcp_status_requested reason=reconnect`) — logging from the view model's construction path crashed six
existing JVM suites that construct it without a `RelayLog` sink, and the opening is already logged by the
destination factory's `thread_destination_bound`. **This ask shares the daemon's per-connection FIFO
app-frame worker with `send_message`:** if the child doesn't answer, the next send on that connection waits
behind it with no timeout of its own. See [Development verification § Emulator and real
evidence](development-verification-emulator-evidence.md#emulator-and-real-evidence) for the scripted `reconnect` hang this
caused and the harness fix, and pyrycode/pyrycode#2702 for the upstream daemon issue.

**Raising a notice from a failure — `McpFailureAcknowledgements`.** The [Top overlay's MCP-failure
pill](thread-top-overlay.md#the-failed-mcp-server-pill-1345) reads this same `mcpStatusReading`: the first
server in report order whose status is exactly `"failed"` and that this host and conversation have not
acknowledged, shown only while the host is `Connected`. Tapping it acknowledges every currently-failed
server and opens this sheet through `ThreadEvent.ChannelInfo`, so opening Channel info after a tap always
re-asks for status too. `McpFailureAcknowledgements` is an app-scoped, in-memory holder keyed by host then
conversation — see [Thread top overlay §
Acknowledgement](thread-top-overlay.md#acknowledgement--mcpfailureacknowledgements) for its shape, and
`ObservablePairedServerStore.forgetRemovedHost` for why unpairing a host clears its entries alongside
`ComposerDraftStore`'s.

**Built-in servers.** `pyry_approve` and `pyry_files` (client-owned `BUILT_IN_SERVER_NAMES`, compared only
for display) stay hidden behind a "Show built-in" row until it is ticked. The tick is `rememberSaveable`
local state, not part of `McpStatus` — since the host composes the section only while the sheet is open,
remounting it on the next opening resets the tick to off, matching desktop's "off on every mount".

**Rows and controls.** Each shown server renders its bounded name and status, plus a bounded error line
when the error is non-empty. Any status other than exactly `connected` — including `disabled` — gets a
Reconnect button; the switch is on unless the status is exactly `disabled`, and a tap sends the opposite of
the rendered state (`onToggle(name, !on)`), never the switch's own proposed value. `Reconnect` calls
`reconnectMcpServer(conversationId, name)`; the switch calls `toggleMcpServer(conversationId, name, enabled)`
— both always the thread's own route `conversationId`, never anything carried on the report. Rows render in
report order with no `key()` on the name.

**The wait.** `busy = status.reconnecting || status.toggling` disables every Reconnect button and every
server switch in the section; "Show built-in" is a local filter that sends nothing, so it stays enabled
while busy, matching desktop (`disabled={busy}` sits only on Reconnect and the per-server switch there too).
A report or a refusal clears the flags in the repository. Dismissing the sheet releases both waits so a
daemon that never answers cannot leave the controls stuck disabled the next time the sheet opens — see
`closeChannelInfo` below.

**Closing.** `ThreadViewModel.closeChannelInfo()` is the single place that flips `pendingChannelInfo` to
false; `ThreadEvent.ChannelInfoDismiss`, `Archive` and a confirmed `DeleteConfirm` all route through it (the
same function also drops the System prompt editor, #1342). It reads `pendingChannelInfo` before clearing it
and calls `repository.endMcpReconnectWait(conversationId)` / `endMcpToggleWait(conversationId)` only when the
sheet was actually open, so a repeat close — e.g. `Archive` fired from the overflow menu while the sheet was
already closed — releases nothing a second time. Every caller runs on the main thread via `onOverflowEvent`,
so the read-then-clear cannot race a second close.

**Copy.** Seven states, all client-owned (no daemon text reaches a notice):
`report == null` → "No MCP report has arrived yet." (no Show built-in row); an empty `report.servers` →
"Claude reported no MCP servers."; every server filtered out → "Only built-in servers are reported.";
`droppedServers > 0` → "Partial list: N more servers were left out by the daemon."; and the three refusal
notices `unavailable` ("The daemon could not report MCP status right now."), `reconnectRefused` ("The
daemon refused to reconnect the MCP server.") and `toggleRefused` ("The daemon refused to change the MCP
server."), each independent and shown whenever its flag is set, in any report state.

**Plain text only.** Server names, statuses and errors are Claude-authored and unsanitized —
`boundMcpText` (desktop's `boundMcpText`) cuts each to its first 256 Unicode code points (counted by code
point, so a surrogate pair is never split), appending "…" only when it was longer. The bounded text is
rendered only as `Text`, and the same bounded name becomes the switch's accessibility label via
`Modifier.semantics { contentDescription = ... }` — never a `testTag`, a `key()`, a log field or an
exception message. `McpServerStatus.toString()` is itself redacted for the same reason. The two new
`ThreadEvent`s, `McpReconnect` and `McpToggle`, override `toString()` to redact the server name, and the
ViewModel's own logs (`event=mcp_status_requested`, `event=mcp_reconnect_sent`,
`event=mcp_toggle_sent enabled=…`, `event=mcp_wait_released`) carry no name, status, error or conversation
id.

See [Remote `ConversationRepository` § MCP status](remote-conversation-repository-mcp-status.md) for
`McpStatus`, `McpStatusReport`, `McpServerStatus` and the five repository members this section calls.

### Footer + clipboard

`Box(fillMaxWidth.padding(start = 24, end = 24, top = 24).combinedClickable(...))` wrapping `Text("Channel ID: $channelId", bodySmall.copy(FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp), onSurfaceVariant, Modifier.alpha(0.5f))`. Combined-clickable uses `indication = null` (no ripple over the low-opacity text), `onClick = {}` (intentional — AC restricts copy to long-press), and `onLongClick` that calls `clipboard.setText(AnnotatedString(channelId))` on `LocalClipboardManager.current` followed by `haptics.performHapticFeedback(HapticFeedbackType.LongPress)` on `LocalHapticFeedback.current`. `combinedClickable` requires `@OptIn(ExperimentalFoundationApi::class)` on the enclosing private `Footer` composable (same import as the precedent in [`ConversationRow.kt`](./conversation-row.md)).

`LocalClipboardManager` is the legacy stable Compose API; the spec explicitly retains it over the newer `LocalClipboard` / `Clipboard.setClipEntry`. The deprecation warning at the call site is expected and accepted — migration is a follow-up the day the legacy API is removed, not now.

### Color & typography mapping

All references resolve through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hex. Figma `Schemes/*` slots map 1:1:

| Figma slot | Compose slot | Used by |
|---|---|---|
| `Schemes/surface-container-low` | `colorScheme.surfaceContainerLow` | Explicit `ModalBottomSheet.containerColor` and preview surface |
| `Schemes/on-surface` | `colorScheme.onSurface` | Title text, row labels |
| `Schemes/on-surface-variant` | `colorScheme.onSurfaceVariant` | Section headers, About values, close-icon tint, footer text |
| `Schemes/primary` | `colorScheme.primary` | `Install` text button content |
| `Schemes/secondary-container` | `colorScheme.secondaryContainer` | `FilledTonalButton` default container for `ActionCell` |
| `Schemes/on-secondary-container` | `colorScheme.onSecondaryContainer` | `FilledTonalButton` default content for `ActionCell` |

| Figma style | Compose style | Used by |
|---|---|---|
| `Static/Title Large` | `typography.titleLarge` | Conversation name in `TitleRow` |
| `Static/Label Large` | `typography.labelLarge` | Section headers, `Install` label, `ActionCell` labels |
| `Static/Body Large` | `typography.bodyLarge` | About row left labels, Memory row left label |
| `Static/Body Medium` | `typography.bodyMedium` | About row right values, `None`, plugin names |
| Roboto Mono 12sp | `bodyMedium.copy(FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)` | Read-only Folder path value |
| Roboto Mono 11sp | `bodySmall.copy(FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)` | Footer `Channel ID: …` line |

`ActionCell` uses `FilledTonalButton` defaults for shape and colors — the M3 defaults produce `secondaryContainer` / `onSecondaryContainer` and the reference's pill shape. The sheet supplies a custom centered 32 × 4 dp drag handle with 12 dp above and 8 dp below; the default M3 handle area placed the title about 20 px too low in a device capture. The handle uses `onSurfaceVariant` at 40% opacity. The final 412 × 892 capture places the sheet top within 4 px of the bottom-aligned reference.

## Recomposition / stability

- All five callback params are `() -> Unit` lambdas; the caller is responsible for `remember`-stabilising hot ones. Same posture as the rest of `ui/conversations/components/`.
- No product state inside the public composable. The body remembers only its scroll position; the footer remembers a `MutableInteractionSource` for indication-less `combinedClickable`, and the shell defaults `rememberModalBottomSheetState(...)`, which the host can override.
- The model carries the current session's `MemorySearchReport` directly from `ThreadUiState.runConfig.memorySearch`. Replacing the report or changing conversations recomposes the sheet without retaining the previous provider list.

## Configuration

- **No new dependencies.** `ModalBottomSheet`, `FilledTonalButton`, `TextButton`, `combinedClickable`, `LocalClipboardManager`, `LocalHapticFeedback`, `TextOverflow.StartEllipsis` (Compose 1.7+, resolved by `composeBom = 2026.02.01`), and `Icons.Outlined.Add` / `Icons.Filled.Close` (already on the classpath via `material-icons-extended`, wired by [#131](../codebase/131.md)) all ship in the existing BOM. No `gradle/libs.versions.toml` edit.
- **No new string resources.** The section labels, memory states and three action labels remain inline; a first-localisation pass can migrate them together.

## Preview

Two `@Preview`s, one per theme, both `widthDp = 412` and `showBackground = true` — the dark variant adds `uiMode = Configuration.UI_MODE_NIGHT_YES`. Both render `ChannelInfoSheetContent(model = SAMPLE_MODEL, ...)` wrapped in `PyrycodeMobileTheme(darkTheme = …) { Surface(color = surfaceContainerLow, contentColor = onSurface) { Column(padding(top = 12.dp)) { … } } }` — identical wrap shape to [`WorkspacePickerSheet`](./workspace-picker-sheet.md)'s previews so the surface colour matches the runtime `ModalBottomSheet` container and the top spacer substitutes for the drag-handle gap.

`SAMPLE_MODEL` uses an explicit absent report, so the previews show `None` and `Install`. Provider and unknown states are exercised by `ThreadScreenChannelInfoTest`.

The [committed visual evidence](../../../app/src/androidTest/assets/channel-info-1266/channel-info-comparison.png) places the Figma render beside a dark emulator capture and labelled 50% overlay at 412 × 892. The reference node is 412 × 596, bottom-aligned in that viewport. The isolated Figma node rounds its bottom corners; the Android sheet meets the viewport bottom. Compact 320 × 692 provider and enlarged-text captures sit beside the comparison.

## Tests

`ThreadScreenChannelInfoTest` checks the populated Folder row, absence of retired workspace controls, memory availability and provider states, supported callbacks, and the `mutationsSupported` gate. The Actions-heading absence assertion is scoped to the sheet dialog because the composer also has an unrelated Actions control. At compact width and enlarged text, shared Compose tests check that bounded values remain readable and the close, install and action controls remain reachable.

`ChannelInfoSessionSectionTest` (#1346, Robolectric, `app/src/sharedTest`) checks the Session section: the "Claude version" vs. "Codex version" label by agent; "Not reported" for absent facts and for an all-control-character value; a 300-code-point value cut to 256 with a surrogate pair intact at the boundary plus the "Truncated" tag; `truncatedFields` tagging each row independently of the local cut; the cost row rendering `"$0.42 est."` when present and absent when `null`; and that control characters render inert. `reportedSessionValue` / `formatSessionCost` are asserted directly as pure helpers in the same class. `TurnEndPayloadsTest` and `ThreadViewModelSessionReadingsTest` cover the decode and the lifetime of `sessionCostUsd` — see [Live-session events § `TurnEnd`](live-session-events.md).

`ThreadScreenSystemPromptTest` (shared/Robolectric, #1342) is a separate test class that sets up its own `ThreadScreen` host with a `systemPrompt` state, rather than adding cases to `ThreadScreenChannelInfoTest` — the System prompt section's own coverage doesn't need the rest of that class's fixture. It checks the section renders after Memory and before Actions; the Loading and Unavailable lines; the Loaded field, byte count, over-limit line (with its `error(...)` semantics), differs line, and each write line (Saving, Saved, and the three refusal lines); and that Save/Clear fire their callbacks and reflect `canSave`/`canClear`'s disabled states. `ThreadViewModelSystemPromptTest` (unit) covers the host side: opening Channel info constructs an editor and reads once, each close path drops it, a reopen reads again, and the three `ThreadEvent`s forward to it.

`ThreadScreenMcpServersTest` (shared/Robolectric, #1344) covers the section's own rules: presence after
System prompt and before Actions and absence when `mcpServersSupported` is false; rows with name/status;
the built-in filter and its reset after a close-and-reopen; the error line shown only when non-empty; the
256-code-point cut on name, status and error (and no "…" at exactly 256); the partial-list line; the three
empty states; the three notices together; Reconnect appearing only on a non-`connected` status; the switch
checked unless `disabled`; one `McpReconnect(name)` / `McpToggle(name, !on)` per tap; and every Reconnect and
switch disabled while `reconnecting` and while `toggling` (Show built-in stays enabled). `ThreadScreenMapperTest`
covers `toChannelInfoUiModel` mapping the capability to the status or to `null`. `ThreadViewModelMcpServersTest`
(unit) covers the host side: `requestMcpStatus` once per real opening and not on a second `ChannelInfo` while
open, again on reopen, never when the capability is false; one repository call per `McpReconnect`/`McpToggle`
with the route's own conversation id; both end-waits called once from `ChannelInfoDismiss` and from `Archive`,
and not again on a repeat dismiss; and that the repository's `McpStatus` reaches `state.mcpStatus`.
`SessionSettingsPayloadsTest` covers the `mcp_servers` decode, present-`true`, present-`false` and absent (default
`true`). Rung 3: `InteractiveStreamE2ETest#interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn`
runs one ping turn (the daemon answers only for a running Claude process), opens Channel info, ticks Show
built-in and waits for `pyry_approve`; it is on the `LIVE=1` curated list in `scripts/e2e-emulator.sh`. No
rung-4 twin — the scripted `fakeclaude` has no MCP child to query.

**#1345 (failure notice):** `McpFailureAcknowledgementsTest` (unit) covers the holder alone — selection is
the first failed server in report order, exact `"failed"` (not `"Failed"` or `"pending"`), built-in names
count, an acknowledged name is skipped, a `null` report selects nothing; the holder's per-host and
per-conversation isolation, `clearHost` leaving other hosts untouched, and an empty `acknowledge` emitting
nothing new. `ThreadViewModelMcpFailureTest` (unit, the `ThreadViewModelMcpServersTest` rig with a
controllable connection and `repositoryAvailable`) covers `mcpFailure` / `onMcpFailureTapped` / the ask: the
notice shows the first unacknowledged failure; a tap acknowledges every failed server in one pass and opens
Channel info with one status request; a repeat report stays quiet, a newly failed server raises its own
notice, a recovered server stops showing; acknowledgements survive a second view model on the shared holder
(a reopen) and a reconnect; another conversation's notice still shows; the pill is hidden while not
`Connected`; exactly one ask on open and one per return of `repositoryAvailable`, none while
`mcpServersSupported` is false; and no log line ever carries a server name.
`ThreadViewModelMcpServersTest`'s `rig` clears the open-time ask and its log before its own exact-call
assertions, since #1345 added one more call on construction. `ThreadTopOverlayTest` (shared screen test)
covers the pill itself — see [Thread top overlay §
Testing](thread-top-overlay.md#testing). `HostChannelListViewModelTest` confirms the production
`forgetRemovedHost` drops a removed host's acknowledgements and keeps another host's. Not rung-3: a failed
MCP server needs a deliberately broken MCP config on the live e2e host, tracked as a follow-up
([#1457](https://github.com/pyrycode/pyrycode-mobile/issues/1457)).

`ChannelInfoCaptureTest` renders the real sheet on the managed Android 13 emulator. Its [result XML](../../../app/src/androidTest/assets/channel-info-1266/device-results.xml) records three executed tests with no failures or skips. The [412 × 892 comparison](../../../app/src/androidTest/assets/channel-info-1266/channel-info-comparison.png) and 320 × 692 provider and enlarged-text captures provide visual checks for sheet geometry and clipping. The capture also exposed the default handle spacing and equal-width Folder truncation, both corrected in the implementation; content assertions alone did not reveal them.

## Edge cases / limitations

- **Folder value start-ellipsizes, not middle.** `TextOverflow.StartEllipsis` keeps the rightmost path segment visible — for `~/Workspace/Projects/KitchenClaw`, narrow widths collapse the leading prefix and preserve `…/KitchenClaw`. Fallback to `TextOverflow.MiddleEllipsis` only if a Compose-BOM resolution issue surfaces (none observed at `composeBom = 2026.02.01`).
- **Provider names are untrusted display text.** Keep their 120-character, two-line bound and do not turn them into links or log values. `ThreadScreenChannelInfoTest` checks the name bound, available and disabled states, unknown after a conversation change, absent-only Install, and its URL.
- **Delete cell carries no destructive emphasis.** Per AC and Figma `20:96`, the cell uses the same `FilledTonalButton` defaults as the other two — `secondaryContainer` / `onSecondaryContainer`, no `error`-tinted variant. The destructive-confirmation flow is owned by the host: since [#227](../codebase/227.md) tapping **Delete** emits `ThreadEvent.Delete`, which opens a Material 3 `DeleteConfirmationDialog` layered over this still-open sheet — only its confirm calls `repository.delete(...)`. (That confirm button is itself a plain `TextButton` with no `error` tint, per the #78 convention; a non-blocking NIT.)
- **Footer copy is long-press only.** Single-tap is wired (`onClick = {}` is intentional) but does nothing; the absence of a ripple (`indication = null`) is deliberate so the low-opacity footer doesn't paint a high-contrast ripple over itself. The haptic feedback on long-press is the user-facing confirmation that the copy fired.
- **`ModalBottomSheet` scrim and back-press both route to `onDismiss`** — provided by the M3 component; the caller doesn't need to wire either separately.

## Related

- Ticket notes: [`../codebase/217.md`](../codebase/217.md) (stateless composable), [`../codebase/226.md`](../codebase/226.md) (thread-overflow host), [`../codebase/227.md`](../codebase/227.md) (Archive/Delete wiring), [`../codebase/508.md`](../codebase/508.md) (`mutationsSupported` relay-mode gate hiding the Actions section)
- Spec: `docs/specs/architecture/217-channelinfosheet-stateless-composable.md`, `docs/specs/architecture/508-hide-unavailable-conversation-actions.md`, `docs/specs/architecture/1342-channel-info-system-prompt.md` (System prompt section), `docs/specs/architecture/1344-channel-info-mcp-servers.md` (MCP servers section), `docs/specs/architecture/1345-mcp-failure-notice.md` (the Top overlay failure pill and the open/reconnect ask)
- [System prompt editor](system-prompt-editor.md) (#824, mounted here by #1342) — `SystemPromptEditorState`, `canSave`/`canClear`, refusal classification, the construction-per-open/drop-on-close lifecycle
- [Remote `ConversationRepository` § MCP status](remote-conversation-repository-mcp-status.md) (#1343, surfaced here by #1344) — `McpStatus`/`McpStatusReport`/`McpServerStatus`, why the reading starts empty per connection, `requestMcpStatus`/`reconnectMcpServer`/`toggleMcpServer`/`endMcpReconnectWait`/`endMcpToggleWait`
- [Thread top overlay § The failed-MCP-server pill](thread-top-overlay.md#the-failed-mcp-server-pill-1345) (#1345) — the notice this section's status report feeds, `McpFailureAcknowledgements`, and the open/reconnect ask's daemon FIFO-worker coupling
- [ChannelListViewModel § Wiring](channel-list-viewmodel.md#wiring) — Edit channel's own system-prompt editing, including the #1342 clear rule this sheet's Clear button shares
- Parent: split from [#144](https://github.com/pyrycode/pyrycode-mobile/issues/144) (Channel Info bottom sheet — host + sheet bundle).
- Sibling sheet: [`WorkspacePickerSheet`](./workspace-picker-sheet.md) (#212) — same `ModalBottomSheet` shell + `*Content` body split, same `internal` visibility posture, same preview wrap. Worth reading first if you're picking up this file.
- Sibling stateless-component conventions: [`ConversationRow`](./conversation-row.md) (precedent for the `combinedClickable` long-press shape used by `Footer`), [`ConnectionBanner`](./connection-banner.md), [`ToolCallRow`](./tool-call-row.md).
- Session section ([#1346](https://github.com/pyrycode/pyrycode-mobile/issues/1346)): [Live-session events](live-session-events.md) (the `turn_end.cost_usd_total` decode `sessionCostUsd` is built from), [`UsageLimitIndicator`](usage-limit-indicator.md) (the inert-text precedent `reportedSessionValue` independently follows).
- Downstream / open:
  - **Thread-overflow host ([#226](../codebase/226.md)) ✅** — wires this sheet into the thread overflow → **Channel info** entry point. [`ThreadScreen`](./thread-screen.md) owns visibility, assembles `ChannelInfoUiModel` from the conversation and current session report, and routes its actions. The absent-only `onInstallMemoryPlugin` opens the shared docs URL through `LocalUriHandler`.
  - **Channel List long-press host (open)** — the other channel-info entry point (long-press a channel row) is not yet wired.
  - Open: non-empty memory plugin layout — revisit when a real plugin source ships and a designer locks the precise list / chip / expander treatment against Figma `20:78`.
  - Open: destructive-emphasis on the host's `DeleteConfirmationDialog` confirm button — it ships as a plain `TextButton` (per the #78 convention) since [#227](../codebase/227.md); `MaterialTheme.colorScheme.error` on the confirm label is the M3 touch if the team ever wants it (a deferred, non-blocking NIT). The Delete *cell* on this sheet stays a plain `FilledTonalButton`.
  - Open: migrate `LocalClipboardManager` → `LocalClipboard` / `Clipboard.setClipEntry` once the legacy API is removed.
  - Open: localise the inline literals alongside the rest of `ui/conversations/components/`'s first `strings.xml` pass.
  - Open: no `@Preview` renders the MCP servers section — `SAMPLE_MODEL` leaves `mcpServers = null`. A sample `McpStatus` with one `connected`, one `failed` (with an error), one `disabled` server and `droppedServers > 0` would let it be checked in both themes without a device. `McpServerRow`'s Reconnect button also duplicates this file's private `ActionCell` rather than reusing it (#1344 verifier NITs, non-blocking).
