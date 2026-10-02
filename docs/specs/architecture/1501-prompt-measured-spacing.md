# #1501 — Prompt spacing and button heights against the measured frames

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt` — `PermissionRequestCard`
  (choices column), `PermissionContext`, `ModalContextRow`, `ModalOptionButton`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/QuestionBatchModal.kt` — `QuestionBlock` (the Other
  label and the Other field's `decorationBox`), `ChoiceRow` (`other` top padding).
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` — `ModalCancelButton`; read only, not changed.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` — `AppTypography` declares no `lineHeightStyle`.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairingHeader.kt` — `TitleLineBox`, the local `Trim.None` precedent.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt` — precedent for dropping
  Material's layout touch floor (`LocalMinimumInteractiveComponentSize provides Dp.Unspecified`) while the pointer
  touch target comes from `ViewConfiguration.minimumTouchTargetSize`.
- `app/src/androidTest/java/de/pyryco/mobile/design/PromptsDesignCaptureTest.kt`, `scripts/design-compare.py`,
  `app/src/androidTest/assets/design-1220/prompts/index.md` — the audit this ticket answers.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/QuestionBatchModalTest.kt` —
  `question_components_use_figma_control_geometry_and_keep_other_editable` asserts the Other field's 48 dp layout
  height, which this ticket removes on purpose.

No other in-flight `feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=635-2036

Measured with `get_metadata`. `639:2242`: the card is one 16 dp column (title, prompt, Reason, Folder, choices);
each context group is a 20 dp label box over a 20 dp value box 8 dp apart (value top 28 below label top); the
choices are 40 dp surfaces 8 dp apart. `636:3279`: the Other row is the 20 dp control at y 0 beside a 20 dp
"Other" label, with the 32 dp "Input small" well at y 28; question cards are 242 dp. `639:3308` and `636:4066`
draw every line box at linear 150 % (title 42, labels 30, buttons 8 + 36 + 8 = 52).

## Context

The #1433 audit measured these gaps against the board. Two causes:

1. `AppTypography`'s styles carry no `lineHeightStyle`, so Compose's default trims a single line's leading: a
   14/20 label renders about 16 dp tall, not the 20 dp box Figma draws. Context label→value reads 24, not 28.
2. Material's layout touch floor (`minimumInteractiveComponentSize`, also applied inside a clickable `Surface`)
   pads each 40 dp choice to 48, and the Other field wraps its 32 dp well in a 48 dp box. Both move layout.

The compact frames are a third, separate case: the platform's non-linear font scaling (Android 14+) scales 16 sp
text and its line height by less than 1.5, while the board scales linearly.

## Change

**Permission (`ThreadPermissionModal.kt`).**
- `PermissionContext` spaces its groups 16 dp (the card's own column gap), not 12.
- `ModalContextRow` renders label and value with a local `LineHeightStyle(Center, Trim.None)` so each keeps its
  20 dp line box; with the existing 8 dp gap the value's top sits 28 dp below the label's top.
- The choices column provides `LocalMinimumInteractiveComponentSize provides Dp.Unspecified`, and
  `ModalOptionButton` drops `minimumInteractiveComponentSize()`. Each surface lays out at 40 dp, 8 dp apart, and
  the armed hint keeps its 8 dp gaps. The 48 dp touch target comes from Compose's pointer hit-test expansion to
  `ViewConfiguration.minimumTouchTargetSize`, which does not take layout space; two 40 dp surfaces 8 dp apart
  expand to meet, not overlap.

**Question (`QuestionBatchModal.kt`).**
- The "Other" label uses the same `Trim.None` line box (20 dp).
- `ChoiceRow` loses its `other` parameter and the 8 dp control offset: the control is top-aligned with the label.
- The Other field drops both 48 dp `heightIn` wrappers; the 32 dp well is the field's whole layout, 28 dp below
  the label's top. The field keeps a 48 dp touch target through the same pointer expansion (8 dp each side).
- The card `Surface` gains `testTag("question_card_$index")` so the 242 dp height is assertable.

**Compact frames: platform scaling kept.** The app does not force linear scaling. Android deliberately scales
large text non-linearly from API 34; overriding `LocalDensity` to linear 1.5 would override the user's
accessibility setting and render text the platform itself never draws at that setting. The capture's `.txt`
gains the measured sp→px conversions at the test's font scale so the cause is recorded with numbers, the two
compact items in `index.md` state that cause, and a follow-up issue asks for `639:3308` and `636:4066` to be
redrawn with Android's scaling. `ModalCancelButton`, `QuestionBatchActions` and `ThreadTopAppBar.kt` stay
unchanged, so the 412-wide frames are untouched by this branch of the criterion.

Nothing else moves: the card's 16 dp column, the 20 dp gutters, Cancel's 12 dp offset below the card, and every
other style are as before.

## Testing strategy

Robolectric screen tests in `app/src/sharedTest` at `@Config(qualifiers = "w412dp-h892dp")` (not `ForcedSize`,
which rescales density and skews measured dp), with `@GraphicsMode(NATIVE)` where text heights matter:

- `ThreadScreenModalTest#permission_context_spacing_matches_the_frame` — label→value 28, group gap 16, last value
  to first choice 16, with the frame's own Reason and Folder fixture.
- `ThreadScreenModalTest#permission_choices_are_40dp_surfaces_8dp_apart_with_48dp_touch_targets` — 40 dp surfaces,
  8 dp gap, `touchBoundsInRoot` ≥ 48 dp, and real pointer taps 3 dp outside each surface in the gap reach the
  nearer choice.
- `ThreadScreenModalTest#armed_hint_sits_in_the_8dp_choice_gaps` — `639:2882`.
- `ThreadInlineQuestionTest#other_choice_matches_the_frame_and_keeps_a_48dp_field_target` — control top equals the
  label top, field 28 below the label top and 32 tall, cards within 2 dp of 242, field `touchBoundsInRoot` ≥ 48 dp
  and a real tap 6 dp below the well focuses it.

Device: `QuestionBatchModalTest#question_components_use_figma_control_geometry_and_keep_other_editable` asserted the
48 dp layout height that `636:3279` removes; it now asserts the 48 dp touch bounds and taps outside the well. Run
focused on the managed device. `PromptsDesignCaptureTest` runs as one whole class on `pixel8Api35`
(`questionFrames`, `permissionFrames`, `questionCompactFrame`, `permissionCompactFrame` re-captured) and the
captures are compared with `scripts/design-compare.py`; device-only because it saves real pixels.

## Open Questions

- Whether the 242 dp card needs the question line on `Trim.None` too: settle by measurement in the shared test.

## Revisions

- **2026-10-02, open question resolved.** With only the Other row fixed, the shared test measured the card at
  236 dp: the question line was trimmed to about 16 dp, and Figma's 1 dp border sits inside its 16 dp padding
  (content at y 17) while the app's border draws over its padding. The question text now uses the same
  `Trim.None` line box (`FrameLineBox`, shared with the Other label), giving 240 dp, within the criterion's 2 dp.
  The 1 dp border inset stays as it is in every other card.
