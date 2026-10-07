# #1866 — Suggested next reply and confirmed long-press submission

## Files read

- `ThreadInputBar.kt`: `ThreadInputBar`, text binding and Send/Stop precedence; the suggestion must never enter `TextFieldState` or undo history.
- `ThreadScreen.kt`: `ThreadScreen` composer mount and connection/attachment gates.
- `ThreadViewModel.kt`: `conversations`, `turnPhase`, `hostConnection`, `onDraftChange`, `sendMessage`, `sendWithAttachments` and the local-send window.
- `MainActivity.kt`: `PyryNavHost` binds the owning host's destination ViewModel to the screen.
- `ConversationRepository.kt`: `observeReplySuggestion` and `ReplySuggestion`, delivered by merged #1865 through the stable host facade.
- `ScriptedThreadHarness.kt`: real repository → ViewModel → screen fixture and envelope seam.
- `InteractiveStreamE2ETest.kt`, `DeterministicInteractiveStreamE2ETest.kt`, `scripts/e2e-emulator.sh`: live and scripted scenario selection.
- `docs/knowledge/features/thread-input-bar.md`: preserve heap-only field state, undo binding, 48dp target and typed Send/empty Stop precedence; a button's clickable surface must not steal the suggestion long press (#221).
- `docs/knowledge/features/thread-screen.md` and `thread-screen-composer-drafts-and-attachments.md`: host/conversation draft ownership and attachment snapshot safeguards.
- `docs/knowledge/features/development-verification-gates.md`: shared Compose tests run under Robolectric and must also compile into the device source set.
- `docs/e2e-interactive-stream.md`: rung-2, rung-3 and rung-4 harness ownership.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: `reply_suggestion` and Security model are the wire and threat-model sources of truth.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957

Inspected design context and screenshot on 2026-10-07: Input large uses body-medium text inside the existing field, with a 48dp trailing target and 28dp full-circle Send glyph. Preserve the existing local vector, layout and theme tokens. Suggestion text reuses `onSurfaceVariant` at 0.6 alpha; haptic confirmation creates no visual surface.

## Context

#1865 has merged the host-local, revisioned suggestion repository contract. This ticket adds the explicit user action that can promote inert daemon text to an ordinary user message. Typed text retains trimming, attachments retain their upload snapshot and failure behavior, and Stop retains its existing precedence. No new dependency or decision record is needed.

Overlaps: #1283-notice-placement touches separate screen status blocks; #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1817 and #1833 touch independent live methods or scenario-list entries. Keep changes additive and local.

Sizing: about 1200 written lines including plan, one exported offer type, two production consumers updated simultaneously, five acceptance criteria and at most ten grouped reject/cancellation branches. One independently checkable deliverable: safely presenting and explicitly sending the current suggestion.

## Design

Expose `suggestedReply: StateFlow<SuggestedReply?>` beside `draft`. `SuggestedReply` is an immutable, redacted, identity token carrying the repository reading and its exact text. It belongs to one ViewModel/host/conversation opening, so another destination's token cannot authorize submission even when its wire identities collide.

Observe the conversation's current session and switch `observeReplySuggestion` with `flatMapLatest`. A replacement clears the previous reading before observing the new session. Keep revision suppression per session for consumed, turn-invalidated and disconnect-invalidated readings. Returning to idle cannot resurrect a suppressed revision; a strictly newer valid set can. Null clears remove the offer. Draft edits revoke the token without consuming its revision, allowing erasure to reveal a fresh token for the same suggestion.

Pass the offer and `onSendSuggestedReply: (SuggestedReply) -> Boolean` through the destination and screen into the input bar. Display only when both the hoisted draft and field's actual text are exactly empty, the agent is idle and a nonblank current offer exists. Whitespace is typing. Never copy suggestion text into the draft, `TextFieldState` or saved state.

For the suggestion variant, use one pointer handler on the 48dp button surface, without an inner competing clickable. Wait for the platform long-press timeout, emit one `LongPress` haptic, then send only on release inside. Any out-of-bounds movement, cancellation, eligibility/token change or disposal cancels the gesture permanently. Re-entering cannot resurrect it. Other variants retain their existing `IconButton` tap behavior. Short suggestion taps are inert.

Expose a localized custom semantics action named “Send suggested reply” only when eligible. Explicit invocation uses the current token, gives haptic feedback and submits through the same ViewModel validation. Normal Send/Stop descriptions stay intact.

