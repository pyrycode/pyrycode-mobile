# Reliability investigation: background-agent reply contract

This is a bounded test repair under shared reliability investigation #2101. It does
not claim acceptance of the whole investigation or change production behaviour.

## Evidence and change

The permission-answer deadlock is repaired separately in backend PR 3279. After
that repair, the background-agent scenario exposed test assumptions: a capitalised
reply failed its token check, an interleaved child tool split main text across rows,
and the child's late reply used a different turn identity from its launch.

Use the recorded reply turn and parent agent identity. Require nonblank, settled
assistant text with complete contiguous fragments. Follow the current repository
when the phone reconnects. The prompt leaves ordinary reply wording open while
retaining the commands that hold and release the task. Navigation accepts several
paragraphs and still proves visibility, collapse, tool ownership and list ordering.

Controlled reply and multi-paragraph screen regressions fail before their repairs
and pass afterward. The focused live scenario passed once with no retry against
backend 9c9cd5a25. Its first passing capture used the effort-test worktree plus this
repair. Validate the clean main-based branch separately before merging.

Private evidence lives in the Mobile agents evidence folder
`background-owned-reply-20261012`. It contains raw testcase XML, phone and daemon
logs, and hashes. Do not publish those raw logs.

## Validation and remaining work

Run the complete debug unit suite, lint, debug build, Android-test compilation,
formatting and repository checks. Execute the named live scenario on the final
main-based branch. Keep both Mobile dispatchers paused. The broader suite, other
wording-dependent scenarios, Stop investigation and effort repair remain separate
work within the reliability investigation.

## Security review

**Verdict:** PASS

**Findings:**

- Trust boundaries: test-only reply selection reads decoded repository messages.
  Role, turn and parent identity are checked before acceptance. Text is not parsed
  as a command or used as authority. Missing fragments cannot satisfy the check.
- Tokens: no credential generation, storage or lookup changes. The existing
  managed launcher supplies the isolated test login. No token enters the new
  helper or its diagnostics.
- Files and storage: no production storage changes. Evidence stays in private
  operator storage. Reply text cannot select a file path.
- Android attack surface: no manifest, exported component, intent, provider or
  WebView changes. The visibility matcher observes the existing test semantics.
- Cryptography: the existing encrypted phone connection remains unchanged. The
  test does not replace the handshake or bypass authentication.
- Network: existing launcher, relay, timeouts and cancellation remain in use.
  Following the active repository does not resend commands or create retry loops.
- Errors and logs: new diagnostics report roles, Boolean identity matches and
  streaming state. They exclude message text, commands, credentials and raw IDs.
  Raw capture retention stays private. The changes compile into test source sets.
- Concurrency: waits remain bounded and cancel with the test. The existing
  reconnect-aware read cancels the old repository subscription when replaced.
  The pure reply predicate has no coroutine or mutable shared state.
- Threat model: this repair adds no production boundary. Dropped fragments and
  wrong ownership fail the checks. A failed connection cannot pass through a
  default empty reply. Existing Noise, Keystore and renderer protections remain
  responsible for relay, storage and hostile-frame threats.

**Reviewer:** Codex, self-review using the builder security-review procedure.
**Date:** 2026-10-12
