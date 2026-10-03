# #1512 — Session delimiter inset

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt` — `SessionBoundaryDelimiterContent` (the column padding to change), `CompactionBoundaryDivider` and `RuleLabelRow` (left unchanged).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` — `MessageContentGutter` (20 dp), `MessageAreaRowSpacing`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiterScreenTest.kt` — `dark_reset_rule_uses_the_reference_inverse_primary_at_sixty_percent` samples the rule at x 24, which the inset moves off the rule.
- `app/src/androidTest/assets/design-1220/thread/index.md` on `feature/1432`, section "Session delimiter — `675:3682`" — the audit finding.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=675-3682

The `Session boundary` frame (`675:3797`) is a centred column with its own `px-[20px]` inside the 20 px message gutter, holding the rule row (`675:3798`: two 1 px `Schemes/inverse-primary` 60 % rules around a `Schemes/primary` body-small label, 12 px gap) and, 8 px below, the `Schemes/on-surface-variant` body-small explanation. At 412 px both rows span x 40–372. Label, copy, colours and vertical spacing already match.

## Change

`SessionBoundaryDelimiterContent` pads its column horizontally by `MessageContentGutter` only. It now pads by `MessageContentGutter + SessionBoundaryInset`, a new private 20 dp constant naming the frame's own padding, so the rule row and the explanation line both span x 40–372 at 412 px. `CompactionBoundaryDivider` pads `RuleLabelRow` itself and is untouched, so it keeps the gutter-to-gutter width. Bottom spacing is unchanged.

## Testing strategy

In `SessionBoundaryDelimiterScreenTest`, move the colour sample in `dark_reset_rule_uses_the_reference_inverse_primary_at_sixty_percent` from x 24 to x 44 (inside the new rule). Add a 412 dp (`w412dp-h892dp`) geometry test that reads pixels on the label's centre line: surface at x 36 and x 376, rule colour at x 44 and x 368. Run the delimiter unit and screen tests and `ScriptedSessionBoundaryTest`, then capture against `675:3682` and compare with `scripts/design-compare.py`.
