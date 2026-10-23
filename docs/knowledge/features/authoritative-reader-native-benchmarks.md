# Authoritative reader native benchmarks

The [saved-history paging contract](authoritative-reader-paging.md) owns normal DB
opening, local demand and source ownership. These receipts keep DB validation and
full-file compatibility measurements distinct.

## Approved-scope native validation

Source324df55be6a3978a5a166aac3bcfec3af1cd0acf passed forced formatting and native compilation/SpotlessCheck, then ui-maqwbx4h selected/executed6/pass6/fail0/error0/skip0. DB first-draw frames247/244/243/207ms retained200payloadresults/decodes,5queries,oneevidence row and nofullreads. The actualDB3000ms slow-control frames3868/3403/3343/3320ms all rejected the same2000ms bound while retaining identical counts. Paging/retry/reopen and descriptor cleanup passed. Both complete file compatibility methods logged required=false/followup2456, each8ordinary/fragmented offline/held-newest first/reopen arms, exact36000facts/18000islands/18000visiblemessages and fullreads offline1/online2/retiredAPIs0. File fragmented frames2166–2646ms remain measurements for2456, never normal DB latency or actual one-time import timing. Raw XML/phone/APKs/source/plan/image/runtime/receipt:2452/manual-20261022/db-file-separated-324d, manifestffce81fc6fb25b0fb76309e20130c9b9c8c29168ed1deea44ef14b935c33ad4c. The selected native runtime uses production destination/repository/ViewModel with controlled transport, no external daemon. Independent #2452 acceptance remains in the [paging topic](authoritative-reader-paging.md); parent acceptance and reviewed merges remain separate.


### Independent opt-in file benchmark

On the same324df55be6 source, normal admitted native gate ui-562olv0x with requireFullFileLatencyBound=true selected/executed1/pass0/fail1/errors0/skips0. Phone records confirmed required=true/followup2456 after all8exact compatibility arms completed. Fragmented file frames2501/2246/2825/2687ms; the actual failure was Probe.assertBound at2501ms versus2000ms. This is retained as a separate2456 latency failure, not DB acceptance or real import latency. Complete XML/phone/APKs/source/image/runtime are under logs/evidence/2456/manual-20261022/optin-324d, manifestb54f7a09902a6c63af9dbbe2c341d0e5f67a561aec3962c992391b160827d949. Together with default required=false in both six-suite file methods, this verifies effective scope selection rather than silently disabling the benchmark. No production change followed the failure.

### Current-source file compatibility measurement — 2026-10-23

Source `45b86ff05dd71c8904a006c8b8c8a7c6489616c7` selected and executed both
`SavedThreadFirstDrawDeviceTest` file compatibility methods on Linux x86_64:
**2 passed, 0 failed, 0 errors, 0 skipped**. Each method completed eight ordinary/fragmented,
offline/held-newest first-open/reopen cases. The native runtime was the API 33 Google ATD
x86_64 image, revision 1, with ANGLE/Vulkan SwiftShader and its unchanged 2000 ms bound.
The table lists committed-frame milliseconds in offline first/reopen, then held-newest
first/reopen order. It is a compatibility measurement, not a DB opening or real import result.

| Method suffix | Ordinary 20-fact control | Fragmented 36000-fact fixture |
| --- | --- | --- |
| `exposesNoRetiredReadApis_firstOpenAndReopen` | 946 / 342 / 407 / 298 | 3940 / 3202 / 2867 / 3308 |
| `preservesExactFacts_offlineAndHeldNewest_firstOpenAndReopen` | 293 / 214 / 253 / 255 | 3201 / 2905 / 2287 / 2805 |

Both method names start with `authoritativeFileCompatibility_`. All large cases retained
36000 exact raw facts, 18000 order islands and 18000 resolved visible messages, including
revision and wrong-content rejection checks. Full reads were one offline and two with held
newest; retired APIs were absent. Both methods logged `required=false followup=2456 openings=8`.
Passing correctness therefore does not establish that the file cases meet the timing target:
all eight large-case measurements exceeded 2000 ms. The fixture uses the production file
cache, destination, repository and ViewModel with controlled transport and zero Claude turns.

Run the existing methods through the normal admitted native gate:

```bash
benchmark_class=de.pyryco.mobile.ui.conversations.thread.SavedThreadFirstDrawDeviceTest
python3 scripts/android-test-gate.py ui --tests "$benchmark_class#authoritativeFileCompatibility_preservesExactFacts_offlineAndHeldNewest_firstOpenAndReopen,$benchmark_class#authoritativeFileCompatibility_exposesNoRetiredReadApis_firstOpenAndReopen"
```

On the same source and Linux runtime, the separately selected opt-in run
`optin/ui-26l2julw` executed 1 method: **0 passed, 1 failed, 0 errors, 0 skipped**.
All eight correctness cases completed and logged `required=true followup=2456 openings=8`.
The large-case frames were 2290 / 2010 / 2112 / 2186 ms; `Probe.assertBound` then failed
at 2290 ms against 2000 ms. This is a current measured timing miss, not a passing timing
check or a production repair. The supported invocation, verified by that phone record, is:

```bash
env 'ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.requireFullFileLatencyBound=true' \
  python3 scripts/android-test-gate.py ui --tests "$benchmark_class#authoritativeFileCompatibility_preservesExactFacts_offlineAndHeldNewest_firstOpenAndReopen"
```

An earlier macOS ARM64 run of the same source selected/executed 2 methods with 0 passes,
2 failures, 0 errors and 0 skips. Both hit the unchanged 30-second CPU-readiness deadline
before the ordinary first opening. No compatibility frame was measured there. Its retained
phone samples establish the readiness failure; they do not establish a file-read or rendering
defect. Raw XML, phone logs, source/APK/image/runtime identity and SHA-256 manifests are retained
under `logs/evidence/2456/manual-20261023/` on the Linux test host and
`logs/evidence/2456/manual-20261023/mac/` on the Mac. The Linux successful run is
`native/ui-3f992hvw`. Historical failures above retain their original outcomes. These hosts
and source revisions do not form a controlled before/after optimisation comparison.
