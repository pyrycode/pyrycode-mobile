# ADR 0008 — a separate, release-kept message trail instead of a `RelayLog` adoption

**Status:** Accepted (2026-10-02 decision; [#1564](https://github.com/pyrycode/pyrycode-mobile/issues/1564)).

## Context

The daemon can prove only that a message never arrived; it cannot say whether the phone kept the message,
failed to send it, or sent it on a connection that then dropped. Only the phone can record that, and Juhana
runs the Play test-track **release** build, not a debug build — the only build where these bugs actually
show up.

[`RelayLog`](../features/relay-log.md) already exists for exactly this kind of connection diagnosis, and it
is safe-by-construction on two axes: it cannot reach release output (`enabled` defaults to the compile-time
`BuildConfig.DEBUG` constant), and it cannot assemble a sensitive value in release (the message is a
`() -> String` lambda invoked only inside the gate). Both axes are deliberate, load-bearing, and by
construction — see ADR context in that document's § How it works.

## Decision

Add a second, separate facility, `MessageTrail` (`data/diagnostics/MessageTrail.kt`), rather than adopting
`RelayLog` or weakening its gate. `MessageTrail` is safe in release not by gating emission but by taking no
free-form text at all: every method takes only a `message_id`, a redacted connection token, a fixed state
and (for failures) a fixed reason and a shape-checked daemon error code. There is no lambda, no message
parameter, and no path by which message text, attachment names, the relay host, the pairing token or a full
`conn_id` could reach a line.

## Rationale

- **`RelayLog`'s debug gate is by construction, not a setting.** Flipping `enabled`'s default, or adding a
  release-build escape hatch, would release every other `RelayLog` line across the app, not just the
  message-send lines this ticket needs. That is a much larger exposure than one ticket's diagnostic need
  justifies.
- **A free-text API cannot be the release channel.** `RelayLog.d/i/w` take a `() -> String` lambda — safe in
  debug because a caller can be held to the MUST-NOT-log KDoc under code review, but nothing stops a future
  release-reachable call from interpolating a secret. A diagnostic that ships in every release build needs a
  stronger guarantee than a comment: an API shape that cannot carry text, checked in code, not in review.
- **The two facilities serve different moments.** `RelayLog` is a debug-only, wide-vocabulary event log for
  an engineer with a plugged-in debug build. `MessageTrail` is a narrow, release-shipping record of one
  thing — the states a specific `message_id` reached — pulled after the fact with `adb pull` from a release
  install.

## Alternatives considered

- **Add a release-reachable mode to `RelayLog`.** Rejected: see Rationale — the blast radius is every
  `RelayLog` call site, not just message sends, and the gate's whole value is that nothing flips it on in a
  release build.
- **Keep `RelayLog`'s lambda API but restrict call sites to literals via convention/review.** Rejected:
  convention isn't code-enforceable, and `RelayLog`'s own MUST-NOT-log contract already accepts that
  limitation for debug-only output; a release-shipping channel should not rest on the same soft guarantee.

## Consequences

- Two diagnostic facilities now exist with different safety mechanisms: `RelayLog` (debug-gated, free-text
  lambda) and `MessageTrail` (release-kept, no free text). A future diagnostic need should pick the
  mechanism that matches where it needs to run, not default to extending whichever facility is closest.
- `MessageTrail` has a known gap — it loses a message's later states across a reconnect, because the states
  it gates on a per-connection ledger (`ThreadProjection.mintedMessageIds`) don't carry over when the phone
  reconnects on a new connection. See [relay-log.md § Message trail](../features/relay-log.md#message-trail--a-separate-release-kept-facility-messagetrail-1564)
  for the mechanism and a possible fix. Not blocking; left as a follow-up.

## Related

- [Message trail](../features/relay-log.md#message-trail--a-separate-release-kept-facility-messagetrail-1564)
  — the facility this decision governs
- [Relay diagnostic log](../features/relay-log.md) — the debug-only sibling facility this decision chose not
  to extend
- Ticket: [#1564](https://github.com/pyrycode/pyrycode-mobile/issues/1564); plan:
  `docs/specs/architecture/1564-message-trail.md` (§ Security review — Verdict PASS)
- Desktop twin: pyrycode-desktop#1721
