# ADR 0004 — Vendor `noise-java` (crypto) in-tree rather than pull a JitPack artifact

**Status:** Accepted (2026-05-30, with #272).

## Context

[#272](../codebase/272.md) provisions the Phase 4 networking + crypto dependency substrate — the root of the Phase 4 blocking spine. One of the three dependencies is the cryptographic library that, in [#274](https://github.com/pyrycode/pyrycode-mobile/issues/274) and the WS-transport ticket, will perform the `Noise_IK` handshake protecting **all** mobile↔daemon traffic. The library is `noise-java` (`com.southernstorm.noise`, [rweather/noise-java](https://github.com/rweather/noise-java)), pinned to commit `49377b6`.

`noise-java` was settled on during the 2026-05-29 Noise-client spike (vault doc *"Phase 4 — Noise Client Spike Findings"*): it is pure Java, ships its own ChaCha20-Poly1305 + BLAKE2s + Curve25519 (no JCE provider, no BouncyCastle), and supports `Noise_IK` with payload-bearing handshake messages. The spike vendored these exact files and proved a Kotlin↔Go `Noise_IK` round-trip end-to-end against both a local relay and the live `wss://pyrycode-relay.pyryco.de`. (The alternative library `jchambers/java-noise` was rejected in the spike because it needs a JCE `ChaCha20-Poly1305` provider — JDK/Android-version coupling.)

The remaining question for #272 was **how to bring `noise-java` onto the classpath**, and because the ticket carries the `security-sensitive` label, the provisioning method is itself a supply-chain decision. The project's repository policy is the binding constraint: `settings.gradle.kts` is locked to `google()` + `mavenCentral()` with `FAIL_ON_PROJECT_REPOS`, so a module-level repo declaration will not work — any new repository must be declared in `settings.gradle.kts`. `noise-java` is **not on Maven Central**.

## Decision

**Vendor the source in-tree.** Copy the 29 `.java` files from the proven spike copy into `app/src/main/java/com/southernstorm/noise/`, **verbatim** (single bulk copy, no hand-edits), package unchanged (`com.southernstorm.noise.*`). The MIT per-file headers (Southern Storm Software, 2016) stay intact and satisfy attribution. There is **no `implementation(...)` line** for `noise-java` — it is in-tree source, not an artifact dependency.

A provenance record ships alongside: [`app/src/main/java/com/southernstorm/noise/VENDOR.txt`](../../../app/src/main/java/com/southernstorm/noise/VENDOR.txt) — plain `.txt` (deliberately not `.md`, to stay out of the spotless `misc` markdown glob, and invisible to the Kotlin/Java compiler and lint) recording: upstream `github.com/rweather/noise-java`, vendored at commit `49377b6`, license MIT, "verbatim — do not edit; re-vendor to update."

`settings.gradle.kts` is **left untouched** — it stays `google()` + `mavenCentral()` with `FAIL_ON_PROJECT_REPOS` intact.

## Rationale

The asset at stake is the **integrity of the cryptographic library**. With no runtime code paths in this ticket and no user-controlled input, the review centred entirely on supply-chain trust boundaries, and vendoring wins on every axis that matters for a small, dependency-free crypto lib:

- **The pin *is* the content.** Vendored bytes are immutable and reviewable in the PR diff — a human (and the code-review agent) reads the exact bytes that ship. Code review confirmed the 29 files are **byte-identical** to the spike copy proven in the 2026-05-29 round-trip (`diff -rq` clean; only addition is `VENDOR.txt`). A tampered library could not have completed that Kotlin↔Go handshake against the live relay — the proof and the pin reinforce each other.
- **No third-party builder in the trust path.** JitPack builds the JAR on demand; vendoring removes that entirely.
- **No repository widening.** `dependencyResolutionManagement` stays `google()` + `mavenCentral()`. The JitPack path would have to *widen* it with a new `maven {}` repo (and, to be safe, a scoped `content { includeGroupByRegex("com\\.github\\.rweather.*") }` so not *all* groups could resolve from JitPack).
- **No CI-time external fetch.** Zero build-time dependency on JitPack's availability — a JitPack outage or cache expiry can't fail the `check` gate.
- **Pure Java, no transitive deps, no Android APIs** — compiles into the `main` source set whether or not anything references it yet, which is what makes the crypto piece of the build-green AC verifiable without any production code.

The cost — re-vendoring on update rather than bumping a `version.ref` — is *acceptable and arguably desirable* for a crypto library: you **want** it pinned and re-reviewed on every change, not silently floated.

## Alternatives considered

- **JitPack artifact (`com.github.rweather:noise-java:49377b6`).** Rejected (documented as *conditional*, not adopted). Would require a scoped `maven { url = "https://jitpack.io" }` block in `settings.gradle.kts`, a catalog `noise-java` library entry, and an `implementation` line. JitPack builds an **unsigned** JAR on demand from the commit; the artifact is not on Maven Central, and the project has **no `verification-metadata.xml`** to checksum-pin it. The trust boundary moves to JitPack's build infrastructure. If the operator had preferred this path, the security verdict would have shifted from PASS to *conditional* (requires the scoped `content {}` block and ideally `verification-metadata.xml`, which the project does not yet have).
- **`jchambers/java-noise`.** Rejected at the spike stage — needs a JCE `ChaCha20-Poly1305` provider, coupling the build to JDK/Android crypto-provider versions. `noise-java` ships its own primitives and avoids this.
- **Hand-editing the vendored source** (e.g. to silence a lint finding). Explicitly forbidden — the whole point of vendoring is that the bytes match upstream. The one lint finding that fired (`SuspiciousIndentation` on upstream's brace-less multi-line `if` guards) was scoped out per-issue-id, per-path via `app/lint.xml`; the source was not touched. See [`272.md`](../codebase/272.md).

## Consequences

- **Updating `noise-java` is a re-vendor, not a version bump.** Re-copy the whole tree from the new upstream commit and bump the `Commit:` line in `VENDOR.txt`. Never hand-edit the files. This is the documented update procedure and is the intended trade-off (pinned + re-reviewed crypto).
- **The vendored package needs a lint carve-out, not a source edit.** `app/lint.xml` scopes `SuspiciousIndentation` out for `src/main/java/com/southernstorm/noise/**` only (`abortOnError` stays on, every other file still linted). If a future re-vendor trips a *new* lint error id on that package, add another per-id `<ignore path="…/southernstorm/noise/**" />` — do **not** lower `abortOnError` and do **not** edit upstream bytes.
- **R8 keep rules are deferred, by design.** No production code references `noise-java` yet, so a release build would tree-shake it away — nothing to keep. R8 validation belongs to the first ticket that adds a production reference (#274 / WS-transport). Recorded for that ticket: `Noise.java` has a single reflective call — `Class.forName("javax.crypto.AEADBadTagException")` on the bad-tag decrypt path — but `AEADBadTagException` is a framework class (in `android.jar` since API 19), not strippable, so it needs **no** keep rule; the call is also not exercised by `HandshakeState` construction, so it is irrelevant to #272's smoke test.
- **Cryptographic-correctness invariants are explicitly *not* covered here.** #272 only proves the suite *loads* (`HandshakeState` constructs). The handshake's security-critical invariants — empty prologue, empty/null AD on every AEAD op, correct `sender`/`receiver` split direction (crossing them = silent MAC failure on the first transport message), standard-padded base64, in-encrypted-handshake token — are documented in the spike doc's "Proven wire contract" and are the responsibility of #274 / the transport ticket, where they become live code. The smoke test deliberately does not exercise them.
- **Device-key custody is #274's concern** (Android Keystore, alias `pyrycode.device_static.<server-id>`). No keys are generated, stored, transmitted, or logged in #272.

## Related

- Ticket notes: [`../codebase/272.md`](../codebase/272.md)
- Spec: `docs/specs/architecture/272-phase4-networking-crypto-dependencies.md` (§ "Crypto — `noise-java`, vendored" and § "Security review")
- Sibling decision from the same ticket: [ADR 0005 — OkHttp WebSocket engine](./0005-okhttp-websocket-engine.md)
- Upstream: [github.com/rweather/noise-java](https://github.com/rweather/noise-java) @ `49377b6` (MIT)
- Spike findings: vault doc *"Phase 4 — Noise Client Spike Findings"* (`second-brain`, `2026-05-02-pyrycode-mobile/phase-4-noise-client-spike-findings.md`) — § "Library decision" and § "noise-java IK initiator call sequence"
- Provisioning posture differs from the other library ADRs ([0001](./0001-kotlinx-datetime-for-data-layer.md)/[0002](./0002-markdown-renderer-library.md)/[0003](./0003-syntax-highlighter-library.md)): those are Maven Central artifacts with a three-line catalog wire-up; this is in-tree source with zero `implementation` line — the difference is the supply-chain posture a `security-sensitive` crypto lib warrants.
- Consumed by: #274 (device keypair / Noise session).
