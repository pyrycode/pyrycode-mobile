# 683 — Save a host diagnostic archive from mobile

Settings' Storage section gains a **Log data** entry that downloads the owning host's daemon-wide
diagnostic archive through the shared mobile modal and writes it to a document the operator picks.
This is the only user-facing entry point for #682's transfer, which nothing calls today.

## Files read

| Path | Symbol | Why it matters |
| --- | --- | --- |
| `app/src/main/java/de/pyryco/mobile/data/repository/DebugBundleTransfer.kt` | `DebugBundleStatus`, `DebugBundleState`, `DebugBundleArchive`, `DebugBundleTransfer` | #682's contract: the closed status set this ticket maps to copy, the `acceptedChunks` progress source, and `takeArchive`'s take-once semantics |
| `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` | `requestDebugBundle` | The entry point. `@Synchronized` against removal; an unknown or disconnected host is answered with a rejected transfer, never another host's |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | `requestDebugBundle`, `endDebugBundle` | Proves the call is synchronous and non-blocking (`pump.send` returns a `Boolean`), and that the repository already rejects a second request per connection — `BUSY` while receiving, `RECONNECT_REQUIRED` once one has finished |
| `app/src/main/java/de/pyryco/mobile/ui/host/HostEditor.kt` | `HostEditorController`, `HostEditorState`, `HostEditorModal` | The shape this ticket copies: a plain controller over the owner's `viewModelScope`, a flag-carrying state with no record-typed field, and a binding composable that resolves failure flags to strings so the view model stays free of `Context` |
| `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` | `SettingsViewModel`, `openOwnerHostEditor`, `connection` | Where the controller is constructed and where the captured owner's resolved display name is already available |
| `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` | `SettingsRow`, the Storage section, `ChevronIcon` | The row treatment `Clear cache` already has, and `SettingsRow`'s nullable `onClick` that #715 uses to draw an ownerless row inert |
| `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` | `MobileModal` | The shell contract: caller owns visibility and submission, `loading` disables OK, `error` renders into a live region |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` | `ThreadDestinationFactory.settings` | Where the registry hand-off goes — the factory already holds the registry, so no new Koin definition |
| `app/src/main/java/de/pyryco/mobile/MainActivity.kt` | the `Routes.SETTINGS` composable, the scanner's `rememberLauncherForActivityResult` | The wiring site and the launcher idiom already in the tree |
| `app/src/main/java/de/pyryco/mobile/ui/settings/HostIdentityRow.kt` | `BoundedLine` | The `MAX_WORKSPACE_LABEL_CHARS` clamp every surface rendering a host name applies |
| `docs/knowledge/features/settings-screen.md` | § Storage, § Testing | The row inventory this entry joins, and the four-times-repeated `androidTest` parameter cascade — `compileDebugAndroidTestKotlin` is a required gate for any change to `SettingsScreen`'s signature |
| `docs/knowledge/features/mobile-modal.md` | § Caller contract, § Callers | The shell's fixed `Cancel`/`OK` labels, and why a second step inside it is a content swap rather than a stacked dialog |

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The node is the **modal shell only** — the `Edit host` instantiation of `MobileModal`: a full-height
dark surface with a `titleLarge` heading and a circular close glyph over a thin divider, a vertically
centred content column of label-over-value lines and one outlined action, and a centred
`Cancel` / `OK` footer. This ticket reuses that shell verbatim through `MobileModal` and supplies only
content — no new shell geometry, no new colour or type scale. There is no Log data frame in the file
and the locked Settings frame [`17-2`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2)
has no Log data row, so the entry is drawn as one more Storage row in `Clear cache`'s existing
language: headline plus `ChevronIcon`, no supporting line, no new section header.

## Context

#682 landed the transfer — request, chunk accumulation, the closed status set and the take-once
archive — but no caller. Desktop already exposes Download in its Log data section with progress and
saved/failed results; this is the mobile equivalent, scoped to one host. It must not claim the archive
covers only the current conversation: the daemon assembles a gzip-wrapped tar for the whole daemon.

The host is the one **this Settings destination captured** (#749), not whichever host compatibility
selection points at — the same rule #715 gave the Archive row and #751 gave the Edit host modal.

No ADR is warranted: this introduces no new architectural choice, only a second consumer of the
`MobileModal` shell and a first consumer of #682's transfer.

**Sizing overage, stated deliberately.** Total written work lands over the 800-line boundary once the
plan doc is counted. The ticket's own `Estimate:` section already made this call under the refiner
guide's floor-beats-ceiling rule, and I agree with it: a download with nowhere to write it delivers
nothing, and a save has nothing to write without it, so either half would be a one-consumer slice of
the other. The other five boundary lines hold — 5 production `.kt` files, 5 new exported types,
2 consumer call sites, 5 acceptance criteria, 9 reject branches.

## Design

### Package structure

One new file, `app/src/main/java/de/pyryco/mobile/ui/settings/DebugBundleDownload.kt`, holding the
state, the controller and the modal binding — the same one-file grouping `ui/host/HostEditor.kt` uses
for `HostEditorController` + `HostEditorModal`.

### Key types

```kotlin
/** Where a completed archive is written. Every method runs off the main thread, in the controller. */
internal interface ArchiveDestination {
    /** The picked document's own display name, for the success line. Never daemon-authored. */
    fun name(): String
    fun openStream(): OutputStream
    /** Removes a document this save created but could not fill. */
    fun discard()
}

