# Navigation

Single-activity Compose Navigation host. `MainActivity` is the only Activity; all screens are `composable` destinations under one `NavHost`.

## What it does

Boots into `welcome` on a fresh install or `channel_list` when a saved pairing
exists. The graph has ten routes; the thread destination carries both the
owning `serverId` and the host-local `conversationId`.

- **`welcome`** (start destination when no paired-server record exists) — renders `WelcomeScreen` (#7).
- **`scanner`** — renders [ScannerScreen](scanner-screen.md) with its destination-scoped ViewModel, camera permission launcher and live preview. A decoded QR is parsed into an immutable fingerprint/record confirmation state without writing. Confirm saves, starts the controller and navigates to `channel_list`, popping the scanner inclusively; this camera path does not await encrypted readiness. Decline/Back from confirmation re-arms scanning. Paste actions navigate to `pair_code`; ordinary Back pops to the caller.
- **`pair_code`** — renders [PairCodeScreen](paste-code-dialog.md) with a destination-scoped `PairCodeViewModel`. Its optional-name form, fingerprint confirmation and saved-target connection wait stay within one route. Cancel returns to the caller; success clears the previous graph entries and opens `channel_list` only after both target connection legs are ready. Since #842 the destination pattern (`Routes.PAIR_CODE_ROUTE`) takes an optional `serverId` query argument, shaped like Settings' own below; when present, the flow is scoped to re-pair exactly that host instead of naming a new one — see [manual pairing entry and return](#manual-pairing-entry-and-return).
- **`channel_list`** — renders [ChannelListScreen](channel-list-screen.md), fed entirely by host-qualified state since #738 retired the flat compatibility model and its `selectedServerId()` adapter for this screen. Its single top-right toolbar control, “Pair another host”, opens `scanner` (`ChannelListEvent.PairHostTapped → navController.navigate(Routes.SCANNER)`). Both empty and populated lists expose this entry. Camera confirmation and successful manual pairing via `pair_code` land back on the list; see [manual pairing entry and return](#manual-pairing-entry-and-return). Unpairing the list's last saved host leaves `welcome` instead — see [Returning to Welcome after the last host](#returning-to-welcome-after-the-last-host-1323).
- **`conversation_thread/{serverId}/{conversationId}`** — renders [ThreadScreen](thread-screen.md#wiring) with a destination-scoped ViewModel and dependencies from the exact retained host. Back pops the stack. Also reachable from outside the graph entirely since [#685](../../specs/architecture/685-mobile-attention-alerts.md): a notification tap parses to the same `HostConversationTarget` and `PyryNavHost` pushes it on top of `channel_list` via its `openTarget` param, gated by `ThreadDestinationFactory.isSavedHost`. Since [#1400](../../specs/architecture/1400-notification-tap-active-conversation.md), a saved host alone is not enough: the tap opens only once the host's `HostConversationSnapshot` holds the conversation among its (unarchived) `channels` or `chats`, waiting up to `NOTIFICATION_TAP_ROW_WAIT` (5 s) for a cold-start snapshot before giving up and staying on the list — see [Push messaging service § Attention alerts and the tap route](push-messaging-service.md#attention-alerts-and-the-tap-route-685).
- **`markdown_reader/{serverId}/{conversationId}/{attachmentId}`** — renders the [Markdown reader screen](markdown-reader-screen.md) (#1027), reached only from the thread destination above: tapping a ready file row whose name ends in `.md`/`.markdown` reads and strictly decodes it first, then routes `ThreadNavigation.OpenMarkdown(attachmentId)` to `navController.navigate(Routes.markdownReader(target, event.attachmentId))`. Wrapped in `HostDestination` like the thread route; the back arrow and system back both pop back to the same thread entry.
- **`markdown_link/{serverId}/{conversationId}`** — since #1050, renders the same [Markdown reader screen](markdown-reader-screen.md#linked-note-live-since-1050) for a markdown-path link tapped in an assistant reply, but for a note read live from the workspace rather than a stored attachment. Ids only, deliberately: the path itself never travels in the route (it would put assistant-authored text in the saved back stack), so `Routes.markdownReader`'s own "ids only: never a file name, path or URI" KDoc holds unchanged, and the sibling `markdown_reader` route above is untouched. `ThreadNavigation.OpenLinkedMarkdown` (carrying nothing — the document lives in the thread's `ThreadViewModel`) routes to `navController.navigate(Routes.markdownLink(target))`. The destination resolves the thread's own `ThreadViewModel` with `navController.getBackStackEntry(Routes.CONVERSATION_THREAD)` + `koinViewModel(viewModelStoreOwner = …)` and reads its held document once. Wrapped in `HostDestination` like the two routes above.
- **`settings?serverId={serverId}`** — renders the [notifications-only Settings modal](settings-screen.md). The existing gear opens it through `Routes.settings(destinations.selectedServerId())`; the optional owner remains in the route for compatibility but supplies no modal content. The route collects the persisted push preference, requests Android notification permission on enable, and pops to the prior view for Close, Done or Back.
- **`archived_discussions/{serverId}`** — renders [Archived Discussions](archived-discussions-screen.md) for the required host owner. The separate channel-list sidebar Archive action navigates with the selected server id; `HostDestination` rejects an unknown or removed owner rather than showing another host’s archive. Restore stays on this screen.
- **`about`** — retains the standalone [About screen](about-screen.md) route and its static content. Settings no longer offers an About entry.

[#382](../codebase/382.md) had added a tenth route, `literal_screen/{serverId}/{conversationId}`, rendering a `LiteralScreenSurface` reached from the thread overflow menu and the stall promotion banner. [#883](../../specs/architecture/883-retire-literal-screen.md) removed the route, its destination ViewModel and both entry points once the daemon dropped the server-side screen-snapshot render path; the graph returned to nine routes at that point. Later reader routes expanded it;
\#1672 removed the unreachable `discussions` route, screen, ViewModel and adapter.
Chats open from the host tree and promotion remains in the
[thread Save as channel modal](save-as-channel-dialog.md).

Other-conversation [attention pills](thread-top-overlay.md#the-attention-pill-1735) use
`openAttentionTarget`: single Waiting and Finished pills retain a typed `HostConversationTarget`
and call the existing `openThread` route for that exact host/conversation pair. Equal ids on another
host remain distinct; display names never choose the destination. The count pill has no target and
returns to `CHANNEL_LIST` with `popUpTo(CHANNEL_LIST)` and `launchSingleTop`. Taps only navigate;
they do not answer a held prompt or send a command. The thread entry's RESUMED lifecycle owns the
attention subscription and timer, so an older entry covered by another thread cannot collect finishes.
`ThreadAttentionNavigationTest` exercises production routes, both targets, colliding ids and
covered/background entry cancellation.

## How it works

`MainActivity.setContent` uses `produceState<Boolean?>`, keyed by both the injected
`PairedServerCollectionStore` and `AppPreferences`, to read the full saved-host
collection once with `list()`. It passes the set of exact `entry.record.serverId`
values to `migrateDefaultWorkspace` and awaits success before composing `PyryNavHost`.
Case and whitespace remain significant; the latest-host `load()` cannot establish
ownership when several hosts are saved.

While collection loading or migration is pending, the value stays null and the
existing neutral `Surface` keeps both pairing and conversation creation unreachable.
A returned migration failure keeps that surface in place, with no automatic retry
or retry UI; restarting the Activity can retry. Composition disposal cancels the
work, and cancellation does not open navigation. Startup logs contain only static
event/outcome codes.

On success, a nonempty snapshot selects `channel_list`; an empty snapshot selects
`welcome`. One initial host receives an eligible legacy default only if its own
key is absent. Zero or multiple initial hosts permanently record no legacy owner,
so a later pairing cannot inherit the unowned path. Missing or unreadable paired
storage counts as an empty snapshot. Restarts recheck migration, but its persisted
decision prevents another transfer or owner change. See the
[workspace storage contract](app-preferences.md#what-it-does). Completing this
decision before exposing pairing prevents a new host from changing the initial
ownership set.

The internal graph defaults to `rememberNavController()` and accepts a controller
for production-route tests. Pairing later in the session navigates explicitly; it
does not rewrite the graph's initial destination. Screens receive callbacks, never
a `NavController`.

### Manual pairing entry and return

Every scanner paste action (viewport, denied and error) navigates to
`Routes.PAIR_CODE`; the scanner remains underneath. The route resolves
`koinViewModel<PairCodeViewModel>()` from `appModule`, using the observable
collection store and registry. Its state holds both drafts in memory, without
saving the pairing code in navigation arguments or saved instance state.
The channel list's single “Pair another host” control is fixed at the toolbar's top right (#1186),
replacing the repeated global-header entries. It emits the existing parameterless `PairHostTapped`:
list → scanner → Paste → code pairing. The control stays visible with an empty list and after scrolling;
empty guidance points to it without assuming that missing host snapshots mean no hosts are paired.
From code editing, Cancel or Back pops to the scanner; ordinary scanner Back returns to the same invoking
list entry. No extra route or list-specific cancellation flag is needed.

Since #842, a tree host row whose saved pairing was rejected (`RelayLinkStatus.PairingRejected`) is a
third entry, scoped to that one host: `ChannelListEvent.TreeHostRePairTapped(serverId)` routes to
`navController.navigate(Routes.pairCode(serverId))`. The destination pattern gains an optional query
argument shaped like Settings' (`pair_code?serverId={serverId}`, `Routes.PAIR_CODE_ROUTE` +
`Routes.pairCodeArguments()`); plain `Routes.PAIR_CODE` still navigates here with the empty default, so
the scanner-paste and unrouted add-host entries above are unchanged. In target mode `PairCodeViewModel`
refuses a code naming any other `serverId` (exact, case-sensitive) before the fingerprint gate opens,
names the Host name field with that host's stored display name, and never overwrites it — see
[pair-with-code target mode](paste-code-dialog.md#re-pairing-a-target-host-842).

Since [#843](https://github.com/pyrycode/pyrycode-mobile/issues/843), the open thread itself is a fourth
entry, scoped to its own host: the composer status area's Re-pair button (visible while `ThreadViewModel.rePairAvailable`
holds — see [Thread screen § trailing contextual-action slot](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643))
calls `navController.navigate(Routes.pairCode(target.serverId))`, the same target-mode route #842's tree
row uses. The thread entry stays underneath on the back stack rather than the channel list — Cancel pops
back to the still-open thread with its cached history intact, Complete pops the whole graph to the channel
list exactly as the other three entries do.

Pair validates the trimmed code and opens the existing fingerprint surface in
the same destination. Confirm uses exactly the record displayed there. The host
name is trimmed only for persistence: nonblank names are local metadata, while
blank names leave new hosts unnamed and preserve existing names. Writes target
the exact case-sensitive server id; names and relay URLs never identify a host.

| Phase | Cancel, toolbar Back or Android Back |
| --- | --- |
| Editing, including failure feedback | Enter Cancelled and pop to the invoking destination; make no further writes. |
| Confirming | Decline/Android Back returns to the unchanged draft without saving. |
| Saving credentials/name | Dismissal and editing are blocked until persistence finishes. |
| Connecting | Cancel the wait, enter Cancelled and pop; later readiness cannot navigate. |

Credential-save failure cannot start a new connection, and Retry for it crosses
the fingerprint gate again and upserts the same host for an unchanged code, as
before. A connection-verification failure is different (#1385): Retry does not
re-parse, re-confirm or save again — it waits on the already-saved record for a
fresh 30 s. See [the shared verification rule](paste-code-dialog.md#target-readiness-and-retry)
for the three outcomes and their texts. Cancel does not undo saved credentials or
a successful name write. See [failure behavior](paste-code-dialog.md#failure-and-cancellation).

The connection wait follows the complete saved record through registry
reconciliation, including replacement credentials on re-pairing. Another host's
connection, a stale bundle or bare relay readiness cannot complete it. Both relay
and encrypted-session status must be Connected within 30 seconds; relay blips
(`Offline`, `Connecting`, `Reconnecting`) keep the wait going, while
`PairingRejected`, `UpdateRequired` or an absent daemon end it earlier — see
[the shared verification rule](paste-code-dialog.md#target-readiness-and-retry).

`LaunchedEffect(state.phase)` translates Cancelled to `popBackStack()` and Complete
to `navigate(CHANNEL_LIST)` with `popUpTo(navController.graph.id) { inclusive = true }`
and `launchSingleTop = true`. Successful manual pairing therefore leaves no
onboarding entry to return to. Registry connection lifetime remains independent
of the destination and follows app lifecycle.

### Host-qualified destinations

The internal `Routes` object is shared with navigation tests. `Routes.thread(target)`
URI-encodes `serverId` and `conversationId` independently (`Routes.literal(target)`,
the same shape for the now-retired literal-screen destination, was removed by
[#883](../../specs/architecture/883-retire-literal-screen.md)). `Routes.hostArguments()` declares both as `NavType.StringType`, and
`Routes.target(arguments)` reconstructs the exact `HostConversationTarget`. Reserved
characters cannot become route separators. Domain ids and wire payloads remain
host-local; do not encode the host into a repository conversation id.

The channel-list destination collects its ViewModel's `hostNavigationEvents` in
`LaunchedEffect(vm)` and passes each target to `openThread`. Tree row callbacks
invoke the host command rather than navigating separately. `openThread` suppresses
only a target identical to the current thread's full pair. A/A/B in one burst
therefore yields one A entry and a distinct B entry, even when conversation ids
collide. Do not use `launchSingleTop` on this parameterized thread route: it can
retain the previous entry's ViewModel across different host arguments.

Resolve `koinViewModel()` inside each guarded destination so its
`NavBackStackEntry` owns the ViewModel and seeds its `SavedStateHandle` with both
identifiers. Back reveals the prior entry; reopening after a pop creates a new
one. Saved back-stack restoration preserves the same host/conversation pair.

### Settings: an optionally-owned destination

`Routes.SETTINGS` remains `"settings?serverId={serverId}"` with an optional query argument and a blank default. `Routes.settings(serverId)` encodes a non-empty id; the bare route also opens on an unpaired phone. The existing gear still captures the selected server id at tap time, but the modal contains no host-specific controls. Keeping the route shape preserves existing entry points and lets Back return to the prior destination.

The destination is not wrapped in `HostDestination`: opening it with no host or an old owner remains valid. It collects only `SettingsViewModel.pushNotifications`. `SettingsScreen` sends switch changes to the ViewModel, requests Android notification permission only when enabling, and sends Close, Done and dialog Back through `onDismissRequest` to `navController.popBackStack()`. This preserves the optional route without retaining the removed Connection section's host-to-host navigation. [Settings modal](settings-screen.md#what-it-does) describes its two notification rows.

The destination also collects `SettingsViewModel.lastHostUnpaired` — see [Returning to Welcome after the last host](#returning-to-welcome-after-the-last-host-1323). Since #1239 the modal draws no host editor and so has no control that can reach `confirmHostUnpair`; the collector exists for the day a Settings unpair entry returns, or is removed with `SettingsViewModel`'s otherwise-unreachable `HostEditorController`.

### Returning to Welcome after the last host (#1323)

`ChannelListViewModel.lastHostUnpaired` and `SettingsViewModel.lastHostUnpaired` (both one-line
delegations to their own [`HostEditorController`](host-editor.md#the-controller)) fire once when a
confirmed unpair leaves no saved host. The `channel_list` and `settings` destinations each collect it in
their own `LaunchedEffect(vm)`, alongside their existing collectors, and call a private
`NavHostController.returnToWelcome()`:

```kotlin
private fun NavHostController.returnToWelcome() {
    navigate(Routes.WELCOME) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
```

The same `popUpTo(graph.id) { inclusive = true }` idiom the `pair_code` Complete branch uses: Welcome
becomes the only back-stack entry, so Back leaves the app rather than returning to the now-empty list —
matching desktop's `runUnpairServer` re-reading its servers and calling `onLastServerUnpaired`. Unpairing
one of several hosts sends nothing and both destinations stay put. The signal itself, including why it is
read from the controller after cleanup rather than from a store-level observer, is
[`HostEditorController.confirmUnpair()`'s concern](host-editor.md#the-controller); this section only
covers where the two destinations collect it and how the stack is cleared.

### Archive: a required-owner destination, two doors (#715)

Where Settings' route argument is optional because the destination has to open with no owner,
`Routes.ARCHIVED_DISCUSSIONS = "archived_discussions/{serverId}"` is a **required** path segment: Archive
has no legitimate no-owner state, so making one inexpressible in the route is cheaper than guarding
against it appearing. Three helpers mirror `thread`/`settings`: `Routes.archive(serverId)`
(`Uri.encode`d into the segment), `Routes.archiveArguments()`, and `Routes.archiveOwner(arguments)`.
And where Settings is deliberately **not** wrapped in `HostDestination` (an unpaired phone must be able
to open it), Archive **is** — the same shape `thread` uses — because an unknown or
newly-removed owner falling through to another host's rows is exactly the failure this ticket closes.
The device test's removal case proves the guard fires by asserting the destination's *departure*, not
merely the other host's absence: a host-bound repository under an unknown owner still emits
`emptyList()`, which renders as a plausible empty archive and would hide a guard that silently failed.

The route originally had two doors: a row in the old Settings Storage section and a separate channel-list Archive entry. Settings now contains only Notifications, so the sidebar entry is the remaining UI path. `ChannelListEvent.ArchiveTapped` reads the selected server id at the tap and navigates with `Routes.archive(id)` only when it is non-empty. The production-graph archive tests open this entry and verify owner capture, restoration and removal. An event-only list test could pass even if the route target were wrong; the graph test is needed to prove the entry reaches Archive.

The ViewModel side of this ownership is [`ThreadDestinationFactory.archive`](dependency-injection-host-conversation-source.md#destination-ownership) — the exact-host
repository seam from #636, resolved once at construction rather than per restore tap, so a selection
change, reconnect or unpair can move neither the rows nor a pending write. See
[Archived Discussions screen](archived-discussions-screen.md)
for the full route/binding/header account.

### Host availability

`HostDestination` observes registry membership before resolving the ViewModel.
A saved host whose retained bundle has not initialized yet waits without rendering
another host's content. An unknown or removed host returns to `channel_list`,
clearing the invalid entries above it; the rejection log contains only a static
code. A disconnected or handshaking bundle remains a valid destination, with the
existing unavailable-action behavior and no selection fallback.

[ThreadDestinationFactory](dependency-injection-host-conversation-source.md#destination-ownership) binds
reads and actions to the retained owner. Reconnect switches its concrete repository
without changing route ownership; changing compatibility selection cannot redirect
the open thread, permission prompt or picker. Demo routes carry the
explicit `demo` id and use the existing fake singleton with inert live/modal/control
dependencies, even when relay hosts are saved. Chats creation sends a null `cwd` on
the explicit `demo` repository, leaving the fake's default behavior independent of
any paired host's migrated legacy default.

### Temporary flat-list compatibility

[ChannelListScreen](channel-list-screen.md)'s conversation tree resolves each row's
host from the row itself: `TreeRowTapped(HostConversationTarget)` calls
`vm.onHostRowTapped(target)` without a `selectedServerId()` lookup. The host's
Chats plus creates directly through that host's repository (#1563), sending a null
`cwd` for the daemon-default folder.

\#738 retired the channel list's flat state, reducer and repository constructor
parameter. #1672 removed the remaining selected-host flat-list adapter with the
unreachable discussion destination. No list destination now collects bare-id
navigation or projects a host promotion prompt into a flat model. Keep host
identity on the row target; reintroducing selected-host routing would open the
wrong host when conversation ids collide.

`HostWorkspaceRepository` wraps the thread destination for its workspace picker. Settings no longer opens a picker. The channel list resolves host-specific repository operations through its ViewModel; see [WorkspacePicker § Consumers](workspace-picker.md#consumers).

### Incoming shares (#1728)

`MainActivity` is the share target. Its manifest entry adds an `ACTION_SEND` / `ACTION_SEND_MULTIPLE`
filter for `*/*` and uses `singleTask`, so a share from another app reaches the existing task through
`onNewIntent` even when the app is in the background. `SharePayload.from` parses the intent once. It
reads plain `EXTRA_TEXT` and ordered `EXTRA_STREAM` URIs, falls back to `ClipData` URIs only when no
stream extra is present, and drops duplicates. A wrongly typed extra, an unsupported action or an empty
share returns no payload, so neither drafts nor the current screen change.

A fresh launch reads its intent only when `savedInstanceState` is null, as the notification tap does.
`onNewIntent` routes a share to the activity-scoped `ShareIntakeViewModel` and clears any pending
notification target. A notification tap cancels a pending share instead. Each new tap bumps
`openTargetVersion`, so tapping the same notification twice still navigates. While a share is pending,
`PyryNavHost` resets the stack to `channel_list` for each new batch generation and draws the picker
for ordinary shares. Direct Share conceals it during capture and target lookup; both paths skip the
notification-permission prompt. Selecting a row first transfers the batch,
then calls `onHostRowTapped` with the row's own target, so the exact host and conversation open.

System Back and the header's back arrow both cancel. They release the captured copies, leave every
draft unchanged and finish the activity. An activity-level `OnBackPressedCallback` covers Back during
startup, before the navigation graph has composed. Activity recreation keeps a pending batch in the
ViewModel and never replays a consumed one. Process death drops it, as it drops drafts. Ownership and
limits are in [Composer pending attachments](thread-screen-composer-drafts-and-attachments.md#composer-pending-attachments).
`ShareActivityTest` covers fresh and new intents, recreation, both Back paths and an ordinary launch.

Since [#1824](../../specs/architecture/1824-share-error-pills.md), `ShareErrorNoticeHost` wraps the
activity content above navigation and collects `ShareIntakeViewModel.notices` into an Error-pill queue,
replacing the root snackbar. Capture failures and intake count refusals appear on the ordinary picker;
selection count/size refusals remain visible after the synchronous transfer navigates to the thread.
The host preserves resource copy, plural counts and `formatMegabytes(AttachmentUploadLimit.MAX_BYTES)`;
no exception, URI, filename or shared text enters the notice. Share failures have no permitted non-error
snackbar classification. The source-routing guard integration remains owned by #1750.

`LocalNavigationErrorNotice` supplies this presentation state; `NavigationErrorPill` renders it in the
channel list/picker and thread. The picker uses its own measured header through the divider, rather than
adopting the thread layout: the overlay has 20dp side gutters and 28dp header clearance, with 12dp after
existing notices, and never reflows rows. The concealed Direct Share surface and the startup surface
while paired-host storage loads also draw the pill with 20dp/28dp padding. Collecting only inside a
picker or thread would lose selection errors when that destination leaves composition. The queue
survives destination changes, but host disposal cancels active and queued notices; see
[transient error lifetimes](thread-top-overlay.md#the-transient-error-pill-1747).
`ShareErrorNoticeTest` drives the production collector and navigation graph; its retained coverage and
counted results are recorded in [verification evidence](development-verification-emulator-evidence.md#share-failure-presentation-1824).

Since [#1729](../../specs/architecture/1729-direct-share-shortcuts.md), opening an active thread from a
list row, notification, launcher shortcut or direct share records the exact host/conversation pair in
`SharingShortcuts`. Its atomic ledger under `noBackupFilesDir` retains at most four targets globally,
limited further by Android's per-activity shortcut cap, across restart. Identity hashes the
length-prefixed pair, never the name: equal conversation ids on different hosts stay distinct, and
rename keeps the id. An open moves that pair to the front once while preserving the others' order;
snapshot relabeling does not report usage or change recency. Labels reuse `notificationTitle`: remove
control characters, truncate to 80 code points without splitting surrogate pairs, trim and fall back
to the app name. The icon is `ic_launcher`.

These are normal dynamic shortcuts, eligible for Direct Share and the launcher's shortcut menu.
The manifest-linked `res/xml/shortcuts.xml` declares the matching conversation-share category for
`text/plain`, `image/*` and `*/*`. No static or pinned shortcuts are requested, and new targets are
not long-lived. Do not exclude the launcher surface: that prevents normal dynamic publication.
Android owns masking, visibility and prediction order; tests assert publication and launch contracts,
not a guaranteed Sharesheet position.

For `ACTION_SEND` or `ACTION_SEND_MULTIPLE`, `SharePayload.from` retains only a strictly typed,
nonblank shortcut id of at most 256 characters. Malformed shortcut extras leave the otherwise valid
share intact. After private-copy capture completes, Direct Share resolves that id only through the
retained ledger's unique match, validates both pair ids with `NotificationTap`'s nonblank/256-character
bounds, checks the saved host and waits up to five seconds for its active row. A valid target merges
into that exact host's existing draft through the same synchronous `select` path as the picker, then
opens the thread. Nothing uploads or sends before Send. Unknown, malformed, archived, deleted,
unpaired or timed-out targets clear direct routing and show the picker with the captured batch and
all drafts intact. Replacement, cancellation and consumption fence lookup by generation; recreation
can retry an unconsumed lookup but cannot replay a transfer.

Launcher activation uses the shortcut's stored explicit `NotificationTap` intent with the exact pair,
without a share payload. The same saved-host and active-row checks and five-second readiness window
apply; unavailable targets stay on the channel list and drafts remain unchanged. Notification and
direct-share routing finish every suspending host read before explicitly entering
`Dispatchers.Main.immediate`. In that final non-suspending turn they recheck the **current** active
snapshot and route; direct sharing also checks generation and transfers the draft before navigating.
A row observed before a second host lookup can be deleted or archived during that suspension, so the
earlier readiness result cannot authorize navigation. Main-only fake stores conceal worker-thread
continuation failures: `NotificationTapNavigationTest` uses suspending IO lookups, asserts the actual
destination callback's main Looper, and holds the final lookup while publishing deletion/archive to
verify the list and existing drafts survive.

Reconciliation is application-owned and starts through `startApplicationGraph`, even without an
activity; selector-only dependency resolution stays lazy and Android-free. Opens, restore, lookup,
reconciliation and writes share one mutex. Startup, cached rows, partial upserts and disconnected or
reconnecting hosts do not establish deletion. `HostConversationSnapshot.rowsLoaded` establishes
absence only after a full conversation snapshot; even a full list equal to an earlier partial list
must emit its readiness edge. A loaded list can remove an absent/archived target, and a successful
saved-host read can establish unpairing. Snapshots can relabel retained targets but cannot insert or
resurrect one without a new open. See [host conversation source](dependency-injection-host-conversation-source.md).

Classified host-store failures preserve the last successful host set, or unknown startup state.
`sharingShortcutHosts` retries each second without requiring a pairing mutation; a newer revision
cancels obsolete recovery, and disposal cancels it entirely. Recovery reconciles the latest rows
before releasing waiting opens/lookups. A conflated revision is not a removal-event queue: confirmed
unpair awaits non-cancellable `forgetRemovedHost` shortcut cleanup under the same mutex, so immediate
re-pair cannot retain the old target. Archive, deletion, unpair and eviction remove both dynamic and
system-cached copies while preserving other hosts' targets. Unreadable ledger storage starts empty;
failed writes retain in-memory state. Diagnostics contain static outcomes/counts, never names, ids or
shared content. `SharingShortcutsTest`, `RecentShareTargetsTest` and the real-Keystore
`SharingShortcutStoreFailureTest` cover these authority and recovery seams; `SharingShortcutsDeviceTest`
checks Android publication, category/MIME data, label, resource icon and stored launcher activation.
The stored launcher intent uses `FLAG_ACTIVITY_CLEAR_TASK`, so replacement can briefly leave no
Compose roots. Its positive composer wait permits that interval with
`atLeastOneRootRequired = false` while still requiring the composer within ten seconds, then checking
that no share picker appears and the existing draft is unchanged (#1850). An immediate root query
can fail before the replacement activity composes.

## Adding a route

1. Add a `const val MY_ROUTE = "my_route"` (or `"my_route/{argName}"` for a parameterized route) to `Routes`. Keep the `{name}` placeholder inside the constant — the graph DSL consumes the literal pattern.
2. Add a `composable(Routes.MY_ROUTE) { MyScreen(...) }` block inside `PyryNavHost`. For parameterized routes, declare arguments explicitly: `arguments = listOf(navArgument("argName") { type = NavType.StringType })`. `StringType` is the default, but the explicit form documents the type at the call site and isolates the swap point.
3. For host-owned conversation destinations, reuse `Routes.hostArguments()` and `Routes.target(...)`, guard host membership, and resolve the ViewModel inside the destination. The factory reads `serverId` from the entry's `SavedStateHandle`; the ViewModel keeps the host-local `conversationId`.
4. Wire the screen's navigation callbacks via `navController.navigate(...)` in the lambda passed from `PyryNavHost` — keep the screen Composable itself stateless and `NavController`-free.

Screens take navigation as `() -> Unit` callbacks, not a `NavController`. This is what lets routing and destination wiring land in parallel tickets (the #7 / #8 / #14 pattern).

## Configuration

- **Dependency:** `androidx.navigation:navigation-compose`, pinned via `navigationCompose` in `gradle/libs.versions.toml`. Compose BOM does **not** cover this artifact group — it needs its own version pin.
- **Back-stack policy:** ordinary `navigate(route)` creates destination entries. Thread navigation suppresses only an identical current host/conversation target. Camera success pops the scanner inclusively; manual pairing success clears the graph's previous entries. Invalid-host rejection returns to the channel list and clears the invalid entries. Those list transitions use `launchSingleTop`; host-qualified thread navigation does not.
- **Start-destination gating:** `NavHost` composition waits for the full saved-host read and successful workspace migration described [above](#how-it-works). Only then does snapshot emptiness choose the initial destination. Later pairing uses explicit navigation; it does not rewrite the back stack.
- **Insets:** the outer `Scaffold` in `MainActivity` reserves system-bar space once
  at `PyryNavHost` with `Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)`.
  Padding reserves the space; consumption tells descendants it is already handled.
  Screen-level `systemBarsPadding()` and nested M3 scaffolds receive only remaining
  insets, so they add no second system-bar gap. Keep screen inset handling for
  standalone hosts. Descendant `imePadding()` reserves only keyboard height beyond
  the consumed bottom inset; no fixed or negative spacing compensation is needed.
  See the [inset correction](../../specs/architecture/1149-consume-system-bar-insets.md).

## Edge cases / limitations

- **No type-safe routes yet.** The first parameterized route (`conversation_thread/{conversationId}`, #15; VM-backed since #126) landed on string constants by design — partially migrating one route while siblings stay as `String` is worse than either end-state. A full migration of `Routes` to `@Serializable` data classes remains a separate, larger future ticket; do not bundle it with a feature ticket.
- **No deep links, no animations.** `composable(Routes.X) { ... }` only — no `deepLinks = listOf(...)`, no custom `enterTransition` / `exitTransition`.
## Testing

`SharePickerTest` accepts the pending share on Main before mounting `PyryNavHost`,
matching fresh share-launch ordering. Mounting ordinary channel navigation first
can start notification onboarding and leave `GrantPermissionsActivity` holding
focus on the device; Robolectric does not expose that dialog. A destination route
or a longer composer timeout cannot establish that the thread is rendered while
another activity holds focus. Keep the route, exact merged composer text and
single-consumption assertions after correcting launch order. The unknown-shortcut
regression seeds a nonempty draft and checks that the ready picker retains the
captured text/file while that draft and its empty attachment list stay unchanged.
See [singleton fixture isolation](development-verification-test-scheduling.md#test-scheduling-and-harnesses)
and [class evidence](development-verification-emulator-evidence.md#share-picker-fixture-isolation-1885).

[`MainActivityInsetsTest`](../../../app/src/sharedTest/java/de/pyryco/mobile/MainActivityInsetsTest.kt)
launches the production Activity and varies injected nonzero bars, checking the
Welcome title at one top inset + 300 dp (168 dp logo offset + 104 dp height + 28 dp
gap) and its footer at one bottom inset + 16 dp above the window bottom. A test
hosting only a screen or `PyryNavHost` misses the outer Scaffold and can pass while
production doubles the padding. Varying insets also catches fixed compensation.
[`MainActivityInsetsDeviceTest`](../../../app/src/androidTest/java/de/pyryco/mobile/MainActivityInsetsDeviceTest.kt)
covers that boundary across onboarding, list, thread and settings at 412×892 and
360×800 dp, including pair fields/actions above a visibly open test IME. See
[Compose evidence](development-verification-compose-evidence.md#compose-evidence) for the distinction
between synthetic-bar geometry and real-bar screenshots.

`PairCodeScreenTest.cancelToolbarAndAndroidBackReturnToCallerWithoutSaving`
mounts the production graph and opens `pair_code` over both Welcome and the
channel list. It exercises all three exit actions after validation failure and
checks the collection is unchanged. Directly navigating from the list here tests
return semantics but cannot prove the toolbar's event wiring. The
[pair-code tests](paste-code-dialog.md#testing) separately cover confirmation,
failure/retry, cancellation fencing and actual keyboard reachability.

[`StartupWorkspaceMigrationTest`](../../../app/src/androidTest/java/de/pyryco/mobile/StartupWorkspaceMigrationTest.kt)
launches the actual `MainActivity` with a controlled collection store and real
`AppPreferences` over a gated DataStore. Delay the collection read and migration
write independently and assert that neither pairing nor creation is exposed.
Returned IO failure must leave both navigation and the ownership decision pending;
a fresh launch after recovery can succeed. Zero/multiple-host launches followed by
a single-host launch must remain ownerless, while an initial sole owner remains
unchanged. [Storage tests](app-preferences.md#testing) separately prove durable
reopening and transaction atomicity.

Close and relaunch the Activity for cold-start ownership tests.
`ActivityScenario.recreate()` restores the existing navigation stack, including
Welcome; expecting it to choose a new initial route tests the wrong lifecycle
([#712 test finding](https://github.com/pyrycode/pyrycode-mobile/pull/717)).

`LiteralScreenNavigationTest` mounts the production `PyryNavHost`, `Routes` and
Koin bindings directly, bypassing the Activity startup gate. It cannot prove
migration completes before navigation opens.
`hostStreamsBackReopenAndRestorationKeepDestinationIdentity` opens both hosts
through `ChannelListViewModel`, returning to `CHANNEL_LIST` before each reopen.
It checks A/A/B duplicate suppression, distinct destination ViewModels for hosts
sharing a conversation id, Back/reopen and saved-state restoration. The suite
also covers reserved characters and invalid-host return. A copied minimal
graph can pass while production route arguments or bindings are wrong.
`recentDiscussionsDestinationIsNotRegistered` additionally checks the production
graph has no `discussions` node; absence of an entry action alone would miss a
registered dead destination.
Its picker cases open the actual descendant component with two Noise peers,
distinct recents and assertions that the other host receives no picker reads or
writes across selection changes and reconnect.

The file kept its name across [#883](../../specs/architecture/883-retire-literal-screen.md)'s removal of the
literal-screen destination it originally covered: only two of its four tests ever
touched the literal screen, and the other two are the workspace-picker owner tests
this paragraph describes, since extended for #904/#899/#685. #883 took out the
literal-screen steps — the overflow-menu trip to `LITERAL_SCREEN` in the back-reopen
test (host B now restores the thread itself instead) and the `Routes.literal`
navigation in the invalid-host test — and left the rest of the harness unchanged.

`flatListWorkspacePickerKeepsCapturedOwnerAcrossSelectionChanges` (#1392) flaked under
full-suite load because `HostConversationSource.reconcile` publishes `snapshots` from a
`Dispatchers.Default` coroutine, the one step on the `openAddWorkspace` → `recent_workspaces`
path that isn't `Main.immediate`, so Compose idling (`waitForIdle`/`runOnIdle`) does not drain
it. Under load that coroutine can lag past the test's `openAddWorkspace(a)` call; the ViewModel
then rejects A as `unknown_host` and sends nothing, so an idle-only wait on the peer's outbound
frame can pass or fail depending on scheduling, not on the production behavior under test. The
fix is test-only: wait on `HostConversationSource.snapshots` holding the host before opening the
picker, then replace the idle-then-assert check on `NavigationPeer.outbound` with a bounded
`compose.waitUntil`; `NavigationPeer.outbound` became a `CopyOnWriteArrayList` since the send and
a device-side `waitUntil` read it from different threads. A test asserting on a value fed by a
non-`Main` coroutine must wait on that value directly — `waitForIdle` only proves the main
dispatcher is quiet, not that every producer has run.

`SettingsNavigationTest` mounts the production `PyryNavHost` and checks the gear-to-modal path, dismissal to the previous view, persisted push state after reopening, and opening without a paired host. `SettingsDensityDeviceTest` sends a real Back key to the focused dialog. Espresso Back aimed at the unfocused Activity root in the graph harness and could miss the dialog window; a real focused-window key tests that dismissal route.

`UnpairNavigationTest` (#1323) reuses `SettingsNavigationTest`'s fixture shape (in-memory
`PairedServerCollectionStore`, real `RelayConnectionRegistry`, in-memory `AppPreferences`) and mounts
`PyryNavHost` from `channel_list`. It drives a single-host unpair to confirmation from the channel list
and asserts the final route is `welcome` with `previousBackStackEntry == null`, so Back cannot reach the
list; drives the same single-host unpair through `SettingsViewModel` reached via its back-stack entry
(no control opens it, since #1239 — see [Settings: an optionally-owned destination](#settings-an-optionally-owned-destination)) to the same `welcome` assertion; and unpairs one of two hosts from the
channel list to confirm the route stays `channel_list`. See
[Returning to Welcome after the last host](#returning-to-welcome-after-the-last-host-1323).

`ArchiveNavigationTest` (#715) copies that same harness for the Archive destination: both hosts hold
an archived conversation under the **same** id, proving the colliding-id case means giving two hosts
the same id rather than two different ones. It covers the captured owner surviving a
compatibility-selection change, saved-state restoration and Back/reopen under a different selection;
restore reaching only the owner's repository while the other host's peer receives no archive verb at
all and its matching-id row stays archived; removing the owner while Archive is open ending in a
departure to `channel_list` rather than a silently-empty render of another host's rows; and, separately,
the channel list's own archive entry opening the selected host's archive and following a later
selection change on a second tap — the case added in this ticket's rework once the verifier found that
door still bounced. See [Archived Discussions screen § Testing](archived-discussions-screen.md#testing).

[Production DI tests](dependency-injection.md#testing) separately verify colliding
ids, content and outbound actions. The existing `InteractiveStreamE2ETest` ping
and Reset-session regressions remain in the [live gate](../../e2e-interactive-stream.md#pre-ship-gate),
which since #847 also proves that two paired hosts whose conversations share an id stay
separate through pairing, rename, link-cycling and a restart, and since #1086 also proves
that each host's own default workspace and Archive stay separate. That gate does not prove
reconnect or phone-reply continuity — those rung-3 scenarios remain #673.

## Related

- Ticket notes: `../codebase/8.md` (NavHost setup), `../codebase/12.md` (Scanner stub + first destination-block Koin/coroutine wiring), `../codebase/13.md` (conditional start destination + `produceState` gating), `../codebase/14.md` (Welcome `onSetup` → `Intent.ACTION_VIEW` + `LocalContext.current` capture in a `composable` block), `../codebase/15.md` (first parameterized route), `../codebase/16.md` (Settings placeholder + interactive-placeholder factoring rule), `../codebase/46.md` (first VM-backed destination — `koinViewModel<…>()` + `collectAsStateWithLifecycle()` shape, inline `when (event)` → `navigate` translation), `../codebase/21.md` (channel-list `SettingsTapped` → `Routes.SETTINGS` wiring + `material-icons-core` on the classpath), `../codebase/24.md` (`discussions` route — first destination with dual nav wiring + a back-arrow `navigationIcon` reusing `R.string.cd_back`), `../codebase/26.md` (`discussions` route wired into the live graph — `ChannelListEvent.RecentDiscussionsTapped → navController.navigate(Routes.DISCUSSION_LIST)`), `../codebase/126.md` (`conversation_thread/{conversationId}` body flipped from placeholder `Text(...)` to real `ThreadScreen` + `ThreadViewModel`; path-argument extraction moves from `backStackEntry.arguments?.getString(...)` into the VM's `SavedStateHandle` via Koin's `viewModel { ThreadViewModel(get()) }` block), `../codebase/271.md` (`about` route — first static sub-screen added with no VM, mirroring the `archived_discussions` block minus the state-collection machinery), [`../codebase/382.md`](../codebase/382.md) (had added the `literal_screen/{conversationId}` route — second parameterized route; per-back-stack-entry `koinViewModel()` for a fresh, per-conversation VM, plus the `onShowLiteralScreen` pure-navigation callback threaded from the thread overflow menu, mirroring `onOpenAbout`; the route and callback were removed by [#883](../../specs/architecture/883-retire-literal-screen.md) once the daemon dropped the server-side screen-snapshot render path)
- Specs: `docs/specs/architecture/8-navigation-compose-setup.md`, `docs/specs/architecture/12-stub-scanner-screen.md`, `docs/specs/architecture/13-conditional-navhost-start-destination.md`, `docs/specs/architecture/15-conversation-thread-placeholder-route.md`, `docs/specs/architecture/16-settings-placeholder-route.md`, `docs/specs/architecture/21-channel-list-top-app-bar.md`, `docs/specs/architecture/126-thread-screen-skeleton.md`, `docs/specs/architecture/271-dedicated-about-screen.md`, `docs/specs/architecture/749-settings-destination-host-owner.md` (optional-owner route + `HostDestination`-guard exemption), `docs/specs/architecture/750-settings-saved-host-connection-rows.md` (host-to-host navigation + `popUpTo … inclusive` back-stack decision), `docs/specs/architecture/715-archive-host-owner.md` (required-owner route + `HostDestination` wrap + the two-doors fix)
- Consumers: [Welcome screen](welcome-screen.md), [Scanner screen](scanner-screen.md), [Paired server store](paired-server-store.md) (read by startup and written by confirmed camera/manual pairing), [Thread screen](thread-screen.md) (VM-backed destination since #126), [Markdown reader screen](markdown-reader-screen.md) (`markdown_reader/{serverId}/{conversationId}/{attachmentId}`, reached only from the thread destination, #1027), [Settings modal](settings-screen.md), standalone [About screen](about-screen.md), [Archived Discussions screen](archived-discussions-screen.md)
- Follow-ups: Phase 2 thread UI (the outer shell at `conversation_thread/{conversationId}` shipped in #126; downstream slices #128–#140 / #145 fill the message list, input bar, status row, connection banner, session-boundary delimiter, empty states, and TopAppBar overflow), pairing — parsing and fingerprint confirmation are shipped for both camera and manual input; scanner redesign remains #640, list entry points #641; two-host pairing, rename, link-cycling and restart are proven by #847's rung-3 e2e, the second host's own rename and unpair by #1085's, and the two-host default-workspace and archive/restore separation by #1086's
