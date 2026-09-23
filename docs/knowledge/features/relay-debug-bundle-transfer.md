# Host diagnostic archive transfer

Split out of [Relay/repository coordinator](relay-repository-coordinator.md#configuration) when
that overview crossed the size cap (\#764). `RelayConnectionRegistry.requestDebugBundle(serverId):
DebugBundleTransfer` is the coordinator's Configuration-owned entry point for an operator-initiated
Log data pull; this document covers the transfer itself.

## How it works

`RelayConnectionRegistry.requestDebugBundle(serverId): DebugBundleTransfer` uses
the caller's exact, case-sensitive host id under the registry's removal lock. It
requests the whole daemon's archive through that bundle's current paired, Open
connection, regardless of `interactive`. Unknown, removed, disconnected or
handshaking hosts return `UNAVAILABLE`; selection changes never redirect an
attempt, and different hosts can transfer independently. The request follows the
daemon's [Debug bundle (v2) contract](https://github.com/pyrycode/pyrycode/blob/main/docs/protocol-mobile.md#debug-bundle-v2),
omitting both `payload` and `conversation_id` (see [envelope encoding](mobile-protocol-v2-wire-layer.md#envelope--application-message-frame)).

Observe `transfer.state: StateFlow<DebugBundleState>` for `status`,
`acceptedChunks` and `retry`. Progress counts accepted chunks, not bytes or a
percentage. `RECEIVING` becomes `COMPLETE` only on a validated completion marker;
zero-chunk archives are valid. On completion, `takeArchive()` returns a
`DebugBundleArchive` once, then returns `null`. Its `sizeBytes` and
`writeTo(OutputStream)` support a separate save owner: bytes stay outside screen
state, remain opaque and are never unpacked. The caller owns the output stream,
output failures and release of the archive reference. Native action/saving belongs
to [#683](https://github.com/pyrycode/pyrycode-mobile/issues/683). The receiver holds
chunks in memory, bounded at 32 MiB of accumulated decoded bytes (\#764, see
[Mobile protocol § Base64 + pubkey helpers](mobile-protocol-v2-wire-layer.md#base64--pubkey-helpers));
a stream that crosses the bound settles `INVALID_STREAM` exactly as a malformed
frame does. It still adds no transfer timeout.

| Admission/result | Retry condition |
| --- | --- |
| `UNAVAILABLE` | `WHEN_AVAILABLE`: make a new explicit request when this host has a usable connection. |
| `BUSY` | `AFTER_TRANSFER`: another transfer is receiving; observe that attempt and re-evaluate afterward. No second frame is sent and the active transfer is retained. |
| `RECONNECT_REQUIRED`, `COMPLETE`, `SEND_FAILED`, `REFUSED`, `INVALID_STREAM`, `DISCONNECTED` | `AFTER_RECONNECT`: a fresh connection is required before another explicit attempt. |

Every attempted send consumes the repository's transfer allowance, including
success, refusal and a false/throwing send. Chunk/done frames have no request
correlation, so resetting an accumulator on the same connection could admit old
frames into a retry. `BUSY`'s `AFTER_TRANSFER` therefore does **not** promise a
same-connection retry; a later call on that still-open connection returns
`RECONNECT_REQUIRED`. Reconnect creates a fresh repository and never replays the
request. Retry values describe availability, not automatic actions.

The repository's sole inbound consumer routes bundle frames and errors whose
`in_reply_to` matches the bundle request to the transfer, via `MessageCommands` (#915,
`data/repository/MessageCommands.kt`), which now owns the retained `DebugBundleTransfer` and its
admission; the repository's `internal requestDebugBundle()` / `endDebugBundle()` stay as one-line
hand-offs for this registry and for `DebugBundleTransferTest`. Unrelated errors and
ordinary events retain their handlers, including after a malformed bundle frame.
Send failure, correlated refusal, invalid input, disconnect, inbound termination
or host removal settle a receiving transfer once and wipe/release partial chunks.
Late frames cannot change a terminal result. Missing completion stays incomplete
until teardown or inbound termination produces `DISCONNECTED`. Transfer failures
and diagnostic logs contain only static categories and accepted counts, never archive content,
recordings, credentials, daemon error bodies or exception details.

**Known limitation, deliberately not closed (\#764 security review):** the 32 MiB bound
charges decoded bytes, not per-chunk object overhead. A peer streaming zero- or one-byte
chunks never meaningfully charges that budget while each chunk still costs an `ArrayList`
slot plus a `ByteArray` header (~24 B), so millions of such frames would still pressure the
heap. Left unfixed because it is materially weaker than the case the byte cap closes (millions
of frames sustained over minutes against an operator-initiated, dismissable transfer, versus a
few hundred legitimately-shaped ones) and because a count bound would need a second derived
number the protocol publishes no minimum for — the daemon guarantees a maximum chunk size,
never a minimum, so a too-tight count would reject a legitimate archive from a daemon that
chunks more finely.

## Testing

`DebugBundleTransferTest` checks opaque output, accepted counts, terminal-once
failure, late frames, correlated errors and all inbound termination modes. Numeric
rejection cases must include **zero accepted chunks**: `JsonPrimitive.intOrNull`
rounded `1e-400` and `-1e-400` to zero, while tests starting after the first chunk
passed because zero already mismatched the expected one. Both `seq` and `total`
now use exact integer text parsing; invalid cases assert no archive and continued
ordinary-event routing. Canonical base64 checks are described in the
[wire layer](mobile-protocol-v2-wire-layer.md#base64--pubkey-helpers).

The accumulation-cap case (\#764) runs two transfers in sequence so the first's
bytes are released before the second allocates: sixteen 2 MiB chunks summing to
exactly `33_554_432` settle `COMPLETE` with `archive.sizeBytes` equal to the cap
(the `<=`-not-`<` boundary — an off-by-one here would reject a maximal legitimate
archive), while the same sixteen chunks plus one more byte settle `INVALID_STREAM`
with `acceptedChunks` still 16 and `takeArchive()` null. The literal cap value is
written into the test arithmetic rather than read from `MAX_ARCHIVE_BYTES`, so a
changed constant reddens the test instead of silently following it. Chunk size is
kept at 2 MiB so each iteration's transients (JSON source string, parsed
primitive, re-encoded round-trip string) stay around 8 MB against the 32 MiB
retained total — a single cap-sized chunk would have needed roughly 200 MB of
transients to prove nothing extra. Byte-identical output on an under-cap stream
stays proven by the existing multi-chunk case; the boundary case asserts
`sizeBytes` rather than materializing a second 32 MiB copy. The zero-fill `fail`
performs on an over-cap stream is proven by construction, not by a direct
assertion: the guard routes through the same `fail` call every other invalid
frame uses, and the buffered arrays are unreachable from the test once dropped.

Bundle registry tests use two real Noise peers without `interactive`, inspect the
decrypted serialized request for omitted fields, and change selection/remove one
host while the other completes. Coordinator tests request immediately after a
transport change, **before `runCurrent()`**, to catch stale admission; after
reconnect they assert no automatic send and no old-pump frames reaching a retry.
