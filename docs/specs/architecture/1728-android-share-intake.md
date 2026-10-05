# Android share intake (#1728)

## Files read

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: `MainActivity`, `PyryNavHost` and `openThread` own launch navigation and host-qualified destinations.
- `app/src/main/AndroidManifest.xml`: the exported launcher activity currently has no share filters.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: `appModule` owns constructor injection and the process-wide `ComposerDraftStore`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`: `ConversationTree` and `treeHost` already preserve host-qualified targets, folds and offline rows.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt`: `onHostRowTapped` records and navigates to the row's actual host.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentPicker.kt`: `describePickedAttachment`, `capturePastedImage` and `captureAndPublishPastedImages` establish the provider and cancellation boundaries.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/OwnedPasteCopy.kt`: private capabilities own bounded, backup-excluded copies and serialize read/deletion.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerDraftStore.kt`: `addAttachment` enforces existing-draft limits and transfers or releases copy ownership.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerAttachmentStrip.kt`: private thumbnail decoding samples images and respects orientation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `sendWithAttachments` retains copies through complete attempts; no send changes are needed.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes` supplies fixture/peer digest and isolation assertions.
- `scripts/e2e-emulator.sh`: the curated full live selector must include the new method.
- `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md`: copies belong to drafts after synchronous publication; cancellation as IO returns needs explicit cleanup.
- `docs/knowledge/features/channel-list-screen.md`, `channel-list-screen-tree-and-controls.md`, `navigation.md`, `development-verification-gates.md`: reuse the tree and routes; shared screen tests require native fonts for exact geometry.
- `docs/e2e-interactive-stream.md`: rung-3 peer and attachment harness contracts.
- Sibling `pyrycode/docs/protocol-mobile.md`, Security model: existing upload/send protocol and Noise boundary stay unchanged.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=771-6753

Inspected design context and screenshot. Reuse the list's static-dark canvas glow and tree. Replace its toolbar with a 48dp back target and `titleLarge` “Share to…” in a row padded 4dp/16dp horizontally and 8dp vertically. The preview row uses 16dp gutters, 12dp spacing, a 48dp outlined rounded thumbnail, `titleMedium` summary and `bodySmall` filename in on-surface-variant at 75% alpha; 24dp separates preview from the tree. Existing back and tree vectors match the frame. Preview imagery is user content, not a Figma asset.

## Context

Android shares currently have no app entry point. This is one destination-selection flow, reusing #1727 rather than introducing a second attachment lifetime. No new wire contract, dependency or decision record is needed.

Sizing: approximately 1450 written lines including plan, production, deterministic tests and the live scenario; at most four new internal exported declarations, five acceptance criteria, fewer than ten classified rejection branches. Existing callers retain defaulted UI parameters; only the activity/list/tree wiring needs simultaneous updates. Codegraph's callers were incomplete, so repository search confirmed the concrete call surface. Shared overlap is additive: #1603 in strings; #1674, #1682, #1689, #1690, #1691, #1693, #1695, #1761 and #1766 in separate live-test methods. No overlap restructures this flow.

## Design

Add share-only helpers under `ui/share/`: a redacted parsed payload, a redacted preview state and an activity-scoped `ShareIntakeViewModel` with constructor-injected draft store, capture callback and IO dispatcher. Intent parsing accepts only SEND/SEND_MULTIPLE, plain EXTRA_TEXT and correctly typed ordered stream extras; ClipData URIs are a fallback only when stream extras are absent. Deduplicate before capture. Malformed extras return no payload. Text is bounded before retention/rendering.

Register both share actions for `*/*` on MainActivity with single-top delivery. Read a fresh initial intent once, and route new intents through the same intake. Retain intake across activity recreation in its ViewModel, never saved state. Normal launcher, pairing and notification navigation remain intact; new notification taps still use their exact target.

Capture each foreign URI into `OwnedPasteCopy` on IO before publishing the picker as selectable. Add a separate generic-file capture entry alongside the unchanged image-only paste validator. Captured byte count replaces provider metadata. Retain at most `MessageAttachmentIds.MAX` files per batch. Preview reads only owned bytes; use sampled, orientation-aware decoding and a file fallback.

Expose a defaulted header slot on `ChannelListScreen` for destination mode. Reuse `ConversationTree`; hide toolbar, plus/edit controls and editor bindings in destination mode, retain folds and connection presentation. The header shows the first image when any exists, all-image/file count summaries and first filename, or ellipsized text alone. Existing empty state remains available and cancellation always works.

Selection synchronously consumes the ready batch, stages files through `ComposerDraftStore.addAttachment` for the row's exact pair, and appends shared text to a nonblank existing draft with a newline (otherwise prefills). Refused copies are released by the store. Only after transfer call `ChannelListViewModel.onHostRowTapped`. No upload/send operation is added. Consumption precedes navigation, so recomposition/recreation cannot replay it. No valid ready batch means no draft edits.

## State and concurrency model

