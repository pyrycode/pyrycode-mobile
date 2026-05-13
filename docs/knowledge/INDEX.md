# Knowledge index

One-line pointers to evergreen docs. Per-ticket implementation notes live under `codebase/` and aren't indexed here — that directory listing is its own index.

## Features

- [Navigation](features/navigation.md) — single-activity Compose NavHost in `MainActivity`; start destination gated on `AppPreferences.pairedServerExists` via `produceState` (`welcome` fresh, `channel_list` once paired); destinations `welcome`, `scanner`, `channel_list`, `conversation_thread/{conversationId}`, `settings`; route constants in colocated `Routes` object.
- [Welcome screen](features/welcome-screen.md) — first onboarding screen; stateless Composable with two navigation callbacks, mounted at the `welcome` route.
- [Scanner screen](features/scanner-screen.md) — stub QR-pairing screen at the `scanner` route; tap anywhere flips `AppPreferences.setPairedServerExists(true)` + navigates to `channel_list` with the scanner popped. Phase 4 replaces with real CameraX + ML Kit.
- [Data model](features/data-model.md) — `Conversation` / `Session` / `Message` schema in `data/model/`; shared shape across mobile, Discord, and pyrycode CLI consumers. Sentinel `DefaultScratchCwd` co-lives in `Conversation.kt` as the single-source-of-truth marker for unbound-workspace conversations.
- [Conversation repository](features/conversation-repository.md) — `ConversationRepository` interface + `ConversationFilter`, `ThreadItem` (`MessageItem` / `SessionBoundary`), `BoundaryReason`; data-layer seam every UI tier binds to. Phase 1 binding is the in-memory `FakeConversationRepository`.
- [Dependency injection](features/dependency-injection.md) — Koin scaffold; single `appModule` at `di/AppModule.kt`, `startKoin` owned by `PyryApp.onCreate`. Downstream tickets append `single`/`viewModel` lines.
- [App preferences](features/app-preferences.md) — `AppPreferences` wrapper over a shared `DataStore<Preferences>` (file `app_prefs`); typed `Flow<T>` reads + `suspend` setters for non-secret app state. First key: `pairedServerExists`.
- [ConversationRow](features/conversation-row.md) — stateless M3 `ListItem`-based row consuming `Conversation` + `Message?`; auto-name fallback branched on `isPromoted`, whitespace-normalized 2-line preview, kotlinx-datetime relative-time bucketing (`just now` / `Nm ago` / `Nh ago` / `Yesterday` / `Nd ago` / abbreviated date). Headline is a `Row` so decorations (workspace label per #19; sleeping-session indicator per #20) compose as natural-width children alongside the name. Shared primitive for #18 channel list and the discussions drilldown.
- [ChannelListViewModel](features/channel-list-viewmodel.md) — first project ViewModel; converts `observeConversations(Channels)` into a hot `StateFlow<ChannelListUiState>` via `stateIn(WhileSubscribed(5_000), initial = Loading)` with sealed `Loading` / `Empty` / `Loaded(channels)` / `Error(message)` variants and a `.catch { }` → `Error` terminal branch. Read-only this slice; events land with the screen ticket.
- [ChannelListScreen](features/channel-list-screen.md) — first stateless `(state, onEvent)` screen; renders the four `ChannelListUiState` variants (centred-`Text` placeholders for `Loading` / `Empty` / `Error`, `LazyColumn` of `ConversationRow`s keyed by `Conversation.id` for `Loaded`) and dispatches `ChannelListEvent.RowTapped` via the destination-level `onEvent` lambda. Introduces the `koinViewModel<…>()` + `collectAsStateWithLifecycle()` call-site shape at the `channel_list` NavHost destination.

## Decisions

- [ADR 0001 — kotlinx-datetime for data layer](decisions/0001-kotlinx-datetime-for-data-layer.md) — every `data/` timestamp uses `kotlinx.datetime.Instant`, never `java.time.Instant`, to keep a Compose Multiplatform walk-back cheap.

## Architecture

_(none yet — system overview will land when there's more than one screen to map)_
