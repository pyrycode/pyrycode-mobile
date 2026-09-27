# 1193 — Current conversation model selection

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig.selectedChoice`, `modelLabel`, `effortChoices` and `ThreadModelChoice` define the shared selection and inert display contract.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `sessionSettings`, `runConfig`, `forAgent` and `ModelMenuRow.toChoice` assemble per-conversation readings and pending writes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `footerMenu` and `offersPermission` consume the same model selection but need different metadata lookups.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt` → `ModelSection` renders the published radios and any unavailable selection.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `StatusSheet` argument wiring shares the footer's projection.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_modelChange_roundTripsAndStaysPerConversation`, `freshSettings` and `openRunConfiguration` supply the live round trip.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelAgentModelMenuTest.kt` → agent filtering and two-conversation fixture pattern.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/FooterMenuTest.kt` and `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/StatusSheetTest.kt` → focused footer and radio semantics.
- `docs/knowledge/features/status-sheet.md` and `docs/knowledge/features/thread-composer-footer.md` → existing pending, sanitization and metadata decisions; the documentation stage updates these.
- `docs/knowledge/features/development-verification.md` → shared tests use Robolectric; daemon e2e stays device-only.
- Desktop `ComposerModelMenu.tsx` → `composerModelRowLabel` supplies the ASCII family display rule; `RunConfigSections.tsx` → `publishedRowFor` and `effortRowFor` keep exact writes apart from inherited metadata.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=598-1565

The dark Run configuration modal presents an M3 Model radio group in published order, with one selected ordinary row and no default radio. Its title, body text and selected indicator use the existing theme roles. This ticket changes row sourcing and selection within the existing sheet; #1195 owns its new modal layout.

## Context

`ThreadRunConfig` currently treats a saved `default` as a selectable row and an empty inherited value as no row. The sheet and footer must instead agree on the conversation's confirmed settings, including when a default row resolves uniquely to an ordinary published row. The independently reported running model remains a separate reading.

## Design

- `runConfig` retains the published Claude `default` row as internal metadata, omits it from visible `choices`, and applies the render cap after that omission. Agent filtering remains before this projection. Codex has no inherited resolution. The `default` metadata continues to supply inherited effort and permission support.
- `ThreadModelChoice` retains the raw `resolvedModel` separately from its inert display detail. `ModelMenuRow.toChoice` labels Claude rows with the ASCII family derived from raw `value` (strip one `claude-`, take leading ASCII letters, uppercase first), falling back to inert `displayName`; Codex rows use inert `displayName`. Raw `value` remains the write argument.
- `ThreadRunConfig.selectedChoice` requires a settings reading or a pending pick. Explicit saved and pending identifiers match only an ordinary row's exact raw `value`. A confirmed Claude inherited `""` or `"default"` selects an ordinary row only when the hidden default row has a nonempty, non-placeholder `resolvedModel` exactly equal to exactly one ordinary row's nonempty `resolvedModel`. A pending `default` is not an inherited choice. Running-model text never participates.
- Both the sheet and footer mark `selectedChoice?.value`; an unmatched explicit value stays visible as inert text, while unresolved inherited state gets a client-owned unavailable label and no marked row. Before a settings reply, the existing `unknown` footer state and no marked sheet row remain. The sheet gets an inert selection note when its rows cannot represent the saved choice.
- The existing `onModelSelected` path keeps optimistic pending, raw writes, fresh-reading confirmation, and failure revert. It accepts only visible rows. `effortChoices` and `offersPermission` use the hidden default metadata for inherited settings, independent of the visible selected row.

## State + concurrency model

`ThreadViewModel` retains the conversation-scoped cold `observeSessionSettings` and `observeModelMenu` flows in its `viewModelScope` state collection. The pending model flow remains Main-owned and clears on a fresh reading or failed write. No additional job or dispatcher is introduced. Closing the thread cancels its collection; reopening obtains a new settings reply.

## Error handling

No new network or parse result is introduced. An absent settings reply is unknown, absent or ambiguous inherited metadata is unavailable, and an unmatched explicit identifier is inert text. A rejected write uses the existing pending clear and snackbar. No raw model identifier enters a log, key, URL or rich-text sink.

## Testing strategy

- Focused JVM tests for Claude inherited resolution, empty/placeholder/ambiguous resolution, exact explicit and pending matches, Codex unset state, row labels, menu order, metadata retention, delayed settings, rejection and two conversations.
- Shared Robolectric screen tests for no selected radio before settings, one selected inherited or explicit radio, and unmatched/unavailable text with no marked row.
- Extend `InteractiveStreamE2ETest.interactiveTurn_modelChange_roundTripsAndStaysPerConversation` to select a published row in Run configuration, leave and reopen, verify that radio after a fresh daemon settings reply, and confirm the other chat is unaffected. This host-backed scenario stays under `androidTest`; the dispatcher owns its real-Claude execution after verifier. Compile it locally and run the focused device case if its harness can be used without real Claude credentials.
- Run focused JVM and shared tests, Android Lint, debug assembly and androidTest compilation. The verifier runs aggregate gates.

## Documentation handoff

Pending for the documentation stage: update selection and label behavior in `docs/knowledge/features/status-sheet.md` and `docs/knowledge/features/thread-composer-footer.md`; record the expanded rung-3 scenario under the model/effort settings round trip in `docs/e2e-interactive-stream.md`.

## Open questions

- Confirm the daemon's placeholder spelling for `resolvedModel` and the current screen semantics used by the e2e row assertion during implementation.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The daemon's menu and settings are untrusted. `ModelMenuRow.toChoice` bounds and strips controls from rendered labels; unmatched saved identifiers pass through `String.inert`. Raw values participate only in exact equality and the existing write path. The new family parser reads a bounded prefix and returns inert text.
- [Tokens, storage, Android attack surface, cryptography] This plan adds no credential, storage, component, intent or crypto path.
- [Network and I/O] This plan changes no frame, socket, parser or request contract. Existing repository reads remain conversation-scoped.
- [Error messages and logs] Selection notes use inert or client-owned text. No identifier is logged; the existing write rejection remains a generic snackbar.
- [Concurrency] The existing `viewModelScope` collection and Main-owned pending flow are retained. Missing settings cannot be treated as confirmed inheritance, including while a model menu is already present.
- [Threat alignment] A hostile daemon can publish ambiguous, placeholder or unmatched identifiers; all render without a falsely selected radio. Relay delay leaves an unknown selection until settings arrive. UI-side screenshot and accessibility exposure is inherent to displaying the requested model name and is outside this ticket's scope.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-27

## Revisions

- Existing `ThreadViewModelEffortRecallTest.anOutstandingModelTap_defersTheDecisionUntilAReadingSettlesIt` exercises a write while the model menu is still absent. Keep `onModelSelected`'s established caller contract; the visible-row restriction belongs to the sheet and footer options. A ViewModel guard would change effort recall timing beyond this ticket.
