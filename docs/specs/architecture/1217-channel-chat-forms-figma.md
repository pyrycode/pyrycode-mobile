# #1217 — Channel and chat forms against Figma

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/CreateChannelModal.kt` → `CreateChannelModal`: host-first create guard, retained name and verbatim prompt.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChannelModal.kt` → `EditChannelModal`, `MuteNotificationsRow`, `ArchiveChannelAction`: prompt-reading state, mute and archive controls.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChatModal.kt` → `EditChatModal`, `ChatNameField`, `ArchiveAction`: chat name, archive and IME submit.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialog.kt` → `SaveAsChannelDialog`: promotion form and retry state.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal`: shared header, scroll region, footer and error placement.
- `app/src/main/java/de/pyryco/mobile/ui/components/ChannelFormFields.kt` → `ChannelFormFields`: shared channel labels, wells, prompt limit and focus behavior.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/ChannelFormFieldsCaptureTest.kt` → `createFormAt412By892`: existing actual-device capture and viewport fixture.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/EditChatModalTest.kt`, `CreateChannelModalTest.kt`, `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialogTest.kt` → existing callback and validation coverage.
- `docs/knowledge/features/mobile-modal.md` → `MobileModal` layout and visible-versus-touch geometry; keep pinned footer and content scrolling.
- `docs/knowledge/features/system-prompt-editor.md` → prompt semantics and verbatim transport.
- `docs/knowledge/features/development-verification.md` → real-pixel emulator capture and evidence limits.

## Design source

**Figma:** [412 × 892 shell](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369), [Create channel](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2435), [Edit chat](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2320), [Edit channel](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=500-2120), [Save as channel](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2355). Inspected 2026-09-30.

The dark shell has a navy full-height surface, 28 dp horizontal inset, titleLarge heading, close icon, divider, centered content and a pinned Cancel/OK footer. The four 676 px content frames use a 12 dp gap between field blocks, labelLarge emphasized labels, 52 dp name wells, a paragraph prompt well, a compact mute checkbox on Edit channel, and a 40 dp outlined archive action on edit forms. Their 676 px width is not a 412 × 892 form reference; compare content spacing and styling, not an invented mobile pixel target. Figma supplies no compact, enlarged-text, keyboard, disabled, loading, error, prompt-reading or archive-confirmation state. The Edit chat frame labels its field “Channel name” and capitalizes “Chat”; those conflict with the chat identity/string casing currently used by the caller and must be resolved from the rendered comparison.

## Context

#1232 and #1233 already provide the shell and channel field primitives. This ticket aligns only the four callers and proves their phone render and behavior. The forms do not choose a workspace or change Chats-plus creation.

## Design

Keep the existing caller signatures, value ownership and `MobileModal`/`ChannelFormFields` composition. Use real captures to identify caller-specific differences, then adjust only affected callers/resources. Match visible control dimensions and gaps while preserving at least 48 dp touch targets. Do not alter channel prompt bytes, callback payloads, host gating or retry keys. Capture the four real forms at 412 × 892 in the fixed dark theme; retain the current Figma PNGs and create labelled side-by-side/difference evidence for comparable shell/content regions. Put durable fixture and evidence files under `app/src/androidTest/` and a narrowly scoped comparison script under `scripts/` only if needed.

## State and concurrency model

All four forms remain local Compose state keyed to their target conversation or host. `MobileModal` owns scrolling and loading display. The caller owns asynchronous writes; this ticket introduces no flow, dispatcher or job. Dismissal removes the caller, cancelling its composition. A failed write leaves remembered input intact.

## Error handling

Keep `MobileModal`'s reachable error text and each existing disabled submit guard. `ChannelFormFields` continues to show the UTF-8 prompt limit warning and prompt-reading/unavailable note. Do not trim system-prompt text. Leave connection and repository failures with the existing caller state machine.

## Testing strategy

- Write a failing screen assertion for each observed in-scope visual mismatch before changing production code. Extend existing shared tests for callback, guards, prompt text, mute and archive where coverage is missing.
- Run focused shared screen classes, Android lint, debug assembly, Android-test compilation and forced Spotless check.
- Add or extend a device capture test for all four actual caller forms at 412 × 892. Inspect fresh device XML for executed tests and nonblank PNGs; compare them with the four Figma content nodes and the shell. Use compact-width, enlarged-text and keyboard/menu assertions for reachable fields and footer, including pointer taps for changed control bounds.
- The existing live Save as channel scenario covers the operator flow; this visual adjustment adds no new operator action. Dispatcher owns full UI/scripted gates.

## Documentation handoff

No documentation-only acceptance criterion or explicit path was supplied. Documentation stage should update the shared form overview with the measured mobile differences and missing Figma states.

## Open questions

- Which differences are present in actual device renders rather than inferred from the 676 px exported content?
- Does the Edit chat field label/capitalization reflect an intended text change, or a conflicting reference state? Record the answer and any design departure in Revisions and the PR.
