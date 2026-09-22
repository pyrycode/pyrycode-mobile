# #810 — Carry each tool call's own input fields and parent identity

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `ToolUsePayloadDto`, `ToolResultPayloadDto` and their `toEvent()` mappers. This is the decode seam, and the live and history lanes both reach it.
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt` → `LiveSessionEvent.ToolUse` and `LiveSessionEvent.ToolResult`. These are the portable events the mappers produce.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` → `ToolCall`. This is the retained shape. Its `status` default already protects existing construction sites from a fixture cascade.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` → `withToolUse` and `withToolResult`. These are the shared folds. The history lane's decode arm in `reduceHistoryPage` decodes with the same DTOs.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `applyToolUse`, `applyToolResult` and `decodeLiveSessionEvent`. The live lane runs through the same DTO, mapper and fold, and each fold writes under `event.conversationId` only. **No edit is needed.**
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` → `MobileJson`, configured with `ignoreUnknownKeys = true` and `explicitNulls = false`. No `coerceInputValues` is set, so a wire `null` in a non-null `String` still fails.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryPageReducerTest.kt` → wire-JSON fixture helpers `toolUsePayload` and `toolResultPayload`.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` → `toolUseEnvelope`, `historyPageEnvelope`, `startRequestHistory` and `collectMessages`. These existing harnesses support the lane-parity test and the cross-conversation test.
- `docs/knowledge/features/live-tool-call.md` → the tool-row state machine and its tolerance table. The idempotent `tool_use`, the drop of orphan results and the last-write-wins result must stay unchanged.
- pyrycode `docs/protocol-mobile.md` § `tool_use` and § `tool_result` are the wire contract. This plan does not restate it.
- The desktop test `src/main/transport/toolParent.test.ts` shows the parent-id reference behaviour: an absent value decodes to empty, and values are carried verbatim.

## Design source

Figma: N/A. This is a data-layer-only ticket. Rendering belongs to #658, and the verifier should skip the visual-fidelity check.

## Context

The daemon already sends `tool_use.input`, a string-to-string map, and `parent_tool_use_id` on both tool frames. Mobile currently drops both because `ignoreUnknownKeys` discards them. This ticket carries them into the retained `ToolCall` on both lanes. It adds no new behaviour and no rendering. No ADR is needed.

## Design

### Decode (`InteractivePayloads.kt`)

- Add `@SerialName("parent_tool_use_id") val parentToolUseId: String = ""` to `ToolUsePayloadDto` and `ToolResultPayloadDto`.
- Add `val input: JsonElement? = null` to `ToolUsePayloadDto`. It is typed as a raw element rather than `Map<String, String>` for these reasons:
  - The AC requires an absent, empty or non-object input to produce an empty set rather than a decode failure.
  - A typed map would fail on a wire `null`, an array or a string, and that would drop the whole `tool_use` frame.
- Add a new private helper, `JsonElement?.toInputFields(): Map<String, String>`:
  - It returns `emptyMap()` unless the element is a `JsonObject`.
  - For an object, it keeps every entry whose value is a JSON **string**, uses that entry's `content` verbatim and preserves wire iteration order.
  - It skips an entry with a non-string value. This is off-contract because the daemon stringifies every value, so the posture fails closed per field and does not rewrite the value.
- `ToolUsePayloadDto.toEvent()` passes `input.toInputFields()` and `parentToolUseId`. `ToolResultPayloadDto.toEvent()` passes `parentToolUseId`.
- The KDoc states the posture decision the ticket asks for. The new keys are **lenient-defaulted**, unlike the strict-required fields in sibling DTOs:
  - The daemon always emits them, so an absent key means an older binary.
  - Under the contract, such a binary meant "main thread" and "no fields". Absence therefore decodes to `""` and `{}` rather than dropping the frame.

### Event (`LiveSessionEvent.kt`)

- Add `ToolUse.parentToolUseId: String = ""` and `ToolUse.input: Map<String, String> = emptyMap()` as trailing defaulted parameters.
- Add `ToolResult.parentToolUseId: String = ""` as a trailing defaulted parameter.
- The trailing defaults keep the positional test constructions compiling, including those in `ThreadViewModelTest`, `RemoteConversationRepositoryTest` and the androidTest harness.
- The KDoc states that both values are inert display and grouping data, carried verbatim.

### Retained model (`Message.kt`)

- Add `ToolCall.inputFields: Map<String, String> = emptyMap()`. It is named `inputFields` because `input` is already the précis string.
- Add `ToolCall.parentToolUseId: String = ""`, where empty means the main thread.
- Both new fields have trailing defaults, following the `status` precedent. No construction site changes.

### Folds (`HistoryPageReducer.kt`)

- `withToolUse` sets `inputFields = event.input` and `parentToolUseId = event.parentToolUseId` on the new row. Its idempotence is unchanged: a repeat id leaves the row untouched.
- `withToolResult` keeps the use's parent when the result's parent is empty. When the result's parent is non-empty, it replaces the row's parent verbatim, alongside the existing output and status update.
  - A conforming daemon sends identical values on both frames, so this rule matters only when a mid-stream daemon upgrade or downgrade makes one frame lack the key.
  - In that case the rule keeps whichever frame actually names a parent. It never un-nests a row because the key was absent. This resolves the AC's instruction to carry the value "from both" frames.
- The rule never touches `inputFields`, because `tool_result` carries no input.

### Lanes and isolation

