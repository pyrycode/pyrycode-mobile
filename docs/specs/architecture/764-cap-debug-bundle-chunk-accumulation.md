# 764 — Cap the debug-bundle transfer's in-memory chunk accumulation

A short plan: one guard, one new private counter, one new constant, in one function. No new
type, no new state arm, no new failure mode, no consumer change.

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/DebugBundleTransfer.kt` → `DebugBundleTransfer.accept`
  (the unbounded `chunks += bytes` append and the `require(...)` block the guard joins), `fail`
  (the zero-fill-and-clear release the `INVALID_STREAM` route already performs), `DebugBundleArchive`
  (`sizeBytes` sums the same bytes after the fact — the reason a running counter, not a re-sum, is
  the right shape here).
- `app/src/test/java/de/pyryco/mobile/data/repository/DebugBundleTransferTest.kt` →
  `malformedFieldsAndOrderingFailWithoutPartialBytesAndKeepOrdinaryEventsAlive` (the settle-then-prove-
  it-stays-settled shape the new case copies), `Fixture` and `Pump` (the harness it reuses).
- `app/src/main/java/de/pyryco/mobile/data/network/OkHttpRelayTransport.kt` → `MAX_INBOUND_FRAME_CHARS`
  — confirms the ticket's premise that `accept` enforces no per-chunk bound of its own, and that in
  production a single chunk is already frame-bounded (so one over-cap chunk is a bounded transient).
- `docs/specs/architecture/683-save-a-host-diagnostic-archive.md` § Security review, `[Network & I/O]`
  — the finding this ticket discharges, and the threat framing (authenticated paired peer, content-blind
  relay) that keeps it below MUST FIX.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md` § Base64 + pubkey helpers — why
  `base64StdEncode(base64StdDecode(data)) == data` guards every append; the new bound counts the bytes
  that check has already made canonical.
- `docs/knowledge/features/relay-repository-coordinator.md` § `requestDebugBundle` — the transfer's only
  production caller, confirming no consumer observes a new status or a new field.
- `../pyrycode/docs/protocol-mobile.md` § Debug bundle (v2) (`debug_bundle_chunk`'s ≈48000-raw-byte
  chunking) and § Error codes, close code `4413` — the push-queue ceiling the 32 MiB figure is derived
  against. Cited, not restated: the wire contract stays owned by that document.
- `../pyrycode/internal/relay/v2session_modal.go` → `pushQueueByteCeiling` — `32 << 20` of retained
  **base64 payload** per session, whose own comment records that an archive "past roughly 25 MB ends
  the conn". This is where the ticket's ~25 MB comes from, and it is what makes 32 MiB of *decoded*
  bytes a ceiling above every deliverable archive.

## Design source

N/A — no operator-visible surface changes. `INVALID_STREAM` already maps to `log_data_failed_stream`,
so an over-cap stream reports through copy that already ships. The ticket states this in place of a
`## Figma` section; the verifier's visual-fidelity check is intentionally skipped.

## Change

`DebugBundleTransfer` gains a private running total of the decoded bytes it has accepted, and
`accept`'s chunk branch gains one `require` beside the existing validations: a chunk is rejected
unless its decoded size fits in what remains of a 32 MiB budget. Because it is a `require`, an
over-cap chunk throws `IllegalArgumentException` into the block's existing `catch` and takes the
identical `fail(INVALID_STREAM)` route a malformed `seq` or a non-canonical base64 string takes —
same status, same zero-fill of the buffered chunks, same terminal-once behaviour, no new arm.

Three properties the guard's shape carries, each deliberate:

- **The bound is on the accumulated total, not on one chunk.** The comparison is
  `bytes.size <= MAX_ARCHIVE_BYTES - acceptedBytes`, so many small chunks summing past the cap fail
  exactly as one oversized chunk does. Written as a subtraction rather than `acceptedBytes + bytes.size
  <= MAX_ARCHIVE_BYTES` so the arithmetic cannot overflow regardless of what arrives.
- **It counts decoded bytes, after the canonical round-trip check.** The peer therefore cannot buy
  itself budget by choosing an encoding: one accepted `data` string maps to exactly one byte count.
- **It is a running counter, not a re-sum of `chunks`.** `chunks.sumOf { … }` per chunk is O(n²), and
  n is peer-chosen — a flood of tiny chunks would make the guard itself the denial of service.

