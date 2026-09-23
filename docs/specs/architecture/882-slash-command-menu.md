# #882 — Keep the server-published slash-command menu for each conversation

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/ModelListPayloads.kt` → `ModelListPayloadDto`, `ModelListRowDto`, `toMenu` — the decode-only DTO + total mapper shape this ticket copies.
- `app/src/main/java/de/pyryco/mobile/data/repository/ModelMenuProjection.kt` → `ModelMenuProjection.apply`, `observe`, `decodeModelList` — the per-conversation retention this ticket mirrors, minus the `request_model_list` ask and refusal correlation (this frame has no inbound verb).
- `app/src/main/java/de/pyryco/mobile/data/repository/ApiRetryProjection.kt` → `ApiRetryProjection` — the minimal projection shape (no send, no capabilities supplier) the new projection takes.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound` `TYPE_MODEL_LIST` arm, the projection fields, `observeModelMenu`, the `TYPE_*` companion constants — where the new arm, field and override sit.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeModelMenu` default, `ModelMenu`, `ModelMenuRow` — the interface default and the co-located domain types the new ones sit beside.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `observeModelMenu`, `switchToLive` — the host-isolating passthrough.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` → `setModelMenu`, `observeModelMenu` — the seeding seam.
- `app/src/test/java/de/pyryco/mobile/data/network/ModelListPayloadsTest.kt`, `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryQuestionTest.kt` (`FakeSessionPump`, `newRepo`), `StableConversationRepositoryTest` (`RecordingConversationRepository`), `FakeConversationRepositoryTest` — test shapes to mirror.
- `../pyrycode/docs/protocol-mobile.md` § `slash_command_list` and `../pyrycode/internal/protocol/testdata/slash_command_list{,_empty,_zero}.json` — the wire contract and fixtures (cited, not restated).

In-flight overlaps: #875 and #890 also edit `ConversationRepository.kt`, `RemoteConversationRepository.kt` (and #890 `StableConversationRepository.kt` + its test). Both add unrelated sibling readings; no dependency. Edits here are additive next to the model-menu members, so a later merge only interleaves.

## Design source

N/A — data-layer ticket, no UI.

## Context

Mobile drops the daemon's `slash_command_list` frame today (it falls through the `onInbound` `else -> Unit`). The Actions control and slash completion tickets need the per-conversation menu. This slice decodes and retains it exactly the way #791 retained `model_list`. No ADR warranted — it is a sibling of an established pattern.

## Design

### Wire layer — new `data/network/SlashCommandListPayloads.kt`

- `internal data class SlashCommandListPayloadDto(conversationId: String /* conversation_id */, commands: List<SlashCommandListRowDto>, droppedCommands: Int /* dropped_commands */)` — all three required, no defaults (a missing key or explicit `null` `commands` fails the frame).
- `internal data class SlashCommandListRowDto(name: String, argumentHint: String /* argument_hint */, description: String, aliases: List<String>, truncatedFields: List<String>? = null /* truncated_fields */)` — the four wire-required fields have no default; `truncatedFields` is the one nullable array, `null` = nothing cut.
- `internal fun SlashCommandListPayloadDto.toMenu(): SlashCommandMenu` — total, non-throwing field copy; `droppedCommands` copied, never derived from `commands.size`.

### Domain types — in `ConversationRepository.kt`, beside `ModelMenu`

- `data class SlashCommandMenu(rows: List<SlashCommandMenuRow>, droppedCommands: Int)` — present-but-empty is distinct from absent (`null` at the flow).
- `data class SlashCommandMenuRow(name: String, argumentHint: String, description: String, aliases: List<String>, truncatedFields: List<String>?)` — verbatim, workspace-authored, SECURITY KDoc (inert text only; never a log, URL, attribute, filename or cache key).

### Interface — `ConversationRepository`

- `fun observeSlashCommandMenu(conversationId: String): Flow<SlashCommandMenu?> = flowOf(null)` — `null` = "no frame heard", default keeps every inline test double compiling.

### Projection — new `data/repository/SlashCommandMenuProjection.kt`

