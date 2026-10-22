# #2464 — measure DB Retry through the committed older-message frame

## Confirmed acceptance gap

Independent #2451 review on a23b found that the paging method asserts the opening Probe bound before demand, then permits 15 seconds for Retry to deliver 400 rows. Its logged drawn_ms still measures opening. Keep that historical evidence as results of the old assertions; it does not establish Retry latency. This bounded child changes native test instrumentation only, based on feature/2463. Full-file timing remains independent non-blocking #2456; import latency is distinct.

## Test first and bounded mechanism

First refactor the existing four-arm DB paging scenario into a helper with successfulOlderReadDelayMs default zero. Preserve the production Room/MigratingConversationCache/destination/repository/ViewModel, 18000-message seed, 200-row opening, unavailable-read hold, settled Retry, exact 400 facts/two attempts/200 decodes/request/viewport checks and no automatic page at rest. Delay only the successful consumed beforeAuthoritativeWindow read, before entering the production disk read; never the deliberately unavailable first attempt or an unused dispatcher. Add a 3000 ms negative control which demands rejection by the same bound. Before a demand timer exists this control must fail because the old opening probe still passes; inspect actual native red and retain it before adding the timer.

Then add an attempt-scoped probe created at the actual onRetryOlderHistory callback immediately before forwarding to the real ViewModel. Its timestamp is separate from opening and excludes the failed-read hold. End only from Android registerFrameCommitCallback after OnDraw observes the exact requested older message, placed fully inside thread-message-region. Capture the attempt probe in the callback so stale work cannot write a replacement opening/attempt. Preserve the existing oldest-row/viewport assertions; log opening and Retry frame times independently. Normal reads must satisfy the existing 1000 ms product / 2000 ms SwiftShader bound in all offline/held-newest first-open/reopen arms. The actual 3000 ms successful older DB delay must be observed and rejected in every arm; retain the separate opening slow control unchanged.

Do not replace a committed visible frame with row-count completion or a virtual Compose clock. No production paging/performance change, schema, file reader or ownership policy change is authorized. If the bound exposes an independent product defect, retain its evidence and file a bounded blocker before product repair.

## Validation and handoff

Execute the actual pre-timer negative control red, then the repaired normal paging and slow-older control through the normal admitted native gate. Run required formatting/compilation and final actual preverify. Publish PR base feature/2463, head feature/2464. Independent complete configured Linux acceptance, actual documentation and normal reviewed merge are required for this child, then every ancestor separately. Keep all earlier failures, skips, cache provenance, raw source/APK/runtime/phone/XML evidence and manifests. Documentation belongs in authoritative-reader-paging.md; move older history verbatim into a linked topic if needed under the 50000 byte cap while preserving parent links and current receipt.

## Security review

**Verdict: PASS** — planned test-only mechanism. The only injected delay is owned by the selected test cache and successful older DB read. Each frame callback captures its own opening/attempt identity; it cannot mark another reader's frame complete. No raw user content, token, endpoint or credential logging is introduced; fixture text is deterministic. Production authentication, admission, generation/reset/reader fencing and certification remain unchanged. Review actual implementation and causal red before handoff.
