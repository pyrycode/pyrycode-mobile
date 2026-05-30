# Architecture: Phase 4 networking + crypto dependency substrate (#272)

## Context

Phase 4 (real backend) kickoff. The app is 100% fake data today (`FakeConversationRepository`, `FakeConnectionStateSource`) with zero networking. The pyrycode server side of Mobile Protocol v2 is shipped + e2e-tested, and a Kotlin↔Go `Noise_IK` round-trip was proven end-to-end (spike, 2026-05-29) against both a local relay and the live `wss://pyrycode-relay.pyryco.de`.

This ticket provisions the three dependencies the rest of the Phase 4 blocking spine consumes — it is the **root** of that spine. It is config-only plus one smoke test; there is **no production networking/crypto code** here. The follow-ups consume it:

- **#273 (wire models)** needs the serialization plugin + `kotlinx-serialization-json` runtime so it can use `@Serializable` + `Json`.
- **#274 (device keypair / Noise session)** needs `noise-java` on the classpath.

Sizing: **XS** (PO sized S; overriding down). One `.kts` production file modified (`app/build.gradle.kts`), catalog edits (`.toml`), one test file, and a verbatim vendored copy of an upstream library. No production source authored. No edit fan-out (no symbol renames, no consumer call sites). No file-overlap with the five in-flight feature branches (`166/203/205/228/236` — checked, none touch `libs.versions.toml`, `app/build.gradle.kts`, `settings.gradle.kts`, or `proguard-rules.pro`).

## Design source

N/A — dependency / build-infrastructure change; no UI, no Figma.

## Files to read first

- `gradle/libs.versions.toml:1-21` — the `[versions]` table. Add `okhttp`, `kotlinxSerialization` here.
- `gradle/libs.versions.toml:23-52` — the `[libraries]` table; match the existing `{ group, name, version.ref }` shape (see `kotlinx-coroutines-core:46` as the closest precedent for an `org.jetbrains.kotlinx` entry).
- `gradle/libs.versions.toml:54-58` — the `[plugins]` table. Note `kotlin-compose` uses `version.ref = "kotlin"`; the serialization plugin alias follows the identical shape.
- `app/build.gradle.kts:7-10` — the module `plugins {}` block. **There is no `kotlin-android` alias** — AGP 9.2.1 ships built-in Kotlin and the standalone `kotlin.compose` compiler plugin is applied on top. The serialization compiler plugin applies the same way (`alias(libs.plugins.kotlin.serialization)`); the compose plugin is your working precedent that a KGP-versioned compiler plugin slots into this block.
- `app/build.gradle.kts:78-109` — the `dependencies {}` block; add the two new `implementation(...)` lines here in the existing alphabetical-ish grouping.
- `settings.gradle.kts:17-23` — `dependencyResolutionManagement` is locked to `google()` + `mavenCentral()` with `FAIL_ON_PROJECT_REPOS`. **The vendoring path leaves this file untouched** (see § noise-java). OkHttp + kotlinx-serialization both resolve from the existing `mavenCentral()`.
- `app/proguard-rules.pro` (whole file, ~30 lines) — the R8-rule policy ("only rules from official docs, on the pinned version; empty sections mean consumer-rules suffice"). **This file stays untouched this ticket** — see § R8 / shrinking.
- `app/build.gradle.kts:73-75` — the `lint { abortOnError = true }` block. Relevant to the vendored-Java lint question (§ Lint).
- `~/Workspace/Projects/noise-spike/build.gradle.kts` + `~/Workspace/Projects/noise-spike/src/main/kotlin/Main.kt` — the proven reference client. `Main.kt:73` is the exact `HandshakeState` construction the smoke test mirrors; `Main.kt:25` is the protocol-name constant.
- `~/Workspace/Projects/noise-spike/src/main/java/com/southernstorm/noise/` — the 29 vendored `.java` files to copy verbatim (the vendoring source). Each carries an MIT header.
- Vault doc **"Phase 4 — Noise Client Spike Findings"** (`second-brain`, `2026-05-02-pyrycode-mobile/phase-4-noise-client-spike-findings.md`) — § "Library decision" and § "noise-java IK initiator call sequence" are the load-bearing references; the suite string and the "pure Java, no JCE/BouncyCastle" property come from there.

