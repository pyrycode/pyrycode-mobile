# Shared mobile modal callers

See [the shared shell](mobile-modal.md) for layout, behavior and verification.

## Callers

[`RenameDialog`](rename-dialog.md) is the thread Rename action's editing-shell caller. It keeps Rename/Name/Cancel/Save copy and a single selected, prefilled field. The content mirrors the shared Input large label gap and well geometry with modal field colors and the `modalControl` shape; the shell supplies the close row, footer, scrolling and IME avoidance. Save and Done share the changed, nonblank trimmed-name guard, while Cancel, Close and Back dismiss. Figma has no dedicated Rename composition; the [labelled comparison](../../../app/src/androidTest/assets/rename-1278/labelled-component-comparison.png) checks shared Modal `489:1942` and Input large `347:6446` against the 412 × 892 emulator capture.

`SettingsScreen` uses `MobileDismissModal` for its notifications-only dialog. This variant puts content directly below the header and one filled Done action at the footer's right edge. Close, Done and dialog Back dismiss the route; the sound row is inert. Its push switch remains backed by `AppPreferences`. At compact width, the text column must take the available width: a fractional intrinsic width can report visual overflow even when a semantics text matcher passes. The [device capture and text-layout check](settings-screen.md#wiring-and-verification) cover that case.

