# #2473: retain local history through negotiated test decorators

## Scope and causal boundary

Independent #2470 verdict at a4fafc identified an inherited omission: TappingConversationRepository's recorded route, InitialAttentionQueryGate.negotiated and PassiveThreadProbe.negotiated delegate the selected negotiated route but inherit localHistory's Idle default. The destination therefore loses actual local-page Loading/Retry through the installed wrapper chain. This is separate from the three synthetic evidence regressions; those are repaired within2470 and their62 affected JVM checks passed on0b0455. This bounded child changes zero production files. Intended PR basefeature/2470/headfeature/2473; no file performance or new product scope.

## Plan before tests and repair

Add a deterministic controlled negotiated route exposing the real localHistory contract and exact selected store/support/connection. A direct control must pass. Each wrapper individually and the actual installed TappingConversationRepository(tapNegotiated=true) chain must propagate Loading, Retry, the original older retry action back to Loading, and Idle. Check exact flow/state identity, host/conversation routing, same store/support/connection/generation and zero extra query/open/observation/cancellation. Run these five tests against unchanged wrappers first; record actual red and preparation counts. Do not repair from static inference if it fails for another reason.

Only after causal red, forward localHistory directly through the three transparent wrappers. Keep every existing acquire/open/older/cancel/progress/observe path, passive UI receipt/current-owner rejection and first-attention gate behavior unchanged. No new route, query, lease, job, state conversion or timeout. Then run the decorated contract class plus existing PassiveAuthoritativeThreadObservationTest, InitialAttentionQueryTest and full AuthoritativeReaderPagingTest to retain real Room/repository/ViewModel replacement/cancellation/reset controls. Final actual preverify follows the final candidate. Complete independent configured Linux JVM/UI/scripted including all seven SavedThread methods, actual docs, reviewed merge and centralDone remain required before advancing2470 or its ancestors.

## Security review

**Verdict:** PASS for this bounded test-first plan; final diff and independent review remain required.

### Findings

- Trust boundary and authorization: wrappers retain the exact selected host, support, store and connection. No capability or admission promotion.
- Input validation and injection: no new input, parser, command, SQL or protocol path.
- Credentials and cryptography: no secret, authentication, pairing or crypto change.
- Data and privacy: no new persistence, payload logging, file access or fixture export.
- Ownership and lifecycle: forward the existing history flow; do not acquire a reader or affect passive evidence, close, cancellation, reset or generation fences.
- Resource use: no new collector, thread, job or query; tests assert observation/open/cancellation counts.
- Error handling: Retry remains the actual provider state and older remains the actual retry action. Do not swallow a failure or synthesize Idle.
- Dependency and platform: no new library, production API or platform behavior.
- Verification: deterministic red precedes the three forwarding lines; retain original red, controls and full independent acceptance evidence without waivers.

## Evidence status

Plan committed before new test source; wrappers unchanged. #2473 was filed manually held at creation, linked as actual2470child and OPEN acceptance blocker through2334; no implementation cycle. DBopening/demand/Retry/coldreopen1000ms product/2000ms SwiftShader and actual consumedDB3000ms controls remain. Direct-file compatibility stays36000facts/18000islands/18000messages; positivefiletiming belongs only to nonblocking2456. Final accepted receipt belongs in authoritative-reader-paging.md with history retained verbatim and all parent links under50000bytes.


## Actual causal red before repair

Exact956e50fd7954963670abc578dbe25907ba9ecd36: five new JVM methods executed freshly, one direct-provider control passed, all four decorator/installed-chain cases failed the intended assertion: actual Loading was hidden as Idle. Zero errors/skips. ForcedSpotlessApply passed; only inspected test parameter layout/trailing comma formatting was preserved in a separate ordinary commit. Raw2473/manual-20261022/decorators-red-956e manifest9cc1c675dead8ce4384782bb31ac5aa95d33e06cad280a03838da7dc8f99c9d8. No wrapper or production change preceded this red. The demonstrated defect justifies exactly one localHistory forwarding override per transparent wrapper; retain all predicates and controls unchanged.


## Repaired affected validation and actual diff review

