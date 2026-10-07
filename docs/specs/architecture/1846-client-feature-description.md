# #1846 — Advertise the app's feature description in hello

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt`: `HelloClientPayload`, `Envelope` and capability constants establish optional-field encoding and token redaction.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt`: `buildHello` and `writeInit` construct and encrypt each connection's hello; re-key sends no hello.
- `app/src/test/java/de/pyryco/mobile/data/network/MobileWireCodecTest.kt`: hello round-trip, default encoding and redaction assertions.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt`: `TestResponder` decrypts real Noise early-data; existing tests cover capabilities, replay and key lifecycle.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md`: `MobileJson.encodeDefaults` requires a property-level omission override for an empty description.
- `docs/knowledge/features/noise-ik-session.md`: reconnect creates a fresh session; preserve synchronized crypto operations and token lifetime.
- `docs/specs/architecture/1119-advertise-multi-agent.md`: nearest advertisement analogue.
- `pyrycode/docs/protocol-mobile.md` from the sibling checkout's `origin/main`: `hello` and Security model are the authoritative wire and threat contracts; the sibling working tree predates `client_features`.
- Sibling `internal/sessions/systemprompt.go` from `origin/main`: `admissibleClientField` and `admitClient` admit descriptions independently with a 512-UTF-8-byte bound.

## Change

Add `HelloClientPayload.clientFeatures: String = ""`, serialized as `client_features`, with `EncodeDefault.Mode.NEVER` so missing decodes as empty and empty encodes without a key despite `MobileJson.encodeDefaults`. Keep nonempty values verbatim with no new model validation. Add an internal app-owned constant containing the issue's exact single-line description and pass it explicitly in `NoiseIkSession.buildHello`. Preserve identity, token redaction, capabilities, replay cursor and re-key behavior. The description is metadata, not a negotiated capability. No new types, consumer migrations, failure branches, dependencies or UI changes. Remote feature-branch scan found no overlapping files. Forecast: approximately 140 written lines including this plan and tests, comparable to #1119 and within every sizing boundary (two acceptance criteria).

## Testing strategy

Write tests first and observe a red run before implementation. `MobileWireCodecTest` pins absent/empty omission, nonempty verbatim round-trip and the app constant's exact text and daemon admission rules (nonblank, at most 512 UTF-8 bytes, no C0/C1/DEL or double quotes). `NoiseIkSessionTest` decrypts two fresh connections' hellos and checks the exact description, identity, token redaction and unchanged capabilities. Existing re-key, replay and capability tests remain in the focused run. No device or real-Claude scenario is needed for outbound metadata with no new operator action; the daemon consumer already has its hermetic handshake-to-spawn proof. Run both affected test classes and the repository stop-task test that constructs a hello, lint, assemble, formatting, then the final full unit/shared suite and `scripts/pre-verify.py --gradle` after merging main.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] `buildHello` sources the report exclusively from an app-owned constant, never an intent, user input or daemon frame. The daemon authenticates and independently admits it; it grants no authorization. Pin its exact bytes and admission constraints in tests.
- [Tokens] `HelloClientPayload.toString` continues to redact the token; serialization still carries it only inside encrypted early-data. Token storage, revocation and the pending-token release in `writeInit` remain intact.
- [Files and storage] The example path is literal explanatory text. No path is opened, persisted or derived from the new field.
- [Android attack surface] No component, intent filter, provider, push payload, pending intent or WebView changes.
- [Cryptography] Use the existing vendored `Noise_IK_25519_ChaChaPoly_BLAKE2s` sealing, static-key authentication and nonce lifecycle. No key derivation or re-key changes.
- [Network and I/O] The 390-byte constant is bounded by the stricter prompt limit. Transport frame limits, TLS, timeouts and supervisor backoff stay in their existing owners.
- [Errors, logs and telemetry] Add no logging of descriptions, decrypted hellos, example paths, tokens or keys. Existing category-only session errors and lifecycle logging remain unchanged; the custom payload `toString` need not expose descriptions.
- [Concurrency] Constant data adds no mutable state, job, suspension or lock. Existing synchronized handshake and background teardown preserve cancellation and key wiping.
- [Threat model] Prompt-structure injection is prevented for this report by its pinned character rules plus daemon admission. Relay MITM, server-id misrouting and replay remain covered by authenticated Noise. Hostile inbound frames use unchanged decoders and transport bounds. Rooted-device token theft, static-key compromise, UI capture and relay denial of service gain no new surface; existing Keystore, revocation, rotation and supervisor behavior remain responsible.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-07
