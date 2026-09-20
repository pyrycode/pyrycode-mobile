# Pyrycode Mobile — Project Instructions

Android client for [Pyrycode](https://github.com/pyrycode/pyrycode). Native Kotlin + Jetpack Compose.

## Status

See [README § Status](README.md#status) — status lives there in exactly one place; this file does not duplicate it.

## Conversations model

The data-model entity is **`Conversation`** with an `isPromoted: Boolean` flag, surfaced as two UI tiers:

- **Discussions** (unpromoted) — auto-named, throwaway, scratch cwd, 30-day auto-archive.
- **Channels** (promoted) — user-named, persistent, dedicated cwd by default (or bound to existing project folder), eligible for memory plugins. The main list.

**Sessions are nested under conversations.** A conversation has a current active session and history of past sessions. Threads render messages chronologically across sessions, with **delimiters** at session boundaries (`/clear`, idle-evict, workspace change). Above-delimiter messages are visually de-emphasized; the explanatory line below the delimiter reminds users that claude doesn't remember above the line and offers a memory-plugin install affordance.

`Conversation` is server-side and shared by the pyrycode clients. Mobile consumes that shared entity.

## Stack

- Kotlin + Jetpack Compose + Material 3
- Min SDK 33 (Android 13), target SDK latest stable
- Architecture: MVI with `ViewModel` + `StateFlow`; sealed `UiState` + `Event` types
- Koin (DI), OkHttp (WebSocket transport, Phase 4 — see ADR 0005), DataStore (settings)
- Single Gradle module
- Gradle Kotlin DSL + `gradle/libs.versions.toml` version catalog

## Build & test

```bash
./gradlew assembleDebug              # build debug APK
./gradlew installDebug               # optional local install; dispatcher owns routine device execution
./gradlew test                       # unit tests
./gradlew connectedAndroidTest       # optional local run; dispatcher owns managed-device UI execution
./gradlew lint                       # Android Lint
./gradlew clean                      # clean build outputs
```

## Layout

Single module `app/`. Source root: `app/src/main/java/de/pyryco/mobile/`. Modularize when build incremental > 60s or screens > 10.

Package layout:

```
de/pyryco/mobile/
├── MainActivity.kt
├── PyryApp.kt              # Composition root, Koin setup
├── ui/
│   ├── theme/              # Material 3 theme + typography
│   ├── conversations/
│   │   ├── list/           # Channel list + recent-discussions drilldown
│   │   ├── thread/         # Conversation thread (channels + discussions)
│   │   └── components/     # ConversationRow, Delimiter, WorkspaceChip,
│   │                       #   PromotionDialog, WorkspacePicker
│   ├── onboarding/         # Pairing / first-run flow
│   └── settings/
├── data/
│   ├── model/              # Conversation, Session, Message
│   ├── repository/         # ConversationRepository interface + Fake/Remote impls
│   ├── preferences/        # DataStore-backed settings
│   ├── crypto/             # Device static key + paired-server key stores (Keystore-wrapped)
│   └── network/            # Phase 4 transport: OkHttpRelayTransport, NoiseIkSession,
│                           #   RelayConnectionSupervisor, MobileWireCodec
├── lifecycle/              # LifecycleConnectionDriver (#302): foreground → connect(),
│                           #   background → close() on the relay supervisor
└── di/                     # Koin modules
```

The Phase 4 crypto primitives use the **vendored `noise-java`** library at `com/southernstorm/noise/` (see ADR 0004) — `data/crypto/` + `data/network/` build the `Noise_IK` WebSocket transport on top of it.

The mobile wire protocol's single source of truth is the pyrycode repo's `docs/protocol-mobile.md` (local sibling checkout: `../pyrycode/docs/protocol-mobile.md`) — don't restate the wire contract here.

`ConversationRepository.observeMessages(conversationId)` paginates across past sessions transparently, producing a chronological stream interleaved with synthetic `SessionBoundary` markers that the thread screen renders as horizontal-rule delimiters.

## Documentation

- Per-ticket specs: `docs/specs/architecture/<N>-slug.md`.
- Feature overviews: `docs/knowledge/features/<feature>.md` (pipeline-written; each ticket's lessons fold into the overview for the area it touched, in the section they belong to).
- `docs/knowledge/codebase/<N>.md` is the frozen per-ticket archive, closed 2026-09-05. Read it as history; nothing writes there.
- Evergreen index: `docs/knowledge/INDEX.md`.
- Detailed document inventory: `docs/knowledge/CATALOG.md`.
- Shared knowledge ownership: `docs/shared-knowledge.md`.
- Verification topic: `docs/knowledge/features/development-verification.md`.
- `docs/PROJECT-MEMORY.md` is a compatibility pointer; do not append to it.
- `scripts/docs-guard.sh` keeps the overviews under 50000 bytes and free of lines that markdown misreads as headings. It is the first entry in the dispatcher's verifier gate list; run it before committing docs.

## Conventions

- **Test-first.** Red → green → refactor. Failing test first, implementation after.
- **Stateless composables.** Hoist state to the ViewModel; UI receives `state: UiState` and `onEvent: (Event) -> Unit`.
- **Sealed types** for `UiState` and `Event`.
- **No direct push to `main`.** PR + review.
- Bundle ID `de.pyryco.mobile` (locked at first Play Store publish — do not rename without understanding the cost).

## Don't

- Don't wire the real backend in by ripping out the fakes — Phase 4 is active, but the swap is architectural: replace `FakeConversationRepository` via a Koin module, don't special-case it in the UI. The networking/crypto stack already exists in `data/network/` + `data/crypto/`.
- Don't bake Android-only assumptions into the data layer — Compose Multiplatform is a walk-back trigger; keep `data/` portable.
- Don't refactor adjacent code "while you're there." Touch only what's necessary.

## Shared knowledge

Claude auto memory is disabled for this project. Start with
`docs/knowledge/INDEX.md`, then read the owning topic. Search
`docs/knowledge/CATALOG.md` only when the short map is not enough. Do not use a
private memory directory as a second source of project instructions.

Builders and verifiers record discoveries on their ticket or pull request. The
documentation stage owns evergreen topic documents, the index and the catalog.
Interactive maintainers may update shared documentation in a reviewed change.
Requirements and review findings stay on the ticket or pull request. Agent
workflow lessons belong in the agent or dispatcher repository. Personal project
direction belongs in the vault.
