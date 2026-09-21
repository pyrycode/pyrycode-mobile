# 723 — Show the default workspace label for the Settings host

## Files read

| Path | Symbol | Why it matters |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` | `defaultWorkspace` | The #714 host-keyed path projection this slice derives a label for; its blank-owner arm and `DEFAULT_SCRATCH_CWD` initial value both survive unchanged. |
| ” | `archivedDiscussionCount` | The existing projection over the same constructor-bound `ConversationRepository` — the shape (`.catch` swallow, `stateIn(WhileSubscribed)`) the new flow copies, and proof a second subscription on that repository is the established pattern. |
| ” | `SettingsViewModel`'s constructor | `conversationRepository` is already the destination's own owner's repository (rebound by #715), so this slice needs no DI change. |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` | `ThreadDestinationFactory.settings` | Confirms the above: it passes `repository(serverId)` built from the route's captured owner, never the compatibility facade. |
| `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` | `workspaceLabel(cwd)` (file-private), the "Default workspace" `SettingsRow` | The duplicate formatter #722's AC3 left for this slice to retire, and the one row that renders it. |
| ” | `ThemeMode.label`, the Default-effort row's `defaultEffort.label()` | Precedent: this screen already resolves row subtitles from display formatters called in the composable, not in the ViewModel. |
| `app/src/main/java/de/pyryco/mobile/ui/workspace/WorkspaceDisplayName.kt` | `workspaceDisplayName`, `MAX_WORKSPACE_LABEL_CHARS` | #722's shared rule and its length clamp — the single definition this slice consumes rather than duplicating. |
| `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` | `Conversation.cwd`, `Conversation.workspaceLabel`, `DEFAULT_SCRATCH_CWD` | The two fields matched on, and the sentinel that means "no bound workspace". |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` | `ConversationFilter` | `All` is the only filter that admits archived rows, which AC1 requires. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | `observeConversations`, `project` | `All -> true` includes archived conversations; the flow emits only after the first `list_conversations` reply lands (`projection.filterNotNull()`), which is what forces the `onStart` decision below. |
| `app/src/main/java/de/pyryco/mobile/MainActivity.kt` | the `Routes.SETTINGS` composable | The one production call site that collects this VM and passes the row's inputs. |
| `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` | `makeVm`, `stubRepo`, `archivedDiscussion`, `OWNER`/`OTHER`/`FOO`/`BAR` | The rig the new cases extend, including the two case-differing host ids and the filter-capturing stub. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` | `setSettings`, `archivedDiscussionsRow_rendersSupportingTextWithCount` | The row-wiring test pattern (scroll to a `hasText` node) the two new cases mirror. |
| `docs/knowledge/features/settings-viewmodel.md` | § What it does (`defaultWorkspace`), § Edge cases (blank owner) | Records why resolving through the unqualified preference is the cross-host leak #714 closed — the same trap a label lookup could reopen through the wrong repository. |
| `docs/knowledge/features/settings-screen.md` | § Default workspace row | The row's current contract and its picker flow, which this slice must leave intact. |
| `docs/specs/architecture/722-show-workspace-labels-in-the-conversation-thread.md` | § Design, § Security review | Defines the shared rule's arms and explicitly defers "the identical clamp-and-render obligation for … the Settings subtitle (#723)" to here. |
| `docs/specs/architecture/714-settings-workspace-picker-host-scope.md` | § Design | The host-keyed preference and picker triple this slice reads from and must not disturb. |
| `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md` | `conversations`, `conversation_updated` rows | Wire SSOT for the label: an opaque string echoed verbatim whose 128-byte bound is a size limit and not a safety property. Cited, not restated. |

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The Default workspace row (`17:56`) is a two-line list entry: headline `M3/body/large` on `Schemes/OnSurface`, supporting line `M3/body/small` on `Schemes/OnSurfaceVariant` reading `scratch`, and a 20dp trailing chevron — `16px` horizontal, `10px` vertical padding, identical to the Default-model and Default-effort rows above it. `SettingsRow`'s M3 `ListItem` already renders exactly this, and the screenshot confirms the rendered row matches. **This slice changes the supporting line's text source only**: no color, type style, spacing, shape, component or asset moves, so no `Schemes/*` variable or theme slot is read or added.

## Context

The Default workspace row formats the saved path as a basename. #714 bound that preference and the picker to the Settings destination's captured host; #720 landed `Conversation.workspaceLabel` and #721 keeps it live on the owning host's projection; #722 put the display rule in one place as `workspaceDisplayName(cwd, label)` and migrated the thread onto it, explicitly leaving this screen's private duplicate for this slice. So every input exists and only the lookup is missing: the row still shows a folder basename where the operator has named the workspace.

