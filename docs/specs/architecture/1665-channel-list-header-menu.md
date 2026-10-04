# Channel list header menu (#1665)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt`: `ChannelListScreen`, `ChannelListTopBar` and `ChannelListBarEntry` own fixed chrome and existing navigation events.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/OptionsOverlay.kt`: `OptionsOverlay`, `AnchoredAbove` and `OptionsColumn` provide action rows, consumed dismissal and constrained scrolling.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen` converts live window anchors into its same-window overlay layer.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/SlashCommandTypeAhead.kt`: default overlay placement must remain above the input.
- `app/src/main/res/values/strings.xml` and `app/src/main/res/drawable/ic_thread_overflow.xml`: existing menu description, labels and exact 6 × 24dp glyph.
- `app/src/main/res/drawable/ic_sidebar_settings.xml` and `ic_sidebar_archive.xml`: retire assets after their list callers migrate.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt`: toolbar geometry, empty and scrolled tree coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/OptionsOverlayColoursTest.kt`: existing above-placement, scrolling and palette assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterTest.kt` and `SlashCommandTypeAheadScreenTest.kt`: default callers' dismissal and interactions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/{SettingsNavigationTest,ArchiveNavigationTest}.kt` and `ui/host/UnpairNavigationTest.kt`: assembled list navigation selectors.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: archive entry and round-trip selectors and shared settings helper.
- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt`, `MainActivityInsetsDeviceTest.kt` and `ui/conversations/list/SidebarToolbarCaptureTest.kt`: affected selectors and real toolbar geometry.
- `docs/knowledge/features/channel-list-screen.md` and `channel-list-screen-how-it-works.md`: fixed toolbar outside list scrolling, preserved rule and gutters.
- `docs/knowledge/features/options-overlay.md`: same-window scrim avoids focus loss and tap-through; intrinsic width precedes scrolling.
- `docs/knowledge/features/development-verification-gates.md` and `docs/e2e-interactive-stream.md`: shared tests run on Robolectric; full live suite belongs to the dispatcher.

## Design source

Closed header: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=133-259, top bar `I133:259;115:3693`, buttons `I133:259;497:1874`. The screenshot shows a primary-tinted vertical ellipsis at the left, the existing plus at the right, and the thin rule below. Figma metadata confirms the ellipsis is 6 × 24dp inside a 24dp visual frame; retain the existing 44dp touch box and primary tint.

Dropdown: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958. Reuse Options overlay in Actions mode: bodySmall primary labels, 12dp horizontal and 6dp vertical row padding, 2dp column padding and the existing 6dp shape and theme surfaces. No separate open-dropdown frame is required under #1665's recorded reuse decision. This hidden component's screenshot renders transparent; its structured context and existing rendered overlay tests supply its appearance contract.

## Context

The standalone Settings and Archive controls differ from the locked header. Replace them with one menu without changing destinations, pairing, list content or shared overlay styling. No decision record is needed.

Sizing: one deliverable, approximately 700 inserted/deleted lines including plan and tests, one new exported placement enum, no required existing consumer migrations for the defaulted overlay parameter, five acceptance criteria and no state-machine rejection branches. The #884 commit includes wider composer/ViewModel work; this ticket only changes presentation and selectors. Codegraph caller queries returned no results, so repository searches establish the two production overlay callers and affected tests.

Overlap: #1642, #1682, #1689, #1690, #1691, #1693 and #1695 touch E2E scenarios; #1642 also adds an unrelated string. Their diffs do not restructure this menu or its helpers. Keep shared-file edits local.

## Design

Add `OptionsOverlayPlacement` with `Above` and `Below`, and a trailing `placement` parameter defaulting to `Above`. Generalize the private anchoring layout while preserving the default branch's math. Below measures only the room between anchor bottom plus 4dp and the layer bottom minus 8dp; it places the column at that top. Both modes retain horizontal anchor-left minus 12dp alignment and 8dp edge clamping.

Wrap the list Scaffold in a same-window Box. Store the button's live window bounds and the Box window origin, translating before passing the anchor to OptionsOverlay. The header emits an UI-local menu-open callback; selecting a fixed settings/archive value closes first and emits the existing event. Settings then Archive use existing client resource labels, actions=true, selectedValue empty and notListed=0. Remove the old descriptions and two now-unused sidebar vectors after migrating every consumer.

## State and concurrency model

The menu-open flag and anchor/origin are UI-local `remember` state, retained through recomposition and allowed to reset on configuration changes. No new ViewModel, flow, dispatcher or coroutine job. Existing overlay BackHandler and updated dismissal callback close locally; same-window scrim intercepts outside taps above the Scaffold.

## Error handling

No new I/O or error state. Both entries retain their existing navigation events and downstream handling. Only client-owned fixed values and resource labels enter this menu.

## Testing strategy

Write failing shared tests first. Update ChannelListScreenTest's empty/tree/scrolled toolbar checks to the two controls and exact menu glyph/target geometry. Pointer taps verify edge routing, both rows, ordering, local close, recomposition retention, outside tap over pairing without tap-through, and back dismissal. Test a nonzero screen origin to prove anchor conversion.

Extend OptionsOverlayColoursTest with below placement, live anchor movement, horizontal clamping and short available-height scrolling; preserve existing above-height tests. Check action semantics and palette pixels in light/static dark. Run affected navigation tests, ChannelListColoursTest, ThreadComposerFooterTest and SlashCommandTypeAheadScreenTest.

Migrate existing E2E archive callers and settings helper through Open menu then the resource label. The existing rung-3 `interactiveTurn_listArchiveEntry_opensArchived` explicitly proves menu → Archive → Archived. This pure navigation has no transient scripted state needing a new rung-4 fixture. Run one existing scripted scenario and the affected SidebarToolbarCaptureTest class for real pixels; its device-only reason is screenshot capture. Compile androidTest, lint, assembleDebug, spotlessApply and forced spotlessCheck. Do not run the live suite here; list `all` in PR Live tests because a shared helper changes. The dispatcher must supply a fresh full live result with executed/failed/skipped counts and named archive method passing.

## Open Questions

None.

## Documentation handoff

Pending for the documentation stage:

- `app/src/androidTest/assets/design-1220/README.md`, “Channel list (sidebar)”: record the closed-header node and #1665's no-separate-frame decision, citing reused Options overlay `533:1958`.
- `docs/knowledge/features/channel-list-screen.md` and `channel-list-screen-how-it-works.md`, toolbar descriptions: use menu → Settings/Archive and the new glyph.
- `docs/e2e-interactive-stream.md`, list-archive-entry scenario description: use menu → Archive.
- `docs/knowledge/features/options-overlay.md`, Shape and Placement: document the optional below placement and unchanged default above behavior.
- Record dispatcher-produced full live evidence, including executed/failed/skipped counts and `InteractiveStreamE2ETest.interactiveTurn_listArchiveEntry_opensArchived` passing. Documentation does not run the live gate.

## Revisions

- 2026-10-04: resource inspection found the thread description is “More actions”, rather than the ticket's required “Open menu”. Add client-owned `cd_open_menu` while preserving the thread label. The existing overflow drawable remains an exact match.
