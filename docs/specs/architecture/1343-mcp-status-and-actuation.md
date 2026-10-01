# #1343 — decode MCP status and send the MCP status, reconnect and toggle requests

## Files read

- `../pyrycode/docs/protocol-mobile.md` § `mcp_status`, § "Asking for MCP status on demand", § "Actuating MCP
  servers on demand" — the wire contract, cited and not restated. Shapes the design: one payload for push and
  reply, five always-present strings per server, `dropped_servers` always a number, `enabled` always present,
  the single merged `mcp_actuation.refused`, and `mcp_status.unavailable` as the one status-ask refusal that
  means "try later".
- `../pyrycode-desktop/src/renderer/src/store/mcpStatusStore.ts` → `setMcpStatus`, `markMcpStatusUnavailable`,
  `beginMcpReconnect` / `beginMcpToggle`, `markMcpReconnectRefused` / `markMcpToggleRefused`,
  `endMcpReconnectWait` / `endMcpToggleWait` — the flag rules copied here.
- `../pyrycode-desktop/src/main/daemonConnection.ts` → `requestMcpStatus`, `reconnectMcpServer`,
  `toggleMcpServer` and the `pendingMcpStatusRequests` / `pendingMcpReconnects` / `pendingMcpToggles` refusal
  correlation — envelope id → conversation, never the server name or requested state.
- `app/src/main/java/de/pyryco/mobile/data/network/ContextUsagePayloads.kt` → `ContextUsagePayloadDto`,
  `toReading` — strict-required DTO, value reject in the mapper.
- `app/src/main/java/de/pyryco/mobile/data/repository/ContextUsageProjection.kt` → `ContextUsageProjection` —
  per-conversation `MutableStateFlow<Map>` and the decode-or-drop idiom.
- `app/src/main/java/de/pyryco/mobile/data/repository/ModelMenuProjection.kt` → `askForModelMenu`,
  `applyRefusal`, `modelListAsks` — the fire-and-forget send with its own refusal ledger, disjoint from
  `RelayRequests`' waiters, ids from the repository's one counter.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound` (the
  `TYPE_CONTEXT_USAGE` and `TYPE_ERROR` arms), the `modelMenuProjection` construction, the `TYPE_*` / `ERROR_*`
  companion constants.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeContextUsage`
  default and `ContextUsage`; `AttachmentOffer.toString` — the precedent for keeping claude text out of
  `toString`.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `switchToLive`,
  `refreshSessionSettings` (the one non-throwing forward via `currentRepository.value`).
