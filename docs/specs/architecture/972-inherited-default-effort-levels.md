# #972 — Effort levels for a conversation with no model override

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig.effortChoices`, `selectedChoice`, `modelLabel` — the one lookup that changes, and the neighbours that must not.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/EffortRecall.kt` → `EffortRecall.decide` — its `unpublished` skip reads `config.effortChoices`, so it follows the fix with no edit.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `footerControlEnabled`, `footerMenu` — the effort control is enabled when `footerMenu(Effort, …)` is non-null, which reads `effortChoices`; the Auto gate reads `selectedChoice?.supportsAutoMode` and stays as is.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/FooterMenuTest.kt` — plain `ThreadRunConfig` fixtures for the footer; the new footer cases go here.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelEffortRecallTest.kt` → `newVm(menu = …)`, `collect`, `reading(model = …)` — the recall fixtures; the new recall case goes here.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel` — the rung-3 scenario AC 3 edits.
- Desktop `RunConfigSections.tsx` → `effortRowFor` — the reference: `publishedRowFor(models, model === '' ? 'default' : model)`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=110-3494

The Input footer's effort button. No visual change: in a conversation with no model override the existing effort control becomes enabled and offers the inherited default row's levels.

## Change

`ThreadRunConfig.effortChoices` looks up the published row for `selectedModel`, substituting `default` (the value the daemon publishes the inherited-default row under) when `selectedModel` is `""`, and returns that row's levels — the same single substitution as desktop's `effortRowFor`. With no `default` row published the result is empty, as today. `selectedChoice` itself is not widened, so `modelLabel`, the model menu's `selectedValue` and the Auto permission gate keep their behaviour. `EffortRecall.decide` and `footerControlEnabled` read `effortChoices` and follow without edits. The substituted value is a private constant beside the class.

Rung 3: the recall scenario keeps every fixture at saved model `""`. It picks the remembered and explicit levels from the published `default` row (failing with a named message when that row offers fewer than two levels), primes by tapping effort on a `""`-model chat, writes only the explicit chat's effort, asserts the fresh chat and channel still save `""`, and drops the KDoc paragraph about #972.

## Testing strategy

- `FooterMenuTest`: with saved model `""` and a published `default` row, `effortChoices` is that row's levels and `footerControlEnabled(Effort, …)` is true; with no `default` row the levels are empty and the control is disabled; the model menu's `selectedValue` stays `""` (nothing else widens).
- `ThreadViewModelEffortRecallTest`: a `""`-model reading with a menu carrying a `default` row that publishes the remembered level sends exactly one recall write of that level.
- Rung 3 runs in the dispatcher's post-verifier real-Claude gate; locally only `compileDebugAndroidTestKotlin`.

## Documentation handoff

None named by the ticket.