The lookup has one hard constraint — it must resolve against **this destination's own host**. `SettingsViewModel`'s `conversationRepository` already is that host's repository (rebound by #715 alongside the Archive destination), so this slice adds no DI wiring and cannot regress into the compatibility facade that #749/#714 removed.

No ADR is warranted: no new architectural seam, one derived projection and one display call.

## Design

**ViewModel — one new projection**, `SettingsViewModel.defaultWorkspaceLabel: StateFlow<String?>`, declared immediately after `defaultWorkspace`:

```kotlin
val defaultWorkspaceLabel: StateFlow<String?>   // the owning host's name for defaultWorkspace's
                                                // directory, or null when nothing there names it
```

It `combine`s the existing `defaultWorkspace` path flow with `conversationRepository.observeConversations(ConversationFilter.All)` and yields the first non-blank `workspaceLabel` among conversations whose `cwd` equals the path exactly. `All` is chosen over `Channels`/`Discussions` because it is the only filter admitting archived rows, which AC1 requires; the host-list cache is deliberately not used for the same reason (it excludes archived rows).

**Why null for an unbound default.** When the path is empty or `DEFAULT_SCRATCH_CWD` the flow yields `null` without scanning, so the row reads `scratch` through the shared rule's fallback arm. The sentinel means *no bound workspace*, and many conversations share it; matching on it would let an unrelated unbound conversation's name be presented as this host's default workspace. This is not a divergence from the thread's rule — the rule itself is untouched and arm 1 stays unconditional. What differs is the *input*: the thread's subject is a conversation that owns its label, while this row's subject is a path, and the sentinel is not one. AC1's "Empty/scratch paths display `scratch`" is satisfied by construction rather than by trusting the daemon to leave unbound conversations unlabelled.

**Why non-blank rather than non-null when selecting.** A conversation carrying `""` must not mask a properly named one later in the list; the shared rule treats blank as absent anyway, so selecting on non-blank keeps the two consistent and makes the choice order-independent.

**Why the conversation arm starts with an empty emission.** `RemoteConversationRepository.observeConversations` emits only after the first `list_conversations` reply (`projection.filterNotNull()`), so a bare `combine` would hold the whole projection at its initial value until the daemon answers — the row would read `scratch` while a bound path is saved, on every cold open and for the whole time a host is offline. `onStart { emit(emptyList()) }` makes the row show the cwd fallback immediately and upgrade to the label when it is known: fallback-then-better, never wrong-then-corrected.

**Screen — one new parameter.** `SettingsScreen` keeps `defaultWorkspace: String` (still the stored path) and gains `defaultWorkspaceLabel: String?`; the Default workspace row's `supporting` becomes `workspaceDisplayName(cwd = defaultWorkspace, label = defaultWorkspaceLabel)` and the file-private `workspaceLabel(cwd)` is deleted. Resolving display text in the composable is this screen's existing convention — the Theme and Default-effort rows directly above call `themeMode.label()` and `defaultEffort.label()` the same way — and it keeps the parameter that feeds the row honest about which of the two values is the path.

**Rejected alternative: resolve the whole display string in the ViewModel** and pass one `defaultWorkspaceDisplayName: String` in place of `defaultWorkspace`. It removes a theoretical one-frame pairing of a new path with the previous label (the two `collectAsStateWithLifecycle` reads are separate state writes, so Compose is not guaranteed to apply them in one recomposition). Rejected on cost: the rename cascades through `MainActivity`, both previews, `SettingsScreenTest` and roughly twelve `vm.defaultWorkspace` assertions in `SettingsViewModelTest`, taking the slice past the builder's ten-call-site boundary — and the alternative's only other exit, keeping the path flow public but production-dead, trades the flicker for a footgun that hands a caller a path under a display-shaped name. The window it closes needs a workspace pick while Settings is open, both writes to land in different frames, and the outgoing label to differ; it has not been observed, and both writes originate from one DataStore emission on `viewModelScope`'s main dispatcher. Recorded here so the next reader knows it was weighed.

**Not touched.** `AppPreferences` and its host-keyed schema; `onDefaultWorkspaceTapped` / `onSelectDefaultWorkspace` / `onWorkspacePickerDismissed` and the `pendingWorkspacePicker` flag; `HostWorkspaceRepository` and the picker's own repository binding; `ChannelListViewModel`'s `.first()` read of the same preference at discussion-creation time. Picker option labels and any new daemon request are out of scope per the ticket.

## State + concurrency model

