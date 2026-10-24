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
| Cold reply arrives before the controlled connection replacement; conversation/session IDs are reused | `interactiveTurn_seededChannel_replySuggestionLongPressSends`: same reply identity on a fresh evidence owner before release. |
| Restored thread content precedes transient busy-phase reconciliation | Same native method: accept exact content and non-idle phase together inside `firstOnLive`. |
| Original result/native suggestion released after recovery, then consumed once | Same native method and unchanged `assertReplySuggestionLongPress` echo/clear/placeholder checks. |

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

### 2026-10-21 — Focused native verification

Linux `scripted reply-suggestion` on pushed candidate
`c225d335bb83120f7395ffd95bb8cfc328e8e3c6` executed/passed the named method once,
with zero failures, errors or skips. All seven checkpoint stages reached completion.
This invocation had no producer gap; the controlled replacement probes provide the
causal proof, rather than a claim that a native passing rerun alone proves reliability.
Raw XML, phone and isolated-daemon logs, APK/source hashes and counted provenance
are retained and hash-verified under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2436/builder-native-c225d335b-20261021/`.
Daemon source is `2c68b595440dd7e8e843af9cff0d9a708bcc81a7`; its binary SHA-256 is
`d93c56662d0072e8aec9d1466bee3f6845f7cdc55247273f177b48b209bdb6ac`.
The four focused JVM classes executed/passed 19 tests, with zero failures or skips;
their raw reports are in the adjacent `builder-green-jvm-20261021/` directory.
Lint passed. Complete configured scripted acceptance remains dispatcher/verifier-owned.

### 2026-10-22 — Reopened scenario investigation and repair plan

The post-repair failure on Mobile `d37187df190fbb8a810aa2f52deb9b31923d9cc7`
is retained in #2388 bundle
`dfc1022f0c76e9b08b2f0af80a35bcd43d4c61619cda8b581d8ab812abc5fdd5`.
It records 24 executed, 23 passed and one failed, followed by a named 1/1 pass.
Its phone checkpoint trace is missing. The producer gap and replacement handshake
are correlations; the original failure stage remains unknown.

Additional files read: `ThreadViewModel`'s suggestion collectors and
`ThreadViewModelReplySuggestionTest`, `ThreadInputBar`'s pointer handler,
`scripts/e2e-emulator.sh`'s fragment-release fence and
`scripts/e2e-fixtures/reply-suggestion.jsonl`. The owning lesson in
`thread-screen-composer-drafts-and-attachments.md` is that disconnect invalidates
an observed revision: same-revision reconciliation must not re-arm the composer.
Core's `replySuggestions.current` and `reconcileReplySuggestions` retain and
re-deliver that same revision. The earlier live-source repair cannot change this
product contract. No decision record or product change is proposed.

First reproduce this boundary with the actual daemon offer: observe it, retire
the phone connection with `severLink`, restore it, then run the unchanged long-press
proof. Retain the failing native XML and checkpoint trace. A fresh unmodified
Linux baseline at Mobile `e6397e0159d7d5e011146882107fe29ddef990ac` passed 1/1
without a producer gap; it does not establish causality.

If the controlled run confirms the mismatch, fence scripted suggestion publication
after reply settlement and a controlled connection replacement. Split the existing
native fixture at its `prompt_suggestion`, using the existing phone-controlled
second-fragment release. Prove that the initial reply and its delivered user echo
survive replacement, that no offer precedes release, then release the actual
native suggestion and retain all existing placeholder, gesture, echo and clear
assertions. No Send is retried. Both fragments remain from the existing scenario;
the live twin and the product's revocation rules remain unchanged.

State/concurrency: reuse bounded test reads, `firstOnLive` and the existing
`DaemonFaultControl.releaseStream`; no new jobs or production state. The native
scenario covers cold setup, reused conversation/session IDs, reply recovery and
offer delivery after replacement. Existing `ReplySuggestionReadsTest` and
`LiveConnectionReadsTest` retain the retired-source and clear-isolation checks.
`ThreadViewModelReplySuggestionTest` retains the same-revision revocation contract.
`ScriptedReplySuggestionTest` retains repository-to-composer delivery coverage.
Native transport and pointer input require the existing device test. Deadlines
remain unchanged. Failures retain static checkpoints and their original causes.

Sizing: one useful behavior, the existing scripted submission proof; about 300
written lines including this plan, no exported types, no production signatures or
reject branches, one native consumer, three acceptance criteria. In-flight #2462
and other deterministic-class branches change different methods; edits stay local.
Run the affected JVM classes, the focused scripted scenario, lint, assembly,
instrumented compilation, formatting and final pre-verification on Linux.

### 2026-10-22 — Native causal reproduction and release boundary

Controlled native run `scripted-rf1u00z5` executed one test and failed at the
unchanged 90-second placeholder wait. Phone logs show the actual offer accepted
and shown, then hidden with `reason=session` during retirement, then accepted on
the replacement without being shown again. `firstOnLive` reaches the placeholder
checkpoint: publication, delivery and current-source observation have succeeded;
the missing UI placeholder precedes the gesture. The run also retained a natural
`baseline_required` teardown. This proves the post-repair revocation mechanism,
without asserting an unretained checkpoint for the historical #2388 occurrence.
Raw reports, phone trace, diagnostic source/patch and APK hashes are retained at
`logs/evidence/2436/reopened-controlled-red-20261022/` in the agents checkout.

Refine the planned split to hold **both result and suggestion**, leaving the
assistant reply arrived but its turn busy across the controlled reconnect.
Core's two-second native-suggestion window begins at result; withholding only the
suggestion could let fallback win first. No new text is authored: move the two
original lines together to the existing phone-controlled second fragment.
The native regression asserts the initial delivered user echo and exact reply,
busy state before and after replacement, recovered reply identity and absent
placeholder, then releases once and runs the original long-press proof unchanged.
It must fail against the original single terminal fixture before wiring the split.
No production code or live-twin change is required. Existing deadlines remain.

The first fenced-fixture attempt exposed an overconstrained new preparation
checkpoint: the original fixture does not replay the initial user's message, so
its delivered echo cannot gate the still-held result. Keep the actual initial
assistant arrival and busy-state proof as the release prerequisite instead.
The required exactly-one delivered **suggestion** echo after the real gesture is
unchanged in `assertReplySuggestionLongPress`. Preserve the failed 1/1 native
attempt under `reopened-held-precondition-red-20261022/`; do not count it as a pass.

The next native probe reached held content on the replacement before its transient
phase reconciliation: the checkpoint sampled Idle at 23:25:30.028, then the phone
accepted Responding at 23:25:30.060. Make busy phase part of the same `firstOnLive`
acceptance predicate as the exact arrived reply, using the original deadline,
instead of asserting on the first content snapshot. The failed run is retained at
`reopened-phase-observation-red-20261022/` (one executed, one failed).

### 2026-10-22 — Focused development verification

The corrected fenced scenario `scripted-6xipuf9p` executed/passed the exact native
method once, with zero failures/errors/skips. Both held-content/busy checkpoints
used distinct connection owners with the same reply identity, followed by every
original offer/placeholder/gesture/echo/clear checkpoint. Raw reports, phone logs,
source patch and APK hashes are retained at `reopened-development-green-20261022/`.
Core source was `647a4e7526f581f78d422195cb22b3e9b979e767`, binary SHA-256
`871b3cf96e8c8993f09cedc97b8269bb2f4c80b7f829743afaae7f5a4d32a49a`.
The two fragments concatenate byte-for-byte to the original fixture (SHA-256
`c42b502052c3ec258a0af6d9a260fa1a1964076df792bc24833c9bd7949ec648`).

The four focused JVM classes passed all 19 tests: `ReplySuggestionReadsTest` (4),
`LiveConnectionReadsTest` (4), `ThreadViewModelReplySuggestionTest` (9) and
`ScriptedReplySuggestionTest` (2), with zero failures/errors/skips. Each ran as a
separate focused invocation. Raw XML and a hash manifest are retained at
`reopened-jvm-20261022/`. These evidence paths are beneath
`$AGENTS_REPO_PATH/logs/evidence/2436/`. Final pushed-candidate native checks and
pre-verification are recorded in the PR; complete configured scripted acceptance
remains dispatcher/verifier-owned. macOS is outside this Linux ticket's scope.

### 2026-10-24 — Bounded continuation and retained-artifact review

The preserved source at `b821a18a4245a01793251d69efb90fabf1d11da7`, its five
changed/untracked files and all saved native attempts were archived before edits.
The complete retained evidence inventory verified 607 manifest entries with no
mismatch. The unpublished commit changes only this plan. The proposed two
scripted fragments concatenate byte-for-byte to the original fixture, SHA-256
`c42b502052c3ec258a0af6d9a260fa1a1964076df792bc24833c9bd7949ec648`.
They contain the existing scripted reply/result/suggestion, not a newly captured
Claude exchange. No recorded-client fixture or strict recorded contract changes.
Raw phone and daemon artifacts remain private in the retained evidence area.

Requirements remain relevant: native offer, exact empty-composer placeholder,
pointer long-press/release, single verbatim delivered suggestion echo, newer clear
for the offered session, placeholder removal, replacement-source regressions,
product revocation and original deadlines. The prototype's initial-user-echo
precondition is not part of the final design: the original fixture does not supply
that echo before its held result. Delivered assistant identity plus non-idle phase
is the recovery prerequisite; the required post-gesture suggestion echo remains.
The failed precondition attempt stays retained. No acceptance criterion is waived.

The approved published Core repair is merged PR #3726 at
`3d5379ec4b778c569c27b7639a2ae495b32cee0d`. Ancestry confirms the prototype's
`647a4e7526f581f78d422195cb22b3e9b979e767` lacks it, while the effective daemon
`64ef0e0fba71a723b7fdc97141cfc144f6355664` includes it. Only approved source and
published evidence were used; the historical refused Core stage-record request
was not repeated or reclassified as an approval.

The original unfenced scenario at the preserved Mobile commit passed on that
effective daemon: `scripted-mdbiqij7`, 1 executed/passed, zero failures/errors/skips,
with all seven original checkpoints. Daemon SHA-256 is
`9d1b74bbfa1163c2043ecdbb53b321c3574cfaf60af4bdced90a62c447476d66`.
This is a current baseline, not proof that arbitrary offer retirement is safe or
that the historical #2388 failure had the same checkpoint. The existing product
regressions deliberately reject same-revision reconciliation after disconnect.
The saved controlled-retirement diagnostic is being compared on the same daemon
through ordinary Linux admission before finalizing the release-boundary evidence.
Final candidate checks and the independent configured gate remain pending.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`,
“Reply suggestion live-connection observation (#2436)”, with the reopened failure,
demonstrated remaining cause and counted Linux evidence. Preserve the previous
controlled proof and historical evidence limitations.
