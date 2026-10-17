# #2221: restored subagent rows keep their Agent parent

## Files read

- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: `CachedMessage`, `CachedToolCall`, `ThreadItem.toRecord`, `CachedThreadRow.toDomain`, `encodeLegacyRow`, `decodeLegacyRows`, `cachedThreadRowProof`, `threadReadJson`. The legacy row codec; it drops both parent ids. #1612 (#1575) fixed the same omission for `inputFields` with a defaulted field.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt`: `MobileJson` is `encodeDefaults = true`, `explicitNulls = false`. A non-null defaulted field would appear on every encoded row; a `null` one is omitted.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryCoverage.kt`: `historyRowProofs` hashes `cachedThreadRowProof` for ordinary rows, and stored coverage keeps those hashes. A row whose encoding changes changes its proof.
- `app/src/main/java/de/pyryco/mobile/data/cache/LegacyRoomStorage.kt`: `writeThread` and `decodeRows` share `encodeLegacyRow`/`decodeLegacyRows`, so the fix reaches it with no edit.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt`: `Message.parentToolUseId` (assistant prose attribution) and `ToolCall.parentToolUseId` (tool attribution), both `""` for the main thread.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt`: `toolNestingDepths` joins `ToolCall.parentToolUseId` to a loaded tool row's `ordinaryId`; its KDoc names the cache gap.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocks.kt`: `foldBackgroundAgentBlocks` owns tool rows by the same chain and prose rows by `Message.parentToolUseId`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ToolRowNestingTest.kt`, `ScriptedBackgroundAgentToolsTest.kt`: the screen assertions to mirror (`cd_tool_subagent_step`, `background-agent-child:<agentId>`).
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_replyStaysUnderAgent`, the live-method shape (peer recording, `allowPromptsUntil`, `firstOnLive`) for the capture.

Overlap: many in-flight branches touch `InteractiveStreamE2ETest.kt`; this ticket only appends one method there.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=795-7178

The existing running Agent frame: the Agent header with its tool children indented under it. This ticket changes no layout, token or component; it restores the data that lets the existing indent and background block render. The Figma tools were unauthenticated in this session, and the ticket states it is not a design change, so no visual comparison is made.

## Context

Production runs the legacy client-built thread (`thread` is not advertised in hello; activation is #2175). Nesting there depends on each row's parent id. The thread cache writes neither `ToolCall.parentToolUseId` nor `Message.parentToolUseId`, so every row restored from cache reads back with `""` and renders top level. The live symptom has no code-level cause; the live capture below decides it. No decision record needed.

## Design

Two optional fields, both `String? = null`:

- `CachedToolCall.parentToolUseId`: written from `ToolCall.parentToolUseId.ifEmpty { null }`, read back as `.orEmpty()`.
- `CachedMessage.parentToolUseId`: the same for `Message.parentToolUseId`.

Nullable rather than `""`-defaulted on purpose: with `explicitNulls = false` a main-thread row encodes byte-for-byte as it did before, so its `cachedThreadRowProof` and every stored coverage hash for it stay valid. Only rows that carry a parent gain the key. A document written before this change has no key and reads back with `""`, top level, as it does today. No `VERSION` bump: the change is additive and old readers ignore unknown keys (`ignoreUnknownKeys = true`).

Nothing in the UI changes. `toolNestingDepths` and `foldBackgroundAgentBlocks` already nest restored rows once the ids survive; the stale clause in the `toolNestingDepths` KDoc is corrected.

This supersedes #1826's storage decision for `Message.parentToolUseId` (`1826-assistant-parent-attribution.md`: "Disk serialization is unchanged", and in its security review "Attribution is not serialized"). #1826 accepted that a restart loses attribution until fresh evidence arrives; this ticket's first criterion requires the reopened thread to nest as before, which in-memory enrichment cannot give. Storing it is acceptable because:

- A parent id is a daemon-issued `tool_use` id, the same opaque value the cache already stores as the parent Agent row's own id (`ordinaryId` is what nesting joins to). No new kind of data reaches disk.
- It sits in the same app-private thread document as the message text and tool input already stored there, under the same custody.
- It stays an inert grouping hint: never logged, never a filename or cache key, granting no authority. A tampered document can at most misgroup rows inside its own conversation, as a hostile daemon already can live.
- #1826's reconciliation is unchanged: a restored empty parent is still filled in memory from wire evidence, and an empty hint never erases a known parent.

## State and concurrency model

No new jobs, flows or dispatchers. The codec runs inside the existing `FileConversationCache` mutex on its injected IO dispatcher.

## State transitions and identity reuse

| Event | Behaviour | Covered by |
|---|---|---|
| Reopen a thread from the cache (process restart or screen re-entry) | Tool and prose rows keep their parent; foreground children indent, background children fold under their Agent | `RestoredSubagentNestingTest` |
| A restored row is written again (next `writeThread`) | Round-trips unchanged | `LegacyRowCodecTest` round trip |
| Document written before this change | Reads back, parents `""`, top level | `LegacyRowCodecTest` pre-change payload |
| Main-thread row encoded after the change | Same bytes, same proof as before | `LegacyRowCodecTest` encoding has no parent key |
| Coverage certified before the change for a row with a parent, later met by a history copy carrying the parent | The proofs differ, so that coverage stops matching and the page is fetched again; one-time, degrades to a refetch, never an error | Existing `HistoryCoverage` behaviour, unchanged |

## Error handling

No new failure modes. A malformed document is still rejected whole by the existing decoder.

## Testing strategy

- `LegacyRowCodecTest` (unit): a tool row and a prose row with parents round-trip through `encodeLegacyRow`/`decodeLegacyRows`; a literal pre-change payload decodes with empty parents; a parentless row encodes with no `parentToolUseId` key.
- `RestoredSubagentNestingTest` (new, `app/src/sharedTest/.../ui/conversations/thread/`, Robolectric): writes a foreground Agent with a child tool, and a background Agent (`run_in_background=true`) with a child tool and a child prose row, through a real `FileConversationCache`, reads them back with `readThread`, and mounts `ThreadScreen` with the restored items and a roster naming the background `local_agent`. Asserts the foreground and background child tools each carry `cd_tool_subagent_step` level 1, and the background child prose sits under `background-agent-child:<agentId>`, which wraps assistant prose only. This is AC2's regression test; it fails on main because the restored parents are empty.
- Live capture (AC3): new method `InteractiveStreamE2ETest#interactiveTurn_foregroundAndBackgroundAgent_childToolsNestUnderAgent`, run once through `python3 scripts/android-test-gate.py live --tests`. One turn launches a foreground and a background Agent, each running one Bash call. It records whether each child `tool_use` carries `parent_tool_use_id` equal to its Agent's `tool_use_id` in a content-free log line, asserts it, and asserts the loaded rows nest live (`toolNestingDepths` depth 1 and the rendered row's subagent description). Device-only because it needs the real daemon and Claude. The finding is posted on the ticket; a daemon or Mobile follow-up is filed per AC3.

