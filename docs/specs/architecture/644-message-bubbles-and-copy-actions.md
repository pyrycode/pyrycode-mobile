# 644 — Match mobile message bubbles and copy actions

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → `MessageBubble`, `UserMessageBubble`, `AssistantMessage`, `StreamingAssistantBody`, `StreamingAssistantBodyView` and the file-private geometry constants — the surface this ticket rewrites. The streaming pair is carried over untouched; only its container changes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt` → `SessionBoundaryDelimiter`, `SessionBoundaryDelimiterContent`, `boundaryLabel`, `formatShortTime`, `MEMORY_PLUGIN_DOCS_URL` — the delimiter restyled here, and the source of the time half of the new meta-row formatter (`internal`, same package, so importable without moving it).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/QueuedBacklog.kt` → `QueuedBacklog`, `QueuedMessageRow` and its copied `QueuedBubbleShape` / `QueuedBubbleMaxWidth` / `BubbleHorizontalPadding` / `BubbleVerticalPadding` — the duplicated bubble constants the ticket asks to keep in the same family. Its own comment already names the duplication as a workaround for the originals being file-private.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoSheet.kt` → `Footer` — the shipped clipboard idiom (`LocalClipboardManager` + `AnnotatedString`) the copy control follows.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the `LazyColumn` body: its `itemsIndexed` key lambda (`"msg:${item.message.id}"`), the `ABOVE_DELIMITER_ALPHA` dimming wrapper, and the absence of any horizontal padding on the list. That last point is why the 20dp content gutter is applied component-side here (see Design).
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` → `Message` — confirms `timestamp: Instant` and `content: String` are already carried per row; no data-layer change is needed.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt`, `.../Color.kt` → `darkScheme`, `lightScheme` and the `*Dark` / `*Light` palette values. Decisive for the token mapping: the design's hexes are this app's dark scheme verbatim, **except** `Schemes/on-primary-fixed`, because the scheme builders never set the M3 "fixed" roles — reading `colorScheme.onPrimaryFixed` would return the baseline-purple default, not this palette.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiterTest.kt` → the five existing assertions (explanation, the three label prefixes, the Install tap). The restyle must keep all five green; they pin prefixes, never formatted times.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedSessionBoundaryTest.kt` → `sessionBoundary_drawsDelimiterBetweenTwoSessionsMessages` — reads tight text rects through `useUnmergedTree = true`. A `Surface` + `Column` wrapper adds no semantics node, so the leaf text nodes it addresses survive; the constraint this imposes is "no `clearAndSetSemantics` and no merged `contentDescription` over the bubble body".
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadRenderTest.kt` → `STREAMING_CARET = "▎"` and the caret-absent-after-`turn_end` assertion; the streaming path must keep producing exactly that glyph inside the new container.
- `app/src/main/res/values/strings.xml` → the `cd_thread_*` family this ticket extends by one.
- `docs/knowledge/features/message-bubble.md` § "Assistant variant — flat, deliberately diverges from Figma", § "Ignored `Message` fields", § "Edge cases / limitations" — the three places recording the state this ticket ends, and the escalation note (`needs-rework:po`) that the ticket body explicitly overrides.
- `docs/specs/architecture/643-apply-the-mobile-chat-header-and-composer-layout.md` → § "`ThreadTopAppBar` — hand-rolled bar plus rule". The precedent this plan follows for design tokens whose literal M3 role only works against the dark reference frame: map to the role that plays the same part in both schemes and record the divergence. Its rule mapping (`Schemes/inverse-primary` at 60% → `outlineVariant` at 0.60 alpha) is reused verbatim for the boundary's rules.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Within `16:8`, the `Message area` (`533:1956`) is a 16dp-gap column of full-width role containers. An assistant container insets its trailing edge by 100dp and left-aligns; a user container insets its leading edge by 100dp and right-aligns — so inside the frame's 372dp content area a bubble is at most 272dp. Both roles use the same `Message` component: a 6dp-cornered filled box with 20dp horizontal / 16dp vertical padding and a 12dp gap between its children, the last of which is a `Meta row` (`132:4446`) carrying a `M3/body/small` timestamp (`13.01.2026 - 13:55`) and an 11×12 copy glyph 8dp apart, aligned to that bubble's own side. The `Session reset` row (`119:3843`) is a 12dp-gap centred row of hairline rule / `M3/body/small` label / hairline rule.

