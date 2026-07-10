# Spec — #564 wire `create_workspace_folder` to the daemon's v2 wire message

**Size:** S (confirmed; not split — see § Scope). **Security-sensitive:** yes (see § Security review) — the reply carries an untrusted filesystem path decoded from the transport, and the request carries two untrusted path components.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1518-1538` — the shipped `rename` override (kdoc + body). **The request/reply spine to clone**: build `Envelope` → `sendAndAwaitReply` → typed-decode the reply. `createWorkspaceFolder` is this **minus** the `upsertConversation` fold (create touches no conversation projection) and **minus** the conversation-keyed payload.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1657-1684` — the shipped `changeWorkspace` override (#560), the closest sibling and the **placement anchor**: add `createWorkspaceFolder` immediately after it, before the `companion object` at :1686.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:338-356` — the inbound demux `when` **success-reply arm** (`TYPE_ACK, TYPE_CONVERSATION_CREATED, … -> envelope.inReplyTo?.let { … complete(payload) }`). **Load-bearing: add `TYPE_WORKSPACE_FOLDER_CREATED` to this arm** (as delete added `TYPE_CONVERSATION_DELETED` at :338). A new reply type not listed here falls through to `else -> Unit` (:480) and the pending deferred **hangs** until teardown (#488). This is the single most important edit — mirror delete (#532), not change_workspace (#560, which reused an already-routed reply type and needed no demux edit).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:629-641` — `mapError`. `conversation.not_found → IllegalArgumentException` (:636); **every other code → `RelayErrorException`** (:639); malformed error reply → fallback `RelayErrorException` (:634). `create_workspace_folder` has **no `not_found` code** (§ Context) — every server error is `protocol.malformed` → `RelayErrorException`. Inherited unchanged; **no per-error catch in the override body**.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:671-680` — `sendAndAwaitReply` (register-before-send; `check(pump.send(...))` throws `IllegalStateException` if not `Open`; `finally`-remove). Inherited unchanged.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1714-1751` — the private `companion object` write-verb constants (`TYPE_CREATE_CONVERSATION` … `TYPE_CHANGE_WORKSPACE` :1736, `TYPE_CONVERSATION_DELETED` :1739). Add the two new consts + the client parent-root const here (§ Design ②).
- `app/src/main/java/de/pyryco/mobile/data/network/DeleteConversationPayloads.kt` (whole file, 41 lines) — **the two-DTO-in-one-file pattern to clone.** It holds an encode-only request DTO **and** a decode-only reply DTO in one file (so ktlint's single-class-filename rule does not apply). `CreateWorkspaceFolderPayloads.kt` is this shape with a `{parent, name}` request and a `{path}` reply.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:164-183` — the `createWorkspaceFolder(name: String): String` interface contract + its throwing default (`error(...)` at :182-183) and the blank-name `IllegalArgumentException` clause (:174). **Signature unchanged**; the remote adds the override the default currently stands in for.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:278-283` — the fake's override (`require(name.isNotBlank())`, builds `"pyry-workspace/$name"`). **The blank-name contract to mirror** (AC #4). Left untouched; read only to match the `require` shape and confirm the fake's relative-path convention is a benign fake≠remote divergence (§ Open questions).
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:125` — `createWorkspaceFolder(name) = live.createWorkspaceFolder(name)`. **Already delegates; unchanged** — confirm only.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePicker.kt:55-66` — **the Tier-1 crash site.** The create-dialog launch does `val path = repository.createWorkspaceFolder(name); onPicked(path)` with **no try/catch**. This is the block you wrap to surface a failure instead of crashing (§ Design ⑤).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialog.kt:52-53,85,91` — the dialog **already trims** (`trimmedName = fieldValue.text.trim()`) and **disables Create when blank** (`isCreateEnabled = trimmedName.isNotEmpty()`). So the `name` reaching the picker is already trimmed and non-blank in the shipped UI; the remote's `require`/`trim` are the contract belt-and-suspenders, not the primary trim authority. **Unchanged.**
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerSheet.kt` — the `ModalBottomSheet` picker. On a failed create `onPicked` is **not** called, so the sheet stays visible (the error surface should read cleanly on top of it). Read to confirm you need not touch it (§ Design ⑤ keeps the failure surface inside `WorkspacePicker.kt`).
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerTest.kt` (whole file, 93 lines) — the component-test harness: `setContent { WorkspacePickerInternal(repository = <fake>, onPicked, onDismiss) }`, drive the row → dialog → Create. **Your failure-surface test injects a repository whose `createWorkspaceFolder` throws** (§ Testing). Note `androidTest` is **not** compiled by the mandatory gates — run `./gradlew compileDebugAndroidTestKotlin` (see § Testing).
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1908-1960` (`delete_*` suite) — **the reply-correlation test shape to mirror** (`startDelete` + `conversationDeletedEnvelope(inReplyTo = sent.id, …)`). Add `startCreateWorkspaceFolder(repo, name)` + a `workspaceFolderCreatedEnvelope(inReplyTo, path)` reply builder. The `rename_*` suite (:1288-1450) is the error-branch template (not-connected/ISE, other-error/RelayError, malformed-reply/decode-throws) — **skip its `conversation.not_found` case** (create has no not-found).
- Daemon SSOT (verified byte-by-byte against merged `pyrycode/pyrycode#887`, CLOSED 2026-07-09; local checkout `~/Workspace/Projects/pyrycode/`): `internal/protocol/workspace.go:19-30` (`CreateWorkspaceFolderPayload{Parent, Name}` / `WorkspaceFolderCreatedPayload{Path}`), `internal/relay/handlers/create_workspace_folder.go` (handler, `$HOME` confinement, four static reject strings), `internal/protocol/testdata/create_workspace_folder.json` (canonical example `{"parent":"~/pyry-workspace","name":"new-project"}`). Field shapes inlined below — you do not need the pyrycode checkout.
- Relevant lessons (Read/grep — codegraph doesn't index markdown): `ktlint filename rule: single class ⇒ file named after it` (two DTOs in one file exempts it — name the file `CreateWorkspaceFolderPayloads.kt`); `androidTest not compiled by mandatory gates` (compile the component test explicitly); `android.util.Log throws in plain JVM unit tests` (the repo path adds **no** `Log.*` — do not introduce one).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=19-44

The **Create Folder Dialog** (node `19:44`) is an M3 `AlertDialog`: `headlineSmall` title "Create workspace", a single outlined text field labelled "What should this workspace be called?", and text buttons Cancel / Create (`Schemes/primary`). **This dialog already ships verbatim** as `CreateFolderDialog.kt` — this ticket does **not** redraw it; it wires what happens *after* Create is tapped. Its trigger row "Create new folder under pyry-workspace…" lives in the Workspace Picker Sheet (node `20:2`). **There is no Figma design for the failure state** — the message surface this ticket adds (§ Design ⑤) is design-owed; use a generic, idiomatic M3 error affordance (recommended: an error `AlertDialog`), pixel-fidelity deferred.

## Context

`ConversationRepository.createWorkspaceFolder(name)` is not overridden by `RemoteConversationRepository` — it inherits the throwing interface default (`error(...)`, `ConversationRepository.kt:183`). The shared Workspace Picker launches the call with **no** `try`/`catch` (`WorkspacePicker.kt:60-61`), so tapping "Create new folder" against the relay throws into an uncaught coroutine and **crashes the app**. The picker is reached from three places — the thread's workspace flow, the settings default-workspace row, and the FAB long-press picker — so the crash reproduces from all three (Tier 1 in the 2026-07-03 backend-gaps audit). Because the failure surface lives in the **shared** picker, fixing it once covers all three (AC #3).

`create_workspace_folder` is the **leanest** verb in the write-verb family (`create` #347, `promote` #348, `rename` #530, `archive`/`unarchive` #549, `delete` #532, `change_workspace` #560). Unlike every conversation write-verb it **names no conversation and carries no `conversation_id`**, touches **no** conversations projection, and replies with a **new** type (`workspace_folder_created`), not the reused `conversation_updated`. Its return — the created folder's path — is **meaningful** (it flows to `onPicked` and becomes the selected workspace), so there is **no** vestigial-`Session` problem (contrast #560).

### Verified daemon SSOT (merged Go, `pyrycode/pyrycode#887`)

- **Request** — type `create_workspace_folder`; `CreateWorkspaceFolderPayload{ Parent string \`json:"parent"\`; Name string \`json:"name"\` }` (`workspace.go:19-22`). **Both fields required, non-pointer.** `Parent` is the untrusted parent dir (a `~`/`~/` prefix anchors at the daemon's `$HOME`); `Name` is the untrusted single path element, validated server-side (non-empty after trim, not absolute, no `/`, no `..`) **before** it is joined under `Parent`.
- **Parent-path mapping (the one architect decision, settled against SSOT).** The mobile interface passes only `name`. The client sends **`parent = "~/pyry-workspace"`** (a fixed constant) + **`name = name.trim()`**. This is exact against the daemon's own golden fixture `testdata/create_workspace_folder.json` → `{"parent":"~/pyry-workspace","name":"new-project"}`, matches the Figma trigger row "…under pyry-workspace", and matches the Fake's `pyry-workspace/` convention. The **tilde prefix is load-bearing**: the daemon's `resolveWorkspaceFolder` does `expandTilde(parent)` → `filepath.Join(parent, name)` → confine-to-`$HOME`; a **relative** `"pyry-workspace"` would resolve against the daemon's process cwd (unpredictable — the same trap the daemon's empty-parent guard documents), so the client must anchor at `$HOME` with `~/`.
- **Success reply** — type `workspace_folder_created`, correlated via `in_reply_to`; `WorkspaceFolderCreatedPayload{ Path string \`json:"path"\` }` (`workspace.go:28-30`). `Path` is the **canonical, symlink-resolved absolute path** of the created folder, confined to `$HOME` (e.g. `/home/op/pyry-workspace/new-project`) — **server-authoritative**, not a client-derived join.
- **Errors** — all non-retryable, all code `protocol.malformed`, all **fixed static strings** (the daemon logs `conn_id` only and **never** echoes `parent`, `name`, the joined/resolved path, or the decode/confine error): undecodable payload (`msgCreateWorkspaceFolderMalformed`), empty parent (`…EmptyParent`), bad name — empty/absolute/separator/`..` (`…BadName`), rejected target — escapes `$HOME` / unresolvable / uncreatable (`…Rejected`). The mobile client treats **all four identically** as an ordinary server error → `RelayErrorException`; **no `not_found` code exists** for this verb.
- **`$HOME` confinement + directly-under-parent are the daemon's job** (two independent fail-closed gates, different fabric). The mobile side sends the two strings and **never touches the filesystem** with them.
- **No projection fold, no broadcast, no session transition.** The handler creates a directory and replies to the requester only — it consumes no conversations registry. Other clients are unaffected. Starting a conversation in the new folder and trust-marking it are out of scope in #887 (deferred to the eventual `create_conversation` into it).

## Design

Four edits — three in the data layer (all mirroring `delete`/`rename`), one in the shared picker (the crash fix).

### ① New DTO file — `data/network/CreateWorkspaceFolderPayloads.kt`

Two DTOs in one file (clone `DeleteConversationPayloads.kt`'s two-in-one shape → ktlint filename rule N/A):

```kotlin
@Serializable
data class CreateWorkspaceFolderPayloadDto(val parent: String, val name: String)   // encode-only

@Serializable
data class WorkspaceFolderCreatedPayloadDto(val path: String)                       // decode-only
```

- All three JSON keys (`parent`, `name`, `path`) are already the wire names — **no `@SerialName`**. Both request fields non-null/required (no Kotlin defaults) → always sent (`MobileJson`'s `explicitNulls=false` never elides a non-null `String`).
- kdoc each, mirroring `DeleteConversationPayloads.kt`: `CreateWorkspaceFolderPayloadDto` is **encode-only** (phone→binary; always encode through `MobileJson`), cite server SSOT `CreateWorkspaceFolderPayload` (#887); note `parent`/`name` are untrusted path components `$HOME`-confined **server-side** (the phone neither validates nor canonicalises them — § Security). `WorkspaceFolderCreatedPayloadDto` is **decode-only** (binary→phone reply); `path` is the daemon's canonical `$HOME`-confined realpath; correlation rides `Envelope.inReplyTo`, not a payload field.

### ② New companion constants (near :1736)

```kotlin
const val TYPE_CREATE_WORKSPACE_FOLDER = "create_workspace_folder"   // request
const val TYPE_WORKSPACE_FOLDER_CREATED = "workspace_folder_created" // correlated reply (NEW type)
const val WORKSPACE_FOLDER_PARENT = "~/pyry-workspace"               // fixed client root, tilde-anchored to daemon $HOME
```

kdoc the reply const as "correlated reply carrying the created folder's canonical path (#564, #887)" and the parent const with the mapping rationale (§ Context — fixed root, `~/`-anchored, matches the daemon fixture + Figma row).

### ③ The `createWorkspaceFolder` override (add after :1684)

Contract (**signature unchanged**): `override suspend fun createWorkspaceFolder(name: String): String`.

Behaviour — the `rename` spine minus the fold, plus the client-side blank-name guard:

1. `require(name.isNotBlank()) { "name must not be blank" }` — **first, before any send** (AC #4; mirrors the fake). A blank/whitespace-only name throws `IllegalArgumentException` and **no request is sent**.
2. Build an `Envelope` with `type = TYPE_CREATE_WORKSPACE_FOLDER`, `payload = MobileJson.encodeToJsonElement(CreateWorkspaceFolderPayloadDto(parent = WORKSPACE_FOLDER_PARENT, name = name.trim()))`, `id = requestId.incrementAndGet()`, `ts = Clock.System.now().toString()`.
3. `val reply = sendAndAwaitReply(request)` — throws on server `error` / not-connected **before** any return (§ Error handling).
4. `return MobileJson.decodeFromJsonElement<WorkspaceFolderCreatedPayloadDto>(reply).path` — the #318 typed-decode boundary; a malformed reply throws here. **No projection fold** — create touches no conversation state; the returned path is the sole effect (it flows to `onPicked`).

The two path components are forwarded to the daemon and **never** used for any client filesystem access; do **not** add client-side path validation or `$HOME` checks (confinement is server-side — § Security). Add a kdoc mirroring `changeWorkspace`'s: describe the round-trip, the fixed-parent mapping, the server-authoritative returned path, the blank-name contract, the three throw types (blank IAE / not-connected ISE / server `RelayErrorException`) plus the decode exception, and that **no state is folded**.

### ④ Demux registration (:338) — the load-bearing edit

Add `TYPE_WORKSPACE_FOLDER_CREATED` to the success-reply arm's type list at :338, and extend its comment to name the new reply (a `workspace_folder_created` (#564) carries the bare `{path}` the `createWorkspaceFolder` waiter decodes for its return). Without this the well-formed reply falls through to `else -> Unit` and the deferred hangs until #488 teardown — the exact delete-family hazard (#532), which change_workspace (#560) did **not** face because it reused an already-routed type.

### ⑤ Picker failure surface (`WorkspacePicker.kt:59-62`) — the crash fix

Wrap the create launch so any failure surfaces a **generic** user-visible message instead of crashing. Hold `var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }` in `WorkspacePickerInternal`; on the create attempt clear it, then:

- Contract: `scope.launch { try { onPicked(repository.createWorkspaceFolder(name)) } catch (CancellationException) { throw it } catch (Exception) { errorMessage = "<generic failure message>" } }`. Catch `CancellationException` **first** and rethrow (coroutine-cancellation correctness; JVM `CancellationException extends IllegalStateException`, so an ISE-only catch would swallow it — see the `catch(IllegalStateException) swallows CancellationException` lesson).
- Render `errorMessage` when non-null as a generic M3 error affordance **self-contained in `WorkspacePicker.kt`** (recommended: an `AlertDialog` titled "Couldn't create folder" with an OK button that clears `errorMessage`; the sheet is still visible beneath it since `onPicked` was not called). Keeping it in `WorkspacePicker.kt` holds the production-file count at 3 (§ Scope) and needs no change to `WorkspacePickerSheet.kt`.
- **Do NOT reach for `GuardedRepoLaunch` (#490)** — it silently swallows, which fails AC #3's "user-visible message". A bespoke try/catch here is correct.
- **Security: the message is a fixed generic string.** It MUST NOT include `RelayErrorException.message` (server-supplied) or the attempted path/name (§ Security, Error messages).

### Data flow

```
CreateFolderDialog.onCreate(trimmedName)  (dialog already trims + gates non-blank; unchanged)
  └─ WorkspacePicker: scope.launch { try { … } catch … }          # NEW: bespoke failure surface
       └─ RemoteConversationRepository.createWorkspaceFolder(name)
            ├─ require(name.isNotBlank())  ── blank ─▶ IllegalArgumentException (no send)   (AC #4)
            ├─ encode {parent:"~/pyry-workspace", name:name.trim()} ─▶ Envelope(create_workspace_folder)
            ├─ sendAndAwaitReply ─▶ (pump.send; await reply by in_reply_to)
            │     ├─ not Open          → IllegalStateException
            │     ├─ server error      → mapError → RelayErrorException   (all codes = protocol.malformed)
            │     └─ teardown mid-await → IllegalStateException (#488)
            ├─ decode<WorkspaceFolderCreatedPayloadDto>(reply).path
            │     └─ malformed reply   → decode throws (SerializationException / IAE)
            └─ return path  (server-authoritative canonical realpath)                        (AC #1)
       ├─ success → onPicked(path)  ── path becomes the selected workspace, same session      (AC #2)
       └─ any throw → errorMessage set → generic message shown, NO crash                      (AC #3)
```

## State + concurrency model

- **No new coroutine / scope in the data layer.** `createWorkspaceFolder` is a plain `suspend` method; the request↔reply await rides the shared single inbound collector via `sendAndAwaitReply`, correlated by `Envelope.id` ↔ reply `in_reply_to`. No second pump subscription.
- **No projection touched.** Unlike every other write-verb there is no `upsertConversation` / `removeConversation`; the method's only effect is its return value. No single-source-of-state concern — nothing is stored.
- **Picker scope.** The launch uses the picker's existing `rememberCoroutineScope()`; leaving composition (sheet dismissed) cancels it → `CancellationException`, rethrown by the catch. `errorMessage` is Compose state (`rememberSaveable`), the single source of the failure surface — no parallel state.
- **Cancellation / teardown.** Inherited from `sendAndAwaitReply`: registers-before-send, `finally`-removes; #488 `failAllPending` completes a mid-await deferred with `IllegalStateException` on teardown — an in-flight create never hangs.

## Error handling

All server-error mapping is **centralized** in `mapError` + `sendAndAwaitReply`'s not-`Open` `check`; the override adds **no per-error catch**. The picker's bespoke catch turns every failure into one generic message.

| Failure | Produced by | Type thrown | Picker behaviour |
|---|---|---|---|
| Blank / whitespace name | override `require` (before send) | `IllegalArgumentException` | caught → message (unreachable from the dialog — Create is disabled) |
| Not connected (pump not Open) | `sendAndAwaitReply` `check` | `IllegalStateException` | caught → message (AC #3) |
| Any server reject (`protocol.malformed`: malformed / empty-parent / bad-name / rejected) | `mapError` else-branch (:639) | `RelayErrorException` | caught → message (AC #3) |
| Teardown mid-await | `failAllPending` (#488) | `IllegalStateException` | caught → message |
| Malformed / undecodable reply | `WorkspaceFolderCreatedPayloadDto` decode | `SerializationException` / `IllegalArgumentException` | caught → message |

Note there is **no `conversation.not_found` path** (create names no conversation) — so, unlike `rename`/`changeWorkspace`, `mapError`'s IAE branch is never hit from the server, and there is no "IAE deliberately crashes" posture here. The only IAE is the client-side blank-name guard, which the picker's broad catch also handles (belt-and-suspenders; the dialog already prevents it). The picker catching broadly (not the #490 guard's IAE-passes-through policy) is correct **because this call site must never crash** (AC #3), and blank-name is structurally unreachable from the shipped dialog.

## Testing strategy

Unit (`./gradlew testDebugUnitTest`) for the codec + repository; **component** (`./gradlew connectedAndroidTest`, device required) for the picker failure surface. `androidTest` is **not** compiled by `test`/`lint`/`assembleDebug` — run `./gradlew compileDebugAndroidTestKotlin` to catch compile errors before a device run (lesson: `androidTest not compiled by mandatory gates`).

**Codec — new `CreateWorkspaceFolderPayloadsTest.kt`** (mirror `ConversationResponseDtoTest`), satisfying AC #5's "request/response codec":
- Request encodes to exactly `{"parent":"~/pyry-workspace","name":"foo"}` (two keys, no extras) through `MobileJson`.
- Reply `{"path":"/home/op/pyry-workspace/foo"}` decodes to `WorkspaceFolderCreatedPayloadDto(path = "/home/op/pyry-workspace/foo")`.

**Repository — extend `RemoteConversationRepositoryTest.kt`** (add `startCreateWorkspaceFolder(repo, name)` + `workspaceFolderCreatedEnvelope(inReplyTo, path)`; mirror the `delete_*`/`rename_*` suites). Scenarios:
- **Request shape.** Drive `createWorkspaceFolder("  foo  ")`; assert the single `create_workspace_folder` frame's payload is exactly `{"parent":"~/pyry-workspace","name":"foo"}` (proves the fixed parent + client-side `trim`).
- **Success returns the server path.** Push `workspaceFolderCreatedEnvelope(inReplyTo = sent.id, path = "/home/op/pyry-workspace/foo")`; assert the call returns `"/home/op/pyry-workspace/foo"` (server-authoritative — feed a path that is *not* a naive join of the request, to prove the client returns the reply's value).
- **Blank name → IAE, nothing sent.** `createWorkspaceFolder("")` and `"   "` each fail with `IllegalArgumentException`; assert no `create_workspace_folder` frame was sent (AC #4).
- **Not connected → ISE.** Pump never Open → result is `Result.failure(IllegalStateException)`.
- **Server error → RelayErrorException.** Push an error envelope (`code = "protocol.malformed"`, the reachable out-of-`$HOME`/bad-name case) → `RelayErrorException` carrying the code. **(Recommended)** assert its message is the daemon's static string — no path/name leak.
- **Malformed reply → decode throws.** Push a `workspace_folder_created` reply missing `path` → decode exception; nothing returned.

Drive the pump→demux→complete flow with `runCurrent()`, not `advanceUntilIdle()` (lesson: `Remote repo pump-fan-out tests: runCurrent not advanceUntilIdle`). The success test also **proves the demux registration (④)** — without the new arm the deferred never completes and the test times out.

**Component — extend `WorkspacePickerTest.kt`** (AC #5 "component coverage exercises the picker's failure surface"):
- **Failure shows a message, no crash.** `setContent { WorkspacePickerInternal(repository = <fake whose createWorkspaceFolder throws IllegalStateException>, onPicked = { picked += it }, onDismiss = {}) }` (subclass `FakeConversationRepository` overriding only `createWorkspaceFolder` to throw). Drive row → type a name → Create; `waitForIdle()`; assert the generic error message node **is displayed**, `onPicked` was **not** invoked, and the test completes (no crash). Tapping OK dismisses the message.
- The existing success test (`dialog_submit_…forwards_returned_path_to_onPicked`) already covers AC #2 against the fake — leave it; it still passes (the picker change wraps but does not alter the success path).

## Scope

Production Kotlin files (excluding tests/md/spec): **3** — `CreateWorkspaceFolderPayloads.kt` (new), `RemoteConversationRepository.kt` (modified: override + 3 consts + 1 demux word), `WorkspacePicker.kt` (modified: bespoke failure surface). `WorkspacePickerSheet.kt` / `CreateFolderDialog.kt` / `StableConversationRepository.kt` / the interface are **untouched**. Two new exported types (both DTOs). **Zero consumer fan-out** — the interface signature is unchanged, so the fake/stable overrides and every call site are untouched. Error mapping is centralized and reused (no new reject branch in `mapError`); one demux arm gains one type. ~40 (DTO) + ~35 (override + consts + demux) + ~30 (picker) ≈ **~105 production LOC**; ~200 test LOC across the three test files ≈ **~305 total**. Every red line clears with margin: production files 3 (≤4, the §4 gate is ≥5), total LOC ~305 (≤600), exported types 2 (≤5), consumer call sites 0 (≤10), reject branches 0-new (≤10), ACs 5 (≤5). **Size S, no split.** PO's `size:s` confirmed. Branch-overlap check (`git fetch --prune` + `git branch -r` vs `origin/main`, 2026-07-10): no in-flight `origin/feature/*` branch touches any of the five candidate files — no blocker to wire.

## Open questions

None blocking. Deliberate deferrals, all named:

- **Fake returns a relative path (`pyry-workspace/$name`); the remote returns the daemon's absolute realpath.** Benign fake≠remote divergence — both satisfy "the created path becomes the selected workspace". The AC constrains only the remote wire; aligning the fake is out of scope (no observed failure). If ever wanted, a fake-fidelity ticket.
- **Failure-state visual design.** The generic message affordance (§ Design ⑤) is design-owed — Figma `19:44` designs only the (already-shipped) input dialog, not an error state. Testable-today behaviour (message shown, no crash) is in scope; pixel fidelity is deferred.
- **Recent-workspaces population** — sibling #565 (`recent_workspaces` wire, blocked on nothing; disjoint method, same picker). A folder created here appears in recents only once #565 lands the read stream.
- **Operator-facing rung-3 e2e** — #566 (Inbox), blocked-by this ticket, rides the #421 harness, gated on the #537 `mutationsSupported` reachability family.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings — two untrusted↔trusted crossings, both contained. **(a) Inbound:** the `workspace_folder_created` reply is decoded at the single explicit `WorkspaceFolderCreatedPayloadDto` boundary; a malformed reply throws at decode — and there is no projection to corrupt (the method has no fold), so no untrusted bytes reach any store. **(b) Outbound:** `parent` (a fixed client constant) and `name` (an untrusted user string) are wire fields only; the phone is **not** the confinement authority and never touches the filesystem with either. The daemon confines the joined target to `$HOME` (fail-closed, symlink-resolved, **before** `MkdirAll`) and independently rejects a `name` that is not a single clean path element — two fail-closed gates, different fabric (`create_workspace_folder.go`).
- **[Tokens, secrets, credentials]** N/A — no tokens/keys/credentials on this path. The frame rides the already-established Noise_IK session (`data/network`, `data/crypto`), untouched. `Envelope.id` is the existing monotonic `requestId` counter (not a secret).
- **[File / storage operations]** N/A on the mobile side — **the security-relevant point.** No mobile filesystem access: neither path component nor the returned `path` is ever opened, `File`-wrapped, canonicalised, or concatenated into a path on the phone; `path` is returned as a `String` and handed to `onPicked` (display + a wire field for later verbs). Path traversal / TOCTOU / storage-scope / atomic-write / `allowBackup` are all server-side concerns the daemon owns and has handled.
- **[Inter-process / Android attack surface]** N/A — no new `Activity`/`Service`/`Receiver`/deep-link/`PendingIntent`/provider/WebView. The affordance is an internal repository method behind the DI-wired `ConversationRepository`, invoked from a Compose sheet; additionally `mutationsSupported`-gated in the shipping UI (dormant until #537/#551).
- **[Cryptographic primitives]** N/A — no RNG, hashing, key handling, or comparison introduced. No `==`-against-secret (there is no secret on this path).
- **[Network & I/O]** No findings — reuses `sendAndAwaitReply` over the existing `SessionPump`/OkHttp WebSocket; no new socket, no timeout/TLS/frame-size change. #488's `failAllPending` fails an in-flight create promptly (ISE) rather than hanging. **The new reply type is registered in the demux (§ Design ④)** — so a well-formed reply never falls through to `else -> Unit` and hangs (the delete-family hazard, explicitly closed here).
- **[Error messages, logs, telemetry]** No findings — **and the co-crux, because both request fields are untrusted paths.** The daemon returns fixed static error strings and logs `conn_id` only (never `parent`/`name`/path/err). On the client: this spec adds **no `Log.*`** on any create path (DTO, encode, decode, override, demux); `RelayErrorException.message` is **never** logged; and the picker's failure message is a **fixed generic literal** that never interpolates the server message or the attempted name/path (§ Design ⑤). So no untrusted path bytes are logged or surfaced. The returned `path` is shown in the workspace chip on success — a daemon-authored, `$HOME`-confined value, display-only.
- **[Concurrency]** No findings — no new data-layer coroutine/scope; **no shared-state mutation at all** (no fold), so no TOCTOU. Every throw is ordered before the return, so a failed create yields nothing (no partial state). The picker's `errorMessage` is Compose state on the main thread; the launch catches `CancellationException` first (avoiding the ISE-swallows-cancellation trap) so a dismissed sheet cancels cleanly.
- **[Threat model alignment]** The relevant mobile-wire threat — a malicious/compromised relay returning a crafted `workspace_folder_created` or `error` frame — is contained: a crafted reply either fails the typed decode (nothing returned, no state) or maps to a caught exception with no text leak. A crafted reply that returns an **attacker-chosen `path`** is a **display-only** effect on the phone (it appears as the selected workspace / chip); it grants no filesystem access on the phone (mobile never uses the path for I/O), and any subsequent server-side use of that path (a later `create_conversation` cwd) is independently re-confined to `$HOME` by the daemon. A user-supplied name that would escape (`../x`, `a/b`, absolute) is rejected server-side as `protocol.malformed`, surfaced as an ordinary caught error — not a client-side vulnerability. Out of scope (named): live cross-client fan-out (#887 defers it, no broadcast); recents population (#565); operator-run e2e (#566).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-10
