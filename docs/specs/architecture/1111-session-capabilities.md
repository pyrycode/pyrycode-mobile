# #1111 — Follow the session's capability list for effort, permission and slash-command actions

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/SessionSettingsPayloads.kt` → `SessionSettingsPayloadDto`, `toSessionSettings` — the decode boundary the nested DTO joins.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `SessionSettings` — the domain reading that gains a nullable field.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig.effortChoices` — the single effort lookup read by the footer, the Status sheet (via `ThreadScreen`) and `EffortRecall`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `footerMenu` (`FooterControl.Permission`), `PermissionModeOption`, `ComposerAction`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `runConfig` (the settings → `ThreadRunConfig` fold), `absentComposerActions`, the `state` combine that feeds `absentActions`, `onComposerCommand` (send guard reads `absentActions`), `onPermissionModeSelected` (the Auto guard the menu mirrors).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/EffortRecall.kt` → reads `config.effortChoices`; covered by the intersection with no edit.
- `../pyrycode/docs/protocol-mobile.md` § `capabilities` (multi_agent, #2646) — wire SSOT; not restated here.
- Tests mirrored: `SessionSettingsPayloadsTest`, `FooterMenuTest`, `ComposerActionAvailabilityTest`, `ThreadViewModelComposerActionsTest`, `ThreadViewModelPermissionTest`.

In-flight overlaps (#1112, #1115 on `ThreadUiState.kt` / `ThreadComposerFooter.kt`, #1116 on `ThreadViewModel.kt`) touch other blocks; build through, edits additive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=110-3494 (permission button 115-3678, `Options overlay` 533-1958)

The `Input footer` row — Actions, permission, model, effort and context labels with up-chevrons in body-small, paperclip at the end. No new visuals: the effort and permission overlays list fewer rows, and a refused action reuses the existing disabled (greyed) `OptionsOverlayOption` row.

## Context

A `multi_agent` daemon's `session_settings` reply carries a `capabilities` object built from the same checks that refuse a write. Today the phone ignores it, so a Codex channel would offer Claude's effort levels and permission modes, and send `/compact` / `/knowledge-capture` as literal text. The phone does not advertise `multi_agent` yet (#1119, blocked by this ticket), so nothing changes at runtime until then; every behaviour here is proven against decoded JSON and the fake repository.

## Design

### Data

- New domain type in `ConversationRepository.kt`, next to `SessionSettings`:
  `data class SessionCapabilities(val effortLevels: List<String>, val permissionModes: List<String>, val slashCommands: Boolean = true)`.
  Strings are daemon-authored; compared only, never rendered or logged.
- `SessionSettings` gains `val capabilities: SessionCapabilities? = null` as the last parameter, so existing constructions compile unchanged. `null` = no list = today's behaviour.
- `SessionSettingsPayloads.kt`: a `@Serializable SessionCapabilitiesDto` with `@SerialName("effort_levels") effortLevels: List<String>` and `@SerialName("permission_modes") permissionModes: List<String>` (both required) and `@SerialName("slash_commands") slashCommands: Boolean = true`. `SessionSettingsPayloadDto` gains `val capabilities: SessionCapabilitiesDto? = null` — the one optional key, because the wire omits it for a non-`multi_agent` conn or an unresolved session. `toSessionSettings` maps it through. A present object missing either array fails decoding (`SerializationException`) like any malformed reply; undeclared keys are ignored by `MobileJson`.

### UI state

- `ThreadRunConfig` gains `val capabilities: SessionCapabilities? = null`, filled by `runConfig(...)` from `settings?.capabilities`. `forLiveSession` leaves it alone: a conversation's agent does not change with its session, and the next reading replaces it.
- `ThreadRunConfig.effortChoices`: the selected row's levels, filtered to `capabilities.effortLevels` when a list is present. Footer, Status sheet and `EffortRecall` follow at once.
- `ThreadComposerFooter.kt`: `internal fun ThreadRunConfig.offersPermission(mode: PermissionModeOption): Boolean` — Auto needs the selected row's `supportsAutoMode`; with a list, a mode other than `Bypass` must have its `wire` in `permissionModes`; `Bypass` is exempt (sent as `yolo`). `footerMenu(FooterControl.Permission)` filters `PermissionModeOption.entries` by it, and `onPermissionModeSelected` replaces its inline Auto check with it, so the menu and the VM guard cannot disagree.
- Actions: `absentComposerActions(menu, slashCommands: Boolean = true)`. When `slashCommands` is false, every action with a `command` (Compact session, Knowledge capture) is absent regardless of the menu; Reset session and Background tasks carry no command and are unaffected. The `state` combine passes `uiState.runConfig.capabilities?.slashCommands ?: true`. `onComposerCommand` already refuses anything in `absentActions`, so tapping a disabled row sends nothing. The rule reads the flag, never the agent.

## State + concurrency model

No new flows or jobs. The capability list rides the existing `SessionSettings` reading into `runConfigFlow`; `absentActions` is derived in the existing `slashCommandMenu` combine arm, which already sees `uiState.runConfig`.

## Error handling

A malformed `capabilities` object fails the whole `session_settings` frame at the existing decode boundary, the same path as any other malformed field. No new logging; existing composer-action log lines carry static codes only.

## Testing strategy

Unit tests only (pure functions and the fake repository):

- `SessionSettingsPayloadsTest`: reply without `capabilities` → `capabilities == null`; with the object → lists decoded, extra keys ignored; without `slash_commands` → `true`; `slash_commands: false` → `false`; object missing `effort_levels` or `permission_modes` → throws.
- `FooterMenuTest`: effort menu with a list offers the intersection only; permission menu with a list offers listed modes + Bypass, and Auto only when both listed and the row supports it; no list → unchanged six-mode behaviour (existing tests).
- `ComposerActionAvailabilityTest`: `absentComposerActions(menu, slashCommands = false)` → Compact + Knowledge capture, even with a menu naming them or no menu.
- `ThreadViewModelComposerActionsTest`: settings reading with `slash_commands` false → `absentActions` holds both commands and `onComposerCommand(CompactSession)` sends nothing; true and absent-list → nothing absent from the flag.
- `ThreadViewModelPermissionTest`: a mode the list omits is not sent.

No Compose screen test: the overlay already renders a disabled option (#884), and no operator-facing flow is new, so no rung-3 scenario.

## Open questions

- None blocking. `forLiveSession` keeping capabilities across a session replacement is a deliberate choice (see UI state).

## Documentation handoff

Pending for the documentation stage: the ticket names no documentation requirement. The thread-screen / run-config overview may want a line that the effort and permission menus and the Actions slash-command rows follow `SessionSettings.capabilities` when present.
