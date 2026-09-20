# Default-real repository builds (#631)

## Files read

- `app/build.gradle.kts` → `android.defaultConfig`, `android.testOptions` — generated flag and test runner configuration.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `appModule`, `conversationRepositoryModule` — existing concrete bindings and isolated selector.
- `app/src/main/java/de/pyryco/mobile/PyryApp.kt` → `PyryApp.onCreate` — production uses the selector default.
- `app/src/test/java/de/pyryco/mobile/di/ConversationRepositoryBindingTest.kt` → `withSelector`, `defaultParam_isSafeFakeDefault` — pure JVM Koin proof and obsolete fake-default tripwire.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` → `onCreate`, `tappedRelayRepositoryModule` — ordinary tests currently inherit the flag; relay tests replace the binding with a parser tap.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eInstrumentationRunner.kt` → `newApplication` — installs the test application before arguments are available.
- `scripts/e2e-emulator.sh` → `GRADLE_TEST_ARGS` invocation — explicit build selection belongs alongside existing instrumentation arguments.
- `scripts/test_android_test_gate.py` → `test_runner_reporting_matches_current_daemon_default` — extracted-shell stub test pattern.
- `docs/knowledge/features/dependency-injection.md` § Adding a binding; `conversation-repository.md` § Phase 1 implementation; `stable-conversation-repository.md` § How it works; `relay-repository-coordinator.md` § How it works — select the existing facade without rebuilding lifecycle or repository behavior.
- `docs/knowledge/features/development-verification.md` § Gradle and source checks, Test scheduling and harnesses; `docs/e2e-interactive-stream.md` § The ladder — scoped JVM task, instrumentation compile, shell stub, dispatcher execution ownership.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` → `RelayLog` — existing debug-only diagnostics.
- Sibling `pyrycode/docs/protocol-mobile.md` § Security model — inherited threat model; this ticket changes no wire contract.

## Design source

N/A — build/DI wiring only, as stated in the ticket; no UI design changes.

## Change

Generate `BuildConfig.USE_RELAY_REPOSITORY` from the Gradle property `useRelayRepository`, parsed as a strict boolean with default `true`. Normal builds and `-PuseRelayRepository=true` select `StableConversationRepository`; `-PuseRelayRepository=false` selects `FakeConversationRepository`. Keep the selector signature, singleton resolution, and explicit `useRelay = false` injection intact. Update its obsolete comments and the production startup comment.

In `E2eTestApplication.onCreate`, pass `useRelay = false` when `relayUrl` is absent and update the application/runner explanations and static fake-mode log. Keep relay argument handling and `tappedRelayRepositoryModule` behavior unchanged. Add `-PuseRelayRepository=true` to the existing harness Gradle invocation. No new state, jobs, network boundaries, runtime setting, or lifecycle/error behavior; the existing supervisor/coordinator and debug-gated relay diagnostics remain responsible for connections.

Sizing: one deliverable (selecting the build repository consistently), approximately 180–220 written lines including proof and this security-reviewed plan; 2 production Kotlin files (comments only), 1 Gradle file, 0 new exported production types, 0 signature migrations, 2 AC, 0 new state-machine reject branches. The #350 analogue was 131 implementation/test additions plus 148 plan lines. This change reuses its selector. All six limits hold. Remote feature branches were fetched and checked for overlaps with all planned paths; none found.

## Testing strategy

- Replace the fake-default tripwire in `ConversationRepositoryBindingTest` with an independent expected build selection supplied to the JVM by Gradle from `useRelayRepository` (default `true`), checking both the generated flag and the resolved concrete singleton. Preserve explicit-fake, explicit-real and singleton tests. Observe RED against the old default before changing production configuration.
- Run `testDebugUnitTest --tests de.pyryco.mobile.di.ConversationRepositoryBindingTest` for default, explicit real, and explicit demo builds. The aggregate `test` task does not accept filters here.
- Add `RepositoryBindingInstrumentedTest`: with no relay argument, assert the installed application's Koin graph resolves the fake. This proves actual runner wiring in the dispatcher UI gate; skip this assertion in relay-argument runs.
- Add `scripts/test_e2e_emulator_gradle.py`: execute the extracted invocation with a stub Gradle executable and synthetic pairing values, asserting exact arguments with and without forced rerun and no tracked-source changes. Observe missing real-mode flag RED before changing the script. No daemon or device is started.
- Run Spotless, lint, `assembleDebug` and `compileDebugAndroidTestKotlin`; compile both real and demo modes. Return to default-real outputs. The dispatcher owns full regression and device execution. Build/DI wiring adds no operator-facing flow requiring a new rung-3 scenario.

## Documentation handoff

Pending for the documentation stage, verbatim requirement: “Documentation stage: update README ‘Build’ with default-real, explicit-real and demo commands, and ‘Status’ to remove the fake-default claim. Reconcile the default description in the DI/repository topics.”

Paths/sections: `README.md` § Build and § Status; `docs/knowledge/features/dependency-injection.md` § How it works, Adding a binding and Related; `docs/knowledge/features/conversation-repository.md` § Phase 1 implementation; `docs/knowledge/features/stable-conversation-repository.md` introduction; `docs/knowledge/features/remote-conversation-repository.md` connection-wiring description. Commands: `./gradlew assembleDebug`, `./gradlew assembleDebug -PuseRelayRepository=true`, `./gradlew assembleDebug -PuseRelayRepository=false`.

## Security review

**Verdict:** PASS

- Trust boundaries: the build operator alone chooses a compile-time boolean; no daemon frame, intent or preference can change it. `conversationRepositoryModule` resolves only existing concrete singletons. Ordinary instrumentation explicitly selects fake; the relay-only tap remains test-source-only.
- Tokens: no credentials added to Gradle configuration or generated fields. Existing `PairedServerStore` and `KeystorePairedServerStore` bindings remain; the shell proof uses invented credentials only.
- Storage: no new runtime paths, persistence or backup changes. The harness continues passing arguments without rewriting tracked source.
- Android attack surface: no manifest, exported component, intent, WebView or push changes. The custom application and binding test remain in `androidTest`.
- Cryptography: no changes to `NoiseSessionFactory`, `NoiseSessionPump`, vendored Noise primitives, key stores or nonce lifecycle.
- Network/I/O: use the existing `StableConversationRepository` facade and `RelayRepositoryCoordinator`, preserving the transport factory, URL validation, TLS policy, timeout/backoff and inbound decoding. No bypass or new endpoint is introduced by the build switch.
- Errors/logs: invalid boolean input fails at configuration; the option is not secret. No runtime error modes are added. The changed test startup log contains only a static mode; existing production relay logging remains debug-gated.
- Concurrency: no new scopes, locks, flows or connections. `LifecycleConnectionDriver` still owns foreground/background transitions; the coordinator still owns connection-scoped repositories.
- Threat alignment: malicious relay, disk token theft, hostile daemon frames and UI leakage retain the upstream security model and existing implementations. This review approves only the binding/build delta, not a new audit or guarantee of those unchanged layers.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-20

## Open questions

None.
