# Recent conversation sharing shortcuts (#1729)

## Files read

- `MainActivity.kt`: `MainActivity`, `PyryNavHost`, `holdsActive` and the notification readiness effect own exported navigation and share staging.
- `ui/conversations/share/ShareIntake.kt`: `SharePayload`, `SharedContent`, `ShareIntakeViewModel.select` preserve private-copy ownership and synchronous single transfer.
- `di/HostConversationSource.kt`: `update`, `withRows`, `Held.live` distinguish loaded lists from startup and cache restore.
- `di/AppModule.kt`: `appModule` supplies process-owned publishers and activity-owned intake.
- `notifications/AttentionNotifier.kt`: `NotificationTap.target` and `notificationTitle` supply validation and label sanitation.
- `data/repository/ConversationListProjection.kt`: `observe` filters the unloaded null projection; reconnect does not emit an initial empty remote list.
- `data/crypto/PairedServerStore.kt` and `di/ObservablePairedServerStore.kt`: saved-host lookup and revision establish unpairing independently of connection availability.
- `AndroidManifest.xml` and launcher resources: exported single-task intake and existing adaptive artwork.
- `ShareIntakeTest`, `ShareActivityTest`, `SharePickerTest`, `NotificationTapNavigationTest`, `HostConversationSourceTest`: existing capture, recreation, exact-host navigation and delayed-list seams.
- `InteractiveStreamE2ETest.interactiveTurn_sharedContentFromAndroid_arrivesAtPeerWithItsBytes` and `scripts/e2e-emulator.sh`: private-byte fixtures, peer assertions and curated live list.
- `docs/knowledge/features/navigation.md`, `dependency-injection-host-conversation-source.md`, `thread-screen.md`: retain disconnected rows and never interpret empty cache reads as authoritative absence.
- `docs/e2e-interactive-stream.md`: rung-3/rung-4 harness contract.
- Sibling `pyrycode/docs/protocol-mobile.md`, Security model: existing encrypted transport stays the wire authority.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=703-5001

Inspected section screenshot and circle preview `703:5009` design context. The existing launcher is a pale blue snowflake over a dark radial glow, centered inside Android's adaptive safe area. Shortcuts reuse `ic_launcher`; Android supplies masking and layout. The #1728 picker and thread retain their existing Material 3 design.

## Context

Direct Share exposes recent exact-host conversations while keeping all shared content unsent until Send. One deliverable combines publication with the only consumer, share intake. Forecast: about 1200 written lines including this plan, three internal types, two existing signatures with defaulted parameters and fewer than ten callers to update, five acceptance criteria, fewer than ten reject branches. No new dependency or decision record is needed.

In-flight #1682, #1689, #1690, #1691, #1693, #1695 and #1766 add independent live methods; #1766 also adds a curated-list entry. These additive overlaps do not block this ticket.

## Design

`SharingShortcuts` in the share UI package owns a bounded recent ledger and Android publication. A small `RecentShareTarget` record holds a validated pair and cleaned label. Identity is a SHA-256 digest over length-prefixed host and conversation strings, never a label. Resolution accepts only a retained, uniquely matched published id. Launcher intents use the existing `NotificationTap` contract with the exact pair; no shared payload is included. All ids satisfy NotificationTap's nonblank, 256-character bounds.

Persist the four-or-platform-limit most recent records in an AtomicFile under noBackupFilesDir. Initialize before processing opens or reconciliations. Opens move a pair to the front once, push its normal dynamic shortcut and update retained ranks; label refreshes use updateShortcuts without reporting a new open. Reconciliation only removes targets for saved-host absence or a snapshot whose new `rowsLoaded` flag establishes absence/archive. Cache restoration never sets that flag. Removal and eviction call removeLongLivedShortcuts as well as removing dynamic publication. New shortcuts are not long-lived, static, pinned or excluded from launcher surfaces.

`shortcuts.xml` declares one matching category and text/plain, image/* and */* data entries, linked from MainActivity metadata. Publication uses launcher artwork and notificationTitle with app-name fallback.