## Design

Three independent provisions verified by one build (`assembleDebug`) + one gate (`check`, which here runs unit tests + lint + `spotlessCheck`) + one runtime smoke test. Each provision is described as the contract to satisfy, not as a paste-in diff.

### 1. WebSocket client — OkHttp 4.12.0

The spike used OkHttp 4.12.0 and the spike doc calls it "the right Android WS engine anyway." Recommend OkHttp 4.12.0 (proven; OkHttp 4.x is the stable Android line). OkHttp 5.x exists but staying on the proven version removes a variable from the Phase-4 root; the WS-transport ticket can revisit if it wants 5.x.

- `[versions]`: `okhttp = "4.12.0"`.
- `[libraries]`: `okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }`.
- `app/build.gradle.kts` deps: `implementation(libs.okhttp)`.
- Resolves from the existing `mavenCentral()` — **no new repository**.

It is intentionally **unreferenced by production code** in this ticket. `assembleDebug` succeeding (it lands on the compile classpath and resolves) is the verification; the WS-transport ticket writes the first reference.

### 2. JSON serialization — kotlinx-serialization (plugin + runtime)

Project style is kotlinx-serialization. This needs **both** the compiler plugin (so `@Serializable` is processed) and the runtime artifact.

- `[versions]`: `kotlinxSerialization = "1.8.1"`. This is the runtime version, **independent** of the plugin version. Use the latest stable `kotlinx-serialization-json` (≥ 1.8.1) that targets Kotlin 2.2.x; `1.8.1` is known-good. The compiler will flag a runtime/compiler skew immediately at `check` if the pinned runtime is too old for the 2.2.10 plugin — bump per the error if so.
- `[plugins]`: `kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }` — version tracks Kotlin (2.2.10), identical to how `kotlin-compose` is declared.
- `[libraries]`: `kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }`.
- `app/build.gradle.kts` plugins block: add `alias(libs.plugins.kotlin.serialization)` alongside `alias(libs.plugins.kotlin.compose)`.
- `app/build.gradle.kts` deps: `implementation(libs.kotlinx.serialization.json)`.

**The one genuine integration unknown is plugin application under AGP-9 built-in Kotlin.** There is no `kotlin-android` plugin in this project — AGP 9.2.1's built-in Kotlin compiles the sources, and `kotlin.compose` is a standalone KGP compiler plugin layered on top. The serialization plugin is the same kind of artifact and should apply identically, but "on the classpath" ≠ "actually transforming `@Serializable`". The smoke test (§ Testing) includes a probe that fails the build now if the plugin is not actually wired, rather than letting that surface downstream in #273.

### 3. Crypto — `noise-java`, **vendored** (recommended)

`noise-java` (`com.southernstorm.noise`, rweather/noise-java, commit `49377b6`) is pure Java, ships its own ChaCha20-Poly1305 + BLAKE2s + Curve25519 (no JCE provider, no BouncyCastle), and supports `Noise_IK` with payload-bearing handshake messages. The provisioning method is a supply-chain decision (the `security-sensitive` label); the two proven paths and the recommendation:

| | **(a) JitPack artifact** | **(b) Vendor source — RECOMMENDED** |
|---|---|---|
| Diff | `maven { url=... }` in `settings.gradle.kts` + catalog + `implementation` | copy 29 `.java` files into `app/src/main/java/com/southernstorm/noise/` + `implementation`-free |
| Repository surface | **Widens** `dependencyResolutionManagement` (new repo). Must be scoped (`content { includeGroupByRegex("com\\.github\\.rweather.*") }`) or all groups can resolve from JitPack | **Unchanged** — stays `google()` + `mavenCentral()`, `FAIL_ON_PROJECT_REPOS` intact |
| Trust path | JitPack's build infra builds the JAR on demand from the commit; artifact is **unsigned**, not on Maven Central; project has **no `verification-metadata.xml`** to checksum-pin it | The exact bytes are committed and **reviewable in the PR diff**; the pin *is* the content (immutable) |
| CI reliability | First resolution triggers an on-demand JitPack build; outage / cache-expiry fails `check` | Zero external build-time dependency |
| Maintenance | `version.ref` bump | re-vendor on update (acceptable — you *want* a crypto lib pinned + reviewed) |

