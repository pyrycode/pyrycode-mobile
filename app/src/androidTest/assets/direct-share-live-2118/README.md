# Direct Share readiness evidence (#2118)

The historical #2057 report has one failed Direct Share method and the rerun has one pass.
Its stderr reports only a 30000 ms coroutine timeout. No stage can be attributed historically.
`historical-provenance.json` retains complete suite counts, tested revisions and verified original hashes;
the two historical XML files select only Direct Share from those reports.

The controlled red uses the original `awaitConnected` predicate against the actual registry:
healthy X times out behind disconnected selected Y, while healthy selected Y admits handshaking X.
Missing-host and unchanged-deadline controls fail too. Addressed combined readiness passes all four.
The six asynchronous stage probes fail with a pass-through helper and pass with the repair.
Normalized XML omits stacks and content; original counted reports and red source snapshots are in
`/tmp/builder-2118/evidence/`, with source hashes in `provenance.json`.

| Check | Executed | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Readiness red / green | 4 / 4 | 0 / 4 | 4 / 0 | 0 / 0 |
| Diagnostics red / green | 6 / 6 | 0 / 6 | 6 / 0 | 0 / 0 |
| Affected registry class and diagnostic class | 32 | 32 | 0 | 0 |
| Scripted direct-share | 1 | 1 | 0 | 0 |
| First focused live | 1 | 1 | 0 | 0 |
| Focused live with correlated daemon capture | 1 | 1 | 0 | 0 |

The scripted case is `DeterministicInteractiveStreamE2ETest#interactiveTurn_directShareShortcut_stagesBeforeExplicitSend`.
Both live runs explicitly select `InteractiveStreamE2ETest#interactiveTurn_directShareShortcut_arrivesAtPeerWithItsBytes`.
All delivery assertions remain enabled. The daemon evidence records two stored files, one enqueued Send
and two retrievals; phone stages correlate by time. Raw daemon snapshots are bounded to the last 1 MiB
per log and sampled during ordinary admission before successful cleanup; their paths/hashes and limits
are in `provenance.json`. Only allowlisted static daemon events/counts are committed here.

Pending dispatcher: fresh configured full live gate, complete suite and named-method counts, Mobile/main
and daemon revisions, XML/logcat and correlated daemon evidence. A focused pass does not prove full-suite
ordering or identify the original failure. Documentation stage updates Recent-conversation Direct Share
in `docs/e2e-interactive-stream.md` after that proof, preserving these attribution limits.
