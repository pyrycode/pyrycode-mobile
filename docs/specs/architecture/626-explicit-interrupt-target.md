# Explicit open-conversation Stop (#626)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` — `sendInterrupt`, `busyTransition`: callback, silent errors and authoritative busy state.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` — `interrupt`: active-connection passthrough.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` — `interrupt`, `interruptRequest`, `newSessionFrame`: send and existing explicit-target encoding pattern from #625.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — `appModule`: existing bound method reference follows the callback signature.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` — `InterruptRecorder`, `makeVm`, interrupt and busy tests: callback and lifecycle proof.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` — interrupt tests and `startNewSession_targetsBWithAndWithoutPriorActivityInA_withoutChangingMessages`: wire and state proof.
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt` — interrupt tests: real repository over a fake pump.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt` — recording callback; `ScriptedThreadRenderTest.kt` — `interrupt_shownWhileBusy_invokesOnTap_goneAfterTurnEnd`: existing affordance proof.
- `docs/knowledge/features/interrupt-send-path.md`, `interrupt-affordance.md`, `remote-conversation-repository-control-sends.md`, `relay-repository-coordinator.md` — existing no-ack/no-local-state/never-log contracts; their bare-target descriptions are superseded by this ticket.
- `docs/knowledge/features/development-verification.md` — cancellation catch ordering and instrumented compilation requirements.
- Sibling `pyrycode/docs/protocol-mobile.md`, section `Interrupt (v2)` — authoritative wire and authorization contract, including daemon change #2103.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot: a dark vertical thread with alternating message bubbles, title bar and bottom status/composer; it uses Schemes/Surface, Primary and container roles, with title-large and body-medium/small typography. The node does not draw a dedicated Stop control; retain the existing shared M3 affordance and its thinking/responding visibility as required, without layout or token changes.

## Change

Carry the ViewModel's saved open `conversationId` through its existing callback, changing it to `suspend (String) -> Unit`, and through both `interrupt(conversationId: String)` methods to `interruptRequest(conversationId: String)`. Encode the single `conversation_id` key with `JsonPrimitive`, following `newSessionFrame`. Keep plain `pump.send`, no acknowledgment wait, no optimistic state mutation and no logs. No new type, state, dependency or failure mode is introduced. `AppModule`'s method reference and lambdas ignoring their argument remain source-compatible; update the typed test helper and recorder.

The coroutine stays in `viewModelScope`, cancelled on ViewModel teardown. Connection loss still fails silently at the existing boundaries; `CancellationException` must still be rethrown before `IllegalStateException`. No flow or dispatcher changes.

## Scope check

One deliverable: Stop names the viewed conversation. Estimate approximately 220 written lines, three production files, zero exported types, three ACs and zero new reject branches. Seven existing consumers need edits: ViewModel callback invocation, coordinator repository invocation, four direct unit-test invocations and the typed VM test helper's constructor forwarding. The recorder's callback type also changes; DI and inferred lambdas compile without edits. Codegraph returned no callers for `interrupt`, so source search supplied the concrete inventory. The three production files plus tests fit the boundaries. Remote feature branches were fetched and checked; no overlap found. #625's implementation was inspected (106 additions, 54 deletions).

## Testing strategy

- RED: update the repository assertion to demand B's explicit payload from the current bare sender, observe that assertion fail before production edits.
- GREEN: repository, coordinator and ViewModel regressions assert B after distinct A activity, exactly one send for the Stop action, no awaited reply and unchanged local state. Update failure invocations with an explicit id, retain cancellation and busy-state tests, strengthen callback target recording.
- Retain the existing instrumented affordance scenario, add target recording/assertion in its harness and cover thinking/responding/idle visibility without changing the UI.
- Run scoped `ThreadViewModelTest`, `RelayRepositoryCoordinatorTest`, `RemoteConversationRepositoryTest`; Spotless, lint, assembleDebug and compileDebugAndroidTestKotlin. The dispatcher executes instrumented tests.
- Live acceptance remains pending in #679: rung-3 `InteractiveStreamE2ETest` with real A/B turns and another device most recently using A; phone Stop in B must end B while A continues. Deterministic targeting is not proof of this outcome. Dispatcher owns live execution after verification.

## Documentation handoff

Pending for documentation stage: update `docs/knowledge/features/interrupt-send-path.md` and the interrupt sections of `docs/knowledge/features/remote-conversation-repository-control-sends.md` and `docs/knowledge/features/relay-repository-coordinator.md`: replace the bare, connection-level targeting description with explicit open-conversation targeting while retaining fire-and-forget and silent-failure semantics.

## Security review

**Verdict:** PASS

- Trust boundaries: `ThreadViewModel.sendInterrupt` supplies its saved thread ID; the daemon validates this lookup key. It is not authorization. No inbound text or parsing surface is added.
- Tokens/secrets: no token creation, persistence, credential or key changes; the ID is serialized only as JSON data.
- File/storage: no path, cache key, disk or backup change.
- Android attack surface: no exported component, intent, deep link, WebView or permission change.
- Cryptography: existing authenticated Noise session and nonce lifecycle remain unchanged.
- Network/I/O: `SessionPump.send` remains the sole transport boundary, with no new connection, timeout or retry. `JsonPrimitive` safely encodes the identifier without interpolation. No reply wait can hang on the deliberately absent ack.
- Errors/logs: preserve content-free existing exception messages and silent ViewModel catches; do not log identifiers or payloads.
- Concurrency: no shared active-conversation cursor is read; each call carries its own target. Existing ViewModel cancellation and connection teardown remain authoritative; no optimistic state reset or job is added.
- Threat model: paired-device authorization and interactive gating stay daemon-owned per `Interrupt (v2)`. Relay dropping/delaying traffic remains possible without any client success claim. No changes to storage or inbound/UI leakage surfaces are proposed. The live cross-device outcome is explicitly owned by #679.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-20
