# #1494 — Refusal row names known models by their menu label

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ModelRefusalRow.kt` — `refusalTitle`,
  `switchBackLabel`, `refusalModelDisplay`, the `ModelRefusalRow` KDoc's security obligations. The change
  lands here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` — `ThreadRunConfig.choices`,
  `overflowChoices`, `inheritedChoice`; `ThreadModelChoice.value`, `label`, `resolvedModel`. The lookup lands
  here as a member of `ThreadRunConfig`, next to `selectedChoice`, whose candidate counting over rendered plus
  overflow rows it mirrors.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` — `ModelMenuRow.toChoice`,
  `dropdownLabel`, `String.inert()`: `label` is already inert (ISO controls dropped, 128-char cap), and
  `resolvedModel` is `""` when the daemon cut it. Read only.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` — the `ThreadItem.ModelRefusal`
  arm of the `LazyColumn` render. One argument added there.
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt` —
  `threadNoticeFramesAt412By892`, `clearStaged` (sets the menu to `null`), `MODELS`, `menuRow`.
- `app/src/androidTest/assets/design-1220/thread/index.md` — sections "Notification text — `620:1577`" and
  "Refusal switch back — `646:4707`", whose Geometry and Typography verdicts this ticket updates.
- `docs/knowledge/features/model-refusal-row.md` § "Security — why each model is its own span": the
  per-span rule (#875, #1360, #1113) stays. Each model name, known or unknown, remains its own span.
- `app/src/sharedTest/.../components/ModelRefusalSwitchBackTest.kt` — the shared screen-test shape mirrored
  by the new test; its no-menu expectations ("Switch back to claude-opus-5-5") stay valid unchanged.

Overlap: #1548 also edits `ThreadScreen.kt` (the composer's `bottomBar` background); a different block, so
this edit stays additive in the refusal arm.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1576 — frames Notification text
`620:1577` and Refusal switch back `646:4707`.

Bare text in the message gutter: "Refused on Opus, continued on Sonnet" on one line in `bodyMedium`
`onSurfaceVariant` (the title's client colour, no monospace, no emphasis on the names), "Show details" in
`labelMedium` primary under it, and the outlined small secondary button reading "Switch back to Opus" in
`bodySmall` medium weight, primary. The frames need no change; only the model-name spans change.

## Change

Add `ThreadRunConfig.knownModelLabel(identifier: String): String?`. It returns the `label` shared by every
row in `choices + overflowChoices` whose `value` or `resolvedModel` equals `identifier` exactly, or `null` when
no row matches, the matching rows disagree on the label, or `identifier` is empty (an empty `resolvedModel`
means "cut", not a match). No family or prefix matching; `inheritedChoice` (the hidden `default` row) is not
a published choice and is not searched. With no menu, `choices` and `overflowChoices` are empty, so a row
restored before a menu arrives is unknown.

`ModelRefusalRow` gains `modelLabel: (String) -> String? = { null }`; `ThreadScreen` passes
`state.runConfig::knownModelLabel`. The default keeps every other caller (previews, tests) on today's
rendering. `refusalTitle` and `switchBackLabel` share one rule per identifier:

1. `refusalModelDisplay(identifier)` is `null` → "unknown model" in the client span, as today.
2. Otherwise, `modelLabel(identifier)` stripped by `refusalModelDisplay` is non-null → that label as **its own
   span**: in the title `withStyle(clientStyle)` (body style, `onSurfaceVariant`); on the button
   `withStyle(SpanStyle())`, the button's own text style. A separate `withStyle` call, so the span boundary
   survives even where the style equals the copy around it.
3. Otherwise → the stripped identifier in its own monospace span, as today (title also `onSurface`).

The lookup takes the raw identifier verbatim (the menu rows are verbatim too); stripping applies only to what
is drawn. The label goes through `refusalModelDisplay` as well, so a label can never carry a line break even
though `inert()` already drops controls. A blank label falls through to rule 3.

Nothing else moves: the ViewModel, the offer, `SwitchBackOffer` and the cache are untouched; the label is
resolved at render time from state the screen already holds, so a menu arriving after a cached row recomposes
it into the known form.

## Testing strategy

- **JVM** `app/src/test/.../thread/ThreadRunConfigKnownModelLabelTest.kt` (beside
  `ThreadRunConfigModelSelectionTest`): match by `value`; match by `resolvedModel`; match only in
  `overflowChoices`; two rows agreeing on the label → that label; two rows disagreeing → `null`; no match,
  a prefix (`claude-opus` against `claude-opus-5-5`) or a family-only match → `null`; empty menu → `null`;
  empty identifier against a row with a cut (`""`) `resolvedModel` → `null`; `inheritedChoice` alone → `null`.
- **Compose, Robolectric** `app/src/sharedTest/.../components/ModelRefusalModelLabelTest.kt`, `@Config
  (qualifiers = "w412dp-h892dp")`, `@GraphicsMode(NATIVE)`, `ForcedSize(412×892)` as `SettingsRowLayoutTest`
  does. Through `ThreadScreen` with a `ThreadUiState.runConfig` menu and a `SwitchBackOffer`:
  - known: title text "Refused on Opus, continued on Sonnet", one line (`TextLayoutResult.lineCount == 1`),
    "Opus" and "Sonnet" each a span range of their own with no monospace family and the client colour;
    button "Switch back to Opus" with "Opus" its own span range;
  - unknown (menu lacks the identifier) and ambiguous (two rows, two labels): the identifier is a monospace
    span in title and button;
  - no menu: today's monospace spans;
  - blank identifier: "unknown model".
- Existing: `ModelRefusalSwitchBackTest`, `ThreadAgentAttributionTest`, `ModelRefusalDisplayTest` run as they
  are; their fixtures have no menu, so they keep asserting the unknown form.
- **Device capture** (AC 4): `ThreadDesignCaptureTest.threadNoticeFramesAt412By892` seeds the `MODELS` menu
  before the `notification-text` capture and waits for "Refused on Opus, continued on Sonnet" and "Switch back
  to Opus". Retaken on `pixel8Api35` with `requireRealSystemBars=true`, compared with
  `scripts/design-compare.py` against `figma-620-1577.png` and `figma-646-4707.png`, and the two sections'
  Geometry and Typography verdicts updated in `index.md`. Device-only because it saves real pixels.
- No rung-3 scenario: this changes how an existing row's names render, not an operator flow; the scripted
  `refusal` scenario still covers the switch-back round trip.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/model-refusal-row.md` § "The row composable",
