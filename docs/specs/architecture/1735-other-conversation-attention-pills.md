# Other-conversation attention pills (#1735)

## Files read

- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `attention`, `snapshots`, `alerts` are independent app-owned sources; alerts have no replay and already deduplicate completed turns.
- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `WaitingForAnswer` and `ConversationViewing` retain exact host identity.
- `app/src/main/java/de/pyryco/mobile/notifications/AttentionNotifier.kt`: reuse `isMuted`, `nameOf` and `notificationTitle`; do not touch the notifier ledger.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: `PyryNavHost`, `Routes.thread` and `openThread` own host-qualified navigation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: pass an optional attention content slot into the overlay without migrating existing callers.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt`: insert the attention slot first, preserving existing notices and their touch geometry.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`: reuse geometry with defaulted container/content colour arguments.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt`, `WarningColors.kt`, `SuccessColors.kt`, `Theme.kt`: existing warning/success text tokens and the static-dark palette convention.
- `app/src/main/res/values/strings.xml`: add local waiting/count/finished copy.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlayTest.kt`: preserve existing stacking, interaction and native graphics coverage.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: reuse `answerHostPeer`, `answerChat` and the held-permission setup from `interactiveTurn_attentionDot_followsARealTurn`.
- `scripts/e2e-emulator.sh`, `scripts/android-test-gate.py`: curated live selection and its executed/failed/skipped reporting.
- `docs/knowledge/features/thread-screen.md`, `thread-top-overlay.md`, `notice-pill.md`, `dependency-injection-host-conversation-source.md`, `navigation.md`, `development-verification-gates.md`: exact-host ownership, overlay spacing and Robolectric width/native graphics traps.
- `docs/e2e-interactive-stream.md`: source of truth for rung-3/rung-4 harness ownership.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: security model; no wire changes.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6618

Screen instances: `779:10468` (one waiting), `779:10680` (finished), `779:10892` (several waiting above usage). Inspected design context and screenshots: right-aligned, hug-width pills without X, bodySmall 12sp/16sp, 6dp corners, 8dp horizontal/4dp vertical padding and 12dp vertical gaps. Waiting uses container `#3D3215` and text `#D8B85A`; Finished uses container `#0F3313` and `Schemes/Success` `#2FC038`. Existing NoticePill supplies geometry and shadow; new theme extensions supply container tokens, with existing warning/success text tokens. Light/dynamic palettes use tonal containers composed over the theme surface.

## Context

Foreground system-notification suppression leaves other conversations' waiting and completion signals invisible inside a thread. Add one foreground attention surface, independent of the notification ledger. No model, repository, protocol or command contract changes and no decision record are needed.

Overlaps: #1642, #1682, #1689, #1690, #1691, #1693, #1695, #1703, #1747, #1753 and #1775 touch shared screen, activity or live-test files. Their work is separate actions/scenarios or notices after the attention slot; edits here remain additive and local.

Sizing: one deliverable, five acceptance criteria, approximately 1100 written lines including plan and tests, no new exported contracts, fewer than five new internal declarations/types/composables, three production consumer updates, and fewer than ten filter/timer branches. The #1345 analogue wrote 625 app lines plus its plan; the extra tests here cover lifecycle/timer races.

## Design

Add internal `ThreadAttention` presentation state and `observeThreadAttention` beside the thread UI. Waiting projects every non-current host/conversation pair whose attention is WaitingForAnswer and whose snapshot does not mark it muted; snapshot/attention updates recompute count, target and name. Unknown names fall back through `notificationTitle` to the app-name resource. A single target remains a typed `HostConversationTarget`; multiple waiting conversations carry no thread target.

Only TurnCompleted alerts from other unmuted pairs can set Finished. New finishes replace rather than enqueue. Waiting clears the finish and cancels its timer; finishes while waiting are discarded. Muting the finish clears it. A snapshot rename updates its label without extending expiry.

An optional composable slot passes through ThreadScreen into ThreadTopOverlay as the first child. `ThreadAttentionNotice` uses NoticePill, the theme tokens, two-line ellipsis and no dismiss callback. MainActivity binds its click to the exact target's existing thread route or the channel list. Names never participate in navigation. Defaulted parameters preserve every unrelated caller.

## State and concurrency model

The destination owns a produceState collector inside repeatOnLifecycle(RESUMED), using its back-stack entry lifecycle. No ViewModel or app-owned timer is added. This cancels collectors and the five-second delay when backgrounded or covered by another destination, including another thread. Cancellation clears presentation state; resuming starts with current Waiting and no Finished.

The cold flow uses child collectors and one replaceable delay job in its caller's coroutine context. Production runs on Compose's main context; tests inject a test dispatcher by collecting in runTest. Each callback reads current StateFlow values, so an alert cannot race a scheduled snapshot/attention projection into showing a finish while Waiting already exists. No suspension occurs between eligibility, state mutation and publishing. Timer expiry reprojects current waiting state. Parent cancellation cancels every child.

## Error handling

This is an in-memory projection over established decoded streams, with no I/O or new failures. Missing names use the app name; missing mute metadata follows the existing notification policy (unknown is unmuted). Existing host availability/navigation guards remain in force. Content-free debug events identify collector lifecycle, waiting counts and finish shown/suppressed/cleared/expired; no names, identifiers, alert keys, message contents or credentials are logged.

