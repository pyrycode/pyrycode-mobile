# 743 — Draw the mobile edit-host modal

One stateless component, `EditHostModal`, that draws the supplied Edit host frame through the
shared modal shell and reports three caller intents. No wiring, no storage, no navigation: the
host row that opens it is #744 and the removal behind its Unpair action is #745.

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal` — the shell
  this component consumes: header, divider, 12 dp centred scroll column, error slot, Cancel/OK
  footer, and `logModalEvent`'s content-free debug-gated logging shape.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RenameDialog.kt` →
  `RenameDialog` — the `initialName` + `onSubmit(String)` + internally `remember`ed
  `TextFieldValue` shape this component follows, and the in-subcomposition focus effect #589
  established.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialog.kt` →
  `CreateFolderDialogInternal` — the same focus contract, stated in its comment.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` →
  `boundedRowText` — the existing rule for clamping attacker-influenceable text before layout and
  before a formatted description; file-private, so this component applies the same bound through
  the constant it delegates to. `TreeHostRow` shows the clamp-once-reuse-everywhere shape, and
  `SELECTED_FILL_ALPHA` records the trap of taking a dark-only Figma fill token literally.
- `app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt` →
  `MAX_WORKSPACE_LABEL_CHARS` — the bound itself (`internal`, same module, so no visibility
  widening is needed), and the display-text-only rule its KDoc states.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `lightScheme` / `darkScheme` — confirms
  no custom `Shapes` is configured, so the shell's `extraLarge`/`small` adaptation is the
  precedent this component reuses for the frame's 6 dp corners.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalTest.kt` → its local
  editable host, `DeviceConfigurationOverride(ForcedSize(320 × 640))` setup and the
  `performScrollTo().assertIsDisplayed()` reachability shape this suite mirrors.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRowsTest.kt`
  → `assertRightEdgeWithinRow` / `assertSingleLine` and `conversationRow_clampsAnOversizedDaemonAuthoredName`
  — the truncation-versus-clamp distinction this suite's AC4 case reuses.
- `docs/knowledge/features/mobile-modal.md` — the shell's caller contract, the recorded design
  adaptations this component extends rather than re-derives, and the statement that the shell has
  no production consumers, which this slice makes wrong (see Documentation handoff).
- `app/src/main/res/values/strings.xml` — confirms no host/relay/unpair strings exist yet.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

Under the shell's "Edit host" header and divider, the content column stacks four items at 12 dp:
a `Server identity:` row and a `Relay address:` row, each putting a semibold `label-large` label
beside a regular `body-medium` value that flexes and is already drawn ellipsised; a `Host name:`
block whose `label-large` label sits 8 dp above a filled, 6 dp-cornered text area holding
"Pyrybox"; and a left-aligned outlined `Unpair host` button in `primary` at 8 dp top padding.
The content sits well short of the frame's height, so the shell's vertical centring applies, and
the frame's `Message` slot — the shell's error line — is drawn hidden.

Deliberate adaptations, in the table's shape from the shell's own mapping:

| Reference | This component | Reason |
| --- | --- | --- |
| `rgba(0,51,85,0.41)` text-area fill (`schemes/on-primary` at 41%) | `onPrimaryContainer` at `FIELD_FILL_ALPHA` | The frame is dark-only, where `on-primary` is a near-black that recesses the well. `onPrimary` is white in our light scheme and the fill would vanish, the trap `SELECTED_FILL_ALPHA` records. Tinting with the shell's own content colour recesses in light and lifts in dark — what M3's filled field already does across schemes — at a far lower alpha, because `onPrimaryContainer` is a high-contrast colour where the reference token is not. |
| 6 dp field and action corners | `MaterialTheme.shapes.small` | The shell already took this adaptation for its own 6 dp actions; no custom `Shapes` is configured. |
| `px-19 py-7` Unpair button (~34 dp tall) | At least 48 dp | The shell's recorded floor for its own actions, and AC5 requires it here. |
| `h-[20px]` fixed identity rows | `heightIn(min = 20.dp)`, single line | A fixed height would clip a value at a large font scale; the bound plus `maxLines = 1` keeps the design's one-line treatment without capping accessibility text. |

Everything else is the shell's: 28/24 dp padding, the 12 dp content gap, `titleLarge`, the
divider, the footer.

## Context

`MobileModal` (#638) shipped with no production consumer. This is its first, and it is drawn on
its own because the wiring slice (#744) already fills the file boundary with the host row, the
list screen's event set, the activity's dispatch, the view model and the Koin edit. Keeping the
component caller-driven is what lets #744 and #713 compose the same one instead of #713
introducing a second removal flow.

No ADR is warranted: this adds no decision the shell's own mapping does not already carry.

## Design

One new file, `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt`, beside the
shell in `de.pyryco.mobile.ui.components` (Technical Notes: the list's package would have to be
undone when #713 lands).

```kotlin
@Composable
internal fun EditHostModal(
    serverIdentity: String,
    relayAddress: String,
    initialHostName: String,
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
    onUnpairRequested: () -> Unit,
    modifier: Modifier = Modifier,
    submissionEnabled: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
)
```

Structure: `EditHostModal` clamps its three text parameters once, then delegates presentation to
`MobileModal`, supplying the content lambda. Two private helpers keep the body flat — an identity
row and the labelled field block. Nothing else is exported.

- **`onSubmit` carries the name.** `RenameDialog`'s shape, not a fully controlled `value`/
  `onValueChange` pair. AC2 says submitting *reports the entered name*, and AC3's "an error
  leaves the entered name as it was" is only a real property to prove when the component owns the
  edit buffer. This is still stateless in the sense the repo means and the Technical Notes ask
  for: no application state, no storage, no connection, no navigation, and visibility, validation,
  loading and error all stay with the caller.
- **The buffer is keyed on the host, not on its name.** `remember(serverIdentity)` holding a
  `TextFieldValue` seeded from the clamped `initialHostName`. Keying on `initialHostName` would
  discard what the operator typed the moment a caller refreshed the stored name, and keying on
  nothing would strand the buffer when #713 reuses one composition for a second host. The key is
  the **raw** parameter, never the clamped one: two identities sharing a 128-character prefix must
  not collapse onto one buffer. `error` and `loading` are not keys, which is what makes AC3 hold.
- **The identity rows are plain `Text`.** The frame calls them "Read only textfield"; a real
  field would be focusable and would carry a `SetText` action. Plain `Text` inside a `Row` with
  merged semantics is inert, non-editable and — absent a `SelectionContainer`, which this
  component does not introduce — non-selectable, which is AC1 read literally. Merging the row's
  descendants gives TalkBack "Server identity: <value>" as one node without any code formatting a
  string, so AC4's "before any content description" holds by construction rather than by care.
- **Clamping.** A single private `boundedText(raw) = raw.take(MAX_WORKSPACE_LABEL_CHARS)`, the
  same bound `boundedRowText` applies and for the same stated reason, applied to `serverIdentity`,
  `relayAddress` **and** `initialHostName` before any of them reaches layout. The ticket's AC4
  names only the first two; the seed is included because it reaches text layout identically and
  `TreeHostRow` already clamps the same value on the row this modal opens from. Values truncate
  with `maxLines = 1` and `TextOverflow.Ellipsis` inside a `weight(1f)`, so an over-long value
  ellipsises within its row instead of stretching it.
- **No initial focus.** The shell makes no focus request and leaves it to the caller; this
  component deliberately declines. Auto-focusing would raise the IME on open and push the Unpair
  action under the keyboard on the 320 × 640 viewport AC5 names. If a consumer ever wants it, the
  effect belongs inside this component's own content lambda — the shell's subcomposition — per
  #589's contract, and that is recorded in the KDoc.
- **Strings** come from `strings.xml`: `edit_host_title`, `edit_host_server_identity_label`,
  `edit_host_relay_address_label`, `edit_host_name_label`, `edit_host_unpair`.
- **Previews:** one composable at 412 × 892 dp in light and dark, matching the shell's.

## State + concurrency model

No coroutine, no scope, no flow, no dispatcher. The only state is the `remember`ed
`TextFieldValue` described above, which lives and dies with the modal's composition; the caller
removes the modal to close it. Nothing survives process death by design — the caller's stored name
is the durable copy, and a half-typed rename is not worth persisting.

## Error handling

The component raises no error of its own: it has no failure mode, because it performs no work. A
caller-supplied `error` passes straight through to the shell, which owns presentation, error
semantics and the polite live region. `loading` passes through unchanged. Both must leave the
entered name and the identity rows untouched, which the buffer's key guarantees and test 3 proves.

## Testing strategy

One device suite, `app/src/androidTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt`,
mirroring `MobileModalTest`'s local-host shape. No unit test: there is no logic outside
composition, and extracting a one-line `.take` to make one would be ceremony.

1. **Frame** (AC1) — both identity rows present with their labels and values, each inert: no
   `EditableText`, no `SetText` action, not focusable. Name field pre-filled with the caller's
   name. Unpair action, Close, Cancel and OK present.
2. **Reporting** (AC2) — typing then OK reports the entered name once and does not close; Unpair
   reports once, submits nothing and does not close; Close, Cancel and Back each report one
   dismissal and no submission.
3. **Error and loading** (AC3) — after typing, flipping `error` and `loading` leaves the field's
   text and both identity rows as they were, and the error reaches the shell's live region.
4. **Unbounded text** (AC4) — a 4000-character identity and relay address render clamped to
   `MAX_WORKSPACE_LABEL_CHARS` with no node carrying the full string, and in a narrowed host each
   value stays on one line with its right edge inside the row.
5. **Small viewport** (AC5) — at 320 × 640 dp both identity rows, the field and the Unpair action
   are reachable through the shell's scroll area, and Unpair measures at least 48 dp on both axes.

RED first: the component lands as a signature-complete stub rendering the shell with empty
content, the suite runs against it and fails on assertions rather than on compilation, then the
content fills in. Focused verification per builder § B2: `testDebugUnitTest` is untouched by this
change, so the gate is `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin` and the managed
API 33 device run of `EditHostModalTest`. No rung-3 scenario: this slice ships no operator-facing
flow — nothing opens the modal until #744.

## Open questions

- Whether `submissionEnabled` earns its place before #744 exists to drive it. Kept because the
  shell's contract puts validation in the caller and a passthrough costs no logic; revisit if #744
  never sets it.

## Documentation handoff

Pending for the documentation stage. `docs/knowledge/features/mobile-modal.md`, in the opening
paragraph that describes the shell's callers: "It currently has no production consumers" becomes
wrong with this slice, and `EditHostModal` is the first consumer to name there. Not edited here.

## Security review

**Verdict:** PASS

The pass revised the design before this section was written: the `initialHostName` clamp and the
raw-parameter `remember` key under Design are its output, not the first draft's.

**Findings:**

- [Trust boundaries] The boundary is the parameter list itself. `serverIdentity` and
  `relayAddress` originate in a scanned QR payload and are therefore attacker-influenceable in
  length; `initialHostName` is the same stored record's field. The design makes the boundary a
  single named `boundedText` applied to all three at the top of the composable, before layout and
  before any merged semantics node, and there is no downstream — the component renders and
  reports. The first draft clamped only the two the ticket names; **SHOULD FIX, folded in**: the
  seed reaches text layout identically and `TreeHostRow` already clamps the same value.
- [Trust boundaries] OUT OF SCOPE — bidi/RTL-override characters inside a clamped value can make
  a hostile relay address or identity *render* as a different string than it is. The component
  neither acts on the value nor authorises anything with it, and the operator reaches this modal
  from a specific host's row, so the exposure is "the confirmation text for an unpair could be
  dressed up." That confirmation is #745's, which is where a display-safety rule for these two
  values belongs; it is a pipeline-wide property of every row that renders stored text, not
  something this slice introduces.
- [Tokens, secrets, credentials] AC1's "nothing it renders can expose a token, key or
  fingerprint" is satisfied structurally rather than by care: the component depends on no store,
  no keystore and no repository, so it cannot read a secret it was not handed. The residual risk
  is a caller passing one in. **SHOULD FIX**: state the obligation in the KDoc — callers pass
  display text, never a device token, static key or raw fingerprint, and never a value that is
  itself a secret.
- [File / storage] Not applicable by design, and structurally so: no store dependency exists to
  write through. AC2 states it as a requirement; the parameter list is what enforces it.
- [Inter-process / Android surface] No intent, deep link, pending intent, provider or WebView.
  Screenshot and accessibility exposure considered and deliberately not hardened: a server
  identity is a public identifier and a relay address a public endpoint, neither is a credential,
  and `FLAG_SECURE` on this modal would diverge from the shell without protecting anything. The
  name field is likewise not a credential field, so no third-party-keyboard hardening
  (`KeyboardType.Password`, no-personalised-learning) is warranted.
- [Cryptographic primitives] Not applicable, and for a reason worth stating: the component
  displays an identity it never derives, compares or verifies. Verification of a server identity
  belongs to the pairing confirmation gate, which already shipped; a future reader must not read
  this row as a trust decision.
- [Network & I/O] No I/O. **SHOULD FIX**: the rendered relay address is display text only and
  must never be the URL anything opens or connects to — the live URL comes from the stored record
  through `RelayConnectionSupervisor`. Say so in the KDoc, as `workspaceDisplayName` says it for
  a workspace label.
- [Errors, logs, telemetry] The live risk in this file. The component adds exactly one log call,
  for the unpair intent the shell cannot see, and it carries a static event string and nothing
  else — no identity, no relay address, no host name — debug-gated in the shell's
  `logModalEvent` shape. **SHOULD FIX**: the KDoc states that a caller-supplied `error` is
  rendered verbatim by the shell, so callers must keep it generic rather than embedding an
  identity or relay address in it.
- [Concurrency] No coroutine, no scope, no flow. The one hazard is the `remember` key: keyed on
  `error` or `loading` it would discard the operator's typing on the first failed save, which AC3
  forbids outright, and keyed on the *clamped* identity two hosts sharing a 128-character prefix
  would share one buffer. **SHOULD FIX, folded in**: key on the raw `serverIdentity`, and prove
  retention in test 3.
- [Threat model] Malicious relay and hostile daemon frame are both out of reach: this component
  holds no connection and its text comes from the operator's own scanned record, not from an
  inbound frame. Token theft from disk is untouched — nothing is stored. UI-side leakage is
  addressed under the Android-surface finding above.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

## Revisions

**2026-09-21 — field text colour, and the measured fill.** The Design source table named the
text-area *fill* but not the text inside it. The frame draws the entered name in
`schemes/on-background`; the implementation uses `onPrimaryContainer`, because the field sits
inside the shell's `primaryContainer` surface and `onBackground` is the app background's pairing,
which carries no contrast guarantee here. Same reason the table's first row gives, applied to the
foreground.

`FIELD_FILL_ALPHA` settled at 0.12. Composited against the real theme values, the well separates
from the shell's surface by 17–23 per channel in both schemes (light `#CFE4FF` → `#B8D2EE`, dark
`#134A74` → `#2A5C85`), against 9–14 for the reference's own dark-only well. Slightly stronger
than the reference by design: the reference never had to survive a light scheme.
