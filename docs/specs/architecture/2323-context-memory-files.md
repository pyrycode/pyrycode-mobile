# Expand context memory files (#2323)

## Files read

- `docs/knowledge/features/thread-composer-footer-context-usage.md`: Context sheet contract, strict shared decode, positional identity and complete wire fixtures.
- `docs/knowledge/features/thread-screen.md`: destination and ViewModel ownership.
- `docs/knowledge/features/development-verification-gates.md`: shared native tests, 320dp width and NATIVE text measurement.
- `app/src/main/java/de/pyryco/mobile/data/network/ContextUsagePayloads.kt`: `ContextUsagePayloadDto.toReading`, the sole inventory validation boundary.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `ContextUsage`, defaulted scalar-compatible constructors and structural equality.
- `app/src/main/java/de/pyryco/mobile/data/repository/ContextUsageProjection.kt`: replacement, rejection and recollection behavior.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadReadEvidence.kt`: `understoodNonvisualEntry` shares that decoder.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ContextSummarySheet.kt`: shared scrolling modal, category order and token format.
- `app/src/test/java/de/pyryco/mobile/data/repository/ContextUsageProjectionTest.kt`: inventory rejection and retained reading assertions.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelContextUsageTest.kt`: held reading forwarding and replacement.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ContextSummarySheetTest.kt`: footer opener and native layout assertions.
- pyrycode `docs/protocol-mobile.md`, section `context_usage`: authoritative inventory and provenance contract.
- Desktop `shortenPath.ts` and `ContextBreakdownPopover.tsx`: display reference; Mobile reuses its existing desktop-compatible token formatter.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=919-8976 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=919-9341

The screenshots show Memory files below MCP tools (below Categories when MCP is absent), a 48dp header with semibold labelLarge title, bodyMedium count, 8dp gaps and 20dp chevron. Expanded rows have a 12dp leading inset, 8dp row gaps, bodyMedium path/token text, and one trailing bodySmall truncation notice. Use the sheet's onPrimaryContainer/onBackground/onSurfaceVariant/primary roles and the Figma chevron asset; preserve the existing shared modal and token formatter. Allowance and MCP implementation remain with their siblings.

## Context

One independently useful behavior: reveal which memory files contribute tokens in the working Context sheet from #2321. Strict decoding and display form one consumer slice. No request or filesystem access is added; no decision record is needed.

Sizing: approximately 650 written lines including plan, production, fixtures and tests; two new types and one internal display helper, zero mandatory updates to existing domain constructors, five acceptance criteria and no new state-machine reject branches. All numeric ceilings hold. Overlap with #2322 is additive inventory fields and a separate group; #2175 and #2245 touch unrelated repository members/resources. Keep edits local and merge any landed MCP group ahead of Memory files.

## Design

Add defaulted `memoryFiles: List<ContextMemoryFile>` and `droppedMemoryFiles: Long` to `ContextUsage`. The entry carries `path`, `type`, `tokens`, with a redacted string representation. DTO fields are required; string and integer primitives are checked without coercion. Missing/null/wrong list shapes or required entry fields reject the entire reading through the existing decoder, including history read evidence. Unknown fields and empty lists remain valid. Bound path to 2048 characters and type to 512 at the boundary; neither drives behavior. Preserve existing scalar/category/MCP behavior.

Add a private memory group to `ContextSummarySheet` only for fresh Claude readings with entries or positive drops. Its local expansion starts false. Render received order using positional slots/tags, including duplicate paths. Header count is received size plus this inventory's nonnegative dropped count, using ULong to avoid overflow; counts never size a collection. Expanded path uses a pure slash-only display helper: ignore empty segments for counting; preserve the original when at most four remain, otherwise `.../` plus the final four. Dot segments and backslashes are text. Paths reach single-line ellipsized Text; token figures have reserved trailing width. Type is retained but not rendered or acted on. The shared modal provides scrolling.

## State and concurrency model