`SharePayload` carries a strictly typed, bounded optional shortcut id. Invalid shortcut extras retain the valid share payload and fall back to the picker. SharedContent keeps that id until direct resolution succeeds or falls back. PyryNavHost resets to the list, conceals the picker during direct capture/lookup, and after capture resolves the retained pair, saved host and active row with the five-second notification readiness window. The final current-generation and saved-host/active-row checks and synchronous select precede navigation. A generation mismatch cannot transfer or navigate. Ordinary shares keep the picker. A thread-entry effect reports an open after an active row is available for every navigation source.

## State and concurrency model

The publisher has one application-owned coroutine scope with injected IO dispatcher and a Mutex serializing restore, open, reconciliation and disk writes. dispose cancels the scope. `sharingShortcutHosts` observes saved-host revisions through a cold latest-revision flow: each revision starts an explicit collection read, retrying classified failures every second until success without a pairing mutation. A new revision cancels a pending read or retry delay and reads immediately; disposal cancels collection and all recovery work. Failures emit no authoritative host set. Snapshot flows trigger reconciliation only after that first success. Confirmed removals await shortcut cleanup under the publisher mutex through `forgetRemovedHost`, even if revisions conflate or the caller is cancelled. `PyryApp` starts the publisher through `startApplicationGraph` before returning from application startup; selector-only module resolution stays lazy and Android-free. Android operations run in this serialized scope. Lookup waits for initialization and reads the same ledger under the mutex.

Capture remains in viewModelScope with the existing injected IO dispatcher and cancellation cleanup. Direct routing runs in a generation/capture-keyed LaunchedEffect, so replacement, consumption, cancellation and recreation cancel the old lookup. Recreation keeps the activity ViewModel and can retry an unconsumed lookup; consumed state cannot replay. Connection shutdown keeps the existing lifecycle driver and rows.

Notification/launcher routing and direct-share routing finish every suspending saved-host lookup before explicitly entering `Dispatchers.Main.immediate`. Both recheck the current active host/conversation snapshot in that final Main turn; the earlier readiness observation cannot authorize navigation after a suspension. Their final UI checks, synchronous draft transfer and navigation contain no suspension, so Compose test continuation interception cannot resume navigation on the store's IO worker. Cancellation still fences entry into that Main turn.

## Error handling

Malformed shortcut extras, unknown ids, unpaired hosts and unavailable rows retain captured content and clear direct routing to show the existing picker. Launcher unavailable targets stay on the list via existing notification semantics. Unreadable shortcut storage starts with an empty ledger; atomic write failures retain the in-memory ledger and log only static outcomes. Android publication failures log static codes without ids, labels or shared data. No errors enter drafts or invoke network uploads.

Classified saved-host read failures preserve the last known successful host set, or unknown state at startup, and leave retained shortcuts untouched until a successful retry. Recovery reconciles the latest loaded rows before completing readiness and releasing waiting opens/lookups. Retries use cancellable delays, do not catch cancellation and stop after success; existing store logs contain only static operation/outcome codes.

## Testing strategy

Test first. Deterministic publisher probes cover host-colliding ids, rename identity, recency permutations and duplicate opens, four-target/platform caps, restart, initial empty/cache/disconnect/reconnect snapshots, archive/delete/unpair and no resurrection without a new open. Use the real fold and injected publisher seams. Intake tests cover malformed shortcut types, capture-before-transfer and obsolete generation refusal. Shared production-graph tests cover direct success, exact-host existing draft merge, delayed rows, stale fallback, cancellation/replacement and no replay, alongside existing picker and notification navigation tests.

A device-only SharingShortcutStoreFailureTest exercises real Keystore read failures and recovery because successful encrypted-host reads require Android Keystore. A device-only SharingShortcutsDeviceTest reads the real ShortcutManager state and manifest XML, checks normal dynamic publication, category, MIME data, label, resource icon, no static/pinned shortcut and stored launcher intent activation. Real OS shortcut publication is the reason for device execution. Run that focused class and retain XML executed counts.

