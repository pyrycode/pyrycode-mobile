# Message reply into the composer (#1818)

## Files read

- `MessageBubble.kt`: `MessageContainer` measures bubbles before the 13dp side column; `MessageActions` copies source independently of timestamp selection.
- `ThreadScreen.kt`: `ThreadScreen` binds delivered rows and the composer; queued and tool rows use separate rendering paths.
- `ThreadInputBar.kt`: `ThreadInputBar` owns heap-only `TextFieldState`, tracks asynchronous draft echoes and preserves user edits.
- `ThreadViewModel.kt`: `onDraftChange` invalidates suggested replies; `sendMessage` retains the existing trim and attachment path.
- `ComposerDraftStore.kt`: `draftFor` and `setDraft` address the constructor's exact server/conversation pair.
- `MainActivity.kt`: `PyryNavHost` binds the destination ViewModel callbacks.
- `MessageBubbleTest.kt`, `ThreadInputBarDraftBindingTest.kt`, `ThreadViewModelReplySuggestionTest.kt`: geometry, heap-only field and scoped ViewModel test patterns.
- `SideMessageCopy.kt`, `InteractiveStreamE2ETest.kt`, `DeterministicInteractiveStreamE2ETest.kt`: source-row matching and existing ping/held-stream scenarios.
- `docs/knowledge/features/message-bubble.md`: retain delivered bubble widths and separate visible geometry from touch geometry.
- `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md`: use the store rather than the derived draft flow for synchronous edits.
- `docs/knowledge/features/thread-input-bar.md`: programmatic edits bypass input transformation; never use saveable field state.
- `docs/e2e-interactive-stream.md`: rung-3 and rung-4 harness contracts.
- Sibling `pyrycode/docs/protocol-mobile.md`, Security model: existing encrypted boundary and residual prompt-injection risk; no protocol changes.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 and shared Message Actions https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=808-12242, inspected with design context and screenshot on 2026-10-07.

Copy (11×12dp) sits above the exported reply asset (13×12dp), with glyph centres 25dp apart. Centre the pair beside each bubble in the existing 13dp column and 12dp gap. Both glyphs follow main's copy treatment from #1889: `primary` tint with no backing, in both themes. That supersedes the ticket's inversePrimary tint and inverseSurface backing, which drew a white chip in dark theme. No pressed variant is drawn.

## Context

Reply stages editable plain text from a delivered user or assistant message, including streaming source at tap time. It never sends, changes attachment ownership or affects another destination. #1866 / PR #1873 is merged and its suggestion eligibility must remain intact. Remote overlaps #1766 and #1283 touch different rendering/status blocks; edits remain local and additive.

## Design

Add an inert-default reply callback through MessageBubble and ThreadScreen. ThreadViewModel exposes `replyToMessage(message): String?`: reject Tool, read the latest exact pair draft synchronously, append a separator newline only when needed, then role label, full unescaped quoted content and a final newline. Call onDraftChange and return the staged text. Formatting stays local to this method, with no reusable formatter or wire type.

ThreadScreen holds a nullable, heap-only pending reply text. A successful callback sets it; ThreadInputBar consumes it once after installing that exact text and moving selection to the end, requesting focus and opening the keyboard. A consumed callback clears the pending request. Existing mounts retain defaults. Three production callers require coordinated binding, below the ten-call limit; no new type or dependency is needed.

The drawn column remains bubble-height, including short bubbles. Its two explicit pointer/semantics targets share a single midpoint: each has width 48dp and height 36.5dp, with outer edges 24dp from glyph centres and adjoining edges 12.5dp from them. Place the larger touch layout outside the measured column without enlarging the row. Disable automatic vertical minimum-target expansion locally so the two targets cannot overlap. Use coordinate tests to prove routing at their shared edge and outer edges.

## State and concurrency model

All draft reads/writes and reply callbacks run synchronously on Main with no suspension. The immutable Message argument snapshots streaming source at tap time. DraftStore continues its existing CAS updates and eagerly derived flows. The field treats the pending reply as an outside edit, recording the pre-update hoisted draft as an echo to ignore until the flow catches up. Focus requests live only in the mounted screen, never a StateFlow or saved state, so reopening cannot replay them. Existing viewModelScope and connection lifecycle jobs are unchanged.

## Error handling

Tool reply is an inert null result and no UI control is drawn. No new network or I/O failure exists. Existing Send remains the only submission path, with trim, connectivity checks and pending attachment handling unchanged. Logs contain only static reply-stage and focus lifecycle events.

## Testing strategy

