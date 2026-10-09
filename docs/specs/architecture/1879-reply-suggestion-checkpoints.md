# #1879 — Identify the reply-suggestion live timeout

## Files read

- `InteractiveStreamE2ETest.kt`: `interactiveTurn_replySuggestion_longPressSends`, `hostRepository` and `runningToolPeer` supply phone setup and the existing authenticated peer.
- `ReplySuggestionE2EAssertions.kt`: `assertReplySuggestionLongPress` has a combined session/offer deadline followed by placeholder, echo and clear waits.
- `SecondClientPeer.kt`: `recorded`, `open`, `linkState` and `close` provide a read-only wire witness with retained identity and bounded redialing.
- `QuestionAnswerStage.kt` and `QuestionAnswerStepTest.kt`: #1703's fixed checkpoint diagnostics preserve original deadlines and cancellation.
- `ScriptedReplySuggestionTest.kt` and `ThreadInputBarSuggestionTest.kt`: repository delivery, placeholder and gesture coverage remain unchanged.
- `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md`: session resolution and offer invalidation require the current host/session; no product change is justified by retained evidence.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: removed gate worktrees can erase phone logcat while isolated daemon artifacts survive.
- `docs/e2e-interactive-stream.md`: “What rung 3 is made of”, “Suggested next reply” and “Verification status” define actual daemon offer and distinct live/scripted proof.
- Sibling `pyrycode/docs/protocol-mobile.md`: `reply_suggestion` is the authoritative wire contract; sets/clears are transient, not history entries.
- Sibling `pyrycode/docs/specs/architecture/2856-native-suggestion-staging.md`: native generation under `allowed_warning` can be suppressed; that separate staging repair is not proof of this occurrence.

## Context

The first retained full gate on mobile `b482488876`, main `ade03664c4`, daemon `bf82c68e10c895c5fecc5552652dbe63e6677d39` ran 64 methods: 61 passed, 3 failed, 0 skipped. The named suggestion method has only an unnamed 90000 ms coroutine timeout. The same-tree rerun ran 3 methods: 2 passed, 1 unrelated failure, 0 skipped; this method passed.

The agents logs are `2026-10-07T04-33-51-215Z_real-claude-gate_#1866.stderr.log` and its rerun counterpart. Retained isolated daemon directories are `pyry-e2e.7ALokd` and `pyry-e2e.P3iSBQ`. The failed conversation's initial send was accepted at 07:54:46.097 +03:00 and its successful four-character assistant reply completed at 07:54:49.296. No second send appears before peer teardown at 07:56:20.257. In the passing conversation the first send was accepted at 08:02:40.468, the reply completed at 08:02:45.629, and the confirmation send was accepted at 08:02:54.048. Both histories record `allowed_warning` and successful turn outcomes. No message text, token or pairing material is copied here.

This establishes initial reply completion and absence of a confirmation send in the failed occurrence. It does not distinguish session/offer waiting from an unsent gesture. Suggestions are excluded from history, INFO daemon logs contain no offer publication record, and the gate worktree with phone logcat is gone. Root cause remains unproven; claiming native suppression from `allowed_warning` alone would overstate the evidence. No decision record is needed.

## Design

Instrument this one scenario with a test-only `ReplySuggestionStage` and `ReplySuggestionProgress`, modelled on #1703. Fixed stages identify list/connection setup, conversation creation, repository acquisition, peer readiness, initial send/reply, session readiness, daemon offer, placeholder, hold/release, exactly-one user echo, newer clear and placeholder removal. A synchronous wrapper converts coroutine/Compose timeouts into a fixed stage label plus content-free diagnostics, retaining the original cause. The inner session/offer stage marker changes inside the existing shared deadline; no deadline grows and no test retries.

Open the existing authenticated `SecondClientPeer` before sending ping. It only witnesses this conversation's daemon frames and never sends a suggestion or confirmation. On timeout report peer link state, valid nonempty offer count, revisioned clear count, user-message count and turn-end count. A peer offer with no repository offer implicates phone delivery/session selection; an echo-stage failure follows completed placeholder and gesture checkpoints; no witnessed offer identifies an upstream-wire absence to investigate, not proof of absent Claude generation. Keep the exact placeholder, real pointer long-press/release, verbatim message and newer revision assertions intact. The deterministic twin inherits named helper checkpoints without requiring a peer.

