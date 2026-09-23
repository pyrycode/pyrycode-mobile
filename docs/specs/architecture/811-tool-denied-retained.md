# #811 — Retain a refused tool call as denied rather than failed

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` → `ToolCall`, `ToolCallStatus` — the retained tool row; gains a `Denied` status value and a defaulted `denial` field.
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt` → `LiveSessionEvent` — deliberately **not** extended (see Design).
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `ToolResultPayloadDto`, `RateLimitedPayloadDto.truncatedFields` (the nullable-report-array shape to copy), `UnrecognizedMessagePayloadDto` (strict-required shape), `QueueStatePayloadDto.toQueue` (the coalescing mapper **not** to copy).
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` → `withToolResult` (must stop overwriting `Denied`), `withHistoryEntry` (replay type gate), `decodeLiveEvent`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound`'s structured-stream arm, `decodeLiveSessionEvent`, `applyToolResult` (the per-conversation fold wrapper), companion `TYPE_TOOL_RESULT`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt` → `ToolCallStatusIcon`, the one exhaustive `when` over `ToolCallStatus`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → its exhaustive `when`s over `LiveSessionEvent` (four sites) — the cost a new event subtype would impose.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt` → `CachedToolCall` persists `status` by enum name; `denial` is not persisted (the #810 `inputFields` precedent).
- `docs/knowledge/features/live-tool-call.md` § "The model", "The state machine" — the fold is keyed `id == toolUseId && role == Role.Tool`; statuses are added as defaulted trailing fields to avoid a fixture cascade.
- pyrycode `docs/protocol-mobile.md` § `tool_denied` — wire contract (not restated).
- pyrycode-desktop `src/main/transport/inboundMessage.ts` → `parseToolDeniedPayload` — all seven strings required, both arrays "string array or null", no token interpretation.

## Design source

**Figma:** N/A — nothing the user sees changes in this ticket. #658 owns the denied row's visual design; the one compile-forced arm in `ToolCallStatusIcon` reuses the failed presentation unchanged.

## Context

A refused tool call reaches mobile today only as the `tool_result` claude writes afterwards (`is_error: true`), so it is retained as `Failed`. The daemon's `tool_denied` frame (pyrycode#2233) says a call was blocked; mobile ignores it on the live lane and drops it in the replay reducer's `else`. This ticket decodes the frame on both lanes and retains the denial on the tool row. No ADR warranted.

## Design

**Domain type** (`Message.kt`, beside `ToolCall`):

