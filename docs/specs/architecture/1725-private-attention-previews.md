# #1725 — Private attention notification previews

## Files read

- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `launchAttention`, `alert`, `Held` and generation guards own host-qualified alert production and lifecycle.
- `app/src/main/java/de/pyryco/mobile/notifications/AttentionNotifier.kt`: `handle`, `post`, `AlertLedger`, title cleaning and confirmed-read suppression remain the posting contract.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `observeMessages`, `requestHistory`, `HistoryPage` provide existing read-only encrypted evidence.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `reduceHistoryPage` maps a page without merging or persisting it.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt`: `AssistantSegment` identifies a turn; parent attribution excludes child replies.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalUiState.kt` and `QuestionBatch.kt`: display fields and wire order supply prompt previews.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownConversions.kt`: existing `markdownPlainText` retains prose, labels and code without formatting.
- `app/src/test/java/de/pyryco/mobile/notifications/AttentionNotifierTest.kt` and `di/HostConversationSourceAttentionTest.kt`: notification privacy, gates, ledger and source harnesses.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` and `SecondClientPeer.kt`: existing one-turn push scenarios and peer-recorded evidence.
- `docs/knowledge/features/push-messaging-service.md`: ledger-before-gates and #1884 notification-lock/read-check lessons prevent re-alerts and late read races.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md` and `remote-conversation-repository-assistant-reply-segments.md`: generation identity and per-turn delta records prevent cross-host and previous-turn substitution.
- `docs/e2e-interactive-stream.md`: rung-3 push scenarios need the real relay; a scripted relay cannot deliver FCM.
- Sibling `pyrycode/docs/protocol-mobile.md`: history, turn and security contracts remain unchanged.

## Design source

Figma: N/A — native Android notification text only. Juhana explicitly waived frames in the 2026-10-07 decision on the issue. Android draws the standard surface; no app-screen or custom-layout change.

## Context

Fixed alert bodies hide whether the operator needs to open the conversation. Content must remain local to the encrypted daemon connection and the unlocked private notification. #1884 is merged and its cancellation contract is retained. No decision record is needed.

Overlapping branches #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1879 and #1888 edit other live methods in `InteractiveStreamE2ETest`; keep imports and the two push methods local and additive.

Sizing: one deliverable, five acceptance criteria, approximately 950 written lines including tests and plan, no new exported types, one updated production construction site; at most eight fallback/lifecycle branches. The preview producer and sole notifier consumer stay together under the sizing floor.

## Design

Add a defaulted optional suspending preview supplier to `AttentionAlert`. This keeps content ephemeral and avoids expanding repository interfaces or wire fields. Prompt suppliers capture only display fields: a permission title plus a nonblank cleaned prompt, or the first question in wire order. Other modal classes and blank prompt/question text supply no preview.

A completion supplier captures its exact host generation and repository. Read local messages once and select the last nonblank top-level assistant segment for the exact completed turn. Require settled rows and continuous per-turn delta evidence starting at zero; legacy rows without segment identity are insufficient. If missing/incomplete, ask once for the newest history page and reduce it independently with `reduceHistoryPage`. Require matching completion evidence and complete segment evidence; never merge that page, walk older pages or borrow another turn/child reply. Failures return no preview.

The notifier's sequential collector records the ledger and applies gates before launching independent enrichment jobs. It keeps no lock during a lookup. A per-conversation sequence prevents a slow earlier alert replacing a newer eligible alert. Before posting, recheck posting gates and #1884's authoritative completion coverage under the notification mutex. Immediate alerts can post while another lookup waits.

`notificationPreview` reuses the markdown plain-text conversion, normalizes all Unicode whitespace runs, removes remaining controls, trims and limits the final string to 200 code points including an ellipsis on truncation. Rendering is `setContentText` only. The private notification has `VISIBILITY_PRIVATE`; a separately built public version has the same sanitized title and only agent-specific fixed copy, without private extras, style or content. Existing icon, tag, tap and completion checkpoint stay on the private notification.

## State and concurrency model

Existing application/host scopes remain owners. Each supplier's lookup runs as a child of the captured host job, with a repository-identity watcher that cancels it on replacement or disconnect. Await cancellation also cancels the child. Generation guards run before and after lookup so retired callbacks cannot post. The whole local/history enrichment has a three-second timeout. The notifier's dispose cancels its independent jobs; host removal/source disposal cancels source lookup children. No new ViewModel, UI state or event is needed. Existing injected dispatchers drive deterministic clocks.

## Error handling

Blank, missing, incomplete, failed and timed-out evidence uses fixed copy. Lifecycle cancellation propagates and posts nothing. Logs contain only static lifecycle/outcome codes, never content or exceptions carrying daemon text. The ledger still stores only digests; no preview storage or push payload is introduced.

## Testing strategy

Test first: notifier privacy/sanitization assertions and source selection/history/lifecycle tests fail before implementation, then pass. Unit tests cover formatting, labels/code, whitespace/control characters, Unicode truncation, blank sources, permission title/prompt, first-question order, unrelated modal fallback, exact host/conversation/turn/parent isolation, sequence gaps, one-page history recovery, failure/timeout and host/repository cancellation. Notification tests inspect built private/public objects, recursively inspect public extras for reply/command/path/question leakage, prove content-free logs/ledger, preserve all existing gates/dedupe/read races and prove other alerts proceed and newer alerts win.

Extend the two existing rung-3 push methods with peer-derived reply/action preview expectations and private/public redaction assertions; keep wake/tap/reconnect/post-time checks and one real turn per method. These remain device-only because they require FCM, process lifecycle and real notification/tap behavior. The dispatcher owns the fresh full live gate with executed/failed/skipped counts and confirmation both methods passed. No scripted push twin is possible because the loopback relay cannot deliver FCM; source and real built-notification tests provide deterministic twins without changing fixtures. Run focused unit classes, lint, assemble, Android-test compilation, formatting, then the whole unit/shared suite and `scripts/pre-verify.py --gradle` after the final main merge and push.

## Open Questions

None.

## Security review

**Verdict:** PASS

- [Trust boundaries] Preview text remains untrusted and passes `notificationPreview` before inert Android text rendering. Exact host/repository/conversation/turn identity and top-level attribution are required; no title/key guesses.
- [Tokens] No token/key collection, storage or rotation changes. Preview suppliers never enter error messages or credentials.
- [Files and storage] No preview-derived filename or file write. `AlertLedger` receives identity digests only, retaining its app-private atomic replacement and backup exclusion.
- [Android attack surface] MUST FIX addressed in design: build the public notification separately from fixed copy, with private visibility on the original, to avoid copying content-bearing extras/styles. Immutable tap navigation remains unchanged; FCM remains wake-only.
- [Cryptography] Reuse the paired Noise encrypted repository and existing transport; no new primitive, key or nonce handling.
- [Network and I/O] One newest history request within a three-second total bound; no retry/page loop or merge. Existing frame caps, TLS and reconnect policy remain intact.
- [Errors/logs] No preview, prompt, question, reply, command, path or daemon exception text in notification logs or ledger; tests inspect both.
- [Concurrency] MUST FIX addressed in design: independent jobs do not hold the notification mutex while fetching; host/repository retirement cancels enrichment; final generation/read/gate checks and per-conversation ordering reject stale posts.
- [Threat model] A malicious relay can delay/drop encrypted evidence only, producing bounded fixed fallback. Hostile daemon content is inert and bounded. Rooted-device token theft remains covered by existing Keystore stores. Unlocked notification/accessibility visibility is the explicitly requested product surface; lock-screen public content is redacted. No keyboard or overlay surface is added.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-08
