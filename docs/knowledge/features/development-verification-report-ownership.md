# Development verification — scenario report ownership

Part of [test scheduling and harnesses](development-verification-test-scheduling.md).
The stream and background-Agent harnesses share the execution boundary below.

## Stream report execution ownership (#2455)

`scripts/e2e-stream-scenarios.py` clears pre-existing `TEST-*.xml` within the
selected device report directory immediately before each scenario subprocess,
then collects all matching XML without comparing mtimes. Existing host/device
admission supplies exclusive execution ownership; the original
`PYRY_TEST_ADMISSION_FD` is still forwarded. Do not widen cleanup to other device
paths or replace it with a timestamp tolerance: even future-dated old XML cannot
satisfy a new execution, and a genuinely new report can predate the launch clock.

Each execution must produce exactly one testcase with the expected class and
method, without failure, error or skip. Missing, zero-count and wrong-identity
reports remain unsuccessful, as does a nonzero child status despite passing XML.
Copy collected XML to temporary retention and remove its original before the
next launch can reuse `TEST-run.xml`; per-scenario archives live under
`build/dispatcher-tests/stream-methods/run-*/`. The `finally` restoration returns
already collected reports to the selected report directory on normal completion
or interruption. This preserves the first outcome when the second launch writes
nothing or interrupts; it does not claim to collect unfinished child output.

`StreamScenarioReportsTest.run_cases` controls the launch clock at
2,000,000,000 ns and the first report mtime at 1,000,000,000 ns. With the second
mtime at 3,000,000,000 ns, the old filter omits the first XML without copying or
removing it; the next writer overwrites that filename. The deterministic
`test_earlier_report_mtime_keeps_both_independent_outcomes` reproduces `2 != 1`,
including passing-first/skipped-second. Both-older reports, future-dated stale XML,
missing second output, interruption, invalid outcomes and admission forwarding
are also covered, with exact archive/restored bytes checked. This proves a causal
loss mechanism, not the originating Linux host's actual clock behavior.