§ "Security — why each model is its own span" and § Switch back "Rendering" describe every model as a
monospace span; they need the known-label rule (`ThreadRunConfig.knownModelLabel`, exact `value` /
`resolvedModel` match, unanimous label, else monospace).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] SHOULD FIX, accepted residual. Two claude-authored inputs now meet: the refusal's model
  identifier and the published menu (`ModelMenuRow`, reduced by `toChoice`). For a Claude row whose `value`
  has no family, `dropdownLabel` falls back to the published `displayName`, so a hostile daemon could publish
  a row `value = "x"`, `displayName = "Opus, continued on Sonnet"` and send a refusal naming `"x"`; the title
  then draws that label in the client's own style. This is the trade the 2026-10-02 design decision accepts:
  the same label is what the Status sheet already shows as that model's name, and the daemon that publishes
  the menu is the one that could already lie in it. What stays enforced, and Phase B must keep: the label is
  its own `withStyle` span (never concatenated into the copy), passes `refusalModelDisplay` so it cannot break
  a line or fake a second row, is capped at 128 characters by `inert()`, and is plain inert `Text`. The lookup
  is exact on the raw identifier against `value` / `resolvedModel` only, with no family or prefix guess, so an
  identifier claude crafted (e.g. `"a, continued on b"`) reaches the known form only if the menu also lists
  that exact string.
- [Trust boundaries] No finding on ambiguity. `knownModelLabel` counts every matching row across `choices`
  and `overflowChoices` (the rows the sheet cannot show) and returns a label only when they all agree, so a
  second published row with a different label cannot be shadowed by the first. An empty identifier never
  matches, so a cut `resolvedModel` (`""`) cannot turn a blank identifier into a known model; a blank or
  escape-only identifier is caught by `refusalModelDisplay` before the lookup and still reads "unknown model".
- [Trust boundaries] No finding on staleness. The label is resolved at render time from the current
  `ThreadRunConfig`; a cached or restored row with no menu yet stays monospace, and a later menu without the
  model reverts it. Nothing about the lookup is persisted.
- [Tokens, secrets] Not applicable by design: no token, key or credential is read or produced; the change is a
  pure function over UI state and a render rule.
- [Files and storage] Not applicable by design: nothing is written; `FileConversationCache` keeps storing the
  row verbatim and the label is never cached.
- [Android attack surface] Not applicable by design: no component, intent, deep link or WebView is added; the
  title and button stay plain `Text` with no link detection or selection.
- [Cryptography] Not applicable by design: no randomness, digest or key material.
- [Network and I/O] Not applicable by design: no frame, request or write is added; the switch-back write
  still sends `SwitchBackOffer.originalModel` verbatim, never the label.
- [Errors, logs] No finding. Nothing is logged; the model names and labels must stay out of logs, as today.
- [Concurrency] Not applicable by design: no coroutine, flow or state is added; `knownModelLabel` is a pure
  getter-style function on an immutable data class.
- [Threat model] Hostile daemon frame: handled as above (exact match, unanimous label, stripped, inert,
  separate span). Malicious relay: cannot reach this path inside the Noise session. Token theft, UI-side
  leakage: unaffected.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-02

## Revisions

**2026-10-02, verifier review of PR #1554.** The Testing strategy said existing tests run as they are because
their fixtures have no menu, and that the scripted `refusal` scenario still covers the switch-back round trip.
It missed the scripted and live harnesses, where the daemon publishes a real menu: fakeclaude's canned menu
lists `value = "haiku"`, so `knownModelLabel("haiku")` is "Haiku" and the button reads "Switch back to Haiku".
`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_refusalSwitchBackRestoresOriginalModel`
now waits for that label (`REFUSAL_ORIGINAL_LABEL`) and still reads back `haiku` as the written model. No live
test asserts refusal text. The contract is unchanged: any e2e assertion on a model name the harness's menu
publishes expects the menu label. The review's nits also rename `ModelRefusalRow`'s parameter to
`knownModelLabel`, so it no longer collides with `ThreadRunConfig.modelLabel`, and add a known-label preview.
