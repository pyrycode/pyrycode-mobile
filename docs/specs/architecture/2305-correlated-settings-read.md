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
| Connection/repository/host replacement | `replacementCannotReturnRetiredReply` |
| Session replacement, including reused identity | `sessionReplacementAbandonsRead` |
| Correlated omitted applied field | `missingAppliedFieldIsReturned` |
| Legacy subscription and refused send | `legacySubscriptionCorrelatesItsRead`, `refusedSendFailsExplicitly` |
| Cancellation followed by another read, and pump termination | `cancelledReadCannotSupplyTheNextCallsReply`, `closedPumpFailsTheOutstandingRead` |

## Error handling
Refused sends, pump end, ownership replacement and missing current-session evidence fail explicitly with static messages. Deadline expiry remains bounded by the existing timeout. Missing `effective_effort` is returned unchanged for `appliedEffortFooter` to reject, never filtered or replaced with saved effort.

## Testing strategy
Write controlled coroutine regressions first against the old first-non-held behaviour, then repair the helper. Use the real repository with controlled pump delivery and request IDs; no sleeps, fixed emission counts or expected-value freshness filters. Shared tests run on Linux JVM and compile for native. Existing native tests stay device-only because they exercise the real relay, Keystore, daemon and Claude. Attempt the two ticket-named live methods through the supported focused launcher; retain raw XML/logs, counts and revision/configuration evidence. Main-based runs cannot certify an activated candidate. Full suites remain dispatcher-owned.

## Open Questions
None. Independent product/prerequisite failures remain failed or blocked evidence under their own trackers.

## Revisions
2026-10-19: Keep writes and restoration on the repository that supplied the fresh session, using `withFreshSettings`; acquiring another repository after a read could pair a retired session with a replacement connection. Authoritative admission is cross-checked synchronously in the inbound turn, after repository processing and before another frame can overwrite the reading. Legacy list rows deliberately contain placeholder session IDs, so legacy correlation uses the repository's cold request waiter and abandons a read on a matching `session_transition`, rather than deriving ownership from a list placeholder.
