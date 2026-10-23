# Final integrated authoritative reader paging acceptance (#2334)

## Approved acceptance scope

On2026-10-22 the user explicitly approved focused causal regressions and independent review for each repair, ordinary integration of the remaining stack, then one complete configured acceptance and one final documentation pass on integrated2334. Broad gates repeat only when subsequent relevant changes justify them. This supersedes earlier per-ancestor full-suite and documentation requirements; original failed outcomes remain historical.

The final application and test source is `688f93fcbdbf591d785a135af1e09aa07c48947a`, including reviewed main `99aab2c925fdea78ffd204c7b363c2a37dea1674`. PR2372 was fast-forwarded to that exact candidate. Current independent run `66d85823fca3674377ab6f6b4f90674bfe2c25b3d56ef8a8b89d35133550ed09` accepted PASS on the same commit. Using that actual run for the identical integrated candidate is evidence reuse, not another unexecuted test run.

## Complete configured acceptance

- Security/policy preverify and documentation guard passed; script-runner unit tests executed214/214PASS.
- Combined JVM/build gate `jvm-3z4ei9jw` passed lint, Spotless, assembly and instrumented compilation. Its5310-passing-method report was verified FROM-CACHE: reusedGradleResult=true, executedInReport5310, executedThisInvocation0. No fresh5310-method execution is claimed. The earlier fresh5310-pass result is bound to1ef86c; all production/shared/JVM sources are byte-identical between that reviewed candidate and688f. Only the native readiness guard and two plan topics changed.
- Native `ui-2xkw_1tg`:334listed,333executed/passed, zero failures/errors, one skip (`RenameDialogCaptureTest.renameAtFigmaViewport`). All seven SavedThread methods and the original Agent restart method executed and passed.
- Device `scripted-all-5vouhl2f`:24executed/passed, zero failures/errors/skips, including daemon-backed ongoing-reply reopen and Agent restart/settlement reconnect. The isolated daemon/relay uses fakeclaude, zero real Claude turns. Script-runner unit tests are separate evidence.

