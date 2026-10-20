# MCP tools in the Context sheet

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/ContextUsagePayloads.kt`: `ContextUsagePayloadDto.toReading` owns strict decoding and bounded display text.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `ContextUsage` uses structural equality and defaulted detail fields.
- `app/src/main/java/de/pyryco/mobile/data/repository/ContextUsageProjection.kt`: `apply` replaces held readings only after decoding and freshness admission.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadReadEvidence.kt`: `understoodNonvisualEntry` shares the decoder.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `runConfigFlow` carries the complete reading without copying inventories.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ContextSummarySheet.kt`: `ContextSummarySheet` and `formatContextTokens` provide the existing sheet and figures.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt`: `MobileDismissModal` supplies scrolling and dismissal.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ContextSummarySheetTest.kt`: footer routing, geometry, ellipsis and scroll patterns.
- `app/src/test/java/de/pyryco/mobile/data/repository/ContextUsageProjectionTest.kt`: malformed frame retention and history certification.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryContextUsageTest.kt`: live repository delivery and fixture shape.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelContextUsageTest.kt`: held reading, updates and recollection.
- `docs/knowledge/features/thread-composer-footer-context-usage.md`: remembered inventories are unavailable; intended-valid fixtures must remain complete.
- `docs/knowledge/features/development-verification-gates.md`: shared screen tests use native graphics for ellipsis measurement.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`, `context_usage`: authoritative wire contract and independent dropped counts.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=919-8976 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=919-9341

Both MCP screenshots were inspected. After Categories, a 48 dp row has a labelLarge emphasized title, bodyMedium count and 20 dp chevron. Expanded rows have 12 dp leading indentation, 12 dp between groups and 8 dp within groups; server titles use labelLarge emphasized, names and fixed trailing token figures use bodyMedium, and the final dropped line uses bodySmall. Reuse the sheet's onPrimaryContainer/onBackground/onSurfaceVariant roles and existing chevron asset. Memory and allowance stay with siblings.

## Context

#2321 is merged at `7a58afac1`; its production consumers carry the complete reading already. This ticket delivers one useful behavior: inspecting MCP contributors in that sheet. Decoder plumbing has only this new consumer and stays with it. No decision record is needed.

Overlap: #2175 and #2245 add repository interface members elsewhere and #2175 adds an unrelated string; these do not restructure this design.

Forecast: about 650–850 written lines including plan and fixture completion, four production Kotlin/resource files, two new types, one private composable, at most six consumers/fixture families needing simultaneous edits, five acceptance criteria, and fewer than ten decoder rejection classes. Existing scalar constructors remain source-compatible through appended defaults. This fits the written-work and fan-out ceilings.

## Design

Add required `mcp_tools: List<ContextMcpToolPayloadDto>` and raw `dropped_mcp_tools` to the DTO. Entry name, server name and tokens use raw primitives to reject coercion. `toReading` validates every entry before returning a reading, bounds both strings to 512 characters like categories, and preserves received order. Add defaulted `mcpTools` and `droppedMcpTools` to `ContextUsage`, plus redacted structural `ContextMcpTool(name, serverName, tokens)`.

Render a private MCP group only for fresh Claude readings with entries or a positive own dropped count. It starts collapsed. Group with Kotlin's insertion-ordered `groupBy` so first server appearance and within-server order survive; duplicate tool names remain separate positional rows. Names never become Compose keys or tags. Display count uses nonnegative dropped count plus received length with overflow-safe unsigned arithmetic. Negative counts remain held verbatim, as existing scalars do, but are not shown as truncation. No count drives allocation or iteration. Use `formatContextTokens` for each figure, weighted one-line ellipsized Text for names, and existing modal scrolling.

## State and concurrency model

No new jobs, dispatcher or flow. Repository synchronized admission and StateFlow replacement stay intact. Expansion is UI-local `remember` state scoped to the mounted eligible group; changing inventory while open preserves expansion and replaces rows. Dismissal or hiding the group removes that state and reopening starts collapsed. Existing ViewModel visibility remains authoritative. Socket background/foreground ownership is unchanged.

## State transitions and identity reuse

