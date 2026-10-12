# Current-session context readings (#1947)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `runConfigFlow`, `contextPercent`, `forLiveSession`, and `runSettingsRereadEdges` join settings, context replies and conversation identity.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `routeInbound` clears context and publishes the replacement session before refreshing settings.
- `app/src/main/java/de/pyryco/mobile/data/repository/ContextUsageProjection.kt`: `onSessionTransition` invalidates only the replaced conversation's reading.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelContextUsageAskTest.kt`: request-edge coverage remains; its manually cleared fake cannot prove repository invalidation.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryContextUsageTest.kt`: channel-backed pump and envelope fixtures to mirror for end-to-end ViewModel regressions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt`: production repository/ViewModel/screen graph for the unavailable and refreshed surfaces.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/StatusSheetTest.kt`: existing reading and modal interaction coverage.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: extend `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` after its fresh reply/footer assertions.
- `docs/knowledge/features/thread-composer-footer-context-usage.md`, `status-sheet-readings.md`, `thread-screen.md`, and `remote-conversation-repository.md`: stale settings fallback and the shared percentage contract.
- `docs/knowledge/features/development-verification-gates.md` and `docs/e2e-interactive-stream.md`: shared screen tests and real-daemon evidence ownership.
- Sibling `pyrycode/docs/protocol-mobile.md`: canonical session/settings/context payloads and Security model; wire unchanged.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=600-1694

Read design context and screenshot: the dark Run configuration modal stacks labelled sections with 28dp horizontal padding, a 4dp label-to-reading gap, labelLarge emphasis and bodyMedium readings in onPrimaryContainer. Context window displays “Not reported yet”. Existing modal, footer circle, typography and tokens remain; this change only corrects their shared reading.

## Context

Clearing context on replacement exposes the prior session's settings token pair. #1869 was closed by the maintainer on 2026-10-12 after three complete 65-method passing live runs with merged reliability PRs #2133/#2134/#2135. Its experimental inline-observer PR #2020 remains open and unmerged; inspect it, but do not incorporate it. The closure supersedes waiting for the retired flake, and current main contains the accepted repairs. No decision record is needed.

## Design

In `runConfigFlow`, carry settings into the context combine only when their nonempty session identity matches this conversation's current session. Keep all other settings facets on their existing path. A context reply remains preferred and may render while replacement settings are pending. Do not clear `contextPercent` in `forLiveSession`: that would also hide a fresh context reply merely because settings are old. Keep percentage arithmetic and request edges unchanged. Repository invalidation already exists; test it through actual inbound frames instead of adding another invalidation owner.

Shared-file overlaps: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032, #2057, #2088, #2089, #2090, #2091, #2092, #2098, #2099, #2100, #2102, #2104, #2112, #2114, #2117, #2118 and #2119 touch the live class. Other methods or adjacent assertions can merge normally; #1869's retired observer edit is separate from our appended Context window assertion. No ViewModel overlap. Keep edits local.

Sizing: one session-freshness deliverable, four acceptance criteria, approximately 500 written lines including tests/plan, zero new exported types or simultaneous consumer updates, one fallback rejection branch. All limits hold.

## State and concurrency model

No new jobs, flows, mutable state or dispatchers. Existing `viewModelScope` collects settings and conversation readings; the repository's single inbound collector publishes transition invalidation. Existing cancellation and background socket closure remain. Settings used for fallback are filtered in the same combined input that contains conversation identity, without resubscribing to the settings flow and its side effects.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Reset replaces session; new replies held | `resetReplacement_hidesOldSources_thenAcceptsContextBeforeSettings` drives the real repository and ViewModel; shared `resetReplacement_updatesBothContextSurfaces` asserts actual unavailable UI. |
| Fresh context arrives before settings | Same regressions: display the context percentage immediately, without a message. |
| Current-session settings arrive with no context | `currentSessionSettings_areTheOnlyFallback` proves stale, empty and matching identities; `unresolvedSession_doesNotAllowSettingsFallback_butAcceptsContext` covers an empty binding. |
| Another conversation resets | `anotherConversationReplacement_keepsTheReading` proves isolation through inbound frames. |
| Repeat reset, idle and reconnect | Existing `ThreadViewModelContextUsageAskTest` proves one ask per reset end and open/reconnect requests. |
| Recollection/configuration/process restart | No new retained state; identity is recomputed from repository inputs. Existing repository retention and settings behavior remains. |

