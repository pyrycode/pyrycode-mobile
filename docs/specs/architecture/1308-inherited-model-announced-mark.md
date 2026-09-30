# #1308 — Mark the running model on default-model conversations, never show Default

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig.selectedChoice`, `modelLabel`, `modelSelectionNote`, `selectedMetadata`, `effortChoices` — the selection surface this ticket changes; `selectedMetadata` / `effortChoices` stay on `inheritedChoice`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `runningModel`, `runConfigFlow`, `runConfig`, `ModelMenuRow.toChoice`, `String.modelFamily`, `reportedText` — where the announcement joins the config and where the family rule lives.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `AnnouncedModel`, `observeAnnouncedModel` — verbatim, never empty, cleared by the repository on a session transition; SECURITY note: render inert, never key a control input on it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the `StatusSheet` call marks by `runConfig.selectedChoice?.value`; `ThreadComposerFooter.kt` → the dormant `footerMenu` reads the same property. Neither changes.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadRunConfigModelSelectionTest.kt` — the focused spec the new cases join.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_modelChange_roundTripsAndStaysPerConversation`, `prepareChat`, `publishedMenu`, `usableRows`, `awaitFooter`, `pickFooterOption`, `hostRepository`.
- `../pyrycode-desktop/src/renderer/src/screens/conversation/ComposerModelMenu.tsx` → `inheritedModelRow`, `composerModelMenuModel`, `modelFamily` — the tier order and "ambiguity stops before a less precise tier" rule mirrored here.
- `../pyrycode/docs/protocol-mobile.md` § `model_announced` — the announced value is claude's echo, at least as specific as what it was given, and need not appear in the list.

Overlaps: `origin/feature/1306` and `origin/feature/1328` edit other functions of `ThreadViewModel.kt` (and #1306 the e2e file); edits here are additive and local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=600-1694

Behaviour only: the Model radio group keeps its current rows and visuals; only which row is marked, and the footer's model label text, change. (The Figma MCP was not authorised in this session; no visual change is made, so none is needed.)

## Context

The inherited mark resolves only through the hidden `default` row's `resolvedModel`, which describes claude's recommended model, not what the session runs (a `model` in claude's settings.json wins over an unset session). Juhana's rule for both apps: the announcement, when present, decides the inherited mark; no option is ever labelled Default. Desktop's `composerModelMenuModel` is the reference.

## Design

`ThreadRunConfig` gains two defaulted fields:

- `announcedModel: String = ""` — the raw announced model, set only while the announcement is present and **not truncated** (a cut value is incomplete, the same rule `toChoice` applies to `resolvedModel`). Used only as a comparison key and a family source; never written, logged or shown raw.
- `agent: ConversationAgent = ConversationAgent.Claude` — set by `runConfig`; the announcement rule applies to Claude conversations only (the AC scopes it to Claude, and the family rule is Claude's).

`selectedChoice` (contract, same null gates as today):

1. No reading and no pick → `null`.
2. Pending pick, else explicit saved model other than `""`/`default` → the row whose `value` equals it exactly, else `null`. Unchanged.
3. Inherited, Claude, `announcedModel` non-empty → tiers over `choices`: exact `value`; else `resolvedModel` equals it; else `modelFamily(value)` equals `modelFamily(announced)` (non-empty). The first tier with any candidate decides: one candidate marks it, more than one marks nothing.
4. Inherited otherwise → today's rule (unique row whose `resolvedModel` equals the `default` row's non-placeholder resolution).

`modelLabel`:

- unknown / marked-row label / explicit-unmatched raw value — unchanged.
- inherited with nothing marked → `modelFamily(announcedModel)` (Claude), else `modelFamily(default resolution)` when non-placeholder, else `UNAVAILABLE_MODEL_LABEL`. Never "Default".

`modelSelectionNote` keeps deriving from `modelLabel`. `String.modelFamily` moves from `private` in `ThreadViewModel.kt` to `internal` so `ThreadUiState` can call it; behaviour unchanged.

## State + concurrency model

`runningModel` becomes a flow of the display value plus the raw key (a `Pair<ThreadRunningModel, String>`), read from the one existing `observeAnnouncedModel` subscription; the existing `.combine(runningModel)` copies both. No new jobs or subscriptions. The repository clears the announcement on a session transition, so the mark falls back to rule 4 until claude reports again. Pending and rejection ride the existing `pendingModel` state: a rejection clears the pick and the previous mark returns.

## Error handling

No new failure modes. An unmatched or ambiguous mapping marks nothing and the label follows the fallback chain.

## Testing strategy

`ThreadRunConfigModelSelectionTest` (unit), cases: no reading; inherited without announcement; announcement matched by value, by `resolvedModel`, by family; two rows sharing a family (nothing marked, label = announced family); announcement disagreeing with the `default` resolution (announcement wins); saved `default` with announcement; explicit matched and unmatched (announcement ignored); pending over announcement; rejected pick restores (pending cleared); truncated announcement ignored; Codex conversation (announcement ignored, label never Default). The existing ambiguous-default case's label becomes the default resolution's family.

Existing ViewModel tests asserting `modelLabel`/`selectedChoice` run as regression coverage (`ThreadViewModelTest`, `ThreadViewModelRunningModelTest`, `ThreadViewModelAgentModelMenuTest`, `FooterMenuTest`, `ThreadViewModelPermissionTest`), plus one ViewModel case proving the raw announcement reaches `runConfig.announcedModel`.

Rung 3: `interactiveTurn_modelChange_roundTripsAndStaysPerConversation` becomes a one-turn scenario. Chat X starts inherited; after a real ping reply, the expected row is computed independently in the test from the fresh menu and the host repository's announced model (same tiers); the sheet marks it (or, if the mapping is empty/ambiguous, marks no model row and shows the family note), and no node reads "Default". Then a pick of another row stays marked after leave and reopen; Y keeps its own saved model. Evidence is the dispatcher's post-verifier live run.

## Open questions

- Codex announcement: desktop maps it too; this ticket's AC scopes the rule to Claude. Resolved here as Claude-only; a Codex conversation keeps today's behaviour.

## Documentation handoff

Pending for the documentation stage:
- `docs/knowledge/features/status-sheet.md` (model selection): the inherited mark rule, tier order, and the settings.json caveat.
- `docs/knowledge/features/thread-composer-footer.md` (model label): the label fallback chain, never "Default".
- `docs/e2e-interactive-stream.md`: `interactiveTurn_modelChange_roundTripsAndStaysPerConversation` now runs one real turn and asserts the announced-model mark.

## Revisions

### 2026-10-01 — verifier review on PR #1377

- **Render cap.** `ThreadRunConfig` gains `overflowChoices: List<ThreadModelChoice>`, the visible rows `runConfig` cuts past `MAX_RENDERED_MODEL_CHOICES`, never composed. The announced tiers count candidates over `choices + overflowChoices`; a tier decides as before, and marks its rendered row only when that is the tier's only candidate. A match past the cap therefore makes a rendered match ambiguous, as `inheritedResolutionUnique` already does for the default resolution.
- **Never "Default".** The inherited label's family fallbacks drop a family that reads as the hidden default's name, so an identifier beginning with `default` falls through to the unavailable label.
- **Unit test.** `ambiguousEarlierTierStopsBeforeFamily` uses a twin of another family, so only an early stop at the ambiguous `resolvedModel` tier yields nothing. New cases cover a match past the cap and a default-named announcement; the ViewModel render-cap case asserts `overflowChoices`.
- **Rung 3.** `awaitAnnouncedMark` replaces `awaitNoModelMarked`: it counts every selected radio labelled by any non-default Claude row of the fresh menu, including rows that share a family label, and requires exactly the expected row's label and `resolved_model` detail, or none with the note shown outside a radio. The e2e `inheritedModelLabel` falls back to the default resolution's family.