One new cold-to-hot projection in `viewModelScope`, `stateIn(SharingStarted.WhileSubscribed(5_000L), initialValue = null)` — identical ownership, timeout and cancellation path to the eight projections beside it, so `LifecycleConnectionDriver`'s background close tears it down with the rest and a foreground return re-subscribes. No new job, mutex, dispatcher or mutable shared state; the `combine` body is pure and non-suspending.

The second subscription on the same repository (`All`, alongside `archivedDiscussionCount`'s `Archived`) costs one extra `list_conversations` frame per Settings open. That verb is idempotent and its reply is absorbed by the projection's `StateFlow` conflation — the same trade `archivedDiscussionCount` already makes, and the reason `observeConversations` documents "redundant requests are absorbed". It dials nothing on its own: it sends on an existing pump and cannot open a socket the lifecycle driver has closed.

Live rename and clear (AC2) arrive on this already-subscribed arm as a re-emission of the same list, so an open row re-derives without resubscribing.

## Error handling

No new failure mode reaches the user. `.catch { emit(emptyList()) }` on the conversation arm only — a throwing conversation stream degrades to "no label" (the row keeps showing the cwd fallback) instead of tearing the screen down, matching the supportive-metadata rule `archivedDiscussionCount`'s `.catch { emit(0) }` set. Scoping the `catch` to the inner arm is load-bearing: `combine` continues with a completed flow's last value, so the path half keeps updating the row after a conversation-stream failure.

No `Result` type, no new UI error surface, no new log call — see the security review on why nothing here may be logged.

## Testing strategy

RED first: every new assertion must fail against the current basename-only row before any production edit.

**`SettingsViewModelTest`** (new cases on the existing rig; no existing test is modified):

- A bound path whose conversation on this host carries a label resolves to that label.
- An **archived** conversation is a valid match, and the flow subscribes with `ConversationFilter.All` — asserted through the existing `captureFiltersInto` stub, since `All` is what admits archived rows in `RemoteConversationRepository.project`.
- No conversation at that cwd → `null`; a blank label at that cwd → `null`.
- Rename → clear while subscribed moves the value label → `null` across one VM and one collector, and `AppPreferences.defaultWorkspace(OWNER)` still reads the original path afterwards (AC2's "does not rewrite the saved path").
- An unbound default (`DEFAULT_SCRATCH_CWD` and `""`) resolves to `null` even when a labelled conversation sits at that cwd.
- Two VMs for the two case-differing host ids, each with its own stub repository holding the **same** cwd under a different label: each reads only its own (AC2's same-path/two-host case, and the at-this-layer form of "compatibility selection cannot retarget the row", since selection is not observable here).

**`SettingsScreenTest`** (row wiring, AC3): `setSettings` gains `defaultWorkspace` / `defaultWorkspaceLabel`; one case asserts the row's supporting line renders the supplied label, one asserts it renders the last path segment when the label is null.

**Previews:** the light preview keeps the frame's `scratch` state (`defaultWorkspaceLabel = null`) for fidelity; the dark preview takes a bound path plus a label so the new state is visible at a glance.

**Live acceptance is not mine.** AC3 assigns the rung-3 cross-client rename/clear scenario to #676 and the live regression gate to the dispatcher; `needs-real-claude` stays on the issue. No e2e source changes.

GREEN gate: `testDebugUnitTest` scoped to `SettingsViewModelTest`, the two new `SettingsScreenTest` methods on the managed API 33 device, then `spotlessApply`, `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin`.

## Documentation handoff

Pending for the documentation stage — no shared doc is edited by this slice. From the ticket's own handoff section:

- `docs/knowledge/features/settings-screen.md` — the Default workspace row's conversation-derived subtitle, its exact-host/exact-cwd matching, the fallback chain, and the distinction between the display text and the stored path.
- `docs/knowledge/features/settings-viewmodel.md` — the new `defaultWorkspaceLabel` projection beside `defaultWorkspace`: `ConversationFilter.All` (archived rows included), the unbound-default null arm, the `onStart` empty emission and why, and the second subscription's one extra `list_conversations` frame.

## Open questions

None. Two decisions were resolved inside this plan rather than deferred: an unbound default never borrows another conversation's label, and the display text is resolved in the composable rather than the ViewModel.

## Security review

**Verdict:** PASS — no MUST FIX. One SHOULD FIX is folded into § Design above (the unbound-default arm was tightened during this pass, before commit).

**Findings:**

- **[Trust boundaries] No new boundary, and the existing one is inherited rather than re-implemented.** `Conversation.workspaceLabel` is daemon-authored text and untrusted relative to the UI. This slice is its second render path, and #722's review placed the clamp inside `workspaceDisplayName` precisely so a consumer inherits it by calling the shared function — which is what this design does, with no second formatter and no bypass. The sink stays `SettingsRow`'s M3 `ListItem` `Text`, which renders a `String` literally: never `MarkdownText`, never a WebView, never an attribute, URL, filename or cache key. The one line this slice adds to the boundary is that display text is also never an *identity*: the row keeps `defaultWorkspace` as the path that the picker writes and `ChannelListViewModel` creates discussions from, and `defaultWorkspaceLabel` is inert display text that reaches nothing else.
- **[Trust boundaries] SHOULD FIX, addressed in § Design before commit — an unbound default must not borrow a label.** A design matching on the raw path would match `DEFAULT_SCRATCH_CWD`, which many conversations share, letting any hostile-or-buggy daemon put a chosen string under "Default workspace" for a host whose default is *unbound* — a misleading claim about the operator's own configuration, reachable without controlling the workspace at all. Fixed by returning `null` for an empty or sentinel path before any scan, so the sentinel case is decided by the client rather than by daemon data.
- **[Trust boundaries] Cross-host confusion is structurally excluded, not merely avoided.** The lookup reads `SettingsViewModel`'s constructor-bound `conversationRepository`, which `ThreadDestinationFactory.settings` builds from the route's captured owner (#715). There is no selection-following flow in the design, so the #398/#749-era defect — a Settings signal silently re-pointing when compatibility selection changes — has no surface here. The two-host test with a shared cwd pins it rather than assuming it.
- **[Tokens] Not applicable.** No credential is generated, read, stored, compared or transmitted. `SettingsHost` and `SettingsHostRow`, the two types in this file that deliberately hold no record-typed fields, are untouched; nothing here reaches `data/crypto/` or the paired-server stores.
- **[File / storage] No path is built, opened or written.** The matched value is compared with `==` and rendered; it is never concatenated into a path, so traversal and TOCTOU have no surface. The preference write path is untouched — this slice adds no writer, and the "does not rewrite the saved path" assertion is a test, not a convention. No storage-scope, at-rest-encryption, atomic-write or backup decision arises.
- **[Android attack surface] Not applicable.** No `Activity`, `Service`, `BroadcastReceiver`, `<intent-filter>`, deep link, `PendingIntent`, content provider, push handling or `android:exported` change. No WebView.
- **[Cryptography] Untouched.** No RNG, hash, key, nonce or secret comparison. The `Noise_IK_25519_ChaChaPoly_BLAKE2s` transport and its vendored implementation are not in the diff. The `it.cwd == path` comparison is between two non-secret display/config strings, so constant-time comparison does not apply.
- **[Network & I/O] One extra request of an existing verb, no new frame type.** Subscribing adds one `list_conversations` per Settings open on the already-open pump; no frame size cap, timeout, TLS setting, pin, backoff or reconnect path changes, and a pre-`Open` send is dropped by the existing pump rather than queued. A hostile relay can drop or delay the reply, which degrades to the cwd fallback — the honest text — rather than to a wrong-but-authoritative name. The client-side length clamp is the compensating control for the protocol's declared non-guarantee; a 5000-character label from a hostile daemon is bounded before it reaches text layout.
- **[Errors / logs / telemetry] No logging added, and the label must never be logged.** The ticket says so, the shared function's KDoc says so, and the design has no log call to violate it: no classified error, no lifecycle event, no user-facing error string that could carry the label into a Snackbar or a crash report. Note that neither the label nor the path may be added to the four existing content-free picker logs, which `workspacePickerLogs_carryNoServerIdAndNoPath` already pins.
- **[Concurrency] No findings.** One `stateIn(viewModelScope, WhileSubscribed(5_000))` projection with the same ownership and cancellation path as its eight siblings; no coroutine launched, no mutex, no check-then-act, no suspension point inside the `combine` body, no hot flow where a cold one was intended. Process death mid-read loses nothing: the projection is derived, holds no state of its own, and re-derives on next subscribe.
- **[Threat model] Hostile daemon frame** — bounded by the shared clamp, rendered as text, and unable to claim a name for an unbound default (finding 2). **Malicious relay** — content-blind and on-path; it cannot author a label inside the Noise session, and dropping or delaying `conversations` degrades to the cwd fallback. **Token theft from disk** — no storage touched. **UI-side leakage** — the row now shows operator-chosen text naming a directory the operator owns and already sees on desktop and in the thread chip; the full path was already derivable from the basename this row showed before, so no audience widens. **OUT OF SCOPE:** the same clamp-and-render obligation for the workspace tree rows (#641) and for picker option labels, which the ticket excludes; and the cross-client rung-3 rename/clear scenario, which AC3 assigns to #676.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22
