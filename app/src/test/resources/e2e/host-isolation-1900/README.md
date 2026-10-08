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
