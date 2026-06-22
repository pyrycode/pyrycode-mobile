# Spec #438 — send `modal_answer` + `modal_cancel` control messages

**Ticket:** #438 — feat(data): send modal_answer + modal_cancel control messages
**Size:** S (PO-sized S, confirmed — 2 production files, 2 new internal types, 0 consumer cascade; see § Scope)
**Epic:** pyrycode#597 Phase 3 (ADR 025) — remote permission modal. **Split from #428** → #437 decode (merged) / **this = outbound send** / #439 render UI / #440 read-only mode.
**Depends on:** #437 (merged `a18f4bd`) — this slice echoes back the `modalId`/`optionId` the #437 decode surfaces.
**Security-sensitive:** **yes** (label present). Security pass at § Security review (verdict **PASS**) — required before commit per the label gate.
**Design source:** N/A — pure data-layer outbound-send slice; **no UI**. The render UI that calls these methods is #439 (it owns the Figma `16-8` modal design). No `## Figma` is a correct omission here, not a PO gap.

---

## Files to read first

Read these before writing anything. Every addition mirrors an existing precedent in this exact package — copy the precedent, don't invent. (Generated from `codegraph_context` + the reads done during this spec; off-topic hits pruned.)

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1034-1048` — **`registerPushToken` — the closest outbound-control precedent.** Build an `Envelope`, encode a `…PayloadDto` through `MobileJson.encodeToJsonElement(...)`, call `sendAndAwaitReply(request)`, return `Unit`, ignore the empty `{}` ack, let `error`/not-connected throw. **Copy this shape exactly.** Your two methods differ only in `type` + payload.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1003-1014` — `requestScreenSnapshot` — second outbound precedent, but it **decodes a typed reply**. The modal methods do **NOT** decode a reply (no typed response payload — only `ack`/`error`, exactly like `registerPushToken`). Note the difference so you don't add a phantom reply DTO.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:548-565` — `sendAndAwaitReply` — the #346 request↔reply primitive to **reuse**. Registers the `CompletableDeferred` *before* `pump.send`, throws `IllegalStateException` if the pump is not `Open` (`pump.send` returns `false`), surfaces a server `error` as the collector's exceptional completion, removes the pending entry in a `finally`. You add no new correlation infra.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:175` — `requestId = AtomicLong(0)`; use `requestId.incrementAndGet()` for each envelope `id` (the same field every outbound method uses).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1141-1167` — the companion-object `TYPE_*` constants block. `TYPE_MODAL_SHOWN` (1146) / `TYPE_MODAL_DISMISSED` (1153) already note *"The outbound `modal_answer`/`modal_cancel` constants belong to the answer-send slice (#438)."* — add them here.
- `app/src/main/java/de/pyryco/mobile/data/network/RegisterPushTokenPayloadDto.kt` (whole file, 32 lines) — **the encode-only DTO template**: `@Serializable data class`, `@SerialName` snake_case on each field, the KDoc voice (wire SSOT pointer, "encode-only", "all required, Go-struct order", never-logged note). The new modal payload DTOs mirror this.
- `app/src/main/java/de/pyryco/mobile/data/network/SnapshotPayload.kt:20-23` — `RequestSnapshotPayloadDto` is a one-field encode DTO — **the exact shape of `modal_cancel` (`{modal_id}`)**. Also shows a multi-type file (request + response DTOs together) is exempt from the ktlint single-class filename rule.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt:131-166` — the #437 **decode** DTOs `ModalShownPayloadDto` / `ModalDismissedPayloadDto` + `ModalOptionDto`. Shows `@SerialName("modal_id")`, the `@SerialName("class")`-style mapping, `internal` visibility, and the multi-type-file convention. The new outbound DTOs are the **encode mirror** of these.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalEvent.kt:60-69` — `ModalOption.id` is documented as *"the opaque token echoed back as the answer (#438)"*, and `modalId` as *"an opaque token to echo back in #438, never as a routing key it asserts."* Confirms: `modalId`/`optionId` are **opaque, caller-supplied** — this slice neither parses nor validates them.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:47-54` — `Envelope(id, type, ts, payload, inReplyTo=null, eventId=null)`; `:147-151` — `RelayErrorException(code, retryable, message)` (what a server `error` throws).
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1250-1344` — **the `registerPushToken` test quartet** (wire-shape, ack success, retryable/non-retryable `error`, `send`-false→`IllegalStateException`). The structural template for the modal tests.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:2907-2914` — `startRegisterPushToken` helper (launch the suspend method on `backgroundScope`, return a `() -> Result<Unit>` getter). Mirror it for `startAnswerModal`/`startCancelModal`.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` — the `FakeSessionPump` (`.sent` list, `.sendResult` flag, `.push(envelope)`), and the `ackEnvelope(inReplyTo)` / `errorEnvelope(inReplyTo, code, retryable)` helpers (used e.g. lines 1272, 1303). Reuse all; add nothing.
- **QMD — pyrycode SSOT:** `pyrycode-docs/specs/architecture/701-modal-wire-types.md` — the wire shapes (`modal_answer = {modal_id, option_id, answer_token}`, `modal_cancel = {modal_id}`) + `answer_token` semantics (client-minted idempotency key; **secrecy is explicitly a non-goal**; daemon dedups `(modal_id, answer_token)` to collapse replays). The mobile DTOs must match this byte-for-byte.
- **Lessons (memory):** ktlint single-public-class filename rule — a file with a single public top-level type must be named after it; a **multi-type file is exempt**. The new DTO file holds two types → any descriptive name is fine.

