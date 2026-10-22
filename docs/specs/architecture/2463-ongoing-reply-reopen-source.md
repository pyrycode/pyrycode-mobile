# #2463 — diagnose ongoing-reply reopen source retirement

## Confirmed evidence and bounded scope

Independent #2451 run0297ee9935aa64557799c36ff00e0d9a6202a06af8ed651dfc6e0afd70645aab on a23b9d1272eae483d8a3bfa3e7fd557bd3f3e0aa failed the configured scripted reopen-stream method twice with90000ms timeouts: full24executed/23pass/1fail and focused1executed/1fail. Main747d782084b655cd01ccf70691ceef749f669b6c passed1/1 with the same daemon2c68b595440dd7e8e843af9cff0d9a708bcc81a7. Preserve the complete failed verdict and comparison, including cached JVM provenance and UI skip. Final failed archive logs/evidence/2451/independent-a23b-20261022 contains1058files with SHA256SUMS-final-failure a330e731b4295ca044d08e711a83836ac97ac1dd68395909000f2dc0b43b63e1. Normal cleanup removed candidate/base worktrees before final APK capture; the receipt explicitly records that limit.

Both failed phone logs show source retirement with4414 shortly after send, before the test's90s wait ends. Corresponding retained daemon logs fault-5aa6b754015398eabf02adef8a18ebff and fault-c9c31918ce56118b62de0c9c37da9f21 identify producer-gap teardown. The method captures currentRepository once and constructs connection-scoped harnessThreadEvidence. That source rejects retired connection evidence, as required. This supports a stale-wait hypothesis, not a proven production paging cause or a shared-flake waiver.

## Plan and test first

First add only narrow phase/source-current/admission/record-count diagnostics to this existing native method. No raw prompt, reply, pairing token, key or endpoint logging. Reproduce through the normal scripted reopen-stream gate on isolated feature/2463, retain XML, phone and daemon/relay evidence, source/runtime identity and manifest. Locate whether the timeout waits for initial prefix, suffix or settlement, and whether its source is retired while the app has a replacement. The existing prefix/display/identity/turn/checkpoint assertions and deadlines remain unchanged in this diagnostic red.

Only after that causal red, choose the smallest consumer/source-lifetime repair. Prefer the existing supported source-following harness seam if the test is waiting on an obsolete connection. Never admit retired evidence, manufacture a reply, weaken owner/host/conversation/epoch/generation identity, remove the first reopened-body checkpoint or increase timeouts. If evidence instead identifies product state loss, add a deterministic production repository/ViewModel regression before that bounded repair. No paging redesign, unrelated file optimization or DB timing fix belongs here; #2464 owns the separate Retry latency assertion.

Run the repaired named scenario and directly affected JVM evidence/source tests. Then final actual preverify, independent complete configured Linux JVM/UI/scripted acceptance, actual documentation and normal reviewed merge into feature/2451 remain required. A focused pass cannot accept this child or any ancestor. Final accepted evidence belongs in authoritative-reader-paging.md; documentation may move older detailed history verbatim to a linked topic to keep every failure/path/hash below the topic cap. Full-file positive latency remains independent non-blocking #2456; exact compatibility and DB bounds remain binding.

## Security review

**Verdict: PASS** — manual review of this diagnostic plan,2026-10-22. Diagnostic output contains only phase labels, booleans and counts; no user content or credentials. Captured retired sources must remain rejected. Any eventual source-following change must select only the currently authenticated host connection, preserve exact reply identity and never let an unverified or replaced connection satisfy the checkpoint. No transport, schema, credential, persistence or ownership policy changes are planned. Re-evaluate this review against the actual causal repair before handoff.

- Findings: raw pairing bundles must never be printed during diagnosis; only approved non-sensitive metadata fields and bounded event lines are read. No production implementation is authorized before the causal regression establishes the failing phase.