**Recommendation: vendor (b).** For a small, dependency-free crypto library on a `security-sensitive` ticket, vendoring is the more defensible posture: auditable bytes in-tree, strongest possible pin, no third-party builder in the trust path, no repository widening, no CI-time external fetch. The spike already vendored these exact files and the round-trip passed. The § Security review below formally walks this trust boundary.

Vendoring contract:

- Copy the 29 files from `~/Workspace/Projects/noise-spike/src/main/java/com/southernstorm/noise/` to `app/src/main/java/com/southernstorm/noise/`, **verbatim** (single bulk copy — do not hand-edit upstream source). Package stays `com.southernstorm.noise.*`. Pure Java, no Android APIs, no transitive deps; it compiles into the `main` source set whether or not anything references it yet, which is what satisfies AC #4 for the crypto piece.
- **License + provenance.** Each file already carries the MIT header (Southern Storm Software, 2016) — that satisfies attribution. Add one provenance record so the pin is documented in-tree: `app/src/main/java/com/southernstorm/noise/VENDOR.txt` (plain text, **not** `.md` — keeps it out of the spotless `misc` md glob) stating: upstream `github.com/rweather/noise-java`, vendored at commit `49377b6`, license MIT, "verbatim — do not edit; re-vendor to update." A `.txt`/no-extension file in a java source dir is invisible to the Kotlin/Java compiler, to spotless, and to lint.
- **No `implementation(...)` line** for noise-java — it is in-tree source, not a dependency.

### What is NOT in this ticket

- No production networking/crypto/serialization code. The first references land in #273/#274/WS-transport.
- No `settings.gradle.kts` change (vendoring path). If the operator overrides to JitPack instead, that file gains the scoped `maven {}` block — but the recommendation is vendoring.
- No `proguard-rules.pro` change (§ R8).
- No instrumented test (§ Testing).

## State + concurrency model

N/A — no runtime state, no flows, no coroutines. Build/dependency configuration plus JVM unit tests.

## Error handling

Failure modes are **build-time**, surfaced by the gate; the developer iterates against them:

- **Serialization runtime/compiler skew** — `check` fails with a kotlinx-serialization version-incompatibility message. Fix: bump `kotlinxSerialization` to the release matching the Kotlin 2.2.10 plugin (§ Design 2).
- **Serialization plugin not actually applied** (the AGP-9 built-in-Kotlin seam) — caught by the `serializationPluginIsWired()` probe failing to compile / `Json` throwing at runtime. Fix: confirm `alias(libs.plugins.kotlin.serialization)` is in the *module* `plugins {}` block (not the root build script).
- **`noise-java` suite fails to load** — the smoke test throws `NoSuchAlgorithmException` from the `HandshakeState` constructor if any primitive class (ChaCha20-Poly1305 / BLAKE2s / Curve25519) is missing or the protocol-name parser rejects the suite. This is exactly the runtime signal AC #5 wants.
- **Vendored Java trips Android Lint** (§ Lint) — `lint`'s `abortOnError = true` fails `check` only on **Error/Fatal** severities. noise-java references no `android.*` APIs (so `NewApi` cannot fire) and is mature code, so error-severity hits are unlikely; warnings do not fail the gate. If an error *does* fire on the vendored package, scope it out (§ Lint) — **never** edit upstream source.

### Lint (vendored Java)

