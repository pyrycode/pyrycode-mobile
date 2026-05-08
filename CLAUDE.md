# Pyrycode Mobile — Project Instructions

Android client for [Pyrycode](https://github.com/pyrycode/pyrycode). Native Kotlin + Jetpack Compose.

## Status

**Phase 0 — UI scaffolding.** Built against a fake data layer (`FakeConversationRepository`); real backend integration is deferred to Phase 4 (after pyrycode ships its mobile API in Phase 3).

## Conversations model

The data-model entity is **`Conversation`** with an `isPromoted: Boolean` flag, surfaced as two UI tiers:

- **Discussions** (unpromoted) — auto-named, throwaway, scratch cwd, 30-day auto-archive.
- **Channels** (promoted) — user-named, persistent, dedicated cwd by default (or bound to existing project folder), eligible for memory plugins. The main list.

**Sessions are nested under conversations.** A conversation has a current active session and history of past sessions. Threads render messages chronologically across sessions, with **delimiters** at session boundaries (`/clear`, idle-evict, workspace change). Above-delimiter messages are visually de-emphasized; the explanatory line below the delimiter reminds users that claude doesn't remember above the line and offers a memory-plugin install affordance.

`Conversation` is server-side (defined by pyrycode CLI's eventual `conversations.json` registry). Discord-side (Phase 2 of pyrycode) and Mobile-side (Phase 3 Mobile API) consume the same entity.

## Stack

- Kotlin + Jetpack Compose + Material 3
- Min SDK 33 (Android 13), target SDK latest stable
- Architecture: MVI with `ViewModel` + `StateFlow`; sealed `UiState` + `Event` types
- Koin (DI), Ktor (networking, Phase 4+), DataStore (settings)
- Single Gradle module
- Gradle Kotlin DSL + `gradle/libs.versions.toml` version catalog

## Build & test

```bash
./gradlew assembleDebug              # build debug APK
./gradlew installDebug               # install on connected device/emulator
./gradlew test                       # unit tests
./gradlew connectedAndroidTest       # instrumented tests (device required)
./gradlew lint                       # Android Lint
./gradlew clean                      # clean build outputs
```

## Layout

Single module `app/`. Source root: `app/src/main/java/de/pyryco/mobile/`. Modularize when build incremental > 60s or screens > 10.

Planned package layout (Phase 1+):

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
│   └── settings/
├── data/
│   ├── model/              # Conversation, Session, Message
│   ├── repository/         # ConversationRepository interface + Fake/Remote impls
│   └── network/            # Ktor client (Phase 4)
└── di/                     # Koin modules
```

`ConversationRepository.observeMessages(conversationId)` paginates across past sessions transparently, producing a chronological stream interleaved with synthetic `SessionBoundary` markers that the thread screen renders as horizontal-rule delimiters.

## Conventions

- **Test-first.** Red → green → refactor. Failing test first, implementation after.
- **Stateless composables.** Hoist state to the ViewModel; UI receives `state: UiState` and `onEvent: (Event) -> Unit`.
- **Sealed types** for `UiState` and `Event`.
- **No direct push to `main`.** PR + review.
- Bundle ID `de.pyryco.mobile` (locked at first Play Store publish — do not rename without understanding the cost).

## Don't

- Don't add a real backend client until Phase 4 (after pyrycode's mobile API ships).
- Don't bake Android-only assumptions into the data layer — Compose Multiplatform is a walk-back trigger; keep `data/` portable.
- Don't generalize an agent dispatcher across pyrycode + pyrycode-mobile prematurely. The eventual `pyrycode-mobile-agents` repo is a fork, not a reuse.
- Don't refactor adjacent code "while you're there." Touch only what's necessary.