Add the named rung-3 direct-share test with text, PNG and document using the published id. Prove no picker or send before Send, both byte digests and text in X, and no message in Y; preserve the ordinary share scenario. Add a deterministic staging twin where the existing scripted stream harness can hold state before Send. Dispatcher owns full live acceptance with fresh executed/failed/skipped counts and confirmation the new method ran and passed; pending live results are not a builder pass.

Focused unit/shared tests, lint, assembleDebug, Android-test compilation, formatting, then final whole unit/shared suite, assembleDebug and scripts/pre-verify.py --gradle after merging main and pushing.

## Open Questions

None. Use non-long-lived dynamic shortcuts; remove cached copies defensively on eviction/removal.

## Security review

**Verdict:** PASS

- [Trust boundaries] MUST FIX addressed in design: exported EXTRA_SHORTCUT_ID is strictly typed and bounded, resolved only through the retained ledger; host and conversation each use NotificationTap bounds. No parsing of caller-controlled ids into routing segments.
- [Tokens] No credentials enter shortcuts or ledger. Existing pairing stays Keystore wrapped.
- [Files] AtomicFile under noBackupFilesDir contains only bounded target metadata, with no caller-authored filenames or shared bytes. Existing private-copy capture and cleanup remain authoritative.
- [Android attack surface] Stored explicit launcher intent uses validated pair extras; every launch rechecks saved host and active row. No pin request, static shortcut or additional exported component.
- [Cryptography] SHA-256 is identity hashing, not authentication. Noise and key lifecycle are unchanged.
- [Network and I/O] No wire change. Readiness waits five seconds and cancellation prevents late transfers; no upload or send before Send.
- [Errors and logs] MUST FIX from re-review: `KeystorePairedServerStore.readSnapshot` distinguishes successful emptiness from classified IO, Keystore and invalid-data failures; failure outcomes carry static codes without original causes. Failed host reads cannot authorize deletion or rewrite the recent ledger. Static event/outcome codes and counts only. Never log names, ids, URIs, payloads, keys or shared content.
- [Concurrency] MUST FIX from re-review: confirmed unpair calls `SharingShortcuts.removeHost` in the awaited non-cancellable cleanup hook, under the same mutex as opens and projections. Revision conflation and immediate re-pair cannot retain or resurrect a removed target; background application startup always subscribes the publisher. All publisher writes serialize; generation checks surround synchronous draft transfer and precede navigation. Reconnect cannot resurrect removed targets from snapshots.
- [Navigation concurrency] Live-gate repair: saved-host IO reads complete before the explicit Main boundary in both exported navigation effects. MUST FIX from re-review: notification/launcher navigation also rechecks `conversations.snapshots.value.holdsActive(target)` in that final turn, so archive or deletion during the last host read rejects the target without opening a thread or changing drafts. Draft transfer, generation/route checks and navigation share a non-suspending Main turn; a cancelled lookup cannot reach that turn. No validation, readiness limit, secret handling or wire contract is relaxed.
- [Recovery] MUST FIX from second re-review: a failed first store read retries independently of pairing mutations, preserving unknown state until success. Recovery belongs to the publisher collector, with a one-second cancellable delay; a newer revision cancels obsolete recovery. No permanent initialization wait survives storage recovery, and disposal leaves no retry job.
- [Threat model] Relay delay or flood remains handled by existing transport; hostile names are bounded and rendered as labels; rooted-device secret theft retains existing Keystore protection. UI screenshot/accessibility exposure is existing platform visibility and shared content remains explicit Send only. Protocol prompt-injection and relay metadata residual risks are unchanged.

**Reviewer:** builder (self-review per builder/security-review.md)
**Date:** 2026-10-06

## Revisions

