# #1487 — Archive matches 18:2: default tab, tapped-tab fill, row pitch, large-text wrap

## Files read

- `ui/settings/ArchivedDiscussionsViewModel.kt` — the `selectedTab` `MutableStateFlow` seed.
- `ui/settings/ArchivedDiscussionsScreen.kt` — `ArchiveTabLabel` (`maxLines = 2`, plain `clickable`), the host label, `LoadedBody`.
- `ui/conversations/components/ArchiveRow.kt` — `ArchiveRow`, whose subtitle uses `bodySmall`.
- `ui/theme/Type.kt` — `AppTypography.bodySmall` declares a 16 sp line height but no `lineHeightStyle`.
- `androidTest/.../design/ListDesignCaptureTest.kt` — `walk` and `tap`.
- `androidTest/assets/design-1220/list/index.md` — the Archive section the audit wrote.
- Tests: `ArchivedDiscussionsViewModelTest`, `ArchivedDiscussionsLayoutTest`, `ArchiveAppearanceCaptureTest` (compact case).

Overlap: #1563 edits other blocks of `ListDesignCaptureTest.walk` and the list `index.md`; edits here stay additive and
local to Archive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=18-2

Header 64 px, the host label "Pyry" (`labelMedium`, 16 px line, 4 px vertical padding, 24 px tall), two equal tabs at
14 px top and 12 px bottom padding around a 20 px `labelLarge` line (46 px) above a 2 px indicator (`Schemes/Primary` under
the selected tab, transparent otherwise) and a 1 px `Schemes/Outline Variant` divider, then 4 px before the rows. Rows are
12 + 24 (`titleMedium`) + 2 + 16 (`bodySmall`, 75 % `onSurfaceVariant`) + 12 = 66 px, beside a 40 px restore button. The
selected tab draws no fill. Channels is selected. Empty-tab frames are `673:3577` and `673:3621`.

## Change

1. **Default tab.** Seed `selectedTab` with `ArchiveTab.Channels`, as `18:2` opens on Channels.
2. **Row pitch.** The 64 px pitch comes from the subtitle. `AppTypography.bodySmall` has no `lineHeightStyle`, so Compose's
   default trim drops the line box above the first and below the last line, and a single subtitle line measures about
   14 px instead of 16 (the title's M3 default `titleMedium` keeps `Trim.None`, so it stays 24). `ArchiveRow` gives its
   subtitle `lineHeightStyle = LineHeightStyle(Center, Trim.None)`, the same box the thread modals use, and the row
   becomes 66. The theme stays unchanged so other `bodySmall` users do not move.
3. **Large text.** `ArchiveTabLabel` uses `maxLines = 1` with `autoSize = TextAutoSize.StepBased(minFontSize = 10.sp,
   maxFontSize = labelLarge.fontSize)`. The label keeps its design size wherever it fits and steps down only when its tab is
   too narrow, as at 320 px and 150 % scale, so the count stays visible on one line above the indicator.
4. **Tapped-tab fill.** Production stays as it is (see Open Questions): the fill is the press ripple still fading. The
   capture's `tap` now waits until the tapped tab reports selected, then for Compose idle, which covers the ripple's
   frames, before it returns. `walk` no longer taps Channels before the `archive` capture.

## Testing strategy

- `ArchivedDiscussionsViewModelTest`: `loaded_defaultSelectedTab_isDiscussions` becomes `…_isChannels`; other cases
  that relied on the Discussions default select it explicitly or expect Channels.
- `ArchivedDiscussionsLayoutTest` (Robolectric, native graphics):
  - consecutive restore buttons' tops differ by 66 dp;
  - at a forced 320x700 dp size with font scale 1.5, each tab label measures one line, fits inside its tab and sits
    above the indicator.
- Device: `ListDesignCaptureTest` on the full `pixel8Api35` image with `requireRealSystemBars=true`, both methods, new
  captures compared with `18:2` by `scripts/design-compare.py`; the Archive section of the list `index.md` gets new
  verdicts. Device-only because it needs real pixels and a real `input tap`.
- Existing: `ArchiveRowTest`, `ArchiveAppearanceCaptureTest` compiled.

## Open Questions

- **Fading ripple or persistent state?** Resolved on 2026-10-03 with a throwaway probe on `pixel8Api35`. A shell `input
  tap` on "Channels (" was sampled at its left edge: no change at +0 and +100 ms (the tap is not yet dispatched),
  27,45,59 at +200 ms, 15,34,48 at +400 ms, back to the base 11,31,45 from +800 ms on. Touch mode was on before and after
  and no semantics node was focused. So it is the default press indication fading, captured mid-fade because `tap`
  returned before the event was dispatched. No production change is needed for it.

## Revisions

- **2026-10-03.** The Channels default reached three callers the plan did not list, all of which assumed Discussions.
  `ArchiveNavigationTest`'s `openArchiveFromTheList` and two live scenarios in `InteractiveStreamE2ETest`
  (`interactiveTurn_archiveRestore_roundTripsListMembership` step 9 and `interactiveTurn_archiveTwoChats_listsSecondArchivedFirst`
  step 3) now tap the Discussions tab, the scenarios through a new private `openArchiveDiscussionsTab` helper. The
  production contract is unchanged.
- **2026-10-03 (rework 1).** The verifier found a fourth caller of the Channels default:
  `interactiveTurn_twoHostsArchive_staysPerHost` step 5 waits for an archived chat, which is a discussion. It now
  selects the Discussions tab first. The helper became `openArchiveTab(labelId)`, which also replaces the inline
  Channels tap in `interactiveTurn_createEditArchiveChannel_readsPromptBack` step 8. `ArchiveTabLabel` keeps
  `TextOverflow.Ellipsis` beside its auto-size, so a label that still does not fit at the 10 sp floor ends in an
  ellipsis instead of clipping. The one-line, fits-in-its-tab contract is unchanged.
