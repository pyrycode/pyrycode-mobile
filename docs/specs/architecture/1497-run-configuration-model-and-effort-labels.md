# #1497 Run configuration model rows are one line and effort labels are capitalised

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt`: `ModelRow` (draws `ThreadModelChoice.detail` as a second line) and `EffortRadioRows` (draws `ThreadEffortChoice.label` verbatim).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt`: `ThreadModelChoice`, `ThreadEffortChoice` (value is the write argument, label the inert render). Unchanged.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/StatusSheetTest.kt`, `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterTest.kt` (`runConfigurationSelectsModelEffortAndPermission` taps the sheet's "max").
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`: `runConfigurationAndReaderAt412By892` captures `run-configuration.png` against `600:1694` with the four-model menu and resolved identifiers.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=600-1694

Dark Run configuration sheet: each model is one `bodyMedium` `onBackground` label beside its radio, with no helper line; effort is a two-column radio grid reading "Low", "Medium", "High", "Max". Everything else in the sheet already matches (#1432 audit).

## Change

`ModelRow` drops the `Column` and the `detail` `Text`, keeping only the label with `maxLines = 1`. `EffortRadioRows` renders `effort.label.replaceFirstChar { it.titlecase() }`; `onEffortSelected` still receives `effort.value`, so the write stays the verbatim wire string. `ThreadModelChoice.detail` and the ViewModel that fills it stay as they are (the ticket leaves the data model untouched), and the composer footer's effort menu is not touched.

## Testing strategy

`StatusSheetTest`: the labels-and-details test asserts the resolved identifier is not drawn; effort tests find "Low"/"High" and assert the tap sends "low"; a new case checks all four published levels read "Low", "Medium", "High", "Max". `ThreadComposerFooterTest.runConfigurationSelectsModelEffortAndPermission` taps "Max" and still records "max". Device evidence: `ThreadDesignCaptureTest#runConfigurationAndReaderAt412By892` on `pixel8Api35` with `requireRealSystemBars=true`, the refreshed `run-configuration.png` compared with `scripts/design-compare.py` against `figma-600-1694.png`, both committed under `app/src/androidTest/assets/design-1220/thread/`, with the `600:1694` verdict in that folder's `index.md` updated. Not operator-facing in the rung-3 sense (presentation only, no new daemon interaction), so no real-Claude scenario.

## Revisions

### 2026-10-02: live mark helper follows the one-line model rows (verifier review of PR #1559)

The verifier found that `InteractiveStreamE2ETest.awaitAnnouncedMark` still expected the removed resolved-identifier line, which would time out `interactiveTurn_modelChange_roundTripsAndStaysPerConversation` and `interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive` on their next live run. The detail line was also what told apart two rows sharing a family label (`opus` and `opus[1m]` both read "Opus"). New contract: the helper first waits until the model radios, in sheet order, read the menu's non-default Claude labels in menu order, then waits until the only marked radio sits at `marked`'s index in that list, or, for no mark, asserts no index is marked. Position, not text, proves which row is marked. Also from the review: `EffortRadioRows` uses `uppercaseChar()` to match `modelFamily()`, and the `ThreadModelChoice.detail` and `toChoice` KDoc no longer say `detail` is drawn. The PR body lists both methods under `## Live tests`.