2026-10-06: Re-review finding 1 on `e1156b04` identified that notification/launcher routing retained its readiness snapshot across the second `isSavedHost` suspension. Recheck the current active row inside the final non-suspending Main turn, alongside saved-host, pending-share and route checks. `NotificationTapNavigationTest` holds that second lookup, publishes an authoritative archive or deletion through the production `HostConversationSource`, then releases it and asserts the list and both hosts' existing text/attachment drafts remain unchanged. In-flight #1672 removes an unrelated discussion route from `MainActivity`; the guard edit stays local.

2026-10-06: The live gate found notification navigation running on an IO worker after `isSavedHost`, corrupting a back-stack entry and crashing teardown during the following rename scenario. The base branch passed both named methods. A production-graph probe with a suspending IO host lookup reproduced the lifecycle failure; direct-share coverage also exposed its final host lookup inside the earlier Main boundary. Both effects now finish suspending validation before entering Main for their UI checks and navigation. `NotificationTapNavigationTest` asserts the actual destination callback runs on the main Looper, exact-host arguments survive and direct content transfers once. #1832 overlaps `MainActivity` only in an unrelated thread callback; edits stay local.

2026-10-06: Device compilation established that public ShortcutInfo omits icon access. The device probe reads the stored icon through LauncherApps using temporary shell shortcut-read permission and compares rendered pixels with the launcher resource. Publisher initialization now gates open and lookup until the first saved-host read, and skips unchanged snapshot projections to avoid artificial usage/rate-limit churn. Added the `direct-share` scripted scenario using the existing ping fixture as the deterministic pre-Send twin.

2026-10-06: A focused production-graph test resumed the Compose test continuation on the publisher IO thread after lookup. Final validation, generation-checked draft transfer and navigation now explicitly enter Main.immediate. Lookup waits for initialization and then reads the bounded in-memory ledger under its mutex, avoiding an unnecessary IO hop. This preserves the synchronous transfer contract on both Robolectric and Android.

2026-10-06: Source inspection found `ConversationListProjection.upsertConversation` can emit a partial list before the first full snapshot. `ConversationListProjection.observeSnapshots` now exposes readiness that only a decoded full `conversations` frame establishes, including when its rows equal the preceding partial list. `RemoteConversationRepository.observeConversationSnapshots` passes the pair to HostConversationSource; other repositories keep their existing complete-list contract. The combine reads current rows after the ready edge so collector ordering cannot pair old partial rows with new readiness. The early-upsert/equal-full-list probe guards this distinction; no wire contract or ordinary repository read changes.

2026-10-06: The whole-suite repository-binding tests exposed that eager publication made the pure JVM selector require an Android context. The publisher remains an application-scoped singleton but initializes when PyryNavHost first resolves it, preserving startup reconciliation while leaving selector-only resolution Android-free. Existing ConversationRepositoryBindingTest cases guard this seam.

2026-10-06: Verifier findings 1–3 repair host lifecycle authority. Add `readSnapshot` alongside compatibility reads so failed production-store reads remain unknown; wire confirmed removal into the awaited non-cancellable hook, serialized with publication; initialize publication in the Android application composition root while preserving the lazy selector module. New probes exercise real Keystore startup/revision failures and recovery, remove/re-pair before observing revisions with cached-removal evidence, and background startup relabel/archive with no activity. #1830 overlaps `AppModule` only in unrelated thread dependencies; edits stay local.

2026-10-06: Second re-review finding 1 identified that dropping startup read failures left readiness pending until a pairing mutation. Add `sharingShortcutHosts` with latest-revision, one-second cancellable retries until an authoritative read succeeds. The real-Keystore probe now restores storage without changing the revision; deterministic probes verify retained metadata through failures, latest-row rename/archive reconciliation on recovery, waiting-open publication, shortcut resolution and cancellation on revision/disposal.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/navigation.md`, Incoming shares, records recent targets, Direct Share and launcher validation and loaded-list reconciliation. `docs/e2e-interactive-stream.md`, The ladder, records the named direct-share rung-3 method and the `direct-share` deterministic scenario. Documentation completion requires the dispatcher’s fresh full live executed/failed/skipped counts and confirmation that the new named method ran and passed.