/** One static sentence per failure. The id resolves at the screen, so nothing here holds a Context. */
internal enum class DebugBundleFailure(@StringRes val message: Int)
// Nine arms: the seven non-terminal DebugBundleStatus values, plus PICKER_CANCELLED and WRITE_FAILED.

internal data class DebugBundleDownloadState(
    val hostName: String,
    val receiving: Boolean = false,
    val saving: Boolean = false,
    /** Non-null exactly while a completed archive is held and unsaved. Size only — never the bytes. */
    val readyBytes: Long? = null,
    val acceptedChunks: Int = 0,
    val savedTo: String? = null,
    val failure: DebugBundleFailure? = null,
)

internal class DebugBundleDownloadController(
    scope: CoroutineScope,          // the owning ViewModel's viewModelScope
    serverId: String,               // the destination's captured owner, fixed at construction
    request: (String) -> DebugBundleTransfer,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    val state: StateFlow<DebugBundleDownloadState?>
    fun open(hostName: String)
    fun requestArchive()
    fun onDestination(destination: ArchiveDestination?)  // null means the picker was cancelled
    fun dismiss()
}

@Composable
internal fun DebugBundleModal(
    state: DebugBundleDownloadState?,
    onRequest: () -> Unit,
    onSave: () -> Unit,
    onDismissRequest: () -> Unit,
)