| Event | Contract and test |
|---|---|
| Repeated expand/collapse | Only inventory visibility changes; `mcpFooterExpansionGroupsAndReplaces` |
| Same scalar reading, new inventory | Structural inequality emits replacement; `mcpInventoryPreservesOrderCountsAndReplacement`, `mcpInventorySurvivesViewModelUpdates` |
| Fresh to remembered/pending/Codex/empty | Hide group and discard local expansion; `mcpUnavailableStatesAndDroppedOnly` |
| Dismiss and reopen | Start collapsed; `mcpFooterExpansionGroupsAndReplaces` |
| Recollection or reconnect | Recollection exposes the complete latest reading, with existing reconnect scope retained; `contextSheetSurvivesRecollectionAndUsesLatestReading` and `reconnect_throughTheFacade_dropsTheOldReading_andAsksNothing` |
| Malformed or missing inventory | Preserve prior reading; cannot certify history; `malformedMcpInventoryCannotReplaceOrCertify` |
| Duplicate or hostile names | Independent positional rows, grouping only; `mcpFooterExpansionGroupsAndReplaces`, `mcpLongNamesEllipsizeAndScroll` |
| Process/configuration recreation | No persisted expansion; `mcpFooterExpansionGroupsAndReplaces` proves group disposal/recreation starts collapsed. Repository/VM source restoration is unchanged. |

## Error handling

Malformed required list, entry fields or dropped primitive reject the whole frame through the existing nullable decoder. Exceptions remain discarded, with no payload logging or new error UI. Unknown keys and empty lists remain valid. Existing scalar/category validation and read evidence use the same path.

## Testing strategy

Write rejection tests first and observe a failure against current code, then implement the decoder. Complete intended-valid Kotlin fixtures to include the required fields. Repository tests prove preservation, independent dropped counts, strict primitive kinds, redaction, bounds and replacement. ViewModel tests prove same-scalar inventory replacement and recollection while open. Extend shared native `ContextSummarySheetTest` for footer expansion, accessible action/state and 48 dp bounds, grouping order, duplicates, counts, collapse, reopening, dropped-only, remembered/Codex/pending/empty, update while expanded, actual ellipsis and scroll to the final entry. Controlled frames suffice; no new producer recording is needed. Run affected tests, lint, assembly, shared Android-test compilation and required formatting/final pre-verification. Retain Linux raw XML and counted results under `/tmp/builder-2322/`; dispatcher owns complete gates.

## Open Questions

None.

## Documentation handoff

- Pending documentation stage: `app/src/androidTest/assets/design-1220/README.md`, Context section: add expanded frame `919-9341`.
- Pending documentation stage: `docs/knowledge/features/thread-composer-footer-context-usage.md`, Context summary and decode boundary: document MCP grouping, independent truncation counts and remembered-reading omission.

## Security review

**Verdict:** PASS

- Trust boundaries: `ContextUsagePayloadDto.toReading` validates all fields together, bounds names and returns typed values. The sheet renders names only with Text; descriptive grouping never resolves a server or grants authority.
- Tokens/secrets: token counts are numeric figures, not credentials. DTO and domain toString implementations redact inventory strings; no new logging sink receives them.
- Files/storage: no inventory persistence, paths, file access or cache keys are introduced.
- Android attack surface: no component, intent, URI, WebView or exported surface changes.
- Cryptography: existing Noise transport and Keystore handling are unchanged.
- Network/I/O: reuse the existing capped context frame and request. Received entries alone bound iteration; dropped counts cannot allocate rows. Unsigned count arithmetic avoids signed overflow at Long.MAX_VALUE.
- Errors/logging: caught serialization errors remain discarded because messages may quote input. Existing static lifecycle logs remain; expansion introduces no remote action or new classified network error.
- Concurrency: no new coroutine; immutable list replacement retains StateFlow equality behavior and existing session admission.
- Threat model: a hostile daemon can provide inert bounded names or malformed frames; tests cover rejection and display. A relay's delay/reordering remains governed by existing admission. Disk theft is unaffected because no storage changes. Accessibility sees displayed names as intended, with no hidden credential content added.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-20

## Revisions

- 2026-10-20: Figma's downloaded chevron has a different path from the existing footer icon. Add a vector converted from that supplied SVG, tinted with the primary theme role. Fixture completion spans seven existing test files; no production constructor consumer needs migration. Raw evidence will also be copied to the persistent agents evidence directory before handoff.
- 2026-10-20: Final transition audit names the existing recollection/reconnect tests explicitly and ties composition-local expansion reset to the footer dismissal/reopening test. This clarifies coverage without changing the state design.
- 2026-10-20: Verifier finding 1 exposed weak ordering assertions in `mcpFooterExpansionGroupsAndReplaces`. Use interleaved tools with nonalphabetical server appearances and assert each server label and tool name/token pair at its positional tag, including duplicate names. Check that alphabetizing servers or reversing a server's tools fails those assertions. This repairs the existing acceptance test; production behavior and security boundaries stay as designed.
