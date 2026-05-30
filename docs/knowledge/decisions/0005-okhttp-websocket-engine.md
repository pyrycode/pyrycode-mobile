# ADR 0005 — OkHttp 4.12.0 as the Phase 4 WebSocket engine (over Ktor-client)

**Status:** Accepted (2026-05-30, with #272).

## Context

[#272](../codebase/272.md) provisions the Phase 4 networking + crypto dependency substrate. Phase 4 is the first time the app talks to a real backend — today it is 100% fake data (`FakeConversationRepository`, `FakeConnectionStateSource`) with zero networking. The mobile↔daemon transport is a WebSocket carrying the `Noise_IK`-encrypted Mobile Protocol v2, so the substrate needs a WS client engine.

[`CLAUDE.md`](../../../CLAUDE.md) names the planned stack as "Koin (DI), **Ktor** (networking, Phase 4+), DataStore (settings)" — so Ktor-client was the previously-documented intent. The ticket body left the engine open ("Architect's call — the AC stays engine-agnostic"). The 2026-05-29 Noise-client spike, however, used **OkHttp 4.12.0** for the WS transport and proved the full Kotlin↔Go `Noise_IK` round-trip against both a local relay and the live `wss://pyrycode-relay.pyryco.de`; the spike doc calls OkHttp "the right Android WS engine anyway."

## Decision

Provision **OkHttp 4.12.0** (`com.squareup.okhttp3:okhttp`) as the Phase 4 WebSocket engine, resolving from the existing `mavenCentral()` — **no new repository**. Catalog wire-up is the standard three lines (`[versions] okhttp = "4.12.0"`, a `[libraries]` entry, one `implementation(libs.okhttp)`).

This **supersedes the "Ktor (networking, Phase 4+)" line in `CLAUDE.md`** for the WebSocket transport specifically: the Phase 4 WS engine is OkHttp, not Ktor.

OkHttp is intentionally **unreferenced by production code** in #272. `assembleDebug` succeeding — it lands on the compile classpath and resolves — is the verification; the WS-transport ticket writes the first reference.

## Rationale

- **Proven in the spike against the live relay.** The end-to-end `Noise_IK` round-trip that de-risked all of Phase 4 ran on OkHttp 4.12.0. Choosing the engine the proof was built on removes a variable from the root of the Phase 4 blocking spine rather than introducing a fresh, unproven WS stack at the most load-bearing point.
- **"The right Android WS engine anyway"** (spike doc). OkHttp is the de-facto Android HTTP/WS client; its `WebSocket` + `WebSocketListener` API is mature and well-understood.
- **OkHttp 4.x is the stable Android line.** OkHttp 5.x exists, but staying on the proven 4.12.0 keeps the substrate deterministic; the WS-transport ticket can revisit 5.x if it wants — the *engine* decision (OkHttp, not Ktor) is settled, only the 4.x→5.x version question is deferred.
- **No build-time integration risk.** A well-behaved Maven Central artifact with no compiler plugin and no build-time transform — unlike kotlinx-serialization (whose plugin-application seam under AGP-9 needed a probe), OkHttp needs no smoke test; classpath resolution via `assembleDebug` is sufficient.

## Alternatives considered

- **Ktor-client.** The previously-documented intent (`CLAUDE.md`). A valid option, but it was *not* what the spike proved, and adopting it would have re-introduced an unproven WS stack at the root of the Phase 4 spine. Rejected in favour of the proven engine. If a later phase wants multiplatform networking (the `data/` portability / Compose-Multiplatform walk-back trigger in `CLAUDE.md`), Ktor-client is the natural re-evaluation candidate — but that is a deliberate future decision, not a default to drift back into.
- **OkHttp 5.x.** Newer, but staying on the spike-proven 4.12.0 removes a variable from the substrate. Deferred to the WS-transport ticket.

## Consequences

- **`CLAUDE.md`'s "Ktor (networking, Phase 4+)" is now historical intent, not the chosen path.** A future contributor reading that line should treat this ADR as the live decision for the WS transport: the Phase 4 engine is OkHttp 4.12.0. (Ktor may still be reconsidered specifically for a multiplatform `data/` layer; see Alternatives.)
- **The WS-transport ticket writes the first production reference** and owns the R8 keep-rule validation that comes with it (a release build tree-shakes OkHttp away until something references it — nothing to keep in #272, so `proguard-rules.pro` is untouched here).
- **4.x → 5.x revisit is open** at the WS-transport ticket's discretion; the engine choice itself is not.

## Related

- Ticket notes: [`../codebase/272.md`](../codebase/272.md)
- Spec: `docs/specs/architecture/272-phase4-networking-crypto-dependencies.md` (§ "WebSocket client — OkHttp 4.12.0")
- Sibling decision from the same ticket: [ADR 0004 — vendor `noise-java`](./0004-vendor-noise-java-crypto.md)
- Project stack line this refines: [`CLAUDE.md`](../../../CLAUDE.md) → "Koin (DI), Ktor (networking, Phase 4+)"
- Spike findings: vault doc *"Phase 4 — Noise Client Spike Findings"* (`second-brain`, `2026-05-02-pyrycode-mobile/`)
- Consumed by: the Phase 4 WS-transport ticket (first production WS reference).
