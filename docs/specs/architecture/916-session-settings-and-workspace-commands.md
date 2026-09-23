# #916 — Move session settings, the system prompt and workspaces into their own classes

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → the source of the move. Session settings: `settingsRevision`, `observeSessionSettings`, `sessionSettingsRead`, `readSessionSettings`, `refreshSessionSettings`, `bumpSettingsRevision`, `setSessionSettings`, `requestSystemPrompt`, `setSystemPrompt`. Workspaces: `recentWorkspaces`, `recentWorkspacesRequest`, `changeWorkspace`, `createWorkspaceFolder`, `renameWorkspace`, `archiveWorkspace`, `malformedWorkspaceReply`. The two routing sites that stay: the `session_transition` arm of `onInbound` (calls `bumpSettingsRevision`) and the `TYPE_WORKSPACE_UPDATED` arm (calls `malformedWorkspaceReply`). The companion constants the moved bodies use stay in the companion.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationCommands.kt` → `ConversationCommands`, the #914 shape; `archive` is what `archiveWorkspace` fans out to.
- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt` → `MessageCommands`, the #915 shape: `internal class`, header KDoc naming what the repository still routes, companion constants imported as `RemoteConversationRepository.Companion.…`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRequests.kt` → `nextRequestId`, `sendAndAwaitReply`, `mapError`: the only request plumbing the moved code uses.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt` → `upsertConversation` (`setSystemPrompt`, `changeWorkspace`), `current` (`archiveWorkspace`), `applyWorkspaceLabel` (stays in `onInbound`).
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt`, `RemoteConversationRepositorySystemPromptTest.kt`, `RemoteConversationRepositoryWorkspaceTest.kt` → reach every moved path through the repository surface.
- `docs/specs/architecture/915-message-commands.md` → the previous step of this split.

In-flight check: no remote `feature/*` branch touches `RemoteConversationRepository.kt`.

## Design source

N/A: data-layer refactor with no UI.

## Context

The last cluster in `RemoteConversationRepository` that is not routing: session settings, the system prompt and workspace management. This ticket moves it into `SessionSettingsCommands` and `WorkspaceCommands`, completing the split #912 to #916. It is a move: behaviour, names, KDoc and ordering stay. No ADR is needed.

## Design

Both classes are `internal`, in `data/repository/`, constructed once in the repository's property list (one repository per connection, #351), with a header KDoc naming what the repository still routes. Bodies move unchanged apart from `relayRequests.` → `requests.` and `conversationListProjection` → `conversationList`; KDoc links to repository members that no longer resolve from the new class are qualified (`[ConversationCommands.rename]`, `[RemoteConversationRepository.requestHistory]`) or reworded to name the repository's private `onInbound` / `init` collector in backticks.

### `SessionSettingsCommands` (`SessionSettingsCommands.kt`)

Constructor: `SessionSettingsCommands(requests: RelayRequests, negotiatedCapabilities: () -> Set<String>, conversationList: ConversationListProjection)`.

| Member | Visibility | Notes |
|---|---|---|
| `settingsRevision` | private `val` | `MutableStateFlow<Map<String, Long>>`, KDoc moves; "the [init] inbound collector" becomes the repository's inbound collector through `bumpSettingsRevision` |
| `observeSessionSettings(conversationId)` | public | `@OptIn(ExperimentalCoroutinesApi::class)` moves with it |
| `sessionSettingsRead`, `readSessionSettings` | private | verbatim |
| `refreshSessionSettings(conversationId)` | public | `= bumpSettingsRevision(conversationId)` |
| `bumpSettingsRevision(conversationId)` | public (was private) | the `session_transition` arm calls it |
| `setSessionSettings`, `requestSystemPrompt`, `setSystemPrompt` | public `suspend` | verbatim, including the `check` / `require` before any frame |

### `WorkspaceCommands` (`WorkspaceCommands.kt`)

Constructor: `WorkspaceCommands(requests: RelayRequests, conversationList: ConversationListProjection, conversationCommands: ConversationCommands)`.

| Member | Visibility | Notes |
|---|---|---|
| `recentWorkspaces()` | public | cold flow with its `.catch { emit(emptyList()) }` |
| `recentWorkspacesRequest()` | private | verbatim |
| `changeWorkspace`, `createWorkspaceFolder`, `renameWorkspace` | public `suspend` | verbatim |
| `archiveWorkspace(path)` | public `suspend` | the loop calls `conversationCommands.archive(conversationId)`, which is exactly what the repository's `archive` hand-off called |
| `malformedWorkspaceReply()` | public (was private) | the `TYPE_WORKSPACE_UPDATED` arm calls it |

### Repository wiring and hand-offs

- `settingsRevision` leaves the repository. `sessionSettingsCommands` and `workspaceCommands` are declared right after `messageCommands`, so every dependency they name (`relayRequests`, `conversationListProjection`, `conversationCommands`) is already initialised, and both precede the `init` block that launches the collector. Each gets a KDoc naming what it owns.
- `onInbound`: the `session_transition` arm calls `sessionSettingsCommands.bumpSettingsRevision(conversationId)` at the same position; the `TYPE_WORKSPACE_UPDATED` arm calls `workspaceCommands.malformedWorkspaceReply()` at the same position. Nothing else in `onInbound` changes.
- Each moved public function stays in its current position as a one-line hand-off with a one-line KDoc naming its owner, e.g. `override suspend fun renameWorkspace(path: String, label: String?) = workspaceCommands.renameWorkspace(path, label)`. `refreshSessionSettings` hands off to `sessionSettingsCommands.refreshSessionSettings`.
- Imports the move leaves unused in the repository go. Companion constants stay; their KDoc links still resolve inside the repository.

## State + concurrency model

Unchanged. `settingsRevision` is still one `MutableStateFlow` per connection with two writers (the inbound collector via `bumpSettingsRevision`, any caller via `refreshSessionSettings`), both going through the atomic `update {}` read-modify-write. `observeSessionSettings` is still cold per collector with `flatMapLatest` cancelling a superseded read. `WorkspaceCommands` holds no state. No new coroutine, scope or lock.

## Error handling

Unchanged. A failed settings read still fails closed to `null` inside `sessionSettingsRead` and discards the throwable unlogged; `recentWorkspaces` still fails closed to one empty list; every other command throws to its caller exactly as before. The `workspace_updated` arm still decodes-or-drops without logging, keeps the single collector alive, fails a correlated waiter with the static `malformedWorkspaceReply()`, and applies the label before completing a `renameWorkspace` waiter. `archiveWorkspace` still rethrows cancellation at once and the first other failure after the loop.

## Testing strategy

The existing tests reach every moved path through the repository surface: `RemoteConversationRepositoryTest` (settings reads and writes, `changeWorkspace`, `createWorkspaceFolder`, `recentWorkspaces`), `RemoteConversationRepositorySystemPromptTest`, `RemoteConversationRepositoryWorkspaceTest` (`renameWorkspace`, `archiveWorkspace`, the `workspace_updated` arm), plus `RelayRepositoryCoordinatorTest` and `StableConversationRepositoryTest` through the facade. No assertion changes; a green `testDebugUnitTest --tests "de.pyryco.mobile.data.repository.*"` is the proof, then `lint` and `assembleDebug`. No new test class: neither class exposes behaviour the repository tests do not already reach.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/remote-conversation-repository-conversation-writes.md`: the `setSessionSettings`, `observeSessionSettings` / `refreshSessionSettings`, `changeWorkspace` and `requestSystemPrompt` / `setSystemPrompt` sections name their owning class (`SessionSettingsCommands`, or `WorkspaceCommands` for `changeWorkspace`).
- `docs/knowledge/features/remote-conversation-repository-workspace-and-push.md`: the `createWorkspaceFolder`, `renameWorkspace` / `archiveWorkspace` and `recentWorkspaces` sections name `WorkspaceCommands`.
- `docs/knowledge/features/remote-conversation-repository.md`: say the split of the repository (#912 to #916) is complete and list what the repository still owns.

## Open questions

- None.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Daemon frames still enter at `onInbound` only. The `session_settings`, `system_prompt`, `recent_workspaces_list`, `workspace_folder_created`, `session_settings_updated` and `conversation_updated` replies still reach their waiter by `in_reply_to` alone and are decoded caller-side, once, through the same DTO boundary (`toSessionSettings`, `toSystemPromptReading`, `RecentWorkspacesListPayloadDto`, `WorkspaceFolderCreatedPayloadDto`, `ConversationResponseDto`), so a malformed reply throws on the caller's coroutine and never on the collector. The `workspace_updated` arm keeps its decode-or-drop in `onInbound`; only the static error it hands a waiter moves. `renameWorkspace` still re-decodes its reply and requires it to name the requested path, so a correlated frame of another type cannot report success.
- [Tokens, secrets, credentials] No findings. No token, key or pairing material is touched.
- [File / storage] No findings. Workspace paths and folder names remain untrusted strings forwarded verbatim to the daemon, which confines them; the phone never touches its filesystem with them. `createWorkspaceFolder` keeps the blank-name `require` before any send and the fixed `WORKSPACE_FOLDER_PARENT`.
- [Inter-process / Android surface] No findings. No manifest, intent, push or WebView change; both classes are `internal` with no Android import, keeping `data/` portable.
- [Cryptographic primitives] No findings. No crypto is touched; request ids still come from the one `RelayRequests` counter per connection.
- [Network & I/O] No findings. The `interactive` gates stay in front of the send in `sessionSettingsRead` and `requestSystemPrompt`, so a non-interactive connection still sends no frame the daemon would leave unanswered. `setSystemPrompt` keeps its `SystemPromptLimit.fits` check before any frame, so an oversized prompt never reaches the wire.
- [Logs] No findings. None of the moved code logs, and nothing new logs. The settings-read `catch` still discards its throwable, which is what keeps a kotlinx-serialization message quoting the payload out of Logcat. Exception messages stay the static companion constants (`SYSTEM_PROMPT_READ_NOT_INTERACTIVE`, `SYSTEM_PROMPT_TOO_LONG`, `WORKSPACE_REPLY_MALFORMED`); no path, label, prompt or id reaches one.
- [Concurrency] No findings. `settingsRevision` keeps its two writers and its atomic `update {}`; moving it changes its owner object, not its access pattern, and nothing else reads it. The new properties are declared before the `init` block, so the collector cannot call `bumpSettingsRevision` or `malformedWorkspaceReply` on an uninitialised property. `archiveWorkspace` still archives sequentially and rethrows `CancellationException` before the general catch.
- [Threat model] No findings. A hostile relay can still only drop, delay or reorder; a stalled reply is still released by `failAllPending` at teardown. A hostile daemon frame is still dropped without ending the single inbound collector, and a reading still cannot cross-route between conversations because it is routed by the id its caller asked with. Behaviour is unchanged, so no new threat is introduced.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