- `enum class ToolCallStatus { Running, Done, Failed, Denied }` — appended value.
- `data class ToolDenial(toolName: String, decisionReasonType: String, decisionReason: String, message: String, truncatedFields: List<String>?, droppedFields: List<String>?)` — every field verbatim; the two arrays keep `null` distinct from `[]`. No per-field helper: the three states ("claude sent nothing" / "daemon emptied" / "daemon cut") are decidable from the value plus membership in the two lists, and the consumer (#658) owns that reading.
- `ToolCall.denial: ToolDenial? = null` — trailing defaulted field, so no construction cascade. Set only by the denial fold; a `Denied` row restored from the disk cache carries `null` (details not persisted, out of scope).

**Why not a `LiveSessionEvent` subtype.** `ThreadViewModel` has four exhaustive `when`s over `LiveSessionEvent`; a subtype forces arms there for an event nothing on the stream consumes. The denial is folded into the thread store only, like `unrecognized_message`, and is not emitted on `liveSessionEvents`.

**Decode seam** (`InteractivePayloads.kt`):

- `@Serializable internal data class ToolDeniedPayloadDto` — seven required `String`s (`conversation_id`, `turn_id`, `tool_use_id`, `tool_name`, `decision_reason_type`, `decision_reason`, `message`) and `truncated_fields` / `dropped_fields` as `List<String>? = null` (the `RateLimitedPayloadDto.truncatedFields` shape).
- `internal fun ToolDeniedPayloadDto.toDenial(): ToolDenial` — total verbatim copy; `conversationId` / `toolUseId` / `turnId` stay routing keys on the DTO, not in the retained value.

**Shared fold** (`HistoryPageReducer.kt`, beside `withToolResult`):

- `internal fun List<ThreadItem>.withToolDenied(toolUseId: String, denial: ToolDenial): List<ThreadItem>` — finds the row via `indexOfMessage(toolUseId, Role.Tool)`; none → return `this` unchanged (no row added). Hit → in-place copy with `status = Denied`, `denial = denial`; output, input, parent and position untouched. Whatever the row's prior status (`Running`, `Done`, `Failed`, already `Denied`), denial wins — covers result-line recovery where the result shipped first. A repeat denial is last-write-wins.
- `withToolResult` changes one expression: if the row's status is already `Denied`, keep `Denied` (and its `denial`); still attach `output` and apply the parent rule. Covers "result after denial".

**Live lane** (`RemoteConversationRepository.kt`):

- Companion: `const val TYPE_TOOL_DENIED = "tool_denied"` beside `TYPE_TOOL_RESULT`.
- `onInbound`: a new `TYPE_TOOL_DENIED` arm, gated on `CAPABILITY_INTERACTIVE` like its siblings, calling `applyToolDenied(envelope)`.
- `private fun applyToolDenied(envelope: Envelope)` — decodes `ToolDeniedPayloadDto` inside one `try`/`catch (IllegalArgumentException)` (malformed → return, envelope dropped, collector alive), then `threadByConversation.update` writing only `dto.conversationId`'s slice through `withToolDenied`. **When the map holds no slice for that conversation, the update returns `current` unchanged** — a denial never adds a row, so it must not mint an empty entry (see Security review, Trust boundaries). The DTO never leaves the method. Does not clear the stall projection or emit on `liveSessionEvents` (a report, not forward progress this ticket needs to model).

**Replay lane** (`HistoryPageReducer.kt`): a `TYPE_TOOL_DENIED` arm in `withHistoryEntry`, gated on `interactive`, decoding the same DTO and calling the same `toDenial()` + `withToolDenied`. The existing `try` owns the malformed drop. `decodeLiveEvent` is untouched (not a live event).

**UI compile fix** (`ToolCallRow.kt`): `ToolCallStatus.Failed, ToolCallStatus.Denied ->` in `ToolCallStatusIcon` — the failed presentation unchanged. `ExpandedBody`'s `!= Running` gate needs nothing.

## State + concurrency model

No new jobs, flows or dispatchers. Both lane writes are single atomic `MutableStateFlow.update` calls on `threadByConversation` from the existing single inbound collector; the replay fold is pure. Per-conversation isolation comes from writing only the frame's own `conversation_id` slice.

## Error handling

Malformed payload (missing/wrong-typed required string, non-array non-null report) → `SerializationException` ⊂ `IllegalArgumentException` → that one envelope/entry dropped silently. Unknown `tool_use_id` → no-op. No logging on any branch, matching every `onInbound` arm and the reducer's header: the payload carries claude prose and host paths.

## Testing strategy

JVM unit tests only (no UI change).

- `HistoryPageReducerTest` (wire-JSON fixtures, add a `toolDeniedPayload` helper):
  - page `tool_use` + `tool_denied` + `tool_result(is_error)` → one row, `Denied`, `denial` fields verbatim, output = result summary.
  - page with result before denial (recovery order) → still `Denied`.
  - denial naming no row → no row added.
  - report arrays: `null` vs `[]` retained distinctly; `dropped_fields:["tool_name"]` with empty `tool_name`, and `truncated_fields:["message"]` retained verbatim.
  - malformed denial (missing `message`) → dropped, other rows of the page still reduced.
  - non-interactive → ignored.
  - `withToolResult` on a `Denied` row keeps `Denied` (direct fold test).
- `RemoteConversationRepositoryTest` (FakeSessionPump):
  - live `tool_use` → `tool_denied` → `tool_result(is_error)` → `Denied`, distinct from a plain failed result.
  - live result before denial → `Denied`.
  - denial for conversation c2 with the same `tool_use_id` leaves c1's row untouched.
  - malformed denial dropped, a later `tool_result` still folds (stream alive).
  - no `interactive` capability → no effect.
- No rung-3/rung-4 scenario: not an operator-facing flow change in this ticket (#658 renders it).

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/live-tool-call.md` § "The model" / "The state machine" should gain the `Denied` status, `ToolDenial`, and the two `tool_denied` rows (denial wins over any prior status; a later result keeps it). The ticket names no other doc requirement.

## Open questions

- None blocking. Whether a denial should clear an active stall is left out (not in the AC; no observed stall-on-denial).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings on placement — the boundary is the single DTO `ToolDeniedPayloadDto` decoded through `MobileJson` in exactly two places (`applyToolDenied`, `withHistoryEntry`), both mapping through the same `toDenial()`; downstream code holds only `ToolDenial`. A hostile or buggy daemon can at worst **relabel a row it already opened** in the same conversation as denied (including a `Done` one): the contract makes the frame a report, nothing in the client keys a behaviour on it, and the fold cannot create a row (`indexOfMessage(…, Role.Tool)` miss → unchanged) or touch a `message_id` / assistant row (role-namespaced match). Cross-conversation reach is closed by writing only the frame's own `conversation_id` slice.
- [Trust boundaries] SHOULD FIX (adopted in Design) — the `applyToolResult` idiom `current + (id to current[id].orEmpty()…)` mints an empty slice for a never-seen `conversation_id`; for a frame that can never add a row that is pure state growth a hostile daemon could drive with random ids. `applyToolDenied` returns `current` when no slice exists. The verifier should check the unknown-conversation case leaves the map unchanged.
- [Trust boundaries] OUT OF SCOPE → #658 — `message` / `decision_reason` are claude prose that may quote a refused command line and name absolute host paths; `tool_name` / `decision_reason_type` are open-set tokens. This ticket retains all four verbatim with no client-side length cap (daemon caps at construction; `OkHttpRelayTransport`'s 65519-byte envelope cap bounds them again, the sibling-DTO posture). The render obligations — inert, attributed text, control/escape stripping, switching tokens against known values with an unknown fallback — belong to #658, which renders the denied row. Stated in `ToolDenial`'s KDoc so they travel with the value.
- [Tokens / secrets] No findings — the frame carries no credential; nothing here generates, stores or compares a secret.
- [File / storage] No findings — `CachedToolCall` persists only the `status` enum name; `ToolDenial` (with its host paths and command lines) is never written to disk, so the cache gains no new sensitive content. No path is built from any field.
- [Android attack surface] No findings — no intent, deep link, provider, push or WebView touched.
- [Crypto] No findings — no primitive touched; the frame arrives inside the existing Noise session.
- [Network & I/O] No findings — no new socket, timeout or frame-size change; size is bounded by the existing transport cap.
- [Logs] No findings by construction — no log call on any branch; the caught `SerializationException` (whose message can quote input) is discarded, the `decodeLiveSessionEvent` idiom.
- [Concurrency] No findings — one atomic `MutableStateFlow.update` from the existing single inbound collector; the replay fold is pure. No new coroutine.
- [Threat model] Hostile daemon frame: malformed → one envelope dropped, stream alive (tested). Malicious relay: content-blind, can drop/reorder — reorder of result vs denial is covered by denial-wins-and-sticks (tested both orders); a dropped denial degrades to today's `Failed`.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