The [current accepted receipt](authoritative-reader-paging.md#accepted-source-binary-and-capture-provenance) retains commands, runtime, source/APK/image identities, final raw/normalized XML and phone evidence. Official2140-entry archive `2473/independent-688f-20261022/verifier-final-20261022T155224Z` has manifest `d7b8344df7bb741fa4c71248fedbd31897b9eceb9b9e6951f2ec2b071c3ac7d4`. Additional2256-file operator preservation `operator-progress-20261022T155304Z` under the same parent has manifest `4e1716ea93720957d26b8586bef676b45c88d04f5fa55930a635adce30f870d2`, including23 retained harness bundles with raw daemon/relay/source receipts. Unused alternate daemon logs and historical missing baseline XML/phone remain explicitly unavailable.

## Independent repair review and scoped checks

Independent1ef86 review explicitly covered all44 changed files and found only the Agent readiness failure. Independent688f PASS re-reviewed every subsequent non-merge commit and the sole correction; no new findings. The required original IDs must be present before unchanged strict checks, with the same120000ms deadline and current-receipt/generation rejection. No new production, reader, collector, query, storage, capability or transport path was added by that correction.

These review results compose the current source review. All production/shared/JVM file blobs are unchanged from the broader reviewed source. The scoped summaries below refer to covered methods in the accepted cached report, not new focused executions; their earlier causal red/green executions remain in each linked plan.

| Repair | Scope and regression coverage |
| --- | --- |
| [2437](../../specs/architecture/2437-local-retry-generation.md) | Reader replacement/generation/cancellation:57 covered paging/owned-upsert/store methods; native demand/Retry/reopen passed. |
| [2447](../../specs/architecture/2447-authoritative-native-first-draw.md) | Approved authoritative-only mapping:all7 current SavedThread native methods passed, including file correctness and zero retired reads. |
| [2449](../../specs/architecture/2449-authoritative-file-validation-cost.md) | Retained validation/restore optimization:47 covered file-cache/decoder methods; exact file compatibility passed. No additional file-speed requirement. |
| [2451](../../specs/architecture/2451-saved-authoritative-numeric-lexemes.md) | Exact unknown numeric/raw facts:10 covered file-cache methods and full native compatibility passed. |
| [2463](../../specs/architecture/2463-ongoing-reply-reopen-source.md) | Restore-health/reopen comparison:40 covered paging/store methods; actual daemon-backed reopen passed. |
| [2464](../../specs/architecture/2464-db-retry-committed-frame.md) | Actual Retry callback-to-committed-frame bound:ordinary and real3000ms older-read controls passed across all four arms. |
| [2470](../../specs/architecture/2470-agent-restart-placement.md) | Passive evidence/controlled-source compatibility:62 covered methods; recorded and scripted Agent restart passed with original strict content/owner/placement checks. |
| [2473](../../specs/architecture/2473-negotiated-local-history.md) | Three localHistory forwarders:41 covered decorator/passive/attention/paging methods, preserving the original four-failure causal red and direct control. |

## Reviewed integration and documentation

All eight repair PRs are merged and their issues are CLOSED/centralDone. PR2474 merged at `5c5843391367c678d3b292382df89f46ef717b22`; its one documentation pass committed `e892c38db33681317fbe70451c74d24c8d77254d`. Subsequent integrations changed no application/test source relative to accepted688f. Each remaining head/base ancestry and documentation-only delta was checked before merging, with fresh protected closure requests preserving other holds, counters and approvals.

| Repair | Reviewed PR | Merge commit |
| --- | --- | --- |
| #2473 | [PR #2474](https://github.com/pyrycode/pyrycode-mobile/pull/2474) | `5c5843391367c678d3b292382df89f46ef717b22` |
| #2470 | [PR #2471](https://github.com/pyrycode/pyrycode-mobile/pull/2471) | `7ad19a47e50e751211a4b199b3aae61d2a2e88ce` |
| #2464 | [PR #2466](https://github.com/pyrycode/pyrycode-mobile/pull/2466) | `4c4b81f2d2aeb1022d92784b96f8e3344e692bc5` |
| #2463 | [PR #2465](https://github.com/pyrycode/pyrycode-mobile/pull/2465) | `c765d902eecc33c36fd43d0f8cf41c7563394e81` |
| #2451 | [PR #2460](https://github.com/pyrycode/pyrycode-mobile/pull/2460) | `ec7450f6fc9612f26e29013a46629af46cce0f13` |
| #2449 | [PR #2459](https://github.com/pyrycode/pyrycode-mobile/pull/2459) | `1848353d146384bafbbe6f8a0921395db920fd0d` |
| #2447 | [PR #2458](https://github.com/pyrycode/pyrycode-mobile/pull/2458) | `7bf6e2bd8030b3bd4c0e08706ddc29f12694bc9b` |
| #2437 | [PR #2438](https://github.com/pyrycode/pyrycode-mobile/pull/2438) | `77371a3f2f29d4de6e74102e1b3b348aef031f8f` |

GitHub marked PR2438's old head merged when PR2372 first received accepted688f. The later reviewed documentation/merge history was explicitly fast-forwarded from `7bf6e2bd8030b3bd4c0e08706ddc29f12694bc9b` into2334; it was not lost or inferred from that earlier PR state. Final documentation notes and this ledger complete the same consolidated documentation pass. Root PR2372's final merge/closure is recorded by GitHub and central workflow; child closure alone is not that final state.

DB opening/demand/Retry/cold-reopen remain1000ms product/2000ms SwiftShader, including actual consumed-DB3000ms negative controls. File correctness remains36000facts/18000islands/18000visiblemessages with raw numeric/read/request/ownership checks. Positive file latency remains independent NONBLOCKING2456; actual one-time import latency is distinct. Retired projection/runtime APIs stay retired. The [main topic](authoritative-reader-paging.md), [verbatim earlier evidence](authoritative-reader-paging-early-evidence.md) and three parent topics retain every historical failure/raw path/hash and capture limit.
