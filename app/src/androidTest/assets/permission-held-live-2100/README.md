# Permission-held running-tool evidence (#2100)

Historical recurrence: #2089 reported a bare 30000 ms coroutine timeout on Mobile
`bda8cc31e3decbe19975bd37df09cd1f1e7ffd93`, production merge base `b44067bbd991`,
daemon `8091a4730d42f43029eb0b955442dc300106030f`, Claude 2.1.280. Its failing phone
output is missing. The earlier `/tmp/builder-2089/prefix-stalled/test-results.log`
records this method starting/completing with instrumentation status 1/0. Retained
daemon logs do not identify the timed-out operation. Historical cause, including
any MCP relationship or final-status assertion failure, remains unresolved.

`readiness-red.xml` records five failing real-registry controls against the actual
extracted selected-banner predicate in `d8d2fe6e3`. `readiness-green.xml` records
all five passing after addressed-host relay plus daemon authentication. Controls
cover stopped selected host, connected unrelated peer, held daemon handshake,
Idle through 29999/30000 ms and missing target; selection is preserved. The
repair neither retries nor increases the deadline. Diagnostic controls also ran
red then green. Complete affected registry/status classes are retained separately.

`baseline-live.xml` records the fresh diagnostic baseline: the named method
executed/passed, zero failures/errors/skips. All fifteen stages completed; its
original readiness predicate happened to see the authenticated target selected.
`baseline-stages.txt` and `baseline-daemon-events.txt` correlate phone readiness,
prior/current peer admission, chat creation, permission holding, remote approval,
running display, turn completion and label removal. Phone and daemon clocks have
an offset (about 0.7 seconds); timestamps are not presented as one clock. The
baseline is successful localization coverage, not a reproduced historical outage.
`baseline-provenance.json` records source/APK hashes, reported and committed Mobile
revisions, daemon revision/dirty flag/hash, Claude version and raw artifact paths.

XML normalization preserves counted cases, outcomes, timestamps and per-case times
when available, omitting properties, stack bodies and log streams. Stage records
and daemon extracts omit identifiers/payloads. Raw phone/daemon snapshots remain
outside the disposable worktree under `/tmp/builder-2100/`.

`focused-live.xml` records the repaired method at Mobile `9ce53a429`: one
executed/passed, zero failures/errors/skips, exit 0. All fifteen stages completed;
its stage/daemon extracts and exact revision/APK/binary provenance are retained.
The real-registry controls provide before/after causal proof; this live pass
confirms the original permission and display contract remains intact.

The existing deterministic tool twin also executed/passed one test, zero
failures/errors/skips, exit 0, zero real Claude turns (`scripted-tool.xml`). Its
raw XML/logcat are retained at `/tmp/builder-2100/scripted-tool/`.

Full live acceptance is pending with the dispatcher. A builder return must commit
`dispatcher-full.xml`, `dispatcher-method.xml` and `dispatcher-provenance.json`
here, confirming the fresh full gate and named method pass, executed/failed/skipped
counts, tested Mobile/daemon revisions and XML/logcat/daemon artifact paths.
