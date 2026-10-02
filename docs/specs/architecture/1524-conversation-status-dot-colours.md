# #1524 Match conversation status dot colours to 15:8

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `ConversationStatusDot`, `TreeConversationRow`: the dot's ring and per-state fill, and the row that knows `selected`.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` / `Color.kt` → `LocalStaticDarkPalette`, `inversePrimaryDark` `#32628D`, `primaryDark` `#9DCBFC`, `tertiaryDark` `#FFB59F`, `successDark` `#2FC038`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt` → the existing `if (LocalStaticDarkPalette.current) inversePrimary else …` idiom this change mirrors.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRowsTest.kt` → `conversationRow_noAttentionState_drawsTheErrorFill`, the hand-drawn-view pixel count the new test reuses.
- `pyrycode-desktop/src/renderer/src/screens/channels/channels.css` → `.conversation-status-dot` and the open-row rule `.channel-list__row:has(> …[aria-current='true']) > .conversation-status-dot--idle`, which fills an idle dot with its ring colour on the open row.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Sidebar `133:259`, Channel rows under `I133:259;103:2985`. Every dot is a 6dp disc with a 1dp ring bound to `Schemes/Inverse Primary`; the frame's rendered pixels are `#32628D` for the ring on every row. Fills read from the rendered frame: `kitchenclaw refactor` / `Gourmet Hub` / `Trading view` none (Idle); `rocd-thinking` `#2FC038` (Unread, `success`); `Culinary Corner` / `Supplement log` `#FFB59F`, node named "Blinking busy status dot", bound `tertiary-fixed-dim` (Running, `tertiary`); `pyrycode discord integration` (`I133:259;103:2972;403:7411`, on the `on-primary` selected row) filled `#32628D`, the ring colour. WaitingForAnswer has no dot in this frame; its frame is `640:2440`, owned by #1507.

## Change

Mapping: Idle → the ring dots, Unread → the green dot, Running → the pink blinking dot. The fills already match (`tertiary` = `#FFB59F`, `success` = `#2FC038` under the static dark palette). Two colours differ:

1. **Ring.** The app draws `primary` (`#9DCBFC`); the frame draws `#32628D`, which is `inversePrimary` under the static dark palette and the binding Figma names. Under `LocalStaticDarkPalette` the ring becomes `colorScheme.inversePrimary`; other palettes keep `primary`, as `SessionBoundaryDelimiter` does, because `inversePrimary` on a light surface would vanish.
2. **Blue dot on the darker row.** It is not an attention state: it sits on the selected (open) row and is an idle dot filled with its own ring colour. Desktop draws exactly this (`channels.css`, the open-row `--idle` rule). `ConversationStatusDot` gains a `selected: Boolean` passed from `TreeConversationRow`; Idle's fill becomes the ring colour when selected. Other states keep their fill when selected, as desktop scopes the rule to `--idle`. Desktop also fills on hover; the phone has no hover and the frame's Hover variant (`398:7259`) shows an empty ring, so pressed does not fill.

WaitingForAnswer's `warning` fill is untouched (#1507). Nothing else moves: size, ring width, blink and semantics are unchanged.

## Testing strategy

New Robolectric test in `ConversationTreeRowsTest` beside `conversationRow_noAttentionState_drawsTheErrorFill`, same hand-drawn-view pixel count, under `PyrycodeMobileTheme(darkTheme = true)`: for Idle and Unread rows the ring paints `inversePrimary` pixels and no `primary` pixels; a selected Idle row paints more `inversePrimary` pixels than an unselected one (the fill). Running shares the ring code path and its blink makes a pixel count clock-dependent, so it is covered by the ring assertion on the other states. The PR carries a capture of Idle, Running, Unread and selected-Idle rows beside the frame's dot crops.
