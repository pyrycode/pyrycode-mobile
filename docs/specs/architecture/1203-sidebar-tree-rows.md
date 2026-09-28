# Sidebar tree row geometry and icons (#1203)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeHostRow`, `TreeHostSectionRow`, `TreeConversationRow`, `FoldableTreeRow`, `TreeRowControl` — current geometry, state and action boundaries.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ConversationTree`, `treeHost` — host-first order, list gutter and inter-host spacing.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRowsTest.kt` → row bounds, truncation and action tests to extend.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/SidebarToolbarCaptureTest.kt` → managed-device capture and viewport precedent.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` → existing M3 titleSmall and bodySmall exactly match the Figma text roles.
- `docs/knowledge/features/channel-list-screen-how-it-works.md` § Tree rows — presentational and routing context; documentation handoff target.
- `docs/knowledge/features/development-verification.md` § Where a screen test goes — shared screen test and device capture placement.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8 ; visible instance `133:259`, component `132:3902`, idle `103:2968`, active `403:7410`, hover `398:7258`. Inspected 2026-09-28.

The sidebar has 28 dp host and section bands, 24 dp conversation bands separated by 4 dp, and 16 dp between host groups. The host and section labels use M3 titleSmall, conversation labels bodySmall; 6 dp row radii, a primary-container interactive fill and darker selected fill frame the small status dots. The server, folder, chevron, pen and plus are supplied vector shapes, with 12–16 dp visible sizes. The reference still shows retired Apps/workspace tiers, and does not show the mobile reconnect, update, connection-leg or always-visible edit controls.

## Context

Current 48 dp rows and stock glyphs change the shape of the sidebar. This ticket restores the visible tree rhythm while retaining the current mobile host-first Channels/Chats product structure, action set and existing toolbar/panel from #1202. The design's pointer-size controls create an accessibility trade-off: each action needs its own named, non-overlapping hit region inside the compact band. Document that trade-off, and measure compact and enlarged-text behavior.

## Design

- Change only tree row components and the list's conversation spacing. Host and section bands measure 28 dp; conversation bands measure 24 dp with 4 dp between siblings. Keep the list's 20 dp horizontal gutter and 16 dp inter-host gap.
- Preserve the existing `TreeHostRow`, `TreeHostSectionRow`, `TreeConversationRow` and event contracts. Use bundled vector drawables from the inspected Figma SVG paths for server, open/closed folder, chevron, pen and add. Existing reconnect/update glyphs remain distinct, with compact drawn sizes.
- Layout each fold/open region and trailing action as separate, named click targets. Bound long names and give the name the remaining width. Retain attention/connection semantics and state colors; selected fill follows the current dark token mapping, while interactive rows use the primary-container role. No row divider appears in the inspected tree; the toolbar rule belongs to #1202.
- Keep phone actions visible without hover. Where additional actions use more width than the Figma pointer variant, reserve trailing slots and ellipsize text. Never let a trailing action consume the fold/open region.

## State and concurrency model

Rows remain stateless composables. `ChannelListScreen` passes existing fold, selected, attention and connection state. No new jobs, flow or lifecycle behavior.

## Error handling

No new I/O or parse path. Disconnected and update-required controls retain their existing callbacks and descriptions. Daemon-authored names continue through `boundedRowText` before text layout or semantic labels.

## Testing strategy

- Extend `ConversationTreeRowsTest` with exact row geometry, sibling gap, control bounds, compact-width/large-text truncation and separated tap dispatch; retain existing fold, reconnect, update and edit cases.
- Add a focused device capture test under `app/src/androidTest` for 412 × 892 and compact/large-text states. Run selected managed-device methods, inspect executed XML and captures, compare to Figma with labelled overlays/differences, and attach evidence to the PR.
- No rung-3 scenario: this ticket changes existing row presentation and leaves live routing/actions unchanged; shared tests and a focused emulator run exercise the UI path.

## Documentation handoff

Pending for the documentation stage: update “Tree rows” in `docs/knowledge/features/channel-list-screen-how-it-works.md` with the shipped geometry, icons and accessibility hit-region trade-off.

## Open questions

- Whether the supplied plus asset is identical to #1202's toolbar plus at the smaller section slot; compare vector paths before adding a duplicate.
- Whether Figma provides a collapsed section component beyond the visible Chats example; if absent, rotate/use the supplied chevron consistently and record the absent reference state in the PR.

## Revisions

- 2026-09-28: The section plus has the same shape as the toolbar plus scaled to 16 dp; retain a separate vector resource at the Figma slot's native viewport. The visible Chats row is collapsed and supplies the right chevron; the expanded Pyry host also shows a right chevron despite visible children. Use down for expanded and right for collapsed, matching the section's state rule, and record the conflicting host instance in visual evidence.
- 2026-09-28: Device pixels exposed a 12 dp inset on both sides of conversation fills and a 10 dp trailing section-add inset. The row component keeps its 24 dp visual band; list-level padding supplies the horizontal inset and 4 dp sibling gap. Existing Compose semantics tests observe expanded hit bounds around compact rows, so exact pixel geometry is proven by the device capture and component test while list tests assert the gap between semantic targets.
- 2026-09-28 rework: Verifier found that semantic `performClick` does not prove physical hit-region separation. `SidebarTreeCaptureTest` now sends root-coordinate taps at control edges and centers on all three viewports, checks one exact event per tap, uses a long conversation name on compact and enlarged-text captures, and opens the host editor through a coordinate tap. The row implementation and geometry remain unchanged.