- The live lane runs `decodeLiveSessionEvent`, then `applyToolUse` and `withToolUse` under `event.conversationId`.
- The history lane runs `reduceHistoryPage`, which decodes with the same DTO and mapper, then calls `withToolUse` under the asked conversation.
- Both lanes already share the DTO, mapper and fold, so AC #3 holds structurally and the tests below prove it. The per-conversation map update provides AC #4, which the tests also pin.

## State + concurrency model

This ticket makes no change. The pure folds still run inside the existing atomic `threadByConversation.update {}`.

## Error handling

- An absent or odd `input` degrades to `{}`, and an absent `parent_tool_use_id` degrades to `""`. Neither causes a decode failure.
- A wire `null` for `parent_tool_use_id` remains a strict decode failure that drops the frame through the existing `catch`. The contract says the value is never null, and the desktop client also rejects it.
- Nothing is logged. The values are content, and the rule is to log shape rather than content.

## Testing strategy

All tests are JVM unit tests (`testDebugUnitTest`).

- **New `app/src/test/java/de/pyryco/mobile/data/network/ToolPayloadsTest.kt`** decodes through `MobileJson` and `toEvent()`:
  - The input fields arrive verbatim. The fixture includes an embedded newline, a trailing `…`, a traversing path and non-ASCII text, and the test asserts wire key order.
  - An absent `input`, `{}`, `null`, an array and a string each produce an empty map without throwing.
  - A non-string value inside the object is skipped, and its sibling string fields survive.
  - `parent_tool_use_id` arrives verbatim on both frames, including a value with spaces and `<x>`. An absent key decodes to `""`.
- **`HistoryPageReducerTest`** adds these cases:
  - A `tool_use` with input and parent produces a row whose `ToolCall` carries both.
  - A `tool_result` with a non-empty parent sets the row's parent.
  - A `tool_result` with an empty parent keeps the use's parent.
  - The existing fixtures keep the no-key case as their default.
- **`RemoteConversationRepositoryTest`** adds these cases:
  - **Lane parity (AC #3):** the same `tool_use` and `tool_result` payload pair is pushed live for `c1` and folded through a `history_page` reply for `c2`. The two `toolCall` values must be equal. Timestamps legitimately differ by lane, so the test compares `ToolCall`.
  - **Isolation (AC #4):** a live `tool_use` for `c1` leaves `c2`'s thread unchanged, including a `tool_result` for `c1` that reuses the same `tool_use_id` as a row in `c2`.
- No rung-3 or rung-4 scenario is needed because this change is not operator-facing, and #658 renders it.

## Documentation handoff

The ticket body names no documentation requirement. `live-tool-call.md` § The model and `data-model.md` describe `ToolCall` and could fold in the two new fields. That work is **pending for the documentation stage**.

## Open questions

- A non-string value inside `input` is resolved above: the entry is skipped.
- The disagreement between the `tool_result` parent and the `tool_use` parent is resolved above: a non-empty result parent wins, and an empty one keeps the row's parent.

## Security review

**Verdict:** PASS

**Findings:**

- **Trust boundaries: no findings.**
  - The only boundary is the decode seam, `ToolUsePayloadDto.toEvent()` / `ToolResultPayloadDto.toEvent()` through `MobileJson`. Downstream code holds typed `Map<String, String>` and `String` values only, and the lenient `JsonElement?` never leaves `toInputFields`.
  - The values stay untrusted because they are model-authored text the daemon did not validate. The KDoc on `ToolCall` and `LiveSessionEvent.ToolUse` states that they are inert display and grouping data: never a path to open, a command to run, an HTML, attribute or URL sink, or a log line.
  - Nothing in this ticket renders or dereferences them.
- **Trust boundaries (length): no findings.**
  - Mobile deliberately does not re-truncate because the AC requires verbatim values. The daemon caps each value at 4000 runes, the map at 8500 runes across at most 16 fields, and the parent at 256 bytes.
  - A hostile daemon that ignored those caps is still bounded by the 65519-byte application-envelope cap. The same cap applies to each stored `history_page` entry. A frame can therefore hold at most about 64 KB of fields, so it creates no memory-exhaustion vector beyond what `input_summary` and `result_summary` already allow.
- **Trust boundaries (cross-conversation): no findings in this ticket.**
  - Each fold writes only under `event.conversationId` on the live lane and under the asked conversation on the history lane. AC #4's test pins this, including a colliding `tool_use_id` across conversations.
  - OUT OF SCOPE for #658: the parent join must match `parentToolUseId` only against `tool_use_id` rows in the **same** conversation's thread. It must render a row at top level when nothing matches, and it must never treat the id as anything but an equality key.
- **Tokens and secrets: no findings.** No credential is created, stored or compared.
- **File and storage: no findings.**
  - A `file_path` value is carried as a string and never touched as a path, so no filesystem API receives it.
  - The retained rows are in-memory and connection-scoped. This ticket adds no persistence.
- **Android attack surface: no findings.** The ticket adds no intent, deep link, provider, push path or WebView.
- **Cryptography: no findings.** It changes no primitive or Noise code.
- **Network and I/O: no findings.** It changes no transport. An off-contract `input` shape degrades to `{}` rather than dropping the frame, while a wrong-typed parent still fails the strict decode. Either way, only that one envelope is affected and the collector survives.
- **Logs and telemetry: no findings.** Neither new field is logged, and the plan adds no log call.
- **Concurrency: no findings.** The folds stay pure inside the existing atomic `update {}`.
- **Threat model (hostile daemon frame): no findings.** Every new field decodes defensively: odd input becomes `{}`, a non-string value is skipped, and an absent parent becomes `""`. Rendering them as inert text is #658's responsibility. This finding is OUT OF SCOPE here and inherited by #658.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22
