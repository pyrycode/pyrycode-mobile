# Knowledge index

One-line pointers to evergreen docs. Per-ticket implementation notes live under `codebase/` and aren't indexed here — that directory listing is its own index.

## Features

- [Welcome screen](features/welcome-screen.md) — first onboarding screen; stateless Composable with two navigation callbacks, consumed by #8 and #14.
- [Data model](features/data-model.md) — `Conversation` / `Session` / `Message` schema in `data/model/`; shared shape across mobile, Discord, and pyrycode CLI consumers.

## Decisions

- [ADR 0001 — kotlinx-datetime for data layer](decisions/0001-kotlinx-datetime-for-data-layer.md) — every `data/` timestamp uses `kotlinx.datetime.Instant`, never `java.time.Instant`, to keep a Compose Multiplatform walk-back cheap.

## Architecture

_(none yet — system overview will land when there's more than one screen to map)_