`DebugBundleModal` still calls the editing shell, but the Settings Storage row that opened it was removed. Its download state machine remains documented in [SettingsViewModel](settings-viewmodel-how-it-works.md#log-data-download-683); it is not a current Settings caller.

[`EditHostModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt)
(#743) draws the shell's content with two inert identity rows, a name field
pre-filled from the caller, and an outlined unpair action. It is stateless and
caller-driven like the shell itself — no storage, connection or navigation — which
is what lets more than one screen compose the same component instead of a second
removal flow. [`ChannelListScreen`](channel-list-screen-tree-and-controls.md#host-row-edit-control-744)
(#744) is its first driving caller: the tree's host row opens it on that row's own
host, [`ChannelListViewModel`](channel-list-viewmodel.md) reads the identity and relay
address with `PairedServerCollectionStore.loadById` at open time and saves the entered
name with `setDisplayName`, mapping a blank name to `null`. `Unpair host` was wired to
an empty lambda there until #745, which gives it a `confirmingUnpair` flag plus
`onUnpairConfirmed` / `onUnpairDeclined` callbacks: while confirming, the shell's four
content children are replaced **in place** by a prompt naming the host (never its
identity or relay address), and the shell's own `title`, `onSubmit` and
`onDismissRequest` are swapped so its existing Cancel/OK footer carries the decision
instead of a second `Dialog` stacking over the first — `MobileModal` is itself one, so
a stacked confirmation would give the phone two back targets for one decision. The
prompt names the host from this component's own already-clamped `boundedName`, the
same fallback the host row uses, so a caller cannot bypass the clamp by formatting an
unbounded name into the confirmation. Declining (Cancel, system Back) returns to the editor. The close glyph is the one route the shell does not hand to the confirmation: `MobileModal`'s `onCloseRequest` defaults to the same callback as Back and Cancel, so before #1560 the close glyph also declined, leaving an operator who meant to leave stuck back in the editor. `EditHostModal` now passes its own `onDismissRequest` as `onCloseRequest`, so the X always closes the whole modal without unpairing, while Cancel and Back still decline back to the editor. See [ChannelListViewModel](channel-list-viewmodel.md#wiring) for the removal itself.

`HostEditorModal` remains the shared binding for Edit host on the channel list. Settings no longer opens it. Its controller, presence rule, loading mapping and failure copy remain described in [Host editor](host-editor.md).

At the [dark 412 × 892 reference](../../specs/architecture/1277-edit-host-content-figma.md#design-source),
the centered Edit host content has two 20 dp read-only identity rows with 12 dp
between them. Each value follows its natural-width semibold label by 10 dp and
uses one-line ellipsis; below 320 dp of content width, the rows reserve one
weighted share for the label and two for the value so enlarged labels can wrap
without collision. The separate Name label sits 8 dp above a 52 dp filled
`BasicTextField` well. M3 `TextField` retained a 56 dp minimum, so it could not
meet that measured well height. The outlined Unpair host action follows with
8 dp extra top space, a 40 dp visible height at default text scale and at least
a 48 dp click target. Its outline and click target grow with enlarged text so
the label stays inside the border; the well and outline use the shared 6 dp
`modalControl` shape. The shell supplies the scrolling and footer reachability
when width or the keyboard constrains the form. The [emulator capture, Figma
render and labelled overlay](../../../app/src/androidTest/assets/host-content-1277/comparison-412x892.png)
preserve both 412 × 892 viewports; Android system bars shift the shell relative
to Figma's bar-free frame. Figma supplied no loading, error, confirmation,
compact-width, enlarged-text or keyboard reference; the [capture
context](../../../app/src/androidTest/assets/host-content-1277/capture-context.txt)
records that limit.

Patterns worth reusing for the next
caller that pre-fills an editable field inside this shell:

- **Key a pre-filled edit buffer on the identity of the thing being edited, not on
  its current value.** `EditHostModal` keys its `remember`ed `TextFieldValue` on the
  raw `serverIdentity`, never the display name and never a value clamped for layout.
  Keying on the display value would discard what the operator typed the moment the
  caller's stored copy changed underneath it (an error or loading flip must not lose
  typed input); keying on a clamped value risks two different edited entities
  collapsing onto one buffer if their unclamped identities happen to share a long
  prefix.
- **Field colours need an explicit light/dark mapping.** The reference's `onPrimary`
  at 41% works over the dark navy shell, but turns white in light. Applying the light
  fallback (`onPrimaryContainer` at 12%) to both themes made dark wells too light
  after the navy shell fix. Use the [shared field roles](mobile-modal.md#layout-and-theme): dark
  keeps the reference well and `onBackground` entered text; light keeps its previous
  tint and foreground. Check the composited well in both themes when changing either
  the field or its shell.
- **Proving a row is read-only means asserting the absence of field semantics**
  (`EditableText`, `SetText`, `Focused`), not asserting that its text is displayed —
  the latter passes identically against a real editable field seeded with the same
  value.
- Attacker-influenceable display text (here, a server identity and relay address
  from a scanned QR payload) is clamped through the same
  [`MAX_WORKSPACE_LABEL_CHARS`](../../../app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt)
  bound `boundedRowText` uses on the host row this modal opens from, applied before
  layout and before any merged-semantics description is built — including the
  pre-filled seed value, not only the two fields the acceptance criteria named.
- **`Espresso.pressBack()` does not wait for Compose to recompose.** Pressed
  immediately after a click that switches the shell into its confirmation step, Back
  can still land on the editor step's `onDismissRequest` rather than the confirmation's
  decline callback, because the confirmation has not yet drawn. Assert the confirmation
  is displayed before pressing Back (`EditHostModalTest`, `EditWorkspaceModalTest`, #1560).
- **A confirmation step inside this shell is a content swap, not a second `Dialog`
  (#745).** `MobileModal` is itself a `Dialog`; stacking a second one over it gives the
  phone two back targets and two dismiss-outside behaviours for what is really one
  decision. The caller instead branches its own content and passes a different
  `title` / `onSubmit` / `onDismissRequest` triple for the confirming state, so the
  shell's single footer and single dismissal funnel keep deciding for both steps. The
  cost: the shell's footer labels are fixed ("Cancel" / "OK"), so a destructive
  confirmation is confirmed by a button reading "OK" — restyling per caller would
  touch a component with other callers, so the prompt copy has to carry that weight
  instead of the button.

[`EditChatModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/EditChatModal.kt)
(#826) is the shell's third direct caller — like `DebugBundleModal`, it draws `MobileModal`
itself rather than going through `EditHostModal`/`HostEditorModal`. It is desktop's
`EditChatDialogView` on the phone: a "Channel name:" field pre-filled from the caller, clamped to
`MAX_WORKSPACE_LABEL_CHARS`, and an outlined "Archive chat" action, following `EditHostModal`'s
identity-keyed buffer and content-free debug-log patterns. Its two actions read different halves
of one guard, matching desktop: OK needs a non-blank trimmed name, an available host and no write
in flight; Archive needs only the host and no write in flight, independent of the field's content,
and takes no confirmation step, since an archived chat comes back through Archive's Restore.
[ChannelListScreen](channel-list-screen.md) is its first caller (#827): a Chats row's pencil —
since #1523, drawn only on the selected row, reached by opening it and pressing Back — opens it on
that row's own host and conversation, and OK renames through that host's
`ConversationRepository.rename`, resolved at the press — see
[ChannelListScreen § tree and controls](channel-list-screen-tree-and-controls.md#chat-row-edit-control-827)
and [ChannelListViewModel](channel-list-viewmodel.md#wiring). Archive chat was wired in #828, on the
same guard's other half: `live.archive(conversationId)` on the same host-resolved-at-the-press
repository, no confirmation step (desktop parity — Archive's Restore undoes it), and no read of the
name field either way, success or failure.

The current Figma Edit Chat content uses the title “Edit Chat” and the existing
“Channel name:” label. Its name well measures 52 dp: a Material `TextField` kept a
taller minimum, so this caller uses a plain `BasicTextField` in the shared
`modalControl` well. The edit chat and edit channel archive actions draw 40 dp
outlines with separate 48 dp minimum touch areas and the same shape token.

A clamp on attacker-influenceable text must not split a UTF-16 surrogate pair when the clamped
value can round-trip back into a write unedited. `EditChatModal` seeds its field with
`initialName.take(MAX_WORKSPACE_LABEL_CHARS)`, then drops a trailing lone high surrogate — a plain
`take(N)` can land mid-pair, and OK sends the field back exactly as typed, so a split pair would
reach `rename_conversation` as a malformed tail. `EditHostModal`'s `boundedText` and
`workspaceDisplayName` (`ui/workspace/WorkspaceDisplayName.kt`) now drop the same trailing high
surrogate (#851), closing the round-trip gap for daemon-written host names and workspace labels.
The `HostEditor.submitName` save clamp (`name.trim().take(MAX_WORKSPACE_LABEL_CHARS)`, see
[host editor](host-editor.md)) still applies a plain `take` and can split a pair in an
operator-*typed* name at the 128-char boundary; #851's security review flagged this, alongside
`HostIdentityRow`'s `boundedRowText`, `ArchivedDiscussionsScreen` and `DebugBundleDownload`, as
out of that ticket's scope and left for a follow-up.

[`AddWorkspaceModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/AddWorkspaceModal.kt)
(#904) is the shell's fourth direct caller — like `DebugBundleModal` and `EditChatModal`, it draws
`MobileModal` itself. It is desktop's host-row Add workspace dialog on the phone: a list of that
host's recent folders as `selectable(role = RadioButton)` rows (a `labelLarge` SemiBold "Recent"
section label, paths in `bodyMedium` monospace, following `EditHostModal`'s field-label styling)
plus an outlined "Create new folder under pyry-workspace…" action styled like `EditChatModal`'s
Archive action, which opens the existing [`CreateFolderDialog`](create-folder-dialog.md) stacked
as a second window over the shell. OK needs a selected folder and an available host; `MobileModal`
also disables it while `loading`. Its former host-row long-press entry was removed in #1190;
[`WorkspacePicker`](workspace-picker.md#consumers) stays for the thread — see [ChannelListScreen § Add controls](channel-list-screen-tree-and-controls.md#add-controls-738)
and [ChannelListViewModel](channel-list-viewmodel.md#wiring) for the host-resolved state machine
this caller is bound to.

A created folder becomes the caller's `selected` value without starting anything — creating and
submitting are two separate transitions, so a folder made in the stacked dialog does not fire OK
on its own. `selected` and `recent` are daemon-authored paths: rendered only as `Text`, clamped to
`MAX_PATH_DISPLAY_CHARS` (512) without splitting a surrogate pair — the same round-trip-safe clamp
`EditChatModal`'s field uses, load-bearing here too since the raw (unclamped) path is what
`onSelect` reports back and what the caller eventually sends to `createDiscussion` — and the
caller caps the recents list itself (`MAX_ADD_WORKSPACE_RECENTS = 50` in `ChannelListViewModel`)
since this shell's content column is not lazy. A selection absent from `recent` (the just-created
folder) draws in its own "New folder" section, so the current selection is always visible even
before the next reopen re-fetches recents.

**A caller-scoped recents list is a `combine` pairing hazard, not just a fetch.** The first draft
paired an untagged `flatMapLatest`-derived recents flow with the open modal's target in one
`combine`, so for one emission after retargeting to a different host the new target's state could
still carry the previous target's daemon-authored folder list — the security review's one MUST
FIX on this ticket. The fix tags each emission with the host it was fetched for and publishes it
only when that tag matches the currently open target; see
[ChannelListViewModel § the tagged recents combine](channel-list-viewmodel.md#wiring) for the
mechanism. Worth checking for any future caller that derives a host-scoped list alongside a
host-scoped open/close flag through the same `combine`.

[`EditWorkspaceModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/EditWorkspaceModal.kt)
(#905) is the shell's fifth direct caller — like `EditChatModal`, it draws `MobileModal` itself. It is
desktop's `EditWorkspaceDialogView` on the phone: one "Workspace name (optional):" field seeded from the
caller and an outlined "Archive workspace" action, in the same field and action styling `EditChatModal`
established. `serverId` and `cwd` key the edit buffer and are never rendered, logged or reported — the
same identity-keyed-buffer pattern `EditHostModal` established. OK is enabled on an available host and a
label the daemon would accept; a blank name is allowed, since it clears the label rather than failing
validation. The field's `supportingText` shows the trimmed name's size against the daemon's own unit,
"UTF-8 bytes: n/128" (chosen over the plan's "n/128 bytes" because Android Lint's `PluralsCandidate` flags
a bare number-then-word as a pluralizable string; leading with the unit avoids that without a plurals
resource for what is really a counter). Archive workspace swaps the content for a confirmation in place —
`EditHostModal`'s unpair shape again — naming the workspace and warning that every active chat and channel
there moves to Archive; the shell's own footer carries the decision (OK confirms, Cancel and Back decline),
while the close glyph passes its own `onDismissRequest` as `onCloseRequest` (#1560) and closes the whole
modal instead of declining, and the typed name survives a decline because the buffer is keyed on identity,
not on the confirmation flag. [ChannelListScreen](channel-list-screen-tree-and-controls.md#workspace-row-edit-and-archive-control-905)
(#905) is its first and only caller: every workspace row's own pencil, in both sections, opens it on that
row's own host and exact `cwd` — see that section and
[ChannelListViewModel](channel-list-viewmodel.md#wiring) for the label rule and the write targeting.

The seed and the confirmation's name are both daemon-authored (the row's shown name) and both clamped once
by `clampWorkspaceText` inside the modal before they reach layout or the prompt's format argument — the
same round-trip-safe, surrogate-pair-aware clamp `workspaceDisplayName` uses. The label rule
(`workspaceLabelFor`, `ui/workspace/WorkspaceDisplayName.kt`) treats that same clamped cut as the folder's
own name too, so an untouched OK on an overlong folder seed clears the label instead of storing the cut as
a new one — but only when the clamp actually cut the folder name; an uncut name is compared exactly and
untrimmed, so a folder whose real name carries trailing whitespace is not silently treated as matching its
own trimmed display. This asymmetry was a two-round fix during verification: the first attempt trimmed
every folder-name comparison, which cleared labels for names it should not have matched.

**A Compose semantics trap in this field's test.** `TextField`'s `supportingText` composes into the
field's own merged `Text` semantics, so `assertTextEquals(typed)` fails against the byte-count line even
when the typed value is correct. Use `assertTextContains(typed)` for any field in this shell that pairs a
value with supporting text.

The [four 412 × 892 emulator captures and labelled Figma comparisons](../../../app/src/androidTest/assets/forms-1217/context.txt)
cover Create channel, Edit Chat, Edit channel and Save as channel. Figma's shell is
412 × 892, but each form-content export is 676 px wide; there is no 412 × 892
form-content reference. Compare field spacing and control dimensions, not full
form positions. Edit channel and Save as channel captures include the keyboard
from initial focus, so their vertical positions cannot be compared with the
keyboard-free exports. The reference has no compact-width, enlarged-text,
keyboard, disabled, loading, failure or prompt-reading state. Edit channel's
20 dp mute checkbox sits in a 48 dp toggle row; the extra vertical space is
the mobile touch target, not a content-frame match. The required API 33 ATD
gate checks footer geometry because that image can return a black framebuffer;
the full Pixel 8 captures supply the pixel comparison.

[`SaveAsChannelDialog`](save-as-channel-dialog.md#shape)
(`ui/conversations/components/SaveAsChannelDialog.kt`, #957) is the shell's sixth direct caller — like
`EditChatModal`, it draws `MobileModal` itself. It replaces a channel name and system prompt
`AlertDialog`-with-workspace-radios pair with this shell's fixed Cancel/OK footer: a "Channel name:"
field seeded from the conversation's own name (or "New channel"), clamped to `MAX_WORKSPACE_LABEL_CHARS`
the same way `EditChatModal`'s field is, and an optional multi-line "Channel system prompt:" field that
always opens empty. Both fields are pulled into a standalone, reusable `ChannelFormFields` composable
(`ui/components/ChannelFormFields.kt`) rather than kept private to this caller, since
[`CreateChannelModal`](#callers) (#958) reuses the same form. OK promotes the conversation in place
(`ConversationRepository.promote(id, name, workspace = null)` — no dedicated-folder choice any more,
following desktop's pyrycode-desktop#1436) and, once that is confirmed, writes a non-blank prompt
verbatim with `setSystemPrompt`; a blank prompt writes nothing. `nameEditable = false` locks the name
field once the promote leg is confirmed, so a retry after a prompt-write failure never repeats the
promote. See [Save as channel](save-as-channel-dialog.md) for the full two-write state machine, its
`compareAndSet` terminal transitions, and why `SaveAsChannelSubmit`'s `toString` redacts the prompt.
[ThreadOverflowMenu](thread-overflow-menu.md)'s discussion-only **Save as channel…** item is its only
caller.

[`CreateChannelModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/CreateChannelModal.kt)
(`ui/components/CreateChannelModal.kt`, #958) is the shell's seventh direct caller — like
`SaveAsChannelDialog`, it draws `MobileModal` itself around `ChannelFormFields`, and desktop's
`CreateChannelDialog` is its analogue. Both fields open empty (there is no existing conversation to seed
from), and `nameEditable = false` locks the name once the create leg is confirmed — the identical
retry-never-repeats-the-first-write shape `SaveAsChannelDialog` uses, with `createChannel` in the first
leg's place instead of `promote`. `serverId` and `cwd` key both buffers (`remember`, not
`rememberSaveable` — the prompt may hold a pasted secret) and are never rendered: the title is the static
string "Create channel," never the target path. [ChannelListScreen § Workspace row create-channel
control](channel-list-screen-tree-and-controls.md#workspace-row-create-channel-control-958) is its only
caller: every Channels-section workspace row's own plus opens it on that row's own host and exact `cwd`
— see that section and [ChannelListViewModel](channel-list-viewmodel.md#wiring) for the two-write state
machine and why a second host sharing the same `cwd` is never addressed.

[`EditChannelModal`](../../../app/src/main/java/de/pyryco/mobile/ui/components/EditChannelModal.kt)
(`ui/components/EditChannelModal.kt`, #667) is the shell's eighth direct caller — like `EditChatModal`, it
draws `MobileModal` itself around `ChannelFormFields`, a private `MuteNotificationsRow` (#1021, between
the prompt field and Archive) and a private outlined `Archive channel` action
copied from `EditChatModal`'s `ArchiveAction` (a verifier SHOULD FIX left for a follow-up: a shared
`internal` action taking a `@StringRes` label would keep the two from drifting apart). `MuteNotificationsRow`
is the app's second whole-row checkbox after `ThreadPermissionModal`'s `AlwaysAllowOffer` — a `toggleable`
`Row` with `Role.Checkbox`, an M3 `Checkbox(onCheckedChange = null)` in `colorScheme.tertiary` and a
label-medium SemiBold label, at the shell's 48dp touch floor — and a `muted` buffer, `remember(conversationId)
{ mutableStateOf(initialMuted) }`, the same per-conversation keying the name and prompt buffers use. It edits an
**existing** channel's own name and already-stored system prompt in place, unlike `CreateChannelModal`
and `SaveAsChannelDialog`, which only ever write a system prompt into a conversation with no stored one.
The name buffer is `remember(conversationId)`, prefilled from the caller's `initialName` — the row's own
host's snapshot name, clamped to `MAX_WORKSPACE_LABEL_CHARS` the same surrogate-safe way `EditChatModal`'s
field is. The prompt buffer is `remember(conversationId) { mutableStateOf<String?>(null) }`: the field
shows `typed ?: read.prompt.orEmpty()` and stays **disabled** — with a static reading line under it in
`ChannelFormFields`'s new `promptNote` slot — until the caller's `prompt: ChannelPromptReading` reading
arrives as `Read`, at which point it shows the stored prompt verbatim and a `Differs` status adds a
static next-session line in the same slot. Until the field is enabled, `onSubmit` reports the prompt as
`null` rather than an empty draft, so nothing the operator never saw can be written. Emptying the field
after it is enabled and pressing OK clears the stored prompt rather than storing `""` — `submitChannelEdit`
sends `null` for a draft over a stored prompt that the operator emptied, desktop's `promptWriteFor` rule
(#1342) — while leaving the field exactly as it loaded and pressing OK sends nothing. OK needs an
available host, a non-blank trimmed name and (when the prompt is showing) a draft within
`SystemPromptLimit.MAX_BYTES`; Archive needs only the host and no write in flight, independent of either
field, with no confirmation step — an archived channel comes back through Archive's own Restore, the
same parity `EditChatModal`'s Archive established. [ChannelListScreen](channel-list-screen.md) is its
only caller: the Channels row's own pen — the same pen shape #827 gave Chats rows, now
generalised behind `TreeConversationRow`'s `editDescription: @StringRes Int` parameter — opens it on
that row's own host and conversation. Since #1523 the pen draws only on the selected conversation row
(the one last opened and left with Back), so Edit channel, like Edit chat, is reached by opening the
row, pressing Back and tapping its pen; it reads the stored prompt once the row's host has a live
repository, opens the checkbox at that host's own stored `Conversation.muted` (#1021), and OK writes
only what changed — a rename, then a mute write, then the prompt, each independently, in that order —
through the
repository resolved **at the press** — see [ChannelListScreen § Channels row edit control
(#667)](channel-list-screen-tree-and-controls.md#channels-row-edit-control-667) and
[ChannelListViewModel § Wiring](channel-list-viewmodel.md#wiring) for the two target-tagged state flows
that keep a prompt read from ever landing on a write's own `compareAndSet`, and for why this caller
resolves the repository at the press rather than binding one at construction the way
[`SystemPromptEditor`](system-prompt-editor.md) does.

**`PermissionModalOverlay`** (`ui/conversations/thread/ThreadPermissionModal.kt`, #815) is the first of
[`MobileGateModal`](mobile-modal.md#the-hardened-gate-mobilegatemodal)'s two callers, and the only one using it rather than
`MobileModal` before #661. It draws the [permission-modal overlay](permission-modal-overlay.md): the server
`title` fills the gate's header, the prompt and the wire-order option list fill `content`, and the footer's
only action is Cancel — the server's own options are the actions, so this caller cannot use the fixed
Cancel/OK footer `MobileModal`'s other callers share. Landing it before the three sibling tickets it was
split from (see the plan's Context) was deliberate, so those write their content into the final gate
container instead of one about to be replaced.

**`QuestionBatchModal`** (`ui/conversations/thread/QuestionBatchModal.kt`, #661) is
[`MobileGateModal`](mobile-modal.md#the-hardened-gate-mobilegatemodal)'s second caller, and the first to use its
submit/sending/error extension: `submitLabel` = "Continue", `submissionEnabled` = every question answered,
`sending` = a send in flight or already succeeded (locked until the daemon's dismissal, not just until the
send settles), and `error` a fixed string while the last send failed. Unlike `PermissionModalOverlay`, which
draws its own gate call inline in `ThreadScreen.kt`, this caller is drawn directly from `MainActivity`
beside `ThreadScreen` rather than inside it — `MobileGateModal` opens its own `Dialog` window, so its place
in the composition tree does not affect what it draws over. See
[Question batch modal](question-batch-modal.md) for the full caller contract.

[`BackgroundTaskPanel`](../../../app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt)
(#678, #1041) uses `MobileReadOnlyModal` inside `ThreadScreen`, with conversation-keyed visibility
toggled by the [Actions menu](thread-composer-footer-actions-menu.md#actions-menu-884).
See [panel placement](thread-screen-how-it-works-overlays-and-app-bar.md#background-tasks-panel-placement-post-678).
It lists the conversation's `BackgroundTaskRoster?` read-only, with three
readings: `null` draws a dashed ring, "No background-task report yet" and "The daemon has not reported on
this conversation since the app connected."; an empty roster draws a solid ring, "No background tasks" and
"Claude has nothing running in the background for this conversation."; a listed roster splits `tasks` into a
"Running · n" group (`filterNot { it.isFinished }`) and a "Finished · n" group (`filter { it.isFinished }`),
each in claude's order and each undrawn when empty — `droppedTasks > 0` both raises a filled
`secondaryContainer` partial-list notice above the groups and switches both counts to "n shown".

Both empty readings use a 160dp top inset inside scrollable content (#1164), with a
32dp ring and 12dp gaps. A trailing spacer absorbs slack, keeping the inset stable
across heights. The accessible header places the ring at 265dp from the shell top
at 412dp width; the reference's 249dp absolute coordinate is not the invariant.
Any partial notice stays before the reading. At short heights, the shell retains
pinned or compact scrolling, keeping the header, supporting text and both Close
actions reachable; see [layout modes](mobile-modal.md#layout-and-theme).

The reported-empty ring is drawn with a 2dp outline; the never-reported dashed
ring uses the supplied 64px image at 32dp. Their copy and meanings stay distinct.

The fixed-dark 412 × 892 reference was inspected on 2026-09-30: [populated
`568:877`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-877),
[capped `568:932`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-932),
[empty `568:981`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-981),
[never reported `568:997`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-997),
and [task tags `563:1054`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=563-1054).
The [checked-in comparisons](../../../app/src/androidTest/assets/task-panel-1295/)
pair each render with a nonblank Pixel 8 API 35 capture; the populated state also
has an aligned overlay. The capped and empty Figma PNGs omit visible header art
that their design trees include, the capped frame says eight shown but draws three
cards, and the never-reported frame alone uses a 20dp close glyph. Keep the
shared header and 28dp close control, and render every held task in the capped
roster. The API 33 ATD captured black pixels despite passing geometry checks;
use the full emulator for visual comparisons, as described in
[Compose evidence](development-verification-compose-evidence.md#compose-evidence).

Each task is a card: the raw `taskType` in monospace beside a
[`TaskStatusTag`](../../../app/src/main/java/de/pyryco/mobile/ui/conversations/thread/TaskStatusTag.kt) pill
(the Figma "Task status tag" component; Running `primaryContainer`/`onPrimaryContainer`, Completed
`colorScheme.success` on a 16% tint of itself — the [success slot](success-color.md#usage)'s second consumer
— Failed `errorContainer`/`onErrorContainer`, Stopped `secondaryContainer`/`onSecondaryContainer`, capped to
160 dp and one line so a long word cannot widen the row), then the description (monospace when `taskType ==
"local_bash"`, a shell command line), the finish summary, and, only when the task was updated mid-life, a
"Latest update" label over a `surface` code block holding the latest patch — italic "No change reported"
when the patch is empty, no label or block at all when `latestUpdate` is `null`.
The local dark styling uses 13/19sp group labels, 14dp horizontal and 12dp vertical
card padding, 8dp row gaps, 2dp progress gaps, 13sp description and progress text, and
a 12/17sp latest-update label. The tag uses a 10dp corner, a 6dp dot and an 11/16sp
medium label, with its existing 160dp width cap.

Every panel and tag text style carries `TextStyle.untrimmedLineBox()` (#1534,
`BackgroundTaskPanel.kt` and `TaskStatusTag.kt`): `copy(lineHeightStyle =
LineHeightStyle(Alignment.Center, Trim.None))`. Compose's default `LineHeightStyle`
trims the half-leading above the first line and below the last, so a single-line
`lineHeight = 19.sp` text measures only 15dp — a loss the #1041 redraw tried to hide
behind stretched gaps (12dp row, 6dp progress), but those only matched one content
shape, and the shortfall (4dp on the banner, 3dp per heading, 1dp on some cards) still
accumulated down the panel. `Trim.None` makes every text box exactly `lineHeight ×
lines`, matching the Figma frames' CSS line boxes for any number of lines, which is
what let the row/progress gaps and the cut marker's vertical padding go back to the
frames' own 8dp/2dp/2dp values — see `BackgroundTaskPanelSpacingTest`. A Figma border
sits inside its CSS box, but a Compose `drawBehind` stroke takes no layout space, so
`CutMarker`'s padding adds the border's width back in.

The tag resolves from `finish`: unfinished reads Running; `finish == null` (the reconnect case — a task marked finished with no
terminal frame ever arriving) reads Finished in the Stopped style; the wire's three known terminal words
(`completed`/`failed`/`stopped`, exact match) read Completed/Failed/Stopped; any other word is shown as
itself in the Stopped style — except a blank word, or one that spells "running" in any case once trimmed,
which falls back to Finished instead. That fallback is a security-review fix, not a style choice: an early
draft showed an unknown terminal status raw, so a daemon-sent status of `"running"` on a *finished* task
would have painted a Running tag — a claude-authored word passing for the app's own claim, and the one real
trust-boundary risk this redraw introduced. No terminal status can read Running now.

A partial-list notice, a "Truncated by the daemon" marker on a field the daemon's own `truncatedFields`
names, and one this client cuts for display at the same `MAX_PANEL_TEXT_CHARS = 4096` bound, are unchanged
in meaning from #678 — the task's own list (`description`/`task_type`) and an update's own list
(`patch`/`summary`) still read independently and never cross — only their look changed: the cut marker is
now a dashed `tertiary` chip and the partial notice a filled row, both still their own element straight
after the field they describe, never text joined onto it. Every field, the tag's word included, still
reaches only a plain `Text` through `printableText` + the 4096-char bound: no link, click, clipboard, parse,
`key()`, test tag or log. `printableText` drops ISO control characters but keeps Unicode bidi format
characters (e.g. U+202E), so a field can still be visually reordered to spell another word — an accepted,
pre-existing limit since #678 and not widened by this redraw, since a tag's style is chosen by exact match
on the raw word rather than on what renders. Closing the panel — any of the three routes above — sends
nothing and changes no task or conversation state.

A running card's progress (#1044, the Figma Populated frame) draws directly under the description and
above the finish summary / "Latest update", gated on `!task.isFinished && task.progress != null` — the
panel gates on `isFinished` itself rather than trusting that the #1042 projection already nulls `progress`
on finish. The block is the activity line (the held frame's `description`, `bodyMedium`/`onSurfaceVariant`,
through the same `TaskField` + cut-marker treatment as every other field) then a meta line
(`bodySmall`/`outline`) joining the last tool name and three client-formatted counters with " · ", e.g.
"Bash · 4 tools · 18k tokens · 2m 41s". `subagentType` is decoded onto the held frame but never rendered.

The progress frame carries its own `truncatedFields` — a *third* independent list alongside the task's own
and an update's own, never crossing either: the task's own list naming `description` does not mark the
activity line, only the progress frame's own list naming `description` does, and naming `last_tool_name`
marks the meta line instead. An empty last-tool-name drops its segment rather than leaving a stray leading
separator. The three counters (`BackgroundTaskProgressFormat.progressCounters`) format purely from the
frame's three `Long` readings, never a daemon string: singular exactly at 1, tokens whole under 1000 then
half-up-rounded thousands with a "k" suffix (division/remainder, not `+500`, so it cannot overflow), elapsed
as `Ns` under a minute, `Nm SSs` under an hour, `Nh MMm` (seconds dropped) beyond, and any negative reading
clamps to zero since the wire's counters are not guaranteed monotonic. Sharing one `Text` for the tool name
and the counters is an accepted limit, not an oversight: a hostile tool name could imitate a counter segment
or bidi-reorder the line, but the same author supplies the integers being formatted, so this grants no new
capability — the same accepted-limit shape as the tag's raw-word display above.