## Error handling

No new I/O or error types. Pending or unavailable context and a nonmatching settings source yield null; Context window says “Not reported yet”, and the footer retains its unavailable description. Invalid context windows still yield unavailable rather than fallback.

## Testing strategy

Write failing deterministic production-path tests first. Seed old settings and context through a channel-backed `SessionPump`; send resetting and session-transition frames; hold replacement replies and assert null. Supply fresh context before settings, matching settings without context, repeated resets and another conversation's transition. Add a shared Compose regression using `ScriptedThreadHarness`, exercising the actual footer and Run configuration modal. Run existing context percentage, request-edge, repository-context and StatusSheet coverage. No new device-only test; extend the existing rung-3 method because real daemon/Claude replies require its device harness. No new rung-4 scenario: fakeclaude context-query behavior is not established; the shared scripted production graph supplies the deterministic twin.

Run focused JVM tests, lint, debug assembly, instrumentation compilation, Spotless and final pre-verify. Run scripted reconnect for preserved request behavior. Dispatcher owns the fresh full live gate: it must report executed/failed/error/skipped counts and explicitly confirm the reset method passed; historical #1869 evidence does not prove this candidate.

## Open Questions

None.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/thread-composer-footer-context-usage.md`, **Context usage segment (#946, computed since #1411)**: replace the known-gap paragraph with the resolved session-freshness rule.
  Also update the later #1761 sentence that still says the stale settings-fallback gap remains possible.
- Pending documentation stage: `docs/knowledge/features/status-sheet-readings.md`, **ContextWindowSection**: describe current-session-only settings fallback.

## Security review

**Verdict:** PASS

- [Trust boundaries] Keep `routeInbound` and existing typed decoders as the boundary. SHOULD FIX: prove stale settings cannot become a current-session reading; the fallback identity filter and production-path tests enforce this.
- [Tokens, secrets and credentials] Token counts are numerical usage readings, not credentials. No credential generation, storage or access changes.
- [Files and storage] No new disk I/O, paths, persistence or backup configuration; filtering is in memory.
- [Android attack surface] No manifest, exported components, intents, providers or WebView changes. Existing plain Compose text renders only client-owned reading copy.
- [Cryptography] No Noise, key, nonce or authentication changes; conversation reset does not reset transport cryptographic state.
- [Network and I/O] Reuse bounded decoded inputs and existing guarded context requests; no new retry, timeout, URL, frame type or capability.
- [Errors, logs and telemetry] Keep static reset-end/reconnect request logs. Never log readings, payloads, conversation/session identifiers, message text, credentials or decrypted bytes; no new telemetry.
- [Concurrency] No extra collector or suspended read/write. Filter settings against identity in one existing combined input. Test delayed replies and cross-conversation invalidation.
- [Threat model] Malicious relay delay/drop yields existing unavailable/error behavior without plaintext leakage. Hostile frames stay behind existing decoder/admission rules; this filter cannot authenticate a dishonest daemon. Rooted-device token theft and accessibility/screenshots remain existing platform/Keystore concerns, unchanged by numerical-source selection. No security posture is weakened.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12

## Revisions

- 2026-10-12: production-path fixtures advertise `thread` as well as `interactive`, because `ConversationListProjection.applySnapshot` accepts summary session bindings only with the negotiated thread capability. The initial fallback test must seed a real accepted binding. Final implementation remains the planned source filter; written work is approximately 470 lines, within every sizing limit.
- 2026-10-13: verifier finding 1 identified two existing positive fallback assertions whose settings used an unrelated session ID. `ThreadViewModelContextUsageTest.currentSessionSettings` now takes the actual current session from `ScriptedRepo`'s delegated conversation flow. The unchanged 75% fallback and 12% precedence assertions cover same-session reading invalidation; session replacement remains covered by the production-path freshness tests and stays unavailable until a current source arrives. No production or security contract changes.
