# #1359 — Hide info-level banners as desktop does

Short plan: one enum value, one mapping arm, one skipped draw arm.

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `BannerLevel`, `ThreadItem.Banner` — gains `Info`; KDoc names which levels read how.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `BannerPayloadDto.toRow` — the one wire-to-level mapping, shared by the live lane (`ThreadProjection.decodeBanner`) and history replay (`HistoryPageReducer`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the `is ThreadItem.Banner` arm of the delivered-row `when` inside the thread `LazyColumn` — where the row is drawn.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/BannerNoticeRow.kt` → `BannerNoticeRow` — reads `level == Warning` only; untouched, it never receives an `Info` row.
- `docs/knowledge/features/banner-notice-row.md` § level — "`level` is closed client-side"; the documentation stage updates it.
- Tests: `HistoryPageReducerTest.reduce_storedBannerOfAnyOtherLevel_readsAsANotice`, `RemoteConversationRepositoryTest.banner_everyLevelButWarning_mapsToNotice` — the existing history and live mapping assertions that currently expect `info` → `Notice`.
- Desktop reference (per ticket): `ConversationScreen.tsx` `TimelineRow` `banner` arm renders nothing for `level === 'info'`.

In-flight overlaps: #1306 and #1328 touch `ThreadScreen.kt`, #1326 touches `ConversationRepository.kt`, #1351 touches `RemoteConversationRepositoryTest.kt`; none touch the banner code, so edits stay additive and local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1576

Thread notification component: muted `bodyMedium` text in the message gutter. No visual change for drawn banners; an `info` banner simply draws nothing. (Figma MCP was unauthenticated in this session; nothing new is drawn, so there is nothing to compare.)

## Change

`enum class BannerLevel { Warning, Notice }` becomes `{ Warning, Notice, Info }`. `BannerPayloadDto.toRow` maps `"warning"` → `Warning`, `"info"` → `Info`, anything else (`notice`, `suggestion`, empty, unknown) → `Notice`, still total. In `ThreadScreen`'s delivered-row `when`, the `ThreadItem.Banner` arm draws `BannerNoticeRow` only when `item.level != BannerLevel.Info`; otherwise the keyed `LazyColumn` item composes to an empty zero-height `Box`. The row stays in `ThreadUiState.items` and in the repository thread (dedup via `holdsBanner`, keys, `timestamp()` all unchanged), matching desktop, which keeps the row in its timeline and renders nothing. No other consumer switches on `BannerLevel` (`BannerNoticeRow` compares with `==`), so nothing else moves.

## Testing strategy

- Mapping, both lanes: update `RemoteConversationRepositoryTest.banner_everyLevelButWarning_mapsToNotice` (live) and `HistoryPageReducerTest.reduce_storedBannerOfAnyOtherLevel_readsAsANotice` (history) so `info` expects `Info` and `notice`, `suggestion`, empty and unknown still expect `Notice`.
- Screen: new Robolectric test `ThreadBannerLevelTest` in `app/src/sharedTest/.../ui/conversations/thread/`: a `ThreadScreen` holding an `Info`, a `Notice` and a `Warning` banner — the info text does not exist; the notice and warning texts are displayed.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/banner-notice-row.md` — the `BannerLevel` snippet and the "`level` is closed client-side" bullet should say `info` maps to `BannerLevel.Info` and draws nothing in the thread (row kept in data, as desktop).
