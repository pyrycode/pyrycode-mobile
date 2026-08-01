# #608 — Thread row for an unrecognized claude message

**Size:** S · **Labels:** `enhancement`, `size:s`, `security-sensitive`
**Split from** #585 · **Blocks** #609 (decode + fold)

---

## Files to read first

| Path | What to extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:260-290` | The `ThreadItem` sealed interface + `SessionBoundary`. The new subtype goes here; copy `SessionBoundary`'s KDoc-invariant idiom ("documented here and asserted in tests; not enforced at construction"). `BoundaryReason` at `:292` shows where a companion top-level enum lives. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt:59-126` | The **structure** to borrow: `rememberSaveable` toggle at `:64`, stateless `…Content` split at `:73`, collapsed header at `:111-126` (icon + single annotated `Text`, `maxLines = 1`, ellipsis), annotated-summary builder at `:207-219`. **Do not borrow `:92`'s palette** — see § Design source. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:278-302` | All three exhaustive `when` arms live in this span: `LazyColumn` key `:281-287`, `rowAlpha` `:289-295`, the `Box` `:296` and the render `when` `:297-301`. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:590-601` | `ThreadItem.timestamp()` (third arm) and `toChannelInfoUiModel`, where `items.firstOrNull()?.timestamp()` drives the channel-info **"created"** label. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:425-448` | The permission modal's KDoc — the in-tree precedent for render-time obligations on server-supplied thread text. Reuse its wording style for the new row's security KDoc. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenSurface.kt:144-148`, `:206-219` | The verbatim-render rule and the `SecureScreen()` `FLAG_SECURE` effect — read to confirm why **neither** `softWrap = false` nor `FLAG_SECURE` transfers to this row (§ Security posture). |
| `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt:124,139,141,150` | `tertiaryDark #FFB59F`, `onSurfaceVariantDark #C2C7CF`, `outlineVariantDark #42474E`, `surfaceContainerDark #1D2024` — the four Figma tokens already exist as M3 scheme roles. Use the roles, never literals. |
| `app/src/main/res/values/strings.xml:53-61` | `thread_*` / `cd_thread_*` naming; add the new strings adjacent. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/ToolCallRowTest.kt:1-60` | The exact test harness to clone: `createComposeRule()` + `PyrycodeMobileTheme { … }`, fixture builders as private funs. |
| `/Users/juhanailmoniemi/Workspace/Projects/pyrycode-desktop/src/renderer/src/screens/conversation/ConversationScreen.tsx:417-494` | The shipped sibling: `UnrecognizedRow`, `UNRECOGNIZED_COPY`, `UNRECOGNIZED_TRUNCATED_COPY`, `unrecognizedSiteLabel`. Copy the **copy**, not the markup. |

Wire SSOT (read-only, sibling checkout): `pyrycode/docs/protocol-mobile.md:611-640` § `unrecognized_message`.

---

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The unrecognized-message row **is not drawn** — confirmed against the file, the third design-owed thread row after #406 (thinking) and #388 (tool running/failed). The drawn sibling to match is the collapsed tool row `16-28`: a 380×33 full-bleed pill on `Schemes/Surface Container` with a hairline `Schemes/Outline Variant` border, holding a mono identifier in `Schemes/Tertiary` followed by a muted `Schemes/On Surface Variant` summary, both `M3/body/small` (Roboto 12/16, tracking 0.4).

Token values re-read from the file via `get_variable_defs(16-28)` and cross-checked against `Color.kt` — all four are the app's **dark** scheme roles:

| Figma token | Value | Compose role |
|---|---|---|
| `Schemes/Surface Container` | `#1d2024` | `MaterialTheme.colorScheme.surfaceContainer` |
| `Schemes/Outline Variant` | `#42474e` | `MaterialTheme.colorScheme.outlineVariant` |
| `Schemes/Tertiary` | `#ffb59f` | `MaterialTheme.colorScheme.tertiary` |
| `Schemes/On Surface Variant` | `#c2c7cf` | `MaterialTheme.colorScheme.onSurfaceVariant` |
| `M3/body/small` | Roboto 12/16, 0.4 | `MaterialTheme.typography.bodySmall` |

**Bind the roles, never the hex.** The frame is the dark scheme; light/medium/high-contrast schemes resolve the same roles to different values and must keep working.

