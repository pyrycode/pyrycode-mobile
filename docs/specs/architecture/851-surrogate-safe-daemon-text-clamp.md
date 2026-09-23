# #851 — Surrogate-safe clamp for daemon-written names

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt` → `workspaceDisplayName`, `MAX_WORKSPACE_LABEL_CHARS` — the label clamp that feeds tree-row labels.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `boundedText` — clamps identity, relay and the seeded host name; OK submits the seeded name back.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChatModal.kt` → the `initialName` seed in `EditChatModal` — the #826 fix this ticket copies (drop a trailing high surrogate after `take`).
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/EditChatModalTest.kt` → `theClampNeverSplitsASurrogatePair` — the device test the new `EditHostModalTest` case mirrors.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt` → `hostName`, `Modal` — the fixture seeds a fixed host name; it becomes a `var` so one test can seed an oversized one.
- `app/src/test/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayNameTest.kt` — sits beside the existing clamp tests.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The Edit host modal. Nothing visual moves: only the clamped text loses a dangling high surrogate, so the fidelity check is unaffected.

## Change

`take(MAX_WORKSPACE_LABEL_CHARS)` can end on the high half of a surrogate pair (`"n".repeat(127) + "😀"` → 127 `n` + `\uD83D`). Both `workspaceDisplayName`'s label arm and `EditHostModal`'s `boundedText` get the same follow-up the `EditChatModal` seed already applies: when the clamped string's last char `isHighSurrogate()`, drop it. The clamp still bounds the length (it only ever shortens by one), so the ANR protection holds; conformant text never reaches the cut. `boundedText` also covers identity and relay, which is harmless and correct. No shared helper is introduced — each site keeps its one expression, matching the `EditChatModal` idiom — and `EditChatModal` itself is untouched.

## Testing strategy

- `WorkspaceDisplayNameTest`: a label of 127 `n` + `😀` + `tail` returns exactly the 127 `n`.
- `EditHostModalTest.theClampNeverSplitsASurrogatePair`: seeds the host name with the same value, asserts the name field shows the 127 characters, clicks OK unedited and asserts `submitted == listOf(kept)`. Run on the managed device, focused on that method.

## Documentation handoff

None named by the ticket.

## Revisions

### 2026-09-23 — security review added during rework

The verifier's first pass on PR #862 failed the plan because it carried no `## Security review` section, although the ticket was labelled `security-sensitive` before the build began. The section below was added during rework, after the code was committed. The pass found no MUST FIX, so the design and the code are unchanged.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No finding. The untrusted inputs are the daemon-written workspace label (`workspaceDisplayName`'s `label`), the host name that seeds `EditHostModal` (`initialHostName`), and the QR-authored identity and relay address. Each surface keeps one explicit clamp: `workspaceDisplayName` for labels and `EditHostModal.boundedText` for all three modal inputs. The fix changes only how the clamp ends. It can shorten the output by at most one char, so the output is still at most `MAX_WORKSPACE_LABEL_CHARS` chars. The ANR bound therefore holds. When the input is at or under the bound, `take` returns it whole and the new check never drops a char. The guarantee is narrow and stated precisely: **the clamp introduces no lone surrogate**. A lone surrogate already present in the input, for example from a JSON `\ud83d` escape, passes through unchanged. It renders as a replacement glyph, and Compose handles it only as text. Sanitising malformed UTF-16 is a separate concern and is not claimed here.
- **[Round trip through OK]** No finding. `EditHostModal` seeds its field from `boundedName` and submits `fieldValue.text.trim()`. After the fix, an unedited OK submits the 127 whole characters and no dangling `\uD83D`. `EditHostModalTest.theClampNeverSplitsASurrogatePair` checks both the seed and the submitted value. The save path in `HostEditor` (`name.trim().take(MAX_WORKSPACE_LABEL_CHARS)`) does not cut again, because the submitted value is already under the bound.
- **[Tokens, secrets, credentials]** No finding. No token, key or pairing secret is read, created, stored or compared. `boundedText` receives the same three display strings it already received.
- **[File / storage operations]** No finding for this diff. No path, filename or new write is introduced. The persisted host name still goes through the existing `setDisplayName`. One gap is **OUT OF SCOPE**: the `HostEditor` save clamp can still split a surrogate pair in an operator-*typed* name at the 128-char boundary and store it malformed. The refiner raised this on #851 together with `HostIdentityRow`, `boundedRowText`, `ArchivedDiscussionsScreen` and `DebugBundleDownload`. Fixing it is a follow-up ticket, because the ticket's scope is limited to these two clamps.
- **[Inter-process / Android attack surface]** No finding. The manifest is unchanged. No component, intent filter, deep link, `PendingIntent`, content provider or `WebView` is added.
- **[Cryptographic primitives]** No finding. No RNG, hash, handshake or key material is involved.
- **[Network & I/O]** No finding. The diff makes no network call. The relay address is still display text and is never parsed as a URL on this path.
- **[Error messages, logs, telemetry]** No finding, and no new sink. The clamped strings reach only Compose text: `HostWorkspaceGroup`'s `displayName` (documented there as display text, never a key, a path, a filename or a log field), `ThreadViewModel`'s `workspaceLabel`, the Settings supporting line, and the modal's rows and field. List identity stays on `(serverId, cwd)`, and the modal's name buffer is still keyed on the raw `serverIdentity`, so the shortened output never becomes a key. The existing `logEditHostEvent` stays content-free.
- **[Concurrency]** No finding. Both clamps are pure functions with no scope, flow or shared state.
- **[Threat model alignment]** No finding. A **hostile daemon frame** can still send an oversized or emoji-heavy label. The clamp still bounds it, and after the fix it can no longer make the operator's unedited OK store a mangled name. A **malicious relay** and **token theft from disk** are not on this path.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