- `app/src/main/java/de/pyryco/mobile/data/repository/HostReadings.kt` — deliberately **not** touched: the
  MCP reading is per connection (#1345 re-asks after a reconnect).
- `docs/knowledge/features/conversation-repository-conventions.md` § Conventions — cascade-escape defaults.
- `app/src/test/.../RemoteConversationRepositoryContextUsageTest.kt` → `FakeSessionPump`, `collect`, `probe`
  — the test fixture shape.

## Design source

**Figma:** N/A — data layer only; the Channel info list (#1344) and the failure notice (#1345) are separate tickets.

## Context

Mobile has no MCP handling. The daemon pushes `mcp_status` once per eligible child and answers
`mcp_status_request`, `mcp_reconnect` and `mcp_toggle` with the same frame (correlated by `in_reply_to`) or an
`error`. This ticket holds the report and five per-conversation flags so #1344 and #1345 have something to show.
No UI. The failure acknowledgements in desktop's store belong to #1345.

## Design

### Domain types (in `ConversationRepository.kt`, beside `ContextUsage`)

```kotlin
data class McpStatus(
    val report: McpStatusReport? = null,     // null = no report; a report with servers = [] = "no servers"
    val unavailable: Boolean = false,
    val reconnecting: Boolean = false,
    val reconnectRefused: Boolean = false,
    val toggling: Boolean = false,
    val toggleRefused: Boolean = false,
)
data class McpStatusReport(val servers: List<McpServerStatus>, val droppedServers: Int)
data class McpServerStatus(val name: String, val status: String, val error: String, val scope: String, val version: String)
```

One type carries report and flags together, so one `update` sets a report and clears the flags atomically and a
collector never sees a new report beside a stale flag. `McpServerStatus.toString` is overridden to leave every
string out (the `AttachmentOffer` precedent): all five are claude-authored.

### Contract (on `ConversationRepository`)

- `fun observeMcpStatus(conversationId: String): Flow<McpStatus> = flowOf(McpStatus())`
- `fun requestMcpStatus(conversationId: String) {}`
- `fun reconnectMcpServer(conversationId: String, serverName: String) {}`
- `fun toggleMcpServer(conversationId: String, serverName: String, enabled: Boolean) {}`
- `fun endMcpReconnectWait(conversationId: String) {}` / `fun endMcpToggleWait(conversationId: String) {}`

All non-suspending and non-throwing: the answer arrives on `observeMcpStatus`, never as a return value. The
defaults are the honest zero of a repository that can send nothing — the contract is "when nothing can be sent,
nothing is set", which is exactly a no-op — so the fake and the inline doubles need no change. This is the
`refreshSessionSettings` default shape, not the `error(...)` one: an un-sendable request is a defined outcome here,
not an accidental invocation.

### Wire DTOs (new file `data/network/McpStatusPayloads.kt`)

- `McpStatusPayloadDto(conversationId, servers: List<McpServerDto>, droppedServers: Int)` — every key
  strict-required, no Kotlin defaults; `McpServerDto(name, status, error, scope, version)` likewise.
- `McpStatusPayloadDto.toReport(): McpStatusReport?` — null for a negative `dropped_servers`; otherwise a
  verbatim copy in claude's order.
- `McpStatusRequestPayloadDto(conversationId)`, `McpReconnectPayloadDto(conversationId, serverName)`,
  `McpTogglePayloadDto(conversationId, serverName, enabled)` — request bodies. `enabled` has no default, so it is
  always encoded (also independent of `MobileJson`'s `encodeDefaults`).

### Projection (new file `data/repository/McpStatusProjection.kt`)

`internal class McpStatusProjection(send, negotiatedCapabilities, nextRequestId)`, the `ModelMenuProjection`
constructor.

State:
- `statusByConversation: MutableStateFlow<Map<String, McpStatus>>`.
- `asks: ConcurrentHashMap<Long, Ask>` — request envelope id → (verb, conversation id). Never the server name or
  the requested state. Disjoint from `RelayRequests`' waiters and `ModelMenuProjection`'s ledger because every id
  comes from the one counter.

Surface:
- `apply(envelope)` — removes `asks[inReplyTo]` if any, decodes, and on success replaces the entry with
  `McpStatus(report)` (all five flags false — `setMcpStatus`). Routing is the payload's own `conversation_id`.
  Malformed → nothing written.
- `applyRefusal(inReplyTo, payload)` — `asks.remove(inReplyTo)` or return. Status: decode `ErrorPayload.code`;
  only `mcp_status.unavailable` sets `unavailable`. Reconnect: `reconnecting = false, reconnectRefused = true`
  whatever the code (payload not even decoded). Toggle: the same pair. The report is never touched.
- `requestStatus(id)`, `reconnect(id, serverName)`, `toggle(id, serverName, enabled)` — shared private send:
  guards (empty conversation id; `interactive` not negotiated) return before anything is set. For reconnect and
  toggle the waiting flag is set **before** the send, so a refusal racing the send cannot be overwritten by a
  late begin; a refused or throwing send removes the ask and clears the flag only if this call set it.
- `endReconnectWait(id)` / `endToggleWait(id)` — clear only the waiting flag.
- `observe(id)` — `map { it[id] ?: McpStatus() }.distinctUntilChanged()`.

### Routing (in `RemoteConversationRepository`)

- Constants `TYPE_MCP_STATUS`, `TYPE_MCP_STATUS_REQUEST`, `TYPE_MCP_RECONNECT`, `TYPE_MCP_TOGGLE`,
  `ERROR_MCP_STATUS_UNAVAILABLE`, `ERROR_MCP_ACTUATION_REFUSED` (the last documents the merged code; tests use it).
- `mcpStatusProjection` built like `modelMenuProjection`, connection-scoped (not in `HostReadings`).
- `TYPE_MCP_STATUS` arm: `apply` behind `CAPABILITY_INTERACTIVE`.
- `TYPE_ERROR` arm: one more line, `mcpStatusProjection.applyRefusal(id, envelope.payload)`.
- The six overrides delegate in one line each.

### Facade (in `StableConversationRepository`)

`observeMcpStatus` via `switchToLive(McpStatus())`. The five commands forward through `currentRepository.value?.`
(the `refreshSessionSettings` shape): with no connection nothing can be sent, so nothing is set and nothing throws.
A reconnect publishes a fresh repository, so flags and report start empty on the new connection.
`CachingConversationRepository` delegates by interface and needs no edit.

## State + concurrency model

No new coroutine or scope. `statusByConversation` has two kinds of writer — the single inbound collector (`apply`,
`applyRefusal`) and caller coroutines (begin/end wait) — and every write is a `MutableStateFlow.update` CAS on
an immutable map, so writers cannot lose each other's change. `asks` is a `ConcurrentHashMap` for the same
reason. No cap on `asks`: each entry is one user action on one connection, consumed by its correlated reply or
refusal, and the whole projection dies with the connection.

## Error handling

A malformed `mcp_status` (missing or `null` key, wrong type, a server missing one of its five strings, negative
`dropped_servers`) drops that one envelope: prior report and flags stand, the collector survives, the caught
throwable is discarded (kotlinx-serialization can quote input). A refused or throwing `send` leaves no flag and
no ask. An `error` whose `in_reply_to` matches no ask is a no-op here.

**Nothing logs.** Matching every sibling projection; the ticket allows at most a static event name for a
refusal, and none is needed for these flags to be observable. Server names, statuses and errors are compared for
nothing and keyed on nothing in this ticket — they are only held for display.

## Testing strategy

Unit tests only (no UI). New `RemoteConversationRepositoryMcpStatusTest.kt` with the `FakeSessionPump` fixture,
JSON string fixtures:

- **decode/projection**: `report == null` before any frame; a push lands verbatim in claude's order with
  `dropped_servers`; `servers: []` is a report with no servers, not null; a later frame replaces; a reply with
  `in_reply_to` lands the same; a frame for `c2` leaves `c1` unchanged.
- **gate**: without `interactive`, a frame changes nothing and no request is sent.
- **malformed**: missing `servers`, `null` servers, missing `dropped_servers`, negative `dropped_servers`, a server
  missing `version`, a wrong-typed `name` — each leaves the prior report, a later valid frame lands.
- **encoding**: each verb's envelope type and exact payload JSON; `enabled:false` present.
- **nothing sent, nothing set**: a pump returning `false`, a closed gate and an empty id set no flag and leave no
  ask (a later error naming the would-be id changes nothing).
- **flags**: reconnect/toggle set waiting; a refusal of any code sets refused and ends the wait and leaves the
  report; `mcp_status.unavailable` on a status ask sets `unavailable`, `protocol.malformed` and
  `conversation.not_found` set nothing; a later report clears all five; end-wait clears only waiting (refused
  stays); a toggle refusal never settles as a reconnect one.
- **facade**: `StableConversationRepository` over a live repo delegates observe and commands; with no connection
  the commands neither throw nor send.

## Open questions

- Desktop's comment says a successful status reply "carries no in_reply_to"; the protocol says it is correlated.
  Consuming `asks[inReplyTo]` on `apply` is harmless either way.

## Documentation handoff

Pending for the documentation stage: a feature overview (or a section in the remote-repository overview) for
the MCP status reading, and the conventions list entries for the six new `ConversationRepository` members with
their no-op/`McpStatus()` defaults.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — one boundary: `McpStatusProjection`'s private decode turns the untrusted `mcp_status` payload into `McpStatusReport` through `McpStatusPayloadDto.toReport`, strict-required keys and a negative `dropped_servers` reject; nothing downstream sees the DTO. Routing is the payload's daemon-authored `conversation_id`; a frame naming another conversation can only write that conversation's entry, the same posture as `ContextUsageProjection`. Server strings are held for display only: never a map key, never compared in this ticket, never parsed (`version` stays opaque).
- [Trust boundaries] OUT OF SCOPE — the five server strings carry no length bound beyond the transport frame cap, and `error`'s 256-byte daemon cap is not sanitization. They reach Compose first in #1344, which must render them as inert, line-bounded text. They are not truncated here, because `name` is sent back verbatim as `server_name` and a truncated name would actuate the wrong server or none.
- [Trust boundaries] No findings — `server_name` crosses back to the daemon only as the value the user picked from a report; the daemon gates actuation on its own device permission before anything else (protocol § "Actuating MCP servers on demand"), so the phone adds no authority and needs no client-side check.
- [Tokens] No findings — no token, key or credential is created, stored or read.
- [Storage] No findings — the report and flags live only in the connection-scoped `McpStatusProjection`'s memory; not in `HostReadings`, not in the conversation cache, never persisted.
- [Android surface] No findings — no Activity, Intent, PendingIntent, provider, WebView or push path is touched.
- [Crypto] No findings — no primitive; envelope ids come from the existing per-connection counter and are correlation handles, not secrets.
- [Network & I/O] No findings — sends ride the existing Noise pump; nothing retries, so a refused or dropped ask cannot become a loop. A hostile daemon flooding distinct `conversation_id`s grows the map like every sibling projection, bounded by the connection lifetime.
- [Network & I/O] No findings — a refusal correlates only by an envelope id this connection minted, held in `asks`; an `error` with any other `in_reply_to` is a no-op. A toggle refusal can never settle a reconnect wait because each ask records its verb.
- [Logs] SHOULD FIX — `McpServerStatus.toString` must omit all five strings (the `AttachmentOffer.toString` precedent) so an accidental log or assertion message cannot carry claude text; `McpStatusReport` and `McpStatus` inherit that through their lists. Nothing logs; decode throwables are discarded since kotlinx-serialization can quote input; no exception is built from a server string.
- [Concurrency] SHOULD FIX — the begin-wait rollback must decide "did this call set the flag" atomically: read the prior value from the same `getAndUpdate` that sets it, not a separate read. Two concurrent begins on one conversation can still end a wait early if one send fails, which is a cosmetic, self-healing state (the next report or end-wait settles it); callers are the main thread in practice.
- [Concurrency] No findings — no coroutine is launched; all state writes are `MutableStateFlow.update` CAS or `ConcurrentHashMap` operations; the projection dies with its repository.
- [Threat model] No findings — a malicious relay can drop a reply and leave a wait open; `endMcpReconnectWait` / `endMcpToggleWait` from the surface that started it (#1344) is the designed release. A hostile daemon frame is decoded defensively and dropped whole when malformed.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01
