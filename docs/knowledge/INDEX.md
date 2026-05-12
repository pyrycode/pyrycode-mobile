# Knowledge index

One-line pointers to evergreen docs. Per-ticket implementation notes live under `codebase/` and aren't indexed here — that directory listing is its own index.

## Features

- [Navigation](features/navigation.md) — single-activity Compose NavHost in `MainActivity`; `welcome` start + `channel_list` and `conversation_thread/{conversationId}` placeholders, route constants in colocated `Routes` object.
- [Welcome screen](features/welcome-screen.md) — first onboarding screen; stateless Composable with two navigation callbacks, mounted at the `welcome` route.
- [Data model](features/data-model.md) — `Conversation` / `Session` / `Message` schema in `data/model/`; shared shape across mobile, Discord, and pyrycode CLI consumers.
- [Conversation repository](features/conversation-repository.md) — `ConversationRepository` interface + `ConversationFilter`, `ThreadItem` (`MessageItem` / `SessionBoundary`), `BoundaryReason`; data-layer seam every UI tier binds to. Phase 1 binding is the in-memory `FakeConversationRepository`.
- [Dependency injection](features/dependency-injection.md) — Koin scaffold; single `appModule` at `di/AppModule.kt`, `startKoin` owned by `PyryApp.onCreate`. Downstream tickets append `single`/`viewModel` lines.
- [App preferences](features/app-preferences.md) — `AppPreferences` wrapper over a shared `DataStore<Preferences>` (file `app_prefs`); typed `Flow<T>` reads + `suspend` setters for non-secret app state. First key: `pairedServerExists`.

## Decisions

- [ADR 0001 — kotlinx-datetime for data layer](decisions/0001-kotlinx-datetime-for-data-layer.md) — every `data/` timestamp uses `kotlinx.datetime.Instant`, never `java.time.Instant`, to keep a Compose Multiplatform walk-back cheap.

## Architecture

_(none yet — system overview will land when there's more than one screen to map)_
