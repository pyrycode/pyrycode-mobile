# Readable background-task type labels (#1751)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt`: `TaskRow`, `boundedText` and the raw `TYPE_LOCAL_BASH` description-style check.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelTest.kt`: panel readings, inert fields and truncation markers.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelLayoutTest.kt`: `populatedContent_usesFigmaTextSizes` and existing layout/interaction coverage.
- `docs/knowledge/features/mobile-modal-callers.md`: background-task fields stay plain, printable, bounded text; markers remain separate elements.
- `docs/knowledge/features/thread-screen-how-it-works-overlays-and-app-bar.md`: both panel entry points share the same read-only roster.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-877

Inspected design context and screenshot: the populated modal groups running and finished cards, with primary-colour 12sp monospace type labels above descriptions. The reference displays “Command” and “Agent”; existing Material 3 tokens, card spacing, status tags and description typography remain unchanged.

## Change

Map the displayed type in `TaskRow` before `boundedText`: exact `local_agent` becomes `Agent`, exact `local_bash` becomes `Command`; other values lose one leading `local_`, replace underscores with spaces and uppercase only the first character. Empty stays empty. A private string helper keeps this local to rendering; raw task types, wire/state, the raw command-description style check, filtering, display bounds and both marker sources remain unchanged. No overlapping in-flight feature branch touches the three planned files. Forecast: about 140 written lines including plan and screen assertions, no new exported types or simultaneous production consumer updates; one display behaviour with two acceptance criteria.

## Testing strategy

Add screen assertions beside existing panel tests for required labels, empty input, one-prefix removal and preservation of remaining case; prove raw types are retained, command description remains monospace, and fallback labels still filter controls and respect display bounds and daemon/client cut markers. Update existing raw-label expectations, including the Figma size assertion. Observe new tests fail before implementation. Run all four shared panel test classes for readings, layout, spacing, insets and interactions, plus lint, assembleDebug, androidTest Kotlin compilation and forced spotlessCheck. This changes text in an existing read-only flow without adding an operator action, transport or stream scenario.

## Revisions

### 2026-10-05 — verifier rework

The verifier found that `InteractiveStreamE2ETest.interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` still matched the raw type against displayed text. Expand test scope to that existing device-only scenario: assert the decoded payload remains `local_bash`, and independently expect `Command` from both panel entry points. The scenario needs a host daemon and real Claude, so its execution remains with the dispatcher live gate, along with `interactiveTurn_backgroundAgentProgress_showsOnRunningCard`. Compile androidTest Kotlin and rerun the four shared panel classes and required build, lint and formatting checks locally; no production contract changes.

## Documentation handoff

- Pending for the documentation stage: `docs/knowledge/features/mobile-modal-callers.md`, background-task panel — replace the raw `taskType` display description with Agent/Command and fallback rules; preserve the distinction from raw wire/state values and the raw `local_bash` description-style check, filtering, bounds and markers.
- Pending for the documentation stage: `docs/e2e-interactive-stream.md`, background-task verification guidance — record the repaired live scenario and subsequent dispatcher evidence.
