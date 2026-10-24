# Durable-gap failure isolation (#2514)

Linux, 2026-10-24. Zero Claude turns. These are builder focused results;
configured dispatcher acceptance remains pending.

## Counted results

| Check | Executed | Passed | Failed | Errors | Skipped | Retained folder |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Legacy cleanup JVM controls | 9 | 0 | 9 | 0 | 0 | `jvm-red` |
| Initial repaired JVM controls | 9 | 9 | 0 | 0 | 0 | `jvm-green` |
| Cleanup-only failure control before repair | 1 | 0 | 1 | 0 | 0 | `cleanup-edge-red` |
| Final JVM controls | 10 | 10 | 0 | 0 | 0 | `jvm-final` |
| Legacy native isolation | 1 | 0 | 1 | 0 | 0 | `native-red` |
| Initial repaired native isolation | 1 | 1 | 0 | 0 | 0 | `native-green` |
| Final repaired native isolation | 1 | 1 | 0 | 0 | 0 | `native-final` |
| Unchanged normal ping pair | 2 | 1 | 1 | 0 | 0 | `normal-pair-failed` |

The original nine JVM controls cover disconnect failure, immediately after severing,
offline production/restart, catch-up before/after normal restoration, simultaneous
restoration/peer/probe failures, primary identity and suppressed order, self-suppression,
pre-disconnect failure and successful offline/catch-up sequencing. The final additional
control covers a cleanup-only exceptional exit after successful catch-up.

The native selector is explicit and separate:
`DURABLE_GAP_ISOLATION_PROOF=1 python3 scripts/android-test-gate.py scripted ping`.
It runs only `interactiveTurn_durableGapFailure_restoresBeforeFollowingPing`.
The legacy control retained the injected primary, then timed out at the unchanged
30000 ms fresh-connection bound. Final phone phases show offline injection, restoration,
peer close, probe end, primary preserved with two cleanups, fresh connection, successful
following ping, and unchanged Activity/Koin graph identity. The full original ping
method's assertions run inside this proof. Both 90000 ms reply deadlines remain intact.

The normal command remains `python3 scripts/android-test-gate.py scripted ping`.
In `scripted-am4jfs36`, `interactiveTurn_seededChannel_durableGapCatchUp` passed;
`interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` failed later at
`SecondClientPeer.markRead` with `peer read mark refused`. Phone send/reply and confirmed
read had completed. Core logged `mark_conversation_read.history_unavailable` for the
diagnostic peer. The unavailable lookup's cause is unproven; this does not establish a
shared cause with historical ping failures. Evidence is recorded on existing
[#2469](https://github.com/pyrycode/pyrycode-mobile/issues/2469#issuecomment-6379144206),
linked and read back as an open blocker of #2514. No assertions were ignored or
weakened and the failed pair was not retried to obtain green. Required builder normal
2/2 proof and independent configured acceptance remain pending.

## Tested identities

- Legacy native source: `7a6f00ebcf8a6821afdc7be7f7ff4977fcd90b91`, run `scripted-3rzuvlqi`.
- Initial repaired native and normal pair: `a6de68392e6f53d0211caf562e90530a1abdd11c`, runs `scripted-ese1ooev` and `scripted-am4jfs36`.
- Final JVM/native source: `6ff4622d7ae7d6ddc35ae875fab132ddd2b7b918`, native run `scripted-aezx0ugq`.
- Supplied Core source: `cb556b7697fa6f10685a630fb6a73139ed82cf9c`.
- Effective daemon SHA-256: `4a090b5cb5784f2e23f5ca0b53b23ee208c0b272a34a069118648fd36554c811` (embedded VCS unavailable).
- Relay source: `9939bc1ff8afec5021c09862e6f14dadba33ad42`.
- Effective relay SHA-256: `27ec11505393dd62fad8c9a2cb672775b230e031b7240ac9c11fddc847324d10`.
- All native runs' app APK SHA-256: `f060dfbe3c19b57616900416b965169f50730ae5b788e16551596049db7925d2`.
- Legacy native test APK SHA-256: `134e0fc9c428420053a8fd246ab6d07f058811bf244d0b1d7ed80dc3e16ffe68`.
- Initial repaired isolation/normal-pair test APK SHA-256: `eb5b80d5be897af7fcbfd72615d9fb44f8f06d25717f01b485512c097608390e`.
- Final native test APK SHA-256: `2562a793def35ce0616cb3f5fff0995c4cdb0a13dcf280710967755036df2791`.

These identify the actual tested source/APKs, not a later evidence-publication HEAD.
The cleanup-edge red folder also retains the exact uncommitted test patch over its base.

## Retention

Raw XML, full phone logs, isolated daemon/control/relay diagnostics, identity JSON,
count summaries and cleanup receipts are private under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2514/builder-20261024/`.
Each folder's SHA256SUMS was verified after copying. Aggregate and native XML in one
folder report the same execution; they are not separate runs. All historical failures
remain failures, including the original #2442 evidence and its provenance correction.

| Folder | SHA-256 of SHA256SUMS |
| --- | --- |
| `jvm-red` | `e5dc271c16e791a5b7b72a66faaee9291a67f8c0b94ff65c3a332499bc5dd86f` |
| `jvm-green` | `53d5b2d37acf91605c5d24e6552873789e8662c12dee212b5e71eb0670d7525e` |
| `cleanup-edge-red` | `a1c098aaa6e779758fba9e878419c33eea65d2210220844ab32879276c21761d` |
| `jvm-final` | `4af0ee1729ce818200ac9f9d6bc69b039d1180e7dfbea90aaf62a3093668c258` |
| `native-red` | `309561750a8948327680a388320988c6bec9ac4d78a8b521086c7d848898966e` |
| `native-green` | `b1e8bc75f46d578cd1f01c34c7166aa24b0c70a8a265019dd61a89148d5d61e9` |
| `native-final` | `1aa7bee1f3202ed26a7062f1d6e9890f18eef78bc41dab8826325b6cf87d89e3` |
| `normal-pair-failed` | `e1b20a4794562e3b6dffd512a87599f67780bb56f8c201cb73fa5c9a1f90b263` |

Pending documentation stage: update `docs/e2e-interactive-stream.md`, “Scripted ping
durable-gap proof (#1842),” with restoration and primary-failure behavior, counted
isolation proof, historical failures and the boundary against configured acceptance.
The other operating system is outside this ticket's verification scope.