The intake ViewModel owns one capture job and a nullable StateFlow preview, keyed by a monotonically increasing generation. Commands run on Main; capture runs on the injected IO dispatcher. Replacement/cancellation invalidates the generation, cancels the job and releases its unselected files. Each captured result is assigned inside IO and released in finally unless published to the current generation; this covers cancellation on return. Capture publishes progress but row selection is disabled until complete. Selection transfers ownership synchronously and clears intake before navigation. ViewModel clearing cancels capture and releases remaining files. The existing process-scoped store and send-retain model own selected files thereafter. Screen exit/background connection cycling never uploads a share.

## Error handling

Malformed/unsupported intents do not replace state or edit drafts. Foreign-URI rejection, unreadable and actual-byte oversize add nothing and report existing attachment notices with static codes. Count refusals report existing counts-only copy. Notices travel through a buffered flow to an activity snackbar, available in picker and selected thread. Cancellation is rethrown, never rendered as failure. Provider exceptions and metadata never reach logs/errors.

## Testing strategy

- Robolectric intent tests cover single/multiple text/file/ClipData precedence, ordering, duplicates, wrong extra types and unsupported inputs.
- Coroutine intake tests cover actual byte/count limits, generic files versus image-only paste, cancellation/replacement/clearing cleanup, consumption once, host/conversation isolation, existing attachments and draft merging, no draft writes before selection, and source revocation before owned preview/send reads.
- Shared Compose picker tests cover header/48dp preview geometry, summaries, empty state, hidden mutation controls, folds, disconnected rows and exact selected pair. Run existing ChannelListScreen/colour tests, notification navigation, owned-copy/paste, draft and attachment-send/strip regressions.
- Activity tests cover fresh/new intents, recreation before/after consumption, both Back paths and ordinary navigation. Robolectric-only activity lifecycle tests remain in unit sources.
- Add `InteractiveStreamE2ETest.interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes`: share PNG/document/text, select X, delete original provider items before Send, assert peer text and both digests in X and none in Y. Device-only because it needs the real daemon/peer and Android provider grants. Preserve the existing phone paste/pick scenario and add the method to the full live selector. Deterministic intake/send tests provide the twin without needing a new fakeclaude fixture to hold a turn.
- Focused Gradle unit/shared tests, lint, assembleDebug, androidTest Kotlin compile, Spotless apply and forced check. Run the existing scripted stream scenario as the focused device/harness check. The dispatcher owns full live execution and must supply fresh counts and explicit named-method passage before documentation completes.

## Open Questions

None; process death intentionally drops share state, as it drops composer drafts.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md`, Composer pending attachments — describe share intake and ownership transfer; `docs/knowledge/features/navigation.md`, incoming navigation — fresh/new intents, cancellation and process-local recreation behavior; `docs/knowledge/features/channel-list-screen.md`, destination picker mode; `docs/e2e-interactive-stream.md`, rung-3/full live suite — new named scenario and fresh executed/failed/skipped counts, confirming it ran and passed. Documentation must wait for dispatcher live evidence.

## Security review

**Verdict:** PASS

- [Trust boundaries] Enforce one typed intent parse and the existing `isForeignContentUri` guard before queries/reads. Wrong extra types cannot mint a private-copy capability. Bound shared text and clamp metadata; text renders only as Compose text/composer content.
- [Tokens] No credential changes; shares do not reach pairing or token stores. Override payload/state stringification so exceptions cannot expose content.
- [Files and storage] Generated files under `noBackupFilesDir/composer-paste-copies`; external filenames/paths never choose a private path. Actual bounded reads remain authoritative. Capability-only preview/send access inherits #1727 reference cleanup. No durable draft/share write or backup entry is introduced.
- [Android attack surface] Exported MainActivity accepts only validated share extras. Own-provider authorities (including profile prefixes), file/resource URIs are rejected. No new provider, permission, mutable pending intent or WebView.
- [Cryptography] No crypto changes; send remains inside vendored Noise IK. Digest checks in live tests use standard SHA-256.
- [Network and I/O] No intake network request; existing bounded attachment reader and upload limits remain. Provider exceptions are discarded. A hostile slow provider can occupy IO as on paste; cancellation invalidates publication and eventual return cleans copies without blocking the main thread.
- [Errors/logs] Only static event/outcome codes and counts; never shared text, bytes, URIs, paths, provider names/types/sizes or exception details. Existing generic notices contain no provider data.
- [Concurrency] Generation guard plus per-result finally cleanup prevents late publication after cancel/replace. Consumption is synchronous and once-only; store attachment lock enforces existing-draft count limits. No saved-state payload. No new mutex order or socket owner.
- [Threat model] Malicious relay/host defenses remain in the unchanged protocol codec/Noise transport. Rooted-device disk access and UI screenshots/accessibility have the existing attachment/composer threat model; copies exclude backup and are temporary, not credentials. Shares grant no automatic send or permission approval.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-05