## Context

`MessageBubble` still renders the assistant as unboxed text on the screen surface and the user as the pre-#643 20/20/6/20 `primaryContainer` bubble. `message-bubble.md` records the flat assistant as a deliberate, unresolved divergence: #128's AC overrode the Figma frame, and the note defers "Figma reconciliation" to a downstream design call with `needs-rework:po` as the escalation path. This ticket is that call and it resolves in favour of the design — both roles box. The divergence closes; nothing is escalated.

Beyond that, the ticket adds the first per-message timestamp and the first copy affordance on this surface, both of which `message-bubble.md` currently lists as absent by design.

No ADR is warranted: this is one component family's visual treatment plus a reusable control, not a new architectural rule. The documentation stage folds the token-mapping table below into `message-bubble.md`.

### Token mapping — the design's roles against this app's two schemes

The supplied adaptation is drawn against this app's dark palette: `Schemes/on-primary` = `#003355` = `onPrimaryDark`, `Schemes/on-primary-container` = `#CFE4FF` = `onPrimaryContainerDark`, `Schemes/on-secondary-container` = `#D6E4F7` = `onSecondaryContainerDark`, `Schemes/inverse-primary` = `#32628D` = `inversePrimaryDark`, `Schemes/primary` = `#9DCBFC` = `primaryDark`. Reading those roles literally reproduces the frame exactly in dark and breaks in light — a bubble filled with `onPrimary` is white in light, and meta text in `inversePrimary` is a pale blue on it. `Schemes/on-primary-fixed` is worse than that: `darkColorScheme(...)` / `lightColorScheme(...)` in `Theme.kt` never set the M3 fixed roles, so it resolves to the baseline default rather than to any colour in this palette.

The designer's *text* picks are the reliable signal, because each names one half of a canonical M3 container pair. This plan takes the named text role and pairs it with its own container — which reproduces the frame in dark and stays contrast-correct in light, the same trade #643 made for the header rule.

| Figma | Design hex | Used here | Why |
|---|---|---|---|
| Assistant fill `Schemes/on-primary-fixed` | `#001D34` | `colorScheme.secondaryContainer` | **Divergence.** The fixed roles are unset in this app's schemes. `secondaryContainer` is the canonical partner of the assistant body's own `onSecondaryContainer` and keeps the assistant bubble distinct from the user's primary-tinted one in both schemes. |
| Assistant body `Schemes/on-secondary-container` | `#D6E4F7` | `colorScheme.onSecondaryContainer` | As named. |
| User fill `Schemes/on-primary` | `#003355` | `colorScheme.primaryContainer` | **Divergence.** Canonical partner of the user body's own `onPrimaryContainer`; also the fill the shipped user bubble and `QueuedBacklog` already paint, so the queued row stays in family for free. |
| User body `Schemes/on-primary-container` | `#CFE4FF` | `colorScheme.onPrimaryContainer` | As named. |
| Meta row text + copy glyph `Schemes/inverse-primary` | `#32628D` | `LocalContentColor.current.copy(alpha = MetaContentAlpha)` | **Divergence.** M3 has no de-emphasis role *inside* a filled container. Taking the host bubble's own content colour at a fixed alpha yields one expression that de-emphasises correctly in both bubbles and both schemes. |
| Boundary rules `Schemes/inverse-primary` @ 60% | `#32628D` | `colorScheme.outlineVariant` at 0.60 alpha | **Divergence**, identical to #643's header rule — the same design token, the same M3 divider role, the same alpha. |
| Boundary label `Schemes/primary` | `#9DCBFC` | `colorScheme.primary` | As named; correct in both schemes. |

## Design

Four production Kotlin files (one new), one string, one drawable. No new public type, no ViewModel / repository / wire change, no change to `ThreadScreen`.

