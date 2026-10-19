# Correlated native settings reads (#2305)

## Files read
- `InteractiveStreamE2ETest.kt`: `freshSettings`, preparation, mutation, restoration and applied-effort assertions share the same wait.
- `E2eTestApplication.kt`: live graph construction and reconstruction own the pump injection.
- `RecordedSuiteNative.kt` and `SuiteConnections.kt`: preserve recording/replay wrapping and the single inbound consumer.
- `SessionSettingsCommands.kt`: legacy subscription issues a read; refresh invalidates that subscription.
- `RemoteConversationRepository.kt`: authoritative subscription observes state; refresh sends a read, and admission precedes projection.
- `RelayConnectionFactory.kt`, `RelayRepositoryCoordinator.kt`, `HostReadings.kt`, `SessionLiveAdmission.kt`: current repository and reconciled session bindings establish ownership.
- `docs/knowledge/features/remote-conversation-repository-session-settings-read.md`: saved effort must never replace applied effort.
- `docs/knowledge/features/remote-conversation-repository-status-projections.md`: supplied frames must pass repository admission.
- `../pyrycode/docs/protocol-mobile.md`: settings request/reply correlation is the wire contract.

## Context
One behaviour: each native helper returns its own current-owner settings reply. The observation seam and regressions belong with that repair. No production API, admission rule, recording or caller-signature migration changes. Forecast: about 650 written lines, three existing integration files plus shared helper/tests and this plan; at most five new test-source types, four criteria, no production consumer migration. The nearest analogue is #2075 (`398909331`).
Shared-file overlaps with #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1947, #2009, #2032, #2052, #2057, #2058, #2088, #2089, #2090, #2091, #2092, #2095, #2097, #2098, #2099, #2100, #2102, #2104, #2108, #2112, #2114, #2117, #2118, #2119, #2121, #2138, #2139, #2140, #2175 are local to other methods. No dependency on activation #2245 or daemon model-menu #3609.

## Design
Add a shared-test settings-read probe wrapping `ManagedSessionPump`. Forward the delegate's single inbound stream and its send result unchanged. A coroutine-context token identifies only reads caused by this helper's subscription (legacy) or explicit refresh (authoritative); record the outgoing ID before calling send so immediate replies cannot race registration. Retain matching replies after forwarding them to the repository. No logs or second inbound collector are used.
Subscribe to repository settings before triggering the authoritative refresh. Return the correlated decoded reading only when the repository also exposes it as an admitted, non-held value. Equality is an admission cross-check, never evidence of freshness. Capture host bundle, repository and reconciled session provenance; check them at send, inbound delivery and completion. Fail explicitly on replacement rather than retrying a mutation or extending the deadline. Reject cleared, conflicting and unrelated reply identities. Keep all existing applied-field and response-wording assertions.

## State and concurrency model
The helper owns its collection jobs within `coroutineScope` and the existing thread timeout. A thread-local token propagated as a coroutine context element follows legacy asynchronous reads. Each wrapped pump holds only active request receipts; helper exit removes them. Pump close or inbound completion fails outstanding receipts. Rebuilding the graph creates fresh wrappers. No new production state or background scope.

## State transitions and identity reuse
| Event | Controlled regression |
| --- | --- |
| Earlier minimal/held/cleared/unrelated settings | `earlierSnapshotsCannotCompleteCurrentRead` |
| Reply during send | `immediateReplyIsRetained` |
| Fresh reply equals current value | `equalValuedReplyCompletes` |
| Connection/repository/host replacement | `replacementCannotReturnRetiredReply`, `legacyRereadAfterOwnerReplacementFailsExplicitly` |
| Session replacement, including reused identity | `sessionReplacementAbandonsRead` |
| Correlated omitted applied field | `missingAppliedFieldIsReturned` |
| Legacy subscription and refused send | `legacySubscriptionCorrelatesItsRead`, `refusedSendFailsExplicitly` |
| Cancellation followed by another read, and pump termination | `cancelledReadCannotSupplyTheNextCallsReply`, `closedPumpFailsTheOutstandingRead` |
| Legacy envelope names another conversation/session or explicitly clears | `legacyWrongConversationCannotCompleteTheRead`, `legacyConflictingSessionCannotCompleteTheRead`, `legacyExplicitNoSessionCannotDescribeANamedSession`, `legacyExplicitClearCannotCompleteTheRead` |
| Same-owner revision invalidates an outstanding or just-delivered read | `legacySameOwnerRefreshReplacesThePendingReceipt`, `legacyRefreshAfterDeliveryDoesNotFailTheHelper` |

