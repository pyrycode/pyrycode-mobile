# Mobile2245 offline recovery recording contract review

Read-only review on 2026-10-20. Decision: PASS for this genuine offline-read capture and its counted successful fresh replay. The reviewed native source preserves the required disconnected recovery, independent producer, cache persistence, all240 exact posts, real reset and paging, rendered uniqueness and chronology checks. Removing the redundant intermediate reconnect changes setup lifecycle without weakening those requirements. No replay request-tolerance change was introduced.

## Exact evidence

HP container pyry-fleet-native-android audit directory:
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2245/operator-manual-20261020/recording-refresh-06/batch-01`

Scenario: `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`.

| Artifact | SHA256 |
| --- | --- |
| Genuine fixture `.json` | `15dfdcc48840707cf915a9189666f944388aae3ae2563ea5a58f8a42b1852800` |
| Capture receipt `.capture.json` | `66c1fb2b85e0eede3e10aa617e47c690211195596767d561902d3bd74b278e7a` |
| Combined capture and replay receipt `.evidence.json` | `2b0f74aa5e2136d6645cf3cbbb607f95ea77c807512dc0a0f06476bbbd3c14f1` |
| Comparison `.comparison-private.json` | `b6be1bec1fb8777ffb487adb463c0cba4853afa24955f125cd9b32716dd10bba` |
| Original shipped fixture in `app/src/androidTest/assets/recorded/suite/` | `e52d05f0a95772ee6a238dd1ddb1d2f7e0e22949bf6f0f5108b432ab50dba755` |

Capture mobile source: `a671976f427fffebd8aaf88e1a60aecd63adadf0`.
Daemon source: `e596971abb101d44d770dea33b4d29d61300e85e`.
Daemon binary: `646cb732b5cc303d3fc7ea3fe83d5dfc07a567f512fc1c23f003f2a5b4581ad5`.
App APK: `4e11052b72552c25364f4dcd84abd4721ad38ea1318397a3efc14ff5129adc6a`.
Test APK: `94c707de1c646cf96b6a7a2da16e9bd7a97a299bd080b37f7e76f8c26aced341`.
Claude: `2.1.280`; Android API33. Capture time: `2026-10-20T10:00:42Z`.

Capture receipt records one executed, zero failed and zero skipped. Fresh replay records one executed, zero failed and zero skipped. Both name the exact fixture hash above and the same actual app and test APK hashes. Capture native execution took34.210seconds. Fresh replay native execution took28.287seconds. No execution was run by this reviewer.

Original shipped source metadata identifies mobile `788314223b701ea5c6a0b530ac255451de97e36e` and daemon `c2e7bec7a434014dfc2089f64d507898c79f8e89`, captured2026-10-16.

## Production route and physical ownership

The fresh raw fixture contains684 events. Both actual phone opens advertise `thread` alongside interactive, multi-agent and stop-background-task. Both peer opens omit `thread`, preserving the permitted independent legacy producer route. The phone sends zero legacy history or backfill requests.

The initial phone connection closes at raw108 and inbound ends109. The phone remains disconnected during both post batches and the missed peer interactive turn. The genuine Android push-wake event222 does not cause a phone reconnect while the test's physical link is disabled. Host stop604 and start607 precede the peer's actual down608, inbound end609 and local close610. The replacement phone connects611 and opens612. It is a distinct actual connection owner. The replacement peer connects640 and opens644. No old-owner response is used for a new-owner query.

Each of the seventeen canonical phone requests has exactly one correlated final response or reset on its own owner and conversation. Requested nonblank catch-up epochs agree with their actual responses. No unsolicited older-page request occurs before recovery enters the explicit older-page gesture loop in the reviewed helper. Neither a reset nor a partial reply is treated as certification.

## Baseline, real missed producer and recovery

The first real phone turn completes before the cut. Its actual canonical window99 and terminal100 certify checkpoint14. A same-boundary catch-up102 receives terminal103. Initial read104 marks11, within that actual certificate. The source awaits the exact owned reply, independent peer completion and saved certified baseline before the deliberate offline cut. The saved baseline is read from the current live source before the cut. No saved epoch, checkpoint or source identity is fabricated.

Two actual post controls generate120 posts each. The peer sends the separate real interactive message at352. Its actual successful terminal364 follows its actual assistant delta. The recovered canonical user fact matches the peer's exact message ID once. The recovered settled assistant family matches that exact peer turn and its complete text. It has one actual canonical root terminal. Assistant and terminal share one nonblank session, are inactive and done, and the root terminal reports success with no error. The exact assistant order is262. No arbitrary response is substituted for the observed producer text.

The replacement phone first performs an unrelated existing-chat catch-up625 from4 and receives terminal632 certifying5. The target chat then sends its real saved checkpoint14 at633. The daemon replies with the actual correlated same-epoch reset634 at506. The next real query635 starts at zero in that reset epoch. Window636 returns50 real facts. Terminal637 certifies506 and exposes an older gap with continuation457. Actual query638 from506 receives same-boundary terminal639. Reset634 alone never advances the independent witness checkpoint.

Nine actual older-page requests follow. They use before-order cursors457,407,357,307,249,199,149,99,49. Each cursor equals the actual continuation from the preceding certified window. Their correlated terminal boundaries retain exact epoch and version506. Each successful terminal narrows the continuation. The last terminal673 exposes lower order7 and older_exists=false. The real page batches contain50 facts each except the final37-fact batch. These are genuine daemon pages and rendered load-earlier actions, not manufactured baseline truncation.

Direct raw reconstruction found all240 expected post texts,240 unique canonical IDs,240 unique texts and exact production chronology across both batches. All three initial certified baseline fact IDs are recovered with nondecreasing revisions and unchanged text. The test additionally verifies the baseline user and reply, exact missed user and reply, no duplication, chronology, current source and rendered rows. The final phone read674 marks504 after actual certification506 and the completed page chain. It does not exceed the real certificate.

## Explicit changes from original shipped fixture

Strict comparison is CHANGED. The full fixture is not claimed identical. The comparison's normalized non-query command sequence is equal. Direct inspection confirms the same concrete actions: create and promote the tested chat, original phone send, first120 posts, actual push-wake, independent peer send, second120 posts, host stop/start and completion controls. There are no extra product mutation commands.

The old phone route uses legacy history. The fresh phone route uses actual canonical catch-up, reset, fresh zero and older pages. Query-checkpoint comparison is changed and requires this raw semantic review. Empty top-level query arrays in the comparison are not evidence of query equivalence. The raw fixture contains eight catch-up requests plus nine page requests.

The lifecycle comparison is also deliberately changed. The old test had three phone connection generations. The revised test has two. Source commit `a671976f427fffebd8aaf88e1a60aecd63adadf0` removes exactly two setup lines: an intermediate close followed by immediate reopen before reading an already certified saved baseline. That extra connection lived about13ms in the preceding failed native capture and raced its ordinary restoration query in replay. Removing the redundant cycle retains the actual offline cut, offline cache reopening, missed turn, host restart and final replacement connection. It does not tolerate or match an extra query, borrow a later connection's reply, or change request payloads.

Raw lifecycle ordering preserves the meaningful boundaries. The fresh initial phone is connect/open/close/inbound-end. The replacement phone is connect/open/close/inbound-end. The initial peer remains connect/open/down/inbound-end/close after the real host restart. The replacement peer remains connect/open/close/inbound-end. Timing and inter-client ordering are not claimed byte-identical. Phone capability lists now additionally advertise the required thread route.

## Scope decision and limits

PASS for the final offline case. No remaining raw-contract blocker was found. This report completes this reviewer's separate last-case review alongside the prior62-case report, three-case sweep03 report and one-case off-screen report. Those reports preserve their own exact source and receipt hashes. Parent maintains the complete67-case fixture-to-successful-replay manifest and final bundle byte checks. This report does not replace that manifest or claim final source gates have run after asset promotion.

No fixture or test source was edited by this reviewer. No asset was promoted. The prior failed sweep05 capture and replay remain separate evidence and are not reclassified as passed.