### New — `MessageMetaRow.kt` (`ui/conversations/components/`)

Three `internal` declarations, reachable from the package so #657 can mount the copy control beside a code block without parsing rendered text back out of the UI:

```kotlin
internal fun formatShortDateTime(instant: Instant, timeZone: TimeZone, locale: Locale): String
```
The localized short *date* joined to the existing `formatShortTime` by the design's ` - ` separator. Both halves come from `DateTimeFormatter.ofLocalized*(FormatStyle.SHORT)`, so neither carries a hardcoded pattern; the separator and the date-then-time order are the design's and are fixed here rather than delegated to `ofLocalizedDateTime`, which would let a locale reorder them.

```kotlin
@Composable
internal fun CopyTextControl(text: String, contentDescription: String, modifier: Modifier = Modifier)
```
A `Box` with `Modifier.clickable(role = Role.Button)` around the design's 11×12 glyph, tinted from `LocalContentColor` so it inherits the host bubble's de-emphasis. On tap it writes `AnnotatedString(text.take(MAX_CLIPBOARD_CHARS))` to `LocalClipboardManager.current` — one write of one caller-supplied string, so nothing else can reach the clipboard on that tap. Takes no `Message` and reads nothing from the composition tree: the text to copy is the caller's argument.

```kotlin
@Composable
internal fun MessageMetaRow(timestamp: Instant, copyText: String, alignment: Alignment.Horizontal, modifier: Modifier = Modifier)
```
`Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MetaRowSpacing, alignment))` of the formatted timestamp (`typography.bodySmall`) and the copy control. The `alignment` argument is the only difference between the assistant and user instances — `Alignment.Start` and `Alignment.End` — matching the design's `items-start` / `justify-end`.

**Touch target — recorded deviation.** The design draws the glyph at 11×12 with no padding, which is a 12dp tap target. The control adds `CopyTouchPadding = 6.dp` inside the clickable for a 24dp target, growing the meta row from 16dp to 24dp. 24dp is still under Material's 48dp guidance; a full `IconButton` would inflate every bubble by ~32dp and visibly miss the frame. The deviation is toward accessibility, is the same on both roles, and is noted in the PR body.

### `MessageBubble.kt` — both roles box on the shared `Message` component

`MessageBubble`'s signature, its `when (message.role)` dispatch, the `Role.Tool` `?.let` arm, and `StreamingAssistantBody` / `StreamingAssistantBodyView` are all unchanged. Only the two role containers are rewritten, both to the same shape:

- Outer `Row(Modifier.fillMaxWidth())` carrying the 20dp content gutter on both edges plus `MessageRoleInset = 100.dp` on the role's opposite edge, `horizontalArrangement` `Start` (assistant) or `End` (user), and `padding(bottom = MessageRowVerticalSpacing = 16.dp)` for the design's inter-row gap. The gutter lives on the component because `ThreadScreen`'s `LazyColumn` applies none and this ticket does not touch it; at the 412dp reference width it yields the design's 372dp content area, and the 100dp inset then caps a bubble at 272dp. The inset is the mechanism, 272dp is its value at the reference width — which is why no separate max-width constant exists to drift from it.
- `Surface(shape = BubbleShape, color = …, contentColor = …)` per the mapping table. Supplying `contentColor` is what replaces the assistant arm's current `CompositionLocalProvider(LocalContentColor provides onSurface)`: `MarkdownText` keeps taking its colour from the ambient, per its documented contract, and the meta row reads the same ambient for its de-emphasis.
- Inner `Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp))` holding the body and then `MessageMetaRow(message.timestamp, message.content, alignment)`.

Bodies keep their current renderers exactly: the user arm a plain `Text(message.content, style = bodyMedium)`, the assistant arm the existing `if (message.isStreaming) StreamingAssistantBody(...) else MarkdownText(...)` pair at `Modifier.fillMaxWidth()`.

