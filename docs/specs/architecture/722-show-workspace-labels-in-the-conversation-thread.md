# 722 — Show workspace labels in the conversation thread

## Files read

| Path | Symbol | Why it matters |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` | `Conversation.workspaceLabel()` (file-private extension) | The basename-only rule this slice replaces and deletes; its sole caller is the `combine` lambda's `workspaceLabel =` argument. |
| ” | `ThreadUiState.workspaceLabel` | The `String` field the chip reads; its `"scratch"` default and the `conv == null` fallback must survive unchanged. |
| ” | `Conversation.displayName()` | The sibling file-private extension the deleted one sat beside — the conversation title, unrelated and untouched. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspaceChip.kt` | `WorkspaceChip`, `WorkspaceChipLightPreview`, `WorkspaceChipDarkPreview` | The single render sink; its `Text` has no line bound today, and the two previews AC4 extends. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` | the `WorkspaceChip` call inside the body `Column` | Confirms production always passes `Modifier.fillMaxWidth().padding(...)` — the constraint the long-label preview must mirror to be evidence. |
| `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` | `Conversation.workspaceLabel`, `DEFAULT_SCRATCH_CWD` | The #720 nullable property this slice first displays, and the sentinel the fallback arm keys on. |
| `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` | `workspaceLabel(cwd)` (file-private) | The duplicate formatter AC3 explicitly leaves for #723 — read to confirm it is a separate declaration with no shared call site. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | `applyWorkspaceLabel` | #721's live writer; the emissions AC2's rename/clear coverage stands in for. |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` | `fixedRepo`, `makeVm`, `state_workspaceLabel_isBasename_forArbitraryCwd` | The three #137 fallback tests that must keep passing, and the fixed-flow double AC2 needs a live-emitting variant of. |
| `docs/knowledge/features/workspace-chip.md` | § `workspaceLabel` derivation | Carries the behaviour table this slice extends, and records that `"foo/"` degenerately yields `"foo/"` — a documented quirk to preserve, not fix here. |
| `docs/knowledge/features/thread-screen-how-it-works-state.md` | the `conv?.workspaceLabel()` paragraph | Records the parens trap the ticket's Technical Notes name; deleting the extension is what retires it. |
| `docs/knowledge/features/thread-screen.md` | § Wiring | Each thread destination is bound to one host's repository, so host isolation needs no new proof here. |
| `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` | `WORKSPACE_CHIP_PREFIX`, the change-workspace scenario's `folderName` match | The live assertions a label-first rule must not break. |
| `docs/specs/architecture/720-retain-workspace-labels.md` | § Security review | Defers display safety to "#628's display-consumer slices" — this slice is that consumer, so the obligation lands here. |
| `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md` | `conversations`, `conversation_created`, `conversation_updated` rows | Wire SSOT: the label is "a stored opaque string echoed **verbatim**" whose "128-byte bound is a size limit and **not** a safety property — rendering it safely is the client's job". Cited, not restated. |

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The `16:8` frame renders a **populated** dark thread — app bar, message bubbles, thinking row, composer, status row — and contains no workspace chip, because the chip only mounts on an unpromoted thread with zero messages. `get_design_context` returns that same populated inventory and no chip node. So this slice has no pixel-exact reference, exactly as `workspace-chip.md` § Related already records for #137: the chip's styling stays anchored to its M3 `AssistChip` + leading `Icons.Outlined.Folder` instruction. This slice changes the chip's **text content and line behaviour only** — no color, type style, spacing, shape or asset moves, so `AssistChip`'s M3 token defaults continue to carry the appearance and no `Schemes/*` variable or theme slot is read or added.

## Context

`ThreadViewModel` derives the chip's text from `cwd` alone. #720 landed the nullable `Conversation.workspaceLabel` and #721 keeps it live on the owning host's projection, so an operator's chosen name already reaches the thread on the existing `observeConversations(ConversationFilter.All)` arm — only the display rule is missing, and the thread still shows a folder basename where desktop shows the chosen name.

The rule must not land as a third private formatter. Two already exist and differ only by receiver: the file-private `Conversation.workspaceLabel()` in `ThreadViewModel.kt` and the file-private `workspaceLabel(cwd)` in `SettingsScreen.kt`. #641 (tree) and #723 (Settings subtitle) each need the same rule, so this slice puts **one** definition where all three packages can reach it and migrates the thread onto it. AC3 scopes the migration: the thread is its only consumer here; Settings' copy is #723's to retire.

