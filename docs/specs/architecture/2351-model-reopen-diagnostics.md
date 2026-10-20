# Model reopen diagnostic evidence

## Files read
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: model scenario, `withFreshSettings`, `publishedMenu`, `awaitFooter`, and restoration.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RunConfigurationWait.kt` and `RunConfigurationWaitTest.kt`: the existing single-section semantics wait and settlement predicates.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SettingsReadProbe.kt` and `SettingsReadProbeTest.kt`: correlated receipt admission without another inbound collector.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: current phone owner and sticky tape access.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedSuiteTape.kt`: `progress` is a read-only snapshot; matching and completion remain strict.
- `app/src/main/java/de/pyryco/mobile/data/repository/HostReadings.kt`: directly readable existing model menu and session binding, without another subscription.
- `docs/recorded-tests.md`: Capture and compatibility, What replay proves, and passive assertion evidence prohibit diagnostic reads that consume captured requests.
- `docs/knowledge/features/remote-conversation-repository-model-and-slash-command-menus.md` and `remote-conversation-repository-session-settings-read.md`: subscriptions can send requests; correlated freshness cannot be replaced by equal held settings.
- Private #2343 `diagnosis.md` and recovery request/response: original model state is unknown; assessment `0b7e8767ce130aae021a51b0cfb5436c944b276ea01efd55d7b77fd07331a355` queued zero execution and grants no wider run.

## Context
The final Y reopen has failed after its exact saved-model check, but the original actual selected row was lost. This ticket ships one useful diagnostic behaviour and measured decision, independently of any eventual repair. Five prior baseline passes do not repair the original failure. The newer #2351 comment also records failures at branch `3513295cfb1e6608ef42b7c00878462c449b6638` and main `77b5717d6d3a1dba9f1a92a251162e7f9b3770e2`.

## Design
Copy the Model section's rows, selected flags, note, pending state and original predicate results from the same semantics traversal used by `awaitRunConfiguration`. Invoke an optional evidence callback once on completion or timeout, before sheet closure. The timeout retains its cause and existing outcome. Evidence failures cannot replace that timeout.

Expose an optional receipt callback on `SettingsReadProbe.read` after its existing correlation/current-owner checks. At final Y, retain the existing fresh read's request/reply identifiers, saved model/effort/session and owner identities. Retain X's existing reopen reading and the initial published menu used to choose Y's expected row. No new repository subscription, refresh, query, wait, or capture control is added.

At the final wait's outcome, emit private native logcat records containing those copies, current host/bundle/repository/transport identities and connection status, Y's existing session binding and held published menu, and `RecordedSuiteTape.progress` including its sticky first failure. Capture selected-host identity separately from the intended host. Record only controlled model/settings fields, never prompt/message text, credentials, or whole UI trees. Split long records into numbered chunks for logcat retention. Both pass and failure records are useful measured evidence; neither changes acceptance.

Edits to shared files stay local. Overlapping branches include #2311, #2347 and #2349; inspected changes affect reply witnesses, graph restart and permission cleanup, not this model block. Older overlapping feature branches affect other scenarios; no dependency is needed.

## State and concurrency model
The Compose wait owns immutable copies of its last inspected semantics. Receipt callbacks run in the existing read coroutine after owner validation; native emission reads existing state values synchronously. There is no observer job, added flow collection, global retained model state or new production API.

## State transitions and identity reuse
| Event | Contract and test |
| --- | --- |
| Wrong selection until original timeout | Controlled Model mismatch in `RunConfigurationWaitTest` retains actual rows and false confirmation, with the original timeout/cause. |
| Sheet closes and settings restore | The same controlled regression removes the sheet and changes its input after failure; retained evidence remains immutable. Native logs emit before `awaitFooter` closes. |
| Correct selection or independent confirmation pending | Existing settlement tests plus callback success coverage retain predicate order and callback count. |
| A fresh settings read completes | `SettingsReadProbeTest` checks callback request/reply correlation and unchanged sends/collections. |
| Connection/session replacement or cancelled read | Existing `SettingsReadProbeTest` abandonment tests remain mandatory; no receipt callback grants admission. |

## Error handling
Do not catch a mismatch as success, alter deadlines, close fences, strict payload matching, exact X/Y assertions, or restoration. Preserve the primary timeout if diagnostic emission fails, attaching that failure as suppressed. Sticky tape progress is read without draining or advancing it. Cleanup runs as before after evidence emission.

## Testing strategy
Write controlled shared-test regressions first and observe their failure before implementation. Run affected `RunConfigurationWaitTest`, `SettingsReadProbeTest` and `RecordedSuiteTapeTest`. Run the named recorded model method on this macOS host through the focused UI gate; the device is required for the existing native lifecycle, storage and actual replay scheduling. Retain exact source revision, command, exit/counts, raw XML, per-test logcat and hashes privately on the persistent agents volume. Commit a content-free measured decision under `app/src/androidTest/assets/model-reopen-2351/`.

Run lint, APK assembly, instrumented Kotlin compilation and required Spotless/final pre-verify checks. Complete UI coverage remains dispatcher-owned; Linux is outside scope. Consult the existing assessment rather than self-authorising its wider sequence.

## Open Questions
- Which actual menu/selection/owner state appears on the fresh native replay? Resolve with retained evidence. Non-reproduction must name the remaining hypothesis and next check, not claim a fix.
- Is there an existing open repair ticket? Search before filing; link the unresolved repair/investigation to root #2343 and this ticket.

## Repair handoff contract
If cause remains unresolved, publish a linked open repair ticket preserving feature root #2343. It must retain exact X/Y saved-model isolation across reopen, current-menu selection and confirmation, strict complete request/payload matching, a regression exposing the diagnosed cause, and counted execution/pass of `RecordedMobileSuiteTest#interactiveTurn_modelChange_roundTripsAndStaysPerConversation` on the repaired revision in focused macOS replay and the configured dispatcher UI gate before repair verifier PASS. No pending repair or acceptance is claimed here. Request-contract changes or recording replacement require genuine native capture, committed/fresh replay, strict comparison and explicit difference review under the runbook, with counts, revisions and raw artifacts; reviewed differences are not successful strict comparison.

## Scope measurement
One diagnostic deliverable, four acceptance criteria, approximately 550–750 written lines including this plan, tests and checked evidence summary; zero production declarations or simultaneous consumer migrations. No new reject branch in a production state machine. Below all numeric ceilings on both sketch and written plan.
