# Refresh context usage when reset ends (#1761)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `runSettingsRereadEdges`, `rereadRunSettings`, `askForContextUsage`, and `contextPercent` own refresh triggers and footer projection.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/RunSettingsRereads.kt`: `resetEndEdges` emits only an active-to-idle transition.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelContextUsageAskTest.kt`: `AskCountingRepo` covers open/reconnect asks and will cover reset edges and reply projection.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_newSession_rendersSessionBoundaryDelimiter`, `hostRepository`, and `awaitContextSegment` provide the live reset and reading checks.
- `app/src/main/java/de/pyryco/mobile/data/repository/ContextUsageProjection.kt`: `onSessionTransition` removes the old reading before the new session's reply lands.
- `docs/knowledge/features/thread-screen.md` and `thread-composer-footer-context-usage.md`: a percentage alone can be settings fallback and cannot prove a context reply arrived.
- `docs/e2e-interactive-stream.md`: rung-3 harness and deterministic fixture limits; fakeclaude context queries are not established, so no rung-4 reset/context twin is added.
- Sibling `pyrycode/docs/protocol-mobile.md`: canonical reset and context-query contract; no wire changes.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected thread and composer `533:1957` context and screenshots. The translucent composer has a 15dp context circle before Actions, primary colour and bodySmall labels, with attach and configuration controls at the right. Existing Compose rendering and tokens remain; only the reading supplied to it refreshes.

## Change

Keep the existing merged settings reread collector and add one context-usage request only when its reason is `reset_end`; host turn endings still reread settings alone. Generalize the private ask helper's Boolean to an optional static reason so open remains log-free and reconnect/reset asks log their respective reason. The same repository request and reply projection handle the new ask. No new state, jobs, errors, types, or wire contracts are introduced. The scope is one behaviour, about 190 written lines across production, tests and this plan, zero exported types and two private call sites.

Overlapping branches #1615, #1727 (ViewModel) and #1674, #1682, #1689, #1690, #1691, #1693, #1695, #1727, #1735, #1756 (live test) modify separate methods; keep edits local.

## Testing strategy

Test first in `ThreadViewModelContextUsageAskTest`: initial idle, wrapping-up and restarting do not ask; each completed reset asks exactly once for this conversation, repeats of idle do not ask, and host turn endings do not ask. Inject a post-reset reply and prove the collected `runConfig.contextPercent` changes without a message, alongside content-free reset logging. Retain existing open/reconnect coverage and run context projection and settings-edge tests.

Extend the existing rung-3 reset method without another user message or Claude turn. Identify the created conversation, observe the context reading becoming null at the transition, then require a new non-null reading after that clear and assert the footer's exact description computed from its token totals. Start observing before tapping reset so the clear cannot be missed. This is device-only because it exercises real daemon/Claude replies over the relay and the actual phone UI. Dispatcher owns live execution and must report this method passed with executed/failed/skipped counts; leave `needs-real-claude` in place and list the method in the PR.

Run focused JVM tests, `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin`, `spotlessApply`, and forced `spotlessCheck`. Run a relevant scripted `reconnect` scenario for the preserved ask path. Existing context-circle screen coverage checks its unchanged rendering. No documentation requirements are attached to the ticket.

## Revisions

- 2026-10-05: final sizing is about 255 inserted/deleted lines including the plan, within all limits. The live reading watcher uses `Dispatchers.Default` with an undispatched start: the UI's blocking test waits must not prevent it from observing the transition clear before the new reply. The planned refresh contract is unchanged.
