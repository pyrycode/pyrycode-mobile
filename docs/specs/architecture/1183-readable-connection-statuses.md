# Readable Settings connection statuses

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLine.kt` — `ConnectionStatusLine`, `StatusLeg`, and `toLegVisual` own layout and accessible status presentation.
- `app/src/main/java/de/pyryco/mobile/ui/settings/HostIdentityRow.kt` — `HostIdentityRow` supplies the real Settings width and start inset.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/SettingsRowLayoutTest.kt` — native text measurement and forced 412dp test fixture.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLineTest.kt` — existing status mapping assertions.
- `docs/knowledge/features/connection-status-line.md` — retain independent leg categories, labels, dots and spoken descriptions.
- `docs/knowledge/features/settings-screen-previews-and-edge-cases.md` — status children clear semantics; address each leg by content description.
- `docs/knowledge/features/development-verification.md` — shared tests need native graphics and explicit width for text geometry.
- `gradle/libs.versions.toml` — Compose layout and test dependencies already exist.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The inspected context and screenshot show two horizontal dot/name groups beneath the server identity, inset from the Settings edge, with small text on the M3 surface. The existing component additionally includes textual states and uses label typography; this ticket preserves those labels, semantic colours, 8dp dots, 6dp internal spacing and 24dp group spacing as required, adapting whole groups to multiple lines only when needed.

## Change

Replace only the outer `Row` in `ConnectionStatusLine` with `FlowRow`, retaining centred items and the horizontal gap and using 6dp between wrapped lines. Each unchanged `StatusLeg` is measured as one group against the available line width, so the second group wraps instead of being squeezed into the first group's remaining space. Add 200% light/dark variants to the existing previews. No new state, jobs, errors, logging events, public contracts or dependencies are introduced.

Size: one deliverable and one acceptance criterion; one production file, under 30 production lines, approximately 180 total added lines including tests and this plan; no new exported production types, changed consumer signatures or reject branches. The required remote feature-branch check found no overlapping files. Codegraph context/callees did not locate this component; direct source reads supplied the design.

## Testing strategy

Add `ConnectionStatusLineLayoutTest` under `app/src/sharedTest`, using the real `HostIdentityRow` at 412dp, font scales 1 and 2, native graphics and static light/dark themes. First demonstrate the enlarged connected case fails. Compare each semantic group's bounds with its unwrapped text measurements to reject fragmentation and clipping; assert ordinary 24dp spacing and enlarged stacking. Exercise the remaining status mappings at both sizes and preserve spoken descriptions. Existing `ConnectionStatusLineTest` proves categories and labels. This presentation-only repair adds no daemon flow and needs no new real-Claude scenario.

Run the two focused test classes, Spotless, lint, debug assembly and androidTest compilation. Inspect rendered component pixels against the Figma reference and the enlarged-text adaptation.

## Documentation handoff

No documentation requirement was specified by the ticket. Pending documentation stage: update `docs/knowledge/features/connection-status-line.md`, section “The component”, to describe whole-group wrapping, and section “Testing” for the new geometry regression proof.
