# 752 — Bound the pairing payload's fields in UTF-8 bytes at the parse boundary

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/PairingPayloadParser.kt` → `parsePairingPayload`,
  `PairingParseFailure`, `isValidRelayOrigin`, `serverKeyFingerprint` — the function this ticket
  changes, its five existing reject categories and the private-object shape a sixth must join.
- `app/src/test/java/de/pyryco/mobile/data/network/PairingPayloadParserTest.kt` →
  `PairingPayloadParserTest` — the `json()`/`wrap()` fixture helpers the new cases reuse, and the
  existing assertion style (`is Failure`, never the `reason` string).
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt` → `PairedServer` — the
  record the parser maps into; its `toString` is already redacted, so nothing here re-opens a leak.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` →
  `HostEditorState` KDoc — the paragraph that currently asserts the parser bounds neither field's
  length, and `submitHostName`, which clamps the *operator-authored* display name on the write path
  (a different value from the QR-authored `serverIdentity`; it is not in scope here).
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt` → the header-clamp
  comment above the `hostName` `Text` — the second site that asserts "shape but not length".
- `app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt` →
  `MAX_WORKSPACE_LABEL_CHARS` (128 **characters**) — the display clamp all render surfaces apply.
  Deliberately a different unit and a different job from the new parse bound; both stand.
- `docs/knowledge/features/pairing-payload-parser.md` § "Pipeline (first failure wins)" — the
  numbered table the documentation stage renumbers, and § "No-leak discipline", which is the
  invariant the new category must not break.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md` — the `device_name`
  rows of the `mint_pairing` and `register_push_token` tables: the sibling precedent for
  **reject, never truncate**, and the statement that a byte bound is a length ceiling and *not* a
  safety property. Cited, not restated.

## Context

`parsePairingPayload` is the pairing flow's single untrusted→trusted boundary. It validates the
scanned payload's *shape* — non-blank `server`/`relay`/`token`, a ws/wss relay origin with a host,
a base64-std 32-byte `server_static_pubkey` — and bounds no field's *length*. An arbitrarily long
value passes every check and is persisted verbatim into `PairedServer`.

The stored `serverId` is then a navigation route argument on `Routes.thread`, `Routes.literal`,
`Routes.archive` and (since #749) `Routes.settings`, which puts it in the back stack's
saved-instance-state `Bundle`, where `Uri.encode` can inflate a reserved character threefold. A long
enough id can push that `Bundle` past the Binder transaction limit and crash the process on save or
restore.

Reachability is low by design — it needs a hostile QR *and* an operator confirming at the
static-key fingerprint gate, by which point they have also handed over a pairing token. This ships
because the fix is small and local and belongs at a boundary that already does exactly this kind of
validation. Found by the adversarial security-review pass on #749's plan
(`docs/specs/architecture/749-settings-destination-host-owner.md` § Security review), which recorded
it as out of scope there because `Routes.thread` and `Routes.literal` already carried `serverId` the
same way.

No ADR is warranted: this adds a reject branch to an existing boundary, it decides nothing
architectural, and the pipeline table in the feature overview is the right durable home.

## Design source

N/A — no rendered change. The two files under `ui/` are touched for comment text only; no composable,
modifier, token or string that reaches layout moves. The visual-fidelity check does not apply.

## Design

**One shared bound, counted in UTF-8 bytes.** A new private constant in `PairingPayloadParser.kt`:

```kotlin
private const val MAX_PAIRING_FIELD_BYTES = 512
```

Chosen against the values `pyry pair` actually emits, with room to spare: `server` is a UUIDv4 (36
bytes), `token` is 64 hex characters (64 bytes), and the structurally longest legitimate `relay`
origin is `wss://` + a maximum-length FQDN (253) + `:65535` = 265 bytes. 512 clears the worst
legitimate case by ~1.9× and every realistic one by an order of magnitude. On the other side, a
field at the bound costs at most 512 × 3 = 1536 bytes as a `Uri.encode`d route argument — three
orders of magnitude below the ~1 MB Binder transaction limit, and below it even multiplied across
the four routes and a deep back stack. One constant rather than three per-field bounds: the
acceptance criteria name a single bound, and three would be three constants, three comments and
three times the test surface to say the same thing.

**A sixth fixed category**, joining the private `PairingParseFailure` object in the same shape as
the five that exist:

```kotlin
const val FIELD_TOO_LONG = "field-too-long"
```

Fixed label, no field value, no scanned bytes — the no-leak invariant the existing five hold.

**Position in the pipeline: after `missing-field`, before `invalid-relay`.** Both the blank check
and the length check are raw-string shape checks, and blankness is the more basic failure, so it
keeps first place. Running the length check *before* `isValidRelayOrigin` means no arbitrarily long
string is ever handed to `java.net.URI` to parse. The documentation stage inserts it as step 5 and
renumbers relay validation to 6 and the pubkey decode to 7.

**The predicate**, a private helper beside `isValidRelayOrigin`:

```kotlin
private fun isWithinFieldBound(value: String): Boolean
```

`value.length > MAX_PAIRING_FIELD_BYTES` short-circuits to `false` before any encoding, which is
sound because a UTF-8 encoding is never shorter than the string's char count (a surrogate pair is 2
chars → 4 bytes; an unpaired surrogate substitutes to 1 byte per char), and it caps the transient
`toByteArray` copy on the surviving path at 4 × 512 bytes rather than at the size of a hostile
input. Then the real check: `value.toByteArray(Charsets.UTF_8).size <= MAX_PAIRING_FIELD_BYTES`.

**Reject, never clamp.** Truncating would mint a `serverId` that is not the daemon's, and that id is
both the storage key and the relay's routing key — the pairing would then fail to connect in a way
that looks like a server fault. The sibling protocol takes the same line for `device_name`.

**`server_static_pubkey` stays out of the guard.** `decodeServerStaticPubkey` already pins it to
exactly 32 decoded bytes, so an oversized value already rejects; folding it in would only change
*which* category fires for it and would blur `rejects_pubkeyNonBase64`'s meaning. The technical
notes call this acceptable, not required.

**Nothing else moves.** `parsePairingPayload` stays pure, synchronous, logging-free and
non-throwing; `PairingParseResult` is untouched; both call paths (the camera scanner through
`MainActivity`'s decode `LaunchedEffect`, and the paste path through `PasteCodeDialog` /
`PairCodeViewModel`) already handle `Failure` generically by its fixed `reason`, so no caller and no
UI changes. Every display clamp — `EditHostModal`'s `boundedText`, `ConversationTreeRows`'
`boundedRowText`, `ArchivedDiscussionsScreen`'s header, `HostIdentityRow` — stays exactly where it
is; this ticket bounds what is **stored and routed**, not what is **painted**.

## State + concurrency model

None. `parsePairingPayload` is a pure synchronous function with no coroutine, no scope, no flow and
no shared state; the guard is a local predicate on a local string. The two `ui/` edits are comment
text and introduce no state.

## Error handling

The new branch returns `PairingParseResult.Failure(PairingParseFailure.FIELD_TOO_LONG)`, the same
typed result the other five return. First failure wins, the function still never throws, and the
parser still performs no logging at all — the scanned string and the decoded JSON both carry the
plaintext token, so the only thing a caller ever logs is the fixed category. Callers map any
`Failure` to the existing recovery path, so the user-facing surface is unchanged.

## Testing strategy

Unit tests only, in `PairingPayloadParserTest` (`./gradlew testDebugUnitTest`) — this is pure
`data/` logic with no Compose, no coroutine and no device dependency, so there is no UI test and no
emulator rung to land. Not an operator-facing flow: no rung-3 scenario is owed.

New cases, reusing the existing `json()` / `wrap()` helpers:

- Each of `server`, `relay`, `token` one byte over the bound → `Failure`, and the `reason` is the
  new category rather than a later one, which pins the ordering (notably that an over-long relay
  rejects as too-long, not as `invalid-relay`).
- A field of exactly the bound → `Success` with all four fields mapped verbatim.
- A multi-byte field whose char count is under the bound but whose UTF-8 byte count is over (a run
  of `€`, 1 char / 3 bytes each) → `Failure`. This is the case a `String.length` check would pass,
  so it is the assertion that pins the unit.
- A multi-byte field at exactly the bound in bytes → `Success`, the byte-accurate other side.

Tests assert the literal `"field-too-long"` rather than referencing the constant: `PairingParseFailure`
stays private, and the literal pins the category that actually reaches a log line against a rename.

Existing coverage carries unchanged and must stay green — the five existing categories on their own
inputs, the success and round-trip field mappings, `failureReason_neverContainsTokenOrPubkey`, and
the four `serverKeyFingerprint` cases.

## Open questions

- **Is 512 the right number, or should it match the sibling protocol's 128?** Resolved at design
  time: 128 is the bound on `device_name`, a *display label*, and it is too tight for a relay
  origin, which can structurally reach 265 bytes. The sibling precedent this ticket cites is
  reject-not-truncate, not the value. 512 it is.
- **Per-field bounds or one?** Resolved: one, per the acceptance criteria's singular "the bound"
  and because three constants buy no real tightening.
- **Does the check belong before or after the blank check?** Resolved: after, so `missing-field`
  keeps first place for a basic failure, and before relay validation so no unbounded string reaches
  `java.net.URI`.

## Documentation handoff

Pending for the documentation stage — not done in this ticket, and no `docs/knowledge/` file is
touched by this PR.