Android Lint scans `src/main/java/**/*.java`, so the vendored package is in scope. Expected outcome: **no error-severity findings** (pure Java, no Android APIs, errors-only gate). If `check` does report a lint *error* on `com/southernstorm/noise/**`:

1. Read the exact issue id(s) from the lint report.
2. Add an `app/lint.xml` with a per-id `<issue id="<Id>"><ignore path="src/main/java/com/southernstorm/noise/**" /></issue>` for each fired id (lint.xml ignores are per-issue-id — there is no wildcard id, so scope by the ids that actually fired).
3. Fallback if an id cannot be path-scoped: `android { lint { disable += setOf("<Id>") } }` in `app/build.gradle.kts`.

Do not lower `abortOnError` and do not modify the vendored source — the whole point of vendoring is that the bytes match upstream.

### R8 / shrinking

**No `proguard-rules.pro` change this ticket.** Rationale: (1) `assembleDebug` and `./gradlew check` (lint + unit tests) do **not** run R8; (2) no production code references OkHttp / kotlinx-serialization / noise-java yet, so a release build would tree-shake all three away — there is nothing to keep. R8 keep-rule validation belongs to the first ticket that adds a production reference (e.g. #274's device keypair / Noise session, or the WS-transport ticket), under the existing `proguard-rules.pro` policy. One note for that future ticket, recorded here so it isn't lost: `noise-java`'s `Noise.java` has a single reflective call — `Class.forName("javax.crypto.AEADBadTagException")` on the bad-tag decrypt path — but `AEADBadTagException` is a framework class (android.jar, since API 19), not strippable, so it needs no keep rule; the call is also not exercised by `HandshakeState` construction, so it is irrelevant to this ticket's smoke test.

## Testing strategy

One JVM unit-test file under `src/test` (runs inside `./gradlew check`; no device). Place it at `app/src/test/java/de/pyryco/mobile/data/network/NoiseSuiteSmokeTest.kt` — seeds the Phase-4 `data/network/` namespace per the CLAUDE.md layout without creating any production code there. JUnit4 (`libs.junit`, already a `testImplementation`). Write the tests in the project's test idiom; scenarios, not paste-in bodies:

- **`noiseIkSuiteLoads()` (mandatory — AC #5).** Construct `HandshakeState("Noise_IK_25519_ChaChaPoly_BLAKE2s", HandshakeState.INITIATOR)`; assert construction returns non-null and throws nothing; `destroy()` it (it is `Destroyable`) in a finally to avoid leaking key material across tests. A missing primitive or an unparseable suite string surfaces as `NoSuchAlgorithmException` → test fails. This is the real RUN coverage of the ChaCha20-Poly1305 + BLAKE2s + Curve25519 suite the AC asks for. Mirror `noise-spike/src/main/kotlin/Main.kt:73`.
- **`serializationPluginIsWired()` (recommended — de-risks the AGP-9 plugin seam for #273).** Declare a tiny `@Serializable private data class Probe(val x: Int)` in the test file and assert a `Json.encodeToString(Probe(1))` / `Json.decodeFromString<Probe>(...)` round-trip. This fails to compile if the serialization compiler plugin is on the classpath but not actually applied — the one integration unknown in this ticket — converting a silent #273 surprise into a hard signal now. Cheap (~6 lines) and evidence-based (the AGP-9 built-in-Kotlin + serialization-plugin combination is the unverified seam, not a hypothetical).

Not in scope:

- **No OkHttp probe.** OkHttp is a well-behaved Maven Central artifact with no compiler plugin and no build-time transform; there is no integration uncertainty to cover. `assembleDebug` resolving it is sufficient.
- **No instrumented (`androidText`/`androidTest`) test.** It would not run in the automated gate (no device), and the on-device/post-R8 class-loading question it answers is moot until production code references noise-java (a release build strips it otherwise). Defer that coverage to #274 / WS-transport, where it is meaningful.

What code-review checks:

- Catalog has `okhttp` + `kotlinxSerialization` versions, the two library entries, and the `kotlin-serialization` plugin alias, all matching the existing table shapes.
- `app/build.gradle.kts` applies `alias(libs.plugins.kotlin.serialization)` and declares both `implementation` lines.
- `settings.gradle.kts` and `proguard-rules.pro` are **unchanged** (vendoring path).
- Vendored `com/southernstorm/noise/**` is byte-identical to upstream `49377b6` (spot-check a couple of files against the spike copy) and carries the `VENDOR.txt` provenance record; MIT headers intact.
- `./gradlew assembleDebug` and `./gradlew check` both green in the PR; smoke test present and passing.

## Open questions

- **Exact `kotlinx-serialization-json` pin for Kotlin 2.2.10.** `1.8.1` is the known-good floor; if a newer 1.x is the officially-aligned runtime for 2.2.x, use it. The `check` gate is the deterministic oracle — a skew fails immediately, so this resolves itself within one build iteration. No external lookup required before starting.
- **JitPack vs vendor, if the operator disagrees.** The spec recommends vendoring and the § Security review concurs. If the operator prefers the JitPack artifact, the only deltas are: scoped `maven {}` in `settings.gradle.kts`, a catalog `noise-java` library entry, and an `implementation` line — and the § Security review's verdict shifts to *conditional* (requires the scoped `content {}` block and ideally `verification-metadata.xml`, which the project does not yet have).

## Security review

This ticket carries the `security-sensitive` label; the pass is mandatory and is performed on this spec before commit. The asset at stake is the **integrity of the cryptographic library** that will, in #274+, perform the `Noise_IK` handshake protecting all mobile↔daemon traffic. There is no user-controlled input handled in this ticket (no runtime code paths), so the review centers on **supply-chain trust boundaries**.

**Trust boundaries.**
- *Crypto library provenance (primary).* The recommended path (vendoring) places the exact `noise-java` bytes in-tree, pinned to commit `49377b6`, reviewable in the PR diff. The trust boundary is the PR review itself — a human/code-review reads the bytes that ship. No third-party build infrastructure (JitPack), no unsigned remote artifact, and no widening of `dependencyResolutionManagement` (which stays `google()` + `mavenCentral()` under `FAIL_ON_PROJECT_REPOS`). This is the strongest available posture for a small, dependency-free crypto lib and is the explicit reason vendoring is recommended over JitPack. **Finding: PASS** — enforced by `settings.gradle.kts:17-23` remaining unchanged and the verbatim-copy + `VENDOR.txt` provenance contract.
- *The rejected alternative (JitPack)* would move the trust boundary to JitPack's on-demand build of an unsigned artifact, with no checksum pinning (the repo has no `verification-metadata.xml`). Documented as conditional, not adopted.
- *OkHttp / kotlinx-serialization.* Both resolve from the pre-existing `mavenCentral()` — no new trust boundary introduced. Standard, widely-audited artifacts.

**Cryptographic correctness (deferred, by design).** This ticket only proves the suite **loads** (`HandshakeState` constructs). The handshake's security-critical invariants — empty prologue, empty/null AD on every AEAD op, correct `sender`/`receiver` split direction (crossing them = silent MAC failure on first transport message), standard-padded base64, in-encrypted-handshake token — are all documented in the spike doc's "Proven wire contract" and are the responsibility of #274 / the transport ticket, where they become live code. Flagging here so they are not assumed-covered by this ticket: **the smoke test deliberately does not exercise them**, and that is correct scope, not a gap.

**License / legal.** `noise-java` is MIT (per-file headers present); vendoring with intact headers + a provenance note is compliant. No copyleft contamination.

**Secrets / data exposure.** None — no keys generated, stored, transmitted, or logged in this ticket. Device-key custody (Android Keystore, alias `pyrycode.device_static.<server-id>`) is #274's concern.

**Verdict: PASS.** The supply-chain decision is made in favor of the lower-trust-surface option (vendoring), the legal posture is clean, and no cryptographic-correctness obligation is silently deferred — each is explicitly assigned to its downstream ticket. No spec revision required.