/** The ContentResolver-backed destination the route builds from a picker Uri. */
internal fun documentArchiveDestination(resolver: ContentResolver, uri: Uri): ArchiveDestination
```

Flags rather than a phase enum, following `HostEditorState`: it keeps the type count at five and
makes the two orthogonal facts — "an archive is held" and "the last attempt failed" — representable
together, which the cancelled-picker retry needs.

**The state never holds the archive.** `readyBytes` is a `Long`; the `DebugBundleArchive` lives in a
private field on the controller. Neither this class nor its state has a redacting `toString`, and a
crash trace renders whatever they hold — the same rule `SettingsHost` and `HostEditorState` carry.

**Both display strings in this state are clamped at the boundary that produces them.** `hostName` is
clamped in `open()` because a paired host's name and id both originate in a scanned QR payload;
`savedTo` is clamped in `onDestination`'s success path because `OpenableColumns.DISPLAY_NAME` is
supplied by whichever document provider the operator picked, which is a third-party app and is not
bound to the name we suggested. Both use `MAX_WORKSPACE_LABEL_CHARS`, the bound every surface that
renders externally-authored text on this screen already applies, and both render through a
`maxLines = 1` `Text` so an embedded newline cannot restructure the modal's content column.

**The archive is written and nothing else.** It is never opened, unpacked, parsed, hashed, uploaded
or offered to a share sheet, and no affordance for any of those is added. The only operation
performed on the bytes is `writeTo` into the picked document's stream.

### Data flow

1. The Storage row's tap reaches `SettingsViewModel.openLogData()`, which resolves the owner's current
   display name out of `connection` (falling back to the server id, clamped to
   `MAX_WORKSPACE_LABEL_CHARS`) and calls `controller.open(name)`. A blank captured owner is rejected
   with a content-free log line, exactly as `openOwnerHostEditor` rejects one — and the route passes a
   null `onClick` for that case anyway, so the row draws inert like #715's Archive row.
2. OK in the opened modal calls `requestArchive()`, which calls `request(serverId)` — always the
   constructor-bound id, never selection — and collects the returned `DebugBundleTransfer.state`.
3. Each `RECEIVING` emission republishes `acceptedChunks`. A terminal failure status maps through the
   exhaustive `when` to a `DebugBundleFailure` and clears `receiving`. `COMPLETE` calls `takeArchive()`
   **once**, into the controller's own field, and publishes `readyBytes`.
4. OK in the ready state reaches the route, which launches `ActivityResultContracts.CreateDocument`
   with a fixed media type and a fixed suggested name.
5. The result callback hands the controller either a `documentArchiveDestination` or null.
6. `onDestination` writes on `io`, then publishes `savedTo`. OK then dismisses.

### Which `when` stops compiling

`DebugBundleStatus` → `DebugBundleFailure?` is a single `when` with no `else` arm, in the controller.
It is the only place the status set is interpreted, so #682 adding an arm breaks the build here and
nowhere else. `DebugBundleFailure` carries its own `@StringRes`, so the screen needs no second `when`.

### Recomposition seams

`DebugBundleModal` is drawn exactly while `state` is non-null, the presence rule `HostEditorModal`
already uses. The shell's `loading` is `receiving || saving`; its `error` is the failure's string.
OK's meaning is a function of the state alone — request when nothing is held and nothing is in
flight, save when an archive is held, dismiss once one is saved — so the screen holds no local state.

## State + concurrency model

- **One controller per destination**, constructed with `viewModelScope`, as `HostEditorController` is.
  Two Settings entries on the back stack never share a download, and clearing either cancels only its
  own collect and its own write.
- **One collect job** over `transfer.state`, cancelled and replaced by the next request and by
  `dismiss()`. The job is `viewModelScope`-bound, so screen exit and the driver's background close
  both reach it.
- **The write runs on an injected `CoroutineDispatcher`** (`Dispatchers.IO` in production,
  `UnconfinedTestDispatcher` in tests) inside a `viewModelScope.launch`, so it is off the main thread
  and has a defined cancellation path.
- **Single-request guard.** `requestArchive()` returns early unless the state is idle — not receiving,
  not saving, no archive held, nothing saved. Deterministic code, and the belt-and-suspenders half is
  #682's own `RemoteConversationRepository.requestDebugBundle`, which answers a second request with
  `BUSY` from a different fabric (a `@Synchronized` field on the repository, not an agent rule).
- **Take-once discipline.** `takeArchive()` is called exactly once, at `COMPLETE`, into the
  controller's own field; the field is cleared only after a write returns successfully. A cancelled
  picker or a failed write therefore leaves the archive exactly where the retry looks for it.
- **`dismiss()` drops the held archive** along with the state. Daemon bytes do not outlive the modal
  that is saving them, and the picker round trip does not go through `dismiss()` — the launched
  activity stops ours without touching composition.
- `state` is a `MutableStateFlow` read and written only from main-dispatched callbacks and from
  `viewModelScope` continuations; each terminal transition is a `compareAndSet` against the state
  published before the suspension, so a write completing after a dismissal cannot resurrect a closed
  modal. Same rule, same reason, as `HostEditorController.submitName`.

## Error handling

Nine failure categories, each one static sentence with no raw daemon or exception text:

| Source | `DebugBundleFailure` | Leaves the action |
| --- | --- | --- |
| `UNAVAILABLE` (no connection for that id, including after unpairing) | `UNAVAILABLE` | idle — a fresh request is allowed |
| `BUSY` | `BUSY` | idle |
| `RECONNECT_REQUIRED` | `RECONNECT_REQUIRED` | idle |
| `SEND_FAILED` | `SEND_FAILED` | idle |
| `REFUSED` | `REFUSED` | idle |
| `INVALID_STREAM` | `INVALID_STREAM` | idle |
| `DISCONNECTED` | `DISCONNECTED` | idle |
| picker returned no Uri | `PICKER_CANCELLED` | holding the archive — OK re-opens the picker |
| `name()`, `openStream()`, `writeTo` or `flush` threw | `WRITE_FAILED` | holding the archive — OK re-opens the picker |

`RECEIVING` and `COMPLETE` are not failures and have no arm in the failure enum; the `when` maps them
to null.

A failed write calls `destination.discard()` before publishing, so the picker-created document does
not survive as a partial archive. A `discard()` that itself throws is swallowed and logged
content-free — the save has already failed and there is nothing further to report.

`savedTo` is set only after `writeTo` returns and the stream closes. No path publishes it early, and
no failure path publishes it at all.

All logging is content-free and event-shaped (`event=log_data_requested`, `…_failed code=<status>`,
`…_saved`) through `RelayLog`, which is the existing debug-only sink. The archive bytes, the document
`Uri`, and any exception message are never logged.

## Testing strategy

**JVM (`app/src/test/java/de/pyryco/mobile/ui/settings/DebugBundleDownloadControllerTest.kt`),
`runTest` with `UnconfinedTestDispatcher` and a hand-written fake `request` plus a fake
`ArchiveDestination` over a `ByteArrayOutputStream`:**

- every `DebugBundleStatus` failure arm maps to its own `DebugBundleFailure` and its own string id,
  and the seven are distinct
- a second `requestArchive()` while receiving does not call `request` again
- a request always passes the constructor-bound server id
- a completed archive survives the picker round trip: the bytes reaching the fake destination equal
  the chunks the transfer accepted, and `savedTo` carries the destination's name
- a cancelled picker leaves the archive saveable — a second `onDestination` with a real destination
  still writes the same bytes
- a failed write publishes `WRITE_FAILED`, calls `discard()`, publishes no `savedTo`, and still
  leaves the archive saveable
- `savedTo` is absent until the write returns (a destination whose `openStream` suspends the assertion
  point via a gated stream)
- `dismiss()` clears the state, and a later `onDestination` writes nothing

**JVM (`SettingsViewModelTest`, existing file):** `openLogData()` with a blank captured owner
publishes no state and logs the rejection; with an owner it publishes the owner's resolved name.

**Compose (`app/src/androidTest/.../ui/settings/SettingsScreenTest.kt`, existing file):** the Log data
row renders in Storage and is inert when `onOpenLogData` is null; the modal renders the progress line
for a receiving state; a cancelled-picker state renders its sentence with the archive still saveable;
a failed-write state renders its sentence and no saved line. Driven through the existing
`setSettings(...)` helper, extended with defaulted parameters so the sixteen existing methods are
untouched. `compileDebugAndroidTestKotlin` is a required gate — this changes `SettingsScreen`'s
signature, which is the exact cascade that failed code review four times (#232, #234, #268, #751).

**No e2e rung.** The live download from a real test host is #684's check, which carries
`needs-real-claude`; this ticket's own acceptance is deterministic and the ticket says so explicitly.

## Documentation handoff

Pending, for the documentation stage:

- `docs/knowledge/features/settings-screen.md` § Storage — add the **Log data** entry to the row
  inventory: headline + chevron, inert when the destination owns no host, opens the shared modal.
- `docs/knowledge/features/settings-viewmodel.md` or `settings-viewmodel-how-it-works.md` (whichever
  keeps both under the docs-guard byte cap) — describe the download-then-save lifecycle: the single
  request guard, the take-once archive held outside screen state, and the nine failure categories.

No documentation-only acceptance criteria are carried forward from older tickets here.

## Open questions

1. Should the completed archive auto-launch the picker instead of waiting for a second OK?
   **Resolved in this plan:** no. One meaning per tap keeps OK unambiguous in a shell whose label is
   fixed, avoids a `LaunchedEffect` that must not refire across configuration change, and gives the
   cancelled-picker retry a route that is the same route as the first attempt.
2. What does the success line name — the suggested file name or the document's actual name?
   **Resolved:** the document's own `OpenableColumns.DISPLAY_NAME`, read inside the same IO block
   before the write, because the picker may dedupe the suggested name and the operator needs the name
   that exists. Falls back to the fixed name when the query yields nothing.
3. Does a fresh request after a completed or failed transfer succeed? **Resolved, and it is #682's
   behaviour, not this ticket's:** `RemoteConversationRepository` holds one transfer per connection,
   so a second request on the same connection answers `RECONNECT_REQUIRED`. The action stays usable
   and the sentence is accurate; nothing here works around it.

## Security review

**Verdict:** PASS (after one MUST FIX was folded into § Design before this section was written)

**Findings:**

- **[Trust boundaries] MUST FIX — fixed in the plan.** The success line names the picked document via
  `OpenableColumns.DISPLAY_NAME`. That string comes from whichever document provider the operator
  chose — a third-party app, not bound to the name we suggested — and the first draft put it into
  `DebugBundleDownloadState.savedTo` and onto the screen unbounded. A hostile or merely broken
  provider returning a megabyte-long or newline-bearing name would then restructure or hang the
  modal's content column. § Design now requires the same `MAX_WORKSPACE_LABEL_CHARS` clamp `hostName`
  gets, applied where the value enters state, plus `maxLines = 1` at render. The daemon-side boundary
  itself is clean: `DebugBundleTransfer.accept` already validates sequence order and the base64
  round trip, no daemon field influences the filename or the media type (both fixed constants), and
  the bytes are written rather than parsed or rendered.
- **[Tokens, secrets, credentials] No findings in the app, one in the payload — SHOULD FIX.** The
  controller is handed a `(String) -> DebugBundleTransfer` lambda and nothing else; it never receives
  `PairedServerCollectionStore`, a `PairedServer`, the pairing token or the server static key, and no
  new secret is created, stored or rotated. But the archive the *daemon* assembles is a diagnostic
  bundle: it plausibly carries host paths and log lines. The operator is about to put it wherever the
  system picker points, which may be a cloud-synced folder. Phase B copy must say the archive covers
  the whole daemon **and** that it can contain paths and logs, so the destination choice is informed.
  The verifier should check the copy carries both halves, not only the daemon-wide half AC1 names.
- **[File / storage operations] No path handling to attack.** No code path concatenates anything into
  a filesystem path: the only handle is a SAF `Uri` the operator produced, used through
  `ContentResolver.openOutputStream` and `DocumentsContract.deleteDocument`, both scoped to the single
  grant the picker returned. No check-then-use gap — there is no `exists()` probe. Writing outside
  app-private storage is the feature, not a leak, and no new file enters app storage, so
  `allowBackup` and at-rest encryption are unaffected. **Named limitation:** a process kill between
  `openStream` and the last chunk leaves a truncated document our `discard()` cannot reach, because
  our process is gone. Nothing reports it as saved, and the next attempt truncates it (`"wt"`), which
  is the honest bound on AC4's "no partial archive is written" — the app discards every partial it
  can observe.
- **[Inter-process / Android attack surface] No findings.** No exported component, `<intent-filter>`,
  deep link, `PendingIntent`, content provider or WebView is added. `CreateDocument` hands the chosen
  provider only the suggested file name and the media type before any byte is written, so a provider
  the operator picked learns that a pyrycode debug bundle was saved and nothing more.
- **[Cryptographic primitives] Not applicable, by construction.** Nothing here touches the handshake,
  the key schedule or the AEAD framing; the chunks arrive already decrypted through `NoiseIkSession`
  and this ticket adds no randomness, no comparison against a secret and no key material.
- **[Network & I/O] OUT OF SCOPE — one pre-existing exposure, to be filed as its own bug.**
  `DebugBundleTransfer` accumulates `chunks` with no cap on count or total size, and this ticket is
  the first caller that can reach it. A peer that streams chunks indefinitely exhausts the phone's
  memory. It is not fixed here: the accumulation lives in `DebugBundleTransfer.accept`, which is
  outside this ticket's five files, and § Scope Discipline requires filing rather than fixing an
  out-of-scope production defect. It is below MUST FIX because the peer must already be the paired
  daemon inside an authenticated Noise session — an attacker in that position has strictly greater
  capabilities than OOMing the client — and because the relay is content-blind and cannot inject
  frames into the session. Filed as a bug ticket on the board; linked from the PR body. No new HTTP
  client, timeout policy, TLS configuration or reconnect path is introduced.
- **[Error messages, logs, telemetry] No findings.** Every user-facing failure is one of nine static
  sentences with no format argument; no exception message, stack trace, status string, `Uri` or byte
  count from the daemon reaches the screen. `RelayLog` lines are event-shaped and carry only the
  enum arm name as `code=`. No telemetry is added.
- **[Concurrency] No findings, one behaviour to state.** Every coroutine is `viewModelScope`-bound, so
  screen exit and `onCleared` cancel the collect and the write; the write dispatcher is injected
  rather than hardcoded; terminal transitions are `compareAndSet` against the state published before
  the suspension, so a write landing after a dismissal cannot resurrect a closed modal. The
  controller's archive field is touched only from main-dispatched callbacks and `viewModelScope`
  continuations, which resume on Main. **Behaviour worth naming:** backgrounding the app mid-receive
  lets `LifecycleConnectionDriver` close the socket, `endDebugBundle` fails the transfer, and the
  modal shows the disconnected sentence. That is correct and honest, not a defect. The transfer has
  no stall deadline — a host that sends one chunk and stops leaves the modal receiving until the
  socket drops — and the shell's dismissal routes stay available throughout, which is the way out.
- **[Threat model alignment] Addressed.** *Malicious relay:* content-blind and on-path; it can drop,
  delay or stall, which surfaces as `DISCONNECTED` or an indefinite receive the operator can dismiss,
  and it never sees plaintext. *Hostile daemon frame:* malformed sequence or base64 is already
  rejected as `INVALID_STREAM` by #682; the unbounded-volume case is the filed out-of-scope item
  above. *Token theft from disk:* not applicable — nothing is persisted. *UI-side leakage:* the modal
  renders a host name and a file name, both clamped, and no archive content ever enters screen state,
  so a screenshot or an accessibility read harvests nothing beyond what the Connection section
  already shows (#750).

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22