The geometry constants become `internal` so `QueuedBacklog` can consume rather than copy them: `BubbleShape` (`RoundedCornerShape(6.dp)`), `BubbleHorizontalPadding` (20dp), `BubbleVerticalPadding` (16dp), `BubbleContentSpacing` (12dp), `MessageContentGutter` (20dp), `MessageRoleInset` (100dp). `UserBubbleShape` and `UserBubbleMaxWidth` are deleted — the asymmetric 20/20/6/20 tail and the 320dp cap are both superseded.

Previews gain the light/dark user-plus-assistant pair rendered through the new containers, and one 320dp narrow preview so the meta row's behaviour at the narrow width is reviewable next to the delimiter's existing narrow preview.

### `SessionBoundaryDelimiter.kt` — rule / label / rule, explanation retained

`SessionBoundaryDelimiter`, `SessionBoundaryDelimiterContent`, `boundaryLabel`, `formatShortTime` and `MEMORY_PLUGIN_DOCS_URL` all keep their signatures. Inside the content composable the single full-width `HorizontalDivider` + centred label becomes the design's `Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically)` of a `weight(1f)` rule, the label in `typography.bodySmall` / `colorScheme.primary`, and a second `weight(1f)` rule. A rule is a `Box(Modifier.height(1.dp).background(outlineVariant.copy(alpha = 0.60f)))`.

The label takes no weight, so `Row` measures it first against the full available width and the rules split what is left. A long `Workspace changed to …` label therefore wraps (centred) and squeezes the rules toward zero instead of pushing anything past the viewport edge — the degradation AC #5 asks for at 320dp. The explanatory `FlowRow` and its `Install` `TextButton` are untouched below it, per `CLAUDE.md` and `session-boundary-delimiter.md`; the design draws neither and both stay.

The delimiter picks up the same `MessageContentGutter` and the same 16dp bottom spacing as a message row so the three row kinds share one rhythm.

### `QueuedBacklog.kt` — consume the bubble constants instead of copying them

`QueuedBubbleShape`, `QueuedBubbleMaxWidth` and the two local `Bubble*Padding` copies are deleted; `QueuedMessageRow` reads `BubbleShape`, `BubbleHorizontalPadding` and `BubbleVerticalPadding` from `MessageBubble.kt` (same package, no import). `BacklogHorizontalPadding` becomes `MessageContentGutter` so the queued rows sit on the same gutter as the bubbles above them. The row keeps its leading `Schedule` glyph, its `QUEUED_ALPHA` de-emphasis and its trailing drop `IconButton`; it does **not** take the 100dp role inset, because those two affordances already consume ~72dp of the row and stacking the inset on top would squeeze the bubble well below the sent one. No meta row and no copy control: a queued message has not been sent and has no server timestamp to show.

### `strings.xml`, `res/drawable/ic_copy.xml`

One string, `cd_thread_copy_message`, in the `cd_thread_*` family. One vector drawable transcribed from the design's `copy-solid-full` asset (single path, 11×12 viewport, `@android:color/white` fill tinted at the call site — the idiom `ic_open_in_new.xml` already uses).

## State + concurrency model

No new state and no new coroutines. `MessageBubble` stays pure rendering; `MessageMetaRow` and `CopyTextControl` hold no `remember`, no `LaunchedEffect` and no scope — the clipboard write is a synchronous call inside an `onClick` lambda on the composition's own thread. `StreamingAssistantBody`'s two `produceState` producers are carried over verbatim, including their disposal-cancellation behaviour, and still key on `content` — so the reveal restarts per delta exactly as today, now inside a `Surface`.

Recomposition: `Message` remains a stable `data class`, so a bubble recomposes only when its own message changes. The meta row's inputs are an `Instant` and a `String` off that same message; the copy control's `onClick` captures that `String`, so the lambda's identity changes only when the text does — which is correct, since a stale capture would copy stale text.