## Error handling
Refused sends, pump end, ownership replacement and missing current-session evidence fail explicitly with static messages. Deadline expiry remains bounded by the existing timeout. Missing `effective_effort` is returned unchanged for `appliedEffortFooter` to reject, never filtered or replaced with saved effort.

## Testing strategy
Write controlled coroutine regressions first against the old first-non-held behaviour, then repair the helper. Use the real repository with controlled pump delivery and request IDs; no sleeps, fixed emission counts or expected-value freshness filters. Shared tests run on Linux JVM and compile for native. Existing native tests stay device-only because they exercise the real relay, Keystore, daemon and Claude. Attempt the two ticket-named live methods through the supported focused launcher; retain raw XML/logs, counts and revision/configuration evidence. Main-based runs cannot certify an activated candidate. Full suites remain dispatcher-owned.

## Open Questions
None. Independent product/prerequisite failures remain failed or blocked evidence under their own trackers.

## Revisions
2026-10-19: Keep writes and restoration on the repository that supplied the fresh session, using `withFreshSettings`; acquiring another repository after a read could pair a retired session with a replacement connection. Authoritative admission is cross-checked synchronously in the inbound turn, after repository processing and before another frame can overwrite the reading. Legacy list rows deliberately contain placeholder session IDs, so legacy correlation uses the repository's cold request waiter and abandons a read on a matching `session_transition`, rather than deriving ownership from a list placeholder.

2026-10-19, verifier rework: Findings 1 and 2 repair the same current-owner read invariant. Legacy metadata may be omitted, but present envelope conversation identity must match the request, present session provenance must agree with the decoded settings session, and an explicit clear cannot complete a read. A correlated conflicting legacy reply fails explicitly because the production waiter has already consumed it; production admission is unchanged. Replace the single request latch with per-request receipts: a revision-driven read on the same pump retires the previous receipt and restarts the bounded wait, including its repository-observation cross-check. A changed pump, repository/host ownership or session transition still abandons the helper. Preserve immediate replies and equal-valued replies. Regressions plus the named recorded operator-bypass method prove the repair; rerun the two ticket-required live methods only. No recordings or production files change. Added work remains below 1600 total written lines, with no new exported type or caller migration. No in-flight branch overlaps the two helper files.

## Documentation handoff
- Pending for documentation stage: `docs/e2e-interactive-stream.md`, settings helper/live verification guidance: document request correlation, route-specific subscription/refresh, ownership rejection and same-owner reread lifecycle; retain exact Linux counts/identities and the distinction from #2245 activation.
- Pending for documentation stage: `docs/knowledge/features/remote-conversation-repository-session-settings-read.md`, settings-read section: describe the native test helper contract without changing production admission or substituting saved effort for applied effort. Preserve the six unexecuted live callers recorded in PR #2310.

## Validation evidence
2026-10-19, Linux verifier rework: the preserved helper at `dc6ac402c9ff1cab86df570d81840ae2a860485d` with the seven new regressions executed 20 tests: 13 passed, 7 failed, zero errors/skips. Four failures demonstrate acceptance of conflicting/cleared legacy replies; three demonstrate the superseded-request lifecycle failure. Raw XML and exact helper/test sources with hashes are retained at `/work/Projects/pyrycode-mobile-agents/logs/evidence/2305/20261019-rework-linux/controlled-red/`. The preceding test-setup compilation failure executed zero tests and is not a pass.

The repaired helper's focused `SettingsReadProbeTest` run executed 20 tests, all passed with zero failures/errors/skips, including a repeat after keeping ownership/termination observation continuously registered during receipt replacement. `spotlessApply`, lint (zero errors), debug APK assembly and native Kotlin compilation passed. Final raw controlled XML and lint XML with hashes are retained in the sibling `controlled-green/` directory.

