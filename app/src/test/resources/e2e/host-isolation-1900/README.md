# Host-isolation readiness control (#1900)

The original scenario waits on `awaitConnected`, whose `ConnectionStateSource` follows the latest saved host. The scenario actually needs A's Noise-Open repository. An unavailable selected B can therefore time out while A is already ready. The repair uses `hostRepository(serverIdA)` with the existing 30-second deadline.

These are sanitized counted results, not a dispatcher full-suite result. Raw XML and per-test logcat remain in the named `build/dispatcher-tests/live-*` folders and `/tmp/builder-1900/evidence/rejected-selection-{before,after}/`. `evidence.json` records source revisions, working-tree patch hashes and original XML hashes. The XML copies retain only counts, method identity, duration and the static failure diagnostic; no payloads, pairing material or logcat are committed.

To replay in an isolated test worktree at mobile `5dbb7c0d2a8b0d75cb78e3bbfe13f569a6983697`, apply `reproduce-before.patch`. It adds a test-only rejected B pairing, confirms A is Open, waits for B's terminal PairingRejected, then runs the unchanged scenario. Run:

```sh
python3 scripts/android-test-gate.py live --tests 'de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_collidingConversationId_phoneFileStaysOnItsHost'
```

Expected red: 1 executed, 1 failed, 0 errors/skips. The diagnostic says `wait for phone connection on A`, `a_ready=true`, `selected_is_a=false`, `selected_relay=PairingRejected`.

Apply `repair-control.patch` and run the identical command. Expected green: 1 executed/passed, 0 failures/errors/skips. The two tested drives differ only in the initial readiness call. Normal code pairing replaces the temporary B record before the scenario's pending-file, exact message/attachment count, byte digest, B row/tile/cache absence and B NotFound assertions. Both drives retain B removal in finally. The deliberately invalid test string is public and conveys no credential authority.

Neither control injection ships in the live method. A clean focused final-source run is recorded separately in the plan/PR. The original historical failure was anonymous; leaked closed fixture selection is corroborated by the historical source but remains an inferred historical trigger, not an observed historical phone state.

Fresh post-#2014 focused verification is retained in [post-2014-focused-live.xml](post-2014-focused-live.xml) and [post-2014-focused-live.json](post-2014-focused-live.json): `live-q6v35j6y` executed/passed the named method **1/1**, zero failures/errors/skips, exit **0**, at mobile `c445ef3adf85714d68fe44309e11e73e9d915d7e` and daemon `a536d17b1e182fb5398a5458e3afe6079b37a510` (Claude `2.1.280`). The manifest includes the exact tested daemon binary hash and its dirty-build metadata; the configured daemon checkout has no tracked changes, only untracked instruction files. The XML is the content-free device report with only CRLF converted to LF and a final newline added; both raw and retained hashes are recorded. Raw XML and per-test logcat are copied to `/tmp/builder-1900/evidence/post-2014-focused-live/`. This selected-method pass does not establish full dispatcher live acceptance.

The [post-blocker rename evidence](rename-rework/README.md) separately closes the outstanding JVM verification finding with a fresh exact-method pass (1) and full-class pass (5). Full deterministic dispatcher gates, a passing full live suite containing this host-isolation method, and the documentation handoff remain pending after builder verification.
