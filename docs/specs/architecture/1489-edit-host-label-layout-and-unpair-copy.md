# #1489 — Edit host label and field layout, and the unpair confirmation copy

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt`: `IdentityRow`, `HostNameField`,
  `UnpairAction`, `UnpairConfirmation` and the spacing constants above `EditHostModal`. Every change lands here.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt`: the shell's content column, which centres
  the children with a 12 dp `spacedBy`. Not changed.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt`: `labelLarge` (Medium) and `bodyMedium`. Not changed;
  the SemiBold weight is applied locally, as today.
- `app/src/main/res/values/strings.xml`: `edit_host_unpair_confirm_body`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt`: existing geometry tests,
  including `unpairOutlineGrowsWithTextScaleWithoutClippingTheLabel`, which must keep passing.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt`: finds the
  confirmation through the string resource, so the copy change needs no edit there.
- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt`: captures `edit-host*` and
  `edit-host-unpair*`; the unpair capture is still tagged `figma=none`.
- `app/src/androidTest/assets/design-1220/list/index.md`: the #1431 audit rows this ticket answers.
- Precedent: `QuestionBatchModal`'s `FrameLineBox` and `PairingHeader`'s `TitleLineBox` use
  `LineHeightStyle(Center, Trim.None)` to get the frame's full line box.

Overlap: #1563 edits the Edit channel part of `ListDesignCaptureTest.walk`; this ticket changes only the unpair
capture's node id there.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369 and
https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=671-5620

`533:2369`: the `MobileModal` shell's content slot centres four children 12 px apart: two 20 px "Read only
textfield" rows (label-large emphasized, Roboto 14/20 weight 600 tracking 0.1, then body-medium value after a
10 px gap), "Input large" (20 px label, 8 px gap, 52 px `modalFieldContainer` well) and an "Actions" frame of
8 px top padding plus the 40 px outlined Unpair host button, 48 px in all. `671:5620`: the same shell titled
"Unpair host?", whose slot centres one 356x40 body-medium message on `onPrimaryContainer`: "Pyrybox will be
removed from this phone: its pairing and its connection. Pairing it again needs its QR code."

## Context

Measured before planning, on the full `pixel8Api35` image with `ListDesignCaptureTest#listFramesAt412By892`
on this branch's base, against a fresh 412x892 export of `533:2369` (bright-pixel bands, raw image px):

| Item | Figma | App |
|---|---|---|
| "Server identity:" ink x | 29–122 | 29–126 |
| "Relay address:" ink x | 29–119 | 29–121 |
| Identity value start x | 136 / 133 | 140 / 136 |
| "Host name:" glyph bottom to field top | 15 | 12.5 |
| Server identity glyph top y | 349 | 342 |
| Field top y | 437 | 427.5 |
| Unpair outline top y | 509 | 499 |

The Medium-weight text (title, Unpair host, Cancel, OK) matches Figma to the pixel, so the font is not the
cause. Three causes, each confirmed on the device:

1. **Label width is pixel hinting, not Roboto metrics.** A probe on the device measured "Server identity:" at
   weight 600 through Compose's `TextMeasurer` at ≈ 93.7 dp unhinted, which is Figma's width; a hinted `Paint`
   at 14 px gives 99. At density 1.0 Android rounds each glyph's advance to whole pixels, and over 16 glyphs
   that adds 4 px. The comment in `IdentityRow` that blames the device's Roboto metrics is wrong.
2. **The label's line box is trimmed.** The theme's styles carry no `lineHeightStyle`, so Compose trims the
   20 sp line to the font's ≈ 16.4 dp. The "Host name:" label is therefore ≈ 3.6 dp shorter than the frame's
   20 px, which pulls the field up, and the identity rows' glyphs sit at the top of their 20 dp rows instead
   of centred in them.
3. **`UnpairAction` is 56 dp in layout against the frame's 48.** It adds the 8 dp top padding outside its
   48 dp touch floor, so 8 dp of invisible touch area hangs below the outline, the centred block grows 8 dp
   and moves up 4.