Rework native validation on pushed Mobile `2ddff36966891396894f5acb67d03d29830c9051`, Linux x86_64:
- Focused `android-test-gate.py ui --tests` for `RecordedMobileSuiteTest#interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`: exit 0; 1 executed/passed, zero failures/errors/skips, 25.011 seconds. Android 13/API 33 managed `pixel2Api33Atd`; raw XML, phone log and device/phase metadata are retained in `ui-rkp0flou/` under the rework evidence root above.
- Focused `android-test-gate.py live --tests` for the two ticket-required methods: exit 0; 2 executed/passed, zero failures/errors/skips. Raw XML names inherited-effort (7.114 seconds) and reconnect-footer (43.563 seconds). `live-y13yaf8c/` retains XML, phone/emulator logs and phase timings; `harness/fault-0bee21c70818b42230866c21dfa824ec/` retains daemon logs, source inputs and its manifest.
- Daemon binary revision `0484cb018fbc4db77eac418ccf7234d23d6d31ad`, SHA256 `5cc81df3ce4e016133432aa76b023ed2ab662acee0c479ae9238fc362f1b6a16`, independently confirmed by Go build metadata with `vcs.modified=false`. The retention-time source checkout is separately recorded as `fe78d60a0ccfb6d7c809bc317403244f0c2118f7`; it is not the binary revision. Claude Code 2.1.280, connected Android 13/API 33 emulator, stream-json default runner with no main-daemon config file, `PYRY_MOBILE_V2=1`, isolated test daemons, managed OAuth route and `wss://pyrycode-relay.pyryco.de`. A local relay log is unavailable because TLS relay delivery is remote. These are main-based legacy-route passes, not an activated #2245 candidate. Full configured suites and documentation remain later-stage work; the six additional live callers remain unexecuted/pending as recorded in PR #2310. macOS remains outside this Linux ticket's verification scope.

2026-10-19, Linux x86_64, main-based Mobile `8f6b92586a412dd39b77c65098b44140c0cf2813`:
- `python3 scripts/gradle-gate.py testDebugUnitTest --tests 'de.pyryco.mobile.e2e.SettingsReadProbeTest' --console=plain`: exit 0; 13 executed, 13 passed, zero failures/errors/skips. The preserved old-helper run executed two regressions and failed both on accepting an earlier snapshot.
- `PYRY_GATE_EVIDENCE_DIR=/work/Projects/pyrycode-mobile-agents/logs/evidence/2305/20261019-resume-linux timeout 1800 python3 scripts/android-test-gate.py live --tests 'de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn,de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive'`: exit 0; two executed, two passed, zero failures/errors/skips. Raw XML names both methods; inherited-effort took 7.303 seconds and reconnect-footer 44.302 seconds.
- Daemon binary/source `a2cab7d3b2c8eb81682df9288d4d05a021c8f400`, binary SHA256 `9f850d56966a607ba558abbc77737ee2931d27aacc6a27cbacc731e77b735c1b`; Claude Code 2.1.280. Android 13/API 33, connected emulator, default stream-json runner with no main-daemon config file, `PYRY_MOBILE_V2=1`, isolated test daemons over `wss://pyrycode-relay.pyryco.de`, managed OAuth credential route. This is the main-based legacy route, not an activated #2245 candidate.
- Private durable root: `/work/Projects/pyrycode-mobile-agents/logs/evidence/2305/20261019-resume-linux`. `controlled-green/` holds the 13-test XML; `live-3zdw2vzc/` holds raw native XML, per-method phone logs, emulator log and phase timings; `harness/fault-01c5960f1ee7a5391034c2ca860205d8/` holds daemon diagnostics, source inputs and its hash manifest. A local relay log is unavailable because the run used the remote TLS relay.
- Earlier red/green/setup-failure XML remains under `/work/Projects/pyrycode-mobile-agents/logs/evidence/2305/20261019-linux`; prior run evidence bundle `473d0875c420ba71fb3b950bbb3ed208d2d87f9dc0efe4e465d9ef5af3889429` has digest `8ab0df21d552817cb6cbbcf5510aaaaae6aa01e40e32617074252ed7c4add5c9`.

The historical activated-candidate failures and final recording gate remain with #2245; model-menu protocol work remains pyrycode/pyrycode#3609. These main-based passes do not certify that activation or replace its failed captures. Full configured suites remain dispatcher-owned. macOS is outside this Linux ticket's verification scope.
