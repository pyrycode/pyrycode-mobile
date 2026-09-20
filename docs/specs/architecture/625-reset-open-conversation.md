# Reset the open conversation (#625)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` — `startNewSession`, `newSessionFrame`: send-only contract and placeholder return.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` — reset, failed-send and session-transition tests; message fixtures prove unchanged projections.
- `app/src/main/res/values/strings.xml` — `thread_overflow_new_session`, `new_session_failed`: user-visible wording.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` — `sendNewSession`: already passes the route id and preserves cancellation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` — `ThreadScreen`: existing payload-free error flow feeds its snackbar.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenOverflowTest.kt` — menu selection and dismissal assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` — `interactiveTurn_newSession_rendersSessionBoundaryDelimiter`: preserve durable delimiter proof.
- `docs/knowledge/features/remote-conversation-repository-control-sends.md` — fire-and-forget and unpersisted placeholder semantics; its bare-payload description is historical.
- `docs/knowledge/features/thread-screen.md`, `thread-overflow-menu.md`, `development-verification.md` — existing M3 popup, scoped Gradle task and dispatcher-owned device gates.
- `docs/e2e-interactive-stream.md` — existing rung-3 reset scenario and displayed-delimiter assertion.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md` § New session (v2) — authoritative explicit-target contract, including daemon #2099.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot: a dark vertical conversation surface, title-large header with a right-side overflow trigger, message column and bottom composer, using Schemes surface/primary roles. The reference does not show an open reset menu; retain the existing M3 DropdownMenu and snackbar, changing only the two strings per the supplied mobile adaptation.

## Change

Pass `conversationId` to the private `newSessionFrame(conversationId: String)` and encode exactly one JSON string field, `conversation_id`, through `JsonObject`/`JsonPrimitive`. Keep `pump.send`, its failure exception, and the unpersisted placeholder Session. No ack wait, local session rotation, new state, job, error branch or public type. Rename the menu to “Reset session” and failure text to “Couldn't reset the session. Check your connection.” Keep resource identifiers and public contracts stable. Update the existing live selector and stale adjacent comments, preserving delimiter assertions.

Sizing: one deliverable; one production Kotlin file plus strings; approximately 150–180 written lines including tests and this plan; zero exported types, one private helper caller, two ACs, zero new reject branches. This stays within every boundary and the nearest #539 analogue's scope. Codegraph returned no useful helper callers/context; source inspection found the single caller. Refreshed remote feature branches: no overlap.

## Testing strategy

- RED: replace the old empty-payload test with an exact B payload assertion both without and after activity in A. No response fixture: completion proves no ack wait. Seed observed messages and assert projections remain unchanged after success and failed send.
- Retain existing failed-send and session-transition tests in `RemoteConversationRepositoryTest`; run that class plus existing `ThreadViewModelTest` reset cases.
- Extend `ThreadScreenOverflowTest` with literal menu wording and the error snackbar wording while retaining thread content. Compile androidTest; dispatcher executes UI tests.
- Update `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` to select “Reset session”; dispatcher runs the existing rung-3 live scenario. #679 owns the two-device/two-conversation proof. No new harness scenario here.
- Run scoped `testDebugUnitTest`, `spotlessApply`, `lint`, `assembleDebug`, and `compileDebugAndroidTestKotlin`. No layout change or new preview; final source comparison against Figma checks reuse of the existing components.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/remote-conversation-repository-control-sends.md` § `startNewSession()` to describe explicit targeting under the upstream protocol; update `docs/knowledge/features/thread-overflow-menu.md` and `docs/e2e-interactive-stream.md` reset wording and remove the obsolete menu/delimiter label-collision explanation. The ticket contains no separate documentation-only AC.

## Security review

**Verdict:** PASS

- Trust boundaries: `newSessionFrame` serializes the route id as JSON data, never interpolation into paths or code. Daemon registry validation and capability checks remain authoritative; an id is not authorization.
- Tokens: no credential generation, storage or lifecycle changes; no identifiers or payloads added to logs.
- Storage: no new filesystem access, persistence, backup or cache behavior.
- Android surface: no new exported components, intents, providers, links or WebViews; error text is a local resource.
- Cryptography: existing SessionPump/Noise transport remains unchanged; session reset does not reset transport nonces.
- Network/I/O: use existing encrypted send and rejection behavior; never wait for the success ack the protocol does not provide. No new transport, URL, timeout or inbound decoding path.
- Errors/logs: fixed send-error message and payload-free snackbar signal remain intact; preserve existing connection diagnostics without logging the new conversation id.
- Concurrency: capture the method argument directly, with no global-active lookup, projection mutation, collector or new job. Existing caller scope and cancellation behavior remain unchanged.
- Threat alignment: explicit B targeting removes cross-device cursor interference. Hostile-relay, disk-theft, inbound-frame and UI-leakage surfaces are unchanged by this outbound field and local copy edit. Cross-device live proof belongs to #679; reset-progress UI belongs to #630.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-20