- **Path:** `docs/knowledge/features/pairing-payload-parser.md`
- **Section:** "Pipeline (first failure wins)" — the numbered table of parse steps.
- **Requirement:** add the new step in the position it actually lands (between the current step 4,
  `missing-field`, and the current step 5, relay validation), with its operation (each of `server`,
  `relay`, `token` within `MAX_PAIRING_FIELD_BYTES` UTF-8 bytes) and its `reason` string
  (`field-too-long`), then renumber the rows below it (relay validation → 6, pubkey decode → 7,
  `Success` → 8).

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. The design keeps the boundary exactly where it is — one pure
  function, `parsePairingPayload`, is still the single place a scanned string becomes a
  `PairedServer`, and the new guard is inside it rather than beside it. Both entry paths (camera
  scanner and paste-code) funnel through it, so one guard covers both with no second boundary to
  drift. Downstream code still holds only the parsed type. The guard does **not** make the stored
  value trusted in any other sense: a bound is a length ceiling and **not** a safety property (the
  sibling protocol says so explicitly for `device_name`), which is why every display surface keeps
  its own clamp and why the two comments this ticket rewrites must say the bound is *additional to*,
  not *a replacement for*, the render-side clamps. Writing either comment as "the parser bounds it
  now, so the clamp is redundant" would be the exploitable outcome of this ticket, and AC4 exists to
  prevent it.
- **[Tokens, secrets, credentials]** No findings, and one thing deliberately *not* done: the guard
  must not report *which* field was over the bound, nor its length. `FIELD_TOO_LONG` is a single
  fixed label for all three fields. A per-field category (`token-too-long`) would turn a parse
  failure into a weak oracle over the scanned payload's shape, and a length in the reason would leak
  a property of the token itself. `token` is compared for length only — never `equals`-compared
  against a secret, so no constant-time question arises. Storage is unchanged:
  `KeystorePairedServerStore` still owns the record, and this ticket touches neither the encryption
  nor the lifecycle.
- **[File / storage operations]** No findings. No path is built from any field, nothing is written,
  and the change strictly *reduces* what reaches `PairedServerStore`. The guard runs before the
  persist, which happens at the call site behind the confirm gate, so a rejected payload never
  reaches disk — AC1's "nothing is persisted" is structural, not a convention.
- **[Inter-process / Android attack surface]** This is the category the ticket exists for. The
  attack is `serverId` → route argument → saved-instance-state `Bundle` → Binder transaction limit
  → process crash on save or restore, with `Uri.encode` inflating a reserved character threefold on
  the way in. The bound is sized against it with three orders of magnitude of margin (512 × 3 =
  1536 bytes per argument against a ~1 MB limit). **Residual, named and accepted:** the bound
  constrains *newly parsed* payloads only. A record already sitting in `PairedServerStore` from
  before this ticket is read back unbounded, so an already-paired oversized id keeps its exposure.
  The ticket calls a load-time check deliberately out of scope and no such failure has been
  observed; recording it here is the belt, and a future ticket owns the suspenders if one ever is.
  No intent filter, deep link, pending intent, provider or WebView is added or touched.
- **[Cryptographic primitives]** No findings. No RNG, no key derivation, no comparison against a
  secret, no handshake code. `decodeServerStaticPubkey`'s exactly-32-decoded-bytes pin is left alone
  and is the reason `server_static_pubkey` needs no bound of its own — deliberately excluded from
  the guard so that an oversized pubkey keeps rejecting as `invalid-server-key`, the category that
  actually describes it.
- **[Network & I/O]** No findings, and one small hardening falls out: ordering the guard before
  `isValidRelayOrigin` means `java.net.URI` is never handed an unbounded string to parse.
  `isValidRelayOrigin`'s own contract — ws/wss scheme, non-empty host — is unchanged; the bound
  narrows what reaches it and relaxes nothing. Bounding the whole `scanned` string before
  `decodeBase64UrlNoPad` is out of scope per the ticket, which leaves a hostile QR able to make the
  process materialize a large string before any field check runs; that is a pre-existing decode-side
  property this ticket neither widens nor closes, and it is bounded in practice by what a QR frame
  can physically carry. No socket, timeout, TLS setting or backoff is touched.
- **[Error messages, logs, telemetry]** No findings. The parser stays logging-free — mandatory, not
  stylistic, because both the scanned string and the decoded JSON carry the plaintext token. The new
  category is a fixed constant with no interpolation, so `failureReason_neverContainsTokenOrPubkey`
  keeps holding over it. Callers log only the fixed `reason`. No telemetry.
- **[Concurrency]** No findings — no coroutine, no scope, no flow, no shared mutable state. The new
  predicate is a pure local function over a local string, so the function's existing purity and
  reentrancy are preserved and there is nothing to cancel, order or shut down safely.
- **[Threat model alignment]** The threat addressed is a **hostile pairing payload** — one that is
  well-formed enough to pass every existing check while carrying a field long enough to crash the
  process later, at a moment (saved-state write or restore) far from the scan that caused it. The
  design fails closed: nothing stored, nothing truncated, a fixed category. Two threats are named
  and **not** addressed here: *already-stored oversized records* (load-time check, deliberately out
  of scope, see Inter-process above) and *authenticity* — a well-formed hostile QR still parses like
  a legitimate one, and the control against that remains the static-key fingerprint confirm gate
  (#343), not this parser. Neither is widened by this change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22
