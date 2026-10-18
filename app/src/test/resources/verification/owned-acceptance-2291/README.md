# Owned coverage acceptance evidence (#2291)

Linux focused checks only. `results.json` records each source revision, exact command,
executed/failure/error/skip counts, test names and SHA-256 hashes. Sibling `.stdout.log`
files preserve managed admission/Gradle output; `.xml.gz` files retain complete JUnit XML.
Complete configured JVM-gate acceptance belongs to the dispatcher; its subsequent results
are recorded in the [evergreen testing topic](../../../../../../docs/knowledge/features/conversation-cache-testing.md#owned-coverage-snapshot-acceptance-2291).

The cold red run's console capture contains one tool-truncated compiler-warning chunk,
explicitly marked in `red.stdout.log`. Its subsequent test-result output and complete
JUnit XML are intact; failure counts and signatures come from that complete XML.

Before changing either probe, inspected both original failure bundles using
`fleet-task.py evidence`:

- #2278 verifier run `104b6f0f3206bdee9e88e1e88be578a848467f515f06bab36e296817de08afc2`,
  bundle digest `4f8854a65020f88434263e49e382533a994a55865f944dd7ad79f74752213ecc`:
  `logs/verifier-gate__2278_4.stderr.log` reports `AssertionError` for both assigned
  methods. `logs/2026-10-18T16-54-39-172Z_verifier-gate-rerun__2278.log` contains
  their rerun XML: 2 executed/passed, 0 failures/errors/skips.
- #2279 verifier run `ad5057f1cab950880786516b6b7fd72425c3bb2c9e47f3f210ca222d6d3004c6`,
  bundle digest `5a8b0569871092d8b64589ec3c5356236b2f1e46b0efae7e85c579777b485a74`:
  `logs/verifier-gate__2279_4.stderr.log` reports `AssertionError` for the revision
  method. `logs/2026-10-18T16-58-33-256Z_verifier-gate-rerun__2279.log` contains
  its rerun XML: 1 executed/passed, 0 failures/errors/skips.

At builder handoff, the original assertion messages were outside the bundled stdout tails. `pointers.json`
retains full-log source paths and hashes on the originating computers; those full logs
were unavailable locally. Coroutine entry locations do not identify the failed assertion.
Historical failures remain failures. The independent gap-subtraction timeout belongs to
#2269, already merged before this ticket's base.

The verifier subsequently recovered #2279's full original log at
`/work/Projects/pyrycode-mobile-agents/logs/verifier-gate_#2279_4.log`, matching pointer SHA-256
`8a288b192e88dc6545fb6e898b9c3af832d6a50eda7fe22ff8d967b4932f2082`.
Its revision assertion expected row 1 alone but found rows 1 and 2; the historical class
had 9 executed, 8 passed, 1 failed, 0 errors/skips. #2278's exact assertions remain unknown.

Controlled reproductions retain real Room I/O and the original exact persistence checks.
The intent probe holds the first nonempty snapshot's history read. The revision probe
holds publication of the final snapshot inside its collector. In each case, a scheduler
drain cannot establish writer acceptance. The repair observes `Source.afterEmission`
after releasing the respective hold: returning from the source emission means the
repository has finished `ThreadWriter.accept`.

At controlled-red revision `e5a22b25243caa8debec1a81d11532086d357fef`, both
methods fail: the intent assertion finds no accepted key, and final disk verification
finds row 2 still present after cancellation before acceptance. These reproduce the
shared test defect; they do not reconstruct the unavailable historical assertions.

At repaired revision `0ce36236b2ee3bd507705922ef6e38517781778b`:

| Selection | Executed/passed | Failures/errors/skips |
| --- | ---: | ---: |
| Exact assigned pair | 2 | 0/0/0 |
| Entire affected concurrency class | 9 | 0/0/0 |

The pair command also passes lint and debug assembly. Both revision-probe branches
remain active: evidence failure and cancellation during the held non-cancellable
commit. Final publication is deliberately held in both; acceptance follows release
and precedes cancellation/final flush. The intent retry uses an empty live capture,
so exact restored content must come from its retained pending intent. Every changed
probe releases held work and joins its readers/saves before closing Room.

macOS is outside this Linux ticket's verification scope. No device or live scenario
is required. Subsequent [verifier acceptance](https://github.com/pyrycode/pyrycode-mobile/pull/2296#issuecomment-6254914512)
at `994f7ead2aa1fed18f54003edc8fc6f63ed2cb1e` records 5994 executed/passed,
0 failures/errors/skips in the configured Linux JVM gate. Both named methods executed
once and passed; their complete class passed 9/9 with 0 failures/errors/skips.
These full-suite results are separate from the focused artifacts in this folder.