No ADR is warranted — this adds no architectural seam, only a shared pure function under `ui/`.

## Design

**New shared definition** — `app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt`, package `de.pyryco.mobile.ui.workspace`:

```kotlin
internal const val MAX_WORKSPACE_LABEL_CHARS: Int = 128

fun workspaceDisplayName(cwd: String, label: String?): String
```

A pure top-level function, no receiver and no `Conversation` parameter. Taking two scalars rather than a `Conversation` is what makes it reachable by #723, whose Settings subtitle holds a bare `defaultWorkspace: String` preference and no conversation at all, and by #641's workspace rows.

**Name.** The ticket requires a name confusable with neither `Conversation.workspaceLabel` (the #720 property) nor `Conversation.workspaceLabel()` (the extension being deleted). `workspaceDisplayName` shares no identifier with either and is not an extension on `Conversation`, so the parens trap cannot recur.

**Rule**, in order:

1. `label` non-null and non-blank → that label, clamped to `MAX_WORKSPACE_LABEL_CHARS` (below), otherwise verbatim.
2. else `cwd` empty or `== DEFAULT_SCRATCH_CWD` → `"scratch"`.
3. else `cwd.substringAfterLast('/').ifEmpty { cwd }`.

Arms 2 and 3 are the current extension moved unchanged, so all three existing fallback outputs — including the documented degenerate `"foo/"` → `"foo/"` — are preserved by construction. Arm 1 is unconditional: a labelled scratch workspace shows its label, because the protocol keys the label by workspace and scratch is nameable. AC1's "an empty cwd or `DEFAULT_SCRATCH_CWD` still displays `scratch`" describes the *unlabelled* fallback, which arm 2 keeps.

**Verbatim, not normalised.** Blank is the only rejected shape. The label is not trimmed, case-folded, escaped or otherwise rewritten — the wire contract calls it an opaque string echoed verbatim, and the domain KDoc already says "opaque daemon-authored display text". Whitespace or markup-looking characters inside a label are the operator's chosen text and render as themselves.

**Length clamp (security, § Security review finding 1).** The label is daemon-authored text entering Compose, and the protocol states in as many words that its 128-byte bound is a size limit and not a safety property. `MAX_WORKSPACE_LABEL_CHARS = 128` bounds what reaches text layout. 128 UTF-16 units is an upper bound on 128 UTF-8 bytes, so the clamp can never truncate a protocol-conformant label; it fires only on a non-conformant one. A clamp that splits a surrogate pair yields one replacement glyph on an already-hostile label — accepted, not guarded.

**ViewModel.** The `combine` lambda's `workspaceLabel = conv?.workspaceLabel() ?: "scratch"` becomes `workspaceLabel = workspaceDisplayName(cwd = conv?.cwd ?: "", label = conv?.workspaceLabel)`. The `conv == null` case still yields `"scratch"` via arm 2, so the missing-conversation edge case is unchanged. The file-private extension and the now-unused `DEFAULT_SCRATCH_CWD` import are deleted. `ThreadUiState.workspaceLabel`'s type, name and `"scratch"` default are untouched, so the screen, the initial-value frame and every other consumer are unaffected.

**Chip.** `WorkspaceChip`'s label `Text` gains `maxLines = 1` and `overflow = TextOverflow.Ellipsis`. It stays a plain `Text` — never `MarkdownText`, never a WebView — which is what keeps daemon text rendering as text. Nothing else about the composable's signature, icon, tokens or call site changes.

**Not touched**, per AC3: `SettingsScreen`'s private formatter (#723), `ChannelInfoSheet` and `ThreadUiState.workspacePath` (which keep showing the full `cwd`, because a label is display text and never replaces a path), and list/tree rendering (#641).

**e2e impact: none.** `WORKSPACE_CHIP_PREFIX = "Workspace:"` is a prefix of the unchanged literal. The change-workspace scenario matches the chip against a folder created through `create_workspace_folder`; a freshly created folder carries no label, so `conversation_updated` names the destination with `null` and arm 3 still yields the basename. The match holds exactly while that premise does, which is the condition the ticket asks to confirm.

## State + concurrency model

None added. `workspaceDisplayName` is a pure, non-suspending function called inside the existing `combine` lambda — no new flow, job, scope, dispatcher or suspension point, and the existing `stateIn(viewModelScope, WhileSubscribed(5_000))` lifetime and cancellation path are untouched. Live rename and clear arrive on the already-subscribed `observeConversations(All)` arm, so the open thread re-derives its text from a re-emission of the same subscription.

## Error handling

No new failure mode. The function is total over every `(String, String?)` input — no throw, no `Result`, no UI error surface, and no branch a caller must handle. There is nothing to classify and nothing to log.

## Testing strategy

RED first: the new assertions must fail against the current basename-only rule before any production edit.

**New — `app/src/test/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayNameTest.kt`** (the rule, exhaustively and cheaply):

- A non-blank label wins over a cwd basename, and survives verbatim including whitespace, Unicode and markup-looking characters.
- A label wins over `DEFAULT_SCRATCH_CWD` too — the arm-1-is-unconditional case.
- `null`, `""` and an all-whitespace label each fall through to the cwd-derived text.
- The unlabelled table from `workspace-chip.md` reproduces exactly: `""` and the sentinel → `"scratch"`; `"pyry-workspace/my-app"` → `"my-app"`; `"~/Workspace/Projects/X"` → `"X"`; `"X"` → `"X"`.
- A conformant 128-character label passes through untruncated; a 5000-character label is bounded to `MAX_WORKSPACE_LABEL_CHARS` and is a prefix of the input.

**Extended — `ThreadViewModelTest`** (AC2, driven through the bound repository's emissions):

- The thread's text prefers the conversation's label over its cwd basename.
- A rename and then a clear on the thread's own host move the text label → cwd-fallback across one VM and one collector: a `MutableStateFlow`-backed repository double emits the conversation unlabelled, then labelled, then cleared, asserting the text after each. Resubscription is disproved directly by counting `observeConversations` subscriptions (`onStart`) and asserting it stayed at one across all three emissions.
- This needs a live-emitting double; the existing `fixedRepo(List<Conversation>)` helper gains a one-line `fixedRepo(Flow<List<Conversation>>)` overload that it delegates to, rather than a second 40-line stub of `ConversationRepository`.
- The three #137 fallback tests keep passing unmodified — they are the regression evidence that arms 2 and 3 moved without changing output.

**AC4** is proven by the preview named in the AC plus the structural `maxLines`/`overflow` change: a third `@Preview` renders a 128-character label through production's own `Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)` chain — the existing two previews wrap content and would not demonstrate a bounded width. Compose layout needs a device, so there is no JVM-level assertion to add; no device test is warranted for a text-truncation change.

**Live acceptance is not mine.** The ticket assigns the rung-3 cross-client rename/clear scenario to #676 and the live regression gate to the dispatcher; `needs-real-claude` stays on the issue. No `androidTest` source changes, so `compileDebugAndroidTestKotlin` is informational here.

GREEN gate: `testDebugUnitTest` scoped to `WorkspaceDisplayNameTest` and `ThreadViewModelTest`, then `spotlessApply`, `lint`, `assembleDebug`.

## Documentation handoff

Pending for the documentation stage — no shared doc is edited by this slice:

- `docs/knowledge/features/workspace-chip.md` § `workspaceLabel` derivation — the label-first rule, its new home at `de.pyryco.mobile.ui.workspace.workspaceDisplayName`, the `MAX_WORKSPACE_LABEL_CHARS` clamp, and a refreshed behaviour table covering the labelled rows.
- `docs/knowledge/features/thread-screen-how-it-works-state.md` — the paragraph contrasting the private extension with the #720 property: the extension is deleted, so the trap is retired and the paragraph should record the shared rule instead.
- `docs/knowledge/features/thread-screen-shape.md` and `docs/knowledge/features/thread-screen-testing.md` — their `workspaceLabel` entries.

## Open questions

None. Two decisions were resolved inside this plan rather than deferred: arm 1 applies unconditionally (a labelled scratch shows its label), and the label is bounded but never normalised.

## Security review

**Verdict:** PASS — after one MUST FIX found during the pass was folded into § Design above. The plan was not committed in its pre-finding shape.

**Findings:**

- **[Trust boundaries] MUST FIX — addressed in § Design before commit.** This slice is the first render path for `Conversation.workspaceLabel`, and #720's own review deferred display safety to exactly this consumer. Daemon-authored text entering Compose needs a length bound and a text-only render path. The protocol is explicit that the 128-byte bound "is a size limit and **not** a safety property", so an in-session hostile or buggy daemon can put an arbitrarily long string into `Text`, where Compose measures the whole `Paragraph` on the UI thread regardless of `maxLines` — an ANR vector. Fixed by clamping to `MAX_WORKSPACE_LABEL_CHARS` inside `workspaceDisplayName`, i.e. at the single shared boundary rather than at each future consumer. The boundary is one named function; `ThreadUiState.workspaceLabel` downstream of it holds bounded display text only.
- **[Trust boundaries] No further findings on the render path.** The sink stays `androidx.compose.material3.Text`, which renders a `String` literally — never `MarkdownText` (which exists one package over and would interpret the label), never a WebView, never an attribute, URL, filename or cache key. A markup-looking label such as `"<b>A</b>"` is asserted to survive as literal characters, which pins "rendered as text" as a test rather than a convention.
- **[Trust boundaries] Display text is never an identity or a path.** `workspaceDisplayName` returns text for rendering only. The real `cwd` continues to reach `ChannelInfoSheet` through the untouched `ThreadUiState.workspacePath`, and no caller may feed the return value to `changeWorkspace` or any path-shaped API. The function's KDoc states this at the declaration, where a future consumer reads it.
- **[Tokens] Not applicable.** No credential is generated, read, stored, compared or transmitted. Nothing here touches `data/crypto/` or the paired-server stores.
- **[File / storage] Not applicable by construction.** A pure function with no I/O, no persistence, no `File`, no `Context`, no preference write. No path is built from any input, so traversal and TOCTOU have no surface; no storage-scope, at-rest-encryption, atomic-write or backup decision arises.
- **[Android attack surface] Not applicable.** No `Activity`, `Service`, `BroadcastReceiver`, `<intent-filter>`, deep link, `PendingIntent`, content provider, push-payload handling or `android:exported` change. No WebView — see the render-path finding.
- **[Cryptography] Untouched.** No RNG, no hash, no comparison against a secret. The `Noise_IK_25519_ChaChaPoly_BLAKE2s` transport, its vendored `noise-java` implementation and its per-direction nonce counters are not in the diff.
- **[Network & I/O] No new surface.** No frame type, request, timeout, TLS setting, pin, backoff or reconnect path changes; the label rides the existing `conversations` / `conversation_created` / `conversation_updated` frames decoded at #720's boundary. The client-side clamp is precisely the compensating control for the protocol's declared non-guarantee — the daemon's bound is not treated as one.
- **[Errors / logs / telemetry] No logging added, and the label must never be logged.** The ticket says so and the design has no log call to violate it: the function is total, adds no classified error and no lifecycle event. It also introduces no user-facing error string, so there is no new message that could carry the label into a Snackbar or a crash report.
- **[Concurrency] No findings.** No coroutine, scope, dispatcher, mutex or mutable shared state is added; a pure call inside the existing `combine` lambda adds no suspension point and no check-then-act. The `WhileSubscribed(5_000)` lifetime, `viewModelScope` ownership and the `LifecycleConnectionDriver` background-close path all behave exactly as before, and the AC2 test asserts the subscription count stays at one rather than assuming it.
- **[Threat model] Hostile daemon frame** — bounded and rendered as text, per the first two findings. **Malicious relay** — content-blind and on-path only; it cannot author a label inside the Noise session, and dropping or delaying one degrades to the cwd fallback on the next emission rather than to a wrong-but-authoritative name. **Token theft from disk** — no storage touched. **UI-side leakage** — the chip now shows operator-chosen text that the operator already sees on desktop and that names a workspace they own; this widens no audience beyond whoever can already read the thread, and the full `cwd` was already on screen through the channel-info sheet. **OUT OF SCOPE:** the identical clamp-and-render obligation for the tree rows (#641) and the Settings subtitle (#723) — each consumes this same bounded definition when it migrates, which is why the bound sits in the shared function rather than in `ThreadViewModel`.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21