## Open Questions

- Whether the released daemon sends matching parents live. Resolved by the capture: both the foreground and the background child `tool_use` carried their Agent's id, and both rows nested live (daemon source `0fa1b5f`, v0.40.4-189). No follow-up ticket.

## Documentation handoff

- `docs/knowledge/features/conversation-cache-layout.md`, the `CachedToolCall` field list ("`parentToolUseId`, `denial` and `resultDetail` stay uncached") and the #1826 assistant-attribution paragraph ("absent from disk serialization"): record that both parent ids are now persisted, nullable and omitted when empty, that cache-only rows restore with their stored parent, and that pre-#2221 documents still restore empty and are enriched in memory. Pending for the documentation stage.

## Revisions

- 2026-10-17: the live method is also added to the curated LIVE list in `scripts/e2e-emulator.sh`, beside the other Agent methods. The live gate only accepts curated methods, so the capture cannot run without it. Design unchanged.
- 2026-10-17, after verifier review: option (b) of its inventory finding. The one-off AC3 probe `interactiveTurn_foregroundAndBackgroundAgent_childToolsNestUnderAgent` is removed from `InteractiveStreamE2ETest` and from the curated LIVE list once its capture ran. A routine method in that class must also be a recorded suite row with a replay (`suite.json`, `liveSelection`, `RecordedMobileSuiteTest`), and the question the probe answers is a one-time diagnosis, not a behaviour to replay. The capture stays the AC3 evidence: it ran at Mobile revision `95cb6b342477fb52279ab45ec4b933e2382386bf`, which is still in this branch's history, and its XML, logcat and daemon logs are retained outside Git under `logs/evidence/2221/` in the agents repository. The shipped change is the cache fix and its two tests only.
- 2026-10-17, after verifier review (rework 2): `AssistantParentCacheTest`'s disk-format test asserted #1826's unattributed disk format and failed under this change. It is rewritten to the new contract and renamed `diskFormatKeepsAttribution_wireEvidenceEnrichesUnattributedRows_andReconnectKeepsIt`: an attributed write restores with its parent, an unattributed document still gains the parent from wire evidence and keeps it across a reconnect, and the collector's final flush persists it. The Design section now records that this plan supersedes #1826's "attribution is not serialized" decision for `Message.parentToolUseId`, with the reasons.
- 2026-10-17: the Testing strategy wording for `RestoredSubagentNestingTest` is corrected. The background child tool is asserted through its level-1 subagent step; `background-agent-child:<agentId>` wraps only the background child prose.
