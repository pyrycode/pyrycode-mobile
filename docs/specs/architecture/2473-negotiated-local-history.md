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
