# ChannelListViewModel — testing

Split out of [ChannelListViewModel](channel-list-viewmodel.md) on 2026-09-24 to keep that document under
the 50000-byte size cap the docs guard enforces. The section below moved here verbatim and kept its
heading, so its anchor (`#testing`) is unchanged. Read [ChannelListViewModel](channel-list-viewmodel.md)
first for the state shapes, the package and [Wiring](channel-list-viewmodel.md#wiring); this document
covers only `HostChannelListViewModelTest`'s coverage of that wiring.

## Testing

`HostChannelListViewModelTest` resolves the production `appModule` ViewModel binding
with controlled source and repository fixtures. Use colliding ids and unchanged
paths on case-distinct hosts, and assert each host's own preview: globally unique
fixture ids can conceal a cross-host merge. A silent host and silent preview must
coexist with visible rows from another host; disconnect must preserve cached rows
while removing previews.

For creation, suspend the preference flow, change compatibility selection and
replace the original host's repository before releasing the preference value.
Give case-distinct hosts different defaults and seed a conflicting global value;
assert both hosts' workspace arguments and qualified navigation, then scratch for
an unset default. A shared default or settled happy path can conceal use of the
legacy property, an early repository capture or a send redirected to selection.
**Add workspace coverage (#904, same `fixture()`, replacing the picker's old cases).**
`addWorkspaceReadsAndWritesOnlyItsOwnHostAndStartsInTheCreatedFolder` uses a preference flow that
throws if read (the explicit path bypasses it) and a selected adapter pointing at the other host as
a decoy: opening on `"Host"` shows only `"Host"`'s own recents, a created folder becomes the
selection on `"Host"` alone without starting a chat, and OK starts the chat on `"Host"` with that
exact path, closes the modal and navigates — then a second open on `"host"` proves the same
discipline the other way. `addWorkspaceRecentsNeverShowAnotherHostsListAndAreCapped` seeds one
host with 80 recents and asserts the published list caps at `MAX_ADD_WORKSPACE_RECENTS` (50), then
retargets to the second host **without closing** and collects every intermediate `hostState`
emission to assert none of them ever pairs the second host's open state with the first host's
list — the test that would fail if the tagged-recents guard in [Wiring](#wiring) were removed — and
that closing empties the list while an unknown id opens nothing.
`addWorkspaceFailuresStayOpenWithSelectionAndStaticFlags` covers a throwing create and a throwing
start each leaving the modal open with the selection intact and only its own flag set, asserts no
captured log line carries the server's message, the typed name, the path or the host id, and proves
a retry after clearing the repository's failure succeeds and navigates.
`addWorkspaceIgnoresPressesWhileBusyAndALateResultCannotReopenOrNavigate` covers submit/create with
nothing open or no selection sending nothing; a second submit, a create and a selection change all
arriving while a write is `busy` are ignored, with the in-flight selection and request left intact;
a dismissal during an in-flight submit whose result lands later neither reopens the modal nor
navigates; and a folder creation gated mid-flight, dismissed, then reopened, does not let the late
completion overwrite the fresh state. The unavailable-host and cancellation-propagation tests
(`unavailableTargets…`, `guardedFailures…`) were adapted in place to open, select and submit through
Add workspace instead of the retired picker methods.

Proving a projection subscribes to nothing (#729's workspace groups) needs the
fixture's `Host.available` flag, not `Host.live.value = null`: `available` gates
only the lookup lambda, so `repositoryFor` returns null and `observeHostEntry`
short-circuits before any preview subscription while `HostConversationSource`
keeps collecting rows — nulling `live` instead stops that row collector too, so
the test would pass against a projection producing nothing.

Fold and selection coverage (#731, same `fixture()`): every host and workspace key
starts expanded (empty `collapsed` set); toggling a host key collapses only that
host's key, leaving the same host's key in the *other* section expanded — proving
`TreeFoldKey.section` is load-bearing, not decorative. A relabel (`displayName` /
workspace label change only) and an incoming list update both leave the collapsed set
and the rendered groups untouched — the key is `(section, serverId, cwd)`, never a
display name. `onHostRowTapped` records the target as `selected`; a later snapshot
emission does not clear it, and a second tap replaces it — pinning "last opened from
this list", not "currently open."

**Editor coverage (#744, same `fixture()`).** `Fixture` gains an in-memory `PairedServerCollectionStore`
fake bound in the override module — the Koin-built view model would otherwise resolve the Keystore-backed
store on the JVM. `editorOpensOnTheRowsOwnStoredRecordAndSurvivesAnIncomingSnapshot` covers a named, an
unnamed and a blank-named host each opening with the right identity, relay address and name (blank for the
latter two), and a later snapshot leaving the published editor untouched.
`editorOpenIgnoresAnUnknownIdAndAnOpenSupersededByALaterTap` covers an unknown id publishing no editor and
proves the `editorOpenJob` cancellation: a second open while the first host's read is still in flight
publishes the **second** host's editor and never the first's, gated by holding the fake store's read open.
`submitSavesTheTrimmedClampedNameOrClearsItAndClosesTheEditor` covers OK writing the trimmed name and
closing, a blank or whitespace-only name writing `null`, and a name past `MAX_WORKSPACE_LABEL_CHARS`
written clamped. `failedSaveKeepsTheEditorOpenAndActionableWhileADismissedOneStaysClosed` covers a throwing
`setDisplayName` leaving the editor open with `saving = false, failed = true` and the stored name
unchanged, and a dismissal during an in-flight save not being undone by its later completion.
`dismissClosesTheEditorWithoutWriting` covers the last case.

**Removal coverage (#745, same `fixture()`).** The fake `Store` gains a working `remove` (a `removals`
list, a `failRemove` switch and a `removeGate` for observing mid-write state), and the stub `DataStore`
gains a real `updateData` so `AppPreferences.removeDefaultWorkspace` can be asserted rather than stubbed
out. `unpairIsGatedOnAConfirmationAndDecliningRemovesNothing` covers request setting `confirmingUnpair`
without writing anything and decline clearing it while leaving the store, the other host and both
workspace preferences untouched — plus a stray request with no open editor arming nothing.
`confirmingRemovesThePairingThenItsWorkspaceAndClosesTheEditor` proves the id-exact removal, the exact
workspace key cleared, the other host's entry and workspace left intact, and the editor closed — and, with
`removeGate` held, that the workspace key is still present until the pairing removal completes, which is
the assertion that actually proves the ordering rather than trusting it. `aFailedUnpairStaysOnTheConfirmationAndChangesNothing`
covers a throwing `remove` leaving `unpairFailed` set, `saving` cleared, the confirmation still up, the
pairing present and the workspace uncleared, the captured log lines carrying neither the id nor the name,
and a retry succeeding. The same test then proves the `saving` guard itself against a second, gated
removal: a decline and a second unpair request arriving mid-write are both ignored, so the write's own
`compareAndSet` still closes the modal rather than stranding it on a step the store never took — and,
separately, that a failure landing after a `dismissHostEditor()` call does not resurrect the modal either.

**Chat editor coverage (#827, same `fixture()`).** Both fixture hosts, `"Host"` and `"host"`, get a chat
sharing the same id, `"same"`, under different names — deliberately colliding rather than globally unique,
because a conversation id is host-local and a rename sent to the wrong host would still pass a suite that
gave every fixture chat its own id. Opening `("Host","same")` pre-fills Host's own name, opening
`("host","same")` pre-fills host's, a nameless chat pre-fills `""`, an unknown id opens nothing, and
opening changes neither `selected` nor the navigation channel. Submitting `"  New  "` renames only on
`"Host"`'s repo with `"New"` and closes the editor; `"host"`'s same-id chat is asserted unrenamed, and the
projected row picks up `"New"` once the repo's own stream re-emits. Since #1336 the editor closes, rather
than stay open, when the target host's status goes disconnected — the snapshot watcher's own coverage, not
this suite's — reversing #1190's keep-open rule; submitting while `repositoryFor` returns null sets
`failed` and sends nothing. A
throwing `rename` leaves the editor open with `failed = true, saving = false`, the stored name unchanged,
and asserts no captured log line carries the name, either id or the exception's message; a retry succeeds
and closes. A gated write completing after `dismissChatEditor()` does not reopen the editor — the same
`compareAndSet`-survives-a-dismissal proof the host editor's suite already established. Dismiss sends
nothing.

**Workspace editor and archive coverage (#905, same `fixture()`).** Both fixture hosts get a workspace
row at the same `cwd`, colliding rather than globally unique, for the same reason the chat editor's
fixture collides on id: a rename or archive sent to the wrong host would still pass a suite that gave
every fixture workspace its own path. `openSeedsTheShownNameAndRejectsAnUnknownHostOrCwd` covers a
labelled and an unlabelled workspace each opening with the row's own displayed name, and an unknown host
or `cwd` opening nothing. `submitRenamesOnlyTheEditorsHostAndCwdByTheLabelRuleAndCloses` covers a blank
name and the folder's own name both sending `null`, other text sent trimmed, and asserts the *other*
host's same-`cwd` row is never renamed — the test the verifier's gate regression broke and a rework fixed;
see `WorkspaceDisplayNameTest` for the label rule's own unit coverage, including the clamped-folder-seed
edge case. An over-bound name sends nothing, and a failed or unavailable-host submit keeps the modal open
with `failed` while logging none of the label, `cwd`, id or server message. Request/decline/confirm mirror
the removal suite's shape: confirm archives only the editor's host and `cwd` and closes, a failure stays
confirming with `archiveFailed` for a retry, and a late completion after dismissal cannot reopen the
editor.

**Archive coverage (#828, same `fixture()` and colliding `"same"` id).** `Repo` needed a recording
`archive` that flips `archived = true` on its own rows and records the call, plus overridden `delete`
and `unarchive` that only record — the fixture's `Repo.archive` had been a plain delegation to
`FakeConversationRepository`, which throws on an id it was never seeded with, so a test calling
`archive` on the fixture's synthetic rows would have gone green by silently exercising the failure path
instead of the success one. Archiving `("Host","same")` archives only on Host's repo, sends no rename
and no delete, closes the editor, and — once the stream re-emits — the chat is gone from Host's `chats`
and present under Host's own `Archived` filter, while `host`'s same-id chat stays unarchived. The
unavailable host sets `archiveFailed` and sends nothing. `RelayErrorException` and `IllegalStateException`
both leave the editor open with `archiveFailed`, `!saving`, `!failed`, the row still active, and no
captured log line carrying the message, an id or a name; a retry succeeds and closes the editor. A gated
archive completing after a dismissal leaves the editor closed, and a second archive or a rename arriving
mid-gate are both ignored — the same `saving`-guard-plus-`compareAndSet` proof #827's rename suite
already established, reused rather than re-derived.

**Channel editor coverage (#667, same `fixture()`).** Both fixture hosts get a channel sharing the same
id, `"same"`, under different names and prompts — the chat editor's colliding-id fixture, repeated here for
the same reason: a conversation id is host-local, and a rename or prompt write sent to the wrong host
would still pass a suite that gave every fixture channel its own id. `Repo` gains a scripted
`requestSystemPrompt` (a stored map, a gate to hold a read open, a failure count and a `SessionPromptStatus`).
`channelEditorOpensOnTheRowsOwnHostAndReadsItsOwnPromptWithoutSelectingOrWriting` covers opening
`("Host","same")` and `("host","same")` each pre-filling that host's own name and reading that host's own
prompt to `Read` with `Differs`, a chat target and an unknown host opening nothing, and asserts `selected`,
the navigation channel and every write method stay untouched. `channelPromptIsReadOnceItsHostConnectsAndAFailedOrOversizeReadIsUnavailable`
opens the editor on a connected host whose repository has not resolved yet — since #1336 a disconnected
host refuses the open outright, so this case is no longer about connection — covers the field staying
`Reading` until the repository resolves, then reading once, plus a thrown read and a reply over
`SystemPromptLimit.MAX_BYTES` both landing `Unavailable`. `channelSubmitSendsOnlyWhatChangedToTheEditorsOwnHostAndCloses`
covers an untouched form sending nothing and closing, a name-only submit renaming once on the row's own
host, a prompt-only submit writing once verbatim, both together renaming then writing, and a stored `null`
with an empty draft writing nothing — with `"host"`'s same-id channel asserted untouched throughout.
`channelSubmitFailuresStayOpenAndARetryNeverRepeatsAConfirmedRename` covers a prompt write failing after a
confirmed rename leaving `failed = true` with `savedName` already advanced, a retry sending only the
prompt, a rename failure alone setting `failed`, and asserts no captured log line carries the name, the
prompt, either id or an exception message. `anUnreadPromptNeverWritesAndNeverBlocksTheNameOrArchive` covers
a `Reading` or `Unavailable` prompt letting a name-only submit and Archive through while a prompt draft is
silently dropped rather than written. `channelArchiveArchivesOnlyItsOwnHostAndFailuresStayOpen` covers
archiving `("Host","same")` closing the editor and leaving `"host"`'s same-id channel active, working with
a blank name field and an unread prompt alike, and a throwing `archive` leaving `archiveFailed` set for a
retry. `channelEditorSendsNothingWhenUnavailableInvalidInFlightOrDismissed` covers the reject list: no open
editor, a blank or over-limit draft, a disconnected host, a second submit while one is already `saving`, and
a read or a write landing after `dismissChannelEditor()` neither reopening the modal nor overwriting a
fresher one.

**Mute notifications checkbox coverage (#1021, same colliding-id fixture).** `Repo` gains `setMuted`,
recording every write, applying it to that repo's own rows the way the daemon's echo would, and failing a
scripted count of times. `channelEditorOpensAtItsOwnHostsMuteFlagAndWritesItOnlyWhenChanged` covers opening
`("Host","same")` and `("host","same")` each reading `savedMuted` from that host's own row though the ids
collide, submitting at the opening value sending no `setMuted`, Cancel/Close/Back sending nothing, and a
flipped value sending exactly one `setMuted` to the editor's own host before the modal closes — with the
other host's same-id channel asserted untouched throughout. `aFailedMuteWriteStaysOpenAndARetrySendsOnlyTheUnconfirmedWrites`
covers the full **rename → mute → prompt** order: a rename that lands then a mute write that fails leaves
`failed = true` with the confirmed rename already recorded and nothing else sent; a retry whose mute lands
but whose prompt write fails records `savedMuted` and leaves the editor open; a further retry sends only the
prompt, and the totals show exactly one rename, one mute and one prompt reaching the editor's own host, none
reaching the colliding id's other host. Both tests assert no captured log line carries the name, the prompt,
either id or an exception message; the failure log is the static `channel_mute_write_failed` event.

**Disconnected-host coverage (#1336, same colliding-id fixture).** `disconnectClosesThatHostsCreateAndEditModalsAndKeepsAnotherHostsOpen`
opens Host's Edit chat and Create channel, host's Edit channel and a failed Chats create on host, then flips
Host offline: Host's Edit chat and Create channel close, host's Edit channel and failed create are
untouched, and a half-up host (`Connected`/`Handshaking`) counts as not connected too — a reconnect brings
back no modal. `aDisconnectedHostRefusesEveryCreateAndEditOpen` flips Host offline first, then calls
`createChat`, `openCreateChannel`, `openChatEditor` and `openChannelEditor` on it: every one publishes
nothing, reads or creates nothing, and logs its own `*_rejected code=disconnected` line.
`aSubmitRacingADisconnectSendsNothingAndClosesItsModal` and `aChatsCreateRacingADisconnectSendsNothing`
set `Main` to a `StandardTestDispatcher` on the test scheduler so the snapshot watcher is still queued when
the submit runs — `Dispatchers.setMain(StandardTestDispatcher(testScheduler))`, not the suite's shared
`UnconfinedTestDispatcher`, which would let the watcher close the modal before the submit's own check ran
and prove nothing about that check. Each of `submitCreateChannel`, `submitChatName`, `archiveChat`,
`submitChannelEdit` and `archiveChannel` is opened connected, raced against an offline flip, and asserted
to send no write and to close its own modal, with its `code=disconnected` log line as the proof that the
submit's own re-check (not the watcher) refused it; a Chats-section `createChat` is raced the same way.

Unavailable-target coverage denies lookup even with cached rows and connected
indicators. Failure tests inspect the action job's cancellation state as well as
missing navigation: absence of navigation alone cannot prove cancellation was
re-thrown. Demo coverage uses the production repository selector and a paired host
owning a migrated legacy default, then checks scratch and an explicit `demo`
default on the existing fake singleton. These are deterministic contract tests.
The [live regression gate](../../e2e-interactive-stream.md#pre-ship-gate) proves
different defaults on two live hosts with
`interactiveTurn_twoHostsDefaultsAndArchive_stayPerHost` (#1086, revised #1190): each host's
default is set from its own Settings, while a chat created through its Chats-section confirmation
uses the daemon default independently of that saved app folder; Archive stays host-isolated.

`rowTapsAndCreationTargetTheirNamedHostRegardlessOfTheSelectedAdapter` (#738,
reshaped from the pre-existing `rowTargetsAndLegacySelectedProjectionAndActionsUseSeparateNavigationStreams`)
is the test that proves the retirement rather than merely asserting it: it moves
the sibling [selected-host compatibility adapter](navigation.md#temporary-flat-list-compatibility)
to a second host (`f.selected.value = f.b.repo`) and asserts a row tap and a
`submitCreateChat` call still land on their own named hosts, never following
the adapter. Reshaping the existing fixture this way — rather than deleting the
test — is positive proof the dependency was actually cut, where a deletion would
have proven nothing.

`ChannelListViewModelTest` — the flat-screen compatibility test class this
document once described here (20 cases: `initialState_isLoading` through
`longPressPicker_overridesDefaultWorkspace`) — retired whole in #738 (683 lines)
along with the `state` / `onEvent` / `navigationEvents` contract it existed to
prove. `HostChannelListViewModelTest` is now the only test class for this view
model, living at `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt`
and using JUnit 4. Its class-level `private val dispatcher = UnconfinedTestDispatcher()`
field (bound to `Dispatchers.setMain(dispatcher)` in `@Before`, passed to
`runTest(dispatcher) { … }` in every test body, `resetMain()` in `@After`) is this
class's own convention, not inherited from the retired sibling.
