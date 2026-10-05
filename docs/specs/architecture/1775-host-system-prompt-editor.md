# Host system prompt editor (#1775)

## Files read

- `ui/host/HostEditor.kt`: `HostEditorController` captures the host and guards terminal writes; `HostEditorModal` binds the shared flow.
- `ui/components/EditHostModal.kt`: `EditHostModal` owns the name buffer; `UnpairAction` supplies the outlined action geometry.
- `ui/components/MobileModal.kt`: `MobileModal` supplies close, Back, Cancel, OK, errors, scrolling and IME avoidance.
- `ui/settings/SettingsScreen.kt`: notification-sound row supplies the headline/subtitle/chevron pattern.
- `ui/conversations/list/ChannelListViewModel.kt`, `ChannelListScreen.kt`, `MainActivity.kt`: controller construction, state projection, events and reachable navigation dispatch.
- `data/repository/ConversationRepository.kt`: `HostSystemPromptReading`, `requestHostSystemPrompt`, `setHostSystemPrompt` and `SystemPromptLimit` from merged #1774.
- `data/repository/SessionSettingsCommands.kt`: host reads/writes validate returned text and return current/default in the acknowledgement.
- `ui/conversations/components/SystemPromptEditor.kt`: loading/unavailable gating and redacted state pattern.
- `docs/knowledge/features/host-editor.md`, `system-prompt-editor.md`: keep the captured target and resolve repositories at each write, rather than construction binding across reconnect.
- `app/src/sharedTest/.../EditHostModalTest.kt`, `app/src/test/.../HostChannelListViewModelTest.kt`: existing modal geometry, name/unpair and captured-host coverage.
- `app/src/androidTest/.../InteractiveStreamE2ETest.kt`, `scripts/e2e-emulator.sh`, `scripts/android-test-gate.py`, `docs/e2e-interactive-stream.md`: real Edit host controls and curated live harness.
- Sibling `pyrycode/docs/protocol-mobile.md`: host prompt wire contract and Security model remain authoritative.

## Design source

Figma: [Edit host empty](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=776-10333), [filled](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=776-10387), [editor empty](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=777-10461), [filled](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=777-10515), [default](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=780-7063).

All five design contexts and screenshots were read. Reuse the dark modal shell, titleLarge header and close glyph, with a centred content block and Cancel/OK footer. The entry has 12 dp vertical padding, 16 dp chevron gap, 2 dp headline/subtitle gap, bodyLarge/onSurface headline and bodySmall/onSurfaceVariant one-line preview, followed by Unpair host. The dedicated field uses bodyMedium, modalFieldContainer/modalFieldText, modalControl shape, 16 dp insets and a 280 dp minimum well that grows for the returned default; helper uses bodySmall/onSurfaceVariant with 8 dp spacing. The outlined reset below the helper uses the existing action geometry, with 4 dp top inset. Existing close and settings-chevron drawables match the design assets. Never embed the sample/default text from Figma.

## Context

The operator needs the daemon-owned host prompt in the shared Edit host flow. #1774 is merged and present. This is one editor deliverable: transport, reset semantics and session effects remain owned by the existing contract. No new dependency or decision record is needed.

Overlap: #1283, #1642, #1682, #1689, #1690, #1691, #1693 and #1695 share resources or the live class; their edits concern other rows/scenarios. Keep additions local. No design depends on these branches.

Size forecast: roughly 1250–1450 written lines including plan, controller/UI, caller/resource wiring, unit/component tests and live scenario. At most five new exported declarations, four production consumer updates, five acceptance criteria and eight reject/error categories. The written plan remains within the ticket ceiling.

## Design

Add a sealed `HostPromptState` (Loading, Unavailable, Loaded) and sealed `HostPromptEvent` (Open, Edit, Reset, Save, Discard). Loaded carries acknowledged current/default, draft, saving and failed; it derives reset visibility and byte-limit gating and redacts text in `toString`. Add prompt state and editor visibility to `HostEditorState`. A repository resolver constructor parameter defaults to unavailable for the retired Settings caller; the channel list supplies `hostSource::repositoryFor`.

