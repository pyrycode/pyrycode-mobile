# #1119 — Advertise `multi_agent` in the handshake

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt` → `CAPABILITY_INTERACTIVE`, `HelloClientPayload.capabilities` — the constant sits beside the existing one and the default list gains it.
- `app/src/test/java/de/pyryco/mobile/data/network/MobileWireCodecTest.kt` → `hello_advertisesInteractiveCapabilityOnEncode` — the only assertion that pins the encoded list; it changes.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt` → `handshake_advertisesInteractiveCapabilityInHello`, `readResp_helloAckEchoingInteractiveSurfacesGrantedSet` — the handshake round trip that carries the hello and surfaces the granted set.
- `../pyrycode/docs/protocol-mobile.md` § "Capability negotiation (v2)" `multi_agent` row, § `conversations`, § `model_list`, § `capabilities` (multi_agent, #2646) — the wire contract; every `multi_agent`-gated field is `omitempty` and absent for a conn that did not negotiate it.
- Blockers #1108 (`ConversationsPayload` `agent`), #1109, #1110 (`ModelListPayloads` `agent`/`family`), #1111 (`SessionSettingsPayloads` `capabilities`) — all closed; each decodes its field as optional with a Claude/absent default, so the phone never checks the negotiated `multi_agent` membership. Nothing in `app/src/main` reads a `CAPABILITY_MULTI_AGENT`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Channel List Screen, unchanged: Codex channels arrive as ordinary `conversations` rows and render through the existing row layout. No visual work in this ticket.

## Context

The daemon withholds every Codex conversation and every frame about one from a conn that did not advertise `multi_agent` (pyrycode#2643, #2644). The decoding side (#1108–#1111) has landed; this ticket flips the advertisement.

## Change

Add `internal const val CAPABILITY_MULTI_AGENT = "multi_agent"` beside `CAPABILITY_INTERACTIVE`, with a KDoc naming the protocol row, and change the `HelloClientPayload.capabilities` default to `listOf(CAPABILITY_INTERACTIVE, CAPABILITY_MULTI_AGENT)`, updating its KDoc. Nothing else moves: the phone's consumers gate on the fields the daemon sends, not on the granted set, so a daemon that grants only `interactive` sends no `agent`, `family` or `capabilities` keys and every decoder takes its existing absent-key path. No consumer gains a `CAPABILITY_MULTI_AGENT in negotiatedCapabilities()` check, because none needs one.

## Testing strategy

- `MobileWireCodecTest`: the hello test asserts the default list is `[interactive, multi_agent]` in that order, the encoded JSON carries `"capabilities":["interactive","multi_agent"]`, and `toString` surfaces both (AC 1).
- `NoiseIkSessionTest`: the handshake hello decoded by the test responder carries `multi_agent` after `interactive` (AC 1, end to end through `NoiseIkSession.writeInit`); a `hello_ack` granting only `interactive` surfaces exactly `{interactive}` as the negotiated set (AC 2 at the handshake). AC 2 past the handshake is already proven by the blockers' absent-key tests (`ModelListPayloadsTest` untagged frame, `SessionSettingsPayloadsTest` no-`capabilities` reply, #1108's absent `agent` reads Claude).
- No rung-3 scenario: this changes what the daemon includes, not an operator-facing flow the phone drives; the Codex-row rendering is the blockers' surface.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: the connection feature overview's capability list could note that the phone now advertises `multi_agent`.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — advertising the string changes which daemon frames arrive, and every newly arriving field was put behind a decoding boundary by its blocker: `agent` read through `conversationAgentOf` (unknown values fall back, never trusted), `agent`/`family` on `ModelListPayloads`, `capabilities` on `SessionSettingsPayloads`. Codex conversations arrive as ordinary `conversations` rows whose text fields already go through the existing length-bounded text render paths. The capability is advisory per the protocol ("support is not permission"); the daemon re-checks every write.
- [Tokens] No findings — `HelloClientPayload.toString` keeps redacting `token`; the capability list is non-secret and already surfaced.
- [File / storage] Not applicable — no storage touched.
- [Android attack surface] Not applicable — no intents, deep links, providers or WebViews.
- [Crypto] No findings — the hello still rides `noise_init` early data through the vendored `noise-java` in `NoiseIkSession`; only its JSON content grows by one string.
- [Network & I/O] No findings — the daemon may now send more frames (Codex conversations' pushes, merged `model_list`). The existing relay frame-size cap and supervisor backoff are unchanged; `model_list` merging adds no combined cap on the daemon side, but each row is bounded by the existing decoder.
- [Logs] No findings — no new log lines; existing hello logging surfaces capabilities, which are non-secret.
- [Concurrency] Not applicable — a constant default; no new jobs or shared state.
- [Threat model] OUT OF SCOPE — a hostile daemon could now claim any conversation is Codex; that only relabels rows and narrows menus client-side, both handled by #1108/#1110/#1111's fail-closed-to-known-values decoding.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25