The row's list identity is `ThreadScreen`'s `"msg:${item.message.id}"` key, which this ticket does not touch and which does not read content — so a growing streaming message keeps its identity (AC #4).

## Error handling

No new I/O and no new failure mode. The single new side effect is the clipboard write, whose one realistic failure is an oversized `ClipData` exceeding the Binder transaction limit; the design prevents it by construction with `MAX_CLIPBOARD_CHARS = 100_000` characters rather than catching afterwards (see Security review, finding 1). No new user-visible error surface, no toast, no banner — a successful copy is silent, as `ChannelInfoSheet`'s footer already is.

Daemon-authored text reaches no new markup, URL, filename, cache-key or log sink: `Message.content` continues to render through `Text` / `MarkdownText`, and the clipboard receives it as a bounded plain `AnnotatedString` with no annotations or styling attached.

## Testing strategy

One new unit test file and one new instrumented file; the two suites the ticket names are re-run rather than relaxed.

**Unit — `app/src/test/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRowFormatTest.kt`** (`formatShortDateTime`, locale-robust the way the delimiter's tests are):

- The result contains the localized short date and the localized short time for a fixed `Locale` / `TimeZone`, each computed in the test through the same `DateTimeFormatter.ofLocalized*` API rather than as a literal — so the assertion pins composition and order, not a pattern.
- The date precedes the time and the two are joined by the design's separator.
- The result differs across two locales with different short-date conventions, and never equals the design's `13.01.2026 - 13:55` sample under a locale that does not produce it.

**Instrumented — `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/MessageBubbleTest.kt`**, the rung-2 component-render layer, run focused on the managed API 33 device. A file-local fake `ClipboardManager` provided through `LocalClipboardManager` captures writes:

- `bothRoles_renderBodyAndMetaRow` — a user and an assistant message mount; each body text is displayed and each bubble carries one copy control. Pins AC #1's "both render as bubbles" and AC #2's "each bubble ends with a meta row".
- `roleAlignment_userSitsRightOfAssistant_andBothClearTheRoleInset` — read both body text rects; the user body starts to the right of the assistant body, and each body's far edge clears the opposite root edge by at least `MessageRoleInset`. Pins AC #1's alignment and the 100dp/272dp geometry without a test tag.
- `copy_putsOnlyThatMessagesTextOnTheClipboard` — with both messages mounted, tapping the assistant's copy control captures exactly `assistant.content`; the captured text neither contains nor equals the user's content, and exactly one write occurred. Pins AC #3 end-to-end.
- `copy_onStreamingMessage_yieldsWhatHasArrived` — a message with `isStreaming = true`, driven with `mainClock.autoAdvance = false` so the caret's unbounded blink loop cannot stall the idling check; advance far enough to reveal content, assert the `▎` caret is present inside the bubble, then tap copy and assert the captured text is the full `message.content`, not the revealed prefix. Pins AC #4's "reveal and caret keep working inside the new container" and "copy yields what has arrived".
- `copyControl_carriesItsAccessibleName` — the control is addressable by `cd_thread_copy_message` and is a `Role.Button`. Pins AC #3's accessible-name clause.

**Re-run, kept green, not relaxed:** `SessionBoundaryDelimiterTest` (all five — the explanation, the three label prefixes, the Install tap survive the restyle: AC #5's "reason label, explanatory line and Install affordance all survive"), `ScriptedSessionBoundaryTest` (the unmerged-tree rect ordering across the restyle and the new bubble container: AC #5's chronological position), `ScriptedThreadRenderTest` (the caret glyph and the finalized-text path through the new container), `QueuedBacklogTest` (the mirrored constants).

`@Preview` coverage is the visual check against the Figma screenshot: the light/dark bubble pair, plus a 320dp narrow preview alongside the delimiter's existing one for AC #5's narrow-width clause.

No rung-3 scenario: this ticket ships no new operator-facing flow — it restyles rows the existing `stream` scenario already drives, and the ticket assigns live proof of this surface to #679.

## Open questions

- Whether `Row`'s measure order genuinely leaves the unweighted boundary label its full width before the weighted rules claim any (the basis for the long-label degradation above). Resolve on the device run of `SessionBoundaryDelimiterTest`; if it does not hold, the fallback is `Modifier.weight(1f, fill = false)` on the label and the change is recorded under Revisions.
- Whether `mainClock.autoAdvance = false` is sufficient to keep the streaming copy test from stalling on the caret's `while (true)` producer. If it proves flaky on device, the test narrows to the non-streaming copy proof and AC #4's streaming clause rests on `ScriptedThreadRenderTest` plus the pinned-snapshot preview; recorded under Revisions either way.

## Documentation handoff

Pending for the documentation stage — no doc file is edited by this ticket:

- `docs/knowledge/features/message-bubble.md` — § "Assistant variant — flat, deliberately diverges from Figma; markdown-rendered since #129" (the divergence is **closed in favour of the design**; both roles now box on the shared `Message` component, and the `needs-rework:po` escalation note retires), § "Ignored `Message` fields" (`timestamp` is now rendered in the meta row), and the two § "Edge cases / limitations" entries "No timestamps in-bubble" and "No long-press, no `SelectionContainer`, no copy affordance" (both describe state this ticket ends; the second's preferred future shape — a screen-level `SelectionContainer` — is superseded by the design's explicit meta-row control). The token-mapping table in this plan's Context is the record of what the design's roles became.
- `docs/knowledge/features/session-boundary-delimiter.md` — § "Shape" (rule / label / rule; the explanation and Install affordance retained below it).
- `docs/knowledge/features/queued-backlog.md` — where it describes the mirrored bubble constants (they are now consumed from `MessageBubble.kt` rather than copied).

## Security review

**Verdict:** PASS

**Findings:**

1. **[Trust boundaries] MUST FIX — addressed in the plan before commit.** The copy control is a genuinely new trust boundary: `Message.content` is daemon-authored for `Role.Assistant` and has never before left the process. It crosses into the system clipboard via a Binder transaction with a ~1MB ceiling, and there is **no length bound anywhere on the inbound path** — `MAX_PAIRING_FIELD_BYTES` in `PairingPayloadParser` is the only length check in `data/network/` and it covers pairing fields only. A hostile or buggy daemon inside the Noise session can accumulate an arbitrarily long `assistant_delta` run; `ClipboardManager.setText` on that string throws `TransactionTooLargeException`, an unchecked `RuntimeException`, and the crash fires on the user's own tap. Mitigated by construction: `CopyTextControl` writes `text.take(MAX_CLIPBOARD_CHARS)` with `MAX_CLIPBOARD_CHARS = 100_000` — roughly 200KB once parcelled as UTF-16, orders of magnitude above any legitimate message and well under the ceiling. The bound sits **inside** the control rather than at its call sites, so every future caller (#657's per-code-block copy included) inherits it without knowing the text is untrusted; that is deliberate, because the `text: String` parameter carries no trust signal in the type system.
2. **[Trust boundaries] No finding — the Android 13 clipboard preview is an acceptable render path.** Min SDK 33 means every copy pops the system clipboard preview, which renders daemon-authored text in a system surface. It renders as plain text, so the "text, never markup" rule holds. `ClipDescription.EXTRA_IS_SENSITIVE` is deliberately **not** set: the copied text is content the user is already reading on screen and explicitly chose to copy, so suppressing the preview would hide a confirmation rather than protect a secret.
3. **[Tokens, secrets, credentials] Not applicable by design — nothing in the diff reads, writes, renders or compares a token, key or credential.** No `SharedPreferences`, no Keystore, no `data/crypto/` reference. The one adjacent exposure worth naming: conversation plaintext copied to the clipboard persists there after the app backgrounds. That is inherent to any copy feature, is the user's explicit action, and Android 12+ already restricts clipboard reads to the focused app — accepted, not deferred.
4. **[File / storage operations] Not applicable by design — the diff performs no file or storage operation.** No path is constructed, so there is no concatenation to traverse, no check-then-use gap, and no write to make atomic. `res/drawable/ic_copy.xml` is a build-time resource with no runtime path handling.
5. **[Inter-process / Android attack surface] No finding beyond 1.** The clipboard is the only IPC surface touched, and finding 1 covers it. No Activity, Service, Receiver, intent filter, deep link, `PendingIntent` or ContentProvider is added, and `android:exported` is unchanged. Specifically **no WebView**: the assistant body keeps rendering through `MarkdownText`'s Compose AST renderer, so the "a WebView that renders daemon-authored text is a MUST FIX" rule does not fire. The delimiter restyle preserves `Install`'s `uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL)` against a **compile-time constant** and does not make that URL dynamic — a future variant sourcing it from a frame would be a real finding and is called out here so the restyle is not read as licence for one.
6. **[Cryptographic primitives] Not applicable by design — no RNG, hash, key schedule, AEAD framing or secret comparison exists in the diff.** Nothing reaches `NoiseIkSession` or the vendored `noise-java` code, so ADR 0004's hand-rolling prohibition is not engaged. `formatShortDateTime` uses `java.time` formatting only.
7. **[Network & I/O] Not applicable by design — no network call, no `OkHttpClient` configuration, no frame decode, no timeout and no reconnect logic.** The only inbound data is an already-decoded `Message`. The upstream absence of a frame-content length cap is real but is finding 9, not a change this ticket makes.
8. **[Error messages, logs, telemetry] SHOULD FIX — hold the zero-logging posture in Phase B.** The plan adds no `Log` or `Timber` call, and that is load-bearing rather than incidental: a copy path that logged the copied text, or a truncation branch that logged the offending content, would put conversation plaintext into Logcat where ADB and any `READ_LOGS` holder on a rooted device can read it. The concrete Phase-B constraint is that **`MessageMetaRow.kt` contains no logging call at all** — not even a byte length, which the project's logging rules would otherwise permit, because there is nothing here worth the risk of the wrong field being interpolated later. No new user-visible error message and no crash-reporter surface is added, so neither can leak.
9. **[Threat model alignment] OUT OF SCOPE — unbounded daemon-authored text can still drive a layout-cost DoS on this screen; the fix belongs at the decode boundary, not in this component.** A hostile daemon can send an arbitrarily long `assistant_delta` accumulation, or an arbitrarily long `workspaceCwd` that `boundaryLabel` interpolates into the delimiter label. Both then render into an unbounded-height `Text`, with layout cost proportional to length. The exposure is **pre-existing and unchanged by this ticket** — `Message.content` has rendered unbounded through `MarkdownText` since #128, and `workspaceCwd` renders unbounded in the delimiter label today through a `fillMaxWidth` centred `Text`; the restyle swaps that for an unweighted `Text` in a `Row`, which wraps identically. Naming it here rather than fixing it is § Scope Discipline: the correct home is a content-length bound in `RemoteConversationRepository`'s fold or `MobileWireCodec`'s decode, which is production code outside this ticket's scope and which no test in this ticket exposes. Carried into the PR's Lessons learned so it stays findable; it needs its own ticket at the wire layer, where one bound covers every render surface instead of each component defending itself.
10. **[Threat model alignment] No finding — the remaining mobile threats are unengaged or unchanged.** A malicious relay stays content-blind and on-path; this ticket adds no frame handling, so its leverage is limited to the daemon-sourced text already covered by findings 1 and 9, and no plaintext leaves the phone. Token theft from disk is untouched (finding 3). Hostile daemon frames render as text with no markup interpretation. UI-side leakage is unchanged: the bubbles were already on screen, and the copy control's accessible name is a **static string**, not message text, so a screen reader announces the control rather than reading content out anywhere it was not already readable. No text-entry field is added, so third-party keyboard logging is not engaged.
11. **[Concurrency] No finding.** No coroutine is launched, no scope taken, no mutex acquired and no `StateFlow` read-then-mutated. The clipboard write is one synchronous main-thread call inside `onClick`. The carried-over `produceState` producers keep their composition-scoped cancellation. Stale-capture check: `onClick` captures `message.content` from the current composition and `Message` is a stable `data class`, so a recomposed row re-captures and a disposed row's lambda is unreachable — the control cannot copy a neighbouring or superseded message's text. Process death mid-`setText` leaves either the old clip or the new one; there is no partial state to recover.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22
