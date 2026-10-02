# Channel list against `15:8` (#1521)

- `channel-list.png`: `ChannelListScreen` at 412x892, density 1.0, dark theme, with no status bar, rendered under Robolectric native graphics on four hosts named after the frame. Chats on Pyry, MB Second brain, Elli, and Channels and Chats on MB Game dev are collapsed through their own fold controls.
- `channel-list-side-by-side.png`: the capture (left) beside the Figma export of `15:8` (right).

Measured as raw image y of rows brighter than luminance 150:

| What | App | Frame |
|---|---|---|
| Settings and Archive glyphs | 32–55 | 32–55 |
| First host name | 106–118 | 106–118 |
| Host-to-host pitch (MB Second brain → Elli → MB Game dev) | 44 (28 row + 16 gap) | 44 |

Collapsed hosts draw a right chevron and no children; collapsed sections draw the closed-folder glyph, a right chevron and no rows, as the frame does. Out of scope here: per-row pens, status dots, the selected-row fill, the background glow and the deferred Apps section (see the #1431 audit).