`sendSuggestedReply` checks token identity, current session/reading, exact-empty store draft, live connected state, idle state and attachment-send exclusion synchronously. Consume locally before launching any coroutine. Share ordinary submission plumbing while preserving typed trimming and guarded draft clearing; suggested text is passed verbatim and does not clear or populate a draft. Pending attachments use the existing upload/send path. Duplicate callbacks cannot send twice.

## State and concurrency model

All suggestion state changes and submission checks run on the ViewModel main dispatcher without suspension between authorization and local consumption. Eager collectors belong to `viewModelScope`, including turn phase and connection invalidation while the screen is not collecting. Session observation cancels its previous subscription. The pointer coroutine belongs to the input node and is cancelled on key replacement or destination disposal; field edits also invalidate the offer immediately.

Repository decoding, revision ordering and connection-lifetime resets remain owned by #1865. Background connection closure removes the visible offer through `hostConnection`. No disk cache or saveable state stores suggested text. Existing attachment IO, dispatcher injection and coroutine cancellation remain unchanged.

## Error handling

A cancelled or stale gesture is an inert no-op, with static structured lifecycle/rejection logs only. A refused suggestion send leaves its local affordance consumed, matching ordinary message failure semantics. Upload errors retain attachment entries and use existing notices. No new UI error, network verb or daemon-text logging is introduced.

## Testing strategy

Write the ViewModel regression first and observe the absent offer fail. Cover exact-empty/whitespace drafts, erase/reveal, verbatim send, duplicate token rejection, revision/clear/session/destination isolation, busy→idle and disconnect invalidation, delayed attachment upload blocking and typed trimming.

Shared Compose gesture tests exercise real pointer down/threshold/up, haptic count, short tap, outside/re-entry, cancellation/disposal, edits, state changes and named accessibility activation. Extend the real `ScriptedThreadHarness` wiring and drive set/clear/conversation/session isolation through the real decoder/repository to the screen. Run existing input style, binding, Enter-key, connection-gate and attachment tests.

Land `InteractiveStreamE2ETest.interactiveTurn_replySuggestion_longPressSends` and register it in the configured full live suite. Wait for a successful real turn and the actual daemon offer; pointer long-press/release it, assert exactly one matching user message and cleared placeholder. This is device-only because it uses a real daemon/Claude and transport. Dispatcher owns the fresh live run after verification; hand off its executed/failed/skipped counts explicitly.

Add a rung-4 deterministic native-suggestion fixture/scenario if the existing fakeclaude fixture seam supports it; run that scenario in the foreground. Compile Android tests, focused JVM checks, lint, assemble, format, push, final whole JVM suite, assemble and `scripts/pre-verify.py --gradle` after merging main.

## Open Questions

- Confirm the native prompt-suggestion fixture shape supports a rung-4 twin. Resolve in Revisions before implementation handoff.

## Security review

**Verdict:** PASS

- [Trust boundaries] `ReplySuggestion` stays bounded by #1865's decoder and is rendered by plain Compose `Text`. Only an explicit threshold/release or named accessibility action can call `sendSuggestedReply`; no automatic send, URL, markup or filename interpretation exists.
- [Tokens/secrets] No credential generation or storage changes. The offer is an in-process identity token, not an authentication credential, and its string representation redacts text.
- [Files/storage] Suggestions remain heap-only and never enter saveable field state or cache. Attachments use the existing reader and owned-copy boundaries; suggested text never selects a file.
- [Android attack surface] No exported component, deep link, provider, PendingIntent or WebView change. Accessibility is intentionally an explicit named send action under the same eligibility checks.
- [Cryptography] Existing Noise/TLS and Keystore boundaries are unchanged; no new randomness or key use.
- [Network/IO] Ordinary `sendMessage` and attachment uploads preserve encrypted transport, bounds and existing error outcomes. No new inbound decoder or verb.
- [Errors/logs] Log only static lifecycle and reject codes; never suggestion text, draft, decrypted bytes, credentials or attachment metadata.
- [Concurrency] Identity must be rechecked and consumed synchronously before any upload/send suspension. Pointer cancellation alone is insufficient: the ViewModel rejects stale tokens after draft, session, turn, clear and connection changes.
- [Threat model] Hostile daemon text remains inert until deliberate submission (prompt-injection residual risk is unchanged). A malicious relay can delay/drop but cannot authorize a stale token; disconnect invalidates it. Rooted-device token theft remains covered by existing Keystore storage. Suggestions are intentionally visible to screenshots/accessibility like conversation text, but never entered into the IME as a draft.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-07