A remaining ≈ 2 px is the shell's: the app's shell is 844 px tall between the real system bars against the
frame's 888, so its content slot's centre sits 2 px above the frame's. The #1431 audit already attributed that
2 px to the shell, and the ticket keeps the shell fixed. No decision record is needed.

## Design

All changes are local to `EditHostModal.kt` and the one string.

- **Label rendering.** A private `EditHostLabelStyle` composable getter (or a local `val` inside each label's
  composable) derives from `labelLarge` with `fontWeight = SemiBold`, `lineHeightStyle =
  LineHeightStyle(Center, Trim.None)` and `textMotion = TextMotion.Animated`. `TextMotion.Animated` turns on
  linear text and subpixel positioning, so the glyph advances are not hinted to whole pixels and the label
  takes its unhinted width at every density. Both identity labels and the "Host name:" label use it.
- **Value and message line box.** The identity values and the confirmation message use `bodyMedium` with the
  same `Center, Trim.None` line box, so each line occupies the frame's 20 px with its glyphs centred.
- **Unpair action height.** The 8 dp top padding moves inside the clickable `Surface`, ahead of the outline,
  and the `Surface` keeps its 48 dp minimum: layout height 8 + 40 = 48 at the default scale, matching the
  Actions frame, with the outline still 8 dp below the item's top. The 48 dp touch target now includes the
  8 dp above the outline instead of the 8 dp below it. At a larger font scale the outline grows and the item
  is 8 dp plus the outline, as before.
- **Copy.** `edit_host_unpair_confirm_body` becomes "%1$s will be removed from this phone: its pairing and its
  connection. Pairing it again needs its QR code." The `%1$s` argument and the `unnamed_host` fallback stay.
- **Comment.** `IdentityRow`'s comment about device Roboto metrics is replaced with the hinting explanation.

Other modals do not move: `MobileModal`, `Type.kt` and the shared theme tokens are untouched.

## State and concurrency model

None. Presentation-only changes to stateless composables.

## Error handling

None new.

## Testing strategy

Shared Robolectric tests in `EditHostModalTest`, written first and red before the change:

- `hostNameLabelKeepsTheFramesLineBoxAboveTheField`: at the 412x892 size, the "Host name:" label node is 20 dp
  tall and the name well's top is 28 dp below the label's top (20 + 8). Red today: the label is ≈ 16 dp.
- `unpairActionIsTheFramesFortyEightDpWithTheOutlineEightBelowItsTop`: `UnpairAction` alone lays out 48 dp tall
  with its outline's top 8 dp below the action's top and the outline 40 dp tall; the touch target stays at
  least 48 dp. Red today: 56 dp.
- `unpairConfirmationNamesTheHostAndNoWorkspace`: with `confirmingUnpair = true`, the body reads exactly the
  `671:5620` sentence with the host name, and no node's text contains "workspace".

Existing tests that must stay green: all of `EditHostModalTest`, including the identity-row alignment and the
Unpair font-scale test, and `ChannelListScreenTest`'s unpair confirmation tests.

The label widths depend on device hinting, which Robolectric does not reproduce, so they are proved by the
capture: `ListDesignCaptureTest` (both methods) on `pixel8Api35` with `requireRealSystemBars=true`, recapturing
`edit-host*.png` and `edit-host-unpair*.png`, with the unpair capture's node changed from `none` to `671:5620`.
`scripts/design-compare.py` builds the side-by-side and overlay against fresh exports of both frames, and the
measured table goes on the PR. The recaptured PNGs and their `.txt` files replace those under
`app/src/androidTest/assets/design-1220/list/`. The capture test changes only its node id, so no device-only
test is added.

## Open Questions

- Is `TextMotion` stable in the pinned Compose BOM, or does it need an opt-in? Settle at compile time.

## Documentation handoff

- `app/src/androidTest/assets/design-1220/list/index.md`, section "Modal › Edit host — `533:2369`" and the
  Unpair host confirmation row in the gaps table: pending for the documentation stage, to record the
  re-measured verdicts and that the confirmation copy no longer names a workspace.