- `internal class SlashCommandMenuProjection` with a private `MutableStateFlow<Map<String, SlashCommandMenu>>`.
- `fun apply(envelope: Envelope)` — decode; on success replace that conversation's entry (`update { it + (id to menu) }`); on failure do nothing. Never clears, never logs.
- `fun observe(conversationId: String): Flow<SlashCommandMenu?>` — `map { it[conversationId] }.distinctUntilChanged()`. **No `onStart` ask** — there is no request verb.
- `private fun decodeSlashCommandList(envelope): Pair<String, SlashCommandMenu>?` — one `try/catch (IllegalArgumentException)` around the `MobileJson` decode; returns `null` for a malformed payload **and for an empty `conversation_id`** (the "no usable conversation_id" case: `""` names nothing — the daemon's `_zero` fixture shape). This is the one deliberate step beyond `decodeModelList`, which retains under `""`; the AC asks for a usable id, and an entry nobody can meaningfully route to is dead weight.

### Repository — `RemoteConversationRepository`

- New field `private val slashCommandMenuProjection = SlashCommandMenuProjection()` next to the other status projections.
- New companion constant `TYPE_SLASH_COMMAND_LIST = "slash_command_list"` next to `TYPE_MODEL_LIST`.
- New `onInbound` arm after `TYPE_MODEL_LIST`: `if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) slashCommandMenuProjection.apply(envelope)` — the same gate as the model-list arm. Folds no thread row, clears no stall.
- `override fun observeSlashCommandMenu(conversationId) = slashCommandMenuProjection.observe(conversationId)`.

### Facade and fake

- `StableConversationRepository.observeSlashCommandMenu` = `switchToLive<SlashCommandMenu?>(null) { it.observeSlashCommandMenu(conversationId) }` — host isolation via `flatMapLatest`.
- `FakeConversationRepository.setSlashCommandMenu(conversationId, menu: SlashCommandMenu?)` (null clears) + `observeSlashCommandMenu` over a private map `StateFlow`, the `setModelMenu` shape.

## State + concurrency model

Single writer: the repository's one inbound collector calls `apply`. Reads fan out from the `StateFlow` via cold `map` + `distinctUntilChanged`, so a frame for another conversation and a value-identical re-snapshot do not re-emit. Connection-scoped: a fresh repository per connection (#351) starts empty; the connect-time snapshot (no `event_id`) refills it. Nothing clears on a connection edge. No jobs launched, so no new cancellation path.

## Error handling

Malformed payload (missing/wrong-typed field, `commands`/`aliases` explicitly `null`, a row missing a required string) → decode throws `SerializationException` ⊂ `IllegalArgumentException`, caught and discarded (its message can quote input) → nothing written, prior menu stands, collector survives. Empty `conversation_id` → dropped the same way. Non-interactive connection → arm never decodes. No branch logs.

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest`); no UI, so no Compose or e2e test — not an operator-facing flow.

- **`data/network/SlashCommandListPayloadsTest`** (new) — the three daemon fixtures embedded verbatim: populated decodes 5 rows in daemon order with names, hints (`<model>` from `<`), the multi-line non-ASCII `claude-api` description, aliases (`reset`,`new`; `cost`,`stats`), per-row `truncated_fields` and `dropped_commands = 2`; `_empty` → present menu with zero rows; `_zero` → decodes with empty id and one all-empty row. Plus: `__remote-workflow` name verbatim; `truncated_fields` absent reads `null`; `commands: null`, `aliases: null` and a row missing `argument_hint` each fail the frame.
- **`data/repository/RemoteConversationRepositorySlashCommandTest`** (new, own `FakeSessionPump` like the sibling split test files) — `null` until a frame, then the fixture's rows; later frame replaces wholesale (shorter replacement, count replaced too); frame for c1 leaves c2 `null`; a burst sharing one envelope id routes by `conversation_id`; empty `commands` is present and distinct from `null`; non-interactive capability blocks; malformed probes + empty-id `_zero` fixture change nothing, prior menu stands, later valid frame applies; identical re-snapshot / foreign frame don't re-emit; subscribing sends nothing; reconnect: repo A holds a menu, fresh repo B on a new pump reads `null`, then a connect-time snapshot (no `event_id`) fills it.
- **`StableConversationRepositoryTest`** — absent live repo emits `null`; delegates and a host switch drops back to `null` (extend `RecordingConversationRepository` with a push seam).
- **`FakeConversationRepositoryTest`** — unseeded `null`; seeded surfaces for that conversation only; `null` clears.

## Open questions

- None blocking. Empty `conversation_id` handling is decided above (drop).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — one explicit boundary: `SlashCommandMenuProjection.decodeSlashCommandList` decodes the untrusted payload through `MobileJson` into the `internal` DTOs, and only the domain `SlashCommandMenu` leaves `data/network`. Every row string (`name`, `argumentHint`, `description`, each alias) is **workspace-authored** — a lower-trust origin than `model_list`'s claude-authored text — and carries embedded newlines in the real capture. The plan keeps them verbatim (ticket requirement) and the `SlashCommandMenuRow` KDoc must state the obligation: inert text only, never a WebView/HTML sink, attribute, URL, filename, cache key or log line, and nothing keys off them. Retention is keyed by the payload's own `conversation_id` only, never envelope id or burst position, so one conversation's frame cannot land under another.
- [Trust boundaries] SHOULD FIX (Phase B) — `name` is not an identifier (`__remote-workflow`, no charset guarantee). Tests must pin that the decode neither trims nor validates it, so a later "cleanup" cannot quietly start rejecting or rewriting real names.
- [Tokens] No findings — the frame carries no credential, and this ticket stores nothing on disk.
- [File / storage] No findings — in-memory, connection-scoped `StateFlow` map; nothing is persisted, and no row string is used as a path or key.
- [Inter-process] No findings — no Android component, intent, deep link, push or WebView touched.
- [Crypto] No findings — rides the existing Noise session; no primitive touched.
- [Network & I/O] No findings — decode-only, sends nothing (subscribing must not send a frame; a test pins it). Per-frame size is bounded by the existing transport frame cap; the daemon also bounds each field and the array (`truncated_fields`, `dropped_commands`), both carried so a consumer can tell a cut menu from a complete one.
- [Network & I/O] OUT OF SCOPE — map growth under a hostile daemon naming many conversation ids is bounded only by what it sends over the connection's life, the same accepted posture as `ModelMenuProjection` (no observed failure; the fresh repository per connection is the reset). Revisit alongside the model-menu map if it is ever observed.
- [Logs] No findings — no branch logs; the caught `IllegalArgumentException` (whose message can quote input) is discarded, and the conversation id is never logged.
- [Concurrency] No findings — single writer on the inbound collector with atomic `update {}`; no launched job; cold per-collector `map`/`distinctUntilChanged` over one `StateFlow`, looked up by the caller's own id; the facade's `flatMapLatest` drops the previous host's reading on a switch.
- [Threat model] Hostile daemon frame — decoded defensively, a malformed or empty-id frame changes nothing, and a non-`interactive` connection never decodes one (fail-closed). OUT OF SCOPE — control-character / escape handling at render time belongs to the consumers (the Actions control and slash-completion tickets split from #655), which own the render boundary the protocol names; sending a `name` back as message text is theirs too.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23
