# #2452 — retry baseline publication ownership

## Retained finding

Native ui-qpyy9qa8 on33516287c3 executed6/pass3/fail3/errors0/skips0 after340affectedJVM methods passed. The database retry method failed expecteditemDecodes200/actual400 in offlineopening1. Its phone log shows exactly200decoded facts on each explicit attempt, successful reader_page, then an extra read_parent_closure before400rows are projected. The prior d87e online failure and unchanged-tree named pass remain visible. Full current raw source/phone/XML/APKs/image/runtime are retained at2451/manual-20261022/green-3351, manifest25ef9e1c4117f197164e49e018edcef0cf29f22bccf2aa30fd4816d9b70bc8bf. No flaky waiver or count relaxation.

## Causal hypothesis and test first

applyAuthoritativeRestore publishes the restored store and then updates persistedAuthoritative under the same store monitor. observeAuthoritativeThread reads that map without the monitor. Another worker can observe400storefacts while still holding the old200-fact baseline, classify the restored page as missing committed facts and schedule redundant parent closure. Force this exact boundary in a real disk/repository/ViewModel regression: use a private test replacement ConcurrentHashMap to pause its existing compute after store publication, with a real two-thread processing dispatcher. The observer must either wait for the same publication monitor or expose the old-baseline read. Release every latch in finally. Verify explicit failed-demand/Retry/success, exact400facts,200payloaddecodes, twoattempts and no extra request at rest across offline/held-newest cold reopens. The test changes no production seam and precedes the production fix.

## Bounded repair if confirmed

Read the baseline on processingDispatcher under the existing authoritativeThreads monitor before deciding needsClosure. This pairs it with restore publication and baseline compute; never hold the monitor across disk access, a coordinator call or suspension. Keep all owner/generation/reader/cancellation, retirement, closure and writer checks. No new lock order or paging redesign. Intended childPR basefeature/2451/headfeature/2452 keeps only this causal delta; parent2449 still owns latency. Never accept parents through child closure.

## Security review

**Verdict: PASS** — builder self-review of proposed bounded locking,2026-10-22. A short monitor-protected map read on the worker grants no validity and changes no persistent data, network request or capability. Existing historyWrites→store order remains unchanged; this read takes only the store monitor and releases it before any suspend/disk/coordinator call. No endpoint, credential, raw-user logging or mutable alias is added. Independent review must audit observer scheduling, publication pairing, cancellation and unchanged complete gates.

## Validation and documentation

Keep1000ms product target,2000msSwiftShader bound,36000facts/18000islands/18000visible fixture, actual3000msslowcontrol and all six mapped native methods unchanged. Require deterministic pre-fix red, affected and named native current-source checks, independent full configured Linux JVM/UI/scripted review, actual documentation and reviewed merge/Done. Record complete accepted evidence in authoritative-reader-paging.md, retaining every historical failure. Current result is pending.

## Confirmed causal red and bounded repair

On54296992a3 plus formatter-only9d99e75a7f, the one selected/executed regression failed exactly expecteditemDecodes200/actual400,0passed/1failed/0errors/skips, fresh XML. It reached the controlled publication edge, recordedold_baseline=true and ReadCounts(itemReads400,itemDecodes400,ordinalReads200,evidenceReads2,evidenceDecodes0,queries208) through real disk/repository/ViewModel on offlineopening0. The full raw XML/log/test/unchangedproduction/plan/formatterdiff are retained at logs/evidence/2452/manual-20261022/red-5429, manifestbd9738ccb51eb19027b28d753247a71407d8e59d5c804f1c1f8d1804d0a22487. This is the actual repeated native cause, not a harness or timing failure. No assertion was relaxed.

The repair reads persistedAuthoritative on processingDispatcher under the existing authoritativeThreads publication monitor before the observer decides whether it needs closure. The monitor is released before processing, disk, writer or coordinator work. All other behavior and every application fence remain unchanged. Current affected and native checks, independent complete configured acceptance and actual documentation/merge remain pending.

## Repaired affected and native evidence

Source76b1e89803 passed67/67selected affected JVM methods (ReaderPaging17,AuthoritativeThreadCache23,Store15,CachingConversationRepository6,NegotiatedViewModel6), zero failures/errors/skips, plus lint, assembly, native compilation and forced formatting. The controlled race passed all four offline/held-newest cold openings withold_baseline=false,itemDecodes200,queries6 and exact400facts/twoattempts. Native ui-9j7pnxza did not execute the requested product method: an incorrect Class.method selector caused initializationError/ClassNotFoundException. Retain that invocation as preparation failure, not a product failure or pass (green-76b1 manifest90545df2556bf3c4e80d8b4f91fed3fa0bca831a408edcdec03802cbb3a7b020).

The unchanged-source corrected Class#method invocation ui-zfqyaha8 selected/executed1/pass1/fail0/error0/skip0. All four native arms retained exact400rows,200itemDecodes/200ordinalReads,oneevidence row,sixqueries,twoattempts and expected0offline/1onlineheldquery with no automatic walk. Frames890/371/319/261ms met the unchanged2000msrendererbound. Complete raw XML/phone/APKs/source/plan and managed-image identity are retained in native-selector-76b1, manifesta0d4f94056857bb39e9b6f77c56e9b36bf3508a1faec8df95ce77129e44b4d45. Formatter diff is empty. This is repaired manual evidence; independent full configured acceptance and actual documentation/merge remain pending, and parent2449 still has its separately measured file latency miss. Do not release manual holds on partial acceptance.
