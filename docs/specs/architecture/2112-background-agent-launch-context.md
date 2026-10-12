# Explain the controlled background Agent launch (#2112)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_followsBottomUntilFinished`, `allowPromptsUntil`, and `STOP_HOLD_PROMPT`; the launch request lacks the controlled-test framing already used for the interrupt proof.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `record`, `dialLink`, and `recorded`; replacement links append to the existing recorder rather than erasing earlier frames.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/WithClearedHostInstructions.kt`: existing test-only helper placement; this change does not clear host instructions or change shared setup.
- `docs/knowledge/INDEX.md`, `docs/knowledge/features/thread-screen-testing-background-agent-viewport.md`, and `docs/e2e-interactive-stream.md`: retain late-child attribution, running/finished placement, real Agent launch, and all release/navigation assertions; the Stop scenario previously encountered an injection interpretation without test context.
- Retained #2099 provenance, named-method phone logcat/XML, isolated daemon log, the target's persisted daemon history, and its bound Claude transcript: correlate actual submission, execution, emission and observation without publishing authored content.

## Change

The original 13-method prefix counted 13 executed, 11 passed, 2 failed, zero errors/skips. This method timed out before its behavioral assertions. Its accepted phone request was durably delivered and appears verbatim inside the bound Claude user turn. Claude Haiku 4.5 completed one successful turn with prose explicitly classifying the request as prompt injection, rather than making any tool call. The emitted assistant deltas exactly match that transcript. Neither the transcript nor persisted daemon history contains a tool call or background start. Completion precedes the transport interruption and peer-link replacement by roughly 34 seconds. The peer's absence therefore matches a launch that never occurred; replacement is not demonstrated to have lost an Agent start. The initial no-live-child queue refusal was followed by delivery in 1.920 seconds, unlike #2013's prolonged admission refusal. The reason for the inherited Haiku selection, whether another model would refuse, and the socket interruption's origin remain unknown.

Extract only this scenario's request into the test-only `backgroundAgentLaunchPrompt(fixture)` function. Add a preface explaining that the maintainer authorizes a controlled lifecycle test on an isolated host, the loopback URLs are test-owned fixtures that only hold requests, the second client approves permission prompts, and the test releases the holds after observing lifecycle boundaries. Preserve the original Agent/Bash instructions verbatim, including background launch, both 180-second commands, two separate printf calls, ordinary child paragraph and immediate launching-turn completion. Keep the existing loopback URL validation in the scenario. No retry, skip, timeout, model, host-instruction, production or shared-wait change is planned.

The prompt contract is the lowest deterministic seam for the demonstrated missing context. Its red/green regression proves that the submitted request contains the explanation and preserves the requested work; it cannot deterministically reproduce Claude's semantic refusal. The historical transcript establishes the refusal, and the fresh full live gate remains necessary for real launch proof. Do not report a unit or scripted pass as an LLM reliability measurement.

Shared-file overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032, #2057, #2088, #2089, #2090, #2091, #2092, #2099, #2100, #2102 and #2104 modify other scenario/setup blocks. #2095 pins the shared launch model independently; #2114 owns the later-reply wait. No inspected branch changes this launch block. Keep the edit local.

Sizing: approximately 300 written lines including plan, tests and sanitized evidence, one new test-only function, no exported types, one updated consumer, three acceptance criteria and no new state-machine/error branches.

## Testing strategy

- Extract the original request unchanged, write `BackgroundAgentLaunchPromptTest`, and observe its controlled-context assertion fail before adding the preface. Also check the exact original instruction suffix and both hold commands so framing cannot weaken the launch contract.
- Run the focused JVM class, lint, debug assembly, Android-test compilation and forced Spotless checks. The existing real-daemon/relay/device scenario remains device-only because it exercises actual socket delivery and real Claude.
- Run the existing deterministic `background-agent` scenario once to verify unchanged fixture lifecycle/rendering; it does not prove Agent launch by real Claude.
- The ticket assigns fresh full live acceptance to the dispatcher. Its gate must execute and pass `InteractiveStreamE2ETest#interactiveTurn_backgroundAgent_followsBottomUntilFinished`, retaining revisions, Claude version, named XML, correlated phone/daemon locations and suite counts. No separate focused live acceptance is claimed.

## State transitions and identity reuse

None: the extracted request is a pure string builder. Existing peer replacement, launching-turn completion, child attribution, release, later reply, navigation and fixture/preference/peer cleanup remain unchanged.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, background Agent lifecycle coverage — explain the controlled fixture preface, separate the observed Claude refusal from the later peer replacement, and record fresh counted full-live evidence when supplied by the dispatcher.

## Security review

**Verdict:** PASS

- [Trust boundaries] Only test-authored instructions and the already-validated loopback fixture URL enter `backgroundAgentLaunchPrompt`. The preface explains the actual operation; it does not ask Claude to bypass safeguards or change its permission posture.
- [Tokens] No pairing, key or credential reads/writes are introduced. Neither the prompt nor authenticated frames are printed.
- [Files and storage] Sanitized test resources contain timestamps, metadata, counts, booleans and source hashes only. Private phone logs, daemon history and Claude transcript remain outside committed/APK artifacts. No authored text is copied into evidence resources.
- [Android attack surface] No manifest, intents, providers, UI or exported-component changes.
- [Cryptography] The existing peer's Noise handshake, identity and fresh per-link state remain unchanged.
- [Network and I/O] Existing loopback URL validation and 180-second fixture commands stay intact. No new requests, larger deadline, transport bypass or retry.
- [Errors and logs] No new log messages. Evidence must exclude raw replies, thinking, request bodies, CLI arguments, credentials and private correlation tokens; safe historical comparisons are booleans.
- [Concurrency] The string builder owns no jobs or state. The scenario's existing release and preference restoration, peer close and host cleanup remain in their current finally paths.
- [Threat model] A malicious relay can still drop/delay traffic and fail the bounded existing waits; hostile daemon frames retain current decoding. No new disk-secret or UI-leakage surface is introduced.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12