First observe a shared bubble test fail because reply is absent. Unit tests cover both roles, whitespace/newlines/quotes/empty source, repeated replies, >100000 characters, stale derived draft, exact pair isolation, attachment retention, tool rejection and explicit trimmed send. Shared Compose tests cover labelled controls, pair/target geometry at 320dp and 412dp, midpoint and outer pointer taps, streaming snapshots, asynchronous field adoption, end cursor, typing and one-shot focus through recomposition and remount. Run existing bubble, palette, timestamp, field draft/Enter/suggestion, frame and slash-command coverage.

Extend the existing live ping method for both roles, exact quote append, focus and real IME with no send. Extend the held-stream deterministic twin so later chunks cannot rewrite staged source. The IME checks are device-only because Robolectric cannot prove the actual keyboard/insets. Run focused device coverage and the stream scenario. The dispatcher owns the fresh full live suite, executed/failed/skipped counts and documentation evidence after verification.

Forecast: approximately 1000 written lines including tests and plan, at most two new exported symbols, three coordinated production callers, five acceptance criteria and one new reject branch. This remains one deliverable.

## Open Questions

None.

## Documentation handoff

Pending for the documentation stage:
- `docs/knowledge/features/message-bubble.md`: reply action, pair geometry and divided targets; both glyphs keep #1889's primary tint without backing.
- `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md`, Composer draft ownership: quote append and one-shot focus.
- `docs/e2e-interactive-stream.md`: extended ping and held-stream coverage, plus fresh dispatcher full-live evidence for the named ping method and executed/failed/skipped counts.

## Security review

**Verdict:** PASS

- [Trust boundaries] Full daemon-authored content enters only a plain TextFieldState via replyToMessage. Preserve source without escaping; no markup renderer, URL, file name, cache key or automatic send is introduced. The existing inbound frame bound stays intact; the explicit full-content requirement forbids reusing the clipboard truncation bound.
- [Tokens] No credentials are read, generated or transferred by this feature; existing Keystore and pairing controls are unchanged.
- [Files and storage] Draft and focus state remain heap-only, absent from SavedStateHandle, saveable fields, disk and backups. Attachments stay under their existing ownership and are not read when quoting.
- [Android attack surface] No new exported component, intent or provider. Accessibility deliberately exposes editable quoted message text just as ordinary drafts do.
- [Cryptography] No crypto changes; existing Noise_IK_25519_ChaChaPoly_BLAKE2s transport remains the boundary.
- [Network and I/O] Reply invokes no I/O. Explicit Send reuses existing authenticated routing and attachments, with outer-whitespace trim.
- [Errors, logs and telemetry] Static lifecycle events only; message/draft text, ids and attachment metadata never enter new logs or exception strings.
- [Concurrency] SHOULD FIX: never append from the lagging draft StateFlow. Read draftFor synchronously and return the installed text to the one-shot request. Tests stage edits and repeated replies without yielding to expose stale-flow mistakes.
- [Threat model] Relay drop/reorder and rooted token theft remain covered by unchanged transport/Keystore controls. Hostile source cannot execute in the plain field and remains editable before explicit Send. Protocol prompt-injection residual risk remains: quoting does not sanitize model instructions. Third-party IME/accessibility visibility matches ordinary drafts; no new token entry is added.

**Reviewer:** builder (self-review per builder/security-review.md)
**Date:** 2026-10-07

## Revisions

- 2026-10-07: fractional-density geometry probes exposed accumulated padding rounding. Place glyphs from each target's outer 24dp radius in pixels, preserving fractional translation, and divide the full 73dp target pair at one shared midpoint. The targets do not widen the drawn column.
- 2026-10-07: a remounted sole text field can inherit platform focus without a reply request. The remount test provides another focused target and also counts keyboard-show requests, distinguishing platform focus from replay of this feature's consumed request.
- 2026-10-07: the clipboard-limit fixture passed on unchanged main but exhausted heap on the reply branch. Its target-scrolling helper tried to bring the overflow above a short first row fully into view. Both side-action helpers now scroll the visual source row before pointer tapping the action; exact source and timestamp assertions stay intact. No inherited bug was filed or assertion ignored.
- 2026-10-07: full-list semantic visibility also includes space beneath the thread chrome. Shared pointer helpers move the glyph between the measured header and composer with a list swipe before tapping its actual centre, preserving exact clipboard/draft and timestamp assertions.
- 2026-10-07: pointer helpers now inject at the glyph centre in root coordinates, avoiding dependence on clipped target rectangles. The device held-stream scenario still fails on the short user copy before reaching reply, while the equivalent two-row shared probe passes. Further device evidence is required; this correction alone did not resolve the gate.
- 2026-10-07: merged main after #1889, #1902 and #1907. Both glyphs now use #1889's `primary` tint without backing. The scripted `stream` copy failure was the Top overlay's failed-MCP pill, which every scripted run shows under the header: with the keyboard open, the short user row's copy glyph sat beneath it, so the tap opened Channel info. The overlay now carries the `thread-top-overlay` tag, and the pointer helper clears it as well as the header.
