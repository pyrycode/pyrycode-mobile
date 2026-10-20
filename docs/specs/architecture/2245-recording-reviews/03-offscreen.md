# Mobile2245 off-screen reply recording contract review

Read-only review on 2026-10-20. Decision: PASS for this case only. No fixture promotion or mutation performed. The genuine capture and fresh replay both executed the named test successfully. This report does not declare the separate offline-read replay passed.

## Evidence and source

Remote audit root on HP, container pyry-fleet-native-android:
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2245/operator-manual-20261020/recording-refresh-05-offscreen/batch-01`

Case: `interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk`.

| Artifact | SHA256 |
| --- | --- |
| Genuine fixture `.json` | `02f08011950d5be8069b3069631fea03108fea19fbda806ddd4a4f8e987a7a7a` |
| Capture receipt `.capture.json` | `6e084ec993b21bea24e42a50e8206e534e3c7ecc44e3e7596e50260046242ba8` |
| Capture and replay receipt `.evidence.json` | `a21718aa64e24fe34e9f18da8ebfec3ce883efb2802a06972bb30b6b33f4312c` |
| Comparison `.comparison-private.json` | `e9039fd7f38ba3cc9a4fbd13aafacddb7c2d48228cb6033f0a6eb259c5856f74` |
| Original shipped fixture in `app/src/androidTest/assets/recorded/suite/` | `030905875773b8961d1962400fcbe60cd07f5c3cdf48715c17af9b793ec59108` |

Captured mobile source: `806ed17830fb9068f858d73a94ee14909dfae570`.
Daemon source: `e596971abb101d44d770dea33b4d29d61300e85e`.
Daemon binary: `646cb732b5cc303d3fc7ea3fe83d5dfc07a567f512fc1c23f003f2a5b4581ad5`.
App APK: `4e11052b72552c25364f4dcd84abd4721ad38ea1318397a3efc14ff5129adc6a`.
Test APK: `32065535e89b1171efcd03911520a0a6bab3f0db48810896e0228babd1ef85ec`.
Claude: `2.1.280`; Android API33. Capture time: `2026-10-20T09:53:36Z`.
Capture receipt: one executed, zero failed or skipped. Fresh replay receipt: one executed, zero failed or skipped, same exact fixture and APK hashes.

Original source metadata names mobile `788314223b701ea5c6a0b530ac255451de97e36e` and daemon `c2e7bec7a434014dfc2089f64d507898c79f8e89`.

## Explicit raw contract review

The genuine recording contains261 events. Both actual phone opens advertise `thread` alongside interactive, multi-agent and stop-background-task capabilities. Both peer opens retain the permitted legacy route. The phone sends zero legacy history or backfill requests. The phone receives canonical replacement and delta facts. The peer remains the independent actual producer witness.

The phone creates two chats and sends the two original prompts in chat A. The second turn is held by a genuine offered permission modal. The peer answers that offered modal at raw index156. The held second reply does not exist before release. Its peer assistant delta is165 and successful terminal167. Phone canonical assistant fact25 arrives170, becomes settled171, and exact root terminal fact26 arrives172. The canonical family text equals the complete independent peer text. Assistant and terminal share a nonblank session and the same exact turn. Both are root facts. The assistant is inactive and done. No response substitution is used.

The phone has moved to chat B before permission release. B's actual initial query is154 and terminal155. A's second completion is then certified on the original phone connection: request28 at173 starts from checkpoint22, actual reply180 and terminal181 certify checkpoint28. A later actual request30 at183 starts at28 and receives an empty same-boundary terminal184. The original phone closes185 and inbound ends186. The replacement phone connects187 and opens188. These are real distinct connection owners.

The recording contains thirteen phone catch-up requests and zero page requests. All are exact same-owner correlated sequences. One query on the original connection at144 starts from A's saved checkpoint14 and receives an actual correlated reset147 at version22. The next actual request148 starts at zero in the reset's epoch. Its five-fact window150 and terminal151 certify22. The reset itself grants no query certificate.

After reconnect, B's query212 receives terminal237. The original already-existing chat query238 receives terminal239. A's request240 starts from its actual checkpoint28 and receives same-boundary terminal241. Reopening A causes the additional actual request247 from28 and terminal248. The captured production source can already retain A's certified connection facts. An empty same-boundary response is therefore valid. The test proves the actual reopened destination subscription and retained exact reply. It does not require a fabricated empty source or forced resend of already held facts.

The initial phone read mark at115 is11 after the actual certified14 boundary at109 and114. The replacement read mark249 is25 after certified28 at241 and248. Neither read advances beyond the phone's actual certificate. The test's typed completion match, exact producer turn, source ownership, canonical shown revision, immutable baseline cache predicate, missed raw assistant absence, exact retained family and rendered uniqueness/order assertions remain active. The native run and fresh replay both passed those assertions.

## Comparison with shipped recording

The comparison receipt reports all twelve normalized non-query command entries equal. Direct raw structural inspection supports the same actions and ownership sequence: two phone sends, one peer permission answer, the same two chats, and the same reconnect purpose. There is no additional non-query product command.

The full fixture is changed. Strict query-checkpoint comparison is changed. The old phone route used legacy history requests. The new route uses real canonical catch-up, reset, zero requery and terminal certification. Settings reads also move to their actual owned session timing. Additional exact canonical synchronization requests belong to initial opening, the completed first turn, held second turn, off-screen completion, and reconnect/reopening. These are reviewed behavior changes, not acceptance through normalization.

Logical per-client connect/close sequences are identical. Raw lifecycle inspection additionally confirms the same connect/open/close/inbound-end order on both phone generations and both peer generations. Full peer lifecycle details match. Full phone lifecycle details differ because the new phone advertises the required thread capability. Timing values and unrelated inter-client scheduling differ. They are not claimed byte-identical. The push-token external event remains a real Android input.

## Scope and limits

This is one independently reviewed genuine case. No source, fixture, transport identity or receipt was altered. Successful execution comes from the preserved receipts, not a test run by this reviewer. No paging claim is made for this case. The separate offline-read case supplies paging coverage and requires its own successful replay and contract review.
