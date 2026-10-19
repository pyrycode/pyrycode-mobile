# Context summary from the thread footer (#2321)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/ContextUsagePayloads.kt`: `ContextUsagePayloadDto.toReading`, strict wire boundary shared by live projection and history evidence.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `ContextUsage`, structural equality and portable reading contract.
- `app/src/main/java/de/pyryco/mobile/data/repository/ContextUsageProjection.kt`: conversation routing, retained reading and transition clear.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadReadEvidence.kt`: `understoodNonvisualEntry` uses the same decoder.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `runConfigFlow`, `state`, `onOverflowEvent`, one eligible settings subscription.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt`: `ThreadRunConfig`, `ThreadUiState`, `ThreadEvent`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt`: `ContextSegment`, fixed text region and trailing controls.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: footer wiring, modal mounting and read-sight obstruction.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt`: `MobileDismissModal` provides scrolling, close, Back, Done and static lifecycle logs.
- `app/src/main/res/values/strings.xml`: client-owned accessible labels and sheet copy.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryContextUsageTest.kt`: existing routing and malformed-frame fixtures.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelContextUsageTest.kt`: reading priority and live replacements.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterWidthTest.kt`, `ContextUsageCircleTest.kt`, `ThreadComposerFooterTest.kt`: pointer routing, circle pixels and semantics.
- `docs/knowledge/features/thread-composer-footer-context-usage.md`: remembered scalars and single computed percent; settings must not be subscribed twice.
- `docs/knowledge/features/thread-screen.md`, `mobile-modal.md`, `development-verification-gates.md`: modal obstruction and shared native-test conventions.
- Core `docs/protocol-mobile.md`, sections `context_usage` and Security model: wire source of truth; remembered empty lists are not fresh reports.
- Desktop `ContextBreakdownPopover.tsx`, `contextUsage.ts`: token formatting and bounded floating-point display arithmetic.

## Design source

Figma: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=919-8976 (loaded), https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=919-9792 (waiting), https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=919-10063 (Codex). Read structured contexts and screenshots on 2026-10-19 through Figma MCP with the design-to-code guidance.

Reuse `MobileDismissModal`: full-height navy modal, Context title, existing circular close asset, separator and bottom-right Done. Body uses `bodyMedium`, emphasized `labelLarge`, `onPrimaryContainer`, `onBackground`, `onSurfaceVariant`, `primaryContainer` tracks and `primary` fills; 12 dp group gaps, 28 dp section gap, 4 dp bars. Inventories and allowance in the reference are deliberately omitted under this ticket's scope. Existing shared close asset is reused unchanged.

## Context

One independently useful behavior: tap the context circle to inspect the current totals and category reading without leaving the thread. Decode plumbing has this one consumer and stays with it. No new dependency or decision record is needed. Overlaps: #1947 adjusts settings eligibility before this combine; #2175 and #2245 add negotiated content/read routing elsewhere. Keep shared edits local and additive.

Forecast: approximately 1050 written lines including plan, tests and fixture repairs; at most four new exported declarations, no mandatory simultaneous consumer migrations (new fields/callbacks default), five acceptance criteria, fewer than ten distinct reject conditions. Category shape checks and fixtures are one decoder contract, not separate deliverables.

## Design

Extend `ContextUsage` with defaulted model, ordered category records and a Long dropped count, preserving existing scalar-only constructors and structural equality. Wire fields are required with no defaults. Validate raw primitive kinds for category name/tokens and model so numeric strings and numeric names cannot coerce across the boundary. Missing/null/wrong shapes fail silently through the existing decoder catch. Bound displayed strings at the mapping boundary; do not key UI rows by text. Inventories remain ignored.

`runConfigFlow` carries the held reading plus the exact used/window pair selected alongside `contextPercent`; it does not add a settings subscriber. Claude requires a held context reading for its header. Codex shows the selected pair only when it has a usable maximum, with no model or categories. Remembered readings show only a scalar header; `asOf` suppresses categories even if a producer supplies some. A fresh empty list with no dropped entries hides Categories; a positive dropped count is displayed independently.

Add Context open/dismiss events and a ViewModel-owned visibility flow projected into `ThreadUiState`. `ThreadScreen` dispatches from the footer and mounts the stateless `ContextSummarySheet(state, onEvent)` while open. Visibility blocks read-sight qualification. The footer reserves a real 48 dp circle action slot without overlapping Actions; the inner 15 dp ring, track, stroke, thresholds and semantics remain intact.

Token formatting matches desktop's one decimal thousands with `.0` removed. Category progress uses Double division before scaling, clamps to 0..1 and uses zero for a nonpositive maximum. Counts never size allocations. The existing modal scrolls the content and pins dismissal chrome.

## State and concurrency model

Visibility is transient per destination ViewModel, not persisted. All changes use existing main-thread event handling; no jobs or dispatchers are introduced. Readings remain conversation-scoped through the existing cold repository flow and `state`'s `WhileSubscribed` ownership in `viewModelScope`. Background socket lifecycle and reconnect retention stay repository-owned.

## State transitions and identity reuse

| Event | Contract and test |
| --- | --- |
| Open, repeated open, dismiss and reopen | Idempotent visibility; `ThreadViewModelContextUsageTest.contextSheetVisibilityAndReadingStayLive`, `ContextSummarySheetTest.footerOpensAndDismisses` |
| Fresh reading replaces reading while open | Header/categories update from current state; `contextSheetVisibilityAndReadingStayLive`, `populatedReadingUpdatesAndClampsBars` |
| Remembered reading, then fresh reading | Scalar-only remembered header; fresh categories return; `rememberedReadingHidesCategories` |
| Session clear, then same conversation reading | Repository clears then refills; existing transition tests plus `contextSheetVisibilityAndReadingStayLive` |
| Other conversation or reconnect | Existing repository isolation/reconnect tests remain; no new routing identity |
| Configuration change/recollection | ViewModel owns visibility; `contextSheetVisibilityAndReadingStayLive` recollects current state |
| Destination disposal/process restore | New ViewModel starts closed; default-state assertion in visibility test |
| Codex source becomes usable/unusable | `codexUsesEligibleScalarSource`, shared `codexShowsOnlyTotalsOrPending` |

## Error handling

Malformed frames preserve the held reading and cannot certify history read evidence. No daemon string enters logs, exceptions emitted by this code, actions or keys. Missing readings show the pending line; unavailable percentages show Not reported yet. Existing scalar validation and timestamp parse behavior remain unchanged.

## Testing strategy

Write failing repository contract tests first, then implement decoder/model; write failing sheet and ViewModel tests before wiring. Keep raw Linux XML and HTML reports outside the tree under `/tmp/builder-2321/` before later runs overwrite them. Record executed, failed and skipped counts in the PR.

Focused tests cover strict category fields, missing/null lists and dropped counts, empty/unknown keys, remembered readings, equality, inert bounded text and history evidence. ViewModel tests cover visibility, source priority, updates and clears. Shared AndroidJUnit4 native Compose tests cover the actual ThreadScreen footer opener, physical touch bounds/routing, all three dismissal routes, pending/loaded/updating/remembered/Codex states, overflow scrolling, long names and clamped 4 dp bars. Retain existing circle pixels and footer interaction coverage. No device-only tests or live turns are needed for this UI/decode change. Run lint, APK assembly, shared-test instrumented compilation, formatting and final pre-verify; complete suites stay dispatcher-owned.

## Open Questions

None. Source selection follows the footer; remembered breakdowns are always withheld.

## Documentation handoff

- Pending documentation stage: `app/src/androidTest/assets/design-1220/README.md`, add loaded, waiting and Codex frame links moved from #2224.
- Pending documentation stage: `docs/knowledge/features/thread-composer-footer-context-usage.md`, describe the tap entry point, totals, fresh categories, remembered and Codex states. Inventory and allowance docs remain with #2322, #2323 and #2226.

## Security review

**Verdict:** PASS

- Trust boundaries: MUST FIX addressed in design: strict field presence and primitive kinds in `ContextUsagePayloadDto.toReading`; bounded model/category text rendered only by Compose Text. History evidence shares this boundary.
- Tokens/secrets: no credential generation, storage or new credential input. Totals and text never enter logs.
- Files/storage: no persistence or file access introduced; model/category text never becomes a path or cache key.
- Android attack surface: no manifest, exported component, intent, deep link or WebView additions. Opener/action labels are client-owned.
- Cryptography: existing Noise_IK and Keystore boundaries unchanged; no new crypto operation.
- Network/I/O: existing envelope cap and transport timeouts remain. Lists iterate received elements only; dropped counts do not allocate, and Double progress avoids Long overflow.
- Errors/logs: existing decoder discards serialization exceptions (which may quote input); new DTO/domain string representations are redacted. Shared modal logs only lifecycle constants/client title. No model or category in event identity or logs.
- Concurrency: existing scoped flow ownership; visibility updates synchronously, no suspension/read-write gap or new mutex.
- Threat model: malicious relay delay/flood remains bounded by transport; hostile daemon malformed types fail closed and long text is bounded. Disk token theft remains within existing Keystore custody. UI disclosure through screenshots/accessibility is the intentional read-only display, with no token entry or new input method.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-19