---

## Context

pyrycode#597 Phase 3 puts a permission/choice modal over the encrypted mobile wire. #437 (merged) decodes the two **binary → phone** modal envelopes (`modal_shown`, `modal_dismissed`) into the portable `ModalEvent` family on `RemoteConversationRepository.modalEvents`. This slice is the **outbound (phone → binary) half**: the two control messages that answer or cancel a surfaced modal.

The wire vocabulary is server-defined and **already merged** (pyrycode#701 SSOT; producer #703 merged):

- `modal_answer` (phone → binary): `{ modal_id, option_id, answer_token }`.
- `modal_cancel` (phone → binary): `{ modal_id }`.

`modal_id` and `option_id` are opaque tokens the caller (#439, via the #437-decoded modal) hands in — this slice echoes them verbatim and interprets neither. The daemon validates `modal_id` against its own current outstanding modal (first-answer-wins; a stale id rejected — pyrycode#703/#706) and maps `option_id` against its own recorded option list; it never trusts a phone-asserted conversation. `answer_token` is **not** authorization (that is `modal_id` validity + the per-device gate #702, default OFF); it is a client-minted **idempotency key** that lets the daemon collapse a replayed/reordered answer to a no-op.

This slice adds **only** the two concrete send methods + their payload DTOs + wire constants. The render UI that calls them is #439; the read-only/ungranted-reject handling is #440.

---

## Design

Purely additive, mirroring `registerPushToken` exactly. **No interface change, no facade change, no Fake change** — see § "Why not the interface" below.

### 1. Two outbound payload DTOs — new file `data/network/ModalOutboundPayloads.kt`

The encode mirror of #437's decode DTOs. Two `@Serializable internal data class`es in one file (multi-type → ktlint filename rule exempt). `internal` matches the #437 modal-DTO visibility and is the tightest scope (only the repo references them; same-module tests inspect the on-wire JSON, not the class).

| DTO | Wire `type` | Fields (`@SerialName` snake_case) |
|---|---|---|
| `ModalAnswerPayloadDto` | `modal_answer` | `modalId` → `modal_id`, `optionId` → `option_id`, `answerToken` → `answer_token` — all required non-null `String` |
| `ModalCancelPayloadDto` | `modal_cancel` | `modalId` → `modal_id` — required non-null `String` |

KDoc in the `RegisterPushTokenPayloadDto` voice: wire SSOT pointer (pyrycode#701 / `protocol-mobile.md` § Modal (v2)), "encode-only — the phone sends it; the only correlated reply is an empty `ack` on success or an `error` on failure", Go-struct field order, "always encode through `MobileJson`", and the never-log note (`modal_id`/`option_id` may name a sensitive command/path).

### 2. Two concrete suspend methods on `RemoteConversationRepository`

Contract sketches (not implementations):

```kotlin
/** Send modal_answer{modal_id, option_id, answer_token} for the surfaced modal; await ack/error. */
suspend fun answerModal(modalId: String, optionId: String)   // returns Unit

/** Send modal_cancel{modal_id} for the surfaced modal; await ack/error. */
suspend fun cancelModal(modalId: String)                      // returns Unit
```

Each: build `Envelope(id = requestId.incrementAndGet(), type = TYPE_MODAL_*, ts = Clock.System.now().toString(), payload = MobileJson.encodeToJsonElement(dto))`, then `sendAndAwaitReply(request)` and discard the empty `{}` ack (return `Unit`). Identical control flow to `registerPushToken` — the only new logic is minting `answerToken` (§ 3).

Add the two companion constants in the `TYPE_*` block:
- `const val TYPE_MODAL_ANSWER = "modal_answer"`
- `const val TYPE_MODAL_CANCEL = "modal_cancel"`

### 3. `answer_token` minting — deterministic, stateless (the one real design decision)

**Decision: derive `answer_token` as a stable, collision-free encoding of `(modalId, optionId)` — a pure function, no stored state.**

Why this satisfies both AC#2 properties **by construction**:
- **Stable across a retry of the same logical answer:** the same `(modalId, optionId)` always yields the same token, so a resend of "answer modal X with option A" carries the identical token and the daemon dedups it to a no-op. No remembered state, no cache lifetime to manage.
- **Unique per distinct answer:** distinct `(modalId, optionId)` pairs yield distinct tokens. `modalId` MUST be part of the derivation (not `optionId` alone): two different modals each answered with an option whose id is `"allow"` are distinct answers and must carry distinct tokens (AC#5).

**Collision-safety:** `modalId`/`optionId` are opaque strings that may contain any character (including a chosen separator). Use an **unambiguous** join so `("a:b","c")` and `("a","b:c")` cannot map to the same token — e.g. length-prefix the modal id (`"${modalId.length}:$modalId:$optionId"`) or any equivalently-reversible encoding. The exact format is the developer's choice; the **contract** is: deterministic, stable per `(modalId, optionId)`, distinct across distinct pairs. Implement as a small `private` pure helper next to `answerModal` (no `android.*`, no `Clock`, no random — purity is what gives stability).

**Why not a random token + a remembered cache (rejected alternative):** minting a fresh UUID per call and caching it keyed by `(modalId, optionId)` to recover stability would add mutable shared state (thread-safety on the map), an eviction question (when to drop entries — coupling this slice to the inbound `modal_dismissed` stream), and unbounded growth otherwise. pyrycode#701 explicitly states **secrecy is a non-goal** and only *uniqueness + stability* matter, and the daemon treats `answer_token` as an opaque comparison key — so the random scheme buys nothing the pure derivation lacks while costing state and a lifecycle. Simplicity-first ⇒ the pure derivation.

> The token is intentionally derivable from the other two fields. That is not redundancy to remove: the token's entire information content *is* the answer's identity `(modalId, optionId)`, and the wire requires the field present as the daemon's dedup key. A random token would carry *less* principle (it needs external state to recover the stability that is already implicit in the inputs).

### Why not the interface / facade / Fake

`answerModal`/`cancelModal` are **concrete-only** methods on `RemoteConversationRepository`, exactly like `registerPushToken` — **not** added to the `ConversationRepository` interface, `StableConversationRepository` facade, or `FakeConversationRepository`. Modal answering is a device/control capability, not a conversation-CRUD method, so it does not belong on the interface, and the facade only delegates interface methods (per `post-352` memory: device-concern capabilities do not reach consumers via the facade). #439 reaches these methods by fetching the concrete `RemoteConversationRepository` (the same way the Firebase caller reaches `registerPushToken`) — that wiring is #439's concern, not this slice's. Keeping the surface concrete avoids a Fake/facade cascade and keeps this slice at 2 files.

---

## State + concurrency model

- **Stateless.** No new fields, no projection mutation, no `StateFlow`. The token is a pure function of the call arguments. This is a request/reply control call, not a state holder.
- **Correlation:** reuses `sendAndAwaitReply` (#346) verbatim — one `CompletableDeferred<JsonElement>` registered in the existing `pendingRequests` map under `requestId.incrementAndGet()`, awaited until the correlated `ack`/`error` arrives on the single inbound collector. No second pump subscription, no new coroutine, no `viewModelScope` (repo-layer suspend functions run on the caller's coroutine).
- **`modal_dismissed` is NOT awaited here.** The `ack` confirms the daemon *received and will process* the answer; the modal's eventual *resolution* arrives asynchronously as the inbound `modal_dismissed` event on #437's `modalEvents` flow. These are two distinct signals: this slice owns only send + ack/error correlation. (Decision on the Technical-Notes "await reply vs fire-and-resolve-via-`modal_dismissed`" question: **await the `ack`/`error`**, like every other v2 control message — AC#4 requires reusing the existing correlation infra and surfacing failure deterministically; it does not await `modal_dismissed`.)
- Dispatcher/threading: inherited from `sendAndAwaitReply`; nothing new.

---

## Error handling

| Failure mode | Surfaced as | Owner |
|---|---|---|
| Session not `Open` (`pump.send` returns `false`) | `IllegalStateException` (from `sendAndAwaitReply`'s `check`), thrown synchronously, no hang | this slice |
| Server `error` envelope (incl. the **ungranted-device reject**, pyrycode#702/#703) | `RelayErrorException(code, retryable, message)` propagated to caller | thrown here; **degrade handling is #440** |
| Caller cancellation while awaiting | `CancellationException`; `finally` removes the pending entry | `sendAndAwaitReply` |

This satisfies AC#4 ("a send failure surfaces deterministically rather than silently dropping"): every failure throws a typed exception; nothing is swallowed. The non-granted-device case is just a `RelayErrorException` with the gate's `code` — **this slice does not catch or interpret it**; #440 will catch it and switch the modal to read-only. No new error type, no new mapping. `answerModal`/`cancelModal` return `Unit`; success = "returned without throwing".

---

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.repository.RemoteConversationRepositoryTest"`; bare `test` aggregate task rejects `--tests`). Add to `RemoteConversationRepositoryTest.kt` beside the `registerPushToken` quartet, templated on it. Add `startAnswerModal(repo, modalId, optionId)` / `startCancelModal(repo, modalId)` helpers mirroring `startRegisterPushToken` (launch on `backgroundScope`, return `() -> Result<Unit>`). Reuse `FakeSessionPump`, `ackEnvelope`, `errorEnvelope`, `runCurrent()` — no new harness.

Scenarios (bullet form — write in the project idiom; assert on-wire JSON by decoding `pump.sent.single { it.type == "modal_answer"/"modal_cancel" }.payload`):

- **`answerModal` wire shape (AC#1, #5):** answer `(modalId="mdl-7f3a", optionId="allow")`; assert the sent `modal_answer` payload equals `{"modal_id":"mdl-7f3a","option_id":"allow","answer_token":<minted>}` — exactly three keys, `modal_id`/`option_id` verbatim, `answer_token` present and non-empty.
- **`modal_cancel` wire shape (AC#3):** cancel `(modalId="mdl-7f3a")`; assert payload equals `{"modal_id":"mdl-7f3a"}` — single key.
- **token stable across a resend (AC#2, #5):** call `answerModal("mdl-7f3a","allow")` twice (two launches, each acked); assert the two sent `modal_answer` payloads carry the **same** `answer_token`.
- **token distinct across distinct answers (AC#2, #5):** assert `answerModal("mdl-7f3a","allow")`, `answerModal("mdl-7f3a","deny")`, and `answerModal("mdl-OTHER","allow")` each carry a **different** `answer_token` (proves both `optionId` and `modalId` participate; the third case guards the "different modal, same option id" collision).
- **`answerModal` on `ack` completes (AC#4):** push `ackEnvelope(sent.id)`; result is success, no throw.
- **`answerModal` on server `error` throws `RelayErrorException` (AC#4):** push `errorEnvelope(sent.id, code=<gate-reject code>, retryable=false)`; assert `RelayErrorException` with that `code` propagates (stand-in for the #702 ungranted-device reject; this slice does not degrade — #440 does).
- **`answerModal` when `pump.send` returns false → `IllegalStateException`, no hang (AC#4):** set `pump.sendResult = false`; assert `IllegalStateException`.
- **`cancelModal` ack + error + send-false:** the same three correlation outcomes as `answerModal` (cancel has no token to vary).

No instrumented (`connectedAndroidTest`) coverage — pure data-layer, no Compose, no device API.

---

## Acceptance criteria → design mapping

1. Public method on concrete repo sends `modal_answer{modal_id, option_id, answer_token}` with a freshly minted token → § Design 2 + 3.
2. Token unique per distinct answer, stable across retry → § Design 3 (deterministic derivation from `(modalId, optionId)`) + tests "token stable"/"token distinct".
3. Public method sends `modal_cancel{modal_id}` → § Design 2.
4. Reuse existing send/correlation infra; failure surfaces deterministically → § Design 2 (`sendAndAwaitReply`) + § Error handling (`IllegalStateException`/`RelayErrorException`, never silent).
5. Tests assert on-wire shapes + token stability/distinctness → § Testing.

---

## Scope (size self-check)

**Production source files modified/created** (excluding `*Test.kt`, `*.md`, this spec): **`ModalOutboundPayloads.kt` (new), `RemoteConversationRepository.kt` (modified) = 2.** Well below the ≥5-file gate and the 3-file S limit. **New exported types: 2** (both `internal` DTOs). **New constants: 2.** **New public methods: 2.** **Consumer cascade: 0** — concrete-only, additive; no interface/facade/Fake touch, no signature change to any existing symbol (`codegraph_impact` would show no dependents — purely new surface). **Reject branches: 0** (no state machine; two error paths are inherited from `sendAndAwaitReply`). **Total written LOC** (≈45 DTO file + ≈40 repo methods/constants/helper + ≈140 tests + this spec) ≈ **~225 production+test**. Below the ~400 S line and the ~600 split line. **No red line tripped — solidly S** (arguably XS; kept S given the token-design decision and the test matrix).

---

## Open questions

- **Token format exactness.** The spec pins the *contract* (deterministic, stable, collision-free) and recommends a length-prefixed join; the developer picks the literal encoding. The daemon treats it as opaque, so any conforming encoding is wire-correct.
- **`cancelModal` token.** `modal_cancel` carries no `answer_token` (pyrycode#701 shape) — cancel is not idempotency-keyed; a re-cancel of an already-resolved modal is a stale-`modal_id` reject the daemon handles. No mobile-side dedup needed. (If a follow-up adds a cancel idempotency token server-side, revisit — not today.)
- **Reachability from a live caller.** #439 must fetch the concrete `RemoteConversationRepository` to call these (the facade won't expose them, by design). Flagged for #439; out of scope here.

---

## Security review

**Reviewer:** architect (self-review; `agents/architect/security-review.md` is not synced into this worktree — performing the pass inline using the standard adversarial categories, per the #701 precedent).
**Date:** 2026-06-23
**Verdict:** PASS

Run adversarially against the spec above, assuming it has holes. This slice opens a **new outbound (phone → daemon) control path that injects a permission decision into the supervised `claude`** — a high-consequence action. The adversarial questions: can a malformed/forged/replayed send escalate privilege, leak data, or wedge the daemon; does the client mint anything trust-bearing; is anything sensitive logged.

**Findings:**

- **[Trust boundaries — outbound control surface].** No findings; the design keeps the daemon as sole authority. The payload carries **only `modal_id`/`option_id` (opaque, caller-supplied, echoed verbatim) + a client-minted `answer_token`** — no `conversation_id`, no action verb, no option *text*. A phone asserts only *which* modal and *which* of the daemon's offered options; the daemon resolves `modal_id` against its own outstanding-modal state and `option_id` against its own option list (pyrycode#703/#706, first-answer-wins, stale-id rejected). This slice **does not parse, validate, or trust** `modalId`/`optionId` — correct: validation is the daemon's, and a client-side check here would be security theatre that could diverge from the daemon. Trust boundary intact.
- **[`answer_token` — client-minted value, is it trust-bearing?].** No findings. `answer_token` is an **idempotency key, not a credential** (pyrycode#701 § answer_token sufficiency, verbatim). Its only security-relevant properties are *uniqueness* and *stability* — **secrecy is an explicit non-goal**. Because authorization is `modal_id` validity (#706) + the per-device gate (#702), a guessed/forged/predictable token grants nothing: a fresh token on a valid `modal_id` is just a normal answer (still gated), and a replayed token is a no-op. The deterministic derivation is therefore *safe* precisely because the token is non-authoritative — there is no secret to leak by making it predictable. (Had the token been authorization-bearing, the deterministic scheme would be a finding; it is not, so it is not.)
- **[Replay / idempotency].** No findings — this is the feature, and the shape supports it. The stable token lets the daemon dedup `(modal_id, answer_token)` and collapse a replayed/reordered `modal_answer` to a no-op (the AC#2 stability property). A network-level replay of a captured `modal_answer` frame is (a) defeated at the AEAD/transport layer (#571, encrypted session — out of this slice) and (b) idempotent at the application layer by the token — defence in depth, neither relied on alone.
- **[Output redaction / logs / telemetry].** No findings — and the obligation is named. `modalId`/`optionId` and (by extension) the modal they answer may reference a sensitive command/path (e.g. "Allow `rm -rf build/`"). The DTO KDoc states **never log the payload or these fields**, mirroring `registerPushToken`'s never-log-the-token posture and #437's verbatim-no-log decode discipline. `RelayErrorException` carries the server's `code`/`retryable` (non-sensitive), not the modal body. No new log statement is introduced.
- **[Fail-closed / availability].** No findings. A not-`Open` session fails fast with `IllegalStateException` (no hang — the `registerPushToken` `whenSendReturnsFalse` precedent, asserted by test); a server reject throws `RelayErrorException` and mutates nothing. There is no state to corrupt (stateless slice) and no unbounded allocation (two fixed-shape envelopes per call). The ungranted-device reject (#702) surfaces as a plain `RelayErrorException` and is **left to #440** to degrade — this slice neither swallows nor mis-handles it.
- **[Secrets / crypto / file ops / subprocess / network / DoS].** N/A — no secret/key/credential handled (the token is a non-secret dedup id), no crypto performed here (AEAD sealing is the transport's, #571), no I/O, no path handling, no execution, no new socket, no attacker-controlled unbounded allocation. `data/` stays portable (no `android.*`).
- **[Threat model alignment].** Aligned with `protocol-mobile.md § Security model` and ADR 025. The new outbound control rides the same authenticated v2 session funnel as `register_push_token`/`request_snapshot`; the high-consequence "answer" is doubly fenced daemon-side (unguessable `modal_id` validity #706 + per-device gate #702), with `answer_token` adding application-layer replay-idempotency. No threat is *introduced* by this client surface; the design correctly pushes all authority to the daemon and mints nothing trust-bearing.

**Producer dependencies (already owned upstream, not this slice's work):** unguessable `modal_id` minting + `(modal_id, answer_token)` dedup + stale-id rejection (#703/#706); per-device answer gate (#702). All merged. No wire-shape or client-shape gap.