Forecast: about 350 written lines including plan, two internal types, three acceptance criteria, two timeout branches, zero production files and two helper consumers. Repository search confirms only live and deterministic methods call the helper. Overlapping branches #1682, #1689, #1690, #1691, #1693, #1695, #1731, #1766, #1833 and #1867 modify other methods/imports in the live class; keep edits local. No dependency blocks instrumentation.

## State and concurrency model

Progress is owned by the test thread and retained only for the scenario. No ViewModel, UiState, Event, dispatcher or product StateFlow changes. The peer keeps its existing IO scope and closes in `finally`; its immutable recorded snapshot is read only when diagnosing a timeout. Existing runBlocking/withTimeout waits remain authoritative, including the single combined session/offer budget. Ordinary cancellation passes through unchanged.

## Error handling

Only coroutine and Compose timeout exceptions become named AssertionErrors. Other failures pass through. Diagnostics contain fixed enum labels and numeric counts/booleans, never daemon-authored text, ids, payload dumps, pairing secrets or file paths. Success does not evaluate diagnostics.

## Testing strategy

Test first: virtual-time regressions cover each unnamed coroutine wait, an inner offer checkpoint at the combined deadline, Compose timeout, successful values, lazy diagnostics, ordinary cancellation and refusals. Fixture-envelope tests distinguish offers, clears, malformed suggestions and user echoes without rendering content in failure text. Run the new unit class and existing suggestion ViewModel, repository and shared screen tests; compile instrumentation tests, lint, assemble and forced Spotless. Run `python3 scripts/android-test-gate.py scripted reply-suggestion` and inspect fresh executed/failed/skipped counts for the existing deterministic twin. The live method remains device-only because it uses a real network daemon, encrypted peer and pointer interaction on the phone.

## Open Questions

The responsible failure and causal regression remain unresolved. Diagnostics are not the flake repair. The next full dispatcher live gate must capture checkpoint evidence; a pass or same-tree rerun alone does not establish root cause. If a fresh offer-stage failure has no peer offer, retain source-generation/publication metadata from the isolated daemon before deciding ownership. No upstream blocker is asserted without that evidence.

## Live evidence handoff

Use `needs-live-artifacts` with `needs-real-claude` preserved. Pending dispatcher work: fresh full live gate, suite executed/failed/skipped counts and explicit execution/result for the named suggestion method; preserve stderr, XML, per-test phone logcat and isolated daemon offer/source metadata. Pending repository records on builder re-entry: `app/src/androidTest/assets/reply-suggestion-1879/evidence.json` (sanitized revisions, checkpoint/offer/echo metadata and method/suite counts) and its `README.md` (provenance and interpretation), plus a causal revision to this plan. Raw logs stay in the dispatcher artifact directory, never in the APK. On return the builder owns committing sanitized causal evidence, fixing the implicated path and adding its red/green regression, then rerunning focused/final checks. This instrumented PR must not close the repair on a passing rerun alone.

## Documentation handoff

Pending documentation stage after causal repair: `docs/e2e-interactive-stream.md`, “What rung 3 is made of”, “Suggested next reply” and “Verification status”: checkpoint diagnostics, established cause and fresh live/scripted proof boundaries.

## Security review

**Verdict:** PASS

- Trust boundaries: wire witness decodes with existing `decodeReplySuggestion`; only counts enter diagnostics. The original offer remains untrusted text rendered through the existing bounded production path.
- Tokens/secrets: reuse `runningToolPeer` and retained peer identity; no credentials are fetched, copied, interpolated or printed.
- Files/storage: no new persistent content store or untrusted path construction; evidence text is sanitized metadata in this plan and ticket.
- Android attack surface: no component, intent, provider, keyboard or permission changes.
- Cryptography: reuse existing Noise session and Keystore paths unchanged; no key generation or comparison changes.
- Network/I/O: retain all existing deadlines, protocol caps and peer reconnection; a dropped relay cannot prolong the scenario through retries.
- Errors/logs: SHOULD FIX in implementation: timeout text and envelope summaries must exclude message content and identifiers, including malformed payloads; regression fixtures pin this boundary.
- Concurrency: progress belongs to the synchronous test thread; peer scope is closed in finally even when timeout diagnostics throw.
- Threat model: malicious relay delay gets a bounded named failure; hostile daemon text is reduced to counts. Token theft, screenshots/accessibility and production frame hardening remain governed by unchanged existing mechanisms, with no added production exposure.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-07
