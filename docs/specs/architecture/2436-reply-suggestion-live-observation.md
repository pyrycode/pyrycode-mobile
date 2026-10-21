# #2436 — Keep suggestion evidence on the live connection

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/ReplySuggestionE2EAssertions.kt`: `assertReplySuggestionLongPress` captures one repository for the offer and clear.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_replySuggestionLongPressSends` captures the connection after sending.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_replySuggestion_longPressSends` is the shared helper's other caller.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: `firstOnLive` cancels retired observations and verifies reference identity before accepting evidence.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: `harnessThreadEvidence` supplies delivered authoritative user evidence.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedReplySuggestionTest.kt`: existing real repository-to-screen offer and isolation coverage.
- `docs/knowledge/features/stable-conversation-repository.md`: suggestions are connection-local and must follow replacements, unlike held settings.
- `docs/knowledge/features/development-verification-gates.md`: shared evidence must preserve delivery and current-source ownership.
- `docs/specs/architecture/2331-idle-history-proof-input.md`: earlier producer-gap/reconnect correlation remains a diagnostic lead.

## Design source

N/A: test observation repair only; no visual or product interaction change.

## Context

The #2333 failure at Mobile `7e6940251cd3cff775b67c913fc070d36f365734`, Core
`2c68b595440dd7e8e843af9cff0d9a708bcc81a7`, retains a producer gap at
03:28:20.724 UTC, close 4414, and a replacement handshake at 03:28:22.403.
The captured repository never follows that replacement. The #2334 verifier bundle
`26f513ea363b0a75f3e0aa79933b529b79e1a995bc5dc0794e88429cfa343798`
confirms another failure and passing rerun, but lacks the failing phone stack.
Use a fresh focused run and controlled connection replacement to distinguish this
observation defect from daemon publication or a gesture failure. Preserve historical
failures; a passing native rerun alone does not prove the mechanism.

One deliverable: reliably observe and submit the actual daemon offer despite a
connection replacement. Forecast: about 400 written lines, no production files,
three internal observation functions, two consumer updates, three criteria and no
new product reject branches. Remote branches overlap both scenario classes;
#2367 changes other methods and #1879 adds progress checkpoints. Keep changes local.

## Design

Extract the existing offer-selection flow into a shared test observation helper.
Its public input is the owning coordinator's `StateFlow` of current repositories.
Select conversations, settings and suggestion together inside `firstOnLive` so
different connections cannot provide mixed session inputs. Use the same live-source
selection for the exact delivered echo and newer revisioned clear. Both native
callers pass their host's connection holder; deterministic channel lookup follows
that holder too. No whole-test retry, synthetic native offer, deadline change,
production change or dependency is introduced.

Keep exact placeholder equality, real pointer long press/release, one verbatim
delivered echo, newer clear for the offered session, and placeholder removal.
Add static stage diagnostics around native checkpoints without authored values.

## State and concurrency model

Observation runs in the existing bounded `runBlocking` test checkpoints. Shared
suspend reads inherit caller cancellation and use `firstOnLive`'s `flatMapLatest`;
no new scope, dispatcher or background job survives the checkpoint. No reads send
the suggestion or retry the gesture. UI lifecycle and production state are unchanged.

## State transitions and identity reuse

| Event | Check |
| --- | --- |
| Connection retires while awaiting offer, including a null gap and stale old-source update | `ReplySuggestionReadsTest.offerFollowsReplacementAndRejectsRetiredUpdates` |
| Replacement reuses conversation/session IDs | Same test: replacement's own offer is required. |
| Session changes before an offer | `ReplySuggestionReadsTest.offerFollowsSessionSelection` |
| Connection retires while awaiting delivered echo or clear | `ReplySuggestionReadsTest.echoAndClearFollowReplacement` |
| No valid offer or newer clear arrives | Existing timeout stays active; negative assertions in controlled tests. |
| Native placeholder and gesture through actual daemon delivery | Focused `reply-suggestion` scenario plus existing `ScriptedReplySuggestionTest`. |

Process recreation and rotations introduce no retained state here; observation is
owned by one test invocation and its existing deadline.

## Error handling

Preserve failures and deadlines. Native checkpoint failures name a static stage
and retain their original cause; diagnostics never print offer text, keys or tokens.
Do not treat a missing offer as permission to retry submission.

## Testing strategy

Write a controlled failing regression using the original captured-source behavior,
then switch to live-source observation and run the same checks green. Cover offer,
session isolation, exact delivered echo and explicit revisioned clear. Run existing
`LiveConnectionReadsTest` and `ScriptedReplySuggestionTest` with the new regressions.
Run Linux `scripted reply-suggestion`, preserving raw XML, phone/daemon logs and
source/binary revisions in the persistent evidence area. This existing device test
requires real encrypted transport, asynchronous daemon delivery and pointer input.
Full configured scripted coverage remains dispatcher-owned; macOS is outside scope.
Run lint, assembly, instrumented Kotlin compilation, formatting and pre-verification
after the final merge of main.

## Open Questions

- Resolved: the fresh native baseline passed 1/1 without a producer gap. It does
  not reproduce or replace either historical failure.
- Resolved: controlled replacement proves a Mobile harness observation defect;
  no foreign repair dependency is demonstrated. Historical phone stacks were not
  retained, so the exact checkpoint of each historical occurrence remains unknown.

## Revisions

### 2026-10-21 — Controlled causal proof

Extracting the original captured-repository reads and replacing their source after
collection begins produces three failing tests out of four: the read never sees
an offer delivered only to the replacement, accepts an old-source offer after
retirement, and accepts an old-source echo. Session selection on an unchanged
connection passes. After using `firstOnLive`, the same four tests pass. The direct
replacement case is `offerArrivingOnlyOnReplacementDoesNotLeaveReadOnRetiredConnection`;
the other transition rows above retain their named coverage. The tests also reject
an inexact or duplicate echo, a non-newer clear, a set masquerading as a clear and
another session's clear. No submission is retried.

The controlled red source, XML and hashes are retained privately under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2436/builder-red-20261021/`.
The native baseline's raw XML and phone trace are under the adjacent
`builder-baseline-20261021/`; daemon/binary metadata is in
`harness/fault-f89f38493678f0dae4054c591c2b34e8/`.
Native checkpoint logs identify each stage without offer text or connection secrets.