> **Drift warning.** The shipped `ToolCallRow.kt:92` uses `surfaceContainerHigh` with **no border** — it diverges from its own frame. Cloning `ToolCallRow`'s palette would propagate that drift. Borrow its *structure*; take the palette from the table above.

---

## Context

The interactive daemon's stream-json parser used to discard every message kind it had no mapping for into a debug log the production daemon never prints — an unknown kind left no trace and no client was told. The daemon now splits *known-ignored* kinds (silent, a measured list) from *genuinely unknown* ones and forwards the latter as an `unrecognized_message` wire frame.

This slice builds **the row and nothing else**: the `ThreadItem` subtype plus its rendering. Decoding the frame and folding it into the thread is **#609**, which depends on this landing because it writes the type declared here. Drawing the row first is the established shape for this codebase's wire-facing UI — the seam binds unconditionally but dormant — and it puts the visual decision against the design file instead of inside a decode ticket.

---

## Design

### 1. The thread-row type

Add to `ThreadItem` in `ConversationRepository.kt`, beside `SessionBoundary`:

```kotlin
data class UnrecognizedMessage(
    val id: String,
    val site: UnrecognizedSite,
    val messageType: String,
    val raw: String,
    val truncated: Boolean,
    val occurredAt: Instant,
) : ThreadItem
```

and a top-level enum beside `BoundaryReason`:

```kotlin
enum class UnrecognizedSite { LineType, AssistantBlock, UserBlock, Undecodable }
```

Field notes, all of which belong in KDoc on the data class:

- **`id`** — a **client-owned** stable identity, not a wire field. See § 2; its uniqueness-per-thread invariant is documented here and enforced by the producer (#609), mirroring how `SessionBoundary` documents its `workspaceCwd` invariant without constructor enforcement.
- **`site`** — closed set, decoded from the wire string by #609.
- **`messageType`** — **empty when `site == Undecodable`**: nothing decoded, so no type was ever read. Not nullable — the wire field is a string that is present-and-empty, and modelling it as `""` keeps the decode arm total.
- **`raw`** — the offending JSON verbatim, capped daemon-side at 16 KiB. A `String`, **not** nested JSON: a truncated blob is no longer valid JSON. The daemon scrubs invalid UTF-8 after cutting, so it arrives well-formed even on a mid-rune cut.
- **`occurredAt`** — arrival instant, stamped by #609. The wire carries **no timestamp**; this exists because `ThreadItem.timestamp()` needs an `Instant` per row, and it is load-bearing beyond ordering — `toChannelInfoUiModel` (`ThreadScreen.kt:600`) reads `items.firstOrNull()?.timestamp()` for the channel-info **"created"** label, so an unrecognized row landing first in a thread supplies that label.

Also extend the `ThreadItem` KDoc at `:260-265`, which currently claims the stream interleaves messages with `SessionBoundary` markers only.

`ConversationRepository.kt` already declares several top-level types, so ktlint's single-class filename rule does not bite when `UnrecognizedSite` is added there.

### 2. Why the row carries a client-stamped `id`

This is the one non-obvious decision in the slice, and AC 1's "repeats each keep their own row rather than collapsing into one another" rests on it.

`MessageItem` keys on `message.id`; `SessionBoundary` keys on its session-id pair. The `unrecognized_message` frame carries **neither a message id nor a `turn_id`** — deliberately, since opening a turn would wedge the conversation. Two remaining options are both unsound:

- **Key on list position.** The key lambda only sees the *reversed* index, and the chronological index is not stable either: `ThreadViewModel.render()` (`ThreadViewModel.kt:1094`) appends a synthetic streaming `MessageItem` and drops it on finalise, shifting positions. A position key would re-identify the row on every such shift, discarding its expanded state and mis-animating the list.
- **Key on the payload.** `occurredAt + site + messageType + raw.hashCode()` collides on two identical frames stamped in the same instant — exactly the repeat case AC 1 names — and retains up to 16 KiB inside a key held for the list's lifetime.

So the subtype carries its own identity. This slice declares the field and its invariant; **#609 stamps it**, and the v2 envelope's monotonic frame `id` is the natural source. Duplicate keys crash `LazyColumn`, so the invariant is load-bearing — state it in KDoc as a producer obligation.

### 3. The row composable

New file `app/src/main/java/de/pyryco/mobile/ui/conversations/components/UnrecognizedMessageRow.kt`, sibling to `ToolCallRow.kt`. Structure mirrors `ToolCallRow` exactly — a stateful entry point owning the toggle, and a stateless `…Content` taking `expanded`/`onToggle` so previews and tests can drive both states without gesture injection.

```kotlin
@Composable
fun UnrecognizedMessageRow(item: ThreadItem.UnrecognizedMessage, modifier: Modifier = Modifier)
```

Behaviour:

- **Toggle** — `var expanded by rememberSaveable { mutableStateOf(false) }`, as `ToolCallRow.kt:64`. Inside a `LazyColumn` item this is scoped by the row's key, so each row keeps its own state and it survives configuration change (AC 2). Do **not** hoist expansion into `ThreadUiState` — that would put payload-adjacent state in the ViewModel and give #609 a fold it does not need.
- **Container** — `Surface` with `RoundedCornerShape`, `color = colorScheme.surfaceContainer`, `border = BorderStroke(1.dp, colorScheme.outlineVariant)`, `Modifier.fillMaxWidth().clickable(onClick = …, onClickLabel = …)`. Reuse `ToolCallRow`'s private dimension vals as the sizing reference; declare this file's own so the two rows stay independently tunable.
- **Collapsed header** — a `Row` of a leading warning `Icon` plus **one** annotated `Text` at `bodySmall`, `maxLines = 1`, `TextOverflow.Ellipsis` (`ToolCallRow.kt:118-124`). The annotated string appends, in order:
  1. the fixed client-owned label (`onSurfaceVariant`),
  2. `messageType` in `FontFamily.Monospace` + `tertiary` — **appended only when `messageType` is non-empty** (AC 4: omit the slot, never render empty quotes or a bare separator),
  3. the site label (`onSurfaceVariant`).
  Separators are client-owned literals; keep the ` · ` idiom from `ToolCallRow.kt:216`.
- **Leading glyph** — `Icons.Outlined.WarningAmber`, `contentDescription = null` (the adjacent text carries the meaning). `material-icons-extended` is already on the classpath — `ToolCallRow.kt:16-17` imports `ErrorOutline` and `Terminal`.
  **Tint: `colorScheme.tertiary`**, not `error`. This reports a gap in *our own* parser, not a claude failure or a tool error; `error` overstates it, and `onSurfaceVariant` erases it as a signal. `tertiary` is the accent the drawn family already gives the notable slot. Design-owed — see § Open questions.
- **Expanded body** — a plain `Text` of `raw` at `bodySmall` + `FontFamily.Monospace`, colour `onSurface`, **no `maxLines`**, normal wrapping. When `truncated`, a second `Text` below it carrying the client-owned truncation note at `labelSmall` / `onSurfaceVariant`, so the reader knows the JSON is incomplete by design rather than malformed at the source.

### 4. The site label — a total function

```kotlin
@Composable
private fun siteLabel(site: UnrecognizedSite): String
```

A `when` over the enum with **no `else`**, each arm returning a `stringResource`. Used as an expression so Kotlin enforces exhaustiveness: a future fifth site is a compile error, not a blank slot (AC 3).

This is the load-bearing half of the encoding posture — it keeps the daemon's `site` value *selecting* a string and never *becoming* one, leaving `raw` and `messageType` as the only daemon-supplied strings that reach the render.

Copy is taken verbatim from the desktop sibling so the two clients agree:

| `site` | Label |
|---|---|
| `LineType` | whole message |
| `AssistantBlock` | assistant block |
| `UserBlock` | user block |
| `Undecodable` | could not be decoded |

### 5. `ThreadScreen` — three arms

All three sites are in `ThreadScreen.kt` and are the *only* exhaustive `when`s over `ThreadItem` in the tree (verified: every other reference is an `is`-check inside `any` / `filter` / `filterIsInstance` / `lastOrNull`, which a new subtype cannot break). The ViewModel fold passes repository rows through verbatim and needs no change.

| Site | Arm |
|---|---|
| `LazyColumn` key `:281-287` | `is ThreadItem.UnrecognizedMessage -> "unrecognized:${item.id}"` |
| Render `:297-301` | `is ThreadItem.UnrecognizedMessage -> UnrecognizedMessageRow(item = item)` |
| `timestamp()` `:591-594` | `is ThreadItem.UnrecognizedMessage -> occurredAt` |

**Above-delimiter dimming is inherited — verify it, do not build it.** `rowAlpha` is computed at `:289-295` and applied by the `Box` at `:296` that wraps the render `when`. Place the new arm *inside* that existing `when`; do not add a sibling branch outside the `Box`. Any subtype rendered there is dimmed with zero new code, so AC 1's dimming clause needs no new logic and no new test.

### 6. String resources

Follow the established thread naming (`strings.xml:53-61`): `thread_*` for visible copy, `cd_thread_*` for content descriptions.

| Key | Value |
|---|---|
| `thread_unrecognized_label` | Unrecognized message |
| `thread_unrecognized_truncated` | Payload truncated by the daemon. |
| `thread_unrecognized_site_line_type` | whole message |
| `thread_unrecognized_site_assistant_block` | assistant block |
| `thread_unrecognized_site_user_block` | user block |
| `thread_unrecognized_site_undecodable` | could not be decoded |
| `cd_thread_unrecognized_expand` | Show the unrecognized payload |
| `cd_thread_unrecognized_collapse` | Hide the unrecognized payload |

The two `cd_` values are `onClickLabel`s, selected on `expanded`, so TalkBack announces the action rather than re-reading the row text.

### 7. Previews

Mirror `ToolCallRow.kt:298-377`: one private `…PreviewMatrix` composable driving `…Content` across collapsed / expanded / truncated / empty-`messageType`, wrapped by a light and a dark `@Preview`. The **dark** preview is the fidelity reference against Figma `16-28`.

---

## State + concurrency model

None. The row is a leaf composable over an immutable `data class`, with one `Boolean` of local UI state. No `viewModelScope` job, no `StateFlow`, no dispatcher, no cancellation surface. `ThreadUiState` is unchanged.

Recomposition: `UnrecognizedMessage` is a `data class` of `String`/`Boolean`/enum/`Instant` fields, so it is stable and the row skips correctly. Keep it that way — no lambdas or collections in the subtype.

---

## Error handling

No failure modes are reachable in this slice: it performs no I/O, no parsing, and no decoding. `raw` is rendered as received (AC 3) — a malformed or truncated payload is the *expected* input, not an error, and must never be validated, parsed, trimmed, or reformatted on this path.

Total-ness is the only correctness obligation, and it is enforced by the compiler: four exhaustive `when`s (three in `ThreadScreen`, one in `siteLabel`), none with an `else`.

The one behaviour that *reads* like error handling — the truncation note — is a render of the daemon's `truncated` flag, not a client-side detection.

---

## Security posture

`raw` and `messageType` are the most untrusted strings the thread holds: unbounded, model-adjacent JSON the daemon could not interpret, arriving over the network. The precedent is the permission modal's render-time obligations (`ThreadScreen.kt:432-442`) — server-supplied text rendered inside the thread screen — **not** `LiteralScreenSurface`, whose four-rule bundle does not transfer wholesale.

**In scope, and load-bearing:**

- **Inert output-encoding.** Every daemon string reaches a plain `Text` as a literal. Never `MarkdownText` (which `MessageBubble.kt:110,150` routes through), never `SelectionContainer` — text selection would open a clipboard exfiltration path.
- **No payload in persisted state.** Only the `Boolean` toggle reaches `rememberSaveable`. No `raw` / `messageType` / `site` in saved-instance state (AC 2).
- **No logging.** Nothing in this slice logs any payload field — not `Log.*`, not `RelayLog`, not a preview fixture pulled from live data.
- **Client-owned `site` copy.** § 4. Constrains the daemon-supplied render surface to exactly two strings.

**Explicitly out of scope** — both are over-reads of the `LiteralScreenSurface` precedent:

- **No `FLAG_SECURE`.** It is a **per-window** flag. The modal gets it via `SecureFlagPolicy.SecureOn` on the *dialog's own* window; `LiteralScreenSurface.kt:206-219` adds it to the host Activity for the duration of that screen and records that "the app uses `FLAG_SECURE` nowhere else". A thread row has no window of its own, so the only way to apply it here is the host Activity — hardening the entire thread screen, permanently, as a side effect of one diagnostic row. Do not add it.
- **No `softWrap = false` / dual-axis scroll.** That is `LiteralScreenSurface.kt:144-148`'s terminal-grid requirement. A thread row wraps like its neighbours.

---

## Testing strategy

Instrumented only — this is Compose rendering with no JVM-testable logic. New file `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/UnrecognizedMessageRowTest.kt`, cloning `ToolCallRowTest.kt`'s harness (`createComposeRule()`, `PyrycodeMobileTheme { … }`, private fixture builders).

Run with `./gradlew connectedAndroidTest`. **Instrumented sources are not compiled by `test` / `lint` / `assembleDebug`** — run `compileDebugAndroidTestKotlin` before assuming green.

Scenarios (AC 5's five, plus restoration for AC 2):

- **Collapsed by default** — fresh row shows the fixed label, the mono type, and the site label; the raw body is *absent* (`assertDoesNotExist`, a member — no import).
- **Expands in place on tap** — click the row, raw body is displayed; the collapsed header is still displayed (in place, not a navigation).
- **Collapses again on second tap** — raw body absent once more.
- **Short raw body** — a small JSON fixture renders verbatim when expanded, byte-identical to the input including leading/trailing whitespace.
- **Truncated flag set** — expanded body shows the truncation note; with the flag clear, the note is absent.
- **Empty `messageType` (`Undecodable` site)** — collapsed line shows the label and the "could not be decoded" site label, and renders **no** empty-quote artefact or dangling separator. Assert on the absence of the separator-plus-nothing shape, not merely on the presence of the label.
- **Toggle survives configuration change** — `StateRestorationTester`: expand, `emulateSavedInstanceStateRestore()`, assert still expanded.

Assertions match on the client-owned strings, so they double as a check that the copy is resource-backed rather than inlined.

**Not tested here, deliberately.** AC 1's interleaving / ordering / no-collapse clauses are properties of the `LazyColumn` key and the `Box` placement, and nothing in this slice can *produce* two rows to interleave. The end-to-end assertion belongs to **#609**, the first slice that can emit them. Dimming needs no test at all — it is inherited structurally (§ 5), not new behaviour.

---

## Open questions

1. **Warning-glyph tint is design-owed.** `tertiary` is this spec's call (§ 3) since the row has no Figma frame. Third such gap after #406 and #388 — resolve as a batch when the frames land, not by blocking this ticket.
2. **Unknown `site` string on the wire.** The enum is closed at the four documented values, which is what makes the compile-error property in AC 3 work. Decoding a *fifth, undocumented* string is **#609's** decision, at the decode boundary — and note that growing the enum later still trips the compile error in `siteLabel`, so the property survives either resolution. Not pre-decided here: no such drift has been observed, and the daemon and client version together. #609 should apply the #593 rule — a total mapper that never drops the row, since a silent hole is the exact failure this feature exists to close.
3. **`id` source.** The v2 envelope's monotonic frame id is the natural stamp, but #609 owns the choice. If it turns out not to be reachable at the fold, a client-side monotonic counter satisfies the invariant equally well.

---

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No MUST FIX. The boundary is explicit and *outside* this slice: `raw` / `messageType` / `site` arrive already-decoded from #609 and are held in one named type, `ThreadItem.UnrecognizedMessage`. Within this slice they cross exactly one further boundary — into Compose text rendering — and § Security posture pins that crossing to inert plain `Text`. The type name itself signals untrusted content to downstream readers, and the closed-enum `site` (§ 4) narrows the daemon-supplied render surface from three strings to two. No parsing, validation, or re-interpretation happens anywhere on the path, so no parser differential can open.
  **Considered and accepted — label spoofing in the collapsed line.** `messageType` renders *adjacent to* client-owned copy in one annotated string, so a crafted value such as `Unrecognized message · assistant block` could try to impersonate the client's own label or site text. Three properties contain it and none may be dropped: the type slot is the only span in `FontFamily.Monospace` + `tertiary` (the client spans are proportional + `onSurfaceVariant`), it always renders *between* the fixed label and the site label rather than replacing either, and `maxLines = 1` + ellipsis clips any embedded newline so a payload cannot manufacture extra visual rows. This is the same containment the drawn frame already gives the tool-row identifier. **Consequence for the developer:** the mono+`tertiary` styling on the type span is a security property, not decoration — do not "simplify" the annotated string to a single uniform style.
- **[Tokens, secrets, credentials]** Not applicable by construction — the slice touches no token, key, or credential, reads no `DataStore` / Keystore / `SharedPreferences`, and adds no field that could carry one. The row's only persisted datum is a `Boolean`.
- **[File / storage operations]** Not applicable — no filesystem path is constructed, read, or written. `raw` is never used as, or concatenated into, a path or file name; it reaches only a `Text`. Nothing new enters auto-backup: the sole saved-state entry is the toggle.
- **[Inter-process / Android attack surface]** No findings. No `Activity` / `Service` / `Receiver` / provider / `<intent-filter>` / `PendingIntent` / WebView is added or altered. The only new Android-surface interaction is `Modifier.clickable`, which is not exported. **This is where the `FLAG_SECURE` decision earns its keep**: the tempting "match the snapshot surface" reading would have made a per-row diagnostic silently harden the host Activity for the whole thread screen — a window-scope change driven by row-scope data, and a behaviour regression on every unrelated row. § Security posture rejects it explicitly and states why.
- **[Cryptographic primitives]** Not applicable — no RNG, hashing, comparison against a secret, or key handling. Note that the `id` in § 2 is an ordering/identity value, **not** a security token: it is not compared against anything, not authenticated, and not required to be unguessable, so a monotonic counter is a legitimate source and `SecureRandom` is not indicated.
- **[Network & I/O]** Not applicable to the slice itself — it opens no socket and sets no timeout. `raw` is capped **daemon-side at 16 KiB**, well inside the v2 65519-byte envelope cap.
  **Considered and accepted — layout cost of a pathological payload.** The expanded body sets no `maxLines`, so the worst shape is not 16 KiB of prose but 16 KiB of newlines: ~16 000 laid-out lines in one `LazyColumn` item, measured in full because laziness is per-item, not within an item. That is a plausible jank/ANR on expand. It is **not** a MUST FIX for three reasons, and the ordering matters: capping it would directly violate AC 3's verbatim rule, which is the feature's entire point; the expansion is user-initiated per row, not automatic; and this row is *strictly more bounded* than the thread surfaces already shipping beside it — `MessageBubble` and `ToolCallRow`'s expanded output render daemon-supplied text with **no cap at all**, so a hostile payload has a cheaper path than this one. Adding a defence here would harden the most-bounded surface in the thread against an unobserved failure while leaving the uncapped ones open. If layout-cost hardening is ever wanted it belongs thread-wide, sized against `MessageBubble` first, in its own ticket.
- **[Error messages, logs, telemetry]** No findings, and this is an active obligation rather than a vacuous one — the tree has a `RelayLog` seam a developer could reasonably reach for when adding a "we could not understand this" row. § Security posture states the MUST-NOT-log rule for every payload field, and it is worth naming that the daemon-side motivation was the *inverse* problem (a debug log nobody read). The fix is a visible row, not a client-side log line. Preview and test fixtures must be hand-written literals, never captured live payloads.
- **[Concurrency]** Not applicable — no coroutine is launched, no scope introduced, no shared mutable state touched. § State + concurrency model records this explicitly. `rememberSaveable` is confined to the composition, and the row keeps `ThreadItem` immutable so no cross-screen flow-sharing question arises.
- **[Threat model alignment]** Two mobile-specific threats apply and are addressed: **clipboard exfiltration** (no `SelectionContainer`) and **active-content injection through model-adjacent JSON** (no `MarkdownText`, literal `Text` only). **Screenshot leakage is named and explicitly deferred** — not silently dropped: `FLAG_SECURE` cannot be scoped to a row, and applying it at the host would harden the entire thread screen, which `LiteralScreenSurface.kt:206-219` records as a deliberate non-default for the app. If in-thread screenshot hardening is ever wanted, it is a thread-screen-wide decision needing its own ticket, not a side effect of this row. Accessibility-service eavesdropping is out of scope on the same basis and applies equally to every existing thread row.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-30