`MAX_ARCHIVE_BYTES = 33_554_432L` lands in the existing `internal companion object`, carrying the
derivation as a comment: the daemon's own per-session push queue retains at most 32 MiB of base64
payload before close code `4413` tears the session down, so the largest archive it can actually
deliver is about three quarters of that — roughly 25 MB. The same 32 MiB counted in *decoded* bytes
therefore sits above every deliverable archive while bounding the phone, the memory-constrained side,
at a size any API 33 heap absorbs. Comment cites the protocol document and the symbol; no line numbers.

The counter is never reset. The transfer is single-use and every terminal state short-circuits at the
top of `accept`, so a stale count after `done` or `fail` is unreachable — resetting it in one path and
not the other would suggest a reuse that does not exist. Nothing else in the file moves: `fail`,
`takeArchive`, `logState`, `DebugBundleArchive` and the companion's `rejected` are untouched, and no
signature changes, so there is no call-site fan-out.

No new log line. `fail` already drives `logState`, which emits `status=INVALID_STREAM chunks=N`; the
accepted count distinguishes an over-cap stream from a first-frame malformation without naming a size.

## Testing strategy

One new JVM unit test beside the existing cases in `DebugBundleTransferTest`, using the same `Fixture`
/ `Pump` harness and the same emit-then-`runCurrent` rhythm. It runs two transfers in sequence so the
first's bytes are released before the second allocates:

- **At the cap, completes.** Sixteen 2 MiB chunks sum to exactly `33_554_432`, then
  `debug_bundle_done{total:16}` settles `COMPLETE` with `archive.sizeBytes` equal to the cap. This is
  the `<=`-not-`<` boundary: an off-by-one here would reject a maximal legitimate archive.
- **One byte past the cap, fails.** The same sixteen chunks, then a seventeenth one-byte chunk →
  `INVALID_STREAM`, `acceptedChunks` still 16, `takeArchive()` null; late `chunk` / `done` frames
  afterwards leave the settled state untouched, the assertion the existing malformed-frame case makes.

The literal `33_554_432` is written into the test arithmetic rather than read from the production
constant, so changing the constant reddens the test instead of following it. Chunk size is kept at
2 MiB so each iteration's transient (JSON source string, parsed primitive, re-encoded round-trip
string) stays around 8 MB against a 32 MiB retained total — comfortably inside the unit-test JVM heap,
where a single cap-sized chunk would have needed roughly 200 MB of transients to prove nothing extra.

Byte-identical output stays proven where it already is, by
`emptySingleAndMultiChunkArchivesExposeOnlyAcceptedProgress` on small under-cap streams; the boundary
case asserts `sizeBytes` rather than materialising a second 32 MiB copy through a
`ByteArrayOutputStream`. The zero-fill in `fail` is proven by construction — the guard routes through
the same `fail` call every other invalid frame uses — and not by a direct assertion, because the
buffered arrays are unreachable from the test once dropped.

No Compose UI test, no emulator rung: the change is a data-layer guard with no operator-facing surface,
which is why the ticket carries no `needs-real-claude`.

## Documentation handoff

The ticket names no documentation requirement and carries no documentation-only acceptance criterion.
Pending for the documentation stage, not done here:

- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md` — the paragraph on `DebugBundleTransfer`'s
  canonical-base64 requirement is where the accumulation bound and its derivation belong beside it.
- `docs/knowledge/features/relay-repository-coordinator.md` § `requestDebugBundle` — the transfer's
  documented failure vocabulary should note that an over-cap stream settles `INVALID_STREAM`.

## Open questions

- Whether the residual named under `[Network & I/O]` below (per-chunk object overhead is not bounded by
  a byte cap) deserves its own ticket. Resolved in the review section: named as a limitation, not filed,
  because it is strictly weaker than the case this ticket closes and the ticket's own threat framing
  already places the attacker above it.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries] No findings; one property the design depends on, stated so it cannot be
  refactored away.** The boundary is `DebugBundleTransfer.accept` and it does not move: every
  daemon-authored field still crosses in one function, and this ticket adds no second parsing site and
  no new field. The bound counts the bytes produced by `base64StdDecode` *after* the canonical
  round-trip check the wire-layer document requires, so the budget is charged against what is actually
  retained; a bound on `data.content.length` would instead let the peer's choice of encoding decide how
  much it may buffer. No daemon-authored value reaches Compose, a log, a path or a URL on this ticket's
  path — the guard's only output is an enum arm that already existed.
- **[Tokens, secrets, credentials] Not applicable, by construction.** `DebugBundleTransfer` holds a
  request id and opaque bytes; it never sees `PairedServerCollectionStore`, a `PairedServer`, the
  pairing token or any static key, and this change reads none of them, creates none and logs none.
- **[File / storage operations] Not applicable.** Nothing here opens, names, or writes a file. The
  archive still reaches storage only through #683's SAF `Uri`, which this change cannot influence —
  the only reachable new outcome is that no archive is produced at all.
- **[Inter-process / Android attack surface] Not applicable.** No component, intent filter, deep link,
  `PendingIntent`, provider or WebView is added or altered; the change is invisible outside the process.
- **[Cryptographic primitives] Not applicable.** The chunks arrive already decrypted through
  `NoiseIkSession`; no handshake, key schedule, AEAD framing, randomness or secret comparison is
  touched. The guard compares two non-secret lengths, so constant-time comparison is not in question.
- **[Network & I/O] The category this ticket exists for — closed for bytes, with one residual named.**
  The accumulation is now bounded at 32 MiB of decoded archive bytes, which is the exposure #683's
  review filed. **Residual, deliberately not fixed here:** a byte cap bounds the bytes, not the
  per-chunk object overhead. A peer streaming zero-byte or one-byte chunks never charges the budget
  meaningfully while each chunk still costs an `ArrayList` slot and a `ByteArray` header (~24 B), so
  roughly ten million such frames would still pressure the heap. This is materially weaker than the
  case being closed — that one needs a few hundred legitimately-shaped 48 KB frames, this one needs
  millions sustained over many minutes against an operator-initiated transfer they can dismiss at any
  time, on a connection `LifecycleConnectionDriver` closes on background. Not filed as a ticket: the
  ticket's own threat framing already establishes that the peer must be the paired daemon inside an
  authenticated Noise session and can do strictly worse from that position, and a second cap would
  need a second derived number the protocol publishes no minimum for (the daemon guarantees a maximum
  chunk size, never a minimum), risking rejection of a legitimate archive from a daemon that chunks
  more finely. Recorded here and in the PR's Lessons learned so the documentation stage carries it.
  No HTTP client, timeout, TLS setting, pin or reconnect path is introduced or changed; the inbound
  frame cap in `OkHttpRelayTransport` is read but not modified.
- **[Error messages, logs, telemetry] No findings, one non-addition on purpose.** The rejection emits
  no new log line and no new string: it reaches the existing `logState`, which prints the status enum
  and the accepted chunk count — both content-free, neither derived from payload bytes. Deliberately
  *not* added: a line naming the observed byte total. It would be a length rather than content and so
  not a leak, but it would be the one place in this file where a daemon-influenced number reaches
  Logcat, and the accepted count already distinguishes an over-cap stream from a first-frame
  malformation. No exception message from `require` is surfaced or logged; the `catch` discards it, as
  it already does.
- **[Concurrency] No findings.** The new counter is read and written only inside `accept`, which is
  `@Synchronized` on the same monitor as `fail` and `takeArchive`, so the read of the remaining budget
  and the append that consumes it are one atomic step — there is no check-then-act gap for a second
  inbound frame to slip through. No coroutine, scope, dispatcher or flow is introduced. Backgrounding
  mid-receive still ends the transfer through `endDebugBundle` exactly as before; the counter dying with
  the transfer is correct, since the transfer is single-use and never resumed.
- **[Threat model alignment] Addressed.** *Malicious relay:* content-blind and unable to inject into
  the Noise session, so it cannot manufacture chunks to trip this guard; it can still stall, which this
  change neither improves nor worsens. *Hostile or buggy daemon frame:* this is the case bounded — an
  endless chunk stream now settles `INVALID_STREAM` at 32 MiB instead of running to the platform's
  low-memory killer, and the buggy-daemon half of that is the more likely one in practice. *Token theft
  from disk:* not applicable, nothing is persisted. *UI-side leakage:* not applicable, no archive byte
  and no new value enters screen state; the operator sees copy that already shipped.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22