Opening Edit host launches a prompt read on the captured host. Only a successfully bounded current/default response becomes Loaded. Opening the dedicated editor copies confirmed text to the draft. Loading/unavailable may be viewed but cannot edit/reset/save. Reset changes only the draft to the returned default. Save sends verbatim, including empty and unchanged text; use the acknowledgement's current/default preview, then return to Edit host. Failure keeps draft and generic retry error. Discard drops the draft and returns to Edit host without writing; outer Cancel never reverses an acknowledged write.

Keep `EditHostModal` composed while replacing its shell with a dedicated editor slot, leaving its existing name buffer above the presentation branch. Add a row slot between name and Unpair. The dedicated UI lives beside its single-consumer state in `HostEditor.kt`; it draws plain multiline text without a hint. Thread/channel prompt nullable clear, session refresh and construction-bound repository behavior are not reused.

## State and concurrency model

All transitions run on the owner's main dispatcher and jobs use its injected `viewModelScope`. Opening/dismissing cancels the read; an open-generation token rejects delayed identity/prompt responses even from non-cooperative fakes. Prompt reading updates only the same active host/open generation and never overwrites a newer draft.

Resolve the captured serverId again at Save. Null repository is a generic failed save retaining the draft, never success. Mark one write in flight, reject duplicate saves, and disable field/reset until it settles. Discard/dismiss/open invalidate the pending write's UI publication through state identity/generation guards; cancellation cannot promise rollback of an already sent daemon write. Later acknowledgement cannot reopen or overwrite a newer editor. Background closes repositories through the existing lifecycle driver; returning drafts stay in controller memory, and subsequent Save resolves the replacement repository. Reopening retries a failed/unavailable read.

## Error handling

Repository Results stay at the controller boundary; thrown non-cancellation exceptions become static failure flags. Rethrow cancellation. No exception message enters UI, state or logs. Reject an oversized current or default before rendering; over-limit local drafts stay editable and show one generic validation error with disabled OK. Preserve all whitespace and line breaks; reuse inclusive `SystemPromptLimit.fits`.

## Testing strategy

Tests first: new controller tests fail before implementation. Cover unread gating, empty/default/filled, UTF-8 inclusive bound, verbatim/empty save, reset/discard, retry, null/replaced repositories, distinct captured hosts, read/write dismissal and newer-host races, duplicate save, cancellation and redacted logs/state. Use a repository fake with controlled reads/writes and in-memory store/preferences.

Shared `HostPromptEditorTest` under `app/src/sharedTest` checks loading/unavailable/empty/filled/default controls, helper, reset visibility, generic validation/save errors, one-line preview, multiline text, name retention on save/discard, close/Back routing and field/action pointer taps. Add device-only `HostPromptCaptureTest` for five dark states: it needs real dialog pixels. Run the existing focused keyboard host-modal method as well. Run shared coverage together with existing `EditHostModalTest`, list host/reconnect tests and relevant Settings tests. Run lint, assembleDebug, compileDebugAndroidTestKotlin and forced spotlessCheck after formatting.

Retain the five synthetic dark-state PNGs, capture context and focused device XML under `app/src/androidTest/assets/host-prompt-1775/`. Capture the focused modal's semantics root, with a fresh dialog for each state and pixel assertions for close/OK; a global window inventory can select a retiring dialog. These software decor captures establish geometry/content, not hardware blur or real system bars.

Add `InteractiveStreamE2ETest.interactiveTurn_hostSystemPrompt_editsResetsAndCancels` and its curated live entry. Use real Edit host controls; independently fresh-read custom/reset/discard values and reopen previews; restore original in finally. Device-only reason: real daemon/relay and instrumented app graph. This storage/editor flow has no scripted Claude turn to hold; controller/component fakes provide its deterministic twin. The dispatcher owns the fresh full-suite live execution on a daemon containing pyrycode#2768. PR Live tests requests `all`; passing live evidence is pending, never inferred from routine checks.

