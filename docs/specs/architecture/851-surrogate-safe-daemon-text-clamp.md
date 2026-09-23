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
