# MCP host-readiness repair (#2089)

The recovery-directed repair replaces MCP's selected-host banner wait with the
explicit harness host's combined relay/daemon readiness. Send now delegates to
the same predicate. Both must be Connected; the deadline remains 30000 ms.
The helper never changes selection or retries a connection.

`readiness-red.xml` retains five failures against the original compatibility
predicate; `readiness-green.xml` retains the same five passing encrypted-registry
controls. They distinguish ready target/unavailable selected peer, unavailable
target/ready selected peer, held daemon handshake, Idle at the original deadline,
and missing target. The red/green XML omits system output and stack frames, keeping
the original failure type/message. Raw XML and all 61 passing focused JVM tests
are retained at `/tmp/builder-2089/readiness-repair/`.

`focused-live-mcp.xml` records one executed/passed method, zero failed/errors/skips.
`focused-live-stages.txt` retains all eight started/completed stages plus the
authenticated target snapshot before chat creation. `focused-live-provenance.json`
records source and binary revisions/hashes, XML and phone/daemon artifact paths.
Raw capture survives worktree removal at
`/tmp/builder-2089/readiness-repair/live-mcp/`. Daemon files are snapshots sampled
every 200 ms before successful harness cleanup; the final teardown tail may be
absent. The daemon binary reports `vcs.modified=true`, retained explicitly with
its hash. `focused-live-sendnow.xml` records the existing consumer's fresh 1/1
pass; raw XML, phone/daemon snapshots and binary hashes are retained in the sibling
`live-sendnow/` folder.
`scripted-sendnow.xml` records the deterministic twin's 1/1 pass with zero
failures/errors/skips; raw XML/phone logs are retained in `scripted-sendnow/`.

`evidence.txt` and the earlier historical/prefix/suffix files describe the prior
diagnostic checkpoint. Their statements that a repair was still pending are
historical. The recovered failure occurred in readiness before chat creation,
but its original outage cause remains unresolved. The controlled readiness defect
is established independently; a passing focused selection is not full acceptance.

Pending dispatcher handoff: a fresh configured full post-verifier live pass,
including this MCP method, with executed/failed/error/skipped counts, tested
mobile/daemon revisions, APK/daemon binary hashes and retained phone/daemon paths.
Return those artifacts for builder retention as `dispatcher-full.xml`,
`dispatcher-method.xml` (the named MCP case) and `dispatcher-provenance.json` here.
Keep `needs-real-claude`; `needs-live-artifacts` requests this builder return.

## Integrated chat-setup assessment (2026-10-12)

`chat-setup-red.xml` records 3 controls with one offscreen-target timeout and
2 passes against the original composed-node polling. `chat-setup-green.xml`
records all 3 passing after MCP-only scrolling. The connected, loaded target
has 30 Channels rows above its Chats control; the repair performs one addressed
creation click and keeps the original deadline.

`assessment-live.xml` records 2 executed, 1 passed (MCP), 1 failed (Send now),
0 errors/skips. `assessment-mcp-method.xml` and `assessment-mcp-stages.txt`
confirm MCP's 1/1 pass and all eight stages, including authenticated readiness,
real ping, report, filter and displayed built-in row. Send now reaches both
Connected legs then times out observing peer queue removal, owned by open #2056.
It stays enabled; its assertion/deadline and queue logic are unchanged.
`assessment-scripted.xml` records the MCP report/sheet twin's 1/1 pass.
`assessment-provenance.json` retains revisions, binary hashes and raw paths.
These focused results do not satisfy configured full-live acceptance.

Raw captures and verified manifests survive worktree removal and container
restart at `$AGENTS_REPO_PATH/logs/evidence/2089/recurrence-assessment-20261012/`.
Earlier raw readiness evidence is also retained at `retained-readiness-20261012/`
and new JVM controls at `chat-setup-controls-20261012/` in that issue directory.
The failed live run includes complete daemon logs; successful scripted snapshots
may omit final teardown. Daemon binary `c4119af59` reports `vcs.modified=true`.
Historical #1977/#2098 attribution remains unresolved. Resume consumer/full gates
after #2056 lands; the three required `dispatcher-*` assets remain pending.