[PR #2457's accepted verifier evidence](https://github.com/pyrycode/pyrycode-mobile/pull/2457#issuecomment-6335301526)
retains these distinct results. Builder runs used macOS Darwin 25.6.0 arm64 and
`python3 -m unittest -v scripts.test_e2e_stream_scenarios.StreamScenarioReportsTest`:

| Run | Revision | Executed | Passed methods | Failure records | Errors | Skipped |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Controlled red, unchanged production | `3869d7289281bf848820fc7cd848f5a6b98c1b48` | 8 | 5 | 6 across 3 methods | 0 | 0 |
| Focused green | `1a4eeeb9f1db272a48860e8837f01b0b4802b2ce` | 8 | 8 | 0 | 0 | 0 |

The raw logs were `/tmp/builder-2455/checks/controlled-red.log` and
`controlled-green.log`, SHA-256 respectively
`3982bb5cf87d23e3891e89eb137e09f1f5b70f53cf3978018ff60ccd085f5db2` and
`97d0bdead2b1004a8d0d325ac3f6b33a8bab13bef27f80b1c7ab747703616292`.
These are retained-file locations at review time, not promises of permanent storage.

At the green revision, completed dispatcher macOS gates supersede the PR body's
pending-gates statement: scripts discovery executed/passed 214, JVM with build
checks executed/passed 5,259, and `scripted-all` executed/passed 24; each had zero
failures, errors or skips. The scripts raw report is
`verifier-gate_#2455_3.stderr.log` in verifier evidence run
`e1b2d3f14106d6804577dc06bee73da184d85deb75c28a72c03d438b2f896c5c`.
The verifier confirmed both independent deterministic stream methods in the full
scripted run. Pre-verify and docs guard passed. The configured UI command skipped
this scripts-only branch and executed no UI tests; no UI pass is claimed.

The original Linux scripts gate at `544fca1f7721f5ddf2a4092efd738251fb017958`
remains failed: 211 executed, one failure, zero errors/skips, in
`test_a_skipped_twin_cannot_count_as_success`. The identical focused failure on
merged main `206eb3927d29819b7b2ff11cad760c6e5dcaa192` occurred on execution 50
after 49 passes; those passes do not replace it. Original evidence is ticket
\#2373 / PR #2454, run
`36c833f91ea567fc3a3247bef09ab2e54ad5deceb4393bbf2bf4be0c9dde1fec`, bundle digest
`3b3d6332da7e1f8389f0bc44ec127dfa8d603fca3cd6e7deb1fd8e35d90bda9f`,
`logs/verifier-gate__2373_3.stderr.log`. The restricted-sandbox full baseline is
non-authoritative. Linux is outside this macOS ticket's verification scope;
PR #2454's Agent acceptance remains independent.

## Background-Agent report execution ownership (#2481)

`scripts/e2e-background-agent-scenarios.py` uses the same selected-device-directory
boundary as the stream harness: immediately before each synchronous scenario
subprocess, remove existing `TEST-*.xml`, then collect every matching XML without
an mtime comparison. Exclusive admission and the original `PYRY_TEST_ADMISSION_FD`
remain intact; cleanup must not reach other device directories. Future-dated old
XML cannot satisfy a launch, and missing second output cannot reuse the first.

Each execution requires exactly one testcase with its expected class/method and
no failure, error or skip. Zero-count, missing and wrong-identity results fail;
passing XML cannot override a nonzero child exit. The first nonzero status is
preserved. Copy original XML bytes into temporary retention and remove the source
before the next execution reuses its filename. `finally` restores already
collected reports on completion or interruption, including a first report whose
mtime predates launch. Enumeration order must not change identities or outcomes.

`AgentScenarioReportsTest.run_cases` freezes the old launch clock at
2,000,000,000 ns and sets the first report mtime to 1,000,000,000 ns. The old filter
omits that XML without removing it, so the next execution overwrites `TEST-run.xml`.
`test_earlier_report_mtime_keeps_both_independent_outcomes` covers passing/passing
and passing/skipped in both enumeration orders, with the second mtime either
3,000,000,000 or 1,000,000,000 ns. All eight controlled red subtests lose a report
(`2 != 1`). Exact original bytes, distinct identities and original outcomes are
checked after restoration. The fixture also covers stale future-dated XML,
interruption, missing output, invalid outcomes/identities and admission forwarding.
No sleeps, retries or freshness window establish this proof. It demonstrates the
loss mechanism; the historical host's precise clock behavior remains unknown.

### Counted Linux evidence

The [accepted verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2482)
reviewed `d02e97b498ac34ee86b74d1382a498d4c6258f8d`. Red used unchanged production
at plan revision `a673e31da8cc634a089871fe4fb1b1fa5b85efae` plus the new fixture;
its harness bytes match merged main `e6397e0159d7d5e011146882107fe29ddef990ac`.
Green harness and fixture hashes match the reviewed candidate. Builder evidence
records Linux x86_64, kernel `7.0.0-38-generic`. The ticket-OS policy supersedes
the issue's older macOS wording; macOS is outside this ticket's verification scope.

Recorded SHA-256 source identities are
`19489f5c3f7c1c855f1db10bf85d9658837502f6c6edeb6449672c8be21648e2`
(unchanged red harness),
`51682d93bd2e40540ec6eb4b41a3d1023e74e945baff751ad51fcfb0741d37e4`
(green harness) and
`a4d91a1bae0cbad85994b59063ae764309ec01562173f090a7a8ac80f3fbfb4e`
(fixture in both runs).

| Run | Exit | Executed | Passed | Failed methods | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Controlled red, exact regression method | 1 | 1 | 0 | 1 (8 failing subtests) | 0 | 0 |
| Focused green, entire `AgentScenarioReportsTest` | 0 | 9 | 9 | 0 | 0 | 0 |
| Complete scripts discovery | 0 | 218 | 218 | 0 | 0 | 0 |
| JVM gate with build checks | 0 | 5327 | 5327 | 0 | 0 | 0 |
| Complete `scripted-all` | 0 | 24 | 24 | 0 | 0 | 0 |

Focused commands used `python3 -m unittest -v` with
`scripts.test_e2e_background_agent_scenarios.AgentScenarioReportsTest`, adding
`.test_earlier_report_mtime_keeps_both_independent_outcomes` for red. Complete gates
were `python3 -m unittest discover -s scripts`,
`python3 scripts/jvm-test-gate.py --with-build-checks` and
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py scripted-all`.
The latter's full-suite XML explicitly passes both
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_backgroundAgentMovesAndSettles`
and `interactiveTurn_seededChannel_earlierSessionAgent_survivesRestartAndSettlementReconnect`;
these are full-suite results, not separate focused device runs, and use zero Claude
turns. The JVM invocation freshly executed its tests and included lint/Spotless,
debug assembly and Android-test compilation. Pre-verify and docs guard passed.
The configured `ui` command exited 0 with an intentional scripts-only skip:
zero device-only UI tests executed, and no UI-test pass is claimed.

Raw builder evidence was `/tmp/builder-2481/checks/controlled-red.log`,
`controlled-green.log` and `source-identities.json`; red/green log SHA-256 values
are `b76fed7f27a72be9b4465865835f3b33f22bd71d420662c7befcf9d9f058a017` and
`fcb666fa01834a4ff00c7585a3cb34ad357c74e06869e5db1b7515b55d2d3ede`.
Accepted verifier evidence run
`510d4d72da3a152d2c747c5774ae54ad27a16ce08be50acd6bc2a627ffbd3053`
retains gate reports; raw counted logs were
`$AGENTS_REPO_PATH/logs/verifier-gate_#2481_3.stderr.log` (scripts),
`verifier-gate_#2481_4.log` (JVM XML) and `verifier-gate_#2481_6.log` (scripted XML).
These paths identify retention locations at review time, not permanent storage.

Historical #2388 / PR #2480 failures remain failed: the original Linux scripts
gate executed 214, passed 213, failed 1, with zero errors/skips. On unchanged main
`e6397e0159d7d5e011146882107fe29ddef990ac`, the exact-method comparison failed on
invocation 39 after 38 passes: 1 executed, 1 failed, zero errors/skips, exit 1.
Original focused output was `/tmp/verifier-2480/baseline-focused.log` on build-hp-1.
Retained #2388 evidence run
`7215cda990cc7ede6c6f5a9f5271d074841f720ffc6ad4ff4d26377fdbe312bb`, bundle digest
`7743f3936f1b013878ecb8cf2e99a2df9e9030bfe4ee48b63f32887096563a14`, includes
`logs/verifier-gate__2388_3.stderr.log` and the focused comparison in its handoff
and agent source log. Later incidental passes do not clear those failures.
\#2388 retains its independent tool-body consumer repair and fresh complete
feature verification; this harness repair does not satisfy that work.