No new coroutine or flow. Existing `ContextUsageProjection` replaces its held immutable reading, and existing ViewModel flow forwards it. Expansion is UI-local `remember`, independent of MCP expansion and retained through ordinary reading replacement while the group exists; disappearing or dismissing the group removes its state. Existing socket background/foreground ownership stays unchanged.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Open from footer, expand/collapse, dismiss and reopen | `memoryFilesExpandFromFooterWithOrderedInertRows` |
| Fresh reading changes files without changing scalar totals | `memoryInventoryReplacesWithoutChangingScalars`; `memoryFilesReplaceAndHideForRememberedCodexPendingAndEmpty` |
| Malformed or missing list after a valid frame | `malformedMemoryCannotReplaceHeldReadingOrCertifyHistory` |
| Empty/dropped-only/huge dropped count | `memoryFilesDroppedOnlyAndHugeCounts`; projection tests |
| Duplicate paths, long rows and scroll to final row | `memoryFilesLongRowsEllipsizeWithoutLosingTokensAndScroll` |
| Recollection or new destination | existing `contextSheetSurvivesRecollectionAndUsesLatestReading` and visibility test; memory forwarding test |

## Error handling

Reuse silent whole-frame rejection in `decodeContextUsage` and `understoodNonvisualEntry`; discard decoding exceptions, which may contain hostile input. Keep prior reading and collector alive. No new I/O or UI error state.

## Testing strategy

Write focused red tests before production. Extend projection tests for shape rejection, required fields, order, duplicates, retained types, independent drops, replacement and redaction. Extend ViewModel tests for inventory-only updates. Extend shared native Context sheet tests for actual footer opening, accessible expansion/collapse, 48dp target, count, order, shortening, dropped-only, long row ellipsis/token bounds, scrolling, replacement and hidden states. Add pure shortening cases beside formatting tests. Complete intended-valid wire fixtures that lack the newly required inventory fields, keeping malformed probes malformed. Run affected classes on Linux; retain raw XML and executed/failed/skipped counts outside the worktree. No new recording is required: focused native supplied-frame coverage proves this display behavior. Complete gates remain dispatcher-owned.

## Open Questions

None. Defaulted domain fields preserve scalar-only in-process callers. MCP is absent on this base; if it lands during this run, retain it ahead of memory and check independent toggles.

## Documentation handoff

- Pending documentation stage: `app/src/androidTest/assets/design-1220/README.md`, Context section: add expanded frame `919-9341`.
- Pending documentation stage: `docs/knowledge/features/thread-composer-footer-context-usage.md`, Context sheet and decode sections: memory path shortening, independent truncation counts and inert-text handling.

## Security review

**Verdict:** PASS

- Trust boundaries: `ContextUsagePayloadDto.toReading` strictly validates required list/entry primitives, bounds path/type and returns only descriptive data. Test hostile markup, URL-looking paths and duplicate paths. No text is an identity.
- Tokens and credentials: no credential handling. DTO, entry and held reading stringify to static redacted text; decoder exceptions are discarded.
- Files and storage: slash splitting is display-only; no normalization, joining filesystem paths, persistence, reads or writes. Traversal and URL-shaped text remain inert.
- Android attack surface: no components, intents, links, permissions, WebViews or providers are added. Only client-owned toggle semantics act; file Text has no actions.
- Cryptography: no changes to Noise, keys, TLS or nonce ownership.
- Network and I/O: existing bounded frame transport and requests are reused. Dropped counts are numeric display only and cannot cause allocation or iteration.
- Errors, logs and telemetry: expansion emits only a static event name and boolean; path/type and caught throwable never reach logs, errors or telemetry.
- Concurrency: no new job, suspension or shared mutable repository state. Local expansion cannot affect MCP, repository routing or actuation.
- Threat model: Noise continues to protect against relay plaintext access; existing freshness/ownership checks handle delayed frames. Hostile decrypted inventory is validated and rendered as Text. Rooted-device token theft and general screenshot/accessibility exposure remain covered by existing platform policy; this feature intentionally exposes file descriptions to the reader/accessibility service, without adding a new export path.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-20
