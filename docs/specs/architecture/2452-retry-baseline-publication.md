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