## Testing strategy

Write and run failing tests before implementation. Unit tests use controlled StateFlows, a non-replayed SharedFlow and the coroutine test clock to prove aggregation, current-pair exclusion, colliding ids, mute/name changes, Prompt/Unread rejection, exact expiry, replacement, stale timer cancellation, waiting precedence and no replay after collection cancellation.

Shared Compose tests use native graphics for colour/measurement assertions and the documented default 320dp width. Prove one/two-line labels, inert hostile/control/emoji names, app-name fallback, no X, hug width, theme colours, first stacking above every existing notice, and real pointer action routing. A navigation/lifecycle render harness uses production Routes and the destination lifecycle to prove host-qualified single targets, list targets and cancellation when another thread covers the old entry or the app backgrounds.

Run new tests plus ThreadTopOverlayTest and existing affected re-pair/layout coverage. Run lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck. The rung-3 method `InteractiveStreamE2ETest.interactiveTurn_otherConversationAttentionPills_waitingAndFinished` uses real daemon/relay/Claude (device-only reason), waits in B while A is shown, taps to B, backs to A without answering, then lets the peer answer and verifies Finished and expiry. Add it to the curated live list. The current rung-4 fixtures do not provide the answer-host peer or a second conversation held on permission; the answer-host setup is live-only. Controlled unit/render tests hold all presentation states without live timing instead; run the existing scripted stream scenario for regression coverage. The dispatcher owns live credentials and execution; hand off the exact method and full-gate executed/failed/skipped evidence requirement.

## Open Questions

None. Existing source and title policy determine identities, mute fallback and sanitisation.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/thread-top-overlay.md` (shape/order/lifetime), `docs/knowledge/features/notice-pill.md` (colour customization), `docs/knowledge/features/navigation.md` (attention targets), and `docs/e2e-interactive-stream.md` (rung-3 scenario). Before documentation, dispatcher-owned full live gate must report executed, failed and skipped counts and confirm the named new method ran and passed.

## Security review

**Verdict:** PASS

- [Trust boundaries] Host-authored names cross only `notificationTitle` before plain Compose Text: controls removed, 80 code points retained without splitting pairs, trim and local app-name fallback. Two-line rendering bounds geometry. Host/id remain opaque typed equality/routing keys and never derive from names.
- [Tokens] No credentials enter the presentation projection or its logs; the source snapshots contain no pairing records.
- [Files/storage] No new disk writes, filenames, cache keys or saved state. Finished is heap-only and forgotten on cancellation/process death.
- [Android attack surface] Only existing internal navigation routes are used, with existing encoded host-qualified arguments. No exported component, intent, deep link, PendingIntent, provider or WebView is introduced. Taps call navigation only and cannot submit permissions or commands.
- [Cryptography] No cryptographic operations change; the established Noise session continues to authenticate inbound frames.
- [Network/I/O] No wire verbs, connections or frame-size changes; source bounds and established reconnect behavior remain intact.
- [Errors/logs] Only static lifecycle/reason codes and counts are logged. No daemon text, host/conversation ids, alert keys, payloads, tokens or exception strings.
- [Concurrency] SHOULD FIX addressed by the design: a back-stack composition can outlive visibility. Entry lifecycle RESUMED scopes collection and cancels the timer; state is cleared on every exit. Reading current waiting/mute values in each callback prevents pending emissions from accepting an ineligible finish.
- [Threat model] Malicious relay delay/drop cannot route across hosts or leak plaintext; token theft mitigation remains existing Keystore ownership. Hostile daemon names are bounded inert text. Accessibility/screenshot exposure is limited to the requested bounded name, as on the list; no credential is exposed. Protocol security model remains the sibling document cited above.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-05

## Revisions

### 2026-10-05 — PR #1780 touch-target review

The verifier found that `ThreadAttentionNotice` inherited the overlay's 36dp vertical hit-test minimum. Its Waiting, Finished and count variants now give an inert `NoticePill` a separate clickable wrapper with 24dp of invisible space above it. The wrapper reports only the visible height to the stack and places its extra touch area upward into the existing top clearance. A 24dp surface minimum matches the Figma instance and prevents native font metrics from shortening the one-line pill; it therefore has a 48dp target, while its bodySmall typography, hug width, colours and 12dp gap below stay unchanged. Longer labels grow both the surface and target. The target ends at the visible pill's bottom, before the next notice's dismiss target, rather than expanding toward that action.

Native-graphics render tests for all three short-label variants assert actual touch bounds, the unchanged 24dp surface and 12dp gap, pointer routing at both target boundaries, and the usage dismiss target above its X glyph, inside the existing Surface clip. Existing colour and geometry assertions now query the distinct visible surface; the merged clickable node retains its existing tag and navigation semantics for live coverage. The Figma count and Finished instances were fetched again for this rework. Security review remains PASS: the wrapper only calls the existing typed navigation callback, retains bounded inert text and one accessible button, and adds no state, I/O, logging or exported surface.

Documentation handoff remains pending; include the separate visible-surface and upward-expanded touch-target contract in `docs/knowledge/features/thread-top-overlay.md` and `docs/knowledge/features/notice-pill.md`.
