# #1109 — Show Codex notifications the daemon cannot map

Short plan: two enum constants, two wire mappings, two labels. No new type, state or failure mode.

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `UnrecognizedSite` — the closed site enum; gains `CodexMethod`, `CodexItem`.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `toUnrecognizedSite` (private, used by `UnrecognizedMessagePayloadDto.toRow`) — the one narrowing point shared by the live path (`ThreadProjection`) and the history path (`HistoryPageReducer`); gains `"codex_method"` and `"codex_item"`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/UnrecognizedMessageRow.kt` → `siteLabel` — exhaustive `when`, gains two arms.
- `app/src/main/res/values/strings.xml` → `thread_unrecognized_site_*` — two new strings: `Codex notification`, `Codex item`.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/UnrecognizedRowSentinel.kt` → `wireToken` — exhaustive `when` in test code; must gain the two wire tokens or the test set stops compiling.
- `../pyrycode/docs/protocol-mobile.md` § `unrecognized_message` — `site` closed set now lists `codex_method` and `codex_item` (pyrycode#2608).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Host frame only. The unrecognized row itself is not drawn in Figma (recorded by #608); this ticket adds only two site-label strings rendered in the existing summary slot, so there is nothing new to compare visually.

## Change

`toUnrecognizedSite` maps `codex_method` → `UnrecognizedSite.CodexMethod` and `codex_item` → `UnrecognizedSite.CodexItem`; every other string still maps to `null`, so `toRow` still drops unknown sites. Because the live and history paths both go through `toRow`, one mapping covers both. `siteLabel` resolves the two new constants to `Codex notification` and `Codex item`. The enum stays closed, so the "hostile daemon cannot inject a label" property is unchanged; KDocs that say "four" become "six".

## Testing strategy

- `RemoteConversationRepositoryTest` (live): extend `unrecognizedMessage_eachSiteMapsToItsConstant` with the two Codex sites. AC 2 (other unknown sites dropped) is already pinned by `unrecognizedMessage_unknownSite_droppedCollectorSurvives`.
- `HistoryPageReducerTest` (history reload): new test — `codex_method` and `codex_item` entries fold to rows with the Codex sites; an unknown site entry is dropped.
- `UnrecognizedMessageRowTest` (Robolectric): new test per label — collapsed summary reads `Unrecognized message · <type> · Codex notification` / `… · Codex item`.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: the thread / unrecognized-row feature overview may note the two Codex sites.