Exacta1804c61ddc77687c5331ebcd023da9d95a9f093 freshly executed41/41 affected JVM methods, zero failures/errors/skips: Decorator5, PassiveObservation3, InitialAttention11 and ReaderPaging22. Forced format and SpotlessCheck passed without source-layout changes. The decorated contract now retains the original exact flow, all Loading/Retry/Idle transitions and retry action; no observation/open/cancellation was added. Existing actual Room/repository/ViewModel reader replacement/cancellation/reset and passive current-owner/attention gates passed. Raw2473/manual-20261022/decorators-green-a180 manifestd431f9d06713d2dab843883693673f2ce99962c3428cbcce5f357dde487e3e46. Original four failures and direct-provider control remain in the causal-red archive. No native method ran in this manual job; the archived reused APKs remain explicitly historical.

Actual final security diff review: PASS. Five files139 inserted lines before this evidence appendix, zero production files; the implementation is exactly the three forwarding overrides, with unchanged acquire/open/older/cancel/progress/observe paths. Trust/authorization, input/injection, crypto/credentials, data/privacy, dependencies/platform and error semantics retain their reviewed boundaries. Lifecycle/resource checks confirm no new observation or ownership lease; existing reset/replacement controls passed. No new payload log, timeout, transport or store mutation is introduced. Full independent Linux JVM/UI/scripted acceptance and actual docs/normal merge remain required on the final candidate. Parent2470 stays manually held with original independent FAIL and its subsequent62 affected passes preserved.

## Independent integration failure and parent-owned readiness correction

Run661e1c937d72a77d9612c05503ae9f1a7bed68304d1d4a0fdbef5f660c2cb22c at1ef86c failed:5310freshJVM passed; fullUI334listed333executed332passed1AgentNoSuchElementException failure1RenameDialogCapture skip; unchanged focused Agent repeat failed1/1, reviewed main99aab passed1/1. Product scripted did not run after the UI failure. Review confirmed no additional source finding in the three forwarding overrides; it did not isolate the stacked Agent failure to them. Full verdict is preserved with original XML/phone/source/APKs and baseline cleanup limits in2473/independent-1ef8-20261022/operator-final-failure-20261022T145645Z,1800files, manifest73b2389de1614596d6a26a04915403e3009e3c736b4b155be0c8c52e4912484f. Manual hold was restored before any automatic builder. Every ancestor hold/error/claim/counter/approval/blocker remains.

The existing2470 Agent placement owner corrected its unchecked required-ID lookups after plan/securitye66007c67d. Exact baseline-family, before and recovered-send IDs must all be present in each current admitted passive screen receipt before unchanged strict identity/content/family/send/placement checks and current-generation keys are used. Missing facts leave the original120000ms wait unready; no global-store fallback or product change. Parent repair247074d70d64b44ee7be41681380d39a9c6becce was ordinarily merged into this child as22a0da1dd1bbb17f8715a9d2f891401b30fbf041; three forwarding overrides and their tests remain unchanged. No new ticket or child scope growth.

On that exact integrated22a0, original nativeui-n1r1dqwk freshly executed/passed1/1, zero failures/errors/skips. Phone diagnostics prove required fact85 initially missing, then reject retired generation0 after reset, and finally accept identical current generation1 screen evidence23facts/version96/checkpoint96 with original indexes4/8/1 and final restart/settlement receipt. Forced format passed with no diff. No JVM tests ran; earlier5310PASS retains old1ef86c provenance. Raw2470/manual-20261022/readiness-integrated-22a0 manifest4278638a6242a7c5f046388c6d5eb042787fbd02a286d850bbfeb9e09c283a7e. Reused productAPK633eab16b9ac41926bbfa2c6b027e01bc7373c134d39503ff38423855e10d3b3 mtime10:14:06.218391Z; new testAPK2b789bcc9ab25695d2faca70370f0b835c45af6629434d125f48eb4ecaf16521 mtime15:07:26.487407Z.

Final integration/security self-review: PASS across the same nine reviewed categories. Child implementation remains exactly three localHistory forwarders, zero production files. The parent presence guard changes readiness only: original epoch, duplicate/changed-content rejection, same-current-receipt owner/generation identity, graph/cache lifecycle and UI inequalities remain strict. No query, reader, lease, job, capability, parser, credential, crypto, storage, transport, platform or dependency change. Numeric diagnostics contain no payload. Actual final guards and fresh independent complete configured Linux JVM/UI/scripted, actual docs, normal reviewed merge into2470 and closed/centralDone remain required on the new child head; no focused pass accepts a candidate or ancestor.
