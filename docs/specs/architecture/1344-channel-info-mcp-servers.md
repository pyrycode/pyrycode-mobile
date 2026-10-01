# #1344 — MCP servers section in Channel info, with Reconnect and an on/off switch

## Files read

- `data/network/SessionSettingsPayloads.kt` — `SessionCapabilitiesDto` and `toSessionSettings`: where `mcp_servers` decodes, beside `slash_commands`.
- `data/repository/ConversationRepository.kt` — `SessionCapabilities` (gains `mcpServers`), and the #1343 members `observeMcpStatus`, `requestMcpStatus`, `reconnectMcpServer`, `toggleMcpServer`, `endMcpReconnectWait`, `endMcpToggleWait`, plus `McpStatus`, `McpStatusReport`, `McpServerStatus` (redacted `toString`).
- `ui/conversations/thread/ThreadViewModel.kt` — `onOverflowEvent` (the `ThreadEvent.ChannelInfo` / `ChannelInfoDismiss` arms, the `Archive` and `DeleteConfirm` arms that also close the sheet), `pendingChannelInfo`, the `state` combine chain (`slashCommandMenu` and `backgroundTaskReading` arms are the shape to mirror).
- `ui/conversations/thread/ThreadUiState.kt` — `ThreadEvent`, `ThreadUiState`, `ThreadRunConfig.capabilities`.
- `ui/conversations/thread/ThreadScreen.kt` — the `ChannelInfoSheet` host block and `toChannelInfoUiModel`.
- `ui/conversations/components/ChannelInfoSheet.kt` — `ChannelInfoSheetContent` section order, `SectionHeader`, `AboutRow`, `ActionCell` (`FilledTonalButton`).
- `ui/settings/SettingsScreen.kt` — the Material 3 `Switch` row the Figma Settings node draws.
- Desktop `src/renderer/src/screens/conversation/McpServersSection.tsx` — `McpServersSectionView` (copy, filter, row rules, `boundMcpText`, busy disables every control, switch labelled by the server name) and `McpServersSection` (Show built-in local and off on each mount; unmount ends both waits).
- `docs/knowledge/features/remote-conversation-repository-mcp-status.md` § "For #1344 and #1345" — the reading starts empty per connection, so the surface asks on open; the end-wait calls exist for exactly this surface.
- `docs/knowledge/features/channel-info-sheet.md` — capability flags ride as sheet params, display content on `ChannelInfoUiModel`; section bodies scroll, so screen tests scroll to off-screen nodes.
- `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` — `interactiveTurn_pingPrompt_statusSheetShowsRunningModel` and the Channel-info overflow steps of `interactiveTurn_deleteConversation_removesFromListAndClosesThread`; `scripts/e2e-emulator.sh` curated `LIVE=1` list.
- pyrycode `docs/protocol-mobile.md` § `capabilities` (`mcp_servers`) and § "Asking for MCP status on demand" (the daemon queries the conversation's live child, so the live test runs one turn first).

Overlapping in-flight branches: #1342 and #1346 add params/fields and a section to `ChannelInfoSheet.kt`; #1314, #1329, #1337, #1342, #1346 and others touch `ThreadViewModel.kt`/`ThreadScreen.kt`/`ThreadUiState.kt`; #1337, #1360, #1397, #1410, #1417 touch `scripts/e2e-emulator.sh`. All additive next to this ticket's edits; this ticket keeps its shared-file edits additive and puts the section body in its own file.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-48 (sheet) and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2 (Settings switch row)

No frame draws the MCP section; it is assembled from the sheet's own parts. It sits after Memory and before Actions: the sheet's `SectionHeader` ("MCP servers", `labelLarge` on `onSurfaceVariant`), a "Show built-in" row in the Settings-row shape (label `bodyLarge` on `onSurface`, trailing M3 `Switch`), then one block per server: an `AboutRow`-shaped line with the name (`bodyLarge`, `onSurface`) and status (`bodyMedium`, `onSurfaceVariant`, end-aligned), an optional error line (`bodySmall`, `colorScheme.error`), and a trailing controls row with a `FilledTonalButton` "Reconnect" (secondary-container defaults, as `ActionCell`) and an M3 `Switch`. Empty and notice lines are `bodyMedium` on `onSurfaceVariant` with the row's 16 dp horizontal padding. All colours and type from `MaterialTheme`.

## Context

#1343 landed the MCP reading and its three sends on `ConversationRepository`; nothing renders it. Desktop already ships the section, and the user wants to see and fix a broken MCP server from the phone. The section must also hide for sessions that cannot answer (Codex), which the `mcp_servers` capability now reports. No decision record needed: this follows desktop's design one-to-one.

## Design

**Capability.** `SessionCapabilitiesDto` gains `@SerialName("mcp_servers") val mcpServers: Boolean = true`; `SessionCapabilities` gains `val mcpServers: Boolean = true` (appended after `slashCommands`, so positional callers are untouched); `toSessionSettings` passes it through. An internal extension `ThreadRunConfig.mcpServersSupported: Boolean` (`capabilities?.mcpServers ?: true`) in `ThreadUiState.kt` is the single reading both the ViewModel and the screen use.

**UI state.** `ThreadUiState` gains `mcpStatus: McpStatus = McpStatus()`. `ThreadViewModel` adds one more combine arm after `backgroundTaskReading`: `repository.observeMcpStatus(conversationId).onStart { emit(McpStatus()) }.distinctUntilChanged()`.

**Events.** Two new `ThreadEvent` members, routed through `onOverflowEvent` like every sheet action:

- `McpReconnect(serverName: String)` → `repository.reconnectMcpServer(conversationId, serverName)`.
- `McpToggle(serverName: String, enabled: Boolean)` → `repository.toggleMcpServer(conversationId, serverName, enabled)`.

Both override `toString` to redact the name. `conversationId` is the ViewModel's own route id, never anything from the report.

**Open and close.**

- `ChannelInfo`: inside the existing `compareAndSet(false, true)` branch, after the settings reread, call `repository.requestMcpStatus(conversationId)` when `state.value.runConfig.mcpServersSupported`.
- A private `closeChannelInfo()` replaces the three `pendingChannelInfo.value = false` writes (`ChannelInfoDismiss`, `Archive`, `DeleteConfirm`): it flips the flag with `getAndUpdate { false }` and, only when it was open, calls `endMcpReconnectWait(conversationId)` and `endMcpToggleWait(conversationId)`. So every way the sheet closes releases the waits once, and a repeat dismiss sends nothing.

**Sheet.** `ChannelInfoUiModel` gains `mcpServers: McpStatus? = null` — `null` hides the section. `toChannelInfoUiModel` sets it to `mcpStatus` when `runConfig.mcpServersSupported`, else `null`. `ChannelInfoSheet` and `ChannelInfoSheetContent` gain `onMcpReconnect: (String) -> Unit = {}` and `onMcpToggle: (String, Boolean) -> Unit = { _, _ -> }` (defaulted after `mutationsSupported`, the #508 parameter-order rule; production always passes them). `ChannelInfoSheetContent` renders `SectionHeader("MCP servers")` and then `McpServersSection(...)` between Memory and Actions when `model.mcpServers != null`. `ThreadScreen` wires the two callbacks to `onOverflowEvent(ThreadEvent.McpReconnect(name))` / `McpToggle(name, enabled)`.

**Section** — new file `ui/conversations/components/McpServersSection.kt`:

- `internal fun boundMcpText(text: String): String` — first 256 code points plus "…" only when longer (code-point iteration, so a surrogate pair is never split).
- `@Composable internal fun McpServersSection(status: McpStatus, onReconnect: (String) -> Unit, onToggle: (String, Boolean) -> Unit)`:
  - `showBuiltIn` is `rememberSaveable { mutableStateOf(false) }`; the host composes the sheet only while open, so the tick resets each opening.
  - `busy = status.reconnecting || status.toggling` disables every Reconnect button and every switch (the Show built-in toggle is a local filter, not a control that sends, so it stays enabled).
  - `report == null`: "No MCP report has arrived yet." and no Show built-in row.
  - Otherwise: the Show built-in row, then the shown servers (built-ins `pyry_approve` and `pyry_files` filtered out unless ticked), then "Claude reported no MCP servers." / "Only built-in servers are reported." when nothing is shown, then "Partial list: N more servers were left out by the daemon." when `droppedServers > 0`.
  - Row: bounded name and bounded status; bounded error line when `error != ""`; Reconnect when `status != "connected"`; switch `checked = status != "disabled"`, `onCheckedChange = { onToggle(name, !on) }` (the opposite of the shown state, not the switch's proposed value — they agree, but the rule is desktop's). The switch's accessibility label is the bounded name (desktop's `aria-labelledby`). Rows are emitted in report order without `key()` on the name.
  - Notices, each independent and in every report state: `unavailable`, `reconnectRefused`, `toggleRefused` with the ticket's fixed copy.
- Strings: inline literals, matching the rest of `ChannelInfoSheet.kt` (no `strings.xml` pass in this package yet).

## State and concurrency model

No new jobs or scopes. `observeMcpStatus` is a cold flow collected by `state`'s existing `WhileSubscribed(5_000)` chain in `viewModelScope`. All five repository calls are synchronous fire-and-forget `Unit` methods that never throw, called on the main thread from `onOverflowEvent`. `pendingChannelInfo` is the one source of "sheet open"; `getAndUpdate` makes close-and-release atomic against a concurrent close. The wait flags live in the repository's connection-scoped projection; a reconnect resets them, and the end-wait calls are no-ops when nothing is held.

## Error handling

No new failure surface. Refusals and unavailability arrive as flags on `McpStatus` and render as the three fixed notices; no daemon text reaches a notice. A send that cannot go out sets no flag (repository contract), so the controls stay enabled. A daemon that never answers is released by `closeChannelInfo`.

## Testing strategy

- `SessionSettingsPayloadsTest` (unit): `mcp_servers: false` decodes to `mcpServers = false` (the existing Codex-example test's expected value gains the field); a capabilities object without the key decodes `mcpServers = true`.
- New `ThreadViewModelMcpServersTest` (unit, a recording `ConversationRepository by FakeConversationRepository` delegate, the `ThreadViewModelSettingsRereadTest` rig): open requests once for the route's conversation and a second `ChannelInfo` while open does not; reopen requests again; `mcpServers = false` reading → no request; `McpReconnect`/`McpToggle` call the repository once each with the route's id, the name and `enabled` verbatim; `ChannelInfoDismiss` calls both end-waits once and a repeat dismiss calls nothing; `Archive` from the open sheet releases too; the repository's `McpStatus` reaches `state.mcpStatus`; logs carry no name or conversation id.
- New `ThreadScreenMcpServersTest` (shared Compose, Robolectric, driving `ThreadScreen` with state): section present by default and absent when `mcpServers = false`; section sits after Memory and before Actions (vertical positions); rows with name/status; built-ins hidden until ticked and the tick resets after close-and-reopen; error line shown only when non-empty; 256-code-point cut on name, status and error (and no "…" at exactly 256); partial-list line; the three empty states; the three notices together; Reconnect only on non-`connected`; switch checked unless `disabled`; taps emit `McpReconnect(name)` and `McpToggle(name, !on)` once; every Reconnect and switch disabled while `reconnecting` and while `toggling`.
- `ThreadScreenMapperTest`: `toChannelInfoUiModel` maps `mcpServers` to the status, or `null` when the capability is false.
- Rung 3: `InteractiveStreamE2ETest#interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn` — one ping turn (the daemon queries the live child), open Channel info, tick Show built-in, wait for `pyry_approve`. Added to the `LIVE=1` curated list in `scripts/e2e-emulator.sh`. No rung-4 twin: the scripted `fakeclaude` holds no MCP child to query.
- No device-only test: everything else runs under Robolectric.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/channel-info-sheet.md` — add the MCP servers section: when it shows (`mcp_servers` capability not `false`), the `requestMcpStatus` call on every opening, the built-in filter and its reset, the wait that disables every control and its release on every close path, and the plain-text, 256-code-point rule for Claude-authored text.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No new boundary. Claude-authored server text is already decoded at `McpStatusPayloadDto.toReport()` into `McpServerStatus`. In this ticket it flows only into `Text` composables and a `Switch`'s semantics label, each through `boundMcpText`, so the render path is inert text with a 256-code-point bound. It is never a `key()`, test tag, URL, log field or exception message. SHOULD FIX: the plan names the switch's accessibility label as the server name; implement it with `semantics { contentDescription = ... }` on the bounded name, never with a `testTag`.
- [Trust boundaries] The status word drives two UI decisions: `!= "connected"` (offer Reconnect) and `== "disabled"` (switch off). Both are exact-equality comparisons on a claim; a hostile word can only show or hide a control the user still has to tap. The daemon re-checks both actuation verbs.
- [Tokens] No findings. No token, key or credential is read, stored or sent by this design.
- [Files and storage] No findings. Nothing persisted; `showBuiltIn` is UI-local saved state holding a Boolean.
- [Android attack surface] No findings. No new component, intent filter, deep link or WebView.
- [Cryptography] No findings. Transport unchanged; sends go through the existing `NoiseIkSession` path in the repository.
- [Network & I/O] Each tap is one send; the repository never retries. SHOULD FIX: a disabled control cannot be tapped, but a second tap inside one frame, before the `reconnecting`/`toggling` flag recomposes, could send twice. Accepted: the toggle's target comes from the same rendered status, so a duplicate sends the same `enabled` value (idempotent), and a duplicate reconnect is a repeat of an idempotent ask. The ViewModel test asserts one call per event.
- [Addressing] Every send uses the ViewModel's route `conversationId`; no conversation id is taken from the report or the event. Asserted in the ViewModel test.
- [Errors, logs and telemetry] The ViewModel logs static events only (`event=mcp_status_requested`, `event=mcp_reconnect_sent`, `event=mcp_toggle_sent enabled=<bool>`, `event=mcp_wait_released`) with no name, status, error or conversation id. The new events override `toString` to redact the name. Notices are fixed client copy.
- [Concurrency] No new coroutine. Close-and-release is one `getAndUpdate` on `pendingChannelInfo`, so two close paths cannot both release, or skip the release. OUT OF SCOPE: a ViewModel cleared with the sheet still open (process kill) leaves the wait held until the connection resets the projection or the next opening's report clears it. The modal sheet takes back-press first, so normal navigation always closes it through `ChannelInfoDismiss`.
- [Threat model] A malicious relay can drop the reply: the wait is released on close, and the next opening asks again. A hostile daemon frame with oversized or control-character text renders bounded and inert. UI leakage: server names are already visible on screen; the accessibility label exposes nothing beyond the visible text.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01
