# Spec — #565 wire `recent_workspaces` to the daemon's v2 wire message

**Size:** S (confirmed; not split — see § Scope). **Security-sensitive:** yes (see § Security review) — the reply carries a set of untrusted filesystem paths decoded from the transport on an internet-exposed demux surface.

> **SSOT naming correction (read first).** The ticket body/title say "the `recent_workspaces` reply". Wired against merged `pyrycode/pyrycode#888`, the **request** type is `recent_workspaces` (empty payload) and the **reply** type is a *distinct* `recent_workspaces_list` (`{workspaces:[{path, last_used_at}]}`). This is exactly the "wire against #888, not the prose" trap the ticket flagged. Use the SSOT names below.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:952-982` — `observeConversations` + `listConversationsRequest()`. **The cold-read flow shape AND the empty-`{}`-payload request builder to clone.** Note the difference: `observeConversations` is *projection-backed* (`emitAll(projection…)` — the demux writes a `StateFlow`); `recentWorkspaces` has **no push projection** (#888 is one-shot), so it awaits a **correlated reply** via `sendAndAwaitReply` inside a `flow { }` instead of collecting a `StateFlow` (§ Design ③).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:668-685` — `sendAndAwaitReply`: register-before-send; `check(pump.send(...))` throws `IllegalStateException` if not `Open`; `finally`-remove. **The one-shot request/reply spine `recentWorkspaces()` awaits inside its `flow { }`.** Inherited unchanged.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:340-361` — the inbound demux **success-reply arm** (`TYPE_ACK, TYPE_CONVERSATION_CREATED, … TYPE_WORKSPACE_FOLDER_CREATED -> envelope.inReplyTo?.let { … complete(payload) }`). **Load-bearing: add `TYPE_RECENT_WORKSPACES_LIST` to this arm** (exactly as #564 added `TYPE_WORKSPACE_FOLDER_CREATED` at :341). A new reply type not listed here falls through to `else -> Unit` (~:480) and the pending deferred **hangs** until #488 teardown — the populated case would then silently degrade to empty and fail AC #1. This is the single most important edit — mirror delete (#532) / create (#564).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:634-646` — `mapError`. `conversation.not_found → IllegalArgumentException`; **every other code → `RelayErrorException`**; malformed error reply → fallback `RelayErrorException`. `recent_workspaces` names no conversation, so **no `not_found` path** exists for it — every server error is an ordinary `RelayErrorException` that the `.catch` degrades to empty. Inherited unchanged; **no per-error catch in the override**.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1738` — `TYPE_LIST_CONVERSATIONS` const (the read-verb neighbor). Add `TYPE_RECENT_WORKSPACES` + `TYPE_RECENT_WORKSPACES_LIST` next to it (§ Design ②).
- `app/src/main/java/de/pyryco/mobile/data/network/DeleteConversationPayloads.kt` (whole file, 41 lines) — **the two-DTOs-in-one-file pattern to clone** (holds >1 top-level class → ktlint's single-class-filename rule does not apply). `RecentWorkspacesPayloads.kt` is this shape but **decode-only** — no request DTO (the request payload is the empty object `{}`, built inline like `listConversationsRequest()`).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — `MobileJson` (`encodeDefaults=true`, `explicitNulls=false`, **`ignoreUnknownKeys = true`**). Why the reply DTO models `path` only and lets the discarded `last_used_at` fall through (§ Design ①).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:153-164` — the `recentWorkspaces(): Flow<List<String>>` interface contract + kdoc (**excludes `""` and `DEFAULT_SCRATCH_CWD`**; cold flow). **Signature unchanged**; the remote adds the override the `flowOf(emptyList())` default currently stands in for.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:21` — `const val DEFAULT_SCRATCH_CWD: String = "~/.pyrycode/scratch"` — the sentinel to filter client-side. Import `de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD` into the repository.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:89` — `recentWorkspaces() = switchToLive(emptyList()) { it.recentWorkspaces() }`. **Already delegates; confirm only, untouched.**
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:98` — `recentWorkspaces() = recents` (a fake-owned `StateFlow`). Read only to confirm the fake≠remote divergence is benign (§ Open questions); **untouched.**
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePicker.kt:55-57` — the picker **already** collects `recentWorkspaces()` via `collectAsStateWithLifecycle(initialValue = emptyList())` and passes it to `WorkspacePickerSheet`. **Confirms there is no UI change** — the data-layer override alone populates the Recent section (AC #2). Read to verify; **untouched.**
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:5124-5136` (`workspaceFolderCreatedEnvelope`) + the `delete_*` suite (:2037+) — the **reply-envelope-builder + reply-correlation test shape to mirror.** Add `recentWorkspacesListEnvelope(inReplyTo, paths)` and collect the one-shot flow with `.first()` (§ Testing). `runCurrent()`, not `advanceUntilIdle()`.
- Daemon SSOT (verified byte-by-byte vs merged `pyrycode/pyrycode#888`; local checkout `~/Workspace/Projects/pyrycode/`): `internal/protocol/workspace.go:32-58` (`RecentWorkspacesPayload struct{}` / `RecentWorkspacesListPayload{Workspaces}` / `RecentWorkspace{Path, LastUsedAt}`), `internal/protocol/codes.go:123-138` (both type strings), `internal/relay/handlers/recent_workspaces.go` (the dedup/sort/skip-empty fold), `internal/protocol/testdata/recent_workspaces_list.json` (canonical reply). Field shapes inlined below — **you do not need the pyrycode checkout.**
- Relevant lessons (Read/grep — codegraph doesn't index markdown): `ktlint filename rule: single class ⇒ file named after it` (two DTOs in one file exempts it — name the file `RecentWorkspacesPayloads.kt`); `Remote repo pump-fan-out tests: runCurrent not advanceUntilIdle`; `android.util.Log throws in plain JVM unit tests` (the recents path adds **no** `Log.*`).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-2

Workspace Picker Sheet (node `20:2`), the **"Recent" section** — an **anchor only**. The section already exists and renders (`WorkspacePickerSheet` consumes the `recent` list the picker collects from `recentWorkspaces()`); this ticket is a **data-layer wire with no composable change**. The row's "Last used …" recency subtitle is a design detail **out of scope** for this wire — the message returns paths only (the `last_used_at` field is discarded; § Design ①). No new UI is drawn, so no visual-fidelity work is prescribed here.

## Context

`ConversationRepository.recentWorkspaces()` is **not** overridden by `RemoteConversationRepository`; it inherits the `flowOf(emptyList())` interface default (`ConversationRepository.kt:164`), so the picker's "Recent" section is silently **always empty** — present in the UI, never populated (Tier 3 in the 2026-07-03 backend-gaps audit). The Workspace Picker already collects and renders `recentWorkspaces()` (`WorkspacePicker.kt:55-57`) and `StableConversationRepository` already delegates it live (`switchToLive`, :89), so this ticket is **entirely data-layer**: adding the remote override closes the loop.

`recent_workspaces` is a **read verb** modeled on `list_conversations` (#312): a **one-shot request/reply**. Per #888 a live push feed that re-emits on change is explicitly **out of scope daemon-side** — there is no projection to subscribe to. So the flow issues one request and emits one list per collection; a fresh open of the picker re-collects and re-fetches.

### Verified daemon SSOT (merged Go, `pyrycode/pyrycode#888`)

- **Request** — type `recent_workspaces`; `RecentWorkspacesPayload struct{}` (`workspace.go:36`). **Payload is empty by spec** (the type exists so the dispatcher decodes into a concrete value, mirroring `ListConversationsPayload`). The client sends `payload = {}` — clone `listConversationsRequest()` (:976-982), no request DTO.
- **Success reply** — type `recent_workspaces_list` (a **distinct, NEW** type, **not** a reused `conversations`/`conversation_updated`), correlated via `in_reply_to`; `RecentWorkspacesListPayload{ Workspaces []RecentWorkspace \`json:"workspaces"\` }` where `RecentWorkspace{ Path string \`json:"path"\`; LastUsedAt time.Time \`json:"last_used_at"\` }` (`workspace.go:44-58`). The slice is **always non-nil** — an empty result marshals as `"workspaces":[]`, never `null`. Canonical fixture (`testdata/recent_workspaces_list.json`): `{"workspaces":[{"path":"/Users/juhana/pyry-workspace/alpha","last_used_at":"2026-05-08T09:12:00Z"},{"path":"/Users/juhana/pyry-workspace/beta","last_used_at":"2026-05-07T18:04:30Z"}]}`.
- **Ordering + dedup are the daemon's job** (`recent_workspaces.go:44-76`). The handler folds the conversations registry's distinct non-empty `Cwd` values, each carrying its most-recent `LastUsedAt`, sorted **most-recent-first** (tie broken by `Path` ascending, deterministic). **The client does NOT re-sort or re-dedup** — it preserves wire order.
- **Sentinel handling — the one client responsibility.** The daemon **skips empty/whitespace `Cwd`** (`strings.TrimSpace(conv.Cwd) == "" → continue`, :52-54) but does **NOT** strip `DEFAULT_SCRATCH_CWD` (`~/.pyrycode/scratch`) — a conversation bound to the scratch dir would surface it. The interface contract (`ConversationRepository.kt:158`) mandates excluding **both** `""` and `DEFAULT_SCRATCH_CWD`, so the client applies a client-side filter regardless of daemon behaviour (belt-and-suspenders for `""`; **load-bearing for scratch**).
- **Archived conversations' folders are included by design (#888)** — a workspace is a folder, not a conversation; archiving does not "un-use" its folder. A folder whose conversations are all archived carries an older `LastUsedAt` and sinks lower. The client surfaces whatever the daemon returns; **no client-side archived filter** (§ Open questions).
- **Errors** — an empty registry yields `"workspaces":[]`, not an error. There is **no `not_found` code** for this verb (it names no conversation). Any server `error` is an ordinary `RelayErrorException`, degraded to empty by the `.catch` (§ Error handling).
- **v1/v2 note (no action):** `recent_workspaces`/`recent_workspaces_list` are `v1TypeSet` members (`codes.go:129-137`); "v2 wire message" in the ticket title names the encrypted v2 **transport**, not the v1/v2 type partition. Mobile dials `/v1/client` regardless (lesson `Phase-4 wire /v1 path trap`) — nothing here changes.

## Design

Three edits, **all in the data layer**, all mirroring the read-verb (`list_conversations`) + correlated-reply (`delete`/`create`) patterns already in the repo.

### ① New DTO file — `data/network/RecentWorkspacesPayloads.kt`

Two **decode-only** DTOs in one file (clone `DeleteConversationPayloads.kt`'s two-in-one shape → ktlint filename rule N/A):

```kotlin
@Serializable
data class RecentWorkspacesListPayloadDto(val workspaces: List<RecentWorkspaceDto>)  // decode-only

@Serializable
data class RecentWorkspaceDto(val path: String)                                      // decode-only
```

- Both JSON keys (`workspaces`, `path`) are already the wire names — **no `@SerialName`**.
- **`last_used_at` is deliberately NOT modeled.** `MobileJson`'s `ignoreUnknownKeys = true` tolerates it (and any future row field). Modeling `path` only follows the reply-DTO "model only what you consume" discipline (`ConversationDeletedPayloadDto` models only `id`) and keeps the Figma "Last used …" subtitle out of scope. No request DTO — the request is the empty `{}` object.
- kdoc each, mirroring `DeleteConversationPayloads.kt`: `RecentWorkspacesListPayloadDto` is **decode-only** (binary→phone reply to a `recent_workspaces` request); cite server SSOT `RecentWorkspacesListPayload` (#888); note the slice is always present (`[]` when empty), ordering/dedup are daemon-authoritative (client does not re-sort), and correlation rides `Envelope.inReplyTo`. `RecentWorkspaceDto` — one row; only `path` is consumed; `last_used_at` is intentionally dropped (recency subtitle out of scope), tolerated by `ignoreUnknownKeys`.

### ② New companion constants (near `TYPE_LIST_CONVERSATIONS`, :1738)

```kotlin
const val TYPE_RECENT_WORKSPACES = "recent_workspaces"           // request (empty payload)
const val TYPE_RECENT_WORKSPACES_LIST = "recent_workspaces_list" // correlated reply (NEW type)
```

kdoc the reply const as "correlated reply carrying the distinct recent-workspace paths, most-recent-first, daemon-ordered (#565, #888)".

### ③ The `recentWorkspaces()` override + request builder (add near the read-verb flows, ~:982 after `listConversationsRequest`)

Contract (**signature unchanged**): `override fun recentWorkspaces(): Flow<List<String>>`.

The **cold one-shot request/reply flow** — the guarded read-verb shape (contract sketch, ~7 lines):

```kotlin
override fun recentWorkspaces(): Flow<List<String>> =
    flow {
        val reply = sendAndAwaitReply(recentWorkspacesRequest())
        val list = MobileJson.decodeFromJsonElement<RecentWorkspacesListPayloadDto>(reply)
        emit(list.workspaces.map { it.path }.filter { it.isNotBlank() && it != DEFAULT_SCRATCH_CWD })
    }.catch { emit(emptyList()) }

private fun recentWorkspacesRequest(): Envelope =
    Envelope(id = requestId.incrementAndGet(), type = TYPE_RECENT_WORKSPACES,
             ts = Clock.System.now().toString(), payload = JsonObject(emptyMap()))
```

Load-bearing behaviour, in order:

1. **Cold, per-collection.** Each `collect` re-issues the request (fresh picker open ⇒ re-fetch; AC #1). No projection, no caching, no dedup across collections.
2. **Correlated await.** `sendAndAwaitReply` sends the empty-`{}` `recent_workspaces` frame and suspends on the deferred keyed by `Envelope.id`; the `recent_workspaces_list` demux arm (④) completes it with the reply payload.
3. **Decode → paths → filter.** Decode the reply at the single `RecentWorkspacesListPayloadDto` boundary; take `path`s in wire order (**no re-sort** — ordering is daemon-authoritative); filter out the two sentinels. Use `isNotBlank()` (a safe superset of the contract's `""` exclusion — the daemon already trims, so this only ever removes what it also would). Emit **once**.
4. **`.catch { emit(emptyList()) }` — the guard (AC #4).** Degrades **every non-cancellation** throwable to an empty emission: not-`Open` `IllegalStateException` (`sendAndAwaitReply`'s `check`), server `RelayErrorException`, teardown-mid-await `IllegalStateException` (#488 `failAllPending`), and malformed-reply decode exceptions (`SerializationException`/`IllegalArgumentException`). `Flow.catch` is **cancellation-transparent** — it does **not** catch the `CancellationException` a lifecycle-STOP/sheet-dismiss raises, so a cancelled collect stops cleanly with no spurious empty emit. This is the "family's guarded one-shot repo-call" realised for a `Flow` (contrast `GuardedRepoLaunch` #490, which guards a *suspend* call launched from the UI).

Add a kdoc mirroring `observeConversations`': one-shot (no push projection exists daemon-side, #888), cold/re-fetch-per-collection, daemon-ordered (no client re-sort), the two-sentinel client filter and why (contract + scratch not stripped server-side), and that `.catch` fails **closed to empty** on every wire/connection error while staying cancellation-transparent.

### ④ Demux registration (:340-341) — the load-bearing edit

Add `TYPE_RECENT_WORKSPACES_LIST` to the success-reply arm's type list at :340-341, and extend its comment to name the new reply (`a recent_workspaces_list (#565) carries the {workspaces:[…]} the recentWorkspaces waiter decodes for its path list`). Also add it to the "always correlated reply (the daemon never broadcasts …)" note (:354-356) — `recent_workspaces_list` is a pure reply, never an unsolicited push. Without this arm the well-formed reply falls through to `else -> Unit` and the deferred hangs until #488 teardown — the exact delete/create-family hazard (#532/#564), which `list_conversations` (#312) did **not** face because its reply drives the `projection` StateFlow, not a correlated deferred.

### Data flow

```
WorkspacePicker: recentWorkspaces().collectAsStateWithLifecycle(initialValue = emptyList())   (unchanged UI)
  └─ RemoteConversationRepository.recentWorkspaces()  ── flow { } (cold, one-shot) ──
       ├─ sendAndAwaitReply(recent_workspaces, payload = {})            (register-before-send)
       │     ├─ not Open                → IllegalStateException ─┐
       │     ├─ server error            → RelayErrorException  ─┤
       │     └─ teardown mid-await      → IllegalStateException ┤ (#488)
       ├─ decode<RecentWorkspacesListPayloadDto>(reply)          │
       │     └─ malformed reply         → decode throws ────────┤
       ├─ .map { it.path } (wire order — NO re-sort)             │
       ├─ .filter { isNotBlank() && != DEFAULT_SCRATCH_CWD }     │  (AC #3)
       └─ emit(list)  ──────────────────────────────────────────┼─▶ Recent section populated   (AC #1/#2)
             .catch { emit(emptyList()) } ◀───────────────────────┘  (any throw ⇒ empty, no error)  (AC #4)
```

## State + concurrency model

- **No new coroutine/scope in the data layer.** `recentWorkspaces()` returns a plain cold `flow { }`; it runs in the collector's context (Compose `collectAsStateWithLifecycle` ⇒ `repeatOnLifecycle`). The await rides the shared single inbound collector via `sendAndAwaitReply`, correlated by `Envelope.id` ↔ reply `in_reply_to`. No second pump subscription.
- **No projection touched.** Unlike `observeConversations` there is **no** `StateFlow` mutation — the method's only effect is its single emission. Single-source-of-state: nothing is stored; each collection is independent and re-fetches (matches the one-shot / re-open-re-fetch AC).
- **Cold, per-collector** (like `observeConversations`/`observeMessages`) — no subscriber sharing, so no cross-screen data leak.
- **Cancellation / teardown.** Inherited from `sendAndAwaitReply`: register-before-send, `finally`-remove; #488 `failAllPending` completes a mid-await deferred with `IllegalStateException` on teardown → `.catch` → empty (never hangs). A lifecycle-STOP/sheet-dismiss cancels the collect → `await()`'s `CancellationException` propagates (not caught by the cancellation-transparent `.catch`) → collection stops. Re-subscription (RESUME/re-open) re-issues the request.

## Error handling

All server-error mapping is **centralized** in `mapError` + `sendAndAwaitReply`'s not-`Open` `check`; the override adds **no per-error catch** — the single `.catch` turns every failure into one empty emission.

| Failure | Produced by | Type thrown | Flow behaviour |
|---|---|---|---|
| Not connected (pump not `Open`) | `sendAndAwaitReply` `check` | `IllegalStateException` | `.catch` → `emit(emptyList())` (AC #4) |
| Any server `error` reply | demux `TYPE_ERROR` → `mapError` | `RelayErrorException` (no `not_found` for this verb) | `.catch` → empty |
| Malformed / undecodable reply | `RecentWorkspacesListPayloadDto` decode | `SerializationException` / `IllegalArgumentException` | `.catch` → empty |
| Teardown mid-await | `failAllPending` (#488) | `IllegalStateException` | `.catch` → empty |
| Collector cancelled (lifecycle STOP / dismiss) | collecting scope | `CancellationException` | **not** caught (`.catch` is cancellation-transparent) → collection stops, no emit |

The `.catch` is intentionally **broad and fail-closed-silent** (no `Log.*` — never-log contract; consistent with the demux decode arms that drop malformed payloads silently). Showing an empty Recent section on any wire error is the safe degrade the AC pins; distinguishing error causes would add branches for no user-visible benefit.

## Testing strategy

**Unit only** (`./gradlew testDebugUnitTest`). **No `androidTest`** — there is no UI change (the picker already collects/renders `recentWorkspaces()`), so no `ComposeTestRule` work. AC #5 is satisfied by the codec test + the repository-projection tests below.

**Codec — new `RecentWorkspacesPayloadsTest.kt`** (mirror `ConversationResponseDtoTest` / the delete-payload codec test):
- Canonical reply `{"workspaces":[{"path":"/Users/x/pyry-workspace/alpha","last_used_at":"2026-05-08T09:12:00Z"},{"path":"/Users/x/pyry-workspace/beta","last_used_at":"…"}]}` decodes to a `RecentWorkspacesListPayloadDto` with two `RecentWorkspaceDto` whose `path`s match — `last_used_at` is tolerated (`ignoreUnknownKeys`) and absent from the DTO.
- Empty `{"workspaces":[]}` decodes to an empty `workspaces` list.
- (SHOULD, cheap) a reply with an extra unknown row/top-level field still decodes (forward-compat proof for `ignoreUnknownKeys`).

**Repository — extend `RemoteConversationRepositoryTest.kt`** (add a `recentWorkspacesListEnvelope(inReplyTo, paths)` reply builder mirroring `workspaceFolderCreatedEnvelope` at :5124; collect the one-shot flow via `async { repo.recentWorkspaces().first() }`). Scenarios (bulleted; developer writes bodies in the project idiom):
- **Request shape.** Collect `recentWorkspaces()`; assert exactly one `recent_workspaces` frame was sent with payload `{}` (empty object), and no other frame.
- **Populated → filtered, order preserved.** Push a `recent_workspaces_list` reply whose paths are `["/z/proj", "", "~/.pyrycode/scratch", "/a/proj"]` (deliberately **not** alphabetical); assert the emission is `["/z/proj", "/a/proj"]` — both sentinels (`""` + scratch) filtered, and **wire order preserved** (proves the client does not re-sort). This test **also proves the demux registration (④)**: without the new arm the deferred never completes and the flow degrades to empty, failing the assertion.
- **Empty registry → empty.** Push `{"workspaces":[]}` → emission is `emptyList()`.
- **Disconnected → empty (AC #4).** Pump never `Open`; `recentWorkspaces().first()` returns `emptyList()` **without** any reply push and without hanging (the `check` throws ISE synchronously → `.catch` → empty).
- **Malformed reply → empty (SHOULD).** Push a `recent_workspaces_list` reply with a row missing `path` → decode throws → `.catch` → `emptyList()`.

Drive the pump→demux→complete flow with `runCurrent()`, **not** `advanceUntilIdle()` (lesson `Remote repo pump-fan-out tests: runCurrent not advanceUntilIdle`).

## Scope

Production Kotlin files (excluding tests/md/spec): **2** — `RecentWorkspacesPayloads.kt` (new), `RemoteConversationRepository.kt` (modified: override + request builder + 2 consts + 1 demux word). `WorkspacePicker.kt` / `WorkspacePickerSheet.kt` / `StableConversationRepository.kt` / `FakeConversationRepository.kt` / the interface are **untouched** (the picker already collects `recentWorkspaces()`; Stable already delegates via `switchToLive`; signature unchanged). Two new exported types (both decode DTOs). **Zero consumer fan-out** — the interface signature is unchanged, so every call site and the fake/stable overrides are untouched. Error mapping is centralized and reused (**no new reject branch**); one demux arm gains one type. ~30 (DTOs) + ~25 (override + builder + consts + demux) ≈ **~55 production LOC**; ~130 test LOC across two test files ≈ **~185 total**. Every red line clears with margin: production files 2 (≤3; the § 4 self-check gate is ≥5), total LOC ~185 (≤600), exported types 2 (≤5), consumer call sites 0 (≤10), reject branches 0-new (≤10), ACs 5 (≤5). **Size S, no split.** PO's `size:s` confirmed. Branch-overlap check (`git fetch --prune` + `git branch -r` vs `origin/main`, 2026-07-10): no in-flight `origin/feature/*` branch touches `RemoteConversationRepository.kt`, the new DTO file, or the repo test — no blocker to wire.

## Open questions

None blocking. Deliberate deferrals, all named:

- **Fake keeps its own `recents` `StateFlow`** (populated in-memory by `createWorkspaceFolder`); the remote re-fetches from the daemon per collection. Benign fake≠remote divergence — both satisfy "the Recent section shows the workspaces you've used". The AC constrains only the remote wire; aligning the fake is out of scope (no observed failure).
- **`last_used_at` not surfaced.** The Figma "Last used …" recency subtitle is design-owed and out of scope per the ticket (the message returns paths). A future recency-subtitle ticket would model the field and thread it to the row.
- **Archived-folder inclusion.** The daemon includes folders whose conversations are all archived (by design, #888). The client surfaces the daemon's list verbatim; no client-side archived filter. Named, not a gap — a "hide fully-archived workspaces" policy, if ever wanted, is a daemon-side decision.
- **No live re-emit on change** (#888 one-shot only). The picker re-fetches on re-open; a workspace created/used elsewhere appears on the next open, not push-live. Named; deferred with the daemon.

## Security review

**Verdict:** PASS

**Findings:**

- **[1 Trust boundaries]** No findings — one untrusted→trusted crossing, explicit and contained. The `recent_workspaces_list` reply is decoded at the single `RecentWorkspacesListPayloadDto` boundary (`RemoteConversationRepository.recentWorkspaces`'s `flow { }`); a malformed reply throws at decode and is caught by `.catch` → empty. **There is no projection fold**, so untrusted bytes reach no store — the decoded paths exist only as the flow's single emission. Downstream, the picker holds them as a `List<String>` (display) and, on pick, forwards a chosen path as a `change_workspace` wire field the daemon re-validates. The phone never treats these strings as trusted.
- **[3 File / storage operations]** N/A — **the security-relevant point.** The decoded paths are **never** used for any client filesystem access: none is opened, `File`-wrapped, canonicalised, or concatenated into a path on the phone. The client filter (`isNotBlank()` + `!= DEFAULT_SCRATCH_CWD`) is plain emptiness/equality against a public constant — no path parsing, no traversal surface. A crafted `path` (`../../etc`, `/home/victim/.ssh`, absolute, symlinked) is a **display-only** string; picking it sends it back to the daemon, which independently confines/validates any workspace change. Path-traversal / TOCTOU / storage-scope / atomic-write / `allowBackup` are all server-side concerns the daemon owns (#888).
- **[6 Network & I/O]** No findings — reuses `sendAndAwaitReply` over the existing `SessionPump`/OkHttp WebSocket; **no new socket, no timeout/TLS/frame-size change.** The new reply type is registered in the demux (§ Design ④) so a well-formed reply never falls through to `else -> Unit` and hangs (the delete-family hazard, explicitly closed). #488's `failAllPending` fails an in-flight recents-await promptly (ISE → `.catch` → empty) rather than hanging. A hostile relay returning a very large `workspaces` array is bounded by the **inherited** OkHttp max-frame cap (unchanged here) and rendered in the picker's bounded `LazyColumn` — the same general inbound-frame concern shared by `conversations`/`message_chunk`, not lifted by this ticket.
- **[7 Error messages, logs, telemetry]** No findings — the paths are network-sourced (they reveal server-side filesystem structure), so this is the co-crux. This spec adds **no `Log.*`** on any recents path (DTO, decode, override, demux); `RelayErrorException.message` is **never** logged (`mapError` never logs); the `.catch` degrades **silently** (never-log contract, consistent with the demux decode arms). No path bytes are logged, put in an error message, or surfaced to a crash reporter. The paths appear only in the picker's Recent section — the user's own recently-used folders, the intended surface.
- **[8 Concurrency]** No findings — no new data-layer coroutine/scope; **no shared-state mutation at all** (no fold), so no TOCTOU on any `StateFlow`. The flow is **cold** (per-collector), so no cross-screen data leak. `Flow.catch` is cancellation-transparent (it does not swallow `CancellationException`) — avoiding the `catch(IllegalStateException) swallows CancellationException` trap the codebase documents — so a dismissed picker cancels the collect cleanly. Each collection is independent; a failed collect yields empty and nothing partial.
- **[2 Tokens, secrets, credentials]** N/A — no tokens/keys/credentials on this path. The frame rides the already-established Noise_IK session (`data/network`, `data/crypto`), untouched. `Envelope.id` is the existing monotonic `requestId` counter (not a secret).
- **[4 Inter-process / Android attack surface]** N/A — no new `Activity`/`Service`/`Receiver`/deep-link/`PendingIntent`/provider/WebView. An internal repository method behind the DI-wired `ConversationRepository`, collected in a Compose sheet.
- **[5 Cryptographic primitives]** N/A — no RNG, hashing, key handling, or comparison introduced. `path != DEFAULT_SCRATCH_CWD` compares a value against a **public** constant, not a secret — no constant-time concern.
- **[9 Threat model alignment]** The relevant mobile-wire threat — a malicious/compromised relay returning a crafted `recent_workspaces_list` or `error` frame — is contained: a crafted reply either fails the typed decode (nothing emitted but `emptyList()`, no state) or maps to a caught exception (→ empty). A reply returning **attacker-chosen paths** is a **display-only** effect on the phone (they appear in the Recent section); it grants no filesystem access on the phone (mobile never uses the path for I/O), and any subsequent server-side use of a picked path (a later `change_workspace` cwd) is independently re-validated by the daemon. Resource exhaustion via a giant list is bounded by the inherited OkHttp max-frame cap. Out of scope (named): live re-emit on change (#888 defers, no broadcast); recency subtitle (design-owed); archived-folder policy (daemon-side); operator-run e2e.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-10