## Open Questions

None. No prompt persistence on phone; no extra reset wire verb or session actuation. Editing is disabled only during a write, while oversized drafts remain editable.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/host-editor.md`, controller/UI/testing sections, record the new host prompt behavior and reconnect-safe resolver.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, rung-3 scenario/evidence sections, record the fresh full curated live executed/failed/skipped counts and explicit passing method, produced by the dispatcher after verification (AC5).

## Security review

**Verdict:** PASS

- [Trust boundaries] Bounded current/default at the controller's read/ack boundary; plain Text/BasicTextField only. No markup, URL, path or cache-key use. Local over-limit drafts remain editable but cannot leave via Save.
- [Tokens] Only display fields are copied from paired records; credentials never enter prompt state. Prompt/default/draft may contain secrets; Loaded and Edit event explicitly redact string representations.
- [Files/storage] No new phone persistence or saved-state bundle for prompt text. Daemon persistence remains #1774/pyrycode#2768. Name/pairing storage is unchanged.
- [Android attack surface] No new exported component, deep link, pending intent or WebView. Ordinary editing uses existing modal accessibility/keyboard behavior; UI-side exposure to keyboard/accessibility/screenshots is the existing operator editing trust surface, not a new credential-entry surface.
- [Cryptography] Reuse #1774's Noise_IK transport and Keystore pairing without changing keys, counters or TLS.
- [Network/I/O] Existing bounded request/ack codec stays intact. Failed/null repository cannot be read as empty or acknowledged success. The live scenario bounds fresh reads and cleanup writes with `THREAD_TIMEOUT_MS`, including reply awaits; repository availability alone does not bound a daemon reply.
- [Errors/logs] Static event/outcome codes only. No prompt/default/draft, raw exception, name or decrypted bytes in logs/errors; redaction asserted by tests.
- [Concurrency] Captured host id, open generation and pending-state identity prevent delayed relay replies crossing editors; current repository resolver prevents stale connection saves. A prompt read completing during an outer write is retained separately until that write fails or closes, without changing its pending-state identity. Open/dismiss clear the retained outcome; outer terminal transitions also check their open generation, including a reopened identical host. Every job belongs to owning viewModelScope.
- [Threat model] Malicious relay delay/drop resolves as generic failure or discarded stale result; cannot read Noise plaintext. Hostile oversized daemon text is rejected. Rooted-disk token theft remains existing Keystore/Noise protection; no additional prompt cache is created. Visible operator text remains accessible through intended plain-text controls.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-05

## Revisions

- 2026-10-05: Component verification showed a long over-limit draft could grow the well beyond the viewport and hide surrounding controls. Keep the 280 dp minimum and cap visible field content at 24 lines, using BasicTextField's internal multiline scrolling; returned defaults still grow within that bound. The generic validation line remains reachable in the shell content scroll area.
- 2026-10-05: PR #1778's verifier found a completed prompt read was dropped during rename/unpair, leaving Loading after a failed write. Retain only deferred Loaded/Unavailable outcomes in `HostEditorController.deferredPromptRead`, merge them into the write's failure state and clear them at that terminal transition. This preserves the pending `compareAndSet` target and any later acknowledged prompt value. Clear on open/dismiss and guard outer terminal publication by the captured open token so older writes cannot affect a reopened identical host. Regression tests cover both read outcomes for each failed outer write, successful closes, subsequent prompt acknowledgement and same-host reopening races. The security review above was rechecked against this revised design; no new trust, storage, logging or crypto boundary is introduced.
- 2026-10-05: The verifier also identified unbounded daemon reply waits in the new live scenario. Wrap its fresh read and finally restoration in the existing `withTimeout(THREAD_TIMEOUT_MS)` convention. Full live execution remains the dispatcher's handoff. Rework shares the live class with additional #1703 and #1735 branches; their changes affect separate scenarios.
